package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.transfer.Report;
import dev.hoori.db.core.transfer.Transfer.Existing;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copy tables: a tab that copies tables' rows between two databases, on sessions of its own. */
class TransferTabTest {

  static final String HOST = "copy.example.com";
  static final String OUTLINE = """
      shop
        items
          *id: int(11)
          name: varchar(200)
        stock
          *item_id: int(11)
          quantity: int(11)
      archive
        items
          *id: int(11)
          name: varchar(200)
          note: text
      ~mysql
      """;
  static final ConnectionProfile MARIA =
      ConnectionProfile.of(Provider.MARIADB).withName("maria").withHost(HOST).withUser("app");
  static final String TAB = "maria: Copy tables";
  static final String ITEMS = HOST + "/archive.items";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.serve(HOST, Provider.MARIADB, "11.4.2-MariaDB", OUTLINE);
    c.servers.rows(HOST + "/shop.items", 5, OptionalLong.of(5));
    c.servers.values(ITEMS, List.of());
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with maria connected, and Copy tables opened from its database shop. */
  App<State> opened(ConnectionProfile profile) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    c.run(app, START + open("maria") + SETTLE + menu("shop", "Copy tables…") + SETTLE);
    return app;
  }

  /** Chooses the option of the combo box. */
  static String choose(String box, String option) {
    return "press role=combobox label=\"" + box + "\"\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"" + option + "\"\nframe\nadvance 1\nframe\n";
  }

  static final String COPY = "press role=button label=\"Copy\"\nframe\n";

  @Test
  void itOpensOnTheDatabaseWithTheTargetToChoose() throws Exception {
    var app = opened(MARIA);
    c.run(app, "snap opened\n");
    var tree = c.tree("opened");
    assertTrue(tree.contains(TAB), tree);
    assertTrue(tree.contains("label=\"items\""), tree);
    assertTrue(tree.contains("label=\"stock\""), tree);
    // The same database on both ends reads what it writes.
    assertTrue(tree.contains("The source and the target are the same"), tree);

    c.run(app, choose("Target database", "archive") + SETTLE + "snap chosen\n");
    var chosen = c.tree("chosen");
    assertFalse(chosen.contains("The source and the target are the same"), chosen);
    assertTrue(chosen.contains("In the target"), chosen);
    assertTrue(chosen.contains("Not in the target"), chosen);
    // Each end reads on a session of its own, never the tree's.
    assertTrue(app.state().transfers(null).getFirst().source.lane != null);
  }

  @Test
  void aCopySkipsATableTheTargetLacksAndSaysWhy() throws Exception {
    var app = opened(MARIA);
    c.run(
        app,
        choose("Target database", "archive")
            + SETTLE
            + "press role=radio label=\"Skip the table\"\nframe\n"
            + COPY
            + SETTLE
            + "snap copied\n");
    var tree = c.tree("copied");
    assertTrue(tree.contains("Copied 1 of 2 tables"), tree);
    assertTrue(tree.contains("stock: The target has no table stock"), tree);
    assertTrue(tree.contains("5 rows copied"), tree);
    var rows = c.servers.rowsOf(ITEMS);
    assertEquals(5, rows.size());
    // By name: the target's column the source lacks gets nothing.
    assertEquals(3, rows.getFirst().size());
    assertEquals(null, rows.getFirst().get(2));
    assertTrue(
        c.servers.calls.contains("copy read " + HOST + "/shop.items"), c.servers.calls.toString());
    assertTrue(c.servers.calls.contains("copy commit " + HOST));
  }

  @Test
  void aTargetWithRowsStopsTheCopyBeforeAnythingIsWritten() throws Exception {
    c.servers.values(ITEMS, List.of(List.of(7, "old", "x"), List.of(8, "old", "y")));
    var app = opened(MARIA);
    c.run(
        app,
        choose("Target database", "archive")
            + SETTLE
            + "press role=checkbox label=\"stock\"\nframe\n"
            + COPY
            + SETTLE
            + "snap stopped\n");
    var tree = c.tree("stopped");
    assertTrue(tree.contains("The copy stopped at a failure"), tree);
    assertTrue(tree.contains("has rows"), tree);
    assertEquals(2, c.servers.rowsOf(ITEMS).size());
    assertTrue(c.servers.calls.stream().noneMatch(k -> k.startsWith("copy write")));

    c.run(app, choose("Where items has rows", "Empty it first") + COPY + SETTLE + "snap emptied\n");
    assertTrue(c.tree("emptied").contains("Copied"), c.tree("emptied"));
    assertEquals(5, c.servers.rowsOf(ITEMS).size());
  }

  @Test
  void aCancelRollsBackWhatWasNotCommitted() throws Exception {
    var app = opened(MARIA);
    var gate = running(app);
    c.run(app, "frame\nsnap running\n");
    assertTrue(c.tree("running").contains("label=\"Cancel the copy\""), c.tree("running"));
    c.run(
        app, "press role=button label=\"Cancel the copy\"\nframe\n" + SETTLE + "snap cancelled\n");
    gate.countDown();
    var tree = c.tree("cancelled");
    assertTrue(tree.contains("Cancelled"), tree);
    assertTrue(
        c.servers.interrupted.contains("copy write " + ITEMS), c.servers.interrupted.toString());
    assertTrue(c.servers.calls.contains("copy rollback " + HOST), c.servers.calls.toString());
    assertEquals(0, c.servers.rowsOf(ITEMS).size());
  }

  @Test
  void aReadOnlyTargetIsRefusedBeforeTheCopy() throws Exception {
    var app = opened(MARIA.withReadOnly(true));
    c.run(app, choose("Target database", "archive") + SETTLE + "snap read-only\n");
    var tree = c.tree("read-only");
    assertTrue(tree.contains("The target connection is read-only"), tree);
    assertTrue(
        tree.lines().anyMatch(l -> l.contains("label=\"Copy\"") && l.contains("disabled")), tree);
  }

  @Test
  void theStatementLogShowsWhatTheCopySent() throws Exception {
    var app = opened(MARIA);
    c.run(
        app,
        choose("Target database", "archive")
            + SETTLE
            + "press role=checkbox label=\"stock\"\nframe\n"
            + COPY
            + SETTLE);
    var logged = app.state().statements.entries().stream()
        .filter(e -> e.sent().purpose() == Purpose.TRANSFER)
        .map(e -> e.sent().text())
        .toList();
    assertTrue(logged.contains("copy write " + ITEMS), logged.toString());
    assertEquals("Copy", LogPanel.part(Purpose.TRANSFER));
  }

  @Test
  void theFileMenuOpensItForTheConnectionSelected() throws Exception {
    var app = c.app(MARIA);
    c.ready(app, MARIA);
    c.run(
        app,
        START
            + open("maria")
            + SETTLE
            + "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Copy tables…\"\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap file\n");
    assertEquals(1, app.state().transfers(null).size());
    assertTrue(c.tree("file").contains(TAB), c.tree("file"));
    assertEquals(
        List.of(),
        app.state().transfers(null).getFirst().checked().stream()
            .filter(t -> !t.container().name().equals("shop"))
            .toList());
  }

  /**
   * Starts the copy of items into archive, which waits at its write, once it got there: the gate it
   * waits at.
   */
  CountDownLatch running(App<State> app) throws Exception {
    var gate = c.servers.hold("copy write " + ITEMS);
    c.run(
        app,
        choose("Target database", "archive")
            + SETTLE
            + "press role=checkbox label=\"stock\"\nframe\n"
            + COPY
            + "frame\nadvance 1\nframe\n");
    assertTrue(app.state().transfers(null).getFirst().running != null);
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("copy write " + ITEMS));
    return gate;
  }

  @Test
  void closingTheTabWhileACopyRunsAsksFirstAndStopsTheCopy() throws Exception {
    var app = opened(MARIA);
    var held = running(app);
    c.run(app, "key W control\nframe\nadvance 1\nframe\nsnap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("Stop the copy?"), asked);
    assertEquals(1, app.state().transfers(null).size());

    c.run(app, "press role=button label=\"Cancel\"\nframe\nadvance 1\nframe\n");
    assertEquals(1, app.state().transfers(null).size());
    assertTrue(app.state().transfers(null).getFirst().running != null);

    c.run(
        app,
        "key W control\nframe\nadvance 1\nframe\n"
            + "press role=button label=\"Stop the copy\"\nframe\n"
            + SETTLE
            + "snap closed\n");
    held.countDown();
    assertEquals(List.of(), app.state().transfers(null));
    assertFalse(c.tree("closed").contains("Stop the copy?"), c.tree("closed"));
    assertTrue(
        c.servers.interrupted.contains("copy write " + ITEMS), c.servers.interrupted.toString());
    assertEquals(0, c.servers.rowsOf(ITEMS).size());
  }

  @Test
  void disconnectingWhileACopyRunsAsksFirst() throws Exception {
    var app = opened(MARIA);
    var held = running(app);
    c.run(app, menu("maria", "Disconnect") + "snap asked\n");
    assertTrue(c.tree("asked").contains("Stop the copy?"), c.tree("asked"));
    assertTrue(app.state().catalog.connected(MARIA.id()));

    c.run(app, "press role=button label=\"Stop the copy\"\nframe\n" + SETTLE + "snap ended\n");
    held.countDown();
    assertFalse(app.state().catalog.connected(MARIA.id()));
    var tab = app.state().transfers(null).getFirst();
    assertEquals(null, tab.running);
    assertEquals(Report.Stage.CANCELLED, tab.report.stage());
  }

  @Test
  void anotherConnectionToTheSameDatabaseIsTheSameTarget() throws Exception {
    var other = ConnectionProfile.of(Provider.MARIADB)
        .withName("maria 2")
        .withHost(HOST)
        .withUser("other");
    var app = c.app(MARIA, other);
    c.ready(app, MARIA, other);
    c.run(
        app,
        START
            + open("maria")
            + SETTLE
            + menu("shop", "Copy tables…")
            + SETTLE
            + open("maria 2")
            + SETTLE
            + choose("Target connection", "maria 2")
            + SETTLE
            + choose("Target database", "shop")
            + SETTLE
            + "snap same\n");
    var tree = c.tree("same");
    assertTrue(tree.contains("The source and the target are the same"), tree);

    c.run(app, choose("Target database", "archive") + SETTLE + "snap other\n");
    assertFalse(c.tree("other").contains("The source and the target are the same"));
  }

  @Test
  void whatIsDoneWhereATableHasRowsIsChosenAgainForAnotherSource() throws Exception {
    var app = opened(MARIA);
    c.run(app, choose("Where items has rows", "Empty it first"));
    var tab = app.state().transfers(null).getFirst();
    assertEquals(Existing.EMPTY, tab.existing("items"));

    c.run(app, choose("Source database", "archive") + SETTLE);
    assertEquals(Existing.STOP, tab.existing("items"));
  }

  @Test
  void aTargetDatabaseWithoutSchemaToWriteIntoBlocksTheCopy() throws Exception {
    var host = "pg.copy.example.com";
    c.servers.serve(host, Provider.POSTGRESQL, "16.4", """
        shop
          public
            items
              *id: integer
        bare
          ~pg_catalog
            pg_class
              oid: oid
        """);
    var pg = ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("pg")
        .withHost(host)
        .withUser("app")
        .withDatabase("shop");
    var app = c.app(pg);
    c.ready(app, pg);
    c.run(
        app,
        START
            + open("pg")
            + SETTLE
            + menu("shop", "Copy tables…")
            + SETTLE
            + choose("Target database", "bare")
            + SETTLE
            + "snap bare\n");
    var tree = c.tree("bare");
    assertTrue(tree.contains("Target: choose its schema."), tree);
    assertTrue(
        tree.lines().anyMatch(l -> l.contains("label=\"Copy\"") && l.contains("disabled")), tree);
  }

  @Test
  void aSchemaReadAfterOneThatFailedClearsItsProblem() throws Exception {
    var host = "pg.copy.example.com";
    c.servers.serve(host, Provider.POSTGRESQL, "16.4", """
        shop
          public
            items
              *id: integer
          secret
            items
              *id: integer
        archive
          public
            items
              *id: integer
        """);
    c.servers.fail(
        "tables " + host + "/shop.secret",
        FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    var pg = ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("pg")
        .withHost(host)
        .withUser("app")
        .withDatabase("shop");
    var app = c.app(pg);
    c.ready(app, pg);
    c.run(
        app,
        START
            + open("pg")
            + SETTLE
            + menu("shop", "Copy tables…")
            + SETTLE
            + choose("Target database", "archive")
            + SETTLE
            + choose("Source schema", "secret")
            + SETTLE
            + "snap denied\n");
    var denied = c.tree("denied");
    assertTrue(denied.contains("Source not read"), denied);

    c.run(app, choose("Source schema", "public") + SETTLE + "snap public\n");
    var tree = c.tree("public");
    assertFalse(tree.contains("Source not read"), tree);
    assertTrue(
        tree.lines().anyMatch(l -> l.contains("label=\"Copy\"") && !l.contains("disabled")), tree);
  }

  @Test
  void exitWhileACopyRunsAsksFirst() throws Exception {
    var app = opened(MARIA);
    var held = running(app);
    var exit = """
        press role=menuitem label="File"
        frame
        advance 1
        frame
        press role=menuitem label="Exit"
        frame
        advance 1
        frame
        """;
    c.run(app, exit + "snap asked\n");
    assertTrue(c.tree("asked").contains("Stop the copy?"), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\nadvance 1\nframe\n");
    assertFalse(app.windows().all().isEmpty());

    c.run(app, exit + "press #answer-stop\nframe\n" + SETTLE);
    held.countDown();
    c.run(app, SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(0, c.servers.rowsOf(ITEMS).size());
  }
}

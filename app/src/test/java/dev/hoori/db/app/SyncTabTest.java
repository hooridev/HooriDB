package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.MonitorTabTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compare data: a table's rows compared with another's on sessions of the comparison's own, and
 * the target made equal to the source.
 */
class SyncTabTest {

  static final String HOST = "sync.example.com";
  static final String ORDERS = HOST + "/shop.public.orders";
  static final String COPY = HOST + "/shop.public.orders_copy";

  static final String OUTLINE = """
      shop
        public
          orders
            *id: bigint
            status: text
            total: numeric(12,2)
            note: text
          orders_copy
            *id: bigint
            status: text
            total: numeric(14,3)
            kept: text
          unkeyed
            id: bigint
            status: text
      """;

  static final ConnectionProfile SHOP = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("store")
      .withHost(HOST)
      .withDatabase("shop")
      .withUser("shop_app");

  static final String TAB = "Compare orders";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.serve(HOST, Provider.POSTGRESQL, "16.4", OUTLINE);
    c.servers.values(
        ORDERS,
        List.of(
            row(1L, "new", "10.00", "a"),
            row(2L, "paid", "20.00", "b"),
            row(3L, "paid", "30.00", "c"),
            row(4L, "sent", "40.00", "d")));
    c.servers.values(
        COPY,
        List.of(
            row(2L, "paid", "20.000", "x"),
            row(3L, "new", "30.000", "y"),
            row(4L, "sent", "40", "z"),
            row(5L, "gone", "50.000", "w")));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  private static List<Object> row(long id, String status, String total, String text) {
    return Arrays.asList(id, status, new BigDecimal(total), text);
  }

  /** The app with the store connected, its public schema read, and orders compared with its copy. */
  App<State> compared(ConnectionProfile... profiles) throws Exception {
    var app = c.app(profiles);
    c.ready(app, profiles);
    c.run(app, START + open("store") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, menu("orders", SyncTab.COMPARE_DATA) + SETTLE);
    c.run(app, choose("store · shop.public.orders_copy") + SETTLE);
    return app;
  }

  static String choose(String target) {
    return """
        press role=combobox label="Target table"
        frame
        advance 1
        frame
        press role=option label="%s"
        frame
        advance 1
        frame
        """.formatted(target);
  }

  static final String COMPARE = "press role=button label=\"Compare\"\nframe\n" + SETTLE;

  @Test
  void aTablesMenuComparesItWithAnotherByTheKeyBothShare() throws Exception {
    var app = compared(SHOP);
    c.run(app, "snap paired\n");
    var paired = c.tree("paired");
    assertTrue(paired.contains("label=\"" + TAB + "\""), paired);
    assertTrue(
        paired.contains("Rows are paired by the primary key (id), and 3 columns of the same name"
            + " are compared. Only in the source, left out: note. Only in the target, kept as"
            + " they are: kept."),
        paired);

    c.run(app, COMPARE + "frame\nframe\nsnap compared\n");
    var tree = c.tree("compared");
    assertTrue(tree.contains("1 row only in the source"), tree);
    assertTrue(tree.contains("1 row only in the target"), tree);
    assertTrue(tree.contains("1 row different"), tree);
    // 20.00 and 20.000, 40.00 and 40 are the same number.
    assertTrue(tree.contains("2 rows equal"), tree);
    assertTrue(tree.contains("label=\"Differences\""), tree);
    assertTrue(tree.contains("Only in source"), tree);
    assertTrue(tree.contains("Only in target"), tree);
    assertTrue(tree.contains("4 rows in the source, 4 rows in the target"), tree);
    // Row by row in the key's order: a row that differs as the source's and the target's, with
    // the cell that differs marked.
    assertTrue(line(tree, "#row-0 role=row").contains("value=\"new\""), tree);
    assertTrue(line(tree, "#row-1 role=row").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#cell-1-2 ").contains("value=\"changed\""), tree);
    assertFalse(line(tree, "#cell-1-3 ").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#cell-2-2 ").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#row-3 role=row").contains("value=\"deleted\""), tree);

    // Both read on read-only sessions of the comparison's own, never the tree's first one.
    var scans = c.servers.linked.stream().filter(l -> l.contains(" scan ")).toList();
    assertFalse(scans.isEmpty());
    for (var scan : scans) {
      int link = Integer.parseInt(scan.substring(0, scan.indexOf(' ')));
      assertTrue(link > 1, scans.toString());
      assertTrue(c.servers.readOnly(link), scan);
    }
  }

  @Test
  void thePaletteComparesTheTableSelectedInTheTree() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("store") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, "press role=treeitem label=\"orders\"\nframe\n");
    c.run(
        app,
        PaletteTest.PALETTE + "type \"compare d\"\nframe\nkey Enter\n" + PaletteTest.SHOWN
            + "snap palette\n");
    var tree = c.tree("palette");
    assertTrue(tree.contains("label=\"" + TAB + "\""), tree);
    assertTrue(tree.contains("Choose the table to compare orders with."), tree);
  }

  @Test
  void applyingWritesTheChosenChangesInOneTransactionAndComparesAgain() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    // Without deleting the row only in the target.
    c.run(app, "press role=checkbox label=\"Delete rows only in the target\"\nframe\n");
    c.run(app, "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\nsnap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("2 changes will be written to orders_copy"), preview);
    assertTrue(preview.contains("UPDATE"), preview);
    assertTrue(preview.contains("INSERT INTO"), preview);
    assertFalse(preview.contains("DELETE FROM"), preview);

    c.run(app, "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, "frame\nframe\nsnap applied\n");
    var tree = c.tree("applied");
    assertTrue(tree.contains("Wrote 2 changes to orders_copy."), tree);
    assertTrue(tree.contains("1 row only in the target"), tree);
    assertTrue(tree.contains("4 rows equal"), tree);
    var rows = c.servers.rowsOf(COPY);
    assertEquals(5, rows.size());
    assertTrue(
        rows.contains(Arrays.asList(1L, "new", new BigDecimal("10.00"), null)), rows.toString());
    assertTrue(rows.contains(row(3L, "paid", "30.000", "y")), rows.toString());
    // Written on a session of its own, in one transaction: a batch of each kind, the update
    // before the new row.
    var writes = c.servers.linked.stream()
        .filter(l -> l.contains(" writer ") || l.contains(" batch ") || l.contains(" commit "))
        .toList();
    assertEquals(4, writes.size(), writes.toString());
    var link = writes.getFirst().substring(0, writes.getFirst().indexOf(' '));
    assertTrue(writes.stream().allMatch(w -> w.startsWith(link + " ")), writes.toString());
    assertFalse(c.servers.readOnly(Integer.parseInt(link)));
  }

  @Test
  void aReadOnlyTargetIsComparedButNotChanged() throws Exception {
    var app = compared(SHOP.withReadOnly(true));
    c.run(app, COMPARE + "snap read-only\n");
    var tree = c.tree("read-only");
    assertTrue(tree.contains("1 row different"), tree);
    assertTrue(line(tree, "label=\"Apply…\"").endsWith("disabled"), tree);
    assertTrue(c.servers.linked.stream().noneMatch(l -> l.contains(" writer ")));
  }

  @Test
  void tablesWithoutAKeyBothShareCannotBeCompared() throws Exception {
    var app = compared(SHOP);
    c.run(app, choose("store · shop.public.unkeyed") + SETTLE + "snap unpaired\n");
    var tree = c.tree("unpaired");
    assertTrue(tree.contains("These tables cannot be compared"), tree);
    assertTrue(line(tree, "label=\"Compare\"").endsWith("disabled"), tree);
  }

  @Test
  void theChangesCanBeSavedAsAScriptInstead() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var file = dir.resolve("orders.sql");
    c.run(
        app,
        "dialog \"" + file.toString().replace('\\', '/') + "\"\n"
            + "press role=button label=\"Save as script…\"\nframe\n" + SETTLE + "snap saved\n");
    assertTrue(c.tree("saved").contains("Saved the script to orders.sql."), c.tree("saved"));
    var script = Files.readString(file);
    assertTrue(script.startsWith("-- Makes the rows of \"public\".\"orders_copy\" equal"), script);
    assertTrue(script.contains("START TRANSACTION;\n"), script);
    // In the order that applying writes them: deletions, updates, new rows.
    int delete = script.indexOf("DELETE FROM \"public\".\"orders_copy\"");
    int update = script.indexOf("UPDATE \"public\".\"orders_copy\"");
    int insert = script.indexOf("INSERT INTO \"public\".\"orders_copy\"");
    assertTrue(0 < delete && delete < update && update < insert, script);
    assertTrue(script.endsWith("COMMIT;\n"), script);
    // Nothing was written to the table.
    assertEquals(4, c.servers.rowsOf(COPY).size());
  }

  static final String APPLY = "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\n"
      + "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n";

  // The changes written once the source compared again: orders' rows in orders_copy.
  private void assertApplied() {
    var rows = c.servers.rowsOf(COPY);
    assertEquals(4, rows.size(), rows.toString());
    assertTrue(rows.contains(row(3L, "paid", "30.000", "y")), rows.toString());
    assertFalse(rows.stream().anyMatch(r -> r.getFirst().equals(5L)), rows.toString());
  }

  // A cancel that comes as the commit has begun cannot stop it: what came of it shows.
  @Test
  void aCancelAsTheChangesAreCommittedLeavesTheCommitToSayWhatCameOfIt() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("commit " + COPY);
    c.run(app, APPLY);
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("commit " + COPY));
    // Pressed in a frame built before the commit began, or after, where it is disabled.
    c.run(app, "frame\npress role=button label=\"Cancel\"\nframe\nsnap pressed\n");
    var pressed = c.tree("pressed");
    assertFalse(pressed.contains("was cancelled"), pressed);
    assertTrue(
        pressed.contains("The changes are being committed")
            || line(pressed, "label=\"Cancel\"").endsWith("disabled"),
        pressed);
    gate.countDown();
    c.run(app, SETTLE + "frame\nframe\nsnap committed\n");
    var tree = c.tree("committed");
    assertTrue(tree.contains("Wrote 3 changes to orders_copy."), tree);
    assertTrue(tree.contains("4 rows equal"), tree);
    assertApplied();
  }

  // Disconnecting, as Exit ends the connections too, closes the writer's session behind the
  // commit, which says what came of it; nothing compares again on the connection that ended.
  @Test
  void aCommitThatAConnectionsEndMeetsStillSaysWhatCameOfIt() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("commit " + COPY);
    c.run(app, APPLY);
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("commit " + COPY));
    var scans = c.servers.calls.stream().filter(l -> l.startsWith("scan ")).count();
    c.run(app, "frame\n" + menu("store", "Disconnect"));
    gate.countDown();
    c.run(app, SETTLE + "frame\nframe\nsnap ended\n");
    var tree = c.tree("ended");
    assertTrue(tree.contains("Wrote 3 changes to orders_copy."), tree);
    assertFalse(tree.contains("A connection of the comparison ended."), tree);
    assertEquals(
        scans, c.servers.calls.stream().filter(l -> l.startsWith("scan ")).count());
    assertApplied();
  }

  // A tab closed as its changes are committed leaves the commit to tell the main window.
  @Test
  void aCommitOfATabClosedMeanwhileGoesToTheNotices() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("commit " + COPY);
    c.run(app, APPLY);
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("commit " + COPY));
    c.run(app, "frame\nkey W control\nframe\nadvance 1\nframe\n");
    gate.countDown();
    c.run(app, SETTLE);
    var notices = app.state().notices;
    assertEquals(1, notices.size(), notices.toString());
    assertEquals("store", notices.getFirst().connection());
    assertEquals(
        "Wrote 3 changes to orders_copy after its comparison closed.",
        notices.getFirst().what());
    assertFalse(notices.getFirst().failure());
    assertApplied();
  }

  @Test
  void aRowChangedMeanwhileRollsAllBack() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("scan " + ORDERS);
    c.run(app, APPLY);
    // The writing has begun and the source is read again: a row of the target changes before
    // the target is.
    CatalogTreeTest.eventually(
        () -> c.servers.calls.stream().filter(l -> l.equals("scan " + ORDERS)).count() == 2);
    var changed = List.of(
        row(2L, "paid", "20.000", "x"),
        row(3L, "lost", "30.000", "y"),
        row(4L, "sent", "40", "z"),
        row(5L, "gone", "50.000", "w"));
    c.servers.values(COPY, changed);
    gate.countDown();
    c.run(app, SETTLE + "snap missed\n");
    var tree = c.tree("missed");
    assertTrue(tree.contains("Nothing was written"), tree);
    assertTrue(tree.contains("changed while the changes were written"), tree);
    assertEquals(changed, c.servers.rowsOf(COPY));
    assertTrue(c.servers.calls.stream().noneMatch(l -> l.startsWith("commit ")));
  }

  @Test
  void aTargetThatTakesNoTransactionIsNotChanged() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    c.servers.refuses(COPY);
    c.run(app, APPLY + SETTLE + "snap refused\n");
    var tree = c.tree("refused");
    assertTrue(tree.contains("cannot take the changes all or nothing"), tree);
    assertEquals(4, c.servers.rowsOf(COPY).size());
  }

  @Test
  void aStatementThatFailsInABatchRollsAllBack() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    c.servers.fail("batch " + COPY, FakeServers.queryFailure(QueryException.Failure.OTHER));
    c.run(app, APPLY + SETTLE + "snap failed\n");
    var tree = c.tree("failed");
    assertTrue(tree.contains("It failed"), tree);
    assertTrue(tree.contains("the server's words for OTHER"), tree);
    assertEquals(4, c.servers.rowsOf(COPY).size());
    assertTrue(c.servers.calls.stream().noneMatch(l -> l.startsWith("commit ")));
  }

  // A failure of the source's server is checked against the source's password too, where the
  // target is of another connection with a password of its own.
  @Test
  void aFailureOfEitherSideWithholdsItsPassword() throws Exception {
    var vault = "vault.example.com";
    c.servers.serve(vault, Provider.POSTGRESQL, "16.4", """
        vault
          archive
            orders_copy
              *id: bigint
              status: text
              total: numeric(14,3)
              kept: text
        """);
    var backup = ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("backup")
        .withHost(vault)
        .withDatabase("vault")
        .withUser("backup_app");
    var app = c.app(SHOP, backup);
    c.ready(app, SHOP, backup);
    app.state().rememberPassword(backup.id(), "an0ther-secret".toCharArray());
    c.run(app, START + open("store") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, open("backup") + SETTLE + open("vault") + SETTLE + open("archive") + SETTLE);
    c.run(app, menu("orders", SyncTab.COMPARE_DATA) + SETTLE);
    c.run(app, choose("backup · vault.archive.orders_copy") + SETTLE);
    c.servers.fail(
        "scan " + ORDERS,
        QueryException.of(
            QueryException.Failure.OTHER, "role shop_app with " + ConnectionsTest.PASSWORD));
    c.run(app, COMPARE + "snap withheld\n");
    var tree = c.tree("withheld");
    assertTrue(tree.contains(ConnectionException.WITHHELD), tree);
    assertFalse(tree.contains(ConnectionsTest.PASSWORD), tree);
  }

  // More changes than a batch holds, and more differences than are kept to show.
  @Test
  void manyChangesGoInBatchesAndTheFirstDifferencesShow() throws Exception {
    var many = new ArrayList<List<Object>>();
    for (long id = 1; id <= 1_200; id++) {
      many.add(row(id, "new", "1.00", "n"));
    }
    c.servers.values(ORDERS, many);
    c.servers.values(COPY, List.of());
    var app = compared(SHOP);
    c.run(app, COMPARE + "snap many\n");
    var tree = c.tree("many");
    assertTrue(tree.contains("1,200 rows only in the source"), tree);
    assertTrue(tree.contains("showing the first 1,000 differences of 1,200"), tree);
    c.run(app, "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\nsnap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("1,200 changes will be written to orders_copy"), preview);
    assertTrue(preview.contains("Those of the first 1,000 differences are shown."), preview);
    c.run(app, "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(1_200, c.servers.rowsOf(COPY).size());
    var batches = c.servers.linked.stream().filter(l -> l.contains(" batch ")).count();
    assertEquals(3, batches);
  }

  // A key that is not unique stops the comparison; what giving up the read said is noticed.
  @Test
  void aRepeatedKeyStopsTheComparisonAndTheReadsGiveUpIsNoticed() throws Exception {
    var many = new ArrayList<List<Object>>();
    many.add(row(1L, "new", "1.00", "n"));
    for (long id = 1; id <= 600; id++) {
      many.add(row(id, "new", "1.00", "n"));
    }
    c.servers.values(ORDERS, many);
    c.servers.failsAtGiveUp(
        "scan " + ORDERS, FakeServers.serverError(Failure.Kind.OTHER, "could not end the read"));
    var app = compared(SHOP);
    c.run(app, COMPARE + "snap repeated\n");
    var tree = c.tree("repeated");
    assertTrue(tree.contains("The rows cannot be paired"), tree);
    assertTrue(tree.contains("The key (id) is not unique in the source"), tree);
    // Told from the lane as the read is given up, behind the failure.
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("close scan " + ORDERS));
    var notices = app.state().notices;
    for (int i = 0; i < 50 && notices.isEmpty(); i++) {
      Thread.sleep(20);
      c.run(app, "await\nframe\n");
    }
    assertEquals(1, notices.size(), notices.toString());
    assertTrue(
        notices.getFirst().what().startsWith(Sync.GAVE_UP + "could not end the read"),
        notices.getFirst().what());
    assertTrue(notices.getFirst().failure());
  }

  @Test
  void cancellingTheWritingLeavesTheTargetAsItWas() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("batch " + COPY);
    c.run(app, "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n");
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("batch " + COPY));
    c.run(app, "frame\npress role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    gate.countDown();
    var tree = c.tree("cancelled");
    assertTrue(tree.contains("Writing the changes was cancelled."), tree);
    assertEquals(4, c.servers.rowsOf(COPY).size());
    assertTrue(c.servers.calls.stream().noneMatch(l -> l.startsWith("commit ")));
  }
}

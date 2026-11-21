package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.log.Purpose;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Run SQL file: a file chosen, run on a session of its own, with its failures listed. */
class FileRunDialogTest {

  static final String OPENS = "frame\nadvance 1\nframe\n";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.statement("INSERT INTO t VALUES (1)", List.of(new Result.Count(1)));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  private Path file(String name, String text) throws Exception {
    var file = dir.resolve(name);
    Files.writeString(file, text);
    return file;
  }

  /** The app with shop-prod connected and Run SQL file open from its menu, the file chosen. */
  private App<State> chosen(Path file) throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("shop-prod") + SETTLE + menu("shop-prod", CatalogTree.FILE_RUN));
    c.run(
        app,
        "dialog \"" + file.toString().replace("\\", "\\\\") + "\"\n"
            + "press role=button label=\"Choose…\"\nframe\nawait\nframe\n");
    return app;
  }

  @Test
  void aFileRunsOnASessionOfItsOwnAndListsWhatFailed() throws Exception {
    var file = file(
        "dump.sql",
        "INSERT INTO t VALUES (1);\n\nSELEC 2;\nCOPY t FROM stdin;\n1\tx\n\\.\n" + "SELECT '"
            + PASSWORD + "';\n");
    var app = chosen(file);
    c.run(app, "snap chosen\n");
    var chosen = c.tree("chosen");
    assertTrue(chosen.contains("role=dialog label=\"" + FileRunDialog.TITLE + "\""), chosen);
    assertTrue(chosen.contains("dump.sql"), chosen);
    c.run(
        app,
        "press label=\"Go on\"\n" + OPENS + "press role=button label=\"Run\"\n" + SETTLE
            + "snap ran\n");
    var ran = c.tree("ran");
    assertTrue(ran.contains("Ran to its end, 2 failed"), ran);
    assertTrue(ran.contains("4 statements ran"), ran);
    assertTrue(ran.contains("SELEC 2"), ran);
    assertTrue(ran.contains("\"3\""), ran); // the line of SELEC 2
    // Its own session: the second link that was opened, not the tree's.
    assertTrue(
        c.servers.linked.contains("2 run " + PG + " INSERT INTO t VALUES (1)"),
        c.servers.linked.toString());
    assertTrue(
        c.servers.calls.contains("copy " + PG + " COPY t FROM stdin 1\tx\n"),
        c.servers.calls.toString());
    // The password is masked in what failed.
    c.noPassword();
    // Nothing of it went into the history.
    assertTrue(
        app.state().keeping.history.isEmpty(), app.state().keeping.history.toString());
    c.run(app, "press role=button label=\"Close\"\n" + OPENS + "snap closed\n");
    assertFalse(c.tree("closed").contains("role=dialog"), c.tree("closed"));
  }

  @Test
  void theLogKeepsTheStartOfALongStatement() throws Exception {
    // A dump's extended INSERT, which may run to megabytes, ending in a pair of surrogates cut
    // where the log cuts it.
    var head = "INSERT INTO t VALUES " + "(1),".repeat(Recorded.SHOWN);
    var sql =
        head.substring(0, Recorded.SHOWN - 1) + Character.toString(0x1F600) + "(2)".repeat(100_000);
    c.servers.statement(sql, List.of(new Result.Count(1)));
    var app = chosen(file("long.sql", sql + ";\n"));
    c.run(app, "press role=button label=\"Run\"\n" + SETTLE + "snap ran\n");
    assertTrue(c.tree("ran").contains("Ran to its end"), c.tree("ran"));
    var logged = app.state().statements.entries().stream()
        .filter(e -> e.sent().purpose() == Purpose.FILE)
        .toList();
    assertEquals(1, logged.size(), logged.toString());
    var text = logged.getFirst().sent().text();
    assertEquals(
        sql.substring(0, Recorded.SHOWN - 1) + "\n/* … " + sql.length() + " characters in all. */",
        text);
  }

  @Test
  void aFailureStopsTheRunUnlessItGoesOn() throws Exception {
    var app = chosen(file("stop.sql", "SELEC 1;\nINSERT INTO t VALUES (1);\n"));
    c.run(app, "press role=button label=\"Run\"\n" + SETTLE + "snap stopped\n");
    var stopped = c.tree("stopped");
    assertTrue(stopped.contains("Stopped at an error"), stopped);
    assertTrue(stopped.contains("It stopped at line 1, after 1 statement."), stopped);
    assertFalse(c.servers.calls.contains("run " + PG + " INSERT INTO t VALUES (1)"));
  }

  @Test
  void oneTransactionRollsBackAFailure() throws Exception {
    var app = chosen(file("tx.sql", "INSERT INTO t VALUES (1);\nSELEC 1;\n"));
    c.run(app, "press label=\"In one transaction\"\n" + OPENS + "snap transaction\n");
    // In a transaction an error always stops the run.
    assertTrue(c.tree("transaction").contains("In one transaction an error stops the run"));
    c.run(app, "press role=button label=\"Run\"\n" + SETTLE + "snap rolled\n");
    var rolled = c.tree("rolled");
    assertTrue(
        rolled.contains("The transaction was rolled back: nothing of the file was kept."), rolled);
    assertTrue(c.servers.calls.contains("run " + PG + " ROLLBACK"), c.servers.calls.toString());
    assertTrue(c.servers.calls.contains("autocommit " + PG + " off"), c.servers.calls.toString());
  }

  @Test
  void aRunCanBeHiddenAndCancelled() throws Exception {
    var held = c.servers.hold("run " + PG + " SELECT pg_sleep(60)");
    var app = chosen(file("sleep.sql", "SELECT pg_sleep(60);\nINSERT INTO t VALUES (1);\n"));
    c.run(app, "press role=button label=\"Run\"\n" + OPENS + "snap running\n");
    var running = c.tree("running");
    assertTrue(running.contains("role=progressbar"), running);
    assertTrue(running.contains("Runs on shop-prod, shop."), running);
    c.run(app, "press role=button label=\"Hide\"\n" + OPENS + "snap hidden\n");
    var hidden = c.tree("hidden");
    assertFalse(hidden.contains("role=dialog"), hidden);
    assertTrue(hidden.contains("Running sleep.sql…"), hidden);
    c.run(
        app,
        "press #file-run\n" + OPENS + "press role=button label=\"Cancel run\"\nframe\n" + SETTLE
            + "snap cancelled\n");
    var cancelled = c.tree("cancelled");
    assertTrue(cancelled.contains("Cancelled after 1 statement."), cancelled);
    assertTrue(
        c.servers.interrupted.toString().contains("SELECT pg_sleep(60)"),
        c.servers.interrupted.toString());
    assertFalse(c.servers.calls.contains("run " + PG + " INSERT INTO t VALUES (1)"));
    held.countDown();
  }

  @Test
  void disconnectingCancelsTheRun() throws Exception {
    var held = c.servers.hold("run " + PG + " SELECT pg_sleep(60)");
    var app = chosen(file("sleep.sql", "SELECT '" + PASSWORD + "';\nSELECT pg_sleep(60);\n"));
    c.run(
        app,
        "press label=\"Go on\"\n" + OPENS + "press role=button label=\"Run\"\n" + OPENS
            + "press role=button label=\"Hide\"\n" + OPENS);
    // The statement before has failed once the run holds at the next.
    CatalogTreeTest.eventually(
        () -> c.servers.calls.contains("run " + PG + " SELECT pg_sleep(60)"));
    c.run(app, menu("shop-prod", "Disconnect") + SETTLE + "snap disconnected\n");
    var disconnected = c.tree("disconnected");
    assertTrue(disconnected.contains("Cancelled as its connection ended"), disconnected);
    // What failed is masked, though the session closed before the run said so.
    assertTrue(disconnected.contains("label=\"Failed statements\""), disconnected);
    c.noPassword();
    held.countDown();
  }

  @Test
  void aDatabasesMenuRunsOnThatDatabase() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, START + open("shop-prod") + SETTLE + menu("shop", CatalogTree.FILE_RUN) + "snap db\n");
    var tree = c.tree("db");
    assertTrue(tree.contains("role=dialog label=\"" + FileRunDialog.TITLE + "\""), tree);
    assertEquals(1, app.state().dialogs.fileRun == null ? 0 : 1);
    assertEquals("shop", app.state().dialogs.fileRun.database);
    // Run waits for a file.
    assertTrue(ConnectionsTest.line(tree, "label=\"Run\"").contains("disabled"), tree);
  }
}

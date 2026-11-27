package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
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
import com.rethinkj.ui.kit.Menu;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.FileRun;
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
    return chosen(SHOP, file);
  }

  /** The app with the connection connected and Run SQL file open from its menu, the file chosen. */
  private App<State> chosen(ConnectionProfile profile, Path file) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    var name = profile.name();
    c.run(app, START + open(name) + SETTLE + menu(name, CatalogTree.FILE_RUN));
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
    // What a transaction of the file's own ran is not said to stay; the tree has the text's end.
    assertTrue(stopped.contains("is rolled back as the session closes.\""), stopped);
    assertFalse(c.servers.calls.contains("run " + PG + " INSERT INTO t VALUES (1)"));
  }

  @Test
  void aStopBeyondTheListedFailuresNamesNoLine() throws Exception {
    // The command it stops at comes after the failures the report lists.
    var app = chosen(file("many.sql", "SELEC 1;\n".repeat(FileRun.LISTED) + "\\connect x\n"));
    c.run(
        app,
        "press label=\"Go on\"\n" + OPENS + "press role=button label=\"Run\"\n" + SETTLE
            + "snap stopped\n");
    var stopped = c.tree("stopped");
    assertTrue(stopped.contains("Stopped at an error"), stopped);
    assertTrue(stopped.contains("After 1001 statements."), stopped);
    assertFalse(stopped.contains("It stopped at line"), stopped);
  }

  @Test
  void theFileMenuWaitsForAQuestionOfTheMainWindow() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("shop-prod") + SETTLE + menu("shop-prod", "New console") + SETTLE);
    assertTrue(runFile(app).isEnabled());
    // Asked in the main window, which another window's File menu does not see.
    c.run(app, ConsoleTest.type(ConsoleConfirmationTest.WIPE) + ConsoleTest.RUN + "snap asked\n");
    assertTrue(c.tree("asked").contains(ConsoleConfirmationTest.EVERY), c.tree("asked"));
    assertFalse(runFile(app).isEnabled());
  }

  // File, Run SQL file… as another window has it, with no dialog of its own open.
  private static Menu.Item runFile(App<State> app) {
    var file = Frame.menus(app.state(), app.state().tabs.main().active(), false).getFirst();
    return file.submenu().stream()
        .filter(item -> "Run SQL file…".equals(item.label()))
        .findFirst()
        .orElseThrow();
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
  void aRollbackSaysWhatTheServerCommittedByItself() throws Exception {
    for (var sql : List.of("LOCK TABLES t WRITE", "UNLOCK TABLES")) {
      c.servers.statement(sql, List.of(new Result.Count(0)));
    }
    // Named apart from its database inventory, which the tree shows below it.
    var app = chosen(
        INVENTORY.withName("stock"),
        file(
            "dump.sql",
            "LOCK TABLES t WRITE;\nINSERT INTO t VALUES (1);\nUNLOCK TABLES;\nSELEC 1;\n"));
    c.run(app, "press label=\"In one transaction\"\n" + OPENS + "snap transaction\n");
    var transaction = c.tree("transaction");
    assertTrue(transaction.contains("LOCK TABLES, UNLOCK TABLES and GRANT"), transaction);
    c.run(app, "press role=button label=\"Run\"\n" + SETTLE + "snap rolled\n");
    var rolled = c.tree("rolled");
    assertTrue(
        rolled.contains("The transaction was rolled back, but what ran up to line 3 stays, as the"
            + " server committed it by itself there."),
        rolled);
  }

  @Test
  void aCallInOneTransactionStopsTheRunUnsentAndNothingIsKept() throws Exception {
    var app = chosen(
        INVENTORY.withName("stock"),
        file("called.sql", "INSERT INTO t VALUES (1);\nCALL commits();\nSELEC 1;\n"));
    c.run(app, "press label=\"In one transaction\"\n" + OPENS + "snap transaction\n");
    c.run(app, "press role=button label=\"Run\"\n" + SETTLE + "snap rolled\n");
    var rolled = c.tree("rolled");
    // The procedure, which could have committed the insert, was never called, so the rollback
    // undid all that ran.
    assertTrue(
        rolled.contains("The transaction was rolled back: nothing of the file was kept."), rolled);
    assertTrue(rolled.contains("This runs a procedure or a prepared statement"), rolled);
    assertTrue(rolled.contains("\"2\""), rolled); // the line of the CALL
    assertFalse(
        c.servers.calls.stream().anyMatch(call -> call.endsWith("CALL commits()")),
        c.servers.calls.toString());
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
    // Ended hidden, it says so in a desktop notification, whose click shows the dialog.
    assertTrue(Files.readString(dir.resolve("out").resolve("disconnected.notifications.txt"))
        .contains("\"sleep.sql: Cancelled\""));
    assertFalse(c.tree("disconnected").contains("role=dialog"), c.tree("disconnected"));
    c.run(
        app,
        "notification click \"sleep.sql: Cancelled\"\nawait\nframe\n" + OPENS
            + "snap disconnected\n");
    var disconnected = c.tree("disconnected");
    assertTrue(disconnected.contains("Cancelled as its connection ended"), disconnected);
    // What failed is masked, though the session closed before the run said so.
    assertTrue(disconnected.contains("label=\"Failed statements\""), disconnected);
    c.noPassword();
    held.countDown();
  }

  /** A run in one transaction, hidden as it holds at its statement, on the second link. */
  private App<State> runningInOne() throws Exception {
    var app = chosen(file("sleep.sql", "SELECT pg_sleep(60);\n"));
    c.run(
        app,
        "press label=\"In one transaction\"\n" + OPENS + "press role=button label=\"Run\"\n" + OPENS
            + "press role=button label=\"Hide\"\n" + OPENS);
    CatalogTreeTest.eventually(
        () -> c.servers.calls.contains("run " + PG + " SELECT pg_sleep(60)"));
    return app;
  }

  @Test
  void exitAsksBeforeItCancelsARunInOneTransaction() throws Exception {
    var held = c.servers.hold("run " + PG + " SELECT pg_sleep(60)");
    var app = runningInOne();
    c.run(app, "snap running\n");
    // The status line counts it as it counts a console's transaction.
    assertTrue(
        c.tree("running").contains("A file that runs in one transaction holds uncommitted work"),
        c.tree("running"));
    c.run(app, ConsoleTransactions.EXIT + OPENS + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(
        asked.contains("sleep.sql runs in one transaction on shop-prod, shop. Cancel it, which"
            + " rolls back what it ran, and HooriDB closes?"),
        asked);
    c.run(app, "press role=button label=\"Keep running\"\n" + OPENS + "snap kept\n");
    var kept = c.tree("kept");
    assertFalse(kept.contains("Cancel the run?"), kept);
    assertTrue(kept.contains("role=progressbar"), kept); // the dialog shows, as it asked there
    assertFalse(app.windows().all().isEmpty());
    assertFalse(c.servers.interrupted.toString().contains("pg_sleep"));
    c.run(
        app,
        "press role=button label=\"Hide\"\n" + OPENS + ConsoleTransactions.EXIT + OPENS
            + "press role=button label=\"Cancel the run\"\nframe\n" + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "HooriDB closed");
    assertTrue(
        c.servers.interrupted.toString().contains("SELECT pg_sleep(60)"),
        c.servers.interrupted.toString());
    assertTrue(c.servers.calls.contains("run " + PG + " ROLLBACK"), c.servers.calls.toString());
    held.countDown();
  }

  @Test
  void disconnectingAsksBeforeItCancelsARunInOneTransaction() throws Exception {
    var held = c.servers.hold("run " + PG + " SELECT pg_sleep(60)");
    var app = runningInOne();
    c.run(app, menu("shop-prod", "Disconnect") + OPENS + "snap asked\n");
    assertTrue(c.tree("asked").contains("and shop-prod disconnects?"), c.tree("asked"));
    // The press takes effect in the next frame, before the run can end.
    c.run(app, "press role=button label=\"Cancel the run\"\nframe\n" + SETTLE + "snap cancelled\n");
    var cancelled = c.tree("cancelled");
    assertTrue(cancelled.contains("Cancelled as its connection ended"), cancelled);
    assertTrue(cancelled.contains("The transaction was rolled back"), cancelled);
    assertFalse(app.state().catalog.connected(SHOP.id()));
    held.countDown();
  }

  @Test
  void aSessionThatFailsToCloseIsANoticeAndExitStays() throws Exception {
    // The run's own session is the second link.
    c.servers.failsAtClose(2, FakeServers.serverError(Failure.Kind.OTHER, "the socket broke"));
    var held = c.servers.hold("run " + PG + " SELECT pg_sleep(60)");
    var app = chosen(file("sleep.sql", "SELECT pg_sleep(60);\n"));
    c.run(
        app,
        "press role=button label=\"Run\"\n" + OPENS + "press role=button label=\"Hide\"\n" + OPENS);
    CatalogTreeTest.eventually(
        () -> c.servers.calls.contains("run " + PG + " SELECT pg_sleep(60)"));
    c.run(app, ConsoleTransactions.EXIT + SETTLE + "snap stayed\n");
    var stayed = c.tree("stayed");
    assertFalse(app.windows().all().isEmpty(), "stayed open");
    assertTrue(
        ConsoleTest.near(stayed, "#notice-1")
            .contains("Closing the file's session failed: the socket broke"),
        stayed);
    assertTrue(stayed.contains("HooriDB stayed open"), stayed);
    held.countDown();
  }

  @Test
  void anEndInDoubtIsANotice() throws Exception {
    c.servers.statement(
        "ROLLBACK",
        List.of(FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "the server went away")));
    var app = chosen(file("tx.sql", "INSERT INTO t VALUES (1);\nSELEC 1;\n"));
    c.run(
        app,
        "press label=\"In one transaction\"\n" + OPENS + "press role=button label=\"Run\"\n"
            + SETTLE + "snap doubt\n");
    var doubt = c.tree("doubt");
    assertTrue(doubt.contains("The transaction's end failed."), doubt);
    assertTrue(
        ConsoleTest.near(doubt, "#notice-1")
            .contains("The end of the file's transaction failed: the server went away"),
        doubt);
  }

  @Test
  void cancelGivesUpARunWhoseSessionStillConnects() throws Exception {
    var app = chosen(file("one.sql", "INSERT INTO t VALUES (1);\n"));
    // The tree's link is open, the run's waits.
    var opening = c.servers.hold("open " + PG + "/shop");
    c.run(
        app,
        "press role=button label=\"Run\"\n" + OPENS + "press role=button label=\"Cancel run\"\n"
            + "frame\n" + SETTLE + "snap given\n");
    var given = c.tree("given");
    assertTrue(given.contains("The run was given up before it began."), given);
    opening.countDown();
    c.run(app, SETTLE);
    assertFalse(c.servers.calls.contains("run " + PG + " INSERT INTO t VALUES (1)"));
  }

  @Test
  void theConnectionsAreChosenAsAQuerysTarget() throws Exception {
    var stock = INVENTORY.withName("stock");
    var app = c.app(SHOP, stock);
    c.ready(app, SHOP, stock);
    c.run(app, START + open("shop-prod") + SETTLE + menu("shop-prod", CatalogTree.FILE_RUN));
    c.run(app, "press #connection\n" + OPENS + "snap connections\n");
    var connections = c.tree("connections");
    // Every connection of SQL, the chosen one first, and one not connected marked so.
    var options = connections.lines().filter(l -> l.contains("role=option")).toList();
    assertEquals(2, options.size(), connections);
    assertTrue(options.getFirst().contains("label=\"shop-prod\""), connections);
    assertTrue(options.getLast().contains("label=\"stock\""), connections);
    assertTrue(connections.contains("Not connected"), connections);
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

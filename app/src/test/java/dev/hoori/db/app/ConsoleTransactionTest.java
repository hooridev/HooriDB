package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.INVENTORY;
import static dev.hoori.db.app.CatalogueTest.MARIA;
import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.CatalogueTest.eventually;
import static dev.hoori.db.app.CatalogueTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.MISSING;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.near;
import static dev.hoori.db.app.ConsoleTest.status;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A console's transactions: Auto and Manual, Commit and Rollback, and what asks before one ends. */
class ConsoleTransactionTest {

  static final String DDL = "CREATE TABLE notes (id int)";
  static final String KILL = "SELECT pg_terminate_backend(pg_backend_pid())";
  // On an open session the switch asks the server first.
  static final String MANUAL = "press role=radio label=\"Manual\"\nframe\n" + SETTLE;
  static final String CALL = "CALL report()"; // begins a transaction, and gives 250 rows
  static final String DEADLOCKED = "UPDATE stock SET n = n - 1 WHERE id = 7";
  static final String CHAIN = "SET completion_type=1";
  static final String COMMITTING = "run " + PG + " COMMIT";
  static final String EXIT = "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
      + "press role=menuitem label=\"Exit\"\nframe\nadvance 1\nframe\n";
  static final String OPENED = "advance 1\nframe\n"; // a dialog fades in, or out

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogueTest c;
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    servers = k.servers
        .statement(DDL, List.of(new Result.Count(0)))
        .statement(CALL, List.of(FakeServers.made(250, "id: bigint")))
        .begins(CALL)
        .statement(DEADLOCKED, List.of(FakeServers.deadlock()))
        .statement(
            KILL,
            List.of(FakeServers.serverError(
                Failure.Kind.CONNECTION_LOST, "terminating connection due to administrator")));
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  /** A console in Manual with a transaction open, after the change. */
  App<State> open(ConnectionProfile profile) throws Exception {
    var app = k.console(profile);
    c.run(app, MANUAL + type(PAY) + RUN);
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    return app;
  }

  /** How many times the server was asked for the transaction. */
  long probes(String host) {
    return servers.calls.stream().filter(("transaction " + host)::equals).count();
  }

  static String badge(String tree) {
    return near(tree, "#transaction");
  }

  @Test
  void manualKeepsTheChangeOpenUntilCommitOrRollback() throws Exception {
    var app = k.console(SHOP);
    c.run(app, "snap auto\n");
    String auto = c.tree("auto");
    assertTrue(line(auto, "role=radio label=\"Auto\"").contains("checked"), auto);
    assertFalse(auto.contains("#commit"), "no Commit in Auto: " + auto);
    assertFalse(auto.contains("#transaction"), auto);
    c.run(app, MANUAL + "snap manual\n");
    String manual = c.tree("manual");
    assertTrue(line(manual, "role=radio label=\"Manual\"").contains("checked"), manual);
    assertTrue(line(manual, "#commit").contains("disabled"), manual);
    assertTrue(line(manual, "#rollback").contains("disabled"), manual);
    assertTrue(badge(manual).contains("No transaction"), manual);
    // A change opens it; the connection turned auto-commit off first.
    c.run(app, type(PAY) + RUN + "snap changed\n");
    String changed = c.tree("changed");
    assertTrue(servers.calls.contains("autocommit " + PG + " off"), servers.calls.toString());
    assertTrue(badge(changed).contains("Transaction open · 1 statement"), changed);
    assertFalse(line(changed, "#commit").contains("disabled"), changed);
    assertFalse(line(changed, "#rollback").contains("disabled"), changed);
    // Commit leaves the results as they are, and the console says so.
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap committed\n");
    String committed = c.tree("committed");
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(badge(committed).contains("No transaction"), committed);
    assertTrue(line(committed, "#commit").contains("disabled"), committed);
    assertTrue(tabs(committed).endsWith("Result 1,Messages"), tabs(committed));
    assertTrue(line(committed, "#said").contains("Committed"), committed);
    assertTrue(status(committed).contains("3 rows changed"), committed);
    // Control+Shift+R rolls back, from the editor.
    c.run(app, "press #sql\n" + RUN + "key R control shift\nframe\n" + SETTLE + "snap undone\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(badge(c.tree("undone")).contains("No transaction"), c.tree("undone"));
    // Control+Shift+C commits.
    c.run(app, RUN + "key C control shift\nframe\n" + SETTLE);
    assertEquals(
        2, servers.calls.stream().filter(x -> x.equals("run " + PG + " COMMIT")).count());
    assertFalse(servers.overlapped, "two calls ran at once on one connection");
  }

  @Test
  void aBeginTypedInAutoShowsTheTransactionUntilItsEnd() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type("BEGIN") + RUN + "snap begun\n");
    String begun = c.tree("begun");
    assertTrue(badge(begun).contains("Transaction open"), begun);
    assertFalse(line(begun, "#commit").contains("disabled"), begun);
    assertTrue(line(begun, "role=radio label=\"Auto\"").contains("checked"), begun);
    c.run(app, type(";\n" + "COMMIT") + RUN + "snap ended\n");
    String ended = c.tree("ended");
    assertFalse(ended.contains("#transaction"), ended);
    assertFalse(ended.contains("#commit"), ended);
    // A ROLLBACK typed in Manual ends it as well.
    c.run(app, MANUAL + type(";\n" + PAY) + RUN);
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, type(";\n" + "ROLLBACK") + RUN + "snap rolledBack\n");
    assertTrue(badge(c.tree("rolledBack")).contains("No transaction"), c.tree("rolledBack"));
  }

  @Test
  void anErrorFailsPostgresqlsTransactionWhichTakesOnlyARollback() throws Exception {
    var app = open(SHOP);
    c.run(app, type(";\n" + MISSING) + RUN + "snap failed\n");
    String failed = c.tree("failed");
    assertTrue(badge(failed).contains("Transaction failed"), failed);
    assertTrue(line(failed, "#commit").contains("disabled"), failed);
    assertFalse(line(failed, "#rollback").contains("disabled"), failed);
    assertTrue(line(failed, "#failed").contains("The transaction failed"), failed);
    // What runs next is refused, as the server refuses it.
    c.run(app, type(";\n" + COUNT) + RUN + "snap refused\n");
    String refused = c.tree("refused");
    assertTrue(refused.contains("current transaction is aborted"), refused);
    c.run(app, "press #roll-back\nframe\n" + SETTLE + "snap rolledBack\n");
    String rolledBack = c.tree("rolledBack");
    assertTrue(badge(rolledBack).contains("No transaction"), rolledBack);
    assertFalse(rolledBack.contains("#failed"), rolledBack);
  }

  @Test
  void onMariaDbAnErrorLeavesItOpenAsTheServerSaysAndDdlCommits() throws Exception {
    var app = open(INVENTORY);
    c.run(app, type(";\n" + MISSING) + RUN + "snap failed\n");
    String failed = c.tree("failed");
    // The portion did not say: the console asked.
    assertTrue(servers.calls.contains("transaction " + MARIA), servers.calls.toString());
    assertTrue(badge(failed).contains("Transaction open · 2 statements"), failed);
    assertFalse(line(failed, "#commit").contains("disabled"), failed);
    c.run(app, type(";\n" + DDL) + RUN + "snap ddl\n");
    assertTrue(badge(c.tree("ddl")).contains("No transaction"), c.tree("ddl"));
  }

  @Test
  void runAllKeepsOneTransactionAndStopsAtTheFirstError() throws Exception {
    var app = k.console(SHOP);
    var text = String.join(";\n", PAY, MISSING, COUNT) + ";";
    c.run(app, MANUAL + type(text) + RUN_ALL + "snap all\n");
    String all = c.tree("all");
    assertFalse(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
    assertTrue(badge(all).contains("Transaction failed"), all);
    assertEquals(2, k.tab(app).inTransaction);
  }

  @Test
  void aCancelInsideTheTransactionShowsWhatTheServerLeft() throws Exception {
    // PostgreSQL fails the transaction.
    var app = open(SHOP);
    var key = "run " + PG + " " + ORDERS;
    servers.hold(key);
    c.run(app, type(";\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    assertTrue(badge(c.tree("cancelled")).contains("Transaction failed"), c.tree("cancelled"));
    // MariaDB leaves it open.
    var maria = open(INVENTORY);
    var held = "run " + MARIA + " " + ORDERS;
    servers.hold(held);
    c.run(maria, type(";\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(held));
    c.run(maria, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap open\n");
    assertTrue(servers.calls.contains("transaction " + MARIA), servers.calls.toString());
    assertTrue(badge(c.tree("open")).contains("Transaction open · 1 statement"), c.tree("open"));
  }

  @Test
  void closingTheTabAsksAndCancelStays() throws Exception {
    var app = open(SHOP);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
    assertTrue(
        asked.contains("after 1 statement. Commit its changes or roll them back before the"
            + " console closes."),
        asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertFalse(stayed.contains("role=dialog"), stayed);
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
  }

  @Test
  void controlWAndTheMenusCloseTabAskToo() throws Exception {
    var app = open(SHOP);
    c.run(app, "key W control\nframe\n" + OPENED + "snap key\n");
    assertTrue(c.tree("key").contains("role=dialog label=\"Commit the transaction?\""));
    c.run(
        app,
        "press #answer-cancel\nframe\n" + OPENED
            + "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Close tab\"\nframe\n" + OPENED + "snap menu\n");
    assertTrue(c.tree("menu").contains("role=dialog label=\"Commit the transaction?\""));
    assertEquals(1, app.state().consoles(null).size());
  }

  @Test
  void closingTheTabCommitsOrRollsBackFirst() throws Exception {
    for (var answer : List.of("COMMIT", "ROLLBACK")) {
      var app = open(SHOP);
      c.run(
          app,
          "press #close-0\nframe\n" + OPENED + "press #answer-" + answer.toLowerCase() + "\nframe\n"
              + OPENED + SETTLE);
      assertTrue(servers.calls.contains("run " + PG + " " + answer), servers.calls.toString());
      assertTrue(app.state().consoles(null).isEmpty(), answer);
      servers.calls.clear();
    }
  }

  @Test
  void switchingToAutoAsks() throws Exception {
    var app = open(SHOP);
    c.run(app, "press role=radio label=\"Auto\"\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to Auto."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(ConsoleTab.Mode.MANUAL, k.tab(app).mode);
    c.run(
        app,
        "press role=radio label=\"Auto\"\nframe\n" + OPENED + "press #answer-commit\nframe\n"
            + OPENED + SETTLE + "snap auto\n");
    String auto = c.tree("auto");
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(line(auto, "role=radio label=\"Auto\"").contains("checked"), auto);
    assertFalse(auto.contains("#transaction"), auto);
    // The next statement runs with auto-commit on.
    c.run(app, "press #sql\n" + RUN);
    assertTrue(servers.calls.contains("autocommit " + PG + " on"), servers.calls.toString());
    assertEquals(Transaction.NONE, k.tab(app).transaction);
  }

  @Test
  void switchingTheDatabaseAsks() throws Exception {
    var app = open(SHOP);
    var choose = "press #database\nframe\n" + OPENED
        + "press role=menuitem label=\"analytics\"\nframe\n" + OPENED;
    c.run(app, choose + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to analytics."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals("shop", k.tab(app).database);
    c.run(app, choose + "press #answer-rollback\nframe\n" + OPENED + SETTLE + "snap switched\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(tabs(c.tree("switched")).startsWith("shop-prod: analytics,"), c.tree("switched"));
  }

  @Test
  void closingHooriDbAsks() throws Exception {
    var app = open(SHOP);
    var exit = "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
        + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED;
    c.run(app, exit + "snap asked\n");
    assertTrue(c.tree("asked").contains("before HooriDB closes."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    c.run(app, exit + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void closingHooriDbCommitsFirst() throws Exception {
    var app = open(SHOP);
    c.run(
        app,
        "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED
            + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void disconnectingAsksAndCancelLeavesItConnected() throws Exception {
    var app = open(SHOP);
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
    assertTrue(asked.contains("before shop-prod disconnects."), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().catalogue.connected(SHOP.id()));
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
    c.run(
        app,
        menu("shop-prod", "Disconnect") + OPENED + "press #answer-rollback\nframe\n" + OPENED
            + SETTLE + "snap ended\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertFalse(app.state().catalogue.connected(SHOP.id()));
    assertFalse(k.tab(app).connected());
    assertFalse(c.tree("ended").contains("role=dialog"), c.tree("ended"));
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  @Test
  void aLostConnectionSaysTheTransactionIsGone() throws Exception {
    var app = open(SHOP);
    c.run(app, type(";\n" + KILL) + RUN + "snap lost\n");
    String lost = c.tree("lost");
    assertTrue(line(lost, "#problem").contains("Disconnected"), lost);
    assertTrue(lost.contains("The open transaction is gone with the connection"), lost);
    assertTrue(badge(lost).contains("No transaction"), lost);
    // Closing asks nothing now.
    c.run(app, "press #close-0\nframe\n" + OPENED);
    assertTrue(app.state().consoles(null).isEmpty());
    // In Auto with none open, nothing is said of one.
    var auto = k.console(SHOP);
    c.run(auto, type(KILL) + RUN + "snap autoLost\n");
    assertFalse(c.tree("autoLost").contains("open transaction"), c.tree("autoLost"));
  }

  // The review's findings, each as it was met, and what goes with them.

  /** 1: the console never turns auto-commit on under a transaction; the mode follows the server. */
  @Test
  void aRollbackAfterSetAutocommitRollsBackAndTheModeFollowsTheServer() throws Exception {
    var app = k.console(INVENTORY);
    c.run(
        app,
        type("START TRANSACTION;\n" + PAY + ";\nSET autocommit=0") + RUN_ALL + "snap manual\n");
    String manual = c.tree("manual");
    assertTrue(line(manual, "role=radio label=\"Manual\"").contains("checked"), manual);
    assertTrue(badge(manual).contains("Transaction open"), manual);
    c.run(app, type(";\nROLLBACK") + RUN + "snap rolledBack\n");
    assertFalse(servers.calls.contains("commit by auto-commit " + MARIA), servers.calls.toString());
    assertTrue(badge(c.tree("rolledBack")).contains("No transaction"), c.tree("rolledBack"));
    // So with Run all, and with the Rollback button.
    c.run(
        app,
        "key A control\n" + "type \"START TRANSACTION; " + PAY + "; SET autocommit=0\"\n"
            + RUN_ALL + "press role=button label=\"Rollback\"\nframe\n" + SETTLE
            + "snap button\n");
    assertFalse(servers.calls.contains("commit by auto-commit " + MARIA), servers.calls.toString());
    assertTrue(line(c.tree("button"), "#said").contains("Rolled back"), c.tree("button"));
  }

  /** 2: after a cancel, Commit waits for the server's word, and is not sent into a failed one. */
  @Test
  void commitIsNotOfferedBeforeTheServerSaysWhatACancelLeft() throws Exception {
    var app = open(SHOP);
    var key = "run " + PG + " " + ORDERS;
    servers.hold(key);
    var probe = servers.hold("transaction " + PG);
    c.run(app, type(";\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    // The cancelled call ends; the question to the server is held.
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\n");
    eventually(() -> servers.calls.contains("transaction " + PG));
    c.run(
        app,
        "frame\nsnap asking\npress role=button label=\"Commit\"\nframe\nkey C control shift\n"
            + "frame\n");
    String asking = c.tree("asking");
    assertTrue(line(asking, "#commit").contains("disabled"), asking);
    assertFalse(line(asking, "#rollback").contains("disabled"), "the safe way out: " + asking);
    probe.countDown();
    c.run(app, SETTLE + "snap failed\n");
    String failed = c.tree("failed");
    assertFalse(servers.calls.contains(COMMITTING), servers.calls.toString());
    assertTrue(badge(failed).contains("Transaction failed"), failed);
    assertTrue(line(failed, "#commit").contains("disabled"), failed);
    assertFalse(failed.contains("Committed"), failed);
  }

  /** 2: on the lane too, a COMMIT goes only into a transaction the server says is open. */
  @Test
  void aCommitIsNotSentIntoAFailedTransaction() throws Exception {
    var link = servers.open(SHOP, ConnectionsTest.PASSWORD.toCharArray(), null);
    link.manual(true);
    link.run(PAY).next(1);
    link.run(MISSING).next(1);
    assertEquals(Transaction.FAILED, link.transaction());
    var ended = ConsoleTab.finish(link, ConsoleTab.COMMIT);
    assertEquals(Transaction.FAILED, ended.before());
    assertEquals(null, ended.portion());
    assertFalse(servers.calls.contains(COMMITTING), servers.calls.toString());
  }

  /** 3: closing while Commit runs waits for its answer, and queues no rollback behind it. */
  @Test
  void closingWhileCommitRunsWaitsForItsOutcome() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, "press role=button label=\"Commit\"\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(
        app,
        "press role=button label=\"Commit\"\nframe\npress #close-0\nframe\n" + OPENED
            + "snap waiting\n");
    String waiting = c.tree("waiting");
    assertTrue(waiting.contains("role=dialog label=\"Waiting for the commit\""), waiting);
    assertFalse(waiting.contains("#answer-rollback"), waiting);
    assertTrue(line(waiting, "#cancel").contains("disabled"), "a commit runs to its end");
    gate.countDown();
    c.run(app, SETTLE);
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /** 3: a commit whose connection was lost is said to be unknown, before the way out goes on. */
  @Test
  void aCommitWhoseConnectionWasLostHasAnUnknownOutcome() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, "press role=button label=\"Commit\"\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(app, "press #close-0\nframe\n" + OPENED);
    gate.countDown();
    c.run(app, SETTLE + OPENED + "snap unknown\n");
    String unknown = c.tree("unknown");
    assertTrue(unknown.contains("role=dialog label=\"The outcome is not known\""), unknown);
    assertTrue(
        unknown.contains("COMMIT: the connection was lost while it committed. Whether it took"
            + " effect is not known"),
        unknown);
    assertFalse(unknown.contains("Committed"), unknown);
    assertEquals(1, app.state().consoles(null).size(), "it waits for the user to read it");
    c.run(app, "press #answer-continue\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    // Without a question, the console says it.
    var again = open(SHOP);
    c.run(again, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap said\n");
    String said = c.tree("said");
    assertTrue(line(said, "#doubt").contains("The outcome is not known"), said);
    assertFalse(said.contains("\"Committed\""), said);
  }

  /** 4: a first Manual statement that still runs may have begun a transaction. */
  @Test
  void closingWhileTheFirstManualStatementRunsAsks() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN + MANUAL);
    var key = "run " + PG + " " + PAY;
    var gate = servers.hold(key);
    c.run(app, type("\n" + PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Roll back the transaction?\""), asked);
    assertTrue(asked.contains("A statement still runs on shop-prod: shop"), asked);
    assertFalse(asked.contains("#answer-commit"), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    gate.countDown();
    c.run(app, SETTLE + "snap stayed\n");
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    // Exit asks the same, while it runs.
    servers.hold("run " + PG + " " + COUNT);
    c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\nframe\n" + EXIT + "snap exit\n");
    assertTrue(c.tree("exit").contains("once it has ended with nothing open, HooriDB closes."));
  }

  /** 5: rows still to come on MariaDB leave the transaction unknown, also in Auto. */
  @Test
  void aProcedureWhoseRowsAreStillToComeMayHaveATransactionOpen() throws Exception {
    var app = k.console(INVENTORY);
    c.run(app, type(CALL) + RUN + "snap reading\n");
    String reading = c.tree("reading");
    assertTrue(badge(reading).contains("Transaction may be open"), reading);
    assertTrue(line(reading, "#commit").contains("disabled"), reading);
    assertFalse(line(reading, "#rollback").contains("disabled"), reading);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("A transaction may be open on inventory"), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    // Read to the end, the server says it.
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap read\n");
    assertTrue(badge(c.tree("read")).contains("Transaction open"), c.tree("read"));
  }

  /** 6: a question to the server that fails leaves the connection and an unknown transaction. */
  @Test
  void aFailedProbeLeavesTheConnectionAndTheTransactionUnknown() throws Exception {
    var app = open(INVENTORY);
    servers.fail(
        "transaction " + MARIA, FakeServers.queryFailure(QueryException.Failure.CANCELLED));
    c.run(app, type(";\n" + MISSING) + RUN + "snap unknown\n");
    String unknown = c.tree("unknown");
    assertTrue(badge(unknown).contains("Transaction may be open"), unknown);
    assertTrue(line(unknown, "#commit").contains("disabled"), unknown);
    assertFalse(line(unknown, "#rollback").contains("disabled"), unknown);
    assertTrue(k.tab(app).connected());
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
    servers.heal("transaction " + MARIA);
    c.run(app, "press role=button label=\"Rollback\"\nframe\n" + SETTLE + "snap rolledBack\n");
    assertTrue(badge(c.tree("rolledBack")).contains("No transaction"), c.tree("rolledBack"));
  }

  /** 6: a probe that fails late does not end the session of what ran after it. */
  @Test
  void aProbeThatFailsLateLeavesTheNextStatementsTransaction() throws Exception {
    var app = open(INVENTORY);
    var probe = servers.hold("transaction " + MARIA);
    long asked = probes(MARIA);
    c.run(app, type(";\n" + MISSING) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked); // held: what the statement left is asked
    // The next statement waits behind the probe on the lane.
    c.run(app, type(";\n" + PAY) + "key Enter control\nframe\nframe\n");
    servers.fail("transaction " + MARIA, FakeServers.queryFailure(QueryException.Failure.OTHER));
    probe.countDown();
    c.run(app, SETTLE + "snap after\n");
    assertTrue(badge(c.tree("after")).contains("Transaction open"), c.tree("after"));
    assertTrue(k.tab(app).connected());
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
  }

  /** 7: the tree's connection is not the console's: a colour while it is lost ends nothing. */
  @Test
  void aColourWhileTheTreeIsLostLeavesTheConsoleAlone() throws Exception {
    var app = open(SHOP);
    servers.fail(
        "tables " + PG + "/shop.public",
        FakeServers.notRead(CatalogException.Failure.CONNECTION_LOST));
    c.run(app, CatalogueTest.open("shop") + SETTLE + CatalogueTest.open("public") + SETTLE);
    assertFalse(app.state().catalogue.active(SHOP.id()), "the tree's connection is lost");
    app.state().put(SHOP.withColour(Colour.RED));
    // And as the tree's connection is given up, as when its password is not given.
    app.state().catalogue.drop(app.state(), SHOP.id());
    c.run(app, "frame\n" + SETTLE + "snap coloured\n");
    String coloured = c.tree("coloured");
    assertFalse(coloured.contains("role=dialog"), coloured);
    assertTrue(k.tab(app).connected());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /** 8: an Exit that waits for a save asks again once it has, and Cancel keeps HooriDB open. */
  @Test
  void anExitThatWaitedForASaveAsksAndCancelKeepsItOpen() throws Exception {
    var app = k.console(SHOP);
    var window = "window \"shop-prod: shop\"\n";
    c.run(
        app,
        type(COUNT + ";") + RUN + MANUAL
            + "press role=tab label=\"shop-prod: shop\" right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n");
    // A new connection, whose password the keyring holds: HooriDB closes once it is saved.
    c.secrets.gate = new CountDownLatch(1);
    c.run(
        app,
        "window main\n" + ConnectionsTest.NEW_SHOP.replace("New connection", "Add connection")
            + "key Enter\nframe\nclose\nframe\n");
    assertFalse(app.windows().all().isEmpty());
    // Meanwhile the console, in its own window, begins a transaction; the exit asked nothing.
    c.run(app, window + type("\n" + PAY) + "key Enter control\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + PAY));
    c.secrets.gate.countDown();
    c.run(app, SETTLE + window + OPENED + "snap asked\n");
    assertFalse(app.windows().all().isEmpty());
    String asked = c.tree("shop-prod-shop-asked");
    assertTrue(asked.contains("before HooriDB closes."), asked);
    c.run(app, window + "press #answer-cancel\nframe\n" + OPENED + SETTLE + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
  }

  /** 9: the switch the question asked about goes on to its own database once nothing is open. */
  @Test
  void aSwitchAskedWhileTheServerWasAskedGoesToItsDatabase() throws Exception {
    var app = open(INVENTORY);
    var probe = servers.hold("transaction " + MARIA);
    long asked = probes(MARIA);
    c.run(app, type(";\n" + DEADLOCKED) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked); // held: what the statement left is asked
    c.run(
        app,
        "frame\npress #database\nframe\n" + OPENED
            + "press role=menuitem label=\"inventory\"\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to inventory."), c.tree("asked"));
    probe.countDown(); // the deadlock rolled it back: nothing is open
    c.run(app, SETTLE + OPENED + "snap switched\n");
    assertTrue(servers.calls.contains("use " + MARIA + " inventory"), servers.calls.toString());
    assertTrue(tabs(c.tree("switched")).startsWith("inventory: inventory,"), c.tree("switched"));
    assertFalse(c.tree("switched").contains("role=dialog"), c.tree("switched"));
  }

  /** 10: with chained completion, the user's Commit or Rollback still lets the way out go on. */
  @Test
  void aTransactionTheServerChainsAfterTheAnswerDoesNotHoldTheTabOpen() throws Exception {
    for (var answer : List.of("commit", "rollback")) {
      var app = k.console(INVENTORY);
      c.run(app, type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction);
      c.run(
          app,
          "press #close-0\nframe\n" + OPENED + "press #answer-" + answer + "\nframe\n" + OPENED
              + SETTLE);
      assertTrue(app.state().consoles(null).isEmpty(), answer);
    }
    // And Exit, and the switch to Auto, whose auto-commit then ends the empty one.
    var app = k.console(INVENTORY);
    c.run(app, type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN);
    c.run(
        app,
        "press role=radio label=\"Auto\"\nframe\n" + OPENED + "press #answer-commit\nframe\n"
            + OPENED + SETTLE + "snap auto\n");
    assertTrue(
        line(c.tree("auto"), "role=radio label=\"Auto\"").contains("checked"), c.tree("auto"));
    assertTrue(servers.calls.contains("autocommit " + MARIA + " on"), servers.calls.toString());
    c.run(app, type(";\n" + PAY) + RUN + EXIT);
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void aFailedCommitKeepsTheTransactionForAnotherTryOrARollback() throws Exception {
    servers.statement(
        "COMMIT", List.of(FakeServers.serverError(Failure.Kind.OTHER, "Error writing file")));
    var app = open(INVENTORY);
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap failed\n");
    String failed = c.tree("failed");
    assertTrue(near(failed, "#problem").contains("Error writing file"), failed);
    assertTrue(badge(failed).contains("Transaction open"), failed);
    assertFalse(failed.contains("\"Committed\""), failed);
    assertFalse(line(failed, "#commit").contains("disabled"), failed);
    servers.forget("COMMIT");
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap committed\n");
    assertTrue(line(c.tree("committed"), "#said").contains("Committed"), c.tree("committed"));
    // Failed, then rolled back.
    servers.statement(
        "COMMIT", List.of(FakeServers.serverError(Failure.Kind.OTHER, "Error writing file")));
    c.run(
        app,
        "press #sql\n" + RUN + "press role=button label=\"Commit\"\nframe\n" + SETTLE
            + "press role=button label=\"Rollback\"\nframe\n" + SETTLE + "snap rolledBack\n");
    String rolledBack = c.tree("rolledBack");
    assertTrue(line(rolledBack, "#said").contains("Rolled back"), rolledBack);
    assertTrue(badge(rolledBack).contains("No transaction"), rolledBack);
  }

  @Test
  void aCommitThatPostgresqlRefusesEndsTheTransactionWithoutCommitted() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(FakeServers.serverError(
            Failure.Kind.CONSTRAINT, "insert or update violates foreign key constraint")));
    var app = open(SHOP);
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap refused\n");
    String refused = c.tree("refused");
    assertTrue(line(refused, "#problem").contains("A constraint was violated"), refused);
    assertTrue(badge(refused).contains("No transaction"), refused);
    assertFalse(refused.contains("\"Committed\""), refused);
  }

  @Test
  void commitRunsOnceAndNotWhileAStatementRuns() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(
        app,
        "press role=button label=\"Commit\"\nframe\nframe\npress role=button label=\"Commit\"\n"
            + "frame\nkey C control shift\nframe\nkey R control shift\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    gate.countDown();
    c.run(app, SETTLE);
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    // While a statement runs, neither.
    c.run(app, "press #sql\n" + RUN);
    servers.hold("run " + PG + " " + COUNT);
    c.run(
        app,
        type(";\n" + COUNT) + "key Enter control\nframe\nframe\n"
            + "press role=button label=\"Commit\"\nframe\nkey C control shift\nframe\n"
            + "press role=button label=\"Rollback\"\nframe\n");
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    servers.openAll();
    c.run(app, SETTLE);
  }

  @Test
  void exitAsksEachConsoleAndCancelAtAnyKeepsItAllOpen() throws Exception {
    var app = open(SHOP);
    c.run(app, menu("shop-prod", "New console") + MANUAL + type(PAY) + RUN);
    var consoles = app.state().consoles(null);
    assertEquals(2, consoles.size());
    assertEquals(Transaction.OPEN, consoles.get(1).transaction);
    // The first answers Commit; the second Cancel.
    c.run(app, EXIT + "snap first\n");
    assertTrue(c.tree("first").contains("shop-prod: shop (1) has a transaction"), c.tree("first"));
    c.run(app, "press #answer-commit\nframe\n" + OPENED + SETTLE + OPENED + "snap second\n");
    assertTrue(
        c.tree("second").contains("shop-prod: shop (2) has a transaction"), c.tree("second"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(Transaction.NONE, consoles.get(0).transaction);
    assertEquals(Transaction.OPEN, consoles.get(1).transaction);
    c.run(app, EXIT + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
  }

  @Test
  void editingTheConnectionAsksItsConsolesFirst() throws Exception {
    var app = open(SHOP);
    var edit =
        menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED;
    c.run(app, edit + "snap asked\n");
    assertTrue(c.tree("asked").contains("before shop-prod disconnects."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE + "snap stayed\n");
    assertTrue(app.state().catalogue.connected(SHOP.id()));
    assertEquals(null, app.state().dialog, "no edit before the console's answer");
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, edit + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().dialog != null, "the edit goes on");
    assertFalse(k.tab(app).connected());
    // A colour leaves the console's session as it is.
    var coloured = open(SHOP);
    coloured.state().put(SHOP.withColour(Colour.BLUE));
    c.run(coloured, "frame\n" + SETTLE);
    assertTrue(k.tab(coloured).connected());
    assertEquals(Transaction.OPEN, k.tab(coloured).transaction);
  }

  @Test
  void deletingTheConnectionAsksItsConsolesFirst() throws Exception {
    var app = open(SHOP);
    c.run(
        app,
        menu("shop-prod", "Delete…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap asked\n");
    assertTrue(c.tree("asked").contains("before shop-prod disconnects."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(null, app.state().deleting);
    assertEquals(1, app.state().profiles.size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  @Test
  void aFailedDdlInsideTheTransactionHasCommittedOnMariaDb() throws Exception {
    servers.statement(
        "CREATE TABLE stock (id int)",
        List.of(FakeServers.serverError(Failure.Kind.OTHER, "Table 'stock' already exists")));
    var app = open(INVENTORY);
    c.run(app, type(";\nCREATE TABLE stock (id int)") + RUN + "snap ddl\n");
    String ddl = c.tree("ddl");
    assertTrue(servers.calls.contains("transaction " + MARIA), servers.calls.toString());
    assertTrue(badge(ddl).contains("No transaction"), ddl);
    assertTrue(line(ddl, "#commit").contains("disabled"), ddl);
  }

  // The second review's findings, each as it was met.

  /** Moves the console of this title into a window of its own. */
  static String tearOff(String title) {
    return "press role=tab label=\"" + title + "\" right\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n";
  }

  static String in(String window) {
    return "window \"" + window + "\"\n";
  }

  /** New 1: whatever became of the session's first call, the mode chosen is the session's first. */
  @Test
  void aCancelledFirstSetupStillLeavesTheNextStatementInManual() throws Exception {
    var app = k.console(SHOP);
    c.run(app, CatalogueTest.open("shop-prod") + SETTLE + "press #sql\n" + MANUAL);
    var open = "open " + PG + "/shop";
    var opening = servers.hold(open);
    c.run(app, type(PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.stream().filter(open::equals).count() == 2);
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\n");
    opening.countDown();
    c.run(app, SETTLE + "press #sql\n" + RUN + "snap retried\n");
    String retried = c.tree("retried");
    var calls = servers.calls;
    int off = calls.indexOf("autocommit " + PG + " off");
    int pay = calls.indexOf("run " + PG + " " + PAY);
    assertTrue(off >= 0 && off < pay, calls.toString());
    assertTrue(badge(retried).contains("Transaction open"), retried);
    assertTrue(line(retried, "role=radio label=\"Manual\"").contains("checked"), retried);
  }

  /** New 2: a statement begun while Save's keyring worked stops the save at the moment of it. */
  @Test
  void aStatementBegunWhileSaveWritesTheKeyringStopsTheSave() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Edit…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    // The edit is open; the console has a Manual session again, with nothing at stake.
    c.run(app, in(window) + MANUAL + "press #sql\n" + RUN + COMMIT_BUTTON);
    assertFalse(k.tab(app).atStake());
    c.secrets.gate = new CountDownLatch(1);
    c.run(
        app,
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"hunter2\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\n");
    assertTrue(app.state().dialog.changing, "the keyring works");
    // The console, in its own window, begins a transaction meanwhile.
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + PAY));
    c.secrets.gate.countDown();
    c.run(app, SETTLE + in(window) + OPENED + "snap asked\n");
    String asked = c.tree("shop-prod-shop-asked");
    assertTrue(asked.contains("before the changed connection is saved."), asked);
    assertTrue(app.state().dialog != null, "the edit stays open");
    assertEquals(SHOP.user(), app.state().profile(SHOP.id()).user());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertTrue(k.tab(app).connected());
    // Answered, the save goes on.
    c.run(app, in(window) + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertEquals(SHOP.user() + "2", app.state().profile(SHOP.id()).user());
    assertEquals(null, app.state().dialog);
  }

  /** New 2: so with Delete. */
  @Test
  void aStatementBegunWhileDeleteWorksStopsTheDelete() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + "press #sql\n" + RUN + COMMIT_BUTTON);
    assertFalse(k.tab(app).atStake());
    c.secrets.gate = new CountDownLatch(1);
    c.run(app, in("main") + "press role=button label=\"Delete\"\nframe\n");
    assertTrue(app.state().removing, "the keyring works");
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + PAY));
    c.secrets.gate.countDown();
    c.run(app, SETTLE + in(window) + OPENED + "snap asked\n");
    assertTrue(c.tree("shop-prod-shop-asked").contains("before shop-prod is deleted."));
    assertEquals(1, app.state().profiles.size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, in(window) + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().profiles.isEmpty());
  }

  /** New 3: a typed COMMIT is waited for, never cancelled into a rollback. */
  @Test
  void closingWhileATypedCommitRunsWaitsForIt() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, type(";\nCOMMIT") + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap waiting\n");
    String waiting = c.tree("waiting");
    assertTrue(waiting.contains("role=dialog label=\"Waiting for the commit\""), waiting);
    assertFalse(waiting.contains("#answer-rollback"), waiting);
    gate.countDown();
    c.run(app, SETTLE);
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /** New 3: a typed COMMIT cancelled, or whose connection was lost, has an unknown outcome. */
  @Test
  void aTypedCommitCancelledOrLostHasAnUnknownOutcome() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, type(";\nCOMMIT") + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertTrue(near(cancelled, "#doubt").contains("COMMIT: it was cancelled"), cancelled);
    gate.countDown();
    c.run(app, "press role=button label=\"Acknowledge\"\nframe\n" + SETTLE);
    servers.statement(
        "COMMIT",
        List.of(
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    c.run(app, "press #sql\nkey A control\n" + type(PAY + ";\nEND;\nCOMMIT") + RUN + "snap lost\n");
    String lost = c.tree("lost");
    assertTrue(near(lost, "#doubt").contains("COMMIT: the connection was lost while it ran"), lost);
    assertFalse(lost.contains(GONE_TEXT), "not the ordinary failure: " + lost);
  }

  static final String COMMIT_BUTTON = "press role=button label=\"Commit\"\nframe\n" + SETTLE;

  static final String GONE_TEXT = "The open transaction is gone with the connection";

  @Test
  void theStatementsThatMayEndATransactionGoByTheirFirstWord() {
    for (var sql : List.of(
        "COMMIT",
        "commit work",
        "ROLLBACK TO SAVEPOINT a",
        "END",
        "ABORT",
        "BEGIN",
        "START TRANSACTION",
        "PREPARE TRANSACTION 'x'",
        "CALL report()",
        "DO $$ BEGIN COMMIT; END $$",
        "EXECUTE stmt",
        "/* note */ COMMIT")) {
      assertTrue(ConsoleTab.mayEnd(sql, Provider.POSTGRESQL), sql);
      assertTrue(ConsoleTab.mayEnd(sql, Provider.MARIADB), sql);
    }
    for (var sql : List.of(
        "CREATE TABLE t (id int)",
        "ALTER TABLE t ADD c int",
        "TRUNCATE t",
        "SET autocommit=1",
        "LOCK TABLES t WRITE",
        "XA COMMIT 'x'")) {
      assertTrue(ConsoleTab.mayEnd(sql, Provider.MYSQL), sql);
    }
    // PostgreSQL's DDL is in the transaction; queries and changes end nothing.
    assertFalse(ConsoleTab.mayEnd("CREATE TABLE t (id int)", Provider.POSTGRESQL));
    for (var sql :
        List.of(PAY, ORDERS, "INSERT INTO t VALUES (1)", "WITH x AS (SELECT 1) SELECT 1")) {
      assertFalse(ConsoleTab.mayEnd(sql, Provider.MARIADB), sql);
    }
  }

  /** New 4: a Commit the answer chose that fails keeps the console, and says why. */
  @Test
  void aFailedCommitGivesUpThePendingCloseAndExit() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(FakeServers.serverError(
            Failure.Kind.CONSTRAINT, "insert or update violates foreign key constraint")));
    var app = open(SHOP);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\n" + OPENED + SETTLE
            + "snap failed\n");
    String failed = c.tree("failed");
    assertEquals(1, app.state().consoles(null).size(), "the close was given up");
    assertEquals(null, app.state().asked);
    assertTrue(line(failed, "#problem").contains("A constraint was violated"), failed);
    assertFalse(failed.contains("role=dialog"), failed);
    // And Exit.
    c.run(app, "press #sql\n" + RUN + EXIT + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(null, app.state().asked);
  }

  /** New 4: so where the COMMIT is not sent, as the server ended the transaction by itself. */
  @Test
  void aCommitNotSentGivesUpThePendingClose() throws Exception {
    var app = open(INVENTORY);
    servers.rollBackBehind(MARIA);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\n" + OPENED + SETTLE
            + "snap not\n");
    assertEquals(1, app.state().consoles(null).size());
    assertTrue(line(c.tree("not"), "#problem").contains("Not committed"), c.tree("not"));
    assertFalse(servers.calls.contains("run " + MARIA + " COMMIT"), servers.calls.toString());
  }

  /** New 5: closing the dialog whose Save asked gives its way out up. */
  @Test
  void discardingTheEditGivesUpTheSaveItsQuestionWaitsFor() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Edit…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + type("\n" + PAY) + RUN);
    c.run(
        app,
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=button label=\"Save\"\nframe\n" + OPENED);
    assertTrue(app.state().asked != null, "the console asks");
    c.run(
        app,
        in("main") + "key Escape\nframe\n" + OPENED + "press role=button label=\"Discard\"\nframe\n"
            + OPENED + SETTLE + in(window) + OPENED + "snap after\n");
    assertEquals(null, app.state().asked);
    assertFalse(c.tree("shop-prod-shop-after").contains("role=dialog"));
    assertEquals(SHOP.user(), app.state().profile(SHOP.id()).user());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /** New 5: so with the delete dialog's Cancel. */
  @Test
  void cancellingTheDeleteGivesUpTheDeleteItsQuestionWaitsFor() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + type("\n" + PAY) + RUN);
    c.run(app, in("main") + "press role=button label=\"Delete\"\nframe\n" + OPENED);
    assertTrue(app.state().asked != null, "the console asks");
    c.run(
        app,
        in("main") + "press role=button label=\"Cancel\"\nframe\n" + OPENED + SETTLE + in(window)
            + OPENED + "snap after\n");
    assertEquals(null, app.state().asked);
    assertFalse(c.tree("shop-prod-shop-after").contains("role=dialog"));
    assertEquals(1, app.state().profiles.size());
  }

  /** New 6: a later Cancel gives up an earlier Exit that waited for another console's Commit. */
  @Test
  void aSecondExitsCancelGivesUpTheFirst() throws Exception {
    var app = k.console(SHOP);
    var b = "shop-prod: shop (2)";
    c.run(
        app,
        type(COUNT)
            + RUN
            + menu("shop-prod", "New console")
            + MANUAL
            + type(PAY)
            + RUN
            + tearOff(b));
    var commit = servers.hold(COMMITTING);
    // Exit asks B, whose Commit is held.
    c.run(app, in("main") + EXIT + in(b) + "press #answer-commit\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    // A runs a statement meanwhile; a second Exit asks A, and is cancelled.
    var count = "run " + PG + " " + COUNT;
    var held = servers.hold(count);
    c.run(app, in("main") + "press #sql\nkey Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.stream().filter(count::equals).count() == 2);
    c.run(app, in("main") + EXIT + "press #answer-cancel\nframe\n" + OPENED);
    held.countDown();
    commit.countDown();
    c.run(app, SETTLE + SETTLE);
    assertFalse(app.windows().all().isEmpty(), "the Cancel gave both Exits up");
    assertEquals(null, app.state().asked);
  }

  /** New 7: an outcome in doubt guards every way out until the user has read it. */
  @Test
  void anUnknownOutcomeStaysUntilAcknowledged() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    var app = open(SHOP);
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE);
    assertFalse(k.tab(app).connected());
    var close = "press #close-0\nframe\n" + OPENED;
    c.run(
        app, close + "snap first\npress #answer-cancel\nframe\n" + OPENED + close + "snap again\n");
    assertTrue(c.tree("again").contains("role=dialog label=\"The outcome is not known\""));
    // Another statement does not clear it.
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + "press #sql\n" + RUN + "snap ran\n");
    assertTrue(c.tree("ran").contains("#doubt"), c.tree("ran"));
    c.run(app, "press role=button label=\"Acknowledge\"\nframe\n" + OPENED + "snap read\n");
    assertFalse(c.tree("read").contains("#doubt"), c.tree("read"));
    c.run(app, "press role=tab label=\"Messages\"\nframe\nsnap messages\n");
    assertTrue(c.tree("messages").contains("Outcome not known"), c.tree("messages"));
    // What asks now is the transaction the statement began, not the outcome that was read.
    c.run(app, close + "snap open\n");
    assertTrue(c.tree("open").contains("role=dialog label=\"Commit the transaction?\""));
    c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /** A probe's answer or failure that arrives after something newer was learned is dropped. */
  @Test
  void aProbeAnsweringLateIsDropped() throws Exception {
    var app = open(INVENTORY);
    var tab = k.tab(app);
    var held = servers.hold("transaction " + MARIA);
    long asked = probes(MARIA);
    c.run(app, type(";\n" + MISSING) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked);
    var probe = tab.probing;
    held.countDown();
    c.run(app, SETTLE + type(";\n" + PAY) + RUN);
    assertEquals(Transaction.OPEN, tab.transaction);
    // As if their answers came only now, after the statement's.
    tab.probed(probe, Transaction.NONE);
    tab.probed(
        app.state(), probe, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    assertEquals(Transaction.OPEN, tab.transaction);
    assertTrue(tab.connected());
  }

  /**
   * The pictures for a review, light and dark, into the folder that {@code -Dreview=<folder>}
   * names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void pictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var app = k.console(SHOP);
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    Script.run(
        type(COUNT) + RUN + "snap console-auto-" + look + "\n" + MANUAL + OPENED
            + "snap console-manual-" + look + "\n" + type(";\n" + PAY) + RUN
            + "snap console-open-" + look + "\n" + "press #close-0\nframe\n" + OPENED
            + "snap console-question-" + look + "\n" + "press #answer-cancel\nframe\n" + OPENED
            + type(";\n" + MISSING) + RUN + "snap console-failed-" + look + "\n",
        app,
        out);
    for (var name : List.of(
        "console-auto", "console-manual", "console-open", "console-question", "console-failed")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }

  /** The pictures of what is not known, light and dark, as {@link #pictures}. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void picturesOfWhatIsNotKnown(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    var maria = k.console(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        type(CALL) + RUN + "snap console-may-be-open-" + look + "\npress #close-0\nframe\n" + OPENED
            + "snap console-may-be-open-question-" + look + "\n",
        maria,
        out);
    servers.statement(
        "COMMIT",
        List.of(
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    var pg = open(SHOP);
    pg.state().theme = theme;
    var gate = servers.hold(COMMITTING);
    Script.run("press role=button label=\"Commit\"\nframe\nframe\n", pg, out);
    eventually(() -> servers.calls.contains(COMMITTING));
    Script.run("press #close-0\nframe\n" + OPENED + "snap console-waiting-" + look + "\n", pg, out);
    gate.countDown();
    Script.run(
        SETTLE + OPENED + "snap console-unknown-outcome-" + look + "\n"
            + "press #answer-cancel\nframe\n" + OPENED + "snap console-doubt-" + look + "\n",
        pg,
        out);
    for (var name : List.of(
        "console-may-be-open",
        "console-may-be-open-question",
        "console-waiting",
        "console-unknown-outcome",
        "console-doubt")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
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
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.secret.Keyring;
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
  // A change that fails: not a plain read, so that what it left is asked of the server.
  static final String BROKEN = "UPDATE missing SET x = 1";
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
  CatalogTreeTest c;
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
            BROKEN,
            List.of(FakeServers.serverError(
                Failure.Kind.INVALID, "relation \"missing\" does not exist")))
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

  /** The notices of the app, one a line: connection, statement, what happened. */
  static String noticed(App<State> app) {
    return String.join(
        "\n",
        app.state().notices.stream()
            .map(n -> n.connection() + " · " + n.statement() + " · " + n.what())
            .toList());
  }

  static final String TAB = "role=tab label=\"shop-prod: shop\"";

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
    assertFalse(line(manual, TAB).contains("modified"), manual);
    // A change opens it; the connection turned auto-commit off first.
    c.run(app, type(PAY) + RUN + "snap changed\n");
    String changed = c.tree("changed");
    assertTrue(servers.calls.contains("autocommit " + PG + " off"), servers.calls.toString());
    assertTrue(badge(changed).contains("Transaction open · 1 statement"), changed);
    assertTrue(line(changed, TAB).contains("modified"), "the tab's dot: " + changed);
    assertFalse(line(changed, "#commit").contains("disabled"), changed);
    assertFalse(line(changed, "#rollback").contains("disabled"), changed);
    // Commit leaves the results as they are, and the console says so.
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap committed\n");
    String committed = c.tree("committed");
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(badge(committed).contains("No transaction"), committed);
    assertFalse(line(committed, TAB).contains("modified"), committed);
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
    c.run(app, type(";\n" + BROKEN) + RUN + "snap failed\n");
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
        asked.contains(
            "after 1 statement. Commit it or roll it back before the" + " console closes."),
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

  /** In a window of its own, Control+W and the palette's Close tab ask as the main window's do. */
  @Test
  void aTornOffWindowsKeysAndPaletteAskToo() throws Exception {
    var app = open(SHOP);
    var window = "shop-prod: shop";
    c.run(app, tearOff(window) + in(window) + "key W control\nframe\n" + OPENED + "snap key\n");
    String key = c.tree("shop-prod-shop-key");
    assertTrue(key.contains("role=dialog label=\"Commit the transaction?\""), key);
    c.run(
        app,
        in(window) + "press #answer-cancel\nframe\n" + OPENED + PaletteTest.PALETTE
            + "type \"close tab\"\nframe\nkey Enter\n" + PaletteTest.SHOWN + OPENED
            + "snap palette\n");
    String palette = c.tree("shop-prod-shop-palette");
    assertTrue(palette.contains("role=dialog label=\"Commit the transaction?\""), palette);
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
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
    assertTrue(app.state().catalog.connected(SHOP.id()));
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
    c.run(
        app,
        menu("shop-prod", "Disconnect") + OPENED + "press #answer-rollback\nframe\n" + OPENED
            + SETTLE + "snap ended\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertFalse(app.state().catalog.connected(SHOP.id()));
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
    var probe = servers.hold("transaction " + PG, 1); // past the statement's own
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
    assertEquals(Transaction.FAILED, link.status().transaction());
    var ended = ConsoleTab.finish(link, ConsoleTab.COMMIT, (sql, what, failure) -> {});
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

  /**
   * 4: a first Manual statement that still runs may have changed something: the console waits for
   * it, or goes on with the plain word that what the server did with it will not be known; it
   * offers no Rollback that would promise anything.
   */
  @Test
  void closingWhileTheFirstManualStatementRunsWaitsOrGoesOnPlainly() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN + MANUAL);
    var key = "run " + PG + " " + PAY;
    var gate = servers.hold(key);
    c.run(app, type("\n" + PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Waiting for " + PAY + "\""), asked);
    // The dump cuts the long text short: its end, of what will not be known, is in the picture.
    assertTrue(asked.contains(PAY + " still runs on shop-prod: shop. Wait"), asked);
    assertTrue(line(asked, "#answer-go-on").contains("label=\"Go on now\""), asked);
    assertFalse(asked.contains("#answer-commit"), asked);
    assertFalse(asked.contains("#answer-rollback"), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    gate.countDown();
    c.run(app, SETTLE + "snap stayed\n");
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    // Go on now: the connection ends at once, and the tab closes without a claim.
    var held = "run " + PG + " " + BROKEN;
    servers.hold(held);
    c.run(app, type(";\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(held));
    c.run(
        app, "press #close-0\nframe\n" + OPENED + "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
  }

  /**
   * Finding 5 of the first review, and the third review's 4 and 5: a statement that is not a plain
   * read, as a procedure that begins a transaction and gives rows, is read to its end at once, so
   * that no execution of it is left open and what it left is known.
   */
  @Test
  void aProcedureIsReadToItsEndSoWhatItLeftIsKnown() throws Exception {
    var app = k.console(INVENTORY);
    c.run(app, type(CALL) + RUN + "snap read\n");
    String read = c.tree("read");
    assertTrue(badge(read).contains("Transaction open"), read);
    assertTrue(near(read, "#result-status").contains("· 250 rows\""), read);
    assertTrue(
        servers.calls.contains("next " + MARIA + " " + CALL + " 200"), servers.calls.toString());
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("role=dialog label=\"Commit the transaction?\""));
  }

  /** 6: a question to the server that fails leaves the connection and an unknown transaction. */
  @Test
  void aFailedProbeLeavesTheConnectionAndTheTransactionUnknown() throws Exception {
    var app = open(INVENTORY);
    servers.fail(
        "transaction " + MARIA, FakeServers.queryFailure(QueryException.Failure.CANCELLED));
    c.run(app, type(";\n" + BROKEN) + RUN + "snap unknown\n");
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

  /** 6: a probe that fails late does not end the session of what was asked after it. */
  @Test
  void aProbeThatFailsLateLeavesTheSession() throws Exception {
    var app = open(INVENTORY);
    var probe = servers.hold("transaction " + MARIA, 1); // past the statement's own
    long asked = probes(MARIA);
    c.run(app, type(";\n" + BROKEN) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked); // held: what the statement left is asked
    // The next statement waits behind the probe on the lane.
    c.run(app, type(";\n" + PAY) + "key Enter control\nframe\nframe\n");
    servers.fail("transaction " + MARIA, FakeServers.queryFailure(QueryException.Failure.OTHER));
    probe.countDown();
    c.run(app, SETTLE + "snap after\n");
    String after = c.tree("after");
    // It could not make sure of the session's mode, so the next one was not sent.
    var pay = "run " + MARIA + " " + PAY;
    assertEquals(1, servers.calls.stream().filter(pay::equals).count(), servers.calls.toString());
    assertTrue(badge(after).contains("Transaction may be open"), after);
    assertTrue(k.tab(app).connected());
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
    servers.heal("transaction " + MARIA);
    c.run(app, "press #sql\n" + RUN + "snap again\n");
    assertTrue(badge(c.tree("again")).contains("Transaction open"), c.tree("again"));
  }

  /** 7: the tree's connection is not the console's: a color while it is lost ends nothing. */
  @Test
  void aColorWhileTheTreeIsLostLeavesTheConsoleAlone() throws Exception {
    var app = open(SHOP);
    servers.fail(
        "tables " + PG + "/shop.public",
        FakeServers.notRead(CatalogException.Failure.CONNECTION_LOST));
    c.run(app, CatalogTreeTest.open("shop") + SETTLE + CatalogTreeTest.open("public") + SETTLE);
    assertFalse(app.state().catalog.active(SHOP.id()), "the tree's connection is lost");
    app.state().put(SHOP.withColor(Color.RED));
    // And as the tree's connection is given up, as when its password is not given.
    app.state().catalog.drop(app.state(), SHOP.id());
    c.run(app, "frame\n" + SETTLE + "snap colored\n");
    String colored = c.tree("colored");
    assertFalse(colored.contains("role=dialog"), colored);
    assertTrue(k.tab(app).connected());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /**
   * Third review, 7: an Exit that waits for a save is the one pending act, armed: it locks every
   * console until it is done, so that nothing can come between and nothing older can resume later.
   */
  @Test
  void anExitThatWaitsForASaveLocksEveryConsole() throws Exception {
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
    // The console, in its own window, starts nothing meanwhile, and says why.
    c.run(app, window + type("\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    String locked = c.tree("shop-prod-shop-locked");
    assertTrue(line(locked, "#locked").contains("HooriDB is closing"), locked);
    assertTrue(line(locked, "#run ").contains("disabled"), locked);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
  }

  /** Third review, 7: an Exit whose question was cancelled is over; a later save resumes nothing. */
  @Test
  void anExitCancelledAtItsQuestionIsOverForGood() throws Exception {
    var app = open(SHOP);
    c.run(app, EXIT + "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().pending);
    app.state().put(SHOP.withColor(Color.GREEN)); // a save of the connections, which settles
    c.run(app, "frame\n" + SETTLE + "press #sql\n" + RUN + COMMIT_BUTTON + SETTLE);
    assertFalse(app.windows().all().isEmpty());
  }

  /** 9: the switch the question asked about goes on to its own database once nothing is open. */
  @Test
  void aSwitchAskedWhileTheServerWasAskedGoesToItsDatabase() throws Exception {
    var app = open(INVENTORY);
    var probe = servers.hold("transaction " + MARIA, 1); // past the statement's own
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
    // Exit waits for the session it ends to say how it closed.
    c.run(app, type(";\n" + PAY) + RUN + EXIT + SETTLE);
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
    assertTrue(app.state().catalog.connected(SHOP.id()));
    assertEquals(null, app.state().dialog, "no edit before the console's answer");
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, edit + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().dialog != null, "the edit goes on");
    assertFalse(k.tab(app).connected());
    // A color leaves the console's session as it is.
    var colored = open(SHOP);
    colored.state().put(SHOP.withColor(Color.BLUE));
    c.run(colored, "frame\n" + SETTLE);
    assertTrue(k.tab(colored).connected());
    assertEquals(Transaction.OPEN, k.tab(colored).transaction);
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
    c.run(app, CatalogTreeTest.open("shop-prod") + SETTLE + "press #sql\n" + MANUAL);
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

  /**
   * Third review, 6: once the questions are answered, Save locks the connection's consoles until it
   * is done, so that nothing comes between the answers and the act, and no keyring step is left half
   * applied by a question at the act.
   */
  @Test
  void aSaveLocksItsConsolesUntilItIsDone() throws Exception {
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
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    String locked = c.tree("shop-prod-shop-locked");
    assertTrue(line(locked, "#locked").contains("shop-prod is being saved"), locked);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE + in(window) + OPENED + "snap saved\n");
    assertFalse(c.tree("shop-prod-shop-saved").contains("role=dialog"));
    assertEquals(SHOP.user() + "2", app.state().profile(SHOP.id()).user());
    assertEquals(null, app.state().dialog);
    assertEquals(null, app.state().pending);
    assertFalse(k.tab(app).connected(), "its session, opened with what changed, ended");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
  }

  /** Third review, 6: so with Delete, whose password then goes for good only with the profile. */
  @Test
  void aDeleteLocksItsConsolesUntilItIsDone() throws Exception {
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
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    assertTrue(
        line(c.tree("shop-prod-shop-locked"), "#locked").contains("shop-prod is being deleted"));
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
    assertTrue(app.state().profiles.isEmpty());
    assertEquals(null, app.state().pending);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
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
    assertTrue(waiting.contains("role=dialog label=\"Waiting for COMMIT\""), waiting);
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

  /** What the console takes for a plain read, per provider; anything else may change data. */
  @Test
  void onlyPlainReadsAreTakenForReads() {
    for (var provider : Provider.values()) {
      for (var sql : List.of(
          "SELECT * FROM orders",
          "select 1",
          "/* note */ SELECT 1",
          "(SELECT 1)",
          "SHOW search_path",
          "VALUES (1)",
          "TABLE orders")) {
        assertTrue(ConsoleTab.reads(sql, provider), provider + ": " + sql);
      }
      for (var sql : List.of(
          PAY,
          "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed",
          "WITH x AS (SELECT 1) SELECT 1",
          "CALL report()",
          "COMMIT",
          "ROLLBACK TO SAVEPOINT a",
          "BEGIN",
          "SET autocommit=1",
          "CREATE TABLE t (id int)",
          "SELECT * INTO copy FROM orders",
          "EXPLAIN ANALYZE DELETE FROM orders",
          // The fourth review's F4: ANALYZE spelled so that no pattern finds it.
          "EXPLAIN (U&\"\\0061nalyze\" true) UPDATE orders SET paid = true",
          "EXPLAIN SELECT 1",
          "DESCRIBE orders",
          "DESC orders",
          "/*! COMMIT */",
          "/*M! COMMIT */",
          "IF 1 THEN COMMIT; END IF",
          "SHUTDOWN",
          "",
          "DO $$ BEGIN COMMIT; END $$")) {
        assertFalse(ConsoleTab.reads(sql, provider), provider + ": " + sql);
      }
    }
    // What ends the transaction typed, as the buttons do; not a rollback to a savepoint.
    for (var sql :
        List.of("COMMIT", "commit work", "END", "ABORT", "ROLLBACK", "COMMIT AND CHAIN")) {
      assertTrue(ConsoleTab.ends(sql), sql);
    }
    for (var sql :
        List.of("ROLLBACK TO SAVEPOINT a", "rollback work to a", "/* x */ COMMIT", PAY)) {
      assertFalse(ConsoleTab.ends(sql), sql);
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
    assertEquals(null, app.state().pending);
    assertTrue(line(failed, "#problem").contains("A constraint was violated"), failed);
    assertFalse(failed.contains("role=dialog"), failed);
    // And Exit.
    c.run(app, "press #sql\n" + RUN + EXIT + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(null, app.state().pending);
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
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "key Escape\nframe\n" + OPENED + "press role=button label=\"Discard\"\nframe\n"
            + OPENED + SETTLE + in(window) + OPENED + "snap after\n");
    assertEquals(null, app.state().pending);
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
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "press role=button label=\"Cancel\"\nframe\n" + OPENED + SETTLE + in(window)
            + OPENED + "snap after\n");
    assertEquals(null, app.state().pending);
    assertFalse(c.tree("shop-prod-shop-after").contains("role=dialog"));
    assertEquals(1, app.state().profiles.size());
  }

  /**
   * Third review, 8, and the second review's 6: one act waits at most. An Exit asked again while
   * the first waits on another console is the same act, now asking that console; its Cancel ends
   * the Exit for good, and the first console's Commit, when it ends, resumes nothing.
   */
  @Test
  void anExitCancelledAtAnyConsoleIsOverForGood() throws Exception {
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
    // A begins a change meanwhile; a second Exit asks A, and is cancelled.
    var change = "run " + PG + " " + BROKEN;
    var held = servers.hold(change);
    c.run(app, in("main") + type(";\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(change));
    c.run(app, in("main") + EXIT + "snap second\n");
    assertTrue(c.tree("second").contains("role=dialog label=\"Waiting for " + BROKEN + "\""));
    c.run(app, in("main") + "press #answer-cancel\nframe\n" + OPENED);
    held.countDown();
    commit.countDown();
    c.run(app, SETTLE + SETTLE);
    assertFalse(app.windows().all().isEmpty(), "the Cancel gave the Exit up");
    assertEquals(null, app.state().pending);
  }

  /** Third review, 8: another act that would ask is refused, visibly, while one waits. */
  @Test
  void anotherActIsRefusedWhileOneWaits() throws Exception {
    var app = open(SHOP);
    var b = "shop-prod: shop (2)";
    c.run(app, menu("shop-prod", "New console") + MANUAL + type(PAY) + RUN + tearOff(b));
    // Closing B's tab asks in B's window; an Exit from the main window meanwhile is refused.
    c.run(app, in(b) + "press #close-0\nframe\n" + OPENED);
    var pending = app.state().pending;
    assertTrue(pending != null && pending.what().equals("the console closes"));
    c.run(app, in("main") + EXIT + "snap refused\n");
    assertTrue(app.state().pending == pending, "not replaced");
    assertTrue(line(c.tree("refused"), "#message").contains("waits for its answer"));
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
    var held = servers.hold("transaction " + MARIA, 1); // past the statement's own
    long asked = probes(MARIA);
    c.run(app, type(";\n" + BROKEN) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked);
    var probe = tab.probing;
    held.countDown();
    c.run(app, SETTLE + type(";\n" + PAY) + RUN);
    assertEquals(Transaction.OPEN, tab.transaction);
    // As if their answers came only now, after the statement's.
    tab.probed(probe, new Status(Transaction.NONE, false));
    tab.probed(
        app.state(), probe, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    assertEquals(Transaction.OPEN, tab.transaction);
    assertTrue(tab.connected());
  }

  // The third review's findings, each as it was met.

  /** 1: a driver that cannot say the server's auto-commit makes Manual unavailable, not pretend. */
  @Test
  void aDriverThatCannotReportMakesManualUnavailable() throws Exception {
    servers.cannotReport(MARIA);
    var app = k.console(INVENTORY);
    c.run(app, MANUAL + type(PAY) + RUN + "snap refused\n");
    String refused = c.tree("refused");
    assertFalse(servers.calls.contains("run " + MARIA + " " + PAY), servers.calls.toString());
    assertTrue(line(refused, "#problem").contains("Not run"), refused);
    assertTrue(line(refused, "#manual-unavailable").contains("Manual is unavailable"), refused);
    assertTrue(line(refused, "role=radio label=\"Auto\"").contains("checked"), refused);
    assertTrue(line(refused, "#mode").contains("disabled"), refused);
    // Auto runs, and what it leaves is asked about, as the server cannot say.
    c.run(app, "press #sql\n" + RUN + "snap auto\n");
    assertTrue(servers.calls.contains("run " + MARIA + " " + PAY), servers.calls.toString());
    assertTrue(badge(c.tree("auto")).contains("Transaction may be open"), c.tree("auto"));
  }

  static final String ON_AND_FAIL = "CALL on_and_fail()";

  /**
   * 2: a statement that turned the server's auto-commit on and then failed: what the console
   * believes of the mode comes from the server, not the driver's stale idea, and it says so.
   */
  @Test
  void aFailedStatementThatTurnedAutoCommitOnIsFollowedFromTheServer() throws Exception {
    servers
        .statement(ON_AND_FAIL, List.of(FakeServers.serverError(Failure.Kind.OTHER, "after")))
        .turnsAutoCommitOn(ON_AND_FAIL);
    var app = open(INVENTORY);
    c.run(app, type(";\n" + ON_AND_FAIL) + RUN + "snap after\n");
    String after = c.tree("after");
    assertTrue(line(after, "role=radio label=\"Auto\"").contains("checked"), after);
    assertFalse(after.contains("#transaction"), "committed as it was turned on: " + after);
    c.run(app, "press role=tab label=\"Messages\"\nframe\nsnap messages\n");
    assertTrue(c.tree("messages").contains("The session's auto-commit is on"), c.tree("messages"));
  }

  /**
   * 3a: an UPDATE in Auto whose answer does not come: closing offers no Rollback that would claim
   * to undo it, and a later Rollback does not say Rolled back while its outcome is in doubt.
   */
  @Test
  void aChangeWhoseAnswerDidNotComeIsNeverCalledRolledBack() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    var key = "run " + PG + " " + PAY;
    servers.hold(key);
    c.run(app, type("\n" + PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertFalse(asked.contains("#answer-rollback"), asked);
    assertTrue(asked.contains("#answer-go-on"), asked);
    // Cancelled instead: what it did is in doubt, and in Auto nothing is offered to undo it.
    c.run(
        app,
        "press #answer-cancel\nframe\n" + OPENED + "press role=button label=\"Cancel\"\nframe\n"
            + SETTLE + "snap after\n");
    String after = c.tree("after");
    assertTrue(near(after, "#doubt").contains(PAY + ": it was cancelled"), after);
    assertFalse(after.contains("#rollback"), after);
    // In Manual, a Rollback while one is in doubt runs, and does not claim to have undone it.
    servers.openAll();
    var manual = open(SHOP);
    var change = "UPDATE orders SET paid = false";
    servers.statement(change, List.of(new Result.Count(3)));
    servers.hold("run " + PG + " " + change);
    c.run(manual, type(";\n" + change) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + change));
    c.run(
        manual,
        "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "press #rollback\nframe\n" + SETTLE
            + "snap rolled\n");
    String rolled = c.tree("rolled");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertFalse(rolled.contains("\"Rolled back\""), rolled);
    assertTrue(line(rolled, "#said").contains("ROLLBACK ran; what is in doubt still is"), rolled);
  }

  static final String COMMIT_ROWS = "CALL commit_rows()";

  /** 4: a connection lost while a procedure's later rows are read leaves its outcome in doubt. */
  @Test
  void aConnectionLostInALaterPortionLeavesTheOutcomeInDoubt() throws Exception {
    servers.statement(
        COMMIT_ROWS,
        List.of(
            FakeServers.made(250, "id: bigint"),
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    var app = k.console(INVENTORY);
    c.run(app, type(COMMIT_ROWS) + RUN + "snap lost\n");
    String lost = c.tree("lost");
    assertTrue(
        near(lost, "#doubt").contains(COMMIT_ROWS + ": the connection was lost while it ran"),
        lost);
    assertTrue(servers.calls.contains("next " + MARIA + " " + COMMIT_ROWS + " 200"));
  }

  static final String WRITING_WITH =
      "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed";

  /** 5: PostgreSQL's WITH that writes is read to its end: nothing of it is given up on a close. */
  @Test
  void aWritingWithIsReadToItsEndAndNeverGivenUp() throws Exception {
    servers.statement(WRITING_WITH, List.of(FakeServers.made(250, "id: bigint")));
    var app = k.console(SHOP);
    c.run(app, type(WRITING_WITH) + RUN + "snap read\n");
    assertTrue(near(c.tree("read"), "#result-status").contains("· 250 rows\""), c.tree("read"));
    c.run(app, "press #close-0\nframe\n" + OPENED);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing at stake: Auto, read to its end");
    assertFalse(
        servers.calls.contains("close " + PG + " " + WRITING_WITH), servers.calls.toString());
  }

  /** A typed COMMIT with chained completion leaves an empty transaction, as the button does. */
  @Test
  void aTypedChainedCommitLeavesNothingAtStake() throws Exception {
    var app = k.console(INVENTORY);
    c.run(app, type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN + type(";\nCOMMIT") + RUN);
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, "press #close-0\nframe\n" + OPENED);
    assertTrue(app.state().consoles(null).isEmpty(), "the chained transaction holds nothing");
  }

  /** A ROLLBACK TO a savepoint leaves what came before it, which still asks. */
  @Test
  void aRollbackToASavepointLeavesTheTransaction() throws Exception {
    servers.statement("SAVEPOINT a", List.of(new Result.Count(0)));
    var app = open(SHOP);
    c.run(app, type(";\nSAVEPOINT a;\nROLLBACK TO SAVEPOINT a") + RUN_ALL);
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("role=dialog label=\"Commit the transaction?\""));
  }

  /** What giving up the rest of a statement's rows failed with, the console says. */
  @Test
  void aFailureAtGivingUpIsShown() throws Exception {
    servers.failsAtGiveUp(
        ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, "the commit at give-up failed"));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS + ";\n" + COUNT) + RUN_ALL + "snap given\n");
    assertTrue(servers.calls.contains("close " + PG + " " + ORDERS), servers.calls.toString());
    // The fifth review's first finding: what came after it is not sent, and the run stops.
    assertFalse(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
    assertTrue(line(c.tree("given"), "#problem").contains("Not run"), c.tree("given"));
    c.run(app, "press role=tab label=\"Messages\"\nframe\n" + OPENED + "snap messages\n");
    assertTrue(
        c.tree("messages").contains(ConsoleTab.ENDED + "the commit at give-up failed"),
        c.tree("messages"));
    assertTrue(noticed(app).contains(ConsoleTab.ENDED + "the commit at give-up failed"));
  }

  /**
   * An open transaction is at stake whatever ran in it: after only a read in Manual, closing asks,
   * without claiming that anything changed.
   */
  @Test
  void aTransactionOpenAfterOnlyAReadInManualAsks() throws Exception {
    for (var profile : List.of(SHOP, INVENTORY)) {
      var app = k.console(profile);
      // A session the server said had nothing open, then a read in Manual.
      c.run(app, type(COUNT + ";") + RUN + MANUAL + type("\n" + COUNT) + RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction, profile.name());
      c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
      String asked = c.tree("asked");
      assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
      assertTrue(asked.contains("has a transaction open, after 1 statement. Commit it or"), asked);
      assertFalse(asked.contains("changes"), asked);
      c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE);
      assertTrue(app.state().consoles(null).isEmpty(), profile.name());
    }
    // In Auto, with none open, a read with rows left still asks nothing.
    var auto = k.console(SHOP);
    c.run(auto, type(ORDERS) + RUN + "press #close-0\nframe\n" + OPENED);
    assertTrue(auto.state().consoles(null).isEmpty());
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

  /** The pictures of what the third round changed, light and dark, as {@link #pictures}. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void picturesOfTheThirdRound(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    // Manual unavailable, with a driver that cannot say the server's auto-commit.
    servers.cannotReport(MARIA);
    var maria = k.console(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        MANUAL + type(PAY) + RUN + "snap console-manual-unavailable-" + look + "\n", maria, out);
    // The close question while a change runs: wait, or go on with the plain word.
    var pg = k.console(SHOP);
    pg.state().theme = theme;
    Script.run(type(COUNT + ";") + RUN, pg, out);
    var key = "run " + PG + " " + PAY;
    var gate = servers.hold(key);
    Script.run(type("\n" + PAY) + "key Enter control\nframe\nframe\n", pg, out);
    eventually(() -> servers.calls.contains(key));
    Script.run("press #close-0\nframe\n" + OPENED + "snap console-go-on-" + look + "\n", pg, out);
    Script.run("press #answer-cancel\nframe\n" + OPENED, pg, out);
    gate.countDown();
    Script.run(SETTLE, pg, out);
    // The locked console, while a save of its connection waits for the keyring.
    var app = k.console(SHOP);
    app.state().theme = theme;
    Script.run(
        type(COUNT + ";") + RUN + tearOff("shop-prod: shop") + in("main")
            + menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n"
            + OPENED + SETTLE + in("shop-prod: shop") + "press #sql\n" + RUN,
        app,
        out);
    c.secrets.gate = new CountDownLatch(1);
    Script.run(
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"hunter2\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\n" + in("shop-prod: shop")
            + "size 1200 760\nframe\nadvance 1\nframe\nsnap console-locked-" + look + "\n",
        app,
        out);
    c.secrets.gate.countDown();
    Script.run(SETTLE, app, out);
    for (var name : List.of("console-manual-unavailable", "console-go-on")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
    assertTrue(Files.exists(out.resolve("shop-prod-shop-console-locked-" + look + ".png")));
  }

  // The fourth review's findings, each as it was met.

  static final String NOTE = "SELECT note(id) FROM source"; // a read whose function writes

  /**
   * F1: PostgreSQL, Auto, a read left with rows unread holds core's own transaction, with
   * auto-commit off; Manual then is not taken from that, and the UPDATE does not commit by itself.
   */
  @Test
  void aReadLeftOpenInAutoDoesNotPassForManual() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    assertTrue(k.tab(app).outputs.getFirst().more, "rows left unread");
    c.run(app, MANUAL + type(";\n" + PAY) + RUN + "snap paid\n");
    var calls = servers.calls;
    int closed = calls.indexOf("close " + PG + " " + ORDERS);
    int off = calls.indexOf("autocommit " + PG + " off");
    int pay = calls.indexOf("run " + PG + " " + PAY);
    assertTrue(closed >= 0 && closed < off && off < pay, calls.toString());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertTrue(badge(c.tree("paid")).contains("Transaction open"), c.tree("paid"));
  }

  /**
   * F2: MariaDB, Manual, a read whose function writes and that has more rows than a portion is
   * read whole: the server's word on the transaction follows it, and closing asks.
   */
  @Test
  void aLongReadInManualIsReadWholeAndClosingAsks() throws Exception {
    servers.statement(NOTE, List.of(FakeServers.made(250, "id: bigint")));
    var app = k.console(INVENTORY);
    c.run(app, MANUAL + type(NOTE) + RUN + "snap read\n");
    String read = c.tree("read");
    assertTrue(near(read, "#result-status").contains("· 250 rows\""), read);
    assertTrue(line(read, "#run-status").contains(ConsoleTab.WHOLE), read);
    assertTrue(servers.calls.contains("next " + MARIA + " " + NOTE + " 200"), "in its one call");
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("role=dialog"), c.tree("asked"));
    assertEquals(1, app.state().consoles(null).size());
  }

  /** F3: a statement that waited for its connection is refused while a lock holds it. */
  @Test
  void aStatementThatWaitedForItsConnectionIsRefusedUnderALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + SETTLE + MANUAL);
    var opening = servers.hold("open " + PG + "/shop");
    c.run(app, type(PAY) + "key Enter control\nframe\nframe\n");
    assertTrue(k.tab(app).waiting);
    // As a Save of the connection does, once nothing of its consoles was at stake.
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", SHOP.id()));
    opening.countDown();
    c.run(app, SETTLE + "snap refused\n");
    String refused = c.tree("refused");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    assertTrue(line(refused, "#problem").contains("Not run"), refused);
    assertTrue(refused.contains("shop-prod is being saved"), refused);
    app.state().drop(save);
  }

  /** F3: Run all goes on to its next statement only through the same gate. */
  @Test
  void runAllStopsAtALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    var key = "run " + PG + " " + ORDERS;
    var held = servers.hold(key);
    // The first, COUNT again, runs to its end; its result starts the held one.
    c.run(
        app,
        type("\n" + ORDERS + ";\n" + PAY + ";")
            + "key Enter control shift\nframe\nawait\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", SHOP.id()));
    held.countDown();
    c.run(app, SETTLE + "snap stopped\n");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    assertTrue(line(c.tree("stopped"), "#problem").contains("Not run"), c.tree("stopped"));
    app.state().drop(save);
  }

  /** F3: and the grid's further rows are not read under a lock. */
  @Test
  void noFurtherRowsAreReadUnderALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", SHOP.id()));
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE);
    assertFalse(
        servers.calls.contains("next " + PG + " " + ORDERS + " 200"), servers.calls.toString());
    app.state().drop(save);
  }

  static final String EXPLAINED = "EXPLAIN (U&\"\\0061nalyze\" true) UPDATE orders SET paid = true";

  /** F4: an EXPLAIN may run what it explains: cancelled, its outcome is in doubt. */
  @Test
  void anExplainIsNoRead() throws Exception {
    servers.statement(EXPLAINED, List.of(FakeServers.made(3, "QUERY PLAN: text")));
    var app = k.console(SHOP);
    var key = "run " + PG + " " + EXPLAINED;
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    servers.hold(key);
    // The script's text escapes its backslash.
    c.run(app, type("\n" + EXPLAINED.replace("\\", "\\\\")) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    assertTrue(
        near(c.tree("cancelled"), "#doubt").contains("it was cancelled"), c.tree("cancelled"));
  }

  /**
   * F5: the drain keeps at most so many rows of a result, those beyond counted as they come, and
   * stops at an interrupt between portions, also where the driver did not notice it.
   */
  @Test
  void theDrainIsBoundedAndStopsAtAnInterrupt() throws Exception {
    var fields = List.of(
        new dev.hoori.db.core.data.Field("id", "bigint", dev.hoori.db.core.data.Kind.INTEGER));
    class Endless implements Servers.Run {
      int portions;
      final int interruptAt;

      Endless(int interruptAt) {
        this.interruptAt = interruptAt;
      }

      @Override
      public dev.hoori.db.core.execution.Portion next(int count) {
        portions++;
        if (portions == interruptAt) {
          Thread.currentThread().interrupt();
        }
        var rows = new java.util.ArrayList<List<Object>>();
        for (int i = 0; i < count; i++) {
          rows.add(List.of((long) i));
        }
        return portion(new Result.Rows(fields, (portions - 1L) * count, rows), portions < 50);
      }

      @Override
      public dev.hoori.db.core.execution.Abandoned close() {
        return dev.hoori.db.core.execution.Abandoned.NOTHING;
      }
    }
    var whole = new Endless(0);
    var ran = ConsoleTab.drained(whole, whole.next(200), 1000);
    var rows = (Result.Rows) ran.portion().results().getFirst();
    assertEquals(1000, rows.rows().size());
    assertEquals(List.of(50L * 200 - 1000), ran.dropped());
    assertEquals(50, whole.portions);
    var cut = new Endless(3);
    try {
      ConsoleTab.drained(cut, cut.next(200), 1000);
      throw new AssertionError("not stopped");
    } catch (InterruptedException e) {
      assertEquals(3, cut.portions, "no portion after the interrupt");
    } finally {
      Thread.interrupted();
    }
  }

  static dev.hoori.db.core.execution.Portion portion(Result result, boolean more) {
    return new dev.hoori.db.core.execution.Portion(
        List.of(result), List.of(), more, java.time.Duration.ZERO, Transaction.NONE, true);
  }

  static final String BIG = "CALL big()";

  /** F6: each result cut short says so on itself, and one result's rows leave the next its own. */
  @Test
  void everyCutResultSaysSo() throws Exception {
    servers.statement(
        BIG,
        List.of(
            FakeServers.made(ConsoleTab.KEPT + 1, "id: bigint"),
            FakeServers.made(50, "id: bigint"),
            new Result.Count(5)));
    var app = k.console(SHOP);
    c.run(app, type(BIG) + RUN + "snap big\n");
    String big = c.tree("big");
    assertTrue(near(big, "#result-status").contains("the first 100,000 rows of 100,001 rows"), big);
    var outputs = k.tab(app).outputs;
    assertEquals(3, outputs.size());
    assertEquals(ConsoleTab.KEPT, outputs.get(0).rows.size());
    assertEquals(50, outputs.get(1).rows.size());
  }

  /** F7: a read given up says nothing of what it changed, and one read whole is not given up. */
  @Test
  void aReadsFooterClaimsNothingOfChanges() throws Exception {
    servers.statement(NOTE, List.of(FakeServers.made(250, "id: bigint")));
    var app = k.console(SHOP);
    c.run(app, MANUAL + type(NOTE) + RUN + "press #rollback\nframe\n" + SETTLE + "snap rolled\n");
    String rolled = c.tree("rolled");
    assertFalse(rolled.contains("stands"), rolled);
    assertTrue(near(rolled, "#result-status").contains("· 250 rows\""), rolled);
  }

  /** F8: an Edit that waited on a question does not replace a dialog opened meanwhile. */
  @Test
  void anEditThatWaitedReplacesNoNewerDialog() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(app, MANUAL + type(PAY) + RUN + tearOff(window));
    c.run(
        app,
        in("main") + menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n"
            + OPENED);
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"New connection…\"\nframe\n" + OPENED
            + "press role=textbox label=\"Name\"\ntype \"kept\"\nframe\n");
    var newer = app.state().dialog;
    assertTrue(newer != null && newer.isNew, "the new connection's dialog");
    c.run(app, in(window) + "press #answer-rollback\nframe\n" + SETTLE + OPENED);
    assertEquals(newer, app.state().dialog);
    assertTrue(app.state().message.contains("was not opened for editing"), app.state().message);
  }

  /** F9: a Delete asked while an Edit of the same connection waits is refused, not put in place. */
  @Test
  void aDeleteDoesNotReplaceAWaitingEdit() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(app, MANUAL + type(PAY) + RUN + tearOff(window));
    c.run(
        app,
        in("main") + menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n"
            + OPENED);
    var edit = app.state().pending;
    c.run(
        app,
        in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED);
    assertEquals(edit, app.state().pending, "the edit still waits");
    c.run(app, in(window) + "press #answer-rollback\nframe\n" + SETTLE + OPENED);
    assertTrue(app.state().dialog != null, "the edit goes on");
    assertEquals(null, app.state().deleting);
  }

  /**
   * The commit of what a read changed, as its session ends with its tab, fails: the main window
   * says so until dismissed, with the connection and the statement.
   */
  @Test
  void aCommitThatFailsAsTheSessionEndsReachesTheMainWindow() throws Exception {
    servers.failsAtGiveUp(
        ORDERS, FakeServers.serverError(Failure.Kind.OTHER, "could not serialize access"));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    c.run(app, "press #close-0\nframe\n" + OPENED);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing asked: a read in Auto");
    eventually(() -> servers.calls.contains("close " + PG + " " + ORDERS));
    // Posted by the thread that closed the session, once it has.
    eventually(() -> {
      try {
        c.run(app, "await\nframe\n");
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return !app.state().notices.isEmpty();
    });
    c.run(app, "frame\nsnap told\n");
    String told = c.tree("told");
    assertTrue(near(told, "#notice-1").contains("shop-prod · " + ORDERS), told);
    assertTrue(noticed(app).contains("could not serialize access"), noticed(app));
    c.run(app, "press #dismiss-1\nframe\nadvance 1\nframe\nsnap dismissed\n");
    assertFalse(c.tree("dismissed").contains("#notice-1"), c.tree("dismissed"));
    assertTrue(app.state().notices.isEmpty());
  }

  static final dev.hoori.db.core.execution.Message NOT_ROLLED_BACK =
      new dev.hoori.db.core.execution.Message(
          "Some non-transactional changed tables couldn't be rolled back", "HY000", 1196);

  /** MariaDB's and MySQL's warning that a rollback left changes is said, not "Rolled back". */
  @Test
  void aRollbackThatLeftChangesSaysSo() throws Exception {
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var app = open(INVENTORY);
    c.run(app, "press #rollback\nframe\n" + SETTLE + "snap partly\n");
    String partly = c.tree("partly");
    assertTrue(line(partly, "#said").contains(ConsoleTab.PARTLY), partly);
    assertTrue(line(partly, "#problem").contains(ConsoleTab.PARTLY), partly);
    assertFalse(partly.contains("\"Rolled back\""), partly);
  }

  /** A keyring that does not answer can be given up, and says what that leaves. */
  @Test
  void aKeyringThatDoesNotAnswerCanBeGivenUp() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    c.secrets.gate = new CountDownLatch(1);
    c.run(
        app,
        "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"hunter2\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nsnap waits\n");
    assertTrue(line(c.tree("waits"), "role=button label=\"Cancel\"").contains("disabled"));
    assertTrue(app.state().pending != null, "the save holds its lock");
    c.run(app, "advance " + State.KEYRING_SECONDS + "\nframe\nsnap slow\n");
    assertTrue(c.tree("slow").contains("The system keyring does not answer"), c.tree("slow"));
    c.run(app, "press role=button label=\"Cancel\"\nframe\nsnap given\n");
    String given = c.tree("given");
    assertTrue(given.contains("the save was given up"), given);
    assertEquals(null, app.state().pending, "the lock ended");
    assertFalse(app.state().dialog.changing);
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
  }

  /** The pictures of what the fourth round changed, light and dark, as {@link #pictures}. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void picturesOfTheFourthRound(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    // Manual reads whole, and a result cut at what is kept says so on itself.
    servers.statement(
        BIG, List.of(FakeServers.made(ConsoleTab.KEPT + 1, "id: bigint"), new Result.Count(5)));
    var pg = k.console(SHOP);
    pg.state().theme = theme;
    Script.run(MANUAL + type(BIG) + RUN + "snap console-read-whole-" + look + "\n", pg, out);
    // A rollback that left a MyISAM table's change.
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var maria = open(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        "press #rollback\nframe\n" + SETTLE + "snap console-rolled-back-in-part-" + look + "\n",
        maria,
        out);
    // The commit of a read's change failed as its session ended: the main window says so.
    servers.failsAtGiveUp(
        ORDERS, FakeServers.serverError(Failure.Kind.OTHER, "could not serialize access"));
    var ended = k.console(SHOP);
    ended.state().theme = theme;
    Script.run(type(ORDERS) + RUN + "press #close-0\nframe\n" + OPENED, ended, out);
    eventually(() -> {
      try {
        Script.run("await\nframe\n", ended, out);
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return !ended.state().notices.isEmpty();
    });
    Script.run("frame\nsnap main-change-may-not-stand-" + look + "\n", ended, out);
    // A keyring that does not answer, and what giving it up leaves.
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    app.state().theme = theme;
    Script.run(CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE, app, out);
    c.secrets.gate = new CountDownLatch(1);
    Script.run(
        "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"hunter2\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nsnap dialog-keyring-slow-" + look + "\n"
            + "press role=button label=\"Cancel\"\nframe\nadvance 1\nframe\n"
            + "snap dialog-keyring-given-up-" + look + "\n",
        app,
        out);
    c.secrets.gate.countDown();
    Script.run(SETTLE, app, out);
    for (var name : List.of(
        "console-read-whole",
        "console-rolled-back-in-part",
        "main-change-may-not-stand",
        "dialog-keyring-slow",
        "dialog-keyring-given-up")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }

  // The fifth review's findings: who owns a failure once it has happened, and a keyring change
  // once it has started. Its first finding is aFailureAtGivingUpIsShown, and against PostgreSQL
  // ServersServerTest's aRunAllStopsWhereAReadsCommitFailed.

  static final String DUPLICATE = "duplicate key value violates unique constraint";

  /**
   * 2: the failure of giving up a read is the app's at once, and neither a Cancel of what came
   * after nor a close of the tab can lose it.
   */
  @Test
  void aGiveUpsFailureOutlivesWhatCameAfter() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    for (var after :
        List.of("press role=button label=\"Cancel\"\nframe\n", "press #close-0\nframe\n")) {
      var app = k.console(SHOP);
      c.run(app, type(ORDERS) + RUN);
      c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\nframe\n");
      eventually(() -> servers.calls.contains("close " + PG + " " + ORDERS));
      c.run(app, after + OPENED + SETTLE + "snap after\n");
      assertTrue(noticed(app).contains(ConsoleTab.ENDED + DUPLICATE), after + noticed(app));
      assertTrue(near(c.tree("after"), "#notice-1").contains(DUPLICATE), c.tree("after"));
      servers.calls.clear();
    }
  }

  /**
   * 3: a Rollback that the close question ran, and that rolled back only in part, stops the close:
   * the console stays and says so, and the app keeps it.
   */
  @Test
  void aRollbackInPartStopsTheCloseItAnswered() throws Exception {
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var app = open(INVENTORY);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-rollback\nframe\n" + OPENED + SETTLE
            + "snap partly\n");
    String partly = c.tree("partly");
    assertEquals(1, app.state().consoles(null).size(), "the close stopped");
    assertEquals(null, app.state().pending);
    assertTrue(line(partly, "#problem").contains(ConsoleTab.PARTLY), partly);
    assertTrue(noticed(app).contains(ConsoleTab.PARTLY), noticed(app));
  }

  /**
   * 4: a keyring change given up still runs, and what is asked of the keyring after it runs after
   * it: the password a save gave up on is not left behind by the delete that came later.
   */
  @Test
  void aKeyringChangeGivenUpRunsBeforeTheNextOne() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"first\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + OPENED
            + "press role=button label=\"Cancel\"\nframe\n" + OPENED
            + "press role=button label=\"Discard\"\nframe\n" + OPENED
            + menu("shop-prod", "Delete…") + OPENED
            + "press role=button label=\"Delete\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nsnap waiting\n");
    assertTrue(c.tree("waiting").contains("this one waits behind it"), c.tree("waiting"));
    late.countDown(); // the save given up writes now, as a native call can
    c.run(app, SETTLE + "snap deleted\n");
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "the delete came last");
    assertTrue(app.state().profiles.isEmpty());
  }

  /** 5: while the keyring is still being found, a Delete can be given up, and so can Exit. */
  @Test
  void aKeyringNeverFoundStrandsNeitherDeleteNorExit() throws Exception {
    var finding = new CountDownLatch(1);
    var app = c.app(
        servers,
        () -> {
          finding.await();
          return new Keyring.Available(c.secrets);
        },
        SHOP);
    c.ready(app, SHOP);
    // The load comes in; the keyring is looked for from then on, and not found.
    c.run(app, "size 1200 760\nframe\nawait\nframe\nframe\nadvance 1\nframe\n");
    c.run(
        app,
        menu("shop-prod", "Delete…") + OPENED + "press role=button label=\"Delete\"\nframe\n"
            + "advance " + State.KEYRING_SECONDS + "\nframe\nadvance 1\nframe\nsnap slow\n");
    String slow = c.tree("slow");
    assertTrue(slow.contains("The system keyring does not answer"), slow);
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + OPENED + "snap given\n");
    assertTrue(c.tree("given").contains("the deletion was given up"), c.tree("given"));
    assertEquals(null, app.state().pending);
    // Delete again, and Exit: the first gives the delete up once it is slow, the next goes.
    c.run(
        app,
        "press role=button label=\"Delete\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\n");
    app.state().requestClose(); // the window's close button, under the dialog
    c.run(app, "frame\n");
    assertFalse(app.windows().all().isEmpty(), "the first close gave up the delete");
    assertEquals(1, app.state().profiles.size());
    assertEquals(null, app.state().pending);
    app.state().requestClose();
    c.run(app, "frame\n");
    assertTrue(app.windows().all().isEmpty());
    finding.countDown();
  }

  /** 6: two sessions whose closing commits fail leave two notices, each readable. */
  @Test
  void twoClosingFailuresAreBothKept() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + menu("shop-prod", "New console") + SETTLE + type(ORDERS) + RUN);
    assertEquals(2, app.state().consoles(null).size());
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + SETTLE + "snap both\n");
    assertEquals(2, app.state().notices.size(), noticed(app));
    String both = c.tree("both");
    assertTrue(near(both, "#notice-1").contains(DUPLICATE), both);
    assertTrue(near(both, "#notice-2").contains(DUPLICATE), both);
  }

  /** 7: a switch to Manual gives up a read left with rows, which then says so and reads no more. */
  @Test
  void aSwitchThatGivesUpAReadLeavesItsGridCut() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + MANUAL);
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap scrolled\n");
    String scrolled = c.tree("scrolled");
    assertFalse(
        servers.calls.contains("next " + PG + " " + ORDERS + " 200"), servers.calls.toString());
    assertTrue(near(scrolled, "#result-status").contains("the rest was not read"), scrolled);
    assertEquals(Transaction.NONE, k.tab(app).transaction);
    assertEquals(null, k.tab(app).problem);
  }

  /** Exit waits for the sessions it ends; one that reports a failure keeps HooriDB open. */
  @Test
  void exitStaysOpenOnASessionsFailure() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + EXIT + SETTLE + "snap stayed\n");
    assertFalse(app.windows().all().isEmpty(), "stayed open");
    assertTrue(near(c.tree("stayed"), "#notice-1").contains(DUPLICATE), c.tree("stayed"));
    assertEquals(null, app.state().pending);
    c.run(app, EXIT + SETTLE);
    assertTrue(app.windows().all().isEmpty());
  }

  /** Exit waits for a session that does not say it closed only so long, then once more. */
  @Test
  void exitWaitsForASilentSessionOnlySoLong() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    servers.dead = true; // the held call ends only once its gate opens, interrupted or not
    var gate = servers.hold("run " + PG + " " + COUNT);
    c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + COUNT));
    c.run(app, EXIT + "advance " + State.EXIT_SECONDS + "\nframe\nsnap silent\n");
    assertFalse(app.windows().all().isEmpty());
    assertTrue(c.tree("silent").contains("did not say in time"), c.tree("silent"));
    c.run(app, EXIT);
    assertTrue(app.windows().all().isEmpty(), "the second Exit goes");
    gate.countDown();
  }

  // The sixth review: every outcome of a lane call reaches its console or the notices.

  /** Delivered, then the tab closed: the console showed it, and no notice repeats it. */
  @Test
  void anOutcomeTakenIsNoNotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(MISSING) + RUN + "press #close-0\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(app.state().notices.isEmpty(), noticed(app));
  }

  /** The lane had the outcome, the console had not taken it, and Cancel came: the app posts it. */
  @Test
  void anOutcomeThereAndNotTakenBecomesANotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    var tab = k.tab(app);
    c.run(app, type("\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE);
    assertTrue(noticed(app).contains("relation \"missing\" does not exist"), noticed(app));
    assertTrue(app.state().notices.getFirst().failure());
  }

  /** Cancel came first, the outcome after it: the lane posts it, as the console gave it up. */
  @Test
  void anOutcomeAfterItWasGivenUpBecomesANotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    servers.dead = true; // the held call answers once its gate opens, cancelled or not
    var key = "run " + PG + " " + BROKEN;
    var gate = servers.hold(key);
    c.run(app, type("\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\n");
    gate.countDown();
    c.run(app, SETTLE);
    assertTrue(noticed(app).contains("relation \"missing\" does not exist"), noticed(app));
  }

  /** A typed ROLLBACK that rolled back only in part, cancelled before the console took it. */
  @Test
  void aTypedRollbackInPartCancelledIsANotice() throws Exception {
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var app = open(INVENTORY);
    var tab = k.tab(app);
    c.run(app, type(";\n" + ConsoleTab.ROLLBACK) + "key Enter control\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE);
    assertTrue(noticed(app).contains(ConsoleTab.PARTLY), noticed(app));
  }

  static final String LAST = "SELECT note(id) FROM orders LIMIT 250";

  /**
   * The last portion of a read, whose own transaction's commit failed, there and not taken as the
   * tab closes: nothing asks at that close, and the app has it.
   */
  @Test
  void aReadsLastPortionFailingAtTheCloseIsANotice() throws Exception {
    servers.statement(
        LAST,
        List.of(
            FakeServers.made(250, "id: bigint"),
            FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE)));
    var app = k.console(SHOP);
    var tab = k.tab(app);
    c.run(app, type(LAST) + RUN + "scroll #grid row 180\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(app, "press #close-0\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing asked");
    assertTrue(noticed(app).contains(DUPLICATE), noticed(app));
  }

  /**
   * 3: Exit after the connections file failed to save goes without the file, as the user was
   * told, but not without the sessions' reports: one that fails keeps HooriDB open.
   */
  @Test
  void quittingWithoutTheFileStillWaitsForTheSessions() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    var file = dir.resolve("config").resolve(dev.hoori.db.core.connection.ProfileStore.FILE);
    Files.delete(file);
    Files.createDirectories(file); // the store cannot write here
    app.state().put(SHOP.withColor(Color.GREEN)); // a change to save
    c.run(app, SETTLE + "close\nframe\n" + SETTLE + "snap unsaved\n");
    assertTrue(c.tree("unsaved").contains("Close it again to quit without them."));
    assertFalse(app.windows().all().isEmpty());
    c.run(app, "close\nframe\n" + SETTLE + "snap reported\n");
    assertFalse(app.windows().all().isEmpty(), "the session's report keeps it open");
    assertTrue(noticed(app).contains(DUPLICATE), noticed(app));
    c.run(app, "close\nframe\n" + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "and then it goes, without the file");
  }

  /**
   * 4: a Rollback that keeps failing, the connection still there, does not keep the console: the
   * next question offers to end the connection, and the app keeps that it was.
   */
  @Test
  void aRollbackThatKeepsFailingCanBeLeft() throws Exception {
    servers.statement(
        ConsoleTab.ROLLBACK,
        List.of(FakeServers.serverError(Failure.Kind.OTHER, "Error writing file")));
    var app = open(INVENTORY);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertEquals(1, app.state().consoles(null).size(), "the failed Rollback stopped the close");
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap again\n");
    assertTrue(c.tree("again").contains("End the connection"), c.tree("again"));
    c.run(app, "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(noticed(app).contains(ConsoleTab.WENT_ON), noticed(app));
  }

  /** 4: a Commit the server does not answer can be left once it has kept the question waiting. */
  @Test
  void aCommitThatDoesNotAnswerCanBeLeft() throws Exception {
    var app = open(INVENTORY);
    var key = "run " + MARIA + " " + ConsoleTab.COMMIT;
    servers.hold(key);
    c.run(app, "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "frame\nsnap waiting\n");
    assertFalse(c.tree("waiting").contains("End the connection"), "not at once");
    c.run(app, "advance " + ConsoleTab.FIRM_SECONDS + "\nframe\n" + OPENED + "snap overdue\n");
    assertTrue(c.tree("overdue").contains("End the connection"), c.tree("overdue"));
    c.run(app, "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(noticed(app).contains(ConsoleTab.WENT_ON), noticed(app));
  }

  /**
   * 2: after a give-up whose delete and refresh both failed, the dialog does not know what the
   * keyring holds; choosing not to keep the password there then asks it to delete, not nothing.
   */
  @Test
  void anUnknownKeyringIsNotTakenForAnEmptyOne() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE + "snap kept\n");
    assertTrue(line(c.tree("kept"), "role=checkbox").contains("checked"), "it says it holds one");
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=textbox label=\"Password\"\ntype \"fresh\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + OPENED);
    c.secrets.fails = true; // the delete given up fails, and so does the refresh behind it
    late.countDown();
    c.run(app, SETTLE + "snap unknown\n");
    assertTrue(c.tree("unknown").contains("is not known"), c.tree("unknown"));
    assertTrue(c.secrets.stored.containsKey(SHOP.id()), "still there");
    c.secrets.fails = false;
    c.run(app, "press role=button label=\"Save\"\nframe\n" + SETTLE);
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "asked to delete, as chosen");
    assertEquals(null, app.state().dialog);
  }

  /**
   * 5: a keyring read that never returns keeps no connection from its password: after the
   * keyring's patience, the connection asks for it, and says why.
   */
  @Test
  void aKeyringReadThatNeverReturnsGoesToThePasswordQuestion() throws Exception {
    c.secrets.stored.put(SHOP.id(), ConnectionsTest.PASSWORD);
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    app.state().forgetPassword(SHOP.id()); // from the keyring, not this session
    var never = new CountDownLatch(1);
    c.secrets.late = never;
    c.run(
        app,
        CatalogTreeTest.START + CatalogTreeTest.open("shop-prod") + "frame\nadvance "
            + State.KEYRING_SECONDS + "\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("The system keyring did not answer"), asked);
    c.run(
        app,
        "press role=textbox label=\"Password\"\ntype \"" + ConnectionsTest.PASSWORD + "\"\n"
            + "press role=button label=\"Connect\"\nframe\n" + SETTLE);
    assertTrue(app.state().catalog.connected(SHOP.id()));
    never.countDown();
  }

  /** 5: the edit dialog's look into a keyring that does not answer does not hold Save back. */
  @Test
  void aLookupThatNeverReturnsDoesNotHoldSaveBack() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var never = new CountDownLatch(1);
    c.secrets.late = never;
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED
            + "press role=textbox label=\"User\"\ntype \"2\"\nframe\nadvance "
            + State.KEYRING_SECONDS + "\nframe\nadvance 1\nframe\nsnap ready\n");
    String ready = c.tree("ready");
    assertFalse(line(ready, "role=button label=\"Save\"").contains("disabled"), ready);
    assertTrue(ready.contains("is not known"), ready);
    never.countDown();
  }

  // The re-check of the sixth round.

  /**
   * 5: a session whose read is left open closes, and the driver's call as its statement ends never
   * returns: the closing thread ends within its bound all the same, and says what it could not.
   */
  @Test
  void aClosingWhoseDriverCallNeverReturnsEnds() throws Exception {
    var stuck = new dev.hoori.db.core.execution.Stuck();
    try {
      var connection = stuck.connection();
      var lane = new Lane(e -> {});
      lane.open(
          (profile, password, driver) -> Servers.Real.of(
              connection,
              Provider.POSTGRESQL,
              dev.hoori.db.core.connection.ServerVersion.UNKNOWN,
              "shop"),
          SHOP,
          ConnectionsTest.PASSWORD.toCharArray(),
          null);
      var first = lane.submit(link -> link.run("SELECT a FROM t").next(10))
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(first.more(), "left open");
      stuck.arm();
      var ended =
          new java.util.concurrent.CompletableFuture<dev.hoori.db.core.execution.Abandoned>();
      var closing = lane.close(ended::complete);
      var bound = Lane.ENDS
          .plus(dev.hoori.db.core.execution.Execution.ENDING)
          .plus(dev.hoori.db.core.query.Query.GRACE.multipliedBy(4));
      assertTrue(closing.join(bound), "the closing thread ended within " + bound);
      assertEquals(
          Failure.Kind.CONNECTION_LOST,
          ended.getNow(null).failure().orElseThrow().kind());
    } finally {
      stuck.release();
    }
  }

  /**
   * The last re-check: a give-up that its deadline cut off ends the session at once and for good.
   * The statement that asked for it never reaches the old link, the console says the connection
   * ended, the next run opens a new session, and the old worker that wakes up changes nothing.
   */
  @Test
  void aGiveUpCutOffByItsDeadlineEndsTheSession() throws Exception {
    var stuck = new dev.hoori.db.core.execution.Stuck();
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    // The tree's connection and the later ones made up; the console's first one stuck.
    Servers mixed = (profile, password, driver) -> opens.getAndIncrement() == 1
        ? Servers.Real.of(
            stuck.connection(),
            Provider.POSTGRESQL,
            dev.hoori.db.core.connection.ServerVersion.UNKNOWN,
            "shop")
        : servers.open(profile, password, driver);
    try {
      var app = c.app(mixed, () -> new Keyring.Available(c.secrets), SHOP);
      c.ready(app, SHOP);
      c.run(app, CatalogTreeTest.START + menu("shop-prod", "New console") + SETTLE);
      var tab = k.tab(app);
      c.run(app, type("SELECT a FROM t") + RUN);
      assertTrue(tab.outputs.getFirst().more, "a read left open on the stuck connection");
      stuck.arm();
      // Its give-up runs to its deadline, longer than an await waits by default.
      c.run(
          app,
          type(";\n" + COUNT) + "key Enter control\nframe\nawait 30\nframe\n" + SETTLE
              + "snap ended\n");
      String ended = c.tree("ended");
      assertFalse(tab.connected(), "the session is over");
      assertTrue(line(ended, "#problem").contains("Disconnected"), ended);
      assertEquals(
          1,
          stuck.calls.stream().filter("createStatement"::equals).count(),
          "nothing more reached the old link: " + stuck.calls);
      // The old worker wakes up, and changes nothing on the connection given up.
      int before = stuck.calls.size();
      var worker = stuck.stuck();
      stuck.release();
      assertTrue(worker.join(java.time.Duration.ofSeconds(10)));
      var after = stuck.calls.subList(before, stuck.calls.size());
      assertTrue(
          after.stream()
              .noneMatch(
                  x -> x.equals("commit") || x.equals("rollback") || x.startsWith("setAutoCommit")),
          after.toString());
      // The next run opens a new session.
      c.run(app, "press #sql\n" + RUN);
      assertTrue(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
      assertTrue(tab.connected());
      assertEquals(
          1,
          stuck.calls.stream().filter("createStatement"::equals).count(),
          "still nothing on the old link: " + stuck.calls);
    } finally {
      stuck.release();
    }
  }

  /**
   * 2: the lane could not open the console's connection, so it never made the call: the console
   * does not stay busy, and says why.
   */
  @Test
  void aConnectionThatCannotOpenEndsTheCallItWasFor() throws Exception {
    var app = k.console(SHOP);
    // A session, lost: the next statement opens another, behind the tree's connection.
    c.run(app, type(COUNT + ";") + RUN + type("\n" + KILL) + RUN);
    assertFalse(k.tab(app).connected());
    servers.fail(
        "open " + PG + "/shop",
        FakeServers.notConnected(
            dev.hoori.db.core.connect.ConnectionException.Failure.UNREACHABLE));
    c.run(app, type(";\n" + COUNT) + RUN + "snap failed\n");
    assertFalse(k.tab(app).busy(), "not busy");
    assertTrue(line(c.tree("failed"), "#problem").contains("role=note"), c.tree("failed"));
  }

  /** 3: a Teller wiped with its session posts no word of the server's that arrives after. */
  @Test
  void aTellerWipedPostsNoUncheckedWords() throws Exception {
    var app = k.console(SHOP);
    var teller = new ConsoleTab.Teller(
        app, app.state(), "shop-prod", ConnectionsTest.PASSWORD.toCharArray(), null);
    teller.wipe();
    teller.tell(PAY, "password authentication failed for " + ConnectionsTest.PASSWORD, true);
    c.run(app, SETTLE);
    assertFalse(noticed(app).contains(ConnectionsTest.PASSWORD), noticed(app));
    assertTrue(noticed(app).contains(ConsoleTab.TOO_LATE), noticed(app));
  }

  /**
   * 4: Password empty, saving unchecked, the delete given up, and it and the refresh failed: what
   * the keyring holds is not known, so the choice still counts, and Save asks again to delete.
   */
  @Test
  void anUnknownKeyringWithNothingElseChangedCanStillBeAskedToDelete() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + OPENED);
    c.secrets.fails = true;
    late.countDown();
    c.run(app, SETTLE);
    c.secrets.fails = false;
    // Save's handler, as its button or Enter calls it, whether or not it would go.
    app.state().dialog.save(app.state());
    c.run(app, "frame\n" + SETTLE);
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "asked to delete, as chosen");
  }

  /** 6: Dismiss all dismisses the notices shown when it was pressed, not one that came since. */
  @Test
  void dismissAllLeavesANoticeThatCameDuringThePress() throws Exception {
    var app = k.console(SHOP);
    var s = app.state();
    for (int i = 1; i <= 4; i++) {
      int n = i;
      app.post(() -> s.notice("shop-prod", "SELECT " + n, "shown " + n, true, null));
    }
    c.run(app, "await\nframe\n" + OPENED + "down #dismiss-all\nframe\n");
    // Between the press and the release, and not shown: no frame was built since.
    s.notice("shop-prod", "SELECT 5", "came during the press", true, null);
    c.run(app, "up #dismiss-all\nframe\n");
    assertEquals(1, s.notices.size(), noticed(app));
    assertTrue(noticed(app).contains("came during the press"), noticed(app));
  }

  /** The pictures of what the fifth round changed, light and dark, as {@link #pictures}. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void picturesOfTheFifthRound(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    // Two notices, and Exit staying open on them.
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    app.state().theme = theme;
    Script.run(
        type(ORDERS) + RUN + menu("shop-prod", "New console") + SETTLE + type(ORDERS) + RUN + EXIT
            + SETTLE + "snap main-exit-stayed-open-" + look + "\n",
        app,
        out);
    // A delete that waits behind a keyring save given up.
    var keyring = c.app(SHOP);
    c.ready(keyring, SHOP);
    keyring.state().theme = theme;
    var late = new CountDownLatch(1);
    Script.run(CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE, keyring, out);
    c.secrets.late = late;
    Script.run(
        "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"first\"\n"
            + "press role=checkbox label=\"Save password in the system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + OPENED
            + "press role=button label=\"Cancel\"\nframe\n" + OPENED
            + "press role=button label=\"Discard\"\nframe\n" + OPENED
            + menu("shop-prod", "Delete…") + OPENED
            + "press role=button label=\"Delete\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nsnap dialog-keyring-behind-" + look + "\n",
        keyring,
        out);
    late.countDown();
    Script.run(SETTLE, keyring, out);
    for (var name : List.of("main-exit-stayed-open", "dialog-keyring-behind")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }

  /** The pictures of what the sixth round changed, light and dark, as {@link #pictures}. */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void picturesOfTheSixthRound(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    // A Rollback that keeps failing: the question offers to end the connection.
    servers.statement(
        ConsoleTab.ROLLBACK,
        List.of(FakeServers.serverError(Failure.Kind.OTHER, "Error writing file")));
    var maria = open(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        "press #close-0\nframe\n" + OPENED + "press #answer-rollback\nframe\n" + OPENED + SETTLE
            + "press #close-0\nframe\n" + OPENED + "snap console-end-the-connection-" + look + "\n",
        maria,
        out);
    // Many notices, scrolling, with Dismiss all.
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var many = k.console(SHOP);
    many.state().theme = theme;
    for (int i = 0; i < 4; i++) {
      Script.run(menu("shop-prod", "New console") + SETTLE + type(ORDERS) + RUN, many, out);
    }
    Script.run(
        menu("shop-prod", "Disconnect") + OPENED + SETTLE + "snap main-notices-" + look + "\n",
        many,
        out);
    // A keyring that does not give the password: the password question says so.
    c.secrets.stored.put(SHOP.id(), ConnectionsTest.PASSWORD);
    var asked = c.app(SHOP);
    c.ready(asked, SHOP);
    asked.state().forgetPassword(SHOP.id());
    asked.state().theme = theme;
    var never = new CountDownLatch(1);
    c.secrets.late = never;
    Script.run(
        CatalogTreeTest.START + CatalogTreeTest.open("shop-prod") + "frame\nadvance "
            + State.KEYRING_SECONDS + "\nframe\n" + OPENED + "snap main-keyring-silent-" + look
            + "\n",
        asked,
        out);
    never.countDown();
    for (var name : List.of("console-end-the-connection", "main-notices", "main-keyring-silent")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }
}

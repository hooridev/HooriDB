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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A console's transactions: Auto and Manual, Commit and Rollback, and what the server says of them. */
class ConsoleTransactionTest extends ConsoleTransactions {

  @Test
  void manualKeepsTheChangeOpenUntilCommitOrRollback() throws Exception {
    var app = k.console(SHOP);
    c.run(app, "snap auto\n");
    String auto = c.tree("auto");
    assertTrue(line(auto, "role=radio label=\"Auto\"").contains("checked"), auto);
    assertTrue(line(auto, "#commit").contains("disabled"), "Commit off in Auto: " + auto);
    assertFalse(auto.contains("#transaction"), auto);
    c.run(app, MANUAL + "snap manual\n");
    String manual = c.tree("manual");
    assertTrue(line(manual, "role=radio label=\"Manual\"").contains("checked"), manual);
    assertTrue(line(manual, "#commit").contains("disabled"), manual);
    assertTrue(line(manual, "#rollback").contains("disabled"), manual);
    assertTrue(badge(manual).contains("No transaction"), manual);
    assertFalse(line(manual, TAB).contains("modified"), manual);
    assertFalse(manual.contains("#uncommitted"), manual);
    // A change opens it; the connection turned auto-commit off first.
    c.run(app, type(PAY) + RUN + "snap changed\n");
    String changed = c.tree("changed");
    assertTrue(
        line(changed, "#uncommitted").contains("\"1 console may hold uncommitted work\""), changed);
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
    assertFalse(committed.contains("#uncommitted"), committed);
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

  /** With Manual configured, both restored and new SQL consoles start in Manual. */
  @Test
  void aNewAndARestoredSqlConsoleStartInManualWhenConfigured() throws Exception {
    var config = dir.resolve("config");
    new SettingsStore(config).save(Settings.DEFAULT.withCommitMode(Settings.CommitMode.MANUAL));
    new ConsoleStore(config).save(List.of(new StoredConsole(SHOP.id(), "shop", COUNT)));
    var app = c.again(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + "snap restored\n");
    String restored = c.tree("restored");
    assertTrue(line(restored, "role=radio label=\"Manual\"").contains("checked"), restored);
    assertTrue(badge(restored).contains("No transaction"), restored);

    c.run(app, menu("shop-prod", "New console") + SETTLE + "snap opened\n");
    String opened = c.tree("opened");
    assertTrue(line(opened, "role=radio label=\"Manual\"").contains("checked"), opened);
    assertTrue(badge(opened).contains("No transaction"), opened);

    c.run(app, type(PAY) + RUN + "snap ran\n");
    assertTrue(servers.calls.contains("autocommit " + PG + " off"), servers.calls.toString());
    assertTrue(badge(c.tree("ran")).contains("Transaction open · 1 statement"), c.tree("ran"));
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
    assertTrue(line(ended, "#commit").contains("disabled"), ended);
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
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + "snap cancelled\n");
    assertTrue(badge(c.tree("cancelled")).contains("Transaction failed"), c.tree("cancelled"));
    // MariaDB leaves it open.
    var maria = open(INVENTORY);
    var held = "run " + MARIA + " " + ORDERS;
    servers.hold(held);
    c.run(maria, type(";\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(held));
    c.run(
        maria,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + "snap open\n");
    assertTrue(servers.calls.contains("transaction " + MARIA), servers.calls.toString());
    assertTrue(badge(c.tree("open")).contains("Transaction open · 1 statement"), c.tree("open"));
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

  /** A connection lost after the server's cancel is lost, not the cancel alone. */
  @Test
  void aConnectionLostAfterTheServersCancelIsLost() throws Exception {
    var app = open(SHOP);
    c.run(app, type(";\n" + CUT) + RUN + "snap cut\n");
    String cut = c.tree("cut");
    assertTrue(line(cut, "#problem").contains("Disconnected"), cut);
    assertTrue(cut.contains("The open transaction is gone with the connection"), cut);
    assertFalse(k.tab(app).connected());
  }

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
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
    eventually(() -> servers.calls.contains("transaction " + PG));
    c.run(app, """
            frame
            snap asking
            press role=button label="Commit"
            frame
            key C control shift
            frame
            """);
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
    var link = servers.open(
        SHOP, ConnectionsTest.PASSWORD.toCharArray(), null, java.time.Duration.ofSeconds(10));
    link.manual(true);
    link.run(PAY).next(1);
    link.run(MISSING).next(1);
    assertEquals(Transaction.FAILED, link.status().transaction());
    var ended = Session.finish(link, ConsoleTab.COMMIT, (sql, what, failure) -> {});
    assertEquals(Transaction.FAILED, ended.before());
    assertEquals(null, ended.portion());
    assertFalse(servers.calls.contains(COMMITTING), servers.calls.toString());
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
    assertTrue(near(read, "#result-status").contains("\"250 rows ·"), read);
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
    c.run(app, """
            press role=button label="Commit"
            frame
            frame
            press role=button label="Commit"
            frame
            key C control shift
            frame
            key R control shift
            frame
            """);
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

  /** New 1: whatever became of the session's first call, the mode chosen is the session's first. */
  @Test
  void aCancelledFirstSetupStillLeavesTheNextStatementInManual() throws Exception {
    var app = k.console(SHOP);
    c.run(app, CatalogTreeTest.open("shop-prod") + SETTLE + "press #sql\n" + MANUAL);
    var open = "open " + PG + "/shop";
    var opening = servers.hold(open);
    c.run(app, type(PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.stream().filter(open::equals).count() == 2);
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
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

  /** New 3: a typed COMMIT cancelled, or whose connection was lost, has an unknown outcome. */
  @Test
  void aTypedCommitCancelledOrLostHasAnUnknownOutcome() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, type(";\nCOMMIT") + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + "snap cancelled\n");
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

  /** What the console takes for a plain read, per provider; anything else may change data. */
  @Test
  void onlyPlainReadsAreTakenForReads() {
    for (var provider : Provider.SQL) {
      for (var sql : List.of(
          "SELECT * FROM orders",
          "select 1",
          "/* note */ SELECT 1",
          "(SELECT 1)",
          "SHOW search_path",
          "VALUES (1)",
          "TABLE orders",
          "WITH x AS (SELECT 1) SELECT 1",
          "WITH RECURSIVE x(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM x WHERE n < 5) SELECT * FROM"
              + " x",
          "SELECT 'into'",
          "SELECT 1 -- into")) {
        assertTrue(ConsoleTab.reads(sql, provider), provider + ": " + sql);
      }
      for (var sql : List.of(
          PAY,
          "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed",
          "WITH x AS (SELECT 1) DELETE FROM orders",
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

  /**
   * A probe's answer asked before a change was sent, and taken after it, does not make the
   * transaction the change leaves open the server's empty one: the switch to Auto still asks,
   * whether the change's outcome came, or a Cancel gave it up once it had run.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aChangeSentBehindTheProbeIsNotTakenForAnEmptyTransaction(boolean cancel) throws Exception {
    var app = open(INVENTORY);
    var tab = k.tab(app);
    c.run(app, "press #rollback\nframe\n" + SETTLE);
    assertEquals(Transaction.NONE, tab.transaction);
    var key = "run " + MARIA + " " + ORDERS;
    servers.hold(key);
    var held = servers.hold("transaction " + MARIA, 1); // past the statement's own
    c.run(app, type(";\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    // The cancel asks the server what it left; the question is held.
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
    var probe = tab.probing;
    assertNotNull(probe);
    servers.dead = true; // the change, held, runs once its gate opens, cancelled or not
    var change = "run " + MARIA + " " + PAY;
    var paying = servers.hold(change);
    c.run(app, type(";\n" + PAY) + "key Enter control\nframe\n");
    // The probe's answer, read before the change ran, reaches the app's thread only now.
    tab.probed(probe, new Status(Transaction.NONE, false));
    held.countDown();
    if (cancel) {
      eventually(() -> servers.calls.contains(change));
      c.run(
          app,
          "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
    }
    paying.countDown();
    c.run(app, SETTLE + "snap settled\n");
    if (c.tree("settled").contains("#doubt")) {
      c.run(app, "press role=button label=\"Acknowledge\"\nframe\n" + OPENED);
    }
    assertEquals(Transaction.OPEN, tab.transaction);
    c.run(app, "press role=radio label=\"Auto\"\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to Auto."), c.tree("asked"));
    assertFalse(servers.calls.contains("commit by auto-commit " + MARIA), servers.calls.toString());
  }

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
        "press #answer-cancel\nframe\n" + OPENED
            + "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + SETTLE
            + "snap after\n");
    String after = c.tree("after");
    assertTrue(near(after, "#doubt").contains(PAY + ": it was cancelled"), after);
    assertTrue(line(after, "#rollback").contains("disabled"), after);
    // In Manual, a Rollback while one is in doubt runs, and does not claim to have undone it.
    servers.openAll();
    var manual = open(SHOP);
    var change = "UPDATE orders SET paid = false WHERE id < 4";
    servers.statement(change, List.of(new Result.Count(3)));
    servers.hold("run " + PG + " " + change);
    c.run(manual, type(";\n" + change) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + change));
    c.run(
        manual,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + "press #rollback\nframe\n" + SETTLE + "snap rolled\n");
    String rolled = c.tree("rolled");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertFalse(rolled.contains("\"Rolled back\""), rolled);
    assertTrue(line(rolled, "#said").contains("ROLLBACK ran; what is in doubt still is"), rolled);
  }

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

  /** A WITH whose CTEs and main query only read runs as a lazy read in Auto. */
  @Test
  void aReadingWithRunsLazilyInAuto() throws Exception {
    var reading = "WITH x AS (SELECT id FROM orders) SELECT * FROM x";
    servers.statement(reading, List.of(FakeServers.made(250, "id: bigint")));
    var app = k.console(SHOP);
    c.run(app, type(reading) + RUN + "snap read\n");
    String read = c.tree("read");
    assertTrue(near(read, "#result-status").contains("200 rows so far"), read);
    assertFalse(
        servers.calls.contains("next " + PG + " " + reading + " 200"), servers.calls.toString());
    assertTrue(k.tab(app).outputs.getFirst().more);
    c.run(app, "press #close-0\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing asked: a plain read in Auto");
  }

  /** 5: PostgreSQL's WITH that writes is read to its end: nothing of it is given up on a close. */
  @Test
  void aWritingWithIsReadToItsEndAndNeverGivenUp() throws Exception {
    servers.statement(WRITING_WITH, List.of(FakeServers.made(250, "id: bigint")));
    var app = k.console(SHOP);
    c.run(app, type(WRITING_WITH) + RUN + "snap read\n");
    assertTrue(near(c.tree("read"), "#result-status").contains("\"250 rows ·"), c.tree("read"));
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
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
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
    assertTrue(near(read, "#result-status").contains("\"250 rows ·"), read);
    assertTrue(line(read, "#run-status").contains(ConsoleTab.WHOLE), read);
    assertTrue(servers.calls.contains("next " + MARIA + " " + NOTE + " 200"), "in its one call");
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("role=dialog"), c.tree("asked"));
    assertEquals(1, app.state().consoles(null).size());
  }

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
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + "snap cancelled\n");
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
    var ran = Session.drained(whole, whole.next(200), 1000);
    var rows = (Result.Rows) ran.portion().results().getFirst();
    assertEquals(1000, rows.rows().size());
    assertEquals(List.of(50L * 200 - 1000), ran.dropped());
    assertEquals(50, whole.portions);
    var cut = new Endless(3);
    try {
      Session.drained(cut, cut.next(200), 1000);
      throw new AssertionError("not stopped");
    } catch (InterruptedException _) {
      assertEquals(3, cut.portions, "no portion after the interrupt");
    } finally {
      Thread.interrupted();
    }
  }

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
    assertTrue(near(rolled, "#result-status").contains("\"250 rows ·"), rolled);
  }

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
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nsnap dialog-keyring-slow-" + look + "\n"
            + "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nadvance 1\nframe\n"
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
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nadvance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED
            + "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED
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

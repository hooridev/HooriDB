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
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
  static final String MANUAL = "press role=radio label=\"Manual\"\nframe\n";
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
  void disconnectingSaysTheTransactionIsRolledBack() throws Exception {
    var app = open(SHOP);
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Disconnect shop-prod?\""), asked);
    assertTrue(asked.contains("which the server then rolls back"), asked);
    c.run(
        app, "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE + "snap ended\n");
    String ended = c.tree("ended");
    assertTrue(line(ended, "#problem").contains("Transaction rolled back"), ended);
    assertTrue(badge(ended).contains("No transaction"), ended);
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
}

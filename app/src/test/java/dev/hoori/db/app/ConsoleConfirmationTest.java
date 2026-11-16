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
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.ConsoleTransactionTest.CHAIN;
import static dev.hoori.db.app.ConsoleTransactionTest.MANUAL;
import static dev.hoori.db.app.ConsoleTransactionTest.OPENED;
import static dev.hoori.db.app.ConsoleTransactionTest.badge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a console asks before a statement: a large read it reads whole, a change of every row. */
class ConsoleConfirmationTest {

  static final String BIG = "SELECT * FROM events";
  static final String WIPE = "UPDATE orders SET paid = false";
  static final String LARGE = "role=dialog label=\"Read about 5,000,000 rows?\"";
  static final String EVERY = "role=dialog label=\"Update every row?\"";
  static final String CANCEL = "press #confirm-cancel\nframe\n" + OPENED;
  static final String ANYWAY = "press role=button label=\"Run anyway\"\nframe\n" + SETTLE;
  static final String MESSAGES = "press role=tab label=\"Messages\"\nframe\n";

  @TempDir
  Path dir;

  final ConsoleTransactionTest t = new ConsoleTransactionTest();
  CatalogTreeTest c;
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    t.dir = dir;
    t.setUp();
    c = t.c;
    servers = t.servers
        .statement(BIG, List.of(FakeServers.made(300, "id: bigint")))
        .plans(BIG, 5_000_000)
        .statement(WIPE, List.of(new Result.Count(1000)));
  }

  @AfterEach
  void tearDown() throws Exception {
    t.tearDown();
  }

  private boolean ran(String sql) {
    return servers.calls.contains("run " + PG + " " + sql);
  }

  private static ConsoleTab.Logged notRun(String sql) {
    return new ConsoleTab.Logged(ConsoleTab.Level.WARNING, sql, null, "Not run");
  }

  private ConsoleTab tab(App<State> app) {
    return t.k.tab(app);
  }

  @Test
  void aLargeReadInATransactionAsksAndCancelLeavesTheTransaction() throws Exception {
    var app = t.open(SHOP);
    int statements = tab(app).inTransaction;
    c.run(app, type(";\n" + BIG) + RUN + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains(LARGE), asked);
    assertTrue(asked.contains("keep the first 100,000 rows"), asked);
    assertTrue(servers.calls.contains("explain " + PG + " " + BIG), servers.calls.toString());
    assertFalse(ran(BIG), servers.calls.toString());
    c.run(app, CANCEL + MESSAGES + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertFalse(cancelled.contains("role=dialog"), cancelled);
    assertFalse(ran(BIG), servers.calls.toString());
    assertTrue(cancelled.contains("\"Not run\""), cancelled);
    assertEquals(notRun(BIG), tab(app).log.getLast());
    assertTrue(badge(cancelled).contains("Transaction open"), cancelled);
    assertEquals(Transaction.OPEN, tab(app).transaction);
    assertEquals(statements, tab(app).inTransaction);
    // Asked again, it runs anyway: read whole, its rows kept.
    c.run(app, RUN + ANYWAY + "snap anyway\n");
    String anyway = c.tree("anyway");
    assertFalse(anyway.contains("role=dialog"), anyway);
    assertTrue(ran(BIG), servers.calls.toString());
    assertEquals(statements + 1, tab(app).inTransaction);
  }

  @Test
  void aRunOfSeveralAsksWhenItComesToTheStatementAndCancelStopsTheRest() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, MANUAL + type(String.join(";\n", COUNT, BIG, ORDERS)) + RUN_ALL + "snap asked\n");
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    assertTrue(ran(COUNT), servers.calls.toString());
    c.run(app, CANCEL + "snap cancelled\n");
    assertFalse(ran(BIG), servers.calls.toString());
    assertFalse(ran(ORDERS), servers.calls.toString());
    // Run anyway goes on with those after it.
    c.run(app, RUN_ALL + ANYWAY + "snap anyway\n");
    assertTrue(ran(BIG), servers.calls.toString());
    assertTrue(ran(ORDERS), servers.calls.toString());
    assertFalse(c.tree("anyway").contains("role=dialog"), c.tree("anyway"));
  }

  /** Cancelled in the empty transaction a chained Commit began, it leaves that empty. */
  @Test
  void aCancelledReadLeavesTheChainedTransactionEmpty() throws Exception {
    // Its database named, which the tree's one connection is on, where the estimate runs.
    var app = t.k.console(INVENTORY.withDatabase("inventory"));
    c.run(
        app,
        type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN
            + "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap empty\n");
    assertTrue(badge(c.tree("empty")).contains("Transaction open · empty"), c.tree("empty"));
    c.run(app, type(";\n" + BIG) + RUN + "snap asked\n");
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    c.run(app, CANCEL + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertFalse(servers.calls.contains("run " + MARIA + " " + BIG), servers.calls.toString());
    assertTrue(badge(cancelled).contains("Transaction open · empty"), cancelled);
    assertFalse(tab(app).atStake());
  }

  static final String EXPLAIN = "explain " + PG + " " + BIG;

  /** The console in Manual, with nothing open, its estimate of the read held. */
  private App<State> estimating() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(COUNT) + RUN + MANUAL); // connected, then Manual with nothing open
    servers.hold(EXPLAIN);
    c.run(app, type(";\n" + BIG) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(EXPLAIN));
    return app;
  }

  @Test
  void theToolbarsCancelStopsTheEstimate() throws Exception {
    var app = estimating();
    c.run(app, "snap estimating\n");
    assertTrue(line(c.tree("estimating"), "#run").contains("busy"), c.tree("estimating"));
    c.run(app, "press #cancel\nframe\n" + SETTLE + MESSAGES + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertTrue(servers.interrupted.contains(EXPLAIN), servers.interrupted.toString());
    assertFalse(ran(BIG), servers.calls.toString());
    assertFalse(cancelled.contains("role=dialog"), cancelled);
    assertFalse(cancelled.contains("#doubt"), cancelled);
    assertTrue(badge(cancelled).contains("No transaction"), cancelled);
    assertFalse(tab(app).busy());
    assertTrue(app.state().notices.isEmpty(), app.state().notices.toString());
    // The tree's lane, which it held, takes the next call.
    c.run(app, type(";\n" + ORDERS) + RUN);
    assertTrue(servers.done.contains("explain " + PG + " " + ORDERS), servers.done.toString());
    assertTrue(ran(ORDERS), servers.calls.toString());
  }

  @Test
  void closingTheTabStopsTheEstimate() throws Exception {
    var app = estimating();
    c.run(app, "key W control\nframe\n" + OPENED + "snap closing\n");
    String closing = c.tree("closing");
    // A whole read in Manual waits or is rolled back, as any does.
    assertTrue(closing.contains("role=dialog label=\"Roll back the transaction?\""), closing);
    c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE + "snap closed\n");
    assertTrue(app.state().consoles(null).isEmpty(), c.tree("closed"));
    assertTrue(servers.interrupted.contains(EXPLAIN), servers.interrupted.toString());
    assertFalse(ran(BIG), servers.calls.toString());
    assertTrue(app.state().notices.isEmpty(), app.state().notices.toString());
  }

  @Test
  void disconnectingStopsTheEstimate() throws Exception {
    var app = estimating();
    c.run(app, menu(SHOP.name(), "Disconnect") + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("role=dialog"), c.tree("asked"));
    c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE + "snap gone\n");
    assertFalse(tab(app).connected(), c.tree("gone"));
    assertFalse(tab(app).busy(), c.tree("gone"));
    assertTrue(servers.interrupted.contains(EXPLAIN), servers.interrupted.toString());
    assertFalse(ran(BIG), servers.calls.toString());
  }

  @Test
  void theDatabaseStaysWhileTheEstimateRunsOrTheQuestionIsOpen() throws Exception {
    var app = estimating();
    tab(app).use(app.state(), "analytics");
    assertEquals("shop", tab(app).database);
    servers.openAll();
    c.run(app, SETTLE + "snap asked\n");
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    tab(app).use(app.state(), "analytics");
    assertEquals("shop", tab(app).database);
    assertFalse(servers.calls.contains("use " + PG + " analytics"), servers.calls.toString());
  }

  /** With the question open, the window's close button ends HooriDB, and nothing of it runs. */
  @Test
  void closingTheWindowDropsTheQuestion() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, MANUAL + type(BIG) + RUN + "snap asked\n");
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    c.run(app, "close\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertFalse(ran(BIG), servers.calls.toString());
  }

  /**
   * Where the tree has no connection on the console's database, nothing is estimated and the read
   * runs: on MariaDB the tree's one connection is on the profile's database, which names none; on
   * PostgreSQL it has none of its own to a database it has not read.
   */
  @Test
  void withoutTheTreesConnectionToTheDatabaseNothingIsEstimated() throws Exception {
    var maria = t.k.console(INVENTORY);
    c.run(maria, MANUAL + type(BIG) + RUN + "snap ran\n");
    assertFalse(c.tree("ran").contains("role=dialog"), c.tree("ran"));
    assertTrue(servers.calls.contains("run " + MARIA + " " + BIG), servers.calls.toString());
    var pg = t.k.console(SHOP);
    c.run(
        pg,
        type(COUNT) + RUN + "press #database\nframe\n" + OPENED
            + "press role=menuitem label=\"analytics\"\nframe\n"
            + OPENED
            + MANUAL + type(";\n" + BIG) + RUN + "snap pg\n");
    assertEquals("analytics", tab(pg).database);
    assertFalse(c.tree("pg").contains("role=dialog"), c.tree("pg"));
    assertTrue(ran(BIG), servers.calls.toString());
    assertTrue(
        servers.calls.stream().noneMatch(k -> k.startsWith("explain ")), servers.calls.toString());
  }

  /**
   * Between Run and the answer, the console's session gets nothing but the question for its
   * status, and after Cancel nothing at all: the estimate runs on the tree's connection.
   */
  @Test
  void theSessionGetsNothingButItsStatusUntilTheAnswer() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(COUNT) + RUN + MANUAL);
    var session = servers.linked.stream()
        .filter(k -> k.endsWith("run " + PG + " " + COUNT))
        .findFirst()
        .orElseThrow()
        .split(" ", 2)[0];
    int from = servers.linked.size();
    c.run(app, type(";\n" + BIG) + RUN + "snap asked\n" + CANCEL + SETTLE);
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    var since = servers.linked.subList(from, servers.linked.size());
    assertEquals(
        List.of(session + " transaction " + PG),
        since.stream().filter(k -> k.startsWith(session + " ")).toList(),
        since.toString());
    assertTrue(
        since.stream()
            .anyMatch(
                k -> k.endsWith(" explain " + PG + " " + BIG) && !k.startsWith(session + " ")),
        since.toString());
  }

  @Test
  void aSmallEstimateRunsWithoutAQuestion() throws Exception {
    servers.plans(BIG, 100_000);
    var app = t.open(SHOP);
    c.run(app, type(";\n" + BIG) + RUN + "snap ran\n");
    assertTrue(servers.calls.contains("explain " + PG + " " + BIG), servers.calls.toString());
    assertTrue(ran(BIG), servers.calls.toString());
    assertFalse(c.tree("ran").contains("role=dialog"), c.tree("ran"));
  }

  @Test
  void autoReadsInPortionsAndAsksNothing() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(BIG + ";\n" + ORDERS) + RUN_ALL + "snap ran\n");
    assertTrue(ran(BIG) && ran(ORDERS), servers.calls.toString());
    assertTrue(
        servers.calls.stream().noneMatch(k -> k.startsWith("explain ")), servers.calls.toString());
    assertFalse(c.tree("ran").contains("role=dialog"), c.tree("ran"));
  }

  /** An EXPLAIN that fails: the read runs, and PostgreSQL's transaction goes on. */
  @Test
  void aFailedEstimateRunsTheReadAndLeavesTheTransaction() throws Exception {
    servers.fail(
        "explain " + PG + " " + BIG, FakeServers.queryFailure(QueryException.Failure.OTHER));
    var app = t.open(SHOP);
    c.run(app, type(";\n" + BIG) + RUN + "snap ran\n");
    String ran = c.tree("ran");
    assertTrue(servers.failed.contains("explain " + PG + " " + BIG), servers.failed.toString());
    assertTrue(ran(BIG), servers.calls.toString());
    assertFalse(ran.contains("role=dialog"), ran);
    assertTrue(badge(ran).contains("Transaction open"), ran);
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE + "snap committed\n");
    assertTrue(ran("COMMIT"), servers.calls.toString());
    assertEquals(Transaction.NONE, tab(app).transaction);
  }

  @Test
  void anUpdateWithoutWhereAsksInAuto() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(WIPE) + RUN + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains(EVERY), asked);
    assertTrue(asked.contains("it would update every row of orders"), asked);
    assertFalse(ran(WIPE), servers.calls.toString());
    c.run(app, CANCEL + MESSAGES + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertFalse(ran(WIPE), servers.calls.toString());
    assertTrue(cancelled.contains("\"Not run\""), cancelled);
    assertEquals(notRun(WIPE), tab(app).log.getLast());
    c.run(app, RUN + ANYWAY + "snap anyway\n");
    assertTrue(ran(WIPE), servers.calls.toString());
    // With a WHERE, nothing is asked.
    c.run(app, type(";\n" + PAY) + RUN + "snap pay\n");
    assertTrue(ran(PAY), servers.calls.toString());
    assertFalse(c.tree("pay").contains("role=dialog"), c.tree("pay"));
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
    var app = t.open(SHOP);
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    Script.run(
        type(";\n" + BIG) + RUN + "snap console-large-read-" + look + "\n" + CANCEL
            + type(";\n" + WIPE) + RUN + "snap console-every-row-" + look + "\n" + CANCEL,
        app,
        out);
    for (var name : List.of("console-large-read", "console-every-row")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }
}

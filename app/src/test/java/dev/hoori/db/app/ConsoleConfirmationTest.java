package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.LEGACY;
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
import static dev.hoori.db.app.ConsoleTransactions.CHAIN;
import static dev.hoori.db.app.ConsoleTransactions.MANUAL;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.ConsoleTransactions.badge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.execution.Failure;
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
  // Bounded unless standard_conforming_strings is off, where the string runs on past the WHERE.
  static final String ODD = "UPDATE orders SET note = 'C:\\' WHERE id = 1";
  static final String LARGE = "role=dialog label=\"Read about 5,000,000 rows?\"";
  static final String EVERY = "role=dialog label=\"Update every row?\"";
  static final String CANCEL = "press #confirm-cancel\nframe\n" + OPENED;
  static final String ANYWAY = "press role=button label=\"Run anyway\"\nframe\n" + SETTLE;
  static final String MESSAGES = "press role=tab label=\"Messages\"\nframe\n";

  @TempDir
  Path dir;

  final ConsoleTransactions t = new ConsoleTransactions() {};
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

  /**
   * The status bar's part does not switch a window's tab behind what is asked there: the console
   * that may hold uncommitted work shares a torn-off window with one asking before a change.
   */
  @Test
  void theStatusBarLeavesAWindowAskingBeforeAChangeAsItIs() throws Exception {
    var app = t.open(SHOP);
    c.run(
        app,
        "press role=tab label=\"shop-prod: shop\" right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "window \"shop-prod: shop\"\nkey N control\nframe\n" + SETTLE
            + "window \"shop-prod: shop\"\n" + type(WIPE) + RUN + OPENED
            + "window main\nframe\nsnap main\n");
    String item = "1 console may hold uncommitted work";
    String main = c.tree("main");
    assertTrue(line(main, "#uncommitted").contains("label=\"" + item + "\""), main);
    assertFalse(line(main, "#uncommitted").contains("role=button"), "no press: " + main);
    c.run(app, "press #uncommitted\nframe\nwindow \"shop-prod: shop\"\nframe\nsnap asked\n");
    String asked = c.tree("shop-prod-shop-asked");
    assertTrue(asked.contains(EVERY), asked);
    assertTrue(line(asked, "role=tab label=\"shop-prod: shop (2)\"").contains("selected"), asked);
    // Once it is answered, the part shows the first console.
    c.run(
        app,
        CANCEL + "window main\nframe\npress role=button label=\"" + item + "\"\nframe\n"
            + "window \"shop-prod: shop\"\nframe\nsnap shown\n");
    String shown = c.tree("shop-prod-shop-shown");
    assertTrue(line(shown, "role=tab label=\"shop-prod: shop (1)\"").contains("selected"), shown);
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

  /** A question after an open read waits until that read is given up, which it may wait long. */
  @Test
  void aQuestionAfterAnOpenReadAsksOnceTheReadIsGivenUp() throws Exception {
    var app = t.k.console(SHOP);
    var close = "close " + PG + " " + BIG;
    var gate = servers.hold(close);
    try {
      c.run(app, type(String.join(";\n", BIG, WIPE)) + "key Enter control shift\nframe\n");
      // No await once the give-up started: it would wait for the gate.
      for (int i = 0;
          i < 12 && (tab(app).outputs.isEmpty() || !tab(app).outputs.getFirst().cut);
          i++) {
        c.run(app, "await\nframe\n");
      }
      eventually(() -> servers.calls.contains(close));
      c.run(app, "frame\nadvance 1\nframe\nsnap closing\n");
      assertFalse(c.tree("closing").contains(EVERY), c.tree("closing"));
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE + "snap asked\n");
    assertTrue(c.tree("asked").contains(EVERY), c.tree("asked"));
    var read = tab(app).outputs.getFirst();
    assertTrue(read.cut);
    assertEquals(200, read.rows.size());
    c.run(app, ANYWAY);
    assertTrue(ran(WIPE), servers.calls.toString());
  }

  /** Where giving up the open read fails, the statement that waited for it says it did not run. */
  @Test
  void aFailedGiveUpBeforeAQuestionSaysTheStatementDidNotRun() throws Exception {
    servers.failsAtGiveUp(BIG, FakeServers.serverError(Failure.Kind.CONSTRAINT, "no end"));
    var app = t.k.console(SHOP);
    c.run(app, type(String.join(";\n", BIG, WIPE)) + RUN_ALL + "snap failed\n");
    assertFalse(c.tree("failed").contains(EVERY), c.tree("failed"));
    assertFalse(ran(WIPE), servers.calls.toString());
    assertTrue(
        tab(app)
            .log
            .contains(
                new ConsoleTab.Logged(ConsoleTab.Level.ERROR, WIPE, null, ConsoleTab.NOT_RUN)),
        tab(app).log.toString());
  }

  /** Cancelled in the empty transaction a chained Commit began, it leaves that empty. */
  @Test
  void aCancelledReadLeavesTheChainedTransactionEmpty() throws Exception {
    // Its database named, as the estimates' connection is too.
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
    assertTrue(line(c.tree("estimating"), "#run ").contains("busy"), c.tree("estimating"));
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n" + SETTLE
            + MESSAGES + "snap cancelled\n");
    String cancelled = c.tree("cancelled");
    assertTrue(servers.interrupted.contains(EXPLAIN), servers.interrupted.toString());
    assertFalse(ran(BIG), servers.calls.toString());
    assertFalse(cancelled.contains("role=dialog"), cancelled);
    assertFalse(cancelled.contains("#doubt"), cancelled);
    assertTrue(badge(cancelled).contains("No transaction"), cancelled);
    assertFalse(tab(app).busy());
    assertTrue(app.state().notices.isEmpty(), app.state().notices.toString());
    // The estimates' lane, which it held, takes the next call.
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
   * A MariaDB or MySQL profile that names no database, its console on the user's one database: the
   * estimates' connection, opened on none, plans on the console's, and the tree reads on as
   * before.
   */
  @ParameterizedTest
  @ValueSource(strings = {"inventory", "legacy"})
  void onMariaDbAndMySqlTheEstimateIsOnTheConsolesDatabase(String database) throws Exception {
    var profile = database.equals("inventory") ? INVENTORY : LEGACY;
    assertTrue(profile.database().isBlank());
    var app = t.k.console(profile);
    c.run(app, MANUAL + type(BIG) + RUN + "snap asked\n");
    assertEquals(database, tab(app).database);
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    assertEquals(database, servers.estimatedOn);
    // The connection is named as its database: the database's row is the one at the second level.
    c.run(
        app,
        CANCEL + "press role=treeitem label=\"" + database + "\" level=2\nkey ArrowRight\nframe\n"
            + SETTLE + "snap tree\n");
    String tree = c.tree("tree");
    assertTrue(
        tree.contains(
            "role=treeitem label=\"" + (database.equals("inventory") ? "items" : "accounts")),
        tree);
  }

  /**
   * On PostgreSQL the estimates have a connection of their own to each database, also one the
   * tree has not read, opened as the first estimate there needs it.
   */
  @Test
  void onPostgreSqlEachDatabaseHasTheEstimatesConnection() throws Exception {
    var pg = t.k.console(SHOP);
    c.run(
        pg,
        type(COUNT) + RUN + "press #database\nframe\n" + OPENED
            + "press role=menuitem label=\"analytics\"\nframe\n"
            + OPENED
            + MANUAL + type(";\n" + BIG) + RUN + "snap pg\n");
    assertEquals("analytics", tab(pg).database);
    assertTrue(c.tree("pg").contains(LARGE), c.tree("pg"));
    assertEquals("analytics", servers.estimatedOn);
    // The console's session and the estimates' connection to it; the tree reads none there.
    assertEquals(
        2,
        servers.calls.stream()
            .filter(k -> k.equals("open " + PG + "/analytics"))
            .count());
  }

  /**
   * Between Run and the answer, the console's session gets nothing but the question for its
   * status, and after Cancel nothing at all. The estimate runs on a connection of its own, which
   * nothing else ever uses, and the tree's gets no EXPLAIN.
   */
  @Test
  void theSessionGetsNothingButItsStatusUntilTheAnswer() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(COUNT) + RUN + MANUAL);
    var session = number("run " + PG + " " + COUNT);
    var tree = number("databases " + PG);
    int from = servers.linked.size();
    c.run(app, type(";\n" + BIG) + RUN + "snap asked\n" + CANCEL + SETTLE);
    assertTrue(c.tree("asked").contains(LARGE), c.tree("asked"));
    var since = servers.linked.subList(from, servers.linked.size());
    assertEquals(
        List.of(session + " transaction " + PG),
        since.stream().filter(k -> k.startsWith(session + " ")).toList(),
        since.toString());
    var planner = number("explain " + PG + " " + BIG);
    assertFalse(planner.equals(session) || planner.equals(tree), servers.linked.toString());
    assertTrue(
        servers.linked.stream()
            .filter(k -> k.startsWith(planner + " "))
            .allMatch(k -> k.startsWith(planner + " explain ")),
        servers.linked.toString());
    assertTrue(
        servers.linked.stream().noneMatch(k -> k.startsWith(tree + " explain ")),
        servers.linked.toString());
  }

  // The number of the link the first call of the key ran on.
  private String number(String key) {
    return servers.linked.stream()
        .filter(k -> k.endsWith(" " + key))
        .findFirst()
        .orElseThrow()
        .split(" ", 2)[0];
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
    assertFalse(c.tree("ran").contains("role=dialog"), c.tree("ran"));
    // BIG was left as ORDERS ran, so only the open read ORDERS asks the planner, after it ran.
    assertFalse(servers.calls.contains("explain " + PG + " " + BIG), servers.calls.toString());
    int ranOrders = servers.calls.indexOf("run " + PG + " " + ORDERS);
    int explainedOrders = servers.calls.indexOf("explain " + PG + " " + ORDERS);
    assertTrue(ranOrders >= 0 && explainedOrders > ranOrders, servers.calls.toString());
  }

  @Test
  void aLazyReadShowsThePlannersEstimateOnItsOwnConnection() throws Exception {
    servers.plans(BIG, 2_400_000).plans(COUNT, 10);
    var app = t.k.console(SHOP);
    c.run(app, type(BIG) + RUN + "snap open\n");
    String open = c.tree("open");
    assertFalse(open.contains("role=dialog"), open);
    assertTrue(
        ConsoleTest.near(open, "#result-status")
            .contains("200 of about 2,400,000 rows so far · cursor open, more as it scrolls"),
        open);
    int ranBig = servers.calls.indexOf("run " + PG + " " + BIG);
    int explainedBig = servers.calls.indexOf(EXPLAIN);
    assertTrue(ranBig >= 0 && explainedBig > ranBig, servers.calls.toString());
    // The estimate ran on the estimates' connection, not on the console's session or the tree's.
    var session = number("run " + PG + " " + BIG);
    var tree = number("databases " + PG);
    var planner = number(EXPLAIN);
    assertFalse(planner.equals(session) || planner.equals(tree), servers.linked.toString());
    assertTrue(
        servers.linked.stream()
            .filter(k -> k.startsWith(planner + " "))
            .allMatch(k -> k.startsWith(planner + " explain ")),
        servers.linked.toString());
    assertTrue(
        servers.linked.stream().noneMatch(k -> k.startsWith(session + " explain ")),
        servers.linked.toString());
    assertTrue(
        servers.linked.stream().noneMatch(k -> k.startsWith(tree + " explain ")),
        servers.linked.toString());

    // Stopped early, the partial result keeps the estimate.
    c.run(app, "press role=button label=\"Stop reading\"\nframe\n" + SETTLE + "snap stopped\n");
    String stopped = c.tree("stopped");
    assertTrue(
        ConsoleTest.near(stopped, "#result-status")
            .contains("the first 200 of about 2,400,000 rows; reading stopped"),
        stopped);

    // Scrolled to its end, the exact count stands and no estimate shows.
    c.run(app, RUN + "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap ended\n");
    String endedStatus = ConsoleTest.near(c.tree("ended"), "#result-status");
    assertTrue(endedStatus.contains("\"300 rows · "), endedStatus);
    assertFalse(endedStatus.contains(" of about "), endedStatus);

    // A read that finishes in its first portion needs no estimate and asks none.
    c.run(app, "press #sql\nkey A control\nkey Delete\n" + type(COUNT) + RUN);
    assertFalse(servers.calls.contains("explain " + PG + " " + COUNT), servers.calls.toString());
  }

  @Test
  void aHeldEstimateDoesNotHoldTheFirstRowsAndCancelsWhenLeft() throws Exception {
    var app = t.k.console(SHOP);
    c.run(app, type(COUNT) + RUN); // session open on shop
    var gate = servers.hold(EXPLAIN);
    try {
      c.run(app, type(";\n" + BIG) + "key Enter control\nframe\nawait\nframe\n");
      eventually(() -> servers.calls.contains(EXPLAIN));
      c.run(app, "advance 1\nframe\nsnap held\n");
      String held = c.tree("held");
      assertEquals(200, tab(app).outputs.getFirst().rows.size());
      assertFalse(tab(app).busy());
      String heldStatus = ConsoleTest.near(held, "#result-status");
      assertTrue(heldStatus.contains("200 rows so far · cursor open, more as it scrolls"), held);
      assertFalse(heldStatus.contains(" of about "), held);
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE + "snap estimated\n");
    String estimated = c.tree("estimated");
    assertTrue(
        ConsoleTest.near(estimated, "#result-status")
            .contains("200 of about 5,000,000 rows so far · cursor open, more as it scrolls"),
        estimated);

    // Leaving the read while a new estimate is held cancels the estimate on the planner lane.
    var second = servers.hold(EXPLAIN);
    try {
      int explainBefore = (int) servers.calls.stream().filter(EXPLAIN::equals).count();
      c.run(app, "press #sql\nkey Enter control\nframe\nawait\nframe\n");
      eventually(() -> servers.calls.stream().filter(EXPLAIN::equals).count() > explainBefore);
      c.run(app, "advance 1\nframe\npress role=button label=\"Stop reading\"\nframe\n");
      eventually(() -> servers.interrupted.contains(EXPLAIN));
    } finally {
      second.countDown();
    }
    c.run(app, SETTLE + "snap stopped\n");
    String stopped = ConsoleTest.near(c.tree("stopped"), "#result-status");
    assertTrue(stopped.contains("the first 200 rows; reading stopped"), stopped);
    assertFalse(stopped.contains(" of about "), stopped);
  }

  @Test
  void aMissingOrFailedEstimateLeavesTodaysStatusLine() throws Exception {
    var app = t.k.console(SHOP);
    // Missing plan (same OptionalLong.empty() as Plan.callsOut): today's text stays.
    c.run(app, type(ORDERS) + RUN + "snap missing\n");
    assertTrue(servers.done.contains("explain " + PG + " " + ORDERS), servers.done.toString());
    String missing = ConsoleTest.near(c.tree("missing"), "#result-status");
    assertTrue(missing.contains("200 rows so far · cursor open, more as it scrolls"), missing);
    assertFalse(missing.contains(" of about "), missing);

    // Failed estimate: today's text stays, with no error or notice.
    servers.fail(EXPLAIN, FakeServers.queryFailure(QueryException.Failure.OTHER));
    c.run(app, "press #sql\nkey A control\nkey Delete\n" + type(BIG) + RUN + "snap failed\n");
    assertTrue(servers.failed.contains(EXPLAIN), servers.failed.toString());
    String failed = ConsoleTest.near(c.tree("failed"), "#result-status");
    assertTrue(failed.contains("200 rows so far · cursor open, more as it scrolls"), failed);
    assertFalse(failed.contains(" of about "), failed);
    assertEquals(null, tab(app).problem);
    assertTrue(app.state().notices.isEmpty(), app.state().notices.toString());
  }

  /**
   * A read the console expected to keep lazy in Auto, but whose server status forces it to be read
   * whole (as with a driver that cannot report auto-commit), asks first where the planner
   * estimates more rows than KEPT.
   */
  @Test
  void aReadForcedWholeByUnknownAutoCommitAsksBeforeRunning() throws Exception {
    servers.cannotReport(MARIA);
    var app = t.k.console(INVENTORY);
    c.run(app, type(BIG) + RUN + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains(LARGE), asked);
    assertTrue(servers.calls.contains("explain " + MARIA + " " + BIG), servers.calls.toString());
    assertFalse(servers.calls.contains("run " + MARIA + " " + BIG), servers.calls.toString());
    c.run(app, ANYWAY + "snap ran\n");
    String ran = c.tree("ran");
    assertFalse(ran.contains("role=dialog"), ran);
    assertTrue(
        servers.calls.contains("next " + MARIA + " " + BIG + " 200"), servers.calls.toString());
    assertTrue(ConsoleTest.near(ran, "#result-status").contains("\"300 rows · "), ran);
  }

  /**
   * Disconnecting while a read forced whole by unknown auto-commit runs asks as for any running
   * read whole.
   */
  @Test
  void disconnectingWhileAReadForcedWholeRunsAsks() throws Exception {
    servers.cannotReport(MARIA);
    var app = t.k.console(INVENTORY);
    c.run(app, CatalogTreeTest.open(INVENTORY.name()) + SETTLE);
    var key = "run " + MARIA + " " + ORDERS;
    var gate = servers.hold(key);
    c.run(app, type(ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(
        app,
        "press role=treeitem label=\"" + INVENTORY.name() + "\" level=1 right\nframe\n" + OPENED
            + "press role=menuitem label=\"Disconnect\"\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Roll back the transaction?\""), asked);
    assertTrue(asked.contains("A statement still runs on"), asked);
    assertTrue(app.state().catalog.connected(INVENTORY.id()));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    gate.countDown();
    c.run(app, SETTLE);
    assertTrue(app.state().catalog.connected(INVENTORY.id()));
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
    // Where the text cannot tell, it says so.
    c.run(app, type(";\n" + ODD.replace("\\", "\\\\")) + RUN + "snap odd\n");
    assertTrue(c.tree("odd").contains("role=dialog label=\"Update every row?\""), c.tree("odd"));
    assertTrue(
        c.tree("odd")
            .contains("Could not tell whether " + ODD.replace("\\", "\\\\")
                + " is limited by a WHERE clause; it may update every row of orders."),
        c.tree("odd"));
    c.run(app, CANCEL);
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
            + type(";\n" + WIPE) + RUN + "snap console-every-row-" + look + "\n" + CANCEL
            + type(";\n" + ODD.replace("\\", "\\\\")) + RUN + "snap console-may-be-every-row-"
            + look + "\n" + CANCEL,
        app,
        out);
    for (var name :
        List.of("console-large-read", "console-every-row", "console-may-be-every-row")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }
}

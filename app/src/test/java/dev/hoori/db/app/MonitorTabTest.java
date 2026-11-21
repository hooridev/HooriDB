package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.FakeServers.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.ServerVariable;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server monitor: a connection's sessions in a tab, read on a session of its own. */
class MonitorTabTest {

  static final String READ = "sessions " + PG;
  static final String TAB = "shop-prod: Server monitor";

  /** Lets the time pass, and what it read in. */
  static String after(int seconds) {
    return "advance " + seconds + "\nframe\nawait\nframe\n";
  }

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.sessions(
        PG,
        session("101", "ada", "SELECT pg_sleep(60)"),
        session("102", "bob", ""),
        session("103", "eve", "SELECT 'the key is " + PASSWORD + "'"));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with shop-prod connected and its monitor open from its menu. */
  App<State> monitor() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("shop-prod") + SETTLE + menu("shop-prod", "Server monitor") + SETTLE);
    return app;
  }

  long reads() {
    return c.servers.calls.stream().filter(READ::equals).count();
  }

  /** The line of the tree with the text. */
  static String line(String tree, String text) {
    return tree.lines()
        .filter(l -> l.contains(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError(text + " in\n" + tree))
        .strip();
  }

  /** The lines of the grid's row, counted from 0. */
  static String row(String tree, int row) {
    int from = tree.indexOf("#row-" + row + " role=row");
    int to = tree.indexOf("#row-" + (row + 1) + " role=row");
    return tree.substring(from, to < 0 ? tree.length() : to);
  }

  @Test
  void theConnectionsMenuOpensTheSessionsOnASessionOfTheMonitorsOwn() throws Exception {
    var app = monitor();
    c.run(app, "snap monitor\n");
    var tree = c.tree("monitor");
    assertTrue(tree.contains("label=\"" + TAB + "\""), tree);
    assertTrue(tree.contains("SELECT pg_sleep(60)"), tree);
    assertTrue(tree.contains("\"4 sessions\""), tree);
    // The monitor's own session, first, is marked.
    assertTrue(row(tree, 0).contains("label=\"person\""), row(tree, 0));
    assertFalse(row(tree, 1).contains("label=\"person\""), row(tree, 1));
    // Its own connection, behind the tree's: the second link that was opened.
    assertTrue(c.servers.linked.contains("2 " + READ), c.servers.linked.toString());
    assertFalse(c.servers.linked.contains("1 " + READ), c.servers.linked.toString());
  }

  @Test
  void theFileMenuOpensItOnceAConnectionIsSelected() throws Exception {
    var app = monitor();
    c.run(app, "key W control\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, "press role=treeitem label=\"shop-prod\"\nframe\n");
    c.run(app, "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=menuitem label=\"Server monitor\"\nframe\n" + SETTLE + "snap again\n");
    assertTrue(c.tree("again").contains("label=\"" + TAB + "\""), c.tree("again"));
    // A second time it goes to the tab it has.
    c.run(app, "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=menuitem label=\"Server monitor\"\nframe\n" + SETTLE + "snap once\n");
    var tree = c.tree("once");
    assertEquals(1, tree.split("role=tab label=\"" + TAB, -1).length - 1, tree);
  }

  @Test
  void itReadsAgainAtTheInterval() throws Exception {
    var app = monitor();
    long first = reads();
    c.run(app, after(5));
    assertEquals(first + 1, reads());
    // The next interval counts from the read's end.
    c.run(app, after(4));
    assertEquals(first + 1, reads());
    c.run(app, after(1));
    assertEquals(first + 2, reads());
  }

  @Test
  void theIntervalCanBeChanged() throws Exception {
    var app = monitor();
    c.run(app, "press #interval\nframe\nadvance 1\nframe\nsnap intervals\n");
    c.run(app, "press label=\"Every 1 s\"\nframe\nadvance 1\nframe\nsnap every\n");
    assertTrue(c.tree("every").contains("\"Every 1 s\""), c.tree("every"));
    // What the step's time began has come in.
    c.run(app, "await\nframe\n");
    long first = reads();
    c.run(app, after(1) + after(1));
    assertEquals(first + 2, reads());
  }

  @Test
  void pausedItReadsOnlyByHandAndResumedAtOnce() throws Exception {
    var app = monitor();
    c.run(app, "press #pause\nframe\nsnap paused\n");
    var tree = c.tree("paused");
    // The toggle says what it does now, beside a badge that says it is paused.
    assertTrue(
        tree.contains("#pause role=button label=\"Resume\" ")
            && line(tree, "#pause role=button").endsWith(" checked"),
        tree);
    assertTrue(tree.contains("#paused") && tree.contains("\"Paused\""), tree);
    long first = reads();
    c.run(app, after(30) + after(30));
    assertEquals(first, reads());
    c.run(app, "press #refresh\nframe\nawait\nframe\n");
    assertEquals(first + 1, reads());
    c.run(app, after(30));
    assertEquals(first + 1, reads());
    c.run(app, "press #pause\nframe\nawait\nframe\n");
    assertEquals(first + 2, reads());
  }

  @Test
  void aHiddenTabDoesNotReadUntilItIsShownAgain() throws Exception {
    var app = monitor();
    // A console in front of it.
    c.run(app, "key N control\nframe\nadvance 1\nframe\n" + SETTLE);
    long first = reads();
    c.run(app, after(30) + after(30));
    assertEquals(first, reads());
    c.run(app, "press role=tab label=\"" + TAB + "\"\nframe\nawait\nframe\n");
    assertEquals(first + 1, reads());
  }

  @Test
  void closingTheTabEndsItsSession() throws Exception {
    var app = monitor();
    int closed = c.servers.closed.size();
    c.run(app, "key W control\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(closed + 1, c.servers.closed.size(), c.servers.closed.toString());
    long first = reads();
    c.run(app, after(30));
    assertEquals(first, reads());
  }

  @Test
  void disconnectingEndsItsSessionAndConnectingOpensItAnew() throws Exception {
    var app = monitor();
    int closed = c.servers.closed.size();
    c.run(app, menu("shop-prod", "Disconnect") + SETTLE + "snap disconnected\n");
    // The tree's connection and the monitor's.
    assertEquals(closed + 2, c.servers.closed.size(), c.servers.closed.toString());
    var tree = c.tree("disconnected");
    assertTrue(tree.contains("\"Disconnected\""), tree);
    // What was read stays.
    assertTrue(tree.contains("SELECT pg_sleep(60)"), tree);
    long first = reads();
    c.run(app, "press role=button label=\"Connect\"\nframe\n" + SETTLE + SETTLE);
    assertEquals(first + 1, reads());
  }

  @Test
  void aHiddenTabConnectedAnewReadsOnlyOnceShown() throws Exception {
    var app = monitor();
    c.run(app, "key N control\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, menu("shop-prod", "Disconnect") + SETTLE);
    long first = reads();
    c.run(app, open("shop-prod") + SETTLE + SETTLE + "snap connected\n");
    assertTrue(c.tree("connected").contains("label=\"shop-prod\""), c.tree("connected"));
    assertEquals(first, reads());
    c.run(app, "press role=tab label=\"" + TAB + "\"\nframe\nawait\nframe\n");
    assertEquals(first + 1, reads());
  }

  @Test
  void theVariablesOfTheServerBeforeAreReadAgainOnceConnectedAnew() throws Exception {
    var key = "variables " + PG;
    var app = monitor();
    c.run(app, "press role=radio label=\"Variables\"\n" + SHOWN + SETTLE);
    c.run(app, "press role=radio label=\"Sessions\"\n" + SHOWN + SETTLE);
    c.run(app, menu("shop-prod", "Disconnect") + SETTLE);
    c.run(app, "press role=button label=\"Connect\"\nframe\n" + SETTLE + SETTLE);
    long first = c.servers.calls.stream().filter(key::equals).count();
    assertEquals(1, first);
    c.run(app, "press role=radio label=\"Variables\"\n" + SHOWN + SETTLE);
    assertEquals(first + 1, c.servers.calls.stream().filter(key::equals).count());
  }

  /** Selects the session of the row, counted from 0, the monitor's own first. */
  static String pick(int row) {
    return "press #cell-" + row + "-1\nframe\n";
  }

  static final String SHOWN = "frame\nadvance 1\nframe\n";

  @Test
  void cancelAsksFirstNamingTheSessionThenReadsAgain() throws Exception {
    var app = monitor();
    c.run(app, pick(1) + "press #cancel-statement\n" + SHOWN + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("Cancel the statement?"), asked);
    assertTrue(asked.contains("Session 101 of ada, from psql (10.0.0.101:5000), runs:"), asked);
    assertTrue(asked.contains("SELECT pg_sleep(60)"), asked);
    long first = reads();
    c.run(app, "press #question-go\n" + SHOWN + SETTLE + "snap cancelled\n");
    assertTrue(c.servers.linked.contains("2 cancel " + PG + " 101"), c.servers.linked.toString());
    assertTrue(reads() > first);
    var tree = c.tree("cancelled");
    assertTrue(tree.contains("\"Cancel requested for session 101\""), tree);
    assertFalse(tree.contains("SELECT pg_sleep(60)"), tree);
    // The session stays selected, and runs nothing now.
    assertTrue(line(tree, "#cancel-statement").contains("disabled"), tree);
  }

  @Test
  void declinedNothingIsSent() throws Exception {
    var app = monitor();
    c.run(app, pick(1) + "press #cancel-statement\n" + SHOWN);
    c.run(app, "press #question-keep\n" + SHOWN + SETTLE + "snap kept\n");
    assertFalse(c.servers.calls.stream().anyMatch(k -> k.startsWith("cancel ")));
    assertFalse(c.tree("kept").contains("Cancel the statement?"), c.tree("kept"));
  }

  @Test
  void endAsksFirstThenTheSessionIsGone() throws Exception {
    var app = monitor();
    c.run(app, pick(2) + "press #end-session\n" + SHOWN + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("End the session?"), asked);
    assertTrue(asked.contains("Session 102 of bob, from psql (10.0.0.102:5000), idle."), asked);
    c.run(app, "press #question-go\n" + SHOWN + SETTLE + "snap ended\n");
    assertTrue(c.servers.calls.contains("end " + PG + " 102"), c.servers.calls.toString());
    var tree = c.tree("ended");
    assertTrue(tree.contains("\"End requested for session 102\""), tree);
    assertTrue(tree.contains("\"3 sessions\""), tree);
  }

  @Test
  void theContextMenuAsksToo() throws Exception {
    var app = monitor();
    c.run(app, pick(1) + "press #cell-1-1 right\n" + SHOWN + "snap menu\n");
    var menu = c.tree("menu");
    assertFalse(line(menu, "label=\"Cancel statement…\"").contains("disabled"), menu);
    c.run(app, "press role=menuitem label=\"End session…\"\n" + SHOWN + "snap asked\n");
    assertTrue(c.tree("asked").contains("End the session?"), c.tree("asked"));
  }

  @Test
  void theMonitorsOwnSessionCannotBeEndedOrCancelled() throws Exception {
    var app = monitor();
    c.run(app, pick(0) + "snap own\n");
    var tree = c.tree("own");
    assertTrue(line(tree, "#end-session").contains("disabled"), tree);
    assertTrue(line(tree, "#cancel-statement").contains("disabled"), tree);
    c.run(app, "press #cell-0-1 right\n" + SHOWN + "snap menu\n");
    var menu = c.tree("menu");
    assertTrue(line(menu, "label=\"End session…\"").contains("disabled"), menu);
    assertTrue(line(menu, "label=\"Cancel statement…\"").contains("disabled"), menu);
  }

  @Test
  void aSessionThatEndedMeanwhileIsSaidSo() throws Exception {
    var app = monitor();
    c.run(app, pick(2) + "press #end-session\n" + SHOWN);
    c.servers.sessions(PG, session("101", "ada", "SELECT pg_sleep(60)"));
    c.run(app, "press #question-go\n" + SHOWN + SETTLE + "snap gone\n");
    assertTrue(c.tree("gone").contains("\"Session 102 had ended already\""), c.tree("gone"));
  }

  @Test
  void aRefusalShowsTheServersWords() throws Exception {
    var app = monitor();
    c.servers.fail(
        "end " + PG + " 102", FakeServers.queryFailure(QueryException.Failure.ACCESS_DENIED));
    c.run(app, pick(2) + "press #end-session\n" + SHOWN);
    c.run(app, "press #question-go\n" + SHOWN + SETTLE + "snap refused\n");
    var tree = c.tree("refused");
    assertTrue(tree.contains("Not ended"), tree);
    assertTrue(tree.contains("The server does not let this user do that to session 102."), tree);
    c.run(app, "press role=button label=\"Dismiss\"\n" + SHOWN + "snap dismissed\n");
    assertFalse(c.tree("dismissed").contains("Not ended"), c.tree("dismissed"));
  }

  @Test
  void theVariablesAreReadWhenShownAndByRefreshAndSearched() throws Exception {
    c.servers.variables(
        PG,
        new ServerVariable("max_connections", "100", "configuration file"),
        new ServerVariable("work_mem", "4MB", "default"),
        new ServerVariable("shared_buffers", "128MB", "configuration file"));
    var app = monitor();
    c.run(app, "press role=radio label=\"Variables\"\n" + SHOWN + SETTLE + "snap variables\n");
    var tree = c.tree("variables");
    assertTrue(tree.contains("\"work_mem\"") && tree.contains("\"4MB\""), tree);
    assertTrue(tree.contains("\"3 variables\""), tree);
    // Read by Refresh alone, it has no interval to choose.
    assertTrue(line(tree, "#interval").contains("disabled"), tree);
    assertTrue(line(tree, "#pause").contains("disabled"), tree);
    assertTrue(c.servers.linked.contains("2 variables " + PG), c.servers.linked.toString());
    long read = c.servers.calls.stream().filter(("variables " + PG)::equals).count();
    c.run(app, after(30) + after(30));
    assertEquals(
        read, c.servers.calls.stream().filter(("variables " + PG)::equals).count());
    c.run(app, "press #search\ntype \"config\"\nframe\nsnap searched\n");
    var searched = c.tree("searched");
    assertTrue(searched.contains("\"2 of 3 variables\""), searched);
    assertFalse(searched.contains("\"work_mem\""), searched);
    c.run(app, "press #refresh\nframe\nawait\nframe\n");
    assertEquals(
        read + 1, c.servers.calls.stream().filter(("variables " + PG)::equals).count());
  }

  @Test
  void theStatusIsReadAtTheIntervalWhileShown() throws Exception {
    c.servers.counters(PG, new Counter("commits", "42"), new Counter("rollbacks", "3"));
    var app = monitor();
    c.run(app, "press role=radio label=\"Status\"\n" + SHOWN + SETTLE + "snap status\n");
    var tree = c.tree("status");
    assertTrue(tree.contains("\"commits\"") && tree.contains("\"42\""), tree);
    assertTrue(tree.contains("\"2 counters\""), tree);
    long status = c.servers.calls.stream().filter(("status " + PG)::equals).count();
    long sessions = reads();
    c.run(app, after(5));
    assertEquals(
        status + 1, c.servers.calls.stream().filter(("status " + PG)::equals).count());
    // Only the view shown is read.
    assertEquals(sessions, reads());
  }

  @Test
  void theLocksLeadToTheSessionThatBlocks() throws Exception {
    c.servers.locks(PG, new Block("101", "102", "public.orders", "ShareLock"));
    var app = monitor();
    c.run(app, "press role=radio label=\"Locks\"\n" + SHOWN + SETTLE + "snap locks\n");
    var tree = c.tree("locks");
    assertTrue(tree.contains("\"public.orders\"") && tree.contains("\"ShareLock\""), tree);
    assertTrue(tree.contains("\"1 wait\""), tree);
    c.run(app, "press #cell-0-1 clicks 2\n" + SHOWN + SETTLE + "snap blocker\n");
    var blocker = c.tree("blocker");
    assertTrue(line(blocker, "role=radio label=\"Sessions\"").contains("checked"), blocker);
    // The session that blocks is selected, to be ended.
    assertTrue(row(blocker, 2).contains("selected"), row(blocker, 2));
    assertFalse(line(blocker, "#end-session").contains("disabled"), blocker);
  }

  @Test
  void aServerThatDoesNotTellLocksSaysSo() throws Exception {
    c.servers.locks(PG, (Block[]) null);
    var app = monitor();
    c.run(app, "press role=radio label=\"Locks\"\n" + SHOWN + SETTLE + "snap locks\n");
    assertTrue(
        c.tree("locks").contains("The server does not tell who holds a lock."), c.tree("locks"));
  }

  @Test
  void aReadAtTheIntervalIsQuietUntilItTakesLong() throws Exception {
    var app = monitor();
    var held = c.servers.hold(READ);
    c.run(app, "advance 5\nframe\nframe\nsnap quiet\n");
    var quiet = c.tree("quiet");
    var monitor = app.state().monitors(null).getFirst();
    assertTrue(monitor.reading != null);
    assertFalse(monitor.busy());
    assertFalse(line(quiet, "role=tab label=\"" + TAB).contains("busy"), quiet);
    assertFalse(line(quiet, "#refresh").contains("busy"), quiet);
    assertTrue(quiet.contains("\"Updated "), quiet);
    c.run(app, "advance 1\nframe\nframe\nsnap slow\n");
    assertTrue(monitor.busy());
    assertTrue(line(c.tree("slow"), "#refresh").contains("busy"), c.tree("slow"));
    held.countDown();
    c.run(app, "await\nframe\n");
    assertFalse(monitor.busy());
  }

  @Test
  void aLostConnectionStaysSaidAcrossViewsAndTryAgainOpensItAnew() throws Exception {
    var app = monitor();
    c.servers.fail(READ, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    c.run(app, after(5) + "snap lost\n");
    assertTrue(c.tree("lost").contains("\"Try again\""), c.tree("lost"));
    c.run(app, "press role=radio label=\"Status\"\n" + SHOWN + SETTLE + "snap switched\n");
    var switched = c.tree("switched");
    assertTrue(switched.contains("\"Try again\""), switched);
    assertTrue(line(switched, "#interval").contains("disabled"), switched);
    c.servers.heal(READ);
    long status = c.servers.calls.stream().filter(("status " + PG)::equals).count();
    c.run(app, "press role=button label=\"Try again\"\n" + SHOWN + SETTLE + "snap again\n");
    assertEquals(
        status + 1, c.servers.calls.stream().filter(("status " + PG)::equals).count());
    assertFalse(c.tree("again").contains("\"Try again\""), c.tree("again"));
  }

  @Test
  void aViewTheServerRefusesIsReadAgainOnlyByHand() throws Exception {
    var key = "status " + PG;
    c.servers.fail(key, FakeServers.queryFailure(QueryException.Failure.ACCESS_DENIED));
    var app = monitor();
    c.run(app, "press role=radio label=\"Status\"\n" + SHOWN + SETTLE + "snap denied\n");
    var denied = c.tree("denied");
    assertTrue(denied.contains("Access denied"), denied);
    long first = c.servers.calls.stream().filter(key::equals).count();
    c.run(app, after(5) + after(5) + "snap later\n");
    assertEquals(first, c.servers.calls.stream().filter(key::equals).count());
    assertFalse(c.tree("later").contains("Reading the status counters"), c.tree("later"));
    c.run(app, "press role=button label=\"Try again\"\n" + SHOWN + SETTLE);
    assertEquals(first + 1, c.servers.calls.stream().filter(key::equals).count());
  }

  @Test
  void theLocksOfAServerThatDoesNotTellThemAreReadOnce() throws Exception {
    c.servers.locks(PG, (Block[]) null);
    var app = monitor();
    c.run(app, "press role=radio label=\"Locks\"\n" + SHOWN + SETTLE + "snap locks\n");
    long first = c.servers.calls.stream().filter(("locks " + PG)::equals).count();
    c.run(app, after(5) + after(5) + "snap later\n");
    assertEquals(first, c.servers.calls.stream().filter(("locks " + PG)::equals).count());
    assertTrue(line(c.tree("later"), "#pause").contains("disabled"), c.tree("later"));
  }

  @Test
  void theActionsWaitForTheSessionsOfANewSession() throws Exception {
    var app = monitor();
    c.run(app, pick(1));
    c.servers.fail(READ, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    c.run(app, after(5));
    c.servers.heal(READ);
    var held = c.servers.hold(READ);
    c.run(app, "press role=button label=\"Try again\"\n" + SHOWN + "snap reading\n");
    var reading = c.tree("reading");
    assertTrue(line(reading, "#cancel-statement").contains("disabled"), reading);
    assertTrue(line(reading, "#end-session").contains("disabled"), reading);
    // What the session before read is gone.
    assertFalse(reading.contains("SELECT pg_sleep(60)"), reading);
    held.countDown();
    c.run(app, SETTLE + pick(1) + "snap read\n");
    assertFalse(line(c.tree("read"), "#cancel-statement").contains("disabled"), c.tree("read"));
  }

  @Test
  void withTwoSessionsSelectedNoActionRuns() throws Exception {
    var app = monitor();
    c.run(app, pick(1) + "press #cell-2-1 shift\nframe\nsnap two\n");
    var two = c.tree("two");
    assertTrue(row(two, 1).contains("selected") && row(two, 2).contains("selected"), two);
    assertTrue(line(two, "#cancel-statement").contains("disabled"), two);
    assertTrue(line(two, "#end-session").contains("disabled"), two);
    c.run(app, "press #cell-1-1 right\n" + SHOWN + "snap menu\n");
    var menu = c.tree("menu");
    assertTrue(line(menu, "label=\"End session…\"").contains("disabled"), menu);
  }

  @Test
  void anActionReachesOnlyTheSessionAsAskedAndNothingIsReadMeanwhile() throws Exception {
    var app = monitor();
    c.run(app, pick(1) + "press #cancel-statement\n" + SHOWN);
    long first = reads();
    c.run(app, after(30));
    assertEquals(first, reads());
    // Session 101 ended and another began under its id.
    c.servers.sessions(PG, session("101", "ada", "SELECT pg_sleep(60)", "a later start"));
    c.run(app, "press #question-go\n" + SHOWN + SETTLE + "snap other\n");
    var other = c.tree("other");
    assertTrue(other.contains("\"Session 101 had ended or runs something else now\""), other);
    assertTrue(other.contains("SELECT pg_sleep(60)"), other);
  }

  @Test
  void theLocksSelectionGoesWhereAnotherWaitTakesItsRow() throws Exception {
    c.servers.locks(
        PG,
        new Block("101", "102", "public.orders", "ShareLock"),
        new Block("103", "102", "public.items", "ShareLock"));
    var app = monitor();
    c.run(app, "press role=radio label=\"Locks\"\n" + SHOWN + SETTLE);
    c.run(app, "press #cell-1-2\nframe\nsnap picked\n");
    assertTrue(row(c.tree("picked"), 1).contains("selected"), c.tree("picked"));
    // The same again keeps it.
    c.run(app, after(5) + "snap same\n");
    assertTrue(row(c.tree("same"), 1).contains("selected"), c.tree("same"));
    c.servers.locks(
        PG,
        new Block("101", "102", "public.orders", "ShareLock"),
        new Block("104", "101", "public.items", "ShareLock"));
    c.run(app, after(5) + "snap changed\n");
    assertFalse(row(c.tree("changed"), 1).contains("selected"), c.tree("changed"));
    // The context menu goes to the session that blocks.
    c.run(app, "press #cell-0-2\nframe\npress #cell-0-2 right\n" + SHOWN + "snap menu\n");
    c.run(app, "press role=menuitem label=\"Go to blocking session\"\n" + SHOWN + SETTLE);
    c.run(app, "snap blocker\n");
    var blocker = c.tree("blocker");
    assertTrue(line(blocker, "role=radio label=\"Sessions\"").contains("checked"), blocker);
    assertTrue(row(blocker, 2).contains("selected"), row(blocker, 2));
  }

  @Test
  void theConnectionsPasswordInAStatementNeverReachesTheTree() throws Exception {
    var app = monitor();
    c.run(app, "snap monitor\n");
    var tree = c.tree("monitor");
    assertFalse(tree.contains(PASSWORD), tree);
    assertTrue(tree.contains("the key is •••"), tree);
  }
}

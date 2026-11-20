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
    return tree.lines().filter(l -> l.contains(text)).findFirst().orElse("").strip();
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
    assertEquals(
        tree.indexOf("role=tab label=\"" + TAB), tree.lastIndexOf("role=tab label=\"" + TAB));
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
    assertTrue(
        tree.contains("#pause role=button label=\"Pause\" ")
            && line(tree, "#pause").endsWith(" checked"),
        tree);
    assertTrue(tree.contains("\"Paused\""), tree);
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
  void theConnectionsPasswordInAStatementNeverReachesTheTree() throws Exception {
    var app = monitor();
    c.run(app, "snap monitor\n");
    var tree = c.tree("monitor");
    assertFalse(tree.contains(PASSWORD), tree);
    assertTrue(tree.contains("the key is •••"), tree);
  }
}

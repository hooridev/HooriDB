package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.INVENTORY;
import static dev.hoori.db.app.CatalogueTest.MARIA;
import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.CatalogueTest.START;
import static dev.hoori.db.app.CatalogueTest.eventually;
import static dev.hoori.db.app.CatalogueTest.menu;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
import dev.hoori.db.core.execution.Result;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A console: statements written, run on a connection of its own, and what they gave. */
class ConsoleTest {

  static final String COUNT = "SELECT count(*) FROM orders";
  static final String ORDERS = "SELECT * FROM orders";
  static final String PAY = "UPDATE orders SET paid = true WHERE id < 4";
  static final String MISSING = "SELECT * FROM missing";
  static final ConnectionProfile RED = SHOP.withColour(Colour.RED);

  @TempDir
  Path dir;

  final CatalogueTest c = new CatalogueTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    servers = c.servers
        .statement(COUNT, List.of(FakeServers.made(1, "count: bigint")))
        .statement(ORDERS, List.of(FakeServers.made(1000, "id: bigint", "total: numeric(12,2)")))
        .statement(
            PAY, List.of(new Result.Count(3)), new Message("3 rows already paid", "01000", 0))
        .statement(
            MISSING,
            List.of(FakeServers.serverError(
                Failure.Kind.INVALID, "relation \"missing\" does not exist")));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with a console open on the connection, which is not connected yet. */
  App<State> console(ConnectionProfile profile) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    c.run(app, START + menu(profile.name(), "New console") + SETTLE);
    return app;
  }

  /** Types the text into the editor, a line at a time. */
  static String type(String text) {
    var out = new StringBuilder("press #sql\n");
    var lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append("key Enter\n");
      }
      if (!lines[i].isEmpty()) {
        out.append("type \"").append(lines[i].replace("\"", "\\\"")).append("\"\n");
      }
    }
    return out.toString();
  }

  static final String RUN = "key Enter control\n" + SETTLE;
  static final String RUN_ALL = "key Enter control shift\n" + SETTLE;

  ConsoleTab tab(App<State> app) {
    return app.state().consoles(null).getFirst();
  }

  /** The line with the text, and the seven after it, as of an element and its children. */
  static String near(String tree, String text) {
    var lines = tree.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(text)) {
        return String.join("\n", lines.subList(i, Math.min(lines.size(), i + 8)));
      }
    }
    throw new AssertionError("No " + text + " in\n" + tree);
  }

  static String status(String tree) {
    return near(tree, "#run-status");
  }

  @Test
  void controlEnterRunsTheStatementAtTheCaretAlone() throws Exception {
    var app = console(RED);
    c.run(app, "snap empty\n");
    String empty = c.tree("empty");
    assertTrue(line(empty, "role=tab label=\"shop-prod: shop\"").contains("value=\"red\""), empty);
    assertTrue(line(empty, "#sql").contains("focused"), empty);
    assertTrue(near(empty, "#database").contains("\"shop\""), empty);
    assertTrue(empty.contains("label=\"Nothing run yet\""), empty);
    // The caret in the middle of the first statement's line: that statement runs, and only it.
    c.run(
        app,
        type(COUNT + ";\n" + ORDERS + ";") + "key ArrowUp\nkey ArrowLeft\n" + RUN + "snap one\n");
    String one = c.tree("one");
    assertTrue(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
    assertFalse(servers.calls.contains("run " + PG + " " + ORDERS), servers.calls.toString());
    assertTrue(tabs(one).startsWith("shop-prod: shop,Result 1,Messages"), tabs(one));
    assertTrue(line(one, "#column-0").contains("label=\"count\""), one);
    assertTrue(cell(one, 0, 0).contains("\"1\""), one);
    assertTrue(status(one).contains("\"Ran in 4 ms · 1 row\""), one);
    // Its own connection, opened behind the tree's.
    assertEquals(
        2, servers.calls.stream().filter(k -> k.equals("open " + PG + "/shop")).count());
    // Run, with the second line selected: the selection runs.
    c.run(
        app,
        "key ArrowDown\nkey End\nkey Home shift\npress role=button label=\"Run\"\nframe\n" + SETTLE
            + "snap selected\n");
    String selected = c.tree("selected");
    assertTrue(servers.calls.contains("run " + PG + " " + ORDERS), servers.calls.toString());
    assertTrue(line(selected, "#column-1").contains("label=\"total\""), selected);
    assertTrue(line(selected, "#sql").contains("focused"), "Run leaves the focus: " + selected);
    assertFalse(servers.overlapped, "two calls ran at once on one connection");
  }

  @Test
  void runAllRunsEachStatementToTheFirstErrorWithAResultEach() throws Exception {
    var app = console(SHOP);
    var text = String.join(";\n", COUNT, ORDERS, PAY, MISSING, "SELECT never") + ";";
    c.run(app, type(text) + RUN_ALL + "snap all\n");
    String all = c.tree("all");
    assertTrue(tabs(all).endsWith("Result 1,Result 2,Result 3,Messages"), tabs(all));
    assertFalse(servers.calls.contains("run " + PG + " SELECT never"), servers.calls.toString());
    // The rows of the second were left as the third ran: the connection was the next one's.
    assertTrue(servers.calls.contains("close " + PG + " " + ORDERS), servers.calls.toString());
    assertTrue(line(all, "role=note").contains("label=\"The statement is not valid\""), all);
    assertTrue(all.contains("\"relation \\\"missing\\\" does not exist\""), all);
    assertTrue(status(all).contains("\"Ran 4 statements in 16 ms, the last failed"), all);
    // A change without rows says how many it changed.
    c.run(app, "press role=tab label=\"Result 3\"\nframe\nsnap changed\n");
    String changed = c.tree("changed");
    assertTrue(changed.contains("label=\"3 rows changed\""), changed);
    c.run(app, "press role=tab label=\"Result 2\"\nframe\nsnap cut\n");
    assertTrue(
        line(c.tree("cut"), "#result-status").contains("the first 200 rows, the rest left unread"),
        c.tree("cut"));
    // Messages: each statement, its time and what it gave, the server's warning and the error.
    c.run(app, "press role=tab label=\"Messages\"\nframe\nsnap messages\n");
    String messages = c.tree("messages");
    assertTrue(cell(messages, 0, 0).contains("\"" + COUNT + "\""), messages);
    assertTrue(cell(messages, 0, 1).contains("\"4 ms\""), messages);
    assertTrue(cell(messages, 1, 2).contains("\"200 rows so far\""), messages);
    assertTrue(cell(messages, 2, 2).contains("\"3 rows changed\""), messages);
    assertTrue(cell(messages, 3, 2).contains("\"3 rows already paid (01000)\""), messages);
    assertTrue(cell(messages, 4, 2).contains("relation \\\"missing\\\" does not exist"), messages);
    assertTrue(cell(messages, 4, 2).contains("label=\"error_circle\""), messages);
  }

  @Test
  void rowsAreReadAPortionAtATimeAsTheGridScrolls() throws Exception {
    var app = console(SHOP);
    c.run(app, type(ORDERS) + RUN + "snap first\n");
    assertTrue(status(c.tree("first")).contains("200 rows so far"), c.tree("first"));
    var next = "next " + PG + " " + ORDERS + " 200";
    assertFalse(servers.calls.contains(next), servers.calls.toString());
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap more\n");
    assertTrue(servers.calls.contains(next), servers.calls.toString());
    assertTrue(status(c.tree("more")).contains("400 rows so far"), c.tree("more"));
    c.run(app, "scroll #grid row 300\nframe\nframe\nsnap later\n");
    assertTrue(cell(c.tree("later"), 300, 0).contains("\"301\""), c.tree("later"));
  }

  @Test
  void aPinnedResultStaysThroughTheNextRun() throws Exception {
    var app = console(SHOP);
    c.run(app, type(COUNT) + RUN + "press #pin\nframe\n" + type(";\n" + PAY) + RUN + "snap two\n");
    String two = c.tree("two");
    assertTrue(tabs(two).endsWith("Result 1,Result 2,Messages"), tabs(two));
    assertFalse(line(two, "role=tab label=\"Result 1\"").contains("selected"), two);
    assertTrue(two.contains("label=\"3 rows changed\""), two);
    assertTrue(status(two).contains("\"Ran in 4 ms · 3 rows changed\""), two);
    // Unpinned, the next run replaces it.
    c.run(
        app,
        "press role=tab label=\"Result 1\"\nframe\npress #pin\nframe\npress #sql\n" + RUN
            + "snap three\n");
    assertTrue(tabs(c.tree("three")).endsWith("Result 3,Messages"), tabs(c.tree("three")));
  }

  @Test
  void cancelStopsTheStatementAndTheFocusStaysInTheEditor() throws Exception {
    var app = console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    var key = "run " + PG + " " + ORDERS;
    var gate = servers.hold(key);
    c.run(app, type("\n" + ORDERS) + "key Enter control\nframe\nframe\nsnap running\n");
    eventually(() -> servers.calls.contains(key));
    String running = c.tree("running");
    assertTrue(line(running, "#run ").contains("busy"), running);
    assertFalse(line(running, "#cancel").contains("disabled"), running);
    assertTrue(status(running).contains("\"Running…\""), running);
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\nsnap cancelled\n");
    eventually(() -> servers.interrupted.contains(key));
    String cancelled = c.tree("cancelled");
    assertTrue(line(cancelled, "#cancel").contains("disabled"), cancelled);
    assertFalse(line(cancelled, "#run ").contains("busy"), cancelled);
    assertTrue(line(cancelled, "#sql").contains("focused"), cancelled);
    assertTrue(status(cancelled).contains("\"Cancelled\""), cancelled);
    // The connection serves the next statement.
    gate.countDown();
    c.run(app, "await\nframe\n" + RUN + "snap again\n");
    assertTrue(status(c.tree("again")).contains("200 rows so far"), c.tree("again"));
  }

  @Test
  void closingTheTabWhileAStatementRunsCancelsItAndEndsItsConnection() throws Exception {
    var app = console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    var key = "run " + PG + " " + ORDERS;
    servers.hold(key);
    c.run(app, type("\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\nframe\nsnap closed\n");
    eventually(() -> servers.interrupted.contains(key));
    // The console's connection closes; the tree's stays.
    eventually(() -> servers.closed.contains(PG + "/shop"));
    assertEquals(1, servers.closed.size(), servers.closed.toString());
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(app.state().catalogue.connected(SHOP.id()));
  }

  @Test
  void aConsoleTornOffKeepsItsTextCaretAndUndo() throws Exception {
    var app = console(SHOP);
    c.run(app, type(COUNT + ";\n" + ORDERS) + "key ArrowUp\nkey End\nframe\n");
    var tab = tab(app);
    int caret = tab.editor.caret();
    c.run(
        app,
        "press #tab-0 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "snap torn\n");
    assertEquals(caret, tab.editor.caret());
    String window = c.tree("shop-prod-shop-torn"); // the window's title, as a file name
    assertTrue(line(window, "#sql").contains(COUNT + ";\\n" + ORDERS), window);
    assertTrue(line(window, "#sql").contains("caret 28 (1:29)"), window);
    c.run(app, "window \"shop-prod: shop\"\nkey Z control\nframe\nsnap undone\n");
    assertFalse(tab.text.contains(ORDERS), tab.text);
  }

  @Test
  void findAndReplaceWorkInTheEditor() throws Exception {
    var app = console(SHOP);
    c.run(
        app,
        type(COUNT + ";\n" + ORDERS + ";") + "key H control\nframe\nadvance 1\nframe\n"
            + "type \"orders\"\nframe\npress role=textbox label=\"Replace\"\ntype \"sales\"\n"
            + "key Enter control alt\nframe\nsnap replaced\n");
    String replaced = c.tree("replaced");
    assertTrue(line(replaced, "#sql").contains("FROM sales;\\nSELECT * FROM sales;"), replaced);
  }

  @Test
  void theDatabaseSwitchesByWhatTheServerCanDo() throws Exception {
    // PostgreSQL connects anew to another database.
    var app = console(SHOP);
    c.run(app, type(COUNT) + RUN + "press #database\nframe\nadvance 1\nframe\nsnap databases\n");
    String databases = c.tree("databases");
    assertTrue(databases.contains("label=\"analytics\""), databases);
    c.run(
        app,
        "press role=menuitem label=\"analytics\"\nframe\nadvance 1\nframe\npress #sql\n" + RUN
            + "snap analytics\n");
    String analytics = c.tree("analytics");
    assertTrue(tabs(analytics).startsWith("shop-prod: analytics,"), analytics);
    assertTrue(servers.calls.contains("open " + PG + "/analytics"), servers.calls.toString());
    // The connection to shop closes on a thread of its own, which nothing in the script awaits.
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  @Test
  void mariaDbSwitchesTheDatabaseOnItsConnection() throws Exception {
    var app = console(INVENTORY);
    c.run(app, "snap none\n");
    assertTrue(near(c.tree("none"), "#database").contains("\"No database\""), c.tree("none"));
    c.run(
        app,
        type(COUNT) + RUN + "press #database\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"inventory\"\nframe\nadvance 1\nframe\n" + SETTLE
            + "snap inventory\n");
    assertTrue(servers.calls.contains("use " + MARIA + " inventory"), servers.calls.toString());
    assertEquals(
        2, servers.calls.stream().filter(k -> k.equals("open " + MARIA)).count());
    assertTrue(tabs(c.tree("inventory")).startsWith("inventory: inventory,"));
  }

  @Test
  void theServersWordsKeepThePasswordOut() throws Exception {
    servers.statement(
        "SELECT secret",
        List.of(new Failure(
            Failure.Kind.OTHER, "no such user", "", 0, 0, "", 0, 0, "", "", "try " + PASSWORD, "")),
        new Message("a note on " + PASSWORD, "01000", 0),
        new Message("a note", PASSWORD, 0)); // a state of the server's choosing
    var app = console(SHOP);
    c.run(
        app,
        type("SELECT secret") + RUN + "snap error\npress role=tab label=\"Messages\"\n"
            + "frame\nsnap messages\n");
    c.noPassword();
    assertTrue(c.tree("error").contains(ConnectionException.WITHHELD), c.tree("error"));
  }

  @Test
  void aConsoleTornOffSaysWhyItCouldNotConnectAndTriesAgainItself() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogueTest.PG_OUTLINE, "another");
    var app = console(SHOP);
    c.run(
        app,
        "press #tab-0 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "window \"shop-prod: shop\"\n" + type(COUNT) + RUN + "snap refused\n");
    String window = c.tree("shop-prod-shop-refused");
    assertTrue(line(window, "#unconnected").contains("label=\"Login refused\""), window);
    assertTrue(window.contains("role=button label=\"Enter password…\""), window);
    assertFalse(line(window, "#run ").contains("busy"), window);
    assertTrue(line(window, "#cancel").contains("disabled"), window);
    servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogueTest.PG_OUTLINE, PASSWORD);
    c.run(
        app,
        "window \"shop-prod: shop\"\npress role=button label=\"Try again\"\nframe\n" + SETTLE
            + "snap ran\n");
    window = c.tree("shop-prod-shop-ran");
    assertTrue(status(window).contains("\"Ran in 4 ms · 1 row\""), window);
    assertFalse(window.contains("#unconnected"), window);
  }

  @Test
  void aCancelledSwitchLeavesTheDatabaseTheConnectionIsOn() throws Exception {
    var app = console(INVENTORY);
    c.run(app, type(COUNT) + RUN);
    var key = "use " + MARIA + " inventory";
    servers.hold(key);
    c.run(
        app,
        "press #database\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"inventory\"\nframe\nadvance 1\nframe\nsnap switching\n");
    eventually(() -> servers.calls.contains(key));
    String switching = c.tree("switching");
    assertTrue(status(switching).contains("\"Switching to inventory…\""), switching);
    assertTrue(near(switching, "#database").contains("\"No database\""), switching);
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\nsnap cancelled\n");
    eventually(() -> servers.interrupted.contains(key));
    String cancelled = c.tree("cancelled");
    assertTrue(near(cancelled, "#database").contains("\"No database\""), cancelled);
    assertEquals("", tab(app).database);
    // Whether the switch took effect is not known: the next statement runs on a new connection.
    eventually(() -> servers.closed.contains(MARIA));
    c.run(app, "press #sql\n" + RUN);
    assertEquals(
        3, servers.calls.stream().filter(k -> k.equals("open " + MARIA)).count());
  }

  @Test
  void aStatementWhosePortionCameAfterCancelIsClosed() throws Exception {
    var app = console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    var key = "run " + PG + " " + ORDERS;
    var gate = servers.hold(key);
    c.run(app, type("\n" + ORDERS) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    // Its first portion is read, with more to come, but not delivered before Cancel.
    gate.countDown();
    eventually(() -> servers.done.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    assertTrue(status(c.tree("cancelled")).contains("\"Cancelled\""), c.tree("cancelled"));
    eventually(() -> servers.calls.contains("close " + PG + " " + ORDERS));
  }

  @Test
  void aResultThatEndsAsTheNextOfItsStatementComesHasNoLoadingRow() throws Exception {
    var both = "CALL report()"; // one statement, whose procedure gives rows, then a count
    servers.statement(both, List.of(FakeServers.made(250, "id: bigint"), new Result.Count(5)));
    var app = console(SHOP);
    c.run(app, type(both) + RUN + "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap ended\n");
    String ended = c.tree("ended");
    assertTrue(tabs(ended).endsWith("Result 1,Result 2,Messages"), tabs(ended));
    assertTrue(near(ended, "#result-status").contains("· 250 rows\""), ended);
    assertTrue(status(ended).contains("\"Ran in 4 ms · 250 rows · 5 rows changed\""), ended);
  }

  @Test
  void aConsoleWaitsForItsConnectionAndSaysWhyItCouldNot() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    servers.fail(
        "open " + PG + "/shop", FakeServers.notConnected(ConnectionException.Failure.UNREACHABLE));
    c.run(
        app,
        START + menu("shop-prod", "New console") + SETTLE + type(COUNT) + RUN + "snap failed\n");
    String failed = c.tree("failed");
    assertTrue(line(failed, "role=note").contains("label=\""), failed);
    assertTrue(failed.contains("label=\"Try again\""), failed);
    servers.heal("open " + PG + "/shop");
    c.run(app, "press #try-again\nframe\n" + SETTLE + "snap ran\n");
    assertTrue(status(c.tree("ran")).contains("\"Ran in 4 ms · 1 row\""), c.tree("ran"));
  }
}

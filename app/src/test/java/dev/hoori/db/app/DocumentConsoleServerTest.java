package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.near;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.DocumentConsoleTest.show;
import static dev.hoori.db.app.TableTabTest.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A console on a MongoDB connection, against core's real server in Docker: statements of the
 * shell, their documents as a grid, as JSON and as a tree, their errors, and what a read-only
 * connection refuses. Without Docker it is skipped.
 */
class DocumentConsoleServerTest {

  static final String MESSAGES = "press role=tab label=\"Messages\"\nframe\n";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with a console on the connection, which runs through the real driver. */
  App<State> console(ConnectionProfile profile) throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    // The app's tests load made-up drivers; its connections go through the real one.
    Servers real = (p, password, driver, timeout) ->
        Servers.SERVER.open(p, password, server.driver(), timeout);
    var app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
    c.ready(app, profile);
    app.state().rememberPassword(profile.id(), TestServer.password());
    c.run(
        app, START + open(profile.name()) + SETTLE + menu(profile.name(), "New console") + SETTLE);
    return app;
  }

  static ConnectionProfile docs() {
    return TestServer.of(Provider.MONGODB).profile().withName("docs");
  }

  @Test
  void aFindShowsItsDocumentsAsAGridJsonAndATree() throws Exception {
    var app = console(docs());
    c.run(
        app,
        type("db.orders.find({ paid: true }, { total: 1 }).sort({ _id: -1 }).limit(3)") + RUN
            + "snap grid\n");
    var grid = c.tree("grid");
    assertTrue(cell(grid, 0, 0).contains("\"250\""), grid);
    assertTrue(cell(grid, 0, 1).contains("\"375\""), grid);
    assertTrue(near(grid, "#result-status").contains("3 documents"), grid);
    c.run(app, show("JSON") + "snap json\n");
    var json = line(c.tree("json"), "#json");
    assertTrue(json.contains("\\\"_id\\\": 250,"), json);
    assertTrue(json.contains("\\\"$numberDecimal\\\": \\\"375\\\""), json);
    c.run(app, show("Tree") + "snap tree\n");
    var tree = c.tree("tree");
    assertTrue(tree.contains("role=treeitem label=\"250\""), tree);
  }

  @Test
  void anEmptyFindStillShowsTheViewSwitcherAndZeroDocuments() throws Exception {
    var app = console(docs());
    c.run(app, type("db.orders.find({ _id: \"none\" })") + RUN + "snap empty-grid\n");
    var grid = c.tree("empty-grid");
    assertTrue(near(grid, "#result-status").contains("0 documents"), grid);
    assertTrue(
        line(grid, "#result-shown ").contains("role=combobox label=\"Show documents as\""), grid);
    assertTrue(near(grid, "#result-shown").contains("\"Grid\""), grid);
    c.run(app, show("Tree") + "snap empty-tree\n");
    var tree = c.tree("empty-tree");
    assertTrue(near(tree, "#result-shown").contains("\"Tree\""), tree);
    assertTrue(tree.contains("#no-documents"), tree);
    assertTrue(line(tree, "#rows-in-view").contains("\"No documents\""), tree);
    assertTrue(line(tree, "role=button label=\"Next page\"").contains("disabled"), tree);
    c.run(app, show("JSON") + "snap empty-json\n");
    var json = c.tree("empty-json");
    assertTrue(near(json, "#result-shown").contains("\"JSON\""), json);
    assertTrue(json.contains("#no-documents"), json);
    assertTrue(line(json, "#rows-in-view").contains("\"No documents\""), json);
    assertTrue(near(json, "#result-status").contains("0 documents"), json);
  }

  @Test
  void aFieldADocumentLacksIsAnEmptyCellAndANullIsNull() throws Exception {
    var app = console(docs());
    c.run(app, type("db.customers.find({}, { email: 1 }).sort({ _id: 1 })") + RUN + "snap grid\n");
    // Grace's email is null; Edsger has none.
    var grid = c.tree("grid");
    assertTrue(cell(grid, 1, 1).contains("\"NULL\""), grid);
    assertFalse(cell(grid, 2, 1).contains("NULL"), grid);
  }

  @Test
  void theDocumentsShownWholeTurnAPageAtATimeAndTheNextIsRead() throws Exception {
    var app = console(docs());
    c.run(app, type("db.orders.find()") + RUN + show("JSON") + "snap first\n");
    var first = c.tree("first");
    assertTrue(line(first, "#rows-in-view").contains("\"Documents 1–200\""), first);
    assertTrue(line(first, "role=button label=\"Previous page\"").contains("disabled"), first);
    assertFalse(line(first, "role=button label=\"Next page\"").contains("disabled"), first);
    // The last page is known only once the result is read to its end.
    assertTrue(line(first, "role=button label=\"Last page\"").contains("disabled"), first);
    // Not past the page that is read: one page back is the page that was read on.
    c.run(
        app,
        "press role=button label=\"Next page\"\nframe\n".repeat(2)
            + "press role=button label=\"Previous page\"\nframe\nsnap back\n");
    var back = c.tree("back");
    assertTrue(line(back, "#rows-in-view").contains("\"Documents 1–200\""), back);
    c.run(app, "press role=button label=\"Next page\"\nframe\n" + SETTLE + "snap second\n");
    var second = c.tree("second");
    assertTrue(line(second, "#rows-in-view").contains("\"Documents 201–250\""), second);
    assertTrue(line(second, "#json").contains("\\\"_id\\\": 201"), second);
    assertTrue(line(second, "role=button label=\"Next page\"").contains("disabled"), second);
  }

  @Test
  void aNextPageThatDoesNotComeLeavesTheLastShown() throws Exception {
    var app = console(docs());
    c.run(
        app,
        type("db.orders.find()") + RUN
            + show("JSON")
            + "press role=button label=\"Next page\"\nframe\nsnap reading\n"
            + "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + SETTLE
            + "snap after\n");
    var reading = c.tree("reading");
    assertTrue(reading.contains("Reading documents…"), reading);
    var after = c.tree("after");
    assertTrue(line(after, "#rows-in-view").contains("\"Documents 1–200\""), after);
    assertTrue(line(after, "#json").contains("\\\"_id\\\": 1,"), after);
  }

  @Test
  void aCountAndAnAggregateAndAUse() throws Exception {
    var app = console(docs());
    c.run(app, type("db.orders.countDocuments({ paid: false })") + RUN + "snap count\n");
    assertTrue(cell(c.tree("count"), 0, 0).contains("\"125\""), c.tree("count"));
    c.run(
        app,
        "key A control\nkey Delete\n"
            + type("db.orders.aggregate([{ $group: { _id: '$customer', n: { $sum: 1 } } },"
                + " { $sort: { _id: 1 } }])")
            + RUN + "snap aggregate\n");
    var aggregate = c.tree("aggregate");
    assertTrue(cell(aggregate, 0, 1).contains("\"83\""), aggregate);
    assertTrue(cell(aggregate, 1, 1).contains("\"84\""), aggregate);
    c.run(app, "key A control\nkey Delete\n" + type("use admin") + RUN + "snap use\n");
    assertTrue(near(c.tree("use"), "#database").contains("\"admin\""), c.tree("use"));
  }

  /** A use takes effect for the statements after it in the same run. */
  @Test
  void aUseSwitchesTheStatementsAfterItInTheSameRun() throws Exception {
    var app = console(docs());
    c.run(
        app,
        type("use admin;\ndb.getName();\ndb.orders.countDocuments({})") + RUN_ALL
            + "press role=tab label=\"Result 1\"\nframe\nsnap name\n"
            + "press role=tab label=\"Result 2\"\nframe\nsnap count\n");
    assertTrue(cell(c.tree("name"), 0, 0).contains("\"admin\""), c.tree("name"));
    // admin has no orders: the count ran there, not on hoori.
    assertTrue(cell(c.tree("count"), 0, 0).contains("\"0\""), c.tree("count"));
    assertTrue(near(c.tree("count"), "#database").contains("\"admin\""), c.tree("count"));
  }

  /** The statement at the caret runs alone: a use on another line has not run. */
  @Test
  void theStatementAtTheCaretRunsOnTheDatabaseOfTheConsole() throws Exception {
    var app = console(docs());
    c.run(app, type("use admin\ndb.getName()") + RUN + "snap alone\n");
    assertTrue(cell(c.tree("alone"), 0, 0).contains("\"hoori\""), c.tree("alone"));
    c.run(app, "key Home control\n" + RUN + "key End control\n" + RUN + "snap after\n");
    assertTrue(cell(c.tree("after"), 0, 0).contains("\"admin\""), c.tree("after"));
  }

  @Test
  void aStatementTheShellDoesNotAllowIsUnderlinedWhereItGoesWrong() throws Exception {
    var app = console(docs());
    c.run(
        app,
        type("db.orders.find({ a: 1 b: 2 })") + RUN + "snap error\n" + MESSAGES
            + "snap messages\n");
    var error = c.tree("error");
    assertTrue(line(error, "#sql").contains("squiggle 22-23"), line(error, "#sql"));
    assertTrue(c.tree("messages").contains("A comma or } is missing"), c.tree("messages"));
  }

  @Test
  void aWriteChangesDocumentsAndAnUpdateOfAllAsksFirst() throws Exception {
    var app = console(docs());
    try {
      c.run(
          app,
          type("db.scratch.insertMany([{ _id: 1, n: 1 }, { _id: 2, n: 2 }])") + RUN
              + "snap inserted\n");
      assertTrue(c.tree("inserted").contains("2 documents changed"), c.tree("inserted"));
      c.run(
          app,
          "key A control\nkey Delete\n" + type("db.scratch.updateMany({}, { $inc: { n: 1 } })")
              + "key Enter control\nframe\nadvance 1\nframe\nsnap asked\n");
      var asked = c.tree("asked");
      assertTrue(asked.contains("role=dialog label=\"Update every document?\""), asked);
      c.run(app, "press role=button label=\"Run anyway\"\nframe\n" + SETTLE + "snap updated\n");
      assertTrue(c.tree("updated").contains("2 documents changed"), c.tree("updated"));
      c.run(
          app,
          "key A control\nkey Delete\n" + type("db.scratch.find({ n: 3 })") + RUN + "snap found\n");
      assertTrue(near(c.tree("found"), "#result-status").contains("1 document"), c.tree("found"));
    } finally {
      c.run(app, "key A control\nkey Delete\n" + type("db.scratch.drop()") + RUN);
    }
  }

  @Test
  void aReadOnlyConnectionRefusesAWriteWithoutSendingIt() throws Exception {
    var app = console(docs().withReadOnly(true));
    c.run(app, type("db.orders.deleteMany({ paid: true })") + RUN + MESSAGES + "snap refused\n");
    var refused = c.tree("refused");
    assertTrue(refused.contains("The connection is read-only"), refused);
    assertFalse(refused.contains("role=dialog"), refused);
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.countDocuments({})") + RUN
            + "snap count\n");
    assertTrue(cell(c.tree("count"), 0, 0).contains("\"250\""), c.tree("count"));
  }

  @Test
  void afterDbTheCollectionsAreOffered() throws Exception {
    var app = console(docs());
    c.run(app, open("hoori") + SETTLE + "press #sql\ntype \"db.or\"\nframe\nsnap offered\n");
    var options = ConsoleEditorTest.options(c.tree("offered"));
    assertEquals("orders", options.getFirst(), options.toString());
  }

  @Test
  void stopReadingFreesTheOpenCursorAndLeavesTheLoadedDocuments() throws Exception {
    var app = console(docs());
    c.run(app, type("db.orders.find()") + RUN + "snap open\n");
    String open = c.tree("open");
    assertTrue(near(open, "#result-status").contains("200 documents so far · cursor open"), open);
    assertTrue(open.contains("role=button label=\"Stop reading\""), open);
    c.run(app, "press role=button label=\"Stop reading\"\nframe\n" + SETTLE + "snap stopped\n");
    String stopped = c.tree("stopped");
    assertTrue(
        near(stopped, "#result-status").contains("the first 200 documents; reading stopped"),
        stopped);
    assertFalse(stopped.contains("role=button label=\"Stop reading\""), stopped);
    // In JSON view, Next page is disabled because no more documents will be read.
    c.run(app, show("JSON") + "snap json\n");
    assertTrue(
        line(c.tree("json"), "role=button label=\"Next page\"").contains("disabled"),
        c.tree("json"));
    // The session stays usable for the next statement.
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.countDocuments({})") + RUN
            + "snap count\n");
    assertTrue(cell(c.tree("count"), 0, 0).contains("\"250\""), c.tree("count"));
  }
}

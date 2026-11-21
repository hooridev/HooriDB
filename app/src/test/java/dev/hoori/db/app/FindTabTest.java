package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.LEGACY;
import static dev.hoori.db.app.CatalogTreeTest.MYSQL;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.MonitorTabTest.line;
import static dev.hoori.db.app.MonitorTabTest.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.find.Found;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Find: a search of a schema's or database's objects and data, on a session of its own. */
class FindTabTest {

  static final String TAB = "shop-prod: Find in shop.public";
  static final Schema PUBLIC = new Schema(new Database("shop", false), "public", false);

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.defined(
        PG,
        new FakeServers.Defined(
            Found.Kind.FUNCTION,
            PUBLIC,
            null,
            "total_of",
            "CREATE FUNCTION public.total_of()\n RETURNS numeric\nAS $$ SELECT 1 /* a needle */ $$"),
        new FakeServers.Defined(
            Found.Kind.VIEW,
            PUBLIC,
            new Table(PUBLIC, "order_totals", Table.Kind.VIEW),
            "order_totals",
            " SELECT customer_id, sum(total) AS total FROM orders GROUP BY customer_id"));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with shop-prod connected and Find open from the menu of its schema public. */
  App<State> find() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE);
    c.run(app, menu("public", "Find…") + SETTLE);
    return app;
  }

  /** The lines of the results' grid, from its start. */
  static String results(String tree) {
    int at = tree.indexOf("label=\"Results\"");
    if (at < 0) {
      throw new AssertionError("No results in\n" + tree);
    }
    return tree.substring(at);
  }

  /** Types the text into the search's field and presses Enter. */
  static String search(String text) {
    return "press #find-text\ntype \"" + text + "\"\nkey Enter\nframe\n" + SETTLE;
  }

  @Test
  void aSchemasMenuOpensFindWhichFindsObjectsByNameOnASessionOfItsOwn() throws Exception {
    var app = find();
    c.run(app, search("cust") + "snap found\n");
    var tree = c.tree("found");
    assertTrue(tree.contains("label=\"" + TAB + "\""), tree);
    // The table and two columns by name, the view by its definition.
    assertTrue(tree.contains("\"4 objects\""), tree);
    var found = results(tree);
    assertTrue(row(found, 0).contains("\"customers\""), row(found, 0));
    assertTrue(row(found, 0).contains("\"Table\""), row(found, 0));
    assertTrue(row(found, 1).contains("\"customer_id\""), row(found, 1));
    assertTrue(row(found, 1).contains("\"orders\""), row(found, 1));
    assertTrue(row(found, 3).contains("\"order_totals\""), row(found, 3));
    assertTrue(row(found, 3).contains("Definition: SELECT customer_id"), row(found, 3));
    // Read anew on the second link, the search's own, not from what the tree read.
    assertTrue(
        c.servers.linked.contains("2 objects " + PG + "/shop.public"), c.servers.linked.toString());
    assertFalse(c.servers.calls.contains("columns " + PG + "/shop.public.customers"));
    // It went to the statement log as a search.
    assertTrue(
        app.state().statements.entries().stream()
            .anyMatch(e -> e.sent().text().equals("objects " + PG + "/shop.public")),
        app.state().statements.entries().toString());
  }

  @Test
  void openingATableOrAColumnSelectsItInTheTree() throws Exception {
    var app = find();
    c.run(app, search("cust") + "press #cell-0-0 clicks 2\nframe\n" + SETTLE + "snap table\n");
    assertTrue(line(c.tree("table"), "role=treeitem label=\"customers\"").contains("selected"));
    // A column: its table opens, and once its columns are read the column is selected.
    c.run(app, "press #cell-1-0 clicks 2\nframe\n" + SETTLE + "snap column\n");
    var tree = c.tree("column");
    assertTrue(line(tree, "role=treeitem label=\"orders\"").contains("expanded"), tree);
    assertTrue(line(tree, "role=treeitem label=\"customer_id\"").contains("selected"), tree);
  }

  @Test
  void aDefinitionOpensReadOnlyInAConsoleAtTheMatch() throws Exception {
    var app = find();
    c.run(app, search("needle") + "snap found\n");
    var found = results(c.tree("found"));
    assertTrue(row(found, 0).contains("\"Function\""), found);
    assertTrue(row(found, 0).contains("Definition: …"), row(found, 0));
    c.run(app, "press #cell-0-0 clicks 2\nframe\n" + SETTLE + "snap console\n");
    var tree = c.tree("console");
    assertTrue(tree.contains("label=\"shop-prod: shop\""), tree);
    var editor = line(tree, "role=textbox label=\"SQL\"");
    assertTrue(editor.contains("readonly"), editor);
    // The match selected.
    assertTrue(editor.endsWith("caret 71 (3:21) anchor 77"), editor);
    // It cannot run, not by its buttons nor by Control+Enter.
    assertTrue(line(tree, "role=button label=\"Run\"").contains("disabled"), tree);
    assertTrue(line(tree, "role=button label=\"Run all\"").contains("disabled"), tree);
    c.run(app, "key Enter control\nframe\nkey Enter control shift\nframe\n" + SETTLE);
    c.run(app, "snap ran\n");
    assertTrue(c.tree("ran").contains("A definition to read is not run"), c.tree("ran"));
    assertFalse(
        c.servers.calls.stream().anyMatch(x -> x.startsWith("run ")), c.servers.calls.toString());
    // Nor is it kept for the next start.
    var s = app.state();
    s.keeping.persist(s.keeping.consolesKept);
    c.run(app, SETTLE);
    assertEquals(List.of(), new ConsoleStore(dir.resolve("config")).load());
    // The view's text is found too, where its name does not match.
    c.run(app, "press role=tab label=\"" + TAB + "\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press #find-text\nkey A control\ntype \"sum(\"\nkey Enter\nframe\n" + SETTLE);
    c.run(app, "snap view\n");
    var view = results(c.tree("view"));
    assertTrue(row(view, 0).contains("\"order_totals\""), view);
    assertTrue(row(view, 0).contains("\"View\""), view);
  }

  @Test
  void dataShowsTheTablesAndTheirSizeThenSearchesEachInTurn() throws Exception {
    var app = find();
    c.run(app, "press role=radio label=\"Data\"\nframe\n" + SETTLE + "snap tables\n");
    var tables = c.tree("tables");
    // The tables are chosen, the view not.
    assertTrue(tables.contains("2 of 3 tables chosen, about 6 rows in 16 kB"), tables);
    c.run(app, search("name 2") + "snap hits\n");
    var hits = c.tree("hits");
    assertTrue(hits.contains("\"1 column with hits, 2 of 2 tables searched\""), hits);
    var results = results(hits);
    assertTrue(row(results, 0).contains("\"customers\""), results);
    assertTrue(row(results, 0).contains("\"name\""), results);
    assertTrue(row(results, 0).contains("\"1\""), results);
    assertTrue(c.servers.linked.contains("2 search " + PG + "/shop.public.customers"));
    assertTrue(c.servers.linked.contains("2 search " + PG + "/shop.public.orders"));
    assertFalse(c.servers.calls.contains("search " + PG + "/shop.public.order_totals"));
    // A search of objects meanwhile leaves the hits with the search that found them.
    c.run(
        app,
        "press role=radio label=\"Objects\"\nframe\nadvance 1\nframe\n"
            + "press #find-text\nkey A control\ntype \"orders\"\nkey Enter\nframe\n" + SETTLE
            + "press role=radio label=\"Data\"\nframe\nadvance 1\nframe\n");
    // Opening a hit, the first row of the results below the tables, opens a console with the
    // query of its rows, which runs.
    c.servers.statement(
        "SELECT * FROM \"public\".\"customers\" WHERE CAST(\"name\" AS pg_catalog.text)"
            + " COLLATE pg_catalog.\"default\""
            + " OPERATOR(pg_catalog.~~*) '%name 2%'",
        List.of(FakeServers.made(1, "id: integer", "name: text")));
    c.run(app, "press text=\"name\" clicks 2\nframe\n" + SETTLE + "snap rows\n");
    var console = c.tree("rows");
    assertTrue(
        console.contains("SELECT * FROM \\\"public\\\".\\\"customers\\\" WHERE CAST(\\\"name\\\" AS"
            + " pg_catalog.text) COLLATE pg_catalog.\\\"default\\\" OPERATOR(pg_catalog.~~*)"
            + " '%name 2%'"),
        console);
    assertTrue(console.contains("\"Ran in 4 ms · 1 row\""), console);
  }

  @Test
  void aSearchInDataShowsItsProgressAndCanBeCancelled() throws Exception {
    var app = find();
    c.run(app, "press role=radio label=\"Data\"\nframe\n" + SETTLE);
    var key = "search " + PG + "/shop.public.customers";
    var gate = c.servers.hold(key);
    c.run(app, "press #find-text\ntype \"42\"\nkey Enter\nframe\nadvance 1\nframe\nsnap running\n");
    var running = c.tree("running");
    assertTrue(running.contains("role=progressbar label=\"Searching customers\""), running);
    assertTrue(running.contains("value=\"0%\""), running);
    // Once the search has its call, which a cancel before it would take off the lane unrun.
    CatalogTreeTest.eventually(() -> c.servers.calls.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n");
    gate.countDown();
    c.run(app, SETTLE + "snap cancelled\n");
    var cancelled = c.tree("cancelled");
    assertTrue(c.servers.interrupted.contains(key), c.servers.interrupted.toString());
    assertFalse(c.servers.calls.contains("search " + PG + "/shop.public.orders"));
    assertTrue(cancelled.contains("0 of 2 tables searched, cancelled"), cancelled);
    assertTrue(cancelled.contains("role=button label=\"Find\""), cancelled);
  }

  @Test
  void aSearchInDataCancelledFromTheObjectsIsCancelledToo() throws Exception {
    var app = find();
    c.run(app, "press role=radio label=\"Data\"\nframe\n" + SETTLE);
    var key = "search " + PG + "/shop.public.customers";
    var gate = c.servers.hold(key);
    c.run(app, "press #find-text\ntype \"42\"\nkey Enter\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=radio label=\"Objects\"\nframe\nadvance 1\nframe\n");
    CatalogTreeTest.eventually(() -> c.servers.calls.contains(key));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n");
    gate.countDown();
    c.run(app, SETTLE + "press role=radio label=\"Data\"\nframe\nadvance 1\nframe\nsnap data\n");
    var data = c.tree("data");
    assertTrue(c.servers.interrupted.contains(key), c.servers.interrupted.toString());
    assertTrue(data.contains("0 of 2 tables searched, cancelled"), data);
  }

  @Test
  void onMySqlTheCaseOfDataFollowsTheCollation() throws Exception {
    var app = c.app(LEGACY);
    c.ready(app, LEGACY);
    c.run(app, START + open("legacy") + SETTLE);
    c.run(
        app,
        "press role=treeitem level=2 label=\"legacy\" right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Find…\"\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, "snap objects\n");
    assertFalse(line(c.tree("objects"), "role=checkbox label=\"Match case\"").contains("disabled"));
    c.run(app, "press role=radio label=\"Data\"\nframe\n" + SETTLE + "snap data\n");
    var data = c.tree("data");
    assertTrue(line(data, "role=checkbox label=\"Match case\"").contains("disabled"), data);
    assertTrue(
        c.servers.calls.contains("candidates " + MYSQL + "/legacy"), c.servers.calls.toString());
  }

  @Test
  void theFileMenuFindsInTheSchemaOfTheNodeSelected() throws Exception {
    var app = find();
    c.run(app, "key W control\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, "press role=treeitem label=\"public\"\nframe\n");
    c.run(app, "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=menuitem label=\"Find…\"\nframe\n" + SETTLE + "snap again\n");
    var tree = c.tree("again");
    assertEquals(1, tree.split("role=tab label=\"" + TAB, -1).length - 1, tree);
  }
}

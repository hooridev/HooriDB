package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleEditorTest.COMPLETE;
import static dev.hoori.db.app.ConsoleEditorTest.options;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Values;
import dev.hoori.db.core.execution.Result;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A console on a made-up MongoDB server, without Docker: what its editor suggests. */
class DocumentConsoleTest {

  static final String MONGO = "mongo.example.com";
  static final ConnectionProfile DOCS = ConnectionProfile.of(Provider.MONGODB)
      .withName("docs")
      .withHost(MONGO)
      .withDatabase("hoori")
      .withUser("app");

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.serve(MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
            customer: string
            total: double
          customers
            _id: objectId
            name: string
        """);
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with a console on the connection, whose database the tree has read. */
  App<State> console() throws Exception {
    var app = c.app(DOCS);
    c.ready(app, DOCS);
    c.run(
        app,
        START
            + open(DOCS.name())
            + SETTLE
            + open("hoori")
            + SETTLE
            + open("orders")
            + SETTLE
            + menu(DOCS.name(), "New console")
            + SETTLE);
    return app;
  }

  /** The completions offered for the text, typed in one edit, at its end. */
  List<String> offered(App<State> app, String text) throws Exception {
    c.run(
        app, "press #sql\nkey A control\nkey Delete\n" + type(text) + COMPLETE + "snap offered\n");
    var options = options(c.tree("offered"));
    c.run(app, "key Escape\nframe\nadvance 1\nframe\n");
    return options;
  }

  @Test
  void afterDbItsCollectionsAndMethods() throws Exception {
    var app = console();
    var options = offered(app, "db.");
    assertEquals(List.of("customers", "orders"), options.subList(0, 2), options.toString());
    assertEquals("runCommand", offered(app, "db.ru").getFirst());
    assertEquals("orders", offered(app, "db.or").getFirst());
  }

  @Test
  void afterACollectionItsMethodsAndAfterACallACursorsMethods() throws Exception {
    var app = console();
    var methods = offered(app, "db.orders.");
    assertTrue(methods.containsAll(List.of("aggregate", "deleteMany")), methods.toString());
    assertEquals("updateMany", offered(app, "db.orders.updateM").getFirst());
    var named = offered(app, "db.getCollection('orders',).");
    assertTrue(named.containsAll(List.of("aggregate", "deleteMany")), named.toString());
    var cursor = offered(app, "db.orders.find().");
    assertTrue(cursor.containsAll(List.of("sort", "limit", "toArray")), cursor.toString());
    assertTrue(!cursor.contains("deleteMany"), cursor.toString());
  }

  @Test
  void inADocumentTheFieldsOfTheCollectionAndAfterADollarTheOperators() throws Exception {
    var app = console();
    var fields = offered(app, "db.orders.find({ ");
    assertEquals(List.of("_id", "customer", "total"), fields.subList(0, 3), fields.toString());
    assertTrue(fields.contains("ObjectId"), fields.toString());
    var named = offered(app, "db.getCollection('orders').find({ ");
    assertEquals(List.of("_id", "customer", "total"), named.subList(0, 3), named.toString());
    var operators = offered(app, "db.orders.find({ total: { $g");
    assertTrue(operators.containsAll(List.of("$gt", "$gte")), operators.toString());
    assertTrue(operators.stream().allMatch(o -> o.startsWith("$")), operators.toString());
    // A dollar typed opens them at once.
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.find({ total: { ")
            + "type \"$\"\nframe\nadvance 1\nframe\nsnap dollar\n");
    assertEquals("$addFields", options(c.tree("dollar")).getFirst(), c.tree("dollar"));
    c.run(app, "key Escape\nframe\nadvance 1\nframe\n");
    // The kit's word has no dollar: the operator goes in after the one typed.
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.find({ total: { $gt") + COMPLETE
            + "key Enter\nframe\n");
    assertEquals("db.orders.find({ total: { $gt", app.state().consoles(null).getFirst().text);
  }

  /** Nothing in a string, a comment or a regular expression, as in one of SQL. */
  @Test
  void inAStringACommentOrARegularExpressionNothing() throws Exception {
    var app = console();
    assertEquals(List.of(), offered(app, "db.orders.find({ note: 'db."));
    assertEquals(List.of(), offered(app, "db.orders.find() // db."));
    assertEquals(List.of(), offered(app, "db.orders.find({ note: /db."));
    assertEquals(List.of(), offered(app, "db.orders.find({ name: /Ada/i"));
    // Nor between the two characters of a comment's opener.
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.find() // x") + "key End\n"
            + "key ArrowLeft\n".repeat(3) + COMPLETE + "snap opener\n");
    assertEquals(List.of(), options(c.tree("opener")), c.tree("opener"));
    c.run(app, "key Escape\nframe\nadvance 1\nframe\n");
    // Nor where the word goes on a name or a number, as after a dollar or a point typed there.
    assertEquals(List.of(), offered(app, "db.orders.find({ total$"));
    assertEquals(List.of(), offered(app, "db.orders.find({ total: 1."));
    // After the string, the fields again.
    assertEquals("customer", offered(app, "db.orders.find({ note: 'db.', cu").getFirst());
  }

  @Test
  void controlAltLLaysTheStatementsOutOrMarksWhereOneGoesWrong() throws Exception {
    var app = console();
    c.run(
        app,
        "press #sql\nkey A control\nkey Delete\n" + type("db.orders.find({customer:'Ada'}) // hers")
            + "key L control alt\nframe\n");
    assertEquals("db.orders.find({ customer: 'Ada' }) // hers", tab(app).text);
    c.run(
        app,
        "key A control\nkey Delete\n" + type("db.orders.find({customer:})")
            + "key L control alt\nframe\nsnap wrong\n");
    assertEquals("db.orders.find({customer:})", tab(app).text);
    assertTrue(c.tree("wrong").contains("Not expected here"), c.tree("wrong"));
  }

  ConsoleTab tab(App<State> app) {
    return app.state().consoles(null).getFirst();
  }

  /**
   * The documentView setting sets the first view of a MongoDB collection tab and of a MongoDB
   * console result, leaves SQL tables in Grid, and MongoDB consoles stay Auto even when Manual is
   * configured.
   */
  @Test
  void documentViewSetsTheFirstViewOfCollectionsAndResultsAndMongoStaysAuto() throws Exception {
    var orderDocs = List.of(Document.of("_id", 1, "customer", "Ada", "total", 12.5));
    var customerDocs = List.of(Document.of("_id", 2, "name", "Grace"));
    c.servers.documents(MONGO + "/hoori.orders", orderDocs);
    c.servers.documents(MONGO + "/hoori.customers", customerDocs);
    var fields = Values.found(orderDocs);
    var rows = orderDocs.stream().map(d -> Values.row(fields, d)).toList();
    var query = "db.orders.find()";
    c.servers.statement(query, List.of(new Result.Rows(fields, 0, rows, orderDocs)));

    new SettingsStore(dir.resolve("config"))
        .save(Settings.DEFAULT
            .withDocumentView(Settings.DocumentView.JSON)
            .withCommitMode(Settings.CommitMode.MANUAL));
    var app = c.app(DOCS, SHOP);
    c.ready(app, DOCS, SHOP);
    c.run(
        app,
        START + open(DOCS.name()) + SETTLE + open("hoori") + SETTLE + table("orders") + SETTLE
            + "snap collection-json\n");
    var collectionJson = c.tree("collection-json");
    assertTrue(
        line(collectionJson, "role=radio label=\"JSON\"").contains("checked"), collectionJson);
    assertTrue(
        line(collectionJson, "#json").contains("\\\"customer\\\": \\\"Ada\\\""), collectionJson);
    assertFalse(collectionJson.contains("label=\"Open in console\""), collectionJson);

    // SQL tables still open in Grid even when documentView is JSON.
    c.run(
        app,
        open(SHOP.name()) + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + "press role=treeitem label=\"orders\" level=4\nkey Enter\nframe\n" + SETTLE
            + "snap sql-grid\n");
    var sqlGrid = c.tree("sql-grid");
    assertTrue(sqlGrid.contains("#grid"), sqlGrid);
    assertFalse(sqlGrid.contains("#json"), sqlGrid);
    assertTrue(sqlGrid.contains("label=\"Open in console\""), sqlGrid);
    assertEquals(
        DocumentPresentation.Mode.GRID,
        app.state().tables(SHOP.id()).getFirst().presentation.mode());

    // Switching documentView to TREE opens the next collection in Tree mode.
    app.post(() ->
        app.state().choose(app.state().settings.withDocumentView(Settings.DocumentView.TREE)));
    c.run(
        app,
        "await\nframe\npress role=treeitem label=\"customers\" level=3\nkey Enter\nframe\n" + SETTLE
            + "snap collection-tree\n");
    var collectionTree = c.tree("collection-tree");
    assertTrue(
        line(collectionTree, "role=radio label=\"Tree\"").contains("checked"), collectionTree);
    assertTrue(collectionTree.contains("#documents"), collectionTree);

    // MongoDB console stays Auto even when commitMode is MANUAL, and results use documentView.
    app.post(() ->
        app.state().choose(app.state().settings.withDocumentView(Settings.DocumentView.JSON)));
    c.run(
        app,
        "await\nframe\n" + menu(DOCS.name(), "New console") + SETTLE + type(query) + RUN
            + "snap result-json\n");
    assertEquals(ConsoleTab.Mode.AUTO, tab(app).mode);
    var resultJson = c.tree("result-json");
    assertTrue(line(resultJson, "role=radio label=\"JSON\"").contains("checked"), resultJson);
    assertTrue(line(resultJson, "#json").contains("\\\"customer\\\": \\\"Ada\\\""), resultJson);
  }
}

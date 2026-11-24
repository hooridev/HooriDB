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
import static dev.hoori.db.app.LayoutTest.widthOf;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.kit.Split;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Values;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    // The name's escapes are read, as the server reads them (the script reads one backslash).
    var escaped = offered(app, "db.getCollection('\\\\x6frders').find({ ");
    assertEquals(List.of("_id", "customer", "total"), escaped.subList(0, 3), escaped.toString());
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

  /** Picks a presentation mode from the console result's documents select. */
  static String show(String mode) {
    return "press #result-shown\nframe\nadvance 1\nframe\n" + "press role=menuitem label=\"" + mode
        + "\"\nframe\nadvance 1\nframe\n";
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
    assertTrue(collectionJson.contains("label=\"Open in console\""), collectionJson);

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
    assertTrue(
        line(resultJson, "#result-shown ").contains("role=combobox label=\"Show documents as\""),
        resultJson);
    assertTrue(ConsoleTest.near(resultJson, "#result-shown").contains("\"JSON\""), resultJson);
    assertTrue(line(resultJson, "#json").contains("\\\"customer\\\": \\\"Ada\\\""), resultJson);
  }

  /**
   * An empty MongoDB console result keeps the view switcher, says 0 documents, and opens in the
   * configured {@code documentView}, while an empty SQL result stays a plain grid saying 0 rows.
   */
  @Test
  void anEmptyResultUsesTheConsoleLanguageForTheSwitcherAndFirstView() throws Exception {
    var mongoQuery = "db.orders.find({ _id: \"none\" })";
    var sqlQuery = "SELECT id FROM orders WHERE 1 = 0";
    c.servers.statement(mongoQuery, List.of(new Result.Rows(List.of(), 0, List.of(), List.of())));
    c.servers.statement(
        sqlQuery,
        List.of(new Result.Rows(List.of(new Field("id", "int4", Kind.INTEGER)), 0, List.of())));

    new SettingsStore(dir.resolve("config"))
        .save(Settings.DEFAULT.withDocumentView(Settings.DocumentView.JSON));
    var app = c.app(DOCS, SHOP);
    c.ready(app, DOCS, SHOP);
    c.run(
        app,
        START + open(DOCS.name()) + SETTLE + menu(DOCS.name(), "New console") + SETTLE
            + type(mongoQuery) + RUN + "snap empty-json\n");
    var emptyJson = c.tree("empty-json");
    assertTrue(
        line(emptyJson, "#result-shown ").contains("role=combobox label=\"Show documents as\""),
        emptyJson);
    assertTrue(ConsoleTest.near(emptyJson, "#result-shown").contains("\"JSON\""), emptyJson);
    assertTrue(emptyJson.contains("#no-documents"), emptyJson);
    assertTrue(line(emptyJson, "#rows-in-view").contains("\"No documents\""), emptyJson);
    assertTrue(ConsoleTest.near(emptyJson, "#result-status").contains("0 documents"), emptyJson);
    assertTrue(line(emptyJson, "role=button label=\"Next page\"").contains("disabled"), emptyJson);

    c.run(app, show("Tree") + "snap empty-tree\n" + show("Grid") + "snap empty-grid\n");
    var emptyTree = c.tree("empty-tree");
    assertTrue(ConsoleTest.near(emptyTree, "#result-shown").contains("\"Tree\""), emptyTree);
    assertTrue(emptyTree.contains("#no-documents"), emptyTree);
    var emptyGrid = c.tree("empty-grid");
    assertTrue(ConsoleTest.near(emptyGrid, "#result-shown").contains("\"Grid\""), emptyGrid);
    assertTrue(emptyGrid.contains("#grid"), emptyGrid);
    assertTrue(ConsoleTest.near(emptyGrid, "#result-status").contains("0 documents"), emptyGrid);

    app.post(() ->
        app.state().choose(app.state().settings.withDocumentView(Settings.DocumentView.TREE)));
    c.run(app, "await\nframe\npress #sql\n" + RUN + "snap empty-default-tree\n");
    var emptyDefaultTree = c.tree("empty-default-tree");
    assertTrue(
        ConsoleTest.near(emptyDefaultTree, "#result-shown").contains("\"Tree\""), emptyDefaultTree);
    assertTrue(emptyDefaultTree.contains("#no-documents"), emptyDefaultTree);

    // An empty SQL result opens in Grid without a view switcher even when documentView is TREE.
    c.run(
        app,
        open(SHOP.name()) + SETTLE + menu(SHOP.name(), "New console") + SETTLE + type(sqlQuery)
            + RUN + "snap empty-sql\n");
    var emptySql = c.tree("empty-sql");
    assertTrue(emptySql.contains("#grid"), emptySql);
    assertFalse(emptySql.contains("#result-shown"), emptySql);
    assertFalse(emptySql.contains("#no-documents"), emptySql);
    assertTrue(ConsoleTest.near(emptySql, "#result-status").contains("0 rows"), emptySql);
  }

  @Test
  void errorsStayWithInlineDiagnosticsAndGutterMarkersWorkInMongoDbConsole() throws Exception {
    var orderDocs = List.of(Document.of("_id", 1, "customer", "Ada", "total", 12.5));
    var fields = Values.found(orderDocs);
    var rows = orderDocs.stream().map(d -> Values.row(fields, d)).toList();
    var ok = "db.orders.find()";
    var bad = "db.orders.find({ a: 1 b: 2 })";
    c.servers.statement(ok, List.of(new Result.Rows(fields, 0, rows, orderDocs)));
    c.servers.statement(
        bad,
        List.of(new Failure(
            Failure.Kind.INVALID,
            "A comma or } is missing",
            "",
            0,
            bad.indexOf("b:") + 1,
            "",
            0,
            0,
            "",
            "",
            "",
            "")));
    var app = console();
    var text = ok + ";\n" + bad + ";";
    int badAt = text.indexOf(bad) + bad.indexOf("b:");
    c.run(app, type(text) + "frame\nsnap before\n");
    String before = c.tree("before");
    assertTrue(
        ConsoleEditorTest.gutterLine(before, 0).contains("role=button label=\"Run statement\""),
        before);
    assertTrue(
        ConsoleEditorTest.gutterLine(before, 1).contains("role=button label=\"Run statement\""),
        before);

    // Run all: line 0 succeeds with 1 document, line 1 fails with persistent error & inline text.
    c.run(app, ConsoleTest.RUN_ALL + "snap ran\n");
    String ran = c.tree("ran");
    assertTrue(
        ConsoleEditorTest.gutterLine(ran, 0)
            .contains("role=button label=\"Ran in 4 ms · 1 document\""),
        ran);
    assertTrue(
        ConsoleEditorTest.gutterLine(ran, 1).contains("role=button label=\"Failed after 4 ms\""),
        ran);
    assertTrue(line(ran, "#sql").contains("squiggle " + badAt + "-" + (badAt + 1)), ran);
    assertTrue(line(ran, "#end-1").contains("A comma or } is missing"), ran);

    // Inserting a line at the top shifts error, inline diagnostic, and both markers down a line.
    c.run(app, "press #sql\nkey Home control\nkey Enter\nframe\nsnap moved\n");
    String moved = c.tree("moved");
    assertTrue(line(moved, "#sql").contains("squiggle " + (badAt + 1) + "-" + (badAt + 2)), moved);
    assertTrue(line(moved, "#end-2").contains("A comma or } is missing"), moved);
    assertTrue(
        ConsoleEditorTest.gutterLine(moved, 1)
            .contains("role=button label=\"Ran in 4 ms · 1 document\""),
        moved);
    assertTrue(
        ConsoleEditorTest.gutterLine(moved, 2).contains("role=button label=\"Failed after 4 ms\""),
        moved);

    // SQL-only quick documentation and go-to-table do not activate in a MongoDB console.
    int ordersAt = tab(app).text.indexOf("orders") + 2;
    tab(app).editor.select(ordersAt, ordersAt);
    app.invalidate();
    c.run(
        app, "frame\nkey Q control\nframe\nadvance 1\nframe\nsnap no-doc\nkey B control\nframe\n");
    assertFalse(c.tree("no-doc").contains("#documentation"), c.tree("no-doc"));
    assertEquals(0, app.state().tables(DOCS.id()).size());
  }

  @Test
  void aMongoDbOpenCursorStopsOnStopReadingAndAfterBeingIdle() throws Exception {
    var docs = java.util.stream.IntStream.rangeClosed(1, 500)
        .mapToObj(i -> Document.of("_id", i, "customer", "c" + i, "total", (double) i))
        .toList();
    var fields = Values.found(docs);
    var rows = docs.stream().map(d -> Values.row(fields, d)).toList();
    var query = "db.orders.find()";
    c.servers.statement(query, List.of(new Result.Rows(fields, 0, rows, docs)));
    var app = console();
    c.run(app, type(query) + RUN + "snap open\n");
    String open = c.tree("open");
    assertTrue(
        ConsoleTest.near(open, "#result-status").contains("200 documents so far · cursor open"),
        open);
    assertTrue(open.contains("role=button label=\"Stop reading\""), open);

    c.run(app, "press role=button label=\"Stop reading\"\nframe\n" + SETTLE + "snap stopped\n");
    assertTrue(
        c.servers.calls.contains("close " + MONGO + " " + query), c.servers.calls.toString());
    var output = tab(app).outputs.getFirst();
    assertFalse(output.more);
    assertTrue(output.cut);
    assertEquals(200, output.documents.size());
    assertTrue(
        ConsoleTest.near(c.tree("stopped"), "#result-status")
            .contains("the first 200 documents; reading stopped"),
        c.tree("stopped"));
    assertFalse(
        c.tree("stopped").contains("role=button label=\"Stop reading\""), c.tree("stopped"));
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE);
    assertFalse(
        c.servers.calls.contains("next " + MONGO + " " + query + " 200"),
        c.servers.calls.toString());

    // Idle give-up closes the cursor too.
    c.servers.calls.clear();
    c.run(
        app,
        "press #sql\n" + RUN + "advance " + ConsoleTab.IDLE_SECONDS + "\nframe\n" + SETTLE
            + "snap idle\n");
    assertTrue(
        c.servers.calls.contains("close " + MONGO + " " + query), c.servers.calls.toString());
    assertTrue(
        ConsoleTest.near(c.tree("idle"), "#result-status")
            .contains("the first 200 documents; reading stopped after being idle"),
        c.tree("idle"));
  }

  /**
   * In a narrow MongoDB result footer with the cursor open, Stop reading, the documents picker,
   * paging, and Pin stay inside the card across Grid, JSON, and Tree views.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 400})
  void aNarrowResultFooterKeepsTheStatusVisibleAndPagingInside(int sidebar) throws Exception {
    var docs = java.util.stream.IntStream.rangeClosed(1, 500)
        .mapToObj(i -> Document.of("_id", i, "customer", "c" + i, "total", (double) i))
        .toList();
    var fields = Values.found(docs);
    var rows = docs.stream().map(d -> Values.row(fields, d)).toList();
    var query = "db.orders.find()";
    c.servers.statement(query, List.of(new Result.Rows(fields, 0, rows, docs)));
    var app = console();
    if (sidebar > 0) {
      app.state().sidebar = Split.Position.first(sidebar);
    }
    c.run(
        app,
        "size 800 760\nframe\n"
            + type(query)
            + RUN
            + "snap narrow-grid\n"
            + show("JSON")
            + "snap narrow-json\n"
            + show("Tree")
            + "snap narrow-tree\n");
    String grid = c.tree("narrow-grid");
    assertTrue(
        widthOf(grid, "#result-status ") >= (sidebar > 0 ? 60 : 140),
        "grid status=" + widthOf(grid, "#result-status ") + "\n" + grid);
    for (String control : List.of("#stop-reading ", "#result-shown ", "#pin ")) {
      assertTrue(
          TableTabTest.end(grid, control) <= TableTabTest.end(grid, "#grid "),
          control + "\n" + grid);
    }

    for (String view : List.of("json", "tree")) {
      String tree = c.tree("narrow-" + view);
      assertTrue(tree.contains("#stop-reading "), tree);
      assertTrue(tree.contains("#result-shown "), tree);
      assertTrue(tree.contains("#paging "), tree);
      assertEquals(0, widthOf(tree, "#rows-in-view "), tree);
      float statusWidth = widthOf(tree, "#result-status ");
      assertTrue(statusWidth >= (sidebar > 0 ? 50 : 70), "status=" + statusWidth + "\n" + tree);
      float edge = TableTabTest.end(tree, view.equals("json") ? "#json " : "#documents ");
      for (String control : List.of("#stop-reading ", "#result-shown ", "#paging ", "#pin ")) {
        assertTrue(TableTabTest.end(tree, control) <= edge, control + "\n" + tree);
      }
    }
  }
}

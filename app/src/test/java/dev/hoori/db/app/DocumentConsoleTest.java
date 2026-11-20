package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConsoleEditorTest.COMPLETE;
import static dev.hoori.db.app.ConsoleEditorTest.options;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
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
    // Nor where the word goes on a name or a number, as after a dollar or a point typed there.
    assertEquals(List.of(), offered(app, "db.orders.find({ total$"));
    assertEquals(List.of(), offered(app, "db.orders.find({ total: 1."));
    // After the string, the fields again.
    assertEquals("customer", offered(app, "db.orders.find({ note: 'db.', cu").getFirst());
  }
}

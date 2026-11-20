package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A MongoDB connection in the app, against core's real server in Docker: its databases and
 * collections in the tree, a collection's documents in a tab. Without Docker it is skipped.
 */
class DocumentsServerTest {

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();
  ConnectionProfile profile;
  App<State> app;

  @BeforeEach
  void setUp() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    c.dir = dir;
    c.setUp();
    profile = server.profile().withName("docs");
    // The app's tests load made-up drivers; its connections go through the real one.
    Servers real = (p, password, driver) -> Servers.SERVER.open(p, password, server.driver());
    app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
    c.ready(app, profile);
    app.state().rememberPassword(profile.id(), TestServer.password());
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The rows of the tree of documents, after those of the connections' tree. */
  static List<String> documents(String tree) {
    var rows = rows(tree);
    return rows.subList(rows.indexOf("1"), rows.size());
  }

  /** The app with the database hoori open in the tree. */
  void hoori() throws Exception {
    c.run(app, START + open("docs") + SETTLE + open("hoori") + SETTLE);
  }

  @Test
  void theTreeHasDatabasesCollectionsAndFields() throws Exception {
    hoori();
    c.run(app, open("customers") + SETTLE + "snap tree\n");
    var rows = rows(c.tree("tree"));
    assertTrue(rows.containsAll(List.of("docs", "  hoori", "    customers")), rows.toString());
    var at = rows.indexOf("    customers");
    assertEquals(
        List.of(
            "      _id",
            "      name",
            "      email",
            "      born",
            "      tags",
            "      address",
            "      score",
            "    empty",
            "    orders",
            "    paid_orders"),
        rows.subList(at + 1, at + 11));
    assertTrue(rows.contains("  admin"), rows.toString());
  }

  @Test
  void aCollectionOpensInATabWithItsDocumentsInTheGrid() throws Exception {
    hoori();
    c.run(app, table("orders") + SETTLE + "snap orders\n");
    var tree = c.tree("orders");
    assertTrue(line(tree, "#column-0").contains("label=\"_id\""), tree);
    assertTrue(line(tree, "#column-2").contains("label=\"total\""), tree);
    assertTrue(cell(tree, 0, 0).contains("\"1\""), tree);
    assertTrue(cell(tree, 0, 2).contains("\"1.5\""), tree);
    assertTrue(TableTabTest.status(tree).contains("\"250 documents\""), tree);
    c.run(app, table("customers") + SETTLE + "snap customers\n");
    var customers = c.tree("customers");
    // Relaxed Extended JSON, as the element tree writes a text with quotes.
    assertTrue(cell(customers, 0, 4).contains("[\\\"math\\\", \\\"poetry\\\"]"), customers);
    assertTrue(cell(customers, 0, 3).contains("\"1815-12-10 00:53:28+00:53:28\""), customers);
  }

  @Test
  void aFieldADocumentLacksIsAnEmptyCellAndANullIsNull() throws Exception {
    hoori();
    c.run(
        app,
        table("customers") + SETTLE
            + "clipboard \"x\"\npress #cell-2-2\nframe\nkey C control\nframe\n"
            + "key Enter shift\nadvance 1\nframe\nsnap missing\n");
    // Grace's email is null; Edsger has none.
    var missing = c.tree("missing");
    assertTrue(cell(missing, 1, 2).contains("\"NULL\""), missing);
    assertFalse(cell(missing, 2, 2).contains("NULL"), missing);
    // The kit's empty field, quoted where it is alone on its line.
    assertEquals("\"\"", app.clipboard().read());
    assertTrue(missing.contains("\"No such field\""), missing);
  }

  @Test
  void theDocumentsShowAsJsonAndAsATreeAPageAtATime() throws Exception {
    hoori();
    c.run(app, table("customers") + SETTLE + """
        press role=radio label="JSON"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap json
        press role=radio label="Tree"
        frame
        advance 1
        frame
        snap tree
        """);
    var json = c.tree("json");
    var editor = line(json, "#json");
    assertTrue(editor.contains("readonly"), editor);
    // An array of Relaxed Extended JSON, formatted: its start and its end are in the tree.
    assertTrue(editor.contains("\"[\\n    {\\n        \\\"_id\\\": 1,"), editor);
    assertTrue(editor.contains("\\\"score\\\": 42\\n    }\\n]\""), editor);
    assertTrue(line(json, "#rows-in-view").contains("\"Documents 1–3\""), json);
    assertTrue(line(json, "role=button label=\"Next page\"").contains("disabled"), json);
    var tree = c.tree("tree");
    assertEquals(List.of("1", "2", "3"), documents(tree));
    c.run(app, """
        press role=treeitem label="1"
        key ArrowRight
        frame
        snap open
        """);
    assertEquals(
        List.of("1", "  _id", "  name", "  email", "  born", "  tags", "  address", "2", "3"),
        documents(c.tree("open")));
    assertTrue(
        line(c.tree("open"), "role=treeitem label=\"name\"").contains("value=\"'Ada'\""),
        c.tree("open"));
    // A moment as the grid shows it, in the computer's zone, here Berlin's of 1815.
    assertTrue(
        line(c.tree("open"), "role=treeitem label=\"born\"")
            .contains("value=\"1815-12-10 00:53:28+00:53:28 · date\""),
        c.tree("open"));
  }

  @Test
  void theNextPageOfDocumentsIsReadAsItIsTurnedTo() throws Exception {
    hoori();
    c.run(app, table("orders") + SETTLE + """
        press role=radio label="JSON"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap first
        press role=button label="Next page"
        frame
        """ + SETTLE + """
        advance 1
        frame
        snap second
        """);
    assertTrue(
        line(c.tree("first"), "#rows-in-view").contains("\"Documents 1–200\""), c.tree("first"));
    var second = c.tree("second");
    assertTrue(line(second, "#rows-in-view").contains("\"Documents 201–250\""), second);
    assertTrue(line(second, "#json").contains("\\\"_id\\\": 201"), second);
    assertTrue(line(second, "role=button label=\"Next page\"").contains("disabled"), second);
  }
}

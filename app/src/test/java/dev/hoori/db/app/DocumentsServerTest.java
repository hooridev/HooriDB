package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.row;
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
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
    Servers real = (p, password, driver, timeout) ->
        Servers.SERVER.open(p, password, server.driver(), timeout);
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
    return rows(tree.substring(tree.indexOf("#documents")));
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
            "      Indexes",
            "    empty",
            "    orders",
            "    paid_orders"),
        rows.subList(at + 1, at + 12));
    assertTrue(rows.contains("  admin"), rows.toString());

    c.run(app, open("Indexes") + "snap customer-indexes\n");
    var customerIndexes = c.tree("customer-indexes");
    assertTrue(
        row(customerIndexes, "_id_").contains("\"(_id: 1) · primary key\""),
        row(customerIndexes, "_id_"));
    assertTrue(
        row(customerIndexes, "email_1").contains("\"(email: 1) · unique · sparse\""),
        row(customerIndexes, "email_1"));
    assertTrue(
        row(customerIndexes, "name_text_tags_text").contains("\"(name: text, tags: text)\""),
        row(customerIndexes, "name_text_tags_text"));

    c.run(
        app,
        "press role=treeitem label=\"customers\"\nkey ArrowLeft\nframe\n"
            + open("orders")
            + SETTLE
            + open("Indexes")
            + open("paid_orders")
            + SETTLE
            + "snap order-indexes\n");
    var orderIndexes = c.tree("order-indexes");
    assertTrue(
        row(orderIndexes, "_id_").contains("\"(_id: 1) · primary key\""),
        row(orderIndexes, "_id_"));
    assertTrue(
        row(orderIndexes, "customer_1").contains("\"(customer: 1)\""),
        row(orderIndexes, "customer_1"));
    assertTrue(
        row(orderIndexes, "customer_1_total_-1").contains("\"(customer: 1, total: -1) · partial\""),
        row(orderIndexes, "customer_1_total_-1"));
    assertTrue(
        row(orderIndexes, "expiresAt_1").contains("\"(expiresAt: 1) · TTL 3600 s\""),
        row(orderIndexes, "expiresAt_1"));
    var indexRows = rows(orderIndexes);
    int paidAt = indexRows.indexOf("    paid_orders");
    assertFalse(
        indexRows.subList(paidAt + 1, indexRows.size()).stream()
            .takeWhile(r -> r.startsWith("      "))
            .anyMatch("      Indexes"::equals),
        indexRows.toString());
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

  @Test
  void filterSortAndHeaderSortWorkAcrossGridJsonAndTree() throws Exception {
    hoori();
    c.run(app, table("orders") + SETTLE + """
            press role=textbox label="Filter"
            clipboard "{ paid: true }"
            key V control
            frame
            press role=textbox label="Sort"
            clipboard "{ total: -1 }"
            key V control
            frame
            key Enter
            frame
            """ + SETTLE + """
            snap filtered-grid
            press role=radio label="JSON"
            frame
            advance 1
            frame
            snap filtered-json
            press role=radio label="Tree"
            frame
            advance 1
            frame
            snap filtered-tree
            """);
    var grid = c.tree("filtered-grid");
    assertTrue(TableTabTest.status(grid).contains("\"125 documents\""), grid);
    assertTrue(cell(grid, 0, 0).contains("\"250\""), grid);
    assertTrue(cell(grid, 1, 0).contains("\"248\""), grid);
    var json = c.tree("filtered-json");
    assertTrue(line(json, "#rows-in-view").contains("\"Documents 1–125\""), json);
    assertTrue(line(json, "#json").contains("\\\"_id\\\": 250"), json);
    var tree = c.tree("filtered-tree");
    assertEquals("250", documents(tree).getFirst());

    // Clear with {}, turn to page 2 in JSON, then apply a new filter: resets to page 1.
    c.run(app, """
        press role=radio label="JSON"
        frame
        advance 1
        frame
        press role=textbox label="Filter"
        key A control
        clipboard "{}"
        key V control
        frame
        press role=textbox label="Sort"
        key A control
        clipboard "{}"
        key V control
        frame
        key Enter
        frame
        """ + SETTLE + """
            press role=button label="Next page"
            frame
            """ + SETTLE + """
            advance 1
            frame
            snap page-two
            press role=textbox label="Filter"
            key A control
            clipboard "{ _id: { $lte: 5 } }"
            key V control
            frame
            key Enter
            frame
            """ + SETTLE + """
            advance 1
            frame
            snap reset-page
            """);
    assertTrue(
        line(c.tree("page-two"), "#rows-in-view").contains("\"Documents 201–250\""),
        c.tree("page-two"));
    var reset = c.tree("reset-page");
    assertTrue(line(reset, "#rows-in-view").contains("\"Documents 1–5\""), reset);
    assertTrue(TableTabTest.status(reset).contains("\"5 documents\""), reset);

    // Header sort in Grid writes { total: 1 } and { total: -1 } and orders on the server.
    c.run(app, """
        press role=radio label="Grid"
        frame
        advance 1
        frame
        press role=columnheader label="total"
        frame
        """ + SETTLE + """
            snap asc
            press role=columnheader label="total"
            frame
            """ + SETTLE + "snap desc\n");
    var asc = c.tree("asc");
    assertTrue(line(asc, "#order").contains("\"{ total: 1 }\""), asc);
    assertTrue(cell(asc, 0, 0).contains("\"1\""), asc);
    var desc = c.tree("desc");
    assertTrue(line(desc, "#order").contains("\"{ total: -1 }\""), desc);
    assertTrue(cell(desc, 0, 0).contains("\"5\""), desc);
  }

  @Test
  void insertEditConflictCopyAndDeleteWorkAgainstRealServer() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var name = "app_edit_" + UUID.randomUUID().toString().replace("-", "");
    var opened = dev.hoori.db.core.connect.DocumentConnector.open(
        server.profile(), TestServer.password(), server.driver(), java.time.Duration.ofSeconds(10));
    try (var session = new DocumentSession(opened.client(), Provider.MONGODB.documents(), false)) {
      session.command(
          TestServer.DATABASE,
          Document.of(
              "insert",
              name,
              "documents",
              Document.array(Document.of("_id", 1, "name", "Ada", "score", 42L))));
      try {
        hoori();
        c.run(app, table(name) + SETTLE);

        // Insert a new document.
        c.run(
            app,
            "press #insert-document\nframe\n"
                + ConsoleTransactions.OPENED
                + "press #document-editor\nkey A control\n"
                + "clipboard \"{ _id: 2, name: 'Grace', score: Long('7') }\"\n"
                + "key V control\nframe\n"
                + "press #document-save\nframe\n"
                + ConsoleTransactions.OPENED
                + "press #document-preview-confirm\nframe\n"
                + SETTLE
                + ConsoleTransactions.OPENED
                + "snap inserted\n");
        assertTrue(TableTabTest.status(c.tree("inserted")).contains("\"2 documents\""));

        // Edit row 0, but change it behind the tab first: conflict keeps text and writes nothing.
        c.run(
            app,
            "press #cell-0-1\nframe\npress #edit-document\nframe\n" + ConsoleTransactions.OPENED);
        var tab = app.state().tables(profile.id()).getFirst();
        var edited = tab.documentDialog.text.replace("Ada", "Alicia");
        session.command(
            TestServer.DATABASE,
            Document.of(
                "update",
                name,
                "updates",
                Document.array(Document.of(
                    "q",
                    Document.of("_id", 1),
                    "u",
                    Document.of("$set", Document.of("score", 99L))))));
        var escaped = edited.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        c.run(
            app,
            "press #document-editor\nkey A control\nclipboard \"" + escaped
                + "\"\nkey V control\nframe\n"
                + "press #document-save\nframe\n"
                + ConsoleTransactions.OPENED
                + "press #document-preview-confirm\nframe\n"
                + SETTLE
                + ConsoleTransactions.OPENED
                + "snap conflict\n");
        assertTrue(c.tree("conflict").contains("Document changed"), c.tree("conflict"));
        assertTrue(tab.documentDialog.text.contains("Alicia"), tab.documentDialog.text);
        c.run(app, "press #document-cancel\nframe\n" + ConsoleTransactions.OPENED);

        // Refresh, then edit row 0 cleanly and delete row 1.
        c.run(
            app,
            "press #refresh\nframe\n"
                + SETTLE
                + "press #cell-0-1\nframe\npress #edit-document\nframe\n"
                + ConsoleTransactions.OPENED);
        var fresh = tab.documentDialog.text.replace("Ada", "Ada Updated");
        var freshEscaped = fresh.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        c.run(
            app,
            "press #document-editor\nkey A control\nclipboard \"" + freshEscaped
                + "\"\nkey V control\nframe\n"
                + "press #document-save\nframe\n"
                + ConsoleTransactions.OPENED
                + "press #document-preview-confirm\nframe\n"
                + SETTLE
                + ConsoleTransactions.OPENED
                + "snap updated\n");
        assertTrue(cell(c.tree("updated"), 0, 1).contains("\"Ada Updated\""), c.tree("updated"));

        c.run(
            app,
            "press #cell-1-1\nframe\npress #delete-document\nframe\n"
                + ConsoleTransactions.OPENED
                + "press #document-preview-confirm\nframe\n"
                + SETTLE
                + ConsoleTransactions.OPENED
                + "snap deleted\n");
        assertTrue(TableTabTest.status(c.tree("deleted")).contains("\"1 document\""));
      } finally {
        try {
          session.command(TestServer.DATABASE, Document.of("drop", name));
        } catch (Exception _) {
          // Best-effort cleanup.
        }
      }
    }
  }
}

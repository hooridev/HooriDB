package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    assertTrue(TableTabTest.status(tree).contains("\"250 rows\""), tree);
    c.run(app, table("customers") + SETTLE + "snap customers\n");
    var customers = c.tree("customers");
    assertTrue(cell(customers, 0, 4).contains("[ 'math', 'poetry' ]"), customers);
  }
}

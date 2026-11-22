package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.TableTabTest.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Console result editing against real PostgreSQL, MySQL and MariaDB servers: edits a cell in a
 * single-table SELECT result, previews the UPDATE, commits it, verifies the server holds the new
 * value, and verifies optimistic concurrency (Missed) when another session updates the row first.
 * Skipped without Docker.
 */
class ConsoleEditServerTest {

  @TempDir
  Path dir;

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void editPreviewAndCommitUpdatesRealServer(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var c = new CatalogTreeTest();
    c.dir = dir;
    c.setUp();
    try (var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10))
        .connection()) {
      try (var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE console_edit_test (id integer PRIMARY KEY, name varchar(100))");
        statement.execute("INSERT INTO console_edit_test VALUES (1, 'Alice'), (2, 'Bob')");
      }
      try {
        var profile = server.profile();
        Servers real = (p, password, driver, timeout) ->
            Servers.SERVER.open(p, password, server.driver(), timeout);
        var app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
        c.ready(app, profile);
        app.state().rememberPassword(profile.id(), TestServer.password());

        String sql = "SELECT id, name FROM console_edit_test WHERE id <= 2 ORDER BY id";
        c.run(
            app,
            START + menu(profile.name(), "New console") + SETTLE + type(sql) + RUN + SETTLE
                + "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n"
                + "press #commit-changes\nframe\n" + OPENED + "snap preview\n");
        String preview = c.tree("preview");
        assertTrue(preview.contains("role=dialog label=\"DML Preview\""), preview);
        assertTrue(preview.contains("UPDATE "), preview);
        assertTrue(preview.contains("'Alicia'"), preview);

        c.run(app, "press #preview-commit\nframe\n" + OPENED + SETTLE + "snap committed\n");
        String committed = c.tree("committed");
        assertTrue(cell(committed, 0, 1).contains("\"Alicia\""), committed);
        assertTrue(line(committed, "#edit-status").contains(ResultEdits.COMMITTED_HINT), committed);

        // Verify the real server holds the committed value.
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("SELECT name FROM console_edit_test WHERE id = 1")) {
          assertTrue(rs.next());
          assertEquals("Alicia", rs.getString(1));
        }

        // Verify Missed when another session modifies row 2 before Commit.
        try (var statement = connection.createStatement()) {
          statement.execute("UPDATE console_edit_test SET name = 'Robert' WHERE id = 2");
        }
        c.run(
            app,
            "press #cell-1-1\ntype \"Bobby\"\nkey Enter\nframe\n"
                + "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n"
                + OPENED + SETTLE + "snap missed\n");
        String missed = c.tree("missed");
        assertTrue(line(missed, "#row-1 role=row").contains("value=\"error\""), missed);
        assertTrue(line(missed, "#write-problem").contains("Changes not committed"), missed);
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("SELECT name FROM console_edit_test WHERE id = 2")) {
          assertTrue(rs.next());
          assertEquals("Robert", rs.getString(1));
        }
      } finally {
        c.tearDown();
        try (var statement = connection.createStatement()) {
          statement.execute("DROP TABLE console_edit_test");
        }
      }
    }
  }
}

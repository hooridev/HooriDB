package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.TableTabTest.table;
import static dev.hoori.db.app.ValuePanelTest.LONG;
import static dev.hoori.db.app.ValuePanelTest.dumped;
import static dev.hoori.db.app.ValuePanelTest.editor;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.json.Json;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The value panel on real servers: a JSON value longer than a page holds is read whole by its
 * row's key and shows formatted. Skipped without Docker.
 */
class ValuePanelServerTest {

  @TempDir
  Path dir;

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aLongJsonValueShowsWholeAndFormatted(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    boolean pg = provider == Provider.POSTGRESQL;
    var c = new CatalogTreeTest();
    c.dir = dir;
    c.setUp();
    try (var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10))
        .connection()) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE value_panel (id integer PRIMARY KEY, doc "
            + (pg ? "jsonb" : "json") + ")" + (pg ? "" : " DEFAULT CHARSET utf8mb4"));
        statement.execute("INSERT INTO value_panel VALUES (1, '" + LONG + "')");
      }
      try {
        var profile = server.profile();
        // The app's tests load made-up drivers; its connections go through the real one.
        Servers real = (p, password, driver) -> Servers.SERVER.open(p, password, server.driver());
        var app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
        c.ready(app, profile);
        app.state().rememberPassword(profile.id(), TestServer.password());
        c.run(
            app,
            START + open(profile.name()) + SETTLE + open(TestServer.DATABASE) + SETTLE
                + (pg ? open("public") + SETTLE : "") + table("value_panel") + SETTLE
                + "press #cell-0-1\nkey Enter shift\nadvance 1\nframe\n" + SETTLE
                + "snap whole\n");
        var formatted = Json.format(LONG).text();
        var editor = editor(c.tree("whole"));
        assertTrue(editor.contains(" \"" + dumped(formatted.substring(0, 60))), editor);
        assertTrue(
            editor.contains(dumped(formatted.substring(formatted.length() - 25)) + "\" length "
                + formatted.length() + " "),
            editor);
      } finally {
        c.tearDown();
        try (var statement = connection.createStatement()) {
          statement.execute("DROP TABLE value_panel");
        }
      }
    }
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.near;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Open console reads against real PostgreSQL and MySQL servers: Stop reading ends PostgreSQL's
 * idle-in-transaction state, and the idle give-up on MySQL frees the streamed statement before
 * {@code net_write_timeout} drops the session. Skipped without Docker.
 */
class ConsoleOpenReadServerTest {

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

  private App<State> console(TestServer server) throws Exception {
    var profile = server.profile();
    Servers real = (p, password, driver, timeout) ->
        Servers.SERVER.open(p, password, server.driver(), timeout);
    var app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
    c.ready(app, profile);
    app.state().rememberPassword(profile.id(), TestServer.password());
    c.run(app, START + menu(profile.name(), "New console") + SETTLE);
    return app;
  }

  private static ConsoleTab tab(App<State> app) {
    return app.state().consoles(null).getFirst();
  }

  private static Object single(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement();
        var rs = statement.executeQuery(sql)) {
      assertTrue(rs.next());
      return rs.getObject(1);
    }
  }

  @Test
  void stopReadingEndsPostgreSqlIdleInTransaction() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var app = console(server);
    try (var watcher = Connector.open(
            server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10))
        .connection()) {
      c.run(app, type("SELECT pg_backend_pid()") + RUN);
      long pid = ((Number) tab(app).outputs.getFirst().rows.getFirst().getFirst()).longValue();

      c.run(
          app,
          "press #sql\nkey A control\nkey Delete\n"
              + type("SELECT n FROM generate_series(1, 500) n")
              + RUN
              + "snap open\n");
      var output = tab(app).outputs.getFirst();
      assertTrue(output.more);
      assertEquals(200, output.rows.size());
      assertTrue(
          near(c.tree("open"), "#result-status").contains("200 rows so far · cursor open"),
          c.tree("open"));
      assertEquals(
          "idle in transaction",
          single(watcher, "SELECT state FROM pg_stat_activity WHERE pid = " + pid));

      c.run(app, "press role=button label=\"Stop reading\"\nframe\n" + SETTLE + "snap stopped\n");
      assertFalse(output.more);
      assertTrue(output.cut);
      assertEquals(200, output.rows.size());
      assertTrue(
          near(c.tree("stopped"), "#result-status").contains("the first 200 rows; reading stopped"),
          c.tree("stopped"));
      eventually(() -> {
        try {
          return "idle"
              .equals(single(watcher, "SELECT state FROM pg_stat_activity WHERE pid = " + pid));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });

      // The next statement runs on the same session.
      c.run(app, "press #sql\nkey A control\nkey Delete\n" + type("SELECT pg_backend_pid()") + RUN);
      assertEquals(
          pid, ((Number) tab(app).outputs.getFirst().rows.getFirst().getFirst()).longValue());
    }
  }

  @Test
  void idleGiveUpOnMySqlKeepsTheSessionPastNetWriteTimeout() throws Exception {
    var server = TestServer.of(Provider.MYSQL);
    var app = console(server);
    ConsoleTab.idleSeconds = 0.5;
    try {
      c.run(app, type("SELECT CONNECTION_ID()") + RUN);
      long id = ((Number) tab(app).outputs.getFirst().rows.getFirst().getFirst()).longValue();

      c.run(
          app,
          "press #sql\nkey A control\nkey Delete\n"
              + type("SET SESSION net_write_timeout = 1")
              + RUN);
      // Enough rows and bytes to block MySQL's streamed send on the server until drained.
      c.run(
          app,
          "press #sql\nkey A control\nkey Delete\n"
              + type("SELECT a.id, REPEAT('x', 100) AS t FROM reading a, reading b")
              + RUN
              + "advance 0.5\nframe\n"
              + SETTLE
              + "snap idle\n");
      var output = tab(app).outputs.getFirst();
      assertFalse(output.more);
      assertTrue(output.cut);
      assertEquals(200, output.rows.size());
      assertTrue(
          near(c.tree("idle"), "#result-status")
              .contains("the first 200 rows; reading stopped after being idle"),
          c.tree("idle"));

      // Past net_write_timeout (1 s): an open stream would have dropped the connection by now.
      Thread.sleep(2000);
      c.run(
          app,
          "press #sql\nkey A control\nkey Delete\n" + type("SELECT CONNECTION_ID()") + RUN
              + "snap after\n");
      assertEquals(null, tab(app).problem);
      assertEquals(
          id, ((Number) tab(app).outputs.getFirst().rows.getFirst().getFirst()).longValue());
    } finally {
      ConsoleTab.idleSeconds = ConsoleTab.IDLE_SECONDS;
    }
  }
}

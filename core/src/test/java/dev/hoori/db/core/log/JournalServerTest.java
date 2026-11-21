package dev.hoori.db.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.DocumentRun;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.sql.Secrets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What core sends to real servers, as the seam records it. */
class JournalServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);

  private final List<Sent> sent = new CopyOnWriteArrayList<>();

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void catalogReadsPagesAndStatementsAreRecorded(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection()) {
      var container = provider == Provider.POSTGRESQL ? new Schema(HOORI, "public", false) : HOORI;
      var reading = new Table(container, "reading", Table.Kind.TABLE);

      var columns =
          Journal.recording(sent::add, () -> Catalog.columns(connection, provider, reading));
      var catalog = sent.getLast();
      assertEquals(Purpose.CATALOG, catalog.purpose());
      assertEquals(Outcome.rows(columns.size()), catalog.outcome());

      sent.clear();
      Journal.recording(sent::add, () -> Rows.read(connection, provider, reading, columns, 0, 100));
      var page = sent.getLast();
      assertEquals(Purpose.ROWS, page.purpose());
      assertTrue(page.text().contains("LIMIT 100"), page.text());
      assertEquals(Outcome.rows(100), page.outcome());

      sent.clear();
      Journal.recording(sent::add, () -> {
        try (var execution = new Execution(connection, provider, "SELECT 1 UNION SELECT 2")) {
          return execution.next(1);
        }
      });
      var statement =
          sent.stream().filter(s -> s.purpose() == Purpose.STATEMENT).findFirst();
      assertEquals("SELECT 1 UNION SELECT 2", statement.orElseThrow().text());
      assertEquals(new Outcome.Rows(1, true), statement.orElseThrow().outcome());
      if (provider == Provider.POSTGRESQL) {
        // Its rows were read in a transaction of its own, which giving them up commits.
        assertTrue(
            sent.stream().anyMatch(s -> s.text().equals(Journal.COMMIT)),
            sent.stream().map(Sent::text).toList().toString());
      }

      sent.clear();
      var change = provider == Provider.POSTGRESQL
          ? "ALTER ROLE nobody_at_all PASSWORD 'hunter2'"
          : "ALTER USER nobody_at_all IDENTIFIED BY 'hunter2'";
      Journal.recording(sent::add, () -> {
        try (var execution = new Execution(connection, provider, change)) {
          return execution.next(1);
        }
      });
      var failed = sent.getFirst();
      assertInstanceOf(Outcome.Failed.class, failed.outcome());
      assertFalse(failed.text().contains("hunter2"), failed.text());
      assertTrue(failed.text().endsWith(Secrets.MASK), failed.text());
    }
  }

  @Test
  void mongoDbCommandsAreRecordedWithTheirDatabase() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    var opened =
        DocumentConnector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    try (var session = new DocumentSession(opened.client(), Provider.MONGODB.documents(), false)) {
      var orders = new Table(HOORI, "orders", Table.Kind.TABLE);
      var fields = Journal.recording(sent::add, () -> session.fields(orders));
      assertEquals(Purpose.CATALOG, sent.getFirst().purpose());
      assertEquals(TestServer.DATABASE, sent.getFirst().database());
      assertTrue(
          sent.getFirst().text().startsWith("db.runCommand({"), sent.getFirst().text());

      sent.clear();
      Journal.recording(sent::add, () -> session.read(orders, fields, 0, 10));
      assertEquals(Purpose.ROWS, sent.getFirst().purpose());
      assertEquals(new Outcome.Rows(10, false), sent.getFirst().outcome());

      sent.clear();
      Journal.recording(sent::add, () -> {
        var run = new DocumentRun(
            session,
            TestServer.DATABASE,
            Script.Parsed.of(
                "db.runCommand({ createUser: 'nobody_at_all', pwd: 'hunter2', roles: [] })"),
            database -> {});
        try {
          return run.next(10);
        } finally {
          run.close();
        }
      });
      assertEquals(Purpose.STATEMENT, sent.getFirst().purpose());
      assertFalse(sent.getFirst().text().contains("hunter2"), sent.getFirst().text());
      Journal.recording(sent::add, () -> {
        var run = new DocumentRun(
            session,
            TestServer.DATABASE,
            Script.Parsed.of("db.runCommand({ dropUser: 'nobody_at_all' })"),
            database -> {});
        try {
          return run.next(10);
        } finally {
          run.close();
        }
      });
    }
  }
}

package dev.hoori.db.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentClient;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.monitor.DocumentMonitor;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.SqlMonitor;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Secrets;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The seam every send passes through, without a server. */
class JournalTest {

  private final List<Sent> sent = new CopyOnWriteArrayList<>();
  private final Recorder recorder = sent::add;

  @Test
  void withoutARecorderTheCallRunsAndNothingIsMadeOfItsText() throws Exception {
    assertFalse(Journal.recording());
    var value = Journal.sql(
        Provider.POSTGRESQL,
        Purpose.ROWS,
        "SELECT 1",
        v -> {
          throw new AssertionError("no outcome without a recorder");
        },
        () -> 42);
    assertEquals(42, value);
  }

  @Test
  void aSendIsRecordedWithItsPurposeTextDurationAndOutcome() throws Exception {
    var value = Journal.recording(
        recorder,
        () -> Journal.sql(
            Provider.MARIADB,
            Purpose.CATALOG,
            "SELECT name FROM t",
            rows -> Outcome.rows(rows.size()),
            () -> List.of("a", "b")));
    assertEquals(List.of("a", "b"), value);
    assertEquals(1, sent.size());
    var entry = sent.getFirst();
    assertEquals(Purpose.CATALOG, entry.purpose());
    assertEquals("SELECT name FROM t", entry.text());
    assertEquals(new Outcome.Rows(2, false), entry.outcome());
    assertNull(entry.database());
    assertFalse(entry.took().isNegative());
    assertFalse(Journal.recording(), "the recorder is bound for the call alone");
  }

  @Test
  void aFailureIsRecordedAndThrownAsItWas() {
    var thrown = new SQLException("relation \"nope\" does not exist", "42P01");
    var caught = assertThrows(
        SQLException.class,
        () -> Journal.recording(
            recorder,
            () -> Journal.sql(
                Provider.POSTGRESQL, Purpose.ROWS, "SELECT * FROM nope", v -> null, () -> {
                  throw thrown;
                })));
    assertSame(thrown, caught);
    assertEquals(
        new Outcome.Failed("relation \"nope\" does not exist"), sent.getFirst().outcome());
  }

  @Test
  void aCancelIsRecordedAsCancelled() {
    assertThrows(
        QueryException.class,
        () -> Journal.recording(
            recorder,
            () -> Journal.call(Purpose.TRANSACTION, Journal.COMMIT, () -> {
              throw QueryException.of(QueryException.Failure.CANCELLED, "");
            })));
    assertEquals(new Outcome.Cancelled(), sent.getFirst().outcome());
    assertEquals("commit()", sent.getFirst().text());
  }

  @Test
  void aRecorderThatFailsLosesTheEntryAndNotTheResult() throws Exception {
    Recorder failing = s -> {
      throw new IllegalStateException("full");
    };
    var value = Journal.recording(
        failing, () -> Journal.call(Purpose.SESSION, Journal.catalog("x"), () -> "done"));
    assertEquals("done", value);
  }

  @Test
  void anOutcomeThatFailsIsDone() throws Exception {
    Journal.recording(
        recorder,
        () -> Journal.sql(
            Provider.MYSQL,
            Purpose.ROWS,
            "SELECT 1",
            v -> {
              throw new IllegalStateException();
            },
            () -> 1));
    assertEquals(Outcome.DONE, sent.getFirst().outcome());
  }

  @Test
  void whatAThreadStartedForTheCallSendsIsRecordedToo() throws Exception {
    Journal.recording(recorder, () -> {
      var worker = Thread.ofVirtual().start(Journal.carried(() -> {
        try {
          Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> null);
        } catch (RuntimeException e) {
          throw e;
        }
      }));
      worker.join();
      return null;
    });
    assertEquals(1, sent.size());
    assertEquals("rollback()", sent.getFirst().text());
    // Without a recorder bound, the work is the work.
    Runnable work = () -> {};
    assertSame(work, Journal.carried(work));
  }

  @Test
  void aStatementIsRecordedWithItsPasswordMasked() throws Exception {
    Journal.recording(
        recorder,
        () -> Journal.sql(
            Provider.POSTGRESQL,
            Purpose.STATEMENT,
            "ALTER ROLE app PASSWORD 'hunter2'",
            v -> Outcome.DONE,
            () -> null));
    assertEquals("ALTER ROLE app PASSWORD " + Secrets.MASK, sent.getFirst().text());
  }

  @Test
  void theFailureOfAStatementThatCarriesAPasswordIsWithheld() {
    assertThrows(
        SQLException.class,
        () -> Journal.recording(
            recorder,
            () -> Journal.sql(
                Provider.POSTGRESQL,
                Purpose.STATEMENT,
                "ALTER ROLE app PASSWORD 'hunter2' VALID",
                v -> Outcome.DONE,
                () -> {
                  throw new SQLException("syntax error at or near \"'hunter2'\"", "42601");
                })));
    assertEquals(new Outcome.Failed(Outcome.WITHHELD), sent.getFirst().outcome());
    assertThrows(
        IllegalStateException.class,
        () -> Journal.recording(
            recorder,
            () -> Journal.command(
                Provider.MONGODB.documents(),
                Purpose.STATEMENT,
                "admin",
                Document.of("updateUser", "app", "pwd", "hunter2"),
                reply -> Outcome.DONE,
                () -> {
                  throw new IllegalStateException("bad pwd hunter2");
                })));
    assertEquals(new Outcome.Failed(Outcome.WITHHELD), sent.getLast().outcome());
  }

  @Test
  void aCommandIsRecordedAsTheShellSendsItWithItsDatabaseAndWithoutItsPassword() throws Exception {
    var command = Document.of("createUser", "app", "pwd", "hunter2", "roles", Document.array());
    Journal.recording(
        recorder,
        () -> Journal.command(
            Provider.MONGODB.documents(),
            Purpose.STATEMENT,
            "admin",
            command,
            reply -> Outcome.DONE,
            () -> Document.of("ok", 1)));
    var entry = sent.getFirst();
    assertEquals("admin", entry.database());
    assertTrue(entry.text().startsWith("db.runCommand({"), entry.text());
    assertTrue(entry.text().contains("createUser"), entry.text());
    assertFalse(entry.text().contains("hunter2"), entry.text());
  }

  @Test
  void theCallsOfTheDriverAreNamedAsCalled() {
    assertEquals("setAutoCommit(false)", Journal.autoCommit(false));
    assertEquals("setCatalog(\"a \\\"b\\\"\")", Journal.catalog("a \"b\""));
  }

  @Test
  void anInterruptIsACancel() {
    assertInstanceOf(Outcome.Cancelled.class, Outcome.of(new InterruptedException()));
    assertEquals(new Outcome.Failed("SQLException"), Outcome.of(new SQLException()));
  }

  @Test
  void sqlMonitorRecordsCancelAndEndUnderMonitorAndNotReads() throws Exception {
    var empty = (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class},
        (_, method, _) -> switch (method.getName()) {
          case "next" -> false;
          case "close" -> null;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var found = (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(),
        new Class<?>[] {ResultSet.class},
        (_, method, _) -> switch (method.getName()) {
          case "next", "getBoolean" -> true;
          case "close" -> null;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var plain = (Statement) Proxy.newProxyInstance(
        Statement.class.getClassLoader(),
        new Class<?>[] {Statement.class},
        (_, method, _) -> switch (method.getName()) {
          case "setEscapeProcessing", "close" -> null;
          case "executeQuery" -> empty;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var prepared = (PreparedStatement) Proxy.newProxyInstance(
        PreparedStatement.class.getClassLoader(),
        new Class<?>[] {PreparedStatement.class},
        (_, method, _) -> switch (method.getName()) {
          case "setString", "close" -> null;
          case "execute" -> true;
          case "getResultSet" -> found;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var connection = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        (_, method, _) -> switch (method.getName()) {
          case "createStatement" -> plain;
          case "prepareStatement" -> prepared;
          case "isClosed" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var monitor = new SqlMonitor(connection, Provider.POSTGRESQL);
    var session = new ServerSession(
        "101",
        "2026-01-01 00:00:00+00",
        "2026-01-01 00:00:00+00",
        false,
        true,
        "ada",
        "psql",
        "shop",
        "active",
        "SELECT 1",
        Duration.ofSeconds(1),
        "");
    Journal.recording(recorder, () -> {
      monitor.sessions();
      assertTrue(monitor.cancel(session));
      assertTrue(monitor.end(session));
      return null;
    });
    assertEquals(2, sent.size(), sent.toString());
    assertEquals(Purpose.MONITOR, sent.get(0).purpose());
    assertEquals(
        Provider.POSTGRESQL.dialect().monitor().cancel(session).sql(),
        sent.get(0).text());
    assertEquals(Outcome.DONE, sent.get(0).outcome());
    assertEquals(Purpose.MONITOR, sent.get(1).purpose());
    assertEquals(
        Provider.POSTGRESQL.dialect().monitor().end(session).sql(), sent.get(1).text());
    assertEquals(Outcome.DONE, sent.get(1).outcome());
  }

  @Test
  void documentMonitorRecordsCancelUnderMonitor() throws Exception {
    var client = new DocumentClient() {
      @Override
      public Document command(String database, Document command) {
        return Document.of("ok", 1);
      }

      @Override
      public void close() {}
    };
    var session = new DocumentSession(client, Provider.MONGODB.documents(), false);
    var target = new ServerSession(
        "101",
        "42",
        "",
        false,
        false,
        "ada",
        "mongosh",
        "shop",
        "active",
        "find orders",
        Duration.ofSeconds(1),
        "");
    Journal.recording(recorder, () -> {
      assertTrue(new DocumentMonitor(session).cancel(target));
      return null;
    });
    assertEquals(1, sent.size(), sent.toString());
    var entry = sent.getFirst();
    assertEquals(Purpose.MONITOR, entry.purpose());
    assertEquals("admin", entry.database());
    assertTrue(entry.text().contains("killOp"), entry.text());
    assertEquals(Outcome.DONE, entry.outcome());
  }
}

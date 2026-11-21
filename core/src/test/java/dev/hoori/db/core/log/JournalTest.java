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
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Secrets;
import java.sql.SQLException;
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
  void aRecorderThatTakesNothingNowGetsNoEntry() throws Exception {
    var taking = new boolean[1];
    var choosing = new Recorder() {
      @Override
      public void sent(Sent entry) {
        sent.add(entry);
      }

      @Override
      public boolean taking() {
        return taking[0];
      }
    };
    Journal.recording(
        choosing,
        () ->
            Journal.sql(Provider.POSTGRESQL, Purpose.FILE, "SELECT 1", v -> Outcome.DONE, () -> 7));
    assertTrue(sent.isEmpty(), sent.toString());
    taking[0] = true;
    Journal.recording(
        choosing,
        () ->
            Journal.sql(Provider.POSTGRESQL, Purpose.FILE, "SELECT 2", v -> Outcome.DONE, () -> 7));
    assertEquals("SELECT 2", sent.getFirst().text());
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
}

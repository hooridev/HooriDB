package dev.hoori.db.core.execution;

import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.sql.StatementReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A run of a file on a made-up session: what it sends, and how it ends. */
class FileRunTest {

  // A session that notes what it is sent, and fails the statements named.
  private static class Session implements FileRun.Target {
    final List<String> sent = new CopyOnWriteArrayList<>();
    final Map<String, Failure> failing;

    Session(Map<String, Failure> failing) {
      this.failing = failing;
    }

    @Override
    public Optional<Failure> run(String sql) {
      sent.add(sql);
      return Optional.ofNullable(failing.get(sql));
    }

    @Override
    public Optional<Failure> copy(String sql, Reader rows) {
      try {
        var text = new StringBuilder();
        var into = new char[64];
        for (int n = rows.read(into); n >= 0; n = rows.read(into)) {
          text.append(into, 0, n);
        }
        sent.add(sql + " <- " + text);
      } catch (IOException e) {
        sent.add(sql + " <- " + e.getMessage());
      }
      return Optional.ofNullable(failing.get(sql));
    }

    @Override
    public Optional<Failure> begin() {
      sent.add("begin");
      return Optional.empty();
    }

    @Override
    public Optional<Failure> commit() {
      sent.add("commit");
      return Optional.ofNullable(failing.get("commit"));
    }

    @Override
    public Optional<Failure> rollback() {
      sent.add("rollback");
      return Optional.empty();
    }

    @Override
    public Optional<Failure> end() {
      sent.add("end");
      return Optional.empty();
    }
  }

  private static final Failure WRONG = Failure.of(Failure.Kind.INVALID, "syntax error");

  private static FileRun.Report run(
      Provider provider, String text, FileRun.Options options, FileRun.Target target) {
    return new FileRun(
            () -> new StatementReader(new StringReader(text), provider, text.length()),
            provider,
            options,
            progress -> {})
        .run(target);
  }

  @Test
  void aFailureStopsTheRunOrLetsItGoOn() {
    var text = "SELECT 1;\n\nSELEC 2;\nSELECT 3;\n";
    var stopping = new Session(Map.of("SELEC 2", WRONG));
    var stopped = run(POSTGRESQL, text, new FileRun.Options(false, false), stopping);
    assertEquals(FileRun.Ending.STOPPED, stopped.ending());
    assertEquals(List.of("SELECT 1", "SELEC 2"), stopping.sent);
    assertEquals(List.of(new FileRun.Failed(3, "SELEC 2", "syntax error")), stopped.failures());
    assertEquals(new FileRun.Progress(19, text.length(), 2, 1), stopped.progress());
    assertEquals(FileRun.Kept.NONE, stopped.transaction());

    var going = new Session(Map.of("SELEC 2", WRONG));
    var went = run(POSTGRESQL, text, new FileRun.Options(true, false), going);
    assertEquals(FileRun.Ending.RAN, went.ending());
    assertEquals(List.of("SELECT 1", "SELEC 2", "SELECT 3"), going.sent);
    assertEquals(new FileRun.Progress(text.length(), text.length(), 3, 1), went.progress());
  }

  @Test
  void aFailureIsAtTheLineTheServerNames() {
    // MariaDB and MySQL name the line of the statement, PostgreSQL the character.
    var mysql = new Failure(
        Failure.Kind.INVALID, "near 'FORM t'", "42000", 1064, 0, "", 0, 2, "FORM t", "", "", "");
    var text = "-- one\nSELECT *\nFORM t;";
    var report = run(
        MYSQL,
        text,
        new FileRun.Options(false, false),
        new Session(Map.of("SELECT *\nFORM t", mysql)));
    assertEquals(3, report.failures().getFirst().line());
  }

  @Test
  void oneTransactionCommitsAtTheEndAndRollsBackAFailure() {
    var text = "INSERT 1;\nINSERT 2;";
    var good = new Session(Map.of());
    var committed = run(POSTGRESQL, text, new FileRun.Options(true, true), good);
    assertEquals(FileRun.Ending.RAN, committed.ending());
    assertEquals(FileRun.Kept.COMMITTED, committed.transaction());
    assertEquals(List.of("begin", "INSERT 1", "INSERT 2", "commit", "end"), good.sent);

    // In a transaction a failure stops the run, also where it would go on.
    var bad = new Session(Map.of("INSERT 1", WRONG));
    var rolledBack = run(POSTGRESQL, text, new FileRun.Options(true, true), bad);
    assertEquals(FileRun.Ending.STOPPED, rolledBack.ending());
    assertEquals(FileRun.Kept.ROLLED_BACK, rolledBack.transaction());
    assertEquals(List.of("begin", "INSERT 1", "rollback", "end"), bad.sent);

    // A commit that fails rolls back, and the run did not run.
    var failing = new Session(Map.of("commit", WRONG));
    var notCommitted = run(POSTGRESQL, text, new FileRun.Options(false, true), failing);
    assertEquals(FileRun.Ending.STOPPED, notCommitted.ending());
    assertEquals(FileRun.Kept.ROLLED_BACK, notCommitted.transaction());
    assertEquals(
        new FileRun.Failed(0, "COMMIT", "syntax error"), notCommitted.failures().getFirst());
  }

  @Test
  void oneTransactionRefusesAStatementThatWouldEndIt() {
    var text = "INSERT 1;\nCOMMIT;\nINSERT 2;";
    var session = new Session(Map.of());
    var report = run(POSTGRESQL, text, new FileRun.Options(false, true), session);
    assertEquals(FileRun.Ending.STOPPED, report.ending());
    assertEquals(FileRun.Kept.ROLLED_BACK, report.transaction());
    assertEquals(List.of("begin", "INSERT 1", "rollback", "end"), session.sent);
    assertEquals(2, report.failures().getFirst().line());
    assertEquals(1, report.progress().statements());

    // Without one transaction the file keeps its own.
    var own = new Session(Map.of());
    run(POSTGRESQL, text, new FileRun.Options(false, false), own);
    assertEquals(List.of("INSERT 1", "COMMIT", "INSERT 2"), own.sent);
  }

  @Test
  void theRowsOfACopyGoWithIt() {
    var text = "COPY t FROM stdin;\n1\ta\n2\tb\n\\.\nSELECT 1;";
    var session = new Session(Map.of());
    var report = run(POSTGRESQL, text, new FileRun.Options(false, false), session);
    assertEquals(FileRun.Ending.RAN, report.ending());
    assertEquals(List.of("COPY t FROM stdin <- 1\ta\n2\tb\n", "SELECT 1"), session.sent);
    assertEquals(2, report.progress().statements());
  }

  @Test
  void psqlsRestrictIsSkippedAndItsOtherCommandsStopTheRun() {
    var text = "\\restrict k\nSELECT 1;\n\\connect other\nSELECT 2;\n\\unrestrict k\n";
    var session = new Session(Map.of());
    var report = run(POSTGRESQL, text, new FileRun.Options(true, false), session);
    assertEquals(FileRun.Ending.STOPPED, report.ending());
    assertEquals(List.of("SELECT 1"), session.sent);
    var failed = report.failures().getFirst();
    assertEquals(3, failed.line());
    // By its name alone, as the rest of its line may hold a password.
    assertEquals("\\connect", failed.statement());
    assertTrue(failed.message().startsWith("\\connect is a command of PostgreSQL's client"));
    assertEquals(new FileRun.Progress(37, text.length(), 1, 1), report.progress());
  }

  @Test
  void aPasswordIsMaskedAndTheMessageWithheld() {
    var sql = "CREATE ROLE r PASSWORD 'hunter2' LOGIN";
    var failure = Failure.of(Failure.Kind.INVALID, "near 'hunter2'");
    var report = run(
        POSTGRESQL,
        sql + ";",
        new FileRun.Options(false, false),
        new Session(Map.of(sql, failure)));
    var failed = report.failures().getFirst();
    assertFalse(failed.statement().contains("hunter2"), failed.statement());
    assertEquals(Outcome.WITHHELD, failed.message());
  }

  @Test
  void aFileThatCannotBeOpenedFails() {
    var report = new FileRun(
            () -> {
              throw new IOException("no such file");
            },
            POSTGRESQL,
            new FileRun.Options(false, false),
            progress -> {})
        .run(new Session(Map.of()));
    assertEquals(FileRun.Ending.STOPPED, report.ending());
    assertEquals(
        List.of(new FileRun.Failed(0, "", "The file cannot be read: no such file")),
        report.failures());
  }

  @Test
  void onlyTheFirstFailuresAreKept() {
    var text = "X;\n".repeat(FileRun.KEPT + 5);
    var report =
        run(POSTGRESQL, text, new FileRun.Options(true, false), new Session(Map.of("X", WRONG)));
    assertEquals(FileRun.KEPT, report.failures().size());
    assertEquals(FileRun.KEPT + 5, report.progress().failed());
  }

  @Test
  void aCancelStopsAStatementThatRunsAndRollsBack() throws Exception {
    var started = new CountDownLatch(1);
    var session = new Session(Map.of()) {
      @Override
      public Optional<Failure> run(String sql) {
        sent.add(sql);
        started.countDown();
        try {
          new CountDownLatch(1).await(); // until the cancel interrupts it
          return Optional.empty();
        } catch (InterruptedException _) {
          return Optional.of(Failure.cancelled()); // as Execution says an interrupt
        }
      }
    };
    var progress = new ArrayList<FileRun.Progress>();
    var run = new FileRun(
        () -> new StatementReader(
            new StringReader("SELECT pg_sleep(60);\nSELECT 2;"), POSTGRESQL, -1),
        POSTGRESQL,
        new FileRun.Options(true, true),
        progress::add);
    var report = new FileRun.Report[1];
    var runner = Thread.ofVirtual().start(() -> report[0] = run.run(session));
    assertTrue(started.await(10, TimeUnit.SECONDS));
    run.cancel();
    runner.join(10_000);
    assertEquals(FileRun.Ending.CANCELLED, report[0].ending());
    assertEquals(FileRun.Kept.ROLLED_BACK, report[0].transaction());
    assertEquals(List.of("begin", "SELECT pg_sleep(60)", "rollback", "end"), session.sent);
    assertTrue(report[0].failures().isEmpty());
  }
}

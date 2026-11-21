package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Secrets;
import dev.hoori.db.core.sql.StatementReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A file of SQL run on a session, its steps one after another as {@link StatementReader} reads
 * them: each statement runs to its end, its rows read and dropped; the rows after a {@code COPY …
 * FROM STDIN} are copied in as the file holds them; a command of the client that is not skipped
 * stops the run, as what follows may depend on it. Nothing of the file is held but the step that
 * runs.
 *
 * <p>On a failure the run stops, or goes on where {@link Options#goOn} says so; in {@link
 * Options#transaction one transaction} it always stops, and what ran is rolled back, also on a
 * cancel; at the end of the file it is committed. A server that commits DDL by itself, without
 * {@link dev.hoori.db.core.connection.Capability#TRANSACTIONAL_DDL}, commits what ran before as
 * well.
 *
 * <p>It runs once, on the calling thread, which it blocks. {@link #cancel}, from any thread, stops
 * it at once, within a statement or a copy too, and the run ends with its report as on a failure.
 */
public final class FileRun {

  /** How many failures a report keeps, in the order they came; it counts all. */
  public static final int KEPT = 1_000;

  /** How much of a failed statement's text a report keeps. */
  public static final int SHOWN = 500;

  // The rows of a result read at a time, and dropped.
  private static final int ROWS = 1_000;

  // The chars of a copy's rows after which it tells how far it came.
  private static final int TOLD = 1 << 20;

  /**
   * How a run goes.
   *
   * @param goOn whether a failure lets the run go on with the next statement; not in a transaction
   * @param transaction whether the whole file runs in one transaction, which a failure rolls back
   */
  public record Options(boolean goOn, boolean transaction) {}

  /**
   * How far a run is.
   *
   * @param bytes the offset in the file up to which it ran
   * @param size the file's size in bytes, or -1 where it is not known
   * @param statements the statements that ran, those that failed among them
   */
  public record Progress(long bytes, long size, long statements, long failed) {}

  /**
   * A step that failed.
   *
   * @param line the line of the file where the server says the failure is, or where the step
   *     begins; 0 for the file itself
   * @param statement the start of its text, at most {@link #SHOWN} chars, with passwords masked
   * @param message why, in the server's words; withheld where the statement holds a password
   */
  public record Failed(long line, String statement, String message) {}

  /** How a run ended. */
  public enum Ending {
    /** At the end of the file, its failures gone over where the run goes on. */
    RAN,
    /** At a failure. */
    STOPPED,
    /** By {@link #cancel}. */
    CANCELLED
  }

  /** What became of the run's transaction. */
  public enum Kept {
    /** The run had none: each statement committed by itself. */
    NONE,
    COMMITTED,
    ROLLED_BACK,
    /**
     * Its commit or rollback failed: the server rolls back what is still open as the session
     * closes, and a commit whose connection was lost may have taken effect or not.
     */
    IN_DOUBT
  }

  /**
   * How a run went: its end, how far it came, the first {@link #KEPT} failures, and its
   * transaction.
   */
  public record Report(
      Ending ending, Progress progress, List<Failed> failures, Kept transaction, Duration took) {

    public Report {
      failures = List.copyOf(failures);
    }
  }

  /** What opens the file to run. */
  @FunctionalInterface
  public interface Source {
    StatementReader open() throws IOException;
  }

  /**
   * The session a file runs on. Each call blocks, and an interrupt of the calling thread cancels
   * it, as {@link Execution} is cancelled; the failure of each, or null where it went well.
   */
  public interface Target {

    /** Runs the statement to its end, its rows read and dropped. */
    Failure run(String sql);

    /** Copies the rows into what the statement, a {@code COPY … FROM STDIN}, names. */
    Failure copy(String sql, Reader rows);

    /** Turns auto-commit off, for one transaction. */
    Failure begin();

    /** Commits the transaction; within a bound, an interrupt or not. */
    Failure commit();

    /** Rolls the transaction back; within a bound, an interrupt or not. */
    Failure rollback();

    /** Turns auto-commit on again; within a bound, an interrupt or not. */
    Failure end();
  }

  private final Source source;
  private final Provider provider;
  private final Options options;
  private final Consumer<Progress> progress;
  private final Object lock = new Object();
  private Thread running; // the thread that runs it, under the lock
  private StatementReader reader; // under the lock
  private volatile boolean cancelled;
  private boolean interrupted; // the cancel interrupted the run, under the lock
  private final List<Failed> failures = new ArrayList<>();
  private long statements;
  private long failed;

  /**
   * A run of the file, with the provider's lexical rules, which tells each step that ran to {@code
   * progress} on the thread that runs it.
   */
  public FileRun(Source source, Provider provider, Options options, Consumer<Progress> progress) {
    this.source = source;
    this.provider = provider;
    this.options = options;
    this.progress = progress;
  }

  /** A run of the file on disk, as {@link StatementReader#open} reads it. */
  public static FileRun of(
      Path file, Provider provider, Options options, Consumer<Progress> progress) {
    return new FileRun(() -> StatementReader.open(file, provider), provider, options, progress);
  }

  /** Stops the run, where it is now or as soon as it begins; any thread, any number of times. */
  public void cancel() {
    synchronized (lock) {
      if (cancelled) {
        return;
      }
      cancelled = true;
      if (reader != null) {
        reader.cancel(); // a copy stops at its next read of the file
      }
      if (running != null) {
        interrupted = true;
        running.interrupt();
      }
    }
  }

  /** Runs the file on the target, once, and says how that went. */
  public Report run(Target target) {
    var started = System.nanoTime();
    synchronized (lock) {
      running = Thread.currentThread();
    }
    var last = new Progress(0, -1, 0, 0);
    var ending = Ending.STOPPED;
    var kept = Kept.NONE;
    try (var file = source.open()) {
      synchronized (lock) {
        reader = file;
        if (cancelled) {
          file.cancel();
        }
      }
      last = new Progress(0, file.size(), 0, 0);
      progress.accept(last);
      if (options.transaction()) {
        var failure = target.begin();
        if (failure != null) {
          fail(0, "", failure.message());
          return report(Ending.STOPPED, Kept.NONE, last, started);
        }
        kept = Kept.ROLLED_BACK;
      }
      ending = steps(target, file);
      last = progress(file);
      calm();
      if (options.transaction()) {
        kept = end(target, ending == Ending.RAN);
        if (kept != Kept.COMMITTED && ending == Ending.RAN) {
          ending = Ending.STOPPED;
        }
      }
    } catch (IOException e) {
      // The file could not be opened, or closed, which no step needs.
      if (statements == 0) {
        fail(0, "", "The file cannot be read: " + e.getMessage());
      }
    } finally {
      calm();
      synchronized (lock) {
        running = null;
        reader = null;
      }
    }
    return report(ending, kept, last, started);
  }

  // Runs the file's steps until its end, a failure that stops it, or a cancel.
  private Ending steps(Target target, StatementReader file) {
    while (!cancelled) {
      StatementReader.Step step;
      try {
        step = file.next();
      } catch (IOException e) {
        if (cancelled) {
          return Ending.CANCELLED;
        }
        fail(file.line(), "", "The file could not be read: " + e.getMessage());
        return Ending.STOPPED;
      }
      if (step == null) {
        return Ending.RAN;
      }
      var failure = switch (step) {
        case StatementReader.Sql sql -> ran(target.run(sql.sql()));
        case StatementReader.Copy copy -> ran(target.copy(copy.sql(), told(file)));
        case StatementReader.Command command -> command(command);
      };
      if (failure == null) {
        progress.accept(progress(file));
        continue;
      }
      if (cancelled) {
        return Ending.CANCELLED;
      }
      failed++;
      fail(step, failure);
      progress.accept(progress(file));
      if (step instanceof StatementReader.Command
          || options.transaction()
          || !options.goOn()
          || failure.kind() == Failure.Kind.CONNECTION_LOST) {
        return Ending.STOPPED;
      }
    }
    return Ending.CANCELLED;
  }

  // The rows of the copy the file is on, which tell how far they came as they are read.
  private Reader told(StatementReader file) {
    return new FilterReader(file.rows()) {
      private long since;

      @Override
      public int read(char[] into, int from, int length) throws IOException {
        int read = super.read(into, from, length);
        since += Math.max(read, 0);
        if (since >= TOLD) {
          since = 0;
          progress.accept(progress(file));
        }
        return read;
      }
    };
  }

  private Failure ran(Failure failure) {
    statements++;
    return failure;
  }

  // A command of the client: skipped, or refused.
  private Failure command(StatementReader.Command command) {
    if (provider.dialect().client().skippedCommands().contains(command.name())) {
      return null;
    }
    return Failure.of(
        Failure.Kind.INVALID,
        "\\" + command.name() + " is a command of " + provider.displayName() + "'s client, which"
            + " HooriDB does not run: the run stops here, as what follows may depend on it.");
  }

  // Ends the transaction: commits it where the run ran to its end, else rolls it back; auto-commit
  // goes on again in either case.
  private Kept end(Target target, boolean commit) {
    var kept = Kept.ROLLED_BACK;
    if (commit) {
      var failure = target.commit();
      if (failure == null) {
        kept = Kept.COMMITTED;
      } else {
        fail(0, "COMMIT", failure.message());
        kept = failure.kind() == Failure.Kind.CONNECTION_LOST ? Kept.IN_DOUBT : Kept.ROLLED_BACK;
      }
    }
    if (kept == Kept.ROLLED_BACK && target.rollback() != null) {
      kept = Kept.IN_DOUBT;
    }
    if (kept != Kept.IN_DOUBT) {
      target.end(); // a session that fails here is closed after the run anyway
    }
    return kept;
  }

  // Clears the interrupt of a cancel, which must not reach what ends the transaction.
  private void calm() {
    synchronized (lock) {
      if (interrupted) {
        Thread.interrupted();
        interrupted = false;
      }
    }
  }

  private Progress progress(StatementReader file) {
    return new Progress(file.offset(), file.size(), statements, failed);
  }

  private Report report(Ending ending, Kept kept, Progress last, long started) {
    var progress = new Progress(last.bytes(), last.size(), statements, failed);
    return new Report(
        ending, progress, failures, kept, Duration.ofNanos(System.nanoTime() - started));
  }

  // The failure of a step, at the line the server names where it does.
  private void fail(StatementReader.Step step, Failure failure) {
    var text = switch (step) {
      case StatementReader.Sql s -> s.sql();
      case StatementReader.Copy c -> c.sql();
      case StatementReader.Command c -> c.text();
    };
    long line = step.line();
    if (!(step instanceof StatementReader.Command)) {
      var place = failure.place(text, provider);
      if (place.isPresent()) {
        line += text.substring(0, place.get().start())
            .chars()
            .filter(c -> c == '\n')
            .count();
      }
    }
    var masked = step instanceof StatementReader.Command ? text : Secrets.masked(text, provider);
    var message = masked.equals(text) ? message(failure) : Outcome.WITHHELD;
    fail(line, masked.length() > SHOWN ? masked.substring(0, SHOWN) : masked, message);
  }

  private void fail(long line, String statement, String message) {
    if (failures.size() < KEPT) {
      failures.add(new Failed(line, statement, message));
    }
  }

  // The server's words, with its detail where it gives one; what HooriDB says where it gives none.
  private static String message(Failure failure) {
    var message = failure.message().isBlank()
        ? switch (failure.kind()) {
          case CANCELLED -> "Cancelled";
          case CONNECTION_LOST -> "The connection was lost";
          default -> "Failed";
        }
        : failure.message();
    return failure.detail().isBlank() ? message : message + "\n" + failure.detail();
  }

  /**
   * The session of a connection of the provider: statements through {@link Execution}, logged as
   * {@link Purpose#FILE}, refused where they may turn a read-only session's {@code readOnly} off;
   * copies through the dialect's {@link dev.hoori.db.core.dialect.ClientDialect#copyIn}.
   */
  public static Target target(Connection connection, Provider provider, boolean readOnly) {
    return new Target() {
      @Override
      public Failure run(String sql) {
        try (var execution = new Execution(connection, provider, sql, readOnly, Purpose.FILE)) {
          while (true) {
            var portion = execution.next(ROWS);
            for (var result : portion.results()) {
              if (result instanceof Failure failure) {
                return failure;
              }
            }
            if (!portion.more()) {
              return null;
            }
          }
        }
      }

      @Override
      public Failure copy(String sql, Reader rows) {
        var client = provider.dialect().client();
        var thrown = new AtomicReference<IOException>();
        try {
          // The reader stops a copy that is cancelled, as the run cancels it, so that the driver
          // ends the copy on the server; past Query's grace the connection is given up.
          Journal.sql(
              provider,
              Purpose.FILE,
              sql,
              copied -> thrown.get() == null
                  ? new Outcome.Changed(copied)
                  : thrown.get() instanceof InterruptedIOException
                      ? new Outcome.Cancelled()
                      : new Outcome.Failed(thrown.get().getMessage()),
              () -> Query.run(connection, provider, () -> {
                try {
                  return client.copyIn(connection, sql, rows);
                } catch (IOException e) {
                  thrown.set(e);
                  return -1L;
                }
              }));
        } catch (QueryException e) {
          return failure(e);
        }
        var problem = thrown.get();
        if (problem == null) {
          return null;
        }
        return problem instanceof InterruptedIOException
            ? Failure.cancelled()
            : Failure.of(Failure.Kind.OTHER, "The file could not be read: " + problem.getMessage());
      }

      @Override
      public Failure begin() {
        return step(Journal.autoCommit(false), () -> connection.setAutoCommit(false));
      }

      @Override
      public Failure commit() {
        return step(Journal.COMMIT, connection::commit);
      }

      @Override
      public Failure rollback() {
        return step(Journal.ROLLBACK, connection::rollback);
      }

      @Override
      public Failure end() {
        return step(Journal.autoCommit(true), () -> connection.setAutoCommit(true));
      }

      private Failure step(String called, Action action) {
        try {
          Journal.call(
              Purpose.TRANSACTION,
              called,
              () -> Query.within(connection, provider, Execution.ENDING, () -> {
                action.run();
                return null;
              }));
          return null;
        } catch (QueryException e) {
          return failure(e);
        }
      }

      private Failure failure(QueryException e) {
        if (e.failure() == QueryException.Failure.ABORTED) {
          return Failure.connectionLost();
        }
        return e.getCause() == null
            ? Failure.cancelled()
            : Failure.of(connection, provider, e.getCause());
      }
    };
  }

  private interface Action {
    void run() throws SQLException;
  }
}

package dev.hoori.db.core.log;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.DocumentDialect;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.sql.Secrets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one seam that every statement and command of core passes through on its way to a server:
 * each is recorded, once it has ended, for the {@link Recorder} that the caller bound with {@link
 * #recording}, with its text as it was sent and a password masked, as the dialect says where one
 * goes. Without a recorder bound, the call runs as it is and nothing is made of its text.
 *
 * <p>The recorder is the caller's thread's, and core carries it into the threads it starts for a
 * call, with {@link #carried}, so that what a worker sends, such as the commit of a statement's
 * transaction of its own, is recorded too.
 */
public final class Journal {

  private static final ScopedValue<Recorder> RECORDER = ScopedValue.newInstance();

  /** The calls of the driver that reach the server, as they are recorded. */
  public static final String COMMIT = "commit()";

  public static final String ROLLBACK = "rollback()";
  public static final String CANCEL = "cancel()";

  private Journal() {}

  /** What runs and sends: a statement's execution, or a step of the driver. */
  @FunctionalInterface
  public interface Call<T, E extends Exception> {
    T call() throws E;
  }

  /** The call, with what it sends recorded for the recorder, on this thread and those it starts. */
  public static <T, E extends Exception> T recording(Recorder recorder, Call<T, E> call) throws E {
    return ScopedValue.where(RECORDER, recorder).call(call::call);
  }

  /** Whether what is sent now is recorded. */
  public static boolean recording() {
    return RECORDER.isBound();
  }

  /** The work, to run on another thread, with what it sends recorded as on this one. */
  public static Runnable carried(Runnable work) {
    if (!RECORDER.isBound()) {
      return work;
    }
    var recorder = RECORDER.get();
    return () -> ScopedValue.where(RECORDER, recorder).run(work);
  }

  /** Runs the call that sends the SQL, recorded with its passwords masked, as {@link Secrets}. */
  public static <T, E extends Exception> T sql(
      Provider provider,
      Purpose purpose,
      String sql,
      Function<? super T, Outcome> outcome,
      Call<T, E> call)
      throws E {
    if (!RECORDER.isBound()) {
      return call.call();
    }
    return send(
        purpose,
        null,
        () -> {
          var masked = Secrets.masked(sql, provider);
          return new Shown(masked, !masked.equals(sql));
        },
        outcome,
        call);
  }

  /**
   * Runs the call that sends the command to the database, recorded as the dialect shows it, with
   * its secrets masked.
   */
  public static <T, E extends Exception> T command(
      DocumentDialect dialect,
      Purpose purpose,
      String database,
      Document command,
      Function<? super T, Outcome> outcome,
      Call<T, E> call)
      throws E {
    if (!RECORDER.isBound()) {
      return call.call();
    }
    return send(
        purpose,
        database,
        // A secret is there where two masks make two texts.
        () -> new Shown(
            dialect.shown(command),
            !command
                .masked(dialect.secretFields(), "a")
                .toString()
                .equals(command.masked(dialect.secretFields(), "b").toString())),
        outcome,
        call);
  }

  /**
   * Runs a call of the driver that reaches the server without a statement of HooriDB's, such as
   * {@link #COMMIT}, recorded by what it is called.
   */
  public static <T, E extends Exception> T call(Purpose purpose, String called, Call<T, E> call)
      throws E {
    if (!RECORDER.isBound()) {
      return call.call();
    }
    return send(purpose, null, () -> new Shown(called, false), value -> Outcome.DONE, call);
  }

  /** The call that switches auto-commit, by what it is called. */
  public static String autoCommit(boolean on) {
    return "setAutoCommit(" + on + ")";
  }

  /** The call that puts the session on the database, by what it is called. */
  public static String catalog(String database) {
    return "setCatalog(\"" + database.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
  }

  private static <T, E extends Exception> T send(
      Purpose purpose,
      String database,
      Supplier<Shown> text,
      Function<? super T, Outcome> outcome,
      Call<T, E> call)
      throws E {
    var recorder = RECORDER.get();
    var at = Instant.now();
    long started = System.nanoTime();
    T value;
    try {
      value = call.call();
    } catch (Exception e) {
      record(recorder, new Entry(at, started, purpose, database, text), Outcome.of(e));
      throw e;
    }
    Outcome ended;
    try {
      ended = outcome.apply(value);
    } catch (RuntimeException e) {
      ended = Outcome.DONE; // what it gave says nothing more
    }
    record(recorder, new Entry(at, started, purpose, database, text), ended);
    return value;
  }

  // What is known of a send before it ends.
  private record Entry(
      Instant at, long started, Purpose purpose, String database, Supplier<Shown> text) {}

  // The text as it is recorded, and whether a secret was masked in it.
  private record Shown(String text, boolean masked) {}

  // A recorder that fails loses its entry, never the call's result. A failure of what carried a
  // secret may repeat it, as a syntax error quotes where it is: its message is withheld.
  private static void record(Recorder recorder, Entry entry, Outcome outcome) {
    var took = Duration.ofNanos(System.nanoTime() - entry.started());
    try {
      if (!recorder.taking()) {
        return;
      }
      var shown = entry.text().get();
      var ended = shown.masked() && outcome instanceof Outcome.Failed
          ? new Outcome.Failed(Outcome.WITHHELD)
          : outcome;
      recorder.sent(
          new Sent(entry.at(), entry.purpose(), entry.database(), shown.text(), took, ended));
    } catch (RuntimeException e) {
      // Dropped: the log is for looking at, and the call went through.
    }
  }
}

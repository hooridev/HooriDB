package dev.hoori.db.core.query;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.query.QueryException.Failure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Runs a statement so that an interrupt of the caller can cancel it on the server.
 *
 * <p>Each call blocks, without a time limit. A connection serves one call at a time: the caller
 * does not use it for anything else until the call returns. The statement is made, run and closed
 * on a thread of its own, and the call returns once it is closed.
 *
 * <p>An interrupted caller always gets {@link Failure#CANCELLED} or {@link Failure#ABORTED}, also
 * where the result is there already, and stays interrupted. {@link Failure#CANCELLED}: the
 * statement has ended, and the connection is usable, as nothing sent to stop it can reach a later
 * statement. A cancel is known to have arrived when the statement reports that the server stopped
 * it, and on MariaDB and MySQL also when their drivers' cancel returns, as it waits for the
 * server's answer to its {@code KILL QUERY}; a {@code KILL QUERY} that finds the connection idle
 * does not touch its next statement. pgjdbc does not say whether its cancel arrived. On PostgreSQL
 * with auto-commit off, a cancelled statement fails the transaction, which the caller then rolls
 * back.
 *
 * <p>{@link Failure#ABORTED}: the connection is closed and must be discarded, because the
 * statement did not end within {@link #GRACE} of the cancel, or a cancel was sent that may still
 * be on its way. Giving the connection up takes at most another {@link #GRACE} and never waits for
 * the server: interrupting the threads that wait in the driver closes their sockets, and the
 * connection is aborted. Only a driver that does not let go of them within that time leaves them
 * to end on their own, on the connection given up.
 */
public final class Query {

  /** How long a cancelled statement has to end, and a connection given up has to close. */
  public static final Duration GRACE = Duration.ofSeconds(2);

  private Query() {}

  /** What runs with the prepared statement: sets its parameters, executes it, reads its result. */
  public interface Work<T> {
    T run(PreparedStatement statement) throws SQLException;
  }

  /** What reads the rows of a query. */
  public interface Result<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** A step of work on a connection, such as executing a statement or reading its rows. */
  public interface Step<T> {
    T run() throws SQLException;
  }

  /** What stops a step on the server, such as {@link Statement#cancel}. */
  public interface Cancel {
    void cancel() throws SQLException;
  }

  // A cancel that says whether it sent anything.
  private interface Stop {
    boolean stop() throws SQLException;
  }

  /**
   * Prepares the statement and runs the work with it. A driver may apply JDBC escapes to the text
   * of a prepared statement, though not within a quoted name, and takes a {@code ?} there for no
   * parameter; a value goes in a parameter.
   */
  public static <T> T run(Connection connection, Provider provider, String sql, Work<T> work)
      throws QueryException {
    var handle = new Handle();
    return run(
        connection,
        provider,
        handle::cancel,
        () -> {
          try (var statement = connection.prepareStatement(sql);
              var _ = handle.start(statement)) {
            return work.run(statement);
          }
        },
        value -> false);
  }

  /**
   * Runs the text as a query of a plain statement, as it is, without escape processing, and reads
   * its rows.
   */
  public static <T> T query(Connection connection, Provider provider, String sql, Result<T> result)
      throws QueryException {
    var handle = new Handle();
    return run(
        connection,
        provider,
        handle::cancel,
        () -> {
          try (var statement = connection.createStatement();
              var _ = handle.start(statement)) {
            statement.setEscapeProcessing(false);
            try (var rows = statement.executeQuery(sql)) {
              return result.read(rows);
            }
          }
        },
        value -> false);
  }

  /**
   * Runs a step that nothing can stop on the server, such as a switch of the connection's
   * database. An interrupt waits {@link #GRACE} for it to end, which leaves the connection usable,
   * and gives the connection up after that; either way the caller cannot know whether the step
   * took effect.
   */
  public static <T> T run(Connection connection, Provider provider, Step<T> step)
      throws QueryException {
    return run(connection, provider, () -> false, step, value -> false);
  }

  /**
   * Runs the step, with the given cancel in place of a statement's.
   *
   * @param stopped whether the step's value shows that the server stopped it, for a step that
   *     reports a failure in its value rather than by throwing
   */
  public static <T> T run(
      Connection connection,
      Provider provider,
      Cancel cancel,
      Step<T> step,
      Predicate<? super T> stopped)
      throws QueryException {
    return run(
        connection,
        provider,
        () -> {
          cancel.cancel();
          return true;
        },
        step,
        stopped);
  }

  private static <T> T run(
      Connection connection,
      Provider provider,
      Stop stop,
      Step<T> step,
      Predicate<? super T> stopped)
      throws QueryException {
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    var result = new CompletableFuture<T>();
    var worker = Thread.ofVirtual().name("query").start(() -> {
      try {
        result.complete(step.run());
      } catch (Throwable e) { // any, an Error too, so that the wait for it always ends
        result.completeExceptionally(e);
      }
    });
    T value;
    try {
      // get() does not look at the interrupt when the result is there, hence the checks after it.
      value = result.get();
    } catch (InterruptedException _) {
      var failure = stop(connection, provider, stop, result, worker, stopped);
      Thread.currentThread().interrupt();
      throw failure;
    } catch (ExecutionException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw cancelled();
      }
      throw failure(connection, provider, e.getCause());
    }
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    return value;
  }

  /**
   * Runs a step that must end, such as a commit or a rollback as a statement is given up, within
   * the limit: an interrupt does not stop it, and where it has not ended by then, the connection
   * is given up, as a server that no longer answers is, and the step with it.
   *
   * @throws QueryException {@link Failure#ABORTED} where the limit passed, the step's own failure
   *     otherwise
   */
  public static <T> T within(Connection connection, Provider provider, Duration limit, Step<T> step)
      throws QueryException {
    return within(connection, provider, limit, step, () -> {});
  }

  /**
   * As {@link #within(Connection, Provider, Duration, Step)}, and {@code passed} runs as the limit passes,
   * before the connection is given up, so that the step, should it wake up after all, can see that
   * it was.
   */
  public static <T> T within(
      Connection connection, Provider provider, Duration limit, Step<T> step, Runnable passed)
      throws QueryException {
    var result = new CompletableFuture<T>();
    var worker = Thread.ofVirtual().name("query within").start(() -> {
      try {
        result.complete(step.run());
      } catch (Throwable e) { // any, an Error too, so that the wait for it always ends
        result.completeExceptionally(e);
      }
    });
    var interrupted = false;
    var deadline = System.nanoTime() + limit.toNanos();
    try {
      while (true) {
        try {
          return result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
          interrupted = true; // the step ends all the same, within the limit
        } catch (ExecutionException e) {
          throw failure(connection, provider, e.getCause());
        } catch (TimeoutException _) {
          passed.run();
          var canceller = Thread.ofVirtual().unstarted(() -> {});
          canceller.start();
          abandon(connection, worker, canceller);
          throw new QueryException(
              Failure.ABORTED,
              "The server did not answer within " + limit.toSeconds() + " seconds",
              null);
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** The failure that the driver's exception means, on the connection that threw it. */
  public static QueryException failure(Connection connection, Provider provider, Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    if (!(thrown instanceof SQLException e)) {
      return new QueryException(Failure.OTHER, message, thrown);
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    var code = e.getErrorCode();
    Failure failure;
    if (state.startsWith("08")
        || e instanceof SQLNonTransientConnectionException
        || e instanceof SQLRecoverableException
        || closed(connection)) {
      failure = Failure.CONNECTION_LOST;
    } else if (Condition.CANCELLED.in(provider, state, code)) {
      failure = Failure.CANCELLED;
    } else if (Condition.ACCESS_DENIED.in(provider, state, code)) {
      failure = Failure.ACCESS_DENIED;
    } else if (Condition.NOT_FOUND.in(provider, state, code)) {
      failure = Failure.NOT_FOUND;
    } else {
      failure = Failure.OTHER;
    }
    return new QueryException(failure, message, thrown);
  }

  private static QueryException cancelled() {
    return new QueryException(Failure.CANCELLED, "", null);
  }

  // The caller was interrupted while the step ran: cancels it, and gives the connection up unless
  // the step has ended and nothing sent to stop it can still be on its way.
  private static <T> QueryException stop(
      Connection connection,
      Provider provider,
      Stop stop,
      CompletableFuture<T> result,
      Thread worker,
      Predicate<? super T> stopped) {
    if (result.isDone()) {
      return cancelled();
    }
    var sent = new CompletableFuture<Boolean>();
    var canceller = Thread.ofVirtual().name("query cancel").start(() -> {
      try {
        sent.complete(stop.stop());
      } catch (Throwable e) { // any, an Error too, so that the wait for it always ends
        sent.completeExceptionally(e);
      }
    });
    var deadline = System.nanoTime() + GRACE.toNanos();
    if (ended(result, deadline) && ended(sent, deadline)) {
      var answered = !sent.isCompletedExceptionally();
      var byServer = switch (result.state()) {
        case SUCCESS -> stopped.test(result.resultNow());
        case FAILED ->
          result.exceptionNow() instanceof SQLException e
              && byServer(
                  provider, e.getSQLState() == null ? "" : e.getSQLState(), e.getErrorCode());
        default -> false;
      };
      if ((answered && !sent.resultNow())
          || byServer
          || (answered && provider.dialect().session().cancelAnswered())) {
        return cancelled();
      }
    }
    abandon(connection, worker, canceller);
    return new QueryException(Failure.ABORTED, "", null);
  }

  /**
   * Whether a failure with the SQLSTATE and error code says that the server stopped the statement
   * for a cancel, and not for a time limit of its own that ends it as a cancel would.
   */
  public static boolean byServer(Provider provider, String state, int code) {
    return Condition.CANCELLED.in(provider, state, code)
        && !Condition.TIME_LIMIT.in(provider, state, code);
  }

  // Whether the future is done by the deadline; a second interrupt gives up the wait. It is taken
  // here, so that it cuts no wait after it short; run interrupts the caller again.
  private static boolean ended(CompletableFuture<?> future, long deadline) {
    try {
      future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (ExecutionException _) {
      // Done all the same.
    } catch (TimeoutException | InterruptedException _) {
      return false;
    }
    return true;
  }

  // An interrupt of a virtual thread that waits in a socket closes the socket, which ends what the
  // worker and the canceller wait for without the server. The abort comes after the worker has let
  // go: MariaDB Connector/J's abort ignores its executor, and while the worker holds its lock it
  // first sends a KILL on a connection of its own, which waits for the server. pgjdbc and MySQL
  // Connector/J close on the executor, here at once.
  private static void abandon(Connection connection, Thread worker, Thread canceller) {
    worker.interrupt();
    canceller.interrupt();
    var deadline = System.nanoTime() + GRACE.toNanos();
    // An interrupt cuts short the one wait that takes it, and the caller is interrupted again after
    // the last.
    var interrupted = join(worker, deadline);
    var aborter = Thread.ofVirtual().name("query abort").start(() -> {
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException _) {
        // The sockets are closed already, or not even an abort is possible.
      }
    });
    interrupted |= join(aborter, deadline);
    interrupted |= join(canceller, deadline);
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // Waits for the thread until the deadline or an interrupt, which it takes; whether one came.
  private static boolean join(Thread thread, long deadline) {
    try {
      thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
      return false;
    } catch (InterruptedException _) {
      return true;
    }
  }

  private static boolean closed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException | RuntimeException _) {
      return true;
    }
  }

  // The statement a worker runs, once it is made, so that a cancel can reach it; a cancel that
  // comes before keeps it from running. The statement is closed only once a cancel under way has
  // returned: MySQL Connector/J's fails on a statement closed meanwhile, and may not have sent its
  // KILL QUERY.
  private static final class Handle {

    private final ReentrantLock lock = new ReentrantLock();
    private Statement statement;
    private boolean cancelled;

    // What ends the statement's run: it waits for a cancel under way, unless the worker is
    // interrupted, which only happens to a connection given up.
    interface Started extends AutoCloseable {
      @Override
      void close();
    }

    Started start(Statement statement) throws SQLException {
      try {
        lock.lockInterruptibly();
      } catch (InterruptedException _) {
        // Only a worker given up is interrupted; it stays so for what closes after.
        Thread.currentThread().interrupt();
        throw new SQLException("Given up before it ran", "08006");
      }
      try {
        if (cancelled) {
          throw new SQLException("Cancelled before it ran", "57014");
        }
        this.statement = statement;
      } finally {
        lock.unlock();
      }
      return () -> {
        try {
          lock.lockInterruptibly();
          lock.unlock();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      };
    }

    boolean cancel() throws SQLException {
      lock.lock();
      try {
        cancelled = true;
        if (statement == null) {
          return false;
        }
        statement.cancel();
        return true;
      } finally {
        lock.unlock();
      }
    }
  }
}

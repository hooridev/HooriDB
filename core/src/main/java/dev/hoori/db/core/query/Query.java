package dev.hoori.db.core.query;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException.Failure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
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

  // MySQL error codes: no access to a database, a table, a column, or without a privilege.
  private static final Set<Integer> MYSQL_ACCESS_DENIED = Set.of(1044, 1142, 1143, 1227);

  // PostgreSQL's undefined table, column and schema, and MariaDB's and MySQL's unknown table and
  // column.
  private static final Set<String> NOT_FOUND = Set.of("42P01", "42703", "3F000", "42S02", "42S22");

  // MariaDB's and MySQL's unknown database, whose state is the general 42000.
  private static final int MYSQL_UNKNOWN_DATABASE = 1049;

  // MariaDB's max_statement_time, which shares the state of a cancelled statement.
  private static final int MARIADB_STATEMENT_TIME = 1969;

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
   * of a prepared statement: it holds no names, which go in parameters.
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
              var started = handle.start(statement)) {
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
              var started = handle.start(statement)) {
            statement.setEscapeProcessing(false);
            try (var rows = statement.executeQuery(sql)) {
              return result.read(rows);
            }
          }
        },
        value -> false);
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
      } catch (Throwable e) {
        result.completeExceptionally(e);
      }
    });
    T value;
    try {
      // get() does not look at the interrupt when the result is there, hence the checks after it.
      value = result.get();
    } catch (InterruptedException e) {
      var failure = stop(connection, provider, stop, result, worker, stopped);
      Thread.currentThread().interrupt();
      throw failure;
    } catch (ExecutionException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw cancelled();
      }
      throw failure(connection, e.getCause());
    }
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    return value;
  }

  /** The failure that the driver's exception means, on the connection that threw it. */
  public static QueryException failure(Connection connection, Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    if (!(thrown instanceof SQLException e)) {
      return new QueryException(Failure.OTHER, message, thrown);
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    Failure failure;
    if (state.startsWith("08")
        || e instanceof SQLNonTransientConnectionException
        || e instanceof SQLRecoverableException
        || closed(connection)) {
      failure = Failure.CONNECTION_LOST;
    } else if (cancellation(e)) {
      failure = Failure.CANCELLED;
    } else if (state.equals("42501") || MYSQL_ACCESS_DENIED.contains(e.getErrorCode())) {
      failure = Failure.ACCESS_DENIED;
    } else if (NOT_FOUND.contains(state) || e.getErrorCode() == MYSQL_UNKNOWN_DATABASE) {
      failure = Failure.NOT_FOUND;
    } else {
      failure = Failure.OTHER;
    }
    return new QueryException(failure, message, thrown);
  }

  // PostgreSQL's query_canceled, and MariaDB's and MySQL's "Query execution was interrupted".
  private static boolean cancellation(SQLException e) {
    return "57014".equals(e.getSQLState()) || "70100".equals(e.getSQLState());
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
      } catch (Throwable e) {
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
              && cancellation(e)
              && e.getErrorCode() != MARIADB_STATEMENT_TIME;
        default -> false;
      };
      if ((answered && !sent.resultNow())
          || byServer
          || (answered && provider != Provider.POSTGRESQL)) {
        return cancelled();
      }
    }
    abandon(connection, worker, canceller);
    return new QueryException(Failure.ABORTED, "", null);
  }

  // Whether the future is done by the deadline; a second interrupt gives up the wait.
  private static boolean ended(CompletableFuture<?> future, long deadline) {
    try {
      future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      // Done all the same.
    } catch (TimeoutException | InterruptedException e) {
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
    join(worker, deadline);
    var aborter = Thread.ofVirtual().name("query abort").start(() -> {
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException e) {
        // The sockets are closed already, or not even an abort is possible.
      }
    });
    join(aborter, deadline);
    join(canceller, deadline);
  }

  private static void join(Thread thread, long deadline) {
    try {
      thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    } catch (InterruptedException e) {
      // The caller stays interrupted; the wait is bounded either way.
    }
  }

  private static boolean closed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException | RuntimeException e) {
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
      } catch (InterruptedException e) {
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
        } catch (InterruptedException e) {
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

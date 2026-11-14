package dev.hoori.db.core.query;

import dev.hoori.db.core.query.QueryException.Failure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a statement so that an interrupt of the caller can cancel it on the server.
 *
 * <p>{@link #run} blocks, without a time limit. A connection serves one call at a time: the caller
 * does not use it for anything else until the call returns. An interrupt of the calling thread
 * ends the call with {@link Failure#CANCELLED}, and leaves the thread interrupted. The statement
 * is cancelled on the server and the connection stays usable; only if the driver cannot stop it
 * within {@link #GRACE}, as when the server no longer answers, is the connection aborted, and
 * closed from then on. On PostgreSQL with auto-commit off, a cancelled statement fails the
 * transaction, which the caller then rolls back.
 */
public final class Query {

  /** How long a cancelled statement has to end before its connection is aborted. */
  public static final Duration GRACE = Duration.ofSeconds(2);

  // MySQL error codes: no access to a database, a table, a column, or without a privilege.
  private static final Set<Integer> MYSQL_ACCESS_DENIED = Set.of(1044, 1142, 1143, 1227);

  // PostgreSQL's undefined table, column and schema, and MariaDB's and MySQL's unknown table and
  // column.
  private static final Set<String> NOT_FOUND = Set.of("42P01", "42703", "3F000", "42S02", "42S22");

  // MariaDB's and MySQL's unknown database, whose state is the general 42000.
  private static final int MYSQL_UNKNOWN_DATABASE = 1049;

  private Query() {}

  /** What runs with the prepared statement: sets its parameters, executes it, reads its result. */
  public interface Work<T> {
    T run(PreparedStatement statement) throws SQLException;
  }

  /** A step of work on a connection, such as executing a statement or reading its rows. */
  public interface Step<T> {
    T run() throws SQLException;
  }

  /** What stops a step on the server, such as {@link Statement#cancel}. */
  public interface Cancel {
    void cancel() throws SQLException;
  }

  /**
   * Prepares the statement and runs the work with it on a thread of its own, so that an interrupt
   * of the caller can cancel it on the server: an interrupt of a virtual thread that is in a
   * driver's call closes the driver's socket, and a platform thread's is not noticed until the
   * server answers. The statement is closed when the work ends.
   */
  public static <T> T run(Connection connection, String sql, Work<T> work) throws QueryException {
    if (Thread.currentThread().isInterrupted()) {
      throw new QueryException(Failure.CANCELLED, "", null);
    }
    PreparedStatement statement;
    try {
      statement = connection.prepareStatement(sql);
    } catch (SQLException | RuntimeException e) {
      throw failure(connection, e);
    }
    return run(connection, statement::cancel, () -> {
      try (statement) {
        return work.run(statement);
      }
    });
  }

  /**
   * Runs the step on a thread of its own, as {@link #run(Connection, String, Work)} runs its work,
   * with the given cancel in place of the statement's.
   */
  public static <T> T run(Connection connection, Cancel cancel, Step<T> step)
      throws QueryException {
    if (Thread.currentThread().isInterrupted()) {
      throw new QueryException(Failure.CANCELLED, "", null);
    }
    var result = new CompletableFuture<T>();
    Thread.ofVirtual().name("query").start(() -> {
      try {
        result.complete(step.run());
      } catch (Throwable e) {
        result.completeExceptionally(e);
      }
    });
    try {
      return result.get();
    } catch (InterruptedException e) {
      cancel(connection, cancel, result);
      Thread.currentThread().interrupt();
      throw new QueryException(Failure.CANCELLED, "", null);
    } catch (ExecutionException e) {
      throw failure(connection, e.getCause());
    }
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
    } else if (state.equals("57014") || state.equals("70100")) {
      // PostgreSQL's query_canceled, and MariaDB's and MySQL's "Query execution was interrupted".
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

  // Waits for the cancel as well as for the statement: a cancel that reaches the server late would
  // stop the caller's next statement instead.
  private static void cancel(Connection connection, Cancel cancel, Future<?> result) {
    var canceller = Thread.ofVirtual().name("query cancel").start(() -> {
      try {
        cancel.cancel();
      } catch (SQLException | RuntimeException e) {
        // The statement is over, or the connection is gone; either way nothing runs any more.
      }
    });
    var deadline = System.nanoTime() + GRACE.toNanos();
    boolean ended;
    try {
      try {
        result.get(GRACE.toNanos(), TimeUnit.NANOSECONDS);
      } catch (ExecutionException e) {
        // What a cancelled statement throws.
      }
      ended = canceller.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    } catch (TimeoutException | InterruptedException e) {
      ended = false;
    }
    if (!ended) {
      try {
        connection.abort(task -> Thread.ofVirtual().start(task));
      } catch (SQLException | RuntimeException e) {
        // Not even an abort is possible; the connection stays as the driver left it.
      }
    }
  }

  private static boolean closed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException | RuntimeException e) {
      return true;
    }
  }
}

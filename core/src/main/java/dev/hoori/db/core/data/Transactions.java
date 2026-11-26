package dev.hoori.db.core.data;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The transactions that this package's writes and reads open of their own on a connection that has
 * auto-commit on, and how they end: within {@link Execution#ENDING}, an interrupt or not, with the
 * connection given up where they do not, so that it is left as it was found or not used again.
 */
final class Transactions {

  private Transactions() {}

  /** Turns auto-commit off, which the next statement's transaction begins with. */
  static void begin(Connection connection, Provider provider) throws QueryException {
    Query.within(
        connection,
        provider,
        Execution.ENDING,
        () -> Journal.call(Purpose.TRANSACTION, Journal.autoCommit(false), () -> {
          connection.setAutoCommit(false);
          return null;
        }));
  }

  /**
   * Commits what the transaction did and stays without auto-commit, so that the next statement
   * begins the next one; a failure of the commit's is withheld from the log, as it may quote a row.
   */
  static void commit(Connection connection, Provider provider) throws QueryException {
    Query.within(
        connection,
        provider,
        Execution.ENDING,
        () -> Journal.callWithheld(Purpose.TRANSACTION, Journal.COMMIT, () -> {
          connection.commit();
          return null;
        }));
  }

  /** A savepoint, to which a statement that fails is rolled back, as the transaction goes on. */
  static Savepoint savepoint(Connection connection, Provider provider, Purpose purpose)
      throws QueryException {
    return Query.run(
        connection,
        provider,
        () -> Journal.call(purpose, Journal.SAVEPOINT, connection::setSavepoint));
  }

  /** Lets go of the savepoint, once what it guarded went well. */
  static void release(
      Connection connection, Provider provider, Purpose purpose, Savepoint savepoint)
      throws QueryException {
    Query.run(
        connection,
        provider,
        () -> Journal.call(purpose, Journal.RELEASE_SAVEPOINT, () -> {
          connection.releaseSavepoint(savepoint);
          return null;
        }));
  }

  /** Takes back what was done since the savepoint, an interrupt or not, within the bound. */
  static void rollBack(
      Connection connection, Provider provider, Purpose purpose, Savepoint savepoint)
      throws QueryException {
    Query.within(
        connection,
        provider,
        Execution.ENDING,
        () -> Journal.call(purpose, Journal.ROLLBACK_TO_SAVEPOINT, () -> {
          connection.rollback(savepoint);
          return null;
        }));
  }

  /**
   * Ends the transaction within a bound, an interrupt or not: commits or rolls back, and turns
   * auto-commit on again. A connection that cannot be left as it was found is aborted, which has
   * the server roll back what it did not commit; nothing after the commit or rollback fails what it
   * did, and nothing begins once the connection is given up for the bound.
   */
  static void end(Connection connection, Provider provider, boolean commit) throws QueryException {
    // Whether the transaction ended as it was to, committed or rolled back.
    var ended = new AtomicBoolean();
    var givenUp = new AtomicBoolean();
    try {
      Query.within(
          connection,
          provider,
          Execution.ENDING,
          () -> {
            finish(connection, commit, ended, givenUp);
            return null;
          },
          () -> givenUp.set(true));
    } catch (QueryException e) {
      if (!ended.get()) {
        throw e;
      }
    }
  }

  private static void finish(
      Connection connection, boolean commit, AtomicBoolean ended, AtomicBoolean givenUp)
      throws SQLException {
    if (connection.getAutoCommit()) {
      return; // never begun
    }
    var failed = commit ? commit(connection, ended, givenUp) : null;
    if (!ended.get()) {
      rollBack(connection, commit, ended, failed);
    }
    try {
      Journal.call(Purpose.TRANSACTION, Journal.autoCommit(true), () -> {
        connection.setAutoCommit(true);
        return null;
      });
    } catch (SQLException _) {
      abort(connection);
    }
    if (failed != null) {
      throw failed;
    }
  }

  // The commit's failure, or null where it committed.
  private static SQLException commit(
      Connection connection, AtomicBoolean ended, AtomicBoolean givenUp) {
    if (givenUp.get()) {
      return new SQLException("Given up before the commit", "08006");
    }
    try {
      Journal.callWithheld(Purpose.TRANSACTION, Journal.COMMIT, () -> {
        connection.commit();
        return null;
      });
      ended.set(true);
      return null;
    } catch (SQLException e) {
      return e;
    }
  }

  // Rolls back, after the commit failed where it did, which fails in its place.
  private static void rollBack(
      Connection connection, boolean commit, AtomicBoolean ended, SQLException failed)
      throws SQLException {
    try {
      Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> {
        connection.rollback();
        return null;
      });
      ended.set(!commit);
    } catch (SQLException e) {
      // pgjdbc would commit as auto-commit is turned on.
      abort(connection);
      if (failed != null) {
        failed.addSuppressed(e);
        throw failed;
      }
      throw e;
    }
  }

  private static void abort(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException _) {
      // Not even an abort is possible; the connection is given up all the same.
    }
  }
}

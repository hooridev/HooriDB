package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.dialect.Reflection.call;

import dev.hoori.db.core.data.PostgreSqlRows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.execution.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL, with pgjdbc. */
public final class PostgreSql implements Dialect {

  public static final PostgreSql INSTANCE = new PostgreSql();

  private final SessionDialect session = new Session();
  private final RowsDialect rows = new PostgreSqlRows();

  private PostgreSql() {}

  @Override
  public SessionDialect session() {
    return session;
  }

  @Override
  public RowsDialect rows() {
    return rows;
  }

  // pgjdbc.
  static final class Session implements SessionDialect {

    // The first words of a PostgreSQL statement that may read its rows in a transaction of its
    // own.
    private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES", "TABLE");

    @Override
    public int status(Connection connection) {
      return -1;
    }

    /**
     * PostgreSQL's transaction state, which pgjdbc keeps from every message of the server; unknown
     * on a closed connection, whose session the server may have ended.
     */
    @Override
    public Transaction transaction(Connection connection, int status) {
      try {
        if (connection.isClosed()) {
          return Transaction.UNKNOWN;
        }
        return switch (call(connection, "getTransactionState").toString()) {
          case "IDLE" -> Transaction.NONE;
          case "OPEN" -> Transaction.OPEN;
          case "FAILED" -> Transaction.FAILED;
          default -> Transaction.UNKNOWN;
        };
      } catch (SQLException | ReflectiveOperationException | RuntimeException e) {
        return Transaction.UNKNOWN;
      }
    }

    // pgjdbc's, which it keeps itself; not known on a closed connection.
    @Override
    public Boolean autoCommit(Connection connection, int status) {
      try {
        return connection.isClosed() ? null : connection.getAutoCommit();
      } catch (SQLException | RuntimeException e) {
        return null;
      }
    }

    @Override
    public Optional<String> statusQuery() {
      return Optional.empty();
    }

    @Override
    public void followAutoCommit(Connection connection, int status) {}

    @Override
    public void keepWriteTimeout(Connection connection) {}

    // pgjdbc's Statement.cancel() does nothing once the statement has returned its first rows; its
    // cancelQuery() cancels whatever the connection runs.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      try {
        call(connection, "cancelQuery");
        return;
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Statement.cancel() is all there is.
      }
      statement.cancel();
    }

    // One row more than asked for, so that the look at the next row needs no second round trip.
    @Override
    public int fetchSize(int count) {
      return count == Integer.MAX_VALUE ? 0 : count + 1;
    }

    // PostgreSQL sends rows in portions only within a transaction, and otherwise sends them all at
    // once.
    // shortcut: pgjdbc before 42.7.4 or so still reads all rows at once after a BEGIN the user
    // ran, as its auto-commit is on; the app recommends current drivers.
    @Override
    public Set<String> queriesInOwnTransaction() {
      return QUERIES;
    }

    // pgjdbc gives what comes while rows are fetched to the result set.
    @Override
    public boolean warningsKeptByDriver() {
      return true;
    }

    // PostgreSQL closes its cursor.
    @Override
    public boolean pendingRowsHoldConnection() {
      return false;
    }

    @Override
    public boolean streamedRowsClosedLast() {
      return false;
    }
  }
}

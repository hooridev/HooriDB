package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.dialect.Reflection.call;

import dev.hoori.db.core.data.MySqlRows;
import dev.hoori.db.core.data.RowsDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/** MySQL, with MySQL Connector/J. */
public final class MySql extends MySqlFamily {

  public static final MySql INSTANCE = new MySql();

  private static final RowsDialect ROWS = new MySqlRows();

  private MySql() {}

  @Override
  public RowsDialect rows() {
    return ROWS;
  }

  @Override
  public int status(Connection connection) {
    try {
      return (int) call(call(call(connection, "getSession"), "getServerSession"), "getStatusFlags");
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  /**
   * Has MySQL Connector/J's auto-commit say what the server's status says, after a statement such
   * as {@code SET autocommit=0} changed it on the server behind the driver's back; it then
   * refuses a commit. Setting it to what the server has already commits nothing.
   */
  @Override
  public void followAutoCommit(Connection connection, int status) throws SQLException {
    if (status < 0) {
      return;
    }
    var server = (status & SERVER_STATUS_AUTOCOMMIT) != 0;
    if (connection.getAutoCommit() != server) {
      connection.setAutoCommit(server);
    }
  }

  /**
   * Keeps MySQL Connector/J from raising the session's {@code net_write_timeout} for streamed
   * rows: it sets it back to what it read when it connected, not to what the session had, and
   * not at all for rows it does not close.
   */
  @Override
  public void keepWriteTimeout(Connection connection) {
    try {
      var properties = call(connection, "getPropertySet");
      var timeout = properties
          .getClass()
          .getMethod("getIntegerProperty", String.class)
          .invoke(properties, "netTimeoutForStreamingResults");
      timeout.getClass().getMethod("setValue", Object.class).invoke(timeout, 0);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // The driver changes it, and sets it back as it closes the rows.
    }
  }

  // MySQL Connector/J's Statement.cancel() does nothing once the statement has returned its first
  // rows: it is told that the statement still runs.
  @Override
  public void cancel(Connection connection, Statement statement) throws SQLException {
    try {
      var executing = call(call(statement, "getQuery"), "getStatementExecuting");
      ((AtomicBoolean) executing).set(true);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Statement.cancel() is all there is.
    }
    statement.cancel();
  }

  // MySQL Connector/J streams only with this value, a row at a time.
  @Override
  public int fetchSize(int count) {
    return Integer.MIN_VALUE;
  }

  // MySQL Connector/J may close streamed rows with a statement of its own, which it refuses while a
  // procedure has more results; they are closed after the last.
  @Override
  public boolean streamedRowsClosedLast() {
    return true;
  }
}

package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.dialect.Reflection.call;

import dev.hoori.db.core.data.MariaDbRows;
import dev.hoori.db.core.data.RowsDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** MariaDB, with MariaDB Connector/J. */
public final class MariaDb extends MySqlFamily {

  public static final MariaDb INSTANCE = new MariaDb();

  private static final RowsDialect ROWS = new MariaDbRows();

  private MariaDb() {}

  @Override
  public RowsDialect rows() {
    return ROWS;
  }

  @Override
  public int status(Connection connection) {
    try {
      return (int) call(call(connection, "getContext"), "getServerStatus");
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  // MariaDB Connector/J reads auto-commit from the status itself.
  @Override
  public void followAutoCommit(Connection connection, int status) {}

  @Override
  public void keepWriteTimeout(Connection connection) {}

  // MariaDB Connector/J cancels as long as it reads.
  @Override
  public void cancel(Connection connection, Statement statement) throws SQLException {
    statement.cancel();
  }

  @Override
  public boolean streamedRowsClosedLast() {
    return false;
  }
}

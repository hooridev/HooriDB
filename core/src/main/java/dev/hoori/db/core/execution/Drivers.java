package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What JDBC does not give but each driver's own public classes do. The drivers are loaded in a
 * class loader of their own, so their classes are reached by reflection; where a method is
 * missing, as in a version that lacks it, the answer is the one JDBC alone gives.
 */
final class Drivers {

  private Drivers() {}

  /** The parts of a PostgreSQL error, as pgjdbc's ServerErrorMessage has them. */
  record ServerError(
      String message,
      int position,
      String internalQuery,
      int internalPosition,
      String detail,
      String hint,
      String context) {}

  /** pgjdbc's parts of the error, of which its message holds only some, worded in the JVM's locale. */
  static ServerError serverError(SQLException e) {
    try {
      var server = call(e, "getServerErrorMessage");
      if (server == null) {
        return null;
      }
      return new ServerError(
          text(call(server, "getMessage")),
          (int) call(server, "getPosition"),
          text(call(server, "getInternalQuery")),
          (int) call(server, "getInternalPosition"),
          text(call(server, "getDetail")),
          text(call(server, "getHint")),
          text(call(server, "getWhere")));
    } catch (ReflectiveOperationException | RuntimeException x) {
      return null;
    }
  }

  // Bits of the server status that MariaDB and MySQL send with every OK and EOF.
  private static final int SERVER_STATUS_IN_TRANS = 1;
  private static final int SERVER_STATUS_AUTOCOMMIT = 2;

  /**
   * PostgreSQL's transaction state, which pgjdbc keeps from every message of the server; unknown
   * on a closed connection, whose session the server may have ended.
   */
  static Transaction transaction(Connection connection) {
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

  /**
   * The server status that MariaDB's and MySQL's drivers last read, or -1. It is current only
   * right after a result ended well: an error carries none, and MySQL Connector/J clears it while
   * it streams rows and once it closes them.
   */
  static int status(Provider provider, Connection connection) {
    try {
      return switch (provider) {
        case POSTGRESQL -> -1;
        case MARIADB -> (int) call(call(connection, "getContext"), "getServerStatus");
        case MYSQL ->
          (int) call(call(call(connection, "getSession"), "getServerSession"), "getStatusFlags");
      };
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  /** Whether auto-commit is on as the status says, or null where it is not known. */
  static Boolean autoCommit(int status) {
    return status < 0 ? null : (status & SERVER_STATUS_AUTOCOMMIT) != 0;
  }

  static Transaction transaction(int status) {
    if (status < 0) {
      return Transaction.UNKNOWN;
    }
    return (status & SERVER_STATUS_IN_TRANS) != 0 ? Transaction.OPEN : Transaction.NONE;
  }

  /**
   * Has MySQL Connector/J's auto-commit say what the server's status says, after a statement such
   * as {@code SET autocommit=0} changed it on the server behind the driver's back; it then
   * refuses a commit. Setting it to what the server has already commits nothing. MariaDB
   * Connector/J reads it from the status itself.
   */
  static void followAutoCommit(Provider provider, Connection connection, int status)
      throws SQLException {
    if (provider != Provider.MYSQL || status < 0) {
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
  static void keepWriteTimeout(Provider provider, Connection connection) {
    if (provider != Provider.MYSQL) {
      return;
    }
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

  /**
   * Cancels on the server what runs on the connection for the statement, also while its rows are
   * read. pgjdbc's and MySQL Connector/J's {@link Statement#cancel} do nothing once the statement
   * has returned its first rows; pgjdbc cancels whatever the connection runs, and Connector/J is
   * told that the statement still runs. MariaDB Connector/J cancels as long as it reads.
   */
  static void cancel(Provider provider, Connection connection, Statement statement)
      throws SQLException {
    try {
      switch (provider) {
        case POSTGRESQL -> {
          call(connection, "cancelQuery");
          return;
        }
        case MYSQL -> {
          var executing = call(call(statement, "getQuery"), "getStatementExecuting");
          ((AtomicBoolean) executing).set(true);
        }
        case MARIADB -> {}
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Statement.cancel() is all there is.
    }
    statement.cancel();
  }

  private static Object call(Object target, String method) throws ReflectiveOperationException {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }
}

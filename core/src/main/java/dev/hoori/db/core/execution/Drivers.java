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

  // Bit 1 of the server status that MariaDB and MySQL send with every OK and EOF.
  private static final int SERVER_STATUS_IN_TRANS = 1;

  static Transaction transaction(Provider provider, Connection connection) {
    try {
      return switch (provider) {
        case POSTGRESQL ->
          switch (call(connection, "getTransactionState").toString()) {
            case "IDLE" -> Transaction.NONE;
            case "OPEN" -> Transaction.OPEN;
            case "FAILED" -> Transaction.FAILED;
            default -> Transaction.UNKNOWN;
          };
        case MARIADB -> status((int) call(call(connection, "getContext"), "getServerStatus"));
        case MYSQL ->
          status((int)
              call(call(call(connection, "getSession"), "getServerSession"), "getStatusFlags"));
      };
    } catch (ReflectiveOperationException | RuntimeException e) {
      return Transaction.UNKNOWN;
    }
  }

  private static Transaction status(int status) {
    return (status & SERVER_STATUS_IN_TRANS) != 0 ? Transaction.OPEN : Transaction.NONE;
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

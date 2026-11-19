package dev.hoori.db.core.execution;

import java.sql.SQLException;

/**
 * What JDBC does not give of a failure but a driver's own public classes do. The drivers are
 * loaded in a class loader of their own, so their classes are reached by reflection; where a
 * method is missing, as in a version that lacks it, the answer is the one JDBC alone gives.
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

  private static Object call(Object target, String method) throws ReflectiveOperationException {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }
}

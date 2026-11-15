package dev.hoori.db.core.execution;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A made-up JDBC connection whose one statement has endless rows of one text column, and whose
 * statement's {@code getWarnings}, once {@code stuck} is set, never returns until released,
 * interrupted or not: a driver call that survives the interrupt and the abort, as a hung native
 * socket call can.
 */
public final class Stuck {

  private Stuck() {}

  /** The connection; {@code release} lets the stuck call return. */
  public static Connection connection(AtomicBoolean stuck, CountDownLatch release) {
    ResultSetMetaData meta = proxy(ResultSetMetaData.class, (name, args) -> switch (name) {
      case "getColumnCount" -> 1;
      case "getColumnTypeName" -> "text";
      case "getColumnLabel", "getColumnName" -> "a";
      case "getColumnType" -> Types.VARCHAR;
      default -> null;
    });
    ResultSet rows = proxy(ResultSet.class, (name, args) -> switch (name) {
      case "getMetaData" -> meta;
      case "next" -> true;
      case "getString", "getObject" -> "x";
      default -> null;
    });
    Statement statement = proxy(Statement.class, (name, args) -> switch (name) {
      case "execute" -> true;
      case "getResultSet" -> rows;
      case "getLargeUpdateCount" -> -1L;
      case "getWarnings" -> {
        while (stuck.get()) {
          try {
            release.await();
            break;
          } catch (InterruptedException e) {
            // Ignored, as such a call does.
          }
        }
        yield null;
      }
      default -> null;
    });
    return proxy(Connection.class, (name, args) -> switch (name) {
      case "createStatement" -> statement;
      case "getAutoCommit" -> true;
      default -> null;
    });
  }

  private interface Answer {
    Object answer(String name, Object[] args);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Answer answer) {
    return (T) Proxy.newProxyInstance(
        Stuck.class.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
          var value = answer.answer(m.getName(), args);
          if (value != null) {
            return value;
          }
          var r = m.getReturnType();
          if (r == boolean.class) {
            return false;
          }
          if (r == int.class) {
            return 0;
          }
          if (r == long.class) {
            return 0L;
          }
          if (r == double.class || r == float.class) {
            return 0.0;
          }
          return null;
        });
  }
}

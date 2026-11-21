package dev.hoori.db.core.execution;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A made-up JDBC connection, to PostgreSQL as core sees it, with no transaction open and
 * auto-commit on, whose one statement has endless rows of one text column. Once {@link #arm}ed,
 * the statement's {@code getWarnings} never returns until {@link #release}d, interrupted or not:
 * a driver call that survives the interrupt and the abort, as a hung native socket call can. It
 * keeps the names of the calls made on the connection and its statement, and the thread that got
 * stuck.
 */
public final class Stuck {

  /** pgjdbc's way to the transaction's state, which core asks by reflection. */
  public interface Pg {
    Object getTransactionState();
  }

  private final AtomicBoolean armed = new AtomicBoolean();
  private final CountDownLatch released = new CountDownLatch(1);
  private final AtomicReference<Thread> stuck = new AtomicReference<>();

  /** The calls made on the connection and its statement, by name, in order. */
  public final List<String> calls = new CopyOnWriteArrayList<>();

  /** From now on, the statement's {@code getWarnings} hangs. */
  public void arm() {
    armed.set(true);
  }

  /** Lets the stuck call return. */
  public void release() {
    released.countDown();
  }

  /** The thread stuck in the call, or null. */
  public Thread stuck() {
    return stuck.get();
  }

  public Connection connection() {
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
    Statement statement = proxy(Statement.class, (name, args) -> {
      calls.add("statement." + name);
      return switch (name) {
        case "execute" -> true;
        case "getResultSet" -> rows;
        case "getLargeUpdateCount" -> -1L;
        case "getWarnings" -> {
          if (armed.get()) {
            stuck.set(Thread.currentThread());
            while (true) {
              try {
                released.await();
                break;
              } catch (InterruptedException _) {
                // Ignored, as such a call does.
              }
            }
          }
          yield null;
        }
        default -> null;
      };
    });
    return (Connection) Proxy.newProxyInstance(
        Stuck.class.getClassLoader(), new Class<?>[] {Connection.class, Pg.class}, (p, m, args) -> {
          var name = m.getName();
          calls.add(name + (args != null && args.length == 1 ? "(" + args[0] + ")" : ""));
          return switch (name) {
            case "createStatement" -> statement;
            case "getAutoCommit" -> true;
            case "getTransactionState" -> "IDLE";
            default -> fallback(m.getReturnType());
          };
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
          return value != null ? value : fallback(m.getReturnType());
        });
  }

  private static Object fallback(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == double.class || type == float.class) {
      return 0.0;
    }
    return null;
  }
}

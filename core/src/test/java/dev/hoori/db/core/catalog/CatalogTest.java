package dev.hoori.db.core.catalog;

import static dev.hoori.db.core.catalog.CatalogServerTest.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.CatalogException.Failure;
import dev.hoori.db.core.connection.Provider;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** What needs no server: wrong arguments, and a driver that cannot stop its query. */
class CatalogTest {

  @Test
  void onlyPostgresqlHasSchemasAndHoldsItsTablesInThem() {
    var database = new Database("hoori", false);
    assertThrows(
        IllegalArgumentException.class, () -> Catalog.schemas(null, Provider.MYSQL, database));
    assertThrows(
        IllegalArgumentException.class, () -> Catalog.tables(null, Provider.POSTGRESQL, database));
    assertThrows(
        IllegalArgumentException.class,
        () -> Catalog.tables(null, Provider.MARIADB, new Schema(database, "public", false)));
  }

  @Test
  void aQueryThatACancelDoesNotStopAbortsTheConnection() throws Exception {
    var executing = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var ended = new CountDownLatch(1);
    // As a driver whose server no longer answers: the cancel goes nowhere, and the query waits on
    // its socket until an interrupt of its virtual thread closes it, or an abort does on the
    // executor it is given, as pgjdbc and MySQL Connector/J do.
    var statement = fake(PreparedStatement.class, (name, arguments) -> switch (name) {
      case "executeQuery" -> {
        executing.countDown();
        try {
          closed.await();
        } catch (InterruptedException e) {
          closed.countDown();
        }
        ended.countDown();
        yield new SQLException("Socket closed", "08006");
      }
      default -> null;
    });
    var connection = fake(Connection.class, (name, arguments) -> switch (name) {
      case "prepareStatement" -> statement;
      case "abort" -> {
        ((Executor) arguments[0]).execute(closed::countDown);
        yield null;
      }
      case "isClosed" -> closed.getCount() == 0;
      default -> null;
    });

    var failure = new CatalogException[1];
    var caller = Thread.ofPlatform()
        .start(() -> failure[0] = assertFailure(
            Failure.ABORTED,
            () -> Catalog.query(connection, Provider.MYSQL, "SELECT 1", rows -> null)));
    executing.await();
    var started = System.nanoTime();
    caller.interrupt();
    caller.join(Catalog.GRACE.multipliedBy(2).plusSeconds(1));
    assertFalse(caller.isAlive());
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Catalog.GRACE) >= 0);
    assertNotNull(failure[0]);
    // Closed, and the query over, by the time the call returned.
    assertTrue(connection.isClosed());
    assertEquals(0, ended.getCount());
  }

  // Answers each call by its method's name and arguments, with a thrown SQLException where the
  // answer is one, and false for a boolean that it has no answer for.
  private static <T> T fake(Class<T> type, BiFunction<String, Object[], Object> answer) {
    return type.cast(Proxy.newProxyInstance(
        type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
          var result = answer.apply(method.getName(), arguments);
          if (result instanceof SQLException e) {
            throw e;
          }
          return result == null && method.getReturnType() == boolean.class ? false : result;
        }));
  }
}

package dev.hoori.db.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException.Failure;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The worker against a made-up driver that behaves as the real ones do where it matters: a call
 * waits on a socket, which an interrupt of a virtual thread closes and a platform thread's does
 * not; abort closes the socket on the executor it is given; a cancel goes out on a socket of its
 * own.
 */
class QueryTest {

  // What the most a call may take that gives a connection up: the grace, the time to give it up,
  // and some slack.
  private static final Duration GIVING_UP = Query.GRACE.multipliedBy(2).plusSeconds(1);

  /** A socket of the made-up driver, and what it is to answer. */
  private static final class Socket {

    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch answer = new CountDownLatch(1);

    // Waits as a read of a socket does, until there is an answer or the socket is closed.
    void read() throws SQLException {
      if (Thread.currentThread().isVirtual()) {
        try {
          while (closed.getCount() > 0 && !answer.await(10, TimeUnit.MILLISECONDS)) {}
        } catch (InterruptedException e) {
          close();
        }
      } else {
        while (closed.getCount() > 0 && answer.getCount() > 0) {
          Thread.onSpinWait();
        }
      }
      if (closed.getCount() == 0) {
        throw new SQLException("Socket closed", "08006");
      }
    }

    void answer() {
      answer.countDown();
    }

    void close() {
      closed.countDown();
    }

    boolean isClosed() {
      return closed.getCount() == 0;
    }
  }

  /** What the made-up driver does; each part may be replaced. */
  private static class Driver {
    final Socket socket = new Socket();
    final Socket cancelSocket = new Socket();
    final CountDownLatch executing = new CountDownLatch(1);
    final CountDownLatch workerEnded = new CountDownLatch(1);
    final CountDownLatch cancellerEnded = new CountDownLatch(1);
    final AtomicInteger executions = new AtomicInteger();
    final AtomicBoolean aborted = new AtomicBoolean();

    void prepare() throws SQLException {}

    // The statement waits for the server's answer.
    Object execute() throws SQLException {
      socket.read();
      return null;
    }

    // As MySQL Connector/J's and MariaDB Connector/J's: a KILL QUERY on a connection of its own,
    // which waits for the server's answer.
    void cancel() throws SQLException {
      cancelSocket.read();
    }

    // As pgjdbc's and MySQL Connector/J's: the socket is closed on the executor.
    void abort(Executor executor) {
      aborted.set(true);
      executor.execute(socket::close);
    }

    void closeStatement() throws SQLException {}

    Connection connection() {
      var statement = fake(PreparedStatement.class, name -> switch (name) {
        case "executeQuery" -> {
          executions.incrementAndGet();
          executing.countDown();
          try {
            yield execute();
          } finally {
            workerEnded.countDown();
          }
        }
        case "cancel" -> {
          try {
            cancel();
          } finally {
            cancellerEnded.countDown();
          }
          yield null;
        }
        case "close" -> {
          closeStatement();
          yield null;
        }
        default -> null;
      });
      return fake(Connection.class, (name, arguments) -> switch (name) {
        case "prepareStatement" -> {
          prepare();
          yield statement;
        }
        case "abort" -> {
          abort((Executor) arguments[0]);
          yield null;
        }
        case "isClosed" -> socket.isClosed();
        default -> null;
      });
    }
  }

  private interface Call {
    Object call() throws Exception;
  }

  private static Object run(Connection connection, Provider provider) throws QueryException {
    return Query.run(connection, provider, "SELECT 1", statement -> statement.executeQuery());
  }

  @Test
  void aStatementThatACancelDoesNotStopIsGivenUpAndAllHasEndedWhenTheCallReturns()
      throws Exception {
    var provider = Provider.MYSQL;
    // As when the server no longer answers: neither the statement nor the cancel ends.
    var driver = new Driver();
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, provider), driver.executing);
    assertEquals(Failure.ABORTED, failure.failure());
    assertTrue(connection.isClosed());
    assertTrue(driver.aborted.get());
    assertEquals(0, driver.workerEnded.getCount());
    assertEquals(0, driver.cancellerEnded.getCount());
  }

  @Test
  void anAbortThatWaitsForTheServerStillEndsTheCallAndTheSocket() throws Exception {
    var hung = new CountDownLatch(1);
    // As MariaDB Connector/J's while a statement holds its lock: a KILL on a connection of its own,
    // which waits for the server, before it closes the socket.
    var driver = new Driver() {
      @Override
      void abort(Executor executor) {
        aborted.set(true);
        try {
          hung.await();
        } catch (InterruptedException e) {
          // Ends the test's thread.
        }
        socket.close();
      }
    };
    var connection = driver.connection();
    try {
      var failure = interrupted(() -> run(connection, Provider.MARIADB), driver.executing);
      assertEquals(Failure.ABORTED, failure.failure());
      assertTrue(connection.isClosed());
      assertEquals(0, driver.workerEnded.getCount());
    } finally {
      hung.countDown();
    }
  }

  // A cancel that returns at once, and a statement that ends well a moment later, before the cancel
  // has reached the server, or after it found the statement over.
  private static class Late extends Driver {
    @Override
    void cancel() throws SQLException {
      new Thread(() -> {
            sleep(100);
            socket.answer();
          })
          .start();
    }
  }

  @Test
  void onPostgresqlACancelThatTheStatementDidNotReportGivesTheConnectionUp() throws Exception {
    var driver = new Late();
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, Provider.POSTGRESQL), driver.executing);
    assertEquals(Failure.ABORTED, failure.failure());
    assertTrue(connection.isClosed());
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void onMariadbAndMysqlACancelThatReturnedKeepsTheConnection(Provider provider) throws Exception {
    var driver = new Late();
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, provider), driver.executing);
    assertEquals(Failure.CANCELLED, failure.failure());
    assertFalse(connection.isClosed());
    assertFalse(driver.aborted.get());
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void onMariadbAndMysqlACancelThatFailedGivesTheConnectionUp(Provider provider) throws Exception {
    var driver = new Late() {
      @Override
      void cancel() throws SQLException {
        super.cancel();
        throw new SQLException("Communications link failure", "08S01");
      }
    };
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, provider), driver.executing);
    assertEquals(Failure.ABORTED, failure.failure());
    assertTrue(connection.isClosed());
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aStatementThatReportsTheCancelKeepsTheConnection(Provider provider) throws Exception {
    var driver = new Driver() {
      @Override
      Object execute() throws SQLException {
        socket.read();
        throw new SQLException("canceling statement due to user request", "57014");
      }

      // As pgjdbc's, which does not say whether it arrived.
      @Override
      void cancel() throws SQLException {
        socket.answer();
        throw new SQLException("Unknown", "08006");
      }
    };
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, provider), driver.executing);
    assertEquals(Failure.CANCELLED, failure.failure());
    assertFalse(connection.isClosed());
  }

  @Test
  void anInterruptAsTheResultArrivesStillCancels() throws Exception {
    // A cancel that finds the statement over, which an interrupt just before the result sends.
    var driver = new Driver() {
      @Override
      void cancel() {}
    };
    var connection = driver.connection();
    for (var i = 0; i < 300; i++) {
      var caller = Thread.currentThread();
      var e = assertThrows(
          QueryException.class,
          () -> Query.run(connection, Provider.MYSQL, "SELECT 1", statement -> {
            caller.interrupt();
            return "value";
          }));
      assertTrue(Thread.interrupted());
      assertEquals(Failure.CANCELLED, e.failure());
    }
    assertFalse(connection.isClosed());
  }

  @Test
  void theResultComesOnceTheStatementIsClosedAndAFailedCloseCounts() throws Exception {
    var closed = new AtomicBoolean();
    var driver = new Driver() {
      @Override
      void closeStatement() {
        sleep(100);
        closed.set(true);
      }
    };
    assertEquals(
        "value", Query.run(driver.connection(), Provider.MYSQL, "SELECT 1", statement -> "value"));
    assertTrue(closed.get());

    var failing = new Driver() {
      @Override
      void closeStatement() throws SQLException {
        throw new SQLException("Close failed", "08006");
      }
    };
    var e = assertThrows(
        QueryException.class,
        () -> Query.run(failing.connection(), Provider.MYSQL, "SELECT 1", statement -> "value"));
    assertEquals(Failure.CONNECTION_LOST, e.failure());
  }

  @Test
  void aStatementBeingPreparedWhenTheCancelComesDoesNotRun() throws Exception {
    var preparing = new CountDownLatch(1);
    var driver = new Driver() {
      // A prepare on the server, which waits for its answer, given 100 ms after the interrupt.
      @Override
      void prepare() throws SQLException {
        preparing.countDown();
        socket.read();
      }
    };
    var connection = driver.connection();
    var failure = interrupted(
        () -> run(connection, Provider.MYSQL),
        preparing,
        () -> new Thread(() -> {
              sleep(100);
              driver.socket.answer();
            })
            .start());
    assertEquals(Failure.CANCELLED, failure.failure());
    assertEquals(0, driver.executions.get());
    assertFalse(connection.isClosed());
  }

  @Test
  void aPrepareThatDoesNotReturnGivesTheConnectionUpOnAPlatformCaller() throws Exception {
    var preparing = new CountDownLatch(1);
    var driver = new Driver() {
      @Override
      void prepare() throws SQLException {
        preparing.countDown();
        socket.read();
      }
    };
    var connection = driver.connection();
    var failure = interrupted(() -> run(connection, Provider.MYSQL), preparing);
    assertEquals(Failure.ABORTED, failure.failure());
    assertTrue(connection.isClosed());
    assertEquals(0, driver.executions.get());
  }

  private static QueryException interrupted(Call call, CountDownLatch started) throws Exception {
    return interrupted(call, started, () -> {});
  }

  // The failure of the call on a platform thread of its own, which is interrupted once the driver
  // has started; the thread is left interrupted, and the call ends in time.
  private static QueryException interrupted(Call call, CountDownLatch started, Runnable after)
      throws Exception {
    var failure = new QueryException[1];
    var interrupted = new boolean[1];
    var caller = Thread.ofPlatform().start(() -> {
      failure[0] = assertThrows(QueryException.class, call::call);
      interrupted[0] = Thread.currentThread().isInterrupted();
    });
    started.await();
    caller.interrupt();
    after.run();
    caller.join(GIVING_UP);
    assertFalse(caller.isAlive(), "The call did not end in time");
    assertNotNull(failure[0]);
    assertTrue(interrupted[0]);
    return failure[0];
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private interface Answer {
    Object answer(String method, Object[] arguments) throws SQLException;
  }

  private interface NamedAnswer {
    Object answer(String method) throws SQLException;
  }

  private static <T> T fake(Class<T> type, NamedAnswer answer) {
    return fake(type, (method, arguments) -> answer.answer(method));
  }

  // Answers each call by its method's name, and false for a boolean that it has no answer for.
  private static <T> T fake(Class<T> type, Answer answer) {
    return type.cast(Proxy.newProxyInstance(
        type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
          var result = answer.answer(method.getName(), arguments);
          return result == null && method.getReturnType() == boolean.class ? false : result;
        }));
  }

  @Test
  void aFailureCanBeMadeFromItsParts() {
    var failure = QueryException.of(Failure.ABORTED, "given up");
    assertEquals(Failure.ABORTED, failure.failure());
    assertEquals("given up", failure.detail());
    assertEquals("ABORTED: given up", failure.getMessage());
    assertEquals(
        "COLUMNS_CHANGED", QueryException.of(Failure.COLUMNS_CHANGED, "").getMessage());
  }
}

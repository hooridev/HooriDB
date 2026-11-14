package dev.hoori.db.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException.Failure;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The worker with the real drivers, through a relay that holds up what goes to the server. */
class QueryServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCancelThatArrivesLateNeverStopsTheNextStatement(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var relay = new Relay(server.host(), server.port());
        var connection = open(server, relay)) {
      // The cancel's own connection arrives half a second late, after the statement has ended well.
      relay.delay(Duration.ofMillis(500));
      var failure = interrupted(() -> sleep(connection, provider, 0.3), 100);
      if (provider == Provider.POSTGRESQL) {
        // pgjdbc does not say whether its cancel arrived, and the statement did not report it.
        assertEquals(Failure.ABORTED, failure.failure());
        assertTrue(connection.isClosed());
      } else {
        // The drivers' cancel waits for the server's answer to its KILL QUERY, which found the
        // connection idle.
        assertEquals(Failure.CANCELLED, failure.failure());
        assertEquals("0", sleep(connection, provider, 0.5));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aServerThatNoLongerAnswersIsGivenUpInTime(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var relay = new Relay(server.host(), server.port());
        var connection = open(server, relay)) {
      relay.freeze();
      var ended = new AtomicBoolean();
      var failure = interrupted(
          () -> {
            try {
              return sleep(connection, provider, 0);
            } finally {
              ended.set(true);
            }
          },
          100);
      assertEquals(Failure.ABORTED, failure.failure());
      assertTrue(connection.isClosed());
      // The worker is done with the connection by the time the call returned.
      assertTrue(ended.get());
    }
  }

  private static String sleep(Connection connection, Provider provider, double seconds)
      throws QueryException {
    var sql = provider == Provider.POSTGRESQL
        ? "SELECT pg_sleep(" + seconds + ")::text"
        : "SELECT SLEEP(" + seconds + ")";
    return Query.query(connection, provider, sql, rows -> {
      rows.next();
      return rows.getString(1);
    });
  }

  private static Connection open(TestServer server, Relay relay) throws Exception {
    return Connector.open(
        server.profile().withHost(Relay.HOST).withPort(relay.port()),
        TestServer.password(),
        server.driver(),
        TIMEOUT);
  }

  private interface Call {
    Object call() throws Exception;
  }

  // The failure of the call on a platform thread of its own, interrupted after the given time;
  // it leaves the thread interrupted, and ends within the time a connection takes to be given up.
  private static QueryException interrupted(Call call, long millis) throws Exception {
    var failure = new QueryException[1];
    var interrupted = new boolean[1];
    var caller = Thread.ofPlatform().start(() -> {
      failure[0] = assertThrows(QueryException.class, call::call);
      interrupted[0] = Thread.currentThread().isInterrupted();
    });
    Thread.sleep(millis);
    var started = System.nanoTime();
    caller.interrupt();
    caller.join(Query.GRACE.multipliedBy(2).plusSeconds(1));
    assertFalse(caller.isAlive(), "The call did not end in time");
    assertTrue(Duration.ofNanos(System.nanoTime() - started)
        .minus(Query.GRACE.multipliedBy(2))
        .isNegative());
    assertNotNull(failure[0]);
    assertTrue(interrupted[0]);
    return failure[0];
  }
}

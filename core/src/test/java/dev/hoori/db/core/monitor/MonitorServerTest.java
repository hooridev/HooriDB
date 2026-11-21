package dev.hoori.db.core.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The monitor of real servers of SQL, with a second session that sleeps. */
class MonitorServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theSessionsHoldTheMonitorsOwnAndAnotherWithWhatItRuns(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var mark = "m" + UUID.randomUUID().toString().replace("-", "");
    try (var monitor = open(server);
        var other = open(server)) {
      var sleeping = sleep(other, provider, mark, 30);
      try {
        var sessions = new SqlMonitor(monitor, provider).sessions();
        var own = sessions.stream().filter(ServerSession::own).toList();
        assertEquals(1, own.size(), sessions.toString());
        assertFalse(own.getFirst().cancellable());
        assertFalse(own.getFirst().ends());
        assertEquals(TestServer.USER, own.getFirst().user());
        var found = waitFor(new SqlMonitor(monitor, provider), mark);
        assertEquals(TestServer.USER, found.user());
        assertEquals(TestServer.DATABASE, found.database());
        assertFalse(found.client().isEmpty(), found.toString());
        assertTrue(found.cancellable(), found.toString());
        assertTrue(found.ends());
        // PostgreSQL names the statement by its start, the others by the thread's id.
        assertEquals(
            provider == Provider.POSTGRESQL,
            !found.operation().equals(found.id()),
            found.toString());
        assertEquals(provider == Provider.POSTGRESQL, !found.started().isEmpty(), found.toString());
        assertNotNull(found.elapsed());
        // Its credential is masked; the rest of its statement shows.
        assertFalse(found.statement().contains("Pw-secret"), found.statement());
        assertTrue(found.statement().contains(Credentials.MASK), found.statement());
        assertFalse(found.state().isEmpty());
        // The ids are in order.
        var ids = sessions.stream().map(s -> Long.parseLong(s.id())).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
      } finally {
        sleeping.cancel(true);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anIdleSessionRunsNothingToCancel(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var monitor = open(server);
        var idle = open(server)) {
      long id = id(idle, provider);
      var sessions = new SqlMonitor(monitor, provider).sessions();
      var found = sessions.stream()
          .filter(s -> s.id().equals(String.valueOf(id)))
          .findFirst()
          .orElseThrow(() -> new AssertionError(sessions.toString()));
      assertFalse(found.own());
      assertFalse(found.cancellable(), found.toString());
      assertTrue(found.ends());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelStopsTheStatementAndLeavesTheSession(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var mark = "m" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = open(server);
        var other = open(server)) {
      var monitor = new SqlMonitor(connection, provider);
      var sleeping = sleep(other, provider, mark, 30);
      try {
        var found = waitFor(monitor, mark);
        assertTrue(monitor.cancel(found));
        // MariaDB and MySQL end SLEEP early with 1 and no error, PostgreSQL with a cancel.
        sleeping.handle((done, failed) -> null).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertFalse(other.isClosed());
        assertEquals(id(other, provider) + "", found.id());
      } finally {
        sleeping.cancel(true);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anEndClosesTheSessionAndOneThatIsGoneIsNotFound(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server);
        var other = open(server)) {
      var monitor = new SqlMonitor(connection, provider);
      var id = String.valueOf(id(other, provider));
      var found =
          monitor.sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
      assertTrue(monitor.end(found));
      long until = System.nanoTime() + TIMEOUT.toNanos();
      while (monitor.sessions().stream().anyMatch(s -> s.id().equals(id))) {
        assertTrue(System.nanoTime() < until, "Session " + id + " did not end");
        Thread.sleep(50);
      }
      assertFalse(monitor.end(found));
      assertThrows(QueryException.class, () -> id(other, provider));
    }
  }

  @Test
  void postgreSqlActsOnlyOnTheSessionAndStatementAsRead() throws Exception {
    var provider = Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var mark = "m" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = open(server);
        var other = open(server)) {
      var monitor = new SqlMonitor(connection, provider);
      var sleeping = sleep(other, provider, mark, 30);
      try {
        var found = waitFor(monitor, mark);
        // Another statement since, and another session of the same id.
        assertFalse(monitor.cancel(with(found, found.started(), "2000-01-01 00:00:00+00")));
        assertFalse(monitor.end(with(found, "2000-01-01 00:00:00+00", found.operation())));
        assertFalse(other.isClosed());
        assertFalse(sleeping.isDone());
        assertTrue(monitor.cancel(found));
        sleeping.handle((done, failed) -> null).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        // Idle now, its statement's start stays as it was: no statement to cancel.
        long until = System.nanoTime() + TIMEOUT.toNanos();
        while (!monitor.sessions().stream()
            .anyMatch(s -> s.id().equals(found.id()) && s.state().equals("idle"))) {
          assertTrue(System.nanoTime() < until, "Session " + found.id() + " did not go idle");
          Thread.sleep(50);
        }
        assertFalse(monitor.cancel(found));
      } finally {
        sleeping.cancel(true);
      }
    }
  }

  private static ServerSession with(ServerSession s, String started, String operation) {
    return new ServerSession(
        s.id(),
        operation,
        started,
        s.own(),
        s.endable(),
        s.user(),
        s.client(),
        s.database(),
        s.state(),
        s.statement(),
        s.elapsed(),
        s.waiting());
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theOwnSessionIsNeitherCancelledNorEnded(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server)) {
      var monitor = new SqlMonitor(connection, provider);
      var own =
          monitor.sessions().stream().filter(ServerSession::own).findFirst().orElseThrow();
      assertThrows(IllegalArgumentException.class, () -> monitor.end(own));
      assertThrows(IllegalArgumentException.class, () -> monitor.cancel(own));
    }
  }

  /** The session whose statement holds the mark, read again until it shows. */
  static ServerSession waitFor(Monitor monitor, String mark) throws Exception {
    long until = System.nanoTime() + TIMEOUT.toNanos();
    while (true) {
      List<ServerSession> sessions = monitor.sessions();
      for (var s : sessions) {
        if (!s.own() && s.statement().contains(mark) && s.cancellable()) {
          return s;
        }
      }
      if (System.nanoTime() > until) {
        throw new AssertionError("No session runs " + mark + ": " + sessions);
      }
      Thread.sleep(50);
    }
  }

  /**
   * Sleeps on the connection, in a statement of the mark that holds a credential, until done or
   * cancelled by the future's cancel, which interrupts it.
   */
  static CompletableFuture<Void> sleep(
      Connection connection, Provider provider, String mark, int seconds) {
    var sql = provider == Provider.POSTGRESQL
        ? "SELECT pg_sleep(" + seconds + ")::text, 'password=Pw-secret' AS " + mark
        : "SELECT SLEEP(" + seconds + "), 'password=Pw-secret' AS " + mark;
    var thread = new Thread[1];
    var done = new CompletableFuture<Void>() {
      @Override
      public boolean cancel(boolean interrupt) {
        thread[0].interrupt();
        try {
          thread[0].join(TIMEOUT);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return super.cancel(interrupt);
      }
    };
    thread[0] = Thread.ofVirtual().start(() -> {
      try {
        Query.query(connection, provider, sql, rows -> rows.next());
        done.complete(null);
      } catch (QueryException e) {
        done.completeExceptionally(e);
      }
    });
    return done;
  }

  static long id(Connection connection, Provider provider) throws QueryException {
    var sql =
        provider == Provider.POSTGRESQL ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()";
    return Query.query(connection, provider, sql, rows -> {
      rows.next();
      return rows.getLong(1);
    });
  }

  static Connection open(TestServer server) throws Exception {
    return Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
  }
}

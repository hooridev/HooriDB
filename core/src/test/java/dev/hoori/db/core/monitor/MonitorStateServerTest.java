package dev.hoori.db.core.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The variables, status and locks of real servers. */
class MonitorStateServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theVariablesHaveTheirValuesAndSources(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = MonitorServerTest.open(server)) {
      var variables = new SqlMonitor(connection, provider).variables();
      var name = "max_connections";
      var found = variables.stream()
          .filter(v -> v.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new AssertionError(variables.size() + " variables"));
      assertTrue(Integer.parseInt(found.value()) > 0, found.toString());
      // Every server tells where it comes from, MySQL from performance_schema.
      assertFalse(found.source().isEmpty(), found.toString());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theStatusCountsWhatTheServerDid(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = MonitorServerTest.open(server)) {
      var status = new SqlMonitor(connection, provider).status();
      var name = provider == Provider.POSTGRESQL ? "commits" : "Questions";
      var found = status.stream()
          .filter(c -> c.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new AssertionError(status.toString()));
      assertTrue(Long.parseLong(found.value()) > 0, found.toString());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSessionThatWaitsForARowLockShowsBesideTheOneThatHoldsIt(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    try (var monitor = privileged(server);
        var holder = MonitorServerTest.open(server);
        var waiter = MonitorServerTest.open(server)) {
      holder.setAutoCommit(false);
      var update = "UPDATE customer SET name = name WHERE id = 1";
      try (var statement = holder.createStatement()) {
        statement.executeUpdate(update);
      }
      var waiting = CompletableFuture.runAsync(() -> {
        try (var statement = waiter.createStatement()) {
          statement.executeUpdate(update);
        } catch (Exception e) {
          // Rolled back, or the connection closed.
        }
      });
      try {
        var holding = String.valueOf(MonitorServerTest.id(holder, provider));
        var reader = new SqlMonitor(monitor, provider);
        long until = System.nanoTime() + TIMEOUT.toNanos();
        List<Block> blocks;
        do {
          Thread.sleep(50);
          blocks = reader.locks().orElseThrow();
        } while (blocks.isEmpty() && System.nanoTime() < until);
        assertEquals(1, blocks.size(), blocks.toString());
        var block = blocks.getFirst();
        assertEquals(holding, block.blocking());
        assertFalse(block.waiting().equals(holding));
        // PostgreSQL's row lock is a wait for the transaction that holds the row.
        var on = provider == Provider.POSTGRESQL ? "transactionid" : "customer";
        assertTrue(block.object().contains(on), block.toString());
        assertFalse(block.mode().isEmpty(), block.toString());
      } finally {
        holder.rollback();
        waiting.get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    }
  }

  @Test
  void mongoDbTellsVariablesAndStatusButNoLocks() throws Exception {
    var server = TestServer.of(Provider.MONGODB);
    try (var session = DocumentMonitorServerTest.session(server.profile(), true)) {
      var monitor = new DocumentMonitor(session);
      var variables = monitor.variables();
      assertTrue(
          variables.stream().anyMatch(v -> v.name().equals("featureCompatibilityVersion")),
          variables.toString());
      var status = monitor.status();
      assertTrue(
          status.stream().anyMatch(c -> c.name().equals("connections.current")), status.toString());
      assertTrue(
          status.stream().anyMatch(c -> c.name().equals("opcounters.query")), status.toString());
      assertTrue(monitor.locks().isEmpty());
    }
  }

  // Who blocks whom takes the PROCESS privilege on MariaDB, and on MySQL the reading of
  // performance_schema, which the server's root has.
  private static Connection privileged(TestServer server) throws Exception {
    if (server.provider() == Provider.POSTGRESQL) {
      return MonitorServerTest.open(server);
    }
    return Connector.open(
            server.profile().withUser("root"),
            ("root-" + TestServer.PASSWORD).toCharArray(),
            server.driver(),
            TIMEOUT)
        .connection();
  }
}

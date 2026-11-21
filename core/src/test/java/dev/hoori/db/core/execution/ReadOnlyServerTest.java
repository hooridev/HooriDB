package dev.hoori.db.core.execution;

import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.failure;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A read-only connection on real servers: the server refuses what writes, in Auto and in Manual
 * and on another database, and a statement that would turn read-only off is not sent.
 */
class ReadOnlyServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static Connection readOnly(Provider provider, String database) throws Exception {
    var server = TestServer.of(provider);
    var profile = server.profile().withDatabase(database).withReadOnly(true);
    return Connector.open(profile, TestServer.password(), server.driver(), TIMEOUT)
        .connection();
  }

  private static Portion run(Connection connection, Provider provider, String sql) {
    try (var execution = new Execution(connection, provider, sql, true)) {
      return execution.next(100);
    }
  }

  // A table of its own, empty, made on a connection that writes.
  private static void table(Provider provider, String name) throws Exception {
    try (var writer = ExecutionServerTest.open(provider)) {
      execute(writer, "DROP TABLE IF EXISTS " + name, "CREATE TABLE " + name + " (id integer)");
    }
  }

  private static long rows(Provider provider, String table) throws Exception {
    try (var writer = ExecutionServerTest.open(provider)) {
      return selectLong(writer, "SELECT count(*) FROM " + table);
    }
  }

  private static void refused(Connection connection, Provider provider, String sql) {
    var failure = failure(run(connection, provider, sql).results());
    // The server's own: a read-only transaction's, not the driver's or HooriDB's.
    assertEquals("25006", failure.state(), failure.toString());
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theServerRefusesWritesInAutoAndManual(Provider provider) throws Exception {
    table(provider, "kept_as_is");
    try (var connection = readOnly(provider, TestServer.DATABASE)) {
      refused(connection, provider, "INSERT INTO kept_as_is VALUES (1)");
      refused(connection, provider, "CREATE TABLE made_anyway (id integer)");
      refused(connection, provider, "DROP TABLE kept_as_is");
      connection.setAutoCommit(false);
      refused(connection, provider, "INSERT INTO kept_as_is VALUES (2)");
      connection.rollback();
      connection.setAutoCommit(true);
      assertFalse(run(connection, provider, "SELECT 1").results().getLast() instanceof Failure);
    }
    assertEquals(0, rows(provider, "kept_as_is"));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aStatementThatTurnsItOffIsNotSent(Provider provider) throws Exception {
    table(provider, "still_empty");
    var off = switch (provider) {
      case POSTGRESQL ->
        List.of("SET default_transaction_read_only = off", "RESET ALL", "BEGIN READ WRITE");
      case MARIADB ->
        List.of(
            "SET SESSION TRANSACTION READ WRITE",
            "SET @@session.transaction_read_only = 0",
            "CREATE TABLE made_anyway (id integer) START TRANSACTION",
            "RESET CONNECTION");
      case MYSQL ->
        List.of(
            "SET SESSION TRANSACTION READ WRITE",
            "SET @@session.transaction_read_only = 0",
            "CREATE TABLE made_anyway (id integer) START TRANSACTION");
      case MONGODB -> throw new IllegalArgumentException("MongoDB does not speak SQL");
    };
    try (var connection = readOnly(provider, TestServer.DATABASE)) {
      for (var sql : off) {
        var failure = failure(run(connection, provider, sql).results());
        assertEquals(Failure.Kind.ACCESS_DENIED, failure.kind(), sql);
        assertTrue(failure.message().startsWith("The connection is read-only"), failure.message());
      }
      refused(connection, provider, "INSERT INTO still_empty VALUES (1)");
      // Read as it is.
      var show = provider == Provider.POSTGRESQL
          ? "SHOW transaction_read_only"
          : "SHOW VARIABLES LIKE 'transaction_read_only'";
      assertFalse(run(connection, provider, show).results().getLast() instanceof Failure);
    }
    assertEquals(0, rows(provider, "still_empty"));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anotherDatabaseIsReadOnlyToo(Provider provider) throws Exception {
    if (provider.supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)) {
      try (var connection = readOnly(provider, TestServer.SPACED_DATABASE)) {
        refused(connection, provider, "CREATE TABLE made_there (id integer)");
      }
      return;
    }
    try (var connection = readOnly(provider, TestServer.DATABASE)) {
      connection.setCatalog(TestServer.SPACED_DATABASE);
      refused(connection, provider, "CREATE TABLE made_there (id integer)");
      var used = run(connection, provider, "USE `" + TestServer.DATABASE + "`");
      assertFalse(used.results().getLast() instanceof Failure, used.toString());
      // Asked of the server: MySQL Connector/J does not follow a USE in getCatalog.
      var on =
          (Result.Rows) run(connection, provider, "SELECT DATABASE()").results().getFirst();
      assertEquals(List.of(TestServer.DATABASE), on.rows().getFirst());
      refused(connection, provider, "CREATE TABLE made_there (id integer)");
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void thePlannerStillEstimates(Provider provider) throws Exception {
    table(provider, "estimated");
    try (var connection = readOnly(provider, TestServer.DATABASE)) {
      assertTrue(Plan.rows(connection, provider, TestServer.DATABASE, "SELECT * FROM estimated")
          .isPresent());
    }
  }
}

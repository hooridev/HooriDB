package dev.hoori.db.core.connect;

import static dev.hoori.db.core.connect.ConnectorTest.WRONG_PASSWORD;
import static dev.hoori.db.core.connect.ConnectorTest.assertFailure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.connection.Tls;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Connecting to real servers, and how they refuse. */
class ConnectorServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  // What the images of TestServer run.
  private static final Map<Provider, String> PRODUCTS = Map.of(
      Provider.POSTGRESQL, "PostgreSQL", Provider.MARIADB, "MariaDB", Provider.MYSQL, "MySQL");
  // What a driver sends as it closes a connection: PostgreSQL's Terminate, MySQL's COM_QUIT.
  private static final Map<Provider, byte[]> GOODBYES = Map.of(
      Provider.POSTGRESQL, new byte[] {'X', 0, 0, 0, 4},
      Provider.MARIADB, new byte[] {1, 0, 0, 0, 1},
      Provider.MYSQL, new byte[] {1, 0, 0, 0, 1});
  private static final Map<Provider, Integer> MAJORS =
      Map.of(Provider.POSTGRESQL, 18, Provider.MARIADB, 12, Provider.MYSQL, 9);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTestReadsWhatTheServerIs(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var found = Connector.test(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    assertEquals(PRODUCTS.get(provider), found.product());
    // Such as "18.7 (Debian 18.7-1.pgdg13+2)", "12.3.3-MariaDB-ubu2404" and "9.7.4".
    var version = found.parsed();
    assertEquals(MAJORS.get(provider), version.major(), found.version());
    assertTrue(
        found.version().startsWith(version.major() + "." + version.minor()), found.version());
    assertEquals(version, ServerVersion.parse(selectVersion(server)));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anOpenConnectionWorksWithoutTimeUntilClosed(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
    try {
      assertEquals(0, connection.getNetworkTimeout());
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT count(*) FROM reading")) {
        assertTrue(rows.next());
        assertEquals(5000, rows.getInt(1));
      }
    } finally {
      Connector.close(connection);
    }
    assertTrue(connection.isClosed());
    Connector.close(connection);
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void namesWithSpacesAndBeyondAsciiReachTheirDatabase(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    for (var name : new String[] {TestServer.SPACED_DATABASE, TestServer.NON_ASCII_DATABASE}) {
      var opened = Connector.open(
          server.profile().withDatabase(name), TestServer.password(), server.driver(), TIMEOUT);
      var connection = opened.connection();
      try {
        assertEquals(name, currentDatabase(provider, connection));
        // What opening read: the database, and the server as a test of it finds it.
        assertEquals(name, opened.database());
        assertEquals(PRODUCTS.get(provider), opened.server().product());
        assertEquals(MAJORS.get(provider), opened.server().parsed().major());
      } finally {
        Connector.close(connection);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aWrongPasswordOrUserIsRefused(Provider provider) {
    var server = TestServer.of(provider);
    assertFailure(
        Failure.LOGIN_REFUSED,
        () -> Connector.test(
            server.profile(), WRONG_PASSWORD.toCharArray(), server.driver(), TIMEOUT));
    assertFailure(
        Failure.LOGIN_REFUSED,
        () -> Connector.test(
            server.profile().withUser("nobody"),
            WRONG_PASSWORD.toCharArray(),
            server.driver(),
            TIMEOUT));
    // pgjdbc refuses by itself when the server asks for a password and there is none.
    assertFailure(
        Failure.LOGIN_REFUSED,
        () -> Connector.test(server.profile(), new char[0], server.driver(), TIMEOUT));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aMissingDatabaseIsNamed(Provider provider) {
    var server = TestServer.of(provider);
    var e = assertFailure(
        Failure.NO_SUCH_DATABASE,
        () -> Connector.test(
            server.profile().withDatabase("missing"),
            TestServer.password(),
            server.driver(),
            TIMEOUT));
    assertTrue(e.detail().contains("missing"), e.detail());
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void tlsThatTheServerDoesNotOfferIsRefused(Provider provider) {
    var plain = plain(provider);
    for (var tls : new Tls[] {Tls.ENCRYPTED, Tls.VERIFIED}) {
      assertFailure(
          Failure.TLS_UNAVAILABLE,
          () -> Connector.test(
              plain.profile().withTls(tls), TestServer.password(), plain.driver(), TIMEOUT));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSelfSignedCertificateIsEncryptedButNotVerified(Provider provider) throws Exception {
    var encrypted = encrypted(provider);
    var profile = encrypted.profile().withTls(Tls.ENCRYPTED);
    var found = Connector.test(profile, TestServer.password(), encrypted.driver(), TIMEOUT);
    assertEquals(PRODUCTS.get(provider), found.product());
    assertFailure(
        Failure.CERTIFICATE_REJECTED,
        () -> Connector.test(
            profile.withTls(Tls.VERIFIED), TestServer.password(), encrypted.driver(), TIMEOUT));
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aConnectionThatIsThereForAnInterruptedCallerIsClosedProperly(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    try (var gate = new Gate(server.host(), server.port())) {
      gate.open();
      // Without TLS, so that the gate sees the driver's goodbye.
      var profile = server.profile().withHost(Gate.HOST).withPort(gate.port()).withTls(Tls.OFF);
      var opened = Connector.open(profile, TestServer.password(), server.driver(), TIMEOUT);
      var connection = opened.connection();
      Thread.currentThread().interrupt();
      try {
        var e = assertThrows(
            ConnectionException.class,
            () -> Connector.await(
                CompletableFuture.completedFuture(opened),
                Thread.ofVirtual().unstarted(() -> {}),
                TIMEOUT,
                thrown -> {
                  throw new AssertionError(thrown);
                }));
        assertEquals(Failure.CANCELLED, e.failure());
      } finally {
        Thread.interrupted();
      }
      assertTrue(connection.isClosed());
      assertTrue(gate.awaitEnd(TIMEOUT));
      var sent = gate.sent();
      var goodbye = GOODBYES.get(provider);
      assertArrayEquals(
          goodbye, Arrays.copyOfRange(sent, sent.length - goodbye.length, sent.length));
      assertEquals(0, otherSessions(server, false));
    }
  }

  private record Phase(String name, TestServer server, Tls tls, Gate.Relay relay, boolean in) {}

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anAbandonedAttemptEndsInEveryPhase(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    // A login over TLS fills MySQL's cache of passwords, which a login without TLS needs to end
    // without an exchange of keys.
    Connector.test(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    var postgres = provider == Provider.POSTGRESQL;
    var phases = List.of(
        new Phase("before the first answer", server, server.profile().tls(), Gate.NONE, false),
        new Phase(
            "TLS handshake",
            encrypted(provider),
            Tls.ENCRYPTED,
            postgres ? Gate.bytes(1) : Gate.messages(provider, 1),
            false),
        new Phase("authentication", server, Tls.OFF, Gate.messages(provider, 1), !postgres),
        new Phase("logged in", server, Tls.OFF, Gate.messages(provider, 2), postgres));
    for (var phase : phases) {
      if (!postgres && phase.name().equals("logged in")) {
        // The server's answer to the login itself is what the gate held in the phase before.
        continue;
      }
      try (var gate = new Gate(phase.server().host(), phase.server().port(), phase.relay())) {
        gate.open();
        var profile =
            phase.server().profile().withHost(Gate.HOST).withPort(gate.port()).withTls(phase.tls());
        var attempt = Attempt.start(profile, TIMEOUT);
        assertTrue(gate.awaitClient());
        if (phase.in()) {
          assertTrue(otherSessions(phase.server(), true) > 0, phase.name());
        } else {
          Thread.sleep(300);
        }
        var released = attempt.cancel();
        assertEquals(Failure.CANCELLED, attempt.failure().failure(), phase.name());
        assertTrue(released.toMillis() < 500, phase.name());
        assertEnded(provider, gate, phase.name());
        assertEquals(0, otherSessions(phase.server(), false), phase.name());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aServerThatTricklesIsLeftWithinTheTimeLimit(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var gate = new Gate(server.host(), server.port(), Gate.trickle(Duration.ofMillis(250)))) {
      gate.open();
      var profile = server.profile().withHost(Gate.HOST).withPort(gate.port());
      var attempt = Attempt.start(profile, Duration.ofMillis(600));
      var released = attempt.end();
      assertEquals(Failure.TIMED_OUT, attempt.failure().failure());
      assertTrue(released.toMillis() < 2500, released::toString);
      assertEnded(provider, gate, "trickle");
      assertEquals(0, otherSessions(server, false));
    }
  }

  // The worker of the attempt has ended, and its socket is closed.
  private static void assertEnded(Provider provider, Gate gate, String phase) throws Exception {
    var worker = Attempt.workersEnd(provider, Duration.ofSeconds(2));
    var socket = gate.awaitEnd(Duration.ofSeconds(2));
    assertNotNull(worker, phase);
    assertTrue(socket, phase);
  }

  private static TestServer plain(Provider provider) {
    var server = TestServer.of(provider);
    return server.tls() ? TestServer.otherTls(provider) : server;
  }

  private static TestServer encrypted(Provider provider) {
    var server = TestServer.of(provider);
    return server.tls() ? server : TestServer.otherTls(provider);
  }

  private static String selectVersion(TestServer server) throws Exception {
    var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(
            server.provider() == Provider.POSTGRESQL
                ? "SHOW server_version"
                : "SELECT VERSION()")) {
      rows.next();
      return rows.getString(1);
    } finally {
      Connector.close(connection);
    }
  }

  private static String currentDatabase(Provider provider, Connection connection)
      throws SQLException {
    var query = provider == Provider.POSTGRESQL ? "SELECT current_database()" : "SELECT DATABASE()";
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getString(1);
    }
  }

  // The sessions of the test user other than the one that asks, once there are some, or once the
  // server has seen the others end.
  private static int otherSessions(TestServer server, boolean some) throws Exception {
    var query = server.provider() == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity"
            + " WHERE usename = current_user AND backend_type = 'client backend'"
            + " AND pid <> pg_backend_pid()"
        : "SELECT count(*) FROM information_schema.processlist" + " WHERE user = '"
            + TestServer.USER + "' AND id <> CONNECTION_ID()";
    var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
    try (var statement = connection.createStatement()) {
      var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (true) {
        try (var rows = statement.executeQuery(query)) {
          rows.next();
          var count = rows.getInt(1);
          if ((count > 0) == some || System.nanoTime() > deadline) {
            return count;
          }
        }
        Thread.sleep(50);
      }
    } finally {
      Connector.close(connection);
    }
  }
}

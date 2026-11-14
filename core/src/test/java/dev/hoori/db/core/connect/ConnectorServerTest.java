package dev.hoori.db.core.connect;

import static dev.hoori.db.core.connect.ConnectorTest.WRONG_PASSWORD;
import static dev.hoori.db.core.connect.ConnectorTest.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Connecting to real servers, and how they refuse. */
class ConnectorServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  // What the images of TestServer run.
  private static final Map<Provider, String> PRODUCTS = Map.of(
      Provider.POSTGRESQL, "PostgreSQL", Provider.MARIADB, "MariaDB", Provider.MYSQL, "MySQL");
  private static final Map<Provider, Integer> MAJORS =
      Map.of(Provider.POSTGRESQL, 18, Provider.MARIADB, 12, Provider.MYSQL, 9);

  @ParameterizedTest
  @EnumSource(Provider.class)
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
  @EnumSource(Provider.class)
  void anOpenConnectionWorksWithoutTimeUntilClosed(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var connection =
        Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
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
  @EnumSource(Provider.class)
  void namesWithSpacesAndBeyondAsciiReachTheirDatabase(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    for (var name : new String[] {TestServer.SPACED_DATABASE, TestServer.NON_ASCII_DATABASE}) {
      var connection = Connector.open(
          server.profile().withDatabase(name), TestServer.password(), server.driver(), TIMEOUT);
      try {
        assertEquals(name, currentDatabase(provider, connection));
      } finally {
        Connector.close(connection);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
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
  @EnumSource(Provider.class)
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
  @EnumSource(Provider.class)
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
  @EnumSource(Provider.class)
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
  @EnumSource(Provider.class)
  void aConnectionThatArrivesAfterACancelIsClosed(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var gate = new Gate(server.host(), server.port())) {
      var profile = server.profile().withHost("127.0.0.1").withPort(gate.port());
      var failure = new ConnectionException[1];
      var caller = Thread.ofPlatform()
          .start(() -> failure[0] = assertThrows(
              ConnectionException.class,
              () -> Connector.open(profile, TestServer.password(), server.driver(), TIMEOUT)));
      gate.awaitClient();
      caller.interrupt();
      caller.join(Duration.ofSeconds(1));
      assertFalse(caller.isAlive());
      assertEquals(Failure.CANCELLED, failure[0].failure());

      // The driver logs in only now, and its connection is closed as soon as it arrives.
      gate.open();
      assertTrue(gate.awaitPassed(TIMEOUT));
      assertEquals(0, otherSessions(server));
    }
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
    var connection =
        Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
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

  // The sessions of the test user other than the one that asks, once the server has seen the
  // others end.
  private static int otherSessions(TestServer server) throws Exception {
    var query = server.provider() == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity"
            + " WHERE usename = current_user AND backend_type = 'client backend'"
            + " AND pid <> pg_backend_pid()"
        : "SELECT count(*) FROM information_schema.processlist" + " WHERE user = '"
            + TestServer.USER + "' AND id <> CONNECTION_ID()";
    var connection =
        Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
    try (var statement = connection.createStatement()) {
      var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (true) {
        try (var rows = statement.executeQuery(query)) {
          rows.next();
          var count = rows.getInt(1);
          if (count == 0 || System.nanoTime() > deadline) {
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

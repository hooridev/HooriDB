package dev.hoori.db.core.connection;

import static dev.hoori.db.core.connection.Capability.CONNECTION_PER_DATABASE;
import static dev.hoori.db.core.connection.Capability.ERROR_POSITION;
import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.connection.Capability.IDENTITY_OVERRIDE;
import static dev.hoori.db.core.connection.Capability.SCHEMAS;
import static dev.hoori.db.core.connection.Capability.TRANSACTIONAL_DDL;
import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderTest {

  private static ConnectionParameters parameters(
      Provider provider, String host, String database, Tls tls)
      throws ConnectionProfile.UnfitException {
    return provider.parameters(ConnectionProfile.of(provider)
        .withHost(host)
        .withDatabase(database)
        .withUser("someone")
        .withTls(tls));
  }

  private static void assertParameters(
      String url, Map<String, String> properties, ConnectionParameters parameters) {
    assertEquals(new ConnectionParameters(url, properties), parameters);
  }

  @Test
  void postgresql() throws Exception {
    assertParameters(
        "jdbc:postgresql://db.example:5432/shop",
        Map.of("sslmode", "disable"),
        parameters(POSTGRESQL, "db.example", "shop", Tls.OFF));
    assertParameters(
        "jdbc:postgresql://db.example:5432/shop",
        Map.of("sslmode", "require"),
        parameters(POSTGRESQL, "db.example", "shop", Tls.ENCRYPTED));
    assertParameters(
        "jdbc:postgresql://db.example:5432/shop",
        Map.of("sslmode", "verify-full", "sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory"),
        parameters(POSTGRESQL, "db.example", "shop", Tls.VERIFIED));
  }

  @Test
  void mariadb() throws Exception {
    assertParameters(
        "jdbc:mariadb://db.example:3306/",
        Map.of("sslMode", "disable", "database", "shop"),
        parameters(MARIADB, "db.example", "shop", Tls.OFF));
    assertParameters(
        "jdbc:mariadb://db.example:3306/",
        Map.of("sslMode", "trust", "database", "shop"),
        parameters(MARIADB, "db.example", "shop", Tls.ENCRYPTED));
    assertParameters(
        "jdbc:mariadb://db.example:3306/",
        Map.of("sslMode", "verify-full", "deferCertificateValidation", "false", "database", "shop"),
        parameters(MARIADB, "db.example", "shop", Tls.VERIFIED));
  }

  @Test
  void mysql() throws Exception {
    assertParameters(
        "jdbc:mysql://db.example:3306/",
        Map.of(
            "sslMode",
            "DISABLED",
            "allowPublicKeyRetrieval",
            "true",
            "dbname",
            "shop",
            "processEscapeCodesForPrepStmts",
            "false",
            "readOnlyPropagatesToServer",
            "false"),
        parameters(MYSQL, "db.example", "shop", Tls.OFF));
    assertParameters(
        "jdbc:mysql://db.example:3306/",
        Map.of(
            "sslMode",
            "REQUIRED",
            "dbname",
            "shop",
            "processEscapeCodesForPrepStmts",
            "false",
            "readOnlyPropagatesToServer",
            "false"),
        parameters(MYSQL, "db.example", "shop", Tls.ENCRYPTED));
    assertParameters(
        "jdbc:mysql://db.example:3306/",
        Map.of(
            "sslMode",
            "VERIFY_IDENTITY",
            "dbname",
            "shop",
            "processEscapeCodesForPrepStmts",
            "false",
            "readOnlyPropagatesToServer",
            "false"),
        parameters(MYSQL, "db.example", "shop", Tls.VERIFIED));
  }

  @Test
  void onlyMysqlWithoutTlsMayFetchTheServersKey() throws Exception {
    for (var provider : Provider.SQL) {
      for (var tls : Tls.values()) {
        var properties = parameters(provider, "h", "d", tls).properties();
        assertEquals(
            provider == MYSQL && tls == Tls.OFF,
            properties.containsKey("allowPublicKeyRetrieval"),
            provider + " " + tls);
      }
    }
  }

  @Test
  void theOtherFieldsGoAsTheyAre() throws Exception {
    var profile = ConnectionProfile.of(MYSQL)
        .withHost("127.0.0.1")
        .withPort(1)
        .withDatabase("d")
        .withTls(Tls.OFF);
    assertEquals("jdbc:mysql://127.0.0.1:1/", MYSQL.parameters(profile).url());
    assertEquals(
        "jdbc:postgresql://db.example.:65535/d",
        POSTGRESQL.parameters(profile.withHost("db.example.").withPort(65535)).url());
  }

  @Test
  void anEmptyDatabaseIsLeftOut() throws Exception {
    assertParameters(
        "jdbc:postgresql://h:5432/",
        Map.of("sslmode", "disable"),
        parameters(POSTGRESQL, "h", "", Tls.OFF));
    assertParameters(
        "jdbc:mariadb://h:3306/",
        Map.of("sslMode", "disable"),
        parameters(MARIADB, "h", "", Tls.OFF));
    assertParameters(
        "jdbc:mysql://h:3306/",
        Map.of(
            "sslMode",
            "DISABLED",
            "allowPublicKeyRetrieval",
            "true",
            "processEscapeCodesForPrepStmts",
            "false",
            "readOnlyPropagatesToServer",
            "false"),
        parameters(MYSQL, "h", "", Tls.OFF));
  }

  @ParameterizedTest
  @CsvSource(textBlock = """
      'a b',          a%20b
      'a/b',          a%2Fb
      '50%',          50%25
      ' lead',        %20lead
      'trail ',       trail%20
      'a?b+&#=;-._~', a%3Fb%2B%26%23%3D%3B-._~
      'Bäckerei 東京', B%C3%A4ckerei%20%E6%9D%B1%E4%BA%AC
      """)
  void databaseNamesArriveAsTheyAre(String database, String path) throws Exception {
    assertEquals(
        "jdbc:postgresql://h:5432/" + path,
        parameters(POSTGRESQL, "h", database, Tls.OFF).url());
    var mariadb = parameters(MARIADB, "h", database, Tls.OFF);
    assertEquals("jdbc:mariadb://h:3306/", mariadb.url());
    assertEquals(database, mariadb.properties().get("database"));
    var mysql = parameters(MYSQL, "h", database, Tls.OFF);
    assertEquals("jdbc:mysql://h:3306/", mysql.url());
    assertEquals(database, mysql.properties().get("dbname"));
  }

  @Test
  void ipv6HostsGoInBrackets() throws Exception {
    for (var host : List.of("::1", "[::1]")) {
      assertEquals(
          "jdbc:postgresql://[::1]:5432/d",
          parameters(POSTGRESQL, host, "d", Tls.OFF).url());
      assertEquals(
          "jdbc:mariadb://[::1]:3306/", parameters(MARIADB, host, "d", Tls.OFF).url());
      assertEquals(
          "jdbc:mysql://[::1]:3306/", parameters(MYSQL, host, "d", Tls.OFF).url());
    }
    assertEquals(
        "jdbc:mysql://[2001:db8::1]:3306/",
        parameters(MYSQL, "2001:db8::1", "d", Tls.OFF).url());
  }

  @Test
  void theZoneOfAnIpv6HostIsEncodedOnlyForMysql() throws Exception {
    for (var host : List.of("fe80::1%1", "[fe80::1%1]")) {
      assertEquals(
          "jdbc:postgresql://[fe80::1%1]:5432/d",
          parameters(POSTGRESQL, host, "d", Tls.OFF).url());
      assertEquals(
          "jdbc:mariadb://[fe80::1%1]:3306/",
          parameters(MARIADB, host, "d", Tls.OFF).url());
      assertEquals(
          "jdbc:mysql://[fe80::1%251]:3306/",
          parameters(MYSQL, host, "d", Tls.OFF).url());
    }
    assertEquals(
        "jdbc:mysql://[fe80::1%25eth0]:3306/",
        parameters(MYSQL, "fe80::1%eth0", "d", Tls.OFF).url());
  }

  @Test
  void parametersHaveNoUser() throws Exception {
    for (var provider : Provider.SQL) {
      for (var tls : Tls.values()) {
        var parameters = parameters(provider, "h", "d", tls);
        assertFalse(parameters.url().contains("someone"), parameters.toString());
        assertFalse(parameters.properties().containsValue("someone"), parameters.toString());
      }
    }
  }

  @Test
  void anUnfitProfileFailsWithItsProblems() {
    for (var provider : Provider.SQL) {
      var profile = ConnectionProfile.of(provider)
          .withHost("db.example/shop?sslmode=disable&ignored=")
          .withPort(65536)
          .withDatabase("a\0b")
          .withUser("c\0d");
      var failure =
          assertThrows(ConnectionProfile.UnfitException.class, () -> provider.parameters(profile));
      assertEquals(profile.problems(), failure.problems());
      assertEquals(4, failure.problems().size());
      assertFalse(failure.getMessage().contains("db.example"), failure.getMessage());
      assertThrows(
          ConnectionProfile.UnfitException.class,
          () -> provider.parameters(ConnectionProfile.of(provider)));
    }
  }

  @Test
  void parametersCannotBeChanged() {
    var properties = new HashMap<>(Map.of("a", "b"));
    var parameters = new ConnectionParameters("u", properties);
    properties.put("c", "d");
    assertEquals(Map.of("a", "b"), parameters.properties());
    var kept = parameters.properties();
    assertThrows(UnsupportedOperationException.class, () -> kept.put("x", "y"));
  }

  @Test
  void quote() {
    assertEquals("\"orders\"", Provider.POSTGRESQL.quote("orders"));
    assertEquals("\"a\"\"b`c\"", Provider.POSTGRESQL.quote("a\"b`c"));
    assertEquals("`a\"b``c`", Provider.MARIADB.quote("a\"b`c"));
    assertEquals("`a\"b``c`", Provider.MYSQL.quote("a\"b`c"));
    assertEquals("``", Provider.MYSQL.quote(""));
  }

  @Test
  void quoteRejectsNul() {
    for (var provider : Provider.SQL) {
      assertThrows(IllegalArgumentException.class, () -> provider.quote("a\0b"));
    }
  }

  // The newest version without the capability and the oldest with it; null where there is none.
  private record Cell(Provider provider, Capability capability, String before, String from) {}

  private static final String NEVER = "999.0";
  private static final String ALWAYS = "0";

  private static final List<Cell> TABLE = List.of(
      new Cell(POSTGRESQL, SCHEMAS, null, ALWAYS),
      new Cell(POSTGRESQL, CONNECTION_PER_DATABASE, null, ALWAYS),
      new Cell(POSTGRESQL, TRANSACTIONAL_DDL, null, ALWAYS),
      new Cell(POSTGRESQL, ERROR_POSITION, null, ALWAYS),
      new Cell(POSTGRESQL, EXPLAIN, null, ALWAYS),
      new Cell(POSTGRESQL, EXPLAIN_JSON, "8.4.22", "9.0"),
      new Cell(POSTGRESQL, EXPLAIN_ANALYZE, "7.1.3", "7.2"),
      new Cell(POSTGRESQL, IDENTITY_OVERRIDE, "9.6.24", "10.0"),
      new Cell(MARIADB, SCHEMAS, NEVER, null),
      new Cell(MARIADB, CONNECTION_PER_DATABASE, NEVER, null),
      new Cell(MARIADB, TRANSACTIONAL_DDL, NEVER, null),
      new Cell(MARIADB, ERROR_POSITION, NEVER, null),
      new Cell(MARIADB, EXPLAIN, null, ALWAYS),
      new Cell(MARIADB, EXPLAIN_JSON, "10.1.1", "10.1.2"),
      new Cell(MARIADB, EXPLAIN_ANALYZE, "10.0.38", "10.1.0"),
      new Cell(MARIADB, IDENTITY_OVERRIDE, NEVER, null),
      new Cell(MYSQL, SCHEMAS, NEVER, null),
      new Cell(MYSQL, CONNECTION_PER_DATABASE, NEVER, null),
      new Cell(MYSQL, TRANSACTIONAL_DDL, NEVER, null),
      new Cell(MYSQL, ERROR_POSITION, NEVER, null),
      new Cell(MYSQL, EXPLAIN, null, ALWAYS),
      new Cell(MYSQL, EXPLAIN_JSON, "5.6.4", "5.6.5"),
      new Cell(MYSQL, EXPLAIN_ANALYZE, "8.0.17", "8.0.18"),
      new Cell(MYSQL, IDENTITY_OVERRIDE, NEVER, null));

  @Test
  void tableCoversEveryProviderAndCapability() {
    var seen = new HashSet<List<Object>>();
    for (var cell : TABLE) {
      assertTrue(seen.add(List.of(cell.provider(), cell.capability())), cell.toString());
    }
    assertEquals(Provider.SQL.size() * Capability.values().length, seen.size());
  }

  @Test
  void supports() {
    var unknown = ServerVersion.UNKNOWN;
    var newest = ServerVersion.parse(NEVER);
    for (var cell : TABLE) {
      var provider = cell.provider();
      var capability = cell.capability();
      if (cell.before() != null) {
        assertFalse(
            provider.supports(capability, ServerVersion.parse(cell.before())), cell.toString());
        assertFalse(provider.supports(capability, unknown), cell.toString());
      }
      if (cell.from() != null) {
        assertTrue(
            provider.supports(capability, ServerVersion.parse(cell.from())), cell.toString());
        assertTrue(provider.supports(capability, newest), cell.toString());
      } else {
        assertFalse(provider.supports(capability, newest), cell.toString());
      }
    }
  }

  @Test
  void explain() {
    var v = ServerVersion.parse("99");
    assertEquals(Optional.of("EXPLAIN SELECT 1"), POSTGRESQL.explain("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN SELECT 1"), MARIADB.explain("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN SELECT 1"), MYSQL.explain("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN SELECT 1"), MYSQL.explain("SELECT 1", ServerVersion.UNKNOWN));
  }

  @Test
  void explainJson() {
    var v = ServerVersion.parse("99");
    assertEquals(
        Optional.of("EXPLAIN (FORMAT JSON) SELECT 1"), POSTGRESQL.explainJson("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN FORMAT=JSON SELECT 1"), MARIADB.explainJson("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN FORMAT=JSON SELECT 1"), MYSQL.explainJson("SELECT 1", v));
    assertEquals(
        Optional.empty(), POSTGRESQL.explainJson("SELECT 1", ServerVersion.parse("8.4.22")));
    assertEquals(Optional.empty(), MARIADB.explainJson("SELECT 1", ServerVersion.parse("10.1.1")));
    assertEquals(Optional.empty(), MYSQL.explainJson("SELECT 1", ServerVersion.parse("5.6.4")));
  }

  @Test
  void explainAnalyze() {
    var v = ServerVersion.parse("99");
    assertEquals(Optional.of("EXPLAIN ANALYZE SELECT 1"), POSTGRESQL.explainAnalyze("SELECT 1", v));
    assertEquals(Optional.of("ANALYZE SELECT 1"), MARIADB.explainAnalyze("SELECT 1", v));
    assertEquals(Optional.of("EXPLAIN ANALYZE SELECT 1"), MYSQL.explainAnalyze("SELECT 1", v));
    assertEquals(
        Optional.empty(), POSTGRESQL.explainAnalyze("SELECT 1", ServerVersion.parse("7.1.3")));
    assertEquals(
        Optional.empty(), MARIADB.explainAnalyze("SELECT 1", ServerVersion.parse("10.0.38")));
    assertEquals(Optional.empty(), MYSQL.explainAnalyze("SELECT 1", ServerVersion.parse("8.0.17")));
  }
}

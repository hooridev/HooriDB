package dev.hoori.db.core.connection;

import static dev.hoori.db.core.connection.Capability.ERROR_POSITION;
import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.connection.Capability.SCHEMAS;
import static dev.hoori.db.core.connection.Capability.TRANSACTIONAL_DDL;
import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderTest {

  private static String url(Provider provider, String host, String database, boolean tls) {
    return provider.jdbcUrl(ConnectionProfile.of(provider)
        .withHost(host)
        .withDatabase(database)
        .withUser("someone")
        .withTls(tls));
  }

  @Test
  void urlWithTls() {
    assertEquals(
        "jdbc:postgresql://db.example:5432/shop?sslmode=verify-full",
        url(Provider.POSTGRESQL, "db.example", "shop", true));
    assertEquals(
        "jdbc:mariadb://db.example:3306/shop?sslMode=verify-full",
        url(Provider.MARIADB, "db.example", "shop", true));
    assertEquals(
        "jdbc:mysql://db.example:3306/shop?sslMode=VERIFY_IDENTITY",
        url(Provider.MYSQL, "db.example", "shop", true));
  }

  @Test
  void urlWithoutTls() {
    assertEquals(
        "jdbc:postgresql://db.example:5432/shop?sslmode=disable",
        url(Provider.POSTGRESQL, "db.example", "shop", false));
    assertEquals(
        "jdbc:mariadb://db.example:3306/shop?sslMode=disable",
        url(Provider.MARIADB, "db.example", "shop", false));
    assertEquals(
        "jdbc:mysql://db.example:3306/shop?sslMode=DISABLED",
        url(Provider.MYSQL, "db.example", "shop", false));
  }

  @Test
  void urlPutsIpv6HostInBrackets() {
    assertEquals(
        "jdbc:postgresql://[::1]:5432/shop?sslmode=disable",
        url(Provider.POSTGRESQL, "::1", "shop", false));
    assertEquals(
        "jdbc:mariadb://[fe80::1]:3306/shop?sslMode=disable",
        url(Provider.MARIADB, "[fe80::1]", "shop", false));
    assertEquals(
        "jdbc:mysql://[2001:db8::1]:3306/shop?sslMode=DISABLED",
        url(Provider.MYSQL, "2001:db8::1", "shop", false));
  }

  @Test
  void urlEncodesDatabase() {
    assertEquals(
        "jdbc:postgresql://h:5432/my%20db%2Fa%3Fb%2B%26%23%25-._~%C3%A4?sslmode=disable",
        url(Provider.POSTGRESQL, "h", "my db/a?b+&#%-._~ä", false));
  }

  @Test
  void urlHasNoUser() {
    var url = url(Provider.MYSQL, "h", "d", true);
    assertEquals(-1, url.indexOf("someone"));
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
    for (var provider : Provider.values()) {
      assertThrows(IllegalArgumentException.class, () -> provider.quote("a\0b"));
    }
  }

  // The newest version without the capability and the oldest with it; null where there is none.
  private record Cell(Provider provider, Capability capability, String before, String from) {}

  private static final String NEVER = "999.0";
  private static final String ALWAYS = "0";

  private static final List<Cell> TABLE = List.of(
      new Cell(POSTGRESQL, SCHEMAS, null, ALWAYS),
      new Cell(POSTGRESQL, TRANSACTIONAL_DDL, null, ALWAYS),
      new Cell(POSTGRESQL, ERROR_POSITION, null, ALWAYS),
      new Cell(POSTGRESQL, EXPLAIN, null, ALWAYS),
      new Cell(POSTGRESQL, EXPLAIN_JSON, "8.4.22", "9.0"),
      new Cell(POSTGRESQL, EXPLAIN_ANALYZE, "7.1.3", "7.2"),
      new Cell(MARIADB, SCHEMAS, NEVER, null),
      new Cell(MARIADB, TRANSACTIONAL_DDL, NEVER, null),
      new Cell(MARIADB, ERROR_POSITION, NEVER, null),
      new Cell(MARIADB, EXPLAIN, null, ALWAYS),
      new Cell(MARIADB, EXPLAIN_JSON, "10.1.1", "10.1.2"),
      new Cell(MARIADB, EXPLAIN_ANALYZE, "10.0.38", "10.1.0"),
      new Cell(MYSQL, SCHEMAS, NEVER, null),
      new Cell(MYSQL, TRANSACTIONAL_DDL, NEVER, null),
      new Cell(MYSQL, ERROR_POSITION, NEVER, null),
      new Cell(MYSQL, EXPLAIN, null, ALWAYS),
      new Cell(MYSQL, EXPLAIN_JSON, "5.6.4", "5.6.5"),
      new Cell(MYSQL, EXPLAIN_ANALYZE, "8.0.17", "8.0.18"));

  @Test
  void tableCoversEveryProviderAndCapability() {
    var seen = new HashSet<List<Object>>();
    for (var cell : TABLE) {
      assertTrue(seen.add(List.of(cell.provider(), cell.capability())), cell.toString());
    }
    assertEquals(Provider.values().length * Capability.values().length, seen.size());
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

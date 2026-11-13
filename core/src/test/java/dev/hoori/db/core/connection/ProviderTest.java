package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}

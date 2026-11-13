package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionProfileTest {

  @Test
  void newProfileHasDefaultPortAndFreshId() {
    var a = ConnectionProfile.of(Provider.POSTGRESQL);
    var b = ConnectionProfile.of(Provider.POSTGRESQL);
    assertEquals(5432, a.port());
    assertEquals(3306, ConnectionProfile.of(Provider.MYSQL).port());
    assertNotEquals(a.id(), b.id());
    assertTrue(a.tls());
  }

  @Test
  void withersChangeOneFieldAndKeepTheId() {
    var p = ConnectionProfile.of(Provider.MARIADB);
    var q = p.withName("Shop")
        .withProvider(Provider.MYSQL)
        .withHost("db")
        .withPort(3307)
        .withDatabase("shop")
        .withUser("app")
        .withTls(false);
    assertEquals(
        new ConnectionProfile(p.id(), "Shop", Provider.MYSQL, "db", 3307, "shop", "app", false), q);
    assertEquals("", p.name());
    assertEquals(Provider.MARIADB, p.provider());
    assertTrue(p.tls());
  }

  @Test
  void equalFieldsAreEqual() {
    var id = UUID.randomUUID();
    var a = new ConnectionProfile(id, "n", Provider.POSTGRESQL, "h", 1, "d", "u", true);
    var b = new ConnectionProfile(id, "n", Provider.POSTGRESQL, "h", 1, "d", "u", true);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, b.withUser("v"));
  }

  @ParameterizedTest
  @CsvSource(textBlock = """
      localhost,                false
      LOCALHOST,                false
      db.localhost,             false
      Db.LocalHost,             false
      localhoſt,                true
      localhost.example.com,    true
      10.example.com,           true
      192.168.1.1.example.com,  true
      localhost:5432,           true
      127.0.0.1:5432,           true
      ' 127.0.0.1',             true
      '',                       true
      db.example,               true
      127.0.0.0,                false
      127.0.0.1,                false
      127.255.255.255,          false
      126.255.255.255,          true
      128.0.0.0,                true
      127.1,                    false
      0177.0.0.1,               true
      9.255.255.255,            true
      10.0.0.0,                 false
      10.255.255.255,           false
      11.0.0.0,                 true
      172.15.255.255,           true
      172.16.0.0,               false
      172.31.255.255,           false
      172.32.0.0,               true
      192.167.255.255,          true
      192.168.0.0,              false
      192.168.255.255,          false
      192.169.0.0,              true
      169.253.255.255,          true
      169.254.0.0,              false
      169.254.255.255,          false
      169.255.0.0,              true
      100.64.0.1,               true
      0.0.0.0,                  true
      8.8.8.8,                  true
      10,                       true
      [127.0.0.1],              true
      ::1,                      false
      [::1],                    false
      '[::1',                   true
      ::1],                     true
      ::2,                      true
      ::,                       true
      fe80::1,                  false
      [fe80::1],                false
      febf:ffff::1,             false
      fe7f:ffff::1,             true
      fec0::1,                  true
      fe80::1%1,                false
      fe80::1%eth0,             true
      fc00::,                   false
      [fdff:ffff::1],           false
      fbff:ffff::1,             true
      fe00::1,                  true
      2001:db8::1,              true
      [2001:db8::1],            true
      ::ffff:192.168.1.1,       false
      [::ffff:10.0.0.1],        false
      ::ffff:7f00:1,            false
      ::ffff:8.8.8.8,           true
      """)
  void tlsByDefaultIsOffForLocalHosts(String host, boolean tls) {
    assertEquals(tls, ConnectionProfile.tlsByDefault(host));
  }
}

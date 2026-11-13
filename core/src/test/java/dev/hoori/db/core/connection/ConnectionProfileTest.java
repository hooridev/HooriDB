package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

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
}

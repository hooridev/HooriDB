package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.hoori.db.core.connection.ConnectionProfile.Field;
import dev.hoori.db.core.connection.ConnectionProfile.Problem;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionProfileTest {

  @Test
  void newProfileHasDefaultPortAndFreshId() {
    var a = ConnectionProfile.of(Provider.POSTGRESQL);
    var b = ConnectionProfile.of(Provider.POSTGRESQL);
    assertEquals(5432, a.port());
    assertEquals(3306, ConnectionProfile.of(Provider.MYSQL).port());
    assertNotEquals(a.id(), b.id());
    assertEquals(Tls.ENCRYPTED, a.tls());
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
        .withTls(Tls.VERIFIED)
        .withColor(Color.TEAL);
    assertEquals(
        new ConnectionProfile(
            p.id(),
            "Shop",
            Provider.MYSQL,
            "db",
            3307,
            "shop",
            "app",
            Tls.VERIFIED,
            Color.TEAL,
            null),
        q);
    assertEquals(null, p.color());
    assertEquals("", p.name());
    assertEquals(Provider.MARIADB, p.provider());
    assertEquals(Tls.ENCRYPTED, p.tls());
  }

  @Test
  void equalFieldsAreEqual() {
    var id = UUID.randomUUID();
    var a =
        new ConnectionProfile(id, "n", Provider.POSTGRESQL, "h", 1, "d", "u", Tls.OFF, null, null);
    var b =
        new ConnectionProfile(id, "n", Provider.POSTGRESQL, "h", 1, "d", "u", Tls.OFF, null, null);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, b.withUser("v"));
    assertNotEquals(a, b.withColor(Color.RED));
    assertNotEquals(a, b.withDriverVersion("42.7.10"));
  }

  @Test
  void aDriverVersionMustBeOneHooriDbAccepts() {
    var p = ConnectionProfile.of(Provider.POSTGRESQL);
    assertEquals(null, p.driverVersion(), "recommended");
    assertEquals("42.7.10", p.withDriverVersion("42.7.10").driverVersion());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> p.withDriverVersion("../42"));
  }

  @ParameterizedTest
  @CsvSource(textBlock = """
      localhost,                 OFF
      LOCALHOST,                 OFF
      db.localhost,              OFF
      Db.LocalHost,              OFF
      localhost.,                OFF
      LocalHost.,                OFF
      db.localhost.,             OFF
      localhost..,               ENCRYPTED
      127.0.0.1.,                ENCRYPTED
      localhoſt,                 ENCRYPTED
      localhost.example.com,     ENCRYPTED
      10.example.com,            ENCRYPTED
      192.168.1.1.example.com,   ENCRYPTED
      localhost:5432,            ENCRYPTED
      127.0.0.1:5432,            ENCRYPTED
      ' 127.0.0.1',              ENCRYPTED
      '',                        ENCRYPTED
      db.example,                ENCRYPTED
      127.0.0.0,                 OFF
      127.0.0.1,                 OFF
      127.255.255.255,           OFF
      126.255.255.255,           ENCRYPTED
      128.0.0.0,                 ENCRYPTED
      127.1,                     OFF
      0177.0.0.1,                ENCRYPTED
      9.255.255.255,             ENCRYPTED
      10.0.0.0,                  OFF
      10.255.255.255,            OFF
      11.0.0.0,                  ENCRYPTED
      172.15.255.255,            ENCRYPTED
      172.16.0.0,                OFF
      172.31.255.255,            OFF
      172.32.0.0,                ENCRYPTED
      192.167.255.255,           ENCRYPTED
      192.168.0.0,               OFF
      192.168.255.255,           OFF
      192.169.0.0,               ENCRYPTED
      169.253.255.255,           ENCRYPTED
      169.254.0.0,               OFF
      169.254.255.255,           OFF
      169.255.0.0,               ENCRYPTED
      100.64.0.1,                ENCRYPTED
      0.0.0.0,                   ENCRYPTED
      8.8.8.8,                   ENCRYPTED
      10,                        ENCRYPTED
      [127.0.0.1],               ENCRYPTED
      ::1,                       OFF
      [::1],                     OFF
      '[::1',                    ENCRYPTED
      ::1],                      ENCRYPTED
      ::2,                       ENCRYPTED
      ::,                        ENCRYPTED
      fe80::1,                   OFF
      [fe80::1],                 OFF
      febf:ffff::1,              OFF
      fe7f:ffff::1,              ENCRYPTED
      fec0::1,                   ENCRYPTED
      fe80::1%1,                 OFF
      fe80::1%eth0,              OFF
      fe80::1%nosuchinterface9,  OFF
      [fe80::1%eth0],            OFF
      [fc00::1%lan.100],         OFF
      2001:db8::1%eth0,          ENCRYPTED
      127.0.0.1%1,               ENCRYPTED
      fc00::,                    OFF
      [fdff:ffff::1],            OFF
      fbff:ffff::1,              ENCRYPTED
      fe00::1,                   ENCRYPTED
      2001:db8::1,               ENCRYPTED
      [2001:db8::1],             ENCRYPTED
      ::ffff:192.168.1.1,        OFF
      [::ffff:10.0.0.1],         OFF
      ::ffff:7f00:1,             OFF
      ::ffff:8.8.8.8,            ENCRYPTED
      """)
  void tlsByDefaultIsOffForLocalHosts(String host, Tls tls) {
    assertEquals(tls, ConnectionProfile.tlsByDefault(host));
  }

  private static final ConnectionProfile FIT = ConnectionProfile.of(Provider.POSTGRESQL)
      .withHost("db.example")
      .withDatabase("shop")
      .withUser("app");

  @Test
  void aCompleteProfileHasNoProblems() {
    assertEquals(Set.of(), FIT.problems());
    assertEquals(Set.of(), FIT.withDatabase("").withUser("").problems());
  }

  @Test
  void anEmptyProfileMissesItsHost() {
    assertEquals(
        Set.of(Problem.HOST_MISSING), ConnectionProfile.of(Provider.MYSQL).problems());
    assertEquals(Field.HOST, Problem.HOST_MISSING.field());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "db",
        "db.example",
        "DB.Example.COM",
        "db.example.",
        "a-b.c-d.example",
        "xn--bckerei-5wa.example",
        "1.example",
        "db.123abc",
        "localhost",
        "localhost.",
        "a.b.c.d.e.f.g",
        "0.0.0.0",
        "127.0.0.1",
        "10.0.0.255",
        "255.255.255.255",
        "::1",
        "[::1]",
        "::",
        "2001:db8::1",
        "[2001:DB8:0:0:0:0:0:1]",
        "::ffff:192.168.1.1",
        "fe80::1%1",
        "[fe80::1%1]",
        "fe80::1%eth0",
        "[fe80::1%eth0.100]",
        "fe80::1%nosuchinterface9",
        "fe80::1%en_0-x",
        "postgres_db",
        "_db.example",
        "db_.example",
        "my_db_1.internal",
        "a._.example",
        "_",
      })
  void validHostsAreAccepted(String host) {
    assertEquals(Set.of(), FIT.withHost(host).problems(), host);
  }

  @Test
  void longNamesAreAcceptedUpToTheLimits() {
    var label = "a".repeat(63);
    assertEquals(Set.of(), FIT.withHost(label + ".example").problems());
    // 253 characters, and the final dot does not count.
    var name = String.join(".", label, label, label, "a".repeat(61));
    assertEquals(253, name.length());
    assertEquals(Set.of(), FIT.withHost(name).problems());
    assertEquals(Set.of(), FIT.withHost(name + ".").problems());
    assertEquals(Set.of(Problem.HOST_INVALID), FIT.withHost(name + "a").problems());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "db.example/shop?sslmode=disable&ignored=",
        "db.example?sslMode=disable",
        "db.example/shop",
        "db.example/",
        "db.example#x",
        "db.example&sslmode=disable",
        "user@db.example",
        "user:secret@db.example",
        "db.example:5432",
        "db.example:",
        "127.0.0.1:5432",
        "[::1]:5432",
        "[127.0.0.1]",
        "db1.example,db2.example",
        "db1.example,",
        "address=(host=db.example)(sslMode=disable)",
        "(host=db.example)",
        "jdbc:postgresql://db.example/shop",
        "//db.example",
        " db.example",
        "db.example ",
        "db example",
        "db.example\t",
        "db.example\n",
        "db.example\r\nX: y",
        "db\0.example",
        "db\u007f.example",
        "db\u00a0.example",
        "bäckerei.example",
        "-db.example",
        "db-.example",
        "db..example",
        ".db.example",
        ".",
        "db.example..",
        "db.example%2f",
        "db.%65xample",
        "127.1",
        "0177.0.0.1",
        "0x7f.0.0.1",
        "127.0.0.01",
        "256.0.0.1",
        "1.2.3.4.5",
        "1.2.3",
        "10",
        "127.0.0.1.",
        "db.123",
        "[::1",
        "::1]",
        "[[::1]]",
        ":::1",
        "1:2:3:4:5:6:7:8:9",
        "fe80::1%",
        "fe80::1%1%2",
        "fe80::1%eth0/x",
        "fe80::1%eth0]?sslMode=disable",
        "fe80::1% eth0",
        "::1 ",
        "[::1] ",
        "::1,::2",
        "::g",
      })
  void hostileOrBrokenHostsAreRefused(String host) {
    assertEquals(Set.of(Problem.HOST_INVALID), FIT.withHost(host).problems(), host);
  }

  @Test
  void anOverlongLabelIsRefused() {
    assertEquals(
        Set.of(Problem.HOST_INVALID), FIT.withHost("a".repeat(64) + ".example").problems());
  }

  @ParameterizedTest
  @CsvSource(textBlock = """
      -1,     false
      0,      false
      1,      true
      5432,   true
      65535,  true
      65536,  false
      2147483647, false
      -2147483648, false
      """)
  void portsFrom1To65535AreAccepted(int port, boolean fit) {
    assertEquals(
        fit ? Set.of() : Set.of(Problem.PORT_OUT_OF_RANGE), FIT.withPort(port).problems());
    assertEquals(Field.PORT, Problem.PORT_OUT_OF_RANGE.field());
  }

  @Test
  void nulInDatabaseOrUserIsRefused() {
    assertEquals(
        Set.of(Problem.DATABASE_HAS_NUL),
        FIT.withDatabase("shop\0user=postgres").problems());
    assertEquals(Set.of(Problem.USER_HAS_NUL), FIT.withUser("\0").problems());
    assertEquals(Field.DATABASE, Problem.DATABASE_HAS_NUL.field());
    assertEquals(Field.USER, Problem.USER_HAS_NUL.field());
  }

  @Test
  void problemsComeInTheOrderOfTheFields() {
    var all = FIT.withHost("a/b").withPort(0).withDatabase("\0").withUser("\0");
    assertEquals(
        List.of(
            Problem.HOST_INVALID,
            Problem.PORT_OUT_OF_RANGE,
            Problem.DATABASE_HAS_NUL,
            Problem.USER_HAS_NUL),
        List.copyOf(all.problems()));
  }
}

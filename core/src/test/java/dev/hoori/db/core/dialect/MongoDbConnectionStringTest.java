package dev.hoori.db.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConnectionString;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MongoDbConnectionStringTest {

  private static ConnectionString parse(String text) {
    return MongoDb.INSTANCE.connectionString(text).orElseThrow();
  }

  @Test
  void otherProvidersReturnEmpty() {
    for (var provider : Provider.SQL) {
      assertEquals(Optional.empty(), provider.traits().connectionString("mongodb://localhost/app"));
      assertEquals(
          Optional.empty(), provider.traits().connectionString("postgresql://localhost/app"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "localhost",
        "cluster0.example.net",
        "https://cluster0.example.net",
        "postgresql://localhost/app",
        "MONGODB://localhost",
        "MongoDb://localhost",
        "MONGODB+SRV://cluster0.example.net",
        "mongodb:localhost",
        " mongodb://localhost",
      })
  void nonMatchingSchemesReturnEmpty(String text) {
    assertEquals(Optional.empty(), MongoDb.INSTANCE.connectionString(text), text);
  }

  @Test
  void plainMinimalUsesDefaultPortAndTlsOff() {
    assertEquals(
        new ConnectionString(
            "localhost", 27017, false, "", "", "", "", Tls.OFF, List.of(), List.of()),
        parse("mongodb://localhost"));
    assertEquals(
        new ConnectionString(
            "db.example.net", 27017, false, "", "", "", "", Tls.OFF, List.of(), List.of()),
        parse("mongodb://db.example.net/"));
  }

  @Test
  void plainWithPortCredentialsDatabaseAndOptions() {
    var cs = parse(
        "mongodb://ann:secret@db.example.net:27018/app?authSource=admin&tls=true&replicaSet=rs0");
    assertEquals(
        new ConnectionString(
            "db.example.net",
            27018,
            false,
            "ann",
            "secret",
            "app",
            "admin",
            Tls.VERIFIED,
            List.of("replicaSet"),
            List.of()),
        cs);
    assertEquals(
        java.util.Set.of(),
        ConnectionProfile.of(Provider.MONGODB)
            .withHost(cs.host())
            .withPort(cs.port())
            .withSrv(cs.srv())
            .withUser(cs.user())
            .withDatabase(cs.database())
            .withAuthDatabase(cs.authDatabase())
            .withTls(cs.tls())
            .problems());
  }

  @Test
  void srvSetsSrvAndVerifiedTlsByDefault() {
    var cs = parse(
        "mongodb+srv://ann:s%40cret@cluster0.example.net/app?replicaSet=rs0&readPreference=secondary");
    assertEquals(
        new ConnectionString(
            "cluster0.example.net",
            27017,
            true,
            "ann",
            "s@cret",
            "app",
            "app",
            Tls.VERIFIED,
            List.of("replicaSet", "readPreference"),
            List.of()),
        cs);
  }

  @Test
  void percentEncodedCredentialsAndDatabaseAreDecoded() {
    var cs =
        parse("mongodb://a%40b%3Ac:p%2F%3F%23%25%2B%C3%A4%E6%9D%B1@db.example.net/b%C3%A4ckerei"
            + "?authSource=auth%2Ddb");
    assertEquals("a@b:c", cs.user());
    assertEquals("p/?#%+\u00e4\u6771", cs.password());
    assertEquals("b\u00e4ckerei", cs.database());
    assertEquals("auth-db", cs.authDatabase());
  }

  @Test
  void userWithoutPasswordAndWithEmptyPassword() {
    var noColon = parse("mongodb://ann@db.example.net/app");
    assertEquals("ann", noColon.user());
    assertEquals("", noColon.password());
    assertEquals("app", noColon.authDatabase());

    var emptyPass = parse("mongodb://ann:@db.example.net/app");
    assertEquals("ann", emptyPass.user());
    assertEquals("", emptyPass.password());
  }

  @Test
  void ipv6WithAndWithoutPortAndWithZone() {
    var withoutPort = parse("mongodb://[::1]/app");
    assertEquals("::1", withoutPort.host());
    assertEquals(27017, withoutPort.port());
    assertTrue(ConnectionProfile.isHost(withoutPort.host()));

    var withPort = parse("mongodb://[2001:db8::1]:27018/app");
    assertEquals("2001:db8::1", withPort.host());
    assertEquals(27018, withPort.port());
    assertTrue(ConnectionProfile.isHost(withPort.host()));

    var withZone = parse("mongodb://[fe80::1%25eth0]:27017");
    assertEquals("fe80::1%eth0", withZone.host());
    assertEquals(27017, withZone.port());
    assertTrue(ConnectionProfile.isHost(withZone.host()));
  }

  @Test
  void multipleHostsKeepTheFirstAndListTheRest() {
    var cs =
        parse("mongodb://a.example.net:27018,b.example.net:27017,[::1]:27019/shop?replicaSet=rs0");
    assertEquals("a.example.net", cs.host());
    assertEquals(27018, cs.port());
    assertEquals(List.of("b.example.net:27017", "[::1]:27019"), cs.otherHosts());
    assertEquals(List.of("replicaSet"), cs.unused());
  }

  @Test
  void authSourceDefaultsToDatabaseOnlyWhenUserAndDatabaseArePresent() {
    assertEquals("admin", parse("mongodb://ann:pw@host/app?authSource=admin").authDatabase());
    assertEquals("custom", parse("mongodb://host/app?authSource=custom").authDatabase());
    assertEquals("app", parse("mongodb://ann:pw@host/app").authDatabase());
    assertEquals("app", parse("mongodb://ann@host/app").authDatabase());
    assertEquals("", parse("mongodb://ann:pw@host/").authDatabase());
    assertEquals("", parse("mongodb://ann:pw@host").authDatabase());
    assertEquals("", parse("mongodb://host/app").authDatabase());
  }

  @Test
  void tlsOptionsAndSrvDefaulting() {
    assertEquals(Tls.OFF, parse("mongodb://host").tls());
    assertEquals(Tls.OFF, parse("mongodb://host/?tls=false").tls());
    assertEquals(Tls.OFF, parse("mongodb://host/?ssl=false").tls());
    assertEquals(Tls.ENCRYPTED, parse("mongodb://host/?tlsInsecure=true").tls());
    assertEquals(Tls.OFF, parse("mongodb://host/?tls=false&tlsInsecure=true").tls());
    assertEquals(
        Tls.VERIFIED,
        parse("mongodb://host/?tls=true&tlsInsecure=true&tlsInsecure=false").tls());
    assertEquals(
        Tls.ENCRYPTED, parse("mongodb://host/?tlsAllowInvalidCertificates=true").tls());

    assertEquals(Tls.VERIFIED, parse("mongodb://host/?tls=true").tls());
    assertEquals(Tls.VERIFIED, parse("mongodb://host/?ssl=true").tls());
    assertEquals(Tls.VERIFIED, parse("mongodb://host/?TLS=true&SSL=true").tls());
    assertEquals(
        Tls.VERIFIED, parse("mongodb://host/?tls=true&tlsInsecure=false").tls());

    assertEquals(
        Tls.ENCRYPTED, parse("mongodb://host/?tls=true&tlsInsecure=true").tls());
    assertEquals(
        Tls.ENCRYPTED,
        parse("mongodb://host/?ssl=true&tlsAllowInvalidCertificates=true").tls());

    assertEquals(Tls.VERIFIED, parse("mongodb+srv://cluster.example.net").tls());
    assertEquals(Tls.OFF, parse("mongodb+srv://cluster.example.net/?tls=false").tls());
    assertEquals(Tls.OFF, parse("mongodb+srv://cluster.example.net/?ssl=false").tls());
    assertEquals(
        Tls.ENCRYPTED,
        parse("mongodb+srv://cluster.example.net/?tlsInsecure=true").tls());
    assertEquals(
        Tls.ENCRYPTED,
        parse("mongodb+srv://cluster.example.net/?tlsAllowInvalidCertificates=true")
            .tls());
  }

  @Test
  void optionsAreCaseInsensitiveAndUnusedNamesHaveNoValues() {
    var scram1 = parse("mongodb://host/?AuthMechanism=SCRAM-SHA-1&AuthSource=admin&TLS=true");
    // The driver picks the mechanism itself, so even a SCRAM one is not used.
    assertEquals(List.of("AuthMechanism"), scram1.unused());
    assertEquals("admin", scram1.authDatabase());
    assertEquals(Tls.VERIFIED, scram1.tls());

    var other = parse("mongodb://host/?authMechanism=MONGODB-AWS"
        + "&authMechanismProperties=AWS_SESSION_TOKEN:secret-token-value"
        + "&replicaSet=rs0&REPLICASET=rs0&retryWrites=true&");
    assertEquals(
        List.of("authMechanism", "authMechanismProperties", "replicaSet", "retryWrites"),
        other.unused());
    assertFalse(other.toString().contains("secret-token-value"), other.toString());
  }

  @Test
  void aCollectionInThePathAndSemicolonsBetweenOptionsAreReadAsTheDriverDoes() {
    var cs = parse("mongodb://db.example.net/sales.prod?replicaSet=rs0;tls=true");
    assertEquals("sales", cs.database());
    assertEquals(Tls.VERIFIED, cs.tls());
    assertEquals(List.of("replicaSet"), cs.unused());
  }

  @Test
  void toStringNeverContainsUserOrPassword() {
    var cs = parse("mongodb+srv://alice_secret_user:hunter2_secret_pass@cluster.example.net/app");
    String rendered = cs.toString();
    assertFalse(rendered.contains("alice_secret_user"), rendered);
    assertFalse(rendered.contains("hunter2_secret_pass"), rendered);
    assertTrue(rendered.contains("cluster.example.net"), rendered);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mongodb://",
        "mongodb:///",
        "mongodb://user_xyz:pass_xyz@",
        "mongodb://user_xyz:pass_xyz@/db",
        "mongodb://@localhost",
        "mongodb://:pass_xyz@localhost",
        "mongodb://:@localhost",
        "mongodb://user_xyz:p@ss_xyz@localhost",
        "mongodb://user_xyz:p:ss_xyz@localhost",
        "mongodb://user_xyz/name:pass_xyz@localhost/db",
        "mongodb://user_xyz:p/ss_xyz@localhost",
        "mongodb://user_xyz:p#ss_xyz@localhost/db",
        "mongodb://user_xyz:pass_xyz@localhost:0",
        "mongodb://user_xyz:pass_xyz@localhost:65536",
        "mongodb://user_xyz:pass_xyz@localhost:9999999999",
        "mongodb://user_xyz:pass_xyz@localhost:abc",
        "mongodb://user_xyz:pass_xyz@localhost:",
        "mongodb://user_xyz:pass_xyz@localhost,-1",
        "mongodb://user_xyz:pass_xyz@a.example,",
        "mongodb://user_xyz:pass_xyz@,a.example",
        "mongodb://user_xyz:pass_xyz@a.example,b.example:99999",
        "mongodb://user_xyz:pass_xyz@%2Ftmp%2Fmongodb-27017.sock",
        "mongodb://user_xyz:pass_xyz@%2ftmp%2fmongodb-27017.sock/db",
        "mongodb://user_xyz:pass%2@localhost",
        "mongodb://user_xyz:pass%GG@localhost",
        "mongodb://user_xyz:pass%FF@localhost",
        "mongodb://user_xyz%ZZ:pass_xyz@localhost",
        "mongodb://user_xyz:pass_xyz@localhost/db%2",
        "mongodb://user_xyz:pass_xyz@localhost/a/b",
        "mongodb://user_xyz:pass_xyz@localhost/a%2Fb",
        "mongodb://user_xyz:pass_xyz@localhost/a%24b",
        "mongodb://user_xyz:pass_xyz@localhost/app?authSource=a.b",
        "mongodb://user_xyz:pass\nxyz@localhost",
        "mongodb://user_xyz:pass\fxyz@localhost",
        "mongodb://user_xyz:pass xyz@localhost",
        "mongodb://user_xyz:pass_xyz@localhost?tls=true",
        "mongodb://user_xyz:pass_xyz@::1",
        "mongodb://user_xyz:pass_xyz@[::1",
        "mongodb://user_xyz:pass_xyz@[127.0.0.1]:27017",
        "mongodb://user_xyz:pass_xyz@[::g]:27017",
        "mongodb://user_xyz:pass_xyz@[::1]extra",
        "mongodb://user_xyz:pass_xyz@bad host",
        "mongodb+srv://user_xyz:pass_xyz@cluster.example.net:27017",
        "mongodb+srv://user_xyz:pass_xyz@[::1]:27017",
        "mongodb+srv://user_xyz:pass_xyz@a.example.net,b.example.net",
        "mongodb://user_xyz:pass_xyz@localhost/?tls=true&ssl=false",
        "mongodb://user_xyz:pass_xyz@localhost/?tls=false&ssl=true",
        "mongodb://user_xyz:pass_xyz@localhost/?tls=true&tls=false",
        "mongodb://user_xyz:pass_xyz@localhost/?tls=yes",
        "mongodb://user_xyz:pass_xyz@localhost/?tlsInsecure=maybe",
        "mongodb://user_xyz:pass_xyz@localhost/?flagonly",
        "mongodb://user_xyz:pass_xyz@localhost/?=value",
      })
  void invalidStringsThrowWithoutEchoingCredentials(String invalid) {
    var error = assertThrows(
        IllegalArgumentException.class, () -> MongoDb.INSTANCE.connectionString(invalid), invalid);
    String message = error.getMessage();
    assertFalse(message == null || message.isBlank(), invalid);
    assertFalse(message.contains("user_xyz"), message);
    assertFalse(message.contains("pass_xyz"), message);
    assertFalse(message.contains("pass"), message);
    assertFalse(message.contains("p@ss"), message);
    assertFalse(message.contains("p:ss"), message);
    assertFalse(message.contains("p/ss"), message);
    assertFalse(message.contains("p#ss"), message);
  }
}

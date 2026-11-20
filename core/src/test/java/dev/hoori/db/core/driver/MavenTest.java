package dev.hoori.db.core.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MavenTest {

  private static final List<String> HOSTILE = List.of(
      "",
      ".",
      "..",
      "../x",
      "a/b",
      "a\\b",
      "a%2fb",
      "%2e%2e",
      "a..b",
      ".a",
      "a.",
      "a b",
      "a\nb",
      "a\0b",
      "a:b",
      "a?b",
      "a#b",
      "a@b",
      "CON",
      "nul.txt",
      "lpt1",
      "ä",
      "x".repeat(101));

  @Test
  void hostileNamesAndVersionsAreRefused() {
    for (var text : HOSTILE) {
      assertFalse(Maven.isName(text), text);
      assertFalse(Maven.isVersion(text), text);
    }
    assertFalse(Maven.isName(null));
    assertFalse(Maven.isVersion(null));
    assertFalse(Maven.isVersion("1.0_"));
    assertThrows(IllegalArgumentException.class, () -> new Driver("org..x", "a", "org.x.Driver"));
    assertThrows(IllegalArgumentException.class, () -> new Driver("org.x", "../a", "a.Driver"));
    assertThrows(IllegalArgumentException.class, () -> new Driver("org.x", "a", "a/Driver"));
  }

  @Test
  void realNamesAndVersionsAreAccepted() {
    for (var name : List.of("org.postgresql", "mariadb-java-client", "com.mysql", "a_b")) {
      assertTrue(Maven.isName(name), name);
    }
    for (var version : List.of(
        "42.7.14", "9.4-1201-jdbc41", "42.2.0.jre7", "1.3.0-beta-1", "26.10.0", "1.5.0-RC1")) {
      assertTrue(Maven.isVersion(version), version);
    }
  }

  @Test
  void onlyNumbersAreStable() {
    for (var version : List.of("42.7.14", "4.0.0", "26.10.0", "9.4.1212")) {
      assertTrue(Maven.isStable(version), version);
    }
    for (var version : List.of(
        "3.0.0-alpha",
        "3.0.1-beta",
        "3.0.2-rc",
        "1.5.0-RC1",
        "1.5.1-RC",
        "1.3.0-beta-1",
        "42.2.0.jre7",
        "9.4-1201-jdbc41",
        "8.0.0-dmr",
        "1.0-SNAPSHOT",
        "2.0.0-M1")) {
      assertFalse(Maven.isStable(version), version);
    }
  }

  @Test
  void versionsAreOrderedAsNumbers() {
    var sorted = Stream.of(
            "42.7.9",
            "3.0.0",
            "42.7.10",
            "9.7.0",
            "26.10.0",
            "26.7.0",
            "3.0.0-alpha",
            "3.0.1-beta",
            "42.2.0.jre7",
            "42.2.0",
            "42.2.1")
        .sorted(Maven.NEWEST_FIRST)
        .toList();
    assertEquals(
        List.of(
            "42.7.10",
            "42.7.9",
            "42.2.1",
            "42.2.0",
            "42.2.0.jre7",
            "26.10.0",
            "26.7.0",
            "9.7.0",
            "3.0.1-beta",
            "3.0.0",
            "3.0.0-alpha"),
        sorted);
  }

  @Test
  void everyProviderHasADriver() {
    for (var provider : Provider.values()) {
      var driverClass = Driver.of(provider).driverClass();
      assertTrue(driverClass.endsWith("Driver") || driverClass.endsWith("Bridge"), provider.name());
    }
    assertEquals(
        new Driver("org.postgresql", "postgresql", "org.postgresql.Driver"),
        Driver.of(Provider.POSTGRESQL));
  }

  @Test
  void aRepositoryIsHttpsOnly() {
    for (var url : List.of(
        "http://repo.maven.apache.org/maven2/",
        "file:///tmp/repo/",
        "ftp://example.com/",
        "https://user:secret@example.com/maven2/",
        "https://example.com/maven2/?a=b",
        "https://example.com/maven2/#secret",
        "https:/maven2/")) {
      var e = assertThrows(
          IllegalArgumentException.class, () -> new MavenRepository(URI.create(url)), url);
      assertFalse(e.getMessage().contains("secret") || e.getMessage().contains("example"), url);
    }
    var central = MavenRepository.central();
    assertEquals(
        URI.create("https://repo.maven.apache.org/maven2/org/postgresql/postgresql/x.jar"),
        central.uri("org/postgresql/postgresql/x.jar"));
    assertEquals(
        URI.create("https://example.com/maven2/a.jar"),
        new MavenRepository(URI.create("https://example.com/maven2")).uri("a.jar"));
    assertThrows(IllegalArgumentException.class, () -> central.uri("../../x"));
    assertThrows(IllegalArgumentException.class, () -> central.uri("https://evil.example/x"));
  }
}

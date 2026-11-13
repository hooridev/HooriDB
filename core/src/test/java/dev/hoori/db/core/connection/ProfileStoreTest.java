package dev.hoori.db.core.connection;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class ProfileStoreTest {

  private static final String PROFILE = """
      {
        "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
        "name": "Local",
        "provider": "POSTGRESQL",
        "host": "localhost",
        "port": 5432,
        "database": "app",
        "user": "app",
        "tls": false
      }""";

  private static final String VALID = "{\"version\": 1, \"profiles\": [" + PROFILE + "]}";

  private static final List<ConnectionProfile> PROFILES = List.of(
      new ConnectionProfile(
          UUID.randomUUID(),
          "  Bäckerei 東京 = a:b #c \\ d \"q\"\nsecond line\t𝄞  ",
          Provider.POSTGRESQL,
          "::1",
          5432,
          "shop/€",
          "ünïcode user",
          false),
      new ConnectionProfile(UUID.randomUUID(), "", Provider.MYSQL, "", 0, "", "", true),
      new ConnectionProfile(
          UUID.randomUUID(), "{\"x\": 1}", Provider.MARIADB, "db.example", 3307, "x", "y", true));

  @TempDir
  Path folder;

  @Test
  void savedProfilesComeBackInOrder() throws IOException {
    var store = new ProfileStore(folder);
    store.save(PROFILES);
    assertEquals(PROFILES, store.load());
    assertEquals(PROFILES, new ProfileStore(folder).load());
  }

  @Test
  void aMissingFileIsNoProfilesAndSaveCreatesTheFolder() throws IOException {
    var store = new ProfileStore(folder.resolve("a").resolve("b"));
    assertEquals(List.of(), store.load());
    store.save(PROFILES);
    assertTrue(Files.isRegularFile(folder.resolve("a").resolve("b").resolve(ProfileStore.FILE)));
  }

  @Test
  void saveReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
    var store = new ProfileStore(folder);
    store.save(PROFILES);
    store.save(PROFILES.subList(1, 2));
    assertEquals(PROFILES.subList(1, 2), store.load());
    try (var files = Files.list(folder)) {
      assertEquals(List.of(folder.resolve(ProfileStore.FILE)), files.toList());
    }
  }

  @Test
  void theFileHoldsOnlyTheFieldsOfTheRecord() throws IOException {
    new ProfileStore(folder).save(PROFILES);
    var text = Files.readString(folder.resolve(ProfileStore.FILE));
    assertFalse(text.toLowerCase(Locale.ROOT).contains("password"), text);
    assertTrue(text.contains("\n  \"profiles\""), "indented: " + text);
    var root = JsonMapper.builder().build().readTree(text);
    assertEquals(Set.of("version", "profiles"), Set.copyOf(root.propertyNames()));
    for (var profile : root.get("profiles")) {
      assertEquals(
          Set.of("id", "name", "provider", "host", "port", "database", "user", "tls"),
          Set.copyOf(profile.propertyNames()));
    }
  }

  @Test
  void unknownFieldsOfTheSameVersionAreIgnored() throws IOException {
    write(VALID
        .replace("\"version\": 1", "\"version\": 1, \"theme\": {\"dark\": true}")
        .replace("\"tls\": false", "\"tls\": false, \"colour\": \"red\"")
        .getBytes(UTF_8));
    var profiles = new ProfileStore(folder).load();
    assertEquals(1, profiles.size());
    assertEquals("Local", profiles.getFirst().name());
  }

  static Stream<Arguments> brokenFiles() {
    return Stream.of(
        broken("", "not a JSON object"),
        broken("[]", "not a JSON object"),
        broken(VALID.replace("]}", "]"), "not valid JSON at line"),
        broken(VALID + " {}", "not valid JSON"),
        broken(VALID.replace("\"port\"", "\"name\": \"again\", \"port\""), "not valid JSON"),
        broken(VALID.replace("\"version\": 1, ", ""), "has no version"),
        broken(VALID.replace("\"version\": 1", "\"version\": 2"), "newer HooriDB"),
        broken(VALID.replace("\"version\": 1", "\"version\": \"1\""), "version \"1\" is unknown"),
        broken(VALID.replace("\"version\": 1", "\"version\": 0"), "version 0 is unknown"),
        broken(VALID.replace("\"profiles\"", "\"other\""), "\"profiles\" is missing"),
        broken(VALID.replace(PROFILE, "1"), "profile 1 is not a JSON object"),
        broken(VALID.replace("\"host\": \"localhost\",", ""), "profile 1 has no \"host\""),
        broken(VALID.replace("\"localhost\"", "null"), "\"host\" is not text"),
        broken(VALID.replace("\"app\",", "7,"), "\"database\" is not text"),
        broken(VALID.replace("0f8fad5b-d9cb-469f", "nope"), "\"id\" is not a UUID"),
        broken(VALID.replace("0f8fad5b-d9cb-469f-a165-70867728950e", "1-2-3-4-5"), "not a UUID"),
        broken(VALID.replace("POSTGRESQL", "ORACLE"), "\"ORACLE\", a database HooriDB does not"),
        broken(VALID.replace("5432", "\"5432\""), "\"port\" is not a whole number"),
        broken(VALID.replace("5432", "5432.0"), "\"port\" is not a whole number"),
        broken(VALID.replace("5432", "99999999999"), "\"port\" is not a whole number"),
        broken(VALID.replace("false", "\"false\""), "\"tls\" is not true or false"),
        broken(VALID.replace(PROFILE, PROFILE + "," + PROFILE), "profile 2 has the id"),
        // "ÿ" in Latin-1 is a byte that UTF-8 never has.
        Arguments.of(VALID.replace("Local", "Lÿcal").getBytes(ISO_8859_1), "not valid JSON"));
  }

  private static Arguments broken(String text, String why) {
    return Arguments.of(text.getBytes(UTF_8), why);
  }

  @ParameterizedTest
  @MethodSource("brokenFiles")
  void aBrokenFileFailsAndStaysAsItIs(byte[] content, String why) throws IOException {
    var file = write(content);
    var failure =
        assertThrows(ProfileStore.UnreadableException.class, new ProfileStore(folder)::load);
    assertTrue(failure.getMessage().contains(why), failure.getMessage());
    assertTrue(failure.getMessage().contains(file.toString()), failure.getMessage());
    assertArrayEquals(content, Files.readAllBytes(file));
  }

  @Test
  void saveKeepsACopyOfABrokenFile() throws IOException {
    var content = VALID.replace("\"version\": 1", "\"version\": 2").getBytes(UTF_8);
    write(content);
    var store = new ProfileStore(folder);
    store.save(PROFILES);
    assertEquals(PROFILES, store.load());
    assertArrayEquals(content, Files.readAllBytes(folder.resolve(ProfileStore.UNREADABLE)));
  }

  private Path write(byte[] content) throws IOException {
    return Files.write(folder.resolve(ProfileStore.FILE), content);
  }
}

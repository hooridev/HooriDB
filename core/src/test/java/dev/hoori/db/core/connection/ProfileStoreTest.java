package dev.hoori.db.core.connection;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
        "provider": "postgresql",
        "host": "localhost",
        "port": 5432,
        "database": "app",
        "user": "app",
        "tls": "off"
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
          Tls.OFF,
          Colour.RED,
          null),
      new ConnectionProfile(
          UUID.randomUUID(), "", Provider.MYSQL, "", 0, "", "", Tls.ENCRYPTED, null, "8.4.0"),
      new ConnectionProfile(
          UUID.randomUUID(),
          "{\"x\": 1}",
          Provider.MARIADB,
          "db.example",
          3307,
          "x",
          "y",
          Tls.VERIFIED,
          Colour.GREY,
          "3.5.1"));

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
    var fields = Set.of("id", "name", "provider", "host", "port", "database", "user", "tls");
    var withColour = new java.util.HashSet<>(fields);
    withColour.add("colour");
    var profiles = root.get("profiles");
    assertEquals(withColour, Set.copyOf(profiles.get(0).propertyNames()));
    var withDriver = new java.util.HashSet<>(fields);
    withDriver.add("driverVersion");
    assertEquals(withDriver, Set.copyOf(profiles.get(1).propertyNames()), "no colour, no field");
  }

  @Test
  void providerAndTlsAreStoredByNamesOfTheirOwn() throws IOException {
    new ProfileStore(folder).save(PROFILES);
    var root =
        JsonMapper.builder().build().readTree(Files.readString(folder.resolve(ProfileStore.FILE)));
    var stored = new ArrayList<String>();
    for (var profile : root.get("profiles")) {
      stored.add(
          profile.get("provider").stringValue() + " " + profile.get("tls").stringValue());
    }
    assertEquals(List.of("postgresql off", "mysql encrypted", "mariadb verified"), stored);
    var colours = new ArrayList<String>();
    for (var colour : Colour.values()) {
      new ProfileStore(folder).save(List.of(PROFILES.get(1).withColour(colour)));
      var text = Files.readString(folder.resolve(ProfileStore.FILE));
      colours.add(JsonMapper.builder()
          .build()
          .readTree(text)
          .get("profiles")
          .get(0)
          .get("colour")
          .stringValue());
      assertEquals(colour, new ProfileStore(folder).load().getFirst().colour());
    }
    assertEquals(
        List.of(
            "blue", "violet", "purple", "pink", "red", "orange", "amber", "green", "teal", "grey"),
        colours);
  }

  @Test
  void aFileWithoutColoursLoadsWithNone() throws IOException {
    write(VALID.getBytes(UTF_8));
    assertEquals(null, new ProfileStore(folder).load().getFirst().colour());
  }

  @Test
  void aFileWithoutADriverVersionLoadsWithTheRecommendedOne() throws IOException {
    write(VALID.getBytes(UTF_8));
    assertEquals(null, new ProfileStore(folder).load().getFirst().driverVersion());
  }

  @Test
  void unknownFieldsOfTheSameVersionAreIgnored() throws IOException {
    write(VALID
        .replace("\"version\": 1", "\"version\": 1, \"theme\": {\"dark\": true}")
        .replace("\"tls\": \"off\"", "\"tls\": \"off\", \"folder\": \"work\"")
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
        broken(VALID.replace("\"version\": 1", "\"version\": \"1\""), "version is not a whole"),
        broken(VALID.replace("\"version\": 1", "\"version\": 0"), "version 0 is unknown"),
        broken(VALID.replace("\"profiles\"", "\"other\""), "\"profiles\" is missing"),
        broken(VALID.replace(PROFILE, "1"), "profile 1 is not a JSON object"),
        broken(VALID.replace("\"host\": \"localhost\",", ""), "profile 1 has no \"host\""),
        broken(VALID.replace("\"localhost\"", "null"), "\"host\" is not text"),
        broken(VALID.replace("\"app\",", "7,"), "\"database\" is not text"),
        broken(VALID.replace("0f8fad5b-d9cb-469f", "nope"), "\"id\" is not a UUID"),
        broken(VALID.replace("0f8fad5b-d9cb-469f-a165-70867728950e", "1-2-3-4-5"), "not a UUID"),
        broken(VALID.replace("postgresql", "oracle"), "\"provider\" is not a database"),
        broken(VALID.replace("postgresql", "POSTGRESQL"), "\"provider\" is not a database"),
        broken(VALID.replace("5432", "\"5432\""), "\"port\" is not a whole number"),
        broken(VALID.replace("5432", "5432.0"), "\"port\" is not a whole number"),
        broken(VALID.replace("5432", "99999999999"), "\"port\" is not a whole number"),
        broken(VALID.replace("\"off\"", "\"strict\""), "\"tls\" is not \"off\", \"encrypted\""),
        broken(VALID.replace("\"off\"", "\"OFF\""), "\"tls\" is not \"off\", \"encrypted\""),
        broken(VALID.replace("\"off\"", "false"), "\"tls\" is not text"),
        broken(VALID.replace("\"off\"", "\"off\", \"colour\": \"crimson\""), "\"colour\" is not a"),
        broken(VALID.replace("\"off\"", "\"off\", \"colour\": \"RED\""), "\"colour\" is not a"),
        broken(VALID.replace("\"off\"", "\"off\", \"colour\": null"), "\"colour\" is not text"),
        broken(
            VALID.replace("\"off\"", "\"off\", \"driverVersion\": \"../1\""),
            "\"driverVersion\" is not a version"),
        broken(
            VALID.replace("\"off\"", "\"off\", \"driverVersion\": 42"),
            "\"driverVersion\" is not text"),
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

  @Test
  void aMessageNeverRepeatsWhatTheFileHolds() throws IOException {
    var broken = List.of(
        VALID.replace("\"version\": 1", "\"version\": \"s3cret\""),
        VALID.replace("0f8fad5b-d9cb-469f", "s3cret"),
        VALID.replace("postgresql", "s3cret"),
        VALID.replace("\"off\"", "\"off\", \"colour\": \"s3cret\""),
        VALID.replace("\"off\"", "\"off\", \"driverVersion\": \"s3cret/\""));
    for (var content : broken) {
      write(content.getBytes(UTF_8));
      var failure =
          assertThrows(ProfileStore.UnreadableException.class, new ProfileStore(folder)::load);
      assertFalse(failure.getMessage().contains("s3cret"), failure.getMessage());
    }
  }

  @Test
  void aFileTooLargeIsRefusedUnreadAndKeptOnSave() throws IOException {
    var content = new byte[(int) ProfileStore.MAX_SIZE + 1];
    Arrays.fill(content, (byte) ' ');
    write(content);
    var store = new ProfileStore(folder);
    var failure = assertThrows(ProfileStore.UnreadableException.class, store::load);
    assertTrue(failure.getMessage().contains("larger than 16 MB"), failure.getMessage());
    store.save(PROFILES);
    assertEquals(PROFILES, store.load());
    assertEquals(content.length, Files.size(folder.resolve(ProfileStore.UNREADABLE)));
  }

  @Test
  void saveRefusesTwoProfilesWithOneIdAndLeavesTheFile() throws IOException {
    var store = new ProfileStore(folder);
    store.save(PROFILES);
    var before = Files.readAllBytes(folder.resolve(ProfileStore.FILE));
    var twice = List.of(PROFILES.getFirst(), PROFILES.getFirst());
    assertThrows(IllegalArgumentException.class, () -> store.save(twice));
    assertArrayEquals(before, Files.readAllBytes(folder.resolve(ProfileStore.FILE)));
  }

  private Path write(byte[] content) throws IOException {
    return Files.write(folder.resolve(ProfileStore.FILE), content);
  }

  /** A failure of the file system: not unreadable, its cause kept, in HooriDB's words. */
  private static IOException failure(Executable call, String says) {
    var failure = assertThrows(IOException.class, call);
    assertFalse(failure instanceof ProfileStore.UnreadableException, failure.getMessage());
    assertTrue(failure.getMessage().contains(says), failure.getMessage());
    assertTrue(failure.getCause() instanceof IOException, failure.getMessage());
    return failure;
  }

  @Test
  void aFolderInPlaceOfTheFileIsSaidSo(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve(ProfileStore.FILE));
    var store = new ProfileStore(folder);
    var file = folder.resolve(ProfileStore.FILE);
    var read = failure(store::load, "it is a folder, not a file");
    assertEquals(
        "Cannot read the connections in " + file + ": it is a folder, not a file.",
        read.getMessage());
    failure(() -> store.save(PROFILES), "Cannot save the connections in " + file);
  }

  @Test
  void aFileInPlaceOfTheFolderIsSaidSo(@TempDir Path folder) throws IOException {
    var blocking = Files.writeString(folder.resolve("blocking"), "");
    var store = new ProfileStore(blocking.resolve("hooridb"));
    assertEquals(List.of(), store.load(), "there is no file below a file");
    failure(() -> store.save(PROFILES), blocking + " is a file, not a folder");
  }

  @Test
  void deniedAccessIsSaidSo(@TempDir Path folder) throws IOException {
    assumeTrue(
        folder.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "permissions are set the POSIX way");
    var store = new ProfileStore(folder);
    store.save(PROFILES);
    var file = folder.resolve(ProfileStore.FILE);
    Files.setPosixFilePermissions(file, Set.of());
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isReadable(file), "an administrator may read any file");
      failure(store::load, "access was denied");
      failure(() -> store.save(PROFILES), "access was denied");
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }
}

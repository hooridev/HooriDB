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
import java.util.HashSet;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceStoreTest {

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

  private static final String CONNECTION = PROFILE.replace("{", "{\"kind\": \"connection\",");

  private static final String FOLDER = """
      {
        "kind": "folder",
        "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "name": "Work",
        "open": true,
        "entries": [%s]
      }""".formatted(CONNECTION);

  // The connection is entry 1.1, in the folder.
  private static final String VALID = "{\"version\": 1, \"entries\": [" + FOLDER + "]}";

  /** The file of connections of a HooriDB before the workspace. */
  private static final String OLDER = """
      {"version": 1, "profiles": [%s, {
        "id": "a3bb189e-8bf9-3888-9912-ace4e6543002",
        "name": "Second",
        "provider": "mariadb",
        "host": "db.example",
        "port": 3307,
        "database": "x",
        "user": "y",
        "tls": "verified",
        "color": "gray",
        "driverVersion": "3.5.1"
      }]}""".formatted(PROFILE);

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
          Color.RED,
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
          Color.GRAY,
          "3.5.1"));

  /** Folders in folders, open and closed, one empty, with connections at each level. */
  private static final Workspace WORKSPACE = new Workspace(List.of(
      PROFILES.get(2),
      new Workspace.Folder(
          UUID.randomUUID(),
          "Production 東京 \"q\"\n",
          true,
          List.of(
              new Workspace.Folder(UUID.randomUUID(), "", false, List.of(PROFILES.get(0))),
              new Workspace.Folder(UUID.randomUUID(), "empty", false, List.of()),
              PROFILES.get(1)))));

  private static Workspace flat(List<ConnectionProfile> profiles) {
    return new Workspace(List.copyOf(profiles));
  }

  @TempDir
  Path folder;

  @Test
  void aSavedWorkspaceComesBackWithItsFoldersInOrder() throws IOException {
    var store = new WorkspaceStore(folder);
    store.save(WORKSPACE);
    assertEquals(WORKSPACE, store.load());
    assertEquals(WORKSPACE, new WorkspaceStore(folder).load());
    assertEquals(
        List.of(PROFILES.get(2), PROFILES.get(0), PROFILES.get(1)), WORKSPACE.connections());
  }

  @Test
  void aMissingFileIsAnEmptyWorkspaceAndSaveCreatesTheFolder() throws IOException {
    var store = new WorkspaceStore(folder.resolve("a").resolve("b"));
    assertEquals(Workspace.EMPTY, store.load());
    store.save(WORKSPACE);
    assertTrue(Files.isRegularFile(folder.resolve("a").resolve("b").resolve(WorkspaceStore.FILE)));
  }

  @Test
  void saveReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
    var store = new WorkspaceStore(folder);
    store.save(WORKSPACE);
    store.save(flat(PROFILES.subList(1, 2)));
    assertEquals(flat(PROFILES.subList(1, 2)), store.load());
    try (var files = Files.list(folder)) {
      assertEquals(List.of(folder.resolve(WorkspaceStore.FILE)), files.toList());
    }
  }

  @Test
  void theFileHoldsOnlyTheFieldsOfTheRecords() throws IOException {
    new WorkspaceStore(folder).save(WORKSPACE);
    var text = Files.readString(folder.resolve(WorkspaceStore.FILE));
    assertFalse(text.toLowerCase(Locale.ROOT).contains("password"), text);
    assertTrue(text.contains("\n  \"entries\""), "indented: " + text);
    var root = JsonMapper.builder().build().readTree(text);
    assertEquals(Set.of("version", "entries"), Set.copyOf(root.propertyNames()));
    var entries = root.get("entries");
    var production = entries.get(1);
    assertEquals(
        Set.of("kind", "id", "name", "open", "entries"), Set.copyOf(production.propertyNames()));
    assertEquals("folder", production.get("kind").stringValue());
    assertTrue(production.get("open").booleanValue());
    assertFalse(production.get("entries").get(0).get("open").booleanValue());
    var fields =
        Set.of("kind", "id", "name", "provider", "host", "port", "database", "user", "tls");
    var withColor = new HashSet<>(fields);
    withColor.add("color");
    var first = production.get("entries").get(0).get("entries").get(0);
    assertEquals("connection", first.get("kind").stringValue());
    assertEquals(withColor, Set.copyOf(first.propertyNames()));
    var withDriver = new HashSet<>(fields);
    withDriver.add("driverVersion");
    var second = production.get("entries").get(2);
    assertEquals(withDriver, Set.copyOf(second.propertyNames()), "no color, no field");
  }

  @Test
  void providerAndTlsAreStoredByNamesOfTheirOwn() throws IOException {
    new WorkspaceStore(folder).save(flat(PROFILES));
    var stored = new ArrayList<String>();
    for (var profile : entries()) {
      stored.add(
          profile.get("provider").stringValue() + " " + profile.get("tls").stringValue());
    }
    assertEquals(List.of("postgresql off", "mysql encrypted", "mariadb verified"), stored);
    var colors = new ArrayList<String>();
    for (var color : Color.values()) {
      new WorkspaceStore(folder).save(flat(List.of(PROFILES.get(1).withColor(color))));
      colors.add(entries().get(0).get("color").stringValue());
      assertEquals(
          color, new WorkspaceStore(folder).load().connections().getFirst().color());
    }
    assertEquals(
        List.of(
            "blue", "violet", "purple", "pink", "red", "orange", "amber", "green", "teal", "gray"),
        colors);
  }

  private JsonNode entries() throws IOException {
    var text = Files.readString(folder.resolve(WorkspaceStore.FILE));
    return JsonMapper.builder().build().readTree(text).get("entries");
  }

  @Test
  void aConnectionWithoutColorOrDriverVersionLoadsWithNeither() throws IOException {
    write(VALID.getBytes(UTF_8));
    var profile = new WorkspaceStore(folder).load().connections().getFirst();
    assertEquals(null, profile.color());
    assertEquals(null, profile.driverVersion());
  }

  @Test
  void unknownFieldsOfTheSameVersionAreIgnored() throws IOException {
    write(VALID
        .replace("\"version\": 1", "\"version\": 1, \"theme\": {\"dark\": true}")
        .replace("\"tls\": \"off\"", "\"tls\": \"off\", \"pinned\": \"yes\"")
        .replace("\"open\": true", "\"open\": true, \"icon\": 3")
        .getBytes(UTF_8));
    var workspace = new WorkspaceStore(folder).load();
    assertEquals(1, workspace.connections().size());
    assertEquals("Local", workspace.connections().getFirst().name());
    var work = (Workspace.Folder) workspace.entries().getFirst();
    assertEquals("Work", work.name());
    assertTrue(work.open());
  }

  /**
   * Nested as deep as the JSON parser takes, some 250 folders: deeper, a save fails and leaves the
   * file, and never writes one that a load would refuse.
   */
  @Test
  void foldersNestAsDeepAsTheFileCanHold() throws IOException {
    var store = new WorkspaceStore(folder);
    int saved = 0;
    for (int depth = 240; depth <= 260; depth++) {
      var before = Files.exists(folder.resolve(WorkspaceStore.FILE))
          ? Files.readAllBytes(folder.resolve(WorkspaceStore.FILE))
          : null;
      var workspace = nested(depth);
      try {
        store.save(workspace);
      } catch (RuntimeException tooDeep) {
        assertArrayEquals(before, Files.readAllBytes(folder.resolve(WorkspaceStore.FILE)));
        continue;
      }
      assertEquals(workspace, store.load(), depth + " folders deep");
      saved = depth;
    }
    assertTrue(saved >= Workspace.DEPTH && saved < 260, "saved up to " + saved);
  }

  private static Workspace nested(int depth) {
    Workspace.Entry entry = PROFILES.getFirst();
    for (int i = 0; i < depth; i++) {
      entry = new Workspace.Folder(UUID.randomUUID(), "f" + i, i % 2 == 0, List.of(entry));
    }
    return new Workspace(List.of(entry));
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
        broken(VALID.replace("\"entries\": [{", "\"other\": [{"), "\"entries\" is missing"),
        broken(VALID.replace(FOLDER, "1"), "entry 1 is not a JSON object"),
        broken(VALID.replace(CONNECTION, "1"), "entry 1.1 is not a JSON object"),
        broken(VALID.replace("\"kind\": \"folder\",", ""), "entry 1 has no \"kind\""),
        broken(VALID.replace("\"folder\"", "\"group\""), "in entry 1, \"kind\" is not \"fo"),
        broken(VALID.replace("\"connection\"", "\"Connection\""), "in entry 1.1, \"kind\" is"),
        broken(VALID.replace("\"connection\"", "7"), "in entry 1.1, \"kind\" is not text"),
        broken(VALID.replace("\"name\": \"Work\",", ""), "entry 1 has no \"name\""),
        broken(VALID.replace("\"Work\"", "null"), "in entry 1, \"name\" is not text"),
        broken(VALID.replace("\"open\": true,", ""), "entry 1 has no \"open\""),
        broken(VALID.replace("true", "\"true\""), "in entry 1, \"open\" is not true or false"),
        broken(VALID.replace("true", "1"), "in entry 1, \"open\" is not true or false"),
        broken(VALID.replace("\"entries\": [{\"kind\"", "\"items\": [{\"kind\""), "has no \"entr"),
        broken(VALID.replace("[" + CONNECTION + "]", "{}"), "\"entries\" is not an array"),
        broken(VALID.replace("7c9e6679-7425-40de", "nope"), "in entry 1, \"id\" is not a UUID"),
        broken(VALID.replace("\"host\": \"localhost\",", ""), "entry 1.1 has no \"host\""),
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
        broken(VALID.replace("\"off\"", "\"off\", \"color\": \"crimson\""), "\"color\" is not a"),
        broken(VALID.replace("\"off\"", "\"off\", \"color\": \"RED\""), "\"color\" is not a"),
        broken(VALID.replace("\"off\"", "\"off\", \"color\": null"), "\"color\" is not text"),
        broken(
            VALID.replace("\"off\"", "\"off\", \"driverVersion\": \"../1\""),
            "\"driverVersion\" is not a version"),
        broken(
            VALID.replace("\"off\"", "\"off\", \"driverVersion\": 42"),
            "\"driverVersion\" is not text"),
        broken(
            VALID.replace(CONNECTION, CONNECTION + "," + CONNECTION),
            "entry 1.2 has the id 0f8fad5b-d9cb-469f-a165-70867728950e of an earlier one"),
        broken(
            VALID.replace("[" + FOLDER, "[" + CONNECTION + "," + FOLDER), "entry 2.1 has the id"),
        // A folder and a connection share it, the folder's own entry too.
        broken(
            VALID.replace(
                "0f8fad5b-d9cb-469f-a165-70867728950e", "7c9e6679-7425-40de-944b-e07fc1f90ae7"),
            "entry 1.1 has the id"),
        broken(VALID.replace("]}", "," + FOLDER.replace(CONNECTION, "") + "]}"), "entry 2 has"),
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
        assertThrows(WorkspaceStore.UnreadableException.class, new WorkspaceStore(folder)::load);
    assertTrue(failure.getMessage().contains(why), failure.getMessage());
    assertTrue(failure.getMessage().contains(file.toString()), failure.getMessage());
    assertEquals(file, failure.file());
    assertEquals(WorkspaceStore.UNREADABLE, WorkspaceStore.kept(failure));
    assertArrayEquals(content, Files.readAllBytes(file));
  }

  @Test
  void saveKeepsACopyOfABrokenFile() throws IOException {
    var content = VALID.replace("\"version\": 1", "\"version\": 2").getBytes(UTF_8);
    write(content);
    var store = new WorkspaceStore(folder);
    store.save(WORKSPACE);
    assertEquals(WORKSPACE, store.load());
    assertArrayEquals(content, Files.readAllBytes(folder.resolve(WorkspaceStore.UNREADABLE)));
  }

  @Test
  void aMessageNeverRepeatsWhatTheFileHolds() throws IOException {
    var broken = List.of(
        VALID.replace("\"version\": 1", "\"version\": \"s3cret\""),
        VALID.replace("0f8fad5b-d9cb-469f", "s3cret"),
        VALID.replace("postgresql", "s3cret"),
        VALID.replace("\"folder\"", "\"s3cret\""),
        VALID.replace("\"Work\"", "\"s3cret\"").replace("true", "\"s3cret\""),
        VALID.replace("\"off\"", "\"off\", \"color\": \"s3cret\""),
        VALID.replace("\"off\"", "\"off\", \"driverVersion\": \"s3cret/\""));
    for (var content : broken) {
      write(content.getBytes(UTF_8));
      var failure =
          assertThrows(WorkspaceStore.UnreadableException.class, new WorkspaceStore(folder)::load);
      assertFalse(failure.getMessage().contains("s3cret"), failure.getMessage());
    }
  }

  @Test
  void aFileTooLargeIsRefusedUnreadAndKeptOnSave() throws IOException {
    var content = new byte[(int) WorkspaceStore.MAX_SIZE + 1];
    Arrays.fill(content, (byte) ' ');
    write(content);
    var store = new WorkspaceStore(folder);
    var failure = assertThrows(WorkspaceStore.UnreadableException.class, store::load);
    assertTrue(failure.getMessage().contains("larger than 16 MB"), failure.getMessage());
    store.save(WORKSPACE);
    assertEquals(WORKSPACE, store.load());
    assertEquals(content.length, Files.size(folder.resolve(WorkspaceStore.UNREADABLE)));
  }

  @Test
  void aWorkspaceTooLargeToReadIsNotSaved() throws IOException {
    var store = new WorkspaceStore(folder);
    store.save(WORKSPACE);
    var file = folder.resolve(WorkspaceStore.FILE);
    var before = Files.readAllBytes(file);
    var name = "x".repeat(1 << 20);
    var large = new ArrayList<Workspace.Entry>();
    for (int i = 0; i < 17; i++) {
      large.add(ConnectionProfile.of(Provider.MYSQL).withName(name));
    }
    var failure = assertThrows(IOException.class, () -> store.save(new Workspace(large)));
    assertEquals(
        "Cannot save the connections in " + file + ": it would be larger than 16 MB.",
        failure.getMessage());
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void theConnectionsOfAnOlderHooriDbAreTakenAtTheTopInTheirOrder() throws IOException {
    var older = older(OLDER);
    var store = new WorkspaceStore(folder);
    var taken = store.load();
    assertEquals(2, taken.entries().size());
    assertEquals(taken.connections(), taken.entries());
    var first = taken.connections().get(0);
    assertEquals(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"), first.id());
    assertEquals("Local", first.name());
    assertEquals(
        new ConnectionProfile(
            UUID.fromString("a3bb189e-8bf9-3888-9912-ace4e6543002"),
            "Second",
            Provider.MARIADB,
            "db.example",
            3307,
            "x",
            "y",
            Tls.VERIFIED,
            Color.GRAY,
            "3.5.1"),
        taken.connections().get(1));
    var changed = taken.remove(first.id());
    store.save(changed);
    assertEquals(changed, store.load(), "from the workspace now");
    assertEquals(changed, new WorkspaceStore(folder).load());
    assertArrayEquals(older, Files.readAllBytes(folder.resolve(WorkspaceStore.CONNECTIONS)));
    try (var files = Files.list(folder)) {
      assertEquals(
          Set.of(folder.resolve(WorkspaceStore.FILE), folder.resolve(WorkspaceStore.CONNECTIONS)),
          Set.copyOf(files.toList()));
    }
  }

  /** The older list could bring back connections deleted since, and lose those added since. */
  @Test
  void aWorkspaceThatCannotBeReadIsNeverReplacedByTheOlderList() throws IOException {
    var older = older(OLDER);
    var content = "{".getBytes(UTF_8);
    write(content);
    var store = new WorkspaceStore(folder);
    var failure = assertThrows(WorkspaceStore.UnreadableException.class, store::load);
    assertEquals(folder.resolve(WorkspaceStore.FILE), failure.file());
    store.save(WORKSPACE);
    assertEquals(WORKSPACE, store.load());
    assertArrayEquals(content, Files.readAllBytes(folder.resolve(WorkspaceStore.UNREADABLE)));
    assertArrayEquals(older, Files.readAllBytes(folder.resolve(WorkspaceStore.CONNECTIONS)));
  }

  @Test
  void aWorkspaceThatIsThereIsReadAndTheOlderListNot() throws IOException {
    older(OLDER);
    new WorkspaceStore(folder).save(Workspace.EMPTY);
    assertEquals(Workspace.EMPTY, new WorkspaceStore(folder).load());
  }

  @Test
  void aWorkspaceThatFailsToReadIsNeverReplacedByTheOlderList() throws IOException {
    older(OLDER);
    Files.createDirectories(folder.resolve(WorkspaceStore.FILE));
    failure(new WorkspaceStore(folder)::load, "it is a folder, not a file");
  }

  /** It is not set aside: no save writes it, so it stays as it is. */
  @Test
  void anOlderListThatCannotBeReadStaysAsItIs() throws IOException {
    var older = older(OLDER.replace("\"verified\"", "\"strict\""));
    var store = new WorkspaceStore(folder);
    var failure = assertThrows(WorkspaceStore.UnreadableException.class, store::load);
    var file = folder.resolve(WorkspaceStore.CONNECTIONS);
    assertEquals(
        "Cannot read the connections in " + file + ": in profile 2, \"tls\" is not \"off\","
            + " \"encrypted\" or \"verified\".",
        failure.getMessage());
    assertEquals(file, failure.file());
    assertEquals(WorkspaceStore.CONNECTIONS, WorkspaceStore.kept(failure));
    store.save(WORKSPACE);
    assertEquals(WORKSPACE, store.load());
    assertArrayEquals(older, Files.readAllBytes(file));
    assertFalse(Files.exists(folder.resolve(WorkspaceStore.UNREADABLE)));
  }

  @Test
  void anOlderListWithTwoProfilesOfOneIdCannotBeRead() throws IOException {
    older("{\"version\": 1, \"profiles\": [" + PROFILE + "," + PROFILE + "]}");
    var failure =
        assertThrows(WorkspaceStore.UnreadableException.class, new WorkspaceStore(folder)::load);
    assertTrue(failure.getMessage().contains("profile 2 has the id"), failure.getMessage());
  }

  private byte[] older(String text) throws IOException {
    var bytes = text.getBytes(UTF_8);
    Files.write(folder.resolve(WorkspaceStore.CONNECTIONS), bytes);
    return bytes;
  }

  private Path write(byte[] content) throws IOException {
    return Files.write(folder.resolve(WorkspaceStore.FILE), content);
  }

  /** A failure of the file system: not unreadable, its cause kept, in HooriDB's words. */
  private static IOException failure(Executable call, String says) {
    var failure = assertThrows(IOException.class, call);
    assertFalse(failure instanceof WorkspaceStore.UnreadableException, failure.getMessage());
    assertTrue(failure.getMessage().contains(says), failure.getMessage());
    assertTrue(failure.getCause() instanceof IOException, failure.getMessage());
    return failure;
  }

  @Test
  void aFolderInPlaceOfTheFileIsSaidSo(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve(WorkspaceStore.FILE));
    var store = new WorkspaceStore(folder);
    var file = folder.resolve(WorkspaceStore.FILE);
    var read = failure(store::load, "it is a folder, not a file");
    assertEquals(
        "Cannot read the connections in " + file + ": it is a folder, not a file.",
        read.getMessage());
    failure(() -> store.save(WORKSPACE), "Cannot save the connections in " + file);
  }

  @Test
  void aFileInPlaceOfTheFolderIsSaidSo(@TempDir Path folder) throws IOException {
    var blocking = Files.writeString(folder.resolve("blocking"), "");
    var store = new WorkspaceStore(blocking.resolve("hooridb"));
    assertEquals(Workspace.EMPTY, store.load(), "there is no file below a file");
    failure(() -> store.save(WORKSPACE), blocking + " is a file, not a folder");
  }

  @Test
  void deniedAccessIsSaidSo(@TempDir Path folder) throws IOException {
    assumeTrue(
        folder.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "permissions are set the POSIX way");
    var store = new WorkspaceStore(folder);
    store.save(WORKSPACE);
    var file = folder.resolve(WorkspaceStore.FILE);
    Files.setPosixFilePermissions(file, Set.of());
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isReadable(file), "an administrator may read any file");
      failure(store::load, "access was denied");
      failure(() -> store.save(WORKSPACE), "access was denied");
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }
}

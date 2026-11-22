package dev.hoori.db.core.connection;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsStoreTest {

  @TempDir
  Path folder;

  @Test
  void roundTripLeavesDefaultsOutOfTheFile() throws IOException {
    var store = new SettingsStore(folder);
    assertEquals(
        new SettingsStore.Loaded(Settings.DEFAULT, List.of()),
        store.load(),
        "no file is the defaults");

    var changed = Settings.DEFAULT
        .withLook(Settings.Look.DARK)
        .withAccent(Color.TEAL)
        .withDensity(Settings.Density.COMPACT)
        .withHighlightOccurrences(false)
        .withKeywordCase(Settings.KeywordCase.LOWER)
        .withCheckForUpdates(false)
        .withConnectTimeout(Duration.ofSeconds(45))
        .withNewConnectionsReadOnly(true)
        .withCommitMode(Settings.CommitMode.MANUAL)
        .withTimeZone(ZoneId.of("Europe/Berlin"))
        .withCopyNull(Settings.CopyNull.BACKSLASH_N)
        .withDocumentView(Settings.DocumentView.JSON)
        .withKeepHistory(false)
        .withMonitorInterval(30);
    store.save(changed);
    assertEquals(new SettingsStore.Loaded(changed, List.of()), new SettingsStore(folder).load());
    assertEquals("""
        {
          "version" : 1,
          "theme" : "dark",
          "accent" : "teal",
          "density" : "compact",
          "highlightOccurrences" : false,
          "keywordCase" : "lower",
          "checkForUpdates" : false,
          "connectTimeoutSeconds" : 45,
          "newConnectionsReadOnly" : true,
          "commitMode" : "manual",
          "timeZone" : "Europe/Berlin",
          "copyNull" : "backslashN",
          "documentView" : "json",
          "keepHistory" : false,
          "monitorIntervalSeconds" : 30
        }""", readNormalized());

    var partial = Settings.DEFAULT
        .withLook(Settings.Look.LIGHT)
        .withDensity(Settings.Density.COMFORTABLE)
        .withCopyNull(Settings.CopyNull.NULL)
        .withDocumentView(Settings.DocumentView.TREE);
    store.save(partial);
    assertEquals(new SettingsStore.Loaded(partial, List.of()), store.load());
    assertEquals("""
        {
          "version" : 1,
          "theme" : "light",
          "density" : "comfortable",
          "copyNull" : "null",
          "documentView" : "tree"
        }""", readNormalized());

    store.save(Settings.DEFAULT);
    assertEquals(new SettingsStore.Loaded(Settings.DEFAULT, List.of()), store.load());
    assertEquals("{\n  \"version\" : 1\n}", readNormalized());
  }

  @Test
  void unknownKeysAreIgnored() throws IOException {
    Files.writeString(folder.resolve(SettingsStore.FILE), """
        {
          "version": 1,
          "theme": "dark",
          "futureFlag": true,
          "futureNested": {"a": [1, 2, 3]},
          "futureNull": null
        }
        """);
    var loaded = new SettingsStore(folder).load();
    assertEquals(Settings.DEFAULT.withLook(Settings.Look.DARK), loaded.settings());
    assertEquals(List.of(), loaded.fellBack());
  }

  @Test
  void badValuesFallBackToDefaultAndAreNamedByKey() throws IOException {
    Files.writeString(folder.resolve(SettingsStore.FILE), """
        {
          "version": 1,
          "theme": "sepia",
          "accent": 42,
          "density": null,
          "highlightOccurrences": "yes",
          "keywordCase": "UPPER",
          "checkForUpdates": 0,
          "connectTimeoutSeconds": 301,
          "newConnectionsReadOnly": "false",
          "commitMode": "deferred",
          "timeZone": "Mars/Olympus",
          "copyNull": "backslashn",
          "documentView": ["grid"],
          "keepHistory": false,
          "monitorIntervalSeconds": 15
        }
        """);
    var loaded = new SettingsStore(folder).load();
    assertEquals(Settings.DEFAULT.withKeepHistory(false), loaded.settings());
    assertEquals(
        List.of(
            "theme",
            "accent",
            "density",
            "highlightOccurrences",
            "keywordCase",
            "checkForUpdates",
            "connectTimeoutSeconds",
            "newConnectionsReadOnly",
            "commitMode",
            "timeZone",
            "copyNull",
            "documentView",
            "monitorIntervalSeconds"),
        loaded.fellBack());
    for (var key : loaded.fellBack()) {
      assertFalse(key.contains("sepia") || key.contains("Mars") || key.contains("301"), key);
    }

    Files.writeString(folder.resolve(SettingsStore.FILE), """
        {
          "version": 1,
          "connectTimeoutSeconds": 10.0,
          "timeZone": null,
          "monitorIntervalSeconds": 0
        }
        """);
    var more = new SettingsStore(folder).load();
    assertEquals(Settings.DEFAULT, more.settings());
    assertEquals(
        List.of("connectTimeoutSeconds", "timeZone", "monitorIntervalSeconds"), more.fellBack());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "[]",
        "{}",
        "{\"version\": \"1\"}",
        "{\"version\": 0}",
        "{\"version\": 2}",
        "{\"version\": 1, \"theme\": \"dark\", \"theme\": \"light\"}"
      })
  void aBrokenOrNewerFileIsUnreadableAndSetAsideOnNextSave(String content) throws IOException {
    var file = Files.writeString(folder.resolve(SettingsStore.FILE), content);
    var store = new SettingsStore(folder);
    var failure = assertThrows(WorkspaceStore.UnreadableException.class, store::load);
    assertTrue(
        failure.getMessage().startsWith("Cannot read the settings in " + file),
        failure.getMessage());
    assertArrayEquals(content.getBytes(UTF_8), Files.readAllBytes(file));

    var saved = Settings.DEFAULT.withLook(Settings.Look.DARK);
    store.save(saved);
    assertEquals(new SettingsStore.Loaded(saved, List.of()), store.load());
    assertEquals(content, Files.readString(folder.resolve(SettingsStore.UNREADABLE)));
  }

  @Test
  void theFileAndSetAsideCopyAreOwnerOnly() throws IOException {
    assumeTrue(folder.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(folder.resolve(SettingsStore.FILE), "{\"version\": 2}");
    new SettingsStore(folder).save(Settings.DEFAULT.withLook(Settings.Look.DARK));
    for (var name : List.of(SettingsStore.FILE, SettingsStore.UNREADABLE)) {
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve(name))),
          name);
    }
  }

  @Test
  void aFileTooLargeIsRefusedUnread() throws IOException {
    try (var file = new RandomAccessFile(folder.resolve(SettingsStore.FILE).toFile(), "rw")) {
      file.setLength(SettingsStore.MAX_SIZE + 1);
    }
    var failure =
        assertThrows(WorkspaceStore.UnreadableException.class, new SettingsStore(folder)::load);
    assertTrue(failure.getMessage().contains("larger than 1 MB"), failure.getMessage());
  }

  @Test
  void recordRejectsInvalidValues() {
    for (var timeout : List.of(Duration.ZERO, Duration.ofSeconds(301), Duration.ofMillis(1500))) {
      assertThrows(
          IllegalArgumentException.class, () -> Settings.DEFAULT.withConnectTimeout(timeout));
    }
    assertThrows(IllegalArgumentException.class, () -> Settings.DEFAULT.withMonitorInterval(3));
    assertThrows(NullPointerException.class, () -> Settings.DEFAULT.withLook(null));
    assertThrows(NullPointerException.class, () -> Settings.DEFAULT.withAccent(null));
  }

  private String readNormalized() throws IOException {
    return Files.readString(folder.resolve(SettingsStore.FILE))
        .replace(System.lineSeparator(), "\n");
  }
}

package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UiStateStoreTest {

  @TempDir
  Path folder;

  @Test
  void roundTripWritesOnlyStoredFields() throws IOException {
    var store = new UiStateStore(folder);
    assertEquals(UiState.EMPTY, store.load(), "no file is empty");

    var state = UiState.EMPTY
        .withWindow("normal 100 80 1280 800")
        .withSidebar(320.0f)
        .withLogOpen(true)
        .withLogHeight(220.0f)
        .withLogDetail(0.6f);
    store.save(state);
    assertEquals(state, new UiStateStore(folder).load());
    assertEquals("""
        {
          "version" : 1,
          "window" : "normal 100 80 1280 800",
          "sidebar" : 320.0,
          "statementLog" : {
            "open" : true,
            "height" : 220.0,
            "detail" : 0.6
          }
        }""", readNormalized());

    var onlySidebar = UiState.EMPTY.withSidebar(280.0f);
    store.save(onlySidebar);
    assertEquals(onlySidebar, store.load());
    assertEquals("""
        {
          "version" : 1,
          "sidebar" : 280.0
        }""", readNormalized());

    store.save(UiState.EMPTY);
    assertEquals(UiState.EMPTY, store.load());
    assertEquals("{\n  \"version\" : 1\n}", readNormalized());
  }

  @Test
  void invalidFieldsDropToNullPerField() throws IOException {
    Files.writeString(folder.resolve(UiStateStore.FILE), """
        {
          "version": 1,
          "window": 123,
          "sidebar": -10.0,
          "unknown": "ignored",
          "statementLog": {
            "open": "yes",
            "height": 1e50,
            "detail": 1.2
          }
        }
        """);
    assertEquals(UiState.EMPTY, new UiStateStore(folder).load());

    Files.writeString(folder.resolve(UiStateStore.FILE), """
        {
          "version": 1,
          "window": "maximized",
          "sidebar": 0,
          "statementLog": {
            "open": false,
            "height": -1,
            "detail": 1.0
          }
        }
        """);
    assertEquals(
        new UiState("maximized", 0.0f, false, null, 1.0f), new UiStateStore(folder).load());

    Files.writeString(folder.resolve(UiStateStore.FILE), """
        {
          "version": 1,
          "sidebar": 240,
          "statementLog": "not an object"
        }
        """);
    assertEquals(UiState.EMPTY.withSidebar(240.0f), new UiStateStore(folder).load());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{", "[]", "{}", "{\"version\": 2}", "{\"version\": \"1\"}"})
  void aBrokenOrNewerFileFallsBackSilentlyToEmpty(String content) throws IOException {
    Files.writeString(folder.resolve(UiStateStore.FILE), content);
    var store = new UiStateStore(folder);
    assertEquals(UiState.EMPTY, store.load());

    var state = UiState.EMPTY.withLogOpen(true);
    store.save(state);
    assertEquals(state, store.load());
  }

  @Test
  void oversizedOrUnreadableFileFallsBackSilentlyToEmpty() throws IOException {
    try (var file = new RandomAccessFile(folder.resolve(UiStateStore.FILE).toFile(), "rw")) {
      file.setLength(UiStateStore.MAX_SIZE + 1);
    }
    var store = new UiStateStore(folder);
    assertEquals(UiState.EMPTY, store.load());

    Files.delete(folder.resolve(UiStateStore.FILE));
    Files.createDirectory(folder.resolve(UiStateStore.FILE));
    assertEquals(UiState.EMPTY, store.load(), "IOException falls back silently");
    assertThrows(IOException.class, () -> store.save(UiState.EMPTY));
  }

  @Test
  void recordRejectsInvalidNumbers() {
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withSidebar(-1f));
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withSidebar(Float.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> UiState.EMPTY.withSidebar(Float.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withLogHeight(-0.1f));
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withLogDetail(-0.01f));
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withLogDetail(1.01f));
    assertThrows(IllegalArgumentException.class, () -> UiState.EMPTY.withLogDetail(Float.NaN));
  }

  private String readNormalized() throws IOException {
    return Files.readString(folder.resolve(UiStateStore.FILE))
        .replace(System.lineSeparator(), "\n");
  }
}

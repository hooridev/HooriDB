package dev.hoori.db.core.connection;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConsoleStoreTest {

  static final List<StoredConsole> CONSOLES = List.of(
      new StoredConsole(UUID.randomUUID(), "shop", "SELECT 1;\n-- \"ünïcode\" 東京 𝄞\tend"),
      new StoredConsole(UUID.randomUUID(), "", ""));

  @TempDir
  Path folder;

  @Test
  void savedConsolesComeBackInOrder() throws IOException {
    var store = new ConsoleStore(folder);
    assertEquals(List.of(), store.load(), "no file is no consoles");
    store.save(CONSOLES);
    assertEquals(CONSOLES, store.load());
    store.save(CONSOLES.reversed());
    assertEquals(CONSOLES.reversed(), new ConsoleStore(folder).load());
  }

  @Test
  void theFileIsTheOwnersAloneAndSoIsTheCopyOfABrokenOne() throws IOException {
    assumeTrue(folder.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.writeString(folder.resolve(ConsoleStore.FILE), "{\"version\": 1}");
    new ConsoleStore(folder).save(CONSOLES);
    for (var name : List.of(ConsoleStore.FILE, ConsoleStore.UNREADABLE)) {
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.resolve(name))),
          name);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"version\": 1}",
        "{\"version\": 1, \"consoles\": [1]}",
        "{\"version\": 1, \"consoles\": [{\"connection\": \"x\", \"database\": \"\", \"text\": \"\"}]}",
        "{\"version\": 1, \"consoles\": [{\"connection\": \"0f8fad5b-d9cb-469f-a165-70867728950e\","
            + " \"database\": \"\"}]}",
        "{\"version\": 1, \"version\": 1, \"consoles\": []}",
        "{\"version\": 2, \"consoles\": []}"
      })
  void aBrokenFileIsRefusedAndKeptOnSave(String content) throws IOException {
    var file = Files.writeString(folder.resolve(ConsoleStore.FILE), content);
    var store = new ConsoleStore(folder);
    var failure = assertThrows(ProfileStore.UnreadableException.class, store::load);
    assertTrue(
        failure.getMessage().startsWith("Cannot read the consoles in " + file),
        failure.getMessage());
    assertArrayEquals(content.getBytes(UTF_8), Files.readAllBytes(file));
    store.save(CONSOLES);
    assertEquals(CONSOLES, store.load());
    assertEquals(content, Files.readString(folder.resolve(ConsoleStore.UNREADABLE)));
  }

  @Test
  void aFileTooLargeIsRefusedUnread() throws IOException {
    try (var file = new RandomAccessFile(folder.resolve(ConsoleStore.FILE).toFile(), "rw")) {
      file.setLength(ConsoleStore.MAX_SIZE + 1); // sparse: nothing is written
    }
    var failure =
        assertThrows(ProfileStore.UnreadableException.class, new ConsoleStore(folder)::load);
    assertTrue(failure.getMessage().contains("larger than 64 MB"), failure.getMessage());
  }
}

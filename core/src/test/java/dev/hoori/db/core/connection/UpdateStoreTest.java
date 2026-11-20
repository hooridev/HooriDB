package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.updater.Version;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateStoreTest {

  @TempDir
  Path folder;

  @Test
  void theStoreRoundTripsAndTreatsABrokenFileAsEmpty() throws IOException {
    var store = new UpdateStore(folder);
    assertEquals(StoredUpdates.NONE, store.load(), "no file is nothing stored");
    var updates = new StoredUpdates(
        Channel.NIGHTLY,
        Version.parse("0.3.0"),
        new StoredUpdates.Attempt(
            Version.parse("0.3.1-nightly.202611200300"),
            folder.resolve("downloads").resolve("download-1-HooriDB.tar.gz"),
            "ab".repeat(32)));
    store.save(updates);
    assertEquals(updates, new UpdateStore(folder).load());
    store.save(StoredUpdates.NONE.withChannel(Channel.STABLE));
    assertEquals(StoredUpdates.NONE.withChannel(Channel.STABLE), store.load());
    assertEquals(
        "{\n  \"version\" : 1,\n  \"channel\" : \"stable\"\n}",
        Files.readString(folder.resolve(UpdateStore.FILE)));

    var broken = "{\"version\": 1, \"channel\": \"weekly\"}";
    Files.writeString(folder.resolve(UpdateStore.FILE), broken);
    assertEquals(StoredUpdates.NONE, store.load());
    store.save(updates);
    assertEquals(updates, store.load(), "the next save replaces it");
    assertEquals(broken, Files.readString(folder.resolve(JsonFile.setAside(UpdateStore.FILE))));
  }
}

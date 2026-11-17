package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryStoreTest {

  static final UUID SHOP = UUID.randomUUID();
  static final UUID CRM = UUID.randomUUID();
  static final Instant AT = Instant.parse("2026-11-17T09:30:15.250Z");

  static HistoryEntry entry(UUID connection, String sql, Outcome outcome) {
    return new HistoryEntry(connection, sql, AT, Duration.ofMillis(12), outcome);
  }

  static final List<HistoryEntry> ENTRIES = List.of(
      entry(SHOP, "SELECT * FROM orders", new Outcome.Rows()),
      entry(SHOP, "UPDATE orders SET paid = true\nWHERE id < 4", new Outcome.Changed(3)),
      entry(
          CRM, "SELECT * FROM missing", new Outcome.Failed("relation \"missing\" does not exist")),
      entry(CRM, "SELECT pg_sleep(60) -- 東京 𝄞", new Outcome.Cancelled()));

  @TempDir
  Path folder;

  @Test
  void savedEntriesComeBackInOrderWithTheirOutcomes() throws IOException {
    var store = new HistoryStore(folder);
    assertEquals(List.of(), store.load(), "no file is no history");
    store.save(ENTRIES);
    assertEquals(ENTRIES, new HistoryStore(folder).load());
  }

  @Test
  void aConnectionKeepsItsNewestThousandAndTheOthersTheirs() {
    List<HistoryEntry> history = List.of(entry(CRM, "SELECT 'crm'", new Outcome.Rows()));
    for (int i = 0; i < HistoryStore.LIMIT + 5; i++) {
      history = HistoryStore.add(history, entry(SHOP, "SELECT " + i, new Outcome.Rows()));
    }
    assertEquals(HistoryStore.LIMIT + 1, history.size());
    assertEquals("SELECT 'crm'", history.getFirst().statement());
    assertEquals("SELECT 5", history.get(1).statement(), "the five oldest went");
    assertEquals("SELECT " + (HistoryStore.LIMIT + 4), history.getLast().statement());
  }

  @Test
  void aStatementLongerThanSixtyFourKibibytesIsNotKept() {
    var longest = "x".repeat(HistoryStore.LONGEST - 2) + "ä"; // two bytes in UTF-8
    var kept = HistoryStore.add(List.of(), entry(SHOP, longest, new Outcome.Rows()));
    assertEquals(1, kept.size());
    var longer = longest + "x";
    assertSame(kept, HistoryStore.add(kept, entry(SHOP, longer, new Outcome.Rows())));
  }

  @Test
  void aSaveLeavesOutTheOldestWhereTheFileWouldBeTooLargeToRead() throws IOException {
    var big = "x".repeat(HistoryStore.LONGEST - 16);
    var entries = new ArrayList<HistoryEntry>();
    for (int i = 0; i < 1100; i++) { // about 70 MB
      entries.add(entry(i % 2 == 0 ? SHOP : CRM, i + " " + big, new Outcome.Rows()));
    }
    var store = new HistoryStore(folder);
    store.save(entries);
    assertTrue(Files.size(folder.resolve(HistoryStore.FILE)) <= HistoryStore.MAX_SIZE);
    var loaded = store.load();
    assertTrue(loaded.size() < entries.size() && loaded.size() > 900, "" + loaded.size());
    assertEquals(entries.subList(entries.size() - loaded.size(), entries.size()), loaded);
  }

  @Test
  void theFileIsTheOwnersAlone() throws IOException {
    assumeTrue(folder.getFileSystem().supportedFileAttributeViews().contains("posix"));
    new HistoryStore(folder).save(ENTRIES);
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(folder.resolve(HistoryStore.FILE))));
  }

  static final String VALID = "{\"version\": 1, \"entries\": [{\"connection\":"
      + " \"0f8fad5b-d9cb-469f-a165-70867728950e\", \"statement\": \"SELECT 1\", \"started\":"
      + " \"2026-11-17T09:30:15Z\", \"took\": 4, \"outcome\": \"changed\", \"count\": 3}]}";

  @Test
  void theValidFileOfTheseTestsLoads() throws IOException {
    Files.writeString(folder.resolve(HistoryStore.FILE), VALID);
    assertEquals(
        new Outcome.Changed(3), new HistoryStore(folder).load().getFirst().outcome());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"outcome\": \"changed\"|\"outcome\": \"s3cret\"",
        "\"count\": 3|\"count\": -3",
        "\"count\": 3|\"count\": \"3\"",
        "\"took\": 4|\"took\": 4.5",
        "2026-11-17T09:30:15Z|yesterday",
        "\"statement\": \"SELECT 1\", |",
        "\"entries\"|\"history\""
      })
  void aBrokenFileIsRefusedWithoutRepeatingIt(String change) throws IOException {
    var parts = change.split("\\|", -1);
    var file =
        Files.writeString(folder.resolve(HistoryStore.FILE), VALID.replace(parts[0], parts[1]));
    var failure =
        assertThrows(ProfileStore.UnreadableException.class, new HistoryStore(folder)::load);
    assertTrue(
        failure.getMessage().startsWith("Cannot read the history in " + file),
        failure.getMessage());
    assertTrue(!failure.getMessage().contains("s3cret"), failure.getMessage());
  }
}

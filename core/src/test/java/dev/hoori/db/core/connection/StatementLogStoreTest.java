package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatementLogStoreTest {

  private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant AT = Instant.parse("2026-11-20T10:15:30.123456Z");

  @TempDir
  Path folder;

  private static Logged entry(int n, Outcome outcome) {
    return new Logged(
        ONE,
        new Sent(
            AT.plusSeconds(n),
            Purpose.values()[n % Purpose.values().length],
            n % 2 == 0 ? "hoori" : null,
            "SELECT " + n + "\nFROM \"t\"",
            Duration.ofNanos(1_234_567L * n),
            outcome));
  }

  @Test
  void entriesComeBackAsTheyWereAppendedTheOldestFirst() throws Exception {
    var store = new StatementLogStore(folder);
    assertEquals(List.of(), store.recent(10));
    var first = List.of(
        entry(0, new Outcome.Rows(100, true)),
        entry(1, new Outcome.Changed(3)),
        entry(2, Outcome.DONE));
    var second =
        List.of(entry(3, new Outcome.Failed("no such table")), entry(4, new Outcome.Cancelled()));
    store.append(first);
    store.append(second);
    var all = new ArrayList<>(first);
    all.addAll(second);
    assertEquals(all, store.recent(10));
    assertEquals(all.subList(3, 5), store.recent(2));
    assertEquals(
        5, Files.readAllLines(folder.resolve(StatementLogStore.FILE)).size(), "one line each");
  }

  @Test
  void pastTheLimitTheFileBecomesTheOlderOneAndOnlyOneOlderIsKept() throws Exception {
    var store = new StatementLogStore(folder);
    var big = "x".repeat(1 << 20);
    var entries = new ArrayList<Logged>();
    for (int i = 0; i < 20; i++) {
      var e = entry(i, Outcome.DONE);
      e = new Logged(
          e.connection(),
          new Sent(
              e.sent().at(),
              e.sent().purpose(),
              e.sent().database(),
              i + big,
              e.sent().took(),
              e.sent().outcome()));
      entries.add(e);
      store.append(List.of(e));
      assertTrue(Files.size(folder.resolve(StatementLogStore.FILE)) <= StatementLogStore.LIMIT);
    }
    assertTrue(Files.exists(folder.resolve(StatementLogStore.OLDER)));
    try (var files = Files.list(folder)) {
      assertEquals(2, files.count(), "the file and one older");
    }
    var recent = store.recent(100);
    assertEquals(entries.getLast(), recent.getLast());
    assertTrue(recent.size() < entries.size(), "the oldest went");
    assertTrue(recent.size() > 7, "both files are read: " + recent.size());
  }

  @Test
  void aLineThatCannotBeUnderstoodIsSkipped() throws Exception {
    var store = new StatementLogStore(folder);
    store.append(List.of(entry(0, Outcome.DONE)));
    Files.writeString(
        folder.resolve(StatementLogStore.FILE),
        "{\"connection\": \"nope\"}\n[1]\n{\"conn",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    store.append(List.of(entry(1, Outcome.DONE)));
    assertEquals(List.of(entry(0, Outcome.DONE), entry(1, Outcome.DONE)), store.recent(10));
  }

  @Test
  void clearingDeletesBothFiles() throws Exception {
    var store = new StatementLogStore(folder);
    store.append(List.of(entry(0, Outcome.DONE)));
    Files.writeString(folder.resolve(StatementLogStore.OLDER), "");
    store.clear();
    assertFalse(Files.exists(folder.resolve(StatementLogStore.FILE)));
    assertFalse(Files.exists(folder.resolve(StatementLogStore.OLDER)));
    assertEquals(List.of(), store.recent(10));
    store.clear(); // nothing to delete
  }

  @Test
  void loggingIsOnUntilTurnedOff() throws Exception {
    var store = new StatementLogStore(folder);
    assertTrue(store.enabled());
    store.enable(false);
    assertFalse(new StatementLogStore(folder).enabled());
    store.enable(true);
    assertTrue(store.enabled());
    Files.writeString(folder.resolve(StatementLogStore.SETTINGS), "not json");
    assertTrue(store.enabled(), "settings it cannot understand leave it on");
  }

  @Test
  void theFilesAreTheOwnersAlone() throws Exception {
    assumeTrue(folder.getFileSystem().supportedFileAttributeViews().contains("posix"));
    var store = new StatementLogStore(folder);
    store.append(List.of(entry(0, Outcome.DONE)));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(folder.resolve(StatementLogStore.FILE))));
  }
}

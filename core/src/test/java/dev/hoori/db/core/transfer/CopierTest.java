package dev.hoori.db.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.transfer.Report.Stage;
import dev.hoori.db.core.transfer.Report.State;
import dev.hoori.db.core.transfer.Transfer.Commit;
import dev.hoori.db.core.transfer.Transfer.Copy;
import dev.hoori.db.core.transfer.Transfer.Existing;
import dev.hoori.db.core.transfer.Transfer.OnError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The run's course on a made-up source and target, which note what is asked of them. */
class CopierTest {

  private static final Database FROM = new Database("from", false);
  private static final Database INTO = new Database("into", false);

  private final List<String> calls = new CopyOnWriteArrayList<>();
  private final Map<String, List<List<Object>>> sourceRows = new HashMap<>();
  private final Map<String, List<List<Object>>> targetRows = new HashMap<>();
  private final List<Pending> pending = new ArrayList<>();
  private final Map<String, Exception> failing = new HashMap<>();
  private final List<ForeignKey> keys = new ArrayList<>();
  private CountDownLatch holding; // a read of the second batch waits for it, where set
  private CountDownLatch held = new CountDownLatch(1);
  private boolean readOnly;

  // A row written and not committed yet.
  private record Pending(String table, List<Object> row) {}

  @Test
  void skipGoesOnAfterAFailedTableAndSaysWhy() {
    rows("x", 3);
    rows("y", 2);
    failing.put("write x", QueryException.of(QueryException.Failure.OTHER, "duplicate key"));

    var report = run(transfer(OnError.SKIP, Commit.TABLE, 10, "x", "y"));

    assertEquals(Stage.DONE, report.stage());
    assertEquals(List.of(State.FAILED, State.COPIED), states(report));
    assertEquals("duplicate key", report.tables().getFirst().problem());
    assertEquals(2, targetRows.get("y").size());
    assertTrue(calls.contains("rollback"), calls.toString());
    assertEquals("end", calls.getLast());
  }

  @Test
  void stopSkipsTheTablesAfter() {
    rows("x", 3);
    rows("y", 2);
    failing.put("write x", QueryException.of(QueryException.Failure.OTHER, "too long"));

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x", "y"));

    assertEquals(Stage.STOPPED, report.stage());
    assertEquals(List.of(State.FAILED, State.SKIPPED), states(report));
    assertTrue(!targetRows.containsKey("y"));
  }

  @Test
  void aProblemFoundBeforeTheRunStopsItBeforeAnythingIsWritten() {
    rows("x", 3);
    rows("y", 2);
    targetRows.put("y", List.of(List.of("old")));

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x", "y"));

    assertEquals(Stage.STOPPED, report.stage());
    assertEquals(List.of(State.SKIPPED, State.FAILED), states(report));
    assertTrue(calls.stream().noneMatch(call -> call.startsWith("write")), calls.toString());
  }

  @Test
  void tablesAreEmptiedReferringFirstAndCopiedReferredFirst() {
    rows("parent", 1);
    rows("child", 1);
    targetRows.put("parent", new ArrayList<>(List.of(List.of("old"))));
    targetRows.put("child", new ArrayList<>(List.of(List.of("old"))));
    keys.add(new ForeignKey(
        "fk", into("child"), List.of("v"), into("parent"), List.of("v"), null, null));

    var report = run(new Transfer(
        List.of(
            new Copy(from("child"), into("child"), Existing.EMPTY),
            new Copy(from("parent"), into("parent"), Existing.EMPTY)),
        Commit.TABLE,
        OnError.STOP,
        10));

    assertEquals(Stage.DONE, report.stage());
    assertEquals(
        List.of("empty child", "empty parent", "write parent", "write child"),
        calls.stream()
            .filter(c -> c.startsWith("empty") || c.startsWith("write"))
            .toList());
    assertEquals(List.of(List.of("parent 0")), targetRows.get("parent"));
  }

  @Test
  void aCommitPerBatchKeepsTheBatchesBeforeAFailure() {
    rows("x", 25);
    failing.put("write x 2", QueryException.of(QueryException.Failure.OTHER, "check"));

    var report = run(transfer(OnError.STOP, Commit.BATCH, 10, "x"));

    var x = report.tables().getFirst();
    assertEquals(State.FAILED, x.state());
    assertEquals(10, x.written());
    assertEquals(10, x.kept());
    assertEquals(10, targetRows.get("x").size());
  }

  @Test
  void aCancelRollsBackAndCancelsTheRest() throws Exception {
    rows("x", 25);
    rows("y", 2);
    holding = new CountDownLatch(1);
    var told = new CopyOnWriteArrayList<Report>();
    var result = new AtomicReference<Report>();
    var interrupted = new AtomicReference<Boolean>();
    var runner = Thread.ofVirtual().start(() -> {
      result.set(Copier.run(
          transfer(OnError.STOP, Commit.TABLE, 10, "x", "y"), source(), target(), told::add));
      interrupted.set(Thread.currentThread().isInterrupted());
    });
    held.await();
    runner.interrupt();
    runner.join();

    var report = result.get();
    assertEquals(Stage.CANCELLED, report.stage());
    assertEquals(List.of(State.CANCELLED, State.CANCELLED), states(report));
    assertEquals(10, report.tables().getFirst().written());
    assertEquals(0, report.tables().getFirst().kept());
    assertTrue(!targetRows.containsKey("x"), targetRows.toString());
    assertEquals(List.of("rollback", "end"), calls.subList(calls.size() - 2, calls.size()));
    assertTrue(interrupted.get());
    assertEquals(report, told.getLast());
  }

  @Test
  void aReadOnlyTargetIsRefusedBeforeAnythingIsAsked() {
    rows("x", 1);
    readOnly = true;

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x"));

    assertEquals(Stage.REFUSED, report.stage());
    assertTrue(report.problem().contains("read-only"));
    assertEquals(List.of(), calls);
  }

  @Test
  void largeRowsAreReadFewerAtATime() {
    var large = "x".repeat(1024 * 1024); // 2 MB a row, as it is counted
    sourceRows.put(
        "x",
        new ArrayList<>(
            IntStream.range(0, 12).mapToObj(i -> List.<Object>of(large)).toList()));

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x"));

    assertEquals(State.COPIED, report.tables().getFirst().state());
    assertEquals(
        List.of("read x 0 10", "read x 10 4"),
        calls.stream().filter(c -> c.startsWith("read")).toList());
  }

  private Report run(Transfer transfer) {
    return Copier.run(transfer, source(), target(), told -> {});
  }

  private static List<State> states(Report report) {
    return report.tables().stream().map(Report.Entry::state).toList();
  }

  private void rows(String table, int count) {
    sourceRows.put(
        table,
        new ArrayList<>(IntStream.range(0, count)
            .mapToObj(i -> List.<Object>of(table + " " + i))
            .toList()));
  }

  private static Transfer transfer(OnError onError, Commit commit, int batch, String... tables) {
    return new Transfer(
        Arrays.stream(tables)
            .map(name -> new Copy(from(name), into(name), Existing.STOP))
            .toList(),
        commit,
        onError,
        batch);
  }

  private static Table from(String name) {
    return new Table(FROM, name, Table.Kind.TABLE);
  }

  private static Table into(String name) {
    return new Table(INTO, name, Table.Kind.TABLE);
  }

  private static List<Column> columns(Table table) {
    return List.of(new Column(table, "v", "text", true, 0));
  }

  private void fail(String call) throws Exception {
    var failure = failing.get(call);
    if (failure != null) {
      throw failure;
    }
  }

  private Source source() {
    return new Source() {
      @Override
      public Provider provider() {
        return Provider.POSTGRESQL;
      }

      @Override
      public void prepare() {
        calls.add("prepare source");
      }

      @Override
      public List<Column> columns(Table table) {
        return CopierTest.columns(table);
      }

      @Override
      public long count(Table table) {
        return sourceRows.get(table.name()).size();
      }

      @Override
      public Batch read(Selection selection, Position from, int count) throws Exception {
        var at = from instanceof Position.At(var offset) ? (int) offset : 0;
        calls.add("read " + selection.table().name() + " " + at + " " + count);
        if (holding != null && at > 0) {
          held.countDown();
          holding.await(); // until interrupted
        }
        var all = sourceRows.get(selection.table().name());
        var rows = all.subList(Math.min(at, all.size()), Math.min(at + count, all.size()));
        return new Batch(
            rows, rows.size() < count ? Position.END : new Position.At(at + rows.size()));
      }
    };
  }

  private Target target() {
    return new Target() {
      private int writes;

      @Override
      public Provider provider() {
        return Provider.POSTGRESQL;
      }

      @Override
      public boolean readOnly() {
        return readOnly;
      }

      @Override
      public void prepare() {
        calls.add("prepare target");
      }

      @Override
      public Shape shape(Table table) {
        return new Shape(CopierTest.columns(table), Set.of());
      }

      @Override
      public List<ForeignKey> foreignKeys(Table table) {
        return keys.stream()
            .filter(k -> k.table().equals(table) || k.referenced().equals(table))
            .toList();
      }

      @Override
      public boolean hasRows(Table table) {
        return !targetRows.getOrDefault(table.name(), List.of()).isEmpty();
      }

      @Override
      public void begin() {
        calls.add("begin");
      }

      @Override
      public long empty(Table table) {
        calls.add("empty " + table.name());
        targetRows.put(table.name(), new ArrayList<>());
        return 1;
      }

      @Override
      public long write(Table table, List<String> columns, List<List<Object>> rows)
          throws Exception {
        writes++;
        calls.add("write " + table.name());
        fail("write " + table.name());
        fail("write " + table.name() + " " + writes);
        rows.forEach(row -> pending.add(new Pending(table.name(), row)));
        return rows.size();
      }

      @Override
      public void written(Table table, List<String> columns) {
        calls.add("written " + table.name());
      }

      @Override
      public void commit() {
        calls.add("commit");
        for (var row : pending) {
          targetRows.computeIfAbsent(row.table(), t -> new ArrayList<>()).add(row.row());
        }
        pending.clear();
      }

      @Override
      public void rollback() {
        calls.add("rollback");
        pending.clear();
      }

      @Override
      public void end() {
        calls.add("end");
        pending.clear();
      }
    };
  }
}

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
import java.util.OptionalLong;
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
  private final Map<String, QueryException> failing = new HashMap<>();
  private final List<ForeignKey> keys = new ArrayList<>();
  // The names of a table's columns on each end, by its name, where they are not just v.
  private final Map<String, List<String>> sourceColumns = new HashMap<>();
  private final Map<String, List<String>> targetColumns = new HashMap<>();
  private CountDownLatch holding; // a read of the second batch waits for it, where set
  private CountDownLatch held = new CountDownLatch(1);
  private boolean readOnly;
  private String sourceIdentity = "from server";
  private String targetIdentity = "into server";
  private OptionalLong limit = OptionalLong.empty();

  // A row written, or a table emptied where the row is null, not committed yet.
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
    assertEquals(
        List.of("rollback", "close x", "end"), calls.subList(calls.size() - 3, calls.size()));
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
  void largeRowsAreReadFewerAtATimeFromTheFirstBatchOn() {
    var large = "x".repeat(1024 * 1024); // 2 MB a row, as it is counted
    sourceRows.put(
        "x",
        new ArrayList<>(
            IntStream.range(0, 12).mapToObj(i -> List.<Object>of(large)).toList()));

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x"));

    assertEquals(State.COPIED, report.tables().getFirst().state());
    assertEquals(
        List.of("batch x 4", "batch x 4", "batch x 4"),
        calls.stream().filter(c -> c.startsWith("batch")).toList());
    assertTrue(calls.contains("next x 10 " + Copier.BUDGET), calls.toString());
  }

  @Test
  void aBatchTakesHalfOfWhatAStatementOfTheTargetMayCarry() {
    var large = "x".repeat(1024 * 1024);
    sourceRows.put(
        "x",
        new ArrayList<>(
            IntStream.range(0, 3).mapToObj(i -> List.<Object>of(large)).toList()));
    limit = OptionalLong.of(4L * 1024 * 1024);

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x"));

    assertEquals(State.COPIED, report.tables().getFirst().state());
    assertTrue(calls.contains("next x 10 " + 2L * 1024 * 1024), calls.toString());
    assertEquals(
        List.of("batch x 1", "batch x 1", "batch x 1"),
        calls.stream().filter(c -> c.startsWith("batch")).toList());
  }

  @Test
  void aTableToEmptyKeepsItsRowsWhereItsCopyFails() {
    rows("x", 3);
    rows("y", 2);
    targetRows.put("x", new ArrayList<>(List.of(List.of("old x"))));
    targetRows.put("y", new ArrayList<>(List.of(List.of("old y"))));
    failing.put("write x", QueryException.of(QueryException.Failure.OTHER, "too long"));
    var copies = List.of(
        new Copy(from("x"), into("x"), Existing.EMPTY),
        new Copy(from("y"), into("y"), Existing.EMPTY));

    var stopped = run(new Transfer(copies, Commit.TABLE, OnError.STOP, 10));

    assertEquals(List.of(State.FAILED, State.SKIPPED), states(stopped));
    assertEquals(List.of(List.of("old x")), targetRows.get("x"));
    assertEquals(List.of(List.of("old y")), targetRows.get("y"));

    var skipped = run(new Transfer(copies, Commit.BATCH, OnError.SKIP, 10));

    assertEquals(List.of(State.FAILED, State.COPIED), states(skipped));
    assertEquals(List.of(List.of("old x")), targetRows.get("x"));
    assertEquals(2, targetRows.get("y").size());
  }

  @Test
  void aTableToEmptyFailsWhereAKeyOfATableNotEmptiedWouldChangeItsRows() {
    rows("parent", 1);
    rows("child", 1);
    targetRows.put("parent", new ArrayList<>(List.of(List.of("old"))));
    keys.add(new ForeignKey(
        "kept_fk",
        into("kept"),
        List.of("v"),
        into("parent"),
        List.of("v"),
        null,
        ForeignKey.Rule.CASCADE));
    // A key of a table emptied in the copy too changes nothing that stays.
    keys.add(new ForeignKey(
        "child_fk",
        into("child"),
        List.of("v"),
        into("parent"),
        List.of("v"),
        null,
        ForeignKey.Rule.SET_NULL));

    var report = run(new Transfer(
        List.of(
            new Copy(from("parent"), into("parent"), Existing.EMPTY),
            new Copy(from("child"), into("child"), Existing.EMPTY)),
        Commit.TABLE,
        OnError.STOP,
        10));

    assertEquals(Stage.STOPPED, report.stage());
    var parent = report.tables().getFirst();
    assertEquals(State.FAILED, parent.state());
    assertTrue(
        parent.problem().contains("delete rows of kept, by its foreign key kept_fk"),
        parent.problem());
    assertEquals(State.SKIPPED, report.tables().get(1).state(), report.toString());
    assertTrue(calls.stream().noneMatch(c -> c.startsWith("empty")), calls.toString());
    assertEquals(List.of(List.of("old")), targetRows.get("parent"));
  }

  @Test
  void aTableOfTheSameServerIsNotCopiedIntoItself() {
    rows("x", 2);
    targetIdentity = sourceIdentity;
    var itself = new Copy(from("x"), from("x"), Existing.EMPTY);

    var report = run(new Transfer(List.of(itself), Commit.TABLE, OnError.STOP, 10));

    assertEquals(State.FAILED, report.tables().getFirst().state());
    assertTrue(report.tables().getFirst().problem().contains("the same table"));
    assertTrue(calls.stream().noneMatch(c -> c.startsWith("empty")), calls.toString());

    // Another database of the same server is another table.
    assertEquals(
        Stage.DONE, run(transfer(OnError.STOP, Commit.TABLE, 10, "x")).stage(), calls.toString());
  }

  @Test
  void aTableThatRefersToItselfSaysSoWhereAWriteFails() {
    rows("x", 3);
    keys.add(new ForeignKey("self", into("x"), List.of("v"), into("x"), List.of("v"), null, null));
    failing.put("write x", QueryException.of(QueryException.Failure.OTHER, "violates the key"));

    var report = run(transfer(OnError.STOP, Commit.TABLE, 10, "x"));

    assertEquals("violates the key " + Copier.SELF, report.tables().getFirst().problem());
  }

  @Test
  void twoColumnsThatWouldGoIntoOneFailTheTableBeforeAnythingIsWritten() {
    rows("x", 1);
    rows("y", 1);
    sourceColumns.put("x", List.of("v", "V"));
    // The one column whose name differs only in case takes the column's values.
    sourceColumns.put("y", List.of("V"));

    var report = run(transfer(OnError.SKIP, Commit.TABLE, 10, "x", "y"));

    assertEquals(List.of(State.FAILED, State.COPIED), states(report));
    assertEquals(
        "The target's x has one column v for the source's columns v, V.",
        report.tables().getFirst().problem());
    assertTrue(!calls.contains("write x"), calls.toString());
    assertEquals(1, targetRows.get("y").size());
  }

  @Test
  void aRollbackThatFailsStopsTheRunAlsoWhereItGoesOnAtAnError() {
    rows("x", 25);
    rows("y", 2);
    failing.put("write x 2", QueryException.of(QueryException.Failure.OTHER, "check"));
    failing.put("rollback", QueryException.of(QueryException.Failure.OTHER, "no answer"));

    var report = run(transfer(OnError.SKIP, Commit.TABLE, 10, "x", "y"));

    assertEquals(Stage.STOPPED, report.stage());
    assertEquals(List.of(State.FAILED, State.FAILED), states(report));
    assertEquals(
        "check Rolling it back failed: no answer", report.tables().getFirst().problem());
    // The rows of x that were not rolled back are not committed with those of another table.
    assertTrue(!calls.contains("commit") && !calls.contains("write y"), calls.toString());
    assertTrue(!targetRows.containsKey("x"), targetRows.toString());
    assertEquals("end", calls.getLast());
  }

  @Test
  void theRowsKeptOfATableThatFailsMoveItsSequencesOn() {
    rows("x", 25);
    failing.put("write x 2", QueryException.of(QueryException.Failure.OTHER, "check"));

    run(transfer(OnError.STOP, Commit.BATCH, 10, "x"));

    var after = calls.subList(calls.indexOf("rollback"), calls.size());
    assertEquals(List.of("rollback", "written x", "commit", "close x", "end"), after);
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

  private static List<Column> columns(Map<String, List<String>> named, Table table) {
    return named.getOrDefault(table.name(), List.of("v")).stream()
        .map(name -> new Column(table, name, "text", true, 0))
        .toList();
  }

  private void fail(String call) throws QueryException {
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
      public String identity() {
        return sourceIdentity;
      }

      @Override
      public List<Column> columns(Table table) {
        return CopierTest.columns(sourceColumns, table);
      }

      @Override
      public long count(Table table) {
        return sourceRows.get(table.name()).size();
      }

      @Override
      public Reading read(Selection selection) {
        var name = selection.table().name();
        calls.add("read " + name);
        var all = sourceRows.get(name);
        return new Reading() {
          private int at;

          @Override
          public Batch next(int count, long budget) throws QueryException, InterruptedException {
            calls.add("next " + name + " " + count + " " + budget);
            if (holding != null && at > 0) {
              held.countDown();
              holding.await(); // until interrupted
            }
            var rows = new ArrayList<List<Object>>();
            long size = 0;
            while (at < all.size() && rows.size() < count) {
              var bytes = Batch.size(all.get(at));
              if (!rows.isEmpty() && size + bytes > budget) {
                break;
              }
              rows.add(all.get(at++));
              size += bytes;
            }
            calls.add("batch " + name + " " + rows.size());
            return new Batch(rows, at < all.size());
          }

          @Override
          public void close() {
            calls.add("close " + name);
          }
        };
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
      public String identity() {
        return targetIdentity;
      }

      @Override
      public OptionalLong statementLimit() {
        return limit;
      }

      @Override
      public Shape shape(Table table) {
        return new Shape(CopierTest.columns(targetColumns, table), Set.of());
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
        pending.add(new Pending(table.name(), null));
        return 1;
      }

      @Override
      public long write(Table table, List<String> columns, List<List<Object>> rows)
          throws QueryException {
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
          if (row.row() == null) {
            targetRows.put(row.table(), new ArrayList<>());
          } else {
            targetRows.computeIfAbsent(row.table(), t -> new ArrayList<>()).add(row.row());
          }
        }
        pending.clear();
      }

      @Override
      public void rollback() throws QueryException {
        calls.add("rollback");
        fail("rollback");
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

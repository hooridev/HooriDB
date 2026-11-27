package dev.hoori.db.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The changes of a comparison handed on in batches of one kind, deletions first. */
class BatchesTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Table SOURCE = new Table(SHOP, "a", Table.Kind.TABLE);
  private static final Table TARGET = new Table(SHOP, "b", Table.Kind.TABLE);
  private static final List<Field> FIELDS =
      List.of(new Field("id", "int", Kind.INTEGER), new Field("v", "text", Kind.TEXT));

  // In the key's order an insert, a deletion and an update alternate.
  private static final List<List<Object>> MINE =
      List.of(row(1, "a"), row(3, "c"), row(4, "d"), row(6, "f"), row(7, "g"));
  private static final List<List<Object>> THEIRS =
      List.of(row(2, "a"), row(3, "x"), row(5, "e"), row(7, "y"), row(8, "h"));

  @Test
  void deletionsGoFirstThenUpdatesThenNewRowsInOnePassWhereTheyFit() throws Exception {
    var told = new ArrayList<String>();
    var reads = new int[1];
    var made = Batches.make(reads(reads), Choice.ALL, 10, batch -> take(told, batch), quiet());
    assertEquals(List.of("delete 2, 5, 8", "update 3, 7", "insert 1, 4, 6"), told);
    assertEquals(1, reads[0]);
    assertEquals(8, made.changes());
    assertEquals(new Tally(3, 3, 2, 0, 5, 5), made.tally());
  }

  @Test
  void kindsThatDoNotFitInABatchAreReadInPassesOfTheirOwn() throws Exception {
    var told = new ArrayList<String>();
    var reads = new int[1];
    var made = Batches.make(reads(reads), Choice.ALL, 2, batch -> take(told, batch), quiet());
    assertEquals(
        List.of("delete 2, 5", "delete 8", "update 3, 7", "insert 1, 4", "insert 6"), told);
    assertEquals(3, reads[0]);
    assertEquals(8, made.changes());
  }

  @Test
  void onlyTheKindsChosenGoAndAMissedBatchEndsThem() throws Exception {
    var told = new ArrayList<String>();
    var reads = new int[1];
    Batches.make(
        reads(reads), new Choice(true, false, false), 2, batch -> take(told, batch), quiet());
    assertEquals(List.of("insert 1, 4", "insert 6"), told);
    assertEquals(1, reads[0]);

    var missed = new Changes.Outcome.Missed(new Change.Delete(row(2, "a")), 0);
    var ended = Batches.make(reads(reads), Choice.ALL, 2, _ -> Optional.of(missed), quiet());
    assertEquals(Optional.of(missed), ended.ended());
    assertEquals(0, ended.changes());
  }

  private static Batches.Progress quiet() {
    return (_, _) -> {
      // Nothing shows it.
    };
  }

  // Each change of the batch, of one kind, by its row's id.
  private static Optional<Changes.Outcome> take(List<String> told, Changes batch) {
    var kinds = batch.changes().stream()
        .map(change -> switch (change) {
          case Change.Delete _ -> "delete";
          case Change.Update _ -> "update";
          case Change.Insert _ -> "insert";
        })
        .distinct()
        .toList();
    assertEquals(1, kinds.size(), kinds.toString());
    var ids = batch.changes().stream()
        .map(change -> String.valueOf(
            switch (change) {
              case Change.Delete(var row) -> row.getFirst();
              case Change.Update update -> update.row().getFirst();
              case Change.Insert(var values) -> values.get(0);
            }))
        .toList();
    told.add(kinds.getFirst() + " " + String.join(", ", ids));
    return Optional.empty();
  }

  // Both tables read anew, a row a portion, counting the reads.
  private static Batches.Reads<Exception> reads(int[] reads) {
    var pairing = ((Pairing.Paired) Pairing.of(
            SOURCE,
            List.of(
                new Column(SOURCE, "id", "int", false, 1),
                new Column(SOURCE, "v", "text", true, 0)),
            List.of(),
            TARGET,
            List.of(
                new Column(TARGET, "id", "int", false, 1),
                new Column(TARGET, "v", "text", true, 0)),
            List.of()))
        .pairing();
    return () -> {
      reads[0]++;
      var merge = Merge.of(pairing, cursor(MINE), cursor(THEIRS));
      return new Batches.Reading() {
        @Override
        public Merge merge() {
          return merge;
        }

        @Override
        public void close() {
          // Nothing is left open.
        }
      };
    };
  }

  private static Cursor cursor(List<List<Object>> all) {
    return new Cursor() {
      private int at;

      @Override
      public Result.Rows next() {
        var next = all.subList(at, Math.min(all.size(), at + 1));
        at += next.size();
        return new Result.Rows(FIELDS, at - next.size(), next);
      }
    };
  }

  private static List<Object> row(long id, String value) {
    return Arrays.asList(id, value);
  }
}

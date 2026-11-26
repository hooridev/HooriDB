package dev.hoori.db.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Result;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The merge of two tables' rows by their key, as their cursors give them in portions. */
class MergeTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Table SOURCE = new Table(SHOP, "a", Table.Kind.TABLE);
  private static final Table TARGET = new Table(SHOP, "b", Table.Kind.TABLE);
  private static final List<Field> FIELDS =
      List.of(new Field("id", "int", Kind.INTEGER), new Field("v", "numeric", Kind.DECIMAL));

  @Test
  void tellsEachDifferenceInTheKeysOrderAndCountsTheRest() throws Exception {
    var merge = merge(
        cursor(2, List.of(row(1L, "1.0"), row(2L, "2"), row(4L, "4"), row(5L, "5"))),
        cursor(3, List.of(row(2L, "2.00"), row(3L, "3"), row(4L, "40"), row(6L, null))));
    var differences = new ArrayList<Difference>();
    for (var d = merge.next(); d != null; d = merge.next()) {
      differences.add(d);
    }
    assertEquals(
        List.of(
            new Difference.OnlyInSource(row(1L, "1.0")),
            new Difference.OnlyInTarget(row(3L, "3")),
            new Difference.Different(row(4L, "4"), row(4L, "40"), List.of(new Pairing.Pair(1, 1))),
            new Difference.OnlyInSource(row(5L, "5")),
            new Difference.OnlyInTarget(row(6L, null))),
        differences);
    assertEquals(new Tally(2, 2, 1, 1, 4, 4), merge.tally());
    assertEquals(3, merge.tally().taken(new Choice(true, true, false)));
    assertEquals(5, merge.tally().differences());
  }

  @Test
  void emptyTablesHaveNothingToTell() throws Exception {
    var merge = merge(cursor(5, List.of()), cursor(5, List.of(row(1L, "1"))));
    assertEquals(new Difference.OnlyInTarget(row(1L, "1")), merge.next());
    assertEquals(null, merge.next());
    assertEquals(new Tally(0, 1, 0, 0, 0, 1), merge.tally());
  }

  // A server that orders a key otherwise would have rows paired that are not each other's.
  @Test
  void aKeyOutOfOrderEndsTheMerge() throws Exception {
    var merge =
        merge(cursor(5, List.of(row(1L, "1"), row(3L, "3"), row(2L, "2"))), cursor(5, List.of()));
    merge.next();
    merge.next();
    var failure = assertThrows(CompareException.class, merge::next);
    assertEquals(
        "The rows of the source did not come in the order of the key at row 3, as its server"
            + " orders a value of the key in another way, so the rows cannot be paired.",
        failure.getMessage());
    var twice = merge(cursor(5, List.of()), cursor(5, List.of(row(1L, "1"), row(1L, "1"))));
    twice.next();
    assertInstanceOf(CompareException.class, assertThrows(CompareException.class, twice::next));
  }

  private static Merge merge(Cursor source, Cursor target) {
    var pairing = Pairing.of(
        SOURCE,
        List.of(
            new Column(SOURCE, "id", "int", false, 1), new Column(SOURCE, "v", "numeric", true, 0)),
        List.of(),
        TARGET,
        List.of(
            new Column(TARGET, "id", "int", false, 1), new Column(TARGET, "v", "numeric", true, 0)),
        List.of());
    return new Merge(((Pairing.Paired) pairing).pairing(), source, target);
  }

  // The rows, at most so many a portion.
  private static Cursor cursor(int portion, List<List<Object>> all) {
    return new Cursor() {
      private int at;

      @Override
      public Result.Rows next() {
        var next = all.subList(at, Math.min(all.size(), at + portion));
        at += next.size();
        return new Result.Rows(FIELDS, at - next.size(), next);
      }
    };
  }

  // A row of an id and a decimal written as text, or NULL.
  private static List<Object> row(long id, String value) {
    return Arrays.asList(id, value == null ? null : new BigDecimal(value));
  }
}

package dev.hoori.db.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Index;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which columns and key two tables are compared by, and the changes that make them equal. */
class PairingTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Table SOURCE = new Table(SHOP, "orders", Table.Kind.TABLE);
  private static final Table TARGET = new Table(SHOP, "orders_copy", Table.Kind.TABLE);

  @Test
  void columnsAreMatchedByNameAndRowsPairedByThePrimaryKeyBothHave() {
    var source = List.of(
        column(SOURCE, "id", "int", 1),
        column(SOURCE, "name", "varchar(20)", 0),
        column(SOURCE, "extra", "int", 0));
    var target = List.of(
        column(TARGET, "kept", "int", 0),
        column(TARGET, "name", "varchar(40)", 0),
        column(TARGET, "id", "bigint", 1));
    var pairing = paired(source, List.of(), target, List.of());
    assertEquals(Pairing.PRIMARY_KEY, pairing.keyName());
    assertEquals(List.of(new Pairing.Pair(0, 2), new Pairing.Pair(1, 1)), pairing.columns());
    assertEquals(List.of(new Pairing.Pair(0, 2)), pairing.key());
    assertEquals(List.of("extra"), pairing.onlyInSource());
    assertEquals(List.of("kept"), pairing.onlyInTarget());
  }

  // A key of a column that may be NULL names no row: the unique key of NOT NULL columns does.
  @Test
  void aUniqueKeyOfColumnsNotNullPairsTheRowsWithoutAPrimaryKey() {
    var source = List.of(
        new Column(SOURCE, "code", "varchar(10)", false, 0),
        new Column(SOURCE, "a", "int", true, 0),
        new Column(SOURCE, "b", "int", false, 0));
    var target = List.of(
        new Column(TARGET, "b", "int", false, 0),
        new Column(TARGET, "a", "int", true, 0),
        new Column(TARGET, "code", "varchar(10)", false, 0));
    var sourceIndexes = List.of(
        new Index("by_a", SOURCE, List.of("a"), true, false),
        new Index("by_code_b", SOURCE, List.of("code", "b"), true, false));
    var targetIndexes = List.of(
        new Index("by_a", TARGET, List.of("a"), true, false),
        new Index("by_b_code", TARGET, List.of("b", "code"), true, false));
    var pairing = paired(source, sourceIndexes, target, targetIndexes);
    assertEquals("by_code_b", pairing.keyName());
    assertEquals(List.of(0, 2), pairing.sourceKey());
    assertEquals(List.of(2, 0), pairing.targetKey());
    // Changes name the target's rows by that key.
    assertEquals(
        List.of(2, 0, 1),
        pairing.keyedTarget().stream().map(Column::keyPosition).toList());
  }

  // A partial unique index, or one not valid yet, names not every row once.
  @Test
  void aPartialUniqueIndexPairsNoRows() {
    var source = List.of(
        new Column(SOURCE, "code", "varchar(10)", false, 0),
        new Column(SOURCE, "b", "int", false, 0));
    var target = List.of(
        new Column(TARGET, "code", "varchar(10)", false, 0),
        new Column(TARGET, "b", "int", false, 0));
    var partial = new Index("by_code", SOURCE, List.of("code"), true, false, "", true);
    var whole = new Index("by_code", TARGET, List.of("code"), true, false);
    assertInstanceOf(
        Pairing.Unpaired.class,
        Pairing.of(SOURCE, source, List.of(partial), TARGET, target, List.of(whole)));
    assertInstanceOf(
        Pairing.Unpaired.class,
        Pairing.of(
            SOURCE,
            source,
            List.of(whole),
            TARGET,
            target,
            List.of(new Index("by_code", TARGET, List.of("code"), true, false, "", true))));
    assertEquals(
        "by_b",
        paired(
                source,
                List.of(partial, new Index("by_b", SOURCE, List.of("b"), true, false)),
                target,
                List.of(whole, new Index("by_b", TARGET, List.of("b"), true, false)))
            .keyName());
  }

  @Test
  void tablesWithoutAKeyBothHaveCannotBeCompared() {
    var source = List.of(column(SOURCE, "id", "int", 1));
    var target = List.of(column(TARGET, "id", "int", 0), column(TARGET, "other", "int", 1));
    var found = Pairing.of(SOURCE, source, List.of(), TARGET, target, List.of());
    assertInstanceOf(Pairing.Unpaired.class, found);
    var same = Pairing.of(SOURCE, source, List.of(), SOURCE, source, List.of());
    assertEquals(
        "The source and the target are the same table.",
        assertInstanceOf(Pairing.Unpaired.class, same).why());
  }

  @Test
  void columnsOfKindsThatCompareApartAreLeftOut() throws Exception {
    var pairing = paired(
        List.of(
            column(SOURCE, "id", "int", 1),
            column(SOURCE, "at", "date", 0),
            column(SOURCE, "n", "int", 0),
            column(SOURCE, "j", "json", 0),
            column(SOURCE, "d", "numeric(5,2)", 0)),
        List.of(),
        List.of(
            column(TARGET, "id", "numeric", 1),
            column(TARGET, "at", "text", 0),
            column(TARGET, "n", "numeric(5,2)", 0),
            column(TARGET, "j", "longtext", 0),
            column(TARGET, "d", "int", 0)),
        List.of());
    // Integers fit in decimals, but decimals in integers would lose their fractions, and the rows
    // would differ again after each apply.
    var read = pairing.read(
        fields(Kind.INTEGER, Kind.DATE, Kind.INTEGER, Kind.JSON, Kind.DECIMAL),
        fields(Kind.DECIMAL, Kind.TEXT, Kind.DECIMAL, Kind.TEXT, Kind.INTEGER));
    assertEquals(List.of("at", "d"), read.unlike());
    assertEquals(3, read.columns().size());
    assertEquals(List.of(), read.onlyInSource());
    var decimalKey = assertThrows(
        CompareException.class,
        () -> pairing.read(
            fields(Kind.DECIMAL, Kind.DATE, Kind.INTEGER, Kind.JSON, Kind.DECIMAL),
            fields(Kind.INTEGER, Kind.DATE, Kind.INTEGER, Kind.JSON, Kind.DECIMAL)));
    assertEquals(
        "Column id of the key holds decimals in the source, which the integers of the target"
            + " cannot hold, so the rows cannot be paired.",
        decimalKey.getMessage());

    var badKey = assertThrows(
        CompareException.class,
        () -> pairing.read(
            fields(Kind.TEXT, Kind.DATE, Kind.INTEGER, Kind.JSON, Kind.DECIMAL),
            fields(Kind.INTEGER, Kind.DATE, Kind.INTEGER, Kind.JSON, Kind.DECIMAL)));
    assertEquals(
        "Column id of the key holds text in the source and numbers in the target, which sort"
            + " apart, so the rows cannot be paired.",
        badKey.getMessage());
  }

  @Test
  void eachDifferenceMakesTheChangeTheChoiceTakes() {
    var pairing = paired(
        List.of(column(SOURCE, "id", "int", 1), column(SOURCE, "name", "text", 0)),
        List.of(),
        List.of(
            column(TARGET, "name", "text", 0),
            column(TARGET, "id", "int", 1),
            column(TARGET, "kept", "int", 0)),
        List.of());
    var only = new Difference.OnlyInSource(List.of(1L, "one"));
    assertEquals(
        new Change.Insert(Map.of(1, 1L, 0, "one")),
        pairing.change(only, Choice.ALL).orElseThrow());
    var gone = new Difference.OnlyInTarget(row("x", 9L, 3L));
    assertEquals(
        new Change.Delete(row("x", 9L, 3L)), pairing.change(gone, Choice.ALL).orElseThrow());
    var differs = new Difference.Different(
        List.of(2L, "two"), row("TWO", 2L, null), List.of(new Pairing.Pair(1, 0)));
    assertEquals(
        new Change.Update(row("TWO", 2L, null), Map.of(0, "two")),
        pairing.change(differs, Choice.ALL).orElseThrow());
    var none = new Choice(false, false, false);
    assertEquals(false, none.any());
    assertEquals(true, pairing.change(only, none).isEmpty());
    assertEquals(true, pairing.change(gone, new Choice(true, true, false)).isEmpty());
  }

  private static Pairing paired(
      List<Column> source,
      List<Index> sourceIndexes,
      List<Column> target,
      List<Index> targetIndexes) {
    var found = Pairing.of(SOURCE, source, sourceIndexes, TARGET, target, targetIndexes);
    return assertInstanceOf(Pairing.Paired.class, found).pairing();
  }

  private static Column column(Table table, String name, String type, int key) {
    return new Column(table, name, type, key == 0, key);
  }

  private static List<Field> fields(Kind... kinds) {
    return Arrays.stream(kinds).map(kind -> new Field("c", "t", kind)).toList();
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }
}

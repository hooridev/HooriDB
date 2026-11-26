package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Collecting changes, and the statements they make, without a server. */
class ChangesTest {

  private static final Table TABLE = new Table(
      new Schema(new Database("hoori", false), "public", false), "item", Table.Kind.TABLE);
  // The key is (b, a), against the order of the columns.
  private static final List<Column> COLUMNS = List.of(
      new Column(TABLE, "a", "integer", false, 2),
      new Column(TABLE, "b", "text", false, 1),
      new Column(TABLE, "v", "text", true, 0),
      new Column(TABLE, "n", "numeric", true, 0));
  private static final List<Object> ROW = row(1L, "x", "old", new BigDecimal("1.50"));
  private static final List<Object> OTHER = row(2L, "y", "kept", null);

  @Test
  void editsOfARowGoIntoItsOneUpdateAndTheValueReadTakesThemBack() {
    var none = changes();
    var edited = none.edit(ROW, 2, "new").edit(ROW, 3, null).edit(OTHER, 2, "other");
    assertEquals(
        List.of(
            new Change.Update(ROW, values(2, "new", 3, null)),
            new Change.Update(OTHER, values(2, "other"))),
        edited.changes());

    var back = edited.edit(ROW, 3, new BigDecimal("1.50"));
    assertEquals(new Change.Update(ROW, values(2, "new")), back.changes().getFirst());
    assertEquals(List.of(edited.changes().get(1)), back.edit(ROW, 2, "old").changes());
    assertSame(none, none.edit(ROW, 2, "old"));
  }

  @Test
  void aDeletionTakesThePlaceOfItsRowsUpdate() {
    var deleted = changes().edit(ROW, 2, "new").edit(OTHER, 2, "other").delete(ROW);
    assertEquals(
        List.of(new Change.Update(OTHER, values(2, "other")), new Change.Delete(ROW)),
        deleted.changes());
    assertSame(deleted, deleted.delete(ROW));
    assertThrows(IllegalStateException.class, () -> deleted.edit(ROW, 2, "again"));
    assertEquals(List.of(deleted.changes().getFirst()), deleted.revert(1).changes());
  }

  @Test
  void aNewRowTakesValuesUntilItIsGivenUp() {
    var inserted = changes().insert(Map.of()).edit(0, 0, 3L).edit(0, 2, null);
    assertEquals(List.of(new Change.Insert(values(0, 3L, 2, null))), inserted.changes());
    var deleted = changes().delete(ROW);
    assertThrows(
        IllegalArgumentException.class, () -> deleted.edit(0, 2, "a deleted row is no new one"));
    assertEquals(List.of(), inserted.revert(0).changes());
  }

  @Test
  void aTableWithoutPrimaryKeyHasNoChanges() {
    var keyless = COLUMNS.stream()
        .map(c -> new Column(c.table(), c.name(), c.type(), c.nullable(), 0))
        .toList();
    assertTrue(Changes.of(TABLE, keyless).isEmpty());
    var deletion = List.<Change>of(new Change.Delete(ROW));
    assertThrows(IllegalArgumentException.class, () -> new Changes(TABLE, keyless, deletion));
  }

  @Test
  void whatWasReadOnlyInPartNeitherNamesARowNorIsWritten() {
    var none = changes();
    var cut = row(1L, new Cut("x", OptionalLong.of(2000)), "old", null);
    assertThrows(IllegalArgumentException.class, () -> none.delete(cut));
    var cutText = new Cut("x", OptionalLong.empty());
    assertThrows(IllegalArgumentException.class, () -> none.edit(ROW, 2, cutText));
    var cutBinary = Map.<Integer, Object>of(2, new Binary(new byte[] {1}, 2));
    assertThrows(IllegalArgumentException.class, () -> none.insert(cutBinary));
    var keyNull = row(1L, null, "", null);
    assertThrows(IllegalArgumentException.class, () -> none.delete(keyNull));
  }

  @Test
  void unreadAndPartialColumnsCannotBeUpdated() {
    var none = changes();
    var unread = row(1L, "x", Missing.FIELD, new BigDecimal("1.50"));
    assertThrows(IllegalArgumentException.class, () -> none.edit(unread, 2, "new"));
    assertThrows(IllegalArgumentException.class, () -> none.edit(unread, 2, Missing.FIELD));
    var changed = values(2, "new");
    assertThrows(IllegalArgumentException.class, () -> new Change.Update(unread, changed));
    // Other present, whole columns of the same row can still be edited, and the row deleted.
    assertEquals(
        List.of(new Change.Update(unread, values(3, new BigDecimal("2.00")))),
        none.edit(unread, 3, new BigDecimal("2.00")).changes());
    assertEquals(List.of(new Change.Delete(unread)), none.delete(unread).changes());

    var cutText = row(1L, "x", new Cut("old", OptionalLong.of(2000)), null);
    assertThrows(IllegalArgumentException.class, () -> none.edit(cutText, 2, "new"));
    assertThrows(IllegalArgumentException.class, () -> new Change.Update(cutText, changed));

    var cutBinary = row(1L, "x", new Binary(new byte[] {1}, 2), null);
    assertThrows(IllegalArgumentException.class, () -> none.edit(cutBinary, 2, "new"));
    assertThrows(IllegalArgumentException.class, () -> new Change.Update(cutBinary, changed));

    assertThrows(IllegalArgumentException.class, () -> none.edit(ROW, 2, Missing.FIELD));
    var unreadValue = values(2, Missing.FIELD);
    assertThrows(IllegalArgumentException.class, () -> none.insert(unreadValue));
  }

  @Test
  void statementsNameTheirRowsByTheKeyAndUpdatesComeFirst() {
    var changes = changes()
        .insert(Map.of(0, 3L, 1, "it's"))
        .edit(ROW, 2, "new")
        .edit(ROW, 3, null)
        .delete(OTHER)
        .insert(Map.of());
    var statements = changes.statements(Provider.POSTGRESQL);
    assertEquals(
        List.of(
            "UPDATE \"public\".\"item\" SET \"v\" = ?, \"n\" = ?"
                + " WHERE \"b\" OPERATOR(pg_catalog.=) ? AND \"a\" OPERATOR(pg_catalog.=) ?",
            "DELETE FROM \"public\".\"item\" WHERE \"b\" OPERATOR(pg_catalog.=) ?"
                + " AND \"a\" OPERATOR(pg_catalog.=) ?",
            "INSERT INTO \"public\".\"item\" (\"a\", \"b\") VALUES (?, ?)",
            "INSERT INTO \"public\".\"item\" (\"a\") VALUES (DEFAULT)"),
        statements.stream().map(Changes.Statement::sql).toList());
    assertEquals(
        List.of(
            Arrays.asList("new", null, "x", 1L), List.of("y", 2L), List.of(3L, "it's"), List.of()),
        statements.stream().map(Changes.Statement::values).toList());
    assertEquals(
        List.of(
            "UPDATE \"public\".\"item\" SET \"v\" = 'new', \"n\" = NULL"
                + " WHERE \"b\" = 'x' AND \"a\" = 1",
            "DELETE FROM \"public\".\"item\" WHERE \"b\" = 'y' AND \"a\" = 2",
            "INSERT INTO \"public\".\"item\" (\"a\", \"b\") VALUES (3, 'it''s')",
            "INSERT INTO \"public\".\"item\" (\"a\") VALUES (DEFAULT)"),
        statements.stream().map(Changes.Statement::preview).toList());
    assertEquals(changes.changes().get(1), statements.getFirst().change());

    var mysql = new Changes(
        new Table(new Database("hoori", false), "it`em", Table.Kind.TABLE),
        COLUMNS.stream()
            .map(c -> new Column(
                new Table(new Database("hoori", false), "it`em", Table.Kind.TABLE),
                c.name(),
                c.type(),
                c.nullable(),
                c.keyPosition()))
            .toList(),
        List.of(new Change.Update(ROW, values(2, "a\\b"))));
    assertEquals(
        "UPDATE `hoori`.`it``em` SET `v` = 'a\\\\b' WHERE `b` = 'x' AND `a` = 1",
        mysql.statements(Provider.MYSQL).getFirst().preview());
  }

  @Test
  void literalsAreWhatTheServersRead() {
    var pg = Provider.POSTGRESQL.dialect().rows();
    var my = Provider.MARIADB.dialect().rows();
    var moment = OffsetDateTime.of(2024, 3, 31, 4, 30, 0, 123_000_000, ZoneOffset.ofHours(2));
    assertEquals("'2024-03-31 02:30:00.123+00:00'", pg.literal(moment));
    assertEquals("'2024-03-31 02:30:00.123+00:00'", my.literal(moment));
    assertEquals("'0044-03-15 BC'", pg.literal(LocalDate.of(-43, 3, 15)));
    assertEquals("'5874897-12-31'", pg.literal(LocalDate.of(5874897, 12, 31)));
    assertEquals(
        "'4713-01-01 00:00:00.5 BC'",
        pg.literal(LocalDateTime.of(-4712, 1, 1, 0, 0, 0, 500_000_000)));
    assertEquals("'0000-01-01'", my.literal(LocalDate.of(0, 1, 1)));
    assertEquals("'NaN'", pg.literal(Double.NaN));
    assertEquals("0.1", pg.literal(0.1f));
    assertEquals("1234567890.000", pg.literal(new BigDecimal("1234567890.000")));
    assertEquals("'\\x00ff'", pg.literal(new Binary(new byte[] {0, -1}, 2)));
    assertEquals("X'00ff'", my.literal(new Binary(new byte[] {0, -1}, 2)));
    assertEquals("TRUE", my.literal(true));
    assertEquals("'it''s \\\\ a\\0b'", my.literal("it's \\ a\0b"));
    assertEquals("E'it''s \\\\ a'", pg.literal("it's \\ a"));
    assertThrows(IllegalArgumentException.class, () -> pg.literal(1));
  }

  private static Changes changes() {
    return Changes.of(TABLE, COLUMNS).orElseThrow();
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  private static Map<Integer, Object> values(Object... pairs) {
    var values = new java.util.TreeMap<Integer, Object>();
    for (var i = 0; i < pairs.length; i += 2) {
      values.put((Integer) pairs[i], pairs[i + 1]);
    }
    return values;
  }
}

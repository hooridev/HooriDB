package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What needs no server: wrong arguments, and binary data that compare by their content. */
class RowsTest {

  private static final Database HOORI = new Database("hoori", false);

  @Test
  void positionsGoForwardFromZeroAndColumnsAreTheTables() {
    var table = new Table(HOORI, "t", Table.Kind.TABLE);
    var other = new Table(HOORI, "u", Table.Kind.TABLE);
    var column = new Column(other, "id", "int", false, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> Rows.read(null, Provider.MYSQL, table, List.of(), -1, 10));
    assertThrows(
        IllegalArgumentException.class,
        () -> Rows.read(null, Provider.MYSQL, table, List.of(), 10, 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> Rows.read(null, Provider.MYSQL, table, List.of(column), 0, 10));
  }

  @Test
  void binaryDataCompareByContentAndCannotBeChanged() {
    var bytes = new byte[] {1, 2};
    var binary = new Binary(bytes, 5);
    bytes[0] = 9;
    binary.bytes()[1] = 9;
    assertEquals(new Binary(new byte[] {1, 2}, 5), binary);
    assertEquals(new Binary(new byte[] {1, 2}, 5).hashCode(), binary.hashCode());
    assertNotEquals(new Binary(new byte[] {1, 2}, 2), binary);
    assertEquals(true, binary.cut());
  }
}

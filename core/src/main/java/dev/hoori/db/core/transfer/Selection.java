package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import java.util.Comparator;
import java.util.List;

/**
 * What is read of a table: the columns, in the order their values come, and the columns of its
 * primary key, in the key's order, by which its rows come; none where it has no key, and its rows
 * come by position.
 */
public record Selection(Table table, List<Column> columns, List<Column> key) {

  public Selection {
    columns = List.copyOf(columns);
    key = List.copyOf(key);
  }

  /** The columns of the table, of all its columns, and the key that those have. */
  public static Selection of(Table table, List<Column> columns, List<Column> all) {
    var key = all.stream()
        .filter(column -> column.keyPosition() > 0)
        .sorted(Comparator.comparingInt(Column::keyPosition))
        .toList();
    return new Selection(table, columns, key);
  }
}

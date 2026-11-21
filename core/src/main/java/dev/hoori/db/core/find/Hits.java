package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Table;
import java.util.List;

/**
 * What a search found in a table: the rows whose values matched, up to {@link Search#LIMIT}, and of
 * them how many matched in each column searched, by the order of the columns.
 *
 * @param searched the columns searched, by name; none where the table has no column that can hold
 *     the value, and was not read
 * @param rows the rows that matched in any column, at most {@link Search#LIMIT}
 * @param more whether more rows than that matched, which were not counted
 */
public record Hits(Table table, List<String> searched, List<Hit> columns, int rows, boolean more) {

  /** A column's hits: the rows counted whose value in it matched. */
  public record Hit(String column, int rows) {}

  public Hits {
    searched = List.copyOf(searched);
    columns = List.copyOf(columns);
  }

  /** A table that has no column to search, which was not read. */
  public static Hits none(Table table) {
    return new Hits(table, List.of(), List.of(), 0, false);
  }
}

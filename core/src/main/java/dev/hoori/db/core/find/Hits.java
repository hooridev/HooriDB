package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * What a search found in a table: of the rows whose values matched, up to {@link Search#LIMIT}, how
 * many matched in each column searched, by the order of the columns.
 *
 * @param searched the columns searched, by name; none where the table has no column that can hold
 *     the value, and was not read
 * @param more whether more rows than {@link Search#LIMIT} matched, which were not counted
 */
public record Hits(Table table, List<String> searched, List<Hit> columns, boolean more) {

  /** A column's hits: the rows counted whose value in it matched. */
  public record Hit(String column, int rows) {}

  public Hits {
    searched = List.copyOf(searched);
    columns = List.copyOf(columns);
  }

  /** A table that has no column to search, which was not read. */
  public static Hits none(Table table) {
    return new Hits(table, List.of(), List.of(), false);
  }

  /**
   * The hits of the columns searched, by the counts of each, in their order, of the rows that
   * matched, which may be more than {@link Search#LIMIT}; a column without one is left out.
   */
  static Hits of(Table table, List<String> searched, int[] counts, int matched) {
    var hits = new ArrayList<Hit>();
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] > 0) {
        hits.add(new Hit(searched.get(i), counts[i]));
      }
    }
    return new Hits(table, searched, hits, matched > Search.LIMIT);
  }
}

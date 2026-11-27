package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a search found in a table: of the rows whose values matched, up to {@link Search#LIMIT}, how
 * many matched in each column searched, by the order of the columns.
 *
 * @param searched the columns searched, by name; none where the table has no column that can hold
 *     the value, and was not read
 * @param more whether more rows than {@link Search#LIMIT} matched, which were not counted
 */
public record Hits(Table table, List<String> searched, List<Hit> columns, boolean more) {

  /**
   * A column's hits: the rows counted whose value in it matched.
   *
   * @param filter of a collection's field, the Filter of the collection's tab that finds the
   *     documents counted, as a document of the shell, which matches only as the field's sampled
   *     values did, as text or as a number; empty for a table's column
   */
  public record Hit(String column, int rows, Optional<String> filter) {

    /** A table's column's hits. */
    public Hit(String column, int rows) {
      this(column, rows, Optional.empty());
    }
  }

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
    return of(table, searched, null, counts, matched);
  }

  /** As {@link #of(Table, List, int[], int)}, each column with the filter that counted it. */
  static Hits of(
      Table table, List<String> searched, List<String> filters, int[] counts, int matched) {
    var hits = new ArrayList<Hit>();
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] > 0) {
        var filter = filters == null ? Optional.<String>empty() : Optional.of(filters.get(i));
        hits.add(new Hit(searched.get(i), counts[i], filter));
      }
    }
    return new Hits(table, searched, hits, matched > Search.LIMIT);
  }
}

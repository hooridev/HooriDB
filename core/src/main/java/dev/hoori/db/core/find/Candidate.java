package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import java.util.List;
import java.util.OptionalLong;

/**
 * A table or view that a search in data may read, with what the server estimates of its size, and
 * its columns as the catalog names their types; a collection's are none, as its documents' fields
 * are sampled when it is searched.
 *
 * @param rows the server's estimate of its rows, or empty where it has none, as for a view
 * @param bytes the server's estimate of the bytes its data takes, or empty
 */
public record Candidate(Table table, OptionalLong rows, OptionalLong bytes, List<Column> columns) {

  public Candidate {
    columns = List.copyOf(columns);
  }
}

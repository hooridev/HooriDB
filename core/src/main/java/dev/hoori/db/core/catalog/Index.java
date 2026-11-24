package dev.hoori.db.core.catalog;

import java.util.List;

/**
 * An index on a table: its {@link #name}, its key {@link #columns} (column names or expressions, in
 * key order), and whether it is unique or backs the table's primary key.
 */
public record Index(
    String name, Table table, List<String> columns, boolean unique, boolean primary) {

  public Index {
    columns = List.copyOf(columns);
  }
}

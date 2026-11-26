package dev.hoori.db.core.catalog;

import java.util.List;

/**
 * An index on a table or collection: its {@link #name}, its key {@link #columns} (column names,
 * expressions, or key entries in key order), whether it is unique or backs the table's primary key,
 * and any further {@link #detail} (such as {@code TTL 3600 s · partial}, or empty when none).
 */
public record Index(
    String name,
    Table table,
    List<String> columns,
    boolean unique,
    boolean primary,
    String detail) {

  public Index {
    columns = List.copyOf(columns);
    detail = detail == null ? "" : detail;
  }

  public Index(String name, Table table, List<String> columns, boolean unique, boolean primary) {
    this(name, table, columns, unique, primary, "");
  }
}

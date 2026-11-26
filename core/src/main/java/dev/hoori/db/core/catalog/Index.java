package dev.hoori.db.core.catalog;

import java.util.List;

/**
 * An index on a table or collection: its {@link #name}, its key {@link #columns} (column names,
 * expressions, or key entries in key order), whether it is unique or backs the table's primary key,
 * any further {@link #detail} (such as {@code TTL 3600 s · partial}, or empty when none), and
 * whether it is {@link #partial}.
 *
 * @param partial whether it holds for only some rows: it has a predicate, as PostgreSQL's {@code
 *     WHERE}, or is not valid yet, so that a unique one does not name every row once
 */
public record Index(
    String name,
    Table table,
    List<String> columns,
    boolean unique,
    boolean primary,
    String detail,
    boolean partial) {

  public Index {
    columns = List.copyOf(columns);
    detail = detail == null ? "" : detail;
  }

  public Index(
      String name,
      Table table,
      List<String> columns,
      boolean unique,
      boolean primary,
      String detail) {
    this(name, table, columns, unique, primary, detail, false);
  }

  public Index(String name, Table table, List<String> columns, boolean unique, boolean primary) {
    this(name, table, columns, unique, primary, "", false);
  }
}

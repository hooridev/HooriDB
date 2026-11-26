package dev.hoori.db.core.catalog;

import java.util.List;

/**
 * A foreign key constraint between two tables: {@link #table}'s {@link #columns} reference {@link
 * #referenced}'s {@link #referencedColumns}, paired by index in key order.
 *
 * @param onUpdate what an update of a referenced row does, or null where the server does not tell
 *     the user, as MariaDB to one who may only read the table
 * @param onDelete what a delete of a referenced row does, or null as {@code onUpdate}
 */
public record ForeignKey(
    String name,
    Table table,
    List<String> columns,
    Table referenced,
    List<String> referencedColumns,
    Rule onUpdate,
    Rule onDelete) {

  public ForeignKey {
    columns = List.copyOf(columns);
    referencedColumns = List.copyOf(referencedColumns);
  }

  /** What the server does when a referenced row changes or is deleted. */
  public enum Rule {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT
  }
}

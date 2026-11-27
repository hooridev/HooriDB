package dev.hoori.db.core.catalog;

/** A table or a view, in the schema or database that holds it. */
public record Table(Container container, String name, Kind kind) {

  public enum Kind {
    /** A table, also a partitioned one. */
    TABLE,
    /** A foreign table of PostgreSQL, whose rows are elsewhere. */
    FOREIGN,
    /** A view, whose rows its query gives as they are read. */
    VIEW,
    /** A materialized view of PostgreSQL, which keeps the rows of its query until refreshed. */
    MATERIALIZED;

    /** Whether it is a view, materialized or not. */
    public boolean view() {
      return this == VIEW || this == MATERIALIZED;
    }
  }
}

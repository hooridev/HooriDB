package dev.hoori.db.core.catalog;

/** A table or a view, in the schema or database that holds it. */
public record Table(Container container, String name, Kind kind) {

  public enum Kind {
    /** A table, also a partitioned one. */
    TABLE,
    /** A foreign table of PostgreSQL, whose rows are elsewhere. */
    FOREIGN,
    /** A view, also a materialized one. */
    VIEW
  }
}

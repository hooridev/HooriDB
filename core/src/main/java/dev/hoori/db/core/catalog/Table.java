package dev.hoori.db.core.catalog;

/** A table or a view, in the schema or database that holds it. */
public record Table(Container container, String name, Kind kind) {

  public enum Kind {
    /** A table, also a partitioned or a foreign one. */
    TABLE,
    /** A view, also a materialized one. */
    VIEW
  }
}

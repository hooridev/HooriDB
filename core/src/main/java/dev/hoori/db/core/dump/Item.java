package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import java.util.Objects;

/**
 * An object of a schema, or of a database where the provider has no schemas, that an export can
 * write: a table, view, sequence, function, procedure or trigger.
 *
 * @param container the schema or database that holds it, a trigger's that of its table
 * @param arguments of a function or procedure where the server tells them apart by them, as
 *     PostgreSQL's {@code integer, text}; empty otherwise
 * @param table the table of a trigger, whose names are its own; empty otherwise
 */
public record Item(Container container, Kind kind, String name, String arguments, String table) {

  /** What an object is, in the order an export's dialog shows them. */
  public enum Kind {
    /** A table, also a partitioned one or a partition. */
    TABLE,
    /** A view, also a materialized one. */
    VIEW,
    SEQUENCE,
    FUNCTION,
    PROCEDURE,
    TRIGGER
  }

  public Item {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(table, "table");
  }

  /** An object without arguments or a table of its own, as a table, view or sequence. */
  public static Item of(Container container, Kind kind, String name) {
    return new Item(container, kind, name, "", "");
  }

  /** Whether it is a function or a procedure. */
  public boolean routine() {
    return kind == Kind.FUNCTION || kind == Kind.PROCEDURE;
  }

  /** Its name as people read it: a routine's with its arguments, a trigger's with its table. */
  public String shown() {
    return switch (kind) {
      case FUNCTION, PROCEDURE -> name + "(" + arguments + ")";
      case TRIGGER -> name + " on " + table;
      default -> name;
    };
  }
}

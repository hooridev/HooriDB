package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import java.util.Objects;
import java.util.Optional;

/**
 * An object that a view uses, or that uses a view: a table, a view or a routine, in the schema or
 * database that holds it.
 *
 * @param arguments of a routine where the server tells routines apart by them, as PostgreSQL's
 *     {@code integer, text}; empty otherwise
 */
public record Reference(Container container, Kind kind, String name, String arguments) {

  /** What the object is. */
  public enum Kind {
    TABLE,
    /** A foreign table of PostgreSQL, whose rows are elsewhere. */
    FOREIGN_TABLE,
    VIEW,
    /** A materialized view of PostgreSQL, which keeps its rows. */
    MATERIALIZED_VIEW,
    FUNCTION,
    PROCEDURE
  }

  public Reference {
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(arguments, "arguments");
  }

  /** The table or view it is, as the catalog names it; empty for a routine. */
  public Optional<Table> table() {
    return switch (kind) {
      case TABLE -> Optional.of(new Table(container, name, Table.Kind.TABLE));
      case FOREIGN_TABLE -> Optional.of(new Table(container, name, Table.Kind.FOREIGN));
      case VIEW -> Optional.of(new Table(container, name, Table.Kind.VIEW));
      case MATERIALIZED_VIEW -> Optional.of(new Table(container, name, Table.Kind.MATERIALIZED));
      case FUNCTION, PROCEDURE -> Optional.empty();
    };
  }

  /** Its name as people read it: a routine's with its arguments. */
  public String shown() {
    return switch (kind) {
      case FUNCTION, PROCEDURE -> name + "(" + arguments + ")";
      default -> name;
    };
  }
}

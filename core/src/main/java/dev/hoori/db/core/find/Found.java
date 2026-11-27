package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import java.util.Objects;

/**
 * An object whose name or definition matched a search, and where.
 *
 * @param container the schema, or the database where the provider has no schemas, that holds it
 * @param table the table or view itself, or the one that holds the column or the trigger; null for
 *     a routine
 * @param name its own name: the table's, the column's, the routine's or the trigger's
 * @param inDefinition whether the match is in the definition, else in the name
 * @param definition the text of its definition, where it has one the server gave; else null
 * @param start where the match starts, in the name or the definition
 * @param end where it ends
 */
public record Found(
    Kind kind,
    Container container,
    Table table,
    String name,
    boolean inDefinition,
    String definition,
    int start,
    int end) {

  /** What was found. */
  public enum Kind {
    TABLE,
    VIEW,
    COLUMN,
    FUNCTION,
    PROCEDURE,
    TRIGGER
  }

  public Found {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(container, "container");
    Objects.requireNonNull(name, "name");
    if (inDefinition && definition == null) {
      throw new IllegalArgumentException("A match in a definition needs the definition");
    }
  }

  /** Whether opening it shows its definition, as a routine's or a trigger's, or a match in one. */
  public boolean showsDefinition() {
    return definition != null
        && (inDefinition
            || kind == Kind.FUNCTION
            || kind == Kind.PROCEDURE
            || kind == Kind.TRIGGER);
  }
}

package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import java.util.Objects;

/**
 * A view as its tab shows it, read in one snapshot: its definition, as an export writes it, what
 * it uses and what uses it, and how its rows can be refreshed where it is materialized.
 *
 * @param concurrently whether a materialized view's rows can be refreshed {@code CONCURRENTLY}, as
 *     {@link DefinitionDialect#refreshesConcurrently} says; false for any other view
 */
public record ViewDefinition(
    Table view, Definition definition, References references, boolean concurrently) {

  public ViewDefinition {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(references, "references");
  }

  /**
   * What creates it, as one text: its statements, each ended with a semicolon, those of a
   * materialized view's indexes after its own.
   */
  public String text() {
    return String.join(";\n\n", definition.statements()) + ";\n";
  }

  /** Whether it keeps its rows, which a refresh reads anew from what it uses. */
  public boolean materialized() {
    return view.kind() == Table.Kind.MATERIALIZED;
  }
}

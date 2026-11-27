package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import java.util.List;
import java.util.Objects;

/**
 * A view as its tab shows it, read in one snapshot: its definition, as it is on its server ({@link
 * DefinitionDialect.Mode#EDIT}), what it uses and what uses it, and how its rows can be refreshed
 * where it is materialized.
 *
 * @param concurrently whether a materialized view's rows can be refreshed {@code CONCURRENTLY}, as
 *     {@link ViewDialect#refreshesConcurrently} says; false for any other view
 * @param restore what gives it back, once dropped and created anew, what its definition does not,
 *     as {@link ViewDialect#restore} says
 * @param unset what takes from it what its definition sets besides its query, as {@link
 *     ViewDialect#unset} says
 */
public record ViewDefinition(
    Table view,
    Definition definition,
    References references,
    boolean concurrently,
    List<String> restore,
    List<String> unset) {

  public ViewDefinition {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(references, "references");
    restore = List.copyOf(restore);
    unset = List.copyOf(unset);
  }

  /** A view read without what a drop takes or a replace keeps, as a server of none has. */
  public ViewDefinition(
      Table view, Definition definition, References references, boolean concurrently) {
    this(view, definition, references, concurrently, List.of(), List.of());
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

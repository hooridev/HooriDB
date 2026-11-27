package dev.hoori.db.core.definition;

import java.util.List;

/**
 * What a view uses and what uses it, as far as its server records it, each by schema or database
 * and then by name.
 *
 * @param uses the tables, views and routines its definition reads
 * @param usedBy the views, and on PostgreSQL the routines, that read it, which a drop would leave
 *     without it or take with it
 */
public record References(List<Reference> uses, List<Reference> usedBy) {

  /** A view that uses nothing and that nothing uses. */
  public static final References NONE = new References(List.of(), List.of());

  public References {
    uses = List.copyOf(uses);
    usedBy = List.copyOf(usedBy);
  }
}

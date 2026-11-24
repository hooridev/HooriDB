package dev.hoori.db.core.data;

import java.util.List;
import java.util.Objects;

/**
 * A filter condition over a table's columns or a collection's fields: either a single {@link
 * ColumnFilter} or a {@link Group} combining conditions with {@code AND} or {@code OR}.
 */
public sealed interface Condition permits ColumnFilter, Condition.Group {

  /**
   * Conditions combined with {@code OR} when {@code any} is true, else {@code AND}.
   *
   * @param any true for {@code OR}, false for {@code AND}
   * @param conditions child conditions, in order
   */
  record Group(boolean any, List<Condition> conditions) implements Condition {

    public Group {
      Objects.requireNonNull(conditions, "conditions");
      conditions = List.copyOf(conditions);
    }

    /** Combines {@code conditions} with {@code AND}. */
    public static Group and(List<? extends Condition> conditions) {
      Objects.requireNonNull(conditions, "conditions");
      return new Group(false, List.copyOf(conditions));
    }

    /** Combines {@code conditions} with {@code OR}. */
    public static Group or(List<? extends Condition> conditions) {
      Objects.requireNonNull(conditions, "conditions");
      return new Group(true, List.copyOf(conditions));
    }
  }
}

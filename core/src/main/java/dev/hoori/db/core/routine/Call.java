package dev.hoori.db.core.routine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A statement of a routine's call, with the values bound to its {@code ?} in order: each as text,
 * which the server reads as the parameter's type, or null for NULL.
 */
public record Call(String sql, List<String> values) {

  public Call {
    values = Collections.unmodifiableList(new ArrayList<>(values));
  }

  /** A statement without values. */
  public static Call of(String sql) {
    return new Call(sql, List.of());
  }
}

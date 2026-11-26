package dev.hoori.db.core.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a row of the source and a row of the target with the same key differ: one of them is not
 * there, or the values of compared columns are not the same. A row is as its {@link Cursor} read
 * it, with a value per column of its table; the lists cannot be changed.
 */
public sealed interface Difference {

  /** A row of the source whose key no row of the target has. */
  record OnlyInSource(List<Object> source) implements Difference {

    public OnlyInSource {
      source = copy(source);
    }
  }

  /** A row of the target whose key no row of the source has. */
  record OnlyInTarget(List<Object> target) implements Difference {

    public OnlyInTarget {
      target = copy(target);
    }
  }

  /**
   * Rows with the same key whose values differ in the compared columns {@code cells}, in the
   * columns' order.
   */
  record Different(List<Object> source, List<Object> target, List<Pairing.Pair> cells)
      implements Difference {

    public Different {
      source = copy(source);
      target = copy(target);
      cells = List.copyOf(cells);
    }
  }

  // Copies that hold nulls, for SQL NULL.
  private static List<Object> copy(List<Object> row) {
    return Collections.unmodifiableList(new ArrayList<>(row));
  }
}

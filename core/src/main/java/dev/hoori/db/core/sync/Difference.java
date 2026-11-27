package dev.hoori.db.core.sync;

import dev.hoori.db.core.data.Rows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a row of the source and a row of the target with the same key differ: one of them is not
 * there, or the values of compared columns are not the same. A row is as its {@link Cursor} read
 * it, with a value per column of its table; the lists cannot be changed.
 */
public sealed interface Difference {

  /**
   * The difference with its rows' values as a page holds them, {@link Rows#shown}: long texts and
   * binary data cut, so that many can be kept to show. Such a copy can be shown, not written.
   */
  default Difference shown() {
    return switch (this) {
      case OnlyInSource(var source) -> new OnlyInSource(cut(source));
      case OnlyInTarget(var target) -> new OnlyInTarget(cut(target));
      case Different(var source, var target, var cells) ->
        new Different(cut(source), cut(target), cells);
    };
  }

  /** About how many characters its rows hold in memory, as {@link Rows#characters(java.util.Collection)}. */
  default long characters() {
    return switch (this) {
      case OnlyInSource(var source) -> Rows.characters(source);
      case OnlyInTarget(var target) -> Rows.characters(target);
      case Different different ->
        Rows.characters(different.source()) + Rows.characters(different.target());
    };
  }

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

  private static List<Object> cut(List<Object> row) {
    var values = new ArrayList<>(row.size());
    row.forEach(value -> values.add(Rows.shown(value)));
    return values;
  }
}

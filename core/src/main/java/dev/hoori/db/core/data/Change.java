package dev.hoori.db.core.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A change of one row of a table: new values of some of its cells, a new row, or its deletion. A
 * row is one as {@link Rows#read} read it, with a value per column; a value is of the Java type of
 * its column's {@link Kind}, or null for SQL NULL; a column is given by its index among the
 * table's. The lists and maps cannot be changed, and a map's columns come in their order.
 */
public sealed interface Change {

  /** New values of cells of a row as it was read. */
  record Update(List<Object> row, Map<Integer, Object> values) implements Change {

    public Update {
      row = copy(row);
      values = copy(values);
    }
  }

  /** A new row, with values of some of its columns; the others take their defaults. */
  record Insert(Map<Integer, Object> values) implements Change {

    public Insert {
      values = copy(values);
    }
  }

  /** A row as it was read, to be deleted. */
  record Delete(List<Object> row) implements Change {

    public Delete {
      row = copy(row);
    }
  }

  // Copies that hold nulls, for SQL NULL.
  private static List<Object> copy(List<Object> row) {
    return Collections.unmodifiableList(new ArrayList<>(row));
  }

  private static Map<Integer, Object> copy(Map<Integer, Object> values) {
    return Collections.unmodifiableSortedMap(new TreeMap<>(values));
  }
}

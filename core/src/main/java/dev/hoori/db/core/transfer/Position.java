package dev.hoori.db.core.transfer;

import java.util.List;

/** Where the next batch of a table's rows begins, as the batch before left it. */
public sealed interface Position {

  /** At the table's first row. */
  Position START = new Start();

  /** Past its last row: nothing more to read. */
  Position END = new End();

  /** At the first row. */
  record Start() implements Position {}

  /**
   * After the row of these values of the primary key, in its order, as they were read: the rows of
   * a table with a key come in the key's order.
   */
  record After(List<Object> key) implements Position {
    public After {
      key = List.copyOf(key); // no value of a primary key is NULL
    }
  }

  /** At the row of this position, from 0: the rows of a table without a key come by position. */
  record At(long offset) implements Position {
    public At {
      if (offset < 0) {
        throw new IllegalArgumentException("At " + offset);
      }
    }
  }

  /** Past the last row. */
  record End() implements Position {}
}

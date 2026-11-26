package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Table;
import java.util.List;
import java.util.Objects;

/**
 * What a run copies: the rows of tables of one connection into tables of the same names of
 * another, or of another database or schema, of the same provider, and how.
 *
 * @param copies each table and the table its rows go into, in the order chosen; the run orders them
 *     by their foreign keys
 * @param commit when what was written is committed
 * @param onError what the run does once a table fails
 * @param batch the most rows read and written at a time
 */
public record Transfer(List<Copy> copies, Commit commit, OnError onError, int batch) {

  /** How many rows a run reads and writes at a time unless told otherwise. */
  public static final int BATCH = 1000;

  /**
   * @throws IllegalArgumentException if a batch is of fewer than one row, or a table is copied
   *     twice or into the same target twice
   */
  public Transfer {
    copies = List.copyOf(copies);
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(onError, "onError");
    if (batch < 1) {
      throw new IllegalArgumentException("A batch of " + batch + " rows");
    }
    if (copies.stream().map(Copy::from).distinct().count() != copies.size()
        || copies.stream().map(Copy::to).distinct().count() != copies.size()) {
      throw new IllegalArgumentException("A table is copied twice");
    }
  }

  /** A table, the table of the target its rows go into, and what happens where that has rows. */
  public record Copy(Table from, Table to, Existing existing) {

    public Copy {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(existing, "existing");
    }
  }

  /** What happens where the target's table has rows before the run. */
  public enum Existing {
    /** The table is not copied, and fails. */
    STOP,
    /** Its rows are deleted first. */
    EMPTY,
    /** The rows are added to them. */
    APPEND
  }

  /** When the rows written are committed. */
  public enum Commit {
    /**
     * Once all of a table's rows are written: a table that fails keeps none of them, and one that
     * was emptied first keeps that.
     */
    TABLE,
    /** After each batch: a table that fails keeps the batches before. */
    BATCH
  }

  /** What a run does once a table fails. */
  public enum OnError {
    /** It stops, and copies no table after it. */
    STOP,
    /** It skips the table and goes on with the next. */
    SKIP
  }
}

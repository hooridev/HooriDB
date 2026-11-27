package dev.hoori.db.core.transfer;

import java.util.List;

/**
 * Rows read of a table, with a value per column read, in that order, as the dialect carries it: a
 * {@code String}, {@code BigDecimal}, {@code byte[]} or null; and whether more may follow.
 */
public record Batch(List<List<Object>> rows, boolean more) {

  /** No rows, and none after them. */
  public static final Batch LAST = new Batch(List.of(), false);

  public Batch {
    rows = List.copyOf(rows);
  }

  /** About how many bytes the rows hold, as {@link #size(List)} counts each. */
  public long size() {
    return rows.stream().mapToLong(Batch::size).sum();
  }

  /**
   * About how many bytes a row holds: two a character of a text, one a byte, and a few for a
   * number or a null, for keeping a batch of large values small.
   */
  public static long size(List<Object> row) {
    long size = 0;
    for (var value : row) {
      size += switch (value) {
        case String text -> 2L * text.length();
        case byte[] bytes -> bytes.length;
        case null, default -> 16;
      };
    }
    return size;
  }
}

package dev.hoori.db.core.transfer;

import java.util.List;
import java.util.Objects;

/**
 * Rows read of a table, with a value per column read, in that order, as the dialect carries it: a
 * {@code String}, {@code BigDecimal}, {@code byte[]} or null; and where the next batch begins.
 */
public record Batch(List<List<Object>> rows, Position next) {

  public Batch {
    rows = List.copyOf(rows);
    Objects.requireNonNull(next, "next");
  }

  /**
   * About how many bytes the rows hold: two a character of a text, one a byte, and a few for a
   * number or a null, for keeping a batch of large values small.
   */
  public long size() {
    long size = 0;
    for (var row : rows) {
      for (var value : row) {
        size += switch (value) {
          case String text -> 2L * text.length();
          case byte[] bytes -> bytes.length;
          case null, default -> 16;
        };
      }
    }
    return size;
  }
}

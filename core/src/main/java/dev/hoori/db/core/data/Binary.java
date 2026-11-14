package dev.hoori.db.core.data;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * Binary data, whole or, beyond {@link Rows#LIMIT} bytes, its start, with its full length in
 * bytes. It compares by its content.
 */
public record Binary(byte[] bytes, long length) {

  public Binary {
    bytes = bytes.clone();
  }

  /** A copy of the bytes that were read. */
  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  /** Whether the data is longer than what was read. */
  public boolean cut() {
    return bytes.length < length;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Binary b && b.length == length && Arrays.equals(b.bytes, bytes);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(bytes) + Long.hashCode(length);
  }

  @Override
  public String toString() {
    return "Binary[" + HexFormat.of().formatHex(bytes) + (cut() ? "…" : "") + ", " + length + "]";
  }
}

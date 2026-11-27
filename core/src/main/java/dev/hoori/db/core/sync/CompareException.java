package dev.hoori.db.core.sync;

import java.io.Serial;

/**
 * Why two tables could not be compared row by row, in words for the user: a key's rows did not
 * come in the order the comparison pairs them by, or its columns are of kinds that sort apart.
 */
public final class CompareException extends Exception {
  @Serial
  private static final long serialVersionUID = 1L;

  public CompareException(String message) {
    super(message);
  }
}

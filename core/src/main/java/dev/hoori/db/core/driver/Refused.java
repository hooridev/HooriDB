package dev.hoori.db.core.driver;

import java.io.IOException;
import java.io.Serial;

/** A {@link Repository} was reached but answered with an error, such as a server error. */
public final class Refused extends IOException {
  @Serial
  private static final long serialVersionUID = 1L;

  public Refused(String message) {
    super(message);
  }
}

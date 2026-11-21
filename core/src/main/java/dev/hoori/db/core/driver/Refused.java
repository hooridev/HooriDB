package dev.hoori.db.core.driver;

import java.io.IOException;

/** A {@link Repository} was reached but answered with an error, such as a server error. */
public final class Refused extends IOException {
  public Refused(String message) {
    super(message);
  }
}

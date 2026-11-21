package dev.hoori.db.core.monitor;

import java.util.Objects;

/**
 * A status counter of a server, such as the statements it ran since it started.
 *
 * @param name its name, dotted where the server keeps it in a section
 * @param value its value as the server writes it
 */
public record Counter(String name, String value) {

  public Counter {
    Objects.requireNonNull(name, "name");
    value = value == null ? "" : value;
  }
}

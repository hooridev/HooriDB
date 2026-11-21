package dev.hoori.db.core.monitor;

import java.util.Objects;

/**
 * A variable or setting of a server, as it is now for every session.
 *
 * @param value its value as the server writes it, masked where its name names a credential
 * @param source where the value comes from, in the server's words, as its configuration file, the
 *     command line or a default; empty where the server does not tell
 */
public record ServerVariable(String name, String value, String source) {

  public ServerVariable {
    Objects.requireNonNull(name, "name");
    value = value == null ? "" : value;
    source = source == null ? "" : source;
  }
}

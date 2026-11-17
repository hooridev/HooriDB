package dev.hoori.db.core.connection;

import java.util.Objects;
import java.util.UUID;

/**
 * A console as it is kept between sessions: the connection it is for, the database it runs on,
 * empty for none chosen, and its text.
 */
public record StoredConsole(UUID connection, String database, String text) {

  public StoredConsole {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(text, "text");
  }
}

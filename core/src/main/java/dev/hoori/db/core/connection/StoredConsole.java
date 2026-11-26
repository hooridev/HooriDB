package dev.hoori.db.core.connection;

import java.util.Objects;
import java.util.UUID;

/**
 * A console as it is kept between sessions: either a connection's console with its database
 * (empty for none chosen) and text, or an open workspace query tab with its query id and its last
 * target connection (null before its first run; its text stays in the workspace).
 */
public record StoredConsole(UUID connection, String database, String text, UUID query) {

  public StoredConsole {
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(text, "text");
    if (query == null) {
      Objects.requireNonNull(connection, "connection");
    } else if (!database.isEmpty() || !text.isEmpty()) {
      throw new IllegalArgumentException(
          "A stored workspace query tab keeps neither database nor text");
    }
  }

  /** A connection's console, not bound to a workspace query. */
  public StoredConsole(UUID connection, String database, String text) {
    this(connection, database, text, null);
  }

  /** An open workspace query tab, with its last target connection or null when untargeted. */
  public static StoredConsole ofQuery(UUID query, UUID connection) {
    Objects.requireNonNull(query, "query");
    return new StoredConsole(connection, "", "", query);
  }
}

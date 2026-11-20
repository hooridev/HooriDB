package dev.hoori.db.core.connection;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A query the user saved on purpose, under a name, for a connection: SQL, or a statement of the
 * MongoDB shell, as the user wrote it.
 */
public record SavedQuery(UUID id, UUID connection, String name, String text, Instant saved) {

  public SavedQuery {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(saved, "saved");
  }
}

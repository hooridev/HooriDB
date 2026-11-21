package dev.hoori.db.core.log;

import java.util.Objects;
import java.util.UUID;

/** What was sent on a connection, as the statement log keeps it. */
public record Logged(UUID connection, Sent sent) {

  public Logged {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(sent, "sent");
  }
}

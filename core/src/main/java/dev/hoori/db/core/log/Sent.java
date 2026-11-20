package dev.hoori.db.core.log;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A statement or a command sent to a server: when it was sent, what for, the text as it went, with
 * a password masked, how long it took and how it ended.
 *
 * @param database the database it was sent to, where core knows it, as for a command of a server
 *     of documents; null where the caller knows it better, as the connection's own
 */
public record Sent(
    Instant at, Purpose purpose, String database, String text, Duration took, Outcome outcome) {

  public Sent {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(took, "took");
    Objects.requireNonNull(outcome, "outcome");
  }
}

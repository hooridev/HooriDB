package dev.hoori.db.core.connection;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A statement the user ran from a console, on a connection: when it started, how long it took and
 * how it ended.
 */
public record HistoryEntry(
    UUID connection, String statement, Instant started, Duration took, Outcome outcome) {

  public HistoryEntry {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(statement, "statement");
    Objects.requireNonNull(started, "started");
    Objects.requireNonNull(took, "took");
    Objects.requireNonNull(outcome, "outcome");
  }

  /** How a statement ended. */
  public sealed interface Outcome {

    /** It gave rows. */
    record Rows() implements Outcome {}

    /** It changed rows, or said it did: the count. */
    record Changed(long count) implements Outcome {}

    /** It failed: the error's message. */
    record Failed(String message) implements Outcome {
      public Failed {
        Objects.requireNonNull(message, "message");
      }
    }

    /** It was cancelled. */
    record Cancelled() implements Outcome {}
  }
}

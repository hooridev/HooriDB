package dev.hoori.db.core.execution;

import java.time.Duration;
import java.util.List;

/**
 * What one call of {@link Execution#next} read: the results in the order the server gave them,
 * ending with a {@link Failure} if the statement failed, and the messages the server sent along.
 *
 * @param more whether the last result has more rows, which the next call reads; if not, the
 *     execution is over
 * @param elapsed how long the call took, from sending the statement or asking for rows to the
 *     last one read, as the client measures it, with the network in it
 */
public record Portion(
    List<Result> results,
    List<Message> messages,
    boolean more,
    Duration elapsed,
    Transaction transaction) {

  public Portion {
    results = List.copyOf(results);
    messages = List.copyOf(messages);
  }
}

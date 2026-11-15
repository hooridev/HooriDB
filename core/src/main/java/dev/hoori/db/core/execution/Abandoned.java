package dev.hoori.db.core.execution;

import java.util.List;
import java.util.Optional;

/**
 * How giving up the rest of a statement ended ({@link Execution#abandon}): the failure of its end,
 * such as the commit of its own transaction or a server that did not answer, and the warnings the
 * server sent meanwhile, such as those of that commit.
 */
public record Abandoned(Optional<Failure> failure, List<Message> messages) {

  /** Nothing was open, or it ended without a word. */
  public static final Abandoned NOTHING = new Abandoned(Optional.empty(), List.of());

  public Abandoned {
    messages = List.copyOf(messages);
  }

  /** Whether it ended well and the server said nothing of it. */
  public boolean quiet() {
    return failure.isEmpty() && messages.isEmpty();
  }
}

package dev.hoori.db.core.log;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.query.QueryException;
import java.util.Objects;

/** How a statement or a command that was sent ended. */
public sealed interface Outcome {

  /** It ended without rows or a count. */
  Outcome DONE = new Done();

  /** The message of a failure that is not shown, as what failed carried a password. */
  String WITHHELD = "The message is not shown, as the statement holds a password.";

  /** The message of a failure that is not shown, as it may quote a row that was written. */
  String ROW_WITHHELD = "The message is not shown, as it may quote values of the row.";

  /** It gave rows: as many as were read with it, and whether it has more. */
  record Rows(long count, boolean more) implements Outcome {}

  /** It changed rows, or said it did: the count. */
  record Changed(long count) implements Outcome {}

  /** It ended without rows or a count, as a commit does. */
  record Done() implements Outcome {}

  /** It failed: the error's message. */
  record Failed(String message) implements Outcome {
    public Failed {
      Objects.requireNonNull(message, "message");
    }
  }

  /** It was cancelled. */
  record Cancelled() implements Outcome {}

  /** That many rows, all of them. */
  static Outcome rows(long count) {
    return new Rows(count, false);
  }

  /** How a call that threw ended: cancelled, or failed with its message. */
  static Outcome of(Exception e) {
    boolean cancelled = switch (e) {
      case QueryException q -> q.failure() == QueryException.Failure.CANCELLED;
      case CatalogException c -> c.failure() == CatalogException.Failure.CANCELLED;
      case DocumentException d -> d.kind() == DocumentException.Kind.CANCELLED;
      case InterruptedException i -> true;
      default -> false;
    };
    if (cancelled) {
      return new Cancelled();
    }
    var message = e.getMessage();
    return new Failed(
        message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
  }
}

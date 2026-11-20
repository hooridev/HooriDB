package dev.hoori.db.app;

import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import java.util.Locale;
import java.util.UUID;

/**
 * Which entries of the statement log show: of a connection, of a part, by how they ended, and
 * holding a text, in any case. Null, or an empty search, admits every entry.
 */
record LogFilter(UUID connection, Purpose part, Ended ended, String search) {

  static final LogFilter ALL = new LogFilter(null, null, null, "");

  /** How an entry ended, as the filter tells them apart. */
  enum Ended {
    SUCCEEDED("Succeeded"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    final String label;

    Ended(String label) {
      this.label = label;
    }

    static Ended of(Outcome outcome) {
      return switch (outcome) {
        case Outcome.Failed f -> FAILED;
        case Outcome.Cancelled c -> CANCELLED;
        case Outcome.Rows r -> SUCCEEDED;
        case Outcome.Changed c -> SUCCEEDED;
        case Outcome.Done d -> SUCCEEDED;
      };
    }
  }

  LogFilter withConnection(UUID connection) {
    return new LogFilter(connection, part, ended, search);
  }

  LogFilter withPart(Purpose part) {
    return new LogFilter(connection, part, ended, search);
  }

  LogFilter withEnded(Ended ended) {
    return new LogFilter(connection, part, ended, search);
  }

  LogFilter withSearch(String search) {
    return new LogFilter(connection, part, ended, search);
  }

  /** Whether it narrows the entries at all. */
  boolean narrows() {
    return !equals(ALL);
  }

  boolean admits(Logged entry) {
    var sent = entry.sent();
    return (connection == null || connection.equals(entry.connection()))
        && (part == null || part == sent.purpose())
        && (ended == null || ended == Ended.of(sent.outcome()))
        && (search.isEmpty()
            || sent.text().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)));
  }
}

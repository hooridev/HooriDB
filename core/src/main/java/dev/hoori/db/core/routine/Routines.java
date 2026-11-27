package dev.hoori.db.core.routine;

import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * What changing and dropping a routine sends, and sending it: Save runs the routine's CREATE
 * statement as the editor holds it, as one statement whatever its body holds, where the server
 * replaces a routine so ({@code CREATE OR REPLACE}), and otherwise after dropping the routine it
 * replaces, which it creates again from its source where the new one fails.
 */
public final class Routines {

  private Routines() {}

  // As many rows as a statement of DDL could give at a time; it gives none.
  private static final int ROWS = 1_000;

  /**
   * The statements of a change, in the order they run, and the source that puts the routine back
   * where a statement after the first fails, as when a CREATE fails after its routine was dropped;
   * null where nothing is put back.
   */
  public record Change(List<String> statements, String restore) {

    public Change {
      statements = List.copyOf(statements);
    }
  }

  /** How a change went. */
  public sealed interface Outcome {

    /** Every statement ran. */
    record Done() implements Outcome {}

    /** Nothing was sent, as the connection is read-only. */
    record Refused(String why) implements Outcome {}

    /**
     * A statement failed, the statements after it were not sent.
     *
     * @param statement the one that failed
     * @param restored where the change was to put the routine back: whether that went well, else
     *     empty
     */
    record Failed(String statement, Failure failure, Optional<Boolean> restored)
        implements Outcome {}
  }

  /**
   * The editor's text as the one statement it is: from where its first statement begins to where
   * its last ends, by the provider's rules, so that the semicolons of a body, a terminator after it
   * and a client's {@code DELIMITER} lines around it are of no matter; empty where it holds none.
   */
  public static String statement(String text, Provider provider) {
    var statements = Statements.split(text, provider);
    if (statements.isEmpty()) {
      return "";
    }
    return text.substring(statements.getFirst().start(), statements.getLast().end());
  }

  /**
   * What Save sends: the routine's CREATE statement in the editor's text; where it replaces a
   * routine that the server does not replace in one step, the drop of that routine first, with its
   * source as it was read to put it back.
   *
   * @param replaced the routine the text was read from, or null for a new one
   * @param source the source of the routine replaced as it was read, or null for a new one
   */
  public static Change save(Provider provider, Routine replaced, String source, String text) {
    var routines = provider.dialect().routines();
    var created = statement(text, provider);
    if (replaced == null || routines.replaces()) {
      return new Change(List.of(created), null);
    }
    return new Change(List.of(routines.dropFirst(replaced), created), statement(source, provider));
  }

  /** What Drop sends. */
  public static Change drop(Provider provider, Routine routine) {
    return new Change(List.of(provider.dialect().routines().drop(routine)), null);
  }

  /**
   * Sends the change's statements one after another on the connection, which commits each by
   * itself as the servers commit DDL; on a read-only one, nothing. Where a statement fails, those
   * after it are not sent, and the change's source puts the routine back where it has one. An
   * interrupt cancels the statement under way, which fails as cancelled; the routine is put back
   * all the same.
   */
  public static Outcome apply(
      Connection connection, Provider provider, Change change, boolean readOnly) {
    if (readOnly) {
      return new Outcome.Refused(
          "The connection is read-only, so routines are not created, changed or dropped on it.");
    }
    var statements = change.statements();
    for (int i = 0; i < statements.size(); i++) {
      var sql = statements.get(i);
      var failure = run(connection, provider, sql);
      if (failure.isPresent()) {
        Optional<Boolean> restored = i > 0 && change.restore() != null
            ? Optional.of(restore(connection, provider, change.restore()))
            : Optional.empty();
        return new Outcome.Failed(sql, failure.get(), restored);
      }
    }
    return new Outcome.Done();
  }

  // Puts the routine back with the interrupt put aside, which cancelled what failed.
  private static boolean restore(Connection connection, Provider provider, String source) {
    var interrupted = Thread.interrupted();
    try {
      return run(connection, provider, source).isEmpty();
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static Optional<Failure> run(Connection connection, Provider provider, String sql) {
    try (var execution = new Execution(connection, provider, sql, false, Purpose.DEFINITION)) {
      while (true) {
        var portion = execution.next(ROWS);
        for (var result : portion.results()) {
          if (result instanceof Failure f) {
            return Optional.of(f);
          }
        }
        if (!portion.more()) {
          return Optional.empty();
        }
      }
    }
  }
}

package dev.hoori.db.core.routine;

import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.sql.Names;
import dev.hoori.db.core.sql.RoutineHead;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * What changing and dropping a routine sends, and sending it. Save runs the routine's CREATE
 * statement as the editor holds it, as one statement whatever its body holds, in a session set as
 * the routine replaced was created. Where the server replaces a routine so ({@code CREATE OR
 * REPLACE}), that is all. Where it does not, as MySQL, the text is first created under a name of
 * its own and dropped again, which tells whether the server takes it, the user's privileges and
 * its definer among it, before the routine it replaces is dropped and created from the text; where
 * that CREATE still fails, the routine is created again from its source, and where the server does
 * not let the user name its definer, without it.
 */
public final class Routines {

  private Routines() {}

  // As many rows as a statement of DDL could give at a time; it gives none.
  private static final int ROWS = 1_000;

  /** What a statement of a change is for. */
  public enum Role {
    /** It sets the session as the routine replaced was created, as MySQL's sql_mode. */
    SESSION,
    /**
     * The text under a name of its own, which tells whether the server takes it before the routine
     * it replaces is dropped.
     */
    TRIAL,
    /** It drops what the trial created, whatever became of the trial. */
    TRIAL_DROP,
    /** It drops the routine replaced, where the server does not replace one in one step. */
    DROP_FIRST,
    /** The CREATE statement of the editor's text. */
    CREATE,
    /** It drops the routine. */
    DROP
  }

  /** A statement of a change, and what it is for. */
  public record Step(Role role, String sql) {}

  /**
   * How the routine replaced is put back where its CREATE fails once it was dropped: its source,
   * and the source without its definer, where it names one, for a user whom the server does not
   * let name another.
   */
  public record Restore(String source, Optional<String> withoutDefiner) {}

  /**
   * The statements of a change, in the order they run, and how the routine is put back where a
   * CREATE fails after its routine was dropped.
   *
   * @param renamed where the trial writes another name in place of the CREATE's: from that
   *     offset of the CREATE, its name's length there and the other name's; empty without a trial
   */
  public record Change(List<Step> steps, Optional<Restore> restore, Optional<Renamed> renamed) {

    public Change {
      steps = List.copyOf(steps);
    }

    /** The statements alone, in their order, as the preview shows them. */
    public List<String> statements() {
      return steps.stream().map(Step::sql).toList();
    }

    /** The statement of the role, the first where there are several; empty where it has none. */
    public Optional<String> statement(Role role) {
      return steps.stream().filter(s -> s.role() == role).map(Step::sql).findFirst();
    }

    /** Whether it drops the routine replaced before it creates it again. */
    public boolean dropsFirst() {
      return statement(Role.DROP_FIRST).isPresent();
    }
  }

  /** Where the trial names its routine another way: at the offset, of these lengths. */
  public record Renamed(int at, int written, int instead) {}

  /** How a change went. */
  public sealed interface Outcome {

    /** Every statement ran. */
    record Done() implements Outcome {}

    /** Nothing was sent, as the connection is read-only. */
    record Refused(String why) implements Outcome {}

    /**
     * A statement failed, or was not sent as the change was cancelled before it; the statements
     * after it were not sent.
     *
     * @param put where the routine had been dropped and the change put it back: how that went;
     *     else empty
     */
    record Failed(Step step, Failure failure, Optional<PutBack> put) implements Outcome {}
  }

  /** How the routine dropped was put back. */
  public sealed interface PutBack {

    /** As it was. */
    record AsItWas() implements PutBack {}

    /**
     * As it was, but with the user as its definer, as the server did not let the user name the
     * definer it had.
     */
    record WithoutDefiner(Failure refused) implements PutBack {}

    /** It could not be: the routine is gone, and its source is all that is left of it. */
    record Not(Failure failure) implements PutBack {}
  }

  /** Sends one statement of a change, as the change's connection runs it; how it failed. */
  @FunctionalInterface
  public interface Sender {
    Optional<Failure> send(String sql);
  }

  /**
   * A change as it runs, which a cancel stops until the routine it replaces is dropped: from then
   * on it runs to its end, as stopping it between its DROP and its CREATE would leave no routine.
   * Any thread may use it.
   */
  public static final class Applying {
    private final Object lock = new Object();
    private Thread thread; // the one that runs the step under way, or null
    private boolean cancelled;
    private boolean firm;

    /**
     * Cancels the change, interrupting the statement under way, unless its routine was dropped
     * already; whether it was cancelled.
     */
    public boolean cancel() {
      synchronized (lock) {
        if (firm) {
          return false;
        }
        if (!cancelled) {
          cancelled = true;
          if (thread != null) {
            thread.interrupt();
          }
        }
        return true;
      }
    }

    /** Whether a cancel no longer stops it, as the routine it replaces was dropped. */
    public boolean firm() {
      synchronized (lock) {
        return firm;
      }
    }

    // The step begins on this thread: false where the change was cancelled before it; a DROP_FIRST
    // makes it firm.
    private boolean begin(Step step) {
      synchronized (lock) {
        if (cancelled) {
          return false;
        }
        thread = Thread.currentThread();
        if (step.role() == Role.DROP_FIRST) {
          firm = true;
        }
        return true;
      }
    }

    // The step ended: an interrupt that a cancel left after it no longer reaches anything.
    private void end() {
      synchronized (lock) {
        thread = null;
        if (cancelled) {
          Thread.interrupted();
        }
      }
    }
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
   * Why Save would not replace the routine with the text, where the server does not replace one in
   * one step and so drops it first: the text creates another routine, or none that its head names;
   * empty where Save may go on.
   *
   * @param replaced the routine the text was read from, or null for a new one, which replaces none
   */
  public static Optional<String> refused(Provider provider, Routine replaced, String text) {
    var routines = provider.dialect().routines();
    if (replaced == null || routines.dropFirst(replaced).isEmpty()) {
      return Optional.empty();
    }
    var head = RoutineHead.of(statement(text, provider), provider);
    if (head.isEmpty()) {
      return Optional.of("The text does not begin as CREATE FUNCTION or CREATE PROCEDURE with a"
          + " name, so Save cannot tell that it creates " + replaced.label() + " again, which it"
          + " drops first, as the server cannot replace a routine in one step.");
    }
    var h = head.get();
    boolean procedure = replaced.kind() == Routine.Kind.PROCEDURE;
    boolean same = h.procedure() == procedure
        && routines.sameName(h.name(), replaced.name())
        && (h.qualifier().isEmpty() || h.qualifier().equals(replaced.container().name()));
    if (same) {
      return Optional.empty();
    }
    var named = (h.qualifier().isEmpty() ? "" : h.qualifier() + ".") + h.name();
    return Optional.of("The text creates the " + (h.procedure() ? "procedure " : "function ")
        + named + ", not " + replaced.label() + ". As the server cannot replace a routine in one"
        + " step, Save drops the one it replaces first, so it does not save a routine of another"
        + " name or kind: put the name back, or create the other one with New function or New"
        + " procedure.");
  }

  /**
   * What Save sends: the routine's CREATE statement in the editor's text, after the settings of
   * the session the routine replaced was created in; where the server does not replace a routine
   * in one step, the trial and the drop of that routine first, with its source as it was read to
   * put it back.
   *
   * @param replaced the routine the text was read from, or null for a new one
   * @param source the source of the routine replaced as it was read, or null for a new one and
   *     where it could not be read, which leaves nothing to put back
   * @throws IllegalArgumentException where Save is {@link #refused}
   */
  public static Change save(
      Provider provider, Routine replaced, Routine.Source source, String text) {
    if (refused(provider, replaced, text).isPresent()) {
      throw new IllegalArgumentException("Save would not replace " + replaced.label());
    }
    var routines = provider.dialect().routines();
    var created = statement(text, provider);
    var steps = new ArrayList<Step>();
    // Also where it is no longer there, and is created again from its source.
    if (source != null) {
      source.session().forEach(sql -> steps.add(new Step(Role.SESSION, sql)));
    }
    var dropFirst = replaced == null ? Optional.<String>empty() : routines.dropFirst(replaced);
    if (dropFirst.isEmpty()) {
      steps.add(new Step(Role.CREATE, created));
      return new Change(steps, Optional.empty(), Optional.empty());
    }
    // The routine's own head, which refused found.
    var head = RoutineHead.of(created, provider).orElseThrow();
    var trial = trialName();
    var written = Names.quoted(trial, provider);
    var check = new Routine(
        replaced.container(), trial, "", replaced.kind(), replaced.returns(), List.of());
    steps.add(new Step(Role.TRIAL, head.renamed(created, written)));
    steps.add(new Step(Role.TRIAL_DROP, routines.dropFirst(check).orElseThrow()));
    steps.add(new Step(Role.DROP_FIRST, dropFirst.get()));
    steps.add(new Step(Role.CREATE, created));
    Optional<Restore> restore = Optional.empty();
    if (source != null) {
      var old = statement(source.statement(), provider);
      var without = RoutineHead.of(old, provider).flatMap(h -> h.withoutDefiner(old));
      restore = Optional.of(new Restore(old, without));
    }
    var nameAt = head.nameAt();
    return new Change(
        steps,
        restore,
        Optional.of(new Renamed(nameAt.start(), nameAt.end() - nameAt.start(), written.length())));
  }

  // A name of its own for the trial, which no routine of the user's is likely to have.
  private static String trialName() {
    var bytes = new byte[6];
    ThreadLocalRandom.current().nextBytes(bytes);
    return "hooridb_check_" + HexFormat.of().formatHex(bytes).toLowerCase(Locale.ROOT);
  }

  /** What Drop sends. */
  public static Change drop(Provider provider, Routine routine) {
    return new Change(
        List.of(new Step(Role.DROP, provider.dialect().routines().drop(routine))),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Where the server says the failure is in the editor's CREATE statement, as {@link
   * Failure#place} finds it in the statement that failed: the CREATE, or the trial, whose places
   * after its name move back by as much as its name is longer; empty for a failure of another
   * statement, and where the server names no place.
   */
  public static Optional<Failure.Place> place(
      Change change, Outcome.Failed failed, Provider provider) {
    var step = failed.step();
    var at = failed.failure().place(step.sql(), provider);
    return switch (step.role()) {
      case CREATE -> at;
      case TRIAL -> change.renamed().flatMap(r -> at.map(p -> back(p, r)));
      case SESSION, TRIAL_DROP, DROP_FIRST, DROP -> Optional.empty();
    };
  }

  // A place in the trial as in the CREATE: one after the trial's name moves back by as much as
  // that name is longer, and one within it is the CREATE's name.
  private static Failure.Place back(Failure.Place place, Renamed renamed) {
    int after = renamed.at() + renamed.instead();
    int shift = renamed.instead() - renamed.written();
    int start =
        place.start() >= after ? place.start() - shift : Math.min(place.start(), renamed.at());
    int end = place.end() >= after ? place.end() - shift : renamed.at() + renamed.written();
    return new Failure.Place(start, Math.max(start, end));
  }

  /**
   * Sends the change's statements one after another on the connection, which commits each by
   * itself as the servers commit DDL; on a read-only one, nothing. Where a statement fails, those
   * after it are not sent, and where the routine was dropped, the change's source puts it back. A
   * cancel interrupts the statement under way, which fails as cancelled, until the routine is
   * dropped; the trial's routine is dropped all the same.
   */
  public static Outcome apply(
      Connection connection,
      Provider provider,
      Change change,
      boolean readOnly,
      Applying applying) {
    if (readOnly) {
      return new Outcome.Refused(
          "The connection is read-only, so routines are not created, changed or dropped on it.");
    }
    return apply(sql -> run(connection, provider, sql), change, applying);
  }

  /**
   * Sends the change's statements as {@link #apply(Connection, Provider, Change, boolean,
   * Applying)} does, each by the sender.
   */
  public static Outcome apply(Sender sender, Change change, Applying applying) {
    Outcome.Failed trial = null; // the trial's failure, once it was sent
    for (var step : change.steps()) {
      if (step.role() == Role.TRIAL_DROP) {
        var failed = dropTrial(sender, step, trial);
        if (failed != null) {
          return failed;
        }
        continue;
      }
      if (!applying.begin(step)) {
        return failed(step, Failure.cancelled());
      }
      Optional<Failure> failure;
      try {
        failure = sender.send(step.sql());
      } finally {
        applying.end();
      }
      if (failure.isEmpty()) {
        continue;
      }
      if (step.role() == Role.TRIAL) {
        trial = failed(step, failure.get()); // after the drop of what it may have created
        continue;
      }
      return new Outcome.Failed(step, failure.get(), putBack(sender, change, step));
    }
    return new Outcome.Done();
  }

  // Whatever became of the trial, its routine may be there: drops it. The trial's failure where
  // it failed, else the drop's, or null where both went through.
  private static Outcome.Failed dropTrial(Sender sender, Step step, Outcome.Failed trial) {
    var dropped = aside(() -> sender.send(step.sql()));
    if (trial != null) {
      return trial;
    }
    return dropped.map(failure -> failed(step, failure)).orElse(null);
  }

  // How the routine was put back where the step that failed is a CREATE after the drop it needs.
  private static Optional<PutBack> putBack(Sender sender, Change change, Step step) {
    if (step.role() != Role.CREATE || !change.dropsFirst() || change.restore().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(aside(() -> restore(sender, change.restore().get())));
  }

  private static Outcome.Failed failed(Step step, Failure failure) {
    return new Outcome.Failed(step, failure, Optional.empty());
  }

  // Puts the routine back from its source, and where the server refuses the user its definer,
  // without the definer.
  private static PutBack restore(Sender sender, Restore restore) {
    var failure = sender.send(restore.source());
    if (failure.isEmpty()) {
      return new PutBack.AsItWas();
    }
    if (failure.get().kind() == Failure.Kind.ACCESS_DENIED
        && restore.withoutDefiner().isPresent()) {
      var again = sender.send(restore.withoutDefiner().get());
      if (again.isEmpty()) {
        return new PutBack.WithoutDefiner(failure.get());
      }
      return new PutBack.Not(again.get());
    }
    return new PutBack.Not(failure.get());
  }

  // Runs what must run whatever cancelled the change, with the interrupt put aside and given back.
  private static <T> T aside(Supplier<T> work) {
    var interrupted = Thread.interrupted();
    try {
      return work.get();
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

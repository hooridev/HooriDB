package dev.hoori.db.core.sync;

import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The changes that make the target's rows equal to the source's, as far as a choice goes, made
 * from merges of both tables and handed on in batches, each of one kind, in the order that lets a
 * unique value move from one row to another: the deletions first, so that a changed or a new row
 * can take a value that a deleted one held, then the updates, so that a new row can take one that
 * an update gave up, and then the new rows. Values that move between rows of one kind, as two
 * updates that swap them, can still meet a unique key, which fails the batch.
 *
 * <p>A pass reads both tables whole: the changes of its first kind go on in batches as they come,
 * and those of the later kinds are held while they fit in one batch, and go on after the pass, in
 * their order. Where they do not fit, they are let go, and another pass reads the tables anew for
 * those kinds that the pass found. So tables that differ in few rows are read once, and others at
 * most once a kind, while no more than two batches of changes are held. One thread uses it.
 */
public final class Batches {

  /** How many changes a batch holds at most. */
  public static final int ROWS = 500;

  /** About how many characters of values a batch holds at most, beyond its last change. */
  public static final long CHARACTERS = 8L * 1024 * 1024;

  private Batches() {}

  /** Both tables read anew and merged; closing it gives up what is left of the reads. */
  public interface Reading extends AutoCloseable {

    /** The merge of their rows, of which nothing was told yet. */
    Merge merge();

    @Override
    void close();
  }

  /** Opens a {@link Reading} of both tables, once a pass. */
  @FunctionalInterface
  public interface Reads<E extends Exception> {
    Reading open() throws E;
  }

  /** Where the batches go: each written, or to a script. */
  @FunctionalInterface
  public interface Sink<E extends Exception> {

    /**
     * Takes the batch, of changes of one kind: empty where it was taken, else what ended the
     * changes, as a {@link Changes.Outcome.Missed} row, after which nothing more is handed on.
     */
    Optional<Changes.Outcome> take(Changes batch) throws E;
  }

  /** Told after each difference: what the pass read so far, and the changes handed on in all. */
  @FunctionalInterface
  public interface Progress {
    void read(Tally tally, long changes);
  }

  /**
   * What the passes came to.
   *
   * @param tally what the last pass read and found
   * @param changes the changes handed on and taken
   * @param ended what ended them before all were handed on, or empty
   */
  public record Made(Tally tally, long changes, Optional<Changes.Outcome> ended) {}

  /**
   * Hands on the changes that the choice takes, in batches of at most {@code rows} changes, in the
   * order the class says, reading the tables once a pass.
   *
   * @throws E as the reads or the sink failed
   * @throws QueryException where a cursor of the merge failed
   * @throws InterruptedException where the thread was interrupted
   * @throws CompareException as the merge did
   */
  public static <E extends Exception> Made make(
      Reads<E> reads, Choice choice, int rows, Sink<E> sink, Progress progress)
      throws E, QueryException, InterruptedException, CompareException {
    var steps = Arrays.stream(Step.values()).filter(step -> step.taken(choice)).toList();
    var handing = new Handing<>(sink);
    while (true) {
      var pass = new Pass(steps.isEmpty() ? null : steps.getFirst(), rows);
      Pairing pairing;
      Tally tally;
      try (var reading = reads.open()) {
        var merge = reading.merge();
        pairing = merge.pairing();
        if (!pass.read(merge, choice, handing, progress)) {
          return handing.made(merge.tally());
        }
        tally = merge.tally();
      }
      var later = steps.isEmpty() ? List.<Step>of() : steps.subList(1, steps.size());
      if (pass.fits) {
        handing.handHeld(pairing, pass.held.byStep(), later);
        return handing.made(tally);
      }
      steps = later.stream().filter(step -> step.found(tally) > 0).toList();
      if (steps.isEmpty()) {
        return handing.made(tally);
      }
    }
  }

  // Hands batches on to the sink and counts the changes it took, until it ends them.
  private static final class Handing<E extends Exception> {
    private final Sink<E> sink;
    private long made;
    private Optional<Changes.Outcome> ended = Optional.empty();

    Handing(Sink<E> sink) {
      this.sink = sink;
    }

    // Hands the changes on; false where the sink ended them.
    boolean hand(Pairing pairing, List<Change> changes) throws E {
      ended = sink.take(pairing.changes(changes));
      if (ended.isPresent()) {
        return false;
      }
      made += changes.size();
      return true;
    }

    // The changes held of the later kinds, in their order, until the sink ends them.
    void handHeld(Pairing pairing, Map<Step, List<Change>> kinds, List<Step> later) throws E {
      for (var step : later) {
        var of = kinds.getOrDefault(step, List.of());
        if (!of.isEmpty() && !hand(pairing, of)) {
          return;
        }
      }
    }

    Made made(Tally tally) {
      return new Made(tally, made, ended);
    }
  }

  // One read of both tables: the changes of its first kind handed on in batches as they come,
  // those of the later kinds held while they fit in one batch.
  private static final class Pass {
    private final Step first;
    private final Batch batch;
    private final Batch held;
    private boolean fits = true;

    Pass(Step first, int rows) {
      this.first = first;
      this.batch = new Batch(rows);
      this.held = new Batch(rows);
    }

    // Reads the merge to its end; false where the sink ended the changes.
    <E extends Exception> boolean read(
        Merge merge, Choice choice, Handing<E> handing, Progress progress)
        throws E, QueryException, InterruptedException, CompareException {
      var pairing = merge.pairing();
      for (var d = merge.next(); d != null; d = merge.next()) {
        var change = pairing.change(d, choice).orElse(null);
        if (change != null && !take(change, pairing, handing)) {
          return false;
        }
        progress.read(merge.tally(), handing.made);
      }
      return batch.empty() || handing.hand(pairing, batch.take());
    }

    // Takes a change into the batch, which goes on when full, or holds it while the held fit.
    private <E extends Exception> boolean take(Change change, Pairing pairing, Handing<E> handing)
        throws E {
      if (Step.of(change) == first) {
        batch.add(change);
        return !batch.full() || handing.hand(pairing, batch.take());
      }
      if (fits) {
        held.add(change);
        if (held.over()) {
          fits = false;
          held.take();
        }
      }
      return true;
    }
  }

  // The kinds of change in the order they are handed on.
  private enum Step {
    DELETE,
    UPDATE,
    INSERT;

    static Step of(Change change) {
      return switch (change) {
        case Change.Delete _ -> DELETE;
        case Change.Update _ -> UPDATE;
        case Change.Insert _ -> INSERT;
      };
    }

    boolean taken(Choice choice) {
      return switch (this) {
        case DELETE -> choice.delete();
        case UPDATE -> choice.update();
        case INSERT -> choice.insert();
      };
    }

    // How many differences of the tally make a change of this kind.
    long found(Tally tally) {
      return switch (this) {
        case DELETE -> tally.onlyInTarget();
        case UPDATE -> tally.different();
        case INSERT -> tally.onlyInSource();
      };
    }
  }

  // Changes collected, with about how many characters their values hold.
  private static final class Batch {
    private final int rows;
    private List<Change> changes = new ArrayList<>();
    private long characters;

    Batch(int rows) {
      this.rows = rows;
    }

    void add(Change change) {
      changes.add(change);
      characters += switch (change) {
        case Change.Delete(var row) -> Rows.characters(row);
        case Change.Update(var row, var values) ->
          Rows.characters(row) + Rows.characters(values.values());
        case Change.Insert(var values) -> Rows.characters(values.values());
      };
    }

    // As many as a batch holds.
    boolean full() {
      return changes.size() >= rows || characters >= CHARACTERS;
    }

    // More than a batch holds.
    boolean over() {
      return changes.size() > rows || changes.size() > 1 && characters > CHARACTERS;
    }

    boolean empty() {
      return changes.isEmpty();
    }

    List<Change> take() {
      var all = changes;
      changes = new ArrayList<>();
      characters = 0;
      return all;
    }

    Map<Step, List<Change>> byStep() {
      var kinds = new EnumMap<Step, List<Change>>(Step.class);
      for (var change : changes) {
        kinds.computeIfAbsent(Step.of(change), _ -> new ArrayList<>()).add(change);
      }
      return kinds;
    }
  }
}

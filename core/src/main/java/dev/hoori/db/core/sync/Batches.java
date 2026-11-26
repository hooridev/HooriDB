package dev.hoori.db.core.sync;

import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Rows;
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
  public interface Reads {
    Reading open() throws Exception;
  }

  /** Where the batches go: each written, or to a script. */
  @FunctionalInterface
  public interface Sink {

    /**
     * Takes the batch, of changes of one kind: empty where it was taken, else what ended the
     * changes, as a {@link Changes.Outcome.Missed} row, after which nothing more is handed on.
     */
    Optional<Changes.Outcome> take(Changes batch) throws Exception;
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
   * @throws Exception as the reads or the sink failed, or {@link CompareException} as the merge
   *     did
   */
  public static Made make(Reads reads, Choice choice, int rows, Sink sink, Progress progress)
      throws Exception {
    var steps = Arrays.stream(Step.values()).filter(step -> step.taken(choice)).toList();
    long made = 0;
    while (true) {
      var first = steps.isEmpty() ? null : steps.getFirst();
      var batch = new Batch(rows);
      var held = new Batch(rows);
      boolean fits = true;
      Tally tally;
      Pairing pairing;
      try (var reading = reads.open()) {
        var merge = reading.merge();
        pairing = merge.pairing();
        for (var d = merge.next(); d != null; d = merge.next()) {
          var change = pairing.change(d, choice).orElse(null);
          if (change != null && Step.of(change) == first) {
            batch.add(change);
            if (batch.full()) {
              var ended = sink.take(pairing.changes(batch.take()));
              if (ended.isPresent()) {
                return new Made(merge.tally(), made, ended);
              }
              made += batch.taken;
            }
          } else if (change != null && fits) {
            held.add(change);
            if (held.over()) {
              fits = false;
              held.take();
            }
          }
          progress.read(merge.tally(), made);
        }
        tally = merge.tally();
        if (!batch.empty()) {
          var ended = sink.take(pairing.changes(batch.take()));
          if (ended.isPresent()) {
            return new Made(tally, made, ended);
          }
          made += batch.taken;
        }
      }
      var later = steps.isEmpty() ? List.<Step>of() : steps.subList(1, steps.size());
      if (fits) {
        var kinds = held.byStep();
        for (var step : later) {
          var of = kinds.getOrDefault(step, List.of());
          if (!of.isEmpty()) {
            var ended = sink.take(pairing.changes(of));
            if (ended.isPresent()) {
              return new Made(tally, made, ended);
            }
            made += of.size();
          }
        }
        return new Made(tally, made, Optional.empty());
      }
      var found = tally;
      steps = later.stream().filter(step -> step.found(found) > 0).toList();
      if (steps.isEmpty()) {
        return new Made(tally, made, Optional.empty());
      }
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
    private int taken; // how many the last take() took

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
      taken = all.size();
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

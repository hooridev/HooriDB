package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sync.Batches;
import dev.hoori.db.core.sync.Choice;
import dev.hoori.db.core.sync.Cursor;
import dev.hoori.db.core.sync.Difference;
import dev.hoori.db.core.sync.Merge;
import dev.hoori.db.core.sync.Pairing;
import dev.hoori.db.core.sync.Tally;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a comparison of two tables does in the background, on lanes of its own that the tab hands
 * it: pairing their columns, comparing their rows, writing the target's changes in one transaction,
 * or a script of them. Each reads both tables anew, in the key's order, a portion at a time, and
 * holds no more than a portion of each, two batches of changes and the differences it keeps, each
 * bounded in rows and in characters. Nothing here touches the app's state; an interrupt of the
 * thread gives the reads and the writing up, but not a commit that has begun.
 */
final class Sync {

  /** How many rows of each table one call reads at most. */
  static final int PORTION = 500;

  /** How many differences a comparison keeps to show, of all it counts. */
  static final int KEPT = 1_000;

  /**
   * About how many characters the differences kept to show hold at most, their statements with
   * them: each value is kept cut to {@link Rows#LIMIT}, but a row may have many.
   */
  static final long KEPT_CHARACTERS = 8L * 1024 * 1024;

  /** What a give-up's failure that a session tells begins with. */
  static final String GAVE_UP = "Its end, as the comparison went on, failed: ";

  /** What a session's failure as it closed begins with. */
  static final String CLOSED = "As the comparison's session closed, giving up its rest failed: ";

  private Sync() {}

  /** A table, the lane it is read on, and where what ending a read says goes. */
  record Side(Lane lane, Table table, Delivery.Tell tell) {}

  /** The two tables compared: the source, and the target that is made equal to it. */
  record Sides(Side source, Side target) {}

  /** Where a run is, as its progress says. */
  enum Stage {
    READING,
    WRITING,
    COMMITTING
  }

  /** How far a run has come: the rows read and what was found, and the changes written. */
  record Progress(Stage stage, Tally tally, long written) {}

  /**
   * A difference kept to show, with its long values cut ({@link Difference#shown}), and the
   * statements of its change, as the preview shows them, with long values cut short.
   */
  record Kept(Difference difference, List<String> statements) {}

  /** What a comparison came to: what it found, or why the tables cannot be compared. */
  sealed interface Comparison permits Compared, NotPaired {}

  /**
   * What a comparison found: the pairing as the reads say the kinds, the source's columns as its
   * read says them, every count, and the first differences in the key's order, at most {@link
   * #KEPT} and {@link #KEPT_CHARACTERS}.
   */
  record Compared(Pairing pairing, List<Field> fields, Tally tally, List<Kept> kept)
      implements Comparison {}

  /** The tables cannot be compared, as their pairing says. */
  record NotPaired(Pairing.Unpaired why) implements Comparison {}

  /**
   * What writing the changes came to: committed, with how many it wrote, or rolled back as a row
   * was missed or the changes refused, as {@link Changes.Outcome} says.
   */
  record Applied(Tally tally, long written, Changes.Outcome outcome) {}

  /**
   * Whether a run ends as it is to, a writing by its commit and a script by taking the file's
   * place, or is given up, decided once, by whichever asks first: so that a cancel either ends the
   * run before that, or comes too late and leaves the run to say what came of it.
   */
  static final class Commit {

    private enum Decided {
      COMMITTING,
      GIVEN_UP
    }

    private final AtomicReference<Decided> decided = new AtomicReference<>();

    /** The run is to commit: whether it may, as it was not given up. */
    boolean begin() {
      return decided.compareAndSet(null, Decided.COMMITTING);
    }

    /** Gives the run up, where its commit has not begun: whether it is given up. */
    boolean cancel() {
      decided.compareAndSet(null, Decided.GIVEN_UP);
      return decided.get() == Decided.GIVEN_UP;
    }

    /** Whether the commit has begun, so that only its end tells what came of the run. */
    boolean begun() {
      return decided.get() == Decided.COMMITTING;
    }
  }

  /** How the two tables are compared, from their columns and indexes as each lane reads them. */
  static Pairing.Found pair(Sides sides) throws Exception {
    var source = sides.source();
    var target = sides.target();
    var sourceColumns = Lane.await(source.lane().submit(link -> link.columns(source.table())));
    var sourceIndexes = Lane.await(source.lane().submit(link -> link.indexes(source.table())));
    var targetColumns = Lane.await(target.lane().submit(link -> link.columns(target.table())));
    var targetIndexes = Lane.await(target.lane().submit(link -> link.indexes(target.table())));
    return Pairing.of(
        source.table(), sourceColumns, sourceIndexes, target.table(), targetColumns, targetIndexes);
  }

  /** Pairs the tables anew, and compares their rows where they pair. */
  static Comparison compare(Sides sides, Provider provider, Work.Progress<Progress> progress)
      throws Exception {
    return switch (pair(sides)) {
      case Pairing.Paired(var pairing) -> compare(sides, pairing, provider, progress);
      case Pairing.Unpaired why -> new NotPaired(why);
    };
  }

  /**
   * Compares the rows of both tables, reporting the tally as each portion is merged; the
   * differences kept are cut to show, and their statements made while their values are whole.
   */
  static Compared compare(
      Sides sides, Pairing pairing, Provider provider, Work.Progress<Progress> progress)
      throws Exception {
    try (var reading = Reading.open(sides, pairing)) {
      var merge = reading.merge();
      var paired = merge.pairing();
      var reporter = new Reporter(progress, Stage.READING);
      var kept = new ArrayList<Kept>();
      long characters = 0;
      for (var d = merge.next(); d != null; d = merge.next()) {
        if (kept.size() < KEPT && characters < KEPT_CHARACTERS) {
          var shown = d.shown();
          var statements = paired
              .change(d, Choice.ALL)
              .map(change -> paired.changes(List.of(change)).previews(provider).stream()
                  .map(statement -> statement + ";")
                  .toList())
              .orElse(List.of());
          kept.add(new Kept(shown, statements));
          characters += shown.characters() + Rows.characters(statements);
        }
        reporter.read(merge.tally(), 0);
      }
      return new Compared(paired, merge.sourceFields(), merge.tally(), List.copyOf(kept));
    }
  }

  /**
   * Makes the target's rows equal to the source's, as far as the choice goes, in one transaction
   * on the writer's lane: the rows compared anew, and the changes written a batch at a time, as
   * {@link Batches} orders them, then committed once {@code commit} lets it. Where it does not come
   * to the commit, all is rolled back.
   */
  static Applied apply(
      Sides sides,
      Lane writer,
      Pairing pairing,
      Choice choice,
      Commit commit,
      Work.Progress<Progress> progress)
      throws Exception {
    var writing = Lane.await(writer.submit(link -> link.writer(sides.target().table())));
    boolean over = false;
    try {
      var made = Batches.make(
          () -> Reading.open(sides, pairing),
          choice,
          Batches.ROWS,
          batch -> Lane.await(writer.submit(link -> writing.write(batch))),
          new Reporter(progress, Stage.WRITING));
      if (made.ended().isPresent()) {
        over = true; // rolled back by the writing
        return new Applied(made.tally(), made.changes(), made.ended().get());
      }
      progress.report(new Progress(Stage.COMMITTING, made.tally(), made.changes()));
      if (!commit.begin()) {
        throw new InterruptedException("Given up before the commit");
      }
      Lane.await(writer.submit(link -> {
        writing.commit();
        return null;
      }));
      over = true;
      return new Applied(made.tally(), made.changes(), new Changes.Outcome.Committed());
    } finally {
      if (!over) {
        // After what runs on the lane now, which may be a batch that an interrupt ends; a lane
        // that has closed took the transaction with its connection, which the server rolls back.
        try {
          writer.submit(link -> {
            writing.rollBack();
            return null;
          });
        } catch (RejectedExecutionException _) {
          // Rolled back by the server as the connection closed.
        }
      }
    }
  }

  /**
   * Writes the statements that make the target's rows equal to the source's, as far as the choice
   * goes, to the file, in one transaction, in the order that applying writes them, each as a
   * script runs it ({@link Changes#script}); the file is written beside it first and takes its
   * place at once, once whole and as {@code commit} lets it, so that a failure or a cancel before
   * leaves it as it was.
   */
  static Tally script(
      Sides sides,
      Pairing pairing,
      Choice choice,
      Provider provider,
      Path file,
      Commit commit,
      Work.Progress<Progress> progress)
      throws Exception {
    var folder = file.toAbsolutePath().getParent();
    var temporary = Files.createTempFile(folder, ".hooridb-", ".sql");
    try {
      Tally tally;
      try (Writer out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        out.write(comment("Makes the rows of "
            + Rows.qualified(provider, sides.target().table()) + " equal to those of "
            + Rows.qualified(provider, sides.source().table()) + "."));
        out.write("START TRANSACTION;\n");
        tally = Batches.make(
                () -> Reading.open(sides, pairing),
                choice,
                Batches.ROWS,
                batch -> {
                  for (var statement : batch.script(provider)) {
                    out.write(statement);
                    out.write(";\n");
                  }
                  return Optional.empty();
                },
                new Reporter(progress, Stage.READING))
            .tally();
        out.write("COMMIT;\n");
      }
      if (!commit.begin()) {
        throw new InterruptedException("Given up before the script took the file's place");
      }
      // At once or not at all: a move that is not atomic may delete the file and then fail.
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return tally;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  // A comment of SQL on one line: a name may hold a line break, after which the rest would run as
  // SQL, so each character that ends a line or controls is written as a space.
  private static String comment(String text) {
    return "-- " + text.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ") + "\n";
  }

  /**
   * The statements that the choice takes of the kept differences, in the order that applying
   * writes them: the deletions, then the updates, then the new rows.
   */
  static List<String> preview(Compared compared, Choice choice) {
    var statements = new ArrayList<String>();
    for (var kind : List.of(
        Difference.OnlyInTarget.class, Difference.Different.class, Difference.OnlyInSource.class)) {
      for (var kept : compared.kept()) {
        if (kind.isInstance(kept.difference()) && choice.takes(kept.difference())) {
          statements.addAll(kept.statements());
        }
      }
    }
    return statements;
  }

  // Reports the tally once a portion more of rows was read, so that progress does not flood; a
  // pass that reads the tables anew begins again.
  private static final class Reporter implements Batches.Progress {
    private final Work.Progress<Progress> progress;
    private final Stage stage;
    private long reported; // the rows read when progress was last reported

    Reporter(Work.Progress<Progress> progress, Stage stage) {
      this.progress = progress;
      this.stage = stage;
    }

    @Override
    public void read(Tally tally, long changes) {
      long read = tally.sourceRows() + tally.targetRows();
      if (read < reported) {
        reported = 0;
      }
      if (read - reported >= PORTION) {
        reported = read;
        progress.report(new Progress(stage, tally, changes));
      }
    }
  }

  /**
   * Both tables read in the key's order on their lanes, and merged; closing gives up what is left
   * of the reads, behind what runs on each lane now, and tells what that said.
   */
  // shortcut: MariaDB and MySQL end a read whose rows wait longer than net_write_timeout, 60 s by
  // default, as one side's can while the other reads a long run of keys it lacks; read ahead into
  // a bounded buffer, or the waiting side again, if tables that far apart are compared.
  private record Reading(Merge merge, Run source, Run target) implements Batches.Reading {

    // A side's run of the read.
    private record Run(Side side, Servers.Run run) {}

    static Reading open(Sides sides, Pairing pairing) throws Exception {
      var source = scan(sides.source(), pairing.sourceColumns(), pairing.sourceKey());
      Run target = null;
      try {
        target = scan(sides.target(), pairing.targetColumns(), pairing.targetKey());
        var merge = Merge.of(pairing, cursor(source), cursor(target));
        return new Reading(merge, source, target);
      } catch (Exception e) {
        giveUp(source);
        if (target != null) {
          giveUp(target);
        }
        throw e;
      }
    }

    private static Run scan(Side side, List<Column> columns, List<Integer> key) throws Exception {
      return new Run(
          side, Lane.await(side.lane().submit(link -> link.scan(side.table(), columns, key))));
    }

    @Override
    public void close() {
      giveUp(source);
      giveUp(target);
    }

    // Behind what runs on the lane, and what that said to the notices; a lane that has closed
    // ended the run with its session, and told that as it closed.
    private static void giveUp(Run of) {
      var side = of.side();
      try {
        side.lane().submit(link -> {
          Session.told(of.run().close(), read(side.table()), true, GAVE_UP, side.tell());
          return null;
        });
      } catch (RejectedExecutionException _) {
        // Closed with its connection.
      }
    }

    // The run's portions, each read on its lane.
    private static Cursor cursor(Run of) {
      return Cursor.of(count -> next(of.side().lane(), of.run(), count), PORTION);
    }

    private static Portion next(Lane lane, Servers.Run run, int count)
        throws QueryException, InterruptedException {
      try {
        return Lane.await(lane.submit(link -> run.next(count)));
      } catch (QueryException | InterruptedException | RuntimeException e) {
        throw e;
      } catch (Exception e) {
        // The lane failed as it opened, which a scan before would have found.
        throw QueryException.of(QueryException.Failure.CONNECTION_LOST, e.getMessage());
      }
    }
  }

  /** What a notice calls a comparison's read of the table. */
  static String read(Table table) {
    return "Compare data: read of " + table.name();
  }
}

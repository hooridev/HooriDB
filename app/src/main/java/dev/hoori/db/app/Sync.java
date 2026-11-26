package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sync.Choice;
import dev.hoori.db.core.sync.Cursor;
import dev.hoori.db.core.sync.Difference;
import dev.hoori.db.core.sync.Merge;
import dev.hoori.db.core.sync.Pairing;
import dev.hoori.db.core.sync.Tally;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * What a comparison of two tables does in the background, on lanes of its own that the tab hands
 * it: pairing their columns, comparing their rows, writing the target's changes in one transaction,
 * or a script of them. Each reads both tables anew, in the key's order, a portion at a time, and
 * holds no more than a portion of each, a batch of changes and the differences it keeps. Nothing
 * here touches the app's state; an interrupt of the thread gives the reads and the writing up.
 */
final class Sync {

  /** How many rows of each table one call reads. */
  static final int PORTION = 500;

  /** How many differences a comparison keeps to show, of all it counts. */
  static final int KEPT = 1_000;

  /** How many changes go to the server in one call of the writing. */
  static final int BATCH = 500;

  private Sync() {}

  /** A table and the lane it is read on. */
  record Side(Lane lane, Table table) {}

  /** Where a run is, as its progress says. */
  enum Stage {
    READING,
    WRITING,
    COMMITTING
  }

  /** How far a run has come: the rows read and what was found, and the changes written. */
  record Progress(Stage stage, Tally tally, long written) {}

  /**
   * What a comparison found: the pairing as the reads say the kinds, the source's columns as its
   * read says them, every count, and the first {@link #KEPT} differences in the key's order.
   */
  record Compared(Pairing pairing, List<Field> fields, Tally tally, List<Difference> kept) {}

  /**
   * What writing the changes came to: committed, with how many it wrote, or rolled back as a row
   * was missed or the changes refused, as {@link Changes.Outcome} says.
   */
  record Applied(Tally tally, long written, Changes.Outcome outcome) {}

  /** How the two tables are compared, from their columns and indexes as each lane reads them. */
  static Pairing.Found pair(Side source, Side target) throws Exception {
    var sourceColumns = Lane.await(source.lane().submit(link -> link.columns(source.table())));
    var sourceIndexes = Lane.await(source.lane().submit(link -> link.indexes(source.table())));
    var targetColumns = Lane.await(target.lane().submit(link -> link.columns(target.table())));
    var targetIndexes = Lane.await(target.lane().submit(link -> link.indexes(target.table())));
    return Pairing.of(
        source.table(), sourceColumns, sourceIndexes, target.table(), targetColumns, targetIndexes);
  }

  /** Compares the rows of both tables, reporting the tally as each portion is merged. */
  static Compared compare(
      Side source, Side target, Pairing pairing, Work.Progress<Progress> progress)
      throws Exception {
    try (var reading = Reading.open(source, target, pairing)) {
      var merge = reading.merge;
      var kept = new ArrayList<Difference>();
      for (var d = merge.next(); d != null; d = merge.next()) {
        if (kept.size() < KEPT) {
          kept.add(d);
        }
        reading.report(progress, Stage.READING, 0);
      }
      return new Compared(merge.pairing(), merge.sourceFields(), merge.tally(), List.copyOf(kept));
    }
  }

  /**
   * Makes the target's rows equal to the source's, as far as the choice goes, in one transaction
   * on the writer's lane: the rows compared anew, and the changes written a batch at a time, then
   * committed. Where it does not come to the commit, all is rolled back.
   */
  static Applied apply(
      Side source,
      Side target,
      Lane writer,
      Pairing pairing,
      Choice choice,
      Work.Progress<Progress> progress)
      throws Exception {
    var writing = Lane.await(writer.submit(link -> link.writer(target.table())));
    boolean over = false;
    try (var reading = Reading.open(source, target, pairing)) {
      var merge = reading.merge;
      var batch = new ArrayList<Change>();
      long written = 0;
      for (var d = merge.next(); d != null; d = merge.next()) {
        merge.pairing().change(d, choice).ifPresent(batch::add);
        if (batch.size() == BATCH) {
          var missed = write(writer, writing, merge.pairing(), batch);
          if (missed.isPresent()) {
            over = true;
            return new Applied(merge.tally(), written, missed.get());
          }
          written += BATCH;
          batch.clear();
        }
        reading.report(progress, Stage.WRITING, written);
      }
      var missed = write(writer, writing, merge.pairing(), batch);
      if (missed.isPresent()) {
        over = true;
        return new Applied(merge.tally(), written, missed.get());
      }
      written += batch.size();
      progress.report(new Progress(Stage.COMMITTING, merge.tally(), written));
      Lane.await(writer.submit(link -> {
        writing.commit();
        return null;
      }));
      over = true;
      return new Applied(merge.tally(), written, new Changes.Outcome.Committed());
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

  // Writes the batch on the writer's lane: empty where it was written, else what rolled back all.
  private static Optional<Changes.Outcome> write(
      Lane writer, Servers.Writing writing, Pairing pairing, List<Change> batch) throws Exception {
    if (batch.isEmpty()) {
      return Optional.empty();
    }
    var changes = pairing.changes(List.copyOf(batch));
    return Lane.await(writer.submit(link -> writing.write(changes)));
  }

  /**
   * Writes the statements that make the target's rows equal to the source's, as far as the choice
   * goes, to the file, in one transaction, as the preview shows them; the file is written beside it
   * first and takes its place once whole, so that a failure or a cancel leaves it as it was.
   */
  static Tally script(
      Side source,
      Side target,
      Pairing pairing,
      Choice choice,
      Provider provider,
      Path file,
      Work.Progress<Progress> progress)
      throws Exception {
    var folder = file.toAbsolutePath().getParent();
    var temporary = Files.createTempFile(folder, ".hooridb-", ".sql");
    try {
      Tally tally;
      try (var reading = Reading.open(source, target, pairing);
          var out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        var merge = reading.merge;
        out.write("-- Makes the rows of " + Rows.qualified(provider, target.table())
            + " equal to those of " + Rows.qualified(provider, source.table()) + ".\n");
        out.write("START TRANSACTION;\n");
        long written = 0;
        for (var d = merge.next(); d != null; d = merge.next()) {
          var change = merge.pairing().change(d, choice);
          if (change.isPresent()) {
            write(out, merge.pairing(), change.get(), provider);
            written++;
          }
          reading.report(progress, Stage.READING, written);
        }
        out.write("COMMIT;\n");
        tally = merge.tally();
      }
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      return tally;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** The statements of the change, as people read them, each ended. */
  static List<String> statements(Pairing pairing, Change change, Provider provider) {
    return pairing.changes(List.of(change)).statements(provider).stream()
        .map(statement -> statement.preview() + ";")
        .toList();
  }

  private static void write(Writer out, Pairing pairing, Change change, Provider provider)
      throws IOException {
    for (var statement : statements(pairing, change, provider)) {
      out.write(statement);
      out.write('\n');
    }
  }

  /**
   * Both tables read in the key's order on their lanes, and merged; closing gives up what is left
   * of the reads, behind what runs on each lane now.
   */
  // shortcut: MariaDB and MySQL end a read whose rows wait longer than net_write_timeout, 60 s by
  // default, as one side's can while the other reads a long run of keys it lacks; read ahead into
  // a bounded buffer, or the waiting side again, if tables that far apart are compared.
  private static final class Reading implements AutoCloseable {

    final Merge merge;
    private final Side source;
    private final Side target;
    private final Servers.Run sourceRun;
    private final Servers.Run targetRun;
    private long reported; // the rows read when progress was last reported

    private Reading(
        Side source, Side target, Servers.Run sourceRun, Servers.Run targetRun, Pairing pairing) {
      this.source = source;
      this.target = target;
      this.sourceRun = sourceRun;
      this.targetRun = targetRun;
      this.merge =
          new Merge(pairing, cursor(source.lane(), sourceRun), cursor(target.lane(), targetRun));
    }

    static Reading open(Side source, Side target, Pairing pairing) throws Exception {
      var sourceRun = Lane.await(source
          .lane()
          .submit(link -> link.scan(source.table(), pairing.sourceColumns(), pairing.sourceKey())));
      try {
        var targetRun = Lane.await(target
            .lane()
            .submit(
                link -> link.scan(target.table(), pairing.targetColumns(), pairing.targetKey())));
        return new Reading(source, target, sourceRun, targetRun, pairing);
      } catch (Exception e) {
        giveUp(source.lane(), sourceRun);
        throw e;
      }
    }

    // Reports the tally once a portion more of rows was read, so that progress does not flood.
    void report(Work.Progress<Progress> progress, Stage stage, long written) {
      var tally = merge.tally();
      long read = tally.sourceRows() + tally.targetRows();
      if (read - reported >= PORTION) {
        reported = read;
        progress.report(new Progress(stage, tally, written));
      }
    }

    @Override
    public void close() {
      giveUp(source.lane(), sourceRun);
      giveUp(target.lane(), targetRun);
    }

    // Behind what runs on the lane; a lane that has closed ended the run with its session.
    private static void giveUp(Lane lane, Servers.Run run) {
      try {
        lane.submit(link -> run.close());
      } catch (RejectedExecutionException _) {
        // Closed with its connection.
      }
    }

    // The run's portions, each read on its lane.
    private static Cursor cursor(Lane lane, Servers.Run run) {
      return Cursor.of(count -> next(lane, run, count), PORTION);
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

  /** The changes the choice takes of the kept differences, as the preview shows them. */
  static List<String> preview(Compared compared, Choice choice, Provider provider) {
    var statements = new ArrayList<String>();
    for (var difference : compared.kept()) {
      Optional<Change> change = compared.pairing().change(difference, choice);
      change.ifPresent(c -> statements.addAll(statements(compared.pairing(), c, provider)));
    }
    return statements;
  }
}

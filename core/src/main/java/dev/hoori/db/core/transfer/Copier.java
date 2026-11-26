package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.transfer.Report.Entry;
import dev.hoori.db.core.transfer.Report.Stage;
import dev.hoori.db.core.transfer.Report.State;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a {@link Transfer}: reads the rows of each table on the source in batches, and writes them
 * into the target's table of the same columns, by name, never holding more than a batch.
 *
 * <p>First it reads what it needs of every table, before it writes anything: the source's columns
 * and how many rows it has, and the target's table, its columns, whether it has rows where it must
 * not, and its foreign keys, by which the tables are ordered, those referred to first. A column of
 * the source that the target's table lacks fails the table, a column that the target generates
 * gets no value, and one of the target that the source lacks gets its default. Where tables fail
 * at this and the run stops at an error, it writes nothing at all.
 *
 * <p>Then the tables to be emptied are emptied, those that refer to others first, each in a
 * transaction of its own; then the rows are copied, a table after the tables it refers to, and
 * committed as the transfer says. A table that fails is rolled back to its last commit, and the
 * run stops or goes on with the next. What the run is told after each step, and what it ends with,
 * is a {@link Report}.
 *
 * <p>The call blocks. An interrupt of the calling thread cancels the call that runs, rolls back
 * what was not committed, and ends the run as {@link Stage#CANCELLED}, with the thread still
 * interrupted.
 */
public final class Copier {

  /** About how many bytes a batch holds at most, of which fewer rows are read where they are large. */
  static final long BUDGET = 8L * 1024 * 1024;

  private final Transfer transfer;
  private final Source source;
  private final Target target;
  private final Consumer<Report> told;
  private final Map<Transfer.Copy, Plan> plans = new HashMap<>();
  private List<Entry> tables;
  private Stage stage = Stage.PREPARING;

  // What is read of a table, and the names of the columns of the target its values go into.
  private record Plan(Selection selection, List<String> columns) {}

  // The run was cancelled.
  private static final class Cancel extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;
  }

  private Copier(Transfer transfer, Source source, Target target, Consumer<Report> told) {
    this.transfer = transfer;
    this.source = source;
    this.target = target;
    this.told = told;
    this.tables = new ArrayList<>(transfer.copies().stream().map(Entry::waiting).toList());
  }

  /**
   * Runs the transfer from the source into the target, telling how far it is after each step, on
   * the calling thread; what it ended with. A target that may not write, or one of another
   * provider, is refused before anything is read.
   */
  public static Report run(Transfer transfer, Source source, Target target, Consumer<Report> told) {
    return new Copier(transfer, source, target, told).run();
  }

  private Report run() {
    if (target.readOnly()) {
      return refused("The target connection is read-only: nothing can be copied into it.");
    }
    if (source.provider() != target.provider()) {
      return refused("Tables are copied only between connections of the same provider: "
          + source.provider().displayName() + " and " + target.provider().displayName()
          + " differ.");
    }
    tell();
    try {
      try {
        source.prepare();
        target.prepare();
      } catch (Exception e) {
        throw cancelled(e) ? new Cancel() : e;
      }
      prepare();
      if (stopsAt(State.FAILED)) {
        return stop();
      }
      target.begin();
      stage = Stage.EMPTYING;
      for (var i = tables.size() - 1; i >= 0; i--) {
        if (tables.get(i).copy().existing() == Transfer.Existing.EMPTY
            && tables.get(i).state() == State.WAITING
            && !empty(i)
            && transfer.onError() == Transfer.OnError.STOP) {
          return stop();
        }
      }
      stage = Stage.COPYING;
      for (var i = 0; i < tables.size(); i++) {
        if (tables.get(i).state() == State.WAITING
            && !copy(i)
            && transfer.onError() == Transfer.OnError.STOP) {
          return stop();
        }
      }
      stage = Stage.DONE;
    } catch (Cancel e) {
      // Where the cancel came as an InterruptedException, which cleared it, the thread stays
      // interrupted all the same.
      Thread.currentThread().interrupt();
      stage = Stage.CANCELLED;
      tables.replaceAll(table -> table.state() == State.WAITING
              || table.state() == State.EMPTYING
              || table.state() == State.COPYING
          ? table.with(State.CANCELLED)
          : table);
    } catch (Exception e) {
      // The session itself failed, as where it cannot be put in the state a copy needs.
      stage = Stage.STOPPED;
      tables.replaceAll(
          table -> table.state() == State.WAITING ? table.with(State.FAILED, why(e)) : table);
    } finally {
      quietly(target::end);
    }
    return tell();
  }

  // Reads what each table needs, and orders them; a table that does not fit fails.
  private void prepare() throws Cancel {
    var keys = new ArrayList<ForeignKey>();
    for (var i = 0; i < tables.size(); i++) {
      var copy = tables.get(i).copy();
      try {
        var problem = plan(copy);
        if (problem != null) {
          tables.set(i, tables.get(i).with(State.FAILED, problem));
        } else {
          keys.addAll(target.foreignKeys(copy.to()));
          tables.set(i, tables.get(i).total(source.count(copy.from())));
        }
      } catch (Exception e) {
        if (cancelled(e)) {
          throw new Cancel();
        }
        tables.set(i, tables.get(i).with(State.FAILED, why(e)));
      }
      tell();
    }
    var order = Order.of(tables.stream().map(t -> t.copy().to()).toList(), keys);
    var by = new HashMap<Table, Entry>();
    tables.forEach(table -> by.put(table.copy().to(), table));
    tables = new ArrayList<>(order.stream().map(by::get).toList());
    tell();
  }

  // The plan of the copy, or why it cannot be copied.
  private String plan(Transfer.Copy copy) throws Exception {
    var from = source.columns(copy.from());
    Target.Shape shape;
    try {
      shape = target.shape(copy.to());
    } catch (CatalogException e) {
      if (e.failure() != CatalogException.Failure.NOT_FOUND) {
        throw e;
      }
      return "The target has no table " + copy.to().name()
          + ": HooriDB does not create tables yet, so it must be created first.";
    }
    var chosen = new ArrayList<Column>();
    var names = new ArrayList<String>();
    var lacking = new ArrayList<String>();
    for (var column : from) {
      var into = match(shape.columns(), column.name());
      if (into == null) {
        lacking.add(column.name());
      } else if (!shape.generated().contains(into.name())) {
        chosen.add(column);
        names.add(into.name());
      }
    }
    if (!lacking.isEmpty()) {
      return "The target's " + copy.to().name() + " has no column " + String.join(", ", lacking)
          + ".";
    }
    if (chosen.isEmpty()) {
      return "The target's " + copy.to().name() + " has no column that takes a value.";
    }
    if (copy.existing() == Transfer.Existing.STOP && target.hasRows(copy.to())) {
      return "The target's " + copy.to().name() + " has rows, which the run was to stop at.";
    }
    plans.put(copy, new Plan(Selection.of(copy.from(), chosen, from), names));
    return null;
  }

  // The column of the name, or else the one column whose name differs from it only in case.
  private static Column match(List<Column> columns, String name) {
    for (var column : columns) {
      if (column.name().equals(name)) {
        return column;
      }
    }
    var alike = columns.stream()
        .filter(
            column -> column.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
        .toList();
    return alike.size() == 1 ? alike.getFirst() : null;
  }

  // Deletes the rows of the table; whether it could.
  private boolean empty(int index) throws Cancel {
    var table = tables.get(index);
    tables.set(index, table.with(State.EMPTYING));
    tell();
    try {
      target.empty(table.copy().to());
      target.commit();
      tables.set(index, table.with(State.WAITING));
      return true;
    } catch (Exception e) {
      quietly(target::rollback);
      if (cancelled(e)) {
        throw new Cancel();
      }
      tables.set(index, table.with(State.FAILED, "Its rows could not be deleted: " + why(e)));
      tell();
      return false;
    }
  }

  // Copies the table's rows, batch by batch; whether all were.
  private boolean copy(int index) throws Cancel {
    var table = tables.get(index);
    var plan = plans.get(table.copy());
    var into = table.copy().to();
    table = table.with(State.COPYING);
    tables.set(index, table);
    tell();
    long written = 0;
    long kept = 0;
    try {
      var at = Position.START;
      var count = transfer.batch();
      while (!(at instanceof Position.End)) {
        var batch = source.read(plan.selection(), at, count);
        written += target.write(into, plan.columns(), batch.rows());
        if (transfer.commit() == Transfer.Commit.BATCH) {
          target.commit();
          kept = written;
        }
        tables.set(index, table.rows(written, kept));
        tell();
        at = batch.next();
        count = next(batch);
      }
      if (written > 0) {
        target.written(into, plan.columns());
      }
      target.commit();
      tables.set(index, table.rows(written, written).with(State.COPIED));
      tell();
      return true;
    } catch (Exception e) {
      quietly(target::rollback);
      var left = table.rows(written, kept);
      if (cancelled(e)) {
        tables.set(index, left.with(State.CANCELLED));
        throw new Cancel();
      }
      tables.set(index, left.with(State.FAILED, why(e)));
      tell();
      return false;
    }
  }

  // How many rows the next batch reads: fewer where the rows of the last were large.
  private int next(Batch batch) {
    var size = batch.size();
    if (batch.rows().isEmpty() || size <= 0) {
      return transfer.batch();
    }
    var fit = BUDGET * batch.rows().size() / size;
    return Math.clamp(fit, 1, transfer.batch());
  }

  // Whether the run stops, as tables are in the state and it stops at an error.
  private boolean stopsAt(State state) {
    return transfer.onError() == Transfer.OnError.STOP
        && tables.stream().anyMatch(table -> table.state() == state);
  }

  // The run stops at a failure: the tables not copied yet are skipped.
  private Report stop() {
    stage = Stage.STOPPED;
    tables.replaceAll(table -> table.state() == State.WAITING
        ? table.with(State.SKIPPED, "Not copied, as the run stopped at a failure.")
        : table);
    return tell();
  }

  private Report refused(String problem) {
    var report = new Report(Stage.REFUSED, problem, tables);
    told.accept(report);
    return report;
  }

  private Report tell() {
    var report = new Report(stage, null, tables);
    told.accept(report);
    return report;
  }

  // What must run as the run ends, also where it was cancelled: its failure is left out, as the
  // run's own says more.
  private interface Step {
    void run() throws Exception;
  }

  private static void quietly(Step step) {
    var interrupted = Thread.interrupted();
    try {
      step.run();
    } catch (Exception _) {
      // The session failed already, or is gone: the server rolls back what it holds.
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // Whether the failure is the run's cancel.
  private static boolean cancelled(Exception e) {
    return Thread.currentThread().isInterrupted()
        || switch (e) {
          case InterruptedException _ -> true;
          case QueryException q -> q.failure() == QueryException.Failure.CANCELLED;
          case CatalogException c -> c.failure() == CatalogException.Failure.CANCELLED;
          default -> false;
        };
  }

  /** The failure in words: the server's own where it gave some. */
  static String why(Exception e) {
    var detail = switch (e) {
      case QueryException q -> q.detail();
      case CatalogException c -> c.detail();
      default -> e.getMessage();
    };
    if (detail != null && !detail.isBlank()) {
      return detail;
    }
    return switch (e) {
      case QueryException q -> words(q.failure().name());
      case CatalogException c -> words(c.failure().name());
      default -> e.getClass().getSimpleName();
    };
  }

  private static String words(String constant) {
    var words = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    return Character.toUpperCase(words.charAt(0)) + words.substring(1) + ".";
  }
}

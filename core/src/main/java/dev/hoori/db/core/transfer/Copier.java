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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Runs a {@link Transfer}: reads the rows of each table on the source, in one statement whose rows
 * come in batches, and writes them into the target's table of the same columns, by name, never
 * holding more than a batch: at most as many rows as the transfer says, and no more than about
 * {@link #BUDGET} bytes of them, or half of what one statement may carry to the target where that
 * is less.
 *
 * <p>First it reads what it needs of every table, before it writes anything: the source's columns
 * and how many rows it has, and the target's table, its columns, whether it has rows where it must
 * not, and its foreign keys, by which the tables are ordered, those referred to first. A column of
 * the source that the target's table lacks fails the table, and so do two that would go into the
 * same of its columns; a column that the target generates gets no value, and one of the target
 * that the source lacks gets its default. A table that would be read as it is written, the same
 * table of the same server, fails, and so does one to be emptied whose rows another table's
 * foreign key would delete or change with it, where that table is not emptied too. Where tables
 * fail at this and the run stops at an error, it writes nothing at all.
 *
 * <p>Then the tables are copied, a table after the tables it refers to, each in a transaction of
 * its own, committed as the transfer says. A table to be emptied is emptied first in that
 * transaction, after the tables to be emptied that refer to it, which are emptied with it, so that
 * a table that fails before its first commit keeps its rows. A table that fails is rolled back to
 * its last commit, and the run stops or goes on with the next; where that rollback fails, the run
 * stops, as the session may still hold what it wrote. What the run is told after each step, and
 * what it ends with, is a {@link Report}.
 *
 * <p>The call blocks. An interrupt of the calling thread cancels the call that runs, rolls back
 * what was not committed, and ends the run as {@link Stage#CANCELLED}, with the thread still
 * interrupted.
 */
public final class Copier {

  /** About how many bytes a batch holds at most, of which fewer rows are read where they are large. */
  static final long BUDGET = 8L * 1024 * 1024;

  // Why a table whose rows refer to rows of its own may fail as its rows are written.
  static final String SELF = "Its rows refer to rows of their own table, and are written in the"
      + " order of its key, so that a row that refers to a later one fails.";

  private final Transfer transfer;
  private final Source source;
  private final Target target;
  private final Consumer<Report> told;
  private final Map<Transfer.Copy, Plan> plans = new HashMap<>();
  private final Set<ForeignKey> keys = new LinkedHashSet<>(); // of the target's tables
  private final Set<Table> emptied = new HashSet<>(); // the target's tables emptied and committed
  private final List<Table> deleting = new ArrayList<>(); // and emptied, not committed yet
  private List<Entry> tables;
  private Stage stage = Stage.PREPARING;
  private boolean same; // whether the source and the target are on the same server and database
  private long budget = BUDGET;

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
        same = source.identity().equals(target.identity());
        var limit = target.statementLimit();
        if (limit.isPresent()) {
          // Half, as a text and bytes may take twice what they are counted as on their way.
          budget = Math.clamp(limit.getAsLong() / 2, 1, BUDGET);
        }
      } catch (Exception e) {
        throw cancelled(e) ? new Cancel() : e;
      }
      prepare();
      if (stopsAt(State.FAILED)) {
        return stop();
      }
      target.begin();
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
      // The session itself failed, as where it cannot be put in the state a copy needs, or a
      // table's rollback failed.
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
    refuseChanging();
    var order = Order.of(tables.stream().map(t -> t.copy().to()).toList(), keys);
    var by = new HashMap<Table, Entry>();
    tables.forEach(table -> by.put(table.copy().to(), table));
    tables = new ArrayList<>(order.stream().map(by::get).toList());
    tell();
  }

  // The plan of the copy, or why it cannot be copied.
  private String plan(Transfer.Copy copy) throws Exception {
    if (same && path(copy.from()).equals(path(copy.to()))) {
      return "The source and the target are the same table, which would be read as it is written.";
    }
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
    for (var name : names) {
      var shared = IntStream.range(0, names.size())
          .filter(i -> names.get(i).equals(name))
          .mapToObj(i -> chosen.get(i).name())
          .toList();
      if (shared.size() > 1) {
        return "The target's " + copy.to().name() + " has one column " + name
            + " for the source's columns " + String.join(", ", shared) + ".";
      }
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

  // Where the table is, as names that a server may take in any case.
  private static List<String> path(Table table) {
    return List.of(table.container().database().name(), table.container().name(), table.name())
        .stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .toList();
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

  // Fails each table to be emptied whose rows a foreign key of a table that is not emptied would
  // delete or change as they go, until none is left.
  private void refuseChanging() {
    for (var again = true; again; ) {
      again = false;
      var emptying = new HashSet<Table>();
      for (var table : tables) {
        if (table.state() == State.WAITING && table.copy().existing() == Transfer.Existing.EMPTY) {
          emptying.add(table.copy().to());
        }
      }
      for (var i = 0; i < tables.size(); i++) {
        var to = tables.get(i).copy().to();
        if (!emptying.contains(to)) {
          continue;
        }
        for (var key : keys) {
          if (key.referenced().equals(to)
              && !key.table().equals(to)
              && !emptying.contains(key.table())
              && changes(key.onDelete())) {
            tables.set(
                i,
                tables
                    .get(i)
                    .with(
                        State.FAILED,
                        "Emptying it would also "
                            + (key.onDelete() == ForeignKey.Rule.CASCADE ? "delete" : "change")
                            + " rows of " + key.table().name() + ", by its foreign key "
                            + key.name() + ": empty " + key.table().name()
                            + " in the copy too, or delete its rows first."));
            again = true;
            break;
          }
        }
      }
    }
  }

  // Whether what a key does as its referenced row is deleted changes the referring row.
  private static boolean changes(ForeignKey.Rule rule) {
    return rule == ForeignKey.Rule.CASCADE
        || rule == ForeignKey.Rule.SET_NULL
        || rule == ForeignKey.Rule.SET_DEFAULT;
  }

  // Copies the table's rows, batch by batch, emptying it first where it is to be; whether all
  // were copied. Where it fails and its rollback fails too, it throws the rollback's failure.
  private boolean copy(int index) throws Exception {
    var table = tables.get(index);
    var plan = plans.get(table.copy());
    var into = table.copy().to();
    long written = 0;
    long kept = 0;
    var emptying = emptying(index);
    var writing = false;
    Source.Reading reading = null;
    try {
      if (!emptying.isEmpty()) {
        table = table.with(State.EMPTYING);
        tables.set(index, table);
        tell();
        for (var other : emptying) {
          target.empty(other);
          deleting.add(other);
        }
      }
      table = table.with(State.COPYING);
      tables.set(index, table);
      tell();
      reading = source.read(plan.selection());
      for (var more = true; more; ) {
        var batch = reading.next(transfer.batch(), budget);
        more = batch.more();
        if (batch.rows().isEmpty()) {
          continue;
        }
        writing = true;
        written += target.write(into, plan.columns(), batch.rows());
        writing = false;
        if (transfer.commit() == Transfer.Commit.BATCH) {
          commit();
          kept = written;
        }
        tables.set(index, table.rows(written, kept));
        tell();
      }
      if (written > 0) {
        target.written(into, plan.columns());
      }
      commit();
      tables.set(index, table.rows(written, written).with(State.COPIED));
      tell();
      return true;
    } catch (Exception e) {
      var unended = attempt(target::rollback);
      deleting.clear();
      if (unended == null && kept > 0) {
        // The rows kept move its sequences on, as all of them would have.
        quietly(() -> {
          target.written(into, plan.columns());
          target.commit();
        });
      }
      var left = table.rows(written, kept);
      if (cancelled(e)) {
        tables.set(index, left.with(State.CANCELLED));
        throw new Cancel();
      }
      var problem =
          table.state() == State.EMPTYING ? "Its rows could not be deleted: " + why(e) : why(e);
      if (writing && refersToItself(into)) {
        problem += " " + SELF;
      }
      if (unended != null) {
        // What it wrote since its last commit may still be in the session, which the next
        // table's commit would keep: the run stops, as where the session failed.
        tables.set(
            index, left.with(State.FAILED, problem + " Rolling it back failed: " + why(unended)));
        throw unended;
      }
      tables.set(index, left.with(State.FAILED, problem));
      tell();
      return false;
    } finally {
      if (reading != null) {
        quietly(reading::close);
      }
    }
  }

  // What its transaction commits: what was written, and the tables emptied in it.
  private void commit() throws Exception {
    target.commit();
    emptied.addAll(deleting);
    deleting.clear();
  }

  // The tables emptied before the table is copied, in its transaction, those that refer to others
  // first: the table, where it is to be emptied and was not yet, and the tables to be emptied
  // that refer to it, through each other, which would keep its rows otherwise.
  private List<Table> emptying(int index) {
    if (!toEmpty(index)) {
      return List.of();
    }
    var found = new LinkedHashSet<Integer>(List.of(index));
    var frontier = new ArrayDeque<Table>(List.of(tables.get(index).copy().to()));
    while (!frontier.isEmpty()) {
      var referred = frontier.removeFirst();
      for (var key : keys) {
        if (!key.referenced().equals(referred) || key.table().equals(referred)) {
          continue;
        }
        for (var j = 0; j < tables.size(); j++) {
          if (j != index
              && tables.get(j).copy().to().equals(key.table())
              && tables.get(j).state() == State.WAITING
              && toEmpty(j)
              && found.add(j)) {
            frontier.add(key.table());
          }
        }
      }
    }
    return found.stream()
        .sorted(Comparator.reverseOrder())
        .map(j -> tables.get(j).copy().to())
        .toList();
  }

  // Whether the table is to be emptied and was not yet.
  private boolean toEmpty(int index) {
    var copy = tables.get(index).copy();
    return copy.existing() == Transfer.Existing.EMPTY && !emptied.contains(copy.to());
  }

  private boolean refersToItself(Table table) {
    return keys.stream()
        .anyMatch(key -> key.table().equals(table) && key.referenced().equals(table));
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

  // What must run as a table or the run ends, also where it was cancelled: its failure is left
  // out, as the run's own says more.
  private interface Step {
    void run() throws Exception;
  }

  private static void quietly(Step step) {
    // The session failed already, or is gone: the server rolls back what it holds.
    attempt(step);
  }

  // Runs the step, also where the run was cancelled; its failure, or null. A cancel that comes
  // as it runs leaves the thread interrupted.
  private static Exception attempt(Step step) {
    var interrupted = Thread.interrupted();
    try {
      step.run();
      return null;
    } catch (Exception e) {
      interrupted |= e instanceof InterruptedException;
      return e;
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

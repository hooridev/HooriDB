package dev.hoori.db.core.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * How far a run is, or how it ended: its stage, and each table's state, in the order they are
 * written once the run ordered them.
 *
 * @param problem why the run was refused before it began, or null
 */
public record Report(Stage stage, String problem, List<Entry> tables) {

  public Report {
    Objects.requireNonNull(stage, "stage");
    tables = List.copyOf(tables);
  }

  /** Where a run is, or how it ended. */
  public enum Stage {
    /** It reads what it needs of each table, before it writes anything. */
    PREPARING,
    /** It copies the tables' rows, emptying those to be emptied first. */
    COPYING,
    /** It ended, every table copied or skipped after a failure. */
    DONE,
    /** It stopped at a failure. */
    STOPPED,
    /** It was cancelled. */
    CANCELLED,
    /** It was refused before anything was read, as the problem says. */
    REFUSED;

    /** Whether the run has ended. */
    public boolean ended() {
      return this == DONE || this == STOPPED || this == CANCELLED || this == REFUSED;
    }
  }

  /** Where a table is. */
  public enum State {
    /** Its turn has not come yet. */
    WAITING,
    /** Its rows are deleted, with those of the tables to be emptied that refer to it. */
    EMPTYING,
    /** Its rows are copied. */
    COPYING,
    /** All its rows were copied. */
    COPIED,
    /** It failed, as the problem says. */
    FAILED,
    /** It was not copied, as the run stopped before. */
    SKIPPED,
    /** It was cancelled, or not copied, as the run was cancelled before. */
    CANCELLED
  }

  /**
   * A table of the run.
   *
   * @param written the rows written so far
   * @param kept the rows written and committed, which stay where the table fails
   * @param total how many rows the source's table has, as counted before the run; empty before
   * @param problem why the table failed, or was not copied, or null
   */
  public record Entry(
      Transfer.Copy copy,
      State state,
      long written,
      long kept,
      OptionalLong total,
      String problem) {

    public Entry {
      Objects.requireNonNull(copy, "copy");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(total, "total");
    }

    /** The table waiting for its turn. */
    static Entry waiting(Transfer.Copy copy) {
      return new Entry(copy, State.WAITING, 0, 0, OptionalLong.empty(), null);
    }

    Entry with(State state) {
      return new Entry(copy, state, written, kept, total, problem);
    }

    Entry with(State state, String problem) {
      return new Entry(copy, state, written, kept, total, problem);
    }

    Entry rows(long written, long kept) {
      return new Entry(copy, state, written, kept, total, problem);
    }

    Entry total(long total) {
      return new Entry(copy, state, written, kept, OptionalLong.of(total), problem);
    }
  }

  /** The rows written of all tables so far. */
  public long written() {
    return tables.stream().mapToLong(Entry::written).sum();
  }

  /** The rows of all tables to copy, as counted before; empty while one is not counted. */
  public OptionalLong total() {
    long total = 0;
    for (var table : tables) {
      if (table.state() == State.FAILED || table.state() == State.SKIPPED) {
        continue;
      }
      if (table.total().isEmpty()) {
        return OptionalLong.empty();
      }
      total += table.total().getAsLong();
    }
    return OptionalLong.of(total);
  }

  /** The tables in the state. */
  public List<Entry> in(State state) {
    var in = new ArrayList<Entry>();
    for (var table : tables) {
      if (table.state() == state) {
        in.add(table);
      }
    }
    return List.copyOf(in);
  }
}

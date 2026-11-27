package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.definition.Definition;
import dev.hoori.db.core.definition.DefinitionDialect;
import dev.hoori.db.core.definition.Item;
import dev.hoori.db.core.definition.Sql;
import dev.hoori.db.core.query.QueryException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * How {@link Dump} reads a provider's rows, and what its file says to the server that reads it
 * back; the objects and their definitions it reads as the provider's {@link DefinitionDialect}
 * does, in its snapshot. A provider's dialect hands one out; what it does is this package's own.
 */
public abstract sealed class DumpDialect permits MySqlFamilyDump, PostgreSqlDump {

  DumpDialect() {}

  /** Whether its servers let a session turn off the checks of foreign keys, for the rows. */
  public abstract boolean foreignKeyChecks();

  /**
   * Whether a file of the data alone keeps the tables' triggers from firing for its rows; where it
   * cannot, an export of the data alone refuses a table that has triggers.
   */
  public abstract boolean quietsTriggers();

  /**
   * Keeps other sessions, until the snapshot ends, from changing the definitions of the tables or
   * emptying them, where the server lets a snapshot do so; right after {@link
   * DefinitionDialect#begin}, before anything of them is read.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where a table no longer exists;
   *     another failure where another session holds one so that it cannot be kept
   */
  abstract void lock(Sql sql, List<Item> tables) throws QueryException;

  /**
   * How the table's rows are read and written; empty where it keeps none of its own. Of an export
   * of the data alone, which the file reads into a table that exists as the structure made it,
   * with its triggers, constraints and row security, also what goes around its rows, so that the
   * table takes them as they are.
   *
   * @param alone whether the export writes the data alone
   * @throws QueryException {@link QueryException.Failure#OTHER} where the data alone cannot go
   *     into the table as they are, as where the server cannot keep its triggers from firing
   */
  abstract Optional<Data> data(Sql sql, Item table, boolean alone) throws QueryException;

  /**
   * What lets the rows of an export of the data alone go into the tables, whose foreign keys refer
   * to each other in a circle that no order of their rows suits: before all rows, and what puts it
   * back after them; nothing for none.
   */
  abstract Around circle(List<Definition> tables);

  /** What sets up the session that reads the file back, as the values are written. */
  abstract List<String> header(Dump.Options options);

  /** What puts back what {@link #header} changed of that session. */
  abstract List<String> footer(Dump.Options options);

  /**
   * What lets the objects' drops go in the order they come, as where tables refer to each other,
   * besides each table's {@link Definition#dropKeys}; before them.
   */
  abstract List<String> beforeDrops();

  /** What puts back what {@link #beforeDrops} changed, after the drops. */
  abstract List<String> afterDrops();

  /** What creates the schemas or the database, where they do not exist, and goes to it. */
  abstract List<String> create(Sql sql, List<Container> containers) throws QueryException;

  /** The statement that begins a transaction around the rows. */
  abstract String beginTransaction();

  /**
   * A table's rows: the query that reads them, and how each is written.
   *
   * @param insert what each {@code INSERT} begins with, before its rows' values; where {@code
   *     defaults} says so, the whole statement that each row goes as
   * @param defaults whether the table has no column to write, so that each row goes as an {@code
   *     INSERT} of its defaults alone
   * @param transactional whether its engine keeps transactions: the rows of one that does not, as
   *     MyISAM, go before all others, as a server that keeps its binary log consistent with GTIDs
   *     takes them in a transaction only before any other change
   * @param around of an export of the data alone, what goes before all tables' rows and after them
   */
  record Data(
      String select,
      String insert,
      boolean defaults,
      boolean transactional,
      Reader reader,
      Around around) {}

  /** What goes before the rows of all tables, and what after them. */
  record Around(List<String> before, List<String> after) {

    static final Around NOTHING = new Around(List.of(), List.of());

    Around {
      before = List.copyOf(before);
      after = List.copyOf(after);
    }
  }

  /** How the values of a query's columns are read, from what the server says of them. */
  interface Reader {
    List<Value> values(ResultSetMetaData metadata) throws SQLException;
  }

  /**
   * How a column's value is read, as a value {@link dev.hoori.db.core.data.RowsDialect#literal}
   * writes: null, a boolean, a number, a text, or binary data.
   */
  interface Value {
    Object read(ResultSet rows, int index) throws SQLException;
  }
}

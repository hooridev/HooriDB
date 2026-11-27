package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.definition.Definition;
import dev.hoori.db.core.definition.DefinitionDialect;
import dev.hoori.db.core.definition.Item;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
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
public abstract class DumpDialect {

  DumpDialect() {}

  /** Whether its servers let a session turn off the checks of foreign keys, for the rows. */
  public abstract boolean foreignKeyChecks();

  /**
   * Keeps other sessions, until the snapshot ends, from changing the definitions of the tables or
   * emptying them, where the server lets a snapshot do so; right after {@link
   * DefinitionDialect#begin}, before anything of them is read.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where a table no longer exists;
   *     another failure where another session holds one so that it cannot be kept
   */
  abstract void lock(Connection connection, Provider provider, List<Item> tables)
      throws QueryException;

  /** How the table's rows are read and written; empty where it keeps none of its own. */
  abstract Optional<Data> data(Connection connection, Provider provider, Item table)
      throws QueryException;

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
  abstract List<String> create(Connection connection, Provider provider, List<Container> containers)
      throws QueryException;

  /** The statement that begins a transaction around the rows. */
  abstract String beginTransaction();

  /** A table's rows: the query that reads them, and how each is written. */
  record Data(String select, String insert, Reader reader) {}

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

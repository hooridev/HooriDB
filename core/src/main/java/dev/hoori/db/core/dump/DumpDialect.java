package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * How {@link Dump} reads a provider's objects, their definitions and rows, and what its file says
 * to the server that reads it back. A provider's dialect hands one out; what it does is this
 * package's own.
 */
public abstract class DumpDialect {

  DumpDialect() {}

  /** Whether its servers let a session turn off the checks of foreign keys, for the rows. */
  public abstract boolean foreignKeyChecks();

  /**
   * Begins the one transaction, read only and in one snapshot, that everything is read in, with
   * the settings in which the definitions and values are written as the file's {@link #header}
   * reads them back.
   */
  abstract void begin(Connection connection, Provider provider) throws QueryException;

  /** Ends what {@link #begin} began, and puts back what it changed of the session. */
  abstract void end(Connection connection, Provider provider) throws QueryException;

  /**
   * The objects of the schema or database, those of the database's own schemas where it has
   * schemas, by schema and then as {@link Item.Kind} orders them; read in a snapshot.
   */
  abstract List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException;

  /**
   * The definitions of the objects, in their order, as they are now.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where one no longer exists
   */
  abstract List<Definition> define(Connection connection, Provider provider, List<Item> items)
      throws QueryException;

  /** How the table's rows are read and written; null where it keeps none of its own. */
  abstract Data data(Connection connection, Provider provider, Item table) throws QueryException;

  /** What sets up the session that reads the file back, as the values are written. */
  abstract List<String> header(Dump.Options options);

  /** What puts back what {@link #header} changed of that session. */
  abstract List<String> footer(Dump.Options options);

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

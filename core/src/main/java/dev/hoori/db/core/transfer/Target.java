package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The session a run writes on. Each call blocks, and an interrupt of the calling thread cancels
 * it, as {@link Source}'s do. From {@link #begin} on it writes in transactions, each ended by
 * {@link #commit} or {@link #rollback}, until {@link #end} turns auto-commit on again.
 */
public interface Target {

  Provider provider();

  /** Whether the session may not write, as that of a read-only connection. */
  boolean readOnly();

  /** Puts the session in the state a copy writes in, as the dialect says. */
  void prepare() throws QueryException, CatalogException, InterruptedException;

  /** What names the server and database the session is on, as {@link Source#identity}. */
  String identity() throws QueryException, CatalogException, InterruptedException;

  /**
   * The most bytes one statement may carry to the server, where it has such a limit, as the
   * dialect reads it; empty where it has none.
   */
  OptionalLong statementLimit() throws QueryException, CatalogException, InterruptedException;

  /** The table's columns and which of them the server generates, which take no value. */
  Shape shape(Table table) throws QueryException, CatalogException, InterruptedException;

  /** A table's columns, in their order, and the names of those the server generates. */
  record Shape(List<Column> columns, Set<String> generated) {
    public Shape {
      columns = List.copyOf(columns);
      generated = Set.copyOf(generated);
    }
  }

  /** The foreign keys whose referencing or referenced table is the table. */
  List<ForeignKey> foreignKeys(Table table)
      throws QueryException, CatalogException, InterruptedException;

  /** Whether the table has a row. */
  boolean hasRows(Table table) throws QueryException, CatalogException, InterruptedException;

  /** Turns auto-commit off, where it is on: what follows is written in a transaction. */
  void begin() throws QueryException, CatalogException, InterruptedException;

  /** Deletes all rows of the table; how many. */
  long empty(Table table) throws QueryException, CatalogException, InterruptedException;

  /** Inserts the rows, with a value for each of the named columns; how many it inserted. */
  long write(Table table, List<String> columns, List<List<Object>> rows)
      throws QueryException, CatalogException, InterruptedException;

  /** Does what the table needs once rows were written into the columns, as the dialect says. */
  void written(Table table, List<String> columns)
      throws QueryException, CatalogException, InterruptedException;

  /** Commits what was written since the last commit or rollback. */
  void commit() throws QueryException, CatalogException, InterruptedException;

  /** Rolls back what was written since the last commit or rollback. */
  void rollback() throws QueryException, CatalogException, InterruptedException;

  /** Turns auto-commit on again, where it is off, rolling back what was not committed. */
  void end() throws QueryException, CatalogException, InterruptedException;
}

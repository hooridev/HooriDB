package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.util.List;
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
  void prepare() throws Exception;

  /** The table's columns and which of them the server generates, which take no value. */
  Shape shape(Table table) throws Exception;

  /** A table's columns, in their order, and the names of those the server generates. */
  record Shape(List<Column> columns, Set<String> generated) {
    public Shape {
      columns = List.copyOf(columns);
      generated = Set.copyOf(generated);
    }
  }

  /** The foreign keys whose referencing or referenced table is the table. */
  List<ForeignKey> foreignKeys(Table table) throws Exception;

  /** Whether the table has a row. */
  boolean hasRows(Table table) throws Exception;

  /** Turns auto-commit off, where it is on: what follows is written in a transaction. */
  void begin() throws Exception;

  /** Deletes all rows of the table; how many. */
  long empty(Table table) throws Exception;

  /** Inserts the rows, with a value for each of the named columns; how many it inserted. */
  long write(Table table, List<String> columns, List<List<Object>> rows) throws Exception;

  /** Does what the table needs once rows were written into the columns, as the dialect says. */
  void written(Table table, List<String> columns) throws Exception;

  /** Commits what was written since the last commit or rollback. */
  void commit() throws Exception;

  /** Rolls back what was written since the last commit or rollback. */
  void rollback() throws Exception;

  /** Turns auto-commit on again, where it is off, rolling back what was not committed. */
  void end() throws Exception;
}

package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.util.List;

/**
 * The session a run reads its tables on. Each call blocks, and an interrupt of the calling thread
 * cancels it; what fails throws the failure of what it used, such as a {@link
 * dev.hoori.db.core.query.QueryException}, or an {@link InterruptedException} where it was
 * cancelled.
 */
public interface Source {

  Provider provider();

  /** Puts the session in the state a copy reads in, as the dialect says. */
  void prepare() throws Exception;

  /** The table's columns, in their order, with their places in the primary key. */
  List<Column> columns(Table table) throws Exception;

  /** How many rows the table has. */
  long count(Table table) throws Exception;

  /**
   * At most {@code count} rows of the selection from the position on, and where the rows after
   * them begin: by the key, or by position where there is none. Each batch is read on its own, so
   * that rows inserted, deleted or given another key meanwhile may be read twice or not at all.
   */
  Batch read(Selection selection, Position from, int count) throws Exception;
}

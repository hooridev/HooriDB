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

  /**
   * What names the server and database the session is on, as the dialect reads it: two sessions on
   * the same give the same.
   */
  String identity() throws Exception;

  /** The table's columns, in their order, with their places in the primary key. */
  List<Column> columns(Table table) throws Exception;

  /** How many rows the table has. */
  long count(Table table) throws Exception;

  /**
   * Starts reading the selection's rows, in the order of its key where it has one: in one
   * statement, whose rows come a batch at a time. Until the reading is closed, the session serves
   * nothing else.
   */
  Reading read(Selection selection) throws Exception;

  /** A table's rows as they are read, a batch at a time. */
  interface Reading {

    /**
     * The next rows: at most {@code count}, and no more than about {@code budget} bytes of them, as
     * {@link Batch#size(List)} counts, but for a first row larger than that; none, and no more,
     * once all were read.
     */
    Batch next(int count, long budget) throws Exception;

    /**
     * Ends the reading, giving up the rows not read yet; it ends within a bound, an interrupt or
     * not.
     */
    void close() throws Exception;
  }
}

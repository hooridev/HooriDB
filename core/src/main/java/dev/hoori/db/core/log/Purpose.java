package dev.hoori.db.core.log;

/** What a statement or a command was sent for. */
public enum Purpose {
  /** Reading the catalog: databases, schemas, tables and their columns. */
  CATALOG,
  /** Reading a table's rows: a page, a whole value, a count or an estimate of its rows. */
  ROWS,
  /**
   * Writing changes of a table's rows: checking whether the table can be changed, checking each row
   * and writing the changes. A failure's message is not recorded, as it may quote the row, also
   * columns that were not read.
   */
  CHANGES,
  /** The planner's estimate of the rows a statement gives, before it runs. */
  ESTIMATE,
  /** A statement the user ran, and its cancel. */
  STATEMENT,
  /** A transaction's end, auto-commit, and asking for their status. */
  TRANSACTION,
  /** The database the session is on. */
  SESSION,
  /** The server monitor's cancel of a statement and end of a session. */
  MONITOR,
  /**
   * Copying tables' rows into another connection's or database's tables: counting and reading the
   * source's rows, and emptying and writing the target's. A write's failure's message is not
   * recorded, as it may quote a row.
   */
  TRANSFER
}

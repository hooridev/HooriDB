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
  /** Reading the rows of tables whole, as an export to files reads them. */
  EXPORT,
  /**
   * Writing the rows of a file into a table: emptying it first, the inserts, and the savepoints
   * around them. A failure's message is not recorded, as it may quote a row of the file.
   */
  IMPORT
}

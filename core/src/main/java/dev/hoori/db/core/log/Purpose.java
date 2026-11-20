package dev.hoori.db.core.log;

/** What a statement or a command was sent for. */
public enum Purpose {
  /** Reading the catalog: databases, schemas, tables and their columns. */
  CATALOG,
  /** Reading a table's rows: a page, a whole value, a count or an estimate of its rows. */
  ROWS,
  /** The planner's estimate of the rows a statement gives, before it runs. */
  ESTIMATE,
  /** A statement the user ran, and its cancel. */
  STATEMENT,
  /** A transaction's end, auto-commit, and asking for their status. */
  TRANSACTION,
  /** The database the session is on. */
  SESSION
}

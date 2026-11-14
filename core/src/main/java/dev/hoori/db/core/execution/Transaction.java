package dev.hoori.db.core.execution;

/**
 * Whether the connection is in a transaction after a portion, as far as that is known without
 * asking the server. pgjdbc keeps PostgreSQL's state from every message of the server, unless the
 * connection is closed. MariaDB and MySQL tell it only when a statement's last result ended well;
 * while rows are still to come, and after a failure or a cancel, it is {@link #UNKNOWN}, as a
 * deadlock, for one, rolls the transaction back without a word.
 */
public enum Transaction {
  /** No transaction is open: with auto-commit, the statement's work is committed. */
  NONE,
  /** A transaction is open, which a commit or a rollback ends. */
  OPEN,
  /** PostgreSQL's transaction failed: it takes nothing more but a rollback. */
  FAILED,
  /** Not known: not said since the statement began, or by a driver this code does not know. */
  UNKNOWN
}

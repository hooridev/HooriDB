package dev.hoori.db.core.execution;

/**
 * Whether the connection is in a transaction after a portion, as the driver last heard from the
 * server, without asking it. PostgreSQL tells all three states after every message. MariaDB and
 * MySQL tell only whether one is open, and only when a statement ends well: a failure leaves the
 * state from before, which is right but for a deadlock or a lock wait timeout that rolled the
 * transaction back.
 */
public enum Transaction {
  /** No transaction is open: with auto-commit, the statement's work is committed. */
  NONE,
  /** A transaction is open, which a commit or a rollback ends. */
  OPEN,
  /** PostgreSQL's transaction failed: it takes nothing more but a rollback. */
  FAILED,
  /** The driver does not say, as one that this code does not know. */
  UNKNOWN
}

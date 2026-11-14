package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;

/**
 * Whether the connection is in a transaction of the caller's after a portion, as far as that is
 * known without asking the server; {@link #of} asks. pgjdbc keeps PostgreSQL's state from every
 * message of the server, unless the connection is closed. MariaDB and MySQL tell it only when a
 * statement's last result ended well; while rows are still to come, and after a failure or a
 * cancel, it is {@link #UNKNOWN}, as a deadlock, for one, rolls the transaction back without a
 * word. A transaction that an {@link Execution} keeps of its own while it reads rows is none of the
 * caller's: it ends with the statement.
 */
public enum Transaction {
  /** No transaction is open: with auto-commit, the statement's work is committed. */
  NONE,
  /** A transaction is open, which a commit or a rollback ends. */
  OPEN,
  /** PostgreSQL's transaction failed: it takes nothing more but a rollback. */
  FAILED,
  /** Not known: not said since the statement began, or by a driver this code does not know. */
  UNKNOWN;

  // MariaDB's and MySQL's statement that does nothing, whose answer carries the server's status.
  private static final String NOTHING = "DO 0";

  /**
   * The connection's transaction as the server has it now, for when a portion did not say: on
   * PostgreSQL pgjdbc's, without a round trip; on MariaDB and MySQL from the status that comes
   * with the answer to {@code DO 0}, which starts no transaction. Nothing else may run on the
   * connection meanwhile; an interrupt cancels it, as {@link Query} does.
   *
   * @throws QueryException as {@link Query} reports it, such as for a lost connection
   */
  public static Transaction of(Connection connection, Provider provider) throws QueryException {
    if (provider == Provider.POSTGRESQL) {
      return Drivers.transaction(connection);
    }
    return Query.run(connection, provider, NOTHING, statement -> {
      statement.execute();
      return Drivers.transaction(Drivers.status(provider, connection));
    });
  }
}

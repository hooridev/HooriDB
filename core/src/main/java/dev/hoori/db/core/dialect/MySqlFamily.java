package dev.hoori.db.core.dialect;

import dev.hoori.db.core.execution.Transaction;
import java.sql.Connection;
import java.util.Optional;
import java.util.Set;

/** What MariaDB and MySQL do alike; their drivers differ. */
abstract sealed class MySqlFamily implements Dialect permits MariaDb, MySql {

  // Bits of the server status that MariaDB and MySQL send with every OK and EOF.
  static final int SERVER_STATUS_IN_TRANS = 1;
  static final int SERVER_STATUS_AUTOCOMMIT = 2;

  // MariaDB's and MySQL's statement that does nothing, whose answer carries the server's status.
  private static final Optional<String> NOTHING = Optional.of("DO 0");

  /**
   * The server status that MariaDB's and MySQL's drivers last read, or -1. It is current only
   * right after a result ended well: an error carries none, and MySQL Connector/J clears it while
   * it streams rows and once it closes them.
   */
  @Override
  public abstract int status(Connection connection);

  @Override
  public Transaction transaction(Connection connection, int status) {
    if (status < 0) {
      return Transaction.UNKNOWN;
    }
    return (status & SERVER_STATUS_IN_TRANS) != 0 ? Transaction.OPEN : Transaction.NONE;
  }

  /** Whether auto-commit is on as the status says, or null where it is not known. */
  @Override
  public Boolean autoCommit(Connection connection, int status) {
    return status < 0 ? null : (status & SERVER_STATUS_AUTOCOMMIT) != 0;
  }

  @Override
  public Optional<String> statusQuery() {
    return NOTHING;
  }

  @Override
  public int fetchSize(int count) {
    return count == Integer.MAX_VALUE ? 0 : count + 1;
  }

  @Override
  public Set<String> queriesInOwnTransaction() {
    return Set.of();
  }

  // MariaDB and MySQL send their warnings only when asked, which they cannot be while rows are on
  // their way.
  @Override
  public boolean warningsKeptByDriver() {
    return false;
  }

  // MariaDB sends the rows while they are read, and waits for the client; MySQL likewise.
  @Override
  public boolean pendingRowsHoldConnection() {
    return true;
  }
}

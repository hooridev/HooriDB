package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection's transaction and its auto-commit, as the one that keeps them has them: on
 * MariaDB and MySQL the server, which sends both in the status of each answer; on PostgreSQL
 * pgjdbc, which keeps the transaction from every message of the server and the auto-commit itself.
 *
 * @param autoCommit whether each statement commits by itself; null where that is not known, as
 *     with a driver whose status cannot be read
 */
public record Status(Transaction transaction, Boolean autoCommit) {

  // MariaDB's and MySQL's statement that does nothing, whose answer carries the server's status.
  private static final String NOTHING = "DO 0";

  /**
   * Both as they are now: on PostgreSQL pgjdbc's, without a round trip; on MariaDB and MySQL from
   * the status that comes with the answer to {@code DO 0}, which starts no transaction, after which
   * MySQL Connector/J's own idea of auto-commit is set to the server's, as a statement that failed
   * may have changed it without telling the driver. Nothing else may run on the connection
   * meanwhile; an interrupt cancels it, as {@link Query} does.
   *
   * @throws QueryException as {@link Query} reports it, such as for a lost connection
   */
  public static Status of(Connection connection, Provider provider) throws QueryException {
    if (provider == Provider.POSTGRESQL) {
      return new Status(Drivers.transaction(connection), autoCommit(connection));
    }
    return Query.run(connection, provider, NOTHING, statement -> {
      statement.execute();
      int status = Drivers.status(provider, connection);
      Drivers.followAutoCommit(provider, connection, status);
      return new Status(Drivers.transaction(status), Drivers.autoCommit(status));
    });
  }

  // pgjdbc's, which it keeps itself; not known on a closed connection.
  static Boolean autoCommit(Connection connection) {
    try {
      return connection.isClosed() ? null : connection.getAutoCommit();
    } catch (SQLException | RuntimeException e) {
      return null;
    }
  }
}

package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;

/**
 * The connection's transaction and its auto-commit, as the one that keeps them has them: on
 * MariaDB and MySQL the server, which sends both in the status of each answer; on PostgreSQL
 * pgjdbc, which keeps the transaction from every message of the server and the auto-commit itself.
 *
 * @param autoCommit whether each statement commits by itself; null where that is not known, as
 *     with a driver whose status cannot be read
 */
public record Status(Transaction transaction, Boolean autoCommit) {

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
    var session = provider.dialect().session();
    var nothing = session.statusQuery();
    if (nothing.isEmpty()) {
      return new Status(session.transaction(connection, -1), session.autoCommit(connection, -1));
    }
    return Journal.sql(
        provider,
        Purpose.TRANSACTION,
        nothing.get(),
        status -> Outcome.DONE,
        () -> Query.run(connection, provider, nothing.get(), statement -> {
          statement.execute();
          int status = session.status(connection);
          session.followAutoCommit(connection, status);
          return new Status(
              session.transaction(connection, status), session.autoCommit(connection, status));
        }));
  }
}

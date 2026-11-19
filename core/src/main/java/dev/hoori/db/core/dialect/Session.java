package dev.hoori.db.core.dialect;

import dev.hoori.db.core.execution.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

/**
 * What a driver does on an open connection beyond JDBC: where the transaction and auto-commit are
 * kept, how a statement's rows come and are given up, and how it is cancelled. Its classes are
 * reached by reflection, as the drivers are loaded in a class loader of their own; where a method
 * is missing, as in a version that lacks it, the answer is the one JDBC alone gives.
 */
public interface Session {

  /**
   * The server status that the driver last read, or -1 where it reads none. It is current only
   * right after a result ended well.
   */
  int status(Connection connection);

  /**
   * The transaction as the one who keeps it has it: the driver, from the connection, or the server,
   * from the status; unknown where neither tells.
   */
  Transaction transaction(Connection connection, int status);

  /** Whether auto-commit is on, kept as {@link #transaction}; null where it is not known. */
  Boolean autoCommit(Connection connection, int status);

  /**
   * The statement that does nothing and starts no transaction, whose answer carries the server's
   * status; empty where the driver keeps the status itself, current without a round trip.
   */
  Optional<String> statusQuery();

  /**
   * Has the driver's own idea of auto-commit say what the status says, where a statement may
   * change it on the server behind the driver's back.
   */
  void followAutoCommit(Connection connection, int status) throws SQLException;

  /** Keeps the driver from changing the session's time limits as it streams rows. */
  void keepWriteTimeout(Connection connection);

  /**
   * Cancels on the server what runs on the connection for the statement, also while its rows are
   * read.
   */
  void cancel(Connection connection, Statement statement) throws SQLException;

  /** The fetch size that has the driver read {@code count} rows at a time, or stream them. */
  int fetchSize(int count);

  /**
   * The first words of a statement that may read its rows in a transaction of its own, where the
   * server sends rows in portions only within a transaction; empty where it sends them anyway.
   */
  Set<String> queriesInOwnTransaction();

  /**
   * Whether the driver keeps the server's warnings as they come, on the statement and its result
   * set alike, so that they can be read while rows are on their way and after a failure.
   */
  boolean warningsKeptByDriver();

  /**
   * Whether rows that a statement did not read hold the connection: the server sends them until
   * they are read, so that a statement given up is cancelled and read to its end.
   */
  boolean pendingRowsHoldConnection();

  /**
   * Whether the driver closes streamed rows with a statement of its own, which it refuses while a
   * procedure has more results, so that they are kept open until the last.
   */
  boolean streamedRowsClosedLast();
}

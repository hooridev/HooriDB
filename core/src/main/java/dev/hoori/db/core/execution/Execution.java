package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.RowReader;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One statement, given as text, run on an open connection: its results, in the order the server
 * gives them, each rows or a count of changed rows, with the server's messages, read a portion at
 * a time. The text goes to the server as it is, without a driver's escapes or parameters; it is
 * one statement, which this does not split. The connection's transaction is left to the caller to
 * commit or roll back.
 *
 * <p>The first {@link #next} runs the statement and reads at most the given number of rows of its
 * first result of rows, with the results before it; each further call reads as many more and goes
 * on to the results after them. A failure is a result, the last one, rather than an exception. The
 * statement is over when a portion has no {@link Portion#more}, or when the execution is closed;
 * closing it before closes the rows still unread.
 *
 * <p>From the first call until it is over, the connection belongs to the execution: nothing else
 * may run on it, as the server is still sending rows.
 *
 * <ul>
 *   <li>PostgreSQL sends rows in portions only within a transaction, and otherwise sends them all
 *       at once. With auto-commit off they come in portions, in the caller's transaction. With
 *       auto-commit on and no transaction open, a statement that starts with {@code SELECT}, {@code
 *       WITH}, {@code VALUES} or {@code TABLE} runs in a transaction of its own, which keeps its
 *       locks while its rows are read and is committed when it is over, or rolled back if it fails
 *       or is cancelled; meanwhile {@link Connection#getAutoCommit} says false. Others, which
 *       include those that cannot run in a transaction, such as {@code VACUUM}, read all their
 *       rows at once.
 *   <li>MariaDB sends the rows while they are read, and waits for the client; its {@code
 *       net_write_timeout}, 60 seconds by default, ends a statement whose rows are not read for
 *       that long. Another statement on the connection would have MariaDB Connector/J read all the
 *       rest into memory first.
 *   <li>MySQL likewise; MySQL Connector/J refuses another statement, and sets {@code
 *       net_write_timeout} to 600 seconds for the statement.
 * </ul>
 *
 * Closing with rows unread ends the statement on the server: PostgreSQL closes its cursor, while
 * MariaDB and MySQL cancel it, which also ends a procedure that had more to run.
 *
 * <p>Each call blocks, without a time limit. An interrupt of the calling thread ends it with a
 * {@link Failure.Kind#CANCELLED} failure, leaves the thread interrupted and the execution over: the
 * statement is cancelled on the server, while it executes and while its rows are read, and the
 * connection stays usable; only if the driver cannot stop it within {@link Query#GRACE}, or a
 * cancel may still be on its way, is the connection aborted, with a {@link
 * Failure.Kind#CONNECTION_LOST} failure. A cancel fails PostgreSQL's transaction, which the caller then rolls back,
 * while on MariaDB and MySQL only the statement ends and the transaction stays open.
 */
public final class Execution implements AutoCloseable {

  // What PostgreSQL may read in a transaction of its own: a query, after spaces, comments and
  // opening parentheses.
  private static final Pattern QUERY = Pattern.compile(
      "(?:\\s|--[^\\n]*|/\\*.*?\\*/|\\()*(?:SELECT|WITH|VALUES|TABLE)\\b",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final Connection connection;
  private final Provider provider;
  private final String sql;
  private Statement statement;
  // Whether auto-commit was turned off for this statement, to be turned on again when it is over.
  private boolean ownTransaction;
  private boolean over;
  // The transaction's state before the statement.
  private Transaction before;
  // The result set being read, how it is read, how many of its rows were read, and whether it is
  // on a row not yet read.
  private ResultSet rows;
  private RowReader reader;
  private long position;
  private boolean ahead;

  /** A statement to run on the connection, to a server of the provider; nothing runs yet. */
  public Execution(Connection connection, Provider provider, String sql) {
    this.connection = connection;
    this.provider = provider;
    this.sql = sql;
  }

  /**
   * Runs the statement, the first time, and reads its results up to and including at most the
   * given number of rows of a result of rows; later, reads as many more.
   *
   * @throws IllegalArgumentException if fewer than one row is asked for
   * @throws IllegalStateException if the execution is over
   */
  public Portion next(int count) {
    if (count < 1) {
      throw new IllegalArgumentException("At least one row, not " + count);
    }
    if (over) {
      throw new IllegalStateException("The statement is over");
    }
    if (statement == null) {
      before = Drivers.transaction(provider, connection);
    }
    try {
      return Query.run(
          connection,
          provider,
          () -> Drivers.cancel(provider, connection, statement),
          () -> read(count),
          // The server's word that a cancel stopped the statement, not MariaDB's time limit.
          portion -> !portion.results().isEmpty()
              && portion.results().getLast() instanceof Failure f
              && (f.state().equals("57014") || f.state().equals("70100"))
              && f.code() != 1969);
    } catch (QueryException e) {
      // Interrupted: the read has ended, or the connection was aborted.
      quietly(() -> end(false));
      var failure = e.failure() == QueryException.Failure.ABORTED
          ? Failure.connectionLost()
          : Failure.cancelled();
      return new Portion(List.of(failure), List.of(), false, Duration.ZERO, transaction(before));
    }
  }

  /** Ends the statement where it is not over, closing what is left of its rows. */
  @Override
  public void close() {
    if (over) {
      return;
    }
    quietly(() -> {
      // What would otherwise have MariaDB and MySQL read the rest of the rows to the end.
      if (rows != null && ahead && provider != Provider.POSTGRESQL) {
        try {
          Drivers.cancel(provider, connection, statement);
        } catch (SQLException e) {
          // Then the driver reads them.
        }
      }
      end(true);
    });
  }

  // MariaDB and MySQL send the state only with a statement that ends well, and MySQL Connector/J
  // forgets it while it streams rows and once it closes them: it is taken when a result ends, and
  // otherwise is what it was before the statement.
  private Transaction transaction(Transaction taken) {
    return provider == Provider.POSTGRESQL ? Drivers.transaction(provider, connection) : taken;
  }

  private Portion read(int count) throws SQLException {
    var started = System.nanoTime();
    var results = new ArrayList<Result>();
    var messages = new ArrayList<Message>();
    var more = false;
    var transaction = before;
    try {
      // A later call goes on with the rows of the result it is on.
      var resultSet = statement == null ? execute(count) : true;
      while (true) {
        if (rows == null) {
          if (!resultSet) {
            var changed = statement.getLargeUpdateCount();
            if (changed == -1) {
              break;
            }
            results.add(new Result.Count(changed));
            transaction = Drivers.transaction(provider, connection);
            resultSet = statement.getMoreResults();
            continue;
          }
          rows = statement.getResultSet();
          reader = RowReader.of(provider, rows.getMetaData());
          position = 0;
          ahead = rows.next();
        }
        var from = position;
        var read = new ArrayList<List<Object>>();
        while (ahead && read.size() < count) {
          read.add(reader.read(rows));
          position++;
          ahead = rows.next();
        }
        results.add(new Result.Rows(reader.fields(), from, List.copyOf(read)));
        if (ahead) {
          more = true;
          break;
        }
        if (provider == Provider.POSTGRESQL) {
          collect(rows.getWarnings(), messages);
        }
        transaction = Drivers.transaction(provider, connection);
        // Which closes the rows; MySQL Connector/J closes streamed rows with a statement of its
        // own, which it refuses before the next result of a procedure is read.
        rows = null;
        resultSet = statement.getMoreResults();
      }
      // MariaDB and MySQL send their warnings only when asked, which they cannot be while rows
      // are on their way.
      if (!more || provider == Provider.POSTGRESQL) {
        collect(statement.getWarnings(), messages);
        statement.clearWarnings();
      }
      // pgjdbc gives what comes while rows are fetched to the result set.
      if (more && provider == Provider.POSTGRESQL) {
        collect(rows.getWarnings(), messages);
        rows.clearWarnings();
      }
      if (!more) {
        finish();
      }
    } catch (SQLException e) {
      if (provider == Provider.POSTGRESQL && statement != null) {
        quietly(() -> collect(statement.getWarnings(), messages));
      }
      results.add(Failure.of(connection, e));
      end(false);
      transaction = before;
    }
    return new Portion(
        results,
        messages,
        more,
        Duration.ofNanos(System.nanoTime() - started),
        transaction(more ? before : transaction));
  }

  private boolean execute(int count) throws SQLException {
    if (provider == Provider.POSTGRESQL
        && connection.getAutoCommit()
        && Drivers.transaction(provider, connection) == Transaction.NONE
        && QUERY.matcher(sql).lookingAt()) {
      connection.setAutoCommit(false);
      ownTransaction = true;
    }
    statement = connection.createStatement();
    statement.setEscapeProcessing(false);
    // One row more than asked for, so that the look at the next row needs no second round trip.
    // MySQL Connector/J streams only with this value, a row at a time.
    statement.setFetchSize(
        provider == Provider.MYSQL
            ? Integer.MIN_VALUE
            : count == Integer.MAX_VALUE ? 0 : count + 1);
    return statement.execute(sql);
  }

  // The statement is over: what it did is committed where it ran in a transaction of its own.
  private void finish() throws SQLException {
    over = true;
    statement.close();
    if (ownTransaction) {
      connection.commit();
      connection.setAutoCommit(true);
      ownTransaction = false;
    }
  }

  // The statement is over, closed early, failed or cancelled: what it did is committed or rolled
  // back where it ran in a transaction of its own. Nothing is reported, as the connection may be
  // gone.
  private void end(boolean commit) {
    over = true;
    for (var closeable : new AutoCloseable[] {rows, statement}) {
      if (closeable != null) {
        try {
          closeable.close();
        } catch (Exception e) {
          // Closed already, or the connection is gone.
        }
      }
    }
    rows = null;
    if (ownTransaction) {
      ownTransaction = false;
      try {
        if (commit) {
          connection.commit();
        } else {
          connection.rollback();
        }
        connection.setAutoCommit(true);
      } catch (SQLException e) {
        // The connection is gone, and its transaction with it.
      }
    }
  }

  private static void collect(SQLWarning warning, List<Message> messages) {
    for (; warning != null; warning = warning.getNextWarning()) {
      messages.add(new Message(
          warning.getMessage() == null ? "" : warning.getMessage(),
          warning.getSQLState() == null ? "" : warning.getSQLState(),
          warning.getErrorCode()));
    }
  }

  private interface Action {
    void run() throws SQLException;
  }

  // Runs it with the thread's interrupt put aside: an interrupted virtual thread would close the
  // driver's socket instead of saying goodbye.
  private static void quietly(Action action) {
    var interrupted = Thread.interrupted();
    try {
      action.run();
    } catch (SQLException e) {
      // As in end(): there is nothing left to do about it.
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

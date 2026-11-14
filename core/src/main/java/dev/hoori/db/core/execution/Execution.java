package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.RowReader;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One statement, given as text, run on an open connection: its results, in the order the server
 * gives them, each rows or a count of changed rows, with the server's messages, read a portion at
 * a time. The text goes to the server as it is, without a driver's escapes or parameters; it is
 * one statement, which this does not split. The connection's transaction is left to the caller to
 * commit or roll back.
 *
 * <p>The first {@link #next} runs the statement and reads at most the given number of rows of its
 * first result of rows, with the results before it; each further call reads as many more and goes
 * on to the results after them. A failure is a result, the last one, rather than an exception,
 * after the rows and messages that came before it; only where ending the statement then lost the
 * connection does a second failure follow, which says so. The statement is over when a portion
 * has no {@link Portion#more}, or when the execution is abandoned or closed; doing so before
 * gives up the rows still unread.
 *
 * <p>From the first call until it is over, the connection belongs to the execution: nothing else
 * may run on it, as the server is still sending rows.
 *
 * <ul>
 *   <li>PostgreSQL sends rows in portions only within a transaction, and otherwise sends them all
 *       at once. With auto-commit off they come in portions, in the caller's transaction. With
 *       auto-commit on and no transaction open, a text that holds one statement, which starts with
 *       {@code SELECT}, {@code WITH}, {@code VALUES} or {@code TABLE}, runs in a transaction of its
 *       own, which keeps its locks while its rows are read; meanwhile {@link
 *       Connection#getAutoCommit} says false. That transaction is committed when the statement ran
 *       to its end and when its rest is given up, as auto-commit would keep what it changed, and
 *       rolled back where it failed or was cancelled. A data-modifying {@code WITH} runs to its end
 *       on the server however few rows are read; functions in a statement whose rest is given up
 *       have run only for the rows computed so far, and what they did is kept. Others, among them
 *       those that cannot run in a transaction, such as {@code VACUUM}, read all their rows at
 *       once.
 *   <li>MariaDB sends the rows while they are read, and waits for the client; its {@code
 *       net_write_timeout}, 60 seconds by default, ends a statement whose rows are not read for
 *       that long. Another statement on the connection would have MariaDB Connector/J read all the
 *       rest into memory first. The results of a procedure it reads all at once, and where the
 *       procedure then fails, it gives the failure alone.
 *   <li>MySQL likewise, with the same {@code net_write_timeout}, which MySQL Connector/J is kept
 *       from changing; it refuses another statement.
 * </ul>
 *
 * Closing with rows unread ends the statement on the server: PostgreSQL closes its cursor, while
 * MariaDB and MySQL cancel it, which also ends a procedure that had more to run, and its results
 * are read to their end. Where the connection is left in a state that cannot be known, as when
 * the transaction of its own cannot be ended or auto-commit not turned on again, the connection is
 * aborted: it is closed, and a portion says it is lost.
 *
 * <p>Each call blocks, without a time limit. An interrupt of the calling thread ends it with a
 * {@link Failure.Kind#CANCELLED} failure, leaves the thread interrupted and the execution over: the
 * statement is cancelled on the server, while it executes and while its rows are read, and the
 * connection stays usable; only if the driver cannot stop it within {@link Query#GRACE}, or a
 * cancel may still be on its way, is the connection aborted, with a {@link
 * Failure.Kind#CONNECTION_LOST} failure. A cancel fails PostgreSQL's transaction, which the caller
 * then rolls back, while on MariaDB and MySQL only the statement ends and the transaction stays
 * open.
 */
public final class Execution implements AutoCloseable {

  // The first words of a PostgreSQL statement that may read its rows in a transaction of its own.
  private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES", "TABLE");

  private final Connection connection;
  private final Provider provider;
  private final String sql;
  private Statement statement;
  // Whether auto-commit was turned off for this statement, to be turned on again when it is over.
  private boolean ownTransaction;
  private boolean over;
  // The result set being read, how it is read, how many of its rows were read, and whether it is
  // on a row not yet read.
  private ResultSet rows;
  private RowReader reader;
  private long position;
  private boolean ahead;
  // MySQL's streamed results that ended, closed once the last result is read.
  private final List<ResultSet> ended = new ArrayList<>();

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
    var started = System.nanoTime();
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
      // Interrupted, or a failure the read did not catch.
      var failure = e.failure() == QueryException.Failure.ABORTED
          ? Failure.connectionLost()
          : e.getCause() == null ? Failure.cancelled() : Failure.of(connection, e.getCause());
      var results = new ArrayList<Result>(List.of(failure));
      var lost = over ? null : withoutInterrupt(() -> end(Ending.FAILED));
      if (lost != null && failure.kind() != Failure.Kind.CONNECTION_LOST) {
        results.add(lost);
      }
      return new Portion(
          results,
          List.of(),
          false,
          Duration.ofNanos(System.nanoTime() - started),
          transaction(-1));
    }
  }

  /**
   * Ends the statement where it is not over, giving up what is left of its rows; where it ran in a
   * transaction of its own, that is committed, as auto-commit would have. The failure, if any: of
   * that commit, such as a deferred constraint's, or that the connection is lost.
   */
  public Optional<Failure> abandon() {
    return over
        ? Optional.empty()
        : Optional.ofNullable(withoutInterrupt(() -> end(Ending.GAVE_UP)));
  }

  /** As {@link #abandon}, without its failure; a caller that reports one uses that. */
  @Override
  public void close() {
    abandon();
  }

  private Portion read(int count) {
    var started = System.nanoTime();
    var results = new ArrayList<Result>();
    var messages = new ArrayList<Message>();
    var more = false;
    // The server status when the last result ended; -1 where it is not known to be current.
    var status = -1;
    // The rows of a portion that is being read, which a failure keeps.
    List<List<Object>> read = null;
    long from = 0;
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
            status = Drivers.status(provider, connection);
            resultSet = moreResults();
            continue;
          }
          rows = statement.getResultSet();
          reader = RowReader.of(provider, rows.getMetaData());
          position = 0;
          ahead = rows.next();
        }
        from = position;
        read = new ArrayList<>();
        while (ahead && read.size() < count) {
          read.add(reader.read(rows));
          position++;
          ahead = rows.next();
        }
        results.add(new Result.Rows(reader.fields(), from, List.copyOf(read)));
        read = null;
        if (ahead) {
          more = true;
          break;
        }
        if (provider == Provider.POSTGRESQL) {
          collect(rows.getWarnings(), messages);
        }
        status = Drivers.status(provider, connection);
        resultSet = moreResults();
      }
      // MariaDB and MySQL send their warnings only when asked, which they cannot be while rows
      // are on their way.
      // shortcut: the SHOW WARNINGS that their drivers send for that resets what a following
      // SELECT ROW_COUNT() gives; read the count first if the console ever offers it.
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
        var failure = end(Ending.RAN, status);
        if (failure != null) {
          results.add(failure);
          status = -1;
        }
      }
    } catch (SQLException | RuntimeException e) {
      if (read != null) {
        results.add(new Result.Rows(reader.fields(), from, List.copyOf(read)));
      }
      if (provider == Provider.POSTGRESQL && statement != null) {
        try {
          collect(statement.getWarnings(), messages);
          if (rows != null) {
            collect(rows.getWarnings(), messages);
          }
        } catch (SQLException x) {
          // The connection is gone, and what it said with it.
        }
      }
      // The server's error ends what it sends; nothing else would.
      if (e instanceof SQLException) {
        ahead = false;
      }
      var failure = Failure.of(connection, e);
      results.add(failure);
      var lost = end(Ending.FAILED);
      if (lost != null && failure.kind() != Failure.Kind.CONNECTION_LOST) {
        results.add(lost);
      }
      more = false;
      status = -1;
    }
    return new Portion(
        results,
        messages,
        more,
        Duration.ofNanos(System.nanoTime() - started),
        transaction(more ? -1 : status));
  }

  // PostgreSQL's state, which pgjdbc keeps current; MariaDB's and MySQL's from the status when the
  // statement's last result ended well, and otherwise not known.
  private Transaction transaction(int status) {
    return provider == Provider.POSTGRESQL
        ? Drivers.transaction(connection)
        : Drivers.transaction(status);
  }

  private boolean execute(int count) throws SQLException {
    // Never around a text with statements of its own after the first, which may begin and end
    // transactions; nor where one is open, which is the caller's.
    // shortcut: pgjdbc before 42.7.4 or so still reads all rows at once after a BEGIN the user
    // ran, as its auto-commit is on; the app recommends current drivers.
    if (provider == Provider.POSTGRESQL
        && connection.getAutoCommit()
        && Drivers.transaction(connection) == Transaction.NONE
        && Statements.split(sql, provider).size() == 1
        && QUERIES.contains(Statements.firstWord(sql, provider).toUpperCase(Locale.ROOT))) {
      connection.setAutoCommit(false);
      ownTransaction = true;
    }
    Drivers.keepWriteTimeout(provider, connection);
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

  // Goes on to the next result. MySQL Connector/J may close streamed rows with a statement of its
  // own, which it refuses while a procedure has more results; they are closed after the last.
  private boolean moreResults() throws SQLException {
    if (provider == Provider.MYSQL && rows != null) {
      ended.add(rows);
      rows = null;
      return statement.getMoreResults(Statement.KEEP_CURRENT_RESULT);
    }
    rows = null;
    return statement.getMoreResults();
  }

  // How a statement came to its end.
  private enum Ending {
    // It ran to its end.
    RAN,
    // The caller gave up the rows still unread.
    GAVE_UP,
    // It failed, or was cancelled.
    FAILED
  }

  private Failure end(Ending ending) {
    return end(ending, -1);
  }

  // Ends the statement: where it did not run to its end, has MariaDB and MySQL stop sending and
  // reads what they still send, so that the connection takes statements again; closes its rows
  // and the statement; and ends its own transaction, committing it unless it failed. The failure
  // of the commit, if any; where the connection is left in a state that cannot be known, it is
  // aborted, and the failure says it is lost.
  private Failure end(Ending ending, int status) {
    over = true;
    var lost = false;
    if (ending != Ending.RAN && provider != Provider.POSTGRESQL && statement != null) {
      lost = !drain();
    }
    var closeables = new ArrayList<AutoCloseable>(ended);
    closeables.add(rows);
    closeables.add(statement);
    ended.clear();
    rows = null;
    for (var closeable : closeables) {
      if (closeable != null) {
        try {
          closeable.close();
        } catch (Exception e) {
          lost = true;
        }
      }
    }
    Failure failure = null;
    if (ownTransaction) {
      ownTransaction = false;
      var commit = ending != Ending.FAILED;
      if (commit) {
        try {
          connection.commit();
        } catch (SQLException e) {
          failure = Failure.of(connection, e);
          commit = false;
        }
      }
      if (!commit) {
        try {
          connection.rollback();
        } catch (SQLException e) {
          lost = true;
        }
      }
      // pgjdbc commits as it turns auto-commit on, which after a failed rollback it must not.
      if (!lost) {
        try {
          connection.setAutoCommit(true);
        } catch (SQLException e) {
          lost = true;
        }
      }
    }
    if (!lost && ending == Ending.RAN && failure == null) {
      try {
        Drivers.followAutoCommit(provider, connection, status);
      } catch (SQLException e) {
        lost = true;
      }
    }
    if (lost) {
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException e) {
        // Not even an abort is possible; the connection is given up all the same.
      }
      return failure != null ? failure : Failure.connectionLost();
    }
    return failure;
  }

  // Cancels what MariaDB or MySQL still send for the statement and reads it to its end; false
  // where the connection was lost meanwhile.
  private boolean drain() {
    if (rows != null && ahead) {
      try {
        Drivers.cancel(provider, connection, statement);
      } catch (SQLException e) {
        // Then the rows are read to their end.
      }
    }
    try {
      while (true) {
        if (rows != null) {
          while (rows.next()) {
            // Read and dropped.
          }
        }
        if (moreResults()) {
          rows = statement.getResultSet();
        } else if (statement.getLargeUpdateCount() == -1) {
          return true;
        }
      }
    } catch (SQLException e) {
      // The server ended the statement with an error, its cancel among them: nothing more comes.
      return Failure.of(connection, e).kind() != Failure.Kind.CONNECTION_LOST;
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

  // Runs it with the thread's interrupt put aside: an interrupted virtual thread would close the
  // driver's socket instead of saying goodbye.
  private static <T> T withoutInterrupt(Supplier<T> action) {
    var interrupted = Thread.interrupted();
    try {
      return action.get();
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

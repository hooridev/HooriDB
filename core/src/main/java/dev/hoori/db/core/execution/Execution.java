package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.KeyedRead;
import dev.hoori.db.core.data.RowReader;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.dialect.SessionDialect;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.ReadWrite;
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
import java.util.concurrent.CopyOnWriteArrayList;
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

  private final Connection connection;
  private final Provider provider;
  private final SessionDialect session;
  private final String sql;
  private final boolean readOnly;
  // The read of a table's rows that it runs, whose reader reads them, or null for a statement's.
  private final KeyedRead keyed;
  private Statement statement;
  // Whether auto-commit was turned off for this statement, to be turned on again when it is over.
  private boolean ownTransaction;
  private boolean over;
  // Its end was given up for its deadline and the connection aborted: a worker that wakes up
  // after that does nothing more on it, neither commit, rollback nor auto-commit.
  private volatile boolean aborted;
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
    this(connection, provider, sql, false);
  }

  /**
   * As {@link #Execution(Connection, Provider, String)}, on a session that is read-only where
   * {@code readOnly}: there a statement that may turn that off, as {@link ReadWrite} reads it, is
   * not sent, and its first portion is a failure that says so.
   */
  public Execution(Connection connection, Provider provider, String sql, boolean readOnly) {
    this(connection, provider, sql, readOnly, null);
  }

  /**
   * The keyed read of a table, run as a statement is: one result of the table's rows, whole, in
   * its key's order, read a portion at a time with the read's own {@link KeyedRead#reader}, and
   * logged as a read of rows.
   */
  public Execution(Connection connection, Provider provider, KeyedRead read) {
    this(connection, provider, read.sql(), false, read);
  }

  private Execution(
      Connection connection, Provider provider, String sql, boolean readOnly, KeyedRead keyed) {
    this.connection = connection;
    this.provider = provider;
    this.session = provider.dialect().session();
    this.sql = sql;
    this.readOnly = readOnly;
    this.keyed = keyed;
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
    var refused = refused();
    if (refused != null) {
      // Refused before it was sent, so there is nothing to log.
      return refused;
    }
    // Sent with its first portion, which is what the server was asked for.
    return statement != null
        ? portion(count)
        : Journal.sql(
            provider,
            keyed == null ? Purpose.STATEMENT : Purpose.ROWS,
            sql,
            Execution::outcome,
            () -> portion(count));
  }

  /**
   * How a statement's first portion went: its failure, else the rows it read and whether more
   * follow, else what it changed.
   */
  public static Outcome outcome(Portion portion) {
    long rows = 0;
    long changed = 0;
    boolean counted = false;
    boolean read = false;
    for (var result : portion.results()) {
      switch (result) {
        case Failure f -> {
          return f.kind() == Failure.Kind.CANCELLED
              ? new Outcome.Cancelled()
              : new Outcome.Failed(f.message());
        }
        case Result.Rows r -> {
          rows += r.rows().size();
          read = true;
        }
        case Result.Count c -> {
          changed += c.count();
          counted = true;
        }
      }
    }
    return read
        ? new Outcome.Rows(rows, portion.more())
        : counted ? new Outcome.Changed(changed) : Outcome.DONE;
  }

  private Portion portion(int count) {
    var started = System.nanoTime();
    try {
      return Query.run(
          connection,
          provider,
          () -> Journal.call(Purpose.STATEMENT, Journal.CANCEL, () -> {
            session.cancel(connection, statement);
            return null;
          }),
          () -> read(count),
          // The server's word that a cancel stopped the statement, not a time limit of its own.
          portion -> !portion.results().isEmpty()
              && portion.results().getLast() instanceof Failure f
              && Query.byServer(provider, f.state(), f.code()));
    } catch (QueryException e) {
      var failure = failure(e);
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
          transaction(-1),
          autoCommit(-1));
    }
  }

  // Interrupted, or a failure the read did not catch.
  private Failure failure(QueryException e) {
    if (e.failure() == QueryException.Failure.ABORTED) {
      return Failure.connectionLost();
    }
    return e.getCause() == null
        ? Failure.cancelled()
        : Failure.of(connection, provider, e.getCause());
  }

  // On a read-only session, the first portion of a statement that may turn that off, not sent;
  // null for any other.
  private Portion refused() {
    if (statement != null || !readOnly || !ReadWrite.in(sql, provider)) {
      return null;
    }
    over = true;
    var refused = Failure.of(
        Failure.Kind.ACCESS_DENIED,
        "The connection is read-only, and this statement may turn that off for the session, so"
            + " it was not sent. SHOW reads a setting.");
    return new Portion(
        List.of(refused), List.of(), false, Duration.ZERO, transaction(-1), autoCommit(-1));
  }

  /** How long ending a statement that is given up may take before its connection is. */
  public static final Duration ENDING = Duration.ofSeconds(10);

  // ENDING but where a test of a server that stops answering waits out a shorter one.
  static volatile Duration giveUpBound = ENDING;

  /**
   * Ends the statement where it is not over, giving up what is left of its rows; where it ran in a
   * transaction of its own, that is committed, as auto-commit would have. Its failure, if any: of
   * that commit, such as a deferred constraint's, or that the connection is lost; and the warnings
   * the server sent as it ended. It ends within {@link #ENDING}, an interrupt or not: a server that
   * does not answer by then has its connection aborted, and the failure says so.
   */
  public Abandoned abandon() {
    if (over) {
      return Abandoned.NOTHING;
    }
    // Written by the worker, read here without a lock it could hold through a JDBC call that
    // never returns: after the deadline, nothing here waits.
    var said = new CopyOnWriteArrayList<Message>();
    Failure failure;
    try {
      failure = Query.within(
          connection,
          provider,
          giveUpBound,
          () -> end(Ending.GAVE_UP, -1, said),
          () -> aborted = true);
    } catch (QueryException e) {
      over = true;
      failure = Failure.of(
          Failure.Kind.CONNECTION_LOST,
          e.getMessage() + " as the statement was ended: the connection was closed, and whether"
              + " its end took effect is not known.");
    }
    return new Abandoned(Optional.ofNullable(failure), said);
  }

  /** As {@link #abandon}, without what it says; a caller that reports it uses that. */
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
    try {
      // A later call goes on with the rows of the result it is on.
      status = readResults(statement != null || execute(count), count, results, messages);
      more = ahead; // the rows of the last result read are still on their way
      collectWarnings(more, messages);
      if (!more) {
        var failure = end(Ending.RAN, status);
        if (failure != null) {
          results.add(failure);
          status = -1;
        }
      }
    } catch (SQLException | RuntimeException e) {
      collectWarningsOfFailure(messages);
      // The server's error ends what it sends; nothing else would.
      if (e instanceof SQLException) {
        ahead = false;
      }
      var failure = Failure.of(connection, provider, e);
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
        transaction(more ? -1 : status),
        autoCommit(more ? -1 : status));
  }

  // Reads the results from the one it is on, given whether that is one of rows, until a result of
  // rows has more than the given number of rows, which leaves it ahead, or until the last; the
  // server status when the last result read ended, -1 where none did.
  private int readResults(boolean onRows, int count, List<Result> results, List<Message> messages)
      throws SQLException {
    var resultSet = onRows;
    var status = -1;
    while (true) {
      if (rows == null && !resultSet) {
        var changed = statement.getLargeUpdateCount();
        if (changed == -1) {
          return status;
        }
        results.add(new Result.Count(changed));
      } else if (readResultOfRows(count, results, messages)) {
        return status;
      }
      status = session.status(connection);
      resultSet = moreResults();
    }
  }

  // Reads at most the given number of rows of the result of rows it is on, or goes on to the
  // statement's current one; whether rows of it are still ahead.
  private boolean readResultOfRows(int count, List<Result> results, List<Message> messages)
      throws SQLException {
    if (rows == null) {
      open();
    }
    readRows(count, results);
    if (ahead) {
      return true;
    }
    if (session.warningsKeptByDriver()) {
      collect(rows.getWarnings(), messages);
    }
    return false;
  }

  // Goes on to the statement's current result, of rows, before its first row.
  private void open() throws SQLException {
    rows = statement.getResultSet();
    reader = keyed == null ? RowReader.of(provider, rows.getMetaData()) : keyed.reader();
    position = 0;
    ahead = rows.next();
  }

  // Reads at most the given number of rows of the result it is on, as a result of its own, and of
  // a keyed read, whose values are whole, at most its portion of characters beyond the first row;
  // where that fails, the rows read before.
  private void readRows(int count, List<Result> results) throws SQLException {
    var from = position;
    var read = new ArrayList<List<Object>>();
    long characters = 0;
    try {
      while (ahead && read.size() < count && characters < KeyedRead.PORTION) {
        var row = reader.read(rows);
        read.add(row);
        if (keyed != null) {
          characters += Rows.characters(row);
        }
        position++;
        ahead = rows.next();
      }
    } finally {
      results.add(new Result.Rows(reader.fields(), from, List.copyOf(read)));
    }
  }

  // Warnings the driver does not keep must be asked for, which they cannot be while rows are on
  // their way.
  // shortcut: the SHOW WARNINGS that MariaDB's and MySQL's drivers send for that resets what a
  // following SELECT ROW_COUNT() gives; read the count first if the console ever offers it.
  private void collectWarnings(boolean more, List<Message> messages) throws SQLException {
    if (!more || session.warningsKeptByDriver()) {
      collect(statement.getWarnings(), messages);
      statement.clearWarnings();
    }
    // What comes while rows are fetched is the result set's.
    if (more && session.warningsKeptByDriver()) {
      collect(rows.getWarnings(), messages);
      rows.clearWarnings();
    }
  }

  // What the driver kept of the warnings of a statement that failed.
  private void collectWarningsOfFailure(List<Message> messages) {
    if (!session.warningsKeptByDriver() || statement == null) {
      return;
    }
    try {
      collect(statement.getWarnings(), messages);
      if (rows != null) {
        collect(rows.getWarnings(), messages);
      }
    } catch (SQLException _) {
      // The connection is gone, and what it said with it.
    }
  }

  // As the driver keeps it current, or from the status when the statement's last result ended
  // well, and otherwise not known. The transaction of its own is none of the caller's, which it
  // began without.
  // As the transaction is known, the caller's while one of its own is open.
  private Boolean autoCommit(int status) {
    if (ownTransaction) {
      return Boolean.TRUE;
    }
    return session.autoCommit(connection, status);
  }

  private Transaction transaction(int status) {
    if (ownTransaction) {
      return Transaction.NONE;
    }
    return session.transaction(connection, status);
  }

  private boolean execute(int count) throws SQLException {
    // Never around a text with statements of its own after the first, which may begin and end
    // transactions; nor where one is open, which is the caller's.
    var queries = session.queriesInOwnTransaction();
    if (!queries.isEmpty()
        && connection.getAutoCommit()
        && session.transaction(connection, -1) == Transaction.NONE
        && Statements.split(sql, provider).size() == 1
        && queries.contains(Statements.firstWord(sql, provider).toUpperCase(Locale.ROOT))) {
      Journal.call(Purpose.TRANSACTION, Journal.autoCommit(false), () -> {
        connection.setAutoCommit(false);
        return null;
      });
      ownTransaction = true;
    }
    session.keepWriteTimeout(connection);
    statement = connection.createStatement();
    statement.setEscapeProcessing(false);
    statement.setFetchSize(session.fetchSize(count));
    return statement.execute(sql);
  }

  // Goes on to the next result, keeping streamed rows open until the last where the driver would
  // close them with a statement of its own.
  private boolean moreResults() throws SQLException {
    if (session.streamedRowsClosedLast() && rows != null) {
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
    return end(ending, -1, null);
  }

  private Failure end(Ending ending, int status) {
    return end(ending, status, null);
  }

  // Ends the statement: where it did not run to its end, has a server whose unread rows hold the
  // connection stop sending and reads what it still sends, so that the connection takes statements
  // again; closes its rows and the statement; and ends its own transaction, committing it unless
  // it failed. The failure of the commit, if any; where the connection is left in a state that
  // cannot be known, it is aborted, and the failure says it is lost.
  // With said, it also keeps what the server says as it ends: the statement's warnings, and those
  // of the commit of its own transaction.
  private Failure end(Ending ending, int status, List<Message> said) {
    over = true;
    var lost = false;
    if (ending != Ending.RAN && session.pendingRowsHoldConnection() && statement != null) {
      lost = !drain();
    }
    if (said != null && !lost) {
      collectWarningsOfEnd(said);
    }
    if (aborted) {
      return Failure.connectionLost();
    }
    if (!closeAll()) {
      lost = true;
    }
    Failure failure = null;
    if (aborted) {
      return Failure.connectionLost();
    }
    if (ownTransaction) {
      ownTransaction = false;
      var own = endOwnTransaction(ending != Ending.FAILED, said, lost);
      if (own.aborted()) {
        return Failure.connectionLost();
      }
      failure = own.failure();
      lost = own.lost();
    }
    if (!lost && ending == Ending.RAN && failure == null) {
      lost = !followAutoCommit(status);
    }
    if (lost) {
      abort();
      return failure != null ? failure : Failure.connectionLost();
    }
    return failure;
  }

  // How the end of its own transaction went: the failure of its commit, if any; whether the
  // connection is left in a state that cannot be known; and whether it was aborted after the
  // commit.
  private record OwnEnd(Failure failure, boolean lost, boolean aborted) {}

  // Ends the statement's own transaction: commits it where toCommit says so and the commit
  // succeeds,
  // else rolls it back, and turns auto-commit on again unless the connection is already lost.
  private OwnEnd endOwnTransaction(boolean toCommit, List<Message> said, boolean lostBefore) {
    var commit = toCommit;
    var lost = lostBefore;
    Failure failure = null;
    if (commit && !aborted) {
      try {
        connection.clearWarnings();
        Journal.call(Purpose.TRANSACTION, Journal.COMMIT, () -> {
          connection.commit();
          return null;
        });
        if (said != null) {
          collect(connection.getWarnings(), said); // pgjdbc's notices of the commit
          connection.clearWarnings();
        }
      } catch (SQLException e) {
        failure = Failure.of(connection, provider, e);
        commit = false;
      }
    }
    if (aborted) {
      return new OwnEnd(failure, lost, true);
    }
    if (!commit) {
      try {
        Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> {
          connection.rollback();
          return null;
        });
      } catch (SQLException _) {
        lost = true;
      }
    }
    // pgjdbc commits as it turns auto-commit on, which after a failed rollback it must not.
    if (!lost && !aborted) {
      try {
        Journal.call(Purpose.TRANSACTION, Journal.autoCommit(true), () -> {
          connection.setAutoCommit(true);
          return null;
        });
      } catch (SQLException _) {
        lost = true;
      }
    }
    return new OwnEnd(failure, lost, false);
  }

  private void collectWarningsOfEnd(List<Message> said) {
    if (statement == null) {
      return;
    }
    try {
      collect(statement.getWarnings(), said);
    } catch (SQLException _) {
      // What it said is gone with what failed, which the ending says.
    }
  }

  // Closes the results that ended, the one it is on and the statement; false where one failed.
  private boolean closeAll() {
    var closeables = new ArrayList<AutoCloseable>(ended);
    closeables.add(rows);
    closeables.add(statement);
    ended.clear();
    rows = null;
    var closed = true;
    for (var closeable : closeables) {
      if (closeable != null) {
        try {
          closeable.close();
        } catch (Exception _) {
          closed = false;
        }
      }
    }
    return closed;
  }

  // False where the connection is left in a state that cannot be known.
  private boolean followAutoCommit(int status) {
    try {
      session.followAutoCommit(connection, status);
      return true;
    } catch (SQLException _) {
      return false;
    }
  }

  private void abort() {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException _) {
      // Not even an abort is possible; the connection is given up all the same.
    }
  }

  // Cancels what the server still sends for the statement and reads it to its end; false where the
  // connection was lost meanwhile.
  private boolean drain() {
    if (rows != null && ahead) {
      try {
        Journal.call(Purpose.STATEMENT, Journal.CANCEL, () -> {
          session.cancel(connection, statement);
          return null;
        });
      } catch (SQLException _) {
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
      return Failure.of(connection, provider, e).kind() != Failure.Kind.CONNECTION_LOST;
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

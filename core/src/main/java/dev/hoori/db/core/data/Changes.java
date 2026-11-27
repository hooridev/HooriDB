package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Changes of the rows of a table, collected until they are written: new values of cells, new
 * rows, and rows to delete. A change names its row by the values its primary key had when it was
 * read, so a table without one cannot be changed, which {@link #of} says.
 *
 * <p>Each change makes one {@link #statements statement}, an {@code UPDATE}, {@code INSERT} or
 * {@code DELETE} with its values as parameters, which names the table and its columns quoted as
 * the provider quotes them; {@link #write} runs them in a transaction of their own, all or
 * nothing. Changes are a value: each step gives new ones, and the ones before stay as they were.
 *
 * @param columns all the table's columns, in their order, as {@link
 *     dev.hoori.db.core.catalog.Catalog#columns} read them, which give the primary key
 * @param changes the changes in the order they were made, one per row
 */
public record Changes(Table table, List<Column> columns, List<Change> changes) {

  /**
   * @throws IllegalArgumentException if no column is in a primary key, a column is not the
   *     table's, or a change does not fit the columns: a row with a value for another number of
   *     columns, a column that is none of them, an update without values, a key without a value, an
   *     update of a column whose value in the row is {@link Missing#FIELD} or read only in part, or
   *     a value read only in part, a {@link Cut} or a {@link Binary#cut} one, which can neither be
   *     written nor name a row
   */
  public Changes {
    columns = List.copyOf(columns);
    changes = List.copyOf(changes);
    Rows.requireColumnsOf(table, columns);
    if (columns.stream().noneMatch(column -> column.keyPosition() > 0)) {
      throw new IllegalArgumentException(table.name() + " has no primary key");
    }
    for (var change : changes) {
      switch (change) {
        case Change.Update(var row, var values) -> {
          requireRow(columns, row);
          requireValues(columns, values);
          if (values.isEmpty()) {
            throw new IllegalArgumentException("An update without values");
          }
        }
        case Change.Insert(var values) -> requireValues(columns, values);
        case Change.Delete(var row) -> requireRow(columns, row);
      }
    }
  }

  /**
   * No changes yet of the rows of the table, which has the given columns; empty where none of them
   * is in a primary key, without which a change cannot name its row.
   *
   * @throws IllegalArgumentException if a column is not the table's
   */
  public static Optional<Changes> of(Table table, List<Column> columns) {
    return columns.stream().anyMatch(column -> column.keyPosition() > 0)
        ? Optional.of(new Changes(table, columns, List.of()))
        : Optional.empty();
  }

  /**
   * With the cell of the row as it was read given the value: in the row's update, a new one where
   * it has none. The value that was read takes the cell's change back, and the update's where it
   * was its last.
   *
   * @throws IllegalArgumentException if the column is out of range, its value in the row is {@link
   *     Missing#FIELD} or read only in part, or the new value is {@link Missing#FIELD} or read only
   *     in part
   * @throws IllegalStateException if the row is to be deleted
   */
  public Changes edit(List<Object> row, int column, Object value) {
    requireRow(columns, row);
    requireOriginal(row, column);
    var key = key(row);
    var changed = new ArrayList<>(changes);
    for (var i = 0; i < changed.size(); i++) {
      if (changed.get(i) instanceof Change.Delete(var deleted) && key(deleted).equals(key)) {
        throw new IllegalStateException("The row is to be deleted");
      }
      if (changed.get(i) instanceof Change.Update(var updated, var values)
          && key(updated).equals(key)) {
        return edit(changed, i, updated, values, column, value);
      }
    }
    if (Objects.equals(row.get(column), value)) {
      return this;
    }
    var values = new TreeMap<Integer, Object>();
    values.put(column, value);
    changed.add(new Change.Update(row, values));
    return new Changes(table, columns, changed);
  }

  // With the update of the row at the index given the cell's value, and without it where that
  // takes back its last.
  private Changes edit(
      List<Change> changed,
      int index,
      List<Object> row,
      Map<Integer, Object> values,
      int column,
      Object value) {
    var edited = new TreeMap<>(values);
    if (Objects.equals(row.get(column), value)) {
      edited.remove(column);
    } else {
      edited.put(column, value);
    }
    if (edited.isEmpty()) {
      changed.remove(index);
    } else {
      changed.set(index, new Change.Update(row, edited));
    }
    return new Changes(table, columns, changed);
  }

  /** With a new row, the last change, with values of the given columns. */
  public Changes insert(Map<Integer, Object> values) {
    var changed = new ArrayList<>(changes);
    changed.add(new Change.Insert(values));
    return new Changes(table, columns, changed);
  }

  /**
   * With the column of the change's new row given the value.
   *
   * @throws IllegalArgumentException if the change is no new row
   */
  public Changes edit(int change, int column, Object value) {
    if (!(changes.get(change) instanceof Change.Insert(var inserted))) {
      throw new IllegalArgumentException("Change " + change + " is no new row");
    }
    var values = new TreeMap<>(inserted);
    values.put(column, value);
    var changed = new ArrayList<>(changes);
    changed.set(change, new Change.Insert(values));
    return new Changes(table, columns, changed);
  }

  /** With the row as it was read to be deleted, in place of its update. */
  public Changes delete(List<Object> row) {
    requireRow(columns, row);
    var key = key(row);
    var changed = new ArrayList<Change>();
    for (var change : changes) {
      switch (change) {
        case Change.Update update when key(update.row()).equals(key) -> {
          // The deletion takes its place.
        }
        case Change.Delete delete
        when key(delete.row()).equals(key) -> {
          return this;
        }
        default -> changed.add(change);
      }
    }
    changed.add(new Change.Delete(row));
    return new Changes(table, columns, changed);
  }

  /** Without the change: a row's update or deletion taken back, or a new row given up. */
  public Changes revert(int change) {
    var changed = new ArrayList<>(changes);
    changed.remove(change);
    return new Changes(table, columns, changed);
  }

  /**
   * A statement that a change makes.
   *
   * @param sql the statement, with a parameter {@code ?} for each value
   * @param values the values of its parameters, in their order
   * @param preview the statement with its values in place as literals of the provider's SQL and
   *     comparisons written plainly, for people to read; a text in it is written as MariaDB and
   *     MySQL read it without {@code NO_BACKSLASH_ESCAPES}, and a moment with its offset
   */
  public record Statement(Change change, String sql, List<Object> values, String preview) {

    public Statement {
      values = Collections.unmodifiableList(new ArrayList<>(values));
    }
  }

  /**
   * The statements of the changes: the updates first, then the deletions and then the new rows,
   * each in the order they were made, so that an update meets its row while all rows named are
   * still there, as a deletion may take others with it by a foreign key, and a new row can take
   * the key of a row deleted. An
   * {@code UPDATE} sets the columns given new values and a new row's {@code INSERT} the columns
   * given values, and one without any the first column to its default. An {@code UPDATE} and a
   * {@code DELETE} name their row by the values its primary key had when it was read, so that an
   * update can give the key new values.
   *
   * @throws IllegalArgumentException if a value is of no kind's type
   */
  public List<Statement> statements(Provider provider) {
    var dialect = provider.dialect().rows();
    var statements = new ArrayList<Statement>();
    for (var change : ordered()) {
      var values = new ArrayList<Object>();
      var sql = sql(provider, change, dialect.keyEquality(), parameters(dialect, values));
      var preview = sql(provider, change, "=", dialect::literal);
      statements.add(new Statement(change, sql, values, preview));
    }
    return statements;
  }

  /**
   * The {@link Statement#preview previews} of the {@link #statements}, in their order, but with a
   * text longer than {@link Rows#LIMIT} code points, or binary data longer than as many bytes, cut
   * to that and its whole length in a comment after it: for people to read the changes of long
   * values, which they do not run.
   *
   * @throws IllegalArgumentException if a value is of no kind's type
   */
  public List<String> previews(Provider provider) {
    var dialect = provider.dialect().rows();
    Function<Object, String> literal = value -> switch (value) {
      case String text
      when Rows.tooLong(text) ->
        dialect.literal(Rows.cut(text).start()) + " /* cut, "
            + text.codePointCount(0, text.length()) + " characters in all */";
      case Binary binary
      when binary.length() > Rows.LIMIT ->
        dialect.literal(Rows.shown(binary)) + " /* cut, " + binary.length() + " bytes in all */";
      case null, default -> dialect.literal(value);
    };
    return ordered().stream().map(change -> sql(provider, change, "=", literal)).toList();
  }

  /**
   * The {@link #statements}, in their order, as a script of them runs them: each value a literal
   * that the server reads back as the value whatever the session's zone, as the parameters of
   * {@link #write} are; a text in it is written as {@link Statement#preview} writes it.
   *
   * @throws IllegalArgumentException if a value is of no kind's type
   */
  public List<String> script(Provider provider) {
    var dialect = provider.dialect().rows();
    return ordered().stream()
        .map(change -> sql(provider, change, "=", dialect::scriptLiteral))
        .toList();
  }

  // The updates first, then the deletions and then the new rows, each in the order they were made.
  private List<Change> ordered() {
    var ordered = new ArrayList<Change>(changes.size());
    for (var kind : List.of(Change.Update.class, Change.Delete.class, Change.Insert.class)) {
      for (var change : changes) {
        if (kind.isInstance(change)) {
          ordered.add(change);
        }
      }
    }
    return ordered;
  }

  /**
   * What follows writes into the table in their transaction, before it commits, as {@link
   * Writer#commit} runs it and a script of the changes ends with it, before its commit: on
   * PostgreSQL the table's sequences moved past the values it holds, never back; none where the
   * server needs nothing.
   */
  public static List<String> afterWrites(Provider provider, Table table) {
    return provider.dialect().rows().afterWrites(provider, table);
  }

  /** What came of writing changes. */
  public sealed interface Outcome {

    /** Every change was written, and committed. */
    record Committed() implements Outcome {}

    /**
     * Nothing was written: the change's row was there not once but the given number of times
     * before the changes began, or its statement met that many rows, as when the row was deleted,
     * its key changed, or a cell an update changes was changed since it was read, and all was
     * rolled back.
     */
    record Missed(Change change, long rows) implements Outcome {}

    /**
     * Nothing was written: the changes of the table could not be all or nothing, each meeting the
     * row its key names, as its storage engine, such as MyISAM, keeps no transactions, or on
     * PostgreSQL other tables inherit from it, whose rows may repeat its keys; or on MariaDB and
     * MySQL a moment falls into an hour that the session's zone repeats, which the server takes
     * for another moment.
     */
    record Refused() implements Outcome {}
  }

  /**
   * Writes the changes in a transaction of their own, all or nothing: first each row that an update
   * or deletion names must be there, once, with each column an update changes still holding its
   * original value, and where there are updates it is locked, so that no statement meets a row that
   * an earlier one moved to its key, as an update of a key or of what a generated key or a trigger
   * takes it from can; then the {@link #statements} run one after another. Where a row is not
   * there once, or a statement meets not exactly one row, or fails, all are rolled back. The
   * connection must have auto-commit on and no transaction open, as one that only reads the table
   * has, and is left so; nothing else may run on it meanwhile.
   *
   * <p>The call blocks. An interrupt of the calling thread cancels the statement that runs and
   * ends with {@link QueryException.Failure#CANCELLED} or {@link QueryException.Failure#ABORTED},
   * as {@link Query} does, once all was rolled back; the transaction ends within {@link
   * Execution#ENDING}, an interrupt or not, and where it does not, the connection is given up.
   *
   * @throws QueryException as {@link Query} reports it, where a statement or the commit failed,
   *     and all was rolled back; where the connection is given up, the server rolls back what it
   *     did not commit as it closes, but where it is lost or given up as the commit runs, whether
   *     the changes were committed is not known
   * @throws IllegalStateException if the connection has a transaction open, or auto-commit off
   * @throws IllegalArgumentException if a value is of no kind's type, or a PostgreSQL connection is
   *     to another database than the table's
   */
  public Outcome write(Connection connection, Provider provider) throws QueryException {
    var statements = statements(provider);
    if (statements.isEmpty()) {
      return new Outcome.Committed();
    }
    Rows.requireConnectedTo(connection, provider, table);
    requireNoTransaction(connection, provider);
    var dialect = provider.dialect().rows();
    var checks = checks(provider, statements);
    var all = new ArrayList<>(checks);
    all.addAll(statements);
    var values = all.stream().flatMap(s -> s.values().stream()).toList();
    if (!dialect.changeable(connection, provider, table, values)) {
      return new Outcome.Refused();
    }
    Outcome outcome;
    try {
      begin(connection, provider);
      outcome = run(connection, provider, all, checks.size());
    } catch (QueryException | RuntimeException e) {
      rollBackAfter(connection, provider, e);
      throw e;
    }
    end(connection, provider, outcome instanceof Outcome.Committed);
    return outcome;
  }

  /**
   * Begins writing changes of the table in one transaction, all or nothing, as {@link #write}
   * does, but a batch of changes at a time: so that they need not all be held at once, as when
   * they come from comparing two tables row by row. Each batch's rows are checked and its
   * statements run as {@link #write} runs them, and nothing is committed before {@link
   * Writer#commit}. The connection must have auto-commit on and no transaction open, and nothing
   * else may run on it until the writer is over.
   *
   * @throws QueryException as {@link #write} does, where auto-commit could not be turned off
   * @throws IllegalStateException if the connection has a transaction open, or auto-commit off
   * @throws IllegalArgumentException if a PostgreSQL connection is to another database than the
   *     table's
   */
  public static Writer writer(Connection connection, Provider provider, Table table)
      throws QueryException {
    Rows.requireConnectedTo(connection, provider, table);
    requireNoTransaction(connection, provider);
    try {
      begin(connection, provider);
    } catch (QueryException | RuntimeException e) {
      rollBackAfter(connection, provider, e);
      throw e;
    }
    return new Writer(connection, provider, table);
  }

  /**
   * Changes of a table written in one transaction, a batch at a time, from {@link #writer} until
   * it is committed or rolled back; where a batch misses a row or is refused, or a statement fails,
   * all is rolled back and the writer is over. Its calls block as {@link #write} does, and an
   * interrupt cancels them alike; one thread uses it.
   */
  public static final class Writer implements AutoCloseable {

    private final Connection connection;
    private final Provider provider;
    private final Table table;
    private boolean open = true;
    private boolean wrote; // whether a batch wrote, after which the dialect's afterWrites run

    private Writer(Connection connection, Provider provider, Table table) {
      this.connection = connection;
      this.provider = provider;
      this.table = table;
    }

    /**
     * Writes the batch in the transaction: empty where it was written, else what ended the
     * transaction, a {@link Outcome.Missed} or {@link Outcome.Refused}, as {@link #write} says
     * them, once all was rolled back.
     *
     * @throws QueryException as {@link #write} does, once all was rolled back
     * @throws IllegalStateException if the writer is over
     * @throws IllegalArgumentException if the batch is of another table, or a value is of no kind's
     *     type
     */
    public Optional<Outcome> write(Changes batch) throws QueryException {
      requireOpen();
      if (!batch.table().equals(table)) {
        throw new IllegalArgumentException(
            "Changes of " + batch.table().name() + ", not of " + table.name());
      }
      var statements = batch.statements(provider);
      if (statements.isEmpty()) {
        return Optional.empty();
      }
      var checks = batch.checks(provider, statements);
      var all = new ArrayList<>(checks);
      all.addAll(statements);
      var values = all.stream().flatMap(s -> s.values().stream()).toList();
      Outcome outcome;
      try {
        outcome = provider.dialect().rows().changeable(connection, provider, table, values)
            ? run(connection, provider, all, checks.size())
            : new Outcome.Refused();
      } catch (QueryException | RuntimeException e) {
        open = false;
        rollBackAfter(connection, provider, e);
        throw e;
      }
      if (outcome instanceof Outcome.Committed) {
        wrote = true;
        return Optional.empty();
      }
      rollBack();
      return Optional.of(outcome);
    }

    /**
     * Commits what the batches wrote, after what {@link #afterWrites} says follows them where any
     * wrote, and turns auto-commit on again; the writer is over.
     *
     * @throws QueryException as {@link #write} does where the commit failed, once all was rolled
     *     back; where the connection is given up as the commit runs, whether it took effect is not
     *     known
     * @throws IllegalStateException if the writer is over
     */
    public void commit() throws QueryException {
      requireOpen();
      if (wrote) {
        try {
          for (var sql : afterWrites(provider, table)) {
            Journal.sql(
                provider,
                Purpose.CHANGES,
                sql,
                _ -> new dev.hoori.db.core.log.Outcome.Done(),
                () -> Query.run(connection, provider, sql, PreparedStatement::execute));
          }
        } catch (QueryException | RuntimeException e) {
          open = false;
          rollBackAfter(connection, provider, e);
          throw e;
        }
      }
      open = false;
      end(connection, provider, true);
    }

    /** Rolls back what the batches wrote, where the writer is not over, and ends it. */
    public void rollBack() throws QueryException {
      if (open) {
        open = false;
        end(connection, provider, false);
      }
    }

    /** As {@link #rollBack}, where a failure to end is the connection's, given up then. */
    @Override
    public void close() {
      try {
        rollBack();
      } catch (QueryException _) {
        // The transaction ended with the connection, which the server rolls back as it closes.
      }
    }

    private void requireOpen() {
      if (!open) {
        throw new IllegalStateException("The writer is over");
      }
    }
  }

  // Turns auto-commit off, within the bound of an ending, for a transaction of the changes' own.
  private static void begin(Connection connection, Provider provider) throws QueryException {
    Query.within(
        connection,
        provider,
        Execution.ENDING,
        () -> Journal.call(Purpose.TRANSACTION, Journal.autoCommit(false), () -> {
          connection.setAutoCommit(false);
          return null;
        }));
  }

  // Rolls back what the failure left of the transaction, adding a failure to do so to it.
  private static void rollBackAfter(Connection connection, Provider provider, Exception failure) {
    try {
      end(connection, provider, false);
    } catch (QueryException ended) {
      failure.addSuppressed(ended);
    }
  }

  private static void requireNoTransaction(Connection connection, Provider provider)
      throws QueryException {
    var status = Status.of(connection, provider);
    if (status.transaction() == Transaction.OPEN
        || status.transaction() == Transaction.FAILED
        || Boolean.FALSE.equals(status.autoCommit())
        || !Boolean.TRUE.equals(Query.run(connection, provider, connection::getAutoCommit))) {
      throw new IllegalStateException("A transaction is open on the connection");
    }
  }

  // The statements that read each row an update or deletion names, in their order.
  private List<Statement> checks(Provider provider, List<Statement> statements) {
    // Locked only where an update could move a row to a key that a later statement names, as
    // PostgreSQL grants a lock only with UPDATE privilege.
    var lock = statements.stream().anyMatch(s -> s.change() instanceof Change.Update);
    var checks = new ArrayList<Statement>();
    for (var statement : statements) {
      switch (statement.change()) {
        case Change.Update(var row, var values) ->
          checks.add(check(provider, statement.change(), row, values, lock));
        case Change.Delete(var row) ->
          checks.add(check(provider, statement.change(), row, Map.of(), lock));
        case Change.Insert _ -> {
          // A new row has no row to read before.
        }
      }
    }
    return checks;
  }

  // Runs the statements one after another, of which the first checks read their row: missed at the
  // first that meets not exactly one row.
  private static Outcome run(
      Connection connection, Provider provider, List<Statement> statements, int checks)
      throws QueryException {
    var dialect = provider.dialect().rows();
    for (var i = 0; i < statements.size(); i++) {
      var statement = statements.get(i);
      var check = i < checks;
      var rows = Journal.sql(
          provider,
          Purpose.CHANGES,
          statement.sql(),
          count -> check
              ? dev.hoori.db.core.log.Outcome.rows(count)
              : new dev.hoori.db.core.log.Outcome.Changed(count),
          () -> Query.run(
              connection,
              provider,
              statement.sql(),
              prepared -> execute(dialect, prepared, statement, check)));
      if (rows != 1) {
        return new Outcome.Missed(statement.change(), rows);
      }
    }
    return new Outcome.Committed();
  }

  // The rows the statement met, or that the check read.
  private static long execute(
      RowsDialect dialect, PreparedStatement prepared, Statement statement, boolean check)
      throws SQLException {
    for (var v = 0; v < statement.values().size(); v++) {
      dialect.bind(prepared, v + 1, statement.values().get(v));
    }
    if (!check) {
      return prepared.executeLargeUpdate();
    }
    var read = 0L;
    try (var result = prepared.executeQuery()) {
      while (result.next()) {
        read++;
      }
    }
    return read;
  }

  // Ends the transaction within a bound, an interrupt or not: commits or rolls back, and turns
  // auto-commit on again. A connection that cannot be left as it was found is aborted, which has
  // the server roll back what it did not commit; nothing after the commit or rollback fails what it
  // did, and nothing begins once the connection is given up for the bound.
  private static void end(Connection connection, Provider provider, boolean commit)
      throws QueryException {
    // Whether the transaction ended as it was to, committed or rolled back.
    var ended = new AtomicBoolean();
    var givenUp = new AtomicBoolean();
    try {
      Query.within(
          connection,
          provider,
          Execution.ENDING,
          () -> {
            finish(connection, commit, ended, givenUp);
            return null;
          },
          () -> givenUp.set(true));
    } catch (QueryException e) {
      if (!ended.get()) {
        throw e;
      }
    }
  }

  private static void finish(
      Connection connection, boolean commit, AtomicBoolean ended, AtomicBoolean givenUp)
      throws SQLException {
    if (connection.getAutoCommit()) {
      return; // never begun
    }
    var failed = commit ? commit(connection, ended, givenUp) : null;
    if (!ended.get()) {
      rollBack(connection, commit, ended, failed);
    }
    try {
      Journal.call(Purpose.TRANSACTION, Journal.autoCommit(true), () -> {
        connection.setAutoCommit(true);
        return null;
      });
    } catch (SQLException _) {
      abort(connection);
    }
    if (failed != null) {
      throw failed;
    }
  }

  // The commit's failure, or null where it committed.
  private static SQLException commit(
      Connection connection, AtomicBoolean ended, AtomicBoolean givenUp) {
    if (givenUp.get()) {
      return new SQLException("Given up before the commit", "08006");
    }
    try {
      Journal.callWithheld(Purpose.TRANSACTION, Journal.COMMIT, () -> {
        connection.commit();
        return null;
      });
      ended.set(true);
      return null;
    } catch (SQLException e) {
      return e;
    }
  }

  // Rolls back, after the commit failed where it did, which fails in its place.
  private static void rollBack(
      Connection connection, boolean commit, AtomicBoolean ended, SQLException failed)
      throws SQLException {
    try {
      Journal.call(Purpose.TRANSACTION, Journal.ROLLBACK, () -> {
        connection.rollback();
        return null;
      });
      ended.set(!commit);
    } catch (SQLException e) {
      // pgjdbc would commit as auto-commit is turned on.
      abort(connection);
      if (failed != null) {
        failed.addSuppressed(e);
        throw failed;
      }
      throw e;
    }
  }

  private static void abort(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException _) {
      // Not even an abort is possible; the connection is given up all the same.
    }
  }

  private String sql(
      Provider provider, Change change, String equal, Function<Object, String> value) {
    var table = Rows.qualified(provider, this.table);
    return switch (change) {
      case Change.Update(var row, var values) -> {
        var set = new StringJoiner(", ");
        values.forEach((column, v) -> set.add(name(provider, column) + " = " + value.apply(v)));
        yield "UPDATE " + table + " SET " + set + where(provider, row, equal, value);
      }
      case Change.Delete(var row) -> "DELETE FROM " + table + where(provider, row, equal, value);
      case Change.Insert(var inserted)
      when inserted.isEmpty() ->
        "INSERT INTO " + table + " (" + name(provider, 0) + ") VALUES (DEFAULT)";
      case Change.Insert(var inserted) -> {
        var names = new StringJoiner(", ");
        var values = new StringJoiner(", ");
        inserted.forEach((column, v) -> {
          names.add(name(provider, column));
          values.add(value.apply(v));
        });
        yield "INSERT INTO " + table + " (" + names + ") VALUES (" + values + ")";
      }
    };
  }

  // The statement that reads the change's row, named by its key and, for an update, the original
  // values of the columns it changes, and may lock it.
  private Statement check(
      Provider provider,
      Change change,
      List<Object> row,
      Map<Integer, Object> changed,
      boolean lock) {
    var dialect = provider.dialect().rows();
    var values = new ArrayList<Object>();
    var parameter = parameters(dialect, values);
    var where = new StringBuilder(where(provider, row, dialect.keyEquality(), parameter));
    for (var column : changed.keySet()) {
      where
          .append(" AND ")
          .append(dialect.unchanged(name(provider, column), row.get(column), parameter));
    }
    var sql =
        "SELECT 1 FROM " + Rows.qualified(provider, table) + where + (lock ? " FOR UPDATE" : "");
    return new Statement(change, sql, values, sql);
  }

  // A parameter for each value, which goes into the values.
  static Function<Object, String> parameters(RowsDialect dialect, List<Object> values) {
    return value -> {
      values.add(value);
      return dialect.parameter(value);
    };
  }

  String where(Provider provider, List<Object> row, String equal, Function<Object, String> value) {
    var where = new StringJoiner(" AND ", " WHERE ", "");
    for (var i : keys(columns)) {
      where.add(name(provider, i) + " " + equal + " " + value.apply(row.get(i)));
    }
    return where.toString();
  }

  private String name(Provider provider, int column) {
    return provider.quote(columns.get(column).name());
  }

  private List<Object> key(List<Object> row) {
    return keys(columns).stream().map(row::get).toList();
  }

  // The indexes of the columns of the primary key, in its order.
  private static List<Integer> keys(List<Column> columns) {
    return IntStream.range(0, columns.size())
        .filter(i -> columns.get(i).keyPosition() > 0)
        .boxed()
        .sorted(Comparator.comparingInt(i -> columns.get(i).keyPosition()))
        .toList();
  }

  static void requireRow(List<Column> columns, List<Object> row) {
    if (row.size() != columns.size()) {
      throw new IllegalArgumentException(
          "A row of " + row.size() + " values for " + columns.size() + " columns");
    }
    if (!names(columns, row)) {
      throw new IllegalArgumentException("A key that cannot name its row");
    }
  }

  /**
   * Whether the row, as it was read with the columns, can be named by its primary key: they have
   * one, and none of its values in the row is NULL or read only in part.
   */
  public static boolean names(List<Column> columns, List<Object> row) {
    var keys = keys(columns);
    return !keys.isEmpty()
        && keys.stream()
            .allMatch(i -> i < row.size()
                && row.get(i) != null
                && row.get(i) != Missing.FIELD
                && whole(row.get(i)));
  }

  static void requireOriginal(List<Object> row, int column) {
    if (column < 0 || column >= row.size()) {
      throw new IllegalArgumentException("No column " + column);
    }
    var original = row.get(column);
    if (original == Missing.FIELD) {
      throw new IllegalArgumentException("Column " + column + " was not read");
    }
    if (!whole(original)) {
      throw new IllegalArgumentException("Column " + column + " was read only in part");
    }
  }

  private static void requireValues(List<Column> columns, Map<Integer, Object> values) {
    for (var entry : values.entrySet()) {
      if (entry.getKey() < 0 || entry.getKey() >= columns.size()) {
        throw new IllegalArgumentException("No column " + entry.getKey());
      }
      if (entry.getValue() == Missing.FIELD) {
        throw new IllegalArgumentException("An unread value cannot be written");
      }
      if (!whole(entry.getValue())) {
        throw new IllegalArgumentException("A value read only in part cannot be written");
      }
    }
  }

  static boolean whole(Object value) {
    return !(value instanceof Cut) && !(value instanceof Binary binary && binary.cut());
  }
}

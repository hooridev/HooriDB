package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
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
   *     columns, a column that is none of them, an update without values, a key without a value,
   *     or a value read only in part, a {@link Cut} or a {@link Binary#cut} one, which can neither
   *     be written nor name a row
   */
  public Changes {
    columns = List.copyOf(columns);
    changes = List.copyOf(changes);
    for (var column : columns) {
      if (!column.table().equals(table)) {
        throw new IllegalArgumentException(column.name() + " is not a column of " + table.name());
      }
    }
    if (columns.stream().noneMatch(column -> column.keyPosition() > 0)) {
      throw new IllegalArgumentException(table.name() + " has no primary key");
    }
    for (var change : changes) {
      switch (change) {
        case Change.Update update -> {
          requireRow(columns, update.row());
          requireValues(columns, update.values());
          if (update.values().isEmpty()) {
            throw new IllegalArgumentException("An update without values");
          }
        }
        case Change.Insert insert -> requireValues(columns, insert.values());
        case Change.Delete delete -> requireRow(columns, delete.row());
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
   * @throws IllegalStateException if the row is to be deleted
   */
  public Changes edit(List<Object> row, int column, Object value) {
    requireRow(columns, row);
    var key = key(row);
    var changed = new ArrayList<>(changes);
    for (var i = 0; i < changed.size(); i++) {
      if (changed.get(i) instanceof Change.Delete delete && key(delete.row()).equals(key)) {
        throw new IllegalStateException("The row is to be deleted");
      }
      if (changed.get(i) instanceof Change.Update update && key(update.row()).equals(key)) {
        var values = new TreeMap<>(update.values());
        if (Objects.equals(update.row().get(column), value)) {
          values.remove(column);
        } else {
          values.put(column, value);
        }
        if (values.isEmpty()) {
          changed.remove(i);
        } else {
          changed.set(i, new Change.Update(update.row(), values));
        }
        return new Changes(table, columns, changed);
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
    if (!(changes.get(change) instanceof Change.Insert insert)) {
      throw new IllegalArgumentException("Change " + change + " is no new row");
    }
    var values = new TreeMap<>(insert.values());
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
        case Change.Update update when key(update.row()).equals(key) -> {}
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
    for (var kind : List.of(Change.Update.class, Change.Delete.class, Change.Insert.class)) {
      for (var change : changes) {
        if (kind.isInstance(change)) {
          var values = new ArrayList<Object>();
          var sql = sql(provider, change, dialect.equal(), parameters(dialect, values));
          var preview = sql(provider, change, "=", dialect::literal);
          statements.add(new Statement(change, sql, values, preview));
        }
      }
    }
    return statements;
  }

  /** What came of writing changes. */
  public sealed interface Outcome {

    /** Every change was written, and committed. */
    record Committed() implements Outcome {}

    /**
     * Nothing was written: the change's row was there not once but the given number of times as
     * the rows were locked, or its statement met that many rows, as when the row was deleted or its
     * key changed since it was read, and all was rolled back.
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
   * Writes the changes in a transaction of their own, all or nothing: where there are updates,
   * first each row that an update or deletion names is locked and must be there, once, so that no
   * statement meets a row that an earlier one moved to its key, as an update of a key or of what a
   * generated key or a trigger takes it from can; then the {@link #statements} run one after
   * another. Where a row is not
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
    var status = Status.of(connection, provider);
    if (status.transaction() == Transaction.OPEN
        || status.transaction() == Transaction.FAILED
        || Boolean.FALSE.equals(status.autoCommit())
        || !Query.run(connection, provider, connection::getAutoCommit)) {
      throw new IllegalStateException("A transaction is open on the connection");
    }
    var dialect = provider.dialect().rows();
    var values = statements.stream().flatMap(s -> s.values().stream()).toList();
    if (!dialect.changeable(connection, provider, table, values)) {
      return new Outcome.Refused();
    }
    // Only an update can move a row to a key that a later statement names: deletions alone are
    // known by their counts, without a lock, which PostgreSQL grants only with UPDATE privilege.
    var updates = statements.stream().anyMatch(s -> s.change() instanceof Change.Update);
    var locks = new ArrayList<Statement>();
    for (var statement : updates ? statements : List.<Statement>of()) {
      switch (statement.change()) {
        case Change.Update update -> locks.add(lock(provider, update, update.row()));
        case Change.Delete delete -> locks.add(lock(provider, delete, delete.row()));
        case Change.Insert insert -> {}
      }
    }
    Outcome outcome = new Outcome.Committed();
    try {
      Query.within(connection, provider, Execution.ENDING, () -> {
        connection.setAutoCommit(false);
        return null;
      });
      var all = new ArrayList<>(locks);
      all.addAll(statements);
      for (var i = 0; i < all.size(); i++) {
        var statement = all.get(i);
        var lock = i < locks.size();
        var rows = Query.run(connection, provider, statement.sql(), prepared -> {
          for (var v = 0; v < statement.values().size(); v++) {
            dialect.bind(prepared, v + 1, statement.values().get(v));
          }
          if (!lock) {
            return prepared.executeLargeUpdate();
          }
          var read = 0L;
          try (var result = prepared.executeQuery()) {
            while (result.next()) {
              read++;
            }
          }
          return read;
        });
        if (rows != 1) {
          outcome = new Outcome.Missed(statement.change(), rows);
          break;
        }
      }
    } catch (QueryException | RuntimeException e) {
      try {
        end(connection, provider, false);
      } catch (QueryException ended) {
        e.addSuppressed(ended);
      }
      throw e;
    }
    end(connection, provider, outcome instanceof Outcome.Committed);
    return outcome;
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
            if (connection.getAutoCommit()) {
              return null; // never begun
            }
            SQLException failed = null;
            if (commit) {
              try {
                if (givenUp.get()) {
                  throw new SQLException("Given up before the commit", "08006");
                }
                connection.commit();
                ended.set(true);
              } catch (SQLException e) {
                failed = e;
              }
            }
            if (!ended.get()) {
              try {
                connection.rollback();
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
            try {
              connection.setAutoCommit(true);
            } catch (SQLException e) {
              abort(connection);
            }
            if (failed != null) {
              throw failed;
            }
            return null;
          },
          () -> givenUp.set(true));
    } catch (QueryException e) {
      if (!ended.get()) {
        throw e;
      }
    }
  }

  private static void abort(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException e) {
      // Not even an abort is possible; the connection is given up all the same.
    }
  }

  private String sql(
      Provider provider, Change change, String equal, Function<Object, String> value) {
    var table = Rows.qualified(provider, this.table);
    return switch (change) {
      case Change.Update update -> {
        var set = new StringJoiner(", ");
        update
            .values()
            .forEach((column, v) -> set.add(name(provider, column) + " = " + value.apply(v)));
        yield "UPDATE " + table + " SET " + set + where(provider, update.row(), equal, value);
      }
      case Change.Delete delete ->
        "DELETE FROM " + table + where(provider, delete.row(), equal, value);
      case Change.Insert insert
      when insert.values().isEmpty() ->
        "INSERT INTO " + table + " (" + name(provider, 0) + ") VALUES (DEFAULT)";
      case Change.Insert insert -> {
        var names = new StringJoiner(", ");
        var values = new StringJoiner(", ");
        insert.values().forEach((column, v) -> {
          names.add(name(provider, column));
          values.add(value.apply(v));
        });
        yield "INSERT INTO " + table + " (" + names + ") VALUES (" + values + ")";
      }
    };
  }

  // The statement that locks the change's row, named by its key, and reads it.
  private Statement lock(Provider provider, Change change, List<Object> row) {
    var dialect = provider.dialect().rows();
    var values = new ArrayList<Object>();
    var sql = "SELECT 1 FROM " + Rows.qualified(provider, table)
        + where(provider, row, dialect.equal(), parameters(dialect, values)) + " FOR UPDATE";
    return new Statement(change, sql, values, sql);
  }

  // A parameter for each value, which goes into the values.
  private static Function<Object, String> parameters(RowsDialect dialect, List<Object> values) {
    return value -> {
      values.add(value);
      return dialect.parameter(value);
    };
  }

  private String where(
      Provider provider, List<Object> row, String equal, Function<Object, String> value) {
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

  private static void requireRow(List<Column> columns, List<Object> row) {
    if (row.size() != columns.size()) {
      throw new IllegalArgumentException(
          "A row of " + row.size() + " values for " + columns.size() + " columns");
    }
    for (var i : keys(columns)) {
      if (row.get(i) == null || !whole(row.get(i))) {
        throw new IllegalArgumentException("A key that cannot name its row");
      }
    }
  }

  private static void requireValues(List<Column> columns, Map<Integer, Object> values) {
    for (var entry : values.entrySet()) {
      if (entry.getKey() < 0 || entry.getKey() >= columns.size()) {
        throw new IllegalArgumentException("No column " + entry.getKey());
      }
      if (!whole(entry.getValue())) {
        throw new IllegalArgumentException("A value read only in part cannot be written");
      }
    }
  }

  private static boolean whole(Object value) {
    return !(value instanceof Cut) && !(value instanceof Binary binary && binary.cut());
  }
}

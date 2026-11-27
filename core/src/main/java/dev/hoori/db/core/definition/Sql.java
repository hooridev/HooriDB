package dev.hoori.db.core.definition;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What reading definitions sends, and what changes them or exports them: each statement through
 * {@link Journal}, as the purpose that the caller bound with {@link #as}. Only core's code that
 * builds on definitions uses it, as the export does.
 */
public final class Sql {

  private static final ScopedValue<Purpose> PURPOSE = ScopedValue.newInstance();

  private Sql() {}

  /** How a row of a query becomes a value. */
  public interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** The call, with what it sends here recorded as sent for the purpose. */
  public static <T, E extends Exception> T as(Purpose purpose, Journal.Call<T, E> call) throws E {
    return ScopedValue.where(PURPOSE, purpose).call(call::call);
  }

  /**
   * What is sent now is sent for.
   *
   * @throws java.util.NoSuchElementException where no caller bound one with {@link #as}
   */
  public static Purpose purpose() {
    return PURPOSE.get();
  }

  /** Runs the statement, which gives no rows that count. */
  public static void execute(Connection connection, Provider provider, String sql)
      throws QueryException {
    Journal.sql(
        provider,
        purpose(),
        sql,
        done -> Outcome.DONE,
        () -> Query.run(connection, provider, sql, statement -> statement.execute()));
  }

  /**
   * Runs what ends the snapshot, recorded as called, within {@link Execution#ENDING}: also after
   * an interrupt, as what cancelled the export leaves the snapshot open, and never longer, as a
   * server that no longer answers does not keep it.
   */
  public static void ending(
      Connection connection, Provider provider, String called, Query.Step<?> step)
      throws QueryException {
    Journal.call(
        purpose(), called, () -> Query.within(connection, provider, Execution.ENDING, step));
  }

  /** Runs the statement as {@link #ending} runs a step. */
  public static void ending(Connection connection, Provider provider, String sql)
      throws QueryException {
    Journal.sql(
        provider,
        purpose(),
        sql,
        done -> Outcome.DONE,
        () -> Query.within(connection, provider, Execution.ENDING, () -> {
          try (var statement = connection.createStatement()) {
            statement.setEscapeProcessing(false);
            return statement.execute(sql);
          }
        }));
  }

  /** What puts back what a step changed, where the step failed. */
  public interface Undo {
    void run() throws QueryException;
  }

  /** The failure, after the undo, whose own failure goes with it. */
  public static QueryException undone(QueryException failure, Undo undo) {
    try {
      undo.run();
    } catch (QueryException e) {
      failure.addSuppressed(e);
    }
    return failure;
  }

  /** The rows of the query, each as the row makes it, with the texts as its parameters. */
  public static <T> List<T> rows(
      Connection connection, Provider provider, String sql, Row<T> row, String... parameters)
      throws QueryException {
    return Journal.sql(
        provider,
        purpose(),
        sql,
        read -> Outcome.rows(read.size()),
        () -> Query.run(connection, provider, sql, statement -> {
          for (var i = 0; i < parameters.length; i++) {
            statement.setString(i + 1, parameters[i]);
          }
          try (var rows = statement.executeQuery()) {
            var read = new ArrayList<T>();
            while (rows.next()) {
              read.add(row.read(rows));
            }
            return List.copyOf(read);
          }
        }));
  }

  /** The rows of the query that the row makes something of, as {@link #rows} reads them. */
  public static <T> List<T> some(
      Connection connection,
      Provider provider,
      String sql,
      Row<Optional<T>> row,
      String... parameters)
      throws QueryException {
    return rows(connection, provider, sql, row, parameters).stream()
        .flatMap(Optional::stream)
        .toList();
  }

  /** The one row of the query, as the row makes it; not found where it gives none. */
  public static <T> T one(
      Connection connection,
      Provider provider,
      String what,
      String sql,
      Row<T> row,
      String... parameters)
      throws QueryException {
    var read = rows(connection, provider, sql, row, parameters);
    if (read.isEmpty()) {
      throw QueryException.of(QueryException.Failure.NOT_FOUND, what + " no longer exists");
    }
    return read.getFirst();
  }
}

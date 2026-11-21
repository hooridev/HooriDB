package dev.hoori.db.core.dump;

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

/** What an export sends, each through {@link Journal} as {@link Purpose#EXPORT}. */
final class Sql {

  private Sql() {}

  interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** Runs the statement, which gives no rows that count. */
  static void execute(Connection connection, Provider provider, String sql) throws QueryException {
    Journal.sql(
        provider,
        Purpose.EXPORT,
        sql,
        done -> Outcome.DONE,
        () -> Query.run(connection, provider, sql, statement -> statement.execute()));
  }

  /**
   * Runs what ends the snapshot, recorded as called, within {@link Execution#ENDING}: also after
   * an interrupt, as what cancelled the export leaves the snapshot open, and never longer, as a
   * server that no longer answers does not keep it.
   */
  static void ending(Connection connection, Provider provider, String called, Query.Step<?> step)
      throws QueryException {
    Journal.call(
        Purpose.EXPORT, called, () -> Query.within(connection, provider, Execution.ENDING, step));
  }

  /** Runs the statement as {@link #ending} runs a step. */
  static void ending(Connection connection, Provider provider, String sql) throws QueryException {
    Journal.sql(
        provider,
        Purpose.EXPORT,
        sql,
        done -> Outcome.DONE,
        () -> Query.within(connection, provider, Execution.ENDING, () -> {
          try (var statement = connection.createStatement()) {
            statement.setEscapeProcessing(false);
            return statement.execute(sql);
          }
        }));
  }

  /**
   * The rows of the query, each as the row makes it, with the texts as its parameters; a row it
   * makes null of is left out.
   */
  static <T> List<T> rows(
      Connection connection, Provider provider, String sql, Row<T> row, String... parameters)
      throws QueryException {
    return Journal.sql(
        provider,
        Purpose.EXPORT,
        sql,
        read -> Outcome.rows(read.size()),
        () -> Query.run(connection, provider, sql, statement -> {
          for (var i = 0; i < parameters.length; i++) {
            statement.setString(i + 1, parameters[i]);
          }
          try (var rows = statement.executeQuery()) {
            var read = new ArrayList<T>();
            while (rows.next()) {
              var made = row.read(rows);
              if (made != null) {
                read.add(made);
              }
            }
            return List.copyOf(read);
          }
        }));
  }

  /** The one row of the query, as the row makes it; not found where it gives none. */
  static <T> T one(
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

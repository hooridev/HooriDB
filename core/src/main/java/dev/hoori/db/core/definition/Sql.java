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
import java.util.Objects;
import java.util.Optional;

/**
 * Where definitions are read, changed or exported: a connection, the provider that speaks on it,
 * and the purpose that what it sends is recorded as through {@link Journal}, which its caller
 * names. Only core's code that builds on definitions uses it, as the export does.
 */
public record Sql(Connection connection, Provider provider, Purpose purpose) {

  public Sql {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(purpose, "purpose");
  }

  /** How a row of a query becomes a value. */
  public interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** Runs the statement, which gives no rows that count. */
  public void execute(String sql) throws QueryException {
    Journal.sql(
        provider,
        purpose,
        sql,
        done -> Outcome.DONE,
        () -> Query.run(connection, provider, sql, statement -> statement.execute()));
  }

  /** Runs the step of the connection itself, as a change of auto-commit, recorded as called. */
  public void call(String called, Query.Step<?> step) throws QueryException {
    Journal.call(purpose, called, () -> Query.run(connection, provider, step));
  }

  /**
   * Runs what ends the snapshot, recorded as called, within {@link Execution#ENDING}: also after
   * an interrupt, as what cancelled the export leaves the snapshot open, and never longer, as a
   * server that no longer answers does not keep it.
   */
  public void ending(String called, Query.Step<?> step) throws QueryException {
    Journal.call(purpose, called, () -> Query.within(connection, provider, Execution.ENDING, step));
  }

  /** Runs the statement as {@link #ending(String, Query.Step)} runs a step. */
  public void ending(String sql) throws QueryException {
    Journal.sql(
        provider,
        purpose,
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
  public <T> List<T> rows(String sql, Row<T> row, String... parameters) throws QueryException {
    return Journal.sql(
        provider,
        purpose,
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
  public <T> List<T> some(String sql, Row<Optional<T>> row, String... parameters)
      throws QueryException {
    return rows(sql, row, parameters).stream().flatMap(Optional::stream).toList();
  }

  /** The one row of the query, as the row makes it; not found where it gives none. */
  public <T> T one(String what, String sql, Row<T> row, String... parameters)
      throws QueryException {
    var read = rows(sql, row, parameters);
    if (read.isEmpty()) {
      throw QueryException.of(QueryException.Failure.NOT_FOUND, what + " no longer exists");
    }
    return read.getFirst();
  }

  /** The literal of the text, as the provider writes one. */
  public String literal(String text) {
    return provider.dialect().rows().literal(text);
  }

  /** The name, quoted as the provider quotes one. */
  public String quote(String name) {
    return provider.quote(name);
  }
}

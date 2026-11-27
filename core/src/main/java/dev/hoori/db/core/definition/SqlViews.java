package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The views of a server of SQL, on a JDBC connection on the view's database, in auto-commit as
 * {@link dev.hoori.db.core.connect.Connector} opens it: read as {@link DefinitionDialect} reads
 * them, and changed by the statements of a {@link ViewChange}, each sent as it is, without escape
 * processing. What it sends goes through {@link Journal} as {@link Purpose#DEFINITION}. Each call
 * runs through {@link Query}: an interrupt ends it with {@link QueryException.Failure#CANCELLED},
 * or {@link QueryException.Failure#ABORTED} where the connection had to be given up.
 *
 * @param readOnly whether the connection's profile is read-only, which changes nothing here and
 *     sends nothing for a change
 */
public record SqlViews(Connection connection, Provider provider, boolean readOnly)
    implements Views {

  @Override
  public ViewDefinition read(Table view) throws QueryException {
    if (view.kind() != Table.Kind.VIEW && view.kind() != Table.Kind.MATERIALIZED) {
      throw new IllegalArgumentException(view.name() + " is no view");
    }
    var dialect = provider.dialect().definitions();
    return Sql.as(Purpose.DEFINITION, () -> {
      requireOn(view);
      dialect.begin(connection, provider);
      try {
        var item = Item.of(view.container(), Item.Kind.VIEW, view.name());
        var definition = dialect.define(connection, provider, List.of(item)).getFirst();
        return new ViewDefinition(
            view,
            definition,
            dialect.references(connection, provider, view),
            dialect.refreshesConcurrently(connection, provider, view));
      } finally {
        endQuietly(dialect);
      }
    });
  }

  @Override
  public Changed change(ViewChange change) throws QueryException {
    if (readOnly) {
      throw QueryException.of(
          QueryException.Failure.OTHER,
          "The connection is read-only, which changes no view: nothing was sent.");
    }
    var dialect = provider.dialect().definitions();
    return Sql.as(Purpose.DEFINITION, () -> {
      requireOn(change.view());
      try {
        if (change.transaction()) {
          inTransaction(change.statements());
        } else {
          for (var sql : change.statements()) {
            send(sql);
          }
        }
      } catch (QueryException e) {
        if (change.kind() == ViewChange.Kind.REPLACE && dialect.keepsColumns(e)) {
          return new KeepsColumns(e.detail());
        }
        throw e;
      }
      return new Done();
    });
  }

  // All or none: a failure rolls back what went before it; either way auto-commit comes back on.
  private void inTransaction(List<String> statements) throws QueryException {
    Journal.call(
        Sql.purpose(),
        Journal.autoCommit(false),
        () -> Query.run(connection, provider, () -> {
          connection.setAutoCommit(false);
          return null;
        }));
    try {
      for (var sql : statements) {
        send(sql);
      }
      Journal.call(
          Sql.purpose(),
          Journal.COMMIT,
          () -> Query.run(connection, provider, () -> {
            connection.commit();
            return null;
          }));
    } catch (QueryException e) {
      throw Sql.undone(
          e,
          () -> Sql.ending(connection, provider, Journal.ROLLBACK, () -> {
            connection.rollback();
            return null;
          }));
    } finally {
      Sql.ending(connection, provider, Journal.autoCommit(true), () -> {
        connection.setAutoCommit(true);
        return null;
      });
    }
  }

  // A plain statement, as the user's text says it: a prepared one would take a ? for a parameter.
  private void send(String sql) throws QueryException {
    var running = new AtomicReference<Statement>();
    Journal.sql(
        provider,
        Sql.purpose(),
        sql,
        done -> Outcome.DONE,
        () -> Query.run(
            connection,
            provider,
            () -> {
              var statement = running.get();
              if (statement != null) {
                statement.cancel();
              }
            },
            () -> {
              try (var statement = connection.createStatement()) {
                running.set(statement);
                statement.setEscapeProcessing(false);
                return statement.execute(sql);
              }
            },
            ran -> false));
  }

  // A failure to end a snapshot that was only read in changes nothing of what was read; a
  // connection it broke is found by its next call.
  private void endQuietly(DefinitionDialect dialect) {
    try {
      dialect.end(connection, provider);
    } catch (QueryException _) {
      // As said.
    }
  }

  // The session names the view's objects without its database on MariaDB and MySQL, and reads
  // only its own database on PostgreSQL; in auto-commit, so that no transaction of the caller's
  // is ended.
  private void requireOn(Table view) throws QueryException {
    String current;
    boolean autoCommits;
    try {
      current = connection.getCatalog();
      autoCommits = connection.getAutoCommit();
    } catch (SQLException e) {
      throw Query.failure(connection, provider, e);
    }
    var database = view.container().database().name();
    if (!database.equals(current)) {
      throw new IllegalArgumentException(
          "A view of " + database + " on a connection to " + current);
    }
    if (!autoCommits) {
      throw new IllegalArgumentException("A view's change on a connection with a transaction open");
    }
  }
}

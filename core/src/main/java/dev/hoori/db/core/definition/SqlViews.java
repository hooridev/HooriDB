package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
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
 * The views of a server of SQL, on a JDBC connection in auto-commit, as {@link
 * dev.hoori.db.core.connect.Connector} opens it: read as {@link DefinitionDialect} and {@link
 * ViewDialect} read them, and changed by the statements of a {@link ViewChange}, each sent as it
 * is, without escape processing. A connection to another database of a server without schemas is
 * put on the view's for the call, which its definition names its objects without, and back after.
 * What it sends goes through {@link Journal} as {@link Purpose#DEFINITION}. Each call runs through
 * {@link Query}: an interrupt ends it with {@link QueryException.Failure#CANCELLED}, or {@link
 * QueryException.Failure#ABORTED} where the connection had to be given up.
 *
 * @param readOnly whether the connection's profile is read-only, which changes nothing here and
 *     sends nothing for a change
 */
public record SqlViews(Connection connection, Provider provider, boolean readOnly)
    implements Views {

  private interface Call<T> {
    T call(Sql sql) throws QueryException;
  }

  @Override
  public ViewDefinition read(Table view) throws QueryException {
    if (!view.kind().view()) {
      throw new IllegalArgumentException(view.name() + " is no view");
    }
    var definitions = provider.dialect().definitions();
    var views = provider.dialect().views();
    return on(view, sql -> {
      definitions.begin(sql);
      try {
        var item = Item.of(view.container(), Item.Kind.VIEW, view.name());
        var definition =
            definitions.define(sql, List.of(item), DefinitionDialect.Mode.EDIT).getFirst();
        return new ViewDefinition(
            view,
            definition,
            views.references(sql, view),
            views.refreshesConcurrently(sql, view),
            views.restore(sql, view),
            views.unset(sql, view));
      } finally {
        endQuietly(definitions, sql);
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
    var views = provider.dialect().views();
    return on(change.view(), sql -> {
      try {
        sendAll(sql, change);
      } catch (QueryException e) {
        return refused(change, views, e);
      }
      return new Done();
    });
  }

  // The change's statements, in one transaction where it has one.
  private void sendAll(Sql sql, ViewChange change) throws QueryException {
    if (change.transaction()) {
      inTransaction(sql, change.statements());
      return;
    }
    for (var statement : change.statements()) {
      send(sql, statement);
    }
  }

  // What a change that failed tells: that the server refused it in a way that asks for another,
  // else its failure, thrown.
  private static Changed refused(ViewChange change, ViewDialect views, QueryException e)
      throws QueryException {
    if (change.kind() == ViewChange.Kind.REPLACE && views.keepsColumns(e)) {
      return new KeepsColumns(e.detail());
    }
    if (change.kind() == ViewChange.Kind.DROP && views.dependedOn(e)) {
      return new DependedOn(e.detail());
    }
    throw e;
  }

  // All or none: a failure rolls back what went before it; either way auto-commit comes back on.
  private void inTransaction(Sql sql, List<String> statements) throws QueryException {
    sql.call(Journal.autoCommit(false), () -> {
      connection.setAutoCommit(false);
      return null;
    });
    try {
      for (var statement : statements) {
        send(sql, statement);
      }
      sql.call(Journal.COMMIT, () -> {
        connection.commit();
        return null;
      });
    } catch (QueryException e) {
      throw Sql.undone(
          e,
          () -> sql.ending(Journal.ROLLBACK, () -> {
            connection.rollback();
            return null;
          }));
    } finally {
      sql.ending(Journal.autoCommit(true), () -> {
        connection.setAutoCommit(true);
        return null;
      });
    }
  }

  // A plain statement, as the user's text says it: a prepared one would take a ? for a parameter.
  private void send(Sql sql, String statement) throws QueryException {
    var running = new AtomicReference<Statement>();
    Journal.sql(
        provider,
        sql.purpose(),
        statement,
        done -> Outcome.DONE,
        () -> Query.run(
            connection,
            provider,
            () -> {
              var sent = running.get();
              if (sent != null) {
                sent.cancel();
              }
            },
            () -> {
              try (var sent = connection.createStatement()) {
                running.set(sent);
                sent.setEscapeProcessing(false);
                return sent.execute(statement);
              }
            },
            ran -> false));
  }

  // A failure to end a snapshot that was only read in changes nothing of what was read; a
  // connection it broke is found by its next call.
  private static void endQuietly(DefinitionDialect dialect, Sql sql) {
    try {
      dialect.end(sql);
    } catch (QueryException _) {
      // As said.
    }
  }

  // The session back on the database it was on.
  private void back(Sql sql, String database) throws QueryException {
    if (database != null) {
      sql.ending(Journal.catalog(database), () -> {
        connection.setCatalog(database);
        return null;
      });
    }
  }

  /**
   * The call on the view's database, in auto-commit, so that no transaction of the caller's is
   * ended: on a server without schemas the session goes to that database for it and back after,
   * as MariaDB and MySQL name the view's objects without it there; on one with schemas, which
   * reads only its own database, it must be there.
   */
  private <T> T on(Table view, Call<T> call) throws QueryException {
    var sql = new Sql(connection, provider, Purpose.DEFINITION);
    String current;
    boolean autoCommits;
    try {
      current = connection.getCatalog();
      autoCommits = connection.getAutoCommit();
    } catch (SQLException e) {
      throw Query.failure(connection, provider, e);
    }
    if (!autoCommits) {
      throw new IllegalArgumentException("A view's change on a connection with a transaction open");
    }
    var database = view.container().database().name();
    if (database.equals(current)) {
      return call.call(sql);
    }
    if (provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN)) {
      throw new IllegalArgumentException(
          "A view of " + database + " on a connection to " + current);
    }
    sql.call(Journal.catalog(database), () -> {
      connection.setCatalog(database);
      return null;
    });
    T result;
    try {
      result = call.call(sql);
    } catch (QueryException e) {
      throw Sql.undone(e, () -> back(sql, current));
    }
    back(sql, current);
    return result;
  }
}

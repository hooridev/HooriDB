package dev.hoori.db.core.monitor;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.dialect.MonitorDialect;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The monitor of a server of SQL, on a JDBC connection, by its dialect's queries. */
public record SqlMonitor(Connection connection, Provider provider) implements Monitor {

  @Override
  public List<ServerSession> sessions() throws QueryException {
    var monitor = provider.dialect().monitor();
    return Query.query(connection, provider, monitor.sessions(), rows -> {
      var sessions = new ArrayList<ServerSession>();
      while (rows.next()) {
        var session = monitor.session(rows);
        sessions.add(session.withStatement(Credentials.mask(session.statement(), provider)));
      }
      return List.copyOf(sessions);
    });
  }

  @Override
  public boolean cancel(ServerSession session) throws QueryException {
    if (!session.cancellable()) {
      throw new IllegalArgumentException("Session " + session.id() + " runs nothing to cancel");
    }
    return stop(provider.dialect().monitor().cancel(session));
  }

  @Override
  public boolean end(ServerSession session) throws QueryException {
    if (!session.ends()) {
      throw new IllegalArgumentException("Session " + session.id() + " cannot be ended here");
    }
    return stop(provider.dialect().monitor().end(session));
  }

  /** By the first of the dialect's queries that the server answers. */
  @Override
  public List<ServerVariable> variables() throws QueryException {
    QueryException failed = null;
    for (var sql : provider.dialect().monitor().variables()) {
      try {
        return Query.query(connection, provider, sql, rows -> {
          boolean sourced = rows.getMetaData().getColumnCount() >= 3;
          var variables = new ArrayList<ServerVariable>();
          while (rows.next()) {
            variables.add(Credentials.mask(new ServerVariable(
                rows.getString(1), rows.getString(2), sourced ? rows.getString(3) : "")));
          }
          return List.copyOf(variables);
        });
      } catch (QueryException e) {
        if (!fallsBack(e)) {
          throw e;
        }
        failed = failed == null ? e : failed;
      }
    }
    throw failed;
  }

  // Whether the next query may answer where this one failed: not where the connection is gone or
  // the read was cancelled.
  private static boolean fallsBack(QueryException e) {
    return switch (e.failure()) {
      case ACCESS_DENIED, NOT_FOUND, OTHER -> true;
      case CONNECTION_LOST, CANCELLED, ABORTED, COLUMNS_CHANGED, CLAUSE_REFUSED -> false;
    };
  }

  @Override
  public List<Counter> status() throws QueryException {
    return Query.query(connection, provider, provider.dialect().monitor().status(), rows -> {
      var counters = new ArrayList<Counter>();
      while (rows.next()) {
        counters.add(new Counter(rows.getString(1), rows.getString(2)));
      }
      return List.copyOf(counters);
    });
  }

  @Override
  public Optional<List<Block>> locks() throws QueryException {
    return Optional.of(Query.query(
        connection, provider, provider.dialect().monitor().locks(), rows -> {
          var blocks = new ArrayList<Block>();
          while (rows.next()) {
            blocks.add(new Block(
                rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
          }
          return List.copyOf(blocks);
        }));
  }

  // Runs the statement that stops a session: whether the server found it, by its row where it
  // gives one, none where it gives none, else by whether it failed as a session that is not there.
  private boolean stop(MonitorDialect.Stop stop) throws QueryException {
    try {
      return Journal.sql(
          provider,
          Purpose.MONITOR,
          stop.sql(),
          found -> Outcome.DONE,
          () -> Query.run(connection, provider, stop.sql(), statement -> {
            for (int i = 0; i < stop.values().size(); i++) {
              statement.setString(i + 1, stop.values().get(i));
            }
            if (!statement.execute()) {
              return true;
            }
            try (var rows = statement.getResultSet()) {
              return rows.next() && rows.getBoolean(1);
            }
          }));
    } catch (QueryException e) {
      if (e.getCause() instanceof SQLException x
          && Condition.NO_SUCH_SESSION.in(
              provider, x.getSQLState() == null ? "" : x.getSQLState(), x.getErrorCode())) {
        return false;
      }
      throw e;
    }
  }
}

package dev.hoori.db.core.monitor;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
    return stop(provider.dialect().monitor().cancel(session.operation()));
  }

  @Override
  public boolean end(ServerSession session) throws QueryException {
    if (!session.ends()) {
      throw new IllegalArgumentException("Session " + session.id() + " cannot be ended here");
    }
    return stop(provider.dialect().monitor().end(session.id()));
  }

  // Runs the statement that stops a session: whether the server found it, by its row where it
  // gives one, else by whether it failed as a session that is not there.
  private boolean stop(String sql) throws QueryException {
    try {
      return Query.run(connection, provider, sql, statement -> {
        if (!statement.execute()) {
          return true;
        }
        try (var rows = statement.getResultSet()) {
          return rows.next() && rows.getBoolean(1);
        }
      });
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

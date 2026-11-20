package dev.hoori.db.core.monitor;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
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
}

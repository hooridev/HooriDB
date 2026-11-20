package dev.hoori.db.core.dialect;

import dev.hoori.db.core.monitor.ServerSession;
import java.sql.ResultSet;
import java.sql.SQLException;

/** How a provider's servers tell what their sessions do now. */
public interface MonitorDialect {

  /**
   * The query of the sessions of the server's clients, a row each in the order of their ids, the
   * one that runs it among them, which {@link #session} reads.
   */
  String sessions();

  /** The session of the query's current row, its statement as the server tells it. */
  ServerSession session(ResultSet row) throws SQLException;
}

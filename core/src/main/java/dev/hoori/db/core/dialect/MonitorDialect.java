package dev.hoori.db.core.dialect;

import dev.hoori.db.core.monitor.ServerSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** How a provider's servers tell what their sessions do now. */
public interface MonitorDialect {

  /**
   * The query of the sessions of the server's clients, a row each in the order of their ids, the
   * one that runs it among them, which {@link #session} reads.
   */
  String sessions();

  /** The session of the query's current row, its statement as the server tells it. */
  ServerSession session(ResultSet row) throws SQLException;

  /** A statement with the values of its parameters, as text, in order. */
  record Stop(String sql, List<String> values) {

    public Stop {
      values = List.copyOf(values);
    }
  }

  /**
   * The statement that cancels what the session runs, by its {@link ServerSession#operation}, in
   * one with checking, where the server can, that the session still runs what it ran when read;
   * one that gives a row says in it whether the server found it so, and none says it did not.
   */
  Stop cancel(ServerSession session);

  /**
   * The statement that ends the session, in one with checking, where the server can, that its id
   * still names it, as {@link #cancel} answers.
   */
  Stop end(ServerSession session);

  /**
   * The queries of the server's variables, the one that tells most first, each of which gives a
   * name, a value and, where it tells, a source, by name; a query that fails gives way to the next,
   * as where performance_schema is off or the user may not read it.
   */
  List<String> variables();

  /** The query of the server's status counters, a name and a value each. */
  String status();

  /**
   * The query of the sessions that wait for a lock, a row each: the id of the one that waits and
   * of the one that blocks it, what the lock is on and its mode, by the one that waits.
   */
  String locks();
}

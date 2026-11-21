package dev.hoori.db.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.monitor.ServerSession;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class PostgreSqlMonitorTest {

  private static final MonitorDialect MONITOR = Provider.POSTGRESQL.dialect().monitor();

  // A row of the sessions' query: pid, own, user, program, address, port, database, state,
  // query, seconds, wait type, wait, backend_start, query_start.
  private static ServerSession session(String state, String started) throws SQLException {
    Object[] values = {
      null,
      "42",
      false,
      "ada",
      "psql",
      "10.0.0.1",
      5432,
      "shop",
      state,
      "SELECT 1",
      1.5,
      null,
      null,
      started,
      "2026-11-21 10:00:00+00"
    };
    boolean[] none = {false};
    var row = (ResultSet) Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, m, args) -> {
          if (m.getName().equals("wasNull")) {
            return none[0];
          }
          var value = values[(Integer) args[0]];
          none[0] = value == null;
          return switch (m.getName()) {
            case "getString" -> value == null ? null : value.toString();
            case "getBoolean" -> value != null && (Boolean) value;
            case "getInt" -> value == null ? 0 : (Integer) value;
            case "getDouble" -> value == null ? 0.0 : (Double) value;
            default -> throw new UnsupportedOperationException(m.getName());
          };
        });
    return MONITOR.session(row);
  }

  @Test
  void onlyAnActiveStatementCanBeCancelled() throws SQLException {
    assertTrue(session("active", "2026-11-21 09:00:00+00").cancellable());
    // A fastpath call does not set its start, so it cannot be told from the next.
    assertFalse(session("fastpath function call", "2026-11-21 09:00:00+00").cancellable());
    var cancel = MONITOR.cancel(session("active", "2026-11-21 09:00:00+00")).sql();
    assertTrue(cancel.contains("a.state OPERATOR(pg_catalog.=) 'active'"), cancel);
    assertFalse(cancel.contains("fastpath"), cancel);
  }

  @Test
  void aSessionWhoseStartIsHiddenCannotBeEnded() throws SQLException {
    assertTrue(session("idle", "2026-11-21 09:00:00+00").ends());
    var hidden = session("", null);
    assertEquals("", hidden.started());
    assertFalse(hidden.ends());
  }
}

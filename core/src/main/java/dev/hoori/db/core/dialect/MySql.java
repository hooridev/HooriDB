package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.dialect.Reflection.call;
import static dev.hoori.db.core.dialect.Since.always;
import static dev.hoori.db.core.dialect.Since.since;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.data.MySqlRows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** MySQL, with MySQL Connector/J. */
public final class MySql extends MySqlFamily {

  public static final MySql INSTANCE = new MySql();

  private final Map<Capability, Since> capabilities = Map.of(
      EXPLAIN, always(PLAN),
      EXPLAIN_JSON, since(5, 6, 5, PLAN_JSON),
      EXPLAIN_ANALYZE, since(8, 0, 18, "EXPLAIN ANALYZE "));
  private final Driver driver =
      new Driver("com.mysql", "mysql-connector-j", "com.mysql.cj.jdbc.Driver");
  private final ConnectDialect connecting = new Connect();
  private final SessionDialect session = new Session();
  private final RowsDialect rows = new MySqlRows();

  private MySql() {}

  @Override
  public String displayName() {
    return "MySQL";
  }

  @Override
  public String storedName() {
    return "mysql";
  }

  @Override
  public Map<Capability, Since> capabilities() {
    return capabilities;
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public String driverName() {
    return "MySQL Connector/J";
  }

  @Override
  public ConnectDialect connecting() {
    return connecting;
  }

  @Override
  public SessionDialect session() {
    return session;
  }

  @Override
  public RowsDialect rows() {
    return rows;
  }

  static final class Connect extends MySqlFamily.Connect {

    private static final Map<Tls, Map<String, String>> TLS = Map.of(
        // Without TLS, a first login with caching_sha2_password fails unless the client may ask
        // for the server's public key. Whoever can alter the traffic can hand it a key of their
        // own and read the password; OFF already accepts an unprotected link, and does not work
        // at all against a current MySQL without it.
        Tls.OFF, Map.of("sslMode", "DISABLED", "allowPublicKeyRetrieval", "true"),
        Tls.ENCRYPTED, Map.of("sslMode", "REQUIRED"),
        Tls.VERIFIED, Map.of("sslMode", "VERIFY_IDENTITY"));

    @Override
    public String scheme() {
      return "mysql";
    }

    @Override
    public Map<String, String> tls(Tls tls) {
      return TLS.get(tls);
    }

    // MariaDB Connector/J does not decode the path of the URL and MySQL Connector/J trims it, so
    // the name goes as a property, which both pass on as it is.
    @Override
    public Optional<String> databaseProperty() {
      return Optional.of("dbname");
    }

    // MySQL Connector/J decodes the host, and fails on the "%" of a zone that is not "%25".
    @Override
    public String ipv6(String address) {
      return address.replace("%", "%25");
    }

    // Else Connector/J applies JDBC escapes to a prepared statement, also inside a quoted name.
    @Override
    public Map<String, String> properties() {
      return Map.of("processEscapeCodesForPrepStmts", "false");
    }

    @Override
    public Map<String, String> timeouts(Duration timeout) {
      var millis = String.valueOf(Math.max(1, timeout.toMillis()));
      return Map.of("connectTimeout", millis, "socketTimeout", millis);
    }

    @Override
    String noTls() {
      return "SSL Connection required, but not provided by server";
    }
  }

  // MySQL Connector/J.
  static final class Session extends MySqlFamily.Session {

    @Override
    public int status(Connection connection) {
      try {
        return (int)
            call(call(call(connection, "getSession"), "getServerSession"), "getStatusFlags");
      } catch (ReflectiveOperationException | RuntimeException e) {
        return -1;
      }
    }

    /**
     * Has MySQL Connector/J's auto-commit say what the server's status says, after a statement such
     * as {@code SET autocommit=0} changed it on the server behind the driver's back; it then
     * refuses a commit. Setting it to what the server has already commits nothing.
     */
    @Override
    public void followAutoCommit(Connection connection, int status) throws SQLException {
      if (status < 0) {
        return;
      }
      var server = (status & SERVER_STATUS_AUTOCOMMIT) != 0;
      if (connection.getAutoCommit() != server) {
        connection.setAutoCommit(server);
      }
    }

    /**
     * Keeps MySQL Connector/J from raising the session's {@code net_write_timeout} for streamed
     * rows: it sets it back to what it read when it connected, not to what the session had, and
     * not at all for rows it does not close.
     */
    @Override
    public void keepWriteTimeout(Connection connection) {
      try {
        var properties = call(connection, "getPropertySet");
        var timeout = properties
            .getClass()
            .getMethod("getIntegerProperty", String.class)
            .invoke(properties, "netTimeoutForStreamingResults");
        timeout.getClass().getMethod("setValue", Object.class).invoke(timeout, 0);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // The driver changes it, and sets it back as it closes the rows.
      }
    }

    // MySQL Connector/J's Statement.cancel() does nothing once the statement has returned its first
    // rows: it is told that the statement still runs.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      try {
        var executing = call(call(statement, "getQuery"), "getStatementExecuting");
        ((AtomicBoolean) executing).set(true);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // Statement.cancel() is all there is.
      }
      statement.cancel();
    }

    // MySQL Connector/J streams only with this value, a row at a time.
    @Override
    public int fetchSize(int count) {
      return Integer.MIN_VALUE;
    }

    // MySQL Connector/J may close streamed rows with a statement of its own, which it refuses while
    // a
    // procedure has more results; they are closed after the last.
    @Override
    public boolean streamedRowsClosedLast() {
      return true;
    }
  }
}

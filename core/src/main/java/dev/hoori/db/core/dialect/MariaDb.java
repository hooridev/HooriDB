package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.dialect.Reflection.call;
import static dev.hoori.db.core.dialect.Since.always;
import static dev.hoori.db.core.dialect.Since.since;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.data.MariaDbRows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** MariaDB, with MariaDB Connector/J. */
public final class MariaDb extends MySqlFamily {

  public static final MariaDb INSTANCE = new MariaDb();

  private final Map<Capability, Since> capabilities = Map.of(
      EXPLAIN, always(PLAN),
      EXPLAIN_JSON, since(10, 1, 2, PLAN_JSON),
      EXPLAIN_ANALYZE, since(10, 1, 0, "ANALYZE "));
  private final Driver driver =
      new Driver("org.mariadb.jdbc", "mariadb-java-client", "org.mariadb.jdbc.Driver");
  private final ConnectDialect connecting = new Connect();
  private final SessionDialect session = new Session();
  private final RowsDialect rows = new MariaDbRows();

  private MariaDb() {}

  @Override
  public String displayName() {
    return "MariaDB";
  }

  @Override
  public String storedName() {
    return "mariadb";
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
    return "MariaDB Connector/J";
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

    // Without deferCertificateValidation=false, verify-full accepts a certificate the trust
    // store does not, against a fingerprint bound to the password and without the host name.
    private static final Map<Tls, Map<String, String>> TLS = Map.of(
        Tls.OFF, Map.of("sslMode", "disable"),
        Tls.ENCRYPTED, Map.of("sslMode", "trust"),
        Tls.VERIFIED, Map.of("sslMode", "verify-full", "deferCertificateValidation", "false"));

    @Override
    public String scheme() {
      return "mariadb";
    }

    @Override
    public Map<String, String> tls(Tls tls) {
      return TLS.get(tls);
    }

    // MariaDB Connector/J does not decode the path of the URL and MySQL Connector/J trims it, so
    // the name goes as a property, which both pass on as it is.
    @Override
    public Optional<String> databaseProperty() {
      return Optional.of("database");
    }

    // Also the time limit for each read while logging in.
    @Override
    public Map<String, String> timeouts(Duration timeout) {
      return Map.of("connectTimeout", String.valueOf(Math.max(1, timeout.toMillis())));
    }

    @Override
    String noTls() {
      return "ssl not enabled in the server";
    }
  }

  // MariaDB Connector/J.
  static final class Session extends MySqlFamily.Session {

    @Override
    public int status(Connection connection) {
      try {
        return (int) call(call(connection, "getContext"), "getServerStatus");
      } catch (ReflectiveOperationException | RuntimeException e) {
        return -1;
      }
    }

    // MariaDB Connector/J reads auto-commit from the status itself.
    @Override
    public void followAutoCommit(Connection connection, int status) {}

    @Override
    public void keepWriteTimeout(Connection connection) {}

    // MariaDB Connector/J cancels as long as it reads.
    @Override
    public void cancel(Connection connection, Statement statement) throws SQLException {
      statement.cancel();
    }

    @Override
    public boolean streamedRowsClosedLast() {
      return false;
    }
  }
}

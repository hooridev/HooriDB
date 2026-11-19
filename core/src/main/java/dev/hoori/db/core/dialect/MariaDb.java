package dev.hoori.db.core.dialect;

import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.dialect.Reflection.call;
import static dev.hoori.db.core.dialect.Since.always;
import static dev.hoori.db.core.dialect.Since.since;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.data.MariaDbRows;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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
  private final PlanDialect plan = new Plan();
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
  public PlanDialect plan() {
    return plan;
  }

  @Override
  public SessionDialect session() {
    return session;
  }

  @Override
  public RowsDialect rows() {
    return rows;
  }

  // MariaDB's plan gives only the rows read of each table: the product over the tables of each
  // select of the outermost UNION, summed, at most its LIMIT.
  static final class Plan extends MySqlFamily.Plan {

    @Override
    public String explain(String sql) {
      return "EXPLAIN " + sql;
    }

    @Override
    public OptionalLong rows(ResultSet rows, String sql, Provider provider) throws SQLException {
      // Each select's tables, by its id, joined; and the selects of a UNION inside another.
      var products = new LinkedHashMap<Object, Long>();
      var unions = new HashSet<Object>();
      var inner = new HashSet<String>();
      boolean any = false;
      while (rows.next()) {
        var id = rows.getObject("id");
        var type = rows.getString("select_type");
        if (id == null) {
          var table = String.valueOf(rows.getString("table"));
          if (table.startsWith("<union")) {
            inner.addAll(List.of(table.replaceAll("[<union>.]", "").split(",")));
          }
          continue;
        }
        if ("UNION".equals(type)) {
          unions.add(id);
        }
        long read = rows.getLong("rows");
        read = rows.wasNull() ? 1 : read;
        any |= !rows.wasNull();
        long product = products.getOrDefault(id, 1L);
        products.put(
            id, read != 0 && product > Long.MAX_VALUE / read ? Long.MAX_VALUE : product * read);
      }
      if (!any) {
        return OptionalLong.empty();
      }
      var first = products.keySet().iterator().next();
      // A UNION RESULT that names the first select names the outermost union's selects.
      boolean outermost = inner.contains(String.valueOf(first));
      long sum = 0;
      for (var entry : products.entrySet()) {
        var id = entry.getKey();
        if (id.equals(first)
            || unions.contains(id) && (outermost || !inner.contains(String.valueOf(id)))) {
          sum = sum > Long.MAX_VALUE - entry.getValue() ? Long.MAX_VALUE : sum + entry.getValue();
        }
      }
      var limit = Statements.limit(sql, provider);
      return OptionalLong.of(Math.min(sum, limit.orElse(Long.MAX_VALUE)));
    }
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

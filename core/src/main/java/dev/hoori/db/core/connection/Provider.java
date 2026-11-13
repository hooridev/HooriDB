package dev.hoori.db.core.connection;

import static dev.hoori.db.core.connection.Capability.ERROR_POSITION;
import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.connection.Capability.SCHEMAS;
import static dev.hoori.db.core.connection.Capability.TRANSACTIONAL_DDL;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** A kind of database server that HooriDB connects to. */
public enum Provider {
  POSTGRESQL(
      "PostgreSQL",
      5432,
      "postgresql",
      '"',
      "sslmode=verify-full",
      "sslmode=disable",
      Map.of(
          SCHEMAS, always(),
          TRANSACTIONAL_DDL, always(),
          ERROR_POSITION, always(),
          EXPLAIN, always("EXPLAIN "),
          EXPLAIN_JSON, since(9, 0, 0, "EXPLAIN (FORMAT JSON) "),
          EXPLAIN_ANALYZE, since(7, 2, 0, "EXPLAIN ANALYZE "))),
  MARIADB(
      "MariaDB",
      3306,
      "mariadb",
      '`',
      "sslMode=verify-full",
      "sslMode=disable",
      Map.of(
          EXPLAIN, always(MySqlFamily.PLAN),
          EXPLAIN_JSON, since(10, 1, 2, MySqlFamily.PLAN_JSON),
          EXPLAIN_ANALYZE, since(10, 1, 0, "ANALYZE "))),
  MYSQL(
      "MySQL",
      3306,
      "mysql",
      '`',
      "sslMode=VERIFY_IDENTITY",
      "sslMode=DISABLED",
      Map.of(
          EXPLAIN, always(MySqlFamily.PLAN),
          EXPLAIN_JSON, since(5, 6, 5, MySqlFamily.PLAN_JSON),
          EXPLAIN_ANALYZE, since(8, 0, 18, "EXPLAIN ANALYZE ")));

  // What MariaDB and MySQL write alike. A class of its own, as the constants above cannot refer
  // to static fields of this enum.
  private static final class MySqlFamily {
    static final String PLAN = "EXPLAIN ";
    static final String PLAN_JSON = "EXPLAIN FORMAT=JSON ";
  }

  // From which version a capability exists, and for a form of EXPLAIN what goes before the
  // statement; null for the others.
  private record Since(ServerVersion version, String prefix) {}

  private final String displayName;
  private final int defaultPort;
  private final String scheme;
  private final char quote;
  private final String tlsOn;
  private final String tlsOff;
  private final Map<Capability, Since> capabilities;

  Provider(
      String displayName,
      int defaultPort,
      String scheme,
      char quote,
      String tlsOn,
      String tlsOff,
      Map<Capability, Since> capabilities) {
    this.displayName = displayName;
    this.defaultPort = defaultPort;
    this.scheme = scheme;
    this.quote = quote;
    this.tlsOn = tlsOn;
    this.tlsOff = tlsOff;
    this.capabilities = capabilities;
  }

  private static Since always() {
    return always(null);
  }

  private static Since always(String prefix) {
    return new Since(ServerVersion.UNKNOWN, prefix);
  }

  private static Since since(int major, int minor, int patch, String prefix) {
    return new Since(new ServerVersion(major, minor, patch), prefix);
  }

  public String displayName() {
    return displayName;
  }

  public int defaultPort() {
    return defaultPort;
  }

  /** The JDBC URL of the profile, without its user or password. */
  public String jdbcUrl(ConnectionProfile profile) {
    var host = profile.host();
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    return "jdbc:" + scheme + "://" + host + ":" + profile.port() + "/" + encode(profile.database())
        + "?" + (profile.tls() ? tlsOn : tlsOff);
  }

  /** The identifier as a quoted name of this provider's SQL. */
  public String quote(String identifier) {
    if (identifier.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("An identifier cannot contain a NUL character");
    }
    var q = String.valueOf(quote);
    return q + identifier.replace(q, q + q) + q;
  }

  /** Whether a server of this provider and version has the capability. */
  public boolean supports(Capability capability, ServerVersion version) {
    var since = capabilities.get(capability);
    return since != null && version.atLeast(since.version());
  }

  /** The statement that shows the plan of the given one, if the server can. */
  public Optional<String> explain(String statement, ServerVersion version) {
    return prefixed(EXPLAIN, statement, version);
  }

  /** The statement that shows the plan of the given one as JSON, if the server can. */
  public Optional<String> explainJson(String statement, ServerVersion version) {
    return prefixed(EXPLAIN_JSON, statement, version);
  }

  /**
   * The statement that shows the plan of the given one with measured times, if the server can. It
   * really executes the statement, so an INSERT, UPDATE or DELETE changes data.
   */
  public Optional<String> explainAnalyze(String statement, ServerVersion version) {
    return prefixed(EXPLAIN_ANALYZE, statement, version);
  }

  private Optional<String> prefixed(Capability form, String statement, ServerVersion version) {
    return supports(form, version)
        ? Optional.of(capabilities.get(form).prefix() + statement)
        : Optional.empty();
  }

  // Percent-encodes every byte but the unreserved characters of RFC 3986, so that a name with
  // "/", "?" or "+" stays one path segment.
  private static String encode(String text) {
    var out = new StringBuilder();
    for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || "-._~".indexOf(c) >= 0) {
        out.append(c);
      } else {
        out.append('%').append(String.format("%02X", (int) c));
      }
    }
    return out.toString();
  }
}

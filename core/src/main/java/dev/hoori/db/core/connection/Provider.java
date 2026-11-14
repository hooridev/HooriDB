package dev.hoori.db.core.connection;

import static dev.hoori.db.core.connection.Capability.ERROR_POSITION;
import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;
import static dev.hoori.db.core.connection.Capability.SCHEMAS;
import static dev.hoori.db.core.connection.Capability.TRANSACTIONAL_DDL;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A kind of database server that HooriDB connects to. */
public enum Provider {
  POSTGRESQL(
      "PostgreSQL",
      5432,
      "postgresql",
      '"',
      // DefaultJavaSSLFactory checks the chain against the JVM's trust store, where the driver's
      // own factory reads a root.crt from the user's PostgreSQL folder; verify-full still has the
      // driver check the host name.
      Map.of(
          Tls.OFF, Map.of("sslmode", "disable"),
          Tls.ENCRYPTED, Map.of("sslmode", "require"),
          Tls.VERIFIED,
              Map.of(
                  "sslmode", "verify-full",
                  "sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")),
      null,
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
      // Without deferCertificateValidation=false, verify-full accepts a certificate the trust
      // store does not, against a fingerprint bound to the password and without the host name.
      Map.of(
          Tls.OFF, Map.of("sslMode", "disable"),
          Tls.ENCRYPTED, Map.of("sslMode", "trust"),
          Tls.VERIFIED, Map.of("sslMode", "verify-full", "deferCertificateValidation", "false")),
      "database",
      Map.of(
          EXPLAIN, always(MySqlFamily.PLAN),
          EXPLAIN_JSON, since(10, 1, 2, MySqlFamily.PLAN_JSON),
          EXPLAIN_ANALYZE, since(10, 1, 0, "ANALYZE "))),
  MYSQL(
      "MySQL",
      3306,
      "mysql",
      '`',
      Map.of(
          // Without TLS, a first login with caching_sha2_password fails unless the client may ask
          // for the server's public key. Whoever can alter the traffic can hand it a key of their
          // own and read the password; OFF already accepts an unprotected link, and does not work
          // at all against a current MySQL without it.
          Tls.OFF, Map.of("sslMode", "DISABLED", "allowPublicKeyRetrieval", "true"),
          Tls.ENCRYPTED, Map.of("sslMode", "REQUIRED"),
          Tls.VERIFIED, Map.of("sslMode", "VERIFY_IDENTITY")),
      "dbname",
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
  private final Map<Tls, Map<String, String>> tls;
  private final String databaseProperty;
  private final Map<Capability, Since> capabilities;

  Provider(
      String displayName,
      int defaultPort,
      String scheme,
      char quote,
      Map<Tls, Map<String, String>> tls,
      String databaseProperty,
      Map<Capability, Since> capabilities) {
    this.displayName = displayName;
    this.defaultPort = defaultPort;
    this.scheme = scheme;
    this.quote = quote;
    this.tls = tls;
    this.databaseProperty = databaseProperty;
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

  /**
   * What this provider's driver needs to connect to the profile, but its user and password, which
   * go into a copy of the properties.
   *
   * @throws ConnectionProfile.UnfitException if the profile has {@link ConnectionProfile#problems}
   */
  public ConnectionParameters parameters(ConnectionProfile profile)
      throws ConnectionProfile.UnfitException {
    var problems = profile.problems();
    if (!problems.isEmpty()) {
      throw new ConnectionProfile.UnfitException(problems);
    }
    var host = profile.host();
    if (host.indexOf(':') >= 0) {
      // As typed: with VERIFIED, pgjdbc and MySQL Connector/J compare an IPv6 address with the
      // certificate's as text, so a shortened form or brackets can fail a right certificate.
      host = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
      // MySQL Connector/J decodes the host, and fails on the "%" of a zone that is not "%25".
      host = "[" + (this == MYSQL ? host.replace("%", "%25") : host) + "]";
    }
    var url = "jdbc:" + scheme + "://" + host + ":" + profile.port() + "/";
    var properties = new HashMap<>(tls.get(profile.tls()));
    if (this == MYSQL) {
      // Else Connector/J applies JDBC escapes to a prepared statement, also inside a quoted name.
      properties.put("processEscapeCodesForPrepStmts", "false");
    }
    var database = profile.database();
    if (databaseProperty == null) {
      url += encode(database);
    } else if (!database.isEmpty()) {
      // MariaDB Connector/J does not decode the path of the URL and MySQL Connector/J trims it, so
      // the name goes as a property, which both pass on as it is.
      properties.put(databaseProperty, database);
    }
    return new ConnectionParameters(url, properties);
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

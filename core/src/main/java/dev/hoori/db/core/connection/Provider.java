package dev.hoori.db.core.connection;

import static dev.hoori.db.core.connection.Capability.EXPLAIN;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_ANALYZE;
import static dev.hoori.db.core.connection.Capability.EXPLAIN_JSON;

import dev.hoori.db.core.dialect.Dialect;
import dev.hoori.db.core.dialect.MariaDb;
import dev.hoori.db.core.dialect.MySql;
import dev.hoori.db.core.dialect.PostgreSql;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Optional;

/**
 * A kind of database server that HooriDB connects to. What each does its own way is its {@link
 * #dialect}.
 */
public enum Provider {
  POSTGRESQL,
  MARIADB,
  MYSQL;

  public String displayName() {
    return dialect().displayName();
  }

  public int defaultPort() {
    return dialect().defaultPort();
  }

  /** What this provider does its own way, which the code that uses it does not name. */
  public Dialect dialect() {
    return switch (this) {
      case POSTGRESQL -> PostgreSql.INSTANCE;
      case MARIADB -> MariaDb.INSTANCE;
      case MYSQL -> MySql.INSTANCE;
    };
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
    var connecting = dialect().connecting();
    var host = profile.host();
    if (host.indexOf(':') >= 0) {
      // As typed: with VERIFIED, pgjdbc and MySQL Connector/J compare an IPv6 address with the
      // certificate's as text, so a shortened form or brackets can fail a right certificate.
      host = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
      host = "[" + connecting.ipv6(host) + "]";
    }
    var url = "jdbc:" + connecting.scheme() + "://" + host + ":" + profile.port() + "/";
    var properties = new HashMap<>(connecting.tls(profile.tls()));
    properties.putAll(connecting.properties());
    var database = profile.database();
    var property = connecting.databaseProperty();
    if (property.isEmpty()) {
      url += encode(database);
    } else if (!database.isEmpty()) {
      properties.put(property.get(), database);
    }
    return new ConnectionParameters(url, properties);
  }

  /** The identifier as a quoted name of this provider's SQL. */
  public String quote(String identifier) {
    if (identifier.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("An identifier cannot contain a NUL character");
    }
    var q = String.valueOf(dialect().syntax().identifierQuote());
    return q + identifier.replace(q, q + q) + q;
  }

  /** Whether a server of this provider and version has the capability. */
  public boolean supports(Capability capability, ServerVersion version) {
    var since = dialect().capabilities().get(capability);
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
        ? Optional.of(dialect().capabilities().get(form).prefix() + statement)
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

package dev.hoori.db.core.connection;

import java.nio.charset.StandardCharsets;

/** A kind of database server that HooriDB connects to. */
public enum Provider {
  POSTGRESQL("PostgreSQL", 5432, "postgresql", '"', "sslmode=verify-full", "sslmode=disable"),
  MARIADB("MariaDB", 3306, "mariadb", '`', "sslMode=verify-full", "sslMode=disable"),
  MYSQL("MySQL", 3306, "mysql", '`', "sslMode=VERIFY_IDENTITY", "sslMode=DISABLED");

  private final String displayName;
  private final int defaultPort;
  private final String scheme;
  private final char quote;
  private final String tlsOn;
  private final String tlsOff;

  Provider(
      String displayName, int defaultPort, String scheme, char quote, String tlsOn, String tlsOff) {
    this.displayName = displayName;
    this.defaultPort = defaultPort;
    this.scheme = scheme;
    this.quote = quote;
    this.tlsOn = tlsOn;
    this.tlsOff = tlsOff;
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

package dev.hoori.db.core.connection;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * How to reach one database. The strings may be empty, as while a form edits a new profile. The
 * password is not part of it: it is kept in the system keyring under the id.
 */
public record ConnectionProfile(
    UUID id,
    String name,
    Provider provider,
    String host,
    int port,
    String database,
    String user,
    boolean tls) {

  public ConnectionProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(user, "user");
  }

  /** A new, empty profile of the provider, on its default port and with TLS on. */
  public static ConnectionProfile of(Provider provider) {
    return new ConnectionProfile(
        UUID.randomUUID(), "", provider, "", provider.defaultPort(), "", "", true);
  }

  /**
   * Whether TLS is on by default for the host: off for a local one, which seldom has TLS set up,
   * and on for everything else. Judged from the text alone; no name is resolved.
   */
  public static boolean tlsByDefault(String host) {
    // Locale.ROOT, and not equalsIgnoreCase, which takes "localhoſt" for "localhost".
    var name = host.toLowerCase(Locale.ROOT);
    if (name.equals("localhost") || name.endsWith(".localhost")) {
      return false;
    }
    InetAddress address;
    try {
      address = InetAddress.ofLiteral(host);
    } catch (IllegalArgumentException notALiteral) {
      return true;
    }
    var local = address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        // For IPv6 this would also take the deprecated site-local fec0::/10.
        || address instanceof Inet4Address && address.isSiteLocalAddress()
        // Unique local fc00::/7, which no is...Address method covers.
        || address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    return !local;
  }

  public ConnectionProfile withName(String name) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withProvider(Provider provider) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withHost(String host) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withPort(int port) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withDatabase(String database) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withUser(String user) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withTls(boolean tls) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }
}

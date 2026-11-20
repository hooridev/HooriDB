package dev.hoori.db.core.connection;

import dev.hoori.db.core.driver.Maven;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How to reach one database. The strings may be empty, as while a form edits a new profile, and
 * {@link #problems} says what keeps it from connecting. The password is not part of it: it is kept
 * in the system keyring under the id. The color that marks it is null for none, and the version
 * of its driver is null for the recommended one. A read-only connection to a server of documents
 * sends only commands that read, as {@link dev.hoori.db.core.document.Commands#reads} says; one to
 * a server of SQL has the server keep its sessions read-only, as {@link
 * dev.hoori.db.core.connect.Connector#open} does. A server of SQL does not take SRV: the host of
 * such a profile names a DNS SRV
 * record that lists the servers, as {@code mongodb+srv://} does, and its port is not used. In the
 * workspace it is an entry of its own.
 */
public record ConnectionProfile(
    UUID id,
    String name,
    Provider provider,
    String host,
    int port,
    String database,
    String user,
    Tls tls,
    Color color,
    String driverVersion,
    boolean readOnly,
    boolean srv)
    implements Workspace.Entry {

  private static final Pattern LABEL =
      Pattern.compile("[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");
  private static final Pattern ZONE = Pattern.compile("[A-Za-z0-9._-]+");

  public ConnectionProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(tls, "tls");
    if (driverVersion != null && !Maven.isVersion(driverVersion)) {
      throw new IllegalArgumentException("Not a driver version");
    }
  }

  /** A field of a profile that can keep it from connecting. */
  public enum Field {
    HOST,
    PORT,
    DATABASE,
    USER
  }

  /** What keeps a profile from connecting. */
  public enum Problem {
    HOST_MISSING(Field.HOST),
    /**
     * Not one DNS host name or IP address: for example a URL, a list of hosts, a host with its
     * port, or spaces.
     */
    HOST_INVALID(Field.HOST),
    /** Not from 1 to 65535. */
    PORT_OUT_OF_RANGE(Field.PORT),
    /** A NUL character, which would end the name early in what the driver sends to the server. */
    DATABASE_HAS_NUL(Field.DATABASE),
    /** Not a name of a database that the provider's servers take, as MongoDB's with a dot. */
    DATABASE_NAME_INVALID(Field.DATABASE),
    USER_HAS_NUL(Field.USER);

    private final Field field;

    Problem(Field field) {
      this.field = field;
    }

    public Field field() {
      return field;
    }
  }

  /** A profile that cannot connect as it is. Its message names the problems, not the values. */
  public static final class UnfitException extends Exception {
    private final transient Set<Problem> problems;

    UnfitException(Set<Problem> problems) {
      super("The connection profile cannot connect: " + problems);
      this.problems = Set.copyOf(problems);
    }

    public Set<Problem> problems() {
      return problems;
    }
  }

  /** A new, empty profile of the provider, on its default port and encrypted. */
  public static ConnectionProfile of(Provider provider) {
    return new ConnectionProfile(
        UUID.randomUUID(),
        "",
        provider,
        "",
        provider.defaultPort(),
        "",
        "",
        tlsByDefault(""),
        null,
        null,
        false,
        false);
  }

  /**
   * The TLS a profile has by default for the host: off for a local one, which seldom has TLS set
   * up, and encrypted for everything else. Never verified, as that fails on the self-signed
   * certificate a server often has; only the user can say that it has a trusted one. Judged from
   * the text alone; no name is resolved.
   */
  public static Tls tlsByDefault(String host) {
    // Locale.ROOT, and not equalsIgnoreCase, which takes "localhoſt" for "localhost".
    var name = host.toLowerCase(Locale.ROOT);
    // "localhost." is the same name, written up to the root.
    if (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.equals("localhost") || name.endsWith(".localhost")) {
      return Tls.OFF;
    }
    InetAddress address;
    try {
      address = InetAddress.ofLiteral(withoutZone(host));
    } catch (IllegalArgumentException notALiteral) {
      return Tls.ENCRYPTED;
    }
    var local = address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        // For IPv6 this would also take the deprecated site-local fec0::/10.
        || address instanceof Inet4Address && address.isSiteLocalAddress()
        // Unique local fc00::/7, which no is...Address method covers.
        || address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    return local ? Tls.OFF : Tls.ENCRYPTED;
  }

  // ofLiteral looks a named zone up among this machine's interfaces, and the zone does not change
  // whether the address is local.
  private static String withoutZone(String host) {
    int zone = host.indexOf('%');
    if (zone < 0 || host.indexOf(':') < 0) {
      return host;
    }
    var bracketed = host.startsWith("[") && host.endsWith("]");
    return host.substring(0, zone) + (bracketed ? "]" : "");
  }

  /** What keeps this profile from connecting, in the order of the fields; empty if nothing. */
  public Set<Problem> problems() {
    var problems = EnumSet.noneOf(Problem.class);
    if (host.isEmpty()) {
      problems.add(Problem.HOST_MISSING);
    } else if (!isHost(host)) {
      problems.add(Problem.HOST_INVALID);
    }
    if (!srv && (port < 1 || port > 65535)) {
      problems.add(Problem.PORT_OUT_OF_RANGE);
    }
    if (database.indexOf('\0') >= 0) {
      problems.add(Problem.DATABASE_HAS_NUL);
    } else if (provider.keepsDocuments() && !provider.documents().isDatabaseName(database)) {
      problems.add(Problem.DATABASE_NAME_INVALID);
    }
    if (user.indexOf('\0') >= 0) {
      problems.add(Problem.USER_HAS_NUL);
    }
    return problems;
  }

  // One DNS name, with or without the final dot and with "_" as Docker Compose names have it, an
  // IPv4 address in four decimal parts, or an IPv6
  // address with or without brackets and zone. Drivers read anything else in the host's place of
  // their URL as more of the URL: a path, parameters, more hosts.
  private static boolean isHost(String host) {
    if (host.indexOf(':') >= 0) {
      var literal =
          host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
      int percent = literal.indexOf('%');
      var address = percent < 0 ? literal : literal.substring(0, percent);
      if (percent >= 0 && !ZONE.matcher(literal.substring(percent + 1)).matches()
          || !IPV6.matcher(address).matches()) {
        return false;
      }
      try {
        InetAddress.ofLiteral(address);
        return true;
      } catch (IllegalArgumentException notALiteral) {
        return false;
      }
    }
    var name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    if (name.length() > 253) {
      return false;
    }
    var labels = name.split("\\.", -1);
    for (var label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    var last = labels[labels.length - 1];
    if (!last.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return true;
    }
    // A number as the last label makes it an address, which resolvers read in many ways: "127.1",
    // "0x7f.1", and "0177.0.0.1" that Java takes for 177.0.0.1 and the C library for 127.0.0.1.
    if (labels.length != 4 || !name.equals(host)) {
      return false;
    }
    for (var part : labels) {
      if (part.length() > 3 || part.length() > 1 && part.charAt(0) == '0') {
        return false;
      }
      if (!part.chars().allMatch(c -> c >= '0' && c <= '9') || Integer.parseInt(part) > 255) {
        return false;
      }
    }
    return true;
  }

  public ConnectionProfile withName(String name) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withProvider(Provider provider) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withHost(String host) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withPort(int port) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withDatabase(String database) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withUser(String user) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withTls(Tls tls) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withColor(Color color) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withDriverVersion(String driverVersion) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withReadOnly(boolean readOnly) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }

  public ConnectionProfile withSrv(boolean srv) {
    return new ConnectionProfile(
        id, name, provider, host, port, database, user, tls, color, driverVersion, readOnly, srv);
  }
}

package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConnectionString;
import dev.hoori.db.core.connection.Tls;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parses a MongoDB connection string ({@code mongodb://} or {@code mongodb+srv://}) without the
 * MongoDB driver. Every error message says what is wrong and never includes any part of the text.
 */
final class MongoConnectionString {

  private static final String STANDARD = "mongodb://";
  private static final String SRV = "mongodb+srv://";

  private MongoConnectionString() {}

  /**
   * Reads a MongoDB connection string; empty where the text does not start with {@code mongodb://}
   * or {@code mongodb+srv://}.
   *
   * @throws IllegalArgumentException where the scheme matches but the string cannot be read
   */
  static Optional<ConnectionString> parse(String text, int defaultPort) {
    Objects.requireNonNull(text, "text");
    boolean srv = text.startsWith(SRV);
    if (!srv && !text.startsWith(STANDARD)) {
      return Optional.empty();
    }
    String rest = text.substring(srv ? SRV.length() : STANDARD.length());
    // A space or line break is never part of one; the field would show it changed.
    if (rest.chars()
        .anyMatch(c ->
            Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c))) {
      throw new IllegalArgumentException("A connection string has no spaces or line breaks.");
    }
    int queryStart = rest.indexOf('?');
    String beforeQuery = queryStart < 0 ? rest : rest.substring(0, queryStart);
    String query = queryStart < 0 ? null : rest.substring(queryStart + 1);

    int at = beforeQuery.lastIndexOf('@');
    var credentials = at < 0 ? NONE : credentials(beforeQuery.substring(0, at));
    String authorityAndPath = beforeQuery.substring(at + 1);

    int slash = authorityAndPath.indexOf('/');
    if (query != null && slash < 0) {
      throw new IllegalArgumentException("A '/' is required before the query options.");
    }
    String hostsPart = slash < 0 ? authorityAndPath : authorityAndPath.substring(0, slash);
    String namespace = slash < 0 ? "" : decodePercent(authorityAndPath.substring(slash + 1));
    // A path of database.collection names the database before the dot, as the driver reads it.
    int dot = namespace.indexOf('.');
    String database = dot < 0 ? namespace : namespace.substring(0, dot);

    if (hostsPart.isEmpty()) {
      throw new IllegalArgumentException("Missing host in the connection string.");
    }
    var entries = List.of(hostsPart.split(",", -1));
    if (srv && entries.size() > 1) {
      throw new IllegalArgumentException(
          "An SRV connection string cannot list more than one host.");
    }
    var hosts = entries.stream().map(e -> parseHostPort(e, srv, defaultPort)).toList();

    var options = new Options();
    if (query != null) {
      options.read(query);
    }
    String authDatabase = options.authSource != null
        ? options.authSource
        : credentials.user().isEmpty() ? "" : database;

    return Optional.of(new ConnectionString(
        hosts.getFirst().host(),
        hosts.getFirst().port(),
        srv,
        credentials.user(),
        credentials.password(),
        database,
        authDatabase,
        options.tls(srv),
        options.unused,
        entries.subList(1, entries.size())));
  }

  private record Credentials(String user, String password) {}

  private static final Credentials NONE = new Credentials("", "");

  private static Credentials credentials(String userInfo) {
    if (userInfo.isEmpty()) {
      throw new IllegalArgumentException("Missing user name before '@'.");
    }
    for (char c : new char[] {'@', '/', '#'}) {
      if (userInfo.indexOf(c) >= 0) {
        throw new IllegalArgumentException("Credentials contain an unencoded '" + c + "'.");
      }
    }
    int colon = userInfo.indexOf(':');
    if (colon < 0) {
      return new Credentials(decodePercent(userInfo), "");
    }
    if (colon == 0) {
      throw new IllegalArgumentException("Password given without a user name.");
    }
    if (userInfo.indexOf(':', colon + 1) >= 0) {
      throw new IllegalArgumentException("Credentials contain an unencoded ':'.");
    }
    return new Credentials(
        decodePercent(userInfo.substring(0, colon)), decodePercent(userInfo.substring(colon + 1)));
  }

  /** The options of the query: those the form has fields for, and the names of the others. */
  private static final class Options {
    String authSource;
    Boolean tls;
    boolean insecure;
    boolean allowInvalid;
    final List<String> unused = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    void read(String query) {
      for (String pair : query.split("[&;]", -1)) {
        if (!pair.isEmpty()) {
          option(pair);
        }
      }
    }

    private void option(String pair) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        throw new IllegalArgumentException("Options must be written as name=value.");
      }
      if (eq == 0) {
        throw new IllegalArgumentException("Option name is missing.");
      }
      String name = decodePercent(pair.substring(0, eq));
      String value = decodePercent(pair.substring(eq + 1));
      String key = name.toLowerCase(Locale.ROOT);
      switch (key) {
        case "authsource" -> authSource = value;
        case "tls", "ssl" -> {
          boolean flag = parseBoolean(value);
          if (tls != null && tls != flag) {
            throw new IllegalArgumentException("Conflicting tls and ssl options.");
          }
          tls = flag;
        }
        case "tlsinsecure" -> insecure = parseBoolean(value);
        case "tlsallowinvalidcertificates" -> allowInvalid = parseBoolean(value);
        default -> {
          if (seen.add(key)) {
            unused.add(name);
          }
        }
      }
    }

    Tls tls(boolean srv) {
      // Asking to relax TLS asks for TLS, unless tls=false says otherwise.
      boolean relaxed = insecure || allowInvalid;
      if (!(tls != null ? tls : srv || relaxed)) {
        return Tls.OFF;
      }
      return relaxed ? Tls.ENCRYPTED : Tls.VERIFIED;
    }
  }

  private record HostPort(String host, int port) {}

  private static HostPort parseHostPort(String entry, boolean srv, int defaultPort) {
    if (entry.isEmpty()) {
      throw new IllegalArgumentException("Missing host in the connection string.");
    }
    if (entry.toLowerCase(Locale.ROOT).contains("%2f")) {
      throw new IllegalArgumentException("Unix domain socket paths are not supported.");
    }
    if (entry.startsWith("[")) {
      return bracketed(entry, srv, defaultPort);
    }
    int colon = entry.indexOf(':');
    if (colon >= 0 && entry.indexOf(':', colon + 1) >= 0) {
      throw new IllegalArgumentException("IPv6 hosts must be enclosed in brackets.");
    }
    String host = colon < 0 ? entry : entry.substring(0, colon);
    int port = colon < 0 ? defaultPort : port(entry.substring(colon + 1), srv);
    if (host.isEmpty()) {
      throw new IllegalArgumentException("Missing host in the connection string.");
    }
    if (!ConnectionProfile.isHost(host)) {
      throw new IllegalArgumentException("Invalid host in the connection string.");
    }
    return new HostPort(host, port);
  }

  private static HostPort bracketed(String entry, boolean srv, int defaultPort) {
    int close = entry.indexOf(']');
    if (close < 0) {
      throw new IllegalArgumentException("Unclosed '[' in IPv6 host.");
    }
    // RFC 6874 encodes the zone separator '%' as '%25' inside bracketed IPv6 literals.
    String host = entry.substring(1, close).replace("%25", "%");
    if (host.indexOf(':') < 0 || !ConnectionProfile.isHost(host)) {
      throw new IllegalArgumentException("Invalid IPv6 host.");
    }
    String after = entry.substring(close + 1);
    if (after.isEmpty()) {
      return new HostPort(host, defaultPort);
    }
    if (!after.startsWith(":")) {
      throw new IllegalArgumentException("Invalid host in the connection string.");
    }
    return new HostPort(host, port(after.substring(1), srv));
  }

  private static int port(String text, boolean srv) {
    if (srv) {
      throw new IllegalArgumentException("An SRV connection string cannot specify a port.");
    }
    return parsePort(text);
  }

  private static int parsePort(String text) {
    if (text.isEmpty() || text.length() > 5) {
      throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
    }
    int value = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
      }
      value = value * 10 + (c - '0');
    }
    if (value < 1 || value > 65535) {
      throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
    }
    return value;
  }

  private static boolean parseBoolean(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("TLS options must be true or false.");
    };
  }

  private static String decodePercent(String text) {
    var out = new StringBuilder(text.length());
    var bytes = new ByteArrayOutputStream();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '%') {
        bytes.write(percentByte(text, i));
        i += 3;
      } else {
        flush(out, bytes);
        out.append(c);
        i++;
      }
    }
    flush(out, bytes);
    return out.toString();
  }

  private static int percentByte(String text, int at) {
    int hi = at + 2 < text.length() ? hex(text.charAt(at + 1)) : -1;
    int lo = hi < 0 ? -1 : hex(text.charAt(at + 2));
    if (lo < 0) {
      throw new IllegalArgumentException("Invalid percent-encoding in the connection string.");
    }
    return hi << 4 | lo;
  }

  private static void flush(StringBuilder out, ByteArrayOutputStream bytes) {
    if (bytes.size() > 0) {
      out.append(decodeUtf8(bytes));
    }
  }

  private static String decodeUtf8(ByteArrayOutputStream bytes) {
    var decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      String decoded = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
      bytes.reset();
      return decoded;
    } catch (CharacterCodingException _) {
      throw new IllegalArgumentException("Invalid UTF-8 in percent-encoding.");
    }
  }

  private static int hex(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }
}

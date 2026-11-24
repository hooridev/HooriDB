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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
    boolean srv;
    String rest;
    if (text.startsWith(SRV)) {
      srv = true;
      rest = text.substring(SRV.length());
    } else if (text.startsWith(STANDARD)) {
      srv = false;
      rest = text.substring(STANDARD.length());
    } else {
      return Optional.empty();
    }

    // A space or line break is never part of one; the field would show it changed.
    if (rest.chars()
        .anyMatch(c ->
            Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c))) {
      throw new IllegalArgumentException("A connection string has no spaces or line breaks.");
    }
    int queryStart = rest.indexOf('?');
    String beforeQuery = queryStart < 0 ? rest : rest.substring(0, queryStart);
    String query = queryStart < 0 ? null : rest.substring(queryStart + 1);

    String user = "";
    String password = "";
    String authorityAndPath = beforeQuery;
    int at = beforeQuery.lastIndexOf('@');
    if (at >= 0) {
      String userInfo = beforeQuery.substring(0, at);
      authorityAndPath = beforeQuery.substring(at + 1);
      if (userInfo.isEmpty()) {
        throw new IllegalArgumentException("Missing user name before '@'.");
      }
      if (userInfo.indexOf('@') >= 0) {
        throw new IllegalArgumentException("Credentials contain an unencoded '@'.");
      }
      if (userInfo.indexOf('/') >= 0) {
        throw new IllegalArgumentException("Credentials contain an unencoded '/'.");
      }
      if (userInfo.indexOf('#') >= 0) {
        throw new IllegalArgumentException("Credentials contain an unencoded '#'.");
      }
      int colon = userInfo.indexOf(':');
      if (colon == 0) {
        throw new IllegalArgumentException("Password given without a user name.");
      }
      if (colon > 0) {
        if (userInfo.indexOf(':', colon + 1) >= 0) {
          throw new IllegalArgumentException("Credentials contain an unencoded ':'.");
        }
        user = decodePercent(userInfo.substring(0, colon));
        password = decodePercent(userInfo.substring(colon + 1));
      } else {
        user = decodePercent(userInfo);
      }
    }

    int slash = authorityAndPath.indexOf('/');
    if (query != null && slash < 0) {
      throw new IllegalArgumentException("A '/' is required before the query options.");
    }
    String hostsPart = slash < 0 ? authorityAndPath : authorityAndPath.substring(0, slash);
    String rawDatabase = slash < 0 ? "" : authorityAndPath.substring(slash + 1);
    String namespace = decodePercent(rawDatabase);
    // A path of database.collection names the database before the dot, as the driver reads it.
    int dot = namespace.indexOf('.');
    String database = dot < 0 ? namespace : namespace.substring(0, dot);

    if (hostsPart.isEmpty()) {
      throw new IllegalArgumentException("Missing host in the connection string.");
    }
    String[] entries = hostsPart.split(",", -1);
    if (srv && entries.length > 1) {
      throw new IllegalArgumentException(
          "An SRV connection string cannot list more than one host.");
    }
    HostPort first = null;
    var otherHosts = new ArrayList<String>();
    for (int i = 0; i < entries.length; i++) {
      HostPort parsed = parseHostPort(entries[i], srv, defaultPort);
      if (i == 0) {
        first = parsed;
      } else {
        otherHosts.add(entries[i]);
      }
    }

    String authSourceOption = null;
    Boolean tlsOption = null;
    boolean tlsInsecure = false;
    boolean tlsAllowInvalidCertificates = false;
    var unused = new ArrayList<String>();
    var seenUnused = new HashSet<String>();

    if (query != null && !query.isEmpty()) {
      for (String pair : query.split("[&;]", -1)) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        if (eq < 0) {
          throw new IllegalArgumentException("Options must be written as name=value.");
        }
        String rawName = pair.substring(0, eq);
        String rawValue = pair.substring(eq + 1);
        if (rawName.isEmpty()) {
          throw new IllegalArgumentException("Option name is missing.");
        }
        String name = decodePercent(rawName);
        String value = decodePercent(rawValue);
        String key = name.toLowerCase(Locale.ROOT);
        switch (key) {
          case "authsource" -> authSourceOption = value;
          case "tls", "ssl" -> {
            boolean flag = parseBoolean(value);
            if (tlsOption != null && tlsOption != flag) {
              throw new IllegalArgumentException("Conflicting tls and ssl options.");
            }
            tlsOption = flag;
          }
          case "tlsinsecure" -> tlsInsecure = parseBoolean(value);
          case "tlsallowinvalidcertificates" -> tlsAllowInvalidCertificates = parseBoolean(value);
          default -> {
            if (seenUnused.add(key)) {
              unused.add(name);
            }
          }
        }
      }
    }

    String authDatabase;
    if (authSourceOption != null) {
      authDatabase = authSourceOption;
    } else if (!user.isEmpty() && !database.isEmpty()) {
      authDatabase = database;
    } else {
      authDatabase = "";
    }

    boolean tlsEnabled = tlsOption != null ? tlsOption : srv;
    Tls tls;
    if (!tlsEnabled) {
      tls = Tls.OFF;
    } else if (tlsInsecure || tlsAllowInvalidCertificates) {
      tls = Tls.ENCRYPTED;
    } else {
      tls = Tls.VERIFIED;
    }

    return Optional.of(new ConnectionString(
        first.host(),
        first.port(),
        srv,
        user,
        password,
        database,
        authDatabase,
        tls,
        unused,
        otherHosts));
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
      int close = entry.indexOf(']');
      if (close < 0) {
        throw new IllegalArgumentException("Unclosed '[' in IPv6 host.");
      }
      String inner = entry.substring(1, close);
      // RFC 6874 encodes the zone separator '%' as '%25' inside bracketed IPv6 literals.
      String host = inner.replace("%25", "%");
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
      if (srv) {
        throw new IllegalArgumentException("An SRV connection string cannot specify a port.");
      }
      return new HostPort(host, parsePort(after.substring(1)));
    }
    int colon = entry.indexOf(':');
    String host;
    int port;
    if (colon >= 0) {
      if (entry.indexOf(':', colon + 1) >= 0) {
        throw new IllegalArgumentException("IPv6 hosts must be enclosed in brackets.");
      }
      if (srv) {
        throw new IllegalArgumentException("An SRV connection string cannot specify a port.");
      }
      host = entry.substring(0, colon);
      port = parsePort(entry.substring(colon + 1));
    } else {
      host = entry;
      port = defaultPort;
    }
    if (host.isEmpty()) {
      throw new IllegalArgumentException("Missing host in the connection string.");
    }
    if (!ConnectionProfile.isHost(host)) {
      throw new IllegalArgumentException("Invalid host in the connection string.");
    }
    return new HostPort(host, port);
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
    if (text.indexOf('%') < 0) {
      return text;
    }
    var out = new StringBuilder(text.length());
    var bytes = new ByteArrayOutputStream();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c != '%') {
        if (bytes.size() > 0) {
          out.append(decodeUtf8(bytes));
        }
        out.append(c);
        i++;
      } else {
        if (i + 2 >= text.length()) {
          throw new IllegalArgumentException("Invalid percent-encoding in the connection string.");
        }
        int hi = hex(text.charAt(i + 1));
        int lo = hex(text.charAt(i + 2));
        if (hi < 0 || lo < 0) {
          throw new IllegalArgumentException("Invalid percent-encoding in the connection string.");
        }
        bytes.write((hi << 4) | lo);
        i += 3;
      }
    }
    if (bytes.size() > 0) {
      out.append(decodeUtf8(bytes));
    }
    return out.toString();
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

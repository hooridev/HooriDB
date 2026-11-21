package dev.hoori.db.core.connection;

import java.util.Comparator;
import java.util.regex.Pattern;

/** The version of a database server, as numbers that compare in order. A missing part is 0. */
public record ServerVersion(int major, int minor, int patch) implements Comparable<ServerVersion> {

  /**
   * The version of a server whose version string could not be read. It is older than every real
   * version, so that only what every version of a provider can do is offered.
   */
  public static final ServerVersion UNKNOWN = new ServerVersion(0, 0, 0);

  private static final Pattern NUMBERS = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

  private static final Comparator<ServerVersion> ORDER = Comparator.comparingInt(
          ServerVersion::major)
      .thenComparingInt(ServerVersion::minor)
      .thenComparingInt(ServerVersion::patch);

  /**
   * The version at the start of a server's version string, such as {@code 16.4 (Debian
   * 16.4-1.pgdg120+2)} or {@code 11.4.2-MariaDB-ubu2404}, or {@link #UNKNOWN} if it has none.
   */
  public static ServerVersion parse(String text) {
    if (text == null) {
      return UNKNOWN;
    }
    var trimmed = text.strip();
    // MariaDB before 11 puts this in front of its version in the handshake, for old MySQL clients.
    if (trimmed.startsWith("5.5.5-")
        && trimmed.length() > 6
        && Character.isDigit(trimmed.charAt(6))) {
      trimmed = trimmed.substring(6);
    }
    var matcher = NUMBERS.matcher(trimmed);
    if (!matcher.lookingAt()) {
      return UNKNOWN;
    }
    try {
      return new ServerVersion(
          Integer.parseInt(matcher.group(1)), part(matcher.group(2)), part(matcher.group(3)));
    } catch (NumberFormatException _) {
      return UNKNOWN;
    }
  }

  private static int part(String digits) {
    return digits == null ? 0 : Integer.parseInt(digits);
  }

  public boolean atLeast(ServerVersion other) {
    return compareTo(other) >= 0;
  }

  @Override
  public int compareTo(ServerVersion other) {
    return ORDER.compare(this, other);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}

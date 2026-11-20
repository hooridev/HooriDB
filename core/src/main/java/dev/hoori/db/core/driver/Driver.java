package dev.hoori.db.core.driver;

import dev.hoori.db.core.connection.Provider;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The driver of a provider: its artifact in a Maven repository and the class that a {@link
 * LoadedDriver} starts from, with the artifacts of its group that it needs beside it at run time,
 * each of the same version. A JDBC driver usually needs none of them to open a plain connection.
 * The oldest version that HooriDB works with is null where any is; an older one is neither offered
 * nor downloaded nor loaded.
 */
public record Driver(
    String group, String artifact, String driverClass, List<Part> parts, String oldest) {

  private static final Pattern CLASS_NAME =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  /**
   * An artifact of the driver's group that is downloaded and loaded beside it, of the same version.
   * An optional one is left out where the repository does not have that version of it, as one
   * that older versions did not yet need.
   */
  public record Part(String artifact, boolean optional) {
    public Part {
      if (!Maven.isName(artifact)) {
        throw new IllegalArgumentException("Not a Maven artifact: " + artifact);
      }
    }
  }

  public Driver {
    if (!Maven.isName(group) || !Maven.isName(artifact)) {
      throw new IllegalArgumentException(
          "Not a Maven group and artifact: " + group + ":" + artifact);
    }
    if (!CLASS_NAME.matcher(driverClass).matches()) {
      throw new IllegalArgumentException("Not a class name: " + driverClass);
    }
    if (oldest != null && !Maven.isVersion(oldest)) {
      throw new IllegalArgumentException("Not a version: " + oldest);
    }
    parts = List.copyOf(parts);
    // Each is a file of its own, also where the file system ignores case.
    var names = new HashSet<String>();
    names.add(artifact.toLowerCase(Locale.ROOT));
    for (var part : parts) {
      if (!names.add(part.artifact().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("An artifact of " + artifact + " is named twice");
      }
    }
  }

  /** A driver of one artifact. */
  public Driver(String group, String artifact, String driverClass) {
    this(group, artifact, driverClass, List.of());
  }

  /** A driver of its artifact and parts, of which any version works. */
  public Driver(String group, String artifact, String driverClass, List<Part> parts) {
    this(group, artifact, driverClass, parts, null);
  }

  /** Whether HooriDB works with the version: it is not older than {@link #oldest}. */
  public boolean takes(String version) {
    return oldest == null || Maven.NEWEST_FIRST.compare(version, oldest) <= 0;
  }

  /** The driver of the provider. */
  public static Driver of(Provider provider) {
    return provider.traits().driver();
  }
}

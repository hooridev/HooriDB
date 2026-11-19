package dev.hoori.db.core.driver;

import dev.hoori.db.core.connection.Provider;
import java.util.regex.Pattern;

/**
 * The JDBC driver of a provider: its artifact in a Maven repository and the class that implements
 * {@link java.sql.Driver}. Each needs nothing else at run time to open a plain JDBC connection.
 */
public record Driver(String group, String artifact, String driverClass) {

  private static final Pattern CLASS_NAME =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

  public Driver {
    if (!Maven.isName(group) || !Maven.isName(artifact)) {
      throw new IllegalArgumentException(
          "Not a Maven group and artifact: " + group + ":" + artifact);
    }
    if (!CLASS_NAME.matcher(driverClass).matches()) {
      throw new IllegalArgumentException("Not a class name: " + driverClass);
    }
  }

  /** The driver of the provider. */
  public static Driver of(Provider provider) {
    return provider.dialect().driver();
  }
}

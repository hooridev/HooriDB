package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.driver.Driver;
import java.util.Map;

/**
 * What every provider has, whatever its servers speak: its name, port, driver and capabilities. A
 * {@link Dialect} adds what a server of tables that speaks SQL through JDBC does its own way, a
 * {@link DocumentDialect} what a server of collections of documents does. Code that needs the one
 * or the other asks by a {@code switch} over these two, and names no provider.
 */
public sealed interface Traits permits Dialect, DocumentDialect {

  /** Its name as people know it. */
  String displayName();

  /** Its name in the workspace's file, spelled out so that renaming a constant cannot change it. */
  String storedName();

  /** The port its servers listen on unless told otherwise. */
  int defaultPort();

  /** The capabilities its servers have, and from which version. */
  Map<Capability, Since> capabilities();

  /** Its driver. */
  Driver driver();

  /** Its driver's name as its makers call it. */
  String driverName();
}

package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.driver.Driver;
import java.util.Map;

/**
 * What every provider has, whatever its servers speak: its name, port, driver and capabilities. A
 * {@link Dialect} adds what a server of SQL does its own way.
 */
public sealed interface Traits permits Dialect {

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

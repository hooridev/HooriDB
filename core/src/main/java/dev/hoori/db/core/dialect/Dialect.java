package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import java.util.Map;

/** Everything a provider does its own way, by concern. */
public interface Dialect {

  /** Its name as people know it. */
  String displayName();

  /** Its name in the connections file, spelled out so that renaming a constant cannot change it. */
  String storedName();

  /** The port its servers listen on unless told otherwise. */
  int defaultPort();

  /** The capabilities its servers have, and from which version. */
  Map<Capability, Since> capabilities();

  /** Its JDBC driver. */
  Driver driver();

  /** Its JDBC driver's name as its makers call it. */
  String driverName();

  /** How its driver connects. */
  ConnectDialect connecting();

  /** The lexical rules of its SQL. */
  SyntaxDialect syntax();

  /** What its driver does on an open connection beyond JDBC. */
  SessionDialect session();

  /** How its tables' rows and its results are read. */
  RowsDialect rows();
}

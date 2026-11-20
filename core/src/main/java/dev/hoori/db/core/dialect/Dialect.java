package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.driver.Driver;
import java.util.Map;

/** Everything a provider does its own way, by concern. */
public interface Dialect {

  /** Its name as people know it. */
  String displayName();

  /** Its name in the workspace's file, spelled out so that renaming a constant cannot change it. */
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

  /** The queries of its catalog. */
  CatalogDialect catalog();

  /** How its servers say why a statement failed. */
  ErrorDialect errors();

  /** How its planner estimates a statement's rows. */
  PlanDialect plan();

  /** The lexical rules of its SQL. */
  SyntaxDialect syntax();

  /** What its driver does on an open connection beyond JDBC. */
  SessionDialect session();

  /** How its tables' rows and its results are read. */
  RowsDialect rows();
}

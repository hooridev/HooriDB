package dev.hoori.db.core.dialect;

import dev.hoori.db.core.data.RowsDialect;
import dev.hoori.db.core.definition.DefinitionDialect;
import dev.hoori.db.core.definition.ViewDialect;
import dev.hoori.db.core.dump.DumpDialect;

/**
 * Everything a provider of SQL does its own way, by concern: a server of tables reached through
 * its JDBC driver.
 */
public non-sealed interface Dialect extends Traits {

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

  /** How its servers tell what their sessions do. */
  MonitorDialect monitor();

  /** How its objects' definitions are read. */
  DefinitionDialect definitions();

  /** How what its views use is read, and how they are changed. */
  ViewDialect views();

  /** How its objects and their rows are exported to a file of SQL. */
  DumpDialect dump();
}

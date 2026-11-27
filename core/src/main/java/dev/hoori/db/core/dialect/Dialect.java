package dev.hoori.db.core.dialect;

import dev.hoori.db.core.data.RowsDialect;

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

  /** How its functions and procedures are listed, read, changed and called. */
  RoutineDialect routines();

  /** The lexical rules of its SQL. */
  SyntaxDialect syntax();

  /** What its command-line client does with a file of statements beyond the server's SQL. */
  ClientDialect client();

  /** What its driver does on an open connection beyond JDBC. */
  SessionDialect session();

  /** How its tables' rows and its results are read. */
  RowsDialect rows();

  /** How its servers tell what their sessions do. */
  MonitorDialect monitor();
}

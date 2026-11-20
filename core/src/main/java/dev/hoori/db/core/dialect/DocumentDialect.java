package dev.hoori.db.core.dialect;

import java.util.Set;

/**
 * What a provider of documents does its own way: a server that keeps collections of documents in
 * databases, reached through a driver of its own and not through JDBC, whose {@link
 * dev.hoori.db.core.driver.Driver#driverClass} is a {@link dev.hoori.db.core.document.DocumentDriver}.
 */
public non-sealed interface DocumentDialect extends Traits {

  /** The databases of the server itself, which the tree shows apart. */
  Set<String> systemDatabases();

  /** The database a user is defined in where the profile does not say. */
  String authDatabase();

  /** The database a connection is on where the profile names none. */
  String defaultDatabase();

  /** Whether its servers take the text as the name of a database; an empty one is none. */
  boolean isDatabaseName(String name);
}

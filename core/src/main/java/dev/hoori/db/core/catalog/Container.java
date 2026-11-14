package dev.hoori.db.core.catalog;

/**
 * What holds tables: a {@link Schema} where the provider has schemas, otherwise a {@link Database}.
 */
public sealed interface Container permits Database, Schema {

  String name();

  /** The database itself, or the database of the schema. */
  Database database();
}

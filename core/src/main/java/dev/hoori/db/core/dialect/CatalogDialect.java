package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Database;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The queries of a provider's own catalog. Each takes its names as parameters, in the order its
 * method says; those of tables and columns give a name and a kind, as {@code r} for a table, {@code
 * v} or {@code m} for a view and {@code f} for a foreign table, and those of columns the name, the
 * type, whether it may be NULL and its place in the primary key.
 */
public interface CatalogDialect {

  /** The server's databases, by name. */
  String databases();

  /** The database of a row of {@link #databases}. */
  Database database(ResultSet rows) throws SQLException;

  /**
   * Whether the database of this name is the server's own, where the provider has no schemas.
   *
   * @throws UnsupportedOperationException where the provider reads that from its catalog rows
   */
  boolean systemDatabase(String name);

  /**
   * The schemas of the connection's database, by name, where the provider has schemas.
   *
   * @throws UnsupportedOperationException where it has none
   */
  String schemas();

  /** Whether the schema of this name is the server's own. */
  boolean systemSchema(String name);

  /** The tables and views of the schema or database named, by name. */
  String tables();

  /** One row where the schema or database named exists. */
  String containerExists();

  /**
   * The schema, name and kind of each table and view of the database's schemas that are not the
   * server's own, by schema and then by name, where the provider has schemas.
   *
   * @throws UnsupportedOperationException where it has none
   */
  String allTables();

  /** The columns of the table in the schema or database named, in their order. */
  String columns();

  /** One row where the table in the schema or database named exists. */
  String tableExists();

  /**
   * The foreign keys referencing or referenced by the table in the schema or database named, taking
   * the container name and table name twice (once for each direction). Each row is one column pair:
   * constraint name, referencing container, referencing table, referenced container, referenced
   * table, referencing column, referenced column, update rule, delete rule and a boolean that is
   * {@code true} where the referencing column shares an index on its table with a column the user
   * cannot see (so that a key whose every row carries {@code true} may be missing columns and is
   * left out). The rules are written as {@code NO ACTION}, {@code RESTRICT}, {@code CASCADE},
   * {@code SET NULL} or {@code SET DEFAULT}, or NULL where the server does not tell them. Rows are
   * ordered by constraint name, referencing container, referencing table and key order so that each
   * key's pairs are consecutive and in key order.
   */
  String foreignKeys();

  /**
   * The indexes of the table in the schema or database named, taking the container name and table
   * name. Each row is one key position: index name, column name or expression, whether the index is
   * unique and whether it is the primary key. Non-key included columns are omitted. Rows are
   * ordered with the primary key first, then by index name, and by key order within each index so
   * that each index's columns are consecutive and in key order.
   */
  String indexes();

  /**
   * Whether the server treats {@code RESTRICT} as {@code NO ACTION} and reports it for an
   * unspecified referential action, as MariaDB and MySQL do.
   */
  boolean restrictIsNoAction();
}

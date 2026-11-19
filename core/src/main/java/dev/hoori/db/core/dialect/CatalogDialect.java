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
}

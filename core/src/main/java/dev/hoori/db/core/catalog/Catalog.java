package dev.hoori.db.core.catalog;

import dev.hoori.db.core.catalog.CatalogException.Failure;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads what a server holds, one level at a time: its databases, the schemas of a database where
 * the provider has {@link Capability#SCHEMAS}, the tables and views of a schema or, without
 * schemas, of a database, and the columns of a table or view. Names come in the order the server
 * compares them, user objects before system ones.
 *
 * <p>Each call blocks, without a time limit, and runs a query of the server's own catalogue on the
 * given connection. A connection serves one call at a time: the caller does not use it for anything
 * else until the call returns. On PostgreSQL a connection sees only its own database, so the
 * children of a database are read on a connection to it; MariaDB and MySQL read every database on
 * any connection.
 *
 * <p>An interrupt of the calling thread ends a call with {@link Failure#CANCELLED}, and leaves the
 * thread interrupted. The query is cancelled on the server and the connection stays usable; only
 * if the driver cannot stop it within {@link #GRACE}, as when the server no longer answers, or a
 * cancel may still be on its way, is the connection closed, with {@link Failure#ABORTED}, as
 * {@link Query} says. On PostgreSQL with auto-commit off, a cancelled query fails the transaction,
 * which the caller then rolls back.
 */
public final class Catalog {

  /** How long a cancelled query has to end before its connection is aborted. */
  public static final Duration GRACE = Query.GRACE;

  private static final Set<String> MYSQL_SYSTEM =
      Set.of("information_schema", "mysql", "performance_schema", "sys");

  // PostgreSQL's operators, functions and types are named with their schema, so that what a user
  // has created under the same names, with a better match or earlier in the search path, is
  // never what runs.
  private static final String EQ = " OPERATOR(pg_catalog.=) ";

  // Tables, partitioned and foreign tables, views and materialized views; not indexes, sequences,
  // composite types or TOAST tables.
  private static final String PG_KINDS = EQ + "ANY ('{r,p,f,v,m}'::pg_catalog.\"char\"[])";

  // Whether a domain in the chain of the column's type, a domain over a domain included, says NOT
  // NULL; only of a table, as a view's outer join can give a domain's column NULL all the same.
  private static final String DOMAIN_NOT_NULL = "WITH RECURSIVE d (base, required) AS ("
      + "SELECT t.typbasetype, t.typnotnull FROM pg_catalog.pg_type t"
      + " WHERE t.oid" + EQ + "a.atttypid"
      + " UNION ALL SELECT t.typbasetype, t.typnotnull FROM pg_catalog.pg_type t"
      + " JOIN d ON t.oid" + EQ + "d.base)"
      + " SELECT FROM d WHERE d.required";

  private Catalog() {}

  interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** The databases of the server. */
  public static List<Database> databases(Connection connection, Provider provider)
      throws CatalogException {
    var databases = switch (provider) {
      case POSTGRESQL ->
        query(
            connection,
            provider,
            "SELECT datname, datistemplate FROM pg_catalog.pg_database ORDER BY datname",
            rows -> new Database(rows.getString(1), rows.getBoolean(2)));
      case MARIADB, MYSQL ->
        query(
            connection,
            provider,
            "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME",
            rows -> {
              var name = rows.getString(1);
              return new Database(name, MYSQL_SYSTEM.contains(name));
            });
    };
    return systemLast(databases, Database::system);
  }

  /**
   * The schemas of the database, on a connection to it.
   *
   * @throws IllegalArgumentException if the provider has no schemas
   */
  public static List<Schema> schemas(Connection connection, Provider provider, Database database)
      throws CatalogException {
    if (!hasSchemas(provider)) {
      throw new IllegalArgumentException(provider.displayName() + " has no schemas");
    }
    requireConnectedTo(connection, provider, database);
    var schemas = query(
        connection,
        provider,
        "SELECT nspname FROM pg_catalog.pg_namespace ORDER BY nspname",
        rows -> {
          var name = rows.getString(1);
          // Names that start with pg_ are kept for the server: pg_toast, pg_temp_3 and the like.
          return new Schema(
              database, name, name.startsWith("pg_") || name.equals("information_schema"));
        });
    return systemLast(schemas, Schema::system);
  }

  /**
   * The tables and views of the schema, or of the database where the provider has no schemas.
   *
   * @throws IllegalArgumentException if the container is a database where the provider has
   *     schemas, or a schema where it has none
   */
  public static List<Table> tables(Connection connection, Provider provider, Container container)
      throws CatalogException {
    if (hasSchemas(provider) != container instanceof Schema) {
      throw new IllegalArgumentException(provider.displayName() + " holds no tables in a "
          + container.getClass().getSimpleName());
    }
    requireConnectedTo(connection, provider, container.database());
    Row<Table> table = rows -> new Table(
        container,
        rows.getString(1),
        switch (rows.getString(2)) {
          case "v", "m" -> Table.Kind.VIEW;
          case "f" -> Table.Kind.FOREIGN;
          default -> Table.Kind.TABLE;
        });
    var tables = switch (provider) {
      case POSTGRESQL ->
        query(
            connection,
            provider,
            "SELECT c.relname, c.relkind FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
                + " WHERE n.nspname" + EQ + "? AND c.relkind" + PG_KINDS + " ORDER BY c.relname",
            table,
            container.name());
      // MariaDB's sequences are tables to the server; they come later, with PostgreSQL's.
      case MARIADB, MYSQL ->
        query(
            connection,
            provider,
            "SELECT TABLE_NAME, CASE WHEN TABLE_TYPE IN ('VIEW', 'SYSTEM VIEW') THEN 'v' ELSE 'r' END"
                + " FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE <> 'SEQUENCE' ORDER BY TABLE_NAME",
            table,
            container.name());
    };
    if (tables.isEmpty()) {
      var exists = switch (provider) {
        case POSTGRESQL -> "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname" + EQ + "?";
        case MARIADB, MYSQL -> "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?";
      };
      requireExists(connection, provider, exists, container.name());
    }
    return tables;
  }

  /** The columns of the table or view, in their order, with their places in the primary key. */
  public static List<Column> columns(Connection connection, Provider provider, Table table)
      throws CatalogException {
    requireConnectedTo(connection, provider, table.container().database());
    Row<Column> column = rows ->
        new Column(table, rows.getString(1), rows.getString(2), rows.getBoolean(3), rows.getInt(4));
    var schema = table.container().name();
    var columns = switch (provider) {
      case POSTGRESQL ->
        query(
            connection,
            provider,
            "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),"
                + " NOT a.attnotnull AND NOT (c.relkind" + EQ
                + "ANY ('{r,p}'::pg_catalog.\"char\"[])"
                + " AND EXISTS (" + DOMAIN_NOT_NULL + ")),"
                // Of the index's attributes only its key, not those it merely includes.
                + " (SELECT k.n FROM pg_catalog.pg_index i,"
                + " pg_catalog.unnest(i.indkey) WITH ORDINALITY k (attnum, n)"
                + " WHERE i.indrelid" + EQ + "c.oid AND i.indisprimary AND k.attnum" + EQ
                + "a.attnum AND k.n OPERATOR(pg_catalog.<=) i.indnkeyatts)"
                + " FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
                + " JOIN pg_catalog.pg_attribute a ON a.attrelid" + EQ + "c.oid"
                + " WHERE n.nspname" + EQ + "? AND c.relname" + EQ + "? AND c.relkind" + PG_KINDS
                + " AND a.attnum OPERATOR(pg_catalog.>) 0 AND NOT a.attisdropped"
                + " ORDER BY a.attnum",
            column,
            schema,
            table.name());
      // shortcut: a temporary table of the session hides a persistent one of the same name from
      // information_schema, so the columns are the temporary one's; tell them apart if the browsing
      // connection ever creates temporary tables.
      case MARIADB, MYSQL ->
        query(
            connection,
            provider,
            "SELECT c.COLUMN_NAME, c.COLUMN_TYPE, c.IS_NULLABLE = 'YES', k.ORDINAL_POSITION"
                + " FROM information_schema.COLUMNS c"
                + " LEFT JOIN information_schema.KEY_COLUMN_USAGE k"
                + " ON k.TABLE_SCHEMA = c.TABLE_SCHEMA AND k.TABLE_NAME = c.TABLE_NAME"
                + " AND k.CONSTRAINT_NAME = 'PRIMARY' AND k.COLUMN_NAME = c.COLUMN_NAME"
                + " WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ? ORDER BY c.ORDINAL_POSITION",
            column,
            schema,
            table.name());
    };
    // A PostgreSQL table may have no columns at all.
    if (columns.isEmpty()) {
      var exists = switch (provider) {
        case POSTGRESQL ->
          "SELECT 1 FROM pg_catalog.pg_class c"
              + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "c.relnamespace"
              + " WHERE n.nspname" + EQ + "? AND c.relname" + EQ + "? AND c.relkind" + PG_KINDS;
        case MARIADB, MYSQL ->
          "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
      };
      requireExists(connection, provider, exists, schema, table.name());
    }
    return columns;
  }

  private static boolean hasSchemas(Provider provider) {
    return provider.supports(Capability.SCHEMAS, ServerVersion.UNKNOWN);
  }

  private static <T> List<T> systemLast(List<T> items, Predicate<T> system) {
    var sorted = new ArrayList<>(items);
    // A stable sort, so that the server's order holds within each part.
    sorted.sort(Comparator.comparing(system::test));
    return List.copyOf(sorted);
  }

  // A PostgreSQL connection would read its own database's content in place of the one asked about.
  private static void requireConnectedTo(
      Connection connection, Provider provider, Database database) throws CatalogException {
    if (provider != Provider.POSTGRESQL) {
      return;
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw failure(connection, e);
    }
    if (!database.name().equals(current)) {
      throw new CatalogException(
          Failure.WRONG_DATABASE,
          "asked about " + database.name() + ", connected to " + current,
          null);
    }
  }

  private static void requireExists(
      Connection connection, Provider provider, String sql, String... names)
      throws CatalogException {
    if (query(connection, provider, sql, rows -> true, names).isEmpty()) {
      throw new CatalogException(Failure.NOT_FOUND, String.join(".", names), null);
    }
  }

  static <T> List<T> query(
      Connection connection, Provider provider, String sql, Row<T> row, String... parameters)
      throws CatalogException {
    try {
      return Query.run(connection, provider, sql, statement -> {
        for (var i = 0; i < parameters.length; i++) {
          statement.setString(i + 1, parameters[i]);
        }
        try (var rows = statement.executeQuery()) {
          var read = new ArrayList<T>();
          while (rows.next()) {
            read.add(row.read(rows));
          }
          return read;
        }
      });
    } catch (QueryException e) {
      throw failure(e);
    }
  }

  private static CatalogException failure(Connection connection, SQLException thrown) {
    return failure(Query.failure(connection, thrown));
  }

  private static CatalogException failure(QueryException e) {
    var failure = switch (e.failure()) {
      case CONNECTION_LOST -> Failure.CONNECTION_LOST;
      case NOT_FOUND -> Failure.NOT_FOUND;
      case ACCESS_DENIED -> Failure.ACCESS_DENIED;
      case CANCELLED -> Failure.CANCELLED;
      case ABORTED -> Failure.ABORTED;
      case OTHER -> Failure.OTHER;
    };
    return new CatalogException(failure, e.detail(), e.getCause());
  }
}

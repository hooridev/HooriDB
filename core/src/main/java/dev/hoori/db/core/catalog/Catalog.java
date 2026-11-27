package dev.hoori.db.core.catalog;

import dev.hoori.db.core.catalog.CatalogException.Failure;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.dialect.CatalogDialect;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reads what a server holds, one level at a time: its databases, the schemas of a database where
 * the provider has {@link Capability#SCHEMAS}, the tables and views of a schema or, without
 * schemas, of a database, and the columns of a table or view. Names come in the order the server
 * compares them, user objects before system ones.
 *
 * <p>Each call blocks, without a time limit, and runs a query of the server's own catalog on the
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

  private Catalog() {}

  interface Row<T> {
    T read(ResultSet rows) throws SQLException;
  }

  /** The databases of the server. */
  public static List<Database> databases(Connection connection, Provider provider)
      throws CatalogException {
    var catalog = provider.dialect().catalog();
    var databases = query(connection, provider, catalog.databases(), catalog::database);
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
    var catalog = provider.dialect().catalog();
    var schemas = query(connection, provider, catalog.schemas(), rows -> {
      var name = rows.getString(1);
      return new Schema(database, name, catalog.systemSchema(name));
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
          case "v" -> Table.Kind.VIEW;
          case "m" -> Table.Kind.MATERIALIZED;
          case "f" -> Table.Kind.FOREIGN;
          default -> Table.Kind.TABLE;
        });
    var catalog = provider.dialect().catalog();
    var tables = query(connection, provider, catalog.tables(), table, container.name());
    if (tables.isEmpty()) {
      requireExists(connection, provider, catalog.containerExists(), container.name());
    }
    return tables;
  }

  /**
   * The tables and views of every schema of the database that is not the system's, in one query,
   * by schema and then by name, each with its schema as its container; where the provider has no
   * schemas, those of the database, as {@link #tables} reads them. One query instead of one per
   * schema, for finding a table by name in a database of many schemas. A schema without tables
   * gives none; the schemas themselves come from {@link #schemas}.
   */
  public static List<Table> allTables(Connection connection, Provider provider, Database database)
      throws CatalogException {
    if (!hasSchemas(provider)) {
      return tables(connection, provider, database);
    }
    requireConnectedTo(connection, provider, database);
    return query(
        connection,
        provider,
        provider.dialect().catalog().allTables(),
        rows -> new Table(
            new Schema(database, rows.getString(1), false),
            rows.getString(2),
            switch (rows.getString(3)) {
              case "v" -> Table.Kind.VIEW;
              case "m" -> Table.Kind.MATERIALIZED;
              case "f" -> Table.Kind.FOREIGN;
              default -> Table.Kind.TABLE;
            }));
  }

  /** The columns of the table or view, in their order, with their places in the primary key. */
  public static List<Column> columns(Connection connection, Provider provider, Table table)
      throws CatalogException {
    requireConnectedTo(connection, provider, table.container().database());
    Row<Column> column = rows ->
        new Column(table, rows.getString(1), rows.getString(2), rows.getBoolean(3), rows.getInt(4));
    var schema = table.container().name();
    var catalog = provider.dialect().catalog();
    var columns = query(connection, provider, catalog.columns(), column, schema, table.name());
    // A PostgreSQL table may have no columns at all.
    if (columns.isEmpty()) {
      requireExists(connection, provider, catalog.tableExists(), schema, table.name());
    }
    return columns;
  }

  /**
   * The foreign keys whose referencing or referenced table is {@code table}, each once (a
   * self-reference appears once), ordered by constraint name and then referencing table. Views and
   * foreign tables have none and return an empty list without querying the server.
   *
   * <p>Where column privileges on MySQL hide part of a composite key, that key is left out rather
   * than returned partial. Because MySQL does not expose a key's hidden columns, a whole key is
   * also left out under column privileges when every one of its columns shares an index on the
   * referencing table with a column the user cannot see.
   */
  public static List<ForeignKey> foreignKeys(Connection connection, Provider provider, Table table)
      throws CatalogException {
    if (table.kind() != Table.Kind.TABLE) {
      return List.of();
    }
    requireConnectedTo(connection, provider, table.container().database());
    var catalog = provider.dialect().catalog();
    var schemas = hasSchemas(provider);
    var database = table.container().database();
    record ReadKey(ForeignKey key, boolean maybePartial) {}
    Row<ReadKey> pair = rows -> new ReadKey(
        new ForeignKey(
            rows.getString(1),
            tableOf(schemas, database, catalog, rows.getString(2), rows.getString(3)),
            List.of(rows.getString(6)),
            tableOf(schemas, database, catalog, rows.getString(4), rows.getString(5)),
            List.of(rows.getString(7)),
            rule(rows.getString(8)),
            rule(rows.getString(9))),
        rows.getBoolean(10));
    var container = table.container().name();
    var pairs = query(
        connection,
        provider,
        catalog.foreignKeys(),
        pair,
        container,
        table.name(),
        container,
        table.name());
    // A key of several columns comes as consecutive rows, one per pair of columns.
    var merged = new ArrayList<ReadKey>();
    for (var next : pairs) {
      var last = merged.isEmpty() ? null : merged.getLast();
      if (last != null
          && last.key().name().equals(next.key().name())
          && last.key().table().equals(next.key().table())) {
        merged.set(
            merged.size() - 1,
            new ReadKey(
                new ForeignKey(
                    last.key().name(),
                    last.key().table(),
                    concat(last.key().columns(), next.key().columns()),
                    last.key().referenced(),
                    concat(last.key().referencedColumns(), next.key().referencedColumns()),
                    last.key().onUpdate(),
                    last.key().onDelete()),
                last.maybePartial() && next.maybePartial()));
      } else {
        merged.add(next);
      }
    }
    return merged.stream()
        .filter(read -> !read.maybePartial())
        .map(ReadKey::key)
        .toList();
  }

  /**
   * The indexes of {@code table}, primary key first and then by name, each with its key columns or
   * expressions in key order. Views and foreign tables have none and return an empty list without
   * querying the server.
   */
  public static List<Index> indexes(Connection connection, Provider provider, Table table)
      throws CatalogException {
    if (table.kind() != Table.Kind.TABLE) {
      return List.of();
    }
    requireConnectedTo(connection, provider, table.container().database());
    var catalog = provider.dialect().catalog();
    Row<Index> part = rows -> {
      var column = rows.getString(2);
      return new Index(
          rows.getString(1),
          table,
          column == null ? List.of() : List.of(column),
          rows.getBoolean(3),
          rows.getBoolean(4));
    };
    var parts = query(
        connection, provider, catalog.indexes(), part, table.container().name(), table.name());
    // An index of several columns comes as consecutive rows, one per key position.
    var merged = new ArrayList<Index>();
    for (var next : parts) {
      var last = merged.isEmpty() ? null : merged.getLast();
      if (last != null && last.name().equals(next.name())) {
        merged.set(
            merged.size() - 1,
            new Index(
                last.name(),
                last.table(),
                concat(last.columns(), next.columns()),
                last.unique(),
                last.primary()));
      } else {
        merged.add(next);
      }
    }
    return List.copyOf(merged);
  }

  private static ForeignKey.Rule rule(String word) {
    return word == null ? null : ForeignKey.Rule.valueOf(word.replace(' ', '_'));
  }

  private static List<String> concat(List<String> first, List<String> second) {
    var both = new ArrayList<>(first);
    both.addAll(second);
    return both;
  }

  private static Table tableOf(
      boolean schemas, Database database, CatalogDialect catalog, String container, String name) {
    Container holder = schemas
        ? new Schema(database, container, catalog.systemSchema(container))
        : new Database(container, catalog.systemDatabase(container));
    return new Table(holder, name, Table.Kind.TABLE);
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

  // A connection to one database would read its own database's content in place of the one
  // asked about.
  private static void requireConnectedTo(
      Connection connection, Provider provider, Database database) throws CatalogException {
    if (!provider.supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)) {
      return;
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw failure(connection, provider, e);
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
      return Journal.sql(
          provider,
          Purpose.CATALOG,
          sql,
          read -> Outcome.rows(read.size()),
          () -> Query.run(connection, provider, sql, statement -> {
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
          }));
    } catch (QueryException e) {
      throw failure(e);
    }
  }

  private static CatalogException failure(
      Connection connection, Provider provider, SQLException thrown) {
    return failure(Query.failure(connection, provider, thrown));
  }

  private static CatalogException failure(QueryException e) {
    var failure = switch (e.failure()) {
      case CONNECTION_LOST -> Failure.CONNECTION_LOST;
      case NOT_FOUND -> Failure.NOT_FOUND;
      case ACCESS_DENIED -> Failure.ACCESS_DENIED;
      case CANCELLED -> Failure.CANCELLED;
      case ABORTED -> Failure.ABORTED;
      case COLUMNS_CHANGED, CLAUSE_REFUSED, OTHER -> Failure.OTHER;
    };
    return new CatalogException(failure, e.detail(), e.getCause());
  }
}

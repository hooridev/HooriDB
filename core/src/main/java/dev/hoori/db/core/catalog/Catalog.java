package dev.hoori.db.core.catalog;

import dev.hoori.db.core.catalog.CatalogException.Failure;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * if the driver cannot stop it within {@link #GRACE}, as when the server no longer answers, is the
 * connection aborted, and closed from then on. On PostgreSQL with auto-commit off, a cancelled
 * query fails the transaction, which the caller then rolls back.
 */
public final class Catalog {

  /** How long a cancelled query has to end before its connection is aborted. */
  public static final Duration GRACE = Duration.ofSeconds(2);

  private static final Set<String> MYSQL_SYSTEM =
      Set.of("information_schema", "mysql", "performance_schema", "sys");

  // Tables, partitioned and foreign tables, views and materialized views; not indexes, sequences,
  // composite types or TOAST tables.
  private static final String PG_KINDS = "('r', 'p', 'f', 'v', 'm')";

  // MySQL error codes: no access to a database, a table, a column, or without a privilege.
  private static final Set<Integer> MYSQL_ACCESS_DENIED = Set.of(1044, 1142, 1143, 1227);

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
            "SELECT datname, datistemplate FROM pg_catalog.pg_database ORDER BY datname",
            rows -> new Database(rows.getString(1), rows.getBoolean(2)));
      case MARIADB, MYSQL ->
        query(
            connection,
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
    var schemas =
        query(connection, "SELECT nspname FROM pg_catalog.pg_namespace ORDER BY nspname", rows -> {
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
        container, rows.getString(1), rows.getBoolean(2) ? Table.Kind.VIEW : Table.Kind.TABLE);
    var tables = switch (provider) {
      case POSTGRESQL ->
        query(
            connection,
            "SELECT c.relname, c.relkind IN ('v', 'm') FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = ? AND c.relkind IN " + PG_KINDS + " ORDER BY c.relname",
            table,
            container.name());
      // MariaDB's sequences are tables to the server; they come later, with PostgreSQL's.
      case MARIADB, MYSQL ->
        query(
            connection,
            "SELECT TABLE_NAME, TABLE_TYPE IN ('VIEW', 'SYSTEM VIEW')"
                + " FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE <> 'SEQUENCE' ORDER BY TABLE_NAME",
            table,
            container.name());
    };
    if (tables.isEmpty()) {
      var exists = switch (provider) {
        case POSTGRESQL -> "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?";
        case MARIADB, MYSQL -> "SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?";
      };
      requireExists(connection, exists, container.name());
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
            "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,"
                + " (SELECT k.n FROM pg_catalog.pg_index i,"
                + " unnest(i.indkey) WITH ORDINALITY k (attnum, n)"
                + " WHERE i.indrelid = c.oid AND i.indisprimary AND k.attnum = a.attnum)"
                + " FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid"
                + " WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN " + PG_KINDS
                + " AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum",
            column,
            schema,
            table.name());
      case MARIADB, MYSQL ->
        query(
            connection,
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
              + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
              + " WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN " + PG_KINDS;
        case MARIADB, MYSQL ->
          "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
      };
      requireExists(connection, exists, schema, table.name());
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

  private static void requireExists(Connection connection, String sql, String... names)
      throws CatalogException {
    if (query(connection, sql, rows -> true, names).isEmpty()) {
      throw new CatalogException(Failure.NOT_FOUND, String.join(".", names), null);
    }
  }

  /**
   * Runs the query on a thread of its own, so that an interrupt of the caller can cancel it on the
   * server: an interrupt of a virtual thread that is in a driver's call closes the driver's socket,
   * and a platform thread's is not noticed until the server answers.
   */
  static <T> List<T> query(Connection connection, String sql, Row<T> row, String... parameters)
      throws CatalogException {
    if (Thread.currentThread().isInterrupted()) {
      throw new CatalogException(Failure.CANCELLED, "", null);
    }
    PreparedStatement statement = null;
    try {
      statement = connection.prepareStatement(sql);
      for (var i = 0; i < parameters.length; i++) {
        statement.setString(i + 1, parameters[i]);
      }
    } catch (SQLException | RuntimeException e) {
      close(statement);
      throw failure(connection, e);
    }
    var prepared = statement;
    var result = new CompletableFuture<List<T>>();
    Thread.ofVirtual().name("catalog").start(() -> {
      try (prepared;
          var rows = prepared.executeQuery()) {
        var read = new ArrayList<T>();
        while (rows.next()) {
          read.add(row.read(rows));
        }
        result.complete(read);
      } catch (Throwable e) {
        result.completeExceptionally(e);
      }
    });
    try {
      return result.get();
    } catch (InterruptedException e) {
      cancel(connection, prepared, result);
      Thread.currentThread().interrupt();
      throw new CatalogException(Failure.CANCELLED, "", null);
    } catch (ExecutionException e) {
      throw failure(connection, e.getCause());
    }
  }

  // Waits for the cancel as well as for the query: a cancel that reaches the server late would
  // stop the caller's next statement instead.
  private static void cancel(Connection connection, Statement statement, Future<?> result) {
    var canceller = Thread.ofVirtual().name("catalog cancel").start(() -> {
      try {
        statement.cancel();
      } catch (SQLException | RuntimeException e) {
        // The query is over, or the connection is gone; either way nothing runs any more.
      }
    });
    var deadline = System.nanoTime() + GRACE.toNanos();
    boolean ended;
    try {
      try {
        result.get(GRACE.toNanos(), TimeUnit.NANOSECONDS);
      } catch (ExecutionException e) {
        // What a cancelled query throws.
      }
      ended = canceller.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
    } catch (TimeoutException | InterruptedException e) {
      ended = false;
    }
    if (!ended) {
      try {
        connection.abort(task -> Thread.ofVirtual().start(task));
      } catch (SQLException | RuntimeException e) {
        // Not even an abort is possible; the connection stays as the driver left it.
      }
    }
  }

  private static CatalogException failure(Connection connection, Throwable thrown) {
    var message = thrown.getMessage() == null ? thrown.getClass().getName() : thrown.getMessage();
    if (!(thrown instanceof SQLException e)) {
      return new CatalogException(Failure.OTHER, message, thrown);
    }
    var state = e.getSQLState() == null ? "" : e.getSQLState();
    Failure failure;
    if (state.startsWith("08")
        || e instanceof SQLNonTransientConnectionException
        || e instanceof SQLRecoverableException
        || closed(connection)) {
      failure = Failure.CONNECTION_LOST;
    } else if (state.equals("57014") || state.equals("70100")) {
      // PostgreSQL's query_canceled, and MariaDB's and MySQL's "Query execution was interrupted".
      failure = Failure.CANCELLED;
    } else if (state.equals("42501") || MYSQL_ACCESS_DENIED.contains(e.getErrorCode())) {
      failure = Failure.ACCESS_DENIED;
    } else {
      failure = Failure.OTHER;
    }
    return new CatalogException(failure, message, thrown);
  }

  private static boolean closed(Connection connection) {
    try {
      return connection.isClosed();
    } catch (SQLException | RuntimeException e) {
      return true;
    }
  }

  private static void close(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException | RuntimeException e) {
      // A statement that never ran holds nothing worth reporting.
    }
  }
}

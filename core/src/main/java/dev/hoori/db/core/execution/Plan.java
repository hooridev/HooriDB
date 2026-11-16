package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.OptionalLong;

/**
 * How many rows a statement gives, as the server's planner estimates it from the statement's plan,
 * which {@code EXPLAIN} shows without running it. It can be far off, as {@link Rows#estimate} says
 * of a table's.
 */
public final class Plan {

  /**
   * How long an estimate may take, waiting for a lock among it, before it is given up: a lock that
   * the user's own transaction holds on another connection would make it wait for good.
   */
  public static final Duration LIMIT = Duration.ofSeconds(2);

  private Plan() {}

  /**
   * The rows the statement gives as the planner estimates them; empty where its plan says none, as
   * MySQL's for an aggregate. On PostgreSQL and MySQL it is the top of the plan's own; on MariaDB,
   * whose plan gives only the rows read of each table, the product over the tables of each select
   * of the outermost UNION, summed, at most its LIMIT: the size of their join before a condition or
   * a grouping cuts it down.
   *
   * <p>It is for a connection of the app's own in auto-commit, on which nothing else runs: it
   * runs in a read-only transaction of its own that it rolls back, also where the {@code EXPLAIN}
   * fails or is interrupted. Planning may run the user's functions, as PostgreSQL folds an
   * immutable or stable one and MariaDB and MySQL fill a derived table or a constant subquery; one
   * that writes then fails, also to a MyISAM table, while what one sets in the session, such as a
   * variable, stays with that connection. Where the server has functions that may reach beyond the
   * transaction, as through another connection, it gives no estimate. It is given up after {@link
   * #LIMIT}.
   *
   * <p>On MariaDB and MySQL it puts the connection on {@code database}, where the statement's
   * names are read, and leaves it there.
   *
   * @param database where the statement's names are read; null for the connection's own, as on
   *     PostgreSQL, whose connection is on one database for good
   * @throws QueryException as {@link Query} reports it, such as for a statement that has no plan,
   *     one whose plan ran a function that wrote, or one that took too long
   */
  public static OptionalLong rows(
      Connection connection, Provider provider, String database, String sql) throws QueryException {
    boolean pg = provider == Provider.POSTGRESQL;
    if (!pg && database != null && !database.isEmpty()) {
      Query.run(connection, provider, () -> {
        connection.setCatalog(database);
        return null;
      });
    }
    var begin = pg ? "BEGIN READ ONLY" : "START TRANSACTION READ ONLY";
    // Not cut short by an interrupt, so that the transaction it begins always reaches its end.
    Query.within(connection, Query.GRACE, () -> execute(connection, begin));
    try {
      return callsOut(connection, provider)
          ? OptionalLong.empty()
          : explain(connection, provider, sql);
    } finally {
      // MariaDB's and MySQL's ROLLBACK would chain or disconnect as completion_type says.
      var rollback = pg ? "ROLLBACK" : "ROLLBACK AND NO CHAIN NO RELEASE";
      Query.within(connection, Query.GRACE, () -> execute(connection, rollback));
    }
  }

  /**
   * Whether planning may run code of the user's that reaches beyond the read-only transaction, as
   * through another connection: on PostgreSQL a function outside the system's schemas that the
   * planner evaluates, immutable or stable, in a language that can call other code, as one that
   * calls dblink_exec; on MariaDB and MySQL any stored function outside the system's schemas,
   * which may call a loadable function. A write to a table of any engine, FEDERATED among them,
   * the read-only transaction fails by itself.
   */
  // shortcut: a C or internal function of PostgreSQL, a loadable function of MariaDB or MySQL used
  // as it is, and a stored function only a view's definer may see are not counted: native code
  // that writes behind its label is its extension's bug. Check for them where one turns up.
  private static boolean callsOut(Connection connection, Provider provider) throws QueryException {
    var sql = provider == Provider.POSTGRESQL
        ? "SELECT 1 FROM pg_catalog.pg_proc p"
            + " JOIN pg_catalog.pg_namespace n ON n.oid OPERATOR(pg_catalog.=) p.pronamespace"
            + " JOIN pg_catalog.pg_language l ON l.oid OPERATOR(pg_catalog.=) p.prolang"
            + " WHERE p.provolatile OPERATOR(pg_catalog.<>) 'v'"
            + " AND n.nspname OPERATOR(pg_catalog.<>) 'pg_catalog'"
            + " AND n.nspname OPERATOR(pg_catalog.<>) 'information_schema'"
            + " AND l.lanname OPERATOR(pg_catalog.<>) 'c'"
            + " AND l.lanname OPERATOR(pg_catalog.<>) 'internal' LIMIT 1"
        : "SELECT 1 FROM information_schema.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION'"
            + " AND ROUTINE_SCHEMA NOT IN ('sys', 'mysql', 'information_schema',"
            + " 'performance_schema') LIMIT 1";
    return query(connection, provider, sql, ResultSet::next);
  }

  private static Void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
    return null;
  }

  // The text as a plain query, read so, given up after LIMIT by the driver's own timeout.
  private static <T> T query(
      Connection connection, Provider provider, String sql, Query.Result<T> result)
      throws QueryException {
    try (var statement = connection.createStatement()) {
      statement.setEscapeProcessing(false);
      statement.setQueryTimeout((int) LIMIT.toSeconds());
      return Query.run(
          connection,
          provider,
          statement::cancel,
          () -> {
            try (var rows = statement.executeQuery(sql)) {
              return result.read(rows);
            }
          },
          value -> false);
    } catch (SQLException e) {
      throw Query.failure(connection, e);
    }
  }

  private static OptionalLong explain(Connection connection, Provider provider, String sql)
      throws QueryException {
    return switch (provider) {
      case POSTGRESQL ->
        query(
            connection,
            provider,
            "EXPLAIN " + sql,
            rows -> rows.next() ? Rows.planned(rows.getString(1)) : OptionalLong.empty());
      // The tree, from MySQL 8.0.18, estimates what each node gives; the table only the rows read.
      case MYSQL ->
        query(
            connection,
            provider,
            "EXPLAIN FORMAT=TREE " + sql,
            rows -> rows.next() ? Rows.planned(rows.getString(1)) : OptionalLong.empty());
      case MARIADB ->
        query(connection, provider, "EXPLAIN " + sql, rows -> {
          // Each select's tables, by its id, joined; and the selects of a UNION inside another.
          var products = new LinkedHashMap<Object, Long>();
          var unions = new HashSet<Object>();
          var inner = new HashSet<String>();
          boolean any = false;
          while (rows.next()) {
            var id = rows.getObject("id");
            var type = rows.getString("select_type");
            if (id == null) {
              var table = String.valueOf(rows.getString("table"));
              if (table.startsWith("<union")) {
                inner.addAll(List.of(table.replaceAll("[<union>.]", "").split(",")));
              }
              continue;
            }
            if ("UNION".equals(type)) {
              unions.add(id);
            }
            long read = rows.getLong("rows");
            read = rows.wasNull() ? 1 : read;
            any |= !rows.wasNull();
            long product = products.getOrDefault(id, 1L);
            products.put(
                id, read != 0 && product > Long.MAX_VALUE / read ? Long.MAX_VALUE : product * read);
          }
          if (!any) {
            return OptionalLong.empty();
          }
          var first = products.keySet().iterator().next();
          // A UNION RESULT that names the first select names the outermost union's selects.
          boolean outermost = inner.contains(String.valueOf(first));
          long sum = 0;
          for (var entry : products.entrySet()) {
            var id = entry.getKey();
            if (id.equals(first)
                || unions.contains(id) && (outermost || !inner.contains(String.valueOf(id)))) {
              sum =
                  sum > Long.MAX_VALUE - entry.getValue() ? Long.MAX_VALUE : sum + entry.getValue();
            }
          }
          var limit = Statements.limit(sql, provider);
          return OptionalLong.of(Math.min(sum, limit.orElse(Long.MAX_VALUE)));
        });
    };
  }
}

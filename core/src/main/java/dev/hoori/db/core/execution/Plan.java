package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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

  private static final String SAVEPOINT = "hooridb_estimate";

  private Plan() {}

  /**
   * The rows the statement gives as the planner estimates them; empty where its plan says none, as
   * MySQL's for an aggregate. On PostgreSQL and MySQL it is the top of the plan's own; on MariaDB,
   * whose plan gives only the rows read of each table, the product over the tables of each select
   * of the outermost UNION, summed, at most its LIMIT: the size of their join before a condition or
   * a grouping cuts it down.
   *
   * <p>Planning may run the user's functions, as PostgreSQL folds an immutable or stable one and
   * MariaDB and MySQL fill a derived table or a constant subquery, and they may write. So the
   * {@code EXPLAIN} runs where writing fails, or is undone: on PostgreSQL in a read-only transaction
   * of its own or a read-only savepoint of the one open; on MariaDB and MySQL in a read-only
   * transaction where none is open, or in a savepoint where one is, which cannot be made read-only
   * there, so that no estimate is made where the server has a stored function. Either way the
   * transaction is left as it was, also where the {@code EXPLAIN} fails or is interrupted, and the
   * savepoint's name is the user's to keep: PostgreSQL nests one of the same name, while MariaDB
   * and MySQL would replace it. In a failed transaction, or one whose state is not known, it sends
   * nothing.
   *
   * @throws QueryException as {@link Query} reports it, such as for a statement that has no plan,
   *     or one whose plan ran a function that wrote
   */
  // shortcut: a loadable function of MariaDB or MySQL, or a stored one that only a view's definer
  // may see, is not found; inside a transaction what it writes to a table that is not
  // transactional stays. Ask the server for them where that matters.
  public static OptionalLong rows(Connection connection, Provider provider, String sql)
      throws QueryException {
    var before = Status.of(connection, provider);
    boolean pg = provider == Provider.POSTGRESQL;
    boolean open = before.transaction() == Transaction.OPEN;
    if (!open && before.transaction() != Transaction.NONE) {
      return OptionalLong.empty();
    }
    var begin = open
        ? "SAVEPOINT " + SAVEPOINT
        : !pg
            ? "START TRANSACTION READ ONLY"
            // pgjdbc begins the transaction itself where auto-commit is off.
            : Boolean.TRUE.equals(before.autoCommit())
                ? "BEGIN READ ONLY"
                : "SET TRANSACTION READ ONLY";
    // Not cut short by an interrupt, so that what it makes always reaches its end below.
    Query.within(connection, Query.GRACE, () -> execute(connection, begin));
    try {
      if (open && pg) {
        Query.run(
            connection,
            provider,
            () -> execute(connection, "SET LOCAL transaction_read_only = on"));
      }
      if (open && !pg && functions(connection, provider)) {
        return OptionalLong.empty();
      }
      return explain(connection, provider, sql);
    } finally {
      Query.within(connection, Query.GRACE, () -> {
        if (open) {
          execute(connection, "ROLLBACK TO SAVEPOINT " + SAVEPOINT);
          return execute(connection, "RELEASE SAVEPOINT " + SAVEPOINT);
        }
        return execute(connection, pg ? "ROLLBACK" : "ROLLBACK AND NO CHAIN NO RELEASE");
      });
    }
  }

  private static Void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
    return null;
  }

  // Whether the server has a stored function the user may call, other than its own.
  private static boolean functions(Connection connection, Provider provider) throws QueryException {
    return Query.query(
        connection,
        provider,
        "SELECT 1 FROM information_schema.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION'"
            + " AND ROUTINE_SCHEMA NOT IN ('sys', 'mysql', 'information_schema',"
            + " 'performance_schema') LIMIT 1",
        ResultSet::next);
  }

  private static OptionalLong explain(Connection connection, Provider provider, String sql)
      throws QueryException {
    return switch (provider) {
      case POSTGRESQL ->
        Query.query(
            connection,
            provider,
            "EXPLAIN " + sql,
            rows -> rows.next() ? Rows.planned(rows.getString(1)) : OptionalLong.empty());
      // The tree, from MySQL 8.0.18, estimates what each node gives; the table only the rows read.
      case MYSQL ->
        Query.query(
            connection,
            provider,
            "EXPLAIN FORMAT=TREE " + sql,
            rows -> rows.next() ? Rows.planned(rows.getString(1)) : OptionalLong.empty());
      case MARIADB ->
        Query.query(connection, provider, "EXPLAIN " + sql, rows -> {
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

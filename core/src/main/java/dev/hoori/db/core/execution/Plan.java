package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.util.Objects;
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
   * whose plan gives only the rows read of each table, the product over the tables of the
   * outermost select, the size of their join before a condition, a grouping or a LIMIT cuts it
   * down.
   *
   * <p>It leaves the connection's transaction as it was, also where the {@code EXPLAIN} fails or is
   * interrupted: on PostgreSQL inside a savepoint where one is open, as a failure fails it there,
   * and where auto-commit is off and none is open, the one the {@code EXPLAIN} begins is rolled
   * back, without beginning another as MariaDB's and MySQL's {@code completion_type} would. In a
   * failed transaction of PostgreSQL, which takes nothing but a rollback, it sends nothing.
   *
   * @throws QueryException as {@link Query} reports it, such as for a statement that has no plan
   */
  public static OptionalLong rows(Connection connection, Provider provider, String sql)
      throws QueryException {
    var before = Status.of(connection, provider);
    boolean pg = provider == Provider.POSTGRESQL;
    if (pg
        && before.transaction() != Transaction.NONE
        && before.transaction() != Transaction.OPEN) {
      return OptionalLong.empty();
    }
    boolean savepoint = pg && before.transaction() == Transaction.OPEN;
    boolean begins =
        before.transaction() == Transaction.NONE && Boolean.FALSE.equals(before.autoCommit());
    if (savepoint) {
      Query.run(connection, provider, () -> {
        try (var statement = connection.createStatement()) {
          statement.execute("SAVEPOINT " + SAVEPOINT);
        }
        return null;
      });
    }
    try {
      return explain(connection, provider, sql);
    } finally {
      if (savepoint || begins) {
        // Also after an interrupt, which Query.run would refuse at once.
        Query.within(connection, Query.GRACE, () -> {
          try (var statement = connection.createStatement()) {
            if (savepoint) {
              statement.execute("ROLLBACK TO SAVEPOINT " + SAVEPOINT);
              statement.execute("RELEASE SAVEPOINT " + SAVEPOINT);
            } else if (pg) {
              connection.rollback();
            } else {
              statement.execute("ROLLBACK AND NO CHAIN NO RELEASE");
            }
          }
          return null;
        });
      }
    }
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
          long product = 1;
          boolean any = false;
          boolean first = true;
          Object outermost = null;
          while (rows.next()) {
            var id = rows.getObject("id");
            if (!first && !Objects.equals(id, outermost)) {
              break; // its lines come first, then those of subqueries and unions
            }
            first = false;
            outermost = id;
            var read = rows.getLong("rows");
            if (!rows.wasNull()) {
              product =
                  read != 0 && product > Long.MAX_VALUE / read ? Long.MAX_VALUE : product * read;
              any = true;
            }
          }
          return any ? OptionalLong.of(product) : OptionalLong.empty();
        });
    };
  }
}

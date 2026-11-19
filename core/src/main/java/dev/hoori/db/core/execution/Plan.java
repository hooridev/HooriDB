package dev.hoori.db.core.execution;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
    var plan = provider.dialect().plan();
    if (!provider.supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)
        && database != null
        && !database.isEmpty()) {
      Query.run(connection, provider, () -> {
        connection.setCatalog(database);
        return null;
      });
    }
    var begin = plan.beginReadOnly();
    // Not cut short by an interrupt, so that the transaction it begins always reaches its end.
    Query.within(connection, provider, Query.GRACE, () -> execute(connection, begin));
    try {
      return callsOut(connection, provider)
          ? OptionalLong.empty()
          : explain(connection, provider, sql);
    } finally {
      var rollback = plan.rollback();
      Query.within(connection, provider, Query.GRACE, () -> execute(connection, rollback));
    }
  }

  /**
   * Whether planning may run code of the user's that reaches beyond the read-only transaction, as
   * through another connection: on PostgreSQL a function outside the system's schemas that the
   * planner evaluates, immutable or stable, in a language that can call other code, as one that
   * calls dblink_exec, unless an extension owns it; on MariaDB and MySQL any stored function outside the system's schemas,
   * which may call a loadable function. A write to a table of any engine, FEDERATED among them,
   * the read-only transaction fails by itself.
   */
  // shortcut: PostgreSQL's C and internal functions and those an extension owns, such as
  // PostGIS's in SQL, a loadable function of MariaDB or MySQL used as it is, and a stored function
  // only a view's definer may see are not counted: what an administrator installed and writes
  // behind its label is its extension's bug. Check for them where one turns up.
  private static boolean callsOut(Connection connection, Provider provider) throws QueryException {
    var sql = provider.dialect().plan().callsOut();
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
      throw Query.failure(connection, provider, e);
    }
  }

  private static OptionalLong explain(Connection connection, Provider provider, String sql)
      throws QueryException {
    var plan = provider.dialect().plan();
    return query(connection, provider, plan.explain(sql), rows -> plan.rows(rows, sql, provider));
  }
}

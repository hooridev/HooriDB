package dev.hoori.db.core.execution;

import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.open;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The estimate of a statement's rows on real servers, and the transaction it leaves as it was. */
class PlanServerTest {

  private static final String MISSING = "SELECT * FROM missing_table";

  private static void table(Connection connection, Provider provider) throws Exception {
    execute(connection, "DROP TABLE IF EXISTS planned", "CREATE TABLE planned (id integer)");
    var rows = provider == Provider.POSTGRESQL
        ? "INSERT INTO planned SELECT g FROM generate_series(1, 5000) g"
        : "INSERT INTO planned WITH RECURSIVE s (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s"
            + " WHERE n < 900) SELECT n FROM s";
    execute(
        connection,
        rows,
        provider == Provider.POSTGRESQL ? "ANALYZE planned" : "ANALYZE TABLE planned");
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void theTopOfThePlanSaysItsRows(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      table(connection, provider);
      try {
        long all =
            Plan.rows(connection, provider, null, "SELECT * FROM planned").orElseThrow();
        assertTrue(all >= 500, provider + ": " + all);
        // What the statement gives, not the rows it reads.
        assertEquals(
            OptionalLong.of(10),
            Plan.rows(connection, provider, null, "SELECT * FROM planned LIMIT 10"));
        long joined = Plan.rows(connection, provider, null, "SELECT * FROM planned a, planned b")
            .orElseThrow();
        assertTrue(joined > 100 * all, provider + ": " + joined);
        // A small select and a large one after it: both count.
        long union = Plan.rows(
                connection, provider, null, "SELECT 0 AS id UNION ALL SELECT id FROM planned")
            .orElseThrow();
        assertTrue(union >= all, provider + ": " + union);
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, null, MISSING));
        assertThrows(
            QueryException.class, () -> Plan.rows(connection, provider, null, "SHOW TABLES"));
      } finally {
        execute(connection, "DROP TABLE planned");
      }
    }
  }

  /** Whatever becomes of the EXPLAIN, its connection is left with nothing open, in auto-commit. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void nothingIsLeftOpen(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      assertTrue(Plan.rows(connection, provider, null, "SELECT 1 FROM " + dual(provider))
              .isPresent()
          || provider == Provider.MARIADB);
      assertEquals(Transaction.NONE, Transaction.of(connection, provider));
      assertThrows(QueryException.class, () -> Plan.rows(connection, provider, null, MISSING));
      assertEquals(Transaction.NONE, Transaction.of(connection, provider));
      assertTrue(connection.getAutoCommit());
      assertEquals(1, selectLong(connection, "SELECT 1"));
    }
  }

  /**
   * MariaDB's and MySQL's completion_type would make a bare ROLLBACK begin the next transaction or
   * close the connection: the estimate's ends neither way, whether it plans or fails.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void completionTypeNeitherChainsNorReleases(Provider provider) throws Exception {
    for (int type : new int[] {1, 2}) {
      try (var connection = open(provider)) {
        execute(connection, "SET SESSION completion_type = " + type);
        Plan.rows(connection, provider, null, "SELECT 1 FROM dual");
        assertEquals(Transaction.NONE, Transaction.of(connection, provider), "type " + type);
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, null, MISSING));
        assertEquals(Transaction.NONE, Transaction.of(connection, provider), "type " + type);
        assertEquals(1, selectLong(connection, "SELECT 1"));
      }
    }
  }

  private static String dual(Provider provider) {
    return provider == Provider.POSTGRESQL ? "(SELECT 1) AS d" : "dual";
  }

  /**
   * On MariaDB and MySQL the plan reads the statement's names on the database given, as the
   * console's, and the connection, the estimate's own, stays there.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void theDatabaseIsTheOneGiven(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(provider);
        var none = on(server, "")) {
      table(connection, provider);
      try {
        assertThrows(
            QueryException.class, () -> Plan.rows(none, provider, null, "SELECT * FROM planned"));
        assertTrue(Plan.rows(none, provider, TestServer.DATABASE, "SELECT * FROM planned")
            .isPresent());
        assertEquals(TestServer.DATABASE, none.getCatalog());
        assertThrows(
            QueryException.class,
            () -> Plan.rows(none, provider, TestServer.SPACED_DATABASE, "SELECT * FROM planned"));
        assertEquals(TestServer.SPACED_DATABASE, none.getCatalog());
        assertEquals(Transaction.NONE, Transaction.of(none, provider));
      } finally {
        execute(connection, "DROP TABLE planned");
      }
    }
  }

  private static Connection on(TestServer server, String database) throws Exception {
    return Connector.open(
            server.profile().withDatabase(database),
            TestServer.password(),
            server.driver(),
            Duration.ofSeconds(10))
        .connection();
  }

  /**
   * The user's transaction on another connection holds a lock the plan waits for, which the server
   * cannot tell from a deadlock of the app's: the estimate gives up after its limit, and leaves its
   * connection usable.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aLockOfTheUsersTransactionEndsItInTime(Provider provider) throws Exception {
    boolean pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider);
        var user = open(provider)) {
      execute(connection, "DROP TABLE IF EXISTS locked", "CREATE TABLE locked (id integer)");
      try {
        if (pg) {
          user.setAutoCommit(false);
          execute(user, "ALTER TABLE locked ADD COLUMN note integer");
        } else {
          execute(user, "LOCK TABLES locked WRITE");
        }
        long began = System.nanoTime();
        assertThrows(
            QueryException.class,
            () -> Plan.rows(connection, provider, null, "SELECT * FROM locked"));
        var took = Duration.ofNanos(System.nanoTime() - began);
        assertTrue(took.compareTo(Plan.LIMIT.plusSeconds(5)) < 0, took.toString());
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertEquals(1, selectLong(connection, "SELECT 1"));
      } finally {
        if (pg) {
          user.rollback();
          user.setAutoCommit(true);
        } else {
          execute(user, "UNLOCK TABLES");
        }
        execute(connection, "DROP TABLE locked");
      }
    }
  }

  /** An EXPLAIN interrupted as it waits for a lock: its transaction is rolled back all the same. */
  @ParameterizedTest
  @EnumSource(value = Provider.class, names = "POSTGRESQL")
  void anInterruptLeavesNothingOpen(Provider provider) throws Exception {
    try (var connection = open(provider);
        var locker = open(provider)) {
      execute(connection, "DROP TABLE IF EXISTS locked", "CREATE TABLE locked (id integer)");
      try {
        locker.setAutoCommit(false);
        execute(locker, "LOCK TABLE locked IN ACCESS EXCLUSIVE MODE");
        var failure = new AtomicReference<Exception>();
        var explaining = Thread.ofVirtual().start(() -> {
          try {
            Plan.rows(connection, provider, null, "SELECT * FROM locked");
          } catch (Exception e) {
            failure.set(e);
          }
        });
        var waiting =
            "SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = 'locked'::regclass";
        for (int i = 0; i < 50 && selectLong(locker, waiting) == 0; i++) {
          Thread.sleep(20);
        }
        assertEquals(1, selectLong(locker, waiting), "the EXPLAIN waits for the lock");
        explaining.interrupt();
        explaining.join();
        var cancelled = assertInstanceOf(QueryException.class, failure.get());
        assertEquals(QueryException.Failure.CANCELLED, cancelled.failure());
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
      } finally {
        locker.rollback();
        execute(connection, "DROP TABLE locked");
      }
    }
  }

  /**
   * A function that writes, which planning runs: PostgreSQL folds an immutable one that calls it,
   * MariaDB fills a derived table, MySQL a constant subquery. The estimate's read-only transaction
   * fails it, also where it writes to a MyISAM table, which no rollback would undo. MariaDB's and
   * MySQL's are made as root, as MySQL's binary log asks for its privilege.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aFunctionThatWritesAtPlanningWritesNothing(Provider provider) throws Exception {
    boolean pg = provider == Provider.POSTGRESQL;
    var sql = switch (provider) {
      case POSTGRESQL -> "SELECT * FROM plan_table WHERE id = plan_fold(1)";
      case MARIADB -> "SELECT * FROM plan_table, (SELECT plan_write(1)) AS x";
      case MYSQL ->
        "SELECT * FROM plan_table WHERE 1 = (SELECT count(*) FROM plan_table WHERE id ="
            + " plan_write(1))";
    };
    try (var connection = open(provider);
        var owner = pg ? open(provider) : root(provider)) {
      execute(
          connection,
          "DROP TABLE IF EXISTS plan_table",
          "DROP TABLE IF EXISTS plan_log",
          "CREATE TABLE plan_table (id integer PRIMARY KEY)",
          "INSERT INTO plan_table VALUES (1), (2)",
          "CREATE TABLE plan_log (n integer)" + (pg ? "" : " ENGINE=MyISAM"));
      if (pg) {
        execute(
            owner,
            "CREATE FUNCTION plan_write(x integer) RETURNS integer VOLATILE LANGUAGE plpgsql"
                + " AS $$ BEGIN INSERT INTO plan_log VALUES (x); RETURN x; END $$",
            "CREATE FUNCTION plan_fold(x integer) RETURNS integer IMMUTABLE LANGUAGE plpgsql"
                + " AS $$ BEGIN RETURN plan_write(x); END $$");
      } else {
        execute(
            owner,
            "CREATE FUNCTION " + TestServer.DATABASE + ".plan_write(x integer) RETURNS integer"
                + " DETERMINISTIC BEGIN INSERT INTO " + TestServer.DATABASE
                + ".plan_log VALUES (x); RETURN x; END");
      }
      try {
        // Without the read-only transaction, the plan runs the function, which writes.
        execute(
            connection, (provider == Provider.MYSQL ? "EXPLAIN FORMAT=TREE " : "EXPLAIN ") + sql);
        long wrote = selectLong(connection, "SELECT count(*) FROM plan_log");
        assertTrue(wrote > 0, provider + " ran no function");
        estimate(connection, provider, sql);
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertEquals(wrote, selectLong(connection, "SELECT count(*) FROM plan_log"));
      } finally {
        execute(owner, pg ? "DROP FUNCTION plan_fold" : "SELECT 1");
        execute(owner, "DROP FUNCTION " + (pg ? "" : TestServer.DATABASE + ".") + "plan_write");
        execute(connection, "DROP TABLE plan_table", "DROP TABLE plan_log");
      }
    }
  }

  /**
   * A function that reaches beyond the read-only transaction: on PostgreSQL an immutable one in
   * PL/pgSQL that writes through dblink, which the planner folds, as a plain EXPLAIN shows; on
   * MariaDB and MySQL any stored function, which may call a loadable function. Where one exists
   * there is no estimate, and nothing is called through it; where none does, there is one, also
   * beside dblink's own functions, which are in C.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aFunctionThatMayCallOutStopsTheEstimate(Provider provider) throws Exception {
    boolean pg = provider == Provider.POSTGRESQL;
    var sql = "SELECT id, plan_remote() FROM plan_rows";
    try (var connection = open(provider);
        var owner = pg ? open(provider) : root(provider)) {
      execute(
          connection,
          "DROP TABLE IF EXISTS plan_rows",
          "DROP TABLE IF EXISTS plan_remote_log",
          "CREATE TABLE plan_rows (id integer PRIMARY KEY)",
          "INSERT INTO plan_rows VALUES (1), (2)",
          "CREATE TABLE plan_remote_log (n integer)");
      if (pg) {
        execute(
            owner,
            "CREATE EXTENSION IF NOT EXISTS dblink",
            "CREATE FUNCTION plan_remote() RETURNS integer IMMUTABLE LANGUAGE plpgsql AS $$ BEGIN"
                + " PERFORM dblink_exec('dbname=" + TestServer.DATABASE + " user=" + TestServer.USER
                + "', 'INSERT INTO plan_remote_log VALUES (1)'); RETURN 1; END $$");
      } else {
        execute(
            owner,
            "CREATE FUNCTION " + TestServer.DATABASE + ".plan_remote() RETURNS integer"
                + " DETERMINISTIC RETURN 1");
      }
      try {
        long called = 0;
        if (pg) {
          // The door: planning commits through the other connection, read-only or not.
          execute(connection, "BEGIN READ ONLY", "EXPLAIN " + sql, "ROLLBACK");
          called = selectLong(connection, "SELECT count(*) FROM plan_remote_log");
          assertEquals(1, called);
        }
        assertEquals(OptionalLong.empty(), Plan.rows(connection, provider, null, sql));
        assertEquals(called, selectLong(connection, "SELECT count(*) FROM plan_remote_log"));
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        execute(owner, "DROP FUNCTION " + (pg ? "" : TestServer.DATABASE + ".") + "plan_remote");
        assertTrue(
            Plan.rows(connection, provider, null, "SELECT id FROM plan_rows").isPresent());
      } finally {
        execute(
            owner,
            "DROP FUNCTION IF EXISTS " + (pg ? "" : TestServer.DATABASE + ".") + "plan_remote");
        if (pg) {
          execute(owner, "DROP EXTENSION IF EXISTS dblink");
        }
        execute(connection, "DROP TABLE plan_rows", "DROP TABLE plan_remote_log");
      }
    }
  }

  /**
   * PostgreSQL's citext installs functions in SQL that the planner may evaluate: an extension owns
   * them, as an administrator installed it, so the estimate stays.
   */
  @ParameterizedTest
  @EnumSource(value = Provider.class, names = "POSTGRESQL")
  void anExtensionsOwnFunctionsKeepTheEstimate(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, "CREATE EXTENSION IF NOT EXISTS citext");
      try {
        assertTrue(selectLong(
                connection,
                "SELECT count(*) FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang"
                    + " WHERE l.lanname = 'sql' AND p.provolatile <> 'v'"
                    + " AND p.proname LIKE 'regexp%' AND p.pronamespace = 'public'::regnamespace")
            > 0);
        assertTrue(Plan.rows(connection, provider, null, "SELECT 1 FROM (SELECT 1) AS d")
            .isPresent());
      } finally {
        execute(connection, "DROP EXTENSION citext");
      }
    }
  }

  // An estimate that runs no function: none, or a failure where writing failed.
  private static void estimate(Connection connection, Provider provider, String sql) {
    try {
      assertEquals(OptionalLong.empty(), Plan.rows(connection, provider, null, sql));
    } catch (QueryException e) {
      assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains("read"), e.getMessage());
    }
  }

  private static Connection root(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(
            server.profile().withUser("root"),
            ("root-" + TestServer.PASSWORD).toCharArray(),
            server.driver(),
            Duration.ofSeconds(10))
        .connection();
  }
}

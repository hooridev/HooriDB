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
        long all = Plan.rows(connection, provider, "SELECT * FROM planned").orElseThrow();
        assertTrue(all >= 500, provider + ": " + all);
        // What the statement gives, not the rows it reads.
        assertEquals(
            OptionalLong.of(10), Plan.rows(connection, provider, "SELECT * FROM planned LIMIT 10"));
        long joined = Plan.rows(connection, provider, "SELECT * FROM planned a, planned b")
            .orElseThrow();
        assertTrue(joined > 100 * all, provider + ": " + joined);
        // A small select and a large one after it: both count.
        long union = Plan.rows(
                connection, provider, "SELECT 0 AS id UNION ALL SELECT id FROM planned")
            .orElseThrow();
        assertTrue(union >= all, provider + ": " + union);
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, MISSING));
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, "SHOW TABLES"));
      } finally {
        execute(connection, "DROP TABLE planned");
      }
    }
  }

  /** A failing EXPLAIN in an open transaction: it stays open, with what it holds, and usable. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aFailureLeavesTheOpenTransaction(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, "DROP TABLE IF EXISTS kept_open", "CREATE TABLE kept_open (id integer)");
      try {
        connection.setAutoCommit(false);
        execute(connection, "INSERT INTO kept_open VALUES (1)");
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, MISSING));
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertTrue(Plan.rows(connection, provider, "SELECT * FROM kept_open").isPresent());
        assertEquals(1, selectLong(connection, "SELECT count(*) FROM kept_open"));
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE kept_open");
      }
    }
  }

  /** A BEGIN typed in auto-commit: PostgreSQL's transaction stays open past a failure too. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aFailureLeavesATransactionBegunInAutoCommit(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      try {
        execute(connection, "BEGIN");
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, MISSING));
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(1, selectLong(connection, "SELECT 1"));
      } finally {
        execute(connection, "ROLLBACK");
      }
    }
  }

  /** With auto-commit off and nothing open, nothing is open after it, also with a chain. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void nothingOpenStaysSo(Provider provider) throws Exception {
    boolean pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      try {
        connection.setAutoCommit(false);
        if (!pg) {
          execute(connection, "SET completion_type=1");
        }
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertTrue(Plan.rows(connection, provider, "SELECT * FROM customer").isPresent());
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertThrows(QueryException.class, () -> Plan.rows(connection, provider, MISSING));
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
      } finally {
        if (!pg) {
          execute(connection, "SET completion_type=0");
        }
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }

  /** An EXPLAIN interrupted as it waits for a lock: PostgreSQL's transaction stays open. */
  @ParameterizedTest
  @EnumSource(value = Provider.class, names = "POSTGRESQL")
  void anInterruptLeavesTheOpenTransaction(Provider provider) throws Exception {
    try (var connection = open(provider);
        var locker = open(provider)) {
      execute(connection, "DROP TABLE IF EXISTS locked", "CREATE TABLE locked (id integer)");
      try {
        locker.setAutoCommit(false);
        execute(locker, "LOCK TABLE locked IN ACCESS EXCLUSIVE MODE");
        connection.setAutoCommit(false);
        execute(connection, "SELECT 1"); // begins the transaction, which pgjdbc keeps open
        var failure = new AtomicReference<Exception>();
        var explaining = Thread.ofVirtual().start(() -> {
          try {
            Plan.rows(connection, provider, "SELECT * FROM locked");
          } catch (Exception e) {
            failure.set(e);
          }
        });
        var waiting = "SELECT count(*) FROM pg_locks WHERE NOT granted"
            + " AND relation = 'locked'::regclass";
        for (int i = 0; i < 500 && selectLong(locker, waiting) == 0; i++) {
          Thread.sleep(20);
        }
        assertEquals(1, selectLong(locker, waiting), "the EXPLAIN waits for the lock");
        explaining.interrupt();
        explaining.join();
        var cancelled = assertInstanceOf(QueryException.class, failure.get());
        assertEquals(QueryException.Failure.CANCELLED, cancelled.failure());
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(1, selectLong(connection, "SELECT 1"));
      } finally {
        locker.rollback();
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE locked");
      }
    }
  }

  /**
   * A function that writes, which planning runs: PostgreSQL folds an immutable one that calls it,
   * MariaDB fills a derived table, MySQL a constant subquery. No estimate runs it, in an open
   * transaction or none, and what it would write is never there. MariaDB's and MySQL's are made
   * as root, as MySQL's binary log asks for its privilege.
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
          "CREATE TABLE plan_log (n integer)");
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
        // As the guard is not there: the function writes as the plan is made.
        if (pg) {
          execute(connection, "EXPLAIN " + sql);
        }
        long before = selectLong(connection, "SELECT count(*) FROM plan_log");
        assertTrue(!pg || before == 1, "PostgreSQL folds the function: " + before);
        // None open, with auto-commit on and off.
        for (boolean auto : new boolean[] {true, false}) {
          connection.setAutoCommit(auto);
          estimate(connection, provider, sql);
          assertEquals(Transaction.NONE, Transaction.of(connection, provider));
          connection.setAutoCommit(true);
        }
        // One open, with what it holds.
        connection.setAutoCommit(false);
        execute(connection, "INSERT INTO plan_table VALUES (3)");
        estimate(connection, provider, sql);
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(3, selectLong(connection, "SELECT count(*) FROM plan_table"));
        connection.rollback();
        connection.setAutoCommit(true);
        assertEquals(before, selectLong(connection, "SELECT count(*) FROM plan_log"));
      } finally {
        connection.setAutoCommit(true);
        execute(owner, pg ? "DROP FUNCTION plan_fold" : "SELECT 1");
        execute(owner, "DROP FUNCTION " + (pg ? "" : TestServer.DATABASE + ".") + "plan_write");
        execute(connection, "DROP TABLE plan_table", "DROP TABLE plan_log");
      }
    }
  }

  // An estimate that runs no function: none, or a failure where writing failed.
  private static void estimate(Connection connection, Provider provider, String sql) {
    try {
      assertEquals(OptionalLong.empty(), Plan.rows(connection, provider, sql));
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

  /**
   * Interrupted as its savepoint is made, which is made all the same, the estimate still ends it:
   * a user's savepoint of the same name, which PostgreSQL nests, is again the one a ROLLBACK TO
   * goes back to.
   */
  @ParameterizedTest
  @EnumSource(value = Provider.class, names = "POSTGRESQL")
  void anInterruptAtTheSavepointLeavesNone(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, "DROP TABLE IF EXISTS saved", "CREATE TABLE saved (id integer)");
      try {
        connection.setAutoCommit(false);
        execute(connection, "SAVEPOINT hooridb_estimate", "INSERT INTO saved VALUES (1)");
        Thread.currentThread().interrupt();
        var cancelled =
            assertThrows(QueryException.class, () -> Plan.rows(connection, provider, "SELECT 1"));
        assertTrue(Thread.interrupted());
        assertEquals(QueryException.Failure.CANCELLED, cancelled.failure());
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        execute(connection, "ROLLBACK TO SAVEPOINT hooridb_estimate");
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM saved"));
      } finally {
        Thread.interrupted();
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE saved");
      }
    }
  }

  /** PostgreSQL's failed transaction takes nothing but a rollback: nothing is sent. */
  @ParameterizedTest
  @EnumSource(value = Provider.class, names = "POSTGRESQL")
  void aFailedTransactionGetsNoEstimate(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      try {
        connection.setAutoCommit(false);
        assertThrows(Exception.class, () -> execute(connection, MISSING));
        assertEquals(Transaction.FAILED, Transaction.of(connection, provider));
        assertEquals(OptionalLong.empty(), Plan.rows(connection, provider, "SELECT 1"));
        assertEquals(Transaction.FAILED, Transaction.of(connection, provider));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }
}

package dev.hoori.db.core.execution;

import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.open;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
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

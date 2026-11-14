package dev.hoori.db.core.execution;

import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.failure;
import static dev.hoori.db.core.execution.ExecutionServerTest.open;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Transactions as a console runs them, statements and their COMMIT and ROLLBACK as text, on real
 * servers, and the state each portion says and the server gives when asked.
 */
class TransactionServerTest {

  private static Portion run(Connection connection, Provider provider, String sql) {
    try (var execution = new Execution(connection, provider, sql)) {
      return execution.next(100);
    }
  }

  // A table of its own for each test, gone afterwards.
  private static void table(Connection connection, String name) throws Exception {
    execute(connection, "DROP TABLE IF EXISTS " + name, "CREATE TABLE " + name + " (id integer)");
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aRollbackUndoesTheChange(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      table(connection, "undone");
      try {
        connection.setAutoCommit(false);
        // Asking starts nothing.
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        var inserted = run(connection, provider, "INSERT INTO undone VALUES (1)");
        assertEquals(Transaction.OPEN, inserted.transaction());
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(Transaction.NONE, run(connection, provider, "ROLLBACK").transaction());
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM undone"));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE undone");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCommitShowsTheChangeToAnotherConnection(Provider provider) throws Exception {
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "kept");
      try {
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO kept VALUES (1)");
        assertEquals(0, selectLong(other, "SELECT count(*) FROM kept"));
        assertEquals(Transaction.NONE, run(connection, provider, "COMMIT").transaction());
        assertEquals(1, selectLong(other, "SELECT count(*) FROM kept"));
      } finally {
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE kept");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void anErrorFailsPostgresqlsTransactionAndLeavesTheOthersOpen(Provider provider)
      throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      table(connection, "strict");
      try {
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO strict VALUES (1)");
        var failed = run(connection, provider, "SELECT * FROM missing_table");
        assertEquals(Failure.Kind.INVALID, failure(failed.results()).kind());
        assertEquals(pg ? Transaction.FAILED : Transaction.UNKNOWN, failed.transaction());
        assertEquals(
            pg ? Transaction.FAILED : Transaction.OPEN, Transaction.of(connection, provider));
        var next = run(connection, provider, "SELECT count(*) FROM strict");
        if (pg) {
          // Nothing but a rollback, which a COMMIT is too.
          assertEquals("25P02", failure(next.results()).state());
          assertEquals(Transaction.FAILED, next.transaction());
          assertEquals(Transaction.NONE, run(connection, provider, "COMMIT").transaction());
          assertEquals(0, selectLong(connection, "SELECT count(*) FROM strict"));
        } else {
          assertEquals(Transaction.OPEN, next.transaction());
          assertInstanceOf(Result.Rows.class, next.results().getFirst());
          assertEquals(1, selectLong(connection, "SELECT count(*) FROM strict"));
        }
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE strict");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void ddlCommitsOnMariaDbAndMySqlOnly(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "before_ddl");
      try {
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO before_ddl VALUES (1)");
        var ddl = run(connection, provider, "CREATE TABLE after_ddl (id integer)");
        assertEquals(pg ? Transaction.OPEN : Transaction.NONE, ddl.transaction());
        assertEquals(pg ? 0 : 1, selectLong(other, "SELECT count(*) FROM before_ddl"));
        run(connection, provider, "ROLLBACK");
        assertEquals(pg ? 0 : 1, selectLong(other, "SELECT count(*) FROM before_ddl"));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE IF EXISTS after_ddl", "DROP TABLE before_ddl");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aBeginTypedWithAutoCommitOpensATransactionUntilItsEnd(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "begun");
      try {
        var begun = run(connection, provider, pg ? "BEGIN" : "START TRANSACTION");
        assertEquals(Transaction.OPEN, begun.transaction());
        assertEquals(
            Transaction.OPEN,
            run(connection, provider, "INSERT INTO begun VALUES (1), (2)").transaction());
        // A query keeps it open, also while its rows are read.
        try (var query = new Execution(connection, provider, "SELECT id FROM begun")) {
          assertEquals(
              pg ? Transaction.OPEN : Transaction.UNKNOWN, query.next(1).transaction());
        }
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(0, selectLong(other, "SELECT count(*) FROM begun"));
        assertEquals(Transaction.NONE, run(connection, provider, "ROLLBACK").transaction());
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM begun"));
      } finally {
        execute(connection, "DROP TABLE begun");
      }
    }
  }

  // A procedure that begins a transaction, changes a row and gives 300 rows, without a commit.
  private static final String TX_ROWS = """
      CREATE PROCEDURE tx_rows()
      BEGIN
        START TRANSACTION;
        INSERT INTO kept_open VALUES (1);
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 300)
        SELECT i FROM n;
      END""";

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aProceduresRowsStillToComeSayNothingOfTheTransactionItBegan(Provider provider)
      throws Exception {
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "kept_open");
      execute(connection, "DROP PROCEDURE IF EXISTS tx_rows", TX_ROWS);
      try (var call = new Execution(connection, provider, "CALL tx_rows()")) {
        // In auto-commit: the first portion cannot say that the procedure left one open.
        var first = call.next(100);
        assertTrue(first.more());
        assertEquals(Transaction.UNKNOWN, first.transaction());
        Portion last;
        do {
          last = call.next(100);
        } while (last.more());
        assertEquals(Transaction.OPEN, last.transaction());
        assertEquals(0, selectLong(other, "SELECT count(*) FROM kept_open"));
        assertEquals(Transaction.NONE, run(connection, provider, "ROLLBACK").transaction());
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM kept_open"));
      } finally {
        execute(connection, "DROP PROCEDURE tx_rows", "DROP TABLE kept_open");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void withChainedCompletionTheServerBeginsTheNextTransactionAtOnce(Provider provider)
      throws Exception {
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "chained");
      try {
        execute(connection, "SET SESSION completion_type = 1");
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO chained VALUES (1)");
        // Committed, and a new transaction open already, with nothing in it.
        assertEquals(Transaction.OPEN, run(connection, provider, "COMMIT").transaction());
        assertEquals(1, selectLong(other, "SELECT count(*) FROM chained"));
        assertEquals(Transaction.OPEN, Transaction.of(connection, provider));
        assertEquals(Transaction.OPEN, run(connection, provider, "ROLLBACK").transaction());
        assertEquals(1, selectLong(connection, "SELECT count(*) FROM chained"));
        // Auto-commit turned on ends the empty one.
        connection.setAutoCommit(true);
        assertEquals(Transaction.NONE, Transaction.of(connection, provider));
      } finally {
        connection.setAutoCommit(true);
        execute(connection, "SET SESSION completion_type = 0", "DROP TABLE chained");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aFailedDdlHasCommittedOnMariaDbAndMySqlAndFailsPostgresqls(Provider provider)
      throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "ddl_failed");
      try {
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO ddl_failed VALUES (1)");
        var ddl = run(connection, provider, "CREATE TABLE ddl_failed (id integer)");
        assertEquals(Failure.Kind.INVALID, failure(ddl.results()).kind(), ddl.toString());
        assertEquals(pg ? Transaction.FAILED : Transaction.UNKNOWN, ddl.transaction());
        // MariaDB and MySQL committed before the statement ran, and kept nothing open.
        assertEquals(
            pg ? Transaction.FAILED : Transaction.NONE, Transaction.of(connection, provider));
        assertEquals(pg ? 0 : 1, selectLong(other, "SELECT count(*) FROM ddl_failed"));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE ddl_failed");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aFailedProcedureThatTurnedAutoCommitOnIsSeenByTheStatusAndTheDriver(Provider provider)
      throws Exception {
    try (var connection = open(provider);
        var other = open(provider)) {
      table(connection, "turned_on");
      execute(
          connection,
          "DROP PROCEDURE IF EXISTS on_and_fail",
          "CREATE PROCEDURE on_and_fail() BEGIN SET autocommit = 1;"
              + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'after'; END");
      try {
        connection.setAutoCommit(false);
        run(connection, provider, "INSERT INTO turned_on VALUES (1)");
        var failed = run(connection, provider, "CALL on_and_fail()");
        assertEquals(Transaction.UNKNOWN, failed.transaction());
        assertEquals(null, failed.autoCommit());
        // Turned on, it committed what was open: the server says so, and the driver follows.
        var status = Status.of(connection, provider);
        assertEquals(new Status(Transaction.NONE, true), status);
        assertTrue(connection.getAutoCommit(), "the driver's own idea, set from the server's");
        assertEquals(1, selectLong(other, "SELECT count(*) FROM turned_on"));
      } finally {
        connection.setAutoCommit(true);
        execute(connection, "DROP PROCEDURE on_and_fail", "DROP TABLE turned_on");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aPortionSaysTheAutoCommitAsItSaysTheTransaction(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      assertEquals(true, run(connection, provider, "SELECT 1").autoCommit());
      connection.setAutoCommit(false);
      try {
        assertEquals(false, run(connection, provider, "SELECT 1").autoCommit());
        var failed = run(connection, provider, "SELECT * FROM missing_table");
        assertEquals(pg ? Boolean.FALSE : null, failed.autoCommit());
        assertEquals(
            new Status(pg ? Transaction.FAILED : Transaction.NONE, false),
            Status.of(connection, provider));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }

  /** A driver whose status cannot be read, as MariaDB Connector/J before 3 lacks the API. */
  @Test
  void aDriverThatCannotReportTheStatusLeavesBothUnknown() throws Exception {
    try (var real = open(Provider.MARIADB)) {
      // The driver's own classes out of reach, as JDBC's interface alone.
      var connection = (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
            try {
              return m.invoke(real, a);
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
          });
      assertEquals(new Status(Transaction.UNKNOWN, null), Status.of(connection, Provider.MARIADB));
      assertEquals(null, run(connection, Provider.MARIADB, "SELECT 1").autoCommit());
    }
  }
}

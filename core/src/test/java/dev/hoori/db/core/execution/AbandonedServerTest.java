package dev.hoori.db.core.execution;

import static dev.hoori.db.core.execution.ExecutionServerTest.awaitRunning;
import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.failure;
import static dev.hoori.db.core.execution.ExecutionServerTest.interrupted;
import static dev.hoori.db.core.execution.ExecutionServerTest.million;
import static dev.hoori.db.core.execution.ExecutionServerTest.only;
import static dev.hoori.db.core.execution.ExecutionServerTest.open;
import static dev.hoori.db.core.execution.ExecutionServerTest.rows;
import static dev.hoori.db.core.execution.ExecutionServerTest.run;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Statements left early, failing on the way, or changing the session, and what is reported of
 * them: rows abandoned, procedures partly read, transactions the text opens, states after
 * failures, columns not in UTF-8.
 */
class AbandonedServerTest {

  // Changes every row of the table and gives their ids, more than a portion of 10.
  private static final String PAY =
      "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed";

  @Test
  void aDataModifyingQueryGivenUpIsCommitted() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      createOrders(connection);
      try {
        var execution = new Execution(connection, provider, PAY);
        assertTrue(execution.next(10).more());
        assertEquals(Optional.empty(), execution.abandon().failure());
        assertTrue(connection.getAutoCommit());
        assertEquals(1000, selectLong(other, "SELECT count(*) FROM orders WHERE paid"));
      } finally {
        execute(connection, "DROP TABLE orders");
      }
    }
  }

  @Test
  void aDataModifyingQueryCancelledIsRolledBack() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      createOrders(connection);
      try {
        var execution = new Execution(
            connection,
            provider,
            "WITH changed AS (UPDATE orders SET paid = true RETURNING id)"
                + " SELECT id, pg_sleep(30) AS slow FROM changed");
        var portion = interrupted(() -> execution.next(10), () -> awaitRunning(provider, "slow"));
        assertEquals(Failure.Kind.CANCELLED, failure(portion.results()).kind());
        assertEquals(Optional.empty(), execution.abandon().failure());
        assertTrue(connection.getAutoCommit());
        assertEquals(0, selectLong(other, "SELECT count(*) FROM orders WHERE paid"));
      } finally {
        execute(connection, "DROP TABLE orders");
      }
    }
  }

  @Test
  void aDataModifyingQueryThatFailsIsRolledBack() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      createOrders(connection);
      try {
        var execution = new Execution(
            connection,
            provider,
            "WITH changed AS (UPDATE orders SET paid = true RETURNING id)"
                + " SELECT 1 / (id - 500) FROM changed");
        Portion portion;
        do {
          portion = execution.next(10);
        } while (portion.more());
        assertEquals(Failure.Kind.OTHER, failure(portion.results()).kind());
        assertTrue(connection.getAutoCommit());
        assertEquals(0, selectLong(other, "SELECT count(*) FROM orders WHERE paid"));
      } finally {
        execute(connection, "DROP TABLE orders");
      }
    }
  }

  @Test
  void aLargeQueryGivenUpLeavesTheConnectionAsItWas() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      var execution =
          new Execution(connection, provider, "SELECT n FROM generate_series(1, 100000) n");
      assertTrue(execution.next(10).more());
      assertEquals(Optional.empty(), execution.abandon().failure());
      assertTrue(connection.getAutoCommit());
      assertEquals(
          Transaction.NONE, Provider.POSTGRESQL.dialect().session().transaction(connection, -1));
      assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
    }
  }

  private static void createOrders(Connection connection) throws Exception {
    execute(
        connection,
        "CREATE TABLE orders (id integer, paid boolean NOT NULL DEFAULT false)",
        "INSERT INTO orders SELECT n FROM generate_series(1, 1000) n");
  }

  @Test
  void aQueryGivenUpKeepsWhatItsFunctionsDidForTheRowsComputed() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE bumped (n integer)",
          "CREATE FUNCTION bump(n integer) RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN INSERT INTO bumped VALUES (n); RETURN n; END $$");
      try {
        var execution =
            new Execution(connection, provider, "SELECT bump(n) FROM generate_series(1, 100) n");
        assertTrue(execution.next(1).more());
        execution.close();
        assertTrue(connection.getAutoCommit());
        var kept = selectLong(connection, "SELECT count(*) FROM bumped");
        assertTrue(kept > 0 && kept < 100, "kept " + kept);

        // Run to its end, all of it is committed.
        run(connection, provider, "SELECT bump(n) FROM generate_series(1, 3) n");
        assertEquals(kept + 3, selectLong(connection, "SELECT count(*) FROM bumped"));
      } finally {
        execute(connection, "DROP FUNCTION bump", "DROP TABLE bumped");
      }
    }
  }

  @Test
  void aCommitThatFailsIsReportedAndAutoCommitComesBack() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE deferred (id integer UNIQUE DEFERRABLE INITIALLY DEFERRED)",
          "INSERT INTO deferred VALUES (1)");
      try {
        var twice = "WITH x AS (INSERT INTO deferred VALUES (1), (2) RETURNING *) SELECT * FROM x";
        var portion = new Execution(connection, provider, twice).next(10);
        assertEquals(2, ((Result.Rows) portion.results().getFirst()).rows().size());
        assertEquals(Failure.Kind.CONSTRAINT, failure(portion.results()).kind());
        assertTrue(connection.getAutoCommit());
        assertEquals(Transaction.NONE, portion.transaction());

        var early = new Execution(connection, provider, twice);
        assertTrue(early.next(1).more());
        assertEquals(
            Failure.Kind.CONSTRAINT, early.abandon().failure().orElseThrow().kind());
        assertTrue(connection.getAutoCommit());
        assertFalse(connection.isClosed());
        assertEquals(1, selectLong(connection, "SELECT count(*) FROM deferred"));
        assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
      } finally {
        execute(connection, "DROP TABLE deferred");
      }
    }
  }

  @Test
  void aTextOfSeveralStatementsKeepsTheTransactionItBegins() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      execute(connection, "CREATE TABLE begun (id integer)");
      try {
        var results = run(connection, provider, "SELECT 1; BEGIN; INSERT INTO begun VALUES (999)");
        assertEquals(new Result.Count(1), results.getLast());
        assertEquals(
            Transaction.OPEN, Provider.POSTGRESQL.dialect().session().transaction(connection, -1));
        assertEquals(0, selectLong(other, "SELECT count(*) FROM begun"));
        run(connection, provider, "ROLLBACK");
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM begun"));
      } finally {
        execute(connection, "DROP TABLE begun");
      }
    }
  }

  @Test
  void aQueryWordInACommentGivesNoTransactionOfItsOwn() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      assertEquals(
          List.of(new Result.Count(0)), run(connection, provider, "-- SELECT\nVACUUM reading"));
      assertEquals(
          List.of(new Result.Count(0)),
          run(connection, provider, "/* a /* SELECT */ b */ VACUUM reading"));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aProcedureLeftEarlyLeavesTheConnectionUsable(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE PROCEDURE three() BEGIN SELECT id FROM reading WHERE id <= 3; SELECT 99; END");
      try {
        var execution = new Execution(connection, provider, "CALL three()");
        assertTrue(execution.next(1).more());
        execution.close();
        assertFalse(connection.isClosed());
        assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
      } finally {
        execute(connection, "DROP PROCEDURE three");
      }
    }
  }

  @Test
  void mysqlEndsWithTheWriteTimeoutItBeganWith() throws Exception {
    var provider = Provider.MYSQL;
    try (var connection = open(provider)) {
      execute(connection, "SET SESSION net_write_timeout = 17");
      run(connection, provider, "SELECT 1");
      assertEquals(17, selectLong(connection, "SELECT @@session.net_write_timeout"));
      var early = new Execution(connection, provider, million(provider));
      early.next(1);
      early.close();
      assertEquals(17, selectLong(connection, "SELECT @@session.net_write_timeout"));
    }
  }

  @Test
  void aFailureWhileFetchingKeepsTheRowsAndNoticesBeforeIt() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE FUNCTION loud(n integer) RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN RAISE NOTICE 'row %', n; RETURN 100 / (12 - n); END $$");
      try {
        var execution =
            new Execution(connection, provider, "SELECT loud(n) FROM generate_series(1, 20) n");
        var first = execution.next(10);
        assertEquals(10, ((Result.Rows) only(first.results())).rows().size());
        var second = execution.next(10);
        assertEquals(2, second.results().size(), second.results().toString());
        var rest = assertInstanceOf(Result.Rows.class, second.results().getFirst());
        assertEquals(10, rest.from());
        assertEquals(List.of(List.of(100L)), rest.rows());
        assertEquals("22012", failure(second.results()).state());
        assertTrue(
            second.messages().contains(new Message("row 12", "00000", 0)),
            second.messages().toString());
      } finally {
        execute(connection, "DROP FUNCTION loud");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aProcedureThatFailsKeepsItsFirstResult(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE PROCEDURE failing() BEGIN SELECT 1 AS one;"
              + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'no more'; END");
      try {
        var results = run(connection, provider, "CALL failing()");
        // MariaDB Connector/J reads a procedure's results at once, and drops them for its failure.
        if (provider == Provider.MYSQL) {
          assertEquals(List.of(List.of(1L)), ((Result.Rows) results.getFirst()).rows());
        }
        assertEquals("no more", failure(results).message());
        assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
      } finally {
        execute(connection, "DROP PROCEDURE failing");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTransactionOpenedByAQueryIsKnownOnlyOnceItEnds(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      connection.setAutoCommit(false);
      try {
        var execution =
            new Execution(connection, provider, "SELECT id FROM reading WHERE id <= 3 FOR UPDATE");
        var first = execution.next(1);
        assertTrue(first.more());
        assertEquals(pg ? Transaction.OPEN : Transaction.UNKNOWN, first.transaction());
        var rest = execution.next(10);
        assertFalse(rest.more());
        assertEquals(Transaction.OPEN, rest.transaction());
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    }
  }

  @Test
  void aSessionTheServerEndedHasNoKnownTransaction() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(connection, "SET idle_in_transaction_session_timeout = 100");
      connection.setAutoCommit(false);
      run(connection, provider, "SELECT 1");
      Thread.sleep(400);
      var portion = new Execution(connection, provider, "SELECT 1").next(1);
      assertEquals(Failure.Kind.CONNECTION_LOST, failure(portion.results()).kind());
      assertEquals(Transaction.UNKNOWN, portion.transaction());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void autoCommitTurnedOffByAStatementLetsTheDriverCommit(Provider provider) throws Exception {
    try (var connection = open(provider);
        var other = open(provider)) {
      execute(connection, "CREATE TABLE switched (id integer)");
      try {
        run(connection, provider, "SET autocommit = 0");
        assertFalse(connection.getAutoCommit());
        var inserted =
            new Execution(connection, provider, "INSERT INTO switched VALUES (1)").next(1);
        assertEquals(Transaction.OPEN, inserted.transaction());
        assertEquals(0, selectLong(other, "SELECT count(*) FROM switched"));
        connection.commit();
        assertEquals(1, selectLong(other, "SELECT count(*) FROM switched"));
        run(connection, provider, "SET autocommit = 1");
        assertTrue(connection.getAutoCommit());
      } finally {
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE switched");
      }
    }
  }

  @Test
  void aFailureOtherThanACancelIsNotCalledOne() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var real = open(provider)) {
      // A driver that fails to decode what it read, as some versions of MariaDB Connector/J did.
      var connection = (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, args) -> {
            var result = invoke(m, real, args);
            if (!m.getName().equals("createStatement")) {
              return result;
            }
            return Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (q, n, more) -> {
                  if (n.getName().equals("execute")) {
                    throw new IllegalStateException("cannot decode");
                  }
                  return invoke(n, result, more);
                });
          });
      var failure =
          failure(new Execution(connection, provider, "SELECT 1").next(1).results());
      assertEquals(Failure.Kind.OTHER, failure.kind());
      assertEquals("cannot decode", failure.message());
    }
  }

  @Test
  void aStreamTheServerGaveUpIsALostConnection() throws Exception {
    var provider = Provider.MYSQL;
    try (var connection = open(provider)) {
      execute(connection, "SET SESSION net_write_timeout = 1");
      var execution = new Execution(connection, provider, million(provider));
      assertTrue(execution.next(1).more());
      Thread.sleep(2500);
      Portion portion;
      do {
        portion = execution.next(100_000);
      } while (portion.more());
      var lost = failure(portion.results());
      assertEquals(Failure.Kind.CONNECTION_LOST, lost.kind(), lost.toString());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void onlyARealSyntaxErrorSaysWhereItIs(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var bogus = failure(run(connection, provider, "BOGUS near 'x'"));
      assertEquals(1064, bogus.code());
      assertEquals("BOGUS near 'x'", bogus.near());
      assertEquals(1, bogus.line());

      var signal = failure(run(
          connection,
          provider,
          "SIGNAL SQLSTATE '42000' SET MESSAGE_TEXT ="
              + " 'You have an error to use near ''x'' at line 999999999999'"));
      assertEquals(Failure.Kind.INVALID, signal.kind(), signal.toString());
      assertEquals(0, signal.line());
      assertEquals("", signal.near());
    }
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** What the server says as a statement's own transaction is committed at the give-up. */
  @Test
  void aCommitsWarningAtTheGiveUpIsSaid() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE warned (id integer)",
          "CREATE FUNCTION warned_note() RETURNS trigger AS $$ BEGIN RAISE WARNING 'checked at"
              + " commit'; RETURN NULL; END $$ LANGUAGE plpgsql",
          "CREATE CONSTRAINT TRIGGER warned_check AFTER INSERT ON warned DEFERRABLE INITIALLY"
              + " DEFERRED FOR EACH ROW EXECUTE FUNCTION warned_note()");
      try {
        var early = new Execution(
            connection,
            provider,
            "WITH x AS (INSERT INTO warned VALUES (1), (2) RETURNING *) SELECT * FROM x");
        assertTrue(early.next(1).more());
        var ended = early.abandon();
        assertEquals(Optional.empty(), ended.failure());
        assertTrue(
            ended.messages().stream().anyMatch(m -> m.text().contains("checked at commit")),
            ended.messages().toString());
        assertEquals(2, selectLong(connection, "SELECT count(*) FROM warned"));
      } finally {
        execute(connection, "DROP TABLE warned", "DROP FUNCTION warned_note");
      }
    }
  }

  /**
   * A server that stops answering while a statement is given up: the give-up ends within its
   * bound all the same, the connection given up, and says so.
   */
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aGiveUpTheServerNeverAnswersEndsWithinItsBound(Provider provider) throws Exception {
    var server = dev.hoori.db.core.TestServer.of(provider);
    try (var _ = new ShortEnding();
        var relay = new dev.hoori.db.core.query.Relay(server.host(), server.port());
        var connection = dev.hoori.db.core.connect.Connector.open(
                server
                    .profile()
                    .withHost(dev.hoori.db.core.query.Relay.HOST)
                    .withPort(relay.port()),
                dev.hoori.db.core.TestServer.password(),
                server.driver(),
                java.time.Duration.ofSeconds(10))
            .connection()) {
      var read = new Execution(connection, provider, million(provider));
      assertTrue(read.next(10).more());
      relay.freeze();
      long started = System.nanoTime();
      var ended = read.abandon();
      var took = java.time.Duration.ofNanos(System.nanoTime() - started);
      assertTrue(
          took.compareTo(
                  ShortEnding.BOUND.plus(dev.hoori.db.core.query.Query.GRACE.multipliedBy(2)))
              < 0,
          took.toString());
      assertTrue(took.compareTo(ShortEnding.BOUND) >= 0, "not before its bound: " + took);
      var failure = ended.failure().orElseThrow();
      assertEquals(Failure.Kind.CONNECTION_LOST, failure.kind());
      assertTrue(failure.message().contains(ShortEnding.CUT_OFF), failure.message());
      assertTrue(connection.isClosed());
    }
  }
}

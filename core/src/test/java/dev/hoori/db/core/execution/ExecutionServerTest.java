package dev.hoori.db.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.query.Query;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Running statements of every kind on real servers, from the sample schema of {@link TestServer}. */
class ExecutionServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aQueryGivesItsRowsAndOneWithoutRowsItsFields(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var some = only(run(connection, provider, "SELECT id, label FROM reading WHERE id <= 3"));
      var rows = assertInstanceOf(Result.Rows.class, some);
      assertEquals(
          List.of("id", "label"), rows.fields().stream().map(Field::name).toList());
      assertEquals(
          List.of(Kind.INTEGER, Kind.TEXT),
          rows.fields().stream().map(Field::kind).toList());
      assertEquals(0, rows.from());
      assertEquals(
          List.of(List.of(1L, "reading 1"), List.of(2L, "reading 2"), List.of(3L, "reading 3")),
          rows.rows());

      var none = only(run(
          connection,
          provider,
          "SELECT id, amount AS \"the amount\" FROM reading WHERE 1 = 0"
              .replace('"', provider == Provider.POSTGRESQL ? '"' : '`')));
      var empty = assertInstanceOf(Result.Rows.class, none);
      assertEquals(
          List.of(
              new Field("id", empty.fields().get(0).type(), Kind.INTEGER),
              new Field("the amount", empty.fields().get(1).type(), Kind.DECIMAL)),
          empty.fields());
      assertEquals(List.of(), empty.rows());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void changesGiveTheirCountsAndDdlNone(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      assertEquals(
          List.of(new Result.Count(0)),
          run(connection, provider, "CREATE TABLE counted (id integer PRIMARY KEY, n integer)"));
      try {
        assertEquals(
            List.of(new Result.Count(3)),
            run(connection, provider, "INSERT INTO counted VALUES (1, 1), (2, 2), (3, 3)"));
        assertEquals(
            List.of(new Result.Count(2)),
            run(connection, provider, "UPDATE counted SET n = n + 1 WHERE id >= 2"));
        assertEquals(
            List.of(new Result.Count(0)),
            run(connection, provider, "UPDATE counted SET n = 0 WHERE id > 10"));
        assertEquals(
            List.of(new Result.Count(3)), run(connection, provider, "DELETE FROM counted"));
      } finally {
        assertEquals(List.of(new Result.Count(0)), run(connection, provider, "DROP TABLE counted"));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aProcedureGivesItsResultsInOrder(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE touched (id integer)",
          pg
              ? "CREATE PROCEDURE several(INOUT total integer) LANGUAGE plpgsql AS $$"
                  + " BEGIN INSERT INTO touched VALUES (1), (2); total := 2; END $$"
              : "CREATE PROCEDURE several() BEGIN SELECT 1 AS one; INSERT INTO touched VALUES"
                  + " (1), (2); SELECT 'two' AS two, 2 AS n; END");
      try {
        var results = run(connection, provider, pg ? "CALL several(NULL)" : "CALL several()");
        if (pg) {
          // A procedure gives its INOUT parameters as a row.
          var total = assertInstanceOf(Result.Rows.class, only(results));
          assertEquals(List.of(List.of(2L)), total.rows());
          // Several statements in one text are several results too.
          results = run(connection, provider, "SELECT 1 AS one; DELETE FROM touched; SELECT 'two'");
          assertEquals(3, results.size());
          assertEquals(List.of(List.of(1L)), ((Result.Rows) results.get(0)).rows());
          assertEquals(new Result.Count(2), results.get(1));
          assertEquals(List.of(List.of("two")), ((Result.Rows) results.get(2)).rows());
        } else {
          assertEquals(3, results.size(), results.toString());
          assertEquals(List.of(List.of(1L)), ((Result.Rows) results.get(0)).rows());
          assertEquals(List.of(List.of("two", 2L)), ((Result.Rows) results.get(1)).rows());
          // The CALL's own status.
          assertInstanceOf(Result.Count.class, results.get(2));
        }
      } finally {
        execute(connection, "DROP PROCEDURE several", "DROP TABLE touched");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aPlanIsRows(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var explain = provider
          .explain("SELECT * FROM reading WHERE id = 1", ServerVersion.UNKNOWN)
          .orElseThrow();
      var plan = assertInstanceOf(Result.Rows.class, only(run(connection, provider, explain)));
      assertFalse(plan.rows().isEmpty());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void rowsComeInPortionsInOrder(Provider provider) throws Exception {
    try (var connection = open(provider);
        var execution = new Execution(connection, provider, "SELECT id FROM reading ORDER BY id")) {
      var first = execution.next(100);
      assertTrue(first.more());
      var rows = assertInstanceOf(Result.Rows.class, only(first.results()));
      assertEquals(0, rows.from());
      assertEquals(ids(1, 100), rows.rows());

      var second = assertInstanceOf(Result.Rows.class, only(execution.next(100).results()));
      assertEquals(100, second.from());
      assertEquals(ids(101, 200), second.rows());

      // Exactly the rest: that there are no more is known without another call.
      var last = execution.next(4800);
      assertFalse(last.more());
      var rest = assertInstanceOf(Result.Rows.class, only(last.results()));
      assertEquals(200, rest.from());
      assertEquals(ids(201, 5000), rest.rows());
      assertThrows(IllegalStateException.class, () -> execution.next(1));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theFirstPortionOfAMillionRowsComesAtOnceAndClosingEarlyEndsTheQuery(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      var execution = new Execution(connection, provider, million(provider));
      var started = System.nanoTime();
      var first = execution.next(100);
      var took = Duration.ofNanos(System.nanoTime() - started);
      assertTrue(first.more());
      assertEquals(100, ((Result.Rows) only(first.results())).rows().size());
      assertTrue(took.toMillis() < 1000, provider + " took " + took);
      if (provider == Provider.POSTGRESQL) {
        // In a transaction of its own, which is none of the caller's.
        assertEquals(
            Transaction.OPEN, Provider.POSTGRESQL.dialect().session().transaction(connection, -1));
        assertEquals(Transaction.NONE, first.transaction());
        assertFalse(connection.getAutoCommit());
      }

      started = System.nanoTime();
      execution.close();
      took = Duration.ofNanos(System.nanoTime() - started);
      // Reading the rest would take about a second.
      assertTrue(took.toMillis() < 300, provider + " took " + took + " to close");
      assertTrue(connection.getAutoCommit());
      if (provider == Provider.POSTGRESQL) {
        assertEquals(0, selectLong(connection, "SELECT count(*) FROM pg_cursors WHERE name <> ''"));
        assertEquals(
            Transaction.NONE, Provider.POSTGRESQL.dialect().session().transaction(connection, -1));
      }
      assertEquals(List.of(List.of(42L)), rows(run(connection, provider, "SELECT 42")));
      execution.close();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void readingManyRowsKeepsMemoryFlat(Provider provider) throws Exception {
    try (var connection = open(provider);
        var execution = new Execution(connection, provider, million(provider))) {
      var before = heap();
      execution.next(1000);
      var first = heap();
      long read = 0;
      while (read < 100_000) {
        read += ((Result.Rows) only(execution.next(1000).results())).rows().size();
      }
      var after = heap();
      // A million rows of 100 characters whole would take hundreds of megabytes.
      assertTrue(first - before < 16 << 20, provider + " took " + (first - before) + " bytes");
      assertTrue(after - first < 16 << 20, provider + " grew by " + (after - first) + " bytes");
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSyntaxErrorHasItsKindCodeAndPlace(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      // Characters beyond the BMP count as one on the server and as two in Java.
      var sql = "SELECT 'Grüße 😀 𝄞',\n  1 +\n  FROM reading";
      var syntax = failure(run(connection, provider, sql));
      assertEquals(Failure.Kind.INVALID, syntax.kind(), syntax.toString());
      if (pg) {
        assertEquals("42601", syntax.state());
        var at = sql.indexOf("FROM");
        assertEquals(sql.codePointCount(0, at) + 1, syntax.position());
        assertEquals(at, sql.offsetByCodePoints(0, syntax.position() - 1));
        assertEquals("syntax error at or near \"FROM\"", syntax.message());
        assertEquals(0, syntax.line());
      } else {
        assertEquals("42000", syntax.state());
        assertEquals(1064, syntax.code());
        assertEquals(3, syntax.line());
        assertEquals("FROM reading", syntax.near());
        assertEquals(0, syntax.position());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void eachFailureHasItsKindAndCode(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      var unknown = failure(run(connection, provider, "SELECT * FROM\n  nothere"));
      assertEquals(Failure.Kind.INVALID, unknown.kind());
      assertEquals(pg ? "42P01" : "42S02", unknown.state());
      assertEquals(pg ? 0 : 1146, unknown.code());
      assertEquals(pg ? 17 : 0, unknown.position());
      assertFalse(unknown.message().startsWith("(conn="), unknown.message());

      execute(connection, "CREATE TABLE unique_key (id integer PRIMARY KEY)");
      try {
        run(connection, provider, "INSERT INTO unique_key VALUES (1)");
        var twice = failure(run(connection, provider, "INSERT INTO unique_key VALUES (1)"));
        assertEquals(Failure.Kind.CONSTRAINT, twice.kind());
        assertEquals(pg ? "23505" : "23000", twice.state());
        assertEquals(pg ? 0 : 1062, twice.code());
        assertEquals(pg ? "Key (id)=(1) already exists." : "", twice.detail());
      } finally {
        execute(connection, "DROP TABLE unique_key");
      }

      if (!pg) {
        var denied = failure(run(connection, provider, "SELECT * FROM mysql.user"));
        assertEquals(Failure.Kind.ACCESS_DENIED, denied.kind());
        assertEquals(1142, denied.code());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTimeLimitOfTheServerIsATimeout(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      if (pg) {
        execute(connection, "SET statement_timeout = 100");
      }
      var timeout = failure(run(
          connection,
          provider,
          switch (provider) {
            case POSTGRESQL -> "SELECT pg_sleep(5)";
            case MARIADB ->
              "SET STATEMENT max_statement_time = 0.1 FOR SELECT SLEEP(5) FROM reading";
            case MYSQL -> "SELECT /*+ MAX_EXECUTION_TIME(100) */ SLEEP(5) FROM reading";
            case MONGODB -> throw new IllegalArgumentException("No SQL");
          }));
      if (pg) {
        execute(connection, "RESET statement_timeout");
      }
      assertEquals(Failure.Kind.TIMEOUT, timeout.kind(), timeout.toString());
      assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aFailureSaysWhereInTheStatementItIs(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      // A syntax error in the middle of a line: the second WHERE.
      var middle = "SELECT id FROM reading WHERE WHERE id = 1";
      assertEquals(
          place(middle, "WHERE id", "WHERE"),
          failure(run(connection, provider, middle)).place(middle, provider));
      // On a later line of a statement of several.
      var later = "SELECT id,\n  label\nFROM reading\nWHERE id IN (1,, 2)";
      assertEquals(
          place(later, ", 2)", ","),
          failure(run(connection, provider, later)).place(later, provider));
      // At the end: the last word.
      var end = "SELECT id FROM reading WHERE";
      assertEquals(
          place(end, "WHERE", "WHERE"),
          failure(run(connection, provider, end)).place(end, provider));
      // An unknown column and an unknown table: PostgreSQL says where, MariaDB and MySQL do not.
      var column = "SELECT id,\n  nothere\nFROM reading";
      assertEquals(
          pg ? place(column, "nothere", "nothere") : Optional.empty(),
          failure(run(connection, provider, column)).place(column, provider));
      // A name in double quotes is a string on MariaDB and MySQL.
      var table = pg ? "SELECT *\nFROM \"nowhere\"" : "SELECT *\nFROM nowhere";
      assertEquals(
          pg ? place(table, "\"nowhere\"", "\"nowhere\"") : Optional.empty(),
          failure(run(connection, provider, table)).place(table, provider));
    }
  }

  private static Optional<Failure.Place> place(String sql, String from, String word) {
    int at = sql.indexOf(from);
    return Optional.of(new Failure.Place(at, at + word.length()));
  }

  @Test
  void postgresqlTellsTheQueryOfAFunctionApartAndWhatItLacksAccessTo() throws Exception {
    var provider = Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE FUNCTION broken() RETURNS integer LANGUAGE plpgsql AS $$"
              + " BEGIN RETURN (SELECT x FROM nothere); END $$");
      try {
        var failure = failure(run(connection, provider, "SELECT broken()"));
        assertEquals(Failure.Kind.INVALID, failure.kind());
        assertEquals(0, failure.position());
        assertEquals("(SELECT x FROM nothere)", failure.internalQuery());
        assertEquals(16, failure.internalPosition());
        assertEquals("PL/pgSQL function broken() line 1 at RETURN", failure.context());

        var hinted = failure(run(connection, provider, "SELECT lenght('x')"));
        assertEquals("42883", hinted.state());
        assertFalse(hinted.hint().isEmpty());
      } finally {
        execute(connection, "DROP FUNCTION broken");
      }

      execute(
          connection, "CREATE ROLE statement_reader LOGIN PASSWORD '" + TestServer.PASSWORD + "'");
      try {
        try (var reader = Connector.open(
                server.profile().withUser("statement_reader"),
                TestServer.password(),
                server.driver(),
                TIMEOUT)
            .connection()) {
          var denied = failure(run(reader, provider, "SELECT * FROM reading"));
          assertEquals(Failure.Kind.ACCESS_DENIED, denied.kind());
          assertEquals("42501", denied.state());
        }
      } finally {
        execute(connection, "DROP ROLE statement_reader");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aLostConnectionIsAFailureToo(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider);
        var other = open(provider)) {
      var id = rows(run(
              connection, provider, pg ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()"))
          .getFirst()
          .getFirst();
      execute(other, pg ? "SELECT pg_terminate_backend(" + id + ")" : "KILL " + id);
      var lost = failure(run(connection, provider, "SELECT 1"));
      assertEquals(Failure.Kind.CONNECTION_LOST, lost.kind(), lost.toString());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theServersMessagesComeAlong(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      if (provider == Provider.POSTGRESQL) {
        var portion = new Execution(
                connection,
                provider,
                "DO $$ BEGIN RAISE NOTICE 'hello %', 1; RAISE WARNING 'careful'; END $$")
            .next(10);
        assertEquals(
            List.of(new Message("hello 1", "00000", 0), new Message("careful", "01000", 0)),
            portion.messages());
        return;
      }
      execute(connection, "SET SESSION sql_mode = ''", "CREATE TABLE narrow (t varchar(3))");
      try {
        var portion =
            new Execution(connection, provider, "INSERT INTO narrow VALUES ('abcdef')").next(10);
        assertEquals(List.of(new Result.Count(1)), portion.results());
        var message = only(portion.messages());
        assertEquals(1265, message.code());
        assertEquals("Data truncated for column 't' at row 1", message.text());
        assertEquals(
            List.of(), new Execution(connection, provider, "SELECT 1").next(10).messages());
      } finally {
        execute(connection, "DROP TABLE narrow");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anInterruptCancelsTheStatementWhileItRunsAndKeepsTheConnection(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      var execution = new Execution(
          connection,
          provider,
          provider == Provider.POSTGRESQL
              ? "SELECT pg_sleep(30) AS slow"
              : "SELECT SLEEP(30) AS slow");
      var portion = interrupted(() -> execution.next(10), () -> awaitRunning(provider, "slow"));
      assertEquals(Failure.Kind.CANCELLED, failure(portion.results()).kind());
      assertFalse(portion.more());
      assertTrue(connection.getAutoCommit());
      assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anInterruptCancelsTheStatementWhileItsRowsAreReadAndKeepsTheConnection(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      var execution = new Execution(connection, provider, slowRows(provider));
      assertTrue(execution.next(5).more());
      var portion =
          interrupted(() -> execution.next(10_000), () -> awaitRunning(provider, "lagging"));
      assertEquals(Failure.Kind.CANCELLED, failure(portion.results()).kind());
      assertTrue(portion.elapsed().toMillis() > 0, portion.elapsed().toString());
      assertTrue(connection.getAutoCommit());
      assertEquals(
          provider == Provider.POSTGRESQL ? Transaction.NONE : Transaction.UNKNOWN,
          portion.transaction());
      assertEquals(List.of(List.of(1L)), rows(run(connection, provider, "SELECT 1")));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelInATransactionFailsItOnPostgresqlOnly(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(connection, "CREATE TABLE kept (id integer)");
      connection.setAutoCommit(false);
      var inserted = new Execution(connection, provider, "INSERT INTO kept VALUES (1)").next(1);
      assertEquals(Transaction.OPEN, inserted.transaction());
      var execution = new Execution(connection, provider, slowRows(provider));
      assertTrue(execution.next(5).more());
      var portion =
          interrupted(() -> execution.next(10_000), () -> awaitRunning(provider, "lagging"));
      assertEquals(Failure.Kind.CANCELLED, failure(portion.results()).kind());
      assertEquals(pg ? Transaction.FAILED : Transaction.UNKNOWN, portion.transaction());
      if (pg) {
        var aborted = failure(run(connection, provider, "SELECT 1"));
        assertEquals("25P02", aborted.state());
      } else {
        assertEquals(
            List.of(List.of(1L)), rows(run(connection, provider, "SELECT count(*) FROM kept")));
      }
      connection.rollback();
      assertEquals(
          List.of(List.of(0L)), rows(run(connection, provider, "SELECT count(*) FROM kept")));
    } finally {
      // On a connection of its own, once the test's has ended its transaction by closing: a
      // cancel that fails gives that connection up.
      try (var connection = open(provider)) {
        execute(connection, "DROP TABLE IF EXISTS kept");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theTransactionStateFollowsAutoCommit(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(connection, "CREATE TABLE committed (id integer PRIMARY KEY)");
      try {
        var on = new Execution(connection, provider, "INSERT INTO committed VALUES (1)").next(1);
        assertEquals(Transaction.NONE, on.transaction());

        // A query of its own transaction, which ends with it.
        var query = new Execution(connection, provider, "SELECT id FROM committed").next(10);
        assertEquals(List.of(List.of(1L)), ((Result.Rows) only(query.results())).rows());
        assertEquals(Transaction.NONE, query.transaction());
        assertTrue(connection.getAutoCommit());

        // A statement that cannot run in a transaction.
        if (pg) {
          assertEquals(List.of(new Result.Count(0)), run(connection, provider, "VACUUM committed"));
        }

        // A transaction the user began keeps going.
        run(connection, provider, pg ? "BEGIN" : "START TRANSACTION");
        var begun = new Execution(connection, provider, "SELECT id FROM committed").next(10);
        assertEquals(Transaction.OPEN, begun.transaction());
        run(connection, provider, "ROLLBACK");

        connection.setAutoCommit(false);
        var off = new Execution(connection, provider, "INSERT INTO committed VALUES (2)").next(1);
        assertEquals(Transaction.OPEN, off.transaction());
        var failed =
            new Execution(connection, provider, "INSERT INTO committed VALUES (2)").next(1);
        assertEquals(Failure.Kind.CONSTRAINT, failure(failed.results()).kind());
        assertEquals(pg ? Transaction.FAILED : Transaction.UNKNOWN, failed.transaction());
        connection.rollback();
        var streamed = new Execution(connection, provider, million(provider));
        var first = streamed.next(10);
        assertTrue(first.more());
        streamed.close();
        assertFalse(connection.getAutoCommit());
        connection.rollback();
        connection.setAutoCommit(true);
        assertEquals(
            List.of(List.of(1L)),
            rows(run(connection, provider, "SELECT count(*) FROM committed")));
      } finally {
        connection.setAutoCommit(true);
        execute(connection, "DROP TABLE committed");
      }
    }
  }

  // The numbers from 1 to a million, with a text of 100 characters each.
  static String million(Provider provider) {
    if (provider == Provider.POSTGRESQL) {
      return "SELECT n, repeat('x', 100) AS t FROM generate_series(1, 1000000) n";
    }
    return "WITH digit (d) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
        + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
        + " UNION ALL SELECT 8 UNION ALL SELECT 9)"
        + " SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d + 100000 * g.d + 1 AS n,"
        + " REPEAT('x', 100) AS t FROM digit g, digit f, digit e, digit c, digit b, digit a";
  }

  // Rows whose statement awaitRunning finds by "lagging" while they are read. PostgreSQL makes the
  // first five and the row its fetch looks ahead to at once, then sleeps once, over the last, so
  // that a cancel finds a fetch running: it drops one that comes between fetches. MariaDB and
  // MySQL make one every 10 milliseconds and send them once they fill a buffer of 16 KB.
  private static String slowRows(Provider provider) {
    return provider == Provider.POSTGRESQL
        ? "SELECT n, pg_sleep(CASE WHEN n <= 6 THEN 0 ELSE 30 END) AS lagging"
            + " FROM generate_series(1, 7) n"
        : "SELECT id, SLEEP(0.01) AS lagging, REPEAT('x', 8000) FROM reading";
  }

  interface Call {
    Portion call() throws Exception;
  }

  interface Wait {
    void await() throws Exception;
  }

  // The portion of the call on a thread of its own, which is interrupted once the wait is over;
  // the call ends within the grace a cancel has, and leaves the thread interrupted.
  static Portion interrupted(Call call, Wait wait) throws Exception {
    var portion = new AtomicReference<Portion>();
    var stillInterrupted = new boolean[1];
    var caller = Thread.ofPlatform().start(() -> {
      try {
        portion.set(call.call());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      stillInterrupted[0] = Thread.currentThread().isInterrupted();
    });
    // Once the call waits for its step: an interrupt before would stop the call before it begins.
    var deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (caller.getState() == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    wait.await();
    var started = System.nanoTime();
    caller.interrupt();
    caller.join(Query.GRACE.plusSeconds(1));
    assertFalse(caller.isAlive());
    var took = Duration.ofNanos(System.nanoTime() - started);
    assertTrue(took.compareTo(Query.GRACE) < 0, "took " + took);
    assertTrue(stillInterrupted[0]);
    return portion.get();
  }

  static void awaitRunning(Provider provider, String marker) throws Exception {
    var query = provider == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity"
            + " WHERE query LIKE ? AND state = 'active' AND pid <> pg_backend_pid()"
        : "SELECT count(*) FROM information_schema.processlist"
            + " WHERE info LIKE ? AND id <> CONNECTION_ID()";
    try (var connection = open(provider);
        var statement = connection.prepareStatement(query)) {
      statement.setString(1, "%AS " + marker + "%");
      var deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (System.nanoTime() < deadline) {
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getInt(1) > 0) {
            return;
          }
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError("Nothing runs " + marker);
  }

  // All results of the statement, read to the end.
  static List<Result> run(Connection connection, Provider provider, String sql) {
    var results = new ArrayList<Result>();
    try (var execution = new Execution(connection, provider, sql)) {
      Portion portion;
      do {
        portion = execution.next(1000);
        results.addAll(portion.results());
      } while (portion.more());
    }
    return results;
  }

  static List<List<Object>> rows(List<Result> results) {
    var all = new ArrayList<List<Object>>();
    for (var result : results) {
      all.addAll(assertInstanceOf(Result.Rows.class, result).rows());
    }
    return all;
  }

  static Failure failure(List<Result> results) {
    return assertInstanceOf(Failure.class, results.getLast(), results.toString());
  }

  static <T> T only(List<T> items) {
    assertEquals(1, items.size(), items.toString());
    return items.getFirst();
  }

  private static List<List<Object>> ids(int from, int to) {
    var ids = new ArrayList<List<Object>>();
    for (long id = from; id <= to; id++) {
      ids.add(List.of(id));
    }
    return ids;
  }

  private static long heap() {
    var runtime = Runtime.getRuntime();
    System.gc();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  static Connection open(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT)
        .connection();
  }

  static long selectLong(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The console's seam to real servers: {@link Servers#SERVER}'s links on lanes, as a console uses
 * them, against core's test servers. Skipped without Docker.
 */
class ServersServerTest {

  // 500 rows, in a form all three servers take.
  static final String MANY =
      "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 500) SELECT i FROM n";

  static Servers.Link open(TestServer server) throws Exception {
    return Servers.SERVER.open(server.profile(), TestServer.password(), server.driver());
  }

  /** A lane of a console, open on the server's database or another. */
  static Lane lane(TestServer server, String database) throws Exception {
    var lane = new Lane(e -> {});
    lane.open(
        Servers.SERVER,
        server.profile().withDatabase(database),
        TestServer.password(),
        server.driver());
    lane.submit(link -> link).get(30, TimeUnit.SECONDS); // fails as the opening did
    return lane;
  }

  static List<List<Object>> rows(Portion portion) {
    for (var result : portion.results()) {
      if (result instanceof Result.Rows r) {
        return r.rows();
      }
    }
    throw new AssertionError("No rows in " + portion);
  }

  /** The one value of a statement's first row. */
  static Object value(Servers.Link link, String sql) {
    var run = link.run(sql);
    try {
      return rows(run.next(1)).getFirst().getFirst();
    } finally {
      run.close();
    }
  }

  static String sleep(Provider provider, int seconds) {
    return provider == Provider.POSTGRESQL
        ? "SELECT pg_sleep(" + seconds + ")"
        : "SELECT SLEEP(" + seconds + ")";
  }

  static String database(Provider provider) {
    return provider == Provider.POSTGRESQL ? "SELECT current_database()" : "SELECT DATABASE()";
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void manyRowsAreReadOnInPortions(Provider provider) throws Exception {
    var link = open(TestServer.of(provider));
    try {
      var run = link.run(MANY);
      var first = run.next(200);
      assertTrue(first.more());
      assertEquals(1L, ((Number) rows(first).getFirst().getFirst()).longValue());
      var second = run.next(200);
      assertEquals(200, ((Result.Rows) second.results().getFirst()).from());
      assertTrue(second.more());
      var third = run.next(200);
      assertEquals(100, rows(third).size());
      assertEquals(500L, ((Number) rows(third).getLast().getFirst()).longValue());
      assertFalse(third.more());
      // The connection is free for the next statement.
      assertEquals(1L, ((Number) value(link, "SELECT 1")).longValue());
    } finally {
      link.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void statementsRunOneAfterAnotherWithTheirCountsWarningsAndError(Provider provider)
      throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var text = pg ? """
          CREATE TEMPORARY TABLE narrow (t varchar(3));
          INSERT INTO narrow VALUES ('abc'), ('de');
          DO $$ BEGIN RAISE WARNING 'careful'; END $$;
          SELECT t FROM missing_table;
          """ : """
          CREATE TEMPORARY TABLE narrow (t varchar(3));
          SET SESSION sql_mode = '';
          INSERT INTO narrow VALUES ('abcdef'), ('de');
          SELECT t FROM missing_table;
          """;
    var link = open(TestServer.of(provider));
    try {
      var portions = new ArrayList<Portion>();
      // As Run all does: a statement at a time, each with its own execution.
      for (var at : Statements.split(text, provider)) {
        portions.add(link.run(text.substring(at.start(), at.end())).next(200));
      }
      assertEquals(4, portions.size());
      assertEquals(List.of(new Result.Count(2)), portions.get(pg ? 1 : 2).results());
      var warned = portions.get(2).messages();
      assertEquals(1, warned.size(), warned.toString());
      if (pg) {
        assertEquals("careful", warned.getFirst().text());
        assertEquals("01000", warned.getFirst().state());
      } else {
        assertEquals(1265, warned.getFirst().code());
      }
      var failure = (Failure) portions.get(3).results().getLast();
      assertEquals(Failure.Kind.INVALID, failure.kind());
      assertTrue(failure.message().contains("missing_table"), failure.message());
      assertFalse(failure.message().contains(TestServer.PASSWORD));
    } finally {
      link.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCancelledStatementLeavesTheConnectionToTheNext(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    try {
      long started = System.nanoTime();
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      Thread.sleep(500);
      slow.cancel(true); // as Lane.await does when the console's work is cancelled
      var next = lane.submit(link -> value(link, "SELECT 7")).get(20, TimeUnit.SECONDS);
      assertEquals(7L, ((Number) next).longValue());
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20));
    } finally {
      lane.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void theDatabaseSwitchesByWhatTheServerCanDo(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    try {
      var version = lane.submit(Servers.Link::version).get();
      if (provider.supports(Capability.CONNECTION_PER_DATABASE, version)) {
        // A connection of its own to the other database.
        var other = lane(server, TestServer.SPACED_DATABASE);
        try {
          assertEquals(
              TestServer.SPACED_DATABASE,
              other.submit(link -> value(link, database(provider))).get());
        } finally {
          other.close();
        }
        return;
      }
      // A switch queued behind a statement, both cancelled: the connection stays where it was.
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      var queued = lane.submit(link -> {
        link.use(TestServer.NON_ASCII_DATABASE);
        return null;
      });
      Thread.sleep(500);
      queued.cancel(true);
      slow.cancel(true);
      assertEquals(
          TestServer.DATABASE,
          lane.submit(link -> value(link, database(provider))).get(20, TimeUnit.SECONDS));
      lane.submit(link -> {
            link.use(TestServer.SPACED_DATABASE);
            return null;
          })
          .get(20, TimeUnit.SECONDS);
      assertEquals(
          TestServer.SPACED_DATABASE,
          lane.submit(link -> value(link, database(provider))).get(20, TimeUnit.SECONDS));
    } finally {
      lane.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void closingWhileAStatementRunsEndsTheSessionOnTheServer(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var pg = provider == Provider.POSTGRESQL;
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      long id = ((Number) lane.submit(
                  link -> value(link, pg ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()"))
              .get())
          .longValue();
      var sessions = pg
          ? "SELECT count(*) FROM pg_stat_activity WHERE pid = " + id
          : "SELECT count(*) FROM information_schema.processlist WHERE id = " + id;
      lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      Thread.sleep(500);
      assertEquals(1L, ((Number) value(watcher, sessions)).longValue());
      lane.close(); // as closing the console's tab does
      eventually(() -> ((Number) value(watcher, sessions)).longValue() == 0);
    } finally {
      lane.close();
      watcher.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void manualCommitsAndRollsBackAsCommitAndRollbackRunThem(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    // As the console runs a statement: auto-commit as its mode says, then the statement.
    Lane.Call<Portion> drop = link -> link.run("DROP TABLE IF EXISTS seam_tx").next(10);
    try {
      lane.submit(drop).get(20, TimeUnit.SECONDS);
      lane.submit(link -> link.run("CREATE TABLE seam_tx (id integer)").next(10))
          .get(20, TimeUnit.SECONDS);
      for (var end : List.of(ConsoleTab.ROLLBACK, ConsoleTab.COMMIT)) {
        var changed = lane.submit(link -> {
              link.manual(true);
              return link.run("INSERT INTO seam_tx VALUES (1)").next(10);
            })
            .get(20, TimeUnit.SECONDS);
        assertEquals(Transaction.OPEN, changed.transaction());
        assertEquals(
            Transaction.OPEN, lane.submit(link -> link.status().transaction()).get());
        assertEquals(0L, ((Number) value(watcher, "SELECT count(*) FROM seam_tx")).longValue());
        var ended = lane.submit(link -> link.run(end).next(10)).get(20, TimeUnit.SECONDS);
        assertEquals(Transaction.NONE, ended.transaction());
        assertEquals(
            Transaction.NONE, lane.submit(link -> link.status().transaction()).get());
        assertEquals(
            end.equals(ConsoleTab.COMMIT) ? 1L : 0L,
            ((Number) value(watcher, "SELECT count(*) FROM seam_tx")).longValue());
      }
      // Back to Auto: each statement commits by itself.
      lane.submit(link -> {
            link.manual(false);
            return link.run("INSERT INTO seam_tx VALUES (2)").next(10);
          })
          .get(20, TimeUnit.SECONDS);
      assertEquals(2L, ((Number) value(watcher, "SELECT count(*) FROM seam_tx")).longValue());
    } finally {
      lane.submit(drop).get(20, TimeUnit.SECONDS);
      lane.close();
      watcher.close();
    }
  }

  /** A statement as the console runs it on its lane: its portion, and the auto-commit after. */
  static ConsoleTab.Ran statement(Lane lane, String sql, ConsoleTab.Mode mode) throws Exception {
    return lane.submit(link -> {
          var held = new AtomicReference<Servers.Run>();
          var ran = ConsoleTab.start(link, sql, mode, false, held, new AtomicReference<>());
          return ran;
        })
        .get(20, TimeUnit.SECONDS);
  }

  static ConsoleTab.Ended end(Lane lane, String sql) throws Exception {
    return lane.submit(link -> ConsoleTab.finish(link, sql, new AtomicReference<>()))
        .get(20, TimeUnit.SECONDS);
  }

  static long count(Servers.Link link, String table) {
    return ((Number) value(link, "SELECT count(*) FROM " + table)).longValue();
  }

  /** The review's first finding: a rollback after SET autocommit=0 rolls back, in Auto. */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void theConsoleNeverTurnsAutoCommitOnUnderATransaction(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_auto", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_auto (id integer)", ConsoleTab.Mode.AUTO);
      for (var sql : List.of("START TRANSACTION", "INSERT INTO seam_auto VALUES (1)")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      var set = statement(lane, "SET autocommit=0", ConsoleTab.Mode.AUTO);
      assertEquals(
          false, set.portion().autoCommit(), "the driver follows the server, as the mode does");
      assertEquals(Transaction.OPEN, set.portion().transaction());
      // The Rollback button's call, then a typed ROLLBACK: neither commits first.
      var rolledBack = end(lane, ConsoleTab.ROLLBACK);
      assertEquals(Transaction.NONE, rolledBack.portion().transaction());
      assertEquals(0L, count(watcher, "seam_auto"));
      statement(lane, "INSERT INTO seam_auto VALUES (2)", ConsoleTab.Mode.MANUAL);
      assertEquals(
          Transaction.NONE,
          statement(lane, "ROLLBACK", ConsoleTab.Mode.MANUAL).portion().transaction());
      assertEquals(0L, count(watcher, "seam_auto"));
    } finally {
      statement(lane, "SET autocommit=1", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_auto", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /** The review's second finding: a COMMIT is not sent into what a cancel left failed. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void commitAfterACancelGoesOnlyIntoATransactionTheServerSaysIsOpen(Provider provider)
      throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_cancel", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_cancel (id integer)", ConsoleTab.Mode.AUTO);
      lane.submit(link -> ConsoleTab.setMode(link, true, false, new AtomicReference<>()))
          .get(20, TimeUnit.SECONDS);
      statement(lane, "INSERT INTO seam_cancel VALUES (1)", ConsoleTab.Mode.MANUAL);
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      Thread.sleep(500);
      slow.cancel(true); // as the console's Cancel does
      var ended = end(lane, ConsoleTab.COMMIT);
      if (pg) {
        // PostgreSQL would have answered the COMMIT by rolling back, without an error.
        assertEquals(Transaction.FAILED, ended.before());
        assertEquals(null, ended.portion());
        end(lane, ConsoleTab.ROLLBACK);
        assertEquals(0L, count(watcher, "seam_cancel"));
      } else {
        assertEquals(Transaction.OPEN, ended.before());
        assertEquals(Transaction.NONE, ended.portion().transaction());
        assertEquals(1L, count(watcher, "seam_cancel"));
      }
    } finally {
      lane.submit(link -> ConsoleTab.setMode(link, false, false, new AtomicReference<>()))
          .get(20, TimeUnit.SECONDS);
      statement(lane, "DROP TABLE seam_cancel", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /** The review's tenth finding: after the user's COMMIT the server's next one holds nothing. */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aTransactionChainedAfterCommitIsEmptyAndAutoCommitEndsIt(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_chain", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_chain (id integer)", ConsoleTab.Mode.AUTO);
      statement(lane, "SET SESSION completion_type = 1", ConsoleTab.Mode.AUTO);
      lane.submit(link -> ConsoleTab.setMode(link, true, false, new AtomicReference<>()))
          .get(20, TimeUnit.SECONDS);
      statement(lane, "INSERT INTO seam_chain VALUES (1)", ConsoleTab.Mode.MANUAL);
      var committed = end(lane, ConsoleTab.COMMIT);
      assertEquals(Transaction.OPEN, committed.before());
      assertEquals(Transaction.OPEN, committed.portion().transaction(), "chained at once");
      assertEquals(1L, count(watcher, "seam_chain"));
      // Not where it is not known to be empty; where it is, auto-commit goes on and ends it.
      var refused = lane.submit(
              link -> ConsoleTab.setMode(link, false, false, new AtomicReference<>()))
          .get();
      assertFalse(refused.done());
      var switched = lane.submit(
              link -> ConsoleTab.setMode(link, false, true, new AtomicReference<>()))
          .get();
      assertTrue(switched.done());
      assertEquals(Transaction.NONE, switched.now().transaction());
    } finally {
      statement(lane, "SET SESSION completion_type = 0", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_chain", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * The second review's first finding: a session whose first call never set the mode, as one
   * cancelled while the connection opened, still runs the next statement in Manual.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aStatementRunsInTheModeChosenWhateverRanBefore(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_mode", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_mode (id integer)", ConsoleTab.Mode.AUTO);
      // A call that ran without the console's mode, in auto-commit.
      lane.submit(link -> value(link, "SELECT 1")).get(20, TimeUnit.SECONDS);
      var inserted = statement(lane, "INSERT INTO seam_mode VALUES (1)", ConsoleTab.Mode.MANUAL);
      assertEquals(Transaction.OPEN, inserted.portion().transaction());
      assertEquals(false, inserted.portion().autoCommit());
      assertEquals(0L, count(watcher, "seam_mode"));
      end(lane, ConsoleTab.ROLLBACK);
      assertEquals(0L, count(watcher, "seam_mode"));
    } finally {
      statement(lane, "DROP TABLE seam_mode", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /** The second review's fourth finding: a COMMIT that fails at a deferred constraint. */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"POSTGRESQL"})
  void aCommitThatFailsAtADeferredConstraintEndsTheTransactionUncommitted(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_child",
          "DROP TABLE IF EXISTS seam_parent",
          "CREATE TABLE seam_parent (id integer PRIMARY KEY)",
          "CREATE TABLE seam_child (parent integer REFERENCES seam_parent"
              + " DEFERRABLE INITIALLY DEFERRED)")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      statement(lane, "INSERT INTO seam_child VALUES (7)", ConsoleTab.Mode.MANUAL);
      var ended = end(lane, ConsoleTab.COMMIT);
      assertEquals(Transaction.OPEN, ended.before());
      var failure = (Failure) ended.portion().results().getLast();
      assertEquals(Failure.Kind.CONSTRAINT, failure.kind());
      assertEquals(Transaction.NONE, ended.portion().transaction());
      assertEquals(0L, count(watcher, "seam_child"));
    } finally {
      statement(lane, "DROP TABLE seam_child", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_parent", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /** Third review, 2 and design 1: after a failed procedure, Manual is made sure of again. */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void manualIsMadeSureOfAfterAFailedProcedureTurnedAutoCommitOn(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_after",
          "CREATE TABLE seam_after (id integer)",
          "DROP PROCEDURE IF EXISTS seam_on_and_fail",
          "CREATE PROCEDURE seam_on_and_fail() BEGIN SET autocommit = 1;"
              + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'after'; END")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      statement(lane, "INSERT INTO seam_after VALUES (1)", ConsoleTab.Mode.MANUAL);
      var failed = statement(lane, "CALL seam_on_and_fail()", ConsoleTab.Mode.MANUAL);
      assertEquals(null, failed.portion().autoCommit());
      // The next statement in Manual: the server says auto-commit is on, nothing open, so the
      // session takes Manual again before it is sent.
      var next = statement(lane, "INSERT INTO seam_after VALUES (2)", ConsoleTab.Mode.MANUAL);
      assertEquals(Transaction.OPEN, next.portion().transaction());
      assertEquals(false, next.portion().autoCommit());
      assertEquals(1L, count(watcher, "seam_after"), "the first, committed as it was turned on");
      end(lane, ConsoleTab.ROLLBACK);
      assertEquals(1L, count(watcher, "seam_after"));
    } finally {
      statement(lane, "DROP PROCEDURE seam_on_and_fail", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_after", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * Third review, 3a: an UPDATE in Auto whose answer the console dropped took effect, and a
   * ROLLBACK after it succeeds all the same; so the console must not say Rolled back.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aChangeWhoseAnswerWasDroppedTookEffectAndARollbackSucceedsAnyway(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_dropped", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_dropped (id integer)", ConsoleTab.Mode.AUTO);
      var held = new AtomicReference<Servers.Run>();
      var update = lane.submit(link -> ConsoleTab.start(
          link,
          "INSERT INTO seam_dropped VALUES (1)",
          ConsoleTab.Mode.AUTO,
          false,
          held,
          new AtomicReference<>()));
      eventually(update::isDone);
      update.cancel(true); // too late, as the console's Cancel or close can be: its answer is lost
      var rolledBack = end(lane, ConsoleTab.ROLLBACK);
      assertTrue(rolledBack.portion().results().stream().noneMatch(r -> r instanceof Failure));
      assertEquals(1L, count(watcher, "seam_dropped"), "the change stands");
    } finally {
      statement(lane, "DROP TABLE seam_dropped", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * Third review, 4 and design 2: a procedure that commits and gives more than a portion of rows
   * is read to its end in the console's one call, so it is never left open between portions.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aProcedureThatCommitsAndGivesManyRowsIsReadToItsEnd(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_rows",
          "CREATE TABLE seam_rows (id integer)",
          "DROP PROCEDURE IF EXISTS seam_commit_rows",
          "CREATE PROCEDURE seam_commit_rows() BEGIN START TRANSACTION;"
              + " INSERT INTO seam_rows VALUES (1); COMMIT;"
              + " WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 450)"
              + " SELECT i FROM n; END")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      var ran = statement(lane, "CALL seam_commit_rows()", ConsoleTab.Mode.AUTO);
      assertFalse(ran.portion().more(), "read to its end in one call");
      assertEquals(450, rows(ran.portion()).size());
      assertEquals(Transaction.NONE, ran.portion().transaction());
      assertEquals(1L, count(watcher, "seam_rows"));
    } finally {
      statement(lane, "DROP PROCEDURE seam_commit_rows", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_rows", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  // PostgreSQL streams such a statement in a transaction core opens, which a close must not undo.
  @Test
  void closingKeepsWhatAStatementChangedThoughItsRowsWereNotAllRead() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS kept",
          "CREATE TABLE kept (id integer, done boolean NOT NULL DEFAULT false)",
          "INSERT INTO kept SELECT n FROM generate_series(1, 1000) n")) {
        var setup = watcher.run(sql);
        setup.next(1);
        setup.close();
      }
      var read = lane.submit(link -> rows(link.run(
                  "WITH changed AS (UPDATE kept SET done = true RETURNING id) SELECT id FROM changed")
              .next(200))
          .size());
      assertEquals(200, read.get(20, TimeUnit.SECONDS));
      lane.close(); // as closing the console's tab does: the statement's rows are never read on
      eventually(() ->
          ((Number) value(watcher, "SELECT count(*) FROM kept WHERE done")).longValue() == 1000);
    } finally {
      lane.close();
      watcher.close();
    }
  }

  /**
   * A plain read by its first word whose function writes, given up with rows left, in Auto: what
   * the server keeps of what it did differs by provider, which the console's footer says.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"POSTGRESQL", "MARIADB"}) // MySQL's test user may not create such a function
  void aReadWhoseFunctionWritesGivenUpKeepsWhatTheServerKeeps(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : pg
          ? List.of(
              "DROP TABLE IF EXISTS seam_noted",
              "CREATE TABLE seam_noted (id integer)",
              "CREATE OR REPLACE FUNCTION seam_note(n integer) RETURNS integer AS"
                  + " 'INSERT INTO seam_noted VALUES (n) RETURNING id' LANGUAGE sql")
          : List.of(
              "DROP TABLE IF EXISTS seam_noted",
              "CREATE TABLE seam_noted (id integer)",
              "DROP FUNCTION IF EXISTS seam_note",
              "CREATE FUNCTION seam_note(n integer) RETURNS integer DETERMINISTIC MODIFIES SQL DATA"
                  + " BEGIN INSERT INTO seam_noted VALUES (n); RETURN n; END")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      var many = pg
          ? "SELECT seam_note(n) FROM generate_series(1, 1000) n"
          : "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 1000)"
              + " SELECT seam_note(i) FROM n";
      var gaveUp = lane.submit(link -> {
            var run = link.run(many);
            assertTrue(run.next(200).more());
            return run.close();
          })
          .get(30, TimeUnit.SECONDS);
      assertTrue(gaveUp.isEmpty(), gaveUp.toString());
      long kept = count(watcher, "seam_noted");
      if (pg) {
        // Its own transaction, committed as it was given up: what it did until then, no more.
        assertTrue(kept > 200 && kept < 1000, "kept " + kept);
      } else {
        // One statement, stopped or finished: all of it or none, on a table with transactions.
        assertTrue(kept == 0 || kept == 1000, "kept " + kept);
      }
    } finally {
      statement(lane, "DROP TABLE seam_noted", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }
}

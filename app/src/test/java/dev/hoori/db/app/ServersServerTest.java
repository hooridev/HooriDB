package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Plan;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.sql.Statements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The console's seam to real servers: {@link Servers#SERVER}'s links on lanes, as a console uses
 * them, against core's test servers. Skipped without Docker.
 */
class ServersServerTest {

  // 500 rows, in a form all three servers take.
  static final String MANY =
      "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 500) SELECT i FROM n";

  static Servers.Link open(TestServer server) throws Exception {
    return Servers.SERVER.open(
        server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10));
  }

  /** A lane of a console, open on the server's database or another. */
  static Lane lane(TestServer server, String database) throws Exception {
    var lane = new Lane(e -> {});
    lane.open(
        Servers.SERVER,
        server.profile().withDatabase(database),
        TestServer.password(),
        server.driver(),
        Duration.ofSeconds(10));
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
    Servers.Run run;
    try {
      run = link.run(sql);
    } catch (QueryException e) {
      throw new AssertionError(e);
    }
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

  /** The number of the lane's session on the server, as other sessions see it. */
  static long session(Lane lane, Provider provider) throws Exception {
    var sql =
        provider == Provider.POSTGRESQL ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()";
    return ((Number) lane.submit(link -> value(link, sql)).get(30, TimeUnit.SECONDS)).longValue();
  }

  /**
   * Waits until the session sleeps in a statement of {@link #sleep} on the server, as the
   * watcher's session sees it: past the driver's BEGIN, which PostgreSQL also reports as active.
   */
  static void sleeping(Servers.Link watcher, Provider provider, long session)
      throws InterruptedException {
    var sql = provider == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep' AND pid = " + session
        : "SELECT count(*) FROM information_schema.processlist WHERE state = 'User sleep' AND id = "
            + session;
    eventually(() -> ((Number) value(watcher, sql)).longValue() == 1);
  }

  static String database(Provider provider) {
    return provider == Provider.POSTGRESQL ? "SELECT current_database()" : "SELECT DATABASE()";
  }

  /**
   * A read-only profile's link, as the tree's, a console's and the planner's are: the server
   * refuses a write, and the link does not send what would turn read-only off.
   */
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aReadOnlyLinkWritesNothing(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var link = Servers.SERVER.open(
        server.profile().withReadOnly(true),
        TestServer.password(),
        server.driver(),
        Duration.ofSeconds(10));
    try {
      var back =
          provider == Provider.POSTGRESQL ? "RESET ALL" : "SET SESSION TRANSACTION READ WRITE";
      var unsent = (Failure) link.run(back).next(10).results().getLast();
      assertTrue(unsent.message().startsWith("The connection is read-only"), unsent.message());
      var write = (Failure)
          link.run("CREATE TABLE never_made (id integer)").next(10).results().getLast();
      assertEquals("25006", write.state(), write.toString()); // the server's own refusal
      assertTrue(link.estimate(TestServer.DATABASE, "SELECT * FROM customer").isPresent());
    } finally {
      link.close();
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aLinksMonitorReadsTheServerOnThatLink(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var link = open(server);
    try {
      var monitor = link.monitor();
      var sessions = monitor.sessions();
      assertEquals(1, sessions.stream().filter(ServerSession::own).count(), sessions.toString());
      assertFalse(monitor.variables().isEmpty());
      assertFalse(monitor.status().isEmpty());
      switch (provider) {
        case POSTGRESQL -> assertTrue(monitor.locks().orElseThrow().isEmpty());
        case MONGODB -> assertTrue(monitor.locks().isEmpty());
        // Who blocks whom takes a privilege the test's user lacks, which the tab says.
        case MARIADB, MYSQL ->
          assertEquals(
              QueryException.Failure.ACCESS_DENIED,
              assertThrows(QueryException.class, monitor::locks).failure());
      }
    } finally {
      link.close();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelledStatementLeavesTheConnectionToTheNext(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      long id = session(lane, provider);
      long started = System.nanoTime();
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      sleeping(watcher, provider, id);
      slow.cancel(true); // as Lane.await does when the console's work is cancelled
      var next = lane.submit(link -> value(link, "SELECT 7")).get(20, TimeUnit.SECONDS);
      assertEquals(7L, ((Number) next).longValue());
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20));
    } finally {
      lane.close();
      watcher.close();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
      long id = session(lane, provider);
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      var queued = lane.submit(link -> {
        link.use(TestServer.NON_ASCII_DATABASE);
        return null;
      });
      var watcher = open(server);
      try {
        sleeping(watcher, provider, id);
      } finally {
        watcher.close();
      }
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
      sleeping(watcher, provider, id);
      assertEquals(1L, ((Number) value(watcher, sessions)).longValue());
      lane.close(); // as closing the console's tab does
      eventually(() -> ((Number) value(watcher, sessions)).longValue() == 0);
    } finally {
      lane.close();
      watcher.close();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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

  /** A statement as the console runs it on its lane, read to its end. */
  static Session.Ran statement(Lane lane, String sql, ConsoleTab.Mode mode) throws Exception {
    return statement(lane, sql, mode, false);
  }

  /**
   * A statement as the console runs it: the console's own unit on its lane, which gives up what is
   * still open first and reads the status fresh; lazily where asked, as a plain read in Auto.
   */
  static Session.Ran statement(Lane lane, String sql, ConsoleTab.Mode mode, boolean lazy)
      throws Exception {
    return lane.submit(link -> unit(
            link,
            l -> Session.start(
                l,
                new Session.Send(sql, null, mode, lazy, ConsoleTab.KEPT, null, null),
                new AtomicReference<>())))
        .get(20, TimeUnit.SECONDS);
  }

  /**
   * A read the console reads whole, in Manual, is not sent where the planner estimates more rows
   * than it keeps; the transaction stays open with what it holds. A read the server has no plan
   * for is sent and fails as itself.
   */
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aLargeReadInManualIsEstimatedFirst(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var planner = lane(server, TestServer.DATABASE); // the tree's, beside the session
    Lane.Call<Portion> drop =
        link -> link.run("DROP TABLE IF EXISTS seam_large").next(10);
    try {
      lane.submit(drop).get(20, TimeUnit.SECONDS);
      lane.submit(link -> link.run("CREATE TABLE seam_large (id integer)").next(10))
          .get(20, TimeUnit.SECONDS);
      statement(lane, "INSERT INTO seam_large VALUES (1), (2), (3)", ConsoleTab.Mode.AUTO);
      // Statistics as they are, which a table just made may not have yet.
      statement(
          lane,
          provider == Provider.POSTGRESQL ? "ANALYZE seam_large" : "ANALYZE TABLE seam_large",
          ConsoleTab.Mode.AUTO);
      statement(lane, "INSERT INTO seam_large VALUES (4)", ConsoleTab.Mode.MANUAL);
      var asked = assertThrows(
          ExecutionException.class,
          () -> lane.submit(link -> unit(
                  link,
                  l -> Session.start(
                      l,
                      new Session.Send(
                          "SELECT * FROM seam_large a, seam_large b",
                          null,
                          ConsoleTab.Mode.MANUAL,
                          false,
                          1,
                          planner,
                          TestServer.DATABASE),
                      new AtomicReference<>())))
              .get(20, TimeUnit.SECONDS));
      var tooMany = assertInstanceOf(Session.TooMany.class, asked.getCause());
      assertTrue(tooMany.rows > 1, String.valueOf(tooMany.rows));
      assertEquals(Transaction.OPEN, tooMany.status.transaction());
      var counted = statement(lane, "SELECT count(*) FROM seam_large", ConsoleTab.Mode.MANUAL);
      assertEquals(4L, ((Number) rows(counted.portion()).getFirst().getFirst()).longValue());
      var missing = lane.submit(link -> unit(
              link,
              l -> Session.start(
                  l,
                  new Session.Send(
                      "SELECT * FROM seam_missing",
                      null,
                      ConsoleTab.Mode.MANUAL,
                      false,
                      1,
                      planner,
                      TestServer.DATABASE),
                  new AtomicReference<>())))
          .get(20, TimeUnit.SECONDS);
      assertInstanceOf(Failure.class, missing.portion().results().getLast());
    } finally {
      lane.submit(link -> {
            link.run("ROLLBACK").next(1);
            link.manual(false);
            return drop.call(link);
          })
          .get(20, TimeUnit.SECONDS);
      lane.close();
      planner.close();
    }
  }

  /**
   * The session's own transaction holds a lock the plan on the other connection waits for: the
   * estimate gives up in time, and the read runs on the session without a question.
   */
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aLockOfTheSessionDoesNotHoldItsRead(Provider provider) throws Exception {
    boolean pg = provider == Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var planner = lane(server, TestServer.DATABASE);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_locked", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_locked (id integer)", ConsoleTab.Mode.AUTO);
      statement(
          lane,
          pg ? "ALTER TABLE seam_locked ADD COLUMN note integer" : "LOCK TABLES seam_locked WRITE",
          ConsoleTab.Mode.MANUAL);
      long began = System.nanoTime();
      var read = lane.submit(link -> unit(
              link,
              l -> Session.start(
                  l,
                  new Session.Send(
                      "SELECT * FROM seam_locked",
                      null,
                      ConsoleTab.Mode.MANUAL,
                      false,
                      1,
                      planner,
                      TestServer.DATABASE),
                  new AtomicReference<>())))
          .get(30, TimeUnit.SECONDS);
      var took = Duration.ofNanos(System.nanoTime() - began);
      assertTrue(took.compareTo(Plan.LIMIT.plusSeconds(5)) < 0, took.toString());
      assertInstanceOf(Result.Rows.class, read.portion().results().getFirst());
    } finally {
      lane.submit(link -> {
            link.run(pg ? "ROLLBACK" : "UNLOCK TABLES").next(1);
            link.manual(false);
            return link.run("DROP TABLE seam_locked").next(1);
          })
          .get(20, TimeUnit.SECONDS);
      lane.close();
      planner.close();
    }
  }

  /**
   * The estimates' connection on MariaDB and MySQL, opened as the profile is, on no database,
   * plans on the console's database and stays there, as nothing else runs on it.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void thePlannersConnectionPlansOnTheConsolesDatabase(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var planner = lane(server, "");
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_switch",
          "CREATE TABLE seam_switch (id integer)",
          "INSERT INTO seam_switch VALUES (1), (2), (3)",
          "ANALYZE TABLE seam_switch")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      assertEquals(
          null, planner.submit(l -> value(l, "SELECT DATABASE()")).get(20, TimeUnit.SECONDS));
      var asked = assertThrows(
          ExecutionException.class,
          () -> lane.submit(link -> unit(
                  link,
                  l -> Session.start(
                      l,
                      new Session.Send(
                          "SELECT * FROM seam_switch a, seam_switch b",
                          null,
                          ConsoleTab.Mode.MANUAL,
                          false,
                          1,
                          planner,
                          TestServer.DATABASE),
                      new AtomicReference<>())))
              .get(20, TimeUnit.SECONDS));
      assertInstanceOf(Session.TooMany.class, asked.getCause());
      assertEquals(
          TestServer.DATABASE,
          planner.submit(l -> value(l, "SELECT DATABASE()")).get(20, TimeUnit.SECONDS));
    } finally {
      statement(lane, "DROP TABLE seam_switch", ConsoleTab.Mode.AUTO);
      lane.close();
      planner.close();
    }
  }

  /** A switch of auto-commit as the console's unit does it. */
  static Session.Switched mode(Lane lane, boolean manual, boolean empty) throws Exception {
    return lane.submit(link -> unit(link, l -> Session.setMode(l, manual, empty)))
        .get(20, TimeUnit.SECONDS);
  }

  static Session.Ended end(Lane lane, String sql) throws Exception {
    return lane.submit(link -> unit(link, l -> Session.finish(l, sql, (a, b, c) -> {})))
        .get(20, TimeUnit.SECONDS);
  }

  /** The console's unit around a call, which fails where giving up what was left open fails. */
  static <T> T unit(Servers.Link link, Lane.Call<T> call) throws Exception {
    var told = new ArrayList<String>();
    var value = Session.after(link, (sql, what, failure) -> told.add(what), "", call);
    assertEquals(List.of(), told, "giving up what was left open");
    return value;
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void commitAfterACancelGoesOnlyIntoATransactionTheServerSaysIsOpen(Provider provider)
      throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      long id = session(lane, provider);
      statement(lane, "DROP TABLE IF EXISTS seam_cancel", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_cancel (id integer)", ConsoleTab.Mode.AUTO);
      mode(lane, true, false);
      statement(lane, "INSERT INTO seam_cancel VALUES (1)", ConsoleTab.Mode.MANUAL);
      var slow = lane.submit(link -> link.run(sleep(provider, 30)).next(10));
      try {
        sleeping(watcher, provider, id);
      } finally {
        slow.cancel(true); // as the console's Cancel does; else the cleanup waits behind it
      }
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
      mode(lane, false, false);
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
      mode(lane, true, false);
      statement(lane, "INSERT INTO seam_chain VALUES (1)", ConsoleTab.Mode.MANUAL);
      var committed = end(lane, ConsoleTab.COMMIT);
      assertEquals(Transaction.OPEN, committed.before());
      assertEquals(Transaction.OPEN, committed.portion().transaction(), "chained at once");
      assertEquals(1L, count(watcher, "seam_chain"));
      // Not where it is not known to be empty; where it is, auto-commit goes on and ends it.
      var refused = mode(lane, false, false);
      assertFalse(refused.done());
      var switched = mode(lane, false, true);
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
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
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aChangeWhoseAnswerWasDroppedTookEffectAndARollbackSucceedsAnyway(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_dropped", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_dropped (id integer)", ConsoleTab.Mode.AUTO);
      var held = new AtomicReference<Servers.Run>();
      var update = lane.submit(link -> Session.after(
          link,
          (sql, what, failure) -> {},
          "",
          l -> Session.start(
              l,
              new Session.Send(
                  "INSERT INTO seam_dropped VALUES (1)",
                  null,
                  ConsoleTab.Mode.AUTO,
                  false,
                  ConsoleTab.KEPT,
                  null,
                  null),
              held)));
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
   * the server keeps of what it did differs by provider, so the console's footer claims nothing.
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
      assertTrue(gaveUp.failure().isEmpty(), gaveUp.toString());
      long kept = count(watcher, "seam_noted");
      if (pg) {
        // Its own transaction, committed as it was given up: what it did until then, no more.
        assertTrue(kept > 200 && kept < 1000, "kept " + kept);
      } else {
        // One statement, stopped or finished: all of it or none, on a table with transactions.
        assertTrue(kept == 0 || kept == 1000, "kept " + kept);
      }
    } finally {
      statement(lane, "DROP FUNCTION seam_note", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_noted", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /** What a read in Manual wrote through a function stays, once the user answers Commit. */
  @Test
  void aReadsWritesInManualStayWhenCommitted() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_read_wrote",
          "CREATE TABLE seam_read_wrote (id integer)",
          "CREATE OR REPLACE FUNCTION seam_read_note(n integer) RETURNS integer AS"
              + " 'INSERT INTO seam_read_wrote VALUES (n) RETURNING id' LANGUAGE sql")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      var sql = "SELECT seam_read_note(7)";
      assertTrue(ConsoleTab.reads(sql, Provider.POSTGRESQL), "a plain read by its first word");
      var read = statement(lane, sql, ConsoleTab.Mode.MANUAL, true);
      assertEquals(Transaction.OPEN, read.portion().transaction());
      assertEquals(0L, count(watcher, "seam_read_wrote"));
      var committed = end(lane, ConsoleTab.COMMIT);
      assertEquals(Transaction.OPEN, committed.before());
      assertEquals(Transaction.NONE, committed.portion().transaction());
      assertEquals(1L, count(watcher, "seam_read_wrote"));
    } finally {
      mode(lane, false, false);
      statement(lane, "DROP FUNCTION seam_read_note", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_read_wrote", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  // The fourth review: the console's unit, run in the true order of the console's calls.

  /**
   * F1: PostgreSQL, a read left with rows unread in Auto holds core's own transaction with
   * auto-commit off; the switch to Manual gives it up first and turns auto-commit off itself, so
   * that the UPDATE after it stays in the user's transaction.
   */
  @Test
  void aReadLeftOpenInAutoDoesNotPassForManual() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      statement(lane, "DROP TABLE IF EXISTS seam_f1", ConsoleTab.Mode.AUTO);
      statement(lane, "CREATE TABLE seam_f1 (id integer)", ConsoleTab.Mode.AUTO);
      var read =
          statement(lane, "SELECT n FROM generate_series(1, 1000) n", ConsoleTab.Mode.AUTO, true);
      assertTrue(read.lazy() && read.portion().more(), "left with rows unread");
      var switched = mode(lane, true, false);
      assertTrue(switched.done());
      assertEquals(new Status(Transaction.NONE, false), switched.now());
      statement(lane, "INSERT INTO seam_f1 VALUES (1)", ConsoleTab.Mode.MANUAL);
      assertEquals(0L, count(watcher, "seam_f1"), "not committed by itself");
      end(lane, ConsoleTab.ROLLBACK);
      assertEquals(0L, count(watcher, "seam_f1"));
    } finally {
      mode(lane, false, false);
      statement(lane, "DROP TABLE seam_f1", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * F2: in Manual a read whose function writes, with more rows than a portion, is read whole in its
   * one call, also where the console asked for it lazily, and the transaction it leaves is the
   * server's word.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"POSTGRESQL", "MARIADB"}) // MySQL's test user may not create such a function
  void aLongReadInManualIsReadWhole(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : pg
          ? List.of(
              "DROP TABLE IF EXISTS seam_f2",
              "CREATE TABLE seam_f2 (id integer)",
              "CREATE OR REPLACE FUNCTION seam_f2_note(n integer) RETURNS integer AS"
                  + " 'INSERT INTO seam_f2 VALUES (n) RETURNING id' LANGUAGE sql")
          : List.of(
              "DROP TABLE IF EXISTS seam_f2",
              "CREATE TABLE seam_f2 (id integer)",
              "DROP FUNCTION IF EXISTS seam_f2_note",
              "CREATE FUNCTION seam_f2_note(n integer) RETURNS integer DETERMINISTIC MODIFIES SQL"
                  + " DATA BEGIN INSERT INTO seam_f2 VALUES (n); RETURN n; END")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      var many = pg
          ? "SELECT seam_f2_note(n) FROM generate_series(1, 1000) n"
          : "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 1000)"
              + " SELECT seam_f2_note(i) FROM n";
      mode(lane, true, false);
      var read = statement(lane, many, ConsoleTab.Mode.MANUAL, true);
      assertFalse(read.lazy());
      assertFalse(read.portion().more(), "read to its end");
      assertEquals(
          1000, ((Result.Rows) read.portion().results().getFirst()).rows().size());
      assertEquals(Transaction.OPEN, read.portion().transaction(), "the server's word");
      assertEquals(0L, count(watcher, "seam_f2"), "in the user's transaction");
      end(lane, ConsoleTab.ROLLBACK);
      assertEquals(0L, count(watcher, "seam_f2"));
    } finally {
      mode(lane, false, false);
      statement(lane, "DROP FUNCTION seam_f2_note", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE seam_f2", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * A rollback that could not undo a MyISAM table's change says so, in the server's warning, on
   * MariaDB; MySQL with consistent GTIDs, as its image has them, takes no such change in one.
   */
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aRollbackOverATableWithoutTransactionsWarns(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_plain, seam_kept",
          "CREATE TABLE seam_plain (id integer) ENGINE=MyISAM",
          "CREATE TABLE seam_kept (id integer) ENGINE=InnoDB")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      mode(lane, true, false);
      statement(lane, "INSERT INTO seam_kept VALUES (1)", ConsoleTab.Mode.MANUAL);
      var plain = statement(lane, "INSERT INTO seam_plain VALUES (1)", ConsoleTab.Mode.MANUAL);
      // MySQL's image keeps GTIDs consistent, and refuses such a change inside a transaction.
      boolean refused =
          plain.portion().results().getFirst() instanceof Failure f && f.code() == 1785;
      assertEquals(
          provider == Provider.MYSQL, refused, plain.portion().results().toString());
      var ended = end(lane, ConsoleTab.ROLLBACK);
      assertEquals(
          !refused,
          ended.portion().messages().stream().anyMatch(m -> m.code() == ConsoleTab.NOT_ROLLED_BACK),
          ended.portion().messages().toString());
      assertEquals(0L, count(watcher, "seam_kept"));
      assertEquals(refused ? 0L : 1L, count(watcher, "seam_plain"), "MyISAM keeps what it took");
    } finally {
      mode(lane, false, false);
      statement(lane, "DROP TABLE seam_plain, seam_kept", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  // The fifth review: the console itself, on its cancellable path, against a real server.

  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path dir;

  /**
   * The app with one connection to the real server, whose console runs as it does for the user:
   * the tree and the console open their connections through {@link Servers#SERVER}, with the
   * server's own driver and password.
   */
  CatalogTreeTest app(TestServer server, List<App<State>> apps) throws Exception {
    var c = new CatalogTreeTest();
    c.dir = dir;
    c.setUp();
    Servers real = (profile, password, driver, timeout) ->
        Servers.SERVER.open(profile, TestServer.password(), server.driver(), timeout);
    var profile = server.profile().withName("real");
    var app = c.app(
        real,
        () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"),
        profile);
    c.ready(app, profile);
    c.run(
        app,
        CatalogTreeTest.START
            + CatalogTreeTest.menu("real", "New console")
            + CatalogTreeTest.SETTLE);
    apps.add(app);
    return c;
  }

  /**
   * The first finding: PostgreSQL, Auto, Run all of a read whose function breaks a deferred
   * constraint, then an INSERT. Giving up the read commits its own transaction, which fails: the
   * INSERT is not sent, and the app has the failure.
   */
  @Test
  void aRunAllStopsWhereAReadsCommitFailed() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    var apps = new ArrayList<App<State>>();
    CatalogTreeTest c = null;
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_once, seam_after",
          "CREATE TABLE seam_once (id integer,"
              + " CONSTRAINT seam_once_id UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)",
          "CREATE TABLE seam_after (id integer)",
          "CREATE OR REPLACE FUNCTION seam_twice(n integer) RETURNS integer AS"
              + " 'INSERT INTO seam_once VALUES (1) RETURNING id' LANGUAGE sql")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      c = app(server, apps);
      var app = apps.getFirst();
      c.run(
          app,
          ConsoleTest.type("SELECT seam_twice(n) FROM generate_series(1, 1000) n;\n"
                  + "INSERT INTO seam_after VALUES (1);")
              + ConsoleTest.RUN_ALL);
      assertEquals(0L, count(watcher, "seam_after"), "not sent after the failed commit");
      assertEquals(0L, count(watcher, "seam_once"), "rolled back");
      assertTrue(
          ConsoleTransactions.noticed(app).contains(Session.ENDED),
          ConsoleTransactions.noticed(app));
      assertTrue(app.state().notices.getFirst().failure());
    } finally {
      if (c != null) {
        c.tearDown();
      }
      statement(lane, "DROP FUNCTION IF EXISTS seam_twice", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE IF EXISTS seam_once, seam_after", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * The third finding: MariaDB, Manual, a change of an InnoDB and of a MyISAM table, then the tab
   * closed and Rollback answered: the server says the rollback was partial, and the close stops.
   */
  @Test
  void aRollbackInPartAnsweredAtCloseKeepsTheConsole() throws Exception {
    var server = TestServer.of(Provider.MARIADB);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    var apps = new ArrayList<App<State>>();
    CatalogTreeTest c = null;
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_plain, seam_kept",
          "CREATE TABLE seam_plain (id integer) ENGINE=MyISAM",
          "CREATE TABLE seam_kept (id integer) ENGINE=InnoDB")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      c = app(server, apps);
      var app = apps.getFirst();
      c.run(
          app,
          ConsoleTransactions.MANUAL
              + ConsoleTest.type(
                  "INSERT INTO seam_kept VALUES (1);\nINSERT INTO seam_plain VALUES (1);")
              + ConsoleTest.RUN_ALL + "press #close-0\nframe\n" + ConsoleTransactions.OPENED
              + "press #answer-rollback\nframe\n" + ConsoleTransactions.OPENED
              + CatalogTreeTest.SETTLE);
      assertEquals(1, app.state().consoles(null).size(), "the close stopped");
      assertTrue(
          ConsoleTransactions.noticed(app).contains(ConsoleTab.PARTLY),
          ConsoleTransactions.noticed(app));
      assertEquals(0L, count(watcher, "seam_kept"));
      assertEquals(1L, count(watcher, "seam_plain"), "MyISAM keeps it");
    } finally {
      if (c != null) {
        c.tearDown();
      }
      statement(lane, "DROP TABLE IF EXISTS seam_plain, seam_kept", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * The sixth review's fourth finding: a read left open, the server stops answering, the session
   * closes: the thread that closes it ends within its bound, the connection given up, and says
   * so. The process waits for that thread, so the bound is the process's.
   */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("dev.hoori.db.core.TestServer#sql")
  void closingASessionWhoseServerStoppedAnsweringEnds(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var _ = new dev.hoori.db.core.execution.ShortEnding();
        var relay = new dev.hoori.db.core.query.Relay(server.host(), server.port())) {
      var lane = new Lane(e -> {});
      lane.open(
          Servers.SERVER,
          server.profile().withHost(dev.hoori.db.core.query.Relay.HOST).withPort(relay.port()),
          TestServer.password(),
          server.driver(),
          Duration.ofSeconds(10));
      lane.submit(link -> link).get(30, TimeUnit.SECONDS);
      var many = provider == Provider.POSTGRESQL
          ? "SELECT n FROM generate_series(1, 100000) n"
          : "WITH d (n) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
              + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
              + " UNION ALL SELECT 8 UNION ALL SELECT 9)"
              + " SELECT a.n + 10 * b.n + 100 * c.n + 1000 * e.n + 10000 * f.n AS n"
              + " FROM d a, d b, d c, d e, d f";
      var read = statement(lane, many, ConsoleTab.Mode.AUTO, true);
      assertTrue(read.portion().more(), "left open");
      relay.freeze();
      var ended = new java.util.concurrent.CompletableFuture<Abandoned>();
      var closing = lane.close(ended::complete);
      var bound = dev.hoori.db.core.execution.ShortEnding.CLOSES;
      assertTrue(closing.join(bound), "the closing thread ended within " + bound);
      var failure = ended.getNow(null).failure().orElseThrow();
      assertEquals(Failure.Kind.CONNECTION_LOST, failure.kind());
      assertTrue(
          failure.message().contains(dev.hoori.db.core.execution.ShortEnding.CUT_OFF),
          failure.message());
    }
  }

  /**
   * The sixth review's first finding, (a): PostgreSQL, Auto, a read whose function breaks a
   * deferred constraint, read as the grid scrolls; its last portion commits its own transaction,
   * which fails, and the tab closes before the console takes it: the app has it.
   */
  @Test
  void aReadsLastCommitFailingAsItsTabClosesIsANotice() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    var apps = new ArrayList<App<State>>();
    CatalogTreeTest c = null;
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_once",
          "CREATE TABLE seam_once (id integer,"
              + " CONSTRAINT seam_once_id UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)",
          "CREATE OR REPLACE FUNCTION seam_twice(n integer) RETURNS integer AS"
              + " 'INSERT INTO seam_once VALUES (1) RETURNING id' LANGUAGE sql")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      c = app(server, apps);
      var app = apps.getFirst();
      var tab = app.state().consoles(null).getFirst();
      c.run(
          app,
          ConsoleTest.type("SELECT seam_twice(n) FROM generate_series(1, 250) n")
              + ConsoleTest.RUN
              + "scroll #grid row 180\nframe\nframe\n");
      eventually(tab::outcomeWaiting);
      c.run(app, "press #close-0\nframe\n" + ConsoleTransactions.OPENED + CatalogTreeTest.SETTLE);
      assertTrue(app.state().consoles(null).isEmpty(), "nothing asked");
      assertTrue(
          ConsoleTransactions.noticed(app).contains("duplicate key"),
          ConsoleTransactions.noticed(app));
      assertEquals(0L, count(watcher, "seam_once"), "rolled back");
    } finally {
      if (c != null) {
        c.tearDown();
      }
      statement(lane, "DROP FUNCTION IF EXISTS seam_twice", ConsoleTab.Mode.AUTO);
      statement(lane, "DROP TABLE IF EXISTS seam_once", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }

  /**
   * The sixth review's first finding, (b): MariaDB, Manual, a typed ROLLBACK that could not undo
   * a MyISAM table's change, cancelled before the console takes its answer: the app has it.
   */
  @Test
  void aTypedRollbackInPartCancelledIsANotice() throws Exception {
    var server = TestServer.of(Provider.MARIADB);
    var lane = lane(server, TestServer.DATABASE);
    var watcher = open(server);
    var apps = new ArrayList<App<State>>();
    CatalogTreeTest c = null;
    try {
      for (var sql : List.of(
          "DROP TABLE IF EXISTS seam_plain, seam_kept",
          "CREATE TABLE seam_plain (id integer) ENGINE=MyISAM",
          "CREATE TABLE seam_kept (id integer) ENGINE=InnoDB")) {
        statement(lane, sql, ConsoleTab.Mode.AUTO);
      }
      c = app(server, apps);
      var app = apps.getFirst();
      var tab = app.state().consoles(null).getFirst();
      c.run(
          app,
          ConsoleTransactions.MANUAL
              + ConsoleTest.type(
                  "INSERT INTO seam_kept VALUES (1);\nINSERT INTO seam_plain VALUES (1);")
              + ConsoleTest.RUN_ALL
              + ConsoleTest.type("\nROLLBACK")
              + "key Enter control\nframe\nframe\n");
      eventually(tab::outcomeWaiting);
      c.run(
          app,
          "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
              + CatalogTreeTest.SETTLE);
      assertTrue(
          ConsoleTransactions.noticed(app).contains(ConsoleTab.PARTLY),
          ConsoleTransactions.noticed(app));
      assertEquals(1L, count(watcher, "seam_plain"), "MyISAM keeps it");
    } finally {
      if (c != null) {
        c.tearDown();
      }
      statement(lane, "DROP TABLE IF EXISTS seam_plain, seam_kept", ConsoleTab.Mode.AUTO);
      lane.close();
      watcher.close();
    }
  }
}

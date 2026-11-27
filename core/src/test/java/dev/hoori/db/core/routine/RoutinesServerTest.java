package dev.hoori.db.core.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.catalog.Routine.Kind;
import dev.hoori.db.core.catalog.Routine.Mode;
import dev.hoori.db.core.catalog.Routine.Parameter;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Listing, reading, saving, dropping and calling routines on real servers: in a schema of their
 * own on PostgreSQL, else in the sample database, with names of their own.
 */
class RoutinesServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final String SCHEMA = "routines_test";
  // Users of MariaDB and MySQL that the tests make: one that defines a routine that the test's own
  // user may not name as its definer, and one that is granted a routine alone.
  private static final String OTHER = "'rt_other'@'%'";
  private static final String RUNNER = "'rt_runner'@'%'";
  private static final String USER = "'" + TestServer.USER + "'@'%'";

  private Provider provider;

  @AfterEach
  void dropWhatTheTestMade() throws Exception {
    if (provider == null) {
      return;
    }
    if (provider == Provider.POSTGRESQL) {
      try (var connection = open(provider)) {
        execute(connection, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      }
      return;
    }
    // As root, who may drop a routine of any definer, and the users the tests made.
    try (var admin = admin(provider)) {
      for (var name :
          List.of("rt_add", "rt_fill", "rt_len", "rt_owned", "new_function", "new_procedure")) {
        var named = TestServer.DATABASE + "." + name;
        execute(admin, "DROP FUNCTION IF EXISTS " + named, "DROP PROCEDURE IF EXISTS " + named);
      }
      execute(admin, "DROP USER IF EXISTS " + OTHER, "DROP USER IF EXISTS " + RUNNER);
      if (provider == Provider.MYSQL) {
        execute(admin, "REVOKE IF EXISTS SHOW_ROUTINE ON *.* FROM " + USER);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void routinesComeWithTheirParametersAndOverloadsApart(Provider provider) throws Exception {
    try (var connection = prepared(provider)) {
      var container = container(provider);
      // Of the routines that a test of another class may have left, this test's alone.
      var routines = Catalog.routines(connection, provider, container).stream()
          .filter(r -> r.name().startsWith("rt_"))
          .toList();
      var names = routines.stream().map(Routine::label).toList();
      if (provider == Provider.POSTGRESQL) {
        assertEquals(
            List.of("rt_add(integer, integer)", "rt_add(text)", "rt_fill(integer, OUT integer)"),
            names);
        var add = routines.getFirst();
        assertEquals("a integer, b integer", add.signature());
        assertEquals(Kind.FUNCTION, add.kind());
        assertEquals("integer", add.returns());
        assertEquals(
            List.of(new Parameter("a", "integer", Mode.IN), new Parameter("b", "integer", Mode.IN)),
            add.parameters());
        var fill = routines.getLast();
        assertEquals(Kind.PROCEDURE, fill.kind());
        assertEquals("", fill.returns());
        assertEquals(
            List.of(
                new Parameter("n", "integer", Mode.IN),
                new Parameter("total", "integer", Mode.OUT)),
            fill.parameters());
      } else {
        // MariaDB writes the display width of an integer.
        var integer = provider == Provider.MARIADB ? "int(11)" : "int";
        assertEquals(
            List.of(
                "rt_add(" + integer + ", " + integer + ")",
                "rt_fill(" + integer + ", OUT " + integer + ")"),
            names);
        var add = routines.getFirst();
        assertEquals("", add.signature());
        assertEquals(integer, add.returns());
        var fill = routines.getLast();
        assertEquals(Kind.PROCEDURE, fill.kind());
        assertEquals(
            List.of(
                new Parameter("n", integer, Mode.IN), new Parameter("total", integer, Mode.OUT)),
            fill.parameters());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theSourceIsACreateStatementThatSaveRunsToReplaceTheRoutine(Provider provider)
      throws Exception {
    try (var connection = prepared(provider)) {
      var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
      var source = Catalog.source(connection, provider, add);
      var text = source.statement();
      var replaces = provider != Provider.MYSQL;
      assertTrue(text.startsWith(replaces ? "CREATE OR REPLACE " : "CREATE "), text);
      assertTrue(text.contains("rt_add"), text);
      // MariaDB and MySQL keep the sql_mode it was created under, which Save sets first.
      assertEquals(
          provider == Provider.POSTGRESQL ? 0 : 1, source.session().size(), source.toString());
      var recorded = new CopyOnWriteArrayList<Sent>();
      // A body changed, with a terminator after it, which Save leaves out.
      var changed = text.replace("a + b", "a + b + 1") + ";\n";
      var change = Routines.save(provider, add, source, changed);
      var roles = change.steps().stream().map(Routines.Step::role).toList();
      assertEquals(
          switch (provider) {
            case POSTGRESQL -> List.of(Routines.Role.CREATE);
            case MARIADB -> List.of(Routines.Role.SESSION, Routines.Role.CREATE);
            default ->
              List.of(
                  Routines.Role.SESSION,
                  Routines.Role.TRIAL,
                  Routines.Role.TRIAL_DROP,
                  Routines.Role.DROP_FIRST,
                  Routines.Role.CREATE);
          },
          roles);
      var outcome = Journal.recording(recorded::add, () -> apply(connection, provider, change));
      assertEquals(new Routines.Outcome.Done(), outcome);
      assertEquals(change.statements(), recorded.stream().map(Sent::text).toList());
      assertTrue(recorded.stream().allMatch(s -> s.purpose() == Purpose.DEFINITION));
      assertEquals(List.of("6"), values(call(connection, provider, add, "2", "3")));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSaveThatFailsLeavesTheRoutineAsItWas(Provider provider) throws Exception {
    try (var connection = prepared(provider)) {
      var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
      var source = Catalog.source(connection, provider, add);
      var broken = source.statement().replace("a + b", "a +* b");
      var outcome = apply(connection, provider, Routines.save(provider, add, source, broken));
      var failed = assertInstanceOf(Routines.Outcome.Failed.class, outcome);
      assertEquals(Failure.Kind.INVALID, failed.failure().kind());
      // Where the server drops it first, the trial failed before the drop.
      assertEquals(
          provider == Provider.MYSQL ? Routines.Role.TRIAL : Routines.Role.CREATE,
          failed.step().role());
      assertEquals(Optional.empty(), failed.put());
      assertEquals(List.of("5"), values(call(connection, provider, add, "2", "3")));
      assertTrue(Catalog.routines(connection, provider, container(provider)).stream()
          .noneMatch(r -> r.name().startsWith("hooridb_check_")));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTemplateCreatesARoutineAndDropDropsIt(Provider provider) throws Exception {
    try (var connection = prepared(provider)) {
      var container = container(provider);
      var routines = provider.dialect().routines();
      for (var kind : List.of(Kind.FUNCTION, Kind.PROCEDURE)) {
        var template = routines.template(kind, container);
        var created = apply(connection, provider, Routines.save(provider, null, null, template));
        assertEquals(new Routines.Outcome.Done(), created, template);
      }
      var function = routine(connection, provider, "new_function", Kind.FUNCTION);
      assertEquals(List.of("3"), values(call(connection, provider, function, "1", "2")));
      var procedure = routine(connection, provider, "new_procedure", Kind.PROCEDURE);
      var dropped = apply(connection, provider, Routines.drop(provider, procedure));
      assertEquals(new Routines.Outcome.Done(), dropped);
      assertTrue(Catalog.routines(connection, provider, container).stream()
          .noneMatch(r -> r.name().equals("new_procedure")));
      var gone = assertThrows(
          CatalogException.class, () -> Catalog.source(connection, provider, procedure));
      assertEquals(CatalogException.Failure.NOT_FOUND, gone.failure());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aReadOnlyConnectionSendsNoChange(Provider provider) throws Exception {
    try (var connection = prepared(provider)) {
      var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
      var recorded = new CopyOnWriteArrayList<Sent>();
      var outcome = Journal.recording(
          recorded::add,
          () -> Routines.apply(
              connection, provider, Routines.drop(provider, add), true, new Routines.Applying()));
      assertInstanceOf(Routines.Outcome.Refused.class, outcome);
      assertEquals(List.of(), recorded);
      assertEquals(add, routine(connection, provider, "rt_add", Kind.FUNCTION));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCallBindsItsValuesAndGivesWhatOutParametersGiveBack(Provider provider) throws Exception {
    try (var connection = prepared(provider)) {
      var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
      assertEquals(List.of("5"), values(call(connection, provider, add, "2", "3")));
      assertEquals(
          Arrays.asList((String) null), values(call(connection, provider, add, "2", null)));
      var fill = routine(connection, provider, "rt_fill", Kind.PROCEDURE);
      assertEquals(List.of("10"), values(call(connection, provider, fill, "4")));
      if (provider == Provider.POSTGRESQL) {
        // An overload found by the types of its values.
        var text = Catalog.routines(connection, provider, container(provider)).get(1);
        assertEquals("rt_add(text)", text.label());
        assertEquals(List.of("x!"), values(call(connection, provider, text, "x")));
      }
      // A value is bound, never written into the statement.
      var quote = "it's";
      var calls = provider.dialect().routines().call(add, Arrays.asList(quote, "1"));
      assertTrue(calls.stream().noneMatch(c -> c.sql().contains(quote)), calls.toString());
    }
  }

  // A user who may not name another definer keeps the routine, as its trial fails before
  // anything is dropped. MariaDB, which replaces it in one step, shows the user no source of a
  // routine of another definer anyway.
  @Test
  void aDefinerTheUserMayNotNameLeavesTheRoutineAsItWas() throws Exception {
    var provider = Provider.MYSQL;
    this.provider = provider;
    try (var admin = admin(provider)) {
      execute(
          admin,
          "CREATE USER " + OTHER + " IDENTIFIED BY '" + TestServer.PASSWORD + "'",
          "CREATE DEFINER=" + OTHER + " FUNCTION " + TestServer.DATABASE + ".rt_owned()"
              + " RETURNS INT DETERMINISTIC SQL SECURITY INVOKER RETURN 1",
          // MySQL shows the source of another's routine to a user who may see routines, which
          // the test's own may not.
          "GRANT SHOW_ROUTINE ON *.* TO " + USER);
      try (var connection = open(provider)) {
        var owned = routine(connection, provider, "rt_owned", Kind.FUNCTION);
        var source = Catalog.source(connection, provider, owned);
        assertTrue(source.statement().contains("DEFINER=`rt_other`@`%`"), source.statement());
        var change = Routines.save(
            provider, owned, source, source.statement().replace("RETURN 1", "RETURN 2"));
        var failed =
            assertInstanceOf(Routines.Outcome.Failed.class, apply(connection, provider, change));
        assertEquals(Failure.Kind.ACCESS_DENIED, failed.failure().kind(), failed.toString());
        assertEquals(Routines.Role.TRIAL, failed.step().role());
        assertEquals(List.of("1"), values(call(connection, provider, owned)));
      }
    }
  }

  // Where the CREATE fails once the routine was dropped, and the server does not let the user
  // name the definer it had, it is put back with the user as its definer.
  @Test
  void aRoutinePutBackWithoutItsDefinerWhereTheUserMayNotNameIt() throws Exception {
    var provider = Provider.MYSQL;
    this.provider = provider;
    try (var admin = admin(provider)) {
      execute(
          admin,
          "CREATE USER " + OTHER + " IDENTIFIED BY '" + TestServer.PASSWORD + "'",
          "CREATE DEFINER=" + OTHER + " FUNCTION " + TestServer.DATABASE + ".rt_owned()"
              + " RETURNS INT DETERMINISTIC SQL SECURITY INVOKER RETURN 1",
          // MySQL shows the source of another's routine to a user who may see routines, which
          // the test's own may not.
          "GRANT SHOW_ROUTINE ON *.* TO " + USER);
      try (var connection = open(provider)) {
        var owned = routine(connection, provider, "rt_owned", Kind.FUNCTION);
        var source = Catalog.source(connection, provider, owned);
        // The user's text names no definer, so its trial runs; its CREATE fails all the same.
        var text = source.statement().replace("DEFINER=`rt_other`@`%` ", "").replace("1", "2");
        var change = Routines.save(provider, owned, source, text);
        var create = change.statement(Routines.Role.CREATE).orElseThrow();
        var failure = new Failure(
            Failure.Kind.OTHER, "made to fail", "HY000", 1, 0, "", 0, 0, "", "", "", "");
        var outcome = Routines.apply(
            sql -> sql.equals(create) ? Optional.of(failure) : send(connection, provider, sql),
            change,
            new Routines.Applying());
        var failed = assertInstanceOf(Routines.Outcome.Failed.class, outcome);
        var put =
            assertInstanceOf(Routines.PutBack.WithoutDefiner.class, failed.put().get());
        assertEquals(Failure.Kind.ACCESS_DENIED, put.refused().kind());
        assertEquals(List.of("1"), values(call(connection, provider, owned)));
        var now = Catalog.source(connection, provider, owned).statement();
        assertTrue(now.contains("DEFINER=`" + TestServer.USER + "`@`%`"), now);
      }
    }
  }

  // The body is read by the sql_mode it was created under, here without backslash escapes, also
  // where the session that saves it has another.
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void saveCreatesTheRoutineUnderTheSqlModeItWasCreatedUnder(Provider provider) throws Exception {
    this.provider = provider;
    try (var connection = open(provider)) {
      execute(
          connection,
          "SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_BACKSLASH_ESCAPES')",
          "CREATE FUNCTION rt_len() RETURNS INT DETERMINISTIC RETURN CHAR_LENGTH('a\\b')");
    }
    try (var connection = open(provider)) {
      var len = routine(connection, provider, "rt_len", Kind.FUNCTION);
      assertEquals(List.of("3"), values(call(connection, provider, len)));
      var source = Catalog.source(connection, provider, len);
      assertTrue(source.session().getFirst().contains("NO_BACKSLASH_ESCAPES"), source.toString());
      var text = source.statement().replace("'a\\b'", "'a\\bc'");
      assertEquals(
          new Routines.Outcome.Done(),
          apply(connection, provider, Routines.save(provider, len, source, text)));
      // In a session of its own, whose sql_mode is the server's.
      try (var other = open(provider)) {
        assertEquals(List.of("4"), values(call(other, provider, len)));
      }
    }
  }

  // MySQL's DROP takes the privileges granted on the routine alone with it, as the preview says.
  @Test
  void theDropOfASaveTakesThePrivilegesOnTheRoutineWithIt() throws Exception {
    var provider = Provider.MYSQL;
    try (var connection = prepared(provider);
        var admin = admin(provider)) {
      execute(
          admin,
          "CREATE USER " + RUNNER + " IDENTIFIED BY '" + TestServer.PASSWORD + "'",
          "GRANT EXECUTE ON FUNCTION " + TestServer.DATABASE + ".rt_add TO " + RUNNER);
      assertTrue(grants(admin).contains("rt_add"), grants(admin));
      var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
      var source = Catalog.source(connection, provider, add);
      var change =
          Routines.save(provider, add, source, source.statement().replace("a + b", "a + b + 1"));
      assertEquals(new Routines.Outcome.Done(), apply(connection, provider, change));
      assertFalse(grants(admin).contains("rt_add"), grants(admin));
    }
  }

  // Without log_bin_trust_function_creators, MySQL keeps a function's CREATE to SUPER where it
  // logs binary: the trial fails, and the function stays.
  @Test
  void aFunctionThatBinaryLoggingRefusesStaysAsItWas() throws Exception {
    var provider = Provider.MYSQL;
    try (var connection = prepared(provider);
        var admin = admin(provider)) {
      execute(admin, "SET GLOBAL log_bin_trust_function_creators = 0");
      try {
        var add = routine(connection, provider, "rt_add", Kind.FUNCTION);
        var source = Catalog.source(connection, provider, add);
        var change =
            Routines.save(provider, add, source, source.statement().replace("a + b", "a + b + 1"));
        var failed =
            assertInstanceOf(Routines.Outcome.Failed.class, apply(connection, provider, change));
        assertEquals(Routines.Role.TRIAL, failed.step().role(), failed.toString());
        assertEquals(List.of("5"), values(call(connection, provider, add, "2", "3")));
      } finally {
        execute(admin, "SET GLOBAL log_bin_trust_function_creators = 1");
      }
    }
  }

  // An extension's functions are its own, not the schema's; a window function, one of a
  // polymorphic argument and one of rows of record are not called with values of a form.
  @Test
  void postgreSqlLeavesOutExtensionsAndSaysWhatItDoesNotCall() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = prepared(provider)) {
      execute(
          connection,
          "CREATE EXTENSION IF NOT EXISTS citext SCHEMA " + SCHEMA,
          "CREATE FUNCTION " + SCHEMA + ".rx_rank() RETURNS bigint LANGUAGE internal WINDOW"
              + " AS 'window_rank'",
          "CREATE FUNCTION " + SCHEMA + ".rx_first(a anyarray) RETURNS anyelement"
              + " LANGUAGE sql AS $$ SELECT a[1] $$",
          "CREATE FUNCTION " + SCHEMA + ".rx_rows() RETURNS SETOF record"
              + " LANGUAGE sql AS $$ SELECT 1, 2 $$");
      var routines = Catalog.routines(connection, provider, container(provider));
      assertTrue(
          routines.stream().allMatch(r -> r.name().startsWith("rt_") || r.name().startsWith("rx_")),
          routines.stream().map(Routine::label).toList().toString());
      var dialect = provider.dialect().routines();
      var rank = routine(connection, provider, "rx_rank", Kind.WINDOW);
      assertTrue(dialect.uncalled(rank).isPresent());
      assertTrue(Catalog.source(connection, provider, rank).statement().contains("WINDOW"));
      for (var name : List.of("rx_first", "rx_rows")) {
        var r = routine(connection, provider, name, Kind.FUNCTION);
        assertTrue(dialect.uncalled(r).isPresent(), r.toString());
      }
      assertEquals(
          Optional.empty(),
          dialect.uncalled(routine(connection, provider, "rt_add", Kind.FUNCTION)));
      assertEquals(
          Optional.empty(),
          dialect.uncalled(routine(connection, provider, "rt_fill", Kind.PROCEDURE)));
    }
  }

  private static String grants(Connection admin) throws SQLException {
    var all = new StringBuilder();
    try (var statement = admin.createStatement();
        var rows = statement.executeQuery("SHOW GRANTS FOR " + RUNNER)) {
      while (rows.next()) {
        all.append(rows.getString(1)).append('\n');
      }
    }
    return all.toString();
  }

  // A statement sent as Routines sends it, through an Execution of its own.
  private static Optional<Failure> send(Connection connection, Provider provider, String sql) {
    try (var execution = new Execution(connection, provider, sql, false, Purpose.DEFINITION)) {
      var portion = execution.next(100);
      return portion.results().stream()
          .filter(Failure.class::isInstance)
          .map(Failure.class::cast)
          .findFirst();
    }
  }

  // The server's root, who may name any definer.
  private static Connection admin(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(
            server.profile().withUser("root").withDatabase(TestServer.DATABASE),
            ("root-" + TestServer.PASSWORD).toCharArray(),
            server.driver(),
            TIMEOUT)
        .connection();
  }

  private static Routines.Outcome apply(
      Connection connection, Provider provider, Routines.Change change) {
    return Routines.apply(connection, provider, change, false, new Routines.Applying());
  }

  // The rows of the last statement of the call, each of its one column.
  private static List<String> values(List<Result> results) {
    var values = new ArrayList<String>();
    for (var result : results) {
      if (result instanceof Failure f) {
        throw new AssertionError(f.message());
      }
    }
    var rows = (Result.Rows)
        results.stream().filter(Result.Rows.class::isInstance).toList().getLast();
    for (var row : rows.rows()) {
      values.add(row.getLast() == null ? null : String.valueOf(row.getLast()));
    }
    return values;
  }

  private static List<Result> call(
      Connection connection, Provider provider, Routine routine, String... values) {
    var results = new ArrayList<Result>();
    for (var call : provider.dialect().routines().call(routine, Arrays.asList(values))) {
      try (var execution = new Execution(
          connection, provider, call.sql(), call.values(), false, Purpose.STATEMENT)) {
        var portion = execution.next(100);
        results.addAll(portion.results());
      }
    }
    return results;
  }

  private static Routine routine(Connection connection, Provider provider, String name, Kind kind)
      throws Exception {
    return Catalog.routines(connection, provider, container(provider)).stream()
        .filter(r -> r.name().equals(name) && r.kind() == kind)
        .findFirst()
        .orElseThrow();
  }

  private static Container container(Provider provider) {
    return provider == Provider.POSTGRESQL ? new Schema(HOORI, SCHEMA, false) : HOORI;
  }

  // The routines of the tests: a function of two integers, on PostgreSQL an overload of text, and
  // a procedure with an OUT parameter, whose body holds semicolons.
  private Connection prepared(Provider provider) throws Exception {
    this.provider = provider;
    var connection = open(provider);
    if (provider == Provider.POSTGRESQL) {
      execute(
          connection,
          "CREATE SCHEMA " + SCHEMA,
          "CREATE FUNCTION " + SCHEMA + ".rt_add(a integer, b integer) RETURNS integer"
              + " LANGUAGE sql AS $$ SELECT a + b $$",
          "CREATE FUNCTION " + SCHEMA + ".rt_add(t text) RETURNS text"
              + " LANGUAGE sql AS $$ SELECT t || '!' $$",
          "CREATE PROCEDURE " + SCHEMA + ".rt_fill(n integer, OUT total integer)"
              + " LANGUAGE plpgsql AS $$ BEGIN total := n; total := total + 6; END $$");
    } else {
      execute(
          connection,
          "CREATE FUNCTION rt_add(a INT, b INT) RETURNS INT DETERMINISTIC RETURN a + b",
          "CREATE PROCEDURE rt_fill(IN n INT, OUT total INT)"
              + " BEGIN SET total = n; SET total = total + 6; END");
    }
    return connection;
  }

  private static Connection open(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(
            server.profile().withDatabase(TestServer.DATABASE),
            TestServer.password(),
            server.driver(),
            TIMEOUT)
        .connection();
  }

  private static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }
}

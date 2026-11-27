package dev.hoori.db.core.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Listing, reading, saving, dropping and calling routines on real servers: in a schema of their
 * own on PostgreSQL, else in the sample database, with names of their own.
 */
class RoutinesServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final String SCHEMA = "routines_test";

  private Provider provider;

  @AfterEach
  void dropWhatTheTestMade() throws Exception {
    if (provider == null) {
      return;
    }
    try (var connection = open(provider)) {
      if (provider == Provider.POSTGRESQL) {
        execute(connection, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      } else {
        for (var name : List.of("rt_add", "rt_fill", "new_function", "new_procedure")) {
          execute(
              connection, "DROP FUNCTION IF EXISTS " + name, "DROP PROCEDURE IF EXISTS " + name);
        }
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
      var replaces = provider != Provider.MYSQL;
      assertTrue(source.startsWith(replaces ? "CREATE OR REPLACE " : "CREATE "), source);
      assertTrue(source.contains("rt_add"), source);
      var recorded = new CopyOnWriteArrayList<Sent>();
      // A body changed, with a terminator after it, which Save leaves out.
      var changed = source.replace("a + b", "a + b + 1") + ";\n";
      var change = Routines.save(provider, add, source, changed);
      assertEquals(replaces ? 1 : 2, change.statements().size(), change.toString());
      var outcome = Journal.recording(
          recorded::add, () -> Routines.apply(connection, provider, change, false));
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
      var broken = source.replace("a + b", "a +* b");
      var outcome =
          Routines.apply(connection, provider, Routines.save(provider, add, source, broken), false);
      var failed = assertInstanceOf(Routines.Outcome.Failed.class, outcome);
      assertEquals(Failure.Kind.INVALID, failed.failure().kind());
      // Where it was dropped first, it was put back.
      assertEquals(
          provider == Provider.MYSQL ? Optional.of(true) : Optional.empty(), failed.restored());
      assertEquals(List.of("5"), values(call(connection, provider, add, "2", "3")));
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
        var created = Routines.apply(
            connection, provider, Routines.save(provider, null, null, template), false);
        assertEquals(new Routines.Outcome.Done(), created, template);
      }
      var function = routine(connection, provider, "new_function", Kind.FUNCTION);
      assertEquals(List.of("3"), values(call(connection, provider, function, "1", "2")));
      var procedure = routine(connection, provider, "new_procedure", Kind.PROCEDURE);
      var dropped = Routines.apply(connection, provider, Routines.drop(provider, procedure), false);
      assertEquals(new Routines.Outcome.Done(), dropped);
      assertTrue(Catalog.routines(connection, provider, container).stream()
          .noneMatch(r -> r.name().equals("new_procedure")));
      assertThrows(CatalogException.class, () -> Catalog.source(connection, provider, procedure));
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
          () -> Routines.apply(connection, provider, Routines.drop(provider, add), true));
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

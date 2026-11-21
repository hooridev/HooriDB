package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.find.Search.Match;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Searches of real servers of SQL, in a container of the test's own: on PostgreSQL a schema of the
 * database, on MariaDB and MySQL the empty database with a space in its name, emptied afterwards.
 */
class FinderServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);

  // What the test searches on, and what made its objects and drops them.
  private record Scratch(
      Connection connection, Connection setup, Provider provider, Container container)
      implements AutoCloseable {

    String name(String table) {
      return provider.quote(container.name()) + "." + provider.quote(table);
    }

    void execute(String... statements) throws SQLException {
      try (var statement = setup.createStatement()) {
        for (var sql : statements) {
          statement.execute(sql);
        }
      }
    }

    @Override
    public void close() throws SQLException {
      try {
        if (container instanceof Schema) {
          execute("DROP SCHEMA " + provider.quote(container.name()) + " CASCADE");
        } else {
          execute(
              "DROP VIEW IF EXISTS " + name("apples"),
              "DROP PROCEDURE IF EXISTS " + name("find_needle"),
              "DROP TABLE IF EXISTS " + name("item"),
              "DROP TABLE IF EXISTS " + name("bulk"));
        }
      } finally {
        connection.close();
        setup.close();
      }
    }
  }

  // Two tables, a view, a routine and a trigger, each holding "needle" or "apple" somewhere; on
  // MariaDB and MySQL made by root, as a trigger needs SUPER where the binary log is on.
  private static Scratch scratch(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    boolean pg = provider == Provider.POSTGRESQL;
    var database = pg ? TestServer.DATABASE : TestServer.SPACED_DATABASE;
    var profile = server.profile().withDatabase(database);
    var connection =
        Connector.open(profile, TestServer.password(), server.driver(), TIMEOUT).connection();
    Container container = pg
        ? new Schema(HOORI, "find " + UUID.randomUUID().toString().substring(0, 8), false)
        : new Database(TestServer.SPACED_DATABASE, false);
    var setup = pg
        ? connection
        : Connector.open(
                profile.withUser("root"),
                ("root-" + TestServer.PASSWORD).toCharArray(),
                server.driver(),
                TIMEOUT)
            .connection();
    var scratch = new Scratch(connection, setup, provider, container);
    try {
      fill(scratch);
      return scratch;
    } catch (Exception e) {
      scratch.close();
      throw e;
    }
  }

  private static void fill(Scratch scratch) throws SQLException {
    var provider = scratch.provider();
    boolean pg = provider == Provider.POSTGRESQL;
    if (pg) {
      scratch.execute("CREATE SCHEMA " + provider.quote(scratch.container().name()));
    }
    scratch.execute(
        "CREATE TABLE " + scratch.name("item") + " (id integer PRIMARY KEY, label varchar(40),"
            + " note text, price decimal(10, 2), made date)",
        "INSERT INTO " + scratch.name("item") + " VALUES"
            + " (1, 'Red apple', 'fresh', 1.50, DATE '2024-01-02'),"
            + " (2, 'Green Apple', 'apple pie', 42.00, NULL),"
            + " (3, 'Pear', NULL, 42.00, DATE '2024-01-03')",
        "CREATE TABLE " + scratch.name("bulk") + " (id integer PRIMARY KEY, word varchar(20))",
        "CREATE VIEW " + scratch.name("apples") + " AS SELECT id, label FROM "
            + scratch.name("item") + " WHERE note = 'needle in a view'");
    var rows = new StringBuilder();
    for (int i = 1; i <= Search.LIMIT + 5; i++) {
      rows.append(i == 1 ? "" : ", ")
          .append("(")
          .append(i)
          .append(", 'apple ")
          .append(i)
          .append("')");
    }
    scratch.execute("INSERT INTO " + scratch.name("bulk") + " VALUES " + rows);
    if (pg) {
      scratch.execute(
          "CREATE FUNCTION " + scratch.name("find_needle") + "() RETURNS integer LANGUAGE sql"
              + " AS 'SELECT 7 /* a needle in a routine */'",
          "CREATE FUNCTION " + scratch.name("stamp") + "() RETURNS trigger LANGUAGE plpgsql"
              + " AS 'BEGIN RETURN NEW; END'",
          "CREATE TRIGGER item_note BEFORE INSERT ON " + scratch.name("item")
              + " FOR EACH ROW EXECUTE FUNCTION " + scratch.name("stamp") + "()");
    } else {
      // By the user who searches, as the server shows a routine's text to its definer alone.
      try (var statement = scratch.connection().createStatement()) {
        statement.execute("CREATE PROCEDURE " + scratch.name("find_needle")
            + "() SELECT 'a needle in a routine'");
      }
      scratch.execute("CREATE TRIGGER " + scratch.name("item_note") + " BEFORE INSERT ON "
          + scratch.name("item") + " FOR EACH ROW SET NEW.note = 'stamp'");
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void objectsAreFoundByTheirNamesAndDefinitions(Provider provider) throws Exception {
    try (var scratch = scratch(provider)) {
      var found = Finder.objects(
          scratch.connection(),
          provider,
          scratch.container(),
          new Search("NEEDLE", Match.CONTAINS, false));
      var c = scratch.container();
      var item = new Table(c, "item", Table.Kind.TABLE);
      // The view in its query, the routine by its name; no table or column is called so.
      assertEquals(
          List.of(
              Found.Kind.VIEW,
              provider == Provider.POSTGRESQL ? Found.Kind.FUNCTION : Found.Kind.PROCEDURE),
          found.stream().map(Found::kind).toList(),
          found.toString());
      var view = found.getFirst();
      assertEquals(new Table(c, "apples", Table.Kind.VIEW), view.table());
      assertTrue(view.inDefinition());
      assertEquals(
          "needle", view.definition().substring(view.start(), view.end()), view.definition());
      var routine = found.get(1);
      assertEquals("find_needle", routine.name());
      assertFalse(routine.inDefinition());
      assertTrue(routine.showsDefinition());
      assertTrue(routine.definition().contains("a needle in a routine"), routine.definition());

      var named = Finder.objects(
          scratch.connection(),
          provider,
          c,
          new Search("^(item|note|item_note)$", Match.PATTERN, true));
      assertEquals(
          List.of(
              new Found(Found.Kind.TABLE, c, item, "item", false, null, 0, 4),
              new Found(Found.Kind.COLUMN, c, item, "note", false, null, 0, 4)),
          named.stream().filter(f -> f.kind() != Found.Kind.TRIGGER).toList());
      var trigger = named.getLast();
      assertEquals(Found.Kind.TRIGGER, trigger.kind(), named.toString());
      assertEquals(item, trigger.table());
      assertEquals("item_note", trigger.name());
      assertTrue(trigger.definition().toLowerCase().contains("stamp"), trigger.definition());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theCandidatesAreTheTablesAndViewsWithTheirColumns(Provider provider) throws Exception {
    try (var scratch = scratch(provider)) {
      var c = scratch.container();
      var candidates = Finder.candidates(scratch.connection(), provider, c);
      assertEquals(
          List.of(
              new Table(c, "apples", Table.Kind.VIEW),
              new Table(c, "bulk", Table.Kind.TABLE),
              new Table(c, "item", Table.Kind.TABLE)),
          candidates.stream().map(Candidate::table).toList());
      var item = candidates.getLast();
      assertEquals(
          List.of("id", "label", "note", "price", "made"),
          item.columns().stream().map(x -> x.name()).toList());
      assertTrue(candidates.getFirst().bytes().isEmpty(), candidates.toString());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTableIsSearchedInTheColumnsThatCanHoldTheValue(Provider provider) throws Exception {
    try (var scratch = scratch(provider)) {
      var c = scratch.container();
      var candidates = Finder.candidates(scratch.connection(), provider, c);
      var item = candidates.getLast();
      var connection = scratch.connection();
      var apple = Finder.search(
          connection, provider, item, new Search("apple", Match.CONTAINS, false), false);
      assertEquals(List.of("label", "note"), apple.searched());
      assertEquals(
          List.of(new Hits.Hit("label", 2), new Hits.Hit("note", 1)), apple.columns(), "" + apple);
      assertEquals(2, apple.rows());
      assertFalse(apple.more());
      // Case counts on PostgreSQL where asked; MariaDB and MySQL go by the column's collation.
      var sensitive = Finder.search(
          connection, provider, item, new Search("Apple", Match.CONTAINS, true), false);
      assertEquals(
          provider == Provider.POSTGRESQL
              ? List.of(new Hits.Hit("label", 1))
              : List.of(new Hits.Hit("label", 2), new Hits.Hit("note", 1)),
          sensitive.columns());
      var number =
          Finder.search(connection, provider, item, new Search("42", Match.EQUALS, false), false);
      assertEquals(List.of(new Hits.Hit("price", 2)), number.columns());
      var date = Finder.search(
          connection, provider, item, new Search("2024-01", Match.CONTAINS, false), true);
      assertEquals(List.of(new Hits.Hit("made", 2)), date.columns(), date.toString());
      var pattern = Finder.search(
          connection, provider, item, new Search("^(red|pear)", Match.PATTERN, false), false);
      assertEquals(List.of(new Hits.Hit("label", 2)), pattern.columns());
      var none = Finder.search(
          connection, provider, item, new Search("100%", Match.CONTAINS, false), false);
      assertEquals(0, none.rows());
      assertTrue(none.columns().isEmpty());

      // The count stops past the limit.
      var bulk = Finder.search(
          connection,
          provider,
          candidates.get(1),
          new Search("apple", Match.CONTAINS, false),
          false);
      assertEquals(Search.LIMIT, bulk.rows());
      assertTrue(bulk.more());
      assertEquals(List.of(new Hits.Hit("word", Search.LIMIT)), bulk.columns());

      // The query of a hit's rows gives them.
      var sql = Finder.rows(
              provider, item, "note", new Search("apple", Match.CONTAINS, false), false)
          .orElseThrow();
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery(sql)) {
        assertTrue(rows.next());
        assertEquals(2, rows.getInt(1));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void aSchemaNamedAsItsDatabaseHoldsItsTablesInADatabaseSearch() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var provider = Provider.POSTGRESQL;
    var name = "same_" + UUID.randomUUID().toString().substring(0, 8);
    try (var connection = Connector.open(
                server.profile().withDatabase(TestServer.DATABASE),
                TestServer.password(),
                server.driver(),
                TIMEOUT)
            .connection();
        var statement = connection.createStatement()) {
      var schema = new Schema(HOORI, TestServer.DATABASE, false);
      // Named as the user too, so first in the search path: dropped before any other test makes
      // a table without a schema.
      statement.execute("CREATE SCHEMA " + schema.name());
      try {
        statement.execute("CREATE TABLE " + schema.name() + "." + name + " (id integer)");
        var found =
            Finder.objects(connection, provider, HOORI, new Search(name, Match.EQUALS, true));
        assertEquals(
            List.of(new Table(schema, name, Table.Kind.TABLE)),
            found.stream().map(Found::table).toList());
      } finally {
        statement.execute("DROP SCHEMA " + schema.name() + " CASCADE");
      }
    }
  }
}

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
              "DROP TABLE IF EXISTS " + name("bulk"),
              "DROP TABLE IF EXISTS " + name("measure"));
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

      // The count stops past the limit.
      var bulk = Finder.search(
          connection,
          provider,
          candidates.get(1),
          new Search("apple", Match.CONTAINS, false),
          false);
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

  // A table with a number of each kind, texts with LIKE's wildcards and a backslash, and on
  // PostgreSQL an oid, a domain over a domain over text and an enum, which MySQL has as text.
  private static Candidate measure(Scratch scratch) throws Exception {
    var provider = scratch.provider();
    var measure = scratch.name("measure");
    if (provider == Provider.POSTGRESQL) {
      var schema = provider.quote(scratch.container().name()) + ".";
      scratch.execute(
          "CREATE DOMAIN " + schema + "word AS varchar(20)",
          "CREATE DOMAIN " + schema + "term AS " + schema + "word",
          "CREATE TYPE " + schema + "taste AS ENUM ('sour', 'sweet')",
          "CREATE TABLE " + measure + " (id integer PRIMARY KEY, o oid, r real,"
              + " d double precision, w " + schema + "term, t " + schema + "taste, note text)",
          "INSERT INTO " + measure + " VALUES (1, 7, 0.1, 0.1, 'kiwi', 'sour', '100x'),"
              + " (2, 8, 2.5, 1e300, 'lime', 'sweet', '1_0'),"
              + " (3, 9, NULL, NULL, NULL, NULL, 'a\\b')");
    } else {
      scratch.execute(
          "CREATE TABLE " + measure + " (id integer PRIMARY KEY, r float, d double,"
              + " t enum('sour', 'sweet'), note varchar(10))",
          "INSERT INTO " + measure + " VALUES (1, 0.1, 0.1, 'sour', '100x'),"
              + " (2, 2.5, 1e300, 'sweet', '1_0'), (3, NULL, NULL, NULL, 'a\\\\b')");
    }
    return Finder.candidates(scratch.connection(), provider, scratch.container()).stream()
        .filter(c -> c.table().name().equals("measure"))
        .findFirst()
        .orElseThrow();
  }

  private static Hits search(Scratch scratch, Candidate table, String text) throws Exception {
    return Finder.search(
        scratch.connection(),
        scratch.provider(),
        table,
        new Search(text, Match.CONTAINS, true),
        false);
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void numbersAndTextsAreFoundAsTheServerHoldsThem(Provider provider) throws Exception {
    try (var scratch = scratch(provider)) {
      var measure = measure(scratch);
      boolean pg = provider == Provider.POSTGRESQL;
      // A float's 0.1 too, which is no double's.
      assertEquals(
          List.of(new Hits.Hit("r", 1), new Hits.Hit("d", 1)),
          search(scratch, measure, "0.1").columns());
      // An oid compared, no error; beyond a double's range, the columns of floats not searched.
      if (pg) {
        assertEquals(
            List.of(new Hits.Hit("o", 1)), search(scratch, measure, "7").columns());
      }
      var huge = search(scratch, measure, "1e400");
      assertEquals(
          pg ? List.of("id", "o", "w", "t", "note") : List.of("id", "t", "note"), huge.searched());
      assertTrue(huge.columns().isEmpty());
      // Beyond any exact number's digits, the columns of numbers not searched either, as
      // PostgreSQL would refuse the number and fail the search of the texts too.
      assertEquals(
          pg ? List.of("w", "t", "note") : List.of("t", "note"),
          search(scratch, measure, "1e-20000").searched());
      assertEquals(
          List.of(new Hits.Hit("d", 1)), search(scratch, measure, "1e300").columns());
      // A domain and an enum as their text.
      if (pg) {
        assertEquals(
            List.of(new Hits.Hit("w", 1)), search(scratch, measure, "kiw").columns());
      }
      assertEquals(
          List.of(new Hits.Hit("t", 1)), search(scratch, measure, "our").columns());
      // LIKE's wildcards and a backslash as themselves: % or _ unescaped would match 100x too.
      assertTrue(search(scratch, measure, "100%").columns().isEmpty());
      assertEquals(
          List.of(new Hits.Hit("note", 1)), search(scratch, measure, "1_").columns());
      assertEquals(
          List.of(new Hits.Hit("note", 1)), search(scratch, measure, "a\\b").columns());
      // The query of a float's hit gives its row.
      var sql = Finder.rows(provider, measure, "r", new Search("0.1", Match.EQUALS, true), false)
          .orElseThrow();
      try (var statement = scratch.connection().createStatement();
          var rows = statement.executeQuery(sql)) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
        assertFalse(rows.next());
      }
    }
  }

  @Test
  void aColumnOfANondeterministicCollationIsSearchedAsTheCaseAsks() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var scratch = scratch(provider)) {
      var ci = provider.quote(scratch.container().name()) + ".ci";
      var fruit = scratch.name("fruit");
      scratch.execute(
          "CREATE COLLATION " + ci
              + " (provider = icu, locale = 'und-u-ks-level2', deterministic = false)",
          "CREATE TABLE " + fruit + " (id integer PRIMARY KEY, name text COLLATE " + ci + ")",
          "INSERT INTO " + fruit + " VALUES (1, 'Apple'), (2, 'apple')");
      var table = Finder.candidates(scratch.connection(), provider, scratch.container()).stream()
          .filter(c -> c.table().name().equals("fruit"))
          .findFirst()
          .orElseThrow();
      for (var match : Match.values()) {
        for (boolean sensitive : List.of(true, false)) {
          var hits = Finder.search(
              scratch.connection(), provider, table, new Search("apple", match, sensitive), false);
          assertEquals(
              List.of(new Hits.Hit("name", sensitive ? 1 : 2)),
              hits.columns(),
              match + (sensitive ? ", case sensitive" : ""));
        }
      }
    }
  }

  @Test
  void aPartitionIsSearchedWithItsTableAlone() throws Exception {
    try (var scratch = scratch(Provider.POSTGRESQL)) {
      var sale = scratch.name("sale");
      scratch.execute(
          "CREATE TABLE " + sale + " (id integer, region text) PARTITION BY LIST (region)",
          "CREATE TABLE " + scratch.name("sale_eu") + " PARTITION OF " + sale
              + " FOR VALUES IN ('eu')",
          "INSERT INTO " + sale + " VALUES (1, 'eu')");
      for (Container scope : List.of(scratch.container(), HOORI)) {
        var names = Finder.candidates(scratch.connection(), Provider.POSTGRESQL, scope).stream()
            .map(c -> c.table().name())
            .toList();
        assertTrue(names.contains("sale"), names.toString());
        assertFalse(names.contains("sale_eu"), names.toString());
      }
      // Found by its name all the same, as the tree lists it.
      var found = Finder.objects(
          scratch.connection(),
          Provider.POSTGRESQL,
          scratch.container(),
          new Search("sale_eu", Match.EQUALS, true));
      assertEquals(List.of("sale_eu"), found.stream().map(Found::name).toList());
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

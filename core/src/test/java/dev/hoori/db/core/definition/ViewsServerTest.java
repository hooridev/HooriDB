package dev.hoori.db.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A view of each provider's real server reads as the statements that create it, with what it uses
 * and what uses it, and is created, replaced, dropped and refreshed by a {@link ViewChange}.
 */
class ViewsServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aViewReadsAsItsCreateWithWhatItUsesAndWhatUsesIt(Provider provider) throws Exception {
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "CREATE VIEW named AS SELECT id, name FROM item WHERE name IS NOT NULL",
          "CREATE VIEW counted AS SELECT COUNT(*) AS n FROM named");
      try (var connection = database.open()) {
        var read = views(connection, provider).read(database.view("named"));
        var text = read.text();
        assertTrue(text.contains("VIEW"), text);
        assertTrue(text.endsWith(";\n"), text);
        // As the server has it, to change it there: with its definer, which an export leaves out.
        assertEquals(provider != Provider.POSTGRESQL, text.contains("DEFINER="), text);
        assertEquals(
            List.of(new Reference(database.container(), Reference.Kind.TABLE, "item", "")),
            read.references().uses());
        assertEquals(
            List.of(new Reference(database.container(), Reference.Kind.VIEW, "counted", "")),
            read.references().usedBy());
        assertFalse(read.concurrently());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anEditedDefinitionReplacesTheViewInPlace(Provider provider) throws Exception {
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "INSERT INTO item VALUES (1, 'a'), (2, NULL)",
          "CREATE VIEW named AS SELECT id, name FROM item");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var view = database.view("named");
        var read = views.read(view);
        // Named with its schema on PostgreSQL, without its database elsewhere.
        var from = provider == Provider.POSTGRESQL ? "FROM public.item" : "from `item`";
        assertTrue(read.text().contains(from), read.text());
        var edited = read.text().replace(from, from + " WHERE name IS NOT NULL");
        var change = ViewChange.replace(provider, read, edited);
        assertEquals(ViewChange.Kind.REPLACE, change.kind());
        assertTrue(
            change.statements().getFirst().startsWith("CREATE OR REPLACE "), change.toString());
        var sent = new ArrayList<Sent>();
        Views.Changed changed = Journal.recording(sent::add, () -> views.change(change));
        assertInstanceOf(Views.Done.class, changed);
        assertTrue(sent.stream().allMatch(s -> s.purpose() == Purpose.DEFINITION), sent.toString());
        assertEquals(1, database.count(connection, "named"));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aViewWhoseColumnsChangeIsDroppedAndCreatedWhereTheServerKeepsThem(Provider provider)
      throws Exception {
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "CREATE VIEW named AS SELECT id, name FROM item");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var view = database.view("named");
        var read = views.read(view);
        var edited = "CREATE VIEW " + database.qualified("named") + " AS SELECT name FROM "
            + database.qualified("item") + ";";
        var changed = views.change(ViewChange.replace(provider, read, edited));
        if (!provider.dialect().views().changesInTransaction()) {
          // MariaDB and MySQL take any columns.
          assertInstanceOf(Views.Done.class, changed);
          assertEquals(List.of("name"), database.columns(connection, view));
          return;
        }
        assertInstanceOf(Views.KeepsColumns.class, changed);
        assertEquals(List.of("id", "name"), database.columns(connection, view), "Nothing changed");
        var again = views.change(ViewChange.recreate(provider, read, edited));
        assertInstanceOf(Views.Done.class, again);
        assertEquals(List.of("name"), database.columns(connection, view));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aViewIsCreatedAndDropped(Provider provider) throws Exception {
    try (var database = new Scratch(provider)) {
      database.run("CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var create = ViewChange.create(
            provider,
            database.container(),
            "new view",
            "  SELECT id FROM " + database.qualified("item") + " ;  ");
        assertEquals(
            List.of("CREATE VIEW " + database.qualified("new view") + " AS\nSELECT id FROM "
                + database.qualified("item")),
            create.statements());
        assertInstanceOf(Views.Done.class, views.change(create));
        var view = database.view("new view");
        assertTrue(database.tables(connection).contains(view));
        assertInstanceOf(Views.Done.class, views.change(ViewChange.drop(provider, view, false)));
        assertFalse(database.tables(connection).contains(view));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aDropWithWhatDependsOnItTakesThemWhereTheServerCan(Provider provider) throws Exception {
    assumeTrue(provider.dialect().views().dropsDependents(), "Only PostgreSQL cascades");
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY)",
          "CREATE VIEW first AS SELECT id FROM item",
          "CREATE VIEW second AS SELECT id FROM first",
          "CREATE VIEW third AS SELECT id FROM second");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var first = database.view("first");
        // Refused, the server names all that a drop with it would take, also what uses that.
        var refused = assertInstanceOf(
            Views.DependedOn.class, views.change(ViewChange.drop(provider, first, false)));
        assertTrue(refused.words().contains("second"), refused.words());
        assertTrue(refused.words().contains("third"), refused.words());
        assertTrue(database.tables(connection).contains(database.view("second")));
        var cascade = ViewChange.drop(provider, first, true);
        assertTrue(cascade.statements().getFirst().endsWith(" CASCADE"), cascade.toString());
        views.change(cascade);
        assertFalse(database.tables(connection).contains(database.view("third")));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSavedViewKeepsItsDefiner(Provider provider) throws Exception {
    assumeTrue(provider != Provider.POSTGRESQL, "Only MariaDB and MySQL define views by a user");
    var user = "definer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE USER '" + user + "'@'%' IDENTIFIED BY 'Secret-1234'",
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "CREATE DEFINER = '" + user + "'@'%' SQL SECURITY DEFINER VIEW named"
              + " AS SELECT id, name FROM item");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var read = views.read(database.view("named"));
        var edited = read.text().replace("from `item`", "from `item` where `name` is not null");
        assertNotEquals(read.text(), edited, read.text());
        assertInstanceOf(
            Views.Done.class, views.change(ViewChange.replace(provider, read, edited)));
        assertEquals(
            List.of(user + "@%"),
            database.strings(
                connection,
                "SELECT DEFINER FROM information_schema.VIEWS WHERE TABLE_SCHEMA = '"
                    + database.name + "' AND TABLE_NAME = 'named'"),
            "Saved by root, defined by the user as before");
      }
    } finally {
      try (var admin = new Scratch(provider)) {
        admin.run("DROP USER IF EXISTS '" + user + "'@'%'");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void whatAViewUsesInAnotherDatabaseIsFound(Provider provider) throws Exception {
    assumeTrue(provider != Provider.POSTGRESQL, "A PostgreSQL view reads its own database alone");
    try (var database = new Scratch(provider);
        var other = new Scratch(provider)) {
      other.run("CREATE TABLE item (id INT PRIMARY KEY)");
      database.run("CREATE VIEW elsewhere AS SELECT id FROM " + other.qualified("item"));
      try (var connection = database.open()) {
        assertEquals(
            List.of(new Reference(other.container(), Reference.Kind.TABLE, "item", "")),
            views(connection, provider)
                .read(database.view("elsewhere"))
                .references()
                .uses());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aDropAndCreateGivesBackWhatTheDropTakes(Provider provider) throws Exception {
    assumeTrue(provider == Provider.POSTGRESQL, "Only PostgreSQL drops and creates a view");
    var role = "owner_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE ROLE " + role,
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "CREATE VIEW named AS SELECT id, name FROM item",
          "COMMENT ON COLUMN named.name IS 'What it is called'",
          "ALTER VIEW named ALTER COLUMN name SET DEFAULT 'none'",
          "CREATE FUNCTION add_named() RETURNS trigger LANGUAGE plpgsql AS"
              + " $$ BEGIN INSERT INTO item VALUES (NEW.id, NEW.name); RETURN NEW; END $$",
          "CREATE TRIGGER adds INSTEAD OF INSERT ON named FOR EACH ROW EXECUTE FUNCTION"
              + " add_named()",
          "CREATE RULE keeps AS ON DELETE TO named DO INSTEAD NOTHING",
          "ALTER VIEW named OWNER TO " + role);
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var view = database.view("named");
        var read = views.read(view);
        assertTrue(
            read.text().contains("COMMENT ON COLUMN \"public\".\"named\".\"name\""), read.text());
        assertTrue(read.text().contains("SET DEFAULT 'none'"), read.text());
        // The column qualified or not, as the server's version writes a view of one table.
        var edited = read.text()
            .replaceFirst("((?:item\\.)?name)(\\s+FROM)", "$1 AS label$2")
            .replace("\"name\"", "\"label\"");
        var change = ViewChange.recreate(provider, read, edited);
        assertInstanceOf(Views.Done.class, views.change(change));
        assertEquals(List.of("id", "label"), database.columns(connection, view));
        assertEquals(
            List.of(role),
            database.strings(
                connection,
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.named'::regclass"));
        assertEquals(
            List.of("adds"),
            database.strings(
                connection,
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'public.named'::regclass"));
        assertEquals(
            List.of("keeps"),
            database.strings(
                connection,
                "SELECT rulename FROM pg_rewrite WHERE ev_class = 'public.named'::regclass"
                    + " AND rulename <> '_RETURN'"));
        assertEquals(
            List.of("What it is called"),
            database.strings(connection, "SELECT col_description('public.named'::regclass, 2)"));
      }
    } finally {
      try (var admin = new Scratch(provider)) {
        admin.run("DROP ROLE IF EXISTS " + role);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCommentLeftOutOfTheDefinitionGoesWithTheReplace(Provider provider) throws Exception {
    assumeTrue(provider == Provider.POSTGRESQL, "Only PostgreSQL keeps a view's comments apart");
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY)",
          "CREATE VIEW v AS SELECT id FROM item",
          "COMMENT ON VIEW v IS 'Gone soon'");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var read = views.read(database.view("v"));
        var edited = read.text().substring(0, read.text().indexOf("COMMENT ON VIEW"));
        assertInstanceOf(
            Views.Done.class, views.change(ViewChange.replace(provider, read, edited)));
        assertEquals(
            java.util.Arrays.asList((String) null),
            database.strings(connection, "SELECT obj_description('public.v'::regclass)"));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aMaterializedViewRefreshesConcurrentlyOnceAUniqueIndexAllows(Provider provider)
      throws Exception {
    assumeTrue(provider == Provider.POSTGRESQL, "Only PostgreSQL has materialized views");
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))",
          "INSERT INTO item VALUES (1, 'a')",
          "CREATE MATERIALIZED VIEW kept AS SELECT id, name FROM item");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var kept = new Table(database.container(), "kept", Table.Kind.MATERIALIZED);
        assertTrue(database.tables(connection).contains(kept), "Apart from views");
        assertFalse(views.read(kept).concurrently());
        database.run(
            "CREATE UNIQUE INDEX kept_id ON kept (id)", "INSERT INTO item VALUES (2, 'b')");
        var read = views.read(kept);
        assertTrue(read.concurrently());
        assertTrue(read.text().contains("CREATE UNIQUE INDEX kept_id"), read.text());
        views.change(ViewChange.refresh(provider, kept, true));
        assertEquals(2, database.count(connection, "kept"));
        // Created anew from its definition, with its index, and refreshed.
        var recreated =
            ViewChange.replace(provider, read, read.text().replace("name", "name AS label"));
        assertEquals(ViewChange.Kind.RECREATE, recreated.kind());
        assertTrue(recreated.transaction());
        assertInstanceOf(Views.Done.class, views.change(recreated));
        assertEquals(List.of("id", "label"), database.columns(connection, kept));
        assertEquals(2, database.count(connection, "kept"));
        assertTrue(views.read(kept).concurrently());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aReadOnlyConnectionSendsNoChange(Provider provider) throws Exception {
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY)", "CREATE VIEW v AS SELECT id FROM item");
      try (var connection = database.open()) {
        var views = new SqlViews(connection, provider, true);
        var view = database.view("v");
        var read = views.read(view);
        var sent = new ArrayList<Sent>();
        var refused = assertThrows(
            QueryException.class,
            () -> Journal.recording(
                sent::add, () -> views.change(ViewChange.drop(provider, view, false))));
        assertTrue(refused.detail().contains("read-only"), refused.detail());
        assertEquals(List.of(), sent, "Nothing was sent");
        assertTrue(database.tables(connection).contains(read.view()));
      }
    }
  }

  private static Views views(Connection connection, Provider provider) {
    return new SqlViews(connection, provider, false);
  }

  /** A database of its own on the provider's server, dropped as it closes. */
  private static final class Scratch implements AutoCloseable {

    final Provider provider;
    final String name;
    private final TestServer server;

    Scratch(Provider provider) throws Exception {
      this.provider = provider;
      this.server = TestServer.of(provider);
      this.name = "views_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
      try (var admin = open(TestServer.DATABASE)) {
        execute(admin, "CREATE DATABASE " + provider.quote(name));
      }
    }

    Container container() {
      var database = new Database(name, false);
      return provider == Provider.POSTGRESQL ? new Schema(database, "public", false) : database;
    }

    Table view(String view) {
      return new Table(container(), view, Table.Kind.VIEW);
    }

    String qualified(String object) {
      return provider.quote(container().name()) + "." + provider.quote(object);
    }

    Connection open() throws ConnectionException {
      return open(name);
    }

    private Connection open(String database) throws ConnectionException {
      var profile = server.profile().withDatabase(database);
      var password = TestServer.password();
      if (provider != Provider.POSTGRESQL) {
        profile = profile.withUser("root");
        password = ("root-" + TestServer.PASSWORD).toCharArray();
      }
      return Connector.open(profile, password, server.driver(), TIMEOUT).connection();
    }

    void run(String... statements) throws Exception {
      try (var connection = open()) {
        for (var sql : statements) {
          execute(connection, sql);
        }
      }
    }

    List<Table> tables(Connection connection) throws Exception {
      return Catalog.tables(connection, provider, container());
    }

    List<String> columns(Connection connection, Table table) throws Exception {
      return Catalog.columns(connection, provider, table).stream()
          .map(c -> c.name())
          .toList();
    }

    List<String> strings(Connection connection, String query) throws SQLException {
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery(query)) {
        var read = new ArrayList<String>();
        while (rows.next()) {
          read.add(rows.getString(1));
        }
        return read;
      }
    }

    long count(Connection connection, String object) throws SQLException {
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM " + qualified(object))) {
        rows.next();
        return rows.getLong(1);
      }
    }

    @Override
    public void close() throws ConnectionException, SQLException {
      try (var admin = open(TestServer.DATABASE)) {
        if (provider == Provider.POSTGRESQL) {
          execute(admin, "DROP DATABASE " + provider.quote(name) + " WITH (FORCE)");
        } else {
          execute(admin, "DROP DATABASE " + provider.quote(name));
        }
      }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }
  }
}

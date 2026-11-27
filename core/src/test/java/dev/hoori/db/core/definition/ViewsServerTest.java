package dev.hoori.db.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertFalse(text.contains("DEFINER="), text);
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
        if (!provider.dialect().definitions().changesInTransaction()) {
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
    assumeTrue(provider.dialect().definitions().dropsDependents(), "Only PostgreSQL cascades");
    try (var database = new Scratch(provider)) {
      database.run(
          "CREATE TABLE item (id INT PRIMARY KEY)",
          "CREATE VIEW first AS SELECT id FROM item",
          "CREATE VIEW second AS SELECT id FROM first");
      try (var connection = database.open()) {
        var views = views(connection, provider);
        var first = database.view("first");
        assertThrows(
            QueryException.class, () -> views.change(ViewChange.drop(provider, first, false)));
        assertTrue(database.tables(connection).contains(database.view("second")));
        var cascade = ViewChange.drop(provider, first, true);
        assertTrue(cascade.statements().getFirst().endsWith(" CASCADE"), cascade.toString());
        views.change(cascade);
        assertFalse(database.tables(connection).contains(database.view("second")));
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

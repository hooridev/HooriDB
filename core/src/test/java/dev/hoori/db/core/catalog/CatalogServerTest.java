package dev.hoori.db.core.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.CatalogException.Failure;
import dev.hoori.db.core.catalog.ForeignKey.Rule;
import dev.hoori.db.core.catalog.Table.Kind;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Reading the catalog of real servers, with the sample schema of {@link TestServer}. */
class CatalogServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);

  private record Expected(
      String name, String postgresql, String mariadb, String mysql, boolean nullable, int key) {

    String type(Provider provider) {
      return switch (provider) {
        case POSTGRESQL -> postgresql;
        case MARIADB -> mariadb;
        case MYSQL -> mysql;
        case MONGODB -> throw new IllegalArgumentException("No SQL");
      };
    }
  }

  private static Expected column(
      String name, String postgresql, String mariadb, String mysql, boolean nullable, int key) {
    return new Expected(name, postgresql, mariadb, mysql, nullable, key);
  }

  private static Expected integer(String name, int key) {
    return column(name, "integer", "int(11)", "int", false, key);
  }

  private static Expected decimal(String name, int precision, int scale, boolean nullable) {
    var type = "(" + precision + "," + scale + ")";
    return column(name, "numeric" + type, "decimal" + type, "decimal" + type, nullable, 0);
  }

  private static Expected varchar(String name, int length, boolean nullable) {
    var type = "varchar(" + length + ")";
    return column(name, "character varying(" + length + ")", type, type, nullable, 0);
  }

  private static Expected same(String name, String type, boolean nullable) {
    return column(name, type, type, type, nullable, 0);
  }

  private static Expected timestamp(String name) {
    return column(name, "timestamp without time zone", "datetime", "datetime", false, 0);
  }

  // The sample schema's tables and view, in the order every server sorts their names.
  private static final Map<String, List<Expected>> SAMPLE = Map.of(
      "active_customer",
      List.of(integer("id", 0), varchar("name", 100, false), varchar("email", 200, true)),
      "customer",
      List.of(
          integer("id", 1),
          varchar("name", 100, false),
          varchar("email", 200, true),
          column("active", "boolean", "tinyint(1)", "tinyint(1)", false, 0),
          same("born", "date", true),
          timestamp("joined"),
          decimal("balance", 12, 2, false),
          same("notes", "text", true)),
      "purchase",
      List.of(
          integer("id", 1),
          integer("customer_id", 0),
          decimal("total", 12, 2, false),
          timestamp("bought")),
      "purchase_line",
      List.of(
          integer("purchase_id", 1),
          integer("line", 2),
          varchar("item", 80, false),
          decimal("quantity", 10, 2, true)),
      "reading",
      List.of(integer("id", 1), varchar("label", 20, false), decimal("amount", 10, 3, true)));

  private static final List<String> SAMPLE_TABLES =
      List.of("active_customer", "customer", "purchase", "purchase_line", "reading");

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theDatabasesIncludeTheSampleOnesAndSystemOnesComeLast(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server, TestServer.DATABASE)) {
      var databases = Catalog.databases(connection, provider);
      var names = databases.stream().map(Database::name).toList();
      for (var name :
          List.of(TestServer.DATABASE, TestServer.SPACED_DATABASE, TestServer.NON_ASCII_DATABASE)) {
        assertTrue(databases.contains(new Database(name, false)), names.toString());
      }
      // What the test user may see of them: MariaDB shows it no more than information_schema.
      var system = provider == Provider.POSTGRESQL
          ? Set.of("template0", "template1")
          : Set.of("information_schema", "mysql", "performance_schema", "sys");
      for (var database : databases) {
        assertEquals(system.contains(database.name()), database.system(), database.name());
      }
      assertSystemLast(databases.stream().map(Database::system).toList(), names);
    }
  }

  @Test
  void theSampleDatabaseHasPublicFirstAndTheSystemSchemasAfterIt() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    try (var connection = open(server, TestServer.DATABASE)) {
      var schemas = Catalog.schemas(connection, Provider.POSTGRESQL, HOORI);
      assertEquals(new Schema(HOORI, "public", false), schemas.getFirst());
      var system = schemas.stream().filter(Schema::system).map(Schema::name).toList();
      assertEquals(schemas.size() - 1, system.size(), schemas.toString());
      assertTrue(
          system.containsAll(List.of("information_schema", "pg_catalog", "pg_toast")),
          system.toString());
      assertTrue(
          Catalog.tables(connection, Provider.POSTGRESQL, new Schema(HOORI, "pg_catalog", true))
              .contains(new Table(new Schema(HOORI, "pg_catalog", true), "pg_class", Kind.TABLE)));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theSampleSchemaHoldsItsTablesAndView(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    try (var connection = open(server, TestServer.DATABASE)) {
      var expected = SAMPLE_TABLES.stream()
          .map(name ->
              new Table(container, name, name.equals("active_customer") ? Kind.VIEW : Kind.TABLE))
          .toList();
      assertEquals(expected, sampleTables(connection, provider));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void eachSampleTableHasItsColumnsTypesAndPrimaryKey(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server, TestServer.DATABASE)) {
      for (var table : sampleTables(connection, provider)) {
        var expected = SAMPLE.get(table.name()).stream()
            .map(e -> new Column(
                table,
                e.name(),
                e.type(provider),
                // PostgreSQL does not carry NOT NULL over into the columns of a view.
                e.nullable() || provider == Provider.POSTGRESQL && table.kind() == Kind.VIEW,
                e.key()))
            .toList();
        assertEquals(expected, Catalog.columns(connection, provider, table), table.name());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void namesThatNeedQuotingAreReadBackExactly(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    // PostgreSQL in a schema of its own, MariaDB and MySQL in a database beyond ASCII.
    Container container = provider == Provider.POSTGRESQL
        ? new Schema(HOORI, "Odd \"Schema\" `Ä`", false)
        : new Database(TestServer.NON_ASCII_DATABASE, false);
    var names = List.of("Mixed Case", "double \"quote\"", "back `tick`", "it's", "Ünïcödé");
    try (var connection = open(server, TestServer.DATABASE)) {
      if (container instanceof Schema) {
        execute(connection, "CREATE SCHEMA " + provider.quote(container.name()));
      }
      try {
        for (var name : names) {
          execute(
              connection,
              "CREATE TABLE " + qualified(provider, container, name) + " ("
                  + provider.quote("Key \"Two\"") + " integer NOT NULL, "
                  + provider.quote("key `one`") + " integer NOT NULL, "
                  + "PRIMARY KEY (" + provider.quote("key `one`") + ", "
                  + provider.quote("Key \"Two\"") + "))");
        }
        if (container instanceof Schema) {
          assertTrue(Catalog.schemas(connection, provider, HOORI)
              .contains(new Schema(HOORI, container.name(), false)));
        }
        var tables = Catalog.tables(connection, provider, container);
        assertEquals(
            names.stream().sorted().toList(),
            tables.stream().map(Table::name).sorted().toList());
        for (var table : tables) {
          // In the table's order, with the key's own order apart from it.
          assertEquals(
              List.of(
                  new Column(table, "Key \"Two\"", integer(provider), false, 2),
                  new Column(table, "key `one`", integer(provider), false, 1)),
              Catalog.columns(connection, provider, table));
        }
      } finally {
        if (container instanceof Schema) {
          execute(connection, "DROP SCHEMA " + provider.quote(container.name()) + " CASCADE");
        } else {
          for (var name : names) {
            execute(connection, "DROP TABLE IF EXISTS " + qualified(provider, container, name));
          }
        }
      }
    }
  }

  /** All tables of a database in one call: those of each schema, as its own call reads them. */
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void allTablesOfADatabaseAreThoseOfEachSchemaInOneCall(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server, TestServer.DATABASE)) {
      if (provider != Provider.POSTGRESQL) {
        assertEquals(
            Catalog.tables(connection, provider, HOORI),
            Catalog.allTables(connection, provider, HOORI));
        return;
      }
      var other = new Schema(HOORI, "zz other", false);
      var empty = new Schema(HOORI, "zz empty", false);
      execute(
          connection,
          "CREATE SCHEMA " + provider.quote(other.name()),
          "CREATE SCHEMA " + provider.quote(empty.name()),
          "CREATE TABLE " + qualified(provider, other, "b") + " (id integer)",
          "CREATE VIEW " + qualified(provider, other, "a") + " AS SELECT 1 AS one");
      try {
        var expected = new ArrayList<Table>();
        for (var schema : Catalog.schemas(connection, provider, HOORI)) {
          if (!schema.system()) {
            expected.addAll(Catalog.tables(connection, provider, schema));
          }
        }
        var all = Catalog.allTables(connection, provider, HOORI);
        assertEquals(expected, all);
        assertTrue(all.contains(new Table(other, "a", Kind.VIEW)), all.toString());
        assertTrue(all.stream().noneMatch(t -> t.container().equals(empty)), all.toString());
        assertTrue(
            all.stream().noneMatch(t -> ((Schema) t.container()).name().startsWith("pg_")),
            all.toString());
        assertFailure(
            Failure.WRONG_DATABASE,
            () -> Catalog.allTables(connection, provider, new Database("postgres", false)));
      } finally {
        execute(
            connection,
            "DROP SCHEMA " + provider.quote(other.name()) + " CASCADE",
            "DROP SCHEMA " + provider.quote(empty.name()));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void onlyPostgresqlNeedsAConnectionToTheDatabaseItReads(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    try (var connection = open(server, TestServer.SPACED_DATABASE)) {
      if (provider == Provider.POSTGRESQL) {
        var customer = new Table(container, "customer", Kind.TABLE);
        var e = assertFailure(
            Failure.WRONG_DATABASE, () -> Catalog.schemas(connection, provider, HOORI));
        assertTrue(e.detail().contains(TestServer.SPACED_DATABASE), e.detail());
        assertFailure(
            Failure.WRONG_DATABASE, () -> Catalog.tables(connection, provider, container));
        assertFailure(
            Failure.WRONG_DATABASE, () -> Catalog.columns(connection, provider, customer));
        assertFailure(
            Failure.WRONG_DATABASE, () -> Catalog.foreignKeys(connection, provider, customer));
        var spaced = new Database(TestServer.SPACED_DATABASE, false);
        assertEquals(
            new Schema(spaced, "public", false),
            Catalog.schemas(connection, provider, spaced).getFirst());
      } else {
        assertEquals(
            SAMPLE_TABLES,
            sampleTables(connection, provider).stream().map(Table::name).toList());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aThousandTablesAndAWideOneAreReadInUnderASecond(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    // MariaDB and MySQL let the test user create no database, but use the empty one with a space.
    Container container = provider == Provider.POSTGRESQL
        ? new Schema(HOORI, "many", false)
        : new Database(TestServer.SPACED_DATABASE, false);
    var names =
        IntStream.range(0, 1000).mapToObj(i -> String.format("t%04d", i)).toList();
    var wide = names.getLast();
    try (var connection = open(server, TestServer.DATABASE)) {
      if (container instanceof Schema) {
        execute(connection, "CREATE SCHEMA many");
      }
      try {
        try (var statement = connection.createStatement()) {
          for (var name : names) {
            var columns = name.equals(wide)
                ? IntStream.rangeClosed(1, 300)
                    .mapToObj(i -> "c" + i + " integer")
                    .collect(Collectors.joining(", "))
                : "id integer PRIMARY KEY";
            statement.addBatch(
                "CREATE TABLE " + qualified(provider, container, name) + " (" + columns + ")");
          }
          statement.executeBatch();
        }
        var started = System.nanoTime();
        var tables = Catalog.tables(connection, provider, container);
        var tablesTook = Duration.ofNanos(System.nanoTime() - started);
        assertEquals(names, tables.stream().map(Table::name).toList());

        started = System.nanoTime();
        var columns = Catalog.columns(connection, provider, tables.getLast());
        var columnsTook = Duration.ofNanos(System.nanoTime() - started);
        assertEquals(300, columns.size());
        assertEquals("c300", columns.getLast().name());

        var times = provider + ": 1000 tables in " + tablesTook.toMillis() + " ms, 300 columns in "
            + columnsTook.toMillis() + " ms";
        assertTrue(tablesTook.compareTo(Duration.ofSeconds(1)) < 0, times);
        assertTrue(columnsTook.compareTo(Duration.ofSeconds(1)) < 0, times);
      } finally {
        if (container instanceof Schema) {
          execute(connection, "DROP SCHEMA many CASCADE");
        } else {
          execute(
              connection,
              "DROP TABLE IF EXISTS "
                  + names.stream()
                      .map(name -> qualified(provider, container, name))
                      .collect(Collectors.joining(", ")));
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void whatWasDroppedSinceTheLastReadIsNotFound(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    try (var connection = open(server, TestServer.DATABASE)) {
      execute(connection, "CREATE TABLE dropped (id integer)");
      Table dropped;
      try {
        dropped = Catalog.tables(connection, provider, container).stream()
            .filter(table -> table.name().equals("dropped"))
            .findFirst()
            .orElseThrow();
      } finally {
        execute(connection, "DROP TABLE dropped");
      }
      var e =
          assertFailure(Failure.NOT_FOUND, () -> Catalog.columns(connection, provider, dropped));
      assertTrue(e.detail().endsWith("dropped"), e.detail());

      // A schema dropped since it was read, or a database that is not there, rather than one with
      // no tables.
      if (provider == Provider.POSTGRESQL) {
        execute(connection, "CREATE SCHEMA gone");
        var gone = new Schema(HOORI, "gone", false);
        try {
          assertTrue(Catalog.schemas(connection, provider, HOORI).contains(gone));
          assertEquals(List.of(), Catalog.tables(connection, provider, gone));
        } finally {
          execute(connection, "DROP SCHEMA gone");
        }
        assertFailure(Failure.NOT_FOUND, () -> Catalog.tables(connection, provider, gone));
      } else {
        var empty = new Database(TestServer.SPACED_DATABASE, false);
        assertEquals(List.of(), Catalog.tables(connection, provider, empty));
        assertFailure(
            Failure.NOT_FOUND,
            () -> Catalog.tables(connection, provider, new Database("gone", false)));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  @SuppressWarnings("try") // closes the connection in its try, to read from it closed
  void aConnectionThatTheServerEndedIsLost(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = open(server, TestServer.DATABASE);
        var other = open(server, TestServer.DATABASE)) {
      var id = selectInt(
          connection,
          provider == Provider.POSTGRESQL ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()");
      execute(
          other,
          provider == Provider.POSTGRESQL
              ? "SELECT pg_terminate_backend(" + id + ")"
              : "KILL " + id);
      assertFailure(Failure.CONNECTION_LOST, () -> Catalog.databases(connection, provider));
      connection.close();
      assertFailure(Failure.CONNECTION_LOST, () -> Catalog.databases(connection, provider));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void anInterruptCancelsTheQueryAndKeepsTheConnection(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var sleep = provider == Provider.POSTGRESQL ? "SELECT pg_sleep(30)" : "SELECT SLEEP(30)";
    try (var connection = open(server, TestServer.DATABASE)) {
      var failure = new CatalogException[1];
      var interrupted = new boolean[1];
      var caller = Thread.ofPlatform().start(() -> {
        failure[0] = assertThrows(
            CatalogException.class, () -> Catalog.query(connection, provider, sleep, rows -> null));
        interrupted[0] = Thread.currentThread().isInterrupted();
      });
      awaitRunning(server, sleep);
      var started = System.nanoTime();
      caller.interrupt();
      caller.join(Catalog.GRACE.plusSeconds(1));
      assertFalse(caller.isAlive());
      // As soon as the server has stopped it, without waiting out the grace.
      assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Catalog.GRACE) < 0);
      assertEquals(Failure.CANCELLED, failure[0].failure());
      assertTrue(interrupted[0]);

      assertFalse(connection.isClosed());
      assertTrue(Catalog.databases(connection, provider).contains(HOORI));

      // An interrupt that came first: nothing is sent.
      Thread.currentThread().interrupt();
      try {
        assertFailure(Failure.CANCELLED, () -> Catalog.databases(connection, provider));
      } finally {
        assertTrue(Thread.interrupted());
      }
    }
  }

  @Test
  void aCatalogTheUserMayNotReadIsAccessDenied() throws Exception {
    var server = TestServer.of(Provider.POSTGRESQL);
    var customer = new Table(sample(Provider.POSTGRESQL), "customer", Kind.TABLE);
    try (var admin = open(server, TestServer.DATABASE)) {
      // The test user is a superuser, whom no privilege holds back.
      execute(
          admin,
          "CREATE ROLE catalog_reader LOGIN PASSWORD '" + TestServer.PASSWORD + "'",
          "REVOKE SELECT ON pg_catalog.pg_attribute FROM PUBLIC");
      try {
        var reader = Connector.open(
                server.profile().withUser("catalog_reader"),
                TestServer.password(),
                server.driver(),
                TIMEOUT)
            .connection();
        try (reader) {
          assertFailure(
              Failure.ACCESS_DENIED, () -> Catalog.columns(reader, Provider.POSTGRESQL, customer));
        }
      } finally {
        execute(
            admin, "GRANT SELECT ON pg_catalog.pg_attribute TO PUBLIC", "DROP ROLE catalog_reader");
      }
    }
  }

  @Test
  void onlyTheKeyOfAPrimaryKeyCountsNotWhatItIncludes() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(TestServer.of(provider), TestServer.DATABASE)) {
      execute(
          connection, "CREATE TABLE included (id integer, j jsonb, PRIMARY KEY (id) INCLUDE (j))");
      try {
        var table = new Table(sample(provider), "included", Kind.TABLE);
        assertEquals(
            List.of(1, 0),
            Catalog.columns(connection, provider, table).stream()
                .map(Column::keyPosition)
                .toList());
      } finally {
        execute(connection, "DROP TABLE included");
      }
    }
  }

  @Test
  void aDomainThatIsNotNullMakesItsColumnsNotNull() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(TestServer.of(provider), TestServer.DATABASE)) {
      execute(
          connection,
          "CREATE DOMAIN required AS integer NOT NULL",
          "CREATE DOMAIN over_required AS required CHECK (VALUE > 0)",
          "CREATE DOMAIN optional AS integer",
          "CREATE TABLE domained (a required, b over_required, c optional, d integer)",
          "CREATE VIEW domained_view AS SELECT * FROM domained");
      try {
        var table = new Table(sample(provider), "domained", Kind.TABLE);
        assertEquals(
            List.of(false, false, true, true),
            Catalog.columns(connection, provider, table).stream()
                .map(Column::nullable)
                .toList());
        // A view's column can be NULL whatever its domain, as of an outer join.
        var view = new Table(sample(provider), "domained_view", Kind.VIEW);
        assertEquals(
            List.of(true, true, true, true),
            Catalog.columns(connection, provider, view).stream()
                .map(Column::nullable)
                .toList());
      } finally {
        execute(
            connection,
            "DROP VIEW domained_view",
            "DROP TABLE domained",
            "DROP DOMAIN over_required",
            "DROP DOMAIN required",
            "DROP DOMAIN optional");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void sampleForeignKeysAreReadInBothDirectionsAndRecordedAsCatalogReads(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    var customer = new Table(container, "customer", Kind.TABLE);
    var purchase = new Table(container, "purchase", Kind.TABLE);
    var line = new Table(container, "purchase_line", Kind.TABLE);
    var reading = new Table(container, "reading", Kind.TABLE);
    var view = new Table(container, "active_customer", Kind.VIEW);
    try (var connection = open(server, TestServer.DATABASE)) {
      var recorded = new CopyOnWriteArrayList<Sent>();
      var keys = Journal.recording(
          recorded::add, () -> Catalog.foreignKeys(connection, provider, purchase));
      assertEquals(2, keys.size(), keys.toString());
      var outgoing =
          keys.stream().filter(k -> k.table().equals(purchase)).findFirst().orElseThrow();
      var incoming =
          keys.stream().filter(k -> k.referenced().equals(purchase)).findFirst().orElseThrow();
      assertEquals(customer, outgoing.referenced());
      assertEquals(List.of("customer_id"), outgoing.columns());
      assertEquals(List.of("id"), outgoing.referencedColumns());
      assertEquals(line, incoming.table());
      assertEquals(List.of("purchase_id"), incoming.columns());
      assertEquals(List.of("id"), incoming.referencedColumns());

      assertEquals(1, recorded.size(), recorded.toString());
      assertEquals(Purpose.CATALOG, recorded.getFirst().purpose());
      assertEquals(Outcome.rows(2), recorded.getFirst().outcome());

      assertEquals(List.of(outgoing), Catalog.foreignKeys(connection, provider, customer));
      assertEquals(List.of(), Catalog.foreignKeys(connection, provider, reading));

      recorded.clear();
      assertEquals(
          List.of(),
          Journal.recording(recorded::add, () -> Catalog.foreignKeys(connection, provider, view)));
      assertTrue(recorded.isEmpty(), recorded.toString());
    }
  }

  // MariaDB hides a key's rules from a user who may only read its table, MySQL does not.
  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aUserWhoMayOnlyReadSeesTheKeys(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var root = server.profile().withUser("root");
    var reader = server.profile().withUser("fk_reader");
    var purchase = new Table(HOORI, "purchase", Kind.TABLE);
    var customer = new Table(HOORI, "customer", Kind.TABLE);
    try (var admin = Connector.open(
            root, ("root-" + TestServer.PASSWORD).toCharArray(), server.driver(), TIMEOUT)
        .connection()) {
      execute(
          admin,
          "CREATE USER IF NOT EXISTS 'fk_reader'@'%' IDENTIFIED BY '" + TestServer.PASSWORD + "'",
          "GRANT SELECT ON hoori.* TO 'fk_reader'@'%'");
      try (var connection = Connector.open(reader, TestServer.password(), server.driver(), TIMEOUT)
          .connection()) {
        var rule = provider == Provider.MARIADB ? null : Rule.NO_ACTION;
        var keys = Catalog.foreignKeys(connection, provider, customer);
        // The sample's key is unnamed, and MariaDB and MySQL name it each their own way.
        assertEquals(
            List.of(new ForeignKey(
                keys.getFirst().name(),
                purchase,
                List.of("customer_id"),
                customer,
                List.of("id"),
                rule,
                rule)),
            keys);
      } finally {
        execute(admin, "DROP USER 'fk_reader'@'%'");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void compositeSelfReferencingAndUniqueForeignKeysAreRead(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    var parent = new Table(container, "fk_parent", Kind.TABLE);
    var child = new Table(container, "fk_child", Kind.TABLE);
    try (var connection = open(server, TestServer.DATABASE)) {
      execute(
          connection,
          "CREATE TABLE fk_parent ("
              + "id integer PRIMARY KEY, "
              + "code varchar(20) NOT NULL UNIQUE, "
              + "a integer NOT NULL, "
              + "b integer NOT NULL, "
              + "UNIQUE (b, a))",
          "CREATE TABLE fk_child ("
              + "id integer PRIMARY KEY, "
              + "parent_code varchar(20), "
              + "ca integer, "
              + "cb integer, "
              + "self_id integer, "
              + "CONSTRAINT fk_unique FOREIGN KEY (parent_code) REFERENCES fk_parent (code), "
              + "CONSTRAINT fk_comp FOREIGN KEY (cb, ca) REFERENCES fk_parent (b, a), "
              + "CONSTRAINT fk_self FOREIGN KEY (self_id) REFERENCES fk_child (id))");
      try {
        var childKeys = Catalog.foreignKeys(connection, provider, child);
        assertEquals(
            List.of("fk_comp", "fk_self", "fk_unique"),
            childKeys.stream().map(ForeignKey::name).toList());
        var comp = childKeys.get(0);
        assertEquals(child, comp.table());
        assertEquals(List.of("cb", "ca"), comp.columns());
        assertEquals(parent, comp.referenced());
        assertEquals(List.of("b", "a"), comp.referencedColumns());

        var self = childKeys.get(1);
        assertEquals(child, self.table());
        assertEquals(child, self.referenced());
        assertEquals(List.of("self_id"), self.columns());
        assertEquals(List.of("id"), self.referencedColumns());

        var unique = childKeys.get(2);
        assertEquals(child, unique.table());
        assertEquals(parent, unique.referenced());
        assertEquals(List.of("parent_code"), unique.columns());
        assertEquals(List.of("code"), unique.referencedColumns());

        assertEquals(List.of(comp, unique), Catalog.foreignKeys(connection, provider, parent));
      } finally {
        execute(connection, "DROP TABLE IF EXISTS fk_child", "DROP TABLE IF EXISTS fk_parent");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void foreignKeyRulesAreReadBack(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var container = sample(provider);
    var parent = new Table(container, "fk_rule_parent", Kind.TABLE);
    var child = new Table(container, "fk_rule_child", Kind.TABLE);
    boolean pg = provider == Provider.POSTGRESQL;
    try (var connection = open(server, TestServer.DATABASE)) {
      // InnoDB on MySQL and MariaDB rejects SET DEFAULT, so SET DEFAULT is covered on PostgreSQL.
      execute(
          connection,
          "CREATE TABLE fk_rule_parent (id integer PRIMARY KEY)",
          "CREATE TABLE fk_rule_child ("
              + "id integer PRIMARY KEY, "
              + "a integer, b integer, c integer, d integer"
              + (pg ? ", e integer DEFAULT 0" : "")
              + ", CONSTRAINT fk_r1 FOREIGN KEY (a) REFERENCES fk_rule_parent (id)"
              + " ON UPDATE NO ACTION ON DELETE RESTRICT"
              + ", CONSTRAINT fk_r2 FOREIGN KEY (b) REFERENCES fk_rule_parent (id)"
              + " ON UPDATE RESTRICT ON DELETE NO ACTION"
              + ", CONSTRAINT fk_r3 FOREIGN KEY (c) REFERENCES fk_rule_parent (id)"
              + " ON UPDATE CASCADE ON DELETE SET NULL"
              + ", CONSTRAINT fk_r4 FOREIGN KEY (d) REFERENCES fk_rule_parent (id)"
              + " ON UPDATE SET NULL ON DELETE CASCADE"
              + (pg
                  ? ", CONSTRAINT fk_r5 FOREIGN KEY (e) REFERENCES fk_rule_parent (id)"
                      + " ON UPDATE SET DEFAULT ON DELETE SET DEFAULT"
                  : "")
              + ")");
      try {
        var expected = new ArrayList<ForeignKey>();
        expected.add(new ForeignKey(
            "fk_r1", child, List.of("a"), parent, List.of("id"), Rule.NO_ACTION, Rule.RESTRICT));
        expected.add(new ForeignKey(
            "fk_r2", child, List.of("b"), parent, List.of("id"), Rule.RESTRICT, Rule.NO_ACTION));
        expected.add(new ForeignKey(
            "fk_r3", child, List.of("c"), parent, List.of("id"), Rule.CASCADE, Rule.SET_NULL));
        expected.add(new ForeignKey(
            "fk_r4", child, List.of("d"), parent, List.of("id"), Rule.SET_NULL, Rule.CASCADE));
        if (pg) {
          expected.add(new ForeignKey(
              "fk_r5",
              child,
              List.of("e"),
              parent,
              List.of("id"),
              Rule.SET_DEFAULT,
              Rule.SET_DEFAULT));
        }
        assertEquals(expected, Catalog.foreignKeys(connection, provider, child));
        assertEquals(expected, Catalog.foreignKeys(connection, provider, parent));
      } finally {
        execute(
            connection,
            "DROP TABLE IF EXISTS fk_rule_child",
            "DROP TABLE IF EXISTS fk_rule_parent");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void foreignKeysCrossSchemasOnPostgresqlAndDatabasesOnMysqlFamily(Provider provider)
      throws Exception {
    var server = TestServer.of(provider);
    var local = sample(provider);
    Container other = provider == Provider.POSTGRESQL
        ? new Schema(HOORI, "fk_other", false)
        : new Database(TestServer.SPACED_DATABASE, false);
    var localParent = new Table(local, "fk_local_parent", Kind.TABLE);
    var localChild = new Table(local, "fk_local_child", Kind.TABLE);
    var remoteParent = new Table(other, "fk_remote_parent", Kind.TABLE);
    var remoteChild = new Table(other, "fk_remote_child", Kind.TABLE);
    try (var connection = open(server, TestServer.DATABASE)) {
      if (other instanceof Schema) {
        execute(connection, "CREATE SCHEMA " + provider.quote(other.name()));
      }
      try {
        execute(
            connection,
            "CREATE TABLE " + qualified(provider, other, remoteParent.name())
                + " (id integer PRIMARY KEY)",
            "CREATE TABLE " + qualified(provider, local, localParent.name())
                + " (id integer PRIMARY KEY)",
            "CREATE TABLE " + qualified(provider, local, localChild.name())
                + " (id integer PRIMARY KEY, rp integer,"
                + " CONSTRAINT fk_to_other FOREIGN KEY (rp) REFERENCES "
                + qualified(provider, other, remoteParent.name()) + " (id))",
            "CREATE TABLE " + qualified(provider, other, remoteChild.name())
                + " (id integer PRIMARY KEY, lp integer,"
                + " CONSTRAINT fk_from_other FOREIGN KEY (lp) REFERENCES "
                + qualified(provider, local, localParent.name()) + " (id))");
        var toOther = Catalog.foreignKeys(connection, provider, localChild);
        assertEquals(1, toOther.size(), toOther.toString());
        assertEquals("fk_to_other", toOther.getFirst().name());
        assertEquals(localChild, toOther.getFirst().table());
        assertEquals(remoteParent, toOther.getFirst().referenced());
        assertEquals(toOther, Catalog.foreignKeys(connection, provider, remoteParent));

        var fromOther = Catalog.foreignKeys(connection, provider, remoteChild);
        assertEquals(1, fromOther.size(), fromOther.toString());
        assertEquals("fk_from_other", fromOther.getFirst().name());
        assertEquals(remoteChild, fromOther.getFirst().table());
        assertEquals(localParent, fromOther.getFirst().referenced());
        assertEquals(fromOther, Catalog.foreignKeys(connection, provider, localParent));
      } finally {
        if (other instanceof Schema) {
          execute(
              connection,
              "DROP TABLE IF EXISTS " + qualified(provider, local, localChild.name()),
              "DROP SCHEMA IF EXISTS " + provider.quote(other.name()) + " CASCADE",
              "DROP TABLE IF EXISTS " + qualified(provider, local, localParent.name()));
        } else {
          execute(
              connection,
              "DROP TABLE IF EXISTS " + qualified(provider, local, localChild.name()),
              "DROP TABLE IF EXISTS " + qualified(provider, other, remoteChild.name()),
              "DROP TABLE IF EXISTS " + qualified(provider, other, remoteParent.name()),
              "DROP TABLE IF EXISTS " + qualified(provider, local, localParent.name()));
        }
      }
    }
  }

  @Test
  void aPartitionedPostgresqlTableListsItsForeignKeysOnce() throws Exception {
    var provider = Provider.POSTGRESQL;
    var schema = sample(provider);
    var parent = new Table(schema, "fk_part_parent", Kind.TABLE);
    var child = new Table(schema, "fk_part_child", Kind.TABLE);
    try (var connection = open(TestServer.of(provider), TestServer.DATABASE)) {
      execute(
          connection,
          "CREATE TABLE fk_part_parent (id integer PRIMARY KEY) PARTITION BY RANGE (id)",
          "CREATE TABLE fk_part_parent_1 PARTITION OF fk_part_parent FOR VALUES FROM (0) TO (100)",
          "CREATE TABLE fk_part_parent_2 PARTITION OF fk_part_parent FOR VALUES FROM (100) TO (200)",
          "CREATE TABLE fk_part_child ("
              + "id integer, parent_id integer, PRIMARY KEY (id, parent_id), "
              + "CONSTRAINT fk_part FOREIGN KEY (parent_id) REFERENCES fk_part_parent (id))"
              + " PARTITION BY RANGE (id)",
          "CREATE TABLE fk_part_child_1 PARTITION OF fk_part_child FOR VALUES FROM (0) TO (100)",
          "CREATE TABLE fk_part_child_2 PARTITION OF fk_part_child FOR VALUES FROM (100) TO (200)");
      try {
        var expected = List.of(new ForeignKey(
            "fk_part",
            child,
            List.of("parent_id"),
            parent,
            List.of("id"),
            Rule.NO_ACTION,
            Rule.NO_ACTION));
        assertEquals(expected, Catalog.foreignKeys(connection, provider, child));
        assertEquals(expected, Catalog.foreignKeys(connection, provider, parent));
      } finally {
        execute(
            connection,
            "DROP TABLE IF EXISTS fk_part_child",
            "DROP TABLE IF EXISTS fk_part_parent");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void tablesWhoseNamesDifferOnlyInCaseKeepTheirOwnForeignKeys(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var lowerParent = new Table(HOORI, "fk_case_p", Kind.TABLE);
    var upperParent = new Table(HOORI, "FK_CASE_P", Kind.TABLE);
    var lowerChild = new Table(HOORI, "fk_case_c", Kind.TABLE);
    var upperChild = new Table(HOORI, "FK_CASE_C", Kind.TABLE);
    try (var connection = open(server, TestServer.DATABASE)) {
      execute(
          connection,
          "CREATE TABLE `fk_case_p` (a integer, b integer, PRIMARY KEY (a, b))",
          "CREATE TABLE `FK_CASE_P` (a integer, b integer, PRIMARY KEY (a, b))",
          "CREATE TABLE `fk_case_c` (id integer PRIMARY KEY, a integer, b integer,"
              + " CONSTRAINT fk_case_lo FOREIGN KEY (a, b) REFERENCES `fk_case_p` (a, b))",
          "CREATE TABLE `FK_CASE_C` (id integer PRIMARY KEY, a integer, b integer,"
              + " CONSTRAINT fk_case_hi FOREIGN KEY (a, b) REFERENCES `FK_CASE_P` (a, b))");
      try {
        var lo = Catalog.foreignKeys(connection, provider, lowerChild);
        assertEquals(1, lo.size(), lo.toString());
        assertEquals("fk_case_lo", lo.getFirst().name());
        assertEquals(lowerChild, lo.getFirst().table());
        assertEquals(lowerParent, lo.getFirst().referenced());
        assertEquals(List.of("a", "b"), lo.getFirst().columns());
        assertEquals(lo, Catalog.foreignKeys(connection, provider, lowerParent));

        var hi = Catalog.foreignKeys(connection, provider, upperChild);
        assertEquals(1, hi.size(), hi.toString());
        assertEquals("fk_case_hi", hi.getFirst().name());
        assertEquals(upperChild, hi.getFirst().table());
        assertEquals(upperParent, hi.getFirst().referenced());
        assertEquals(List.of("a", "b"), hi.getFirst().columns());
        assertEquals(hi, Catalog.foreignKeys(connection, provider, upperParent));
      } finally {
        execute(
            connection,
            "DROP TABLE IF EXISTS `fk_case_c`",
            "DROP TABLE IF EXISTS `FK_CASE_C`",
            "DROP TABLE IF EXISTS `fk_case_p`",
            "DROP TABLE IF EXISTS `FK_CASE_P`");
      }
    }
  }

  private static Container sample(Provider provider) {
    return provider == Provider.POSTGRESQL ? new Schema(HOORI, "public", false) : HOORI;
  }

  private static String integer(Provider provider) {
    return integer("", 0).type(provider);
  }

  private static String qualified(Provider provider, Container container, String name) {
    return provider.quote(container.name()) + "." + provider.quote(name);
  }

  // The sample's tables and view as the catalog reads them, without those another test left behind.
  private static List<Table> sampleTables(Connection connection, Provider provider)
      throws CatalogException {
    return Catalog.tables(connection, provider, sample(provider)).stream()
        .filter(table -> SAMPLE.containsKey(table.name()))
        .toList();
  }

  private static void assertSystemLast(List<Boolean> system, List<String> names) {
    assertTrue(system.contains(true), names.toString());
    assertEquals(system.stream().sorted().toList(), system, names.toString());
  }

  private static Connection open(TestServer server, String database) throws Exception {
    return Connector.open(
            server.profile().withDatabase(database),
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

  private static int selectInt(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  // Until the server runs the statement, so that the interrupt finds it there.
  private static void awaitRunning(TestServer server, String sql) throws Exception {
    var query = server.provider() == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity WHERE query = ? AND state = 'active'"
        : "SELECT count(*) FROM information_schema.processlist WHERE info = ?";
    try (var connection = open(server, TestServer.DATABASE);
        var statement = connection.prepareStatement(query)) {
      statement.setString(1, sql);
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
    throw new AssertionError("The statement did not start: " + sql);
  }

  static CatalogException assertFailure(Failure failure, Executable executable) {
    var e = assertThrows(CatalogException.class, executable);
    assertEquals(failure, e.failure(), e.getMessage());
    return e;
  }
}

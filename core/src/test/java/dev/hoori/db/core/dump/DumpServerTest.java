package dev.hoori.db.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An export of each provider's real server, read back into an empty database statement by
 * statement as its text splits, gives the same objects, definitions and rows.
 */
class DumpServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @TempDir
  Path folder;

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aDumpReadBackGivesTheSameObjectsDefinitionsAndRows(Provider provider) throws Exception {
    try (var databases = new Databases(provider)) {
      var file = folder.resolve("dump.sql");
      // With the keys checked, which tables that refer to each other drop despite.
      var options = new Dump.Options(true, false, 2, true, false, true);
      var written = export(databases, Dump.Content.STRUCTURE_AND_DATA, options, file, l -> true);
      assertTrue(written.rows() > 0, written.toString());
      var text = Files.readString(file);
      // Named without their database, so that it reads into another.
      assertFalse(
          text.lines().filter(l -> !l.startsWith("--")).anyMatch(l -> l.contains(databases.source)),
          text);
      // Twice, as what it drops goes first.
      databases.run(text);
      databases.run(text);
      databases.assertSame();
      try (var files = Files.list(folder)) {
        assertEquals(List.of(file), files.toList(), "Nothing else is left");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theServersOwnClientReadsItBackAlike(Provider provider) throws Exception {
    try (var databases = new Databases(provider)) {
      var file = folder.resolve("client.sql");
      // Keys right after the tables, but those of the circle of parent and child, which follow the
      // rows, checked.
      var options = new Dump.Options(false, false, 3, true, false, false);
      export(databases, Dump.Content.STRUCTURE_AND_DATA, options, file, l -> true);
      var ran = TestServer.of(provider).client(file, databases.target);
      assertEquals(0, ran.getExitCode(), ran.getStdout() + ran.getStderr());
      databases.assertSame();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theStructureAndTheDataGoApartAndTogetherGiveTheSame(Provider provider) throws Exception {
    try (var databases = new Databases(provider)) {
      var structure = folder.resolve("structure.sql");
      var data = folder.resolve("data.sql");
      // Without a circle of keys, which rows that go in where keys are checked cannot have.
      try (var connection = databases.open(databases.source)) {
        execute(
            connection,
            provider == Provider.POSTGRESQL
                ? "ALTER TABLE parent DROP CONSTRAINT parent_child"
                : "ALTER TABLE parent DROP FOREIGN KEY parent_child");
      }
      // Keys right after the tables, checked, with the rows in the order they refer to each other.
      var options = new Dump.Options(false, false, 100, false, false, false);
      export(databases, Dump.Content.STRUCTURE, options, structure, l -> true);
      export(databases, Dump.Content.DATA, options, data, l -> true);
      assertFalse(Files.readString(structure).contains("INSERT INTO"));
      assertFalse(Files.readString(data).contains("CREATE TABLE"));
      databases.run(Files.readString(structure));
      assertEquals(0, databases.rows(databases.target, "parent").size());
      databases.run(Files.readString(data));
      databases.assertSame();
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aFailedOrCancelledDumpLeavesTheChosenFileAsItWas(Provider provider) throws Exception {
    try (var databases = new Databases(provider)) {
      var file = folder.resolve("kept.sql");
      Files.writeString(file, "before");
      var database = new Database(databases.source, false);
      var gone = Item.of(databases.container(), Item.Kind.TABLE, "no_such_table");
      var choice = new Dump.Choice(
          database, List.of(gone), Dump.Content.STRUCTURE_AND_DATA, Dump.Options.DEFAULT);
      try (var connection = databases.open(databases.source)) {
        var failed = assertThrows(
            QueryException.class,
            () -> Dump.write(connection, provider, choice, file, (item, rows) -> {}));
        assertEquals(QueryException.Failure.NOT_FOUND, failed.failure());
        var listed = Dump.list(connection, provider, databases.container());
        var all = new Dump.Choice(
            database,
            listed.stream().map(Listed::item).toList(),
            Dump.Content.STRUCTURE_AND_DATA,
            Dump.Options.DEFAULT);
        Thread.currentThread().interrupt();
        try {
          var cancelled = assertThrows(
              QueryException.class,
              () -> Dump.write(connection, provider, all, file, (item, rows) -> {}));
          assertEquals(QueryException.Failure.CANCELLED, cancelled.failure());
        } finally {
          Thread.interrupted();
        }
        // Whole, the file still waits to be let take the chosen one's place.
        var refused = assertThrows(
            QueryException.class,
            () -> Dump.write(connection, provider, all, file, new Dump.Progress() {
              @Override
              public void at(Item item, long rows) {
                // Nothing to show.
              }

              @Override
              public boolean replacing() {
                return false;
              }
            }));
        assertEquals(QueryException.Failure.CANCELLED, refused.failure());
      }
      assertEquals("before", Files.readString(file));
      try (var files = Files.list(folder)) {
        assertEquals(List.of(file), files.toList());
      }
    }
  }

  private static Dump.Written export(
      Databases databases,
      Dump.Content content,
      Dump.Options options,
      Path file,
      Predicate<Listed> chosen)
      throws Exception {
    try (var connection = databases.open(databases.source)) {
      var listed = Dump.list(connection, databases.provider, databases.container());
      var kinds = listed.stream().map(l -> l.item().kind()).distinct().toList();
      assertTrue(
          kinds.containsAll(List.of(
              Item.Kind.TABLE,
              Item.Kind.VIEW,
              Item.Kind.FUNCTION,
              Item.Kind.PROCEDURE,
              Item.Kind.TRIGGER)),
          listed.toString());
      var items = listed.stream().filter(chosen).map(Listed::item).toList();
      var steps = new ArrayList<Item>();
      var choice = new Dump.Choice(new Database(databases.source, false), items, content, options);
      var written = Dump.write(connection, databases.provider, choice, file, (item, rows) -> {
        if (steps.isEmpty() || !steps.getLast().equals(item)) {
          steps.add(item);
        }
      });
      assertFalse(steps.isEmpty());
      return written;
    }
  }

  // A database of objects and rows, and an empty one to read its export into, on the provider's
  // server, as a user who may create both and what goes into them; dropped at the end.
  private static final class Databases implements AutoCloseable {

    final Provider provider;
    final String source;
    final String target;
    private final TestServer server;

    Databases(Provider provider) throws Exception {
      this.provider = provider;
      this.server = TestServer.of(provider);
      var mark = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
      this.source = "dump_" + mark + "_from";
      this.target = "dump_" + mark + "_to";
      try {
        try (var admin = open(TestServer.DATABASE)) {
          execute(admin, "CREATE DATABASE " + provider.quote(source));
          execute(admin, "CREATE DATABASE " + provider.quote(target));
        }
        try (var connection = open(source)) {
          for (var sql : provider == Provider.POSTGRESQL ? postgresql() : mysql(provider)) {
            execute(connection, sql);
          }
        }
      } catch (Exception e) {
        try {
          close();
        } catch (Exception dropped) {
          e.addSuppressed(dropped);
        }
        throw e;
      }
    }

    // What holds the objects: PostgreSQL's schema public, the database itself elsewhere.
    dev.hoori.db.core.catalog.Container container() {
      var database = new Database(source, false);
      return provider == Provider.POSTGRESQL
          ? new dev.hoori.db.core.catalog.Schema(database, "public", false)
          : database;
    }

    private dev.hoori.db.core.catalog.Container targetContainer() {
      var database = new Database(target, false);
      return provider == Provider.POSTGRESQL
          ? new dev.hoori.db.core.catalog.Schema(database, "public", false)
          : database;
    }

    Connection open(String database) throws ConnectionException {
      var profile = server.profile().withDatabase(database);
      var password = TestServer.password();
      if (provider != Provider.POSTGRESQL) {
        profile = profile.withUser("root");
        password = ("root-" + TestServer.PASSWORD).toCharArray();
      }
      return Connector.open(profile, password, server.driver(), TIMEOUT).connection();
    }

    // Each statement of the text, as it splits.
    void run(String text) throws Exception {
      try (var connection = open(target)) {
        for (var statement : Statements.split(text, provider)) {
          var sql = text.substring(statement.start(), statement.end());
          try {
            execute(connection, sql);
          } catch (SQLException e) {
            throw new AssertionError(e.getMessage() + " in:\n" + sql, e);
          }
        }
      }
    }

    void assertSame() throws Exception {
      try (var from = open(source);
          var to = open(target)) {
        var objects = objects(from, container());
        assertEquals(objects.keySet(), objects(to, targetContainer()).keySet());
        var was = definitions(from, container());
        var now = definitions(to, targetContainer());
        assertEquals(was, now);
        for (var name : objects.keySet()) {
          if (objects.get(name).kind() == Item.Kind.TABLE) {
            assertEquals(rows(source, objects.get(name).name()), rows(target, name), name);
          }
        }
      }
    }

    private Map<String, Item> objects(Connection connection, dev.hoori.db.core.catalog.Container c)
        throws Exception {
      var objects = new TreeMap<String, Item>();
      for (var listed : Dump.list(connection, provider, c)) {
        objects.put(listed.item().shown(), listed.item());
      }
      return objects;
    }

    // What creates each object, by its name.
    private Map<String, List<String>> definitions(
        Connection connection, dev.hoori.db.core.catalog.Container c) throws Exception {
      var items = Dump.list(connection, provider, c).stream().map(Listed::item).toList();
      var definitions = new TreeMap<String, List<String>>();
      for (var definition : Dump.definitions(connection, provider, items)) {
        var all = new ArrayList<>(definition.statements());
        all.addAll(definition.restore());
        definitions.put(definition.item().shown(), all);
      }
      return definitions;
    }

    // The table's rows as the server writes them, by their first column.
    List<List<String>> rows(String database, String table) throws Exception {
      var qualified = provider == Provider.POSTGRESQL
          ? "public." + provider.quote(table)
          : provider.quote(table);
      try (var connection = open(database);
          var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT * FROM " + qualified + " ORDER BY 1")) {
        var rows = new ArrayList<List<String>>();
        var columns = result.getMetaData().getColumnCount();
        while (result.next()) {
          var row = new ArrayList<String>();
          for (var i = 1; i <= columns; i++) {
            row.add(text(result, i));
          }
          rows.add(row);
        }
        return rows;
      }
    }

    // Binary data by its bytes, anything else as the driver writes it.
    private static String text(java.sql.ResultSet result, int column) throws SQLException {
      return switch (result.getMetaData().getColumnType(column)) {
        case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
          var bytes = result.getBytes(column);
          yield bytes == null ? null : HexFormat.of().formatHex(bytes);
        }
        default -> result.getString(column);
      };
    }

    @Override
    public void close() throws ConnectionException, SQLException {
      try (var admin = open(TestServer.DATABASE)) {
        var force = provider == Provider.POSTGRESQL ? " WITH (FORCE)" : "";
        execute(admin, "DROP DATABASE IF EXISTS " + provider.quote(source) + force);
        execute(admin, "DROP DATABASE IF EXISTS " + provider.quote(target) + force);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setEscapeProcessing(false);
      statement.execute(sql);
    }
  }

  private static List<String> postgresql() {
    return List.of(
        "CREATE SEQUENCE counter START 5",
        "SELECT nextval('counter'), nextval('counter')",
        "CREATE TABLE parent (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " name text NOT NULL DEFAULT 'x', child_id integer, made timestamptz DEFAULT now())",
        "CREATE TABLE child (id serial PRIMARY KEY, parent_id integer NOT NULL"
            + " REFERENCES parent (id), note text, CONSTRAINT short CHECK (length(note) < 100))",
        "ALTER TABLE parent ADD CONSTRAINT parent_child FOREIGN KEY (child_id) REFERENCES child (id)",
        "CREATE INDEX parent_name ON parent (lower(name))",
        "COMMENT ON TABLE parent IS 'The parents'' table'",
        "COMMENT ON COLUMN parent.name IS 'Its name'",
        "CREATE TABLE typed (id integer PRIMARY KEY, b bytea, n numeric, nan numeric,"
            + " f float8, r real, inf float8, bo boolean, d date, ts timestamp, tz timestamptz,"
            + " iv interval, j jsonb, arr integer[], u uuid, t text,"
            + " twice integer GENERATED ALWAYS AS (id * 2) STORED, c varchar(10) COLLATE \"C\")",
        "CREATE UNLOGGED TABLE scratch (k text)",
        // Its name breaks the lines of the comments that name it, which stay comments.
        "CREATE TABLE \"line\nbreak\" (k text)",
        "INSERT INTO \"line\nbreak\" VALUES ('x')",
        "CREATE TABLE measure (id integer, at date NOT NULL) PARTITION BY RANGE (at)",
        "CREATE TABLE measure_2024 PARTITION OF measure"
            + " FOR VALUES FROM ('2024-01-01') TO ('2025-01-01')",
        "CREATE FUNCTION twice(x integer) RETURNS integer LANGUAGE sql IMMUTABLE"
            + " AS $$ SELECT x * 2 $$",
        "CREATE FUNCTION twice(x text) RETURNS text LANGUAGE sql IMMUTABLE AS $$ SELECT x || x $$",
        "CREATE VIEW z_base AS SELECT id, name FROM parent",
        "CREATE VIEW a_top AS SELECT id, twice(id) AS t FROM z_base",
        "CREATE MATERIALIZED VIEW counted AS SELECT count(*) AS n FROM parent",
        "CREATE FUNCTION upper_name() RETURNS trigger LANGUAGE plpgsql"
            + " AS $$ BEGIN NEW.name := upper(NEW.name); RETURN NEW; END $$",
        "CREATE TRIGGER parent_upper BEFORE INSERT ON parent FOR EACH ROW"
            + " EXECUTE FUNCTION upper_name()",
        "CREATE PROCEDURE touch() LANGUAGE plpgsql AS $$ BEGIN PERFORM 1; END $$",
        "INSERT INTO parent (name) VALUES ('ada'), ('it''s \\ quoted'), ('Grüße ✓')",
        "INSERT INTO child (parent_id, note) VALUES (1, 'first'), (2, NULL), (2, E'line\\nbreak')",
        "UPDATE parent SET child_id = 1 WHERE id = 2",
        "INSERT INTO typed (id, b, n, nan, f, r, inf, bo, d, ts, tz, iv, j, arr, u, t, c) VALUES"
            + " (1, '\\x00ff10', 12345678901234567890.123456789, 'NaN', 0.1, 1.5, 'Infinity',"
            + " true, '0044-03-15 BC', '2024-03-31 02:30:00.123456', '2024-03-31 02:30:00+02',"
            + " '1 year 2 mons 3 days 04:05:06.7', '{\"a\": [1, \"x\"]}', '{1,NULL,3}',"
            + " 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'tab\there ''q'' \\ end', 'abc'),"
            + " (2, NULL, NULL, NULL, NULL, NULL, '-Infinity', false, NULL, NULL, NULL, NULL,"
            + " NULL, '{}', NULL, '', NULL)",
        "INSERT INTO scratch VALUES ('kept')",
        "INSERT INTO measure VALUES (1, '2024-05-01'), (2, '2024-06-01')",
        "REFRESH MATERIALIZED VIEW counted");
  }

  private static List<String> mysql(Provider provider) {
    var statements = new ArrayList<>(List.of(
        "CREATE TABLE parent (id integer AUTO_INCREMENT PRIMARY KEY, name varchar(50) NOT NULL"
            + " DEFAULT 'x' COMMENT 'Its name', child_id integer, KEY parent_name (name))"
            + " COMMENT 'The parents'' table'",
        "CREATE TABLE child (id integer AUTO_INCREMENT PRIMARY KEY, parent_id integer NOT NULL,"
            + " note text, CONSTRAINT child_parent FOREIGN KEY (parent_id) REFERENCES parent (id))",
        "ALTER TABLE parent ADD CONSTRAINT parent_child FOREIGN KEY (child_id) REFERENCES child"
            + " (id)",
        "CREATE TABLE typed (id integer PRIMARY KEY, bits bit(5), bl blob, d date,"
            + " dt datetime(3), ts timestamp(6) NULL, t time, y year, j json,"
            + " e enum('a', 'b'), s set('x', 'y'), f float, dbl double, amount decimal(30, 9),"
            + " u bigint unsigned, txt text, g geometry NULL,"
            + " twice integer GENERATED ALWAYS AS (id * 2) VIRTUAL,"
            + " FULLTEXT KEY typed_txt (txt))",
        "CREATE TABLE plain (k varchar(10)) ENGINE=MyISAM",
        "CREATE FUNCTION twice(x integer) RETURNS integer DETERMINISTIC RETURN x * 2",
        "CREATE VIEW z_base AS SELECT id, name FROM parent",
        "CREATE VIEW a_top AS SELECT id, twice(id) AS t FROM z_base",
        "CREATE PROCEDURE touch() BEGIN SELECT 1; SELECT 2; END",
        "CREATE TRIGGER parent_none BEFORE INSERT ON parent FOR EACH ROW BEGIN"
            + " IF NEW.name = '' THEN SET NEW.name = 'none'; END IF; END",
        "INSERT INTO parent (name) VALUES ('ada'), ('it''s \\\\ quoted'), ('Grüße ✓'), ('')",
        "INSERT INTO child (parent_id, note) VALUES (1, 'first'), (2, NULL),"
            + " (2, 'line\\nbreak and \\0 nul')",
        "UPDATE parent SET child_id = 1 WHERE id = 2",
        "DELETE FROM child WHERE id = 3",
        "INSERT INTO typed (id, bits, bl, d, dt, ts, t, y, j, e, s, f, dbl, amount, u, txt, g)"
            + " VALUES (1, b'10101', X'00ff10', '1815-12-10', '2024-03-31 02:30:00.123',"
            + " '2024-03-31 02:30:00.123456', '-838:59:59', 2024, '{\"a\": [1, \"x\"]}', 'b',"
            + " 'x,y', 0.1, 1e300, 12345678901234567890.123456789, 18446744073709551615,"
            + " 'tab\\there ''q'' \\\\ end', ST_GeomFromText('POINT(1 2)')),"
            + " (2, NULL, X'', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '', NULL, NULL, NULL,"
            + " 0, '', NULL)",
        "INSERT INTO plain VALUES ('kept')"));
    if (provider == Provider.MARIADB) {
      statements.add("CREATE SEQUENCE counter START WITH 5");
      statements.add("SELECT NEXTVAL(counter), NEXTVAL(counter)");
    }
    return statements;
  }
}

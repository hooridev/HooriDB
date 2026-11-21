package dev.hoori.db.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
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

  // What PostgreSQL's catalog tells of the objects of public: relations, columns, indexes with
  // whether they are valid, constraints, views, routines, triggers and sequences.
  private static final List<String> POSTGRESQL_FACTS =
      List.of("""
      SELECT c.relname, c.relkind, c.relpersistence, c.relispartition,
        pg_catalog.pg_get_expr(c.relpartbound, c.oid), c.reloptions::text,
        pg_catalog.obj_description(c.oid, 'pg_class')
      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S') ORDER BY 1""", """
      SELECT c.relname, a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull,
        pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity, a.attgenerated, a.attislocal,
        a.attcollation, pg_catalog.col_description(c.oid, a.attnum)
      FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
      WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm') AND a.attnum > 0
      AND NOT a.attisdropped ORDER BY c.relname, a.attnum""", """
      SELECT t.relname, (SELECT pg_catalog.count(*) FROM pg_catalog.pg_index o
        WHERE o.indrelid = t.oid), ic.relname, i.indisvalid, i.indisunique, i.indisprimary,
        pg_catalog.pg_get_indexdef(i.indexrelid)
      FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
      JOIN pg_catalog.pg_class t ON t.oid = i.indrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
      WHERE n.nspname = 'public' ORDER BY 1, 3""", """
      SELECT t.relname, co.conname, co.contype, co.conislocal, co.coninhcount,
        pg_catalog.pg_get_constraintdef(co.oid)
      FROM pg_catalog.pg_constraint co JOIN pg_catalog.pg_class t ON t.oid = co.conrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
      WHERE n.nspname = 'public' AND co.contype <> 'n' ORDER BY 1, 2""", """
      SELECT c.relname, pg_catalog.pg_get_viewdef(c.oid)
      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm') ORDER BY 1""", """
      SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid),
        pg_catalog.pg_get_functiondef(p.oid), pg_catalog.obj_description(p.oid, 'pg_proc')
      FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'public' ORDER BY 1, 2""", """
      SELECT c.relname, t.tgname, pg_catalog.pg_get_triggerdef(t.oid), t.tgenabled
      FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND NOT t.tgisinternal ORDER BY 1, 2""", """
      SELECT sequencename, data_type, start_value, min_value, max_value, increment_by, cycle,
        cache_size, last_value
      FROM pg_catalog.pg_sequences WHERE schemaname = 'public' ORDER BY 1""");

  // What information_schema of MariaDB and MySQL tells of the objects of the database.
  private static final List<String> MYSQL_FACTS = List.of("""
      SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION, TABLE_COMMENT
      FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY 1""", """
      SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, COLUMN_TYPE,
        CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_KEY, EXTRA, COLUMN_COMMENT,
        GENERATION_EXPRESSION
      FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY 1, 3""", """
      SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE, SUB_PART,
        COLLATION, NULLABLE
      FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? ORDER BY 1, 2, 3""", """
      SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE
      FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ? ORDER BY 1""", """
      SELECT TABLE_NAME, VIEW_DEFINITION, CHECK_OPTION, IS_UPDATABLE, SECURITY_TYPE
      FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ? ORDER BY 1""", """
      SELECT ROUTINE_NAME, ROUTINE_TYPE, DTD_IDENTIFIER, ROUTINE_DEFINITION, IS_DETERMINISTIC,
        SQL_DATA_ACCESS, SECURITY_TYPE, SQL_MODE, ROUTINE_COMMENT
      FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ? ORDER BY 1""", """
      SELECT TRIGGER_NAME, EVENT_MANIPULATION, EVENT_OBJECT_TABLE, ACTION_ORDER, ACTION_STATEMENT,
        ACTION_TIMING, SQL_MODE
      FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY 1""");

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

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSessionWithAnsiQuotesExportsAlikeAndKeepsItsMode(Provider provider) throws Exception {
    assumeTrue(provider != Provider.POSTGRESQL, "Only MariaDB and MySQL have ANSI_QUOTES");
    try (var databases = new Databases(provider)) {
      var file = folder.resolve("ansi.sql");
      var mode = new String[1];
      export(
          databases,
          Dump.Content.STRUCTURE_AND_DATA,
          Dump.Options.DEFAULT,
          file,
          l -> true,
          connection -> {
            execute(
                connection, "SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',ANSI_QUOTES')");
            mode[0] = sqlMode(connection);
          },
          connection -> assertEquals(mode[0], sqlMode(connection)));
      assertTrue(mode[0].contains("ANSI_QUOTES"), mode[0]);
      databases.run(Files.readString(file));
      databases.assertSame();
    }
  }

  private static String sqlMode(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
      result.next();
      return result.getString(1);
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void largeRowsGoInInsertsOfABoundedSize(Provider provider) throws Exception {
    try (var databases = new Databases(provider)) {
      try (var connection = databases.open(databases.source)) {
        var binary = provider == Provider.POSTGRESQL ? "bytea" : "longblob";
        execute(connection, "CREATE TABLE big (id integer PRIMARY KEY, b " + binary + ")");
        for (var id = 1; id <= 5; id++) {
          // 400 KB a row, written as twice as many hexadecimal digits.
          var bytes = "'00ff" + id + "00a'";
          execute(
              connection,
              provider == Provider.POSTGRESQL
                  ? "INSERT INTO big SELECT " + id + ", pg_catalog.decode(pg_catalog.repeat("
                      + bytes + ", 100000), 'hex')"
                  : "INSERT INTO big VALUES (" + id + ", REPEAT(UNHEX(" + bytes + "), 100000))");
        }
      }
      var file = folder.resolve("big.sql");
      export(
          databases,
          Dump.Content.STRUCTURE_AND_DATA,
          Dump.Options.DEFAULT,
          file,
          l -> l.item().name().equals("big"));
      var text = Files.readString(file);
      var inserts = Statements.split(text, provider).stream()
          .map(st -> text.substring(st.start(), st.end()))
          .filter(sql -> sql.startsWith("INSERT INTO"))
          .toList();
      // Each past the bound by its last row at most: two rows each, the last alone.
      assertEquals(
          3, inserts.size(), inserts.stream().map(String::length).toList().toString());
      for (var insert : inserts) {
        assertTrue(insert.length() < Dump.INSERT_SIZE + 900_000, String.valueOf(insert.length()));
      }
      databases.run(text);
      assertEquals(
          databases.rows(databases.source, "big"), databases.rows(databases.target, "big"));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void theTablesExportedCannotBeChangedByOthersUntilTheExportEnds(Provider provider)
      throws Exception {
    assumeTrue(provider == Provider.POSTGRESQL, "Only PostgreSQL's snapshot keeps DDL out");
    try (var databases = new Databases(provider);
        var other = databases.open(databases.source)) {
      execute(other, "SET lock_timeout = '200ms'");
      var refused = new ArrayList<String>();
      var file = folder.resolve("locked.sql");
      try (var connection = databases.open(databases.source)) {
        var listed = Dump.list(connection, provider, databases.container());
        var choice = new Dump.Choice(
            new Database(databases.source, false),
            listed.stream().map(Listed::item).toList(),
            Dump.Content.STRUCTURE_AND_DATA,
            Dump.Options.DEFAULT);
        Dump.write(connection, provider, choice, file, (item, rows) -> {
          if (refused.isEmpty()) {
            for (var sql :
                List.of("TRUNCATE child, parent", "ALTER TABLE typed ADD late integer")) {
              try {
                execute(other, sql);
              } catch (SQLException e) {
                refused.add(e.getSQLState());
              }
            }
          }
        });
      }
      assertEquals(List.of("55P03", "55P03"), refused);
      databases.run(Files.readString(file));
      databases.assertSame();
      // One that holds a table already makes the export fail, and says why.
      execute(other, "BEGIN");
      execute(other, "LOCK TABLE parent IN ACCESS EXCLUSIVE MODE");
      try (var connection = databases.open(databases.source)) {
        var choice = new Dump.Choice(
            new Database(databases.source, false),
            List.of(Item.of(databases.container(), Item.Kind.TABLE, "parent")),
            Dump.Content.STRUCTURE_AND_DATA,
            Dump.Options.DEFAULT);
        var failed = assertThrows(
            QueryException.class,
            () -> Dump.write(connection, provider, choice, file, (item, rows) -> {}));
        assertTrue(failed.detail().contains("Another session"), failed.detail());
      } finally {
        execute(other, "ROLLBACK");
      }
    }
  }

  /** What a test does with the connection that exports, before and after the export. */
  interface Session {
    void on(Connection connection) throws Exception;
  }

  private static Dump.Written export(
      Databases databases,
      Dump.Content content,
      Dump.Options options,
      Path file,
      Predicate<Listed> chosen)
      throws Exception {
    return export(databases, content, options, file, chosen, c -> {}, c -> {});
  }

  private static Dump.Written export(
      Databases databases,
      Dump.Content content,
      Dump.Options options,
      Path file,
      Predicate<Listed> chosen,
      Session before,
      Session after)
      throws Exception {
    try (var connection = databases.open(databases.source)) {
      before.on(connection);
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
      after.on(connection);
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
    Container container() {
      return container(source);
    }

    private Container container(String name) {
      var database = new Database(name, false);
      return provider == Provider.POSTGRESQL ? new Schema(database, "public", false) : database;
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

    // The same as the catalog of each server tells it, which the export does not read alike, then
    // as the export defines it, and the same rows, their values read exactly.
    void assertSame() throws Exception {
      try (var from = open(source);
          var to = open(target)) {
        assertEquals(facts(from, source), facts(to, target));
        var objects = objects(from, container());
        assertEquals(objects.keySet(), objects(to, container(target)).keySet());
        assertEquals(definitions(from, container()), definitions(to, container(target)));
        for (var name : objects.keySet()) {
          if (objects.get(name).kind() == Item.Kind.TABLE) {
            assertEquals(rows(source, objects.get(name).name()), rows(target, name), name);
          }
        }
      }
    }

    // What the server's catalog tells of the objects, a line each, with the database's own name
    // as <database>, so that the two databases' compare.
    private String facts(Connection connection, String database) throws SQLException {
      var lines = new ArrayList<String>();
      var postgresql = provider == Provider.POSTGRESQL;
      for (var sql : postgresql ? POSTGRESQL_FACTS : MYSQL_FACTS) {
        try (var statement = connection.prepareStatement(sql)) {
          if (!postgresql) {
            statement.setString(1, database);
          }
          try (var result = statement.executeQuery()) {
            var columns = result.getMetaData().getColumnCount();
            while (result.next()) {
              var line = new StringBuilder(sql.substring(0, sql.indexOf('\n')));
              for (var i = 1; i <= columns; i++) {
                line.append(" | ").append(result.getString(i));
              }
              lines.add(line.toString().replace(database, "<database>"));
            }
          }
        }
      }
      return String.join("\n", lines);
    }

    private Map<String, Item> objects(Connection connection, Container c) throws Exception {
      var objects = new TreeMap<String, Item>();
      for (var listed : Dump.list(connection, provider, c)) {
        objects.put(listed.item().shown(), listed.item());
      }
      return objects;
    }

    // What creates each object, by its name.
    private Map<String, List<String>> definitions(Connection connection, Container c)
        throws Exception {
      var items = Dump.list(connection, provider, c).stream().map(Listed::item).toList();
      var definitions = new TreeMap<String, List<String>>();
      for (var definition : Dump.definitions(connection, provider, items)) {
        var all = new ArrayList<>(definition.statements());
        all.addAll(definition.restore());
        definitions.put(definition.item().shown(), all);
      }
      return definitions;
    }

    // The table's rows as the server writes them, by their first column; on MariaDB and MySQL a
    // float as a double, which these servers write rounded otherwise.
    List<List<String>> rows(String database, String table) throws Exception {
      try (var connection = open(database);
          var statement = connection.createStatement()) {
        var qualified = provider == Provider.POSTGRESQL
            ? "public." + provider.quote(table)
            : provider.quote(table);
        var select = provider == Provider.POSTGRESQL ? "*" : exactly(connection, database, table);
        try (var result =
            statement.executeQuery("SELECT " + select + " FROM " + qualified + " ORDER BY 1")) {
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
    }

    private String exactly(Connection connection, String database, String table)
        throws SQLException {
      try (var statement = connection.prepareStatement("""
          SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION""")) {
        statement.setString(1, database);
        statement.setString(2, table);
        var columns = new ArrayList<String>();
        try (var result = statement.executeQuery()) {
          while (result.next()) {
            var name = provider.quote(result.getString(1));
            columns.add(result.getString(2).equalsIgnoreCase("float") ? name + " + 0E0" : name);
          }
        }
        return String.join(", ", columns);
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
        // Each drop is tried, also where the other failed, and the first failure is thrown.
        SQLException failed = null;
        for (var name : List.of(source, target)) {
          try {
            execute(admin, "DROP DATABASE IF EXISTS " + provider.quote(name) + force);
          } catch (SQLException e) {
            if (failed == null) {
              failed = e;
            } else {
              failed.addSuppressed(e);
            }
          }
        }
        if (failed != null) {
          throw failed;
        }
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
        // An index of the partitioned table, which its partition's attaches to, and a default and
        // NOT NULL of the partition's own.
        "CREATE INDEX measure_id ON measure (id)",
        "ALTER TABLE measure_2024 ALTER COLUMN id SET DEFAULT 7",
        "ALTER TABLE measure_2024 ALTER COLUMN id SET NOT NULL",
        // A child with defaults and NOT NULL of its own on the columns it inherits.
        "CREATE TABLE base (id integer DEFAULT 0, note text DEFAULT 'b')",
        "CREATE TABLE derived (extra integer) INHERITS (base)",
        "ALTER TABLE ONLY derived ALTER COLUMN id DROP DEFAULT",
        "ALTER TABLE ONLY derived ALTER COLUMN note SET DEFAULT 'd'",
        "ALTER TABLE derived ALTER COLUMN id SET NOT NULL",
        "INSERT INTO base VALUES (1, 'in base')",
        "INSERT INTO derived (id, extra) VALUES (2, 5)",
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
        // A float of more digits than these servers write of one.
        "INSERT INTO typed (id, f) VALUES (3, 1.23456789)",
        "INSERT INTO plain VALUES ('kept')"));
    if (provider == Provider.MARIADB) {
      statements.add("CREATE SEQUENCE counter START WITH 5");
      statements.add("SELECT NEXTVAL(counter), NEXTVAL(counter)");
      // A default of the sequence, which the server names with its database.
      statements.add("CREATE TABLE numbered (id integer NOT NULL DEFAULT nextval(counter),"
          + " k varchar(5))");
      statements.add("INSERT INTO numbered (k) VALUES ('a')");
    }
    return statements;
  }
}

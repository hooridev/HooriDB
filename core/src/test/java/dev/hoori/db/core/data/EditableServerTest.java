package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Recorder;
import dev.hoori.db.core.sql.EditableQuery;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Deciding whether a console statement's result can be edited, and which table and columns it
 * edits, against real PostgreSQL, MySQL and MariaDB servers.
 */
class EditableServerTest {

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSingleTableSelectTracesToItsTableAndColumns(Provider provider) throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      var sql = "SELECT name AS n, id FROM customer WHERE active = true";
      var editable = decide(console, writer, provider, sql, false).orElseThrow();
      var expectedTable = table(provider, "customer");
      var expectedColumns = Catalog.columns(writer, provider, expectedTable);
      assertEquals(expectedTable, editable.table());
      assertEquals(expectedColumns, editable.columns());
      assertEquals(
          List.of(column(expectedColumns, "name"), column(expectedColumns, "id")),
          editable.edited());

      // Composite primary key, with columns in a different order than the table's.
      var lineSql = "SELECT line, item, purchase_id FROM purchase_line ORDER BY purchase_id, line";
      var line = decide(console, writer, provider, lineSql, false).orElseThrow();
      var lineTable = table(provider, "purchase_line");
      var lineColumns = Catalog.columns(writer, provider, lineTable);
      assertEquals(lineTable, line.table());
      assertEquals(
          List.of(
              column(lineColumns, "line"),
              column(lineColumns, "item"),
              column(lineColumns, "purchase_id")),
          line.edited());

      // Expressions and scalar subqueries stay read-only while real columns stay editable.
      var exprSql = "SELECT id, upper(name) AS up, 1 AS one,"
          + " (SELECT count(*) FROM purchase p WHERE p.customer_id = c.id) AS orders, name"
          + " FROM customer c WHERE c.id IN (SELECT customer_id FROM purchase)";
      var expr = decide(console, writer, provider, exprSql, false).orElseThrow();
      assertEquals(expectedTable, expr.table());
      assertEquals(column(expectedColumns, "id"), expr.edited().get(0));
      assertNull(expr.edited().get(1));
      assertNull(expr.edited().get(2));
      assertNull(expr.edited().get(3));
      assertEquals(column(expectedColumns, "name"), expr.edited().get(4));

      // Table pages keep null origins.
      var page = Rows.read(writer, provider, expectedTable, expectedColumns, 0, 5);
      for (var field : page.fields()) {
        assertNull(field.origin());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void joinsSetOperationsViewsCtesSubqueriesMissingKeysAndLocksAreRefused(Provider provider)
      throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      var refused = List.of(
          // Join, comma join (selecting only one table's columns), self-join.
          "SELECT c.id, c.name, p.total FROM customer c JOIN purchase p ON c.id = p.customer_id",
          "SELECT c.id, c.name FROM customer c, purchase p WHERE c.id = p.customer_id",
          "SELECT a.id, a.name, b.name AS other FROM customer a JOIN customer b ON a.id = b.id",
          "SELECT a.id, a.name FROM customer a, customer b WHERE a.id = b.id",
          // UNION and UNION ALL.
          "SELECT id, name FROM customer UNION SELECT id, name FROM customer",
          "SELECT id, name FROM customer UNION ALL SELECT id, name FROM customer",
          // View.
          "SELECT id, name, email FROM active_customer",
          // CTEs, including renaming a column and named like a real table.
          "WITH x AS (SELECT id, name FROM customer) SELECT id, name FROM x",
          "WITH x AS (SELECT name AS email, id FROM customer) SELECT id, email FROM x",
          "WITH purchase AS (SELECT id, name FROM customer) SELECT id, name FROM purchase",
          // Subqueries in FROM, including renaming a column and aliased like a real table.
          "SELECT id, name FROM (SELECT id, name FROM customer) s",
          "SELECT id, email FROM (SELECT name AS email, id FROM customer) s",
          "SELECT id, name FROM (SELECT id, name FROM customer) purchase",
          // Key as expression, missing key, partial composite key, repeated key.
          "SELECT id + 0 AS id, name FROM customer",
          "SELECT name, email FROM customer",
          "SELECT purchase_id, item FROM purchase_line",
          "SELECT id, id AS id2, name FROM customer",
          // Locking clause.
          "SELECT id, name FROM customer FOR UPDATE");
      for (var sql : refused) {
        assertEquals(Optional.empty(), decide(console, writer, provider, sql, false), sql);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aRepeatedColumnIsReadOnlyInBothPlaces(Provider provider) throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      var sql = "SELECT id, name, NAME AS again, email FROM customer";
      var editable = decide(console, writer, provider, sql, false).orElseThrow();
      var columns = editable.columns();
      assertEquals(column(columns, "id"), editable.edited().get(0));
      assertNull(editable.edited().get(1));
      assertNull(editable.edited().get(2));
      assertEquals(column(columns, "email"), editable.edited().get(3));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aTableWithoutPrimaryKeyIsRefusedAndJsonIsEditable(Provider provider) throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      execute(
          writer,
          "CREATE TABLE editable_no_pk (a integer, b varchar(20))",
          "INSERT INTO editable_no_pk VALUES (1, 'x')",
          "CREATE TABLE editable_json (id integer PRIMARY KEY, doc json)",
          "INSERT INTO editable_json VALUES (1, '{\"a\":1}')");
      try {
        assertEquals(
            Optional.empty(),
            decide(console, writer, provider, "SELECT a, b FROM editable_no_pk", false));
        var json = decide(console, writer, provider, "SELECT id, doc FROM editable_json", false)
            .orElseThrow();
        assertEquals(column(json.columns(), "id"), json.edited().get(0));
        assertEquals(column(json.columns(), "doc"), json.edited().get(1));
      } finally {
        execute(writer, "DROP TABLE editable_no_pk", "DROP TABLE editable_json");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void mariaDbAndMySqlLeaveTimestampReadOnlyAndRefuseTimestampPrimaryKey(Provider provider)
      throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      execute(
          writer,
          "CREATE TABLE editable_ts (id integer PRIMARY KEY, name varchar(20), ts timestamp NOT NULL)",
          "INSERT INTO editable_ts VALUES (1, 'a', '2024-03-31 02:30:00')",
          "CREATE TABLE editable_ts_pk (ts timestamp PRIMARY KEY, name varchar(20))",
          "INSERT INTO editable_ts_pk VALUES ('2024-03-31 02:30:00', 'a')");
      try {
        var editable = decide(
                console, writer, provider, "SELECT id, name, ts FROM editable_ts", false)
            .orElseThrow();
        assertEquals(column(editable.columns(), "id"), editable.edited().get(0));
        assertEquals(column(editable.columns(), "name"), editable.edited().get(1));
        assertNull(editable.edited().get(2));

        assertEquals(
            Optional.empty(),
            decide(console, writer, provider, "SELECT ts, name FROM editable_ts_pk", false));
      } finally {
        execute(writer, "DROP TABLE editable_ts", "DROP TABLE editable_ts_pk");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void mariaDbAndMySqlRefuseAfterCreateTemporaryTableInTheConsoleSession(Provider provider)
      throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      var create = "CREATE TEMPORARY TABLE customer (id integer PRIMARY KEY, name varchar(100))";
      assertTrue(EditableQuery.createsTemporaryTable(create, provider));
      execute(console, create, "INSERT INTO customer VALUES (99, 'Temp')");
      var sql = "SELECT id, name FROM customer";
      assertEquals(Optional.empty(), decide(console, writer, provider, sql, true));
    }
  }

  @Test
  void postgreSqlHandlesTemporaryTablesMaterializedViewsSystemColumnsAndDroppedColumns()
      throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var console = open(provider);
        var writer = open(provider)) {
      // A temporary table shadowing customer is refused by relpersistence, while a permanent table
      // on the same session remains editable even when createdTemporaryTable is true.
      execute(
          console,
          "CREATE TEMPORARY TABLE customer (id integer PRIMARY KEY, name varchar(100))",
          "INSERT INTO customer VALUES (99, 'Temp')");
      assertEquals(
          Optional.empty(),
          decide(console, writer, provider, "SELECT id, name FROM customer", false));
      assertEquals(
          Optional.empty(),
          decide(console, writer, provider, "SELECT id, name FROM customer", true));
      assertTrue(decide(console, writer, provider, "SELECT id, total FROM purchase", true)
          .isPresent());

      // System columns have no origin and stay read-only.
      var sys = decide(console, writer, provider, "SELECT ctid, id, total FROM purchase", false)
          .orElseThrow();
      assertNull(sys.edited().get(0));
      assertEquals(column(sys.columns(), "id"), sys.edited().get(1));
      assertEquals(column(sys.columns(), "total"), sys.edited().get(2));

      // Materialized view is refused; a table with a dropped column and a partitioned table in
      // another schema still resolve accurately.
      execute(
          writer,
          "CREATE MATERIALIZED VIEW editable_mv AS SELECT id, name FROM public.customer",
          "CREATE TABLE editable_dropped (id integer PRIMARY KEY, gone text, kept text)",
          "ALTER TABLE editable_dropped DROP COLUMN gone",
          "INSERT INTO editable_dropped VALUES (1, 'here')",
          "CREATE SCHEMA editable_s",
          "CREATE TABLE editable_s.part (id integer PRIMARY KEY, label text) PARTITION BY RANGE"
              + " (id)",
          "CREATE TABLE editable_s.part_1 PARTITION OF editable_s.part FOR VALUES FROM (1) TO (10)",
          "INSERT INTO editable_s.part VALUES (1, 'one')");
      try {
        assertEquals(
            Optional.empty(),
            decide(console, writer, provider, "SELECT id, name FROM editable_mv", false));
        var dropped = decide(
                console, writer, provider, "SELECT kept, id FROM editable_dropped", false)
            .orElseThrow();
        assertEquals(column(dropped.columns(), "kept"), dropped.edited().get(0));
        assertEquals(column(dropped.columns(), "id"), dropped.edited().get(1));

        var part = decide(console, writer, provider, "SELECT id, label FROM editable_s.part", false)
            .orElseThrow();
        assertEquals(
            new Table(
                new Schema(new Database(TestServer.DATABASE, false), "editable_s", false),
                "part",
                Table.Kind.TABLE),
            part.table());
        assertEquals(column(part.columns(), "id"), part.edited().get(0));
        assertEquals(column(part.columns(), "label"), part.edited().get(1));
      } finally {
        execute(
            writer,
            "DROP MATERIALIZED VIEW editable_mv",
            "DROP TABLE editable_dropped",
            "DROP SCHEMA editable_s CASCADE");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void resolutionRecordsItsQueriesThroughJournal(Provider provider) throws Exception {
    try (var console = open(provider);
        var writer = open(provider)) {
      var sql = "SELECT id, name FROM customer";
      var fields = fields(console, provider, sql);
      var purposes = new ArrayList<Purpose>();
      Recorder recorder = entry -> purposes.add(entry.purpose());
      var editable =
          Journal.recording(recorder, () -> Editable.of(writer, provider, sql, fields, false));
      assertTrue(editable.isPresent());
      assertFalse(purposes.isEmpty());
      assertTrue(purposes.contains(Purpose.CATALOG));
    }
  }

  private static Optional<Editable> decide(
      Connection console,
      Connection writer,
      Provider provider,
      String sql,
      boolean createdTemporaryTable)
      throws Exception {
    var fields = fields(console, provider, sql);
    return Editable.of(writer, provider, sql, fields, createdTemporaryTable);
  }

  private static List<Field> fields(Connection console, Provider provider, String sql) {
    try (var execution = new Execution(console, provider, sql)) {
      var portion = execution.next(10);
      var rows = assertInstanceOf(Result.Rows.class, portion.results().getFirst());
      return rows.fields();
    }
  }

  private static Column column(List<Column> columns, String name) {
    var found = columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    assertNotNull(found, name);
    return found;
  }

  private static Table table(Provider provider, String name) {
    var hoori = new Database(TestServer.DATABASE, false);
    return new Table(
        provider == Provider.POSTGRESQL ? new Schema(hoori, "public", false) : hoori,
        name,
        Table.Kind.TABLE);
  }

  private static Connection open(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(
            server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10))
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

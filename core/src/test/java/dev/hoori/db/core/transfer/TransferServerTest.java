package dev.hoori.db.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.transfer.Report.Stage;
import dev.hoori.db.core.transfer.Report.State;
import dev.hoori.db.core.transfer.Transfer.Commit;
import dev.hoori.db.core.transfer.Transfer.Copy;
import dev.hoori.db.core.transfer.Transfer.Existing;
import dev.hoori.db.core.transfer.Transfer.OnError;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Copying tables between two databases of each real server of SQL, from {@link
 * TestServer#DATABASE} into {@link TestServer#SPACED_DATABASE}, on a connection to each, in tables
 * of each test's own, which it drops.
 */
class TransferServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final String TYPES = "dev.hoori.db.core.TestServer#sql";

  @ParameterizedTest
  @MethodSource(TYPES)
  void copiesEveryValueAsItWasInBatchesByTheKey(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var name = unique("transfer_types");
    var columns = pg
        ? "id integer, k varchar(10), t text, n numeric(30, 10), f double precision, r real,"
            + " b boolean, d date, ts timestamp(6), tz timestamptz, tm time, j jsonb, js json,"
            + " u uuid, a integer[], bin bytea, iv interval, PRIMARY KEY (k, id)"
        : "id int, k varchar(10), t text, n decimal(30, 10), f double, r float, ti tinyint(1),"
            + " bi bigint unsigned, d date, dt datetime(6), ts timestamp(3) NULL, tm time,"
            + " y year, j json, bin varbinary(10), bl blob, bt bit(5), en enum('a', 'b'),"
            + " st set('x', 'y'), p point, PRIMARY KEY (k, id)";
    var values = IntStream.range(0, 23)
        .mapToObj(i -> pg
            ? "(" + i + ", 'k" + (i % 3) + "', 'täxt '' " + i + "', 12345678901234567890." + i
                + ", 'NaN', 1.25, " + (i % 2 == 0) + ", '2024-02-29', 'infinity',"
                + " '2024-03-31 02:30:00.123456+02', '24:00:00', '{\"a\": [1, " + i + "]}',"
                + " '{\"b\" :  1}', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{1,NULL," + i + "}',"
                + " '\\x00ff" + String.format("%02x", i) + "', '1 day 02:03:04')"
            : "(" + i + ", 'k" + (i % 3) + "', 'täxt '' " + i + "', 12345678901234567890." + i
                + ", 1.5e300, 1.25, " + (i % 3) + ", 18446744073709551615, '2024-02-29',"
                + " '2024-10-27 02:30:00.123456', '2024-03-31 02:30:00.123', '-838:59:59',"
                + " 2024, '{\"a\": [1, " + i + "]}', X'00FF" + String.format("%02X", i) + "',"
                + " X'01', b'10101', 'b', 'x,y', ST_GeomFromText('POINT(1 2)'))")
        .collect(Collectors.joining(", "));
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      execute(from, "CREATE TABLE " + qualified(provider, name) + " (" + columns + ")");
      execute(into, "CREATE TABLE " + qualified(provider, name) + " (" + columns + ")");
      try {
        execute(from, "INSERT INTO " + qualified(provider, name) + " VALUES " + values);
        // A row of NULLs, but for the key.
        execute(from, "INSERT INTO " + qualified(provider, name) + " (id, k) VALUES (99, 'k9')");
        var sent = new CopyOnWriteArrayList<Sent>();
        var report = Journal.recording(
            sent::add,
            () -> Copier.run(
                new Transfer(
                    List.of(
                        new Copy(source(provider, name), target(provider, name), Existing.STOP)),
                    Commit.TABLE,
                    OnError.STOP,
                    5),
                new SqlSource(from, provider),
                new SqlTarget(into, provider, version(into), false),
                told -> {}));

        assertEquals(Stage.DONE, report.stage(), report.toString());
        var copied = report.tables().getFirst();
        assertEquals(State.COPIED, copied.state());
        assertEquals(24, copied.written());
        assertEquals(24, copied.kept());
        assertEquals(24, copied.total().getAsLong());
        assertEquals(
            text(from, provider, source(provider, name)),
            text(into, provider, target(provider, name)));
        // Read in pages of 5 by the key, written in batches as one statement each.
        assertEquals(
            5,
            sent.stream()
                .filter(s -> s.purpose() == Purpose.TRANSFER && s.text().startsWith("SELECT"))
                .filter(s -> s.text().contains("LIMIT 5"))
                .count(),
            sent.toString());
        assertEquals(
            List.of(5L, 5L, 5L, 5L, 4L),
            sent.stream()
                .filter(s -> s.text().startsWith("INSERT"))
                .map(s -> ((Outcome.Changed) s.outcome()).count())
                .toList());
      } finally {
        execute(from, "DROP TABLE " + qualified(provider, name));
        execute(into, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void copiesATableWithoutKeyByPosition(Provider provider) throws Exception {
    var name = unique("transfer_keyless");
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      execute(from, "CREATE TABLE " + qualified(provider, name) + " (v integer, w varchar(5))");
      execute(into, "CREATE TABLE " + qualified(provider, name) + " (v integer, w varchar(5))");
      try {
        execute(
            from,
            "INSERT INTO " + qualified(provider, name) + " VALUES "
                + IntStream.range(0, 17)
                    .mapToObj(i -> "(" + (i % 4) + ", 'x')")
                    .collect(Collectors.joining(", ")));
        var report = run(provider, from, into, Existing.STOP, Commit.BATCH, 4, name);
        assertEquals(Stage.DONE, report.stage(), report.toString());
        assertEquals(
            text(from, provider, source(provider, name)),
            text(into, provider, target(provider, name)));
      } finally {
        execute(from, "DROP TABLE " + qualified(provider, name));
        execute(into, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void stopsAtRowsEmptiesOrAppends(Provider provider) throws Exception {
    var name = unique("transfer_existing");
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      execute(from, "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY)");
      execute(into, "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY)");
      try {
        execute(from, "INSERT INTO " + qualified(provider, name) + " VALUES (1), (2)");
        execute(into, "INSERT INTO " + qualified(provider, name) + " VALUES (7)");

        var stopped = run(provider, from, into, Existing.STOP, Commit.TABLE, 10, name);
        assertEquals(Stage.STOPPED, stopped.stage());
        assertEquals(State.FAILED, stopped.tables().getFirst().state());
        assertTrue(stopped.tables().getFirst().problem().contains("has rows"));
        assertEquals("7", text(into, provider, target(provider, name)));

        assertEquals(
            Stage.DONE,
            run(provider, from, into, Existing.APPEND, Commit.TABLE, 10, name).stage());
        assertEquals("1\n2\n7", text(into, provider, target(provider, name)));

        assertEquals(
            Stage.DONE,
            run(provider, from, into, Existing.EMPTY, Commit.TABLE, 10, name).stage());
        assertEquals("1\n2", text(into, provider, target(provider, name)));

        // The same rows again fail on the key; the table is left as it was.
        var failed = run(provider, from, into, Existing.APPEND, Commit.TABLE, 10, name);
        assertEquals(Stage.STOPPED, failed.stage());
        assertEquals(State.FAILED, failed.tables().getFirst().state());
        assertEquals(0, failed.tables().getFirst().kept());
        assertEquals("1\n2", text(into, provider, target(provider, name)));
      } finally {
        execute(from, "DROP TABLE " + qualified(provider, name));
        execute(into, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void aBatchCommittedStaysWhereALaterOneFails(Provider provider) throws Exception {
    var name = unique("transfer_batches");
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      execute(from, "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY)");
      execute(into, "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY)");
      try {
        execute(
            from,
            "INSERT INTO " + qualified(provider, name) + " VALUES "
                + IntStream.rangeClosed(1, 12)
                    .mapToObj(i -> "(" + i + ")")
                    .collect(Collectors.joining(", ")));
        execute(into, "INSERT INTO " + qualified(provider, name) + " VALUES (8)");
        var failed = run(provider, from, into, Existing.APPEND, Commit.BATCH, 5, name);
        assertEquals(State.FAILED, failed.tables().getFirst().state());
        assertEquals(5, failed.tables().getFirst().kept());
        assertEquals("1\n2\n3\n4\n5\n8", text(into, provider, target(provider, name)));
      } finally {
        execute(from, "DROP TABLE " + qualified(provider, name));
        execute(into, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void ordersByForeignKeysAndLeavesGeneratedColumnsToTheServer(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var parent = unique("transfer_parent");
    var child = unique("transfer_child");
    var parentColumns = pg
        ? "id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v integer,"
            + " twice integer GENERATED ALWAYS AS (v * 2) STORED"
        : "id integer AUTO_INCREMENT PRIMARY KEY, v integer,"
            + " twice integer GENERATED ALWAYS AS (v * 2) STORED";
    var childColumns =
        "id integer PRIMARY KEY, parent integer, FOREIGN KEY (parent) REFERENCES %s (id)";
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      for (var connection : List.of(from, into)) {
        execute(
            connection, "CREATE TABLE " + qualified(provider, parent) + " (" + parentColumns + ")");
        execute(
            connection,
            "CREATE TABLE " + qualified(provider, child) + " ("
                + childColumns.formatted(qualified(provider, parent)) + ")");
      }
      try {
        execute(
            from,
            "INSERT INTO " + qualified(provider, parent) + " (v) VALUES (1), (2), (3)",
            "DELETE FROM " + qualified(provider, parent) + " WHERE v = 1",
            "INSERT INTO " + qualified(provider, child) + " VALUES (1, 2), (2, 3)");
        var report = Copier.run(
            new Transfer(
                List.of(
                    new Copy(source(provider, child), target(provider, child), Existing.STOP),
                    new Copy(source(provider, parent), target(provider, parent), Existing.STOP)),
                Commit.TABLE,
                OnError.STOP,
                10),
            new SqlSource(from, provider),
            new SqlTarget(into, provider, version(into), false),
            told -> {});

        assertEquals(Stage.DONE, report.stage(), report.toString());
        assertEquals(
            List.of(parent, child),
            report.tables().stream().map(t -> t.copy().to().name()).toList());
        assertEquals("2\t2\t4\n3\t3\t6", text(into, provider, target(provider, parent)));
        // A new row takes the next value after those copied.
        execute(into, "INSERT INTO " + qualified(provider, parent) + " (v) VALUES (4)");
        assertEquals("2\t2\t4\n3\t3\t6\n4\t4\t8", text(into, provider, target(provider, parent)));
      } finally {
        for (var connection : List.of(from, into)) {
          execute(
              connection,
              "DROP TABLE " + qualified(provider, child),
              "DROP TABLE " + qualified(provider, parent));
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void aMissingTableOrColumnFailsBeforeAnythingIsWritten(Provider provider) throws Exception {
    var kept = unique("transfer_kept");
    var gone = unique("transfer_gone");
    var narrow = unique("transfer_narrow");
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      for (var table : List.of(kept, gone, narrow)) {
        execute(
            from, "CREATE TABLE " + qualified(provider, table) + " (id integer, extra integer)");
        execute(from, "INSERT INTO " + qualified(provider, table) + " VALUES (1, 2)");
      }
      execute(into, "CREATE TABLE " + qualified(provider, kept) + " (id integer, extra integer)");
      execute(into, "CREATE TABLE " + qualified(provider, narrow) + " (id integer)");
      try {
        var copies = List.of(
            new Copy(source(provider, kept), target(provider, kept), Existing.APPEND),
            new Copy(source(provider, gone), target(provider, gone), Existing.APPEND),
            new Copy(source(provider, narrow), target(provider, narrow), Existing.APPEND));
        var stopped = Copier.run(
            new Transfer(copies, Commit.TABLE, OnError.STOP, 10),
            new SqlSource(from, provider),
            new SqlTarget(into, provider, version(into), false),
            told -> {});
        assertEquals(Stage.STOPPED, stopped.stage());
        assertEquals(
            List.of(State.SKIPPED, State.FAILED, State.FAILED),
            stopped.tables().stream().map(Report.Entry::state).toList());
        assertTrue(stopped.tables().get(1).problem().contains("no table"), stopped.toString());
        assertTrue(
            stopped.tables().get(2).problem().contains("no column extra"), stopped.toString());
        assertEquals("", text(into, provider, target(provider, kept)));

        var skipped = Copier.run(
            new Transfer(copies, Commit.TABLE, OnError.SKIP, 10),
            new SqlSource(from, provider),
            new SqlTarget(into, provider, version(into), false),
            told -> {});
        assertEquals(Stage.DONE, skipped.stage());
        assertEquals(State.COPIED, skipped.tables().getFirst().state());
        assertEquals("1\t2", text(into, provider, target(provider, kept)));
      } finally {
        for (var table : List.of(kept, gone, narrow)) {
          execute(from, "DROP TABLE " + qualified(provider, table));
        }
        execute(
            into,
            "DROP TABLE " + qualified(provider, kept),
            "DROP TABLE " + qualified(provider, narrow));
      }
    }
  }

  @ParameterizedTest
  @MethodSource(TYPES)
  void aReadOnlyTargetIsRefused(Provider provider) throws Exception {
    try (var from = open(provider, TestServer.DATABASE);
        var into = open(provider, TestServer.SPACED_DATABASE)) {
      var sent = new CopyOnWriteArrayList<Sent>();
      var report = Journal.recording(
          sent::add,
          () -> Copier.run(
              new Transfer(
                  List.of(new Copy(source(provider, "x"), target(provider, "x"), Existing.STOP)),
                  Commit.TABLE,
                  OnError.STOP,
                  10),
              new SqlSource(from, provider),
              new SqlTarget(into, provider, version(into), true),
              told -> {}));
      assertEquals(Stage.REFUSED, report.stage());
      assertEquals(List.of(), sent);
    }
  }

  private static Report run(
      Provider provider,
      Connection from,
      Connection into,
      Existing existing,
      Commit commit,
      int batch,
      String name) {
    return Copier.run(
        new Transfer(
            List.of(new Copy(source(provider, name), target(provider, name), existing)),
            commit,
            OnError.STOP,
            batch),
        new SqlSource(from, provider),
        new SqlTarget(into, provider, version(into), false),
        told -> {});
  }

  // The rows of the table as the dialect reads them, in order, a line each, tabs between values.
  private static String text(Connection connection, Provider provider, Table table)
      throws Exception {
    var source = new SqlSource(connection, provider);
    source.prepare();
    var columns = Catalog.columns(connection, provider, table);
    var all = Selection.of(table, columns, columns);
    var lines = new ArrayList<String>();
    for (var at = Position.START; !(at instanceof Position.End); ) {
      var batch = source.read(all, at, 1000);
      for (var row : batch.rows()) {
        lines.add(row.stream()
            .map(v -> v instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(v))
            .collect(Collectors.joining("\t")));
      }
      at = batch.next();
    }
    if (all.key().isEmpty()) {
      lines.sort(null);
    }
    return String.join("\n", lines);
  }

  private static ServerVersion version(Connection connection) {
    try {
      return ServerVersion.parse(connection.getMetaData().getDatabaseProductVersion());
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Table source(Provider provider, String name) {
    return new Table(container(provider, TestServer.DATABASE), name, Table.Kind.TABLE);
  }

  private static Table target(Provider provider, String name) {
    return new Table(container(provider, TestServer.SPACED_DATABASE), name, Table.Kind.TABLE);
  }

  private static Container container(Provider provider, String database) {
    var of = new Database(database, false);
    return provider == Provider.POSTGRESQL ? new Schema(of, "public", false) : of;
  }

  // The table in the connection's own database or schema.
  private static String qualified(Provider provider, String name) {
    return provider.quote(name);
  }

  private static Connection open(Provider provider, String database) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(
            server.profile().withDatabase(database),
            TestServer.password(),
            server.driver(),
            TIMEOUT)
        .connection();
  }

  private static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.setEscapeProcessing(false);
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static String unique(String name) {
    return name + "_" + UUID.randomUUID().toString().substring(0, 8);
  }
}

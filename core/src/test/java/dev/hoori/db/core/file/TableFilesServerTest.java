package dev.hoori.db.core.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Inserts;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tables written to files and read back into tables on each real server of SQL: every kind of
 * value whole, as long as it is; a row that fails skipped or stopped at; and what a read-only
 * connection and a cancel leave.
 */
class TableFilesServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final OffsetDateTime T1 =
      OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 123_000_000, ZoneOffset.UTC);

  // The digits, as MariaDB and MySQL make rows without a deep recursion.
  private static final String DIGITS = "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2"
      + " UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6"
      + " UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";

  @TempDir
  Path dir;

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void writesEveryKindWholeAndReadsItBackIntoATable(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var source = unique("files_kinds");
    var target = unique("files_copy");
    var longText = "é€".repeat(3000);
    var longBytes = pg ? "'\\x" + "07".repeat(5000) + "'::bytea" : "x'" + "07".repeat(5000) + "'";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + " (id integer PRIMARY KEY,"
              + " t text, n numeric(20,5), f double precision, b " + (pg ? "boolean" : "bit(1)")
              + ", d date, z " + (pg ? "timestamptz" : "timestamp(3) NULL") + ", bin "
              + (pg ? "bytea" : "longblob") + ", j " + (pg ? "jsonb" : "json") + ")",
          "INSERT INTO " + qualified(provider, source) + " VALUES"
              + " (1, " + literal(provider, longText) + ", 12345.67890, 0.5, " + (pg ? "true" : "1")
              + ", '2024-02-29', "
              + (pg ? "'2024-03-31 02:30:00.123+00'" : "FROM_UNIXTIME(1711852200.123)")
              + ", " + longBytes + ", '{\"a\": [1, \"x,y\"]}'),"
              + " (2, '', -1, -2.5E10, " + (pg ? "false" : "0") + ", NULL, NULL, NULL, 'null'),"
              + " (3, 'line\nbreak, \"quoted\"', NULL, NULL, NULL, '1999-12-31', NULL, "
              + (pg ? "'\\x00ff'::bytea" : "x'00ff'") + ", NULL)",
          pg
              ? "CREATE TABLE " + qualified(provider, target) + " (LIKE "
                  + qualified(provider, source) + " INCLUDING ALL)"
              : "CREATE TABLE " + qualified(provider, target) + " LIKE "
                  + qualified(provider, source));
      try {
        var from = table(provider, source);
        var to = table(provider, target);
        var columns = Catalog.columns(connection, provider, from);
        var all = IntStream.range(0, columns.size()).boxed().toList();
        for (var format : List.of(
            Format.Delimited.CSV,
            Format.Delimited.TSV.withNullText("\\N").withEncoding(Encoding.UTF_16LE))) {
          var file = dir.resolve(source + "." + format.extension());
          var written = Export.write(
              file, format, each -> Rows.each(connection, provider, from, columns, all, each));
          assertEquals(3, written);

          var into = Catalog.columns(connection, provider, to);
          Inserts.Report report;
          try (var rows = Import.open(file, format, all)) {
            report = Inserts.write(
                connection,
                provider,
                to,
                into,
                all,
                rows,
                Inserts.Options.DEFAULT.withEmpty(true),
                false,
                Inserts.Progress.NONE);
          }
          assertEquals(
              new Inserts.Count(3, 3, 3, 0), report.count(), report.failed().toString());
          assertEquals(
              sorted(read(connection, provider, from, columns)),
              sorted(read(connection, provider, to, into)),
              format.toString());
          assertLeftAsItWas(connection, provider);
        }

        var read = sorted(read(connection, provider, from, columns));
        assertEquals(longText, read.get(0).get(1));
        assertEquals(new BigDecimal("12345.67890"), read.get(0).get(2));
        assertEquals(T1, read.get(0).get(6));
        assertEquals(5000, ((Binary) read.get(0).get(7)).length());
        assertFalse(((Binary) read.get(0).get(7)).cut());
        assertEquals(LocalDate.of(1999, 12, 31), read.get(2).get(5));

        var json = dir.resolve(source + ".jsonl");
        Export.write(
            json,
            Format.Json.LINES,
            each -> Rows.each(connection, provider, from, columns, List.of(0, 1, 4, 6, 8), each));
        var lines = Files.readAllLines(json).stream()
            .map(line -> JsonMapper.builder().build().readTree(line))
            .sorted(Comparator.comparingInt(node -> node.get("id").asInt()))
            .toList();
        assertEquals(3, lines.size());
        assertEquals(longText, lines.get(0).get("t").asString());
        assertTrue(lines.get(0).get("b").isBoolean());
        assertEquals("2024-03-31T02:30:00.123+00:00", lines.get(0).get("z").asString());
        assertEquals("x,y", lines.get(0).get("j").get("a").get(1).asString());
        assertTrue(lines.get(1).get("j").isNull());
        assertTrue(lines.get(2).get("j").isNull());
      } finally {
        dropAfter(
            provider,
            "DROP TABLE " + qualified(provider, source),
            "DROP TABLE " + qualified(provider, target));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void skipsOrStopsAtTheRowThatFailsAndCommitsAsAsked(Provider provider) throws Exception {
    var name = unique("files_failing");
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name)
              + " (id integer PRIMARY KEY, name varchar(10) NOT NULL, note varchar(10))",
          "INSERT INTO " + qualified(provider, name) + " VALUES (9, 'old', NULL)");
      try {
        var table = table(provider, name);
        var columns = Catalog.columns(connection, provider, table);
        var file = dir.resolve(name + ".csv");
        // Line 4 repeats a key, line 5 has a value too few, line 6 has NULL for a name.
        Files.writeString(file, "id,name\n1,a\n2,b\n1,again\n3\n4,\n5,e\n");
        var options = new Inserts.Options(true, 2, Inserts.Commit.ONCE, Inserts.OnError.SKIP);

        var skipped = load(connection, provider, table, columns, file, options);
        assertEquals(new Inserts.Count(6, 3, 3, 3), skipped.count());
        assertNull(skipped.stoppedAt());
        assertEquals(
            List.of(4L, 5L, 6L),
            skipped.failed().stream().map(Inserts.Failed::line).toList());
        assertEquals(
            "It has 1 value where 2 values come before", skipped.failed().get(1).message());
        assertEquals(List.of(1L, 2L, 5L), ids(connection, provider, table, columns));

        var stopped = load(
            connection, provider, table, columns, file, options.withOnError(Inserts.OnError.STOP));
        assertEquals(4, stopped.stoppedAt().line());
        assertEquals(0, stopped.count().committed());
        assertEquals(List.of(1L, 2L, 5L), ids(connection, provider, table, columns));

        var batches = load(
            connection,
            provider,
            table,
            columns,
            file,
            options.withOnError(Inserts.OnError.STOP).withCommit(Inserts.Commit.EACH_BATCH));
        assertEquals(4, batches.stoppedAt().line());
        assertEquals(2, batches.count().committed());
        assertEquals(List.of(1L, 2L), ids(connection, provider, table, columns));
        assertLeftAsItWas(connection, provider);

        var refused = assertThrows(
            QueryException.class,
            () -> Inserts.write(
                connection,
                provider,
                table,
                columns,
                List.of(0, 1),
                () -> {
                  throw new AssertionError("Nothing is read");
                },
                options,
                true,
                Inserts.Progress.NONE));
        assertTrue(refused.getMessage().contains("read-only"), refused.getMessage());
        assertEquals(List.of(1L, 2L), ids(connection, provider, table, columns));
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelReportsWhatWasCommittedAndOneAtTheCommitLetsItEnd(Provider provider)
      throws Exception {
    var name = unique("files_cancel");
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name)
              + " (id integer PRIMARY KEY, name varchar(10))");
      try {
        var table = table(provider, name);
        var columns = Catalog.columns(connection, provider, table);
        var file = dir.resolve(name + ".csv");
        Files.writeString(file, "id,name\n1,a\n2,b\n3,c\n4,d\n5,e\n");

        // Cancelled once the first batch was committed: the second is taken back.
        var cancelled = load(
            connection,
            provider,
            table,
            columns,
            file,
            new Inserts.Options(false, 2, Inserts.Commit.EACH_BATCH, Inserts.OnError.STOP),
            count -> Thread.currentThread().interrupt());
        assertTrue(Thread.interrupted());
        assertTrue(cancelled.cancelled());
        assertNull(cancelled.stoppedAt());
        assertEquals(new Inserts.Count(4, 2, 2, 0), cancelled.count());
        assertEquals(List.of(1L, 2L), ids(connection, provider, table, columns));
        assertLeftAsItWas(connection, provider);

        // Cancelled as its one transaction is to commit: the commit ends, and is counted.
        var committed = load(
            connection,
            provider,
            table,
            columns,
            file,
            new Inserts.Options(true, 2, Inserts.Commit.ONCE, Inserts.OnError.STOP),
            count -> {
              if (count.written() == 5) {
                Thread.currentThread().interrupt();
              }
            });
        assertTrue(Thread.interrupted());
        assertFalse(committed.cancelled());
        assertEquals(new Inserts.Count(5, 5, 5, 0), committed.count());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(connection, provider, table, columns));
        assertLeftAsItWas(connection, provider);
      } finally {
        Thread.interrupted();
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  // Where the rows come in a transaction of the export's own, the columns are described in it, so
  // that no change of theirs comes between.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void describesTheColumnsInTheExportsOwnTransaction(Provider provider) throws Exception {
    assumeTrue(
        !provider.dialect().session().queriesInOwnTransaction().isEmpty(),
        "The rows come without a transaction of the export's own");
    var name = unique("files_described");
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY, b boolean)",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, true)");
      try {
        var table = table(provider, name);
        var columns = Catalog.columns(connection, provider, table);
        var sent = new CopyOnWriteArrayList<Sent>();
        var rows = Journal.recording(sent::add, () -> read(connection, provider, table, columns));
        assertEquals(List.of(List.<Object>of(1L, true)), rows);
        var calls = sent.stream().map(Sent::text).toList();
        var begun = calls.indexOf(Journal.autoCommit(false));
        var described = IntStream.range(0, calls.size())
            .filter(i -> calls.get(i).endsWith(" WHERE false"))
            .findFirst()
            .orElseThrow();
        assertTrue(begun >= 0 && begun < described, calls.toString());
        assertLeftAsItWas(connection, provider);
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void refusesATableWhoseStorageKeepsNoTransactions(Provider provider) throws Exception {
    assumeTrue(provider != Provider.POSTGRESQL, "Every table of PostgreSQL keeps transactions");
    var name = unique("files_myisam");
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name)
              + " (id integer PRIMARY KEY, name varchar(10)) ENGINE=MyISAM",
          "INSERT INTO " + qualified(provider, name) + " VALUES (9, 'old')");
      try {
        var table = table(provider, name);
        var columns = Catalog.columns(connection, provider, table);
        var file = dir.resolve(name + ".csv");
        // A batch that fails on line 3 would keep line 2's row, which its retry writes again.
        Files.writeString(file, "id,name\n1,a\n1,again\n2,b\n");
        var refused = assertThrows(
            QueryException.class,
            () -> load(
                connection,
                provider,
                table,
                columns,
                file,
                new Inserts.Options(false, 10, Inserts.Commit.ONCE, Inserts.OnError.SKIP)));
        assertTrue(refused.getMessage().contains("keeps no transactions"), refused.getMessage());
        assertEquals(List.of(9L), ids(connection, provider, table, columns));
        assertLeftAsItWas(connection, provider);
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void streamsALargeTableAndLeavesNoFileWhenCancelled(Provider provider) throws Exception {
    var name = unique("files_large");
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY, t text)",
          provider == Provider.POSTGRESQL
              ? "INSERT INTO " + qualified(provider, name)
                  + " SELECT n, repeat('x', 100) FROM generate_series(1, 30000) n"
              : "INSERT INTO " + qualified(provider, name)
                  + " SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d + 1,"
                  + " REPEAT('x', 100) FROM " + DIGITS + " a, " + DIGITS + " b, " + DIGITS
                  + " c, " + DIGITS
                  + " e, (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2) f");
      try {
        var table = table(provider, name);
        var columns = Catalog.columns(connection, provider, table);
        var file = dir.resolve(name + ".csv");
        var fields = new ArrayList<Field>();
        var rows = Export.write(
            file,
            Format.Delimited.CSV,
            each -> Rows.each(connection, provider, table, columns, List.of(1, 0), new Rows.Each() {
              @Override
              public void fields(List<Field> given) throws IOException {
                fields.addAll(given);
                each.fields(given);
              }

              @Override
              public void row(List<Object> values) throws IOException {
                each.row(values);
              }
            }));
        assertEquals(30000, rows);
        assertEquals(List.of("t", "id"), fields.stream().map(Field::name).toList());
        assertEquals(Kind.INTEGER, fields.get(1).kind());
        try (Stream<String> lines = Files.lines(file)) {
          assertEquals(30001, lines.count());
        }
        assertLeftAsItWas(connection, provider);

        var cancelled = dir.resolve("cancelled.csv");
        // The user cancels on the thread that waits for the export, once rows have come.
        var caller = Thread.currentThread();
        var asked = new AtomicBoolean();
        var thrown = assertThrows(
            QueryException.class,
            () -> Export.write(
                cancelled,
                Format.Delimited.CSV,
                each ->
                    Rows.each(connection, provider, table, columns, List.of(0), new Rows.Each() {
                      @Override
                      public void fields(List<Field> given) throws IOException {
                        each.fields(given);
                      }

                      @Override
                      public void row(List<Object> values) throws IOException {
                        each.row(values);
                        if (!asked.getAndSet(true)) {
                          caller.interrupt();
                        }
                      }
                    })));
        // Taken before anything else can fail, so that the cleanup is not cancelled.
        var interrupted = Thread.interrupted();
        assertTrue(interrupted);
        assertInstanceOf(QueryException.class, thrown);
        assertTrue(
            thrown.failure() == QueryException.Failure.CANCELLED
                || thrown.failure() == QueryException.Failure.ABORTED,
            thrown.toString());
        assertFalse(Files.exists(cancelled));
        try (Stream<Path> left = Files.list(dir)) {
          assertEquals(List.of(file), left.toList());
        }
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  private static Inserts.Report load(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      Path file,
      Inserts.Options options)
      throws Exception {
    return load(connection, provider, table, columns, file, options, Inserts.Progress.NONE);
  }

  private static Inserts.Report load(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      Path file,
      Inserts.Options options,
      Inserts.Progress progress)
      throws Exception {
    try (var rows = Import.open(file, Format.Delimited.CSV, List.of(0, 1))) {
      return Inserts.write(
          connection, provider, table, columns, List.of(0, 1), rows, options, false, progress);
    }
  }

  private static List<Long> ids(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws Exception {
    return sorted(read(connection, provider, table, columns)).stream()
        .map(row -> (Long) row.getFirst())
        .toList();
  }

  private static List<List<Object>> read(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws Exception {
    var rows = new ArrayList<List<Object>>();
    Rows.each(
        connection,
        provider,
        table,
        columns,
        IntStream.range(0, columns.size()).boxed().toList(),
        new Rows.Each() {
          @Override
          public void fields(List<Field> fields) {}

          @Override
          public void row(List<Object> values) {
            rows.add(values);
          }
        });
    return rows;
  }

  private static List<List<Object>> sorted(List<List<Object>> rows) {
    return rows.stream()
        .sorted(Comparator.comparingLong(row -> (Long) row.getFirst()))
        .toList();
  }

  private static void assertLeftAsItWas(Connection connection, Provider provider) throws Exception {
    assertEquals(new Status(Transaction.NONE, true), Status.of(connection, provider));
  }

  private static String literal(Provider provider, String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  private static Table table(Provider provider, String name) {
    var container = provider == Provider.POSTGRESQL ? new Schema(HOORI, "public", false) : HOORI;
    return new Table(container, name, Table.Kind.TABLE);
  }

  private static String qualified(Provider provider, String name) {
    var container = provider == Provider.POSTGRESQL ? "public" : TestServer.DATABASE;
    return provider.quote(container) + "." + provider.quote(name);
  }

  private static String unique(String name) {
    return name + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static Connection open(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT)
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

  private static void dropAfter(Provider provider, String... statements) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, statements);
    }
  }
}

package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.query.QueryException.Failure;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Reading rows of real servers, from the sample schema of {@link TestServer} and own tables. */
class RowsServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Database HOORI = new Database(TestServer.DATABASE, false);

  @ParameterizedTest
  @EnumSource(Provider.class)
  void pagesOfReadingComeInTheOrderOfTheKey(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var reading = table(provider, "reading", Table.Kind.TABLE);
      var columns = Catalog.columns(connection, provider, reading);

      var first = Rows.read(connection, provider, reading, columns, 0, 100);
      assertEquals(
          List.of(
              new Field("id", columns.get(0).type(), Kind.INTEGER),
              new Field("label", columns.get(1).type(), Kind.TEXT),
              new Field("amount", columns.get(2).type(), Kind.DECIMAL)),
          first.fields());
      assertEquals(ids(1, 101), ids(first));
      assertEquals(
          List.of(1L, "reading 1", new BigDecimal("0.125")), first.rows().getFirst());
      assertEquals(List.of(10L, "reading 10"), first.rows().get(9).subList(0, 2));
      assertNull(first.rows().get(9).get(2));

      assertEquals(
          ids(2501, 2601), ids(Rows.read(connection, provider, reading, columns, 2500, 2600)));
      assertEquals(
          ids(4901, 5001), ids(Rows.read(connection, provider, reading, columns, 4900, 5000)));
      assertEquals(
          ids(4951, 5001), ids(Rows.read(connection, provider, reading, columns, 4950, 5050)));
      var beyond = Rows.read(connection, provider, reading, columns, 6000, 6100);
      assertEquals(first.fields(), beyond.fields());
      assertEquals(List.of(), beyond.rows());
      assertEquals(
          List.of(), Rows.read(connection, provider, reading, columns, 7, 7).rows());

      assertEquals(
          Rows.read(connection, provider, reading, columns, 2500, 2600),
          Rows.read(connection, provider, reading, columns, 2500, 2600));
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCompositeKeyOrdersByItsOwnOrderAndAnEmptyTableHasNoRows(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE pair (a integer NOT NULL, b integer NOT NULL, PRIMARY KEY (b, a))",
          "INSERT INTO pair VALUES (1, 2), (2, 1), (1, 1)",
          "CREATE TABLE nothing (id integer PRIMARY KEY)");
      try {
        var pair = table(provider, "pair", Table.Kind.TABLE);
        var page =
            Rows.read(connection, provider, pair, columns(connection, provider, pair), 0, 10);
        assertEquals(List.of(List.of(1L, 1L), List.of(2L, 1L), List.of(1L, 2L)), page.rows());

        var nothing = table(provider, "nothing", Table.Kind.TABLE);
        var empty =
            Rows.read(connection, provider, nothing, columns(connection, provider, nothing), 0, 10);
        assertEquals(List.of("id"), empty.fields().stream().map(Field::name).toList());
        assertEquals(List.of(), empty.rows());
        assertEquals(0, Rows.count(connection, provider, nothing));
      } finally {
        execute(connection, "DROP TABLE pair", "DROP TABLE nothing");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aTableAndAViewWithoutKeyGiveTheSameRowsForTheSamePositions(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE loose (n integer, t varchar(10))",
          "INSERT INTO loose VALUES (3, 'c'), (1, 'a'), (2, NULL), (1, 'a'), (NULL, 'z'), (2, 'b')");
      try {
        for (var table : List.of(
            table(provider, "loose", Table.Kind.TABLE),
            table(provider, "active_customer", Table.Kind.VIEW))) {
          var columns = columns(connection, provider, table);
          var all = Rows.read(connection, provider, table, columns, 0, 100).rows();
          var paged = new ArrayList<List<Object>>();
          for (var from = 0; from < all.size(); from += 2) {
            paged.addAll(
                Rows.read(connection, provider, table, columns, from, from + 2).rows());
          }
          assertEquals(all, paged, table.name());
          assertEquals(
              all, Rows.read(connection, provider, table, columns, 0, 100).rows());
          assertEquals(Rows.count(connection, provider, table), all.size());
        }
      } finally {
        execute(connection, "DROP TABLE loose");
      }
    }
  }

  private record Typed(String type, String literal, Kind kind, Object value) {}

  private static Typed typed(String type, String literal, Kind kind, Object value) {
    return new Typed(type, literal, kind, value);
  }

  private static final String EMOJI = "Grüße 😀 𝄞";

  private static List<Typed> types(Provider provider) {
    var utc = ZoneOffset.UTC;
    return switch (provider) {
      case POSTGRESQL ->
        List.of(
            typed("smallint", "-32768", Kind.INTEGER, -32768L),
            typed("integer", "2147483647", Kind.INTEGER, 2147483647L),
            typed("bigint", "-9223372036854775808", Kind.INTEGER, Long.MIN_VALUE),
            typed("oid", "4294967295", Kind.INTEGER, 4294967295L),
            typed(
                "numeric",
                "123456789012345678901234567890.123456789012345678901234567890",
                Kind.DECIMAL,
                new BigDecimal("123456789012345678901234567890.123456789012345678901234567890")),
            typed("numeric", "'NaN'", Kind.DECIMAL, Double.NaN),
            typed("numeric", "'-Infinity'", Kind.DECIMAL, Double.NEGATIVE_INFINITY),
            typed("numeric(10,2)", "1.5", Kind.DECIMAL, new BigDecimal("1.50")),
            typed("real", "0.1", Kind.DECIMAL, 0.1f),
            typed("double precision", "0.1", Kind.DECIMAL, 0.1),
            typed("double precision", "'Infinity'", Kind.DECIMAL, Double.POSITIVE_INFINITY),
            typed("boolean", "true", Kind.BOOLEAN, true),
            typed("boolean", "false", Kind.BOOLEAN, false),
            typed("varchar(10)", "''", Kind.TEXT, ""),
            typed("char(3)", "'ab'", Kind.TEXT, "ab "),
            typed("text", "'" + EMOJI + "'", Kind.TEXT, EMOJI),
            typed("mood", "'happy'", Kind.TEXT, "happy"),
            typed("positive", "5", Kind.INTEGER, 5L),
            typed("bytea", "'\\x00ff'", Kind.BINARY, new Binary(new byte[] {0, -1}, 2)),
            typed("date", "'0044-03-15 BC'", Kind.DATE, LocalDate.of(-43, 3, 15)),
            typed("date", "'5874897-12-31'", Kind.DATE, LocalDate.of(5874897, 12, 31)),
            typed("date", "'infinity'", Kind.DATE, "infinity"),
            typed("time", "'23:59:59.999999'", Kind.TIME, LocalTime.of(23, 59, 59, 999_999_000)),
            typed("time", "'24:00:00'", Kind.TIME, "24:00:00"),
            typed(
                "timetz",
                "'10:00:00+05:30'",
                Kind.TIME,
                OffsetTime.of(10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))),
            // In the gap of the JVM's zone, which the test sets.
            typed(
                "timestamp",
                "'2024-03-31 02:30:00'",
                Kind.DATE_TIME,
                LocalDateTime.of(2024, 3, 31, 2, 30)),
            typed(
                "timestamp",
                "'4713-01-01 00:00:00.5 BC'",
                Kind.DATE_TIME,
                LocalDateTime.of(-4712, 1, 1, 0, 0, 0, 500_000_000)),
            typed("timestamp", "'-infinity'", Kind.DATE_TIME, "-infinity"),
            typed(
                "timestamptz",
                "'2024-03-31 02:30:00.123+00'",
                Kind.DATE_TIME_ZONED,
                OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 123_000_000, utc)),
            // Before 1893 the JVM's zone has an offset with seconds.
            typed(
                "timestamptz",
                "'1800-01-01 00:00:00+00'",
                Kind.DATE_TIME_ZONED,
                OffsetDateTime.of(1800, 1, 1, 0, 0, 0, 0, utc)),
            typed("interval", "'1 day 02:00:00'", Kind.OTHER, "1 day 02:00:00"),
            typed(
                "uuid",
                "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'",
                Kind.OTHER,
                "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
            typed("json", "'{\"b\": 1,  \"a\": 2}'", Kind.OTHER, "{\"b\": 1,  \"a\": 2}"),
            typed("jsonb", "'{\"b\": 1,  \"a\": 2}'", Kind.OTHER, "{\"a\": 2, \"b\": 1}"),
            typed("integer[]", "'{1,2,NULL}'", Kind.OTHER, "{1,2,NULL}"),
            typed("bit(4)", "B'1010'", Kind.OTHER, "1010"),
            typed("money", "12.5", Kind.OTHER, "$12.50"));
      case MARIADB, MYSQL -> {
        var types = new ArrayList<>(List.of(
            typed("tinyint", "-128", Kind.INTEGER, -128L),
            typed("tinyint(1)", "2", Kind.INTEGER, 2L),
            typed("boolean", "true", Kind.INTEGER, 1L),
            typed("smallint unsigned", "65535", Kind.INTEGER, 65535L),
            typed("mediumint", "-8388608", Kind.INTEGER, -8388608L),
            typed("int unsigned", "4294967295", Kind.INTEGER, 4294967295L),
            typed("bigint", "-9223372036854775808", Kind.INTEGER, Long.MIN_VALUE),
            typed(
                "bigint unsigned",
                "18446744073709551615",
                Kind.INTEGER,
                new BigInteger("18446744073709551615")),
            typed("year", "2024", Kind.INTEGER, 2024L),
            typed("year", "0", Kind.INTEGER, 0L),
            typed(
                "decimal(65,30)",
                "12345678901234567890123456789012345.123456789012345678901234567890",
                Kind.DECIMAL,
                new BigDecimal(
                    "12345678901234567890123456789012345.123456789012345678901234567890")),
            typed("float", "0.1", Kind.DECIMAL, 0.1f),
            typed("double", "0.1", Kind.DECIMAL, 0.1),
            typed("bit(1)", "b'1'", Kind.BOOLEAN, true),
            typed("bit(1)", "b'0'", Kind.BOOLEAN, false),
            typed("bit(8)", "b'10101010'", Kind.BINARY, new Binary(new byte[] {(byte) 0xaa}, 1)),
            typed("char(3)", "'ab'", Kind.TEXT, "ab"),
            typed("varchar(10)", "''", Kind.TEXT, ""),
            typed("text", "'" + EMOJI + "'", Kind.TEXT, EMOJI),
            typed("enum('x','y')", "'y'", Kind.TEXT, "y"),
            typed("set('x','y')", "'x,y'", Kind.TEXT, "x,y"),
            typed("blob", "x'00ff'", Kind.BINARY, new Binary(new byte[] {0, -1}, 2)),
            typed("binary(4)", "x'01'", Kind.BINARY, new Binary(new byte[] {1, 0, 0, 0}, 4)),
            typed("date", "'1000-01-01'", Kind.DATE, LocalDate.of(1000, 1, 1)),
            typed("date", "'0000-00-00'", Kind.DATE, "0000-00-00"),
            typed("date", "'2024-00-15'", Kind.DATE, "2024-00-15"),
            typed("time(6)", "'12:34:56.5'", Kind.TIME, LocalTime.of(12, 34, 56, 500_000_000)),
            typed("time", "'838:59:59'", Kind.TIME, "838:59:59"),
            typed("time", "'-01:00:00'", Kind.TIME, "-01:00:00"),
            typed(
                "datetime(6)",
                "'2024-03-31 02:30:00.123456'",
                Kind.DATE_TIME,
                LocalDateTime.of(2024, 3, 31, 2, 30, 0, 123_456_000)),
            typed("datetime", "'0000-00-00 00:00:00'", Kind.DATE_TIME, "0000-00-00 00:00:00"),
            // Written in the session's zone of +05:00, which the test sets.
            typed(
                "timestamp(3) NULL",
                "'2024-03-31 07:30:00.123'",
                Kind.DATE_TIME_ZONED,
                OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 123_000_000, utc)),
            typed(
                "timestamp NULL",
                "'0000-00-00 00:00:00'",
                Kind.DATE_TIME_ZONED,
                "0000-00-00 00:00:00")));
        if (provider == Provider.MYSQL) {
          types.add(typed("json", "'{\"b\": 1,  \"a\": 2}'", Kind.OTHER, "{\"a\": 2, \"b\": 1}"));
        } else {
          // MariaDB's json is longtext with a check.
          types.add(typed("json", "'{\"b\": 1,  \"a\": 2}'", Kind.TEXT, "{\"b\": 1,  \"a\": 2}"));
          types.add(typed(
              "uuid",
              "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'",
              Kind.OTHER,
              "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"));
        }
        yield types;
      }
    };
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void eachTypeHasItsKindAndItsExactValue(Provider provider) throws Exception {
    var types = types(provider);
    var pg = provider == Provider.POSTGRESQL;
    var zone = TimeZone.getDefault();
    // A zone with a daylight saving gap, for the drivers to trip over.
    TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    try (var connection = open(provider)) {
      var definitions = IntStream.range(0, types.size())
          .mapToObj(i -> ", c" + i + " " + types.get(i).type())
          .collect(Collectors.joining());
      var literals = types.stream().map(t -> ", " + t.literal()).collect(Collectors.joining());
      if (pg) {
        execute(
            connection,
            "CREATE TYPE mood AS ENUM ('sad', 'happy')",
            "CREATE DOMAIN positive AS integer CHECK (VALUE > 0)",
            "SET lc_monetary = 'C'");
      } else {
        // Zero dates need a lenient mode; the zone shows that a timestamp is read as a moment.
        execute(connection, "SET SESSION sql_mode = ''", "SET SESSION time_zone = '+05:00'");
      }
      execute(
          connection,
          "CREATE TABLE typed (id integer PRIMARY KEY" + definitions + ")"
              + (pg ? "" : " DEFAULT CHARSET utf8mb4"),
          "INSERT INTO typed VALUES (1" + literals + ")",
          "INSERT INTO typed (id) VALUES (2)");
      try {
        var table = table(provider, "typed", Table.Kind.TABLE);
        var page =
            Rows.read(connection, provider, table, columns(connection, provider, table), 0, 10);
        for (var i = 0; i < types.size(); i++) {
          var expected = types.get(i);
          var label = expected.type() + " " + expected.literal();
          assertEquals(expected.kind(), page.fields().get(i + 1).kind(), label);
          var value = page.rows().getFirst().get(i + 1);
          assertEquals(expected.value(), value, label);
          assertEquals(expected.value().getClass(), value.getClass(), label);
          assertNull(page.rows().get(1).get(i + 1), label);
        }
      } finally {
        execute(connection, "DROP TABLE typed");
        if (pg) {
          execute(connection, "DROP TYPE mood", "DROP DOMAIN positive");
        }
      }
    } finally {
      TimeZone.setDefault(zone);
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void longTextAndBinaryDataAreCutAndSaySo(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var limit = Rows.LIMIT;
    var text = pg ? "text" : "longtext";
    var bytes = pg ? "bytea" : "longblob";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE long_value (id integer PRIMARY KEY, t " + text + ", b " + bytes + ")"
              + (pg ? "" : " DEFAULT CHARSET utf8mb4"),
          // At the limit, and one beyond it; a character beyond the BMP is two UTF-16 units.
          "INSERT INTO long_value VALUES (1, " + repeat(provider, "😀", limit) + ", "
              + repeatBytes(provider, limit) + ")",
          "INSERT INTO long_value VALUES (2, " + repeat(provider, "😀", limit + 1) + ", "
              + repeatBytes(provider, limit + 1) + ")");
      try {
        var table = table(provider, "long_value", Table.Kind.TABLE);
        var rows = Rows.read(
                connection, provider, table, columns(connection, provider, table), 0, 2)
            .rows();
        var whole = "😀".repeat(limit);
        assertEquals(whole, rows.get(0).get(1));
        var b = assertInstanceOf(Binary.class, rows.get(0).get(2));
        assertEquals(limit, b.length());
        assertFalse(b.cut());
        assertEquals(new Cut(whole, 4L * (limit + 1)), rows.get(1).get(1));
        b = assertInstanceOf(Binary.class, rows.get(1).get(2));
        assertTrue(b.cut());
        assertEquals(limit, b.bytes().length);
        assertEquals(limit + 1, b.length());
      } finally {
        execute(connection, "DROP TABLE long_value");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aRowWithAHugeValueCostsLittleMemory(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      // MariaDB and MySQL build no value larger than max_allowed_packet.
      long size = 100_000_000;
      if (!pg) {
        size = Math.min(size, selectLong(connection, "SELECT @@max_allowed_packet") - 1_000_000);
      }
      execute(
          connection,
          "CREATE TABLE huge (id integer PRIMARY KEY, t " + (pg ? "text" : "longtext") + ", b "
              + (pg ? "bytea" : "longblob") + ")",
          "INSERT INTO huge VALUES (1, " + repeat(provider, "x", size) + ", "
              + (pg
                  ? "convert_to(" + repeat(provider, "y", size) + ", 'UTF8')"
                  : "CAST(" + repeat(provider, "y", size) + " AS BINARY)")
              + ")");
      try {
        var table = table(provider, "huge", Table.Kind.TABLE);
        var columns = columns(connection, provider, table);
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var before = threads.getTotalThreadAllocatedBytes();
        var row = Rows.read(connection, provider, table, columns, 0, 1).rows().getFirst();
        var allocated = threads.getTotalThreadAllocatedBytes() - before;

        assertEquals(new Cut("x".repeat(Rows.LIMIT), size), row.get(1));
        var b = assertInstanceOf(Binary.class, row.get(2));
        assertEquals(size, b.length());
        assertEquals("y".repeat(Rows.LIMIT), new String(b.bytes()));
        // What a page of one row costs, against the hundreds of megabytes of the values.
        assertTrue(allocated < 8_000_000, provider + " allocated " + allocated + " bytes");
      } finally {
        execute(connection, "DROP TABLE huge");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void namesThatNeedQuotingAreRead(Provider provider) throws Exception {
    var name = "Odd \"Name\" `x` it's";
    var key = "Key \"One\"";
    var other = "back `tick` Ä";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (" + provider.quote(other)
              + " varchar(10), " + provider.quote(key) + " integer PRIMARY KEY)",
          "INSERT INTO " + qualified(provider, name) + " VALUES ('b', 2), ('a', 1)");
      try {
        var table = table(provider, name, Table.Kind.TABLE);
        var page =
            Rows.read(connection, provider, table, columns(connection, provider, table), 0, 10);
        assertEquals(
            List.of(other, key), page.fields().stream().map(Field::name).toList());
        assertEquals(List.of(List.of("a", 1L), List.of("b", 2L)), page.rows());
        assertEquals(2, Rows.count(connection, provider, table));
        assertTrue(Rows.estimate(connection, provider, table).isPresent());
      } finally {
        execute(connection, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void theEstimateFollowsTheStatisticsAndTheCountIsExact(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(connection, "CREATE TABLE counted (id integer PRIMARY KEY)");
      try {
        var table = table(provider, "counted", Table.Kind.TABLE);
        var empty = Rows.estimate(connection, provider, table).orElseThrow();
        execute(connection, "INSERT INTO counted " + numbers(provider, 20_000));
        var inserted = Rows.estimate(connection, provider, table).orElseThrow();
        assertEquals(20_000, Rows.count(connection, provider, table));
        execute(connection, pg ? "ANALYZE counted" : "ANALYZE TABLE counted");
        var analysed = Rows.estimate(connection, provider, table).orElseThrow();
        System.out.println(provider + " estimates of 20000 rows: " + empty + " when empty, "
            + inserted + " right after inserting them, " + analysed + " after analysing");
        assertTrue(empty >= 0 && inserted > 0);
        assertTrue(analysed > 10_000 && analysed < 40_000, String.valueOf(analysed));

        var view = table(provider, "active_customer", Table.Kind.VIEW);
        assertEquals(pg, Rows.estimate(connection, provider, view).isPresent());
        assertEquals(2, Rows.count(connection, provider, view));
        assertEquals(
            5000, Rows.count(connection, provider, table(provider, "reading", Table.Kind.TABLE)));
      } finally {
        execute(connection, "DROP TABLE counted");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void anInterruptCancelsAReadAndACountAndKeepsTheConnection(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          pg
              ? "CREATE VIEW slow AS SELECT 1 AS id FROM pg_sleep(30)"
              : "CREATE VIEW slow AS SELECT SLEEP(30) AS id");
      try {
        var slow = table(provider, "slow", Table.Kind.VIEW);
        var columns = columns(connection, provider, slow);
        List<Executable> calls = List.of(
            () -> Rows.read(connection, provider, slow, columns, 0, 10),
            () -> Rows.count(connection, provider, slow));
        for (var call : calls) {
          var failure = new QueryException[1];
          var interrupted = new boolean[1];
          var caller = Thread.ofPlatform().start(() -> {
            failure[0] = assertThrows(QueryException.class, call);
            interrupted[0] = Thread.currentThread().isInterrupted();
          });
          awaitRunning(server, qualified(provider, "slow"));
          var started = System.nanoTime();
          caller.interrupt();
          caller.join(Query.GRACE.plusSeconds(1));
          assertFalse(caller.isAlive());
          assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Query.GRACE) < 0);
          assertEquals(Failure.CANCELLED, failure[0].failure());
          assertTrue(interrupted[0]);

          assertFalse(connection.isClosed());
          assertEquals(
              5000, Rows.count(connection, provider, table(provider, "reading", Table.Kind.TABLE)));
        }
      } finally {
        execute(connection, "DROP VIEW slow");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aTableDroppedSinceTheLastReadIsNotFound(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, "CREATE TABLE dropped (id integer PRIMARY KEY)");
      var table = table(provider, "dropped", Table.Kind.TABLE);
      List<Column> columns;
      try {
        columns = columns(connection, provider, table);
        assertEquals(
            List.of(), Rows.read(connection, provider, table, columns, 0, 10).rows());
      } finally {
        execute(connection, "DROP TABLE dropped");
      }
      assertFailure(
          Failure.NOT_FOUND, () -> Rows.read(connection, provider, table, columns, 0, 10));
      assertFailure(Failure.NOT_FOUND, () -> Rows.count(connection, provider, table));
      assertFailure(Failure.NOT_FOUND, () -> Rows.estimate(connection, provider, table));
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aConnectionThatTheServerEndedIsLost(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var reading = table(provider, "reading", Table.Kind.TABLE);
    try (var connection = open(provider);
        var other = open(provider)) {
      var columns = columns(connection, provider, reading);
      var id = selectLong(connection, pg ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()");
      execute(other, pg ? "SELECT pg_terminate_backend(" + id + ")" : "KILL " + id);
      assertFailure(
          Failure.CONNECTION_LOST, () -> Rows.read(connection, provider, reading, columns, 0, 10));
    }
  }

  @Test
  void aTableTheUserMayNotReadIsAccessDenied() throws Exception {
    var provider = Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var reading = table(provider, "reading", Table.Kind.TABLE);
    try (var admin = open(provider)) {
      var columns = columns(admin, provider, reading);
      execute(admin, "CREATE ROLE rows_reader LOGIN PASSWORD '" + TestServer.PASSWORD + "'");
      try {
        var reader = Connector.open(
            server.profile().withUser("rows_reader"),
            TestServer.password(),
            server.driver(),
            TIMEOUT);
        try (reader) {
          assertFailure(
              Failure.ACCESS_DENIED, () -> Rows.read(reader, provider, reading, columns, 0, 10));
          assertFailure(Failure.ACCESS_DENIED, () -> Rows.count(reader, provider, reading));
        }
      } finally {
        execute(admin, "DROP ROLE rows_reader");
      }
    }
  }

  @Test
  void aPostgresqlConnectionReadsOnlyItsOwnDatabase() throws Exception {
    var provider = Provider.POSTGRESQL;
    var server = TestServer.of(provider);
    var reading = table(provider, "reading", Table.Kind.TABLE);
    try (var connection = Connector.open(
        server.profile().withDatabase(TestServer.SPACED_DATABASE),
        TestServer.password(),
        server.driver(),
        TIMEOUT)) {
      assertThrows(IllegalArgumentException.class, () -> Rows.count(connection, provider, reading));
    }
  }

  private static Container sample(Provider provider) {
    return provider == Provider.POSTGRESQL ? new Schema(HOORI, "public", false) : HOORI;
  }

  private static Table table(Provider provider, String name, Table.Kind kind) {
    return new Table(sample(provider), name, kind);
  }

  private static List<Column> columns(Connection connection, Provider provider, Table table)
      throws Exception {
    return Catalog.columns(connection, provider, table);
  }

  private static String qualified(Provider provider, String name) {
    return provider.quote(sample(provider).name()) + "." + provider.quote(name);
  }

  private static List<Long> ids(int from, int to) {
    return IntStream.range(from, to).mapToObj(i -> (long) i).toList();
  }

  private static List<Object> ids(Page page) {
    return page.rows().stream().map(row -> row.getFirst()).toList();
  }

  private static String repeat(Provider provider, String text, long times) {
    return provider == Provider.POSTGRESQL
        ? "repeat('" + text + "', " + times + ")"
        : "REPEAT('" + text + "', " + times + ")";
  }

  private static String repeatBytes(Provider provider, int times) {
    var hex = HexFormat.of().formatHex(new byte[] {7}).repeat(times);
    return provider == Provider.POSTGRESQL ? "'\\x" + hex + "'::bytea" : "x'" + hex + "'";
  }

  // The numbers from 1 to the given one, at most 100000.
  private static String numbers(Provider provider, int to) {
    if (provider == Provider.POSTGRESQL) {
      return "SELECT generate_series(1, " + to + ")";
    }
    return "WITH digit (d) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
        + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
        + " UNION ALL SELECT 8 UNION ALL SELECT 9)"
        + " SELECT n FROM (SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d + 1 AS n"
        + " FROM digit a, digit b, digit c, digit e, digit f) numbers WHERE n <= " + to;
  }

  private static Connection open(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    return Connector.open(server.profile(), TestServer.password(), server.driver(), TIMEOUT);
  }

  private static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static long selectLong(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // Until the server runs a statement that reads the table, so that the interrupt finds it there.
  private static void awaitRunning(TestServer server, String table) throws Exception {
    var query = server.provider() == Provider.POSTGRESQL
        ? "SELECT count(*) FROM pg_stat_activity"
            + " WHERE query LIKE ? AND state = 'active' AND pid <> pg_backend_pid()"
        : "SELECT count(*) FROM information_schema.processlist"
            + " WHERE info LIKE ? AND id <> CONNECTION_ID()";
    try (var connection = open(server.provider());
        var statement = connection.prepareStatement(query)) {
      statement.setString(1, "%FROM " + table + "%");
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
    throw new AssertionError("No statement reads " + table);
  }

  static QueryException assertFailure(Failure failure, Executable executable) {
    var e = assertThrows(QueryException.class, executable);
    assertEquals(failure, e.failure(), e.getMessage());
    return e;
  }
}

package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Result;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A statement's result read as {@link Rows#read} reads a table: for every type that {@link
 * RowsServerTest} reads, {@code SELECT *} of the table gives the same kinds and values, but where
 * the result's metadata does not tell as much as the catalogue.
 */
class RowReaderServerTest {

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aQueryOfATableGivesTheKindsAndValuesOfTheTable(Provider provider) throws Exception {
    var types = RowsServerTest.types(provider);
    var pg = provider == Provider.POSTGRESQL;
    var zone = TimeZone.getDefault();
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
        execute(connection, "SET SESSION sql_mode = ''", "SET SESSION time_zone = '+05:00'");
      }
      execute(
          connection,
          "CREATE TABLE typed (id integer PRIMARY KEY" + definitions + ")"
              + (pg ? "" : " DEFAULT CHARSET utf8mb4"),
          "INSERT INTO typed VALUES (1" + literals + ")",
          "INSERT INTO typed (id) VALUES (2)");
      try {
        var table = table(provider, "typed");
        var page = Rows.read(
            connection, provider, table, Catalog.columns(connection, provider, table), 0, 10);
        var query = query(connection, provider, "SELECT * FROM typed ORDER BY id");
        assertEquals(
            page.fields().stream().map(Field::name).toList(),
            query.fields().stream().map(Field::name).toList());
        for (var i = 0; i < types.size(); i++) {
          var typed = types.get(i);
          var label = typed.type() + " " + typed.literal();
          var expectedKind = page.fields().get(i + 1).kind();
          Object expected = page.rows().getFirst().get(i + 1);
          if (provider == Provider.MYSQL && typed.type().equals("bit(1)")) {
            // MySQL Connector/J reports bit(1) as it reports tinyint(1).
            expectedKind = Kind.INTEGER;
            expected = expected.equals(true) ? 1L : 0L;
          } else if (!pg && typed.type().startsWith("timestamp")) {
            // The time in the session's zone of +05:00.
            expectedKind = Kind.DATE_TIME;
            expected = expected instanceof String
                ? expected
                : LocalDateTime.of(2024, 3, 31, 7, 30, 0, 123_000_000);
          }
          assertEquals(expectedKind, query.fields().get(i + 1).kind(), label);
          var value = query.rows().getFirst().get(i + 1);
          assertEquals(expected, value, label);
          assertEquals(expected.getClass(), value.getClass(), label);
          assertEquals(null, query.rows().get(1).get(i + 1), label);
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
  void aQueryCutsLongTextAndBinaryDataAsATableDoes(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var limit = Rows.LIMIT;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE long_value (id integer PRIMARY KEY, t " + (pg ? "text" : "longtext")
              + ", b " + (pg ? "bytea" : "longblob") + ")" + (pg ? "" : " DEFAULT CHARSET utf8mb4"),
          "INSERT INTO long_value VALUES (1, " + repeat(provider, "😀", limit) + ", "
              + bytes(provider, limit) + ")",
          "INSERT INTO long_value VALUES (2, " + repeat(provider, "😀", limit + 1) + ", "
              + bytes(provider, limit + 1) + ")");
      try {
        var table = table(provider, "long_value");
        var page = Rows.read(
            connection, provider, table, Catalog.columns(connection, provider, table), 0, 2);
        var query = query(connection, provider, "SELECT * FROM long_value ORDER BY id");
        assertEquals(page.rows(), query.rows());
        assertEquals(
            new Cut("😀".repeat(limit), 4L * (limit + 1)), query.rows().get(1).get(1));
        assertInstanceOf(Binary.class, query.rows().get(1).get(2));
      } finally {
        execute(connection, "DROP TABLE long_value");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void expressionsHaveTheKindsOfTheirTypes(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      var query = query(
          connection,
          provider,
          pg
              ? "SELECT 1 + 1, 1.5 * 2, 'a' || 'b', now() > now() - interval '1 day',"
                  + " DATE '2024-01-02' + 1, max(label), NULL FROM reading"
              : "SELECT 1 + 1, 1.5 * 2, CONCAT('a', 'b'), 1 = 1, DATE '2024-01-02' + INTERVAL 1 DAY,"
                  + " MAX(label), NULL FROM reading");
      assertEquals(
          List.of(
              Kind.INTEGER,
              Kind.DECIMAL,
              Kind.TEXT,
              pg ? Kind.BOOLEAN : Kind.INTEGER,
              pg ? Kind.DATE : Kind.DATE,
              Kind.TEXT,
              pg ? Kind.TEXT : Kind.OTHER),
          query.fields().stream().map(Field::kind).toList());
      var row = query.rows().getFirst();
      assertEquals(2L, row.get(0));
      assertEquals("ab", row.get(2));
      assertEquals(pg ? true : 1L, row.get(3));
      assertEquals(java.time.LocalDate.of(2024, 1, 3), row.get(4));
      assertEquals("reading 999", row.get(5));
    }
  }

  // The rows of the first result, all of them.
  private static Page query(Connection connection, Provider provider, String sql) {
    try (var execution = new Execution(connection, provider, sql)) {
      var portion = execution.next(1000);
      var rows = assertInstanceOf(Result.Rows.class, portion.results().getFirst());
      return new Page(rows.fields(), new ArrayList<>(rows.rows()));
    }
  }

  private static Table table(Provider provider, String name) {
    var hoori = new Database(TestServer.DATABASE, false);
    return new Table(
        provider == Provider.POSTGRESQL ? new Schema(hoori, "public", false) : hoori,
        name,
        Table.Kind.TABLE);
  }

  private static String repeat(Provider provider, String text, long times) {
    return (provider == Provider.POSTGRESQL ? "repeat('" : "REPEAT('") + text + "', " + times + ")";
  }

  private static String bytes(Provider provider, int times) {
    var hex = HexFormat.of().formatHex(new byte[] {7}).repeat(times);
    return provider == Provider.POSTGRESQL ? "'\\x" + hex + "'::bytea" : "x'" + hex + "'";
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

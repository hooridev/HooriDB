package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * How long a page of 100 rows takes at the start, the middle and the end of a million rows, with a
 * primary key and without. It fills a table for a minute, so it runs only when asked: {@code mvn
 * -pl core test -Dtest=DeepPagingServerTest -Dhooridb.test.deep=true}. It prints the times.
 */
@EnabledIfSystemProperty(named = "hooridb.test.deep", matches = "true")
class DeepPagingServerTest {

  private static final int ROWS = 1_000_000;
  private static final int PAGE = 100;
  private static final int RUNS = 5;

  @ParameterizedTest
  @EnumSource(Provider.class)
  void pagesFarIntoAMillionRows(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var pg = provider == Provider.POSTGRESQL;
    var hoori = new Database(TestServer.DATABASE, false);
    var container = pg ? new Schema(hoori, "public", false) : hoori;
    try (var connection = Connector.open(
            server.profile(), TestServer.password(), server.driver(), Duration.ofSeconds(10))
        .connection()) {
      var numbers = pg
          ? "SELECT n, 'reading ' || n, n / 8.0 FROM generate_series(1, " + ROWS + ") n"
          : "WITH digit (d) AS (SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
              + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
              + " UNION ALL SELECT 8 UNION ALL SELECT 9)"
              + " SELECT n, CONCAT('reading ', n), n / 8.0 FROM (SELECT a.d + 10 * b.d"
              + " + 100 * c.d + 1000 * e.d + 10000 * f.d + 100000 * g.d + 1 AS n"
              + " FROM digit a, digit b, digit c, digit e, digit f, digit g) numbers";
      execute(
          connection,
          "CREATE TABLE deep (id integer PRIMARY KEY, label varchar(20) NOT NULL,"
              + " amount decimal(10, 3))",
          "INSERT INTO deep " + numbers,
          "CREATE TABLE deep_loose AS SELECT * FROM deep",
          pg ? "ANALYZE" : "ANALYZE TABLE deep, deep_loose");
      try {
        for (var name : List.of("deep", "deep_loose")) {
          var table = new Table(container, name, Table.Kind.TABLE);
          var columns = Catalog.columns(connection, provider, table);
          var line = new StringBuilder(provider + " " + name + ":");
          for (var from : List.of(0, ROWS / 2, ROWS - PAGE)) {
            var times = new ArrayList<Long>();
            for (var run = 0; run < RUNS; run++) {
              var started = System.nanoTime();
              var page = Rows.read(connection, provider, table, columns, from, from + PAGE);
              times.add((System.nanoTime() - started) / 1_000);
              assertEquals(PAGE, page.rows().size());
              if (name.equals("deep")) {
                assertEquals((long) from + 1, page.rows().getFirst().getFirst());
              }
            }
            times.sort(null);
            line.append(String.format(" at %d %.1f ms", from, times.get(RUNS / 2) / 1000.0));
          }
          var started = System.nanoTime();
          assertEquals(ROWS, Rows.count(connection, provider, table));
          line.append(
              String.format(", count %.1f ms", (System.nanoTime() - started) / 1_000_000.0));
          started = System.nanoTime();
          var estimate = Rows.estimate(connection, provider, table);
          line.append(String.format(
              ", estimate %s in %.1f ms", estimate, (System.nanoTime() - started) / 1_000_000.0));
          System.out.println(line);
        }
      } finally {
        execute(connection, "DROP TABLE deep", "DROP TABLE deep_loose");
      }
    }
  }

  private static void execute(Connection connection, String... statements) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }
}

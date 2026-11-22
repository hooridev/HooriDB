package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.RowsServerTest.assertFailure;
import static dev.hoori.db.core.data.RowsServerTest.columns;
import static dev.hoori.db.core.data.RowsServerTest.execute;
import static dev.hoori.db.core.data.RowsServerTest.ids;
import static dev.hoori.db.core.data.RowsServerTest.open;
import static dev.hoori.db.core.data.RowsServerTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException.Failure;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Filtering and ordering a table's rows on real SQL servers, and refusing bad clauses. */
class RowsFilterServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void filteredAndOrderedPagesAndCountsWorkWithStablePaging(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var reading = table(provider, "reading", Table.Kind.TABLE);
      var columns = columns(connection, provider, reading);

      // WHERE only: keeps key order.
      var whereOnly = new Rows.Filter("id > 10 AND id <= 30", "");
      assertEquals(20, Rows.count(connection, provider, reading, whereOnly, false));
      assertTrue(Rows.estimate(connection, provider, reading, whereOnly, false).isPresent());
      var page = Rows.read(connection, provider, reading, columns, 0, 10, whereOnly, false);
      assertEquals(List.of(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L), ids(page));

      // ORDER BY only: user order first.
      var orderOnly = new Rows.Filter("", "id DESC");
      assertEquals(5000, Rows.count(connection, provider, reading, orderOnly, false));
      var top = Rows.read(connection, provider, reading, columns, 0, 5, orderOnly, false);
      assertEquals(List.of(5000L, 4999L, 4998L, 4997L, 4996L), ids(top));

      // Both WHERE and ORDER BY with ties in the user order, plus trailing -- comments.
      var ties = new Rows.Filter(
          "id <= 15 AND amount IS NOT NULL -- drop id 10", "MOD(id, 3) DESC -- tie-break by id");
      assertEquals(14, Rows.count(connection, provider, reading, ties, false));
      var all =
          Rows.read(connection, provider, reading, columns, 0, 20, ties, false).rows();
      assertEquals(14, all.size());
      // MOD(id, 3) = 2: 2, 5, 8, 11, 14; = 1: 1, 4, 7, 13 (10 dropped); = 0: 3, 6, 9, 12, 15.
      assertEquals(
          List.of(2L, 5L, 8L, 11L, 14L, 1L, 4L, 7L, 13L, 3L, 6L, 9L, 12L, 15L),
          all.stream().map(List::getFirst).toList());
      var paged = new ArrayList<List<Object>>();
      for (var from = 0; from < all.size(); from += 4) {
        paged.addAll(Rows.read(connection, provider, reading, columns, from, from + 4, ties, false)
            .rows());
      }
      assertEquals(all, paged);

      // Unknown column in a filter fails as NOT_FOUND, not COLUMNS_CHANGED.
      assertFailure(
          Failure.NOT_FOUND,
          () -> Rows.read(
              connection,
              provider,
              reading,
              columns,
              0,
              10,
              new Rows.Filter("no_such_col = 1", ""),
              false));

      // A count and an estimate name the table as a page does.
      var aliased = new Rows.Filter("r.id <= 5", "");
      assertEquals(5, Rows.count(connection, provider, reading, aliased, false));
      assertEquals(
          5,
          Rows.read(connection, provider, reading, columns, 0, 10, aliased, false)
              .rows()
              .size());
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aFilteredReadTellsALostColumnFromAnUnknownOne(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE f_lost (id integer PRIMARY KEY, gone integer)",
          "INSERT INTO f_lost VALUES (1, 1)");
      try {
        var table = table(provider, "f_lost", Table.Kind.TABLE);
        var columns = columns(connection, provider, table);
        execute(connection, "ALTER TABLE f_lost DROP COLUMN gone");
        var filter = new Rows.Filter("id = 1", "");
        assertFailure(
            Failure.COLUMNS_CHANGED,
            () -> Rows.read(connection, provider, table, columns, 0, 10, filter, false));
      } finally {
        execute(connection, "DROP TABLE f_lost");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aFilteredEstimateIsOfTheRowsItKeeps(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE f_many (id integer PRIMARY KEY, grp integer)",
          "INSERT INTO f_many SELECT id, id % 100 FROM reading",
          provider == Provider.POSTGRESQL ? "ANALYZE f_many" : "ANALYZE TABLE f_many");
      try {
        var table = table(provider, "f_many", Table.Kind.TABLE);
        var all = Rows.estimate(connection, provider, table).orElseThrow();
        // A condition on a column without an index, which the server reads the whole table for.
        var some = Rows.estimate(connection, provider, table, new Rows.Filter("grp = 7", ""), false)
            .orElseThrow();
        // MariaDB's EXPLAIN says nothing of a condition's selectivity without persistent
        // statistics.
        assertTrue(provider == Provider.MARIADB || some < all / 2, some + " of " + all);
      } finally {
        execute(connection, "DROP TABLE f_many");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void mariaDbJsonDetectionAndCollisionsWorkWithFilters(Provider provider) throws Exception {
    var jsonType = provider == Provider.POSTGRESQL ? "jsonb" : "json";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE filtered_json (id integer PRIMARY KEY, payload " + jsonType
              + ", grp integer, __hoori_order integer)",
          "INSERT INTO filtered_json VALUES"
              + " (1, '{\"k\": 1}', 2, 30),"
              + " (2, '{\"k\": 2}', 1, 20),"
              + " (3, '{\"k\": 3}', 2, 10),"
              + " (4, '{\"k\": 4}', 1, 40)");
      try {
        var table = table(provider, "filtered_json", Table.Kind.TABLE);
        var columns = columns(connection, provider, table);
        if (provider == Provider.MARIADB) {
          assertEquals("longtext", columns.get(1).type());
        }
        for (var filter : List.of(
            new Rows.Filter("grp = 2", ""),
            new Rows.Filter("", "grp ASC, __hoori_order DESC"),
            new Rows.Filter("grp = 2", "__hoori_order ASC"),
            new Rows.Filter("id < 0", "__hoori_order DESC"))) {
          var page = Rows.read(connection, provider, table, columns, 0, 10, filter, false);
          assertEquals(Kind.JSON, page.fields().get(1).kind(), filter.toString());
        }
        var ordered = Rows.read(
            connection,
            provider,
            table,
            columns,
            0,
            10,
            new Rows.Filter("grp = 2", "__hoori_order ASC"),
            false);
        assertEquals(List.of(3L, 1L), ids(ordered));
        assertEquals(
            2, Rows.count(connection, provider, table, new Rows.Filter("grp = 2", ""), false));
      } finally {
        execute(connection, "DROP TABLE filtered_json");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void tablesWithoutKeyAndViewsFilterAndPageStably(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE loose_f (grp integer, name varchar(10))",
          "INSERT INTO loose_f VALUES (2, 'b'), (1, 'a'), (2, 'a'), (1, 'c'), (2, 'c'), (1, 'b')");
      try {
        var loose = table(provider, "loose_f", Table.Kind.TABLE);
        var columns = columns(connection, provider, loose);
        var filter = new Rows.Filter("grp >= 1", "grp DESC");
        var all = Rows.read(connection, provider, loose, columns, 0, 10, filter, false)
            .rows();
        assertEquals(6, all.size());
        assertEquals(
            List.of(2L, 2L, 2L, 1L, 1L, 1L), all.stream().map(List::getFirst).toList());
        var paged = new ArrayList<List<Object>>();
        for (var from = 0; from < all.size(); from += 2) {
          paged.addAll(
              Rows.read(connection, provider, loose, columns, from, from + 2, filter, false)
                  .rows());
        }
        assertEquals(all, paged);
      } finally {
        execute(connection, "DROP TABLE loose_f");
      }
    }
  }

  @Test
  void postgreSqlInheritedTableKeepsPlacesWithFilter() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE f_parent (id integer PRIMARY KEY, tag text)",
          "CREATE TABLE f_child () INHERITS (f_parent)",
          "INSERT INTO f_parent VALUES (1, 'p1'), (2, 'p2'), (3, 'p3')",
          "INSERT INTO f_child VALUES (1, 'c1'), (2, 'c2'), (3, 'c3')");
      try {
        var parent = table(provider, "f_parent", Table.Kind.TABLE);
        var columns = columns(connection, provider, parent);
        var filter = new Rows.Filter("id >= 2", "id DESC");
        var all = Rows.read(connection, provider, parent, columns, 0, 10, filter, false)
            .rows();
        assertEquals(4, all.size());
        assertEquals(List.of(3L, 3L, 2L, 2L), all.stream().map(List::getFirst).toList());
        var paged = new ArrayList<List<Object>>();
        for (var from = 0; from < all.size(); from += 2) {
          paged.addAll(
              Rows.read(connection, provider, parent, columns, from, from + 2, filter, false)
                  .rows());
        }
        assertEquals(all, paged);
      } finally {
        execute(connection, "DROP TABLE f_parent CASCADE");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void refusedClausesSendNothingToTheServer(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var reading = table(provider, "reading", Table.Kind.TABLE);
      var columns = columns(connection, provider, reading);
      var sent = new CopyOnWriteArrayList<Sent>();
      for (var bad : List.of("1=1; DROP TABLE x", "1) OR (1", "1=1 /*", "label = 'open")) {
        for (var filter : List.of(new Rows.Filter(bad, ""), new Rows.Filter("", bad))) {
          sent.clear();
          Journal.recording(sent::add, () -> {
            assertFailure(
                Failure.CLAUSE_REFUSED,
                () -> Rows.read(connection, provider, reading, columns, 0, 10, filter, false));
            assertFailure(
                Failure.CLAUSE_REFUSED,
                () -> Rows.count(connection, provider, reading, filter, false));
            assertFailure(
                Failure.CLAUSE_REFUSED,
                () -> Rows.estimate(connection, provider, reading, filter, false));
            return null;
          });
          assertEquals(List.of(), sent, provider + " sent SQL for " + filter);
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void readOnlyConnectionRefusesReadWriteClauseWithoutSending(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    try (var connection = Connector.open(
            server.profile().withReadOnly(true), TestServer.password(), server.driver(), TIMEOUT)
        .connection()) {
      var reading = table(provider, "reading", Table.Kind.TABLE);
      var columns = columns(connection, provider, reading);
      var bypass = provider == Provider.POSTGRESQL
          ? "set_config('default_transaction_read_only', 'off', false) IS NOT NULL"
          : "@@transaction_read_only = 0";
      var filter = new Rows.Filter(bypass, "");
      var sent = new CopyOnWriteArrayList<Sent>();
      Journal.recording(sent::add, () -> {
        assertFailure(
            Failure.CLAUSE_REFUSED,
            () -> Rows.read(connection, provider, reading, columns, 0, 10, filter, true));
        assertFailure(
            Failure.CLAUSE_REFUSED, () -> Rows.count(connection, provider, reading, filter, true));
        assertFailure(
            Failure.CLAUSE_REFUSED,
            () -> Rows.estimate(connection, provider, reading, filter, true));
        return null;
      });
      assertEquals(List.of(), sent, provider + " sent SQL for " + bypass);

      // A harmless filter still works on the read-only connection.
      var ok = new Rows.Filter("id <= 3", "id DESC");
      assertEquals(
          List.of(3L, 2L, 1L),
          ids(Rows.read(connection, provider, reading, columns, 0, 10, ok, true)));
      assertEquals(3, Rows.count(connection, provider, reading, ok, true));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void unfilteredSqlStaysUnchanged(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      var reading = table(provider, "reading", Table.Kind.TABLE);
      var columns = columns(connection, provider, reading);
      var sent = new CopyOnWriteArrayList<Sent>();
      Journal.recording(
          sent::add,
          () -> Rows.read(connection, provider, reading, columns, 0, 10, Rows.Filter.NONE, false));
      var sql = sent.getLast().text();
      assertFalse(sql.contains("WHERE"), sql);
      assertFalse(sql.contains("ROW_NUMBER"), sql);
    }
  }
}

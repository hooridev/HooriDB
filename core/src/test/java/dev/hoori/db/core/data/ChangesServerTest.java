package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.RowsServerTest.columns;
import static dev.hoori.db.core.data.RowsServerTest.execute;
import static dev.hoori.db.core.data.RowsServerTest.open;
import static dev.hoori.db.core.data.RowsServerTest.qualified;
import static dev.hoori.db.core.data.RowsServerTest.table;
import static dev.hoori.db.core.data.RowsServerTest.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Writing changes of rows to real servers, in tables of each test's own, which it drops on a
 * connection of its own.
 */
class ChangesServerTest {

  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final OffsetDateTime T1 =
      OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 123_000_000, ZoneOffset.UTC);
  private static final OffsetDateTime T2 =
      OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
  private static final OffsetDateTime T3 =
      OffsetDateTime.of(2024, 10, 27, 1, 30, 0, 0, ZoneOffset.UTC);

  @ParameterizedTest
  @EnumSource(Provider.class)
  void changesUpdateInsertAndDeleteRowsByAKeyOfSeveralColumns(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var name = unique("changes_keyed");
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      // On MariaDB and MySQL the moments are written in the session's zone, and read as moments.
      var at = pg ? "+05" : "";
      if (!pg) {
        execute(connection, "SET SESSION time_zone = '+05:00'");
      }
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (a integer, b varchar(20), c "
              + (pg ? "timestamptz" : "timestamp(3)") + " NOT NULL, v varchar(20), n integer,"
              + " PRIMARY KEY (b, a, c))",
          "INSERT INTO " + qualified(provider, name) + " VALUES"
              + " (1, 'x', '2024-03-31 07:30:00.123" + at + "', 'old', 7),"
              + " (1, 'y', '2024-03-31 07:30:00.123" + at + "', 'gone', 8),"
              + " (2, 'x', '2024-01-02 08:04:05" + at + "', 'kept', 9)");
      try {
        var columns = columns(connection, provider, table);
        var read = Rows.read(connection, provider, table, columns, 0, 10).rows();
        assertEquals(
            List.of(
                row(1L, "x", T1, "old", 7L),
                row(2L, "x", T2, "kept", 9L),
                row(1L, "y", T1, "gone", 8L)),
            read);

        var changes = Changes.of(table, columns)
            .orElseThrow()
            .edit(read.get(0), 3, "new")
            .edit(read.get(0), 4, null)
            .edit(read.get(1), 0, 4L) // a new key
            .delete(read.get(2))
            .insert(values(0, 3L, 1, "z", 2, T3, 3, null));
        assertEquals(new Changes.Outcome.Committed(), changes.write(connection, provider));

        assertEquals(
            List.of(
                row(1L, "x", T1, "new", null),
                row(4L, "x", T2, "kept", 9L),
                row(3L, "z", T3, null, null)),
            Rows.read(connection, provider, table, columns, 0, 10).rows());
        assertLeftAsItWas(connection, provider);
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  // MariaDB and MySQL count the rows an UPDATE meets, not the ones it changes, as their drivers
  // ask.
  @ParameterizedTest
  @EnumSource(Provider.class)
  void aCellGivenTheValueItHoldsCountsItsRow(Provider provider) throws Exception {
    var name = unique("changes_same");
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY, v varchar(20))",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, 'same')");
      try {
        var columns = columns(connection, provider, table);
        var row = Rows.read(connection, provider, table, columns, 0, 10).rows().getFirst();
        var same = new Changes(table, columns, List.of(new Change.Update(row, values(1, "same"))));
        assertEquals(new Changes.Outcome.Committed(), same.write(connection, provider));
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aTableWithoutPrimaryKeyCannotBeChanged(Provider provider) throws Exception {
    var name = unique("changes_keyless");
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer, v varchar(20))",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, 'one')");
      try {
        var columns = columns(connection, provider, table);
        assertTrue(Changes.of(table, columns).isEmpty());
        var row = Rows.read(connection, provider, table, columns, 0, 10).rows().getFirst();
        assertThrows(
            IllegalArgumentException.class,
            () -> new Changes(table, columns, List.of(new Change.Delete(row))));
        assertEquals(List.of(row(1L, "one")), rows(provider, table));
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aRowGoneSinceItWasReadOrAFailureRollsAllBack(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var name = unique("changes_gone");
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      if (!pg) {
        execute(connection, "SET SESSION time_zone = '+05:00'");
      }
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY, v varchar(20))",
          "INSERT INTO " + qualified(provider, name)
              + " VALUES (1, 'one'), (2, 'two'), (3, 'three')");
      try {
        var columns = columns(connection, provider, table);
        var read = Rows.read(connection, provider, table, columns, 0, 10).rows();
        try (var other = open(provider)) {
          execute(other, "DELETE FROM " + qualified(provider, name) + " WHERE id = 2");
        }
        var none = Changes.of(table, columns).orElseThrow();
        // The deletion and the first update run before the update of the row that is gone.
        var changes = none.edit(read.get(0), 1, "changed")
            .edit(read.get(1), 1, "lost")
            .delete(read.get(2))
            .insert(values(0, 4L, 1, "four"));
        assertEquals(
            new Changes.Outcome.Missed(changes.changes().get(1), 0),
            changes.write(connection, provider));
        var left = List.of(row(1L, "one"), row(3L, "three"));
        assertEquals(left, rows(provider, table));
        assertLeftAsItWas(connection, provider);

        // The new row's key is taken, after the update ran.
        var taken = none.edit(read.get(0), 1, "changed").insert(values(0, 3L, 1, "again"));
        assertThrows(QueryException.class, () -> taken.write(connection, provider));
        assertEquals(left, rows(provider, table));
        assertLeftAsItWas(connection, provider);
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Provider.class,
      names = {"MARIADB", "MYSQL"})
  void aTableThatCannotRollBackIsNotChanged(Provider provider) throws Exception {
    var name = unique("changes_myisam");
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name)
              + " (id integer PRIMARY KEY, v varchar(20)) ENGINE=MyISAM",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, 'one')");
      try {
        var columns = columns(connection, provider, table);
        var row = Rows.read(connection, provider, table, columns, 0, 10).rows().getFirst();
        var changes = Changes.of(table, columns).orElseThrow().edit(row, 1, "changed");
        assertEquals(new Changes.Outcome.CannotRollBack(), changes.write(connection, provider));
        assertEquals(List.of(row(1L, "one")), rows(provider, table));
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  // Each value of each type, as Rows reads it, under the conditions of RowsServerTest.
  @ParameterizedTest
  @EnumSource(Provider.class)
  void whatWasReadIsWrittenBackAsItWas(Provider provider) throws Exception {
    var types = types(provider);
    var pg = provider == Provider.POSTGRESQL;
    var schema = unique("changes_typed");
    var name = pg ? "typed" : schema;
    var table = pg
        ? new Table(new Schema(HOORI, schema, false), name, Table.Kind.TABLE)
        : table(provider, name, Table.Kind.TABLE);
    var created = provider.quote(table.container().name()) + "." + provider.quote(name);
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
            "CREATE SCHEMA " + schema,
            "SET search_path = " + schema,
            "CREATE TYPE mood AS ENUM ('sad', 'happy')",
            "CREATE DOMAIN positive AS integer CHECK (VALUE > 0)",
            "SET lc_monetary = 'C'");
      } else {
        execute(connection, "SET SESSION sql_mode = ''", "SET SESSION time_zone = '+05:00'");
      }
      try {
        execute(
            connection,
            "CREATE TABLE " + created + " (id integer PRIMARY KEY" + definitions + ")"
                + (pg ? "" : " DEFAULT CHARSET utf8mb4"),
            "INSERT INTO " + created + " VALUES (1" + literals + ")",
            "INSERT INTO " + created + " (id) VALUES (2)");
        if (pg) {
          execute(connection, "RESET search_path");
        }
        var columns = columns(connection, provider, table);
        var read = Rows.read(connection, provider, table, columns, 0, 10).rows();
        var first = read.getFirst();
        var changes = Changes.of(table, columns).orElseThrow();
        var copy = new TreeMap<Integer, Object>();
        copy.put(0, 3L);
        for (var i = 1; i < columns.size(); i++) {
          copy.put(i, first.get(i));
          changes = changes.edit(read.get(1), i, first.get(i));
        }
        changes = changes.insert(copy);
        assertEquals(new Changes.Outcome.Committed(), changes.write(connection, provider));

        var written = Rows.read(connection, provider, table, columns, 0, 10).rows();
        assertEquals(3, written.size());
        for (var i = 1; i < columns.size(); i++) {
          var label = types.get(i - 1).type() + " " + types.get(i - 1).literal();
          assertEquals(first.get(i), written.get(1).get(i), "updated " + label);
          assertEquals(first.get(i), written.get(2).get(i), "inserted " + label);
        }
        // Every value of the row names it, as a key would.
        var deleted = Changes.of(table, columns)
            .orElseThrow()
            .delete(written.get(2))
            .write(connection, provider);
        assertEquals(new Changes.Outcome.Committed(), deleted);
        assertLeftAsItWas(connection, provider);
      } finally {
        dropAfter(
            provider,
            pg ? "DROP SCHEMA " + schema + " CASCADE" : "DROP TABLE IF EXISTS " + created);
      }
    } finally {
      TimeZone.setDefault(zone);
    }
  }

  // A JDBC escape, a parameter's mark and the quote in a name stay part of it, and on PostgreSQL
  // the key is compared by its own operator, whatever a user has created under the same name.
  @ParameterizedTest
  @EnumSource(Provider.class)
  void namesAndAUsersOperatorsChangeNothing(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var name = unique("changes we?rd");
    var hostile = unique("changes_hostile");
    var column = "v? {d '2024-01-01'} " + provider.dialect().syntax().identifierQuote() + "q";
    var table = table(provider, name, Table.Kind.TABLE);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (k smallint PRIMARY KEY, "
              + provider.quote(column) + " varchar(20))",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, 'a'), (2, 'b')");
      try {
        if (pg) {
          execute(
              connection,
              "CREATE SCHEMA " + hostile,
              "CREATE FUNCTION " + hostile + ".same(smallint, smallint) RETURNS boolean"
                  + " LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'a hostile operator ran'; END$$",
              "CREATE OPERATOR " + hostile + ".= (LEFTARG = smallint, RIGHTARG = smallint,"
                  + " FUNCTION = " + hostile + ".same)",
              "SET search_path = " + hostile + ", public, pg_catalog");
          assertThrows(SQLException.class, () -> execute(connection, "SELECT 1::int2 = 1::int2"));
        }
        var columns = columns(connection, provider, table);
        var read = Rows.read(connection, provider, table, columns, 0, 10).rows();
        var changes = Changes.of(table, columns)
            .orElseThrow()
            .edit(read.get(0), 1, "changed")
            .delete(read.get(1))
            .insert(values(0, 3L, 1, "c"));
        assertEquals(new Changes.Outcome.Committed(), changes.write(connection, provider));
        assertEquals(List.of(row(1L, "changed"), row(3L, "c")), rows(provider, table));
      } finally {
        dropAfter(provider, "DROP TABLE " + qualified(provider, name));
        if (pg) {
          dropAfter(provider, "DROP SCHEMA IF EXISTS " + hostile + " CASCADE");
        }
      }
    }
  }

  // The write connection has auto-commit on, no transaction open, and on MariaDB and MySQL the
  // session's own zone.
  private static void assertLeftAsItWas(Connection connection, Provider provider) throws Exception {
    assertEquals(new Status(Transaction.NONE, true), Status.of(connection, provider));
    if (provider != Provider.POSTGRESQL) {
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery("SELECT @@session.time_zone")) {
        rows.next();
        assertEquals("+05:00", rows.getString(1));
      }
    }
  }

  // The table's rows as another connection reads them.
  private static List<List<Object>> rows(Provider provider, Table table) throws Exception {
    try (var other = open(provider)) {
      return Rows.read(other, provider, table, columns(other, provider, table), 0, 100)
          .rows();
    }
  }

  private static String unique(String name) {
    return name + "_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static void dropAfter(Provider provider, String... statements) throws Exception {
    try (var connection = open(provider)) {
      execute(connection, statements);
    }
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  private static Map<Integer, Object> values(Object... pairs) {
    var values = new TreeMap<Integer, Object>();
    for (var i = 0; i < pairs.length; i += 2) {
      values.put((Integer) pairs[i], pairs[i + 1]);
    }
    return values;
  }
}

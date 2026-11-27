package dev.hoori.db.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.KeyedRead;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Comparing the rows of two tables of a real server and making the target's equal, each read on a
 * connection of its own and written on a third, in tables of each test's own, which it drops.
 */
class SyncServerTest {

  private static final Database HOORI = new Database(TestServer.DATABASE, false);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  // Values beyond what a page keeps compare whole; numbers of another scale are the same; columns
  // of one table alone are neither compared nor written, and the target's keep theirs.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void makesTheTargetsRowsEqualToTheSources(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var source = unique("sync_source");
    var target = unique("sync_target");
    var bytes = pg ? "bytea" : "blob";
    var one = pg ? "'\\x01'::bytea" : "x'01'";
    var long1 = pg ? "repeat('x', 2000) || 'a'" : "CONCAT(REPEAT('x', 2000), 'a')";
    var long2 = pg ? "repeat('x', 2000) || 'b'" : "CONCAT(REPEAT('x', 2000), 'b')";
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + " (id integer PRIMARY KEY,"
              + " name varchar(40), amount numeric(10,2), note text, data " + bytes + ","
              + " extra varchar(10))",
          "CREATE TABLE " + qualified(provider, target) + " (id integer PRIMARY KEY,"
              + " name varchar(40), amount numeric(12,3), note text, data " + bytes + ","
              + " kept varchar(10) DEFAULT 'd')",
          "INSERT INTO " + qualified(provider, source) + " VALUES"
              + " (1, 'one', 1.00, 'n', NULL, 'e'), (2, 'two', 2.50, NULL, " + one + ", 'e'),"
              + " (3, 'three', 3.00, 'n', NULL, 'e'), (4, 'four', 4.00, " + long1 + ", NULL, 'e')",
          "INSERT INTO " + qualified(provider, target) + " VALUES"
              + " (2, 'two', 2.5, NULL, " + one + ", 'k'), (3, 'THREE', 3.000, 'n', NULL, 'k'),"
              + " (4, 'four', 4, " + long2 + ", NULL, 'k'), (5, 'five', 5, NULL, NULL, 'k')");
      var pairing = pairing(connection, provider, source, target);
      assertEquals(Pairing.PRIMARY_KEY, pairing.keyName());
      assertEquals(List.of("extra"), pairing.onlyInSource());
      assertEquals(List.of("kept"), pairing.onlyInTarget());

      var sent = new CopyOnWriteArrayList<Sent>();
      var compared = Journal.recording(sent::add, () -> compare(provider, pairing));
      assertEquals(new Tally(1, 1, 2, 1, 4, 4), compared.tally());
      var differences = compared.differences();
      assertEquals(1L, ((Difference.OnlyInSource) differences.get(0)).source().getFirst());
      var three = (Difference.Different) differences.get(1);
      assertEquals(List.of("name"), names(pairing, three));
      var four = (Difference.Different) differences.get(2);
      assertEquals(List.of("note"), names(pairing, four));
      assertEquals(2001, ((String) four.source().get(3)).length());
      assertEquals(5L, ((Difference.OnlyInTarget) differences.get(3)).target().getFirst());
      // Each table's rows in one read, logged as a read of rows.
      assertEquals(
          2,
          sent.stream()
              .filter(s -> s.purpose() == Purpose.ROWS && s.text().contains("ORDER BY"))
              .count());

      apply(provider, compared.pairing(), Choice.ALL);
      var again = compare(provider, pairing);
      assertEquals(new Tally(0, 0, 0, 4, 4, 4), again.tally());
      try (var other = open(provider)) {
        var table = table(provider, target);
        var rows = Rows.read(other, provider, table, Catalog.columns(other, provider, table), 0, 9)
            .rows();
        assertEquals(
            List.of("d", "k", "k", "k"), rows.stream().map(row -> row.get(5)).toList());
        assertEquals(new BigDecimal("1.000"), rows.getFirst().get(2));
        assertEquals(new Binary(new byte[] {1}, 1), rows.get(1).get(4));
      }
    }
  }

  // Keys that a collation of the language orders otherwise pair by their code points.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void textKeysPairByTheirCodePointsWhateverTheCollation(Provider provider) throws Exception {
    var source = unique("sync_text_source");
    var target = unique("sync_text_target");
    // No two of them alike in a collation that is blind to case or accents, as MariaDB's and
    // MySQL's are by default, where they could not both be keys.
    var keys = List.of("a", "B", "Z", "_x", "-y", "é", "ß", "zz", "😀", "\uE000");
    var values = new StringBuilder();
    for (var key : keys) {
      values.append(values.isEmpty() ? "" : ", ").append("('").append(key).append("', 1)");
    }
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      var create = " (code varchar(10) NOT NULL PRIMARY KEY, n integer)";
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + create,
          "CREATE TABLE " + qualified(provider, target) + create,
          "INSERT INTO " + qualified(provider, source) + " VALUES " + values,
          "INSERT INTO " + qualified(provider, target) + " VALUES " + values,
          "DELETE FROM " + qualified(provider, target) + " WHERE code = 'é'",
          "UPDATE " + qualified(provider, target) + " SET n = 2 WHERE code = '😀'");
      var pairing = pairing(connection, provider, source, target);
      var compared = compare(provider, pairing);
      assertEquals(
          new Tally(1, 0, 1, keys.size() - 2, keys.size(), keys.size() - 1), compared.tally());
      apply(provider, compared.pairing(), Choice.ALL);
      assertEquals(
          new Tally(0, 0, 0, keys.size(), keys.size(), keys.size()),
          compare(provider, pairing).tally());
    }
  }

  // A target without a primary key has its rows named by the unique key that pairs them.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aUniqueKeyPairsAndNamesTheRows(Provider provider) throws Exception {
    var source = unique("sync_unique_source");
    var target = unique("sync_unique_target");
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source)
              + " (code varchar(10) NOT NULL PRIMARY KEY, n integer)",
          "CREATE TABLE " + qualified(provider, target)
              + " (code varchar(10) NOT NULL, n integer, CONSTRAINT "
              + provider.quote(target + "_code") + " UNIQUE (code))",
          "INSERT INTO " + qualified(provider, source) + " VALUES ('a', 1), ('b', 2), ('c', 3)",
          "INSERT INTO " + qualified(provider, target) + " VALUES ('b', 20), ('c', 3), ('d', 4)");
      var pairing = pairing(connection, provider, source, target);
      assertEquals(Pairing.PRIMARY_KEY, pairing.keyName());
      var compared = compare(provider, pairing);
      assertEquals(new Tally(1, 1, 1, 1, 3, 3), compared.tally());

      // Only the update: the rest stays as it is.
      apply(provider, compared.pairing(), new Choice(false, true, false));
      assertEquals(new Tally(1, 1, 0, 2, 3, 3), compare(provider, pairing).tally());
    }
  }

  // A unique value moves from one row to another: a row updated or new takes it from a row
  // deleted, and a new row from a row updated, which the deletions first, then the updates and
  // then the new rows let them, each kind in batches of its own and in passes of their own.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aUniqueValueMovesBetweenRowsAcrossBatches(Provider provider) throws Exception {
    var source = unique("sync_move_source");
    var target = unique("sync_move_target");
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      var create = " (id integer PRIMARY KEY, email varchar(20) NOT NULL UNIQUE)";
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + create,
          "CREATE TABLE " + qualified(provider, target) + create,
          "INSERT INTO " + qualified(provider, source)
              + " VALUES (1, 'a'), (3, 'c'), (6, 'f'), (7, 'g')",
          "INSERT INTO " + qualified(provider, target)
              + " VALUES (1, 'b'), (2, 'a'), (5, 'c'), (7, 'f')");
      var pairing = pairing(connection, provider, source, target);
      var compared = compare(provider, pairing);
      assertEquals(new Tally(2, 2, 2, 0, 4, 4), compared.tally());
      var made = apply(provider, compared.pairing(), Choice.ALL);
      assertEquals(6, made.changes());
      assertEquals(new Tally(0, 0, 0, 4, 4, 4), compare(provider, pairing).tally());
    }
  }

  // Long values make portions of fewer rows, so that a portion holds no more than about so many
  // characters, whatever rows it is asked for.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aKeyedReadsPortionHoldsSoManyCharacters(Provider provider) throws Exception {
    var pg = provider == Provider.POSTGRESQL;
    var name = unique("sync_long");
    var three = pg ? "repeat('x', 3000000)" : "REPEAT('x', 3000000)";
    try (var connection = open(provider);
        var _ = dropping(provider, name)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (id integer PRIMARY KEY, note "
              + (pg ? "text" : "mediumtext") + ")",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1, " + three + "), (2, " + three
              + "), (3, " + three + "), (4, " + three + ")");
      var table = table(provider, name);
      var columns = Catalog.columns(connection, provider, table);
      try (var rows = new Execution(
          connection, provider, KeyedRead.of(connection, provider, table, columns, List.of(0)))) {
        var first = (Result.Rows) rows.next(500).results().getFirst();
        assertEquals(3, first.rows().size());
        assertEquals(3_000_000, ((String) first.rows().getFirst().get(1)).length());
        var rest = rows.next(500);
        assertEquals(1, ((Result.Rows) rest.results().getFirst()).rows().size());
        assertFalse(rest.more());
      }
      assertLeftAsItWas(connection, provider);
    }
  }

  // Times of a day with an offset that are at the same time in UTC are keys of their own, which
  // PostgreSQL orders with the greater offset first, as the merge then does.
  @Test
  void timeKeysWithAnOffsetPairInPostgreSqlsOrder() throws Exception {
    var provider = Provider.POSTGRESQL;
    var source = unique("sync_timetz_source");
    var target = unique("sync_timetz_target");
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      var create = " (at time with time zone PRIMARY KEY, n integer)";
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + create,
          "CREATE TABLE " + qualified(provider, target) + create,
          "INSERT INTO " + qualified(provider, source) + " VALUES ('09:30+00', 1),"
              + " ('12:00+02', 1), ('11:00+01', 1), ('10:00+00', 1)",
          "INSERT INTO " + qualified(provider, target) + " VALUES ('08:00+00', 1),"
              + " ('12:00+02', 2), ('11:00+01', 1), ('10:00+00', 1)");
      var pairing = pairing(connection, provider, source, target);
      var compared = compare(provider, pairing);
      assertEquals(new Tally(1, 1, 1, 2, 4, 4), compared.tally());
      apply(provider, compared.pairing(), Choice.ALL);
      assertEquals(new Tally(0, 0, 0, 4, 4, 4), compare(provider, pairing).tally());
    }
  }

  // PostgreSQL's numeric holds numbers beyond what a double does and the infinities, which are
  // keys of their own.
  @Test
  void numericKeysBeyondADoubleAreNotInfinities() throws Exception {
    var provider = Provider.POSTGRESQL;
    var source = unique("sync_numeric_source");
    var target = unique("sync_numeric_target");
    try (var connection = open(provider);
        var _ = dropping(provider, source, target)) {
      var create = " (k numeric PRIMARY KEY, n integer)";
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, source) + create,
          "CREATE TABLE " + qualified(provider, target) + create,
          "INSERT INTO " + qualified(provider, source) + " VALUES (-1e400, 1), (1, 1), (1e400, 1)",
          "INSERT INTO " + qualified(provider, target)
              + " VALUES ('-Infinity', 1), (1, 1), ('Infinity', 1)");
      var compared = compare(provider, pairing(connection, provider, source, target));
      assertEquals(new Tally(2, 2, 0, 1, 3, 3), compared.tally());
    }
  }

  private record Compared(Pairing pairing, List<Difference> differences, Tally tally) {}

  // Both tables read in the key's order on connections of their own, two rows a portion.
  private static Compared compare(Provider provider, Pairing pairing) throws Exception {
    try (var mine = open(provider);
        var theirs = open(provider)) {
      var source = KeyedRead.of(
          mine, provider, pairing.source(), pairing.sourceColumns(), pairing.sourceKey());
      var target = KeyedRead.of(
          theirs, provider, pairing.target(), pairing.targetColumns(), pairing.targetKey());
      try (var sourceRows = new Execution(mine, provider, source);
          var targetRows = new Execution(theirs, provider, target)) {
        var merge =
            Merge.of(pairing, Cursor.of(sourceRows::next, 2), Cursor.of(targetRows::next, 2));
        var differences = new ArrayList<Difference>();
        for (var d = merge.next(); d != null; d = merge.next()) {
          differences.add(d);
        }
        assertLeftAsItWas(mine, provider);
        assertLeftAsItWas(theirs, provider);
        return new Compared(merge.pairing(), differences, merge.tally());
      }
    }
  }

  // The changes the choice takes, two in a batch, in one transaction, the tables read anew on
  // connections of their own for each pass.
  private static Batches.Made apply(Provider provider, Pairing pairing, Choice choice)
      throws Exception {
    try (var connection = open(provider);
        var mine = open(provider);
        var theirs = open(provider)) {
      Batches.Made made;
      try (var writer = Changes.writer(connection, provider, pairing.target())) {
        made = Batches.make(
            reads(provider, pairing, mine, theirs), choice, 2, writer::write, (_, _) -> {
              // Nothing shows it.
            });
        assertEquals(Optional.empty(), made.ended());
        writer.commit();
      }
      assertLeftAsItWas(connection, provider);
      return made;
    }
  }

  // Both tables read anew on the connections, two rows a portion.
  private static Batches.Reads<Exception> reads(
      Provider provider, Pairing pairing, Connection mine, Connection theirs) {
    return () -> {
      var sourceRows = new Execution(
          mine,
          provider,
          KeyedRead.of(
              mine, provider, pairing.source(), pairing.sourceColumns(), pairing.sourceKey()));
      var targetRows = new Execution(
          theirs,
          provider,
          KeyedRead.of(
              theirs, provider, pairing.target(), pairing.targetColumns(), pairing.targetKey()));
      Merge merge;
      try {
        merge = Merge.of(pairing, Cursor.of(sourceRows::next, 2), Cursor.of(targetRows::next, 2));
      } catch (Exception e) {
        sourceRows.close();
        targetRows.close();
        throw e;
      }
      return new Batches.Reading() {
        @Override
        public Merge merge() {
          return merge;
        }

        @Override
        public void close() {
          sourceRows.close();
          targetRows.close();
        }
      };
    };
  }

  private static List<String> names(Pairing pairing, Difference.Different different) {
    return different.cells().stream()
        .map(pair -> pairing.sourceColumns().get(pair.source()).name())
        .toList();
  }

  private static Pairing pairing(
      Connection connection, Provider provider, String source, String target) throws Exception {
    var mine = table(provider, source);
    var theirs = table(provider, target);
    var found = Pairing.of(
        mine,
        Catalog.columns(connection, provider, mine),
        Catalog.indexes(connection, provider, mine),
        theirs,
        Catalog.columns(connection, provider, theirs),
        Catalog.indexes(connection, provider, theirs));
    return assertInstanceOf(Pairing.Paired.class, found).pairing();
  }

  private static void assertLeftAsItWas(Connection connection, Provider provider) throws Exception {
    assertEquals(new Status(Transaction.NONE, true), Status.of(connection, provider));
  }

  private static Container sample(Provider provider) {
    return provider == Provider.POSTGRESQL ? new Schema(HOORI, "public", false) : HOORI;
  }

  private static Table table(Provider provider, String name) {
    return new Table(sample(provider), name, Table.Kind.TABLE);
  }

  private static String qualified(Provider provider, String name) {
    return provider.quote(sample(provider).name()) + "." + provider.quote(name);
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

  // Drops the tables, on a connection of its own, once the test is over, also where making them
  // failed partway: a failure of the test stays the one that shows, with the drop's beside it.
  private static AutoCloseable dropping(Provider provider, String... tables) {
    return () -> {
      try (var connection = open(provider)) {
        for (var table : tables) {
          execute(connection, "DROP TABLE IF EXISTS " + qualified(provider, table));
        }
      }
    };
  }

  private static String unique(String name) {
    return name + "_" + UUID.randomUUID().toString().substring(0, 8);
  }
}

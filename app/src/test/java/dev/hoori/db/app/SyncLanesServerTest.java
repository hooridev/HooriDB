package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.Work;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sync.Choice;
import dev.hoori.db.core.sync.Pairing;
import dev.hoori.db.core.sync.Tally;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compare data's runs through {@link Servers#SERVER}'s links on lanes, as the tab uses them,
 * against core's test servers: the tables read on read-only sessions and written on a third.
 * Skipped without Docker.
 */
class SyncLanesServerTest {

  private static final Work.Progress<Sync.Progress> QUIET = new Work.Progress<>() {
    @Override
    public void report(Sync.Progress progress) {
      // Nothing shows it.
    }

    @Override
    public boolean isCancelled() {
      return false;
    }
  };

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void comparesAndMakesTheTargetEqualOnLanesOfItsOwn(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var source = table(provider, "sync_lane_source_" + suffix);
    var target = table(provider, "sync_lane_target_" + suffix);
    var setup = ServersServerTest.open(server);
    try {
      run(
          setup,
          "CREATE TABLE " + qualified(provider, source) + " (id integer PRIMARY KEY, v varchar(9))",
          "CREATE TABLE " + qualified(provider, target) + " (id integer PRIMARY KEY, v varchar(9))",
          "INSERT INTO " + qualified(provider, source) + " VALUES (1, 'a'), (2, 'b'), (3, 'c')",
          "INSERT INTO " + qualified(provider, target) + " VALUES (2, 'B'), (3, 'c'), (4, 'd')");
      var reader = server.profile().withReadOnly(true);
      var mine = lane(reader);
      var theirs = lane(reader);
      var writer = lane(server.profile());
      var readOnly = lane(reader);
      try {
        var sides = new Sync.Sides(side(mine, source), side(theirs, target));
        var pairing = assertInstanceOf(Pairing.Paired.class, Sync.pair(sides)).pairing();
        var compared = Sync.compare(sides, pairing, provider, QUIET);
        assertEquals(new Tally(1, 1, 1, 1, 3, 3), compared.tally());
        assertEquals(3, compared.kept().size());

        // A read-only session takes no writer, before anything is sent.
        var refused = assertThrows(
            QueryException.class,
            () -> Sync.apply(
                sides, readOnly, compared.pairing(), Choice.ALL, new Sync.Commit(), QUIET));
        assertEquals(QueryException.Failure.ACCESS_DENIED, refused.failure());

        var applied =
            Sync.apply(sides, writer, compared.pairing(), Choice.ALL, new Sync.Commit(), QUIET);
        assertEquals(new Changes.Outcome.Committed(), applied.outcome());
        assertEquals(3, applied.written());
        assertEquals(
            new Tally(0, 0, 0, 3, 3, 3),
            Sync.compare(sides, pairing, provider, QUIET).tally());
      } finally {
        for (var lane : new Lane[] {mine, theirs, writer, readOnly}) {
          lane.close(ended -> {}).join();
        }
      }
    } finally {
      run(
          setup,
          "DROP TABLE " + qualified(provider, source),
          "DROP TABLE " + qualified(provider, target));
      setup.close();
    }
  }

  // A script runs as applying would, on any of the servers: a moment of the key written as each
  // reads it back, which MariaDB reads with no offset in a literal.
  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aSavedScriptMakesTheTargetEqualWhenItRuns(Provider provider, @TempDir Path dir)
      throws Exception {
    var server = TestServer.of(provider);
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var source = table(provider, "sync_script_source_" + suffix);
    var target = table(provider, "sync_script_target_" + suffix);
    var moment = provider == Provider.POSTGRESQL ? "timestamptz" : "timestamp(6)";
    var setup = ServersServerTest.open(server);
    try {
      run(
          setup,
          "CREATE TABLE " + qualified(provider, source) + " (at " + moment + " PRIMARY KEY,"
              + " v integer)",
          "CREATE TABLE " + qualified(provider, target) + " (at " + moment + " PRIMARY KEY,"
              + " v integer)",
          "INSERT INTO " + qualified(provider, source) + " VALUES"
              + " ('2024-03-01 10:00:00.25', 1), ('2024-03-02 10:00:00', 2)",
          "INSERT INTO " + qualified(provider, target) + " VALUES"
              + " ('2024-03-02 10:00:00', 20), ('2024-03-03 10:00:00', 3)");
      var reader = server.profile().withReadOnly(true);
      var mine = lane(reader);
      var theirs = lane(reader);
      try {
        var sides = new Sync.Sides(side(mine, source), side(theirs, target));
        var pairing = assertInstanceOf(Pairing.Paired.class, Sync.pair(sides)).pairing();
        assertEquals(
            new Tally(1, 1, 1, 0, 2, 2),
            Sync.compare(sides, pairing, provider, QUIET).tally());
        var file = dir.resolve("sync.sql");
        Sync.script(sides, pairing, Choice.ALL, provider, file, QUIET);
        for (var line : Files.readAllLines(file)) {
          if (!line.startsWith("--")
              && !line.equals("START TRANSACTION;")
              && !line.equals("COMMIT;")) {
            run(setup, line.substring(0, line.length() - 1));
          }
        }
        assertEquals(
            new Tally(0, 0, 0, 2, 2, 2),
            Sync.compare(sides, pairing, provider, QUIET).tally());
      } finally {
        for (var lane : new Lane[] {mine, theirs}) {
          lane.close(ended -> {}).join();
        }
      }
    } finally {
      run(
          setup,
          "DROP TABLE " + qualified(provider, source),
          "DROP TABLE " + qualified(provider, target));
      setup.close();
    }
  }

  // A side whose sessions' ends tell nothing.
  static Sync.Side side(Lane lane, Table table) {
    return new Sync.Side(lane, table, (sql, what, failure) -> {});
  }

  private static Lane lane(ConnectionProfile profile) {
    var lane = new Lane(e -> {});
    lane.open(
        Servers.SERVER,
        profile.withDatabase(TestServer.DATABASE),
        TestServer.password(),
        TestServer.driver(profile.provider()),
        Duration.ofSeconds(10));
    return lane;
  }

  private static void run(Servers.Link link, String... statements) throws QueryException {
    for (var sql : statements) {
      var statement = link.run(sql);
      try {
        statement.next(1);
      } finally {
        statement.close();
      }
    }
  }

  private static Table table(Provider provider, String name) {
    var database = new Database(TestServer.DATABASE, false);
    Container container =
        provider == Provider.POSTGRESQL ? new Schema(database, "public", false) : database;
    return new Table(container, name, Table.Kind.TABLE);
  }

  private static String qualified(Provider provider, Table table) {
    return provider.quote(table.container().name()) + "." + provider.quote(table.name());
  }
}

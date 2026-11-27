package dev.hoori.db.core.execution;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static dev.hoori.db.core.execution.ExecutionServerTest.execute;
import static dev.hoori.db.core.execution.ExecutionServerTest.open;
import static dev.hoori.db.core.execution.ExecutionServerTest.selectLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Files run on real servers: the dumps of their own tools, failures, transactions, a cancel. */
class FileRunServerTest {

  private static final FileRun.Options STOP = new FileRun.Options(false, false);

  @TempDir
  Path folder;

  private static Path resource(String name) throws Exception {
    return Path.of(FileRunServerTest.class.getResource("/sql-files/" + name).toURI());
  }

  private static FileRun.Report run(
      Connection connection, Provider provider, Path file, FileRun.Options options) {
    return FileRun.of(file, provider, options, progress -> {})
        .run(FileRun.target(connection, provider, false));
  }

  private Path file(String name, String text) throws Exception {
    var file = folder.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  private static String text(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static void ran(FileRun.Report report) {
    assertEquals(List.of(), report.failures());
    assertEquals(FileRun.Ending.RAN, report.ending());
  }

  @Test
  void aPlainDumpOfPgDumpRunsWithItsCopies() throws Exception {
    var dump = resource("postgresql-dump.sql");
    try (var connection = open(POSTGRESQL);
        var check = open(POSTGRESQL)) {
      var report = run(connection, POSTGRESQL, dump, STOP);
      ran(report);
      assertEquals(Files.size(dump), report.progress().bytes());
      assertEquals(Files.size(dump), report.progress().size());
      // And once more, in one transaction, as PostgreSQL's DDL is transactional.
      var again = run(connection, POSTGRESQL, dump, new FileRun.Options(false, true));
      ran(again);
      assertEquals(FileRun.Kept.COMMITTED, again.transaction());
      // What the file committed, as another session sees it.
      assertEquals(3, selectLong(check, "SELECT count(*) FROM file_run.item"));
      assertEquals("Grüße; 'quoted'", text(check, "SELECT name FROM file_run.item WHERE id = 1"));
      assertEquals(
          "00ff", text(check, "SELECT encode(data, 'hex') FROM file_run.item WHERE id = 1"));
      assertEquals("tab\there", text(check, "SELECT name FROM file_run.item WHERE id = 2"));
      assertEquals("line\nbreak", text(check, "SELECT note FROM file_run.item WHERE id = 2"));
      assertEquals("back\\slash", text(check, "SELECT name FROM file_run.item WHERE id = 3"));
      assertEquals(
          "-- not a comment; /* nor this */",
          text(check, "SELECT note FROM file_run.item WHERE id = 3"));
      execute(check, "INSERT INTO file_run.item (name) VALUES ('new')");
      assertEquals(
          "touched; by trigger", text(check, "SELECT note FROM file_run.item WHERE id = 4"));
      assertEquals(
          "Rows; of a dump", text(check, "SELECT obj_description('file_run.item'::regclass)"));
      // A body of BEGIN ATOMIC … END, its statements one with the function's.
      assertEquals(3, selectLong(check, "SELECT file_run.plus(2)"));
    } finally {
      // The catalog's tests see the database's schemas, the sample's first.
      try (var cleaning = open(POSTGRESQL)) {
        execute(cleaning, "DROP SCHEMA IF EXISTS file_run CASCADE");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("mysqlFamily")
  void aDumpOfMysqldumpRunsWithItsDelimitersAndExecutableComments(Provider provider)
      throws Exception {
    try (var connection = open(provider)) {
      ran(run(connection, provider, resource("mysql-dump.sql"), STOP));
    }
    try (var check = open(provider)) {
      assertEquals(3, selectLong(check, "SELECT count(*) FROM file_run_item"));
      assertEquals("Grüße; 'quoted'", text(check, "SELECT name FROM file_run_item WHERE id = 1"));
      assertEquals("tab\there", text(check, "SELECT name FROM file_run_item WHERE id = 2"));
      assertEquals("line\nbreak", text(check, "SELECT note FROM file_run_item WHERE id = 2"));
      assertEquals("back\\slash", text(check, "SELECT name FROM file_run_item WHERE id = 3"));
      assertEquals(
          "-- not a comment; /* nor this */ # nor this",
          text(check, "SELECT note FROM file_run_item WHERE id = 3"));
      // The trigger and the procedure, each with semicolons in its body, came whole.
      execute(check, "INSERT INTO file_run_item (name) VALUES ('new')");
      assertEquals(
          "touched; by trigger", text(check, "SELECT note FROM file_run_item WHERE id = 4"));
      execute(check, "CALL file_run_count(@n)");
      assertEquals(4, selectLong(check, "SELECT @n"));
    }
  }

  static List<Provider> mysqlFamily() {
    return List.of(MARIADB, MYSQL);
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void failuresAreListedAtTheirLinesAndTheRunGoesOn(Provider provider) throws Exception {
    var text =
        "SELECT 1;\n\nSELECT * FROM file_run_missing;\nSELECT 2;\n-- next\nSELECT 3\nFORM x;\n";
    try (var connection = open(provider)) {
      var report =
          run(connection, provider, file("fails.sql", text), new FileRun.Options(true, false));
      assertEquals(FileRun.Ending.RAN, report.ending());
      assertEquals(new FileRun.Progress(text.length(), text.length(), 4, 2), report.progress());
      var lines = report.failures().stream().map(FileRun.Failed::line).toList();
      // MariaDB and MySQL name the line within the statement, PostgreSQL the character.
      assertEquals(List.of(3L, 7L), lines, report.failures().toString());
      assertEquals(
          "SELECT * FROM file_run_missing", report.failures().getFirst().statement());
      assertFalse(report.failures().getFirst().message().isBlank());
      // And it stops at the first, where it does not go on.
      var stopped = run(connection, provider, file("fails.sql", text), STOP);
      assertEquals(FileRun.Ending.STOPPED, stopped.ending());
      assertEquals(2, stopped.progress().statements());
      // The connection takes statements after either.
      assertEquals(1, selectLong(connection, "SELECT 1"));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void oneTransactionRollsBackAFailureButNotDdlWhereTheServerCommitsIt(Provider provider)
      throws Exception {
    var table = "file_run_rollback";
    try (var writer = open(provider)) {
      execute(writer, "DROP TABLE IF EXISTS " + table);
    }
    var text = "CREATE TABLE " + table + " (id integer);\n"
        + "INSERT INTO " + table + " VALUES (1);\n"
        + "INSERT INTO " + table + " VALUES ('x');\n";
    try (var connection = open(provider)) {
      var report = run(connection, provider, file("tx.sql", text), new FileRun.Options(true, true));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      assertEquals(FileRun.Kept.ROLLED_BACK, report.transaction());
      assertEquals(3, report.failures().getFirst().line());
      assertTrue(connection.getAutoCommit());
    }
    try (var check = open(provider)) {
      var version = ServerVersion.parse(check.getMetaData().getDatabaseProductVersion());
      if (provider.supports(Capability.TRANSACTIONAL_DDL, version)) {
        assertEquals(
            0,
            selectLong(
                check,
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                    + table
                    + "'"));
      } else {
        // The CREATE TABLE committed what came before it and itself; the INSERT is gone.
        assertEquals(0, selectLong(check, "SELECT count(*) FROM " + table));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("mysqlFamily")
  void oneTransactionTellsThatLockTablesCommittedWhatRanBefore(Provider provider) throws Exception {
    var table = "file_run_locked";
    try (var writer = open(provider)) {
      execute(writer, "DROP TABLE IF EXISTS " + table, "CREATE TABLE " + table + " (id integer)");
    }
    // As mysqldump writes a table's rows, then a failure after them.
    var text = "LOCK TABLES " + table + " WRITE;\n"
        + "INSERT INTO " + table + " VALUES (1);\n"
        + "UNLOCK TABLES;\n"
        + "INSERT INTO " + table + " VALUES (2);\n"
        + "INSERT INTO " + table + " VALUES ('x');\n";
    try (var connection = open(provider)) {
      var report =
          run(connection, provider, file("locked.sql", text), new FileRun.Options(false, true));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      assertEquals(FileRun.Kept.ROLLED_BACK, report.transaction());
      assertEquals(3, report.committedAt());
    }
    try (var check = open(provider)) {
      // What ran up to UNLOCK TABLES stays, what ran after it is gone.
      assertEquals(1, selectLong(check, "SELECT count(*) FROM " + table));
    }
  }

  @ParameterizedTest
  @MethodSource("mysqlFamily")
  void oneTransactionTellsThatDdlCommittedWhatRanBeforeButNotAnUnlockAlone(Provider provider)
      throws Exception {
    var table = "file_run_ddl";
    try (var writer = open(provider)) {
      execute(
          writer,
          "DROP TABLE IF EXISTS " + table + ", file_run_ddl_made",
          "CREATE TABLE " + table + " (id integer)");
    }
    var unlocked = "INSERT INTO " + table + " VALUES (1);\nUNLOCK TABLES;\n" + "INSERT INTO "
        + table + " VALUES ('x');\n";
    var made = "INSERT INTO " + table + " VALUES (2);\nCREATE TABLE file_run_ddl_made (id int);\n"
        + "INSERT INTO " + table + " VALUES ('x');\n";
    try (var connection = open(provider);
        var check = open(provider)) {
      var tx = new FileRun.Options(false, true);
      var report = run(connection, provider, file("unlocked.sql", unlocked), tx);
      assertEquals(FileRun.Kept.ROLLED_BACK, report.transaction());
      assertEquals(0, report.committedAt());
      // The UNLOCK TABLES committed nothing, as another session sees.
      assertEquals(0, selectLong(check, "SELECT count(*) FROM " + table));
      var ddl = run(connection, provider, file("made.sql", made), tx);
      assertEquals(FileRun.Kept.ROLLED_BACK, ddl.transaction());
      assertEquals(2, ddl.committedAt());
      // The CREATE TABLE committed the insert before it.
      assertEquals(2, selectLong(check, "SELECT max(id) FROM " + table));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "BEGIN NOT ATOMIC COMMIT; END",
        "IF TRUE THEN COMMIT; END IF",
        "l: LOOP COMMIT; LEAVE l; END LOOP l"
      })
  void oneTransactionRollsBackWhatRanBeforeACompoundStatementThatCommits(String compound)
      throws Exception {
    var table = "file_run_compound";
    try (var writer = open(MARIADB)) {
      execute(writer, "DROP TABLE IF EXISTS " + table, "CREATE TABLE " + table + " (id integer)");
    }
    var text = "INSERT INTO " + table + " VALUES (1);\n"
        + "DELIMITER //\n" + compound + "//\nDELIMITER ;\n"
        + "INSERT INTO " + table + " VALUES (2);\n";
    try (var connection = open(MARIADB)) {
      var report =
          run(connection, MARIADB, file("compound.sql", text), new FileRun.Options(false, true));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      assertEquals(FileRun.Kept.ROLLED_BACK, report.transaction());
      assertEquals(3, report.failures().getFirst().line());
    }
    // Another session finds nothing of the file: the compound statement did not commit the first.
    try (var check = open(MARIADB)) {
      assertEquals(0, selectLong(check, "SELECT count(*) FROM " + table));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aReadOnlyConnectionRefusesTheWrites(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var profile = server.profile().withReadOnly(true);
    try (var connection = Connector.open(
            profile, TestServer.password(), server.driver(), Duration.ofSeconds(30))
        .connection()) {
      var text = "SELECT 1;\nCREATE TABLE file_run_read_only (id integer);\n";
      var report = FileRun.of(file("ro.sql", text), provider, STOP, progress -> {})
          .run(FileRun.target(connection, provider, true));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      assertEquals(2, report.failures().getFirst().line());
    }
  }

  @Test
  void aCopyOnAReadOnlyConnectionIsRefused() throws Exception {
    try (var writer = open(POSTGRESQL)) {
      execute(
          writer,
          "DROP TABLE IF EXISTS file_run_copied",
          "CREATE TABLE file_run_copied (id integer)");
    }
    var server = TestServer.of(POSTGRESQL);
    try (var connection = Connector.open(
            server.profile().withReadOnly(true),
            TestServer.password(),
            server.driver(),
            Duration.ofSeconds(30))
        .connection()) {
      var report = FileRun.of(
              file("copy.sql", "COPY file_run_copied FROM stdin;\n1\n\\.\nSELECT 1;\n"),
              POSTGRESQL,
              STOP,
              progress -> {})
          .run(FileRun.target(connection, POSTGRESQL, true));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      assertEquals(1, report.failures().getFirst().line());
      assertEquals(1, selectLong(connection, "SELECT 1"));
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aCancelStopsAStatementThatRuns(Provider provider) throws Exception {
    var sleep = (provider == POSTGRESQL ? "SELECT pg_sleep(30)" : "SELECT SLEEP(30)")
        + " AS file_run_sleep";
    var file = file("sleep.sql", sleep + ";\nSELECT 2;\n");
    try (var connection = open(provider)) {
      var run = FileRun.of(file, provider, STOP, progress -> {});
      var report = new FileRun.Report[1];
      var started = System.nanoTime();
      var runner = Thread.ofVirtual()
          .start(() -> report[0] = run.run(FileRun.target(connection, provider, false)));
      ExecutionServerTest.awaitRunning(provider, "file_run_sleep");
      run.cancel();
      runner.join(20_000);
      assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20);
      assertEquals(FileRun.Ending.CANCELLED, report[0].ending());
      assertEquals(1, report[0].progress().statements());
      assertEquals(1, selectLong(connection, "SELECT 1"));
    }
  }

  @Test
  void aCopysFailureQuotesNoneOfItsRows() throws Exception {
    try (var writer = open(POSTGRESQL)) {
      execute(
          writer,
          "DROP TABLE IF EXISTS file_run_quoted",
          "CREATE TABLE file_run_quoted (id integer, name text)");
    }
    var file = file("quoted.sql", "COPY file_run_quoted FROM stdin;\n1\tok\ns3cret\tx\n\\.\n");
    var sent = new ArrayList<Sent>();
    try (var connection = open(POSTGRESQL)) {
      var report = Journal.recording(sent::add, () -> run(connection, POSTGRESQL, file, STOP));
      assertEquals(FileRun.Ending.STOPPED, report.ending());
      var failed = report.failures().getFirst();
      assertEquals(Outcome.ROW_WITHHELD, failed.message());
      // The server would have quoted the row, as the statement log does not.
      var logged = sent.stream().filter(s -> s.purpose() == Purpose.FILE).toList();
      assertEquals(new Outcome.Failed(Outcome.ROW_WITHHELD), logged.getLast().outcome());
      assertFalse(report.toString().contains("s3cret"), report.toString());
      assertFalse(sent.toString().contains("s3cret"), sent.toString());
    }
  }

  @Test
  void aCancelStopsACopyAndTheServerKeepsNoneOfIt() throws Exception {
    try (var writer = open(POSTGRESQL)) {
      execute(
          writer,
          "DROP TABLE IF EXISTS file_run_big",
          "CREATE TABLE file_run_big (id integer, t text)");
    }
    var rows = IntStream.range(0, 400_000)
        .mapToObj(i -> i + "\tsome text to make the line longer " + i)
        .collect(Collectors.joining("\n"));
    var file = file("big.sql", "COPY file_run_big FROM stdin;\n" + rows + "\n\\.\nSELECT 2;\n");
    try (var connection = open(POSTGRESQL)) {
      var holder = new FileRun[1];
      // Cancelled from the copy's own progress, once it has sent a part of its rows.
      holder[0] = FileRun.of(file, POSTGRESQL, STOP, progress -> {
        if (progress.bytes() > 2_000_000) {
          holder[0].cancel();
        }
      });
      var report = holder[0].run(FileRun.target(connection, POSTGRESQL, false));
      assertEquals(FileRun.Ending.CANCELLED, report.ending());
      assertTrue(report.progress().bytes() < Files.size(file));
      assertEquals(0, selectLong(connection, "SELECT count(*) FROM file_run_big"));
    }
  }
}

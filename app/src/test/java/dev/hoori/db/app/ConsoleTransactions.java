package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.near;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** The fixture of the tests of a console's transactions, and what they share. */
abstract class ConsoleTransactions {

  static final String DDL = "CREATE TABLE notes (id int)";
  // A change that fails: not a plain read, so that what it left is asked of the server.
  static final String BROKEN = "UPDATE missing SET x = 1 WHERE id = 1";
  static final String KILL = "SELECT pg_terminate_backend(pg_backend_pid())";
  // The server cancels it, and ending what it read then loses the connection.
  static final String CUT = "SELECT pg_sleep(60)";
  // On an open session the switch asks the server first.
  static final String MANUAL = "press role=radio label=\"Manual\"\nframe\n" + SETTLE;
  static final String CALL = "CALL report()"; // begins a transaction, and gives 250 rows
  static final String DEADLOCKED = "UPDATE stock SET n = n - 1 WHERE id = 7";
  static final String CHAIN = "SET completion_type=1";
  static final String COMMITTING = "run " + PG + " COMMIT";
  static final String EXIT = """
      press role=menuitem label="File"
      frame
      advance 0.3
      frame
      press role=menuitem label="Exit"
      frame
      advance 1
      frame
      """;
  static final String OPENED = "advance 1\nframe\n"; // a dialog fades in, or out
  static final String TAB = "role=tab label=\"shop-prod: shop\"";
  static final String COMMIT_BUTTON = "press role=button label=\"Commit\"\nframe\n" + SETTLE;
  static final String GONE_TEXT = "The open transaction is gone with the connection";
  static final String ON_AND_FAIL = "CALL on_and_fail()";
  static final String COMMIT_ROWS = "CALL commit_rows()";
  static final String WRITING_WITH =
      "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed";
  static final String NOTE = "SELECT note(id) FROM source"; // a read whose function writes
  static final String EXPLAINED = "EXPLAIN (U&\"\\0061nalyze\" true) UPDATE orders SET paid = true";
  static final String BIG = "CALL big()";
  static final dev.hoori.db.core.execution.Message NOT_ROLLED_BACK =
      new dev.hoori.db.core.execution.Message(
          "Some non-transactional changed tables couldn't be rolled back", "HY000", 1196);
  static final String DUPLICATE = "duplicate key value violates unique constraint";
  static final String LAST = "SELECT note(id) FROM orders LIMIT 250";

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    servers = k.servers
        .statement(DDL, List.of(new Result.Count(0)))
        .statement(CALL, List.of(FakeServers.made(250, "id: bigint")))
        .begins(CALL)
        .statement(DEADLOCKED, List.of(FakeServers.deadlock()))
        .statement(
            BROKEN,
            List.of(FakeServers.serverError(
                Failure.Kind.INVALID, "relation \"missing\" does not exist")))
        .statement(
            KILL,
            List.of(FakeServers.serverError(
                Failure.Kind.CONNECTION_LOST, "terminating connection due to administrator")))
        .statement(
            CUT,
            List.of(
                FakeServers.serverError(Failure.Kind.CANCELLED, "canceling statement"),
                FakeServers.serverError(Failure.Kind.CONNECTION_LOST, ""))); // as core's own
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  /** A console in Manual with a transaction open, after the change. */
  App<State> open(ConnectionProfile profile) throws Exception {
    var app = k.console(profile);
    c.run(app, MANUAL + type(PAY) + RUN);
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    return app;
  }

  /** How many times the server was asked for the transaction. */
  long probes(String host) {
    return servers.calls.stream().filter(("transaction " + host)::equals).count();
  }

  static String badge(String tree) {
    return near(tree, "#transaction");
  }

  /** The notices of the app, one a line: connection, statement, what happened. */
  static String noticed(App<State> app) {
    return String.join(
        "\n",
        app.state().notices.stream()
            .map(n -> n.connection() + " · " + n.statement() + " · " + n.what())
            .toList());
  }

  /** Moves the console of this title into a window of its own. */
  static String tearOff(String title) {
    return "press role=tab label=\"" + title + "\" right\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n";
  }

  static String in(String window) {
    return "window \"" + window + "\"\n";
  }

  static dev.hoori.db.core.execution.Portion portion(Result result, boolean more) {
    return new dev.hoori.db.core.execution.Portion(
        List.of(result), List.of(), more, java.time.Duration.ZERO, Transaction.NONE, true);
  }
}

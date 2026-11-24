package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.ConsoleTransactions.MANUAL;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.LayoutTest.widthOf;
import static dev.hoori.db.app.TableTabTest.cell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.kit.Split;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Editing single-table SELECT results in an SQL console, DML Preview, Commit, Revert and guards. */
class ConsoleEditTest {

  private static final String CUSTOMERS_PATH = PG + "/shop.public.customers";
  private static final String WRITE_CUSTOMERS = "write " + CUSTOMERS_PATH;
  private static final String SELECT_CUSTOMERS =
      "SELECT id, name, email FROM customers WHERE id <= 2";

  @TempDir
  Path dir;

  private final ConsoleTest k = new ConsoleTest();
  private CatalogTreeTest c;
  private FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    servers = k.servers;
    servers.values(
        CUSTOMERS_PATH,
        List.of(
            List.of(1L, "Alice", "alice@example.com"),
            List.of(2L, "Bob", "bob@example.com"),
            List.of(3L, "Carol", "carol@example.com")));
    servers.statement(SELECT_CUSTOMERS, this::customersAnswer);
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  private FakeServers.Answer customersAnswer() {
    var fields = List.of(
        FakeServers.originField("id", "integer", "shop.public", "customers", "id"),
        FakeServers.originField("name", "text", "shop.public", "customers", "name"),
        FakeServers.originField(
            "email", "character varying(255)", "shop.public", "customers", "email"));
    var rows = servers.rowsOf(CUSTOMERS_PATH).stream()
        .filter(r -> ((Number) r.get(0)).longValue() <= 2)
        .toList();
    return new FakeServers.Answer(List.of(new Result.Rows(fields, 0, rows)), List.of());
  }

  private App<State> openCustomers() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(SELECT_CUSTOMERS) + RUN + SETTLE);
    return app;
  }

  private long writeCount() {
    return servers.calls.stream().filter(call -> call.startsWith("write ")).count();
  }

  @Test
  void editPreviewAndCommitUpdatesFakeServerAndShowsHint() throws Exception {
    var app = openCustomers();
    // Edit row 0, name: "Alice" -> "Alicia".
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\nsnap edited\n");
    String edited = c.tree("edited");
    assertTrue(line(edited, "#row-0 role=row").contains("value=\"changed\""), edited);
    assertTrue(line(edited, "#cell-0-1 ").contains("value=\"changed\""), edited);
    assertTrue(line(edited, "#pending-changes").contains("1 change"), edited);
    assertFalse(line(edited, "#commit-changes").contains("disabled"), edited);
    assertTrue(
        line(edited, "#rollback").contains("disabled"),
        "pending changes must not enable Rollback: " + edited);
    assertTrue(
        line(edited, "#uncommitted").contains("1 console may hold uncommitted work"), edited);

    // Commit… opens the DML Preview dialog with the UPDATE statement.
    c.run(app, "press #commit-changes\nframe\n" + OPENED + "snap preview\n");
    String preview = c.tree("preview");
    assertTrue(preview.contains("role=dialog label=\"DML Preview\""), preview);
    assertTrue(
        preview.contains("UPDATE \\\"public\\\".\\\"customers\\\" SET \\\"name\\\" = 'Alicia'"),
        preview);
    assertTrue(preview.contains("WHERE \\\"id\\\" = 1;"), preview);

    // Cancel in DML Preview sends nothing and keeps the pending change.
    c.run(app, "press #preview-cancel\nframe\n" + OPENED + SETTLE + "snap cancelled\n");
    assertEquals(0, writeCount(), servers.calls.toString());
    assertEquals("Alice", servers.rowsOf(CUSTOMERS_PATH).get(0).get(1));
    assertTrue(line(c.tree("cancelled"), "#pending-changes").contains("1 change"));

    // Open preview again and Commit: writes to FakeServers, keeps new value, shows hint.
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap committed\n");
    assertEquals(1, writeCount(), servers.calls.toString());
    assertTrue(servers.calls.contains(WRITE_CUSTOMERS), servers.calls.toString());
    assertEquals("Alicia", servers.rowsOf(CUSTOMERS_PATH).get(0).get(1));
    String committed = c.tree("committed");
    assertTrue(cell(committed, 0, 1).contains("\"Alicia\""), committed);
    assertFalse(committed.contains("#pending-changes"), committed);
    assertTrue(line(committed, "#edit-status").contains(ResultEdits.COMMITTED_HINT), committed);

    // Re-running the SELECT asks nothing and reads the committed value back.
    c.run(app, "press #sql\n" + RUN + SETTLE + "snap rerun\n");
    assertFalse(c.tree("rerun").contains("role=dialog"), c.tree("rerun"));
    assertTrue(cell(c.tree("rerun"), 0, 1).contains("\"Alicia\""), c.tree("rerun"));
  }

  @Test
  void revertSelectionAndRevertAllSendNothing() throws Exception {
    var app = openCustomers();
    c.run(app, """
        press #cell-0-1
        type "Alicia"
        key Enter
        frame
        press #cell-1-1
        type "Bobby"
        key Enter
        frame
        snap two
        """);
    assertTrue(line(c.tree("two"), "#pending-changes").contains("2 changes"), c.tree("two"));

    // Revert only the selected cell (#cell-0-1).
    c.run(app, "press #cell-0-1\nframe\npress #revert-selection\nframe\nsnap one\n");
    String one = c.tree("one");
    assertTrue(cell(one, 0, 1).contains("\"Alice\""), one);
    assertTrue(cell(one, 1, 1).contains("\"Bobby\""), one);
    assertTrue(line(one, "#pending-changes").contains("1 change"), one);
    assertTrue(line(one, "#revert-selection").contains("disabled"), one);
    assertFalse(line(one, "#revert-all").contains("disabled"), one);

    // Revert all clears the remaining change.
    c.run(app, "press #revert-all\nframe\nsnap none\n");
    String none = c.tree("none");
    assertTrue(cell(none, 1, 1).contains("\"Bob\""), none);
    assertFalse(none.contains("#pending-changes"), none);
    assertEquals(0, writeCount(), servers.calls.toString());
  }

  @Test
  void discardQuestionsGuardEveryActionAndSendNothing() throws Exception {
    // 1. Running another statement asks; Cancel keeps changes, Discard runs it.
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(app, "press #sql\nkey Enter control\nframe\n" + OPENED + "snap run-asked\n");
    assertTrue(
        c.tree("run-asked").contains("role=dialog label=\"Discard 1 change?\""),
        c.tree("run-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(1, k.tab(app).pendingChanges(false));
    c.run(
        app,
        "press #sql\nkey Enter control\nframe\n" + OPENED + "press #answer-discard\nframe\n"
            + OPENED + SETTLE);
    assertEquals(0, k.tab(app).pendingChanges(false));
    assertEquals(0, writeCount());

    // 1b. Pinning the result preserves its pending changes when running another statement.
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\npress #pin\nframe\n");
    c.run(app, "press #sql\n" + RUN + SETTLE + "snap pinned-run\n");
    assertFalse(c.tree("pinned-run").contains("role=dialog"), c.tree("pinned-run"));
    assertEquals(1, k.tab(app).pendingChanges(false));
    assertEquals(0, k.tab(app).pendingChanges(true));
    assertEquals(0, writeCount());

    // 2. Switching database asks; Cancel stays, Discard switches.
    app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    String chooseDb = "press #database\nframe\n" + OPENED
        + "press role=menuitem label=\"analytics\"\nframe\n" + OPENED;
    c.run(app, chooseDb + "snap db-asked\n");
    assertTrue(c.tree("db-asked").contains("Discard 1 change?"), c.tree("db-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals("shop", k.tab(app).database);
    c.run(app, chooseDb + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertEquals("analytics", k.tab(app).database);
    assertEquals(0, writeCount());

    // 3. Switching mode (Auto -> Manual) asks; Cancel stays, Discard switches.
    app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(app, "press role=radio label=\"Manual\"\nframe\n" + OPENED + "snap mode-asked\n");
    assertTrue(c.tree("mode-asked").contains("Discard 1 change?"), c.tree("mode-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(ConsoleTab.Mode.AUTO, k.tab(app).mode);
    c.run(
        app,
        "press role=radio label=\"Manual\"\nframe\n" + OPENED + "press #answer-discard\nframe\n"
            + OPENED + SETTLE);
    assertEquals(ConsoleTab.Mode.MANUAL, k.tab(app).mode);
    assertEquals(0, writeCount());

    // 4. Closing tab asks; Cancel keeps tab, Discard closes it.
    app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap close-asked\n");
    assertTrue(c.tree("close-asked").contains("Discard 1 change?"), c.tree("close-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(1, app.state().consoles(null).size());
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertEquals(0, writeCount());
  }

  @Test
  void discardQuestionsGuardConnectionActsAndExit() throws Exception {
    // Disconnect, Edit connection, Delete connection, and Exit each ask.
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap disc-asked\n");
    assertTrue(c.tree("disc-asked").contains("Discard 1 change?"), c.tree("disc-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertTrue(app.state().catalog.connected(SHOP.id()));

    c.run(
        app,
        menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap edit-asked\n");
    assertTrue(c.tree("edit-asked").contains("Discard 1 change?"), c.tree("edit-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().dialogs.dialog);

    c.run(
        app,
        menu("shop-prod", "Delete…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap del-asked\n");
    assertTrue(c.tree("del-asked").contains("Discard 1 change?"), c.tree("del-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().dialogs.deleting);

    String exit = "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED;
    c.run(app, exit + "snap exit-asked\n");
    assertTrue(c.tree("exit-asked").contains("Discard 1 change?"), c.tree("exit-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    c.run(app, exit + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(0, writeCount());
  }

  @Test
  void manualModeAsksTransactionQuestionBeforeDmlPreviewAndKeepsChanges() throws Exception {
    var app = k.console(SHOP);
    c.run(app, MANUAL + type(SELECT_CUSTOMERS) + RUN + SETTLE);
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // Commit… first asks the transaction question, without discarding edits.
    c.run(app, "press #commit-changes\nframe\n" + OPENED + "snap tx-asked\n");
    String txAsked = c.tree("tx-asked");
    assertTrue(txAsked.contains("role=dialog label=\"Commit the transaction?\""), txAsked);
    assertTrue(txAsked.contains("before the changes are committed."), txAsked);

    // Committing the console transaction proceeds directly to the DML Preview with edits intact.
    c.run(app, "press #answer-commit\nframe\n" + OPENED + SETTLE + OPENED + "snap preview\n");
    String preview = c.tree("preview");
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(preview.contains("role=dialog label=\"DML Preview\""), preview);
    assertTrue(preview.contains("Alicia"), preview);

    // Confirming Commit in DML Preview writes the change.
    c.run(app, "press #preview-commit\nframe\n" + OPENED + SETTLE);
    assertEquals("Alicia", servers.rowsOf(CUSTOMERS_PATH).get(0).get(1));
  }

  @Test
  void missedRefusedFailureAndUnknownOutcomesKeepChangesAndNeverRetry() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // 1. Missed: another session changed the row before Commit.
    servers.values(
        CUSTOMERS_PATH,
        List.of(
            List.of(1L, "ChangedElsewhere", "alice@example.com"),
            List.of(2L, "Bob", "bob@example.com"),
            List.of(3L, "Carol", "carol@example.com")));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap missed\n");
    String missed = c.tree("missed");
    assertTrue(line(missed, "#row-0 role=row").contains("value=\"error\""), missed);
    assertTrue(line(missed, "#cell-0-1 ").contains("invalid"), missed);
    assertTrue(line(missed, "#write-problem").contains("Changes not committed"), missed);
    assertTrue(line(missed, "#pending-changes").contains("1 change"), missed);
    assertEquals("ChangedElsewhere", servers.rowsOf(CUSTOMERS_PATH).get(0).get(1));

    // Restore server value for the remaining outcome checks.
    servers.values(
        CUSTOMERS_PATH,
        List.of(
            List.of(1L, "Alice", "alice@example.com"),
            List.of(2L, "Bob", "bob@example.com"),
            List.of(3L, "Carol", "carol@example.com")));

    // 2. Refused.
    servers.refuse(WRITE_CUSTOMERS);
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap refused\n");
    assertTrue(
        line(c.tree("refused"), "#write-problem").contains("Changes refused"), c.tree("refused"));
    assertTrue(line(c.tree("refused"), "#pending-changes").contains("1 change"));
    servers.allow(WRITE_CUSTOMERS);

    // 3. Server failure (e.g. constraint error).
    servers.fail(
        WRITE_CUSTOMERS,
        QueryException.of(QueryException.Failure.OTHER, "violates check constraint"));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap failed\n");
    String failed = c.tree("failed");
    assertTrue(line(failed, "#write-problem").contains("Commit failed"), failed);
    assertTrue(failed.contains("violates check constraint"), failed);
    assertTrue(line(failed, "#pending-changes").contains("1 change"));

    // 4. CONNECTION_LOST / ABORTED: says outcome is not known, keeps changes, never retries.
    long beforeLost = writeCount();
    servers.fail(WRITE_CUSTOMERS, QueryException.of(QueryException.Failure.CONNECTION_LOST, "eof"));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap lost\n");
    String lost = c.tree("lost");
    assertTrue(line(lost, "#write-problem").contains(ConsoleTab.UNSURE), lost);
    assertTrue(line(lost, "#pending-changes").contains("1 change"), lost);
    assertEquals(beforeLost + 1, writeCount(), "must not retry automatically");
  }

  @Test
  void cancelInFlightWriteMarksOutcomeUnknownAndKeepsChanges() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    var gate = servers.hold(WRITE_CUSTOMERS);
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + "frame\n");
    eventually(() -> servers.calls.contains(WRITE_CUSTOMERS));
    c.run(app, "frame\nsnap writing\n");
    assertTrue(c.tree("writing").contains("#cancel-write"), c.tree("writing"));
    c.run(app, "press #cancel-write\nframe\n" + OPENED + "snap cancelled\n");
    eventually(() -> servers.interrupted.contains(WRITE_CUSTOMERS));
    String cancelled = c.tree("cancelled");
    assertTrue(line(cancelled, "#write-problem").contains(ConsoleTab.UNSURE), cancelled);
    assertTrue(line(cancelled, "#pending-changes").contains("1 change"), cancelled);
    gate.countDown();
    c.run(app, SETTLE);
  }

  @Test
  void cancellingTheCommitInTheQuestionKeepsChangesAndAsksAboutThem() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    var gate = servers.hold(WRITE_CUSTOMERS);
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + "frame\n");
    eventually(() -> servers.calls.contains(WRITE_CUSTOMERS));
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap waiting\n");
    assertTrue(c.tree("waiting").contains("#answer-cancel-commit"), c.tree("waiting"));
    c.run(app, "press #answer-cancel-commit\nframe\n" + OPENED + "snap asked\n");
    eventually(() -> servers.interrupted.contains(WRITE_CUSTOMERS));
    assertTrue(c.tree("asked").contains("Discard 1 change?"), c.tree("asked"));
    assertEquals(1, k.tab(app).pendingChanges(false));
    assertTrue(app.state().catalog.connected(SHOP.id()));
    gate.countDown();
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
  }

  @Test
  void aResultOfAConnectionOpenedAgainIsReadOnly() throws Exception {
    var app = openCustomers();
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + SETTLE);
    c.run(app, CatalogTreeTest.open("shop-prod") + SETTLE + "snap again\n");
    assertTrue(app.state().catalog.connected(SHOP.id()));
    String again = c.tree("again");
    assertTrue(line(again, "#edit-status").contains(ResultEdits.CONNECTION_CHANGED), again);
    assertTrue(line(again, "#commit-changes").contains("disabled"), again);
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    assertEquals(0, k.tab(app).pendingChanges(false));
    assertEquals(0, writeCount());
  }

  @Test
  void oneLongCellOfAnEditableResultCopiesWhole() throws Exception {
    var name = "n".repeat(3000);
    servers.values(
        CUSTOMERS_PATH,
        List.of(List.of(1L, name, "alice@example.com"), List.of(2L, "Bob", "bob@example.com")));
    var app = openCustomers();
    // A fresh grid takes keys before any cell is chosen.
    c.run(app, "focus #grid\nkey ArrowDown\nframe\npress #cell-0-1\nkey C control\nframe\n");
    assertEquals(name, app.clipboard().read());
  }

  @Test
  void aZonedTimeIsStagedAsOneMoment() {
    var field = new Field("at", "timestamptz", Kind.DATE_TIME_ZONED);
    var column = new dev.hoori.db.core.catalog.Column(null, "at", "timestamptz", true, 0);
    var york = java.time.ZoneId.of("America/New_York");
    // Skipped and repeated local times name no one moment.
    assertFalse(Cells.parse(field, column, "2026-03-08 02:30:00", null, york).valid());
    assertFalse(Cells.parse(field, column, "2026-11-01 01:30:00", null, york).valid());
    // A time without an offset is staged with the one it has now, so another zone keeps it.
    var parsed = Cells.parse(field, column, "2026-01-15 12:00:00", null, york);
    var staged = ResultEdits.staged(field, parsed.value(), null, "2026-01-15 12:00:00", york);
    var again = Cells.parse(field, column, staged, null, java.time.ZoneOffset.UTC);
    assertEquals(parsed.value(), again.value(), staged);
    // With the offset's minutes, which the grid's editor takes back (rethinkj/ui#510).
    assertEquals("2026-01-15 12:00:00-05:00", staged);
    var utc = ResultEdits.staged(field, parsed.value(), null, "x", java.time.ZoneOffset.UTC);
    assertEquals("2026-01-15 17:00:00+00:00", utc);
  }

  @Test
  void selectingWholeRowsFindsTheChangesInThem() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-1-1\ntype \"Bobby\"\nkey Enter\nframe\n");
    // The first row whole, which reaches to the last column there is.
    var o = k.tab(app).outputs.getFirst();
    o.selection.selectAll(1);
    assertFalse(ResultEdits.hasSelectedChanges(o));
    c.run(app, "press #cell-1-2\nkey A control\nframe\nframe\nsnap all\n");
    assertFalse(line(c.tree("all"), "#revert-selection").contains("disabled"), c.tree("all"));
  }

  @Test
  void aRowShownTwiceIsNotWrittenFromBothCopies() throws Exception {
    var twice = "SELECT id, name, email FROM customers WHERE id = 1";
    servers.statement(twice, () -> {
      var answer = customersAnswer();
      var rows = (Result.Rows) answer.results().getFirst();
      var first = rows.rows().getFirst();
      return new FakeServers.Answer(
          List.of(new Result.Rows(rows.fields(), 0, List.of(first, first))), List.of());
    });
    var app = k.console(SHOP);
    c.run(app, type(twice) + RUN + SETTLE);
    c.run(
        app,
        "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n"
            + "press #cell-1-1\ntype \"Ali\"\nkey Enter\nframe\n"
            + "press #commit-changes\nframe\n" + OPENED + "snap twice\n");
    String shown = c.tree("twice");
    assertFalse(shown.contains("DML Preview"), shown);
    assertTrue(line(shown, "#write-problem").contains("Row shown twice"), shown);
    assertEquals(0, writeCount());
  }

  @Test
  void readOnlyReasonsShowInStatusLine() throws Exception {
    // 1. Read-only connection.
    var ro = SHOP.withReadOnly(true);
    var app = k.console(ro);
    c.run(app, type(SELECT_CUSTOMERS) + RUN + SETTLE + "snap readonly\n");
    String roTree = c.tree("readonly");
    assertTrue(line(roTree, "#edit-status").contains(ResultEdits.READ_ONLY_CONNECTION), roTree);
    assertFalse(roTree.contains("#commit-changes"), roTree);

    // 2. Non-single-table SELECT (JOIN).
    String join = "SELECT c.id, o.total FROM customers c JOIN orders o ON c.id = o.customer_id";
    servers.statement(join, List.of(FakeServers.made(1, "id: integer", "total: numeric(12,2)")));
    app = k.console(SHOP);
    c.run(app, type(join) + RUN + SETTLE + "snap join\n");
    assertTrue(line(c.tree("join"), "#edit-status").contains(ResultEdits.NOT_SINGLE_TABLE));

    // 3. Session created a temporary table on MariaDB -> read-only until reconnect.
    String createTemp = "CREATE TEMPORARY TABLE items (id int PRIMARY KEY, name varchar(50))";
    String selectItems = "SELECT id, name FROM items";
    servers.statement(createTemp, List.of(new Result.Count(0)));
    servers.statement(
        selectItems,
        List.of(new Result.Rows(
            List.of(
                FakeServers.originField("id", "int(11)", "inventory", "items", "id"),
                FakeServers.originField("name", "varchar(200)", "inventory", "items", "name")),
            0,
            List.of(List.of(1L, "Widget")))));
    app = k.console(INVENTORY);
    c.run(
        app,
        type(createTemp + ";\n" + selectItems) + "key Enter control shift\n" + SETTLE
            + "snap temp\n");
    assertTrue(
        line(c.tree("temp"), "#edit-status").contains(ResultEdits.TEMP_TABLE_SESSION),
        c.tree("temp"));

    // 4. Editable result with expression column, repeated column, and Cut cell.
    String mixed = "SELECT id, upper(name) AS up, name, name AS again, email FROM customers";
    servers.statement(
        mixed,
        List.of(new Result.Rows(
            List.of(
                FakeServers.originField("id", "integer", "shop.public", "customers", "id"),
                new Field("up", "text", Kind.TEXT),
                FakeServers.originField("name", "text", "shop.public", "customers", "name"),
                FakeServers.originField("again", "text", "shop.public", "customers", "name"),
                FakeServers.originField(
                    "email", "character varying(255)", "shop.public", "customers", "email")),
            0,
            List.of(List.of(
                1L, "ALICE", "Alice", "Alice", new Cut("short...", OptionalLong.empty()))))));
    app = k.console(SHOP);
    c.run(app, type(mixed) + RUN + SETTLE);
    c.run(app, "press #cell-0-1\nframe\nsnap expr\n");
    assertTrue(line(c.tree("expr"), "#edit-status").contains("expression"), c.tree("expr"));
    c.run(app, "press #cell-0-2\nframe\nsnap repeated\n");
    assertTrue(
        line(c.tree("repeated"), "#edit-status").contains("repeated column"), c.tree("repeated"));
    c.run(app, "press #cell-0-4\nframe\nsnap cutcell\n");
    assertTrue(line(c.tree("cutcell"), "#edit-status").contains("cut short"), c.tree("cutcell"));

    // 5. MariaDB timestamp column stays read-only inside an editable result.
    servers.serve(MARIA, Provider.MARIADB, "11.4.2-MariaDB", """
        inventory
          events
            *id: int(11)
            label: varchar(100)
            at: timestamp
        """);
    String selectEvents = "SELECT id, label, at FROM events";
    servers.statement(
        selectEvents,
        List.of(new Result.Rows(
            List.of(
                FakeServers.originField("id", "int(11)", "inventory", "events", "id"),
                FakeServers.originField("label", "varchar(100)", "inventory", "events", "label"),
                FakeServers.originField("at", "timestamp", "inventory", "events", "at")),
            0,
            List.of(List.of(1L, "ok", "2026-01-01 10:00:00")))));
    app = k.console(INVENTORY);
    c.run(app, type(selectEvents) + RUN + SETTLE + "press #cell-0-2\nframe\nsnap ts\n");
    assertTrue(line(c.tree("ts"), "#edit-status").contains("timestamp"), c.tree("ts"));
  }

  @Test
  void pasteAndClearOverUnloadedRowsAreRefused() throws Exception {
    var fields = List.of(
        FakeServers.originField("id", "integer", "shop.public", "customers", "id"),
        FakeServers.originField("name", "text", "shop.public", "customers", "name"),
        FakeServers.originField(
            "email", "character varying(255)", "shop.public", "customers", "email"));
    var rows = new ArrayList<List<Object>>();
    for (long i = 1; i <= 250; i++) {
      rows.add(
          Collections.unmodifiableList(Arrays.asList(i, "Name " + i, "u" + i + "@example.com")));
    }
    String many = "SELECT id, name, email FROM customers";
    servers.values(CUSTOMERS_PATH, rows);
    servers.statement(many, List.of(new Result.Rows(fields, 0, rows)));
    var app = k.console(SHOP);
    c.run(app, type(many) + RUN + SETTLE);
    // Only first 200 rows are loaded; row 200 is the loading row. Ctrl+A -> Delete must be refused.
    c.run(app, "press #cell-0-1\nkey A control\nframe\nkey Delete\nframe\nsnap refused-clear\n");
    String refused = c.tree("refused-clear");
    assertTrue(line(refused, "#edit-status").contains(ResultEdits.UNLOADED_PASTE), refused);
    assertEquals(0, k.tab(app).pendingChanges(false));
  }

  /**
   * In a narrow editable result footer, feedback texts give way before the status line, which shows
   * them on hover, while the edit controls and Pin stay inside the card.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 400})
  void aNarrowEditableResultFooterKeepsRoomForTheStatusLine(int sidebar) throws Exception {
    String mixed = "SELECT id, upper(name) AS up, name, email FROM customers";
    servers.statement(
        mixed,
        List.of(new Result.Rows(
            List.of(
                FakeServers.originField("id", "integer", "shop.public", "customers", "id"),
                new Field("up", "text", Kind.TEXT),
                FakeServers.originField("name", "text", "shop.public", "customers", "name"),
                FakeServers.originField(
                    "email", "character varying(255)", "shop.public", "customers", "email")),
            0,
            List.of(List.of(1L, "ALICE", "Alice", "alice@example.com")))));
    var app = k.console(SHOP);
    if (sidebar > 0) {
      app.state().sidebar = Split.Position.first(sidebar);
    }
    c.run(
        app,
        "size 800 760\nframe\n"
            + type(mixed)
            + RUN
            + SETTLE
            + "press #cell-0-1\nframe\nsnap narrow-edit\n");
    String tree = c.tree("narrow-edit");
    assertTrue(line(tree, "#edit-status").contains("expression"), tree);
    float statusWidth = widthOf(tree, "#result-status ");
    assertEquals(0, widthOf(tree, "#edit-status "), tree);
    assertTrue(statusWidth >= (sidebar > 0 ? 10 : 80), "status=" + statusWidth + "\n" + tree);
    assertTrue(TableTabTest.end(tree, "#pin ") <= TableTabTest.end(tree, "#grid "), tree);
    c.run(app, "move #result-status\nframe\nadvance 1\nframe\nframe\nsnap tip\n");
    String tip = c.tree("tip");
    assertTrue(ConnectionsTest.line(tip, "role=tooltip").contains("expression"), tip);
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.TableTabTest.BIG;
import static dev.hoori.db.app.TableTabTest.KINDS;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.scroll;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editing cells in a table tab: DML Preview, Commit, Revert, dropped pages, guards and reasons. */
class TableTabEditTest {

  private static final String CUSTOMERS = PG + "/shop.public.customers";
  private static final String ITEMS = PG + "/shop.public.items";
  private static final String WRITE_CUSTOMERS = "write " + CUSTOMERS;
  private static final String WRITE_BIG = "write " + BIG;

  @TempDir
  Path dir;

  private final TableTabTest t = new TableTabTest();
  private CatalogTreeTest c;
  private FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    t.dir = dir;
    t.setUp();
    c = t.c;
    servers = t.servers;
    String outline = TableTabTest.OUTLINE.replace("    logs\n      name: text\n", """
            logs
              name: text
            items
              *id: integer
              name: text
              note: text
            @active_customers
              id: integer
              name: text
        """);
    assertTrue(outline.contains("@active_customers"), outline);
    servers.serve(PG, Provider.POSTGRESQL, "16.4", outline);
    servers.values(
        CUSTOMERS, List.of(List.of(1L, "Alice"), List.of(2L, "Bob"), List.of(3L, "Carol")));
    servers.values(
        ITEMS,
        List.of(
            List.of(1L, "Alice", "vip"), List.of(2L, "Bob", "trial"), List.of(3L, "Carol", "std")));
  }

  @AfterEach
  void tearDown() throws Exception {
    t.tearDown();
  }

  private App<State> openCustomers() throws Exception {
    var app = t.shop();
    c.run(app, table("customers") + SETTLE);
    return app;
  }

  private long writeCount() {
    return servers.calls.stream().filter(call -> call.startsWith("write ")).count();
  }

  private static String clause(String label, String text) {
    return "press role=textbox label=\""
        + label
        + "\"\nkey A control\n"
        + (text.isEmpty()
            ? "key Backspace\n"
            : "clipboard \"" + text.replace("\"", "\\\"") + "\"\nkey V control\n")
        + "frame\n";
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
    assertTrue(line(edited, "#uncommitted").contains("1 table has uncommitted changes"), edited);

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
    assertEquals("Alice", servers.rowsOf(CUSTOMERS).get(0).get(1));
    assertTrue(line(c.tree("cancelled"), "#pending-changes").contains("1 change"));

    // Open preview again and Commit: writes to FakeServers, keeps new value, shows hint.
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap committed\n");
    assertEquals(1, writeCount(), servers.calls.toString());
    assertTrue(servers.calls.contains(WRITE_CUSTOMERS), servers.calls.toString());
    assertEquals("Alicia", servers.rowsOf(CUSTOMERS).get(0).get(1));
    String committed = c.tree("committed");
    assertTrue(cell(committed, 0, 1).contains("\"Alicia\""), committed);
    assertFalse(committed.contains("#pending-changes"), committed);
    assertTrue(
        line(committed, "#edit-status").contains(ResultEdits.TABLE_COMMITTED_HINT), committed);

    // Refresh asks nothing and reads the committed value back.
    c.run(app, "press #refresh\nframe\n" + SETTLE + "snap refreshed\n");
    assertFalse(c.tree("refreshed").contains("role=dialog"), c.tree("refreshed"));
    assertTrue(cell(c.tree("refreshed"), 0, 1).contains("\"Alicia\""), c.tree("refreshed"));
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
  void refreshAndChangedClausesAskBeforeDiscarding() throws Exception {
    servers.values(CUSTOMERS + " WHERE id > 1", List.of(List.of(2L, "Bob"), List.of(3L, "Carol")));
    servers.values(
        CUSTOMERS + " WHERE id > 1 ORDER BY id DESC",
        List.of(List.of(3L, "Carol"), List.of(2L, "Bob")));
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // 1. Refresh asks "Discard 1 change?"; Cancel keeps changes, Discard refreshes with no write.
    int beforeRefresh = servers.calls.size();
    c.run(app, "press #refresh\nframe\n" + OPENED + "snap refresh-asked\n");
    String refreshAsked = c.tree("refresh-asked");
    assertTrue(refreshAsked.contains("role=dialog label=\"Discard 1 change?\""), refreshAsked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(1, t.tab(app).pendingChanges());
    assertEquals(beforeRefresh, servers.calls.size(), "Cancel must not refresh");
    c.run(
        app,
        "press #refresh\nframe\n" + OPENED + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertEquals(0, t.tab(app).pendingChanges());
    assertEquals(0, writeCount());
    assertTrue(cell(t.snapped(app, "after-refresh"), 0, 1).contains("\"Alice\""));

    // 2. Changed WHERE asks "Discard 1 change?"; Cancel keeps changes, Discard applies WHERE.
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(app, clause("WHERE", "id > 1") + "key Enter\nframe\n" + OPENED + "snap where-asked\n");
    String whereAsked = c.tree("where-asked");
    assertTrue(whereAsked.contains("role=dialog label=\"Discard 1 change?\""), whereAsked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(1, t.tab(app).pendingChanges());
    assertEquals("", t.tab(app).filter.where(), "Cancel must not apply WHERE");
    c.run(
        app,
        "press role=textbox label=\"WHERE\"\nkey Enter\nframe\n" + OPENED
            + "press #answer-discard\nframe\n" + OPENED + SETTLE + "snap filtered\n");
    assertEquals(0, t.tab(app).pendingChanges());
    assertEquals("id > 1", t.tab(app).filter.where());
    assertTrue(cell(c.tree("filtered"), 0, 1).contains("\"Bob\""), c.tree("filtered"));
    assertEquals(0, writeCount());

    // 3. Pressing Enter with unchanged WHERE does not ask.
    c.run(app, "press #cell-0-1\ntype \"Bobby\"\nkey Enter\nframe\n");
    c.run(app, "press role=textbox label=\"WHERE\"\nkey Enter\nframe\n" + OPENED);
    assertFalse(t.snapped(app, "unchanged").contains("role=dialog"));
    assertEquals(1, t.tab(app).pendingChanges());

    // 4. Changed ORDER BY asks "Discard 1 change?" and Discard applies it.
    c.run(
        app, clause("ORDER BY", "id DESC") + "key Enter\nframe\n" + OPENED + "snap order-asked\n");
    assertTrue(c.tree("order-asked").contains("Discard 1 change?"), c.tree("order-asked"));
    c.run(app, "press #answer-discard\nframe\n" + OPENED + SETTLE + "snap ordered\n");
    assertEquals(0, t.tab(app).pendingChanges());
    assertEquals("id DESC", t.tab(app).filter.order());
    assertTrue(cell(c.tree("ordered"), 0, 1).contains("\"Carol\""), c.tree("ordered"));
    assertEquals(0, writeCount());
  }

  @Test
  void headerSortAsksBeforeDiscardingAndIgnoresPressWhilePreviewIsOpen() throws Exception {
    servers.values(
        CUSTOMERS + " ORDER BY \"name\" ASC",
        List.of(List.of(1L, "Alice"), List.of(2L, "Bob"), List.of(3L, "Carol")));
    servers.values(
        CUSTOMERS + " ORDER BY \"name\" DESC",
        List.of(List.of(3L, "Carol"), List.of(2L, "Bob"), List.of(1L, "Alice")));
    var app = openCustomers();
    c.run(app, "press role=columnheader label=\"name\"\nframe\n" + SETTLE);
    assertEquals("\"name\" ASC", t.tab(app).filter.order());

    // Stage a pending edit, then press the name header: Guard asks before discarding.
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    int before = servers.calls.size();
    c.run(app, "press role=columnheader label=\"name\"\nframe\n" + OPENED + "snap sort-asked\n");
    assertTrue(c.tree("sort-asked").contains("Discard 1 change?"), c.tree("sort-asked"));

    // Cancel keeps the edit, applied ORDER BY, and ascending sort arrow.
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE + "snap sort-cancelled\n");
    String cancelled = c.tree("sort-cancelled");
    assertEquals(1, t.tab(app).pendingChanges());
    assertEquals("\"name\" ASC", t.tab(app).filter.order());
    assertTrue(line(cancelled, "#column-1").contains("sort=ascending"), cancelled);
    assertEquals(before, servers.calls.size(), "Cancel must not re-read");

    // While DML Preview is open, the grid has no sort handler.
    c.run(app, "press #commit-changes\nframe\n" + OPENED);
    assertTrue(t.tab(app).preview != null);
    c.run(app, "press #preview-cancel\nframe\n" + OPENED);

    // Pressing the header again and choosing Discard applies the descending sort.
    c.run(
        app,
        "press role=columnheader label=\"name\"\nframe\n"
            + OPENED
            + "press #answer-discard\nframe\n"
            + OPENED
            + SETTLE
            + "snap sort-discarded\n");
    String discarded = c.tree("sort-discarded");
    assertEquals(0, t.tab(app).pendingChanges());
    assertEquals("\"name\" DESC", t.tab(app).filter.order());
    assertTrue(line(discarded, "#column-1").contains("sort=descending"), discarded);
    assertTrue(cell(discarded, 0, 1).contains("\"Carol\""), discarded);
    assertEquals(0, writeCount());
  }

  @Test
  void editedRowsPageStaysAndCommitsRightRow() throws Exception {
    var rows = new ArrayList<List<Object>>(3_000);
    for (long i = 1; i <= 3_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // Edit row 0 (id = 1).
    c.run(app, "press #cell-0-1\ntype \"Edited 1\"\nkey Enter\nframe\n");
    assertEquals(1, tab.pendingChanges());

    // Scroll far enough through distinct pages that keep() would drop page 0: it holds an edit.
    for (long r : new long[] {600, 1000, 1400, 1800, 2200, 2600}) {
      c.run(app, scroll(r) + SETTLE);
    }
    assertTrue(tab.pages.containsKey(0L), tab.pages.keySet().toString());
    assertEquals(1, tab.pendingChanges());

    // Scroll back to row 0: the edited row still shows its edit, then commits the right row.
    c.run(app, scroll(0) + SETTLE + "snap back\n");
    String back = c.tree("back");
    assertTrue(line(back, "#row-0 role=row").contains("value=\"changed\""), back);
    assertTrue(cell(back, 0, 1).contains("\"Edited 1\""), back);

    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE);
    assertEquals(1, writeCount(), servers.calls.toString());
    assertTrue(servers.calls.contains(WRITE_BIG), servers.calls.toString());
    assertEquals("Edited 1", servers.rowsOf(BIG).get(0).get(1));
    assertEquals("name 2", servers.rowsOf(BIG).get(1).get(1));

    // A row the server changed since it was read is missed, not overwritten.
    c.run(app, "press #cell-1-1\ntype \"Edited 2\"\nkey Enter\nframe\n");
    var mutated = new ArrayList<>(servers.rowsOf(BIG));
    mutated.set(1, List.of(2L, "ServerChanged"));
    servers.values(BIG, mutated);
    for (long r : new long[] {600, 1000, 1400, 1800, 2200, 2600}) {
      c.run(app, scroll(r) + SETTLE);
    }
    c.run(app, scroll(0) + SETTLE);
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap dropped-missed\n");
    String missed = c.tree("dropped-missed");
    assertTrue(line(missed, "#write-problem").contains("Changes not committed"), missed);
    assertEquals("ServerChanged", servers.rowsOf(BIG).get(1).get(1));
  }

  @Test
  void redoWritesTheRowItWasMadeOn() throws Exception {
    var rows = new ArrayList<List<Object>>(3_000);
    for (long i = 1; i <= 3_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // Edit id 1, undo it, and let the server delete id 1: page 0, with undo history, stays.
    c.run(app, "press #cell-0-1\ntype \"Edited 1\"\nkey Enter\nframe\nkey Z control\nframe\n");
    assertEquals(0, tab.pendingChanges());
    for (long r : new long[] {600, 1000, 1400, 1800, 2200, 2600}) {
      c.run(app, scroll(r) + SETTLE);
    }
    assertTrue(tab.pages.containsKey(0L), tab.pages.keySet().toString());
    servers.values(BIG, rows.subList(1, rows.size()));
    c.run(app, scroll(0) + SETTLE + "snap back\n");
    assertTrue(cell(c.tree("back"), 0, 1).contains("\"name 1\""), c.tree("back"));

    // Redo stages the edit on id 1 again, not on id 2: the write misses.
    c.run(
        app,
        "key Y control\nframe\npress #commit-changes\nframe\n" + OPENED
            + "press #preview-commit\nframe\n" + OPENED + SETTLE + "snap redone\n");
    assertTrue(line(c.tree("redone"), "#write-problem").contains("Changes not committed"));
    assertEquals("name 2", servers.rowsOf(BIG).get(0).get(1));
  }

  @Test
  void undoHistoryIsForgottenWhereItsPageComesBackAfterItWasGone() throws Exception {
    var rows = new ArrayList<List<Object>>(1_000);
    for (long i = 1; i <= 1_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // Edit id 1 and revert it, which undo can bring back.
    c.run(
        app,
        "press #cell-0-1\ntype \"Edited 1\"\nkey Enter\nframe\npress #cell-0-1\nframe\n"
            + "press #revert-selection\nframe\n");
    assertEquals(0, tab.pendingChanges());

    // Page 0 is gone, as after a read found it empty, and comes back with other rows: id 1 is
    // deleted, so id 2 is at row 0 now.
    servers.values(BIG, rows.subList(1, rows.size()));
    tab.pages.remove(0L);
    c.run(app, scroll(0) + SETTLE + "snap back\n");
    assertTrue(cell(c.tree("back"), 0, 1).contains("\"name 2\""), c.tree("back"));

    // A fresh edit of id 2, undone, and undo once more: the revert of id 1's edit is not undone
    // onto id 2.
    c.run(
        app,
        "press #cell-0-1\ntype \"Fresh\"\nkey Enter\nframe\n"
            + "key Z control\nframe\nkey Z control\nframe\n");
    assertEquals(0, tab.pendingChanges());
  }

  @Test
  void aFreshEditWhereAnotherRowWasUndoneForgetsTheHistory() throws Exception {
    var rows = new ArrayList<List<Object>>(1_000);
    for (long i = 1; i <= 1_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // Id 1 is edited when its page comes back with id 2 at row 0.
    c.run(app, "press #cell-0-1\ntype \"Edited 1\"\nkey Enter\nframe\n");
    servers.values(BIG, rows.subList(1, rows.size()));
    tab.pages.remove(0L);
    c.run(app, scroll(0) + SETTLE);

    // Revert the edit of id 1, then edit id 2, shown there now, and undo twice.
    c.run(
        app,
        "press #cell-0-1\nframe\npress #revert-selection\nframe\n"
            + "press #cell-0-1\ntype \"Fresh\"\nkey Enter\nframe\n"
            + "key Z control\nframe\nkey Z control\nframe\n");
    assertEquals(0, tab.pendingChanges());
  }

  @Test
  void aDraftWhoseRowWentIsCancelledWhenItsPageComesBack() throws Exception {
    var rows = new ArrayList<List<Object>>(1_000);
    for (long i = 1; i <= 1_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // A draft on id 1, whose page goes and comes back with id 2 at row 0.
    c.run(app, "press #cell-0-1\ntype \"Draft\"\nframe\n");
    assertTrue(tab.selection.isEditing());
    servers.values(BIG, rows.subList(1, rows.size()));
    tab.pages.remove(0L);
    c.run(app, scroll(600) + SETTLE + scroll(0) + SETTLE);
    assertTrue(tab.pages.containsKey(0L), tab.pages.keySet().toString());
    assertFalse(tab.selection.isEditing());
    assertEquals(0, tab.pendingChanges());
  }

  @Test
  void aCommitThatChangesAKeyReadsTheRowsAnew() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-0\ntype \"10\"\nkey Enter\nframe\n");
    long reads =
        servers.calls.stream().filter(x -> x.startsWith("read " + CUSTOMERS)).count();
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap rekeyed\n");
    assertEquals(10L, servers.rowsOf(CUSTOMERS).get(0).get(0));
    // The rows' places may have changed with their keys: they are read as the server holds them.
    assertTrue(
        servers.calls.stream().filter(x -> x.startsWith("read " + CUSTOMERS)).count() > reads,
        servers.calls.toString());
    assertEquals(0, t.tab(app).pendingChanges());
  }

  @Test
  void pagesWithEditsLeaveRoomForThoseInView() throws Exception {
    var rows = new ArrayList<List<Object>>(3_000);
    for (long i = 1; i <= 3_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // One edit on each of as many pages as are kept.
    for (int page = 0; page < TableTab.KEPT; page++) {
      int row = page * TableTab.PAGE;
      c.run(
          app, scroll(row) + SETTLE + "press #cell-" + row + "-1\ntype \"E\"\nkey Enter\nframe\n");
    }
    assertEquals(TableTab.KEPT, tab.pendingChanges());

    c.run(app, scroll(2_600) + SETTLE + "snap far\n");
    assertTrue(cell(c.tree("far"), 2_600, 1).contains("\"name 2601\""), c.tree("far"));
  }

  @Test
  void editsOutliveALostConnectionAndReadsGoOnOnceConnected() throws Exception {
    var rows = new ArrayList<List<Object>>(1_000);
    for (long i = 1; i <= 1_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    c.run(app, "press #cell-0-1\ntype \"Edited 1\"\nkey Enter\nframe\n");
    var key = "read " + BIG + " 600-800";
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    c.run(app, scroll(600) + SETTLE + "advance 1\nframe\nsnap lost\n");
    assertTrue(c.tree("lost").contains("role=note label=\"Disconnected\""), c.tree("lost"));

    servers.heal(key);
    c.run(app, "press role=button label=\"Connect\"\nframe\n" + SETTLE + "frame\nsnap again\n");
    String again = c.tree("again");
    assertFalse(again.contains("role=note"), again);
    assertTrue(cell(again, 600, 1).contains("\"name 601\""), again);
    assertEquals(1, t.tab(app).pendingChanges());
    c.run(
        app,
        scroll(0) + SETTLE + "press #commit-changes\nframe\n" + OPENED
            + "press #preview-commit\nframe\n" + OPENED + SETTLE);
    assertEquals("Edited 1", servers.rowsOf(BIG).get(0).get(1));
  }

  @Test
  void openEditorKeepsItsPageWhileScrolledAway() throws Exception {
    var rows = new ArrayList<List<Object>>(3_000);
    for (long i = 1; i <= 3_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // A draft typed into row 0, not staged yet.
    c.run(app, "press #cell-0-1\ntype \"Draft\"\nframe\n");
    assertTrue(tab.selection.isEditing());
    for (long r : new long[] {600, 1000, 1400, 1800, 2200, 2600}) {
      c.run(app, scroll(r) + SETTLE);
    }
    assertTrue(tab.pages.containsKey(0L), tab.pages.keySet().toString());
    assertEquals(1, tab.pendingChanges());
  }

  @Test
  void readOnlyReasonsShowForViewNoPrimaryKeyReadOnlyConnectionAndCells() throws Exception {
    var app = t.shop();

    // 1. View.
    c.run(app, table("active_customers") + SETTLE + "snap readonly-view\n");
    String view = c.tree("readonly-view");
    assertTrue(line(view, "#edit-status").contains(ResultEdits.VIEW_READ_ONLY), view);
    assertFalse(view.contains("#commit-changes"), view);

    // 2. Table without primary key.
    c.run(app, table("logs") + SETTLE + "snap readonly-no-pk\n");
    String noPk = c.tree("readonly-no-pk");
    assertTrue(line(noPk, "#edit-status").contains(ResultEdits.NO_PRIMARY_KEY), noPk);
    assertFalse(noPk.contains("#commit-changes"), noPk);

    // 3. Per-cell read-only reasons: cut value and binary value in an editable table.
    servers.values(
        KINDS,
        List.of(Arrays.asList(
            1L,
            null,
            null,
            new Cut("x".repeat(1024), OptionalLong.of(5_000)),
            "ok",
            true,
            null,
            null,
            null,
            null,
            new Binary(new byte[] {1, 2}, 2),
            null)));
    c.run(app, "size 2600 760\nframe\n" + table("kinds") + SETTLE);
    c.run(app, "press #cell-0-3\nframe\nsnap cut-cell\n");
    assertTrue(line(c.tree("cut-cell"), "#edit-status").contains("cut short"), c.tree("cut-cell"));
    c.run(app, "press #cell-0-10\nframe\nsnap binary-cell\n");
    assertTrue(
        line(c.tree("binary-cell"), "#edit-status").contains("binary"), c.tree("binary-cell"));

    // 4. Read-only connection.
    var roProfile = SHOP.withReadOnly(true);
    var roApp = c.app(roProfile);
    c.ready(roApp, roProfile);
    c.run(
        roApp,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + table("customers") + SETTLE + "snap readonly-connection\n");
    String ro = c.tree("readonly-connection");
    assertTrue(line(ro, "#edit-status").contains(ResultEdits.READ_ONLY_CONNECTION), ro);
    assertFalse(ro.contains("#commit-changes"), ro);

    // 5. MongoDB collection has no editing controls or read-only status.
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        shop
          orders
            _id: ObjectId
            total: Int32
        """);
    var mongoApp = c.app(DocumentConsoleTest.DOCS);
    c.ready(mongoApp, DocumentConsoleTest.DOCS);
    c.run(
        mongoApp,
        START + open("docs") + SETTLE + open("shop") + SETTLE + table("orders") + SETTLE
            + "snap mongo\n");
    String mongo = c.tree("mongo");
    assertFalse(mongo.contains("#edit-status"), mongo);
    assertFalse(mongo.contains("#commit-changes"), mongo);
  }

  @Test
  void missedRefusedFailureAndCancelWriteKeepChanges() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // 1. Missed: another session changed the row before Commit.
    servers.values(
        CUSTOMERS,
        List.of(List.of(1L, "ChangedElsewhere"), List.of(2L, "Bob"), List.of(3L, "Carol")));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap missed\n");
    String missed = c.tree("missed");
    assertTrue(line(missed, "#row-0 role=row").contains("value=\"error\""), missed);
    assertTrue(line(missed, "#cell-0-1 ").contains("invalid"), missed);
    assertTrue(line(missed, "#write-problem").contains("Changes not committed"), missed);
    assertTrue(line(missed, "#pending-changes").contains("1 change"), missed);
    assertEquals("ChangedElsewhere", servers.rowsOf(CUSTOMERS).get(0).get(1));

    // Restore server value for the remaining checks.
    servers.values(
        CUSTOMERS, List.of(List.of(1L, "Alice"), List.of(2L, "Bob"), List.of(3L, "Carol")));

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

    // 3. Server error.
    servers.fail(
        WRITE_CUSTOMERS, QueryException.of(QueryException.Failure.OTHER, "check violation"));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap failed\n");
    assertTrue(
        line(c.tree("failed"), "#write-problem").contains("Commit failed"), c.tree("failed"));
    assertTrue(c.tree("failed").contains("check violation"), c.tree("failed"));
    servers.heal(WRITE_CUSTOMERS);

    // 4. Cancel in-flight write marks outcome unknown and keeps changes.
    var gate = servers.hold(WRITE_CUSTOMERS);
    long writes = writeCount();
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + "frame\n");
    eventually(() -> writeCount() > writes);
    c.run(app, "frame\nsnap writing\n");
    assertTrue(c.tree("writing").contains("#cancel-write"), c.tree("writing"));
    c.run(app, "press #cancel-write\nframe\n" + OPENED + "snap cancel-write\n");
    eventually(() -> servers.interrupted.contains(WRITE_CUSTOMERS));
    assertTrue(
        line(c.tree("cancel-write"), "#write-problem").contains(ConsoleTab.UNSURE),
        c.tree("cancel-write"));
    assertTrue(line(c.tree("cancel-write"), "#pending-changes").contains("1 change"));
    gate.countDown();
    c.run(app, SETTLE);
  }

  @Test
  void guardQuestionsProtectCloseDisconnectDeleteEditAndExit() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // 1. Closing tab asks; Cancel keeps tab, Discard closes it.
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap close-asked\n");
    assertTrue(c.tree("close-asked").contains("Discard 1 change?"), c.tree("close-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(1, app.state().tables(null).size());

    // 2. Disconnect asks; Cancel stays connected.
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap disc-asked\n");
    assertTrue(c.tree("disc-asked").contains("Discard 1 change?"), c.tree("disc-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertTrue(app.state().catalog.connected(SHOP.id()));

    // 3. Edit connection asks; Cancel keeps dialog closed.
    c.run(
        app,
        menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap edit-asked\n");
    assertTrue(c.tree("edit-asked").contains("Discard 1 change?"), c.tree("edit-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().dialogs.dialog);

    // 4. Delete connection asks; Cancel keeps dialog closed.
    c.run(
        app,
        menu("shop-prod", "Delete…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap del-asked\n");
    assertTrue(c.tree("del-asked").contains("Discard 1 change?"), c.tree("del-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().dialogs.deleting);

    // 5. Cancelling an in-flight write in the Disconnect question keeps changes and asks about
    // them.
    var gate = servers.hold(WRITE_CUSTOMERS);
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + "frame\n");
    eventually(() -> servers.calls.contains(WRITE_CUSTOMERS));
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap waiting\n");
    assertTrue(c.tree("waiting").contains("#answer-cancel-commit"), c.tree("waiting"));
    c.run(app, "press #answer-cancel-commit\nframe\n" + OPENED + "snap after-cancel-commit\n");
    eventually(() -> servers.interrupted.contains(WRITE_CUSTOMERS));
    assertTrue(
        c.tree("after-cancel-commit").contains("Discard 1 change?"), c.tree("after-cancel-commit"));
    assertEquals(1, t.tab(app).pendingChanges());
    gate.countDown();
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);

    // 6. Exit asks; Cancel stays open, Discard exits.
    String exit = "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED;
    c.run(app, exit + "snap exit-asked\n");
    assertTrue(c.tree("exit-asked").contains("Discard 1 change?"), c.tree("exit-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    c.run(app, exit + "press #answer-discard\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void deleteFolderAsksBeforeDiscardingTableTabChanges() throws Exception {
    var app = openCustomers();
    // Put shop-prod into a new folder, then edit a cell in its open table tab.
    c.run(
        app,
        "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"New folder…\"\nframe\n" + OPENED
            + "type \"Work\"\nkey Enter\nframe\n" + OPENED + SETTLE);
    var folderId = app.state().workspace().entries().stream()
        .filter(dev.hoori.db.core.connection.Workspace.Folder.class::isInstance)
        .map(dev.hoori.db.core.connection.Workspace.Entry::id)
        .findFirst()
        .orElseThrow();
    app.post(() -> app.state()
        .move(
            new Node.Connection(SHOP.id()),
            new Node.Folder(folderId),
            dev.hoori.db.core.connection.Workspace.Place.INTO));
    c.run(app, SETTLE);
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");
    c.run(
        app,
        menu("Work", "Delete…")
            + "press role=button label=\"Delete contents\"\nframe\n"
            + OPENED
            + "snap folder-asked\n");
    assertTrue(c.tree("folder-asked").contains("Discard 1 change?"), c.tree("folder-asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(1, t.tab(app).pendingChanges());
    assertTrue(app.state().folder(folderId) != null);
    assertEquals(0, writeCount());
  }

  @Test
  void pasteAndClearOverUnloadedPagesAreRefused() throws Exception {
    servers.rows(BIG, 1_000, OptionalLong.of(1_000));
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    // Page 0 and 1 (400 rows) are loaded; rows 400..999 are unloaded. Ctrl+A -> Delete is refused.
    c.run(app, "press #cell-0-1\nkey A control\nframe\nkey Delete\nframe\nsnap refused-clear\n");
    String refused = c.tree("refused-clear");
    assertTrue(line(refused, "#edit-status").contains(ResultEdits.UNLOADED_PASTE), refused);
    assertEquals(0, t.tab(app).pendingChanges());
  }

  @Test
  void insertUpdateAndDeletePreviewCancelAndCommitTogether() throws Exception {
    var app = t.shop();
    c.run(app, table("items") + SETTLE);

    // 1. Update row 0 (id=1): "Alice" -> "Alicia".
    c.run(app, "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n");

    // 2. Insert a new row after row 0: leave id at DEFAULT, type name "Dave", set note to NULL.
    c.run(app, """
        press #cell-0-1
        frame
        key Insert
        frame
        type "Dave"
        key Enter
        frame
        press #cell-1-2
        frame
        key 0 control
        frame
        snap insert-new-row
        """);
    String inserted = c.tree("insert-new-row");
    assertTrue(line(inserted, "#row-1 role=row").contains("value=\"new\""), inserted);
    assertTrue(cell(inserted, 1, 0).contains("\"DEFAULT\""), inserted);
    assertTrue(cell(inserted, 1, 1).contains("\"Dave\""), inserted);
    assertTrue(cell(inserted, 1, 2).contains("\"NULL\""), inserted);
    assertTrue(line(inserted, "#pending-changes").contains("2 changes"), inserted);

    // 3. Mark grid row 2 (original row 1, id=2, shifted below the new row) for deletion.
    c.run(app, "press #cell-2-1\nframe\nkey - control\nframe\nsnap delete-marked-row\n");
    String deleted = c.tree("delete-marked-row");
    assertTrue(line(deleted, "#row-2 role=row").contains("value=\"deleted\""), deleted);
    assertTrue(line(deleted, "#pending-changes").contains("3 changes"), deleted);
    assertTrue(line(deleted, "#edit-status").contains("marked for deletion"), deleted);
    // Cells of a deleted row cannot be edited.
    c.run(app, "type \"Ignored\"\nframe\n");
    assertFalse(t.tab(app).selection.isEditing());

    // 4. Commit… opens DML Preview with UPDATE, DELETE, and INSERT (leaving DEFAULT id out).
    c.run(app, "press #commit-changes\nframe\n" + OPENED + "snap insert-delete-preview\n");
    String preview = c.tree("insert-delete-preview");
    assertTrue(
        preview.contains(
            "UPDATE \\\"public\\\".\\\"items\\\" SET \\\"name\\\" = 'Alicia' WHERE \\\"id\\\" = 1;"),
        preview);
    assertTrue(
        preview.contains("DELETE FROM \\\"public\\\".\\\"items\\\" WHERE \\\"id\\\" = 2;"),
        preview);
    assertTrue(
        preview.contains(
            "INSERT INTO \\\"public\\\".\\\"items\\\" (\\\"name\\\", \\\"note\\\") VALUES ('Dave', NULL);"),
        preview);

    // 5. Cancel sends nothing and keeps all 3 changes.
    c.run(app, "press #preview-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(0, writeCount(), servers.calls.toString());
    assertEquals(3, servers.rowsOf(ITEMS).size());
    assertEquals("Alice", servers.rowsOf(ITEMS).get(0).get(1));
    assertEquals(3, t.tab(app).pendingChanges());

    // 6. Commit writes once, FakeServers holds the result, and the table is read anew.
    long readsBefore =
        servers.calls.stream().filter(x -> x.startsWith("read " + ITEMS)).count();
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap after-commit\n");
    assertEquals(1, writeCount(), servers.calls.toString());
    var stored = servers.rowsOf(ITEMS);
    assertEquals(3, stored.size(), stored.toString());
    assertEquals(List.of(1L, "Alicia", "vip"), stored.get(0));
    assertEquals(List.of(3L, "Carol", "std"), stored.get(1));
    assertEquals(Arrays.asList(null, "Dave", null), stored.get(2));
    assertTrue(
        servers.calls.stream().filter(x -> x.startsWith("read " + ITEMS)).count() > readsBefore,
        servers.calls.toString());
    String after = c.tree("after-commit");
    assertTrue(cell(after, 0, 1).contains("\"Alicia\""), after);
    assertTrue(cell(after, 1, 1).contains("\"Carol\""), after);
    assertTrue(cell(after, 2, 1).contains("\"Dave\""), after);
  }

  @Test
  void revertSelectionRemovesNewRowAndUnmarksDeletedRow() throws Exception {
    var app = openCustomers();
    // Stage an update on row 0, a new row at grid row 1, and a delete on grid row 2 (id=2).
    c.run(app, """
        press #cell-0-1
        type "Alicia"
        key Enter
        frame
        press #cell-0-1
        frame
        key Insert
        frame
        type "Dave"
        key Enter
        frame
        press #cell-2-1
        frame
        key - control
        frame
        snap staged-all
        """);
    String staged = c.tree("staged-all");
    assertTrue(line(staged, "#pending-changes").contains("3 changes"), staged);
    assertTrue(line(staged, "#row-1 role=row").contains("value=\"new\""), staged);
    assertTrue(line(staged, "#row-2 role=row").contains("value=\"deleted\""), staged);

    // Revert selection on the new row removes it; row 1 is now id=2 (still deleted).
    c.run(app, "press #cell-1-1\nframe\npress #revert-selection\nframe\nsnap after-revert-new\n");
    String afterNew = c.tree("after-revert-new");
    assertTrue(line(afterNew, "#pending-changes").contains("2 changes"), afterNew);
    assertTrue(line(afterNew, "#row-1 role=row").contains("value=\"deleted\""), afterNew);
    assertTrue(cell(afterNew, 1, 1).contains("\"Bob\""), afterNew);

    // Revert selection on the deleted row unmarks it; row 0 update stays.
    c.run(app, "press #cell-1-0\nframe\npress #revert-selection\nframe\nsnap after-revert-del\n");
    String afterDel = c.tree("after-revert-del");
    assertTrue(line(afterDel, "#pending-changes").contains("1 change"), afterDel);
    assertFalse(line(afterDel, "#row-1 role=row").contains("value=\"deleted\""), afterDel);
    assertTrue(line(afterDel, "#row-0 role=row").contains("value=\"changed\""), afterDel);
    assertEquals(0, writeCount());
  }

  @Test
  void deleteOverUnloadedRowsIsRefused() throws Exception {
    servers.rows(BIG, 1_000, OptionalLong.of(1_000));
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    // Page 0 and 1 (400 rows) are loaded; rows 400..999 are unloaded. Ctrl+A -> Ctrl+- is refused.
    c.run(app, "press #cell-0-1\nkey A control\nframe\nkey - control\nframe\nsnap refused-del\n");
    String refused = c.tree("refused-del");
    assertTrue(line(refused, "#edit-status").contains(ResultEdits.UNLOADED_DELETE), refused);
    assertEquals(0, t.tab(app).pendingChanges());
  }

  @Test
  void missedDeleteKeepsMarkedRowAndReportsProblem() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-1-1\nframe\nkey - control\nframe\n");
    // Another session deleted id=2 before Commit.
    servers.values(CUSTOMERS, List.of(List.of(1L, "Alice"), List.of(3L, "Carol")));
    c.run(
        app,
        "press #commit-changes\nframe\n" + OPENED + "press #preview-commit\nframe\n" + OPENED
            + SETTLE + "snap missed-delete\n");
    String missed = c.tree("missed-delete");
    assertTrue(line(missed, "#write-problem").contains("Changes not committed"), missed);
    assertTrue(line(missed, "#row-1 role=row").contains("value=\"deleted\""), missed);
    assertTrue(line(missed, "#pending-changes").contains("1 change"), missed);
  }

  @Test
  void anUntouchedEditorOnAnEmptyNewCellKeepsDefaultAndThePanelShowsIt() throws Exception {
    var app = openCustomers();
    // F2 and Enter change nothing, also on the integer key, whose empty text would be NULL.
    c.run(app, """
        press #cell-0-1
        frame
        key Insert
        frame
        press #cell-1-0
        frame
        key F2
        frame
        key Enter
        frame
        press #cell-1-1
        frame
        key F2
        frame
        key Enter
        frame
        press #cell-1-0
        frame
        key Enter shift
        advance 1
        frame
        snap untouched
        """);
    String tree = c.tree("untouched");
    assertFalse(t.tab(app).selection.isEditing(), tree);
    assertTrue(cell(tree, 1, 0).contains("DEFAULT"), tree);
    assertTrue(cell(tree, 1, 1).contains("DEFAULT"), tree);
    // The two cells and the value panel.
    assertEquals(3, tree.lines().filter(l -> l.contains("\"DEFAULT\"")).count(), tree);
    c.run(app, "press #commit-changes\nframe\n" + OPENED + "snap untouched-preview\n");
    assertTrue(
        c.tree("untouched-preview").contains("(\\\"id\\\") VALUES (DEFAULT)"),
        c.tree("untouched-preview"));
  }

  @Test
  void forgettingTheHistoryKeepsNewRowsAndEditsUnderDeletionMarks() throws Exception {
    var rows = new ArrayList<List<Object>>(1_000);
    for (long i = 1; i <= 1_000; i++) {
      rows.add(List.of(i, "name " + i));
    }
    servers.values(BIG, rows);
    var app = t.shop();
    c.run(app, table("big") + SETTLE);
    var tab = t.tab(app);

    // Id 6 edited and then marked for deletion, a new row after id 11, and id 1 edited and
    // reverted, which undo could bring back.
    c.run(app, """
        press #cell-5-1
        type "Edited 6"
        key Enter
        frame
        press #cell-5-1
        frame
        key - control
        frame
        press #cell-10-1
        frame
        key Insert
        frame
        type "New"
        key Enter
        frame
        press #cell-0-1
        type "Edited 1"
        key Enter
        frame
        press #cell-0-1
        frame
        press #revert-selection
        frame
        """);
    assertEquals(2, tab.pendingChanges());

    // Page 0 comes back where undo history was: the history is forgotten.
    tab.pages.remove(0L);
    c.run(app, scroll(300) + SETTLE + scroll(0) + SETTLE);
    assertEquals(0, tab.edits.undoable);

    // Unmarking id 6 shows its edit again; undo takes back only the unmarking.
    c.run(app, "press #cell-5-1\nframe\nkey - control\nframe\nsnap unmarked\n");
    String unmarked = c.tree("unmarked");
    assertEquals(1, tab.edits.undoable, "undoable after unmark");
    assertTrue(cell(unmarked, 5, 1).contains("\"Edited 6\""), unmarked);
    assertTrue(cell(unmarked, 11, 1).contains("\"New\""), unmarked);
    c.run(app, "key Z control\nframe\nkey Z control\nframe\nsnap undone\n");
    String undone = c.tree("undone");
    assertTrue(line(undone, "#row-5 role=row").contains("value=\"deleted\""), undone);
    assertTrue(line(undone, "#row-11 role=row").contains("value=\"new\""), undone);
    assertTrue(cell(undone, 0, 1).contains("\"name 1\""), undone);
    assertEquals(2, tab.pendingChanges());
  }

  @Test
  void aCommitOfNewRowsAfterAnUnknownOutcomeWarnsInThePreview() throws Exception {
    var app = openCustomers();
    c.run(app, "press #cell-0-1\nframe\nkey Insert\nframe\ntype \"Dave\"\nkey Enter\nframe\n");
    t.tab(app).edits.unknown = true; // as after a cancelled or lost commit
    c.run(app, "press #commit-changes\nframe\n" + OPENED + "snap unsure-preview\n");
    assertTrue(
        c.tree("unsure-preview").contains("new rows it sent may be in the table already"),
        c.tree("unsure-preview"));
    // Enter does not commit them: Commit must be pressed.
    c.run(app, "key Enter\nframe\n" + SETTLE);
    assertEquals(0, writeCount(), servers.calls.toString());
  }
}

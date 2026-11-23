package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.ConsoleTransactions.MANUAL;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.FoldersTest.DELETE_CONTENTS;
import static dev.hoori.db.app.FoldersTest.SHOWN;
import static dev.hoori.db.app.FoldersTest.add;
import static dev.hoori.db.app.FoldersTest.drag;
import static dev.hoori.db.app.FoldersTest.folder;
import static dev.hoori.db.app.FoldersTest.into;
import static dev.hoori.db.app.FoldersTest.moveTo;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workspace queries: created, organized, edited with autosave, and run across targets. */
class WorkspaceQueriesTest {

  static final String SAVE_TO_WORKSPACE = "press role=button label=\"Save to workspace\"\n" + SHOWN;

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    k.servers.statement(
        "SELECT count(*) FROM items", List.of(FakeServers.made(1, "count: bigint")));
    k.servers.statement("UPDATE items SET stock = 0 WHERE id = 1", List.of(new Result.Count(1)));
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  Workspace saved() throws IOException {
    return new WorkspaceStore(dir.resolve("config")).load();
  }

  static Workspace.Query query(String name, String text, Workspace.Language language) {
    return new Workspace.Query(UUID.randomUUID(), name, text, language);
  }

  static String nameQuery(String name) {
    return "press #query-name\nkey A control\nkey Delete\ntype \"" + name + "\"\nframe\n"
        + "key Enter\n" + SHOWN;
  }

  @Test
  void aQueryIsCreatedOpenedAutosavedRenamedDuplicatedMovedAndDeleted() throws Exception {
    var reports = folder("Reports", true);
    var app = c.app(new Workspace(List.of(SHOP, reports)));
    c.run(
        app,
        START + "press role=treeitem label=\"Reports\"\nframe\n" + add("New query…")
            + "snap asked\n" + nameQuery("Daily orders") + SETTLE + "snap created\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"New query\""), asked);
    assertEquals(List.of("shop-prod", "Reports", "  Daily orders"), rows(c.tree("created")));
    var created = saved().queries().getFirst();
    assertEquals("Daily orders", created.name());
    assertEquals("", created.text());
    assertEquals(Workspace.Language.SQL, created.language());

    // Open by Enter, edit text, autosave after pause; opening again raises the single tab.
    c.run(
        app,
        "press role=treeitem label=\"Daily orders\"\nkey Enter\nframe\n" + type(COUNT)
            + "advance 1.5\nframe\n" + SETTLE + "snap edited\n"
            + "press role=treeitem label=\"Daily orders\"\nkey Enter\nframe\nsnap again\n");
    assertEquals("Daily orders", tabs(c.tree("edited")));
    assertEquals("Daily orders", tabs(c.tree("again")), "one tab per query");
    assertEquals(COUNT, saved().query(created.id()).text());

    // Rename updates both the tree and the open tab's title.
    c.run(
        app,
        menu("Daily orders", "Rename…") + nameQuery("Order count") + SETTLE + "snap renamed\n");
    assertEquals("Order count", tabs(c.tree("renamed")));
    assertEquals("Order count", saved().query(created.id()).name());

    // Duplicate goes right after original, Move to moves to top level, Delete closes its tab.
    c.run(
        app,
        menu("Order count", "Duplicate") + SETTLE + "snap dup\n"
            + moveTo("Order count (copy)", "Top level") + SETTLE + "snap moved\n"
            + menu("Order count", "Delete…") + "snap deleting\n"
            + "press role=button label=\"Delete\"\n" + SHOWN + SETTLE + "snap deleted\n");
    assertEquals(
        List.of("shop-prod", "Reports", "  Order count", "  Order count (copy)"),
        rows(c.tree("dup")));
    assertEquals(
        List.of("shop-prod", "Reports", "  Order count", "Order count (copy)"),
        rows(c.tree("moved")));
    assertTrue(c.tree("deleting").contains("Its open tab closes."), c.tree("deleting"));
    assertEquals("", tabs(c.tree("deleted")));
    assertEquals(List.of("shop-prod", "Reports", "Order count (copy)"), rows(c.tree("deleted")));
    assertEquals(1, saved().queries().size());
  }

  @Test
  void firstRunAsksSilentRerunWithOneConnectedAndChooserReturnsWithTwoConnected() throws Exception {
    var shop = SHOP.withColor(Color.RED);
    var inv = INVENTORY.withColor(Color.GREEN).withReadOnly(true);
    var mongo = ConnectionProfile.of(Provider.MONGODB)
        .withName("docs")
        .withHost("mongo.example")
        .withDatabase("shop");
    var prod = folder("Production", true, shop);
    var staging = folder("Staging", true, inv);
    var q = query("Orders", COUNT, Workspace.Language.SQL);
    var app = c.app(new Workspace(List.of(prod, staging, mongo, q)));
    c.ready(app, shop, inv);
    // Connect only shop first.
    c.run(
        app,
        START + open("shop-prod") + SETTLE
            + "press role=treeitem label=\"Orders\"\nkey Enter\nframe\n" + "key Enter control\n"
            + SHOWN + "snap first\n");
    var first = c.tree("first");
    assertTrue(first.contains("role=menu label=\"Run on\""), first);
    // SQL connections only (no MongoDB); connected first, color mark, folder path, read-only badge.
    assertEquals(List.of("shop-prod", "inventory"), items(first), first);
    assertTrue(item(first, "shop-prod").contains("value=\"red\""), first);
    assertTrue(item(first, "shop-prod").contains("\"Production\""), first);
    assertTrue(item(first, "inventory").contains("\"Staging · Read-only · Not connected\""), first);

    // Escape closes the chooser and runs nothing.
    c.run(app, "key Escape\n" + SHOWN + "snap escaped\n");
    assertFalse(c.tree("escaped").contains("role=menu label=\"Run on\""), c.tree("escaped"));
    assertFalse(k.servers.calls.contains("run " + PG + " " + COUNT), k.servers.calls.toString());

    // Open chooser again and press Enter to run on shop-prod (first item).
    c.run(
        app,
        "press #sql\nframe\nkey Enter control\n" + SHOWN + "key Enter\n" + SHOWN + SETTLE
            + "snap ran1\n");
    var ran1 = c.tree("ran1");
    assertFalse(ran1.contains("role=menu label=\"Run on\""), ran1);
    assertTrue(line(ran1, "role=tab label=\"Orders\"").contains("value=\"red\""), ran1);
    assertEquals(
        1,
        k.servers.calls.stream()
            .filter(s -> s.equals("run " + PG + " " + COUNT))
            .count());

    // Second run with only shop-prod connected: runs silently without opening chooser!
    c.run(app, "press #sql\nframe\nkey Enter control\nframe\n" + SETTLE + "snap silent\n");
    assertFalse(c.tree("silent").contains("role=menu label=\"Run on\""), c.tree("silent"));
    assertEquals(
        2,
        k.servers.calls.stream()
            .filter(s -> s.equals("run " + PG + " " + COUNT))
            .count());

    // Connect a second SQL connection: chooser opens again with last target (shop-prod) selected.
    c.run(
        app,
        open("inventory") + SETTLE + "press #sql\nframe\nkey Enter control\n" + SHOWN
            + "snap two\n");
    var two = c.tree("two");
    assertTrue(two.contains("role=menu label=\"Run on\""), two);
    assertEquals(List.of("shop-prod", "inventory"), items(two), two);
    assertTrue(line(two, "role=menuitem label=\"shop-prod\"").contains("checked"), two);

    // The last target disconnects: it stays first, so Enter does not move the query to inventory.
    c.run(
        app,
        "key Escape\n" + SHOWN + menu("shop-prod", "Disconnect") + SHOWN + SETTLE
            + "press #sql\nframe\nkey Enter control\n" + SHOWN + "snap gone\n");
    var gone = c.tree("gone");
    assertEquals(List.of("shop-prod", "inventory"), items(gone), gone);
    assertTrue(item(gone, "shop-prod").contains("Not connected"), gone);
  }

  @Test
  void aMarkerRunsNothingWhereTheChosenDialectStartsNoStatement() throws Exception {
    var q = query("Notes", "# Report notes;\nSELECT 2;", Workspace.Language.SQL);
    var app = c.app(new Workspace(List.of(INVENTORY, q)));
    c.ready(app, INVENTORY);
    // Before a target the markers split as PostgreSQL does; on MariaDB the first line is a comment.
    c.run(
        app,
        START + open("inventory") + SETTLE
            + "press role=treeitem label=\"Notes\"\nkey Enter\nframe\nframe\nsnap opened\n"
            + "press 366,120\n" + SHOWN + "key Enter\n" + SHOWN
            + SETTLE + "snap marker\n");
    // The first marker stands on the comment's line: it ran nothing, and SELECT 2 stayed unsent.
    assertTrue(line(c.tree("opened"), "#marker-1").contains("360,114"), c.tree("opened"));
    assertFalse(
        k.servers.calls.stream().anyMatch(s -> s.startsWith("run " + MARIA)),
        k.servers.calls.toString());
  }

  @Test
  void choosingADisconnectedTargetConnectsItThenRuns() throws Exception {
    var shop = SHOP.withColor(Color.RED);
    var q = query("Orders", COUNT, Workspace.Language.SQL);
    var app = c.app(new Workspace(List.of(shop, q)));
    c.ready(app, shop);
    c.run(
        app,
        START + "press role=treeitem label=\"Orders\"\nkey Enter\nframe\n"
            + "press role=button label=\"Run\"\n" + SHOWN + "snap ask\n"
            + "key Enter\n" + SHOWN + SETTLE + "snap ran\n");
    assertTrue(c.tree("ask").contains("role=menu label=\"Run on\""), c.tree("ask"));
    assertTrue(app.state().catalog.connected(shop.id()));
    assertTrue(k.servers.calls.contains("run " + PG + " " + COUNT), k.servers.calls.toString());
    assertTrue(tabs(c.tree("ran")).contains("Result 1"), tabs(c.tree("ran")));
  }

  @Test
  void switchingTargetAsksWithOpenTransactionClearsPinnedResultsAndResetsDatabase()
      throws Exception {
    var shop = SHOP.withColor(Color.RED);
    var inv = INVENTORY.withColor(Color.GREEN);
    var q = query("Work", COUNT + ";\n" + PAY + ";", Workspace.Language.SQL);
    var app = c.app(new Workspace(List.of(shop, inv, q)));
    c.ready(app, shop, inv);
    // Connect both so chooser asks on each run, open query, switch to Manual, run COUNT and pin it,
    // then run PAY to open a transaction on shop-prod.
    c.run(
        app,
        START + open("shop-prod") + SETTLE + open("inventory") + SETTLE
            + "press role=treeitem label=\"Work\"\nkey Enter\nframe\n"
            + MANUAL
            + "press #sql\nkey Home control\nkey Enter control\n" + SHOWN
            + "key Enter\n" + SHOWN + SETTLE
            + "press #pin\nframe\n"
            + "press #sql\nkey End control\nkey Enter control\n" + SHOWN
            + "key Enter\n" + SHOWN + SETTLE + "snap openTx\n");
    var tab = app.state().queryTab(q.id());
    assertNotNull(tab);
    assertEquals(shop.id(), tab.id);
    assertEquals("shop", tab.database);
    assertEquals(Transaction.OPEN, tab.transaction);
    assertEquals(2, tab.outputs.size(), "pinned Result 1 and Result 2");
    assertTrue(tab.outputs.getFirst().pinned);

    // Move caret back to COUNT, trigger Run, pick inventory (ArrowDown + Enter) -> Guard asks!
    c.run(
        app,
        "press #sql\nkey Home control\nkey Enter control\n" + SHOWN
            + "key ArrowDown\nframe\nkey Enter\n" + SHOWN + OPENED + "snap guard\n");
    var guard = c.tree("guard");
    assertTrue(guard.contains("Commit the transaction?"), guard);
    assertTrue(guard.contains("before it switches to inventory"), guard);

    // Cancel keeps old target, old session, and pinned result!
    c.run(app, "press #answer-cancel\n" + SHOWN + SETTLE);
    assertEquals(shop.id(), tab.id);
    assertEquals(Transaction.OPEN, tab.transaction);
    assertEquals(2, tab.outputs.size());

    // Now switch again and Rollback -> old session ends, ALL results (pinned too) cleared,
    // database resets to inventory's default, and COUNT runs on inventory!
    c.run(
        app,
        "press #sql\nkey Home control\nkey Enter control\n" + SHOWN
            + "key ArrowDown\nframe\nkey Enter\n" + SHOWN + OPENED
            + "press #answer-rollback\n" + SHOWN + SETTLE + "snap switched\n");
    assertEquals(inv.id(), tab.id);
    assertEquals("inventory", tab.database);
    assertEquals(1, tab.outputs.size(), "pinned result from shop-prod was cleared");
    assertFalse(tab.outputs.getFirst().pinned);
    assertTrue(k.servers.calls.contains("run " + MARIA + " " + COUNT), k.servers.calls.toString());
    var switched = c.tree("switched");
    assertTrue(line(switched, "role=tab label=\"Work\"").contains("value=\"green\""), switched);
    // History entries stayed tied to the connection each statement ran on.
    assertTrue(app.state().keeping.history.stream()
        .anyMatch(e -> e.connection().equals(shop.id()) && e.statement().equals(PAY)));
    assertTrue(app.state().keeping.history.stream()
        .anyMatch(e -> e.connection().equals(inv.id()) && e.statement().equals(COUNT)));
  }

  @Test
  void saveToWorkspaceCreatesAQueryInTheConnectionsFolder() throws Exception {
    var prod = folder("Production", true, SHOP);
    var app = c.app(new Workspace(List.of(prod)));
    c.ready(app, SHOP);
    c.run(
        app,
        START + menu("shop-prod", "New console") + SETTLE + type(ORDERS) + SAVE_TO_WORKSPACE
            + "snap asked\n" + nameQuery("All orders") + SETTLE + "snap saved\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Save to workspace\""), asked);
    assertTrue(line(asked, "#query-name").contains(ORDERS), asked);
    var ws = saved();
    var p = (Workspace.Folder) ws.entries().getFirst();
    assertEquals(1, p.queries().size());
    assertEquals("All orders", p.queries().getFirst().name());
    assertEquals(ORDERS, p.queries().getFirst().text());
    assertEquals(Workspace.Language.SQL, p.queries().getFirst().language());
    assertEquals(List.of("Production", "  shop-prod", "  All orders"), rows(c.tree("saved")));
  }

  @Test
  void deletingAFolderCountsItsQueriesAndClosesTheirTabs() throws Exception {
    var q = query("Report", COUNT, Workspace.Language.SQL);
    var reports = folder("Reports", true, SHOP, q);
    var app = c.app(new Workspace(List.of(reports)));
    c.run(
        app,
        START + "press role=treeitem label=\"Report\"\nkey Enter\nframe\n"
            + menu("Reports", "Delete…") + "snap asked\n" + DELETE_CONTENTS + SETTLE
            + "snap end\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("It holds 1 connection and 1 query, its folders included."), asked);
    assertEquals("", tabs(c.tree("end")));
    assertNull(app.state().queryTab(q.id()));
    assertEquals(Workspace.EMPTY, saved());
  }

  @Test
  void gutterMarkerAsksAndDragMovesQuery() throws Exception {
    var shop = SHOP.withColor(Color.RED);
    var inv = INVENTORY.withColor(Color.GREEN);
    var prod = folder("Production", true, shop);
    var staging = folder("Staging", true, inv);
    var q = query("Checks", COUNT, Workspace.Language.SQL);
    var app = c.app(new Workspace(List.of(prod, staging, q)));
    c.ready(app, shop, inv);
    // Drag query into Staging, open it, trigger via gutter marker, run on the first.
    c.run(
        app,
        START + drag("Checks", into("Staging"), "dragging") + "snap dragged\n"
            + "press role=treeitem label=\"Checks\"\nkey Enter\nframe\n"
            + "press role=button label=\"Run statement\"\n" + SHOWN + "snap gutter\n"
            + "key Enter\n" + SHOWN + SETTLE + "snap ran\n");
    assertEquals(
        List.of("Production", "  shop-prod", "Staging", "  inventory", "  Checks"),
        rows(c.tree("dragged")));
    assertTrue(c.tree("gutter").contains("role=menu label=\"Run on\""), c.tree("gutter"));
    assertEquals(List.of("shop-prod", "inventory"), items(c.tree("gutter")), c.tree("gutter"));
    assertEquals(shop.id(), app.state().queryTab(q.id()).id);
    assertTrue(k.servers.calls.contains("run " + PG + " " + COUNT), k.servers.calls.toString());

    // Deleting the target connection keeps the query tab open, detaches its target, clears results.
    c.run(
        app,
        menu("shop-prod", "Delete…") + "press role=button label=\"Disconnect\"\n" + SHOWN + SETTLE
            + "press role=button label=\"Delete\"\n" + SHOWN + SETTLE + "snap detached\n");
    var tab = app.state().queryTab(q.id());
    assertNotNull(tab, "query tab stays open when target connection is deleted");
    assertNull(tab.id);
    assertEquals("", tab.database);
    assertTrue(tab.outputs.isEmpty());
  }
  /** The labels of the Run on menu's items, in their order. */
  private static List<String> items(String tree) {
    return ConnectionsTest.after(tree, "role=menu label=\"Run on\"")
        .lines()
        .filter(l -> l.contains("role=menuitem"))
        .map(l -> l.replaceAll(".*role=menuitem label=\"([^\"]*)\".*", "$1"))
        .toList();
  }

  /** The lines of the menu item with the label, to the next item. */
  private static String item(String tree, String label) {
    return ConnectionsTest.after(tree, "role=menuitem label=\"" + label + "\"");
  }
}

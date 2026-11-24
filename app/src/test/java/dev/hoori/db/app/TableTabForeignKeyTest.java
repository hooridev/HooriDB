package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTransactions.OPENED;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Following foreign keys from a SQL table tab's grid context menu: outgoing, incoming, composite,
 * cross-database, multiple keys on one column, disabled states, reused tabs with pending edits,
 * kept ORDER BY, and self-references.
 */
class TableTabForeignKeyTest {

  private static final String SHOWN = "frame\nadvance 1\nframe\n";
  private static final String ORDERS = PG + "/shop.public.orders";
  private static final String CUSTOMERS = PG + "/shop.public.customers";
  private static final String REGIONS = PG + "/shop.billing.regions";
  private static final String ORDER_ITEMS = PG + "/shop.public.order_items";
  private static final String NOTES = PG + "/shop.public.notes";
  private static final String TAGS = PG + "/shop.public.tags";

  private static final String FK_OUTLINE = """
      shop
        public
          customers
            *id: integer
            name: text
          partners
            *id: integer
            name: text
          orders
            *id: bigint
            total: numeric(12,2)
            customer_id: integer > customers.id on delete cascade
            parent_id: bigint > orders.id on update set null
            region_a: integer > region_fk: billing.regions.a on delete restrict
            region_b: text > region_fk: billing.regions.b
            dual_id: integer > fk_cust: customers.id > fk_part: partners.id
          order_items
            *order_id: bigint > orders.id on delete cascade
            *line: integer
          notes
            *id: integer
            tag_code: text > tags.code
            raw_key: bytea > blobs.k
          tags
            *code: text
            label: text
          blobs
            *k: bytea
            note: text
        billing
          regions
            *a: integer
            *b: text
            label: text
      """;

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
    servers.serve(PG, Provider.POSTGRESQL, "16.4", FK_OUTLINE);
    servers.values(CUSTOMERS, List.of(List.of(10L, "Alice"), List.of(20L, "Bob")));
    servers.values(CUSTOMERS + " WHERE \"id\" = 10", List.of(List.of(10L, "Alice")));
    servers.values(CUSTOMERS + " WHERE \"id\" = 20", List.of(List.of(20L, "Bob")));
    servers.values(
        CUSTOMERS + " WHERE \"id\" = 20 ORDER BY \"name\" DESC", List.of(List.of(20L, "Bob")));
    servers.values(
        CUSTOMERS + " ORDER BY \"name\" DESC", List.of(List.of(20L, "Bob"), List.of(10L, "Alice")));
    servers.values(
        ORDERS,
        List.of(
            Arrays.asList(1L, "50.00", 10L, 2L, 100L, "eu", 20L),
            Arrays.asList(2L, "25.00", null, null, 100L, null, 10L)));
    servers.values(
        ORDERS + " WHERE \"id\" = 2",
        List.of(Arrays.asList(2L, "25.00", null, null, 100L, null, 10L)));
    servers.values(
        ORDERS + " WHERE \"parent_id\" = 1",
        List.of(Arrays.asList(3L, "10.00", 10L, 1L, 100L, "eu", 10L)));
    servers.values(
        REGIONS + " WHERE \"a\" = 100 AND \"b\" = 'eu'", List.of(List.of(100L, "eu", "Europe")));
    servers.values(
        ORDERS + " WHERE \"region_a\" = 100 AND \"region_b\" = 'eu'",
        List.of(Arrays.asList(1L, "50.00", 10L, 2L, 100L, "eu", 20L)));
    servers.values(
        ORDER_ITEMS + " WHERE \"order_id\" = 1", List.of(List.of(1L, 1L), List.of(1L, 2L)));
    servers.values(
        NOTES,
        List.of(
            Arrays.asList(1L, "vip", new Binary(new byte[] {0x01, 0x02}, 2)),
            Arrays.asList(
                2L,
                new Cut("x".repeat(1024), OptionalLong.of(2000)),
                new Binary(new byte[] {0x01}, 2000))));
    servers.values(TAGS + " WHERE \"code\" = 'vip'", List.of(List.of("vip", "VIP")));
  }

  @AfterEach
  void tearDown() throws Exception {
    t.tearDown();
  }

  private App<State> openOrders() throws Exception {
    var app = t.shop();
    c.run(app, open("orders") + SETTLE + table("orders") + SETTLE);
    return app;
  }

  private static String rightClick(int row, int col) {
    return "press #cell-" + row + "-" + col + " right\n" + SHOWN;
  }

  private TableTab tabNamed(App<State> app, String tableName) {
    return app.state().tables(null).stream()
        .filter(x -> x.table.name().equals(tableName))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void contextMenuShowsOutgoingAndIncomingKeysAndReadsMissingKeysOnDemand() throws Exception {
    // Open orders WITHOUT expanding it in the tree: opening the tab does not read foreign keys.
    var app = t.shop();
    c.run(app, table("orders") + SETTLE);
    assertFalse(
        servers.calls.stream().anyMatch(call -> call.startsWith("foreign keys ")),
        servers.calls.toString());

    // Right-clicking customer_id (col 2) triggers reading foreign keys on demand; once settled,
    // both "Go to customers" and "Referenced by" appear in the open menu.
    c.run(app, rightClick(0, 2) + SETTLE + "snap key-col\n");
    String keyCol = c.tree("key-col");
    assertTrue(servers.calls.contains("foreign keys " + ORDERS), servers.calls.toString());
    assertTrue(keyCol.contains("role=menuitem label=\"Go to customers\""), keyCol);
    assertFalse(
        line(keyCol, "role=menuitem label=\"Go to customers\"").contains("disabled"), keyCol);
    assertTrue(keyCol.contains("role=menuitem label=\"Referenced by\""), keyCol);
    assertTrue(keyCol.contains("role=menuitem label=\"Refresh\""), keyCol);

    // Open Referenced by submenu: shows both incoming keys with referencing columns.
    c.run(app, "press role=menuitem label=\"Referenced by\"\n" + SHOWN + "snap incoming\n");
    String incoming = c.tree("incoming");
    assertTrue(incoming.contains("role=menuitem label=\"order_items (order_id → id)\""), incoming);
    assertTrue(incoming.contains("role=menuitem label=\"orders (parent_id → id)\""), incoming);

    // Choose order_items (order_id → id): opens order_items tab filtered to "order_id" = 1.
    c.run(
        app,
        "press role=menuitem label=\"order_items (order_id → id)\"\n" + SHOWN + SETTLE
            + "snap items-tab\n");
    String itemsTree = c.tree("items-tab");
    assertTrue(line(itemsTree, "role=tab label=\"order_items\"").contains("selected"), itemsTree);
    assertEquals("\"order_id\" = 1", tabNamed(app, "order_items").filter.where());
    assertTrue(
        servers.calls.contains("read " + ORDER_ITEMS + " 0-200 WHERE \"order_id\" = 1"),
        servers.calls.toString());
    assertFalse(
        servers.calls.contains("read " + ORDER_ITEMS + " 0-200"),
        "must not read unfiltered first: " + servers.calls);

    // Back to orders, right-click non-FK column `total` (col 1): no Go to, still has Referenced by.
    c.run(app, "press role=tab label=\"orders\"\n" + SHOWN + rightClick(0, 1) + "snap plain-col\n");
    String plain = c.tree("plain-col");
    assertFalse(plain.contains("label=\"Go to"), plain);
    assertTrue(plain.contains("role=menuitem label=\"Referenced by\""), plain);

    // Rows 0 and 1 selected, the active cell in row 1: a press on row 0 follows neither row.
    c.run(
        app,
        "key Escape\n" + SHOWN + "press #cell-0-2\nframe\npress #cell-1-2 shift\nframe\n"
            + rightClick(0, 2) + "snap two-rows\n");
    String twoRows = c.tree("two-rows");
    assertFalse(twoRows.contains("label=\"Go to"), twoRows);
    assertFalse(twoRows.contains("label=\"Referenced by\""), twoRows);
    assertTrue(twoRows.contains("role=menuitem label=\"Refresh\""), twoRows);

    // Row 1 deselected again, the active cell stays there: a press on row 0 still follows neither.
    c.run(
        app,
        "key Escape\n" + SHOWN + "press #cell-1-2 control\nframe\n" + rightClick(0, 2)
            + "snap deselected\n");
    assertFalse(c.tree("deselected").contains("label=\"Go to"), c.tree("deselected"));

    // Nothing selected, a press on a row's number: the menu has Refresh alone.
    c.run(app, "key Escape\n" + SHOWN);
    tabNamed(app, "orders").selection.clear();
    c.run(app, "press #number-0 right\n" + SHOWN + "snap no-cell\n");
    assertFalse(c.tree("no-cell").contains("label=\"Go to"), c.tree("no-cell"));
    assertTrue(c.tree("no-cell").contains("role=menuitem label=\"Refresh\""), c.tree("no-cell"));

    // Right-click `dual_id` (col 6, in TWO outgoing FKs): shows "Go to" submenu with both targets.
    c.run(app, "key Escape\n" + SHOWN + rightClick(0, 6) + "snap multi-fk\n");
    String multi = c.tree("multi-fk");
    assertTrue(multi.contains("role=menuitem label=\"Go to\""), multi);
    c.run(app, "press role=menuitem label=\"Go to\"\n" + SHOWN + "snap multi-sub\n");
    String multiSub = c.tree("multi-sub");
    assertTrue(multiSub.contains("role=menuitem label=\"customers (dual_id → id)\""), multiSub);
    assertTrue(multiSub.contains("role=menuitem label=\"partners (dual_id → id)\""), multiSub);

    // Choose customers (dual_id → id) from the Go to submenu: opens customers filtered to "id" =
    // 20.
    c.run(
        app,
        "press role=menuitem label=\"customers (dual_id → id)\"\n" + SHOWN + SETTLE
            + "snap cust-tab\n");
    assertEquals("\"id\" = 20", tabNamed(app, "customers").filter.where());
    assertTrue(cell(c.tree("cust-tab"), 0, 1).contains("\"Bob\""), c.tree("cust-tab"));
  }

  @Test
  void compositeAndCrossDatabaseForeignKeysFilterBothDirections() throws Exception {
    // 1. Composite cross-schema FK on PostgreSQL: (region_a, region_b) -> billing.regions(a, b).
    var app = openOrders();
    c.run(app, rightClick(0, 4) + "snap comp-menu\n");
    String compMenu = c.tree("comp-menu");
    assertTrue(compMenu.contains("role=menuitem label=\"Go to billing.regions\""), compMenu);
    c.run(
        app,
        "press role=menuitem label=\"Go to billing.regions\"\n" + SHOWN + SETTLE
            + "snap regions-tab\n");
    String regionsTree = c.tree("regions-tab");
    assertTrue(line(regionsTree, "role=tab label=\"regions\"").contains("selected"), regionsTree);
    assertEquals("\"a\" = 100 AND \"b\" = 'eu'", tabNamed(app, "regions").filter.where());
    assertTrue(cell(regionsTree, 0, 2).contains("\"Europe\""), regionsTree);

    // Follow Referenced by back from billing.regions to public.orders (composite incoming key).
    c.run(
        app,
        rightClick(0, 0) + SETTLE + "press role=menuitem label=\"Referenced by\"\n" + SHOWN
            + "snap rev-comp\n");
    String revComp = c.tree("rev-comp");
    assertTrue(
        revComp.contains("role=menuitem label=\"public.orders (region_a, region_b → a, b)\""),
        revComp);
    c.run(
        app,
        "press role=menuitem label=\"public.orders (region_a, region_b → a, b)\"\n" + SHOWN + SETTLE
            + "snap back-orders\n");
    assertTrue(
        line(c.tree("back-orders"), "role=tab label=\"orders\"").contains("selected"),
        c.tree("back-orders"));
    assertEquals(
        "\"region_a\" = 100 AND \"region_b\" = 'eu'",
        tabNamed(app, "orders").filter.where());

    // 2. Cross-database FK on MariaDB: shop.orders.account_id -> billing.accounts.id.
    servers.serve(MARIA, Provider.MARIADB, "11.4.2-MariaDB", """
        shop
          orders
            *id: int
            account_id: int > billing.accounts.id
        billing
          accounts
            *id: int
            owner: varchar(64)
        """);
    String mariaOrders = MARIA + "/shop.orders";
    String mariaAccounts = MARIA + "/billing.accounts";
    servers.values(mariaOrders, List.of(List.of(1L, 7L)));
    servers.values(mariaAccounts + " WHERE `id` = 7", List.of(List.of(7L, "Acme")));
    servers.values(mariaOrders + " WHERE `account_id` = 7", List.of(List.of(1L, 7L)));

    var maria = c.app(INVENTORY);
    c.ready(maria, INVENTORY);
    c.run(
        maria,
        START
            + open("inventory")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("orders")
            + SETTLE
            + table("orders")
            + SETTLE
            + rightClick(0, 1)
            + "snap maria-out\n");
    String mariaOut = c.tree("maria-out");
    assertTrue(mariaOut.contains("role=menuitem label=\"Go to billing.accounts\""), mariaOut);
    c.run(
        maria,
        "press role=menuitem label=\"Go to billing.accounts\"\n" + SHOWN + SETTLE
            + "snap maria-acc\n");
    assertEquals("`id` = 7", tabNamed(maria, "accounts").filter.where());
    assertTrue(cell(c.tree("maria-acc"), 0, 1).contains("\"Acme\""), c.tree("maria-acc"));

    // Incoming cross-database key from billing.accounts back to shop.orders.
    c.run(
        maria,
        rightClick(0, 0) + SETTLE + "press role=menuitem label=\"Referenced by\"\n" + SHOWN
            + "snap maria-in\n");
    assertTrue(
        c.tree("maria-in").contains("role=menuitem label=\"shop.orders (account_id → id)\""),
        c.tree("maria-in"));
    c.run(maria, "press role=menuitem label=\"shop.orders (account_id → id)\"\n" + SHOWN + SETTLE);
    assertEquals("`account_id` = 7", tabNamed(maria, "orders").filter.where());
  }

  @Test
  void itemsAreDisabledForNullCutValuesAndPendingEdits() throws Exception {
    var app = openOrders();

    // Row 1: customer_id (col 2) is NULL -> Go to customers disabled.
    c.run(app, rightClick(1, 2) + "snap null-key\n");
    assertTrue(
        line(c.tree("null-key"), "role=menuitem label=\"Go to customers\"").contains("disabled"),
        c.tree("null-key"));

    // Row 1: composite region_fk has region_a=100, region_b=NULL -> disabled even from region_a.
    c.run(app, "key Escape\n" + SHOWN + rightClick(1, 4) + "snap null-comp\n");
    assertTrue(
        line(c.tree("null-comp"), "role=menuitem label=\"Go to billing.regions\"")
            .contains("disabled"),
        c.tree("null-comp"));

    // Pending edit on non-key column `total` (row 0, col 1) leaves Go to customers enabled.
    c.run(app, "key Escape\n" + SHOWN + "press #cell-0-1\ntype \"99.00\"\nkey Enter\nframe\n");
    c.run(app, rightClick(0, 2) + "snap other-edited\n");
    assertFalse(
        line(c.tree("other-edited"), "role=menuitem label=\"Go to customers\"")
            .contains("disabled"),
        c.tree("other-edited"));

    // Pending edit on `customer_id` itself (row 0, col 2) disables Go to customers.
    c.run(
        app,
        "key Escape\n" + SHOWN + "press #cell-0-2\ntype \"20\"\nkey Enter\nframe\n"
            + rightClick(0, 2) + "snap key-edited\n");
    assertTrue(
        line(c.tree("key-edited"), "role=menuitem label=\"Go to customers\"").contains("disabled"),
        c.tree("key-edited"));

    // Pending edit on `id` (row 0, col 0) disables incoming Referenced by items for this row.
    c.run(
        app,
        "key Escape\n" + SHOWN + "press #cell-0-0\ntype \"99\"\nkey Enter\nframe\n"
            + rightClick(0, 0) + "press role=menuitem label=\"Referenced by\"\n" + SHOWN
            + "snap pk-edited\n");
    assertTrue(
        line(c.tree("pk-edited"), "role=menuitem label=\"order_items (order_id → id)\"")
            .contains("disabled"),
        c.tree("pk-edited"));

    // Staged new row (Insert): Go to customers is disabled.
    c.run(
        app,
        "key Escape\n" + SHOWN + "press #cell-0-2\nkey Insert\nframe\n" + rightClick(1, 2)
            + "snap new-row\n");
    assertTrue(
        line(c.tree("new-row"), "role=menuitem label=\"Go to customers\"").contains("disabled"),
        c.tree("new-row"));

    // Cut text and cut binary in `notes`: cut values disabled, whole values enabled.
    c.run(app, "key Escape\n" + SHOWN + table("notes") + SETTLE);
    c.run(app, rightClick(1, 1) + SETTLE + "snap cut-text\n");
    assertTrue(
        line(c.tree("cut-text"), "role=menuitem label=\"Go to tags\"").contains("disabled"),
        c.tree("cut-text"));
    c.run(app, "key Escape\n" + SHOWN + rightClick(1, 2) + "snap cut-bin\n");
    assertTrue(
        line(c.tree("cut-bin"), "role=menuitem label=\"Go to blobs\"").contains("disabled"),
        c.tree("cut-bin"));
    c.run(app, "key Escape\n" + SHOWN + rightClick(0, 2) + "snap whole-bin\n");
    assertFalse(
        line(c.tree("whole-bin"), "role=menuitem label=\"Go to blobs\"").contains("disabled"),
        c.tree("whole-bin"));
  }

  @Test
  void openTargetTabIsReusedKeepsOrderByAndAsksAboutPendingEdits() throws Exception {
    var app = openOrders();
    // Open customers, sort by name DESC, and stage an uncommitted edit on row 0.
    c.run(app, table("customers") + SETTLE);
    c.run(
        app,
        "press role=textbox label=\"ORDER BY\"\nclipboard \"\\\"name\\\" DESC\"\nkey V control\n"
            + "key Enter\nframe\n" + SETTLE
            + "press #cell-0-1\ntype \"Bobby\"\nkey Enter\nframe\n");
    assertEquals("\"name\" DESC", tabNamed(app, "customers").filter.order());
    assertEquals(1, tabNamed(app, "customers").pendingChanges());

    // Switch to orders and follow dual_id = 20 -> customers (dual_id → id).
    c.run(
        app,
        "press role=tab label=\"orders\"\n" + SHOWN + rightClick(0, 6)
            + "press role=menuitem label=\"Go to\"\n" + SHOWN
            + "press role=menuitem label=\"customers (dual_id → id)\"\n" + SHOWN + OPENED
            + "snap asked\n");
    String asked = c.tree("asked");
    assertEquals(2, app.state().tables(null).size(), "must reuse existing customers tab");
    assertTrue(line(asked, "role=tab label=\"customers\"").contains("selected"), asked);
    assertTrue(asked.contains("role=dialog label=\"Discard 1 change?\""), asked);

    // Cancel keeps the pending edit and leaves WHERE untouched.
    c.run(app, "press #answer-cancel\n" + SHOWN + OPENED + SETTLE);
    assertEquals(1, tabNamed(app, "customers").pendingChanges());
    assertEquals("", tabNamed(app, "customers").filter.where());
    assertEquals("\"name\" DESC", tabNamed(app, "customers").filter.order());

    // Follow again and Discard: drops edit, applies WHERE "id" = 20, and keeps ORDER BY.
    c.run(
        app,
        "press role=tab label=\"orders\"\n" + SHOWN + rightClick(0, 6)
            + "press role=menuitem label=\"Go to\"\n" + SHOWN
            + "press role=menuitem label=\"customers (dual_id → id)\"\n" + SHOWN + OPENED
            + "press #answer-discard\n" + SHOWN + OPENED + SETTLE + "snap reused\n");
    var customers = tabNamed(app, "customers");
    assertEquals(0, customers.pendingChanges());
    assertEquals("\"id\" = 20", customers.filter.where());
    assertEquals("\"name\" DESC", customers.filter.order());
    assertTrue(cell(c.tree("reused"), 0, 1).contains("\"Bob\""), c.tree("reused"));
  }

  @Test
  void selfReferenceFiltersItsOwnTabAfterPendingEditsQuestion() throws Exception {
    var app = openOrders();
    // Edit non-key column `total` (col 1) so the tab has a pending edit while parent_id is clean.
    c.run(app, "press #cell-0-1\ntype \"88.00\"\nkey Enter\nframe\n");

    // Follow self-reference on parent_id (col 3, value 2) -> asks before filtering orders itself.
    c.run(
        app,
        rightClick(0, 3) + "press role=menuitem label=\"Go to orders\"\n" + SHOWN + OPENED
            + "snap self-asked\n");
    String selfAsked = c.tree("self-asked");
    assertEquals(1, app.state().tables(null).size(), "must filter its own tab");
    assertTrue(selfAsked.contains("role=dialog label=\"Discard 1 change?\""), selfAsked);

    // Cancel keeps the edit and leaves orders unfiltered.
    c.run(app, "press #answer-cancel\n" + SHOWN + OPENED + SETTLE);
    assertEquals(1, tabNamed(app, "orders").pendingChanges());
    assertEquals("", tabNamed(app, "orders").filter.where());

    // Follow again and Discard: filters orders to "id" = 2.
    c.run(
        app,
        rightClick(0, 3) + "press role=menuitem label=\"Go to orders\"\n" + SHOWN + OPENED
            + "press #answer-discard\n" + SHOWN + OPENED + SETTLE + "snap self-filtered\n");
    assertEquals(1, app.state().tables(null).size());
    assertEquals(0, tabNamed(app, "orders").pendingChanges());
    assertEquals("\"id\" = 2", tabNamed(app, "orders").filter.where());
    assertTrue(cell(c.tree("self-filtered"), 0, 0).contains("\"2\""), c.tree("self-filtered"));
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.MonitorTabTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compare data: a table's rows compared with another's on sessions of the comparison's own, and
 * the target made equal to the source.
 */
class SyncTabTest {

  static final String HOST = "sync.example.com";
  static final String ORDERS = HOST + "/shop.public.orders";
  static final String COPY = HOST + "/shop.public.orders_copy";

  static final String OUTLINE = """
      shop
        public
          orders
            *id: bigint
            status: text
            total: numeric(12,2)
            note: text
          orders_copy
            *id: bigint
            status: text
            total: numeric(14,3)
            kept: text
          unkeyed
            id: bigint
            status: text
      """;

  static final ConnectionProfile SHOP = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("store")
      .withHost(HOST)
      .withDatabase("shop")
      .withUser("shop_app");

  static final String TAB = "Compare orders";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers.serve(HOST, Provider.POSTGRESQL, "16.4", OUTLINE);
    c.servers.values(
        ORDERS,
        List.of(
            row(1L, "new", "10.00", "a"),
            row(2L, "paid", "20.00", "b"),
            row(3L, "paid", "30.00", "c"),
            row(4L, "sent", "40.00", "d")));
    c.servers.values(
        COPY,
        List.of(
            row(2L, "paid", "20.000", "x"),
            row(3L, "new", "30.000", "y"),
            row(4L, "sent", "40", "z"),
            row(5L, "gone", "50.000", "w")));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  private static List<Object> row(long id, String status, String total, String text) {
    return Arrays.asList(id, status, new BigDecimal(total), text);
  }

  /** The app with the store connected, its public schema read, and orders compared with its copy. */
  App<State> compared(ConnectionProfile... profiles) throws Exception {
    var app = c.app(profiles);
    c.ready(app, profiles);
    c.run(app, START + open("store") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, menu("orders", SyncTab.COMPARE_DATA) + SETTLE);
    c.run(app, choose("store · shop.public.orders_copy") + SETTLE);
    return app;
  }

  static String choose(String target) {
    return """
        press role=combobox label="Target table"
        frame
        advance 1
        frame
        press role=option label="%s"
        frame
        advance 1
        frame
        """.formatted(target);
  }

  static final String COMPARE = "press role=button label=\"Compare\"\nframe\n" + SETTLE;

  @Test
  void aTablesMenuComparesItWithAnotherByTheKeyBothShare() throws Exception {
    var app = compared(SHOP);
    c.run(app, "snap paired\n");
    var paired = c.tree("paired");
    assertTrue(paired.contains("label=\"" + TAB + "\""), paired);
    assertTrue(
        paired.contains("Rows are paired by the primary key (id), and 3 columns of the same name"
            + " are compared. Only in the source, left out: note. Only in the target, kept as"
            + " they are: kept."),
        paired);

    c.run(app, COMPARE + "frame\nframe\nsnap compared\n");
    var tree = c.tree("compared");
    assertTrue(tree.contains("1 row only in the source"), tree);
    assertTrue(tree.contains("1 row only in the target"), tree);
    assertTrue(tree.contains("1 row different"), tree);
    // 20.00 and 20.000, 40.00 and 40 are the same number.
    assertTrue(tree.contains("2 rows equal"), tree);
    assertTrue(tree.contains("label=\"Differences\""), tree);
    assertTrue(tree.contains("Only in source"), tree);
    assertTrue(tree.contains("Only in target"), tree);
    assertTrue(tree.contains("4 rows in the source, 4 rows in the target"), tree);
    // Row by row in the key's order: a row that differs as the source's and the target's, with
    // the cell that differs marked.
    assertTrue(line(tree, "#row-0 role=row").contains("value=\"new\""), tree);
    assertTrue(line(tree, "#row-1 role=row").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#cell-1-2 ").contains("value=\"changed\""), tree);
    assertFalse(line(tree, "#cell-1-3 ").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#cell-2-2 ").contains("value=\"changed\""), tree);
    assertTrue(line(tree, "#row-3 role=row").contains("value=\"deleted\""), tree);

    // Both read on read-only sessions of the comparison's own, never the tree's first one.
    var scans = c.servers.linked.stream().filter(l -> l.contains(" scan ")).toList();
    assertFalse(scans.isEmpty());
    for (var scan : scans) {
      int link = Integer.parseInt(scan.substring(0, scan.indexOf(' ')));
      assertTrue(link > 1, scans.toString());
      assertTrue(c.servers.readOnly(link), scan);
    }
  }

  @Test
  void thePaletteComparesTheTableSelectedInTheTree() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("store") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, "press role=treeitem label=\"orders\"\nframe\n");
    c.run(
        app,
        PaletteTest.PALETTE + "type \"compare d\"\nframe\nkey Enter\n" + PaletteTest.SHOWN
            + "snap palette\n");
    var tree = c.tree("palette");
    assertTrue(tree.contains("label=\"" + TAB + "\""), tree);
    assertTrue(tree.contains("Choose the table to compare orders with."), tree);
  }

  @Test
  void applyingWritesTheChosenChangesInOneTransactionAndComparesAgain() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    // Without deleting the row only in the target.
    c.run(app, "press role=checkbox label=\"Delete rows only in the target\"\nframe\n");
    c.run(app, "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\nsnap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("2 changes will be written to orders_copy"), preview);
    assertTrue(preview.contains("UPDATE"), preview);
    assertTrue(preview.contains("INSERT INTO"), preview);
    assertFalse(preview.contains("DELETE FROM"), preview);

    c.run(app, "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n" + SETTLE);
    c.run(app, "frame\nframe\nsnap applied\n");
    var tree = c.tree("applied");
    assertTrue(tree.contains("Wrote 2 changes to orders_copy."), tree);
    assertTrue(tree.contains("1 row only in the target"), tree);
    assertTrue(tree.contains("4 rows equal"), tree);
    var rows = c.servers.rowsOf(COPY);
    assertEquals(5, rows.size());
    assertTrue(
        rows.contains(Arrays.asList(1L, "new", new BigDecimal("10.00"), null)), rows.toString());
    assertTrue(rows.contains(row(3L, "paid", "30.000", "y")), rows.toString());
    // Written on a session of its own, in one transaction.
    var writes = c.servers.linked.stream()
        .filter(l -> l.contains(" writer ") || l.contains(" batch ") || l.contains(" commit "))
        .toList();
    assertEquals(3, writes.size(), writes.toString());
    var link = writes.getFirst().substring(0, writes.getFirst().indexOf(' '));
    assertTrue(writes.stream().allMatch(w -> w.startsWith(link + " ")), writes.toString());
    assertFalse(c.servers.readOnly(Integer.parseInt(link)));
  }

  @Test
  void aReadOnlyTargetIsComparedButNotChanged() throws Exception {
    var app = compared(SHOP.withReadOnly(true));
    c.run(app, COMPARE + "snap read-only\n");
    var tree = c.tree("read-only");
    assertTrue(tree.contains("1 row different"), tree);
    assertTrue(line(tree, "label=\"Apply…\"").endsWith("disabled"), tree);
    assertTrue(c.servers.linked.stream().noneMatch(l -> l.contains(" writer ")));
  }

  @Test
  void tablesWithoutAKeyBothShareCannotBeCompared() throws Exception {
    var app = compared(SHOP);
    c.run(app, choose("store · shop.public.unkeyed") + SETTLE + "snap unpaired\n");
    var tree = c.tree("unpaired");
    assertTrue(tree.contains("These tables cannot be compared"), tree);
    assertTrue(line(tree, "label=\"Compare\"").endsWith("disabled"), tree);
  }

  @Test
  void theChangesCanBeSavedAsAScriptInstead() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var file = dir.resolve("orders.sql");
    c.run(
        app,
        "dialog \"" + file.toString().replace('\\', '/') + "\"\n"
            + "press role=button label=\"Save as script…\"\nframe\n" + SETTLE + "snap saved\n");
    assertTrue(c.tree("saved").contains("Saved the script to orders.sql."), c.tree("saved"));
    var script = Files.readString(file);
    assertTrue(script.startsWith("-- Makes the rows of \"public\".\"orders_copy\" equal"), script);
    assertTrue(script.contains("START TRANSACTION;\n"), script);
    assertTrue(script.contains("INSERT INTO \"public\".\"orders_copy\""), script);
    assertTrue(script.contains("DELETE FROM \"public\".\"orders_copy\""), script);
    assertTrue(script.endsWith("COMMIT;\n"), script);
    // Nothing was written to the table.
    assertEquals(4, c.servers.rowsOf(COPY).size());
  }

  @Test
  void cancellingTheWritingLeavesTheTargetAsItWas() throws Exception {
    var app = compared(SHOP);
    c.run(app, COMPARE);
    var gate = c.servers.hold("batch " + COPY);
    c.run(app, "press role=button label=\"Apply…\"\nframe\nadvance 1\nframe\n");
    c.run(app, "press role=button label=\"Apply\"\nframe\nadvance 1\nframe\n");
    CatalogTreeTest.eventually(() -> c.servers.calls.contains("batch " + COPY));
    c.run(app, "frame\npress role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    gate.countDown();
    var tree = c.tree("cancelled");
    assertTrue(tree.contains("Writing the changes was cancelled."), tree);
    assertEquals(4, c.servers.rowsOf(COPY).size());
    assertTrue(c.servers.calls.stream().noneMatch(l -> l.startsWith("commit ")));
  }
}

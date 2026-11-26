package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Export data… and Import data…: tables written into files and files read into a table. */
class TableFilesTest {

  private static final String CUSTOMERS = PG + "/shop.public.customers";
  private static final String ORDERS = PG + "/shop.public.orders";

  /** Lets a dialog that opened or closed, or a part of it that changed, show. */
  private static final String SHOWN = "frame\nadvance 1\nframe\n";

  @TempDir
  Path dir;

  private final TableTabTest t = new TableTabTest();
  private CatalogTreeTest c;
  private FakeServers servers;
  private Path out;

  @BeforeEach
  void setUp() throws Exception {
    t.dir = dir;
    t.setUp();
    c = t.c;
    servers = t.servers;
    servers.values(CUSTOMERS, List.of(List.of(1L, "Alice"), List.of(2L, "Bob, \"B\"")));
    out = Files.createDirectories(dir.resolve("files"));
  }

  @AfterEach
  void tearDown() throws Exception {
    t.tearDown();
  }

  // A path as a script takes it in quotes.
  private static String quoted(Path path) {
    return "\"" + path.toString().replace('\\', '/') + "\"";
  }

  private static String press(String key) {
    return "press #" + key + "\nframe\n";
  }

  private static String check(String label) {
    return "press role=checkbox label=\"" + label + "\"\nframe\n";
  }

  private static String choose(String select, String option) {
    return "press #" + select + "\n" + SHOWN + "press role=menuitem label=\"" + option + "\"\n"
        + SHOWN;
  }

  // Whether the checkbox of the label is checked.
  private static boolean checked(String tree, String label) {
    var line = line(tree, "role=checkbox label=\"" + label + "\"");
    return line.contains(" checked") && !line.contains("checked=false");
  }

  // The text a select shows, on the line after its own.
  private static String shown(String tree, String key) {
    var lines = tree.lines().toList();
    for (int i = 0; i < lines.size() - 1; i++) {
      if (lines.get(i).contains("#" + key + " ")) {
        return lines.get(i + 1);
      }
    }
    throw new AssertionError(key + " in\n" + tree);
  }

  private List<Path> files() throws Exception {
    try (Stream<Path> files = Files.list(out)) {
      return files.sorted().toList();
    }
  }

  private App<State> exporting(String row) throws Exception {
    var app = t.shop();
    c.run(app, menu(row, CatalogTree.EXPORT) + SETTLE + "snap opened\n");
    return app;
  }

  @Test
  void writesTheCheckedTablesOfASchemaEachIntoAFileOfTheFolder() throws Exception {
    var app = exporting("public");
    var opened = c.tree("opened");
    assertTrue(opened.contains("role=dialog label=\"Export data\""), opened);
    for (var table : List.of("orders", "kinds", "big", "customers", "logs")) {
      assertTrue(checked(opened, table), opened);
    }
    assertTrue(line(opened, "#export ").contains("disabled"), opened);

    c.run(
        app,
        press("tables-none") + check("customers") + check("orders") + "dialog " + quoted(out) + "\n"
            + press("choose-folder") + "await\nframe\nsnap ready\n");
    var ready = c.tree("ready");
    assertTrue(line(ready, "#folder").contains(out.getFileName().toString()), ready);
    assertTrue(ready.contains("Writes orders.csv, customers.csv."), ready);
    assertFalse(line(ready, "#export ").contains("disabled"), ready);

    c.run(app, press("export") + SETTLE + "snap done\n");
    var done = c.tree("done");
    assertTrue(line(done, "#export-outcome").contains("Exported"), done);
    assertTrue(done.contains("2 tables, 5 rows, written in"), done);
    assertEquals(List.of(out.resolve("customers.csv"), out.resolve("orders.csv")), files());
    assertEquals(
        "id,name\r\n1,Alice\r\n2,\"Bob, \"\"B\"\"\"\r\n",
        Files.readString(out.resolve("customers.csv")));
    var orders = Files.readAllLines(out.resolve("orders.csv"));
    assertEquals("id,customer_id,placed_at,total", orders.getFirst());
    assertEquals("1,1,2026-01-01T09:00:00+00:00,0.99", orders.get(1));

    // On a session of its own, read-only at the server.
    var export = servers.linked.stream()
        .filter(l -> l.endsWith("export " + CUSTOMERS))
        .findFirst()
        .orElseThrow();
    var link = Integer.parseInt(export.substring(0, export.indexOf(' ')));
    assertTrue(servers.readOnly(link), servers.linked.toString());
    assertTrue(
        servers.linked.stream().anyMatch(l -> l.equals(link + " tables " + PG + "/shop.public")),
        servers.linked.toString());

    c.run(app, press("export-close") + SHOWN + SETTLE + "snap closed\n");
    assertFalse(c.tree("closed").contains("label=\"Export data\""), c.tree("closed"));
  }

  @Test
  void writesTheChosenColumnsOfOneTableAsJsonLines() throws Exception {
    var app = exporting("customers");
    var opened = c.tree("opened");
    assertTrue(checked(opened, "customers"), opened);
    assertFalse(checked(opened, "orders"), opened);
    assertTrue(checked(opened, "id"), opened);

    c.run(
        app,
        check("id") + choose("format", "JSON Lines") + "dialog " + quoted(out) + "\n"
            + press("choose-folder") + "await\nframe\n" + press("export") + SETTLE
            + "snap done\n");
    assertTrue(line(c.tree("done"), "#export-outcome").contains("Exported"), c.tree("done"));
    assertEquals(
        "{\"name\":\"Alice\"}\n{\"name\":\"Bob, \\\"B\\\"\"}\n",
        Files.readString(out.resolve("customers.jsonl")));
  }

  @Test
  void aCancelledExportLeavesNoFile() throws Exception {
    var gate = servers.hold("export " + CUSTOMERS);
    var app = exporting("customers");
    c.run(
        app,
        "dialog " + quoted(out) + "\n" + press("choose-folder") + "await\nframe\n" + press("export")
            + SHOWN + "snap running\n");
    var running = c.tree("running");
    assertTrue(running.contains("Exporting customers (1 of 1)"), running);
    assertTrue(line(running, "#export-cancel").contains("Cancel"), running);

    c.run(app, press("export-cancel") + SETTLE + "snap cancelled\n");
    gate.countDown();
    var cancelled = c.tree("cancelled");
    assertTrue(line(cancelled, "#export-outcome").contains("Export cancelled"), cancelled);
    assertTrue(servers.interrupted.contains("export " + CUSTOMERS), servers.interrupted.toString());
    CatalogTreeTest.eventually(() -> {
      try {
        return files().isEmpty();
      } catch (Exception e) {
        return false;
      }
    });
  }

  private App<State> importing(Path file, String steps) throws Exception {
    var app = t.shop();
    c.run(
        app,
        menu("customers", CatalogTree.IMPORT) + SETTLE + "dialog " + quoted(file) + "\n"
            + press("choose-file") + SETTLE + steps + "snap ready\n");
    return app;
  }

  @Test
  void readsAFileIntoTheTableAsItsPreviewAndMappingShowIt() throws Exception {
    var file = Files.writeString(out.resolve("people.csv"), "Name;ID\nCarol;7\nDan;8\n");
    var app = importing(file, choose("delimiter", "Semicolon") + SETTLE);
    var ready = c.tree("ready");
    assertTrue(ready.contains("role=dialog label=\"Import into customers\""), ready);
    // The preview reads the file as the options say, and maps its columns by their names.
    assertTrue(ready.contains("label=\"Preview of the file\""), ready);
    assertTrue(ready.contains("\"Carol\""), ready);
    assertTrue(shown(ready, "map-0").contains("\"ID\""), ready);
    assertTrue(shown(ready, "map-1").contains("\"Name\""), ready);

    c.run(
        app,
        "press role=radio label=\"Empty the table first\"\nframe\n" + press("import") + SETTLE
            + "snap done\n");
    var done = c.tree("done");
    assertTrue(line(done, "#import-outcome").contains("Imported"), done);
    assertTrue(done.contains("2 rows written into customers."), done);
    assertEquals(List.of(List.of(7L, "Carol"), List.of(8L, "Dan")), servers.rowsOf(CUSTOMERS));
    assertTrue(servers.calls.contains("import " + CUSTOMERS), servers.calls.toString());
  }

  @Test
  void skipsTheRowsThatFailAndListsThem() throws Exception {
    servers.refusesImported(CUSTOMERS, "Bob");
    var file = Files.writeString(out.resolve("people.csv"), "id,name\n3,Carol\n4,Bob\nx,Eve\n5\n");
    var app = importing(file, "press role=radio label=\"Skip the row and go on\"\nframe\n");
    c.run(app, press("import") + SETTLE + "snap done\n");
    var done = c.tree("done");
    assertTrue(done.contains("1 row written into customers, 3 rows skipped."), done);
    assertTrue(done.contains("label=\"Rows that failed\""), done);
    assertTrue(done.contains("duplicate key value"), done);
    assertTrue(done.contains("It has 1 value where 2 values come before"), done);
    assertEquals(3, servers.rowsOf(CUSTOMERS).size());
    assertEquals(List.of(3L, "Carol"), servers.rowsOf(CUSTOMERS).get(2));
  }

  @Test
  void stopsAtTheRowThatFailsAndKeepsNothingOfItsTransaction() throws Exception {
    servers.refusesImported(CUSTOMERS, "Bob");
    var file = Files.writeString(out.resolve("people.csv"), "id,name\n3,Carol\n4,Bob\n");
    var app = importing(file, "");
    c.run(app, press("import") + SETTLE + "snap done\n");
    var done = c.tree("done");
    assertTrue(line(done, "#import-outcome").contains("Stopped at line 3"), done);
    assertTrue(done.contains("Nothing was imported."), done);
    assertEquals(2, servers.rowsOf(CUSTOMERS).size());
  }

  @Test
  void aReadOnlyConnectionRefusesTheImportBeforeItStarts() throws Exception {
    var readOnly = SHOP.withReadOnly(true);
    var app = c.app(readOnly);
    c.ready(app, readOnly);
    c.run(
        app,
        CatalogTreeTest.START + CatalogTreeTest.open("shop-prod") + SETTLE
            + CatalogTreeTest.open("shop") + SETTLE + CatalogTreeTest.open("public") + SETTLE
            + menu("customers", CatalogTree.IMPORT) + SETTLE + "snap refused\n");
    var refused = c.tree("refused");
    assertTrue(line(refused, "#read-only").contains("The connection is read-only"), refused);
    assertFalse(refused.contains("#import "), refused);
    assertFalse(servers.calls.stream().anyMatch(call -> call.startsWith("import ")));
  }

  @Test
  void theFileMenuExportsAndImportsTheTableSelected() throws Exception {
    var app = t.shop();
    c.run(
        app,
        "press role=treeitem label=\"customers\"\nframe\n" + "press role=menuitem label=\"File\"\n"
            + SHOWN + "snap menu\n");
    var menu = c.tree("menu");
    assertFalse(line(menu, "label=\"" + CatalogTree.EXPORT + "\"").contains("disabled"), menu);
    assertFalse(line(menu, "label=\"" + CatalogTree.IMPORT + "\"").contains("disabled"), menu);
    c.run(
        app,
        "press role=menuitem label=\"" + CatalogTree.IMPORT + "\"\n" + SHOWN + SETTLE
            + "snap import\n");
    assertTrue(c.tree("import").contains("role=dialog label=\"Import into customers\""));
  }
}

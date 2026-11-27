package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.LEGACY;
import static dev.hoori.db.app.CatalogTreeTest.MYSQL;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.MonitorTabTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dump.Dump;
import dev.hoori.db.core.dump.Item;
import dev.hoori.db.core.dump.Listed;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.query.QueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Export to SQL: a schema's or database's objects, checked, written to a file chosen. */
class ExportDialogTest {

  static final String LIST = "export list " + PG + "/shop.public";
  static final String EXPORT = "export " + PG + "/shop";
  static final Schema PUBLIC = new Schema(new Database("shop", false), "public", false);

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers
        .rows(PG + "/shop.public.orders", 1200, OptionalLong.of(1000))
        .objects(
            PG,
            Listed.of(new Item(PUBLIC, Item.Kind.FUNCTION, "total_of", "integer", "")),
            Listed.of(new Item(PUBLIC, Item.Kind.TRIGGER, "stamp", "", "orders")));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with shop-prod connected, its database open, and the dialog of public open. */
  App<State> opened() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE);
    c.run(app, menu("public", "Export to SQL…") + SETTLE);
    return app;
  }

  static String choose(Path file) {
    return "dialog \"" + file.toString().replace("\\", "\\\\") + "\"\n"
        + "press role=button label=\"Choose the file\"\nframe\nawait\nframe\n";
  }

  static final String EXPORT_IT = "press role=button label=\"Export\"\nframe\n";

  @Test
  void aSchemasMenuListsItsObjectsAllCheckedOnASessionOfItsOwn() throws Exception {
    var app = opened();
    c.run(app, "snap opened\n");
    var tree = c.tree("opened");
    assertTrue(tree.contains("\"Export shop.public to SQL\""), tree);
    for (var name : List.of("customers", "orders", "order_totals", "total_of(integer)")) {
      assertTrue(line(tree, "role=checkbox label=\"" + name + "\"").contains("checked"), name);
      assertFalse(line(tree, "role=checkbox label=\"" + name + "\"").contains("checked=false"));
    }
    assertTrue(tree.contains("stamp on orders"), tree);
    // The tables' estimates, beside them and in all.
    assertTrue(tree.contains("~1,000 rows"), tree);
    assertTrue(line(tree, "#export-summary").contains("5 of 5 objects checked, 2 tables"), tree);
    // No file chosen yet: Export waits for one.
    assertTrue(line(tree, "role=button label=\"Export\"").contains("disabled"), tree);
    // Read on a connection of its own, behind the tree's, read-only at the server, so that what
    // the export reads, as a view or function that writes, cannot change it.
    assertTrue(c.servers.linked.contains("2 " + LIST), c.servers.linked.toString());
    assertTrue(c.servers.readOnly(2));
  }

  @Test
  void theCheckedObjectsGoToTheFileChosenAsChosen() throws Exception {
    var app = opened();
    var file = dir.resolve("public.sql");
    c.run(
        app,
        "press role=checkbox label=\"customers\"\nframe\n"
            + "press role=radio label=\"Structure only\"\nframe\n"
            + "press role=checkbox label=\"Drop each object first\"\nframe\n"
            + choose(file) + "snap chosen\n");
    var chosen = c.tree("chosen");
    assertTrue(line(chosen, "#export-summary").contains("4 of 5 objects checked, 1 table"));
    assertFalse(line(chosen, "role=button label=\"Export\"").contains("disabled"), chosen);
    c.run(app, EXPORT_IT + SETTLE + "snap done\n");
    var choice = c.servers.exported.getLast();
    assertEquals(Dump.Content.STRUCTURE, choice.content());
    assertTrue(choice.options().drop());
    assertEquals(
        List.of("orders", "order_totals", "total_of", "stamp"),
        choice.items().stream().map(Item::name).toList());
    assertTrue(Files.readString(file).contains("TABLE orders"), Files.readString(file));
    var done = c.tree("done");
    assertTrue(line(done, "#export-done").contains("Exported"), done);
    assertTrue(CatalogTreeTest.message(done).contains("Exported 4 objects to public.sql"), done);
  }

  @Test
  void allAndNoneCheckWhatTheFilterShowsAndExportWaitsForAnObject() throws Exception {
    var app = opened();
    c.run(
        app,
        "press role=button label=\"Check none of public\"\nframe\n" + choose(dir.resolve("x.sql"))
            + "snap none\n");
    var none = c.tree("none");
    assertTrue(line(none, "#export-summary").contains("0 of 5 objects checked"), none);
    assertTrue(line(none, "role=button label=\"Export\"").contains("disabled"), none);
    c.run(app, """
        press role=textbox label="Filter by name"
        type "ord"
        frame
        press role=button label="Check all of public"
        frame
        snap filtered
        """);
    var filtered = c.tree("filtered");
    assertFalse(filtered.contains("role=checkbox label=\"customers\""), filtered);
    assertTrue(line(filtered, "#export-summary").contains("2 of 5 objects checked, 1 table"));
  }

  @Test
  void aCancelledExportLeavesNoFileAndItsSessionClosesWithTheDialog() throws Exception {
    var app = opened();
    var file = dir.resolve("cancelled.sql");
    var gate = c.servers.hold(EXPORT);
    c.run(app, choose(file) + EXPORT_IT + "frame\nsnap running\n");
    var running = c.tree("running");
    assertTrue(line(running, "role=button label=\"Cancel\"").contains("button"), running);
    assertTrue(line(running, "role=button label=\"Export\"").contains("busy"), running);
    // Cancelled where it reached the server, not while it waits for the lane.
    CatalogTreeTest.eventually(() -> c.servers.calls.contains(EXPORT));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE + "snap cancelled\n");
    CatalogTreeTest.eventually(() -> c.servers.interrupted.contains(EXPORT));
    gate.countDown();
    var cancelled = c.tree("cancelled");
    assertTrue(line(cancelled, "#export-cancelled").contains("Cancelled"), cancelled);
    assertFalse(Files.exists(file));
    c.run(app, "press role=button label=\"Close\"\nframe\n" + SETTLE + "snap closed\n");
    assertFalse(c.tree("closed").contains("export-dialog"), c.tree("closed"));
    CatalogTreeTest.eventually(
        () -> c.servers.closed.stream().filter((PG + "/shop")::equals).count() >= 1);
  }

  @Test
  void aCancelThatGivesUpTheSessionLeavesTheNextExportANewOne() throws Exception {
    var app = opened();
    var file = dir.resolve("again.sql");
    // The cancel ends as core's does where it had to give up the connection.
    c.servers.fail(EXPORT, FakeServers.queryFailure(QueryException.Failure.ABORTED));
    var gate = c.servers.hold(EXPORT);
    c.run(app, choose(file) + EXPORT_IT + "frame\n");
    CatalogTreeTest.eventually(() -> c.servers.calls.contains(EXPORT));
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE);
    CatalogTreeTest.eventually(() -> c.servers.interrupted.contains(EXPORT));
    gate.countDown();
    c.servers.heal(EXPORT);
    c.run(app, "frame\n" + EXPORT_IT + SETTLE + "snap again\n");
    var again = c.tree("again");
    assertTrue(line(again, "#export-done").contains("Exported"), again);
    assertTrue(Files.exists(file));
    // On a session of its own again, the third connection, as the second was given up.
    assertTrue(c.servers.linked.contains("3 " + EXPORT), c.servers.linked.toString());
    CatalogTreeTest.eventually(() -> c.servers.closed.contains(PG + "/shop"));
  }

  @Test
  void aNameWithoutExtensionAsksBeforeTheFileOfItWithSqlIsReplaced() throws Exception {
    var app = opened();
    Files.writeString(dir.resolve("backup.sql"), "before");
    // The look for the file runs once the system's dialog has answered.
    c.run(app, choose(dir.resolve("backup")) + "await\nframe\nadvance 1\nframe\nsnap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("backup.sql exists already"), asked);
    assertTrue(line(asked, "role=button label=\"Export\"").contains("disabled"), asked);
    c.run(app, "press role=button label=\"Replace it\"\nframe\nadvance 1\nframe\nsnap let\n");
    var let = c.tree("let");
    assertFalse(let.contains("exists already"), let);
    assertFalse(line(let, "role=button label=\"Export\"").contains("disabled"), let);
    c.run(app, EXPORT_IT + SETTLE);
    assertTrue(Files.readString(dir.resolve("backup.sql")).startsWith("-- A made-up export"));
    // One that does not exist goes without a question.
    c.run(app, choose(dir.resolve("fresh")) + "await\nframe\nadvance 1\nframe\nsnap fresh\n");
    var fresh = c.tree("fresh");
    assertFalse(fresh.contains("exists already"), fresh);
    assertTrue(line(fresh, "#export-file").contains("fresh.sql"), fresh);
    assertFalse(line(fresh, "role=button label=\"Export\"").contains("disabled"), fresh);
  }

  @Test
  void dataOnlyWaitsForATableAndTheCalloutTellsWhatTheExportWrote() throws Exception {
    var app = opened();
    c.run(
        app,
        choose(dir.resolve("data.sql")) + EXPORT_IT + SETTLE
            + "press role=radio label=\"Data only, of the tables\"\nframe\n"
            + "press role=button label=\"Check none of public\"\nframe\n"
            + "press role=checkbox label=\"total_of(integer)\"\nframe\nadvance 1\nframe\n"
            + "snap none\n");
    var none = c.tree("none");
    assertTrue(line(none, "role=button label=\"Export\"").contains("disabled"), none);
    assertTrue(none.contains("none of them is checked"), none);
    c.run(
        app,
        "press role=radio label=\"Structure only\"\nframe\nadvance 1\nframe\nsnap structure\n");
    var structure = c.tree("structure");
    assertFalse(structure.contains("none of them is checked"), structure);
    // The export that ran wrote rows, whatever is chosen since.
    assertTrue(structure.contains(" rows to "), structure);
  }

  @Test
  void aSessionWhoseClosingFailsSaysSoInTheNotices() throws Exception {
    var app = opened();
    c.servers.failsAtClose(
        PG + "/shop", FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "it did not end"));
    c.run(app, "press role=button label=\"Close\"\nframe\n" + SETTLE + "snap closed\n");
    CatalogTreeTest.eventually(() -> !app.state().notices.isEmpty());
    var notice = app.state().notices.getFirst();
    assertEquals("shop-prod", notice.connection());
    assertTrue(notice.what().contains("Closing the export's session failed: it did not end"));
    assertTrue(notice.failure());
  }

  @Test
  void theFileMenuExportsTheSchemaOfAnyNodeUnderIt() throws Exception {
    c.servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogTreeTest.INDEX_OUTLINE);
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE
            + open("Indexes")
            + SETTLE);
    c.run(
        app,
        "press role=treeitem label=\"orders_pkey\"\nframe\n"
            + "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Export to SQL…\"\nframe\n" + SETTLE + "snap index\n");
    assertTrue(c.tree("index").contains("\"Export shop.public to SQL\""), c.tree("index"));
  }

  @Test
  void theFileMenuExportsTheDatabaseOfWhatIsSelected() throws Exception {
    var legacy = new Database("legacy", false);
    c.servers.objects(
        MYSQL, Listed.of(new Item(legacy, Item.Kind.TRIGGER, "audited", "", "accounts")));
    var app = c.app(LEGACY);
    c.ready(app, LEGACY);
    // The connection's row, then its database's of the same name below it.
    c.run(app, START + open("legacy") + SETTLE + "key ArrowDown\nkey ArrowRight\nframe\n" + SETTLE);
    c.run(
        app,
        "press role=treeitem label=\"accounts\"\nframe\n"
            + "press role=menuitem label=\"File\"\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Export to SQL…\"\nframe\n" + SETTLE + "snap mysql\n");
    var tree = c.tree("mysql");
    assertTrue(tree.contains("\"Export legacy to SQL\""), tree);
    assertTrue(tree.contains("role=checkbox label=\"Create the database\""), tree);
    // MariaDB and MySQL can check no foreign keys while the rows go.
    assertTrue(tree.contains("FOREIGN_KEY_CHECKS = 0"), tree);
    // Nor can they keep a table's triggers from firing for the data alone, which wait for it to
    // go with its structure, or not at all.
    c.run(
        app,
        choose(dir.resolve("legacy.sql"))
            + "press role=radio label=\"Data only, of the tables\"\nframe\nadvance 1\nframe\n"
            + "snap triggered\n");
    var triggered = c.tree("triggered");
    assertTrue(triggered.contains("#export-triggered"), triggered);
    assertTrue(triggered.contains("cannot keep the triggers of accounts from firing"), triggered);
    assertTrue(line(triggered, "role=button label=\"Export\"").contains("disabled"), triggered);
    c.run(app, "press role=checkbox label=\"accounts\"\nframe\nadvance 1\nframe\nsnap unchecked\n");
    var unchecked = c.tree("unchecked");
    assertFalse(unchecked.contains("export-triggered"), unchecked);
    // It was the only table, so that only the trigger is checked now, which has no rows.
    assertTrue(unchecked.contains("#export-no-data"), unchecked);
    assertTrue(
        c.servers.calls.contains("export list " + MYSQL + "/legacy"), c.servers.calls.toString());
  }
}

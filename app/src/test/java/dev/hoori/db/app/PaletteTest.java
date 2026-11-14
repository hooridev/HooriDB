package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.INVENTORY;
import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.CatalogueTest.START;
import static dev.hoori.db.app.CatalogueTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The command palette, Go to table and the shortcuts: everything from the keyboard. */
class PaletteTest {

  @TempDir
  Path dir;

  final CatalogueTest c = new CatalogueTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** After a key that opens or closes an overlay. */
  static final String SHOWN = "frame\nadvance 1\nframe\n";

  static final String PALETTE = "key P control shift\n" + SHOWN;
  static final String GO_TO = "key P control\n" + SHOWN;

  /** The app with shop's public schema and inventory's database read in the tree. */
  App<State> connected() throws Exception {
    return connected(SHOP, INVENTORY);
  }

  App<State> connected(ConnectionProfile shop, ConnectionProfile inventory) throws Exception {
    var app = c.app(shop, inventory);
    c.ready(app, shop, inventory);
    c.run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + open("inventory") + SETTLE + "key ArrowDown\nkey ArrowRight\nframe\n" + SETTLE);
    return app;
  }

  /** The palette's options, in order. */
  static List<String> options(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=option"))
        .map(l -> l.replaceAll(".*label=\"([^\"]*)\".*", "$1"))
        .toList();
  }

  /** The lines of the option with the label, to the next option. */
  static String option(String tree, String label) {
    return ConnectionsTest.after(tree, "role=option label=\"" + label + "\"");
  }

  @Test
  void theRankPutsTheNameItsStartWordsInitialsAndTheRestInThatOrder() {
    assertEquals(0, GoTo.rank("orders", "orders"));
    assertEquals(1, GoTo.rank("orders", "ord"));
    assertEquals(2, GoTo.rank("order_items", "item"));
    assertEquals(2, GoTo.rank("OrderItems", "items"));
    assertEquals(3, GoTo.rank("order_items", "oi"));
    assertEquals(3, GoTo.rank("OrderItems", "oi"));
    assertEquals(4, GoTo.rank("customers", "tom"));
    assertEquals(-1, GoTo.rank("customers", "oi"));
    assertEquals(0, GoTo.rank("customers", ""));
    // Lower-casing İ makes two characters of one: positions are those of the name as it is.
    assertEquals(3, GoTo.rank("İ_Order", "io"));
    assertEquals(2, GoTo.rank("İ_Order", "ord"));
    assertEquals(1, GoTo.rank("İ_Order", "i"));
  }

  @Test
  void thePaletteListsTheMenusCommandsWithTheirShortcutsAndRunsOne() throws Exception {
    var app = c.app(SHOP);
    c.run(app, START + PALETTE + "snap open\n");
    var open = c.tree("open");
    assertTrue(line(open, "role=dialog").contains("label=\"Command palette\""), open);
    var all = options(open);
    assertTrue(
        all.containsAll(List.of(
            "File: New connection…",
            "File: New console",
            "File: Close tab",
            "View: Command palette…",
            "View: Light theme",
            "Go: Go to table…",
            "Go: Next tab",
            "Go: Connections",
            "Go: Tab content")),
        all.toString());
    assertTrue(option(open, "Go: Go to table…").contains("\"Ctrl+P\""), open);
    assertTrue(option(open, "View: Command palette…").contains("\"Ctrl+Shift+P\""), open);
    // Disabled in the menu, disabled here: no tab is open, and nothing is selected for a console.
    assertTrue(line(open, "label=\"File: Close tab\"").contains("disabled"), open);
    assertTrue(line(open, "label=\"File: New console\"").contains("disabled"), open);
    assertFalse(line(open, "label=\"View: Light theme\"").contains("disabled"), open);
    c.run(app, "type \"light th\"\nframe\nkey Enter\n" + SHOWN + "snap ran\n");
    assertSame(Theme.LIGHT, app.state().theme);
    assertFalse(c.tree("ran").contains("label=\"Command palette\""), c.tree("ran"));
  }

  @Test
  void theViewMenuOpensThePaletteAndShowsItsKeys() throws Exception {
    var app = c.app(SHOP);
    c.run(
        app,
        START + "press role=menuitem label=\"Go\"\n" + SHOWN + "snap go\n"
            + "press role=menuitem label=\"View\"\n" + SHOWN
            + "press role=menuitem label=\"Command palette…\"\n" + SHOWN + "snap palette\n");
    var go = c.tree("go");
    assertTrue(line(go, "role=menuitem label=\"Next tab\"").contains("disabled"), go);
    assertTrue(
        ConnectionsTest.after(go, "role=menuitem label=\"Connections\"").contains("\"Ctrl+0\""),
        go);
    var palette = c.tree("palette");
    assertTrue(line(palette, "role=dialog").contains("label=\"Command palette\""), palette);
    assertTrue(line(palette, "role=textbox").contains("focused"), palette);
  }

  @Test
  void atOrdFindsOrdersFirstAndEnterOpensItsTab() throws Exception {
    var app = connected();
    c.run(
        app,
        GO_TO + "type \"ord\"\nframe\nsnap found\nkey Enter\n" + SHOWN + SETTLE + "snap end\n");
    var found = c.tree("found");
    assertEquals(List.of("orders", "order_totals"), options(found).subList(0, 2), found);
    assertTrue(option(found, "orders").contains("\"shop-prod · shop · public\""), found);
    assertTrue(line(found, "role=option label=\"orders\"").contains("selected"), found);
    assertEquals("orders", tabs(c.tree("end")));
    assertFalse(c.tree("end").contains("label=\"Command palette\""));
  }

  @Test
  void aSecondConnectionsTableIsFoundAndOpened() throws Exception {
    var app = connected();
    c.run(app, GO_TO + "type \"st\"\nframe\nsnap found\n");
    var found = c.tree("found");
    var all = options(found);
    // Its start, then a word's start, then anywhere: of both connections.
    assertEquals("stock", all.get(0), found);
    assertEquals("low_stock", all.get(1), found);
    assertTrue(all.contains("customers"), found);
    assertTrue(option(found, "stock").contains("\"inventory · inventory\""), found);
    c.run(app, "key Enter\n" + SHOWN + SETTLE + "snap end\n");
    assertEquals("stock", tabs(c.tree("end")));
    assertEquals(INVENTORY.id(), app.state().tables(null).getFirst().id);
  }

  @Test
  void aDatabaseNotReadYetSaysSoAndIsReadWhenChosen() throws Exception {
    var app = connected();
    var held = c.servers.hold("all tables " + PG + "/analytics");
    c.run(app, GO_TO + "type \"ev\"\nframe\nsnap unread\n");
    var unread = c.tree("unread");
    assertTrue(unread.contains("role=group label=\"" + GoTo.UNREAD + "\""), unread);
    assertTrue(
        option(unread, "analytics").contains("\"shop-prod · choose to read its tables\""), unread);
    assertTrue(option(unread, "postgres").contains("choose to read its tables"), unread);
    assertFalse(options(unread).contains("events"), unread);
    // The system's databases need no word.
    assertFalse(options(unread).contains("template0"), unread);
    c.run(app, "press role=option label=\"analytics\"\n" + SHOWN + "snap reading\n");
    var reading = c.tree("reading");
    assertTrue(line(reading, "role=dialog").contains("label=\"Command palette\""), reading);
    assertTrue(option(reading, "analytics").contains("reading its tables…"), reading);
    assertTrue(option(reading, "analytics").contains("label=\"Reading\""), "it waits: " + reading);
    assertTrue(line(reading, "role=option label=\"analytics\"").contains("disabled"), reading);
    held.countDown();
    c.run(app, SETTLE + "snap read\n");
    var read = c.tree("read");
    assertEquals("events", options(read).getFirst(), read);
    assertTrue(option(read, "events").contains("\"shop-prod · analytics · tracking\""), read);
    assertFalse(options(read).contains("analytics"), read);
    // The tree did not open it.
    assertFalse(
        app.state().catalogue.open.stream().anyMatch(n -> n.toString().contains("analytics")));
    c.run(app, "press role=option label=\"events\"\n" + SHOWN + SETTLE + "snap end\n");
    assertEquals("events", tabs(c.tree("end")));
  }

  @Test
  void withoutAnOpenConnectionThePaletteSaysWhereTablesComeFrom() throws Exception {
    var app = c.app(SHOP);
    c.run(app, START + GO_TO + "snap none\n");
    var none = c.tree("none");
    assertTrue(line(none, "label=\"No connection is open\"").contains("disabled"), none);
    assertTrue(none.contains("\"Open one in the tree to find its tables\""), none);
  }

  @Test
  void theShortcutsOpenCloseAndGoThroughTheTabsAndMoveTheFocus() throws Exception {
    var app = connected();
    c.run(
        app,
        "press role=treeitem label=\"shop-prod\"\nframe\nkey N control\nframe\nsnap console\n"
            + "key W control\nframe\nsnap closed\n"
            + GO_TO + "type \"orders\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + GO_TO + "type \"customers\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + GO_TO + "type \"items\"\nframe\nkey Enter\n" + SHOWN + SETTLE + "snap three\n"
            + "key PageDown control\nframe\nsnap next\n"
            + "key PageUp control\nframe\nsnap previous\n"
            + "key Tab control\nframe\nsnap tab\n"
            + "key 1 control\nframe\nsnap content\n"
            + "key 0 control\nframe\nsnap tree\n"
            + PALETTE + "type \"tab content\"\nframe\nkey Enter\n" + SHOWN + "snap from-palette\n");
    assertTrue(tabs(c.tree("console")).startsWith("shop-prod: shop"), c.tree("console"));
    assertEquals("", tabs(c.tree("closed")));
    assertEquals("orders,customers,items", tabs(c.tree("three")));
    assertEquals("items", active(c.tree("three")));
    assertEquals("orders", active(c.tree("next")));
    assertEquals("items", active(c.tree("previous")));
    assertEquals("orders", active(c.tree("tab")));
    var content = c.tree("content");
    assertTrue(
        focused(content).contains("#grid") || focused(content).contains("role=grid"),
        focused(content));
    var tree = c.tree("tree");
    assertTrue(focused(tree).contains("role=tree "), focused(tree));
    var fromPalette = c.tree("from-palette");
    assertTrue(focused(fromPalette).contains("role=grid"), focused(fromPalette));
  }

  @Test
  void controlOneGoesBackToWhereTheFocusWasInAConsole() throws Exception {
    var app = connected();
    c.run(
        app,
        "press role=treeitem label=\"shop-prod\"\nframe\nkey N control\nframe\n" + SETTLE
            + "snap new\nfocus #database\nframe\nkey 0 control\nframe\nsnap tree\n"
            + "key 1 control\nframe\nsnap back\n");
    assertTrue(focused(c.tree("new")).contains("#sql"), focused(c.tree("new")));
    assertTrue(focused(c.tree("tree")).contains("role=tree "), focused(c.tree("tree")));
    assertTrue(focused(c.tree("back")).contains("#database"), focused(c.tree("back")));
  }

  @Test
  void thePaletteAndItsKeysInATornOffWindowActOnItsTabs() throws Exception {
    var app = connected();
    c.run(
        app,
        GO_TO + "type \"orders\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + GO_TO + "type \"customers\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + "press #tab-1 right\n" + SHOWN
            + "press role=menuitem label=\"Move to new window\"\n" + SHOWN
            + "window \"customers\"\n" + GO_TO + "type \"order_t\"\nframe\nkey Enter\n" + SHOWN
            + SETTLE + "snap torn\n");
    assertEquals("orders", tabs(c.tree("torn")));
    assertEquals("customers,order_totals", tabs(c.tree("customers-torn")));
    c.run(app, "key W control\nframe\n" + SETTLE + "snap closed\n");
    assertEquals("orders", tabs(c.tree("closed")));
    assertEquals("customers", tabs(c.tree("customers-closed")));
    c.run(app, PALETTE + "snap palette\n");
    var palette = c.tree("customers-palette");
    assertTrue(line(palette, "role=dialog").contains("label=\"Command palette\""), palette);
    assertTrue(line(palette, "label=\"Go: Connections\"").contains("disabled"), palette);
    assertFalse(c.tree("palette").contains("label=\"Command palette\""), "not in the main window");
    c.run(app, "type \"close tab\"\nframe\nkey Enter\n" + SHOWN + SETTLE);
    assertEquals(1, app.windows().all().size(), "its window closed with its last tab");
    assertEquals(
        List.of("orders"),
        app.state().tables(null).stream().map(x -> x.table.name()).toList());
  }

  /** Orders and customers open in the main window, customers then moved to a window of its own. */
  App<State> torn() throws Exception {
    var app = connected();
    c.run(
        app,
        GO_TO + "type \"orders\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + GO_TO + "type \"customers\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + "press #tab-1 right\n" + SHOWN
            + "press role=menuitem label=\"Move to new window\"\n" + SHOWN);
    return app;
  }

  @Test
  void aCommandThatOpensADialogWaitsForTheOneOpenInTheMainWindowFromEveryWindow() throws Exception {
    var app = torn();
    c.run(
        app,
        "press role=button label=\"Add connection\"\n" + SHOWN
            + "press role=textbox label=\"Name\"\ntype \"half done\"\nframe\n"
            + "window \"customers\"\n" + PALETTE + "snap all\ntype \"new connection\"\nframe\n"
            + "snap asked\nkey Enter\n" + SHOWN + "window main\nframe\nsnap main\n");
    var dialog = app.state().dialog;
    var asked = c.tree("customers-asked");
    assertTrue(line(asked, "label=\"File: New connection…\"").contains("disabled"), asked);
    var all = c.tree("customers-all");
    assertTrue(line(all, "label=\"File: Drivers…\"").contains("disabled"), all);
    assertTrue(c.tree("main").contains("\"half done\""), "the dialog is as it was");
    assertSame(dialog, app.state().dialog);
  }

  @Test
  void twoConnectionsOfOneNameAreToldApartAndEnterOpensTheChosenOnesTable() throws Exception {
    var first = SHOP.withName("local");
    var second = ConnectionProfile.of(Provider.POSTGRESQL)
        .withName("local")
        .withHost(PG)
        .withDatabase("shop")
        .withUser("shop_app");
    var app = c.app(first, second);
    c.ready(app, first, second);
    c.run(app, START);
    var s = app.state();
    for (var p : List.of(first, second)) {
      var db = new Node.OfDatabase(p.id(), new Database("shop", false));
      s.catalogue.toggle(s, new Node.Connection(p.id()), true);
      c.run(app, SETTLE);
      s.catalogue.toggle(s, db, true);
      c.run(app, SETTLE);
      s.catalogue.toggle(
          s, new Node.OfSchema(p.id(), new Schema(db.database(), "public", false)), true);
      c.run(app, SETTLE);
    }
    c.run(
        app,
        GO_TO + "type \"orders\"\nframe\nkey ArrowDown\nframe\nframe\nsnap chosen\n" + "key Enter\n"
            + SHOWN + SETTLE);
    var chosen = c.tree("chosen");
    var orders =
        chosen.lines().filter(l -> l.contains("role=option label=\"orders\"")).toList();
    assertEquals(2, orders.size(), chosen);
    assertTrue(orders.get(1).contains("selected"), "the second stays chosen: " + chosen);
    assertTrue(chosen.contains("\"local (1) · shop · public\""), chosen);
    assertTrue(chosen.contains("\"local (2) · shop · public\""), chosen);
    assertEquals(second.id(), s.tables(null).getFirst().id);
  }

  @Test
  void aConnectionIsCalledByItsServerWhereThatTellsItApart() {
    var here = SHOP.withName("local");
    var there = SHOP.withName("local").withHost("other.example.com");
    var alone = INVENTORY;
    var all = List.of(here, there, alone);
    assertEquals("local (shop_app@" + PG + ":5432)", GoTo.label(here, all));
    assertEquals("local (shop_app@other.example.com:5432)", GoTo.label(there, all));
    assertEquals("inventory", GoTo.label(alone, all));
  }

  @Test
  void closingThePaletteStopsWhatItBeganReadingAndLeavesTheTreesReads() throws Exception {
    var app = connected();
    var held = c.servers.hold("columns " + PG + "/shop.public.orders");
    c.run(app, open("orders"));
    CatalogueTest.eventually(
        () -> c.servers.calls.contains("columns " + PG + "/shop.public.orders"));
    // Shop's sales schema is not read: its scan waits behind the held read, on the same lane.
    c.run(
        app,
        GO_TO + "type \"inv\"\nframe\npress role=option label=\"shop\"\n" + SHOWN
            + "snap reading\nkey Escape\n" + SHOWN + open("customers"));
    assertTrue(option(c.tree("reading"), "shop").contains("reading its tables…"));
    held.countDown();
    c.run(app, SETTLE + GO_TO + "type \"inv\"\nframe\nsnap after\n");
    assertFalse(c.servers.calls.contains("all tables " + PG + "/shop"), c.servers.calls.toString());
    assertTrue(c.servers.done.contains("columns " + PG + "/shop.public.orders"));
    assertTrue(c.servers.done.contains("columns " + PG + "/shop.public.customers"));
    var after = c.tree("after");
    assertTrue(option(after, "shop").contains("choose to read its tables"), after);
    assertFalse(options(after).contains("invoices"), after);
  }

  @Test
  void whatThePaletteReadsWaitsBehindWhatTheTreeAsksFor() throws Exception {
    var app = torn();
    var held = c.servers.hold("columns " + PG + "/shop.public.orders");
    c.run(app, "window main\n" + open("orders"));
    CatalogueTest.eventually(
        () -> c.servers.calls.contains("columns " + PG + "/shop.public.orders"));
    // The palette of the other window stays open while the tree is used.
    c.run(
        app,
        "window \"customers\"\n" + GO_TO + "type \"inv\"\nframe\n"
            + "press role=option label=\"shop\"\n" + SHOWN + "window main\n"
            + open("order_totals"));
    held.countDown();
    c.run(app, SETTLE + "window \"customers\"\nframe\nsnap found\n");
    var calls = c.servers.calls;
    int tree = calls.indexOf("columns " + PG + "/shop.public.order_totals");
    int palette = calls.indexOf("all tables " + PG + "/shop");
    assertTrue(tree >= 0 && palette > tree, calls.toString());
    assertTrue(options(c.tree("customers-found")).contains("invoices"), c.tree("customers-found"));
  }

  @Test
  void aReadThePaletteBeganThatFailsSaysSoThereAndIsOfferedAgain() throws Exception {
    var app = connected();
    var key = "all tables " + PG + "/analytics";
    c.servers.fail(key, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    c.run(
        app,
        GO_TO + "type \"ev\"\nframe\npress role=option label=\"analytics\"\n" + SHOWN + SETTLE
            + "snap failed\n");
    var failed = c.tree("failed");
    assertTrue(
        option(failed, "analytics").contains("\"shop-prod · Access denied, choose to try again\""),
        failed);
    assertFalse(line(failed, "role=option label=\"analytics\"").contains("disabled"), failed);
    // Not in the tree, whose node was never opened.
    assertFalse(CatalogueTest.rows(failed).contains("    tracking"), failed);
    c.servers.heal(key);
    c.run(app, "press role=option label=\"analytics\"\n" + SHOWN + SETTLE + "snap read\n");
    assertEquals("events", options(c.tree("read")).getFirst(), c.tree("read"));
  }

  @Test
  void aRefreshOrADisconnectStopsWhatThePaletteReads() throws Exception {
    var app = torn();
    var key = "all tables " + PG + "/shop";
    var held = c.servers.hold(key);
    var pick = "window \"customers\"\n" + GO_TO + "type \"inv\"\nframe\n"
        + "press role=option label=\"shop\"\n" + SHOWN;
    c.run(app, pick);
    CatalogueTest.eventually(() -> c.servers.calls.contains(key));
    c.run(app, "window main\n" + CatalogueTest.menu("shop-prod", "Refresh") + SETTLE);
    CatalogueTest.eventually(() -> c.servers.interrupted.contains(key));
    c.run(app, "window \"customers\"\nframe\nsnap refreshed\n");
    var refreshed = c.tree("customers-refreshed");
    assertTrue(option(refreshed, "shop").contains("choose to read its tables"), refreshed);
    assertFalse(options(refreshed).contains("invoices"), refreshed);
    c.servers.interrupted.clear();
    c.run(app, pick);
    CatalogueTest.eventually(() -> c.servers.interrupted.isEmpty()
        && c.servers.calls.stream().filter(key::equals).count() == 2);
    c.run(app, "window main\n" + CatalogueTest.menu("shop-prod", "Disconnect") + SETTLE);
    CatalogueTest.eventually(() -> c.servers.interrupted.contains(key));
    held.countDown();
    c.run(app, SETTLE);
    assertTrue(app.state().catalogue.live(SHOP.id()) == null);
  }

  @Test
  void aRefreshNeverStartsThePalettesReadThatWaits() throws Exception {
    var app = torn();
    var columns = "columns " + PG + "/shop.public.orders";
    var held = c.servers.hold(columns);
    c.run(app, "window main\n" + open("orders"));
    CatalogueTest.eventually(() -> c.servers.calls.contains(columns));
    c.run(
        app,
        "window \"customers\"\n" + GO_TO + "type \"inv\"\nframe\n"
            + "press role=option label=\"shop\"\n" + SHOWN + "snap asked\nwindow main\n"
            + CatalogueTest.menu("shop", "Refresh"));
    var asked = c.tree("customers-asked");
    assertTrue(option(asked, "shop").contains("reading its tables…"), asked);
    // The tree's read that runs is interrupted, which lets the lane go on at once: the palette's
    // read behind it has left the lane by then.
    CatalogueTest.eventually(() -> c.servers.interrupted.contains(columns));
    held.countDown();
    c.run(app, SETTLE);
    assertFalse(c.servers.calls.contains("all tables " + PG + "/shop"), c.servers.calls.toString());
  }

  @Test
  void aClosedWindowsPaletteIsForgotten() throws Exception {
    var app = torn();
    c.run(
        app,
        "window \"customers\"\n" + PALETTE + "key Escape\n" + SHOWN + PALETTE
            + "type \"close tab\"\nframe\nkey Enter\n" + SHOWN + SETTLE);
    assertEquals(1, app.windows().all().size());
    var s = app.state();
    assertEquals(java.util.Set.of(s.tabs.main()), s.palettes.keySet());
  }

  @Test
  void aTableOpenInTheMainWindowChosenInAnotherBringsTheMainWindowForwardWithTheFocusOnIt()
      throws Exception {
    var app = torn();
    var main = app.windows().of(app);
    c.run(app, "window \"customers\"\nframe\n");
    assertTrue(app.windows().place(app, 100, 100).window() != main, "the other lies on top");
    c.run(
        app,
        GO_TO + "type \"orders\"\nframe\nkey Enter\n" + SHOWN + "frame\n"
            + "window main\nframe\nsnap main\n");
    assertSame(main, app.windows().place(app, 100, 100).window(), "the main window is on top");
    var tree = c.tree("main");
    assertEquals("orders", active(tree));
    assertTrue(focused(tree).contains("role=grid"), focused(tree));
  }

  @Test
  void tiesGoToTheShorterNameAndThenInTheOrderOfTheTree() throws Exception {
    var app = connected();
    c.run(app, GO_TO + "type \"o\"\nframe\nsnap ties\n");
    var all = options(c.tree("ties"));
    // Its start, then anywhere: stock before customers and low_stock, which are as long.
    assertEquals(
        List.of("orders", "order_totals", "stock", "customers", "low_stock"),
        all.subList(0, 5),
        c.tree("ties"));
  }

  /** The active tab's label. */
  static String active(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=tab ") && l.contains(" selected"))
        .map(l -> l.replaceAll(".*label=\"([^\"]*)\".*", "$1"))
        .findFirst()
        .orElse("");
  }

  static String focused(String tree) {
    return tree.lines().filter(l -> l.contains(" focused")).findFirst().orElse("");
  }

  /**
   * The pictures for a review, light and dark, into the folder that {@code -Dreview=<folder>}
   * names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void pictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var app = connected(SHOP.withColour(Colour.TEAL), INVENTORY.withColour(Colour.ORANGE));
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    var held = c.servers.hold("schemas " + PG + "/analytics");
    var close = "key Escape\n" + SHOWN;
    Script.run(
        "frame\n" + PALETTE + "snap palette-commands-" + look + "\n" + close
            + GO_TO + "type \"st\"\nframe\nframe\nsnap palette-tables-" + look + "\n" + close
            + GO_TO + "type \"ev\"\nframe\npress role=option label=\"analytics\"\n" + SHOWN
            + "snap palette-unread-" + look + "\n",
        app,
        out);
    held.countDown();
    Script.run(
        SETTLE + "snap palette-read-" + look + "\n" + close + GO_TO
            + "type \"orders\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + GO_TO + "type \"stock\"\nframe\nkey Enter\n" + SHOWN + SETTLE
            + "press role=menuitem label=\"Go\"\n" + SHOWN + "snap menu-go-" + look + "\n"
            + close,
        app,
        out);
  }
}

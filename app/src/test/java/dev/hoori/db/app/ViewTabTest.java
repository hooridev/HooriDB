package dev.hoori.db.app;

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
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.definition.Reference;
import dev.hoori.db.core.definition.References;
import dev.hoori.db.core.definition.ViewChange;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A view's definition in a tab of its own: read on a session of its own, edited and saved, dropped
 * and refreshed after the statements are shown, and a new view created.
 */
class ViewTabTest {

  static final Schema PUBLIC = new Schema(new Database("shop", false), "public", false);
  static final String TOTALS = PG + "/shop.public.order_totals";
  static final String CREATE =
      "CREATE VIEW public.order_totals AS\nSELECT customer_id, sum(total) AS total\n"
          + "FROM public.orders\nGROUP BY customer_id";
  static final String REPLACE = CREATE.replace("CREATE VIEW", "CREATE OR REPLACE VIEW");

  // A materialized view beside the view.
  static final String OUTLINE = CatalogTreeTest.PG_OUTLINE.replace(
      "    @order_totals\n", "    %recent\n      id: bigint\n    @order_totals\n");

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers
        .serve(PG, Provider.POSTGRESQL, "16.4", OUTLINE)
        .view(
            TOTALS,
            List.of(CREATE, "COMMENT ON VIEW public.order_totals IS 'Totals'"),
            new References(
                List.of(new Reference(PUBLIC, Reference.Kind.TABLE, "orders", "")),
                List.of(new Reference(PUBLIC, Reference.Kind.VIEW, "big_spenders", ""))),
            false)
        .view(
            PG + "/shop.public.recent",
            List.of("CREATE MATERIALIZED VIEW public.recent AS\nSELECT id FROM public.orders\n"
                + "  WITH NO DATA"),
            new References(
                List.of(new Reference(PUBLIC, Reference.Kind.TABLE, "orders", "")), List.of()),
            true);
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with the connection, its database and schema public open. */
  App<State> opened(ConnectionProfile profile) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    c.run(
        app,
        START + open(profile.name()) + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    return app;
  }

  static final String SAVE = "press role=button label=\"Save…\"\nframe\nadvance 1\nframe\n";

  static String replace(String text) {
    return "press #definition\nkey A control\nclipboard \""
        + text.replace("\"", "\\\"").replace("\n", "\\n") + "\"\nkey V control\nframe\n";
  }

  ViewTab tab(App<State> app) {
    return app.state().views(null).getFirst();
  }

  @Test
  void aViewsDefinitionOpensReadOnlyOnASessionOfItsOwnWithWhatItUses() throws Exception {
    var app = opened(SHOP);
    c.run(app, menu("order_totals", "Definition") + SETTLE + "snap opened\n");
    var tree = c.tree("opened");
    var tab = tab(app);
    assertTrue(tab.text.startsWith(CREATE.substring(0, 20)), tab.text);
    assertTrue(tab.text.contains("COMMENT ON VIEW"), tab.text);
    assertFalse(tab.editing);
    assertTrue(tree.contains("label=\"Definition\""), tree);
    assertFalse(line(tree, "role=button label=\"Edit\"").contains("disabled"), tree);
    assertTrue(line(tree, "role=button label=\"Save…\"").contains("disabled"), tree);
    assertTrue(tree.contains("orders"), tree);
    assertTrue(tree.contains("big_spenders"), tree);
    // Read on a connection of its own, behind the tree's.
    assertTrue(c.servers.linked.contains("2 view " + TOTALS), c.servers.linked.toString());
  }

  @Test
  void shiftEnterAndThePaletteOpenTheDefinitionAndTheTabIsReusedForIt() throws Exception {
    var app = opened(SHOP);
    c.run(app, "press role=treeitem label=\"order_totals\"\nkey Enter shift\nframe\n" + SETTLE);
    assertEquals(1, app.state().views(null).size(), "Shift+Enter opens it");
    assertEquals(List.of(), app.state().tables(null), "Not its rows");
    c.run(app, "key W control\n" + FoldersTest.SHOWN + SETTLE);
    assertEquals(List.of(), app.state().views(null), "Closed, which ends its session");
    c.run(
        app,
        "press role=treeitem label=\"order_totals\"\n" + PaletteTest.PALETTE
            + "type \"view definition\"\nframe\nkey Enter\n" + FoldersTest.SHOWN + SETTLE);
    assertEquals(1, app.state().views(null).size(), "The palette opens it");
    c.run(app, menu("order_totals", "Definition") + SETTLE);
    assertEquals(1, app.state().views(null).size(), "The menu goes to the one open");
    assertEquals(2, c.servers.calls.stream().filter(("view " + TOTALS)::equals).count());
  }

  @Test
  void anEditedDefinitionIsShownAsItsReplaceBeforeItRunsAndTheTreeReadsAgain() throws Exception {
    var app = opened(SHOP);
    var edited = CREATE.replace("GROUP BY", "WHERE total > 0\nGROUP BY") + ";\n";
    c.run(
        app,
        menu("order_totals", "Definition") + SETTLE
            + "press role=button label=\"Edit\"\nframe\n" + replace(edited) + SAVE
            + "snap preview\n");
    var preview = c.tree("preview");
    assertTrue(line(preview, "#view-summary").contains("Replaces the definition"), preview);
    var change = tab(app).asked.change();
    assertEquals(ViewChange.Kind.REPLACE, change.kind());
    assertTrue(
        change.statements().getFirst().startsWith("CREATE OR REPLACE VIEW"), change.toString());
    long tables = c.servers.calls.stream()
        .filter(("tables " + PG + "/shop.public")::equals)
        .count();
    c.run(app, "press role=button label=\"Save\"\nframe\n" + SETTLE + "snap saved\n");
    assertEquals(change, c.servers.viewChanges.getLast());
    assertTrue(
        c.servers.calls.contains("ddl " + PG + " " + change.statements().getFirst()),
        c.servers.calls.toString());
    assertEquals(
        tables + 1,
        c.servers.calls.stream()
            .filter(("tables " + PG + "/shop.public")::equals)
            .count(),
        "The tree reads the schema again");
    assertFalse(tab(app).editing, "Read again, as saved");
  }

  @Test
  void aViewWhoseColumnsTheServerKeepsIsDroppedAndCreatedWhereNothingUsesIt() throws Exception {
    var app = opened(SHOP);
    var edited = "CREATE VIEW public.order_totals AS\nSELECT customer_id\nFROM public.orders;\n";
    c.servers.keepsColumns(
        "CREATE OR REPLACE VIEW public.order_totals AS\nSELECT customer_id\nFROM public.orders");
    var save = "press role=button label=\"Edit\"\nframe\n" + replace(edited) + SAVE
        + "press role=button label=\"Save\"\nframe\n" + SETTLE;
    c.run(app, menu("order_totals", "Definition") + SETTLE + save + "snap kept\n");
    // A view uses it, which a drop would take with it: the tab says so, and asks nothing.
    var kept = c.tree("kept");
    assertTrue(kept.contains("#problem"), kept);
    var problem = tab(app).problem;
    assertEquals("The view's columns cannot change in place", problem.title());
    assertTrue(problem.text().contains("public.big_spenders uses it"), problem.text());
    assertEquals(null, tab(app).asked);
    // Once nothing uses it, read anew.
    c.servers.view(TOTALS, List.of(CREATE), References.NONE, false);
    c.run(
        app,
        "press role=button label=\"Revert\"\nframe\npress role=button label=\"Reload\"\nframe\n"
            + SETTLE + save + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "#view-summary").contains("cannot change the view's columns"), asked);
    assertTrue(line(asked, "#view-summary").contains("must be granted again"), asked);
    assertEquals("cannot drop columns from view", tab(app).asked.refused());
    assertEquals(ViewChange.Kind.RECREATE, tab(app).asked.change().kind());
    c.run(app, "press role=button label=\"Drop and create\"\nframe\n" + SETTLE);
    var change = c.servers.viewChanges.getLast();
    assertEquals(ViewChange.Kind.RECREATE, change.kind());
    assertEquals("DROP VIEW \"public\".\"order_totals\"", change.statements().getFirst());
    assertTrue(change.transaction());
  }

  @Test
  void aDropNamesWhatDependsOnItAndTheViewLeavesTheTree() throws Exception {
    var app = opened(SHOP);
    c.run(app, menu("order_totals", "Drop view…") + SETTLE + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "#view-summary").contains("public.big_spenders"), asked);
    assertEquals(
        List.of("DROP VIEW \"public\".\"order_totals\" CASCADE"),
        tab(app).asked.change().statements());
    c.run(app, "press role=button label=\"Drop\"\nframe\n" + SETTLE + "snap dropped\n");
    var dropped = c.tree("dropped");
    assertEquals(List.of(), app.state().views(null), "Its tab closes");
    assertFalse(dropped.contains("label=\"order_totals\""), dropped);
    assertTrue(CatalogTreeTest.message(dropped).contains("Dropped order_totals"), dropped);
  }

  @Test
  void aNewViewIsCreatedFromItsSchemaNameAndQuery() throws Exception {
    var app = opened(SHOP);
    c.run(
        app,
        menu("public", "New view…") + "press #name\ntype \"recent_orders\"\nframe\n"
            + replace("SELECT id FROM public.orders") + SAVE + "snap asked\n");
    var change = tab(app).asked.change();
    assertEquals(
        List.of("CREATE VIEW \"public\".\"recent_orders\" AS\nSELECT id FROM public.orders"),
        change.statements());
    c.run(app, "press role=button label=\"Create\"\nframe\n" + SETTLE + "snap created\n");
    var created = c.tree("created");
    assertTrue(created.contains("label=\"recent_orders\""), created);
    var tab = tab(app);
    assertFalse(tab.creating);
    assertTrue(
        c.servers.calls.contains("view " + PG + "/shop.public.recent_orders"),
        c.servers.calls.toString());
  }

  @Test
  void aMaterializedViewStandsApartAndRefreshesConcurrentlyWhereItCan() throws Exception {
    var app = opened(SHOP);
    c.run(app, "snap tree\n" + menu("recent", "Refresh rows…") + SETTLE + "snap asked\n");
    var tree = c.tree("tree");
    assertTrue(line(tree, "label=\"recent\"").contains("materialized"), tree);
    var asked = c.tree("asked");
    assertFalse(line(asked, "label=\"Concurrently").contains("disabled"), asked);
    assertEquals(
        List.of("REFRESH MATERIALIZED VIEW CONCURRENTLY \"public\".\"recent\""),
        tab(app).asked.change().statements());
    c.run(app, "press role=button label=\"Refresh\"\nframe\n" + SETTLE);
    assertEquals(ViewChange.Kind.REFRESH, c.servers.viewChanges.getLast().kind());
  }

  @Test
  void aReadOnlyConnectionChangesNoView() throws Exception {
    var app = opened(SHOP.withReadOnly(true));
    c.run(
        app,
        "press role=treeitem label=\"order_totals\" right\nframe\nadvance 1\nframe\nsnap menu\n"
            + "key Escape\nframe\nadvance 1\nframe\n" + menu("order_totals", "Definition")
            + SETTLE + "snap tab\n");
    var menu = c.tree("menu");
    assertTrue(line(menu, "role=menuitem label=\"Drop view…\"").contains("disabled"), menu);
    var tab = c.tree("tab");
    assertTrue(line(tab, "role=button label=\"Edit\"").contains("disabled"), tab);
    assertTrue(line(tab, "role=button label=\"Drop…\"").contains("disabled"), tab);
    assertEquals(List.of(), c.servers.viewChanges);
  }
}

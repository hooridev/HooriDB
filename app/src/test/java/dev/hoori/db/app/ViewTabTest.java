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
import dev.hoori.db.core.execution.Failure;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A view's definition in a tab of its own: read on the tree's connection, edited and saved, dropped
 * and refreshed after the statements are shown on the connection that changes views, cancelled,
 * asked about before its edits go, and a new view created.
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
  void aViewsDefinitionOpensReadOnlyOnTheTreesConnectionWithWhatItUses() throws Exception {
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
    // Read on the tree's connection, which only reads: no connection of its own.
    assertTrue(c.servers.linked.contains("1 view " + TOTALS), c.servers.linked.toString());
    assertTrue(c.servers.readOnly(1));
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
  void aDropGoesAloneFirstAndWithWhatDependsOnItAsTheServerNamesIt() throws Exception {
    var app = opened(SHOP);
    c.run(app, menu("order_totals", "Drop view…") + SETTLE + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "#view-summary").contains("public.big_spenders"), asked);
    // Never a CASCADE from what was read before: the server says what it would take, now.
    assertEquals(
        List.of("DROP VIEW \"public\".\"order_totals\""),
        tab(app).asked.change().statements());
    c.run(app, "press role=button label=\"Drop\"\nframe\n" + SETTLE + "snap refused\n");
    var refused = c.tree("refused");
    assertTrue(line(refused, "role=dialog").contains("Drop with what depends on it?"), refused);
    assertTrue(line(refused, "#view-summary").contains("Something depends on"), refused);
    assertEquals(
        "view public.big_spenders depends on view public.order_totals",
        tab(app).asked.refused());
    assertEquals(
        List.of("DROP VIEW \"public\".\"order_totals\" CASCADE"),
        tab(app).asked.change().statements());
    c.run(app, "press role=button label=\"Drop all\"\nframe\n" + SETTLE + "snap dropped\n");
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

  @Test
  void controlSSavesOnlyWhatTheSaveButtonWould() throws Exception {
    var app = opened(SHOP);
    // A materialized view not edited: Save would drop and create it anew.
    c.run(
        app,
        menu("recent", "Definition") + SETTLE + "press #definition\nkey S control\nframe\n"
            + "press role=button label=\"Edit\"\nframe\nkey S control\nframe\nadvance 1\nframe\n"
            + "snap unchanged\n");
    assertEquals(null, tab(app).asked, "Nothing to save");
    var unchanged = c.tree("unchanged");
    assertFalse(unchanged.contains("role=dialog"), unchanged);
    var edited = tab(app).text.replace("SELECT id", "SELECT id + 0 AS id");
    c.run(app, replace(edited) + "key S control\nframe\nadvance 1\nframe\n");
    assertEquals(ViewChange.Kind.RECREATE, tab(app).asked.change().kind(), "Edited, it saves");
  }

  @Test
  void aDefinitionThatNamesAnotherViewIsNotSaved() throws Exception {
    var app = opened(SHOP);
    var other = CREATE.replace("public.order_totals", "public.customer_totals") + ";\n";
    c.run(
        app,
        menu("order_totals", "Definition") + SETTLE + "press role=button label=\"Edit\"\nframe\n"
            + replace(other) + SAVE + "snap named\n");
    assertEquals(null, tab(app).asked, "No second view while the tab would say Saved");
    var problem = tab(app).problem;
    assertEquals("The definition names another view", problem.title());
    assertTrue(problem.text().contains("public.customer_totals"), problem.text());
    assertEquals(List.of(), c.servers.viewChanges);
  }

  @Test
  void anEditedViewAsksBeforeItsTabClosesAndKeepsItsEditsOnCancel() throws Exception {
    var app = opened(SHOP);
    var edited = CREATE.replace("GROUP BY", "WHERE total > 0\nGROUP BY") + ";\n";
    c.run(
        app,
        menu("order_totals", "Definition") + SETTLE
            + "press role=button label=\"Edit\"\nframe\n" + replace(edited)
            + "key W control\nframe\nadvance 1\nframe\nsnap asked\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("Discard the edits?"), asked);
    c.run(app, "press role=button label=\"Cancel\"\nframe\nadvance 1\nframe\n");
    assertEquals(1, app.state().views(null).size(), "Cancel keeps the tab");
    assertTrue(tab(app).edited());
    c.run(
        app,
        "key W control\nframe\nadvance 1\nframe\npress role=button label=\"Discard\"\nframe\n"
            + "advance 1\nframe\n");
    assertEquals(List.of(), app.state().views(null), "Discarded, it closes");
  }

  @Test
  void exitAsksAboutAnEditedViewFirst() throws Exception {
    var app = opened(SHOP);
    c.run(
        app,
        menu("public", "New view…") + "press #name\ntype \"recent_orders\"\nframe\n"
            + replace("SELECT id FROM public.orders") + "snap edited\n" + ConsoleTransactions.EXIT
            + "snap asked\n");
    var edited = c.tree("edited");
    assertTrue(line(edited, "#uncommitted").contains("1 view has edits not saved"), edited);
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("Discard the edits?"), asked);
    assertTrue(asked.contains("before HooriDB closes?"), asked);
    assertFalse(app.windows().all().isEmpty(), "It waits for the answer");
    c.run(app, "press role=button label=\"Discard\"\nframe\n" + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "Discarded, HooriDB closes");
  }

  @Test
  void deletingTheConnectionClosesItsViewTabs() throws Exception {
    var app = opened(SHOP);
    c.run(
        app,
        menu("order_totals", "Definition") + SETTLE + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\nadvance 1\nframe\nawait\nframe\n"
            + "press role=button label=\"Delete\"\nframe\n" + SETTLE);
    assertTrue(app.state().profiles.isEmpty());
    assertEquals(List.of(), app.state().views(null), "No tab of a connection that is gone");
  }

  @Test
  void aChangeRunsOnTheConnectionForChangesAndCanBeCancelled() throws Exception {
    var app = opened(SHOP);
    var refresh = "ddl " + PG + " REFRESH MATERIALIZED VIEW CONCURRENTLY \"public\".\"recent\"";
    var gate = c.servers.hold(refresh);
    try {
      c.run(
          app,
          menu("recent", "Refresh rows…") + SETTLE
              + "press role=button label=\"Refresh\"\nframe\nadvance 1\nframe\nsnap running\n");
      var running = c.tree("running");
      assertTrue(running.contains("#cancel-change"), running);
      var link = c.servers.linked.stream()
          .filter(l -> l.endsWith(refresh))
          .map(l -> Integer.parseInt(l.substring(0, l.indexOf(' '))))
          .findFirst()
          .orElseThrow();
      assertTrue(link > 1, "Not the tree's connection: " + c.servers.linked);
      assertFalse(c.servers.readOnly(link), "One that writes");
      c.run(app, "press #cancel-change\nframe\n" + SETTLE + "snap cancelled\n");
      assertEquals(ConsoleTab.UNSURE, tab(app).problem.title());
      assertEquals(null, tab(app).changing);
      assertTrue(c.servers.interrupted.contains(refresh), c.servers.interrupted.toString());
      assertTrue(c.tree("cancelled").contains("#problem"), c.tree("cancelled"));
    } finally {
      gate.countDown();
    }
  }

  @Test
  void exitCancelsARunningChangeAndHearsHowItsConnectionClosed() throws Exception {
    var app = opened(SHOP);
    var refresh = "ddl " + PG + " REFRESH MATERIALIZED VIEW CONCURRENTLY \"public\".\"recent\"";
    var gate = c.servers.hold(refresh);
    try {
      c.run(
          app,
          menu("recent", "Refresh rows…") + SETTLE
              + "press role=button label=\"Refresh\"\nframe\nadvance 1\nframe\n");
      c.servers.failsAtClose(
          PG, FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "it did not end"));
      c.run(app, ConsoleTransactions.EXIT + SETTLE);
      assertTrue(c.servers.interrupted.contains(refresh), c.servers.interrupted.toString());
      CatalogTreeTest.eventually(() -> !app.state().notices.isEmpty());
      var notice = app.state().notices.getFirst();
      assertTrue(
          notice.what().contains("Closing the session that changes views failed: it did not end"),
          notice.what());
      c.run(app, SETTLE);
      assertFalse(app.windows().all().isEmpty(), "Exit waited for it, and stayed to say so");
    } finally {
      gate.countDown();
    }
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.LEGACY;
import static dev.hoori.db.app.CatalogTreeTest.MYSQL;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.row;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.catalog.Routine.Kind;
import dev.hoori.db.core.catalog.Routine.Mode;
import dev.hoori.db.core.catalog.Routine.Parameter;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.log.Purpose;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Functions and procedures: in the tree under their schema, their source in a tab of its own, Save
 * and Drop after the statements are shown, on a session of their own, and Run in a console.
 */
class RoutineTabTest {

  static final String OPENS = "frame\nadvance 1\nframe\n";

  private static final Schema PUBLIC = new Schema(new Database("shop", false), "public", false);
  private static final Database LEGACY_DB = new Database("legacy", false);

  static final Routine ADD = new Routine(
      PUBLIC,
      "add",
      "a integer, b integer",
      Kind.FUNCTION,
      "integer",
      List.of(new Parameter("a", "integer", Mode.IN), new Parameter("b", "integer", Mode.IN)));
  static final String ADD_SOURCE = """
      CREATE OR REPLACE FUNCTION public.add(a integer, b integer)
       RETURNS integer
       LANGUAGE sql
      AS $function$ SELECT a + b $function$""";
  static final Routine ADD_TEXT = new Routine(
      PUBLIC, "add", "t text", Kind.FUNCTION, "text", List.of(new Parameter("t", "text", Mode.IN)));
  static final Routine FILL = new Routine(
      PUBLIC,
      "fill",
      "n integer, OUT total integer",
      Kind.PROCEDURE,
      "",
      List.of(new Parameter("n", "integer", Mode.IN), new Parameter("total", "integer", Mode.OUT)));

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.servers
        .routine(PG, ADD, ADD_SOURCE)
        .routine(PG, ADD_TEXT, "CREATE OR REPLACE FUNCTION public.add(t text) …")
        .routine(
            PG, FILL, "CREATE OR REPLACE PROCEDURE public.fill(n integer, OUT total integer) …");
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** The app with the connection connected and its routines open in the tree. */
  private App<State> routines(ConnectionProfile profile, String... path) throws Exception {
    var app = c.app(profile);
    c.ready(app, profile);
    var script = new StringBuilder(START).append(open(profile.name())).append(SETTLE);
    for (var level : path) {
      script.append(open(level)).append(SETTLE);
    }
    script.append(open("Routines")).append(SETTLE);
    c.run(app, script.toString());
    return app;
  }

  private App<State> shop() throws Exception {
    return routines(SHOP, "shop", "public");
  }

  @Test
  void routinesShowAfterTheTablesOfTheirSchemaWithTheirOverloadsApart() throws Exception {
    var app = shop();
    c.run(app, "snap tree\n");
    var rows = rows(c.tree("tree"));
    int at = rows.indexOf("      Routines");
    assertTrue(at > 0, rows.toString());
    assertEquals("      order_totals", rows.get(at - 1), rows.toString());
    assertEquals(
        List.of(
            "        add(integer, integer)",
            "        add(text)",
            "        fill(integer, OUT integer)"),
        rows.subList(at + 1, at + 4));
    var tree = c.tree("tree");
    assertTrue(row(tree, "add(integer, integer)").contains("integer"), tree);
    assertTrue(row(tree, "fill(integer, OUT integer)").contains("procedure"), tree);
    assertTrue(
        c.servers.calls.contains("routines " + PG + "/shop.public"), c.servers.calls.toString());
  }

  @Test
  void aRoutineOpensReadOnlyUntilEditedAndSaveRunsItOnASessionOfItsOwn() throws Exception {
    var app = shop();
    var changed = ADD_SOURCE.replace("a + b", "a + b + 1");
    c.servers.defines(PG, changed, () -> {
      c.servers.forget(PG, ADD);
      c.servers.routine(PG, ADD, changed);
    });
    c.run(
        app,
        "press role=treeitem label=\"add(integer, integer)\"\nkey Enter\n" + SETTLE
            + "snap opened\n");
    var opened = c.tree("opened");
    assertTrue(opened.contains("$function$ SELECT a + b $function$"), opened);
    assertTrue(opened.contains("label=\"Edit\""), opened);
    assertTrue(
        c.servers.calls.contains("source " + PG + "/shop.public add(integer, integer)"),
        c.servers.calls.toString());
    c.run(
        app,
        "press role=button label=\"Edit\"\n" + OPENS + "press #source\nkey A control\n"
            + "clipboard \"" + escaped(changed) + "\"\nkey V control\nframe\n"
            + "press role=button label=\"Save…\"\n" + OPENS + "snap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("role=dialog label=\"Save routine\""), preview);
    assertTrue(preview.contains("a + b + 1"), preview);
    int links = c.servers.linked.size();
    c.run(app, "press role=button label=\"Save\"\n" + OPENS + SETTLE + "snap saved\n");
    var saved = c.tree("saved");
    assertFalse(saved.contains("role=dialog"), saved);
    assertTrue(saved.contains("Saved"), saved);
    // Not on the tree's link, the first, but on one opened for it.
    var defined = c.servers.linked.stream()
        .filter(l -> l.endsWith("define " + PG + " " + changed))
        .toList();
    assertEquals(
        1,
        defined.size(),
        c.servers.linked.subList(links, c.servers.linked.size()).toString());
    assertFalse(defined.getFirst().startsWith("1 "), defined.toString());
    // The tree read the routines again on its link, and the log has the change.
    assertEquals(
        2,
        c.servers.linked.stream()
            .filter(k -> k.equals("1 routines " + PG + "/shop.public"))
            .count(),
        c.servers.linked.toString());
    assertTrue(
        app.state().statements.entries().stream()
            .anyMatch(
                e -> e.sent().purpose() == Purpose.DEFINITION && e.sent().text().equals(changed)),
        app.state().statements.entries().toString());
  }

  @Test
  void aRoutineSomeoneElseCreatesDuringSaveIsNotTheOneTheTabShows() throws Exception {
    var app = shop();
    var changed = ADD_SOURCE.replace("a + b", "a + b + 1");
    var theirs = new Routine(PUBLIC, "theirs", "", Kind.FUNCTION, "integer", List.of());
    // Only the body changes, and between the routines read before and after it, theirs appears.
    c.servers.defines(PG, changed, () -> {
      c.servers.forget(PG, ADD);
      c.servers.routine(PG, ADD, changed);
      c.servers.routine(PG, theirs, "CREATE FUNCTION public.theirs() …");
    });
    c.run(
        app,
        "press role=treeitem label=\"add(integer, integer)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS + edit(changed)
            + "press role=button label=\"Save…\"\n" + OPENS + "press role=button label=\"Save\"\n"
            + OPENS + SETTLE + "snap saved\n");
    var tab = app.state().routines(null).getFirst();
    assertEquals(ADD, tab.routine);
    assertEquals("add(integer, integer)", app.state().title(tab));
    var saved = c.tree("saved");
    assertTrue(saved.contains("a + b + 1"), saved);
    assertFalse(saved.contains("public.theirs"), saved);
  }

  // MySQL's add(int, int), defined by another user, created under a sql_mode of its own.
  private static final Routine MY_ADD = new Routine(
      LEGACY_DB,
      "add",
      "",
      Kind.FUNCTION,
      "int",
      List.of(new Parameter("a", "int", Mode.IN), new Parameter("b", "int", Mode.IN)));
  private static final String MY_SOURCE = """
      CREATE DEFINER=`app`@`%` FUNCTION `add`(a INT, b INT) RETURNS int
          DETERMINISTIC
      RETURN a + b""";
  private static final String MY_MODE = "SET SESSION sql_mode = 'ANSI_QUOTES'";
  private static final String MY_CHANGED = MY_SOURCE.replace("a + b", "a + b + 1");

  /** MySQL's add(int, int) open in its tab, edited to MY_CHANGED, and Save… asked. */
  private App<State> mySave() throws Exception {
    c.servers.routine(MYSQL, MY_ADD, new Routine.Source(MY_SOURCE, List.of(MY_MODE)));
    var app = routines(LEGACY.withName("legacy-prod"), "legacy");
    c.run(
        app,
        "press role=treeitem label=\"add(int, int)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS + edit(MY_CHANGED)
            + "press role=button label=\"Save…\"\n" + OPENS);
    return app;
  }

  // Replaces the editor's text with the one given, in one edit.
  private static String edit(String text) {
    return "press #source\nkey A control\nclipboard \"" + escaped(text) + "\"\nkey V control\n"
        + "frame\n";
  }

  @Test
  void whereTheServerCannotReplaceARoutineSaveTriesTheTextBeforeItDropsIt() throws Exception {
    var app = mySave();
    c.run(app, "snap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("role=dialog label=\"Save routine\""), preview);
    // The tree cuts the statements short; the preview shows them all.
    var tab = app.state().routines(null).getFirst();
    var shown = ((RoutineTab.Preview) tab.asked).change().statements();
    assertEquals(5, shown.size(), shown.toString());
    assertEquals(MY_MODE, shown.getFirst());
    assertTrue(shown.get(1).contains("hooridb_check_"), shown.toString());
    assertEquals("DROP FUNCTION IF EXISTS legacy.`add`", shown.get(3));
    assertTrue(ConsoleTest.near(preview, "#preview-grants").contains("Privileges"), preview);
    c.run(app, "press role=button label=\"Save\"\n" + OPENS + SETTLE + "snap saved\n");
    var defined = c.servers.calls.stream()
        .filter(k -> k.startsWith("define "))
        .map(k -> k.substring(("define " + MYSQL + " ").length()))
        .toList();
    assertEquals(5, defined.size(), defined.toString());
    assertEquals(MY_MODE, defined.get(0));
    assertTrue(
        defined.get(1).startsWith("CREATE DEFINER=`app`@`%` FUNCTION hooridb_check_"),
        defined.toString());
    assertTrue(
        defined.get(2).startsWith("DROP FUNCTION IF EXISTS legacy.hooridb_check_"),
        defined.toString());
    assertEquals("DROP FUNCTION IF EXISTS legacy.`add`", defined.get(3));
    assertEquals(MY_CHANGED, defined.get(4));
    assertTrue(c.tree("saved").contains("Saved"), c.tree("saved"));
  }

  @Test
  void saveNeedsAnEditAndControlSAsksAsItsButtonDoes() throws Exception {
    var app = shop();
    c.run(
        app,
        "press role=treeitem label=\"add(integer, integer)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS
            + "press #source\nkey S control\n" + OPENS + "snap unedited\n");
    var unedited = c.tree("unedited");
    assertTrue(line(unedited, "label=\"Save…\"").contains("disabled"), unedited);
    assertFalse(unedited.contains("role=dialog"), unedited);
    c.run(app, "press #source\ntype \" \"\nkey S control\n" + OPENS + "snap edited\n");
    assertTrue(c.tree("edited").contains("role=dialog label=\"Save routine\""), c.tree("edited"));
  }

  @Test
  void whereTheRoutineCannotBeCreatedAgainItsSourceStaysForRevertAndSave() throws Exception {
    var refused = FakeServers.serverError(
        Failure.Kind.ACCESS_DENIED, "Access denied; you need the SET_ANY_DEFINER privilege");
    var withoutDefiner = MY_SOURCE.replace("DEFINER=`app`@`%` ", "");
    c.servers
        .definitionFails(
            MYSQL, MY_CHANGED, FakeServers.serverError(Failure.Kind.OTHER, "Made to fail"))
        .definitionFails(MYSQL, MY_SOURCE, refused)
        .definitionFails(MYSQL, withoutDefiner, refused);
    var app = mySave();
    c.run(app, "press role=button label=\"Save\"\n" + OPENS + SETTLE + "snap lost\n");
    var lost = c.tree("lost");
    // The tree cuts the callout's text short.
    var tab = app.state().routines(null).getFirst();
    var said = tab.problem.text();
    assertTrue(said.contains("could not be created again"), said);
    assertTrue(said.contains("SET_ANY_DEFINER"), said);
    assertTrue(said.contains("Revert shows its source"), said);
    // Both ways of putting it back were tried.
    assertTrue(c.servers.calls.contains("define " + MYSQL + " " + MY_SOURCE));
    assertTrue(c.servers.calls.contains("define " + MYSQL + " " + withoutDefiner));
    assertEquals(null, tab.routine);
    assertTrue(lost.contains("1 routine has unsaved edits"), lost);
    c.run(app, "press role=button label=\"Revert\"\n" + OPENS + "snap reverted\n");
    assertEquals(MY_SOURCE, tab.text);
    var reverted = c.tree("reverted");
    assertFalse(line(reverted, "label=\"Save…\"").contains("disabled"), reverted);
    // Its source is at stake: closing the tab asks.
    c.run(app, "press #source\nkey W control\n" + OPENS + "snap close\n");
    var close = c.tree("close");
    assertTrue(close.contains("role=dialog label=\"Discard the edits?\""), close);
    assertTrue(close.contains("no longer on the server"), close);
  }

  @Test
  void cancelStopsOnlyUntilTheDropAndAClosedTabsOutcomeIsANoticeThatExitWaitsFor()
      throws Exception {
    var app = mySave();
    var create = "define " + MYSQL + " " + MY_CHANGED;
    var gate = c.servers.hold(create);
    try {
      c.run(app, "press role=button label=\"Save\"\n" + OPENS);
      held(create);
      // A press builds the view anew, which reads that the routine was dropped.
      c.run(app, "press #source\nframe\nsnap held\n");
      var held = c.tree("held");
      assertTrue(line(held, "#cancel-change").contains("disabled"), held);
      // The tab closes after its question, and the change goes on.
      c.run(
          app,
          "press #source\nkey W control\n" + OPENS + "press role=button label=\"Discard\"\n"
              + OPENS);
      assertTrue(app.state().routines(null).isEmpty());
      app.state().requestClose();
      c.run(app, "frame\n");
      assertFalse(app.windows().all().isEmpty(), "Exit waits for the change");
      var failed = FakeServers.serverError(Failure.Kind.OTHER, "Made to fail");
      c.servers
          .definitionFails(MYSQL, MY_CHANGED, failed)
          .definitionFails(MYSQL, MY_SOURCE, failed);
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE + SETTLE);
    assertFalse(app.windows().all().isEmpty(), "a notice keeps Exit open");
    assertEquals(1, app.state().notices.size(), app.state().notices.toString());
    var notice = app.state().notices.getFirst();
    assertTrue(notice.what().contains("could not be created again"), notice.toString());
  }

  @Test
  void aCancelBeforeTheDropLeavesTheRoutineAsItWas() throws Exception {
    var app = mySave();
    var mode = "define " + MYSQL + " " + MY_MODE;
    var gate = c.servers.hold(mode);
    try {
      c.run(app, "press role=button label=\"Save\"\n" + OPENS);
      held(mode);
      c.run(app, "frame\npress #cancel-change\nframe\n");
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE + "snap cancelled\n");
    var cancelled = c.tree("cancelled");
    assertTrue(cancelled.contains("nothing of it changed"), cancelled);
    assertFalse(
        c.servers.calls.contains("define " + MYSQL + " DROP FUNCTION IF EXISTS legacy.`add`"),
        c.servers.calls.toString());
    assertEquals(MY_ADD, app.state().routines(null).getFirst().routine);
  }

  @Test
  void whereTheServerDropsFirstATextOfAnotherRoutineIsNotSaved() throws Exception {
    c.servers.routine(MYSQL, MY_ADD, MY_SOURCE);
    var app = routines(LEGACY.withName("legacy-prod"), "legacy");
    c.run(
        app,
        "press role=treeitem label=\"add(int, int)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS
            + edit(MY_SOURCE.replace("`add`", "`plus`"))
            + "press role=button label=\"Save…\"\n" + OPENS + "snap refused\n");
    var refused = c.tree("refused");
    assertFalse(refused.contains("role=dialog"), refused);
    assertTrue(refused.contains("creates the function plus, not add(int, int)"), refused);
    assertTrue(c.servers.calls.stream().noneMatch(k -> k.startsWith("define ")));
  }

  @Test
  void anEditedTabAsksBeforeItClosesButNotBeforeItsConnectionDisconnects() throws Exception {
    var app = shop();
    c.run(
        app,
        "press role=treeitem label=\"add(integer, integer)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS + "press #source\ntype \" \"\n"
            + "frame\nkey W control\n" + OPENS + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Discard the edits?\""), asked);
    c.run(app, "press role=button label=\"Keep editing\"\n" + OPENS + "snap kept\n");
    assertEquals(1, app.state().routines(null).size());
    assertTrue(c.tree("kept").contains("1 routine has unsaved edits"), c.tree("kept"));
    c.run(app, menu("shop-prod", "Disconnect") + SETTLE + "snap disconnected\n");
    var disconnected = c.tree("disconnected");
    assertFalse(disconnected.contains("Discard the edits?"), disconnected);
    assertEquals(1, app.state().routines(null).size());
    c.run(
        app,
        "press #source\nkey W control\n" + OPENS + "press role=button label=\"Discard\"\n" + OPENS);
    assertTrue(app.state().routines(null).isEmpty());
  }

  @Test
  void runAsksAValueOfEachArgumentWhoseTypeHasNoEmptyOne() throws Exception {
    var app = shop();
    c.run(
        app,
        menu("add(integer, integer)", "Run…") + SETTLE
            + "press role=button label=\"Run\"\nframe\nsnap empty\n");
    var empty = c.tree("empty");
    assertTrue(empty.contains("role=dialog label=\"Run add(integer, integer)\""), empty);
    assertTrue(empty.contains("Enter a value of integer, or check NULL."), empty);
    assertTrue(app.state().consoles(null).isEmpty());
  }

  @Test
  void aggregatesAndWindowFunctionsAreNotRunAndAnAggregateIsNotRead() throws Exception {
    var sum = new Routine(
        PUBLIC,
        "total",
        "integer",
        Kind.AGGREGATE,
        "bigint",
        List.of(new Parameter("", "integer", Mode.IN)));
    var rank = new Routine(PUBLIC, "ranked", "", Kind.WINDOW, "bigint", List.of());
    var first = new Routine(
        PUBLIC,
        "first",
        "a anyarray",
        Kind.FUNCTION,
        "anyelement",
        List.of(new Parameter("a", "anyarray", Mode.IN)));
    c.servers
        .routine(PG, sum, "")
        .routine(PG, rank, "CREATE OR REPLACE FUNCTION public.ranked() …")
        .routine(PG, first, "CREATE OR REPLACE FUNCTION public.first(a anyarray) …");
    var app = shop();
    for (var label : List.of("total(integer)", "ranked()", "first(anyarray)")) {
      c.run(
          app,
          "press role=treeitem label=\"" + label + "\" right\n" + OPENS + "snap menu\nkey Escape\n"
              + OPENS);
      var menu = c.tree("menu");
      assertTrue(line(menu, "label=\"Run…\"").contains("disabled"), label + "\n" + menu);
    }
    c.run(
        app,
        "press role=treeitem label=\"total(integer)\"\nkey Enter\n" + SETTLE + "snap aggregate\n");
    var aggregate = c.tree("aggregate");
    assertTrue(aggregate.contains("does not write the source of an aggregate"), aggregate);
    assertFalse(aggregate.contains("Source not read"), aggregate);
    assertTrue(c.servers.calls.stream()
        .noneMatch(k -> k.startsWith("source " + PG + "/shop.public total")));
  }

  // Waits until the fake took the call of the key, which then waits at its gate.
  private void held(String key) throws InterruptedException {
    assertTrue(c.servers.awaitCall(key, Duration.ofSeconds(5)), c.servers.calls.toString());
  }

  @Test
  void dropAsksFirstAndTheTreeLosesTheRoutine() throws Exception {
    var app = shop();
    c.run(app, menu("add(integer, integer)", "Drop…") + SETTLE + "snap asked\n");
    var asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Drop routine\""), asked);
    assertTrue(asked.contains("DROP FUNCTION public.add(a integer, b integer)"), asked);
    c.run(app, "press role=button label=\"Drop\"\n" + OPENS + SETTLE + "snap dropped\n");
    var dropped = c.tree("dropped");
    assertFalse(dropped.contains("role=dialog"), dropped);
    assertTrue(
        c.servers.calls.contains(
            "define " + PG + " DROP FUNCTION public.add(a integer, b integer)"),
        c.servers.calls.toString());
    var rows = rows(dropped);
    assertFalse(rows.contains("        add(integer, integer)"), rows.toString());
    assertTrue(rows.contains("        add(text)"), rows.toString());
  }

  @Test
  void runOpensAConsoleThatBindsTheValues() throws Exception {
    var call = "SELECT * FROM public.add(CAST(? AS integer), CAST(? AS integer))";
    c.servers.statement(call, List.of(FakeServers.made(1, "add: integer")));
    var app = shop();
    c.run(app, menu("add(integer, integer)", "Run…") + SETTLE + "snap form\n");
    var form = c.tree("form");
    assertTrue(form.contains("role=dialog label=\"Run add(integer, integer)\""), form);
    c.run(
        app,
        "press label=\"a (integer)\"\ntype \"2\"\npress label=\"NULL b\"\nframe\n"
            + "press role=button label=\"Run\"\n" + OPENS + SETTLE + "snap ran\n");
    var ran = c.tree("ran");
    assertTrue(c.servers.calls.contains("bind " + PG + " 2, NULL"), c.servers.calls.toString());
    assertTrue(c.servers.calls.contains("run " + PG + " " + call), c.servers.calls.toString());
    var console = app.state().consoles(null).getFirst();
    assertEquals(call + ";\n", console.text);
    assertTrue(ran.contains("role=grid"), ran);
  }

  @Test
  void aReadOnlyConnectionRunsRoutinesButChangesNone() throws Exception {
    var app = routines(SHOP.withReadOnly(true), "shop", "public");
    c.run(app, "press role=treeitem label=\"Routines\" right\n" + OPENS + "snap menu\n");
    var menu = c.tree("menu");
    assertTrue(line(menu, "label=\"New function…\"").contains("disabled"), menu);
    c.run(
        app,
        "key Escape\n" + OPENS + "press role=treeitem label=\"add(integer, integer)\"\nkey Enter\n"
            + SETTLE + "snap opened\n");
    var opened = c.tree("opened");
    assertTrue(line(opened, "label=\"Edit\"").contains("disabled"), opened);
    assertTrue(line(opened, "label=\"Drop…\"").contains("disabled"), opened);
    assertFalse(line(opened, "label=\"Run…\"").contains("disabled"), opened);
  }

  @Test
  void aNewFunctionStartsFromTheTemplateAndTakesTheRoutineItSaved() throws Exception {
    var template = SHOP.provider().dialect().routines().template(Kind.FUNCTION, PUBLIC);
    var made = new Routine(
        PUBLIC, "new_function", "a integer, b integer", Kind.FUNCTION, "integer", ADD.parameters());
    c.servers.defines(PG, template, () -> c.servers.routine(PG, made, template));
    var app = shop();
    c.run(app, menu("Routines", "New function…") + SETTLE + "snap new\n");
    var created = c.tree("new");
    assertTrue(created.contains("CREATE FUNCTION public.new_function"), created);
    c.run(
        app,
        "press role=button label=\"Save…\"\n" + OPENS + "press role=button label=\"Save\"\n" + OPENS
            + SETTLE + "snap saved\n");
    assertTrue(
        c.servers.calls.contains("define " + PG + " " + template), c.servers.calls.toString());
    var tab = app.state().routines(null).getFirst();
    assertEquals(made, tab.routine);
    assertEquals("new_function(integer, integer)", app.state().title(tab));
    assertTrue(rows(c.tree("saved")).contains("        new_function(integer, integer)"));
  }

  // The line of the element tree that holds the text.
  private static String line(String tree, String text) {
    return tree.lines().filter(l -> l.contains(text)).findFirst().orElse("");
  }

  private static String escaped(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}

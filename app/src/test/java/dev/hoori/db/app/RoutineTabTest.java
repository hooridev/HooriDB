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
import dev.hoori.db.core.log.Purpose;
import java.nio.file.Path;
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
  void whereTheServerCannotReplaceARoutineSaveDropsItFirst() throws Exception {
    var add = new Routine(
        LEGACY_DB,
        "add",
        "",
        Kind.FUNCTION,
        "int",
        List.of(new Parameter("a", "int", Mode.IN), new Parameter("b", "int", Mode.IN)));
    var source = "CREATE DEFINER=`app`@`%` FUNCTION `add`(a INT, b INT) RETURNS int\n"
        + "    DETERMINISTIC\nRETURN a + b";
    c.servers.routine(MYSQL, add, source);
    var app = routines(LEGACY.withName("legacy-prod"), "legacy");
    c.run(
        app,
        "press role=treeitem label=\"add(int, int)\"\nkey Enter\n" + SETTLE
            + "press role=button label=\"Edit\"\n" + OPENS
            + "press role=button label=\"Save…\"\n" + OPENS + "snap preview\n");
    var preview = c.tree("preview");
    assertTrue(preview.contains("DROP FUNCTION IF EXISTS legacy.`add`"), preview);
    assertTrue(preview.contains("dropped first"), preview);
    c.run(app, "press role=button label=\"Save\"\n" + OPENS + SETTLE + "snap saved\n");
    var defined = c.servers.calls.stream().filter(k -> k.startsWith("define ")).toList();
    assertEquals(
        List.of(
            "define " + MYSQL + " DROP FUNCTION IF EXISTS legacy.`add`",
            "define " + MYSQL + " " + source),
        defined);
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

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.TableTabTest.KINDS;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.ExtendedJson;
import dev.hoori.db.core.document.Values;
import dev.hoori.db.core.execution.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The value of the current cell beside the grid: whole, and JSON formatted. */
class ValuePanelTest {

  /** A JSON value longer than a page holds. */
  static final String LONG = "{\"items\": ["
      + IntStream.range(0, 60)
          .mapToObj(i -> "{\"n\": " + i + ", \"name\": \"item " + i + "\"}")
          .collect(Collectors.joining(", "))
      + "]}";

  // The doc column of kinds, a jsonb.
  static final int DOC = 11;

  @TempDir
  Path dir;

  final TableTabTest t = new TableTabTest();

  @BeforeEach
  void setUp() throws Exception {
    t.dir = dir;
    t.setUp();
  }

  @AfterEach
  void tearDown() throws Exception {
    t.tearDown();
  }

  /** A row of kinds with only its key and its doc. */
  static List<Object> kinds(long id, Object doc) {
    var row = new Object[12];
    row[0] = id;
    row[DOC] = doc;
    return Arrays.asList(row);
  }

  /** The editor of the panel's value, with its text. */
  static String editor(String tree) {
    return tree.lines()
        .filter(l -> l.contains("#value role=textbox"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No value in\n" + tree));
  }

  /** The text as a tree dump writes it, between its quotes. */
  static String dumped(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  App<State> kinds(List<List<Object>> rows) throws Exception {
    t.servers.values(KINDS, rows);
    var app = t.shop();
    t.c.run(app, "size 2600 760\nframe\n" + table("kinds") + SETTLE);
    return app;
  }

  @Test
  void aCutJsonValueIsReadWholeByItsKeyAndShowsFormatted() throws Exception {
    t.servers.whole(KINDS, "doc", "2", LONG);
    var app = kinds(List.of(
        kinds(1, "{\"a\": 1}"), kinds(2, new Cut(LONG.substring(0, 1024), OptionalLong.empty()))));
    t.c.run(
        app,
        "press #cell-1-" + DOC + "\nkey Enter shift\nadvance 1\nframe\n" + SETTLE + "snap whole\n");
    String whole = t.c.tree("whole");
    // Formatted whole: the dump writes its start, its end and its length.
    var formatted = Json.format(LONG).text();
    var editor = editor(whole);
    assertTrue(editor.contains(" \"" + dumped(formatted.substring(0, 60))), editor);
    assertTrue(
        editor.contains(dumped(formatted.substring(formatted.length() - 25)) + "\" length "
            + formatted.length() + " "),
        editor);
    assertTrue(whole.contains("text role=heading \"doc\""), whole);
    assertTrue(t.servers.calls.contains("whole " + KINDS + " doc 2"), t.servers.calls.toString());
    // The panel follows the selection: a value read whole shows as it is.
    t.c.run(app, "key ArrowUp\nframe\nsnap first\n");
    assertTrue(
        editor(t.c.tree("first")).contains("\"" + dumped("{\n    \"a\": 1\n}") + "\""),
        t.c.tree("first"));
    // Shift+Enter closes it from the grid, and from the panel.
    t.c.run(app, "key Enter shift\nadvance 1\nframe\nsnap closed\n");
    assertFalse(t.c.tree("closed").contains("#value-panel"), t.c.tree("closed"));
    assertFalse(t.tab(app).value.open);
    t.c.run(app, """
            key Enter shift
            advance 1
            frame
            press #value
            key Enter shift
            advance 1
            frame
            snap closed-there
            """);
    assertFalse(t.c.tree("closed-there").contains("#value-panel"), t.c.tree("closed-there"));
    assertFalse(t.tab(app).value.open);
  }

  @Test
  void jsonThatIsNotValidShowsAsItCameWithItsProblem() throws Exception {
    var app = kinds(List.of(kinds(1, "{\"a\": }")));
    t.c.run(
        app,
        "press #cell-0-" + DOC + "\npress role=button label=\"Value\"\nadvance 1\nframe\n"
            + "snap invalid\n");
    var editor = editor(t.c.tree("invalid"));
    assertTrue(editor.contains("\"" + dumped("{\"a\": }") + "\""), editor);
    assertTrue(editor.contains("squiggle 6-7"), editor);
  }

  @Test
  void aCellShownNoLongerIsNotReadWhole() throws Exception {
    var key = "whole " + KINDS + " doc 2";
    t.servers.hold(key);
    var app = kinds(List.of(
        kinds(1, "{\"a\": 1}"), kinds(2, new Cut(LONG.substring(0, 1024), OptionalLong.empty()))));
    t.c.run(app, "press #cell-1-" + DOC + "\nkey Enter shift\nadvance 1\nframe\nsnap waiting\n");
    eventually(() -> t.servers.calls.contains(key));
    assertTrue(t.c.tree("waiting").contains("label=\"Reading the value\""), t.c.tree("waiting"));
    t.c.run(app, "key ArrowUp\nframe\nsnap moved\n");
    assertTrue(
        editor(t.c.tree("moved")).contains("\"" + dumped("{\n    \"a\": 1\n}") + "\""),
        t.c.tree("moved"));
    eventually(() -> t.servers.interrupted.contains(key));
  }

  @Test
  void aWholeValueThatCannotBeReadSaysWhy() throws Exception {
    var app = kinds(List.of(kinds(2, new Cut(LONG.substring(0, 1024), OptionalLong.empty()))));
    t.c.run(
        app,
        "press #cell-0-" + DOC + "\nkey Enter shift\nadvance 1\nframe\n" + SETTLE + "snap gone\n");
    String gone = t.c.tree("gone");
    assertTrue(
        gone.contains("\"Could not be read whole: the server's words for NOT_FOUND\""), gone);
    // What the page holds shows meanwhile, as it came.
    assertTrue(editor(gone).contains(" \"" + dumped(LONG.substring(0, 60))), editor(gone));
  }

  @Test
  void aTableWithoutAKeyShowsWhatThePageHolds() throws Exception {
    var logs = CatalogTreeTest.PG + "/shop.public.logs";
    t.servers.values(logs, List.of(List.of(new Cut("x".repeat(1024), OptionalLong.of(5_000)))));
    var app = t.shop();
    t.c.run(
        app,
        table("logs") + SETTLE + "press #cell-0-0\nkey Enter shift\nadvance 1\nframe\n" + SETTLE
            + "snap keyless\n");
    String keyless = t.c.tree("keyless");
    assertTrue(
        keyless.contains("\"Cut short: the first 1024 characters of 5.0 kB; without a primary key"
            + " the rest cannot be read\""),
        keyless);
    assertTrue(editor(keyless).contains(" \"xxxxxxxx"), editor(keyless));
    assertTrue(t.servers.calls.stream().noneMatch(call -> call.startsWith("whole")));
  }

  @Test
  void aConsolesResultShowsLongJsonWholeAndCutBeyondBudgetWithANote() throws Exception {
    var sql = "SELECT doc FROM kinds";
    t.servers.statement(
        sql,
        List.of(new Result.Rows(
            List.of(new Field("doc", "jsonb", Kind.JSON)),
            0,
            List.of(
                List.of(LONG),
                List.of(new Cut(LONG.substring(0, Rows.LIMIT), OptionalLong.empty())),
                List.of("[1, 2]")))));
    var app = t.c.app(SHOP);
    t.c.ready(app, SHOP);
    t.c.run(
        app,
        START + menu(SHOP.name(), "New console") + SETTLE + ConsoleTest.type(sql) + ConsoleTest.RUN
            + "press #cell-0-0\nframe\nkey C control\nframe\n"
            + "key Enter shift\nadvance 1\nframe\nsnap whole\n"
            + "key ArrowDown\nframe\nsnap cut\n"
            + "key ArrowDown\nframe\nsnap next\n");
    // Copying a cell gives the whole value, even though the grid cell draws only the start.
    assertEquals(LONG, DataGrid.parseText(app.clipboard().read()).getFirst().getFirst());
    String whole = t.c.tree("whole");
    var gridCell = TableTabTest.cell(whole, 0, 0);
    assertTrue(gridCell.contains(dumped(LONG.substring(0, 60))), gridCell);
    assertFalse(gridCell.contains("item 59"), gridCell);
    assertFalse(gridCell.contains("more_horizontal"), gridCell);
    var formatted = Json.format(LONG).text();
    var wholeEditor = editor(whole);
    assertTrue(wholeEditor.contains(" \"" + dumped(formatted.substring(0, 60))), wholeEditor);
    assertTrue(
        wholeEditor.contains(dumped(formatted.substring(formatted.length() - 25)) + "\" length "
            + formatted.length() + " "),
        wholeEditor);
    assertFalse(whole.contains("Cut short"), whole);
    String cut = t.c.tree("cut");
    assertTrue(
        cut.contains("\"Cut short: the first 1024 characters; a result keeps no more\""), cut);
    assertTrue(editor(cut).contains(" \"" + dumped(LONG.substring(0, 60))), editor(cut));
    String next = t.c.tree("next");
    assertTrue(editor(next).contains("\"" + dumped("[\n    1,\n    2\n]") + "\""), editor(next));
    assertFalse(next.contains("Cut short"), next);
    // Copying more cells gives what the grid shows, which bounds a large selection's text.
    t.c.run(app, "press #cell-0-0\nframe\nkey ArrowDown shift\nframe\nkey C control\nframe\n");
    var copied = DataGrid.parseText(app.clipboard().read());
    assertEquals(Cells.cell(LONG, ZoneOffset.UTC), copied.getFirst().getFirst());
    assertEquals(LONG.substring(0, Rows.LIMIT), copied.get(1).getFirst());
  }

  @Test
  void aGridCellShowsOnlyTheStartOfALongStringWithoutSplittingASurrogatePair() {
    var boundary = "a".repeat(Rows.LIMIT - 1) + "😀" + "z";
    assertEquals("a".repeat(Rows.LIMIT - 1) + "😀", Cells.cell(boundary, ZoneOffset.UTC));
    assertEquals(boundary, Cells.text(boundary, ZoneOffset.UTC));
    var exact = "😀".repeat(Rows.LIMIT);
    assertEquals(exact, Cells.cell(exact, ZoneOffset.UTC));
    var longer = exact + "😀";
    assertEquals(exact, Cells.cell(longer, ZoneOffset.UTC));
  }

  @Test
  void aMongoDbConsolesValuePanelShowsTheDocumentsValueWhole() throws Exception {
    t.c.servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: int
            payload: object
            note: string
        """);
    var items = IntStream.range(0, 60)
        .mapToObj(i -> (Object) Document.of("n", i, "name", "item " + i))
        .toList();
    var payload = Document.of("items", items);
    var note = "n".repeat(Rows.LIMIT + 50);
    var first = Document.of("payload", payload, "note", note);
    var second = Document.of("payload", null);
    var documents = List.of(first, second);
    var fields = Values.found(documents);
    var rows = documents.stream().map(d -> Values.row(fields, d)).toList();
    var query = "db.orders.find()";
    t.c.servers.statement(query, List.of(new Result.Rows(fields, 0, rows, documents)));
    var app = t.c.app(DocumentConsoleTest.DOCS);
    t.c.ready(app, DocumentConsoleTest.DOCS);
    t.c.run(
        app,
        START + menu(DocumentConsoleTest.DOCS.name(), "New console") + SETTLE
            + ConsoleTest.type(query) + ConsoleTest.RUN
            + "press #cell-0-0\nframe\nkey C control\nframe\n"
            + "key Enter shift\nadvance 1\nframe\nsnap payload\n"
            + "key ArrowRight\nframe\nsnap note\n"
            + "key ArrowDown\nframe\nsnap missing\n"
            + "key ArrowLeft\nframe\nsnap nulled\n");
    var wholeJson = ExtendedJson.of(payload);
    assertEquals(
        wholeJson, DataGrid.parseText(app.clipboard().read()).getFirst().getFirst());
    var payloadTree = t.c.tree("payload");
    var gridCell = TableTabTest.cell(payloadTree, 0, 0);
    assertTrue(gridCell.contains("more_horizontal"), gridCell);
    assertFalse(gridCell.contains("item 59"), gridCell);
    var formatted = Json.format(wholeJson).text();
    var payloadEditor = editor(payloadTree);
    assertTrue(payloadEditor.contains(" \"" + dumped(formatted.substring(0, 60))), payloadEditor);
    assertTrue(
        payloadEditor.contains(dumped(formatted.substring(formatted.length() - 25)) + "\" length "
            + formatted.length() + " "),
        payloadEditor);
    assertFalse(payloadTree.contains("Cut short"), payloadTree);
    var noteTree = t.c.tree("note");
    assertTrue(editor(noteTree).contains(" length " + note.length() + " "), editor(noteTree));
    assertFalse(noteTree.contains("Cut short"), noteTree);
    assertTrue(t.c.tree("missing").contains("\"No such field\""), t.c.tree("missing"));
    assertTrue(t.c.tree("nulled").contains("\"NULL\""), t.c.tree("nulled"));
  }

  @Test
  void aFieldADocumentLacksIsAnEmptyCellAndANullIsNull() throws Exception {
    var sql = "SELECT a, b FROM kinds";
    t.servers.statement(
        sql,
        List.of(new Result.Rows(
            List.of(new Field("a", "string", Kind.TEXT), new Field("b", "string", Kind.TEXT)),
            0,
            List.of(Arrays.asList(Missing.FIELD, null)))));
    var app = t.c.app(SHOP);
    t.c.ready(app, SHOP);
    t.c.run(
        app,
        START + menu(SHOP.name(), "New console") + SETTLE + ConsoleTest.type(sql) + ConsoleTest.RUN
            + "clipboard \"x\"\npress #cell-0-0\nframe\nkey C control\nframe\n"
            + "key Enter shift\nadvance 1\nframe\nsnap missing\n"
            + "key ArrowRight\nframe\nsnap null\n");
    // The kit's empty field, quoted where it is alone on its line, as an empty text copies.
    assertEquals("\"\"", app.clipboard().read());
    String missing = t.c.tree("missing");
    assertFalse(TableTabTest.cell(missing, 0, 0).contains("NULL"), missing);
    assertTrue(TableTabTest.cell(missing, 0, 1).contains("\"NULL\""), missing);
    assertTrue(missing.contains("\"No such field\""), missing);
    String nulled = t.c.tree("null");
    assertFalse(nulled.contains("No such field"), nulled);
  }

  /** Changing the time zone setting updates a moment already shown in the value panel. */
  @Test
  void changingTimeZoneUpdatesTheValuePanel() throws Exception {
    var row = new Object[12];
    row[0] = 1L;
    row[9] = OffsetDateTime.of(2026, 11, 13, 13, 30, 0, 0, ZoneOffset.UTC);
    var app = kinds(List.of(Arrays.asList(row)));
    t.c.run(app, "press #cell-0-9\nkey Enter shift\nadvance 1\nframe\nsnap berlin\n");
    assertTrue(
        editor(t.c.tree("berlin")).contains("\"2026-11-13 14:30:00+01\""), t.c.tree("berlin"));

    app.post(() -> app.state().choose(app.state().settings.withTimeZone(ZoneId.of("UTC"))));
    t.c.run(app, "await\nframe\nsnap utc\n");
    assertTrue(editor(t.c.tree("utc")).contains("\"2026-11-13 13:30:00+00\""), t.c.tree("utc"));
  }

  /** Copy NULL setting controls single-cell and multi-cell copies in console results. */
  @Test
  void copyNullSettingControlsHowConsoleResultNullsAreCopied() throws Exception {
    var sql = "SELECT a, b FROM kinds";
    t.servers.statement(
        sql,
        List.of(new Result.Rows(
            List.of(new Field("a", "string", Kind.TEXT), new Field("b", "string", Kind.TEXT)),
            0,
            List.of(Arrays.asList(Missing.FIELD, null)))));
    var app = t.c.app(SHOP);
    t.c.ready(app, SHOP);
    t.c.run(
        app,
        START + menu(SHOP.name(), "New console") + SETTLE + ConsoleTest.type(sql) + ConsoleTest.RUN
            + "press #cell-0-1\nframe\nkey C control\nframe\n");
    assertEquals("\"\"", app.clipboard().read(), "default copies NULL as empty");

    app.post(() -> app.state().choose(app.state().settings.withCopyNull(Settings.CopyNull.NULL)));
    t.c.run(app, "await\nframe\nkey C control\nframe\n");
    assertEquals("NULL", app.clipboard().read());

    // Multi-cell copy: missing field stays empty, NULL becomes NULL, then \N.
    t.c.run(app, "press #cell-0-0\nframe\nkey ArrowRight shift\nframe\nkey C control\nframe\n");
    assertEquals("\tNULL", app.clipboard().read());

    app.post(
        () -> app.state().choose(app.state().settings.withCopyNull(Settings.CopyNull.BACKSLASH_N)));
    t.c.run(app, "await\nframe\nkey C control\nframe\n");
    assertEquals("\t\\N", app.clipboard().read());
  }

  @Test
  void theCellsPageStaysWhileThePanelShowsItAndARowGoneShowsNone() throws Exception {
    t.servers.rows(TableTabTest.BIG, 10_000, OptionalLong.of(10_000));
    var app = t.shop();
    t.c.run(
        app,
        table("big") + SETTLE + "press #cell-2-1\nkey Enter shift\nadvance 1\nframe\n"
            + IntStream.rangeClosed(1, 12)
                .mapToObj(i -> TableTabTest.scroll(i * 400L) + SETTLE)
                .collect(Collectors.joining())
            + "snap far\n");
    // More pages than are kept were read on the way; the one of the cell stays.
    assertTrue(t.servers.calls.contains("read " + TableTabTest.BIG + " 4800-5000"));
    var first = "read " + TableTabTest.BIG + " 0-200";
    assertEquals(1L, t.servers.calls.stream().filter(first::equals).count());
    // After a refresh far from the cell its page is read for the panel, also while it scrolls.
    var held = t.servers.hold(first);
    // The opening reads the rows in view; the frame after it asks for the cell's page.
    t.c.run(app, "press role=button label=\"Refresh\"\nframe\nawait\nframe\n");
    eventually(() -> t.servers.calls.stream().filter(first::equals).count() == 2);
    t.c.run(app, TableTabTest.scroll(5200));
    held.countDown();
    t.c.run(app, SETTLE + "snap refreshed\n");
    assertFalse(t.servers.interrupted.contains(first), t.servers.interrupted.toString());
    assertTrue(editor(t.c.tree("refreshed")).contains("\"name 3\""), t.c.tree("refreshed"));
    assertTrue(editor(t.c.tree("far")).contains("\"name 3\""), editor(t.c.tree("far")));
    t.servers.rows(TableTabTest.BIG, 2, OptionalLong.of(2));
    t.c.run(app, "press role=button label=\"Refresh\"\n" + SETTLE + "snap gone\n");
    String gone = t.c.tree("gone");
    assertFalse(gone.contains("Reading the value"), gone);
    assertTrue(gone.contains("\"No cell\""), gone);
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
    t.servers.whole(KINDS, "doc", "2", LONG);
    var app = kinds(List.of(
        kinds(1, "{\"a\": }"), kinds(2, new Cut(LONG.substring(0, 1024), OptionalLong.empty()))));
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    Script.run(
        "size 1600 760\nframe\npress #cell-1-0\nkey Enter shift\nadvance 1\nframe\nkey End\nframe\n"
            + SETTLE + "snap value-panel-" + look + "\nkey ArrowUp\nframe\nadvance 1\nframe\n"
            + "snap value-panel-invalid-" + look + "\n",
        app,
        out);
    assertTrue(Files.exists(out.resolve("value-panel-" + look + ".png")));
  }
}

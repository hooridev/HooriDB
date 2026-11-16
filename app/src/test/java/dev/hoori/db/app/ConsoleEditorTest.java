package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.INVENTORY;
import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.CatalogueTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A console's editor: where an error is, and what it suggests at the caret. */
class ConsoleEditorTest {

  static final String WRONG = "SELECT id\nFROM orders\nWHERE WHERE id = 1";
  static final String ITEMS = "SELECT count(*) FROM items";
  static final String MARIA_WRONG = "SELECT sku,\n  name\nFROM items\nWHERE sku IN ('a',, 'b')";

  @TempDir
  Path dir;

  final ConsoleTest console = new ConsoleTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    console.dir = dir;
    console.setUp();
    servers = console
        .servers
        .statement(ITEMS, List.of(FakeServers.made(1, "count: bigint")))
        .statement(
            WRONG,
            List.of(new Failure(
                Failure.Kind.INVALID,
                "syntax error at or near \"WHERE\"",
                "42601",
                0,
                WRONG.indexOf("WHERE id") + 1,
                "",
                0,
                0,
                "",
                "",
                "",
                "")))
        .statement(
            MARIA_WRONG,
            List.of(new Failure(
                Failure.Kind.INVALID,
                "You have an error in your SQL syntax; check the manual that corresponds to your"
                    + " MariaDB server version for the right syntax to use near ' 'b')' at line 4",
                "42000",
                1064,
                0,
                "",
                0,
                4,
                " 'b')",
                "",
                "",
                "")));
  }

  @AfterEach
  void tearDown() throws Exception {
    console.tearDown();
  }

  /** What the console's editor underlines, or null for nothing. */
  Failure.Place underlined(App<State> app) {
    var editor = console.tab(app).editor;
    return editor.errorFrom() == editor.errorTo()
        ? null
        : new Failure.Place(editor.errorFrom(), editor.errorTo());
  }

  static String caret(String tree) {
    return line(tree, "#sql").replaceAll(".* caret (\\d+).*", "$1");
  }

  @Test
  void anErrorInTheSecondStatementIsUnderlinedWhereTheServerSaysAndTheCaretGoesThere()
      throws Exception {
    var app = console.console(SHOP);
    var text = COUNT + ";\n" + WRONG + ";";
    int at = text.indexOf(WRONG) + WRONG.indexOf("WHERE id");
    console.c.run(app, type(text) + RUN_ALL + "snap failed\n");
    String failed = console.c.tree("failed");
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
    assertEquals(String.valueOf(at), caret(failed), line(failed, "#sql"));
    assertTrue(line(failed, "#sql").contains("underline " + at + "-" + (at + 5)), failed);
    assertTrue(failed.contains("\"syntax error at or near \\\"WHERE\\\"\""), failed);
    // Typing drops it, and the message stays.
    console.c.run(app, "type \" \"\nframe\nsnap typed\n");
    assertNull(underlined(app));
    assertTrue(
        console.c.tree("typed").contains("syntax error at or near"), console.c.tree("typed"));
    // Run again, it comes back; while the next run runs, it is gone.
    console.c.run(app, "key Backspace\n" + RUN_ALL);
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
    var held = servers.hold("run " + PG + " " + COUNT);
    console.c.run(app, "key Home control\nkey Enter control\nframe\n");
    assertNull(underlined(app));
    held.countDown();
    console.c.run(app, SETTLE);
    assertNull(underlined(app));
  }

  /** The sixth review's sixth finding: a Run refused while one runs leaves that one's underline. */
  @Test
  void aRunRefusedWhileOneRunsLeavesItsUnderline() throws Exception {
    var app = console.console(SHOP);
    var text = COUNT + ";\n" + WRONG + ";";
    int at = text.indexOf(WRONG) + WRONG.indexOf("WHERE id");
    var key = "run " + PG + " " + WRONG;
    console.c.run(app, type(COUNT + ";") + RUN); // the session is open
    var held = servers.hold(key);
    console.c.run(app, type("\n" + WRONG + ";") + "key Enter control\nframe\nframe\n");
    CatalogueTest.eventually(() -> servers.calls.contains(key));
    // Another statement, refused: something runs.
    console.c.run(app, "key Home control\nkey Enter control\nframe\n");
    held.countDown();
    console.c.run(app, SETTLE);
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
  }

  /** An error that comes while another tab is shown puts the caret there all the same. */
  @Test
  void anErrorWhileAnotherTabIsShownPutsTheCaretThereAsTheTabIsShown() throws Exception {
    var app = console.console(SHOP);
    var text = COUNT + ";\n" + WRONG + ";";
    int at = text.indexOf(WRONG) + WRONG.indexOf("WHERE id");
    var key = "run " + PG + " " + WRONG;
    console.c.run(app, type(COUNT + ";") + RUN); // the session is open
    var held = servers.hold(key);
    console.c.run(app, type("\n" + WRONG + ";") + "key Enter control\nframe\nframe\n");
    CatalogueTest.eventually(() -> servers.calls.contains(key));
    console.c.run(app, "key N control\nframe\nadvance 1\nframe\nsnap other\n");
    var other = console.c.tree("other");
    held.countDown();
    console.c.run(app, SETTLE + "key PageUp control\nframe\nadvance 1\nframe\nsnap shown\n");
    assertEquals(2, app.state().consoles(null).size(), other);
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
    var shown = console.c.tree("shown");
    assertEquals(String.valueOf(at), caret(shown), line(shown, "#sql"));
  }

  /** In Manual a statement is read whole in its one call; its error is underlined all the same. */
  @Test
  void anErrorInManualIsUnderlinedToo() throws Exception {
    var app = console.console(SHOP);
    int at = WRONG.indexOf("WHERE id");
    console.c.run(app, ConsoleTransactionTest.MANUAL + type(WRONG) + RUN);
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
  }

  static final String LATE = "SELECT id / (300 - id) FROM orders";

  /** An error in a later portion of a read, as the grid scrolls, is underlined where it is. */
  @Test
  void anErrorInALaterPortionIsUnderlined() throws Exception {
    int at = LATE.indexOf("/");
    servers.statement(
        LATE,
        List.of(
            FakeServers.made(250, "id: bigint"),
            new Failure(
                Failure.Kind.OTHER,
                "division by zero",
                "22012",
                0,
                at + 1,
                "",
                0,
                0,
                "",
                "",
                "",
                "")));
    var app = console.console(SHOP);
    console.c.run(app, type(LATE) + RUN + "scroll #grid row 180\nframe\nframe\n" + SETTLE);
    assertEquals(new Failure.Place(at, at + 1), underlined(app));
  }

  @Test
  void mariadbsLineAndNearTextFindThePlaceInASelectedStatement() throws Exception {
    var app = console.console(INVENTORY);
    var text = ITEMS + ";\n" + MARIA_WRONG + ";";
    // The second comma of the last line: MariaDB's text begins after it.
    int at = text.indexOf(",, 'b'") + 1;
    // Selected from the start of the second line to the end.
    console.c.run(
        app,
        type(text) + "key Home control\nkey ArrowDown\nkey End control shift\nframe\n" + RUN
            + "snap failed\n");
    String failed = console.c.tree("failed");
    assertEquals(new Failure.Place(at, at + 1), underlined(app));
    assertEquals(String.valueOf(at), caret(failed), line(failed, "#sql"));
  }

  static final String QUOTED = "quoted.example.com";
  static final ConnectionProfile ODD = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("odd")
      .withHost(QUOTED)
      .withDatabase("shop")
      .withUser("shop_app");

  /** The tree's connection open, and the tables of its schema public with their columns. */
  static final String TREE = open("shop-prod")
      + SETTLE
      + open("shop")
      + SETTLE
      + open("public")
      + SETTLE
      + open("orders")
      + SETTLE;

  static final String COMPLETE = "key Space control\nframe\nadvance 1\nframe\n";

  /** The labels of the completions shown, in order. */
  static List<String> options(String tree) {
    return tree.lines()
        .dropWhile(l -> !l.contains("#completions"))
        .filter(l -> l.contains("role=option"))
        .map(l -> l.replaceAll(".* label=\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1").replace("\\\"", "\""))
        .toList();
  }

  @Test
  void afterAnAliasAndADotTheColumnsOfItsTableAlsoWhereItsFromComesLater() throws Exception {
    var app = console.console(SHOP);
    // The caret right after "SELECT " of the statement.
    console.c.run(
        app,
        TREE + type("SELECT  FROM orders o") + "key Home\n" + "key ArrowRight\n".repeat(7)
            + "type \"o.\"\n" + COMPLETE + "snap columns\n");
    var columns = options(console.c.tree("columns"));
    assertEquals(List.of("customer_id", "id", "placed_at", "total"), columns);
    // Typed on: those that start with it.
    console.c.run(app, "type \"t\"\nframe\nsnap typed\n");
    assertEquals(List.of("total", "customer_id", "placed_at"), options(console.c.tree("typed")));
    console.c.run(app, "key Enter\nframe\n");
    assertEquals("SELECT o.total FROM orders o", console.tab(app).text);
  }

  @Test
  void afterASchemaAndADotItsTablesAndViews() throws Exception {
    var app = console.console(SHOP);
    console.c.run(app, TREE + type("SELECT * FROM public.") + COMPLETE + "snap tables\n");
    assertEquals(List.of("customers", "order_totals", "orders"), options(console.c.tree("tables")));
  }

  /** A dot typed after a schema or an alias opens the list at once; one after a number does not. */
  @Test
  void aDotTypedOpensTheListOfWhatComesAfterIt() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app,
        TREE + type("SELECT * FROM public") + "type \".\"\nframe\nadvance 1\nframe\nsnap tables\n"
            + "key A control\nkey Delete\n" + type("SELECT  FROM orders o") + "key Home\n"
            + "key ArrowRight\n".repeat(7) + "type \"o.\"\nframe\nadvance 1\nframe\nsnap columns\n"
            + "key Escape\nkey A control\nkey Delete\n" + type("SELECT 1")
            + "type \".\"\nframe\nadvance 1\nframe\nsnap number\n");
    assertEquals(List.of("customers", "order_totals", "orders"), options(console.c.tree("tables")));
    assertEquals(
        List.of("customer_id", "id", "placed_at", "total"), options(console.c.tree("columns")));
    assertFalse(console.c.tree("number").contains("#completions"), console.c.tree("number"));
  }

  @Test
  void whatIsNotReadYetIsReadOnTheTreesConnectionAndOfferedAtTheNextKeystroke() throws Exception {
    var app = console.console(SHOP);
    var tables = "all tables " + PG + "/shop";
    console.c.run(
        app,
        open("shop-prod") + SETTLE + type("SELECT * FROM o") + "type \"r\"\n"
            + "frame\nsnap unread\n");
    // Nothing of shop is read yet, so nothing fits: the list stays closed while it is read.
    assertEquals(List.of(), options(console.c.tree("unread")));
    console.c.run(app, SETTLE + "type \"d\"\nframe\nadvance 1\nframe\nsnap read\n");
    assertEquals(List.of("order_totals", "orders"), options(console.c.tree("read")));
    assertEquals(
        1, servers.calls.stream().filter(tables::equals).count(), servers.calls.toString());
    // On the tree's connection, not on the console's, which is not even open.
    assertEquals(
        1, servers.calls.stream().filter(("open " + PG + "/shop")::equals).count());
    // The columns of a table of the statement, as soon as it is one.
    console.c.run(
        app,
        "key Escape\ntype \"ers o WHERE \"\n" + COMPLETE + SETTLE
            + "type \"t\"\ntype \"o\"\nframe\n" + "advance 1\nframe\nsnap columns\n");
    assertTrue(
        servers.calls.contains("columns " + PG + "/shop.public.orders"), servers.calls.toString());
    assertEquals("total", options(console.c.tree("columns")).getFirst());
  }

  /** A name that needs quotes is listed as it is and put in quoted. */
  @Test
  void aNameThatNeedsQuotesIsInsertedQuoted() throws Exception {
    servers.serve(QUOTED, Provider.POSTGRESQL, "16.4", """
        shop
          public
            Order Items
              *id: integer
            user
              name: text
          Sales
            Invoices
              *id: integer
        """);
    var app = console.console(ODD);
    console.c.run(
        app,
        open("odd") + SETTLE + type("SELECT * FROM O") + "type \"r\"\nframe\n" + SETTLE
            + "type \"d\"\n" + "frame\nadvance 1\nframe\nsnap odd\nkey Enter\nframe\n");
    assertEquals(List.of("Order Items"), options(console.c.tree("odd")));
    assertEquals("SELECT * FROM \"Order Items\"", console.tab(app).text);
    console.c.run(app, "type \" JOIN u\"\ntype \"s\"\nframe\nadvance 1\nframe\nkey Enter\nframe\n");
    assertEquals("SELECT * FROM \"Order Items\" JOIN \"user\"", console.tab(app).text);
    // Off the search path, with its schema.
    console.c.run(
        app,
        "type \" JOIN I\"\ntype \"n\"\nframe\nadvance 1\nframe\nsnap other\nkey Enter\nframe\n");
    assertEquals(List.of("Sales.Invoices"), options(console.c.tree("other")));
    assertEquals(
        "SELECT * FROM \"Order Items\" JOIN \"user\" JOIN \"Sales\".\"Invoices\"",
        console.tab(app).text);
  }

  @Test
  void closingTheConsoleGivesUpItsRead() throws Exception {
    var app = console.console(SHOP);
    var tables = "all tables " + PG + "/shop";
    var held = servers.hold(tables);
    console.c.run(
        app, open("shop-prod") + SETTLE + type("SELECT * FROM o") + "type \"r\"\n" + "frame\n");
    CatalogueTest.eventually(() -> servers.calls.contains(tables));
    console.c.run(app, "key W control\nframe\n" + SETTLE + "snap closed\n");
    CatalogueTest.eventually(() -> servers.interrupted.contains(tables));
    assertFalse(servers.done.contains(tables), servers.done.toString());
    held.countDown();
  }

  @Test
  void aMariadbConsoleOffersMariadbsKeywordsAndNotPostgresqls() throws Exception {
    var app = console.console(INVENTORY);
    console.c.run(
        app,
        type("SELECT * FROM items ST") + "type \"R\"\nframe\nadvance 1\nframe\nsnap join\n"
            + "key Escape\ntype \";\"\nkey Enter\ntype \"V\"\ntype \"A\"\nframe\nadvance 1\nframe\nsnap va\n");
    // STRAIGHT_JOIN is MariaDB's; VACUUM is PostgreSQL's only, VALUES everyone's.
    assertTrue(options(console.c.tree("join")).contains("STRAIGHT_JOIN"), console.c.tree("join"));
    var va = options(console.c.tree("va"));
    assertTrue(va.contains("VALUES"), va.toString());
    assertFalse(va.contains("VACUUM"), va.toString());
  }

  static final String PATHS = "paths.example.com";
  static final ConnectionProfile AUDITED = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("audited")
      .withHost(PATHS)
      .withDatabase("shop")
      .withUser("shop_app");

  /** Control+Space, and what that reads let in, until the database and its columns are read. */
  static final String READ = (COMPLETE + SETTLE).repeat(3) + COMPLETE;

  @Test
  void aCommentGetsNoCompletionsAndEnterMakesANewLine() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app,
        TREE + type("SELECT 1 -- se") + "type \"l\"\nframe\nadvance 1\nframe\nsnap comment\n"
            + "key Enter\nframe\n");
    assertEquals(List.of(), options(console.c.tree("comment")));
    assertEquals("SELECT 1 -- sel\n", console.tab(app).text);
    console.c.run(
        app, "type \"-- se\"\ntype \"l\"\nframe\nadvance 1\nframe\nsnap alone\nkey Enter\nframe\n");
    assertEquals(List.of(), options(console.c.tree("alone")));
    assertEquals("SELECT 1 -- sel\n-- sel\n", console.tab(app).text);
  }

  @Test
  void aNameWithoutSchemaIsWhereTheSearchPathFindsItAndOthersAreInsertedQualified()
      throws Exception {
    servers.serve(PATHS, Provider.POSTGRESQL, "16.4", """
        shop
          audit
            orders
              *id: integer
              changed_by: text
            events
              *id: bigint
          public
            orders
              *id: bigint
              total: numeric(12,2)
        """);
    var app = console.console(AUDITED);
    console.c.run(
        app,
        open("audited") + SETTLE + type("SELECT  FROM orders o") + "key Home\n"
            + "key ArrowRight\n".repeat(7) + "type \"o.\"\n" + READ + "snap columns\n");
    assertEquals(List.of("id", "total"), options(console.c.tree("columns")));
    console.c.run(
        app,
        "key Escape\nkey A control\ntype \"SELECT * FROM e\"\ntype \"v\"\nframe\nadvance 1\n"
            + "frame\nsnap events\nkey Enter\nframe\n");
    assertEquals(List.of("audit.events"), options(console.c.tree("events")));
    assertEquals("SELECT * FROM audit.events", console.tab(app).text);
  }

  @Test
  void theColumnsOfATableAreReadOnceWhileTheTreeReadsThem() throws Exception {
    var app = console.console(SHOP);
    var key = "columns " + PG + "/shop.public.orders";
    console.c.run(
        app, open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    var held = servers.hold(key);
    // The tree reads the columns of orders, held; the console wants them too meanwhile.
    console.c.run(
        app, open("orders") + type("SELECT * FROM orders o WHERE ") + COMPLETE + "frame\n");
    CatalogueTest.eventually(() -> servers.calls.contains(key));
    held.countDown();
    console.c.run(app, SETTLE + COMPLETE + "snap read\n");
    assertEquals(1, servers.calls.stream().filter(key::equals).count(), servers.calls.toString());
    assertTrue(options(console.c.tree("read")).contains("total"), console.c.tree("read"));
  }

  @Test
  void theTreeReadingColumnsTheConsoleReadsGivesUpTheConsolesRead() throws Exception {
    var app = console.console(SHOP);
    var key = "columns " + PG + "/shop.public.customers";
    console.c.run(
        app, open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    var held = servers.hold(key);
    console.c.run(app, type("SELECT * FROM customers c WHERE ") + COMPLETE + "frame\n");
    CatalogueTest.eventually(() -> servers.calls.contains(key));
    console.c.run(app, "key Escape\n" + open("customers"));
    CatalogueTest.eventually(() -> servers.interrupted.contains(key));
    held.countDown();
    console.c.run(app, SETTLE + "snap tree\n");
    assertTrue(console.c.tree("tree").contains("role=treeitem label=\"email\""));
    assertFalse(servers.overlapped, "two calls ran at once on one connection");
  }

  @Test
  void theListIsMadeAgainOnlyWhenTheTextOrTheCaretChanged() throws Exception {
    var app = console.console(SHOP);
    console.c.run(app, TREE + type("SELECT * FROM or") + "type \"d\"\nframe\nadvance 1\nframe\n");
    var suggestions = console.tab(app).help.suggestions;
    int made = suggestions.made;
    console.c.run(
        app, "key ArrowDown\nframe\nkey ArrowDown\nframe\nkey ArrowUp\nframe\nsnap moved\n");
    assertEquals(made, suggestions.made);
    assertEquals(List.of("order_totals", "orders"), options(console.c.tree("moved")));
    console.c.run(app, "type \"e\"\nframe\n");
    assertEquals(made + 1, suggestions.made);
  }

  @Test
  void aMariadbConsoleCompletesTheTablesOfADatabaseAndTheColumnsOfAnAlias() throws Exception {
    var app = console.console(INVENTORY);
    console.c.run(
        app,
        open("inventory") + SETTLE + type("SELECT * FROM inventory.") + READ + "snap tables\n");
    assertEquals(List.of("items", "low_stock", "stock"), options(console.c.tree("tables")));
    console.c.run(
        app,
        "key Escape\nkey A control\ntype \"SELECT i. FROM inventory.items i\"\nkey Home\n"
            + "key ArrowRight\n".repeat(9) + READ + "snap columns\n");
    assertEquals(List.of("id", "name", "sku"), options(console.c.tree("columns")));
  }

  @Test
  void aConsoleInASecondWindowCompletesAndMarksItsErrorThere() throws Exception {
    var app = console.console(SHOP);
    console.c.run(app, TREE);
    var s = app.state();
    var tab = console.tab(app);
    var group = s.tabs.groupOf(tab);
    s.tabs.toNewWindow(group, group.tabs().indexOf(tab));
    var text = COUNT + ";\n" + WRONG + ";";
    int at = text.indexOf(WRONG) + WRONG.indexOf("WHERE id");
    console.c.run(
        app,
        "frame\nwindow \"shop-prod: shop\"\nframe\n" + type("SELECT * FROM or")
            + "type \"d\"\nframe\nadvance 1\nframe\nsnap there\nkey Escape\nkey A control\n"
            + type(text).replaceFirst("press #sql\n", "") + RUN_ALL + "snap failed\n");
    assertEquals(List.of("order_totals", "orders"), options(window("there")));
    assertEquals(new Failure.Place(at, at + 5), underlined(app));
    assertEquals(String.valueOf(at), caret(window("failed")), line(window("failed"), "#sql"));
  }

  /** The tree of the snap of the window other than the main one. */
  String window(String snap) throws IOException {
    try (var files = Files.list(dir.resolve("out"))) {
      var file = files
          .filter(f -> f.getFileName().toString().endsWith("-" + snap + ".tree.txt"))
          .findFirst()
          .orElseThrow();
      return Files.readString(file);
    }
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
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    var app = console.console(SHOP.withColour(Colour.TEAL));
    app.state().theme = theme;
    Script.run(
        type(COUNT + ";\n" + WRONG + ";") + RUN_ALL + "snap editor-error-" + look + "\n", app, out);
    var maria = console.console(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        type(ITEMS + ";\n" + MARIA_WRONG + ";") + RUN_ALL + "snap editor-error-mariadb-" + look
            + "\n",
        maria,
        out);
    var shots = List.of(
        type("SELECT  FROM orders o") + "key Home\n" + "key ArrowRight\n".repeat(7)
            + "type \"o.\"\n" + COMPLETE + "snap editor-columns-",
        type("SELECT * FROM public.") + COMPLETE + "snap editor-tables-",
        type("SELECT o") + "type \"r\"\ntype \"d\"\nframe\nadvance 1\nframe\nsnap editor-mixed-");
    for (var shot : shots) {
      var completing = console.console(SHOP.withColour(Colour.TEAL));
      completing.state().theme = theme;
      console.c.run(completing, TREE);
      Script.run(shot + look + "\n", completing, out);
    }
  }
}

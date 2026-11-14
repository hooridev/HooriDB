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

  ConsoleEditor help(App<State> app) {
    return console.tab(app).help;
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
    assertEquals(new Failure.Place(at, at + 5), help(app).underlined());
    assertEquals(String.valueOf(at), caret(failed), line(failed, "#sql"));
    assertTrue(failed.contains("\"syntax error at or near \\\"WHERE\\\"\""), failed);
    // Typing drops it, and the message stays.
    console.c.run(app, "type \" \"\nframe\nsnap typed\n");
    assertNull(help(app).underlined());
    assertTrue(
        console.c.tree("typed").contains("syntax error at or near"), console.c.tree("typed"));
    // Run again, it comes back; while the next run runs, it is gone.
    console.c.run(app, "key Backspace\n" + RUN_ALL);
    assertEquals(new Failure.Place(at, at + 5), help(app).underlined());
    var held = servers.hold("run " + PG + " " + COUNT);
    console.c.run(app, "key Home control\nkey Enter control\nframe\n");
    assertNull(help(app).underlined());
    held.countDown();
    console.c.run(app, SETTLE);
    assertNull(help(app).underlined());
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
    assertEquals(new Failure.Place(at, at + 1), help(app).underlined());
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

  @Test
  void aNameThatNeedsQuotesIsInsertedQuoted() throws Exception {
    servers.serve(QUOTED, Provider.POSTGRESQL, "16.4", """
        shop
          public
            Order Items
              *id: integer
            user
              name: text
        """);
    var app = console.console(ODD);
    console.c.run(
        app,
        open("odd") + SETTLE + type("SELECT * FROM O") + "type \"r\"\nframe\n" + SETTLE
            + "type \"d\"\n" + "frame\nadvance 1\nframe\nsnap odd\nkey Enter\nframe\n");
    assertEquals(List.of("\"Order Items\""), options(console.c.tree("odd")));
    assertEquals("SELECT * FROM \"Order Items\"", console.tab(app).text);
    console.c.run(app, "type \" JOIN u\"\ntype \"s\"\nframe\nadvance 1\nframe\nkey Enter\nframe\n");
    assertEquals("SELECT * FROM \"Order Items\" JOIN \"user\"", console.tab(app).text);
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

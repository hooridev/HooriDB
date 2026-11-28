package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A console's editor: where an error is, and what it suggests at the caret. */
class ConsoleEditorTest {

  private static final Pattern CARET = Pattern.compile(" caret (\\d+)");
  private static final Pattern OPTION = Pattern.compile(" label=\"((?:[^\"\\\\]|\\\\.)*+)\"");

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

  /** What the console's editor underlines in the last frame, or null for nothing. */
  Failure.Place underlined(App<State> app) throws Exception {
    console.c.run(app, "snap underlined\n");
    var underline = Pattern.compile(" squiggle (\\d+)-(\\d+)")
        .matcher(line(console.c.tree("underlined"), "#sql"));
    return underline.find()
        ? new Failure.Place(
            Integer.parseInt(underline.group(1)), Integer.parseInt(underline.group(2)))
        : null;
  }

  static String caret(String tree) {
    var l = line(tree, "#sql");
    return CARET.matcher(l).results().map(r -> r.group(1)).findFirst().orElse(l);
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
    assertTrue(line(failed, "#sql").contains("squiggle " + at + "-" + (at + 5)), failed);
    assertTrue(failed.contains("\"syntax error at or near \\\"WHERE\\\"\""), failed);
    assertTrue(line(failed, "#end-3").contains("syntax error at or near"), failed);
    // Typing before it moves the underline and keeps its inline message.
    console.c.run(app, "type \" \"\nframe\nsnap typed\n");
    assertEquals(new Failure.Place(at + 1, at + 6), underlined(app));
    assertTrue(line(console.c.tree("typed"), "#end-3").contains("syntax error at or near"));
    // Inserting a line above moves the underline and its inline message down a line.
    console.c.run(app, "key Home control\nkey Enter\nframe\nsnap shifted\n");
    assertEquals(new Failure.Place(at + 2, at + 7), underlined(app));
    assertTrue(line(console.c.tree("shifted"), "#end-4").contains("syntax error at or near"));
    // Editing the underlined text itself drops the underline and inline message.
    console.c.run(app, "key Z control\nframe\n");
    console.tab(app).editor.select(at + 2, at + 2);
    app.invalidate();
    console.c.run(app, "frame\ntype \"X\"\nframe\nsnap edited\n");
    assertNull(underlined(app));
    assertFalse(console.c.tree("edited").contains("#end-"), console.c.tree("edited"));
    // Undoing back to the sent text restores the underline; while a new run runs, it is gone.
    console.c.run(app, "key Z control\nframe\nkey Z control\nframe\n");
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
    CatalogTreeTest.eventually(() -> servers.calls.contains(key));
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
    CatalogTreeTest.eventually(() -> servers.calls.contains(key));
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
    console.c.run(app, ConsoleTransactions.MANUAL + type(WRONG) + RUN);
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
        .map(l -> OPTION
            .matcher(l)
            .results()
            .map(r -> r.group(1))
            .findFirst()
            .orElse(l)
            .replace("\\\"", "\""))
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
            + "type \".\"\nframe\nadvance 1\nframe\n" + SETTLE + "snap number\n");
    assertEquals(List.of("customers", "order_totals", "orders"), options(console.c.tree("tables")));
    assertEquals(
        List.of("customer_id", "id", "placed_at", "total"), options(console.c.tree("columns")));
    assertFalse(console.c.tree("number").contains("#completions"), console.c.tree("number"));
  }

  @Test
  void whatIsNotReadYetIsReadOnTheTreesConnectionWhileTheListWaitsOnItsLoadingRow()
      throws Exception {
    var app = console.console(SHOP);
    var tables = "all tables " + PG + "/shop";
    var held = servers.hold(tables);
    console.c.run(
        app,
        open("shop-prod") + SETTLE + type("SELECT * FROM o") + "type \"r\"\n"
            + "frame\nadvance 1\nframe\nsnap unread\n");
    // Nothing of shop is read yet: the list stays open on its loading row while it is read.
    var unread = console.c.tree("unread");
    assertEquals(List.of(), options(unread));
    assertTrue(unread.contains("#completions") && unread.contains("#loading"), unread);
    held.countDown();
    // What the read brought shows without another keystroke.
    console.c.run(app, SETTLE + "frame\nsnap arrived\n");
    var arrived = console.c.tree("arrived");
    assertEquals(List.of("order_totals", "orders"), options(arrived).subList(0, 2));
    assertFalse(arrived.contains("#loading"), arrived);
    console.c.run(app, "type \"d\"\nframe\nadvance 1\nframe\nsnap read\n");
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
  void aFailedReadEndsTheLoadingRowAndLeavesWhatTheListHas() throws Exception {
    var app = console.console(SHOP);
    servers.fail(
        "all tables " + PG + "/shop", FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    console.c.run(
        app,
        open("shop-prod") + SETTLE + type("SELECT o") + "type \"r\"\n" + SETTLE
            + "frame\nsnap failed\n");
    var failed = console.c.tree("failed");
    assertTrue(options(failed).contains("ORDER"), failed);
    assertFalse(failed.contains("#loading"), failed);
  }

  @Test
  void aReadThatBringsLessThanItWantedIsNotAskedAgainByItself() throws Exception {
    var keys = "foreign keys " + PG + "/shop.public.orders";
    servers.fail(keys, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    var app = console.console(SHOP);
    console.c.run(
        app,
        TREE + type("SELECT * FROM orders JOIN o") + "type \"r\"\nframe\nadvance 1\nframe\n"
            + SETTLE + SETTLE + "snap settled\n");
    long asked = servers.calls.stream().filter(keys::equals).count();
    console.c.run(app, SETTLE + SETTLE + SETTLE + "snap later\n");
    assertEquals(
        asked, servers.calls.stream().filter(keys::equals).count(), servers.calls.toString());
    var later = console.c.tree("later");
    assertTrue(options(later).contains("orders"), later);
    assertFalse(later.contains("#loading"), later);
  }

  @Test
  void theHighlightedTableShowsItsDocumentationBesideTheList() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app,
        TREE + type("SELECT * FROM or") + "type \"d\"\nframe\nadvance 1\nframe\n"
            + "key ArrowDown\nframe\n" + DOCUMENTED + "snap orders\n");
    var orders = console.c.tree("orders");
    assertEquals(List.of("order_totals", "orders"), options(orders));
    assertTrue(orders.contains("#documentation"), orders);
    assertTrue(orders.contains("Table public.orders"), orders);
    assertTrue(orders.contains("total numeric(12,2)"), orders);
  }

  @Test
  @Disabled("The documentation of an unchanged entry is not asked again: rethinkj/ui#947")
  void theDocumentationOfATableWhoseColumnsWereUnreadShowsThemWhileTheHighlightStays()
      throws Exception {
    var app = console.console(SHOP);
    // The table at first, which reads its columns, and once they are, its columns.
    console.c.run(
        app,
        TREE + type("SELECT * FROM cu") + "type \"s\"\n" + "frame\nadvance 1\nframe\n" + DOCUMENTED
            + DOCUMENTED + "snap read\n");
    assertTrue(
        servers.calls.contains("columns " + PG + "/shop.public.customers"),
        servers.calls.toString());
    var read = console.c.tree("read");
    assertTrue(read.contains("email character varying(255)"), read);
  }

  /** The documentation's delay, its work, and the frame that shows it. */
  static final String DOCUMENTED = "advance 1\nframe\nawait\nframe\nadvance 1\nframe\n";

  @Test
  void aColumnOfTwoTablesIsListedForEachWithItsTable() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT i FROM orders o JOIN customers c";
    console.c.run(
        app,
        TREE + type(sql) + "key Home\n" + "key ArrowRight\n".repeat(8) + "type \"d\"\n" + READ
            + "snap repeated\n");
    var repeated = console.c.tree("repeated");
    assertEquals(List.of("id", "id"), options(repeated).subList(0, 2));
    var list = repeated.substring(repeated.indexOf("#completions"));
    // The alias names the table, and the documentation follows the highlight.
    assertTrue(list.contains("text \"o\"") && list.contains("text \"c\""), list);
    console.c.run(
        app, DOCUMENTED + "snap first\nkey ArrowDown\nframe\n" + DOCUMENTED + "snap second\n");
    assertTrue(console.c.tree("first").contains("In table public.orders"), console.c.tree("first"));
    var second = console.c.tree("second");
    assertTrue(second.contains("In table public.customers"), second);
    console.c.run(app, "key Enter\nframe\n");
    assertEquals("SELECT id FROM orders o JOIN customers c", console.tab(app).text);
  }

  @Test
  void anAliasOfTwoTablesInAUnionNamesEachColumnByItsTable() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT i FROM orders t UNION ALL SELECT id FROM customers t";
    console.c.run(
        app,
        TREE + type(sql) + "key Home\n" + "key ArrowRight\n".repeat(8) + "type \"d\"\n" + READ
            + DOCUMENTED + "snap first\nkey ArrowDown\nframe\n" + DOCUMENTED + "snap second\n");
    var first = console.c.tree("first");
    assertEquals(List.of("id", "id"), options(first).subList(0, 2));
    var list = first.substring(first.indexOf("#completions"));
    assertTrue(list.contains("text \"orders\"") && list.contains("text \"customers\""), list);
    assertTrue(first.contains("In table public.orders"), first);
    var second = console.c.tree("second");
    assertTrue(second.contains("In table public.customers"), second);
  }

  @Test
  void anAliasThatIsAnotherTablesNameNamesEachColumnByItsTable() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT i FROM customers orders UNION ALL SELECT id FROM orders";
    console.c.run(
        app,
        TREE + type(sql) + "key Home\n" + "key ArrowRight\n".repeat(8) + "type \"d\"\n" + READ
            + "snap named\n");
    var named = console.c.tree("named");
    assertEquals(List.of("id", "id"), options(named).subList(0, 2));
    var list = named.substring(named.indexOf("#completions"));
    assertTrue(list.contains("text \"orders\"") && list.contains("text \"customers\""), list);
  }

  @Test
  void aColumnOfATableJoinedToItselfIsListedForEachAlias() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT i FROM orders parent JOIN orders child";
    console.c.run(
        app,
        TREE + type(sql) + "key Home\n" + "key ArrowRight\n".repeat(8) + "type \"d\"\n" + READ
            + "snap self\n");
    var self = console.c.tree("self");
    assertEquals(List.of("id", "id"), options(self).subList(0, 2));
    var list = self.substring(self.indexOf("#completions"));
    assertTrue(list.contains("text \"parent\"") && list.contains("text \"child\""), list);
    // Two entries, so the documentation of the second is asked for anew.
    console.c.run(app, "key ArrowDown\nframe\n" + DOCUMENTED + "snap child\n");
    var child = console.c.tree("child");
    assertTrue(
        child.contains("option-1 role=option label=\"id\"") && child.contains("selected"), child);
    assertTrue(child.contains("In table public.orders"), child);
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
    CatalogTreeTest.eventually(() -> servers.calls.contains(tables));
    console.c.run(app, "key W control\nframe\n" + SETTLE + "snap closed\n");
    CatalogTreeTest.eventually(() -> servers.interrupted.contains(tables));
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
    console.c.run(app, """
            key Escape
            key A control
            type "SELECT * FROM e"
            type "v"
            frame
            advance 1
            frame
            snap events
            key Enter
            frame
            """);
    assertEquals(List.of("audit.events"), options(console.c.tree("events")));
    assertEquals("SELECT * FROM audit.events", console.tab(app).text);
    // The name of two tables: the one the server finds says its schema.
    console.c.run(app, """
        key A control
        type "SELECT * FROM o"
        type "r"
        frame
        advance 1
        frame
        snap orders
        """);
    var orders = console.c.tree("orders");
    assertEquals(List.of("orders", "audit.orders"), options(orders).subList(0, 2));
    var list = orders.substring(orders.indexOf("#completions"));
    assertTrue(list.contains("text \"public\""), list);
    assertFalse(list.contains("text \"audit\""), "audit.orders says its schema already: " + list);
  }

  @Test
  void aTableThatAnEarlierSchemaOnTheSearchPathShadowsIsInsertedWithItsSchema() throws Exception {
    servers.serve(PATHS, Provider.POSTGRESQL, "16.4", """
        shop
          shop_app
            orders
              *id: integer
          public
            @orders
              total: numeric
        """);
    var app = console.console(AUDITED);
    console.c.run(
        app,
        open("audited") + SETTLE + type("SELECT * FROM o") + "type \"r\"\n" + READ
            + "snap shadowed\n");
    var shadowed = console.c.tree("shadowed");
    assertEquals(List.of("orders", "public.orders"), options(shadowed).subList(0, 2));
    // The bare name is the table the server takes for it, which its detail names.
    var list = shadowed.substring(shadowed.indexOf("#completions"));
    assertTrue(list.contains("text \"shop_app\""), list);
    console.c.run(app, "key ArrowDown\nframe\nkey Enter\nframe\n");
    assertEquals("SELECT * FROM public.orders", console.tab(app).text);
  }

  @Test
  void aBareTableNameClaimsNoSchemaWhileAnEarlierOneOnTheSearchPathIsRead() throws Exception {
    servers.serve(PATHS, Provider.POSTGRESQL, "16.4", """
        shop
          shop_app
            orders
              *id: integer
          audit
            orders
              *id: integer
          public
            orders
              *id: bigint
        """);
    var app = console.console(AUDITED);
    // The tree read public and audit, not shop_app, which comes first on the search path.
    console.c.run(
        app,
        open("audited")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("audit")
            + SETTLE
            + open("public")
            + SETTLE);
    var held = servers.hold("all tables " + PATHS + "/shop");
    console.c.run(
        app, type("SELECT * FROM o") + "type \"r\"\nframe\nadvance 1\nframe\nsnap unread\n");
    var unread = console.c.tree("unread");
    assertEquals(List.of("orders", "audit.orders"), options(unread).subList(0, 2));
    var list = unread.substring(unread.indexOf("#completions"));
    assertFalse(list.contains("text \"public\""), list);
    held.countDown();
    console.c.run(app, SETTLE + "frame\nsnap read\n");
    var read = console.c.tree("read");
    assertTrue(read.substring(read.indexOf("#completions")).contains("text \"shop_app\""), read);
  }

  @Test
  @Disabled("The documentation of an unchanged entry is not asked again: rethinkj/ui#947")
  void aBareTableNameIsDocumentedOnceAnEarlierSchemaOnTheSearchPathIsReadEmpty() throws Exception {
    servers.serve(PATHS, Provider.POSTGRESQL, "16.4", """
        shop
          shop_app
          public
            orders
              *id: bigint
            zorro
              *id: bigint
        """);
    var app = console.console(AUDITED);
    // The tree read public and orders, whose documentation then changes by its target alone.
    console.c.run(
        app,
        open("audited")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE);
    var held = servers.hold("all tables " + PATHS + "/shop");
    console.c.run(
        app,
        type("SELECT * FROM o") + "type \"r\"\nframe\nadvance 1\nframe\n"
            // Back on orders, the list asks for its documentation: none yet.
            + "key ArrowDown\nframe\nkey ArrowUp\nframe\nadvance 1\nframe\n");
    held.countDown();
    // shop_app has no orders: the bare name is public's, documented without reopening the list.
    console.c.run(app, SETTLE + DOCUMENTED + "snap read\n");
    var read = console.c.tree("read");
    assertEquals(List.of("orders", "zorro"), options(read));
    assertTrue(read.contains("Table public.orders"), read);
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
    CatalogTreeTest.eventually(() -> servers.calls.contains(key));
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
    CatalogTreeTest.eventually(() -> servers.calls.contains(key));
    console.c.run(app, "key Escape\n" + open("customers"));
    CatalogTreeTest.eventually(() -> servers.interrupted.contains(key));
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
    assertTrue(
        line(window("failed"), "#sql").contains("squiggle " + at + "-" + (at + 5)),
        window("failed"));
    assertEquals(String.valueOf(at), caret(window("failed")), line(window("failed"), "#sql"));
  }

  /**
   * Turning off highlightOccurrences drops the highlight of matching words in open SQL and MongoDB
   * consoles without losing their text or caret, and turning it back on restores it.
   */
  @Test
  void highlightOccurrencesTogglesOnOpenSqlAndMongodbConsolesWithoutLosingTextOrCaret()
      throws Exception {
    var out = dir.resolve("out");

    var sql = console.console(SHOP);
    var query = "SELECT orders.id FROM orders";
    console.c.run(
        sql,
        type(query) + "key Home\n" + "key ArrowRight\n".repeat(9) + "frame\nsnap occurrences-on\n");
    var onTree = console.c.tree("occurrences-on");
    var onPng = Files.readAllBytes(out.resolve("occurrences-on.png"));
    assertEquals("9", caret(onTree), line(onTree, "#sql"));

    sql.state().choose(sql.state().settings.withHighlightOccurrences(false));

    sql.invalidate(); // as the handler of a choice would
    console.c.run(sql, "frame\nsnap occurrences-off\n");
    var offTree = console.c.tree("occurrences-off");
    var offPng = Files.readAllBytes(out.resolve("occurrences-off.png"));
    assertEquals(query, console.tab(sql).text);
    assertEquals(line(onTree, "#sql"), line(offTree, "#sql"));
    assertFalse(Arrays.equals(onPng, offPng), "highlight disappeared when turned off");

    sql.state().choose(sql.state().settings.withHighlightOccurrences(true));

    sql.invalidate(); // as the handler of a choice would
    console.c.run(sql, "frame\nsnap occurrences-back\n");
    var backTree = console.c.tree("occurrences-back");
    var backPng = Files.readAllBytes(out.resolve("occurrences-back.png"));
    assertEquals(query, console.tab(sql).text);
    assertEquals(line(onTree, "#sql"), line(backTree, "#sql"));
    assertArrayEquals(onPng, backPng);

    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
        """);
    var mongo = console.console(DocumentConsoleTest.DOCS);
    var shell = "db.orders.find({ orders: 1 })";
    console.c.run(
        mongo,
        type(shell)
            + "key Home\n"
            + "key ArrowRight\n".repeat(5)
            + "frame\nsnap mongo-occurrences-on\n");
    var mongoOnTree = console.c.tree("mongo-occurrences-on");
    var mongoOnPng = Files.readAllBytes(out.resolve("mongo-occurrences-on.png"));
    assertEquals("5", caret(mongoOnTree), line(mongoOnTree, "#sql"));

    mongo.state().choose(mongo.state().settings.withHighlightOccurrences(false));

    mongo.invalidate(); // as the handler of a choice would
    console.c.run(mongo, "frame\nsnap mongo-occurrences-off\n");
    var mongoOffTree = console.c.tree("mongo-occurrences-off");
    var mongoOffPng = Files.readAllBytes(out.resolve("mongo-occurrences-off.png"));
    assertEquals(shell, console.tab(mongo).text);
    assertEquals(line(mongoOnTree, "#sql"), line(mongoOffTree, "#sql"));
    assertFalse(Arrays.equals(mongoOnPng, mongoOffPng), "MongoDB highlight disappeared");

    mongo.state().choose(mongo.state().settings.withHighlightOccurrences(true));

    mongo.invalidate(); // as the handler of a choice would
    console.c.run(mongo, "frame\nsnap mongo-occurrences-back\n");
    assertArrayEquals(mongoOnPng, Files.readAllBytes(out.resolve("mongo-occurrences-back.png")));
  }

  /** SQL keyword completions follow keywordCase: UPPER by default, LOWER when chosen. */
  @Test
  void keywordCaseChangesSqlCompletionsBetweenUpperAndLower() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app, "press #sql\ntype \"sel\"\nframe\nadvance 1\nframe\nsnap upper\nkey Enter\nframe\n");
    var upper = options(console.c.tree("upper"));
    assertEquals("SELECT", upper.getFirst(), upper.toString());
    assertEquals("SELECT", console.tab(app).text);

    app.state().choose(app.state().settings.withKeywordCase(Settings.KeywordCase.LOWER));
    console.c.run(app, """
        key A control
        key Delete
        type "sel"
        frame
        advance 1
        frame
        snap lower
        key Enter
        frame
        """);
    var lower = options(console.c.tree("lower"));
    assertEquals("select", lower.getFirst(), lower.toString());
    assertFalse(lower.contains("SELECT"), lower.toString());
    assertEquals("select", console.tab(app).text);

    app.state().choose(app.state().settings.withKeywordCase(Settings.KeywordCase.UPPER));
    console.c.run(
        app,
        "key A control\nkey Delete\ntype \"sel\"\nframe\nadvance 1\nframe\nkey Enter\nframe\n");
    assertEquals("SELECT", console.tab(app).text);
  }

  /** Finds an element with {@code key} in the tree, storing its window origin in {@code origin}. */
  static El find(El el, String key, float[] origin) {
    if (el == null) {
      return null;
    }
    float x = origin[0] + el.x();
    float y = origin[1] + el.y();
    if (key.equals(el.key())) {
      origin[0] = x;
      origin[1] = y;
      return el;
    }
    for (var child : el.children()) {
      float[] childOrigin = {x, y};
      var found = find(child, key, childOrigin);
      if (found != null) {
        origin[0] = childOrigin[0];
        origin[1] = childOrigin[1];
        return found;
      }
    }
    return null;
  }

  /** Finds logical window coordinates inside {@code #sql} whose text offset is {@code offset}. */
  static String pointAt(App<State> app, int offset) {
    float[] origin = new float[2];
    var sql = find(app.tree(), "sql", origin);
    for (int y = 4; y < sql.height() - 4; y += 6) {
      for (int x = 4; x < sql.width() - 4; x += 2) {
        if (sql.textPositionAt(x, y) == offset) {
          return Math.round(origin[0] + x) + "," + Math.round(origin[1] + y);
        }
      }
    }
    throw new AssertionError("No point for offset " + offset + " in " + sql);
  }

  /** The portion of the tree dump for the gutter line {@code #line-<index>}. */
  static String gutterLine(String tree, int index) {
    var lines = tree.lines().toList();
    String key = "#line-" + index + " ";
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(key)) {
        int end = i + 1;
        while (end < lines.size()) {
          String next = lines.get(end);
          int nextIndent = next.length() - next.stripLeading().length();
          if (nextIndent <= lines.get(i).length() - lines.get(i).stripLeading().length()) {
            break;
          }
          end++;
        }
        return String.join("\n", lines.subList(i, end));
      }
    }
    throw new AssertionError("No #line-" + index + " in\n" + tree);
  }

  @Test
  void quickDocumentationShowsTableAndColumnInfoAndReadsWhatIsMissing() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT o.total, customer_id FROM orders o";
    console.c.run(app, TREE + type(sql) + "frame\n");

    // Hover over table name `orders`: shows kind, schema and columns with types.
    String ordersPt = pointAt(app, sql.indexOf("orders") + 2);
    console.c.run(
        app, "move " + ordersPt + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap table-doc\n");
    String tableDoc = console.c.tree("table-doc");
    assertTrue(tableDoc.contains("#documentation"), tableDoc);
    assertTrue(tableDoc.contains("Table public.orders"), tableDoc);
    assertTrue(tableDoc.contains("id bigint"), tableDoc);
    assertTrue(tableDoc.contains("total numeric(12,2)"), tableDoc);

    // Move away and hover over qualified column `o.total`: shows type and table.
    String totalPt = pointAt(app, sql.indexOf("total") + 2);
    console.c.run(
        app,
        "leave\nframe\nadvance 1\nframe\nframe\nadvance 1\nframe\nmove " + totalPt
            + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap col-doc\n");
    String colDoc = console.c.tree("col-doc");
    assertTrue(colDoc.contains("#documentation"), colDoc);
    assertTrue(colDoc.contains("Column total numeric(12,2)"), colDoc);
    assertTrue(colDoc.contains("In table public.orders"), colDoc);

    // Ctrl+Q on unqualified column `customer_id` shows its documentation too.
    console.tab(app).editor.select(sql.indexOf("customer_id") + 2, sql.indexOf("customer_id") + 2);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\nsnap key-doc\n");
    String keyDoc = console.c.tree("key-doc");
    assertTrue(keyDoc.contains("#documentation"), keyDoc);
    assertTrue(keyDoc.contains("Column customer_id integer"), keyDoc);
    assertTrue(keyDoc.contains("In table public.orders"), keyDoc);

    // Unread table (`customers` has no columns read yet): first hover shows the table and triggers
    // reading its columns on the tree's connection; next hover shows the columns.
    var customersSql = "SELECT * FROM customers";
    console.c.run(app, "key Escape\nkey A control\nkey Delete\n" + type(customersSql) + "frame\n");
    String custPt = pointAt(app, customersSql.indexOf("customers") + 2);
    console.c.run(
        app, "move " + custPt + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-first\n");
    String custFirst = console.c.tree("cust-first");
    assertTrue(custFirst.contains("Table public.customers"), custFirst);
    assertFalse(custFirst.contains("email"), custFirst);
    console.c.run(
        app,
        SETTLE + "leave\nframe\nadvance 1\nframe\nframe\nadvance 1\nframe\nmove " + custPt
            + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-second\n");
    assertTrue(
        servers.calls.contains("columns " + PG + "/shop.public.customers"),
        servers.calls.toString());
    String custSecond = console.c.tree("cust-second");
    assertTrue(custSecond.contains("email character varying(255)"), custSecond);
  }

  @Test
  void quickDocumentationOfAQualifiedTableReadsTheSchemasFirst() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT * FROM public.orders";
    // The connection's databases are read, but not the schemas of shop.
    console.c.run(app, open("shop-prod") + SETTLE + type(sql) + "frame\n");
    String at = pointAt(app, sql.indexOf("orders") + 2);
    String hover = "move " + at + "\nframe\nadvance 1\nframe\nadvance 1\nframe\n";
    String away = "leave\nframe\nadvance 1\nframe\nframe\nadvance 1\nframe\n";
    console.c.run(app, hover + "snap first\n");
    assertFalse(console.c.tree("first").contains("Table public.orders"), console.c.tree("first"));
    console.c.run(app, SETTLE + away + hover + SETTLE + away + hover + "snap read\n");
    assertTrue(console.c.tree("read").contains("Table public.orders"), console.c.tree("read"));
  }

  @Test
  void goToTableOpensTheTableOnControlBAndControlClick() throws Exception {
    var app = console.console(SHOP);
    var sql = "SELECT o.total, c.email FROM orders o JOIN customers c ON c.id = o.customer_id";
    console.c.run(app, TREE + type(sql) + "frame\n");

    // Ctrl+B on table name `orders` opens its table tab.
    int ordersAt = sql.indexOf("orders") + 2;
    console.tab(app).editor.select(ordersAt, ordersAt);
    app.invalidate();
    console.c.run(app, "frame\nkey B control\nframe\n" + SETTLE + "snap orders-tab\n");
    String ordersTab = console.c.tree("orders-tab");
    assertTrue(line(ordersTab, "role=tab label=\"orders\"").contains("selected"), ordersTab);

    // Switch back to the console; Ctrl+B on column `o.total` opens its table (`orders`).
    console.c.run(
        app,
        "press role=tab label=\"shop-prod: shop\"\nframe\nadvance 1\nframe\npress #sql\nframe\n");
    int totalAt = sql.indexOf("total") + 2;
    console.tab(app).editor.select(totalAt, totalAt);
    app.invalidate();
    console.c.run(app, "frame\nkey B control\nframe\n" + SETTLE + "snap col-tab\n");
    assertTrue(
        line(console.c.tree("col-tab"), "role=tab label=\"orders\"").contains("selected"),
        console.c.tree("col-tab"));

    // Switch back to the console; Ctrl+hover marks `customers` navigable, Ctrl+click opens it.
    console.c.run(app, "press role=tab label=\"shop-prod: shop\"\nframe\nadvance 1\nframe\n");
    String customersPt = pointAt(app, sql.indexOf("customers") + 2);
    console.c.run(app, "move " + customersPt + " control\nframe\n");
    assertEquals(Cursor.POINTER, app.cursor());
    console.c.run(app, "press " + customersPt + " control\nframe\n" + SETTLE + "snap click-tab\n");
    String clickTab = console.c.tree("click-tab");
    assertTrue(line(clickTab, "role=tab label=\"customers\"").contains("selected"), clickTab);
  }

  @Test
  void controlBOnColumnFollowsSingleOutgoingForeignKeyAndFallsBackForNoneOrSeveral()
      throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", """
        shop
          public
            customers
              *id: integer
              name: text
            partners
              *id: integer
              name: text
            orders
              *id: bigint
              customer_id: integer > customers.id on delete cascade
              dual_id: integer > fk_cust: customers.id > fk_part: partners.id
              region_a: integer > region_fk: billing.regions.a
              region_b: integer > region_fk: billing.regions.b
              total: numeric(12,2)
            order_items
              *order_id: bigint > orders.id
              *line: integer
          billing
            regions
              *a: integer
              *b: integer
        """);
    var app = console.console(SHOP);
    var sql = "SELECT o.id, o.customer_id, o.region_a, o.dual_id, o.total FROM orders o";
    console.c.run(app, TREE + type(sql) + "frame\n");

    // Single outgoing FK (`o.customer_id` -> `customers`): Ctrl+B opens `customers`.
    int custAt = sql.indexOf("customer_id") + 2;
    console.tab(app).editor.select(custAt, custAt);
    app.invalidate();
    console.c.run(app, "frame\nkey B control\nframe\n" + SETTLE + "snap fk-one\n");
    assertTrue(
        line(console.c.tree("fk-one"), "role=tab label=\"customers\"").contains("selected"),
        console.c.tree("fk-one"));

    // Column in a single composite cross-schema FK (`o.region_a` -> `billing.regions`): Ctrl+click
    // opens `regions`.
    console.c.run(app, "press role=tab label=\"shop-prod: shop\"\nframe\nadvance 1\nframe\n");
    String regionPt = pointAt(app, sql.indexOf("region_a") + 2);
    console.c.run(app, "press " + regionPt + " control\nframe\n" + SETTLE + "snap fk-composite\n");
    assertTrue(
        line(console.c.tree("fk-composite"), "role=tab label=\"regions\"").contains("selected"),
        console.c.tree("fk-composite"));

    // Column in TWO outgoing FKs (`o.dual_id`): Ctrl+B opens its own table (`orders`).
    console.c.run(
        app,
        "press role=tab label=\"shop-prod: shop\"\nframe\nadvance 1\nframe\npress #sql\nframe\n");
    int dualAt = sql.indexOf("dual_id") + 2;
    console.tab(app).editor.select(dualAt, dualAt);
    app.invalidate();
    console.c.run(app, "frame\nkey B control\nframe\n" + SETTLE + "snap fk-multi\n");
    assertTrue(
        line(console.c.tree("fk-multi"), "role=tab label=\"orders\"").contains("selected"),
        console.c.tree("fk-multi"));

    // Column with NO outgoing FK (only incoming: `o.id`, and plain: `o.total`): opens `orders`.
    console.c.run(
        app,
        "press role=tab label=\"shop-prod: shop\"\nframe\nadvance 1\nframe\npress #sql\nframe\n");
    int idAt = sql.indexOf("o.id") + 3;
    console.tab(app).editor.select(idAt, idAt);
    app.invalidate();
    console.c.run(app, "frame\nkey B control\nframe\n" + SETTLE + "snap fk-incoming-only\n");
    assertTrue(
        line(console.c.tree("fk-incoming-only"), "role=tab label=\"orders\"").contains("selected"),
        console.c.tree("fk-incoming-only"));
  }

  @Test
  void gutterMarkersRunEachStatementShowLastOutcomeAndMoveWithEdits() throws Exception {
    servers.statement(ConsoleTest.PAY, List.of(new Result.Count(3)));
    var app = console.console(SHOP);
    var text = COUNT + ";\n" + WRONG + ";\n" + ConsoleTest.PAY + ";";
    console.c.run(app, type(text) + "frame\nsnap before\n");
    String before = console.c.tree("before");
    assertTrue(gutterLine(before, 0).contains("role=button label=\"Run statement\""), before);
    assertTrue(gutterLine(before, 1).contains("role=button label=\"Run statement\""), before);
    assertTrue(gutterLine(before, 4).contains("role=button label=\"Run statement\""), before);

    // Markers are not rebuilt on frames when text, provider and outcomes are unchanged.
    int builtBefore = console.tab(app).help.markersBuilt;
    console.c.run(app, "key ArrowLeft\nframe\nkey ArrowRight\nframe\n");
    assertEquals(builtBefore, console.tab(app).help.markersBuilt);

    // Run statement 1 and statement 2 with Run all: line 0 succeeds, line 1 fails, line 4 unrun.
    console.c.run(app, RUN_ALL + "snap ran\n");
    String ran = console.c.tree("ran");
    assertTrue(
        gutterLine(ran, 0).contains("role=button label=\"Ran in 4.0 ms · 1 row\""),
        gutterLine(ran, 0));
    assertTrue(
        gutterLine(ran, 1).contains("role=button label=\"Failed after 4.0 ms\""),
        gutterLine(ran, 1));
    assertTrue(
        gutterLine(ran, 4).contains("role=button label=\"Run statement\""), gutterLine(ran, 4));

    // Pressing line 4's unique "Run statement" marker runs the UPDATE statement alone.
    console.c.run(
        app, "press role=button label=\"Run statement\"\nframe\n" + SETTLE + "snap pay\n");
    String pay = console.c.tree("pay");
    assertTrue(
        gutterLine(pay, 4).contains("role=button label=\"Ran in 4.0 ms · 3 rows changed\""),
        gutterLine(pay, 4));

    // Inserting a line at the top moves every marker down by one line.
    console.c.run(app, "press #sql\nkey Home control\nkey Enter\nframe\nsnap moved\n");
    String moved = console.c.tree("moved");
    assertTrue(
        gutterLine(moved, 1).contains("role=button label=\"Ran in 4.0 ms · 1 row\""),
        gutterLine(moved, 1));
    assertTrue(
        gutterLine(moved, 2).contains("role=button label=\"Failed after 4.0 ms\""),
        gutterLine(moved, 2));
    assertTrue(
        gutterLine(moved, 5).contains("role=button label=\"Ran in 4.0 ms · 3 rows changed\""),
        gutterLine(moved, 5));

    // Clicking the moved succeeded marker runs statement 1 again at its current offsets.
    long runsBefore =
        servers.calls.stream().filter(("run " + PG + " " + COUNT)::equals).count();
    console.c.run(app, "press role=button label=\"Ran in 4.0 ms · 1 row\"\nframe\n" + SETTLE);
    long runsAfter =
        servers.calls.stream().filter(("run " + PG + " " + COUNT)::equals).count();
    assertEquals(runsBefore + 1, runsAfter, servers.calls.toString());

    // Editing inside statement 1 changes its text and resets its gutter marker to Run statement.
    int countEnd = console.tab(app).text.indexOf(COUNT) + COUNT.length();
    console.tab(app).editor.select(countEnd, countEnd);
    app.invalidate();
    console.c.run(app, "frame\ntype \" o\"\nframe\nsnap edited-stmt\n");
    String editedStmt = console.c.tree("edited-stmt");
    assertTrue(
        gutterLine(editedStmt, 1).contains("role=button label=\"Run statement\""),
        gutterLine(editedStmt, 1));
    assertTrue(
        gutterLine(editedStmt, 2).contains("role=button label=\"Failed after 4.0 ms\""),
        gutterLine(editedStmt, 2));
  }

  @Test
  void quickDocumentationShowsForeignKeysForTableAndColumnInBothDirections() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogTreeTest.FK_OUTLINE);
    var app = console.console(SHOP);
    var sql = "SELECT o.id, o.customer_id FROM orders o JOIN order_items oi ON oi.order_id = o.id";
    console.c.run(app, TREE + type(sql) + "frame\n");

    // Hover over table `orders`: lists References (with rules + composite cross-schema) and
    // Referenced by.
    String ordersPt = pointAt(app, sql.indexOf("orders") + 2);
    console.c.run(
        app,
        "move " + ordersPt
            + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap foreign-keys-doc\n");
    String tableDoc = console.c.tree("foreign-keys-doc");
    assertTrue(tableDoc.contains("#documentation"), tableDoc);
    assertTrue(tableDoc.contains("Table public.orders"), tableDoc);
    assertTrue(tableDoc.contains("References"), tableDoc);
    assertTrue(tableDoc.contains("orders_customer_id_fkey"), tableDoc);
    assertTrue(tableDoc.contains("(customer_id) → customers(id) · ON DELETE CASCADE"), tableDoc);
    assertTrue(tableDoc.contains("region_fk"), tableDoc);
    assertTrue(
        tableDoc.contains("(region_a, region_b) → billing.regions(a, b) · ON DELETE RESTRICT"),
        tableDoc);
    assertTrue(tableDoc.contains("Referenced by"), tableDoc);
    assertTrue(tableDoc.contains("order_items_order_id_fkey"), tableDoc);
    assertTrue(tableDoc.contains("order_items(order_id) → (id) · ON DELETE CASCADE"), tableDoc);

    // Ctrl+Q on outgoing key column `o.customer_id`: lists its target and non-default rule.
    console.tab(app).editor.select(sql.indexOf("customer_id") + 2, sql.indexOf("customer_id") + 2);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\nsnap fk-col-out\n");
    String outColDoc = console.c.tree("fk-col-out");
    assertTrue(outColDoc.contains("Column customer_id integer"), outColDoc);
    assertTrue(outColDoc.contains("References"), outColDoc);
    assertTrue(outColDoc.contains("→ customers.id · ON DELETE CASCADE"), outColDoc);

    // Ctrl+Q on referenced key column `o.id`: lists incoming keys that reference it.
    console.tab(app).editor.select(sql.indexOf("o.id") + 3, sql.indexOf("o.id") + 3);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\nsnap fk-col-in\n");
    String inColDoc = console.c.tree("fk-col-in");
    assertTrue(inColDoc.contains("Column id bigint"), inColDoc);
    assertTrue(inColDoc.contains("Referenced by"), inColDoc);
    assertTrue(inColDoc.contains("order_items_order_id_fkey"), inColDoc);
    assertTrue(inColDoc.contains("order_items(order_id) → (id) · ON DELETE CASCADE"), inColDoc);
    assertTrue(inColDoc.contains("orders_parent_id_fkey"), inColDoc);

    // Unread table (`customers`): first hover triggers reading columns and foreign keys; next shows
    // them.
    var custSql = "SELECT c.id FROM customers c";
    console.c.run(app, "key Escape\nkey A control\nkey Delete\n" + type(custSql) + "frame\n");
    String custPt = pointAt(app, custSql.indexOf("customers") + 2);
    console.c.run(
        app, "move " + custPt + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-first\n");
    assertFalse(
        console.c.tree("cust-first").contains("Referenced by"), console.c.tree("cust-first"));
    console.c.run(
        app,
        SETTLE
            + "leave\nframe\nadvance 1\nframe\nframe\nadvance 1\nframe\nmove "
            + custPt
            + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-second\n");
    String custSecond = console.c.tree("cust-second");
    assertTrue(custSecond.contains("Referenced by"), custSecond);
    assertTrue(custSecond.contains("orders_customer_id_fkey"), custSecond);
    assertTrue(custSecond.contains("orders(customer_id) → (id) · ON DELETE CASCADE"), custSecond);
  }

  @Test
  void
      quickDocumentationReadsKeysWhenColumnsWereAlreadyReadWithoutThemAndShowsBothDirectionsOnColumn()
          throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", """
        shop
          public
            customers
              *id: integer
            orders
              *id: integer > customers.id on delete cascade
            payments
              *id: integer
              order_id: integer > orders.id on delete restrict
        """);
    var keysCall = "foreign keys " + PG + "/shop.public.orders";
    servers.fail(
        keysCall,
        FakeServers.notRead(dev.hoori.db.core.catalog.CatalogException.Failure.ACCESS_DENIED));
    var app = console.console(SHOP);
    var sql = "SELECT o.id FROM orders o";
    // Opening orders in the tree reads its columns while its foreign key read fails alone.
    console.c.run(app, TREE + type(sql) + "frame\n");
    servers.heal(keysCall);

    // First Ctrl+Q on `o.id` sees columns already Read, but triggers a scan to fetch missing keys.
    console.tab(app).editor.select(sql.indexOf("o.id") + 3, sql.indexOf("o.id") + 3);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(
        2, servers.calls.stream().filter(keysCall::equals).count(), servers.calls.toString());

    // Next Ctrl+Q on `o.id` shows both outgoing and incoming keys for the column.
    console.c.run(
        app, "key Escape\nframe\nkey Q control\nframe\nadvance 1\nframe\nsnap both-col\n");
    String both = console.c.tree("both-col");
    assertTrue(both.contains("Column id integer"), both);
    assertTrue(both.contains("References"), both);
    assertTrue(both.contains("→ customers.id · ON DELETE CASCADE"), both);
    assertTrue(both.contains("Referenced by"), both);
    assertTrue(both.contains("payments_order_id_fkey"), both);
    assertTrue(both.contains("payments(order_id) → (id) · ON DELETE RESTRICT"), both);
  }

  @Test
  void quickDocumentationShowsIndexesForTableAndReadsWhenMissing() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogTreeTest.INDEX_OUTLINE);
    var app = console.console(SHOP);
    var sql = "SELECT o.id FROM orders o";
    console.c.run(app, TREE + type(sql) + "frame\n");

    // Hover over already-read table `orders`: lists Indexes with columns and unique / primary key.
    String ordersPt = pointAt(app, sql.indexOf("orders") + 2);
    console.c.run(
        app, "move " + ordersPt + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap orders-idx\n");
    String ordersTree = console.c.tree("orders-idx");
    assertTrue(ordersTree.contains("#documentation"), ordersTree);
    String ordersDoc = ordersTree.substring(ordersTree.indexOf("#documentation"));
    assertTrue(ordersDoc.contains("References"), ordersDoc);
    assertTrue(ordersDoc.contains("Indexes"), ordersDoc);
    assertTrue(ordersDoc.indexOf("References") < ordersDoc.indexOf("Indexes"), ordersDoc);
    assertTrue(ordersDoc.contains("orders_pkey (id) · primary key"), ordersDoc);
    assertTrue(ordersDoc.contains("orders_code_expr_idx (lower(code)) · unique"), ordersDoc);
    assertTrue(
        ordersDoc.contains("orders_customer_placed_idx (customer_id, placed_at)"), ordersDoc);

    // Unread table (`customers`): first hover triggers reading columns/keys/indexes; next shows
    // them.
    var custSql = "SELECT c.id FROM customers c";
    console.c.run(app, "key Escape\nkey A control\nkey Delete\n" + type(custSql) + "frame\n");
    String custPt = pointAt(app, custSql.indexOf("customers") + 2);
    console.c.run(
        app, "move " + custPt + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-first\n");
    String custFirstTree = console.c.tree("cust-first");
    assertTrue(custFirstTree.contains("#documentation"), custFirstTree);
    String custFirst = custFirstTree.substring(custFirstTree.indexOf("#documentation"));
    assertFalse(custFirst.contains("Indexes"), custFirst);
    console.c.run(
        app,
        SETTLE
            + "leave\nframe\nadvance 1\nframe\nframe\nadvance 1\nframe\nmove "
            + custPt
            + "\nframe\nadvance 1\nframe\nadvance 1\nframe\nsnap cust-second\n");
    String custSecondTree = console.c.tree("cust-second");
    String custSecond = custSecondTree.substring(custSecondTree.indexOf("#documentation"));
    assertTrue(custSecond.contains("Indexes"), custSecond);
    assertTrue(custSecond.contains("customers_pkey (id) · primary key"), custSecond);
    assertTrue(custSecond.contains("customers_email_uidx (email) · unique"), custSecond);
  }

  @Test
  void quickDocumentationReadsIndexesWhenColumnsWereAlreadyReadWithoutThem() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", CatalogTreeTest.INDEX_OUTLINE);
    var idxCall = "indexes " + PG + "/shop.public.orders";
    servers.fail(
        idxCall,
        FakeServers.notRead(dev.hoori.db.core.catalog.CatalogException.Failure.ACCESS_DENIED));
    var app = console.console(SHOP);
    var sql = "SELECT o.id FROM orders o";
    // Opening orders in the tree reads columns and foreign keys while indexes fails alone.
    console.c.run(app, TREE + type(sql) + "frame\n");
    servers.heal(idxCall);

    // First Ctrl+Q on `orders` triggers a scan for the missing indexes.
    console.tab(app).editor.select(sql.indexOf("orders") + 2, sql.indexOf("orders") + 2);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(
        2, servers.calls.stream().filter(idxCall::equals).count(), servers.calls.toString());

    // Next Ctrl+Q on `orders` shows the Indexes section.
    console.c.run(
        app, "key Escape\nframe\nkey Q control\nframe\nadvance 1\nframe\nsnap idx-loaded\n");
    String loadedTree = console.c.tree("idx-loaded");
    String loaded = loadedTree.substring(loadedTree.indexOf("#documentation"));
    assertTrue(loaded.contains("Indexes"), loaded);
    assertTrue(loaded.contains("orders_pkey (id) · primary key"), loaded);
  }

  @Test
  void quickDocumentationShowsIndexDetailAfterUniqueOrAlone() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", """
        shop
          public
            orders
              *id: bigint
              email: text
              expires_at: timestamp
              #orders_pkey (id) primary
              #orders_email_uidx (email) unique | sparse
              #orders_ttl_idx (expires_at) | TTL 3600 s · partial
        """);
    var app = console.console(SHOP);
    var sql = "SELECT id FROM orders";
    console.c.run(app, TREE + type(sql) + "frame\n");
    console.tab(app).editor.select(sql.indexOf("orders") + 2, sql.indexOf("orders") + 2);
    app.invalidate();
    console.c.run(app, "frame\nkey Q control\nframe\nadvance 1\nframe\nsnap detail-doc\n");
    String docTree = console.c.tree("detail-doc");
    String doc = docTree.substring(docTree.indexOf("#documentation"));
    assertTrue(doc.contains("orders_pkey (id) · primary key"), doc);
    assertTrue(doc.contains("orders_email_uidx (email) · unique · sparse"), doc);
    assertTrue(doc.contains("orders_ttl_idx (expires_at) · TTL 3600 s · partial"), doc);
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
    var app = console.console(SHOP.withColor(Color.TEAL));
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
      var completing = console.console(SHOP.withColor(Color.TEAL));
      completing.state().theme = theme;
      console.c.run(completing, TREE);
      Script.run(shot + look + "\n", completing, out);
    }
  }
}

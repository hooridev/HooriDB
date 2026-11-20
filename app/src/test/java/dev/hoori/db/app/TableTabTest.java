package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.ZONE;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A table's rows in a tab: read in pages, late, on the lane of its connection. */
class TableTabTest {

  static final String OUTLINE = """
      shop
        public
          orders
            *id: bigint
            customer_id: integer
            placed_at: timestamp with time zone
            total: numeric(12,2)
          kinds
            *id: bigint
            amount: numeric
            ratio: double precision
            name: text
            note: text
            paid: boolean
            due: date
            at: time
            local: timestamp
            moment: timestamp with time zone
            photo: bytea
            doc: jsonb
          big
            *id: bigint
            name: text
          customers
            *id: integer
            name: text
          logs
            name: text
        sales
          orders
            *id: integer
            amount: numeric(12,2)
      analytics
        tracking
          events
            *id: bigint
      """;

  static final String ORDERS = PG + "/shop.public.orders";
  static final String KINDS = PG + "/shop.public.kinds";
  static final String BIG = PG + "/shop.public.big";

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    servers = c.servers.serve(PG, Provider.POSTGRESQL, "16.4", OUTLINE);
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  /** Selects the table's row in the tree and opens its rows with Enter. */
  static String table(String label) {
    return "press role=treeitem label=\"" + label + "\"\nkey Enter\nframe\n";
  }

  /** Scrolls the grid of the active tab so that the row is at its top. */
  static String scroll(long row) {
    // The grid tells the rows it built in the frame after it built them.
    return "scroll #rows row " + row + "\nframe\nframe\n";
  }

  /** The app with shop's public schema open in the tree. */
  App<State> shop() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    return app;
  }

  /** The lines of a cell, from its own to the next cell's, row number's or row's. */
  static String cell(String tree, long row, int column) {
    var lines = tree.lines().toList();
    var out = new StringBuilder();
    boolean in = false;
    for (var l : lines) {
      if (l.contains("#cell-" + row + "-" + column + " ")) {
        in = true;
      } else if (in && (l.contains("#cell-") || l.contains("#number-") || l.contains("role=row"))) {
        break;
      }
      if (in) {
        out.append(l).append('\n');
      }
    }
    assertFalse(out.isEmpty(), "no cell " + row + "-" + column + " in\n" + tree);
    return out.toString();
  }

  static String status(String tree) {
    return line(tree, "#rows-status");
  }

  static String tabs(String tree) {
    return String.join(
        ",",
        tree.lines()
            .filter(l -> l.contains("role=tab "))
            .map(l -> l.replaceAll(".*label=\"([^\"]*)\".*", "$1"))
            .toList());
  }

  TableTab tab(App<State> app) {
    return app.state().tables(null).getFirst();
  }

  @Test
  void openingATableShowsLoadingCellsThenItsRows() throws Exception {
    var app = shop();
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var held = servers.hold("read " + ORDERS + " 0-200");
    // The tree has its columns: the grid shows them at once, its rows loading.
    c.run(app, open("orders") + SETTLE + table("orders") + "frame\nsnap loading\n");
    String loading = c.tree("loading");
    assertTrue(line(loading, "#column-0").contains("label=\"id\""), loading);
    assertTrue(cell(loading, 0, 0).contains("\"…\""), loading);
    assertTrue(line(loading, "#refresh").contains("busy"), loading);
    assertTrue(line(loading, "role=tab label=\"orders\"").contains("busy"), loading);
    assertTrue(loading.contains("role=progressbar label=\"Reading\""), loading);
    held.countDown();
    c.run(app, SETTLE + "snap opened\n");
    String opened = c.tree("opened");
    assertEquals("orders", tabs(opened));
    assertFalse(line(opened, "role=tab label=\"orders\"").contains("busy"), opened);
    assertTrue(line(opened, "role=heading \"shop.public.orders\"").contains("level=3"), opened);
    // The columns: their names, the server's types, the key and the kind's icon.
    String id = c.tree("opened").substring(opened.indexOf("#column-0"));
    assertTrue(id.substring(0, id.indexOf("#column-1")).contains("label=\"key\""), opened);
    assertTrue(id.substring(0, id.indexOf("#column-1")).contains("\"bigint\""), opened);
    assertTrue(opened.contains("\"timestamp with time zone\""), opened);
    assertTrue(opened.contains("label=\"calendar_clock\""), opened);
    assertTrue(cell(opened, 0, 0).contains("\"1\""), opened);
    assertTrue(cell(opened, 1, 2).contains("\"2026-01-01 10:01:00+01\""), opened);
    assertTrue(cell(opened, 2, 3).contains("\"3.49\""), opened);
    // NULL, every seventh row of a column that may hold it.
    assertTrue(cell(opened, 6, 3).contains("\"NULL\""), opened);
    assertTrue(status(opened).contains("\"1,000 rows\""), opened);
    assertTrue(line(opened, "#read-status").matches(".*\"Read 200 rows in \\d+ ms\".*"), opened);
    // A second open goes to the same tab.
    c.run(app, table("kinds") + SETTLE + table("orders") + SETTLE + "snap again\n");
    String again = c.tree("again");
    assertEquals("orders,kinds", tabs(again));
    assertTrue(line(again, "role=tab label=\"orders\"").contains("selected"), again);
  }

  @Test
  void twoTablesOfOneNameSayWhereTheyLive() throws Exception {
    var app = shop();
    c.run(
        app,
        table("orders") + SETTLE + open("sales") + SETTLE
            + "press role=treeitem label=\"orders\" pos=1/1\nkey Enter\nframe\n" + SETTLE
            + "snap both\n");
    assertEquals("shop.public.orders,shop.sales.orders", tabs(c.tree("both")));
    c.run(app, "press #close-1\nframe\n" + SETTLE + "snap one\n");
    assertEquals("orders", tabs(c.tree("one")));
  }

  @Test
  void eachKindOfValueShowsAsTheDatabaseWritesIt() throws Exception {
    var photo = new byte[Cells.PREVIEW + 4];
    var png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    System.arraycopy(png, 0, photo, 0, png.length);
    servers.values(
        KINDS,
        List.of(
            List.of(
                1L,
                new BigDecimal("12345678901234567890.123456789"),
                1e20,
                "plain text",
                "",
                true,
                LocalDate.of(2026, 11, 13),
                LocalTime.of(14, 30),
                LocalDateTime.of(2026, 11, 13, 14, 30, 5, 250_000_000),
                OffsetDateTime.of(2026, 11, 13, 13, 30, 0, 0, ZoneOffset.UTC),
                new Binary(photo, 12_345),
                "{\"a\": 1}"),
            Arrays.asList(
                2L,
                new BigDecimal("-0.50"),
                0.1,
                new Cut("x".repeat(1024), OptionalLong.of(5_000)),
                null,
                false,
                "0000-00-00",
                "24:00:00",
                "infinity",
                null,
                new Binary(new byte[] {(byte) 0xde, (byte) 0xad}, 2),
                null)));
    var app = shop();
    c.run(app, "size 2600 760\nframe\n" + table("kinds") + SETTLE + "snap kinds\n");
    String kinds = c.tree("kinds");
    String[] first = {
      "\"1\"",
      "\"12345678901234567890.123456789\"",
      "\"1e+20\"",
      "\"plain text\"",
      null,
      "\"true\"",
      "\"2026-11-13\"",
      "\"14:30:00\"",
      "\"2026-11-13 14:30:05.25\"",
      "\"2026-11-13 14:30:00+01\"",
      "\"0x89504e470d0a1a0a… (12 kB)\"",
      "\"{\\\"a\\\": 1}\""
    };
    for (int i = 0; i < first.length; i++) {
      if (first[i] != null) {
        assertTrue(cell(kinds, 0, i).contains(first[i]), first[i] + " in " + cell(kinds, 0, i));
      }
    }
    // The empty text is not NULL.
    assertFalse(cell(kinds, 0, 4).contains("NULL"), cell(kinds, 0, 4));
    assertTrue(cell(kinds, 1, 4).contains("\"NULL\""), cell(kinds, 1, 4));
    assertTrue(cell(kinds, 1, 1).contains("\"-0.50\""), kinds);
    assertTrue(cell(kinds, 1, 2).contains("\"0.1\""), kinds);
    // A text cut short says so, and how long it is.
    assertTrue(cell(kinds, 1, 3).contains("label=\"more_horizontal\""), cell(kinds, 1, 3));
    assertTrue(cell(kinds, 1, 3).contains("\"xxxxxxxx"), cell(kinds, 1, 3));
    // Values the Java types cannot hold, as the server wrote them.
    assertTrue(cell(kinds, 1, 6).contains("\"0000-00-00\""), kinds);
    assertTrue(cell(kinds, 1, 7).contains("\"24:00:00\""), kinds);
    assertTrue(cell(kinds, 1, 8).contains("\"infinity\""), kinds);
    assertTrue(cell(kinds, 1, 10).contains("\"0xdead (2 B)\""), kinds);
    assertEquals(
        "Cut short: the first 1024 characters of 5.0 kB",
        Cells.icon(new Cut("x", OptionalLong.of(5_000))).tooltip());
    assertTrue(
        Cells.icon(new Cut("x", OptionalLong.empty())).tooltip().endsWith("1024 characters"));
  }

  @Test
  void aMillionRowsAreReadAPageAtATimeWhereTheGridIs() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE + "snap first\n");
    String first = c.tree("first");
    assertTrue(cell(first, 0, 1).contains("\"name 1\""), first);
    assertTrue(status(first).contains("About 1,000,000 rows, as the server estimates"), first);
    c.run(app, scroll(500_000) + SETTLE + "snap middle\n");
    assertTrue(cell(c.tree("middle"), 500_000, 0).contains("\"500001\""), c.tree("middle"));
    c.run(app, scroll(999_990) + SETTLE + "snap end\n");
    String end = c.tree("end");
    assertTrue(cell(end, 999_999, 0).contains("\"1000000\""), end);
    // The page at the end was full, so one more read found that nothing follows.
    assertTrue(
        servers.calls.contains("read " + BIG + " 1000000-1000200"), servers.calls.toString());
    assertTrue(status(end).contains("\"1,000,000 rows\""), end);
    // The empty read that proved the end is not the read the status line tells of.
    assertTrue(line(end, "#read-status").contains("\"Read 200 rows in "), end);
    // Jumping around keeps no more than a few pages.
    for (long row : new long[] {100_000, 200_000, 300_000, 400_000, 600_000, 700_000, 0}) {
      c.run(app, scroll(row) + SETTLE);
    }
    assertTrue(tab(app).pages.size() <= TableTab.KEPT, tab(app).pages.keySet().toString());
    assertTrue(tab(app).pages.containsKey(0L));
    assertFalse(servers.overlapped);
  }

  @Test
  void aTableWithoutAKeyReadsNoPagesAhead() throws Exception {
    var logs = PG + "/shop.public.logs";
    servers.rows(logs, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("logs") + SETTLE + scroll(500) + SETTLE);
    var reads = servers.calls.stream().filter(x -> x.startsWith("read " + logs)).toList();
    assertEquals(List.of("read " + logs + " 0-200", "read " + logs + " 400-600"), reads);
  }

  @Test
  void aPageScrolledPastIsCancelledAndTheNewestWins() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var middle = "read " + BIG + " 500000-500200";
    servers.hold(middle);
    c.run(app, scroll(500_000));
    eventually(() -> servers.calls.contains(middle));
    c.run(app, scroll(800_000));
    // The read in the server is cancelled there; those behind it never start.
    eventually(() -> servers.interrupted.contains(middle));
    c.run(app, SETTLE + "snap end\n");
    assertTrue(cell(c.tree("end"), 800_000, 0).contains("\"800001\""), c.tree("end"));
    assertFalse(servers.calls.contains("read " + BIG + " 500200-500400"), servers.calls.toString());
    assertFalse(servers.calls.contains("read " + BIG + " 499600-499800"), servers.calls.toString());
    assertFalse(tab(app).pages.containsKey(2500L));
  }

  @Test
  void anEstimateTooHighIsCorrectedByTheFirstPage() throws Exception {
    servers.rows(ORDERS, 5, OptionalLong.of(1000)); // as PostgreSQL guesses a new table
    var app = shop();
    c.run(app, table("orders") + SETTLE + "snap orders\n");
    String orders = c.tree("orders");
    assertTrue(status(orders).contains("\"5 rows\""), orders);
    assertTrue(line(orders, "#rows").contains("rows 0-4/5"), orders);
  }

  @Test
  void anEstimateTooLowIsCorrectedByTheCount() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(10));
    var app = shop();
    c.run(app, table("orders") + SETTLE + "snap counted\n");
    // The first page held more than estimated, and a small estimate is counted at once.
    var calls = servers.calls;
    assertTrue(
        calls.indexOf("read " + ORDERS + " 0-200") < calls.indexOf("count " + ORDERS),
        calls.toString());
    assertTrue(status(c.tree("counted")).contains("\"1,000 rows\""), c.tree("counted"));
  }

  @Test
  void withoutAnEstimateThePagesTellAndCountRowsCounts() throws Exception {
    servers.rows(ORDERS, 50, OptionalLong.empty()); // as a view on MySQL
    servers.rows(BIG, 1000, OptionalLong.empty());
    var app = shop();
    c.run(app, table("orders") + SETTLE + "snap orders\n" + table("big") + SETTLE + "snap big\n");
    assertTrue(status(c.tree("orders")).contains("\"50 rows\""), c.tree("orders"));
    // Each full page at the end says that there are more, which the grid then asks for.
    assertTrue(status(c.tree("big")).contains("\"At least "), c.tree("big"));
    assertFalse(servers.calls.contains("count " + BIG), "a table of unknown size is not counted");
    c.run(app, "press #count\nframe\n" + SETTLE + "snap counted\n");
    assertTrue(status(c.tree("counted")).contains("\"1,000 rows\""), c.tree("counted"));
  }

  @Test
  void refreshReadsAnewAndKeepsThePlace() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("big") + SETTLE + scroll(500) + SETTLE);
    servers.rows(BIG, 900, OptionalLong.of(900));
    int before = servers.calls.size();
    c.run(app, "press #refresh\nframe\n" + SETTLE + "snap refreshed\n");
    String refreshed = c.tree("refreshed");
    var again = servers.calls.subList(before, servers.calls.size());
    assertEquals("columns " + BIG, again.getFirst(), again.toString());
    assertTrue(again.contains("read " + BIG + " 400-600"), again.toString());
    assertTrue(cell(refreshed, 500, 0).contains("\"501\""), refreshed);
    assertTrue(status(refreshed).contains("\"900 rows\""), refreshed);
  }

  @Test
  void columnsChangedUnderneathAreReadAnew() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    // Another type: a page no longer fits the grid's columns.
    servers.serve(
        PG, Provider.POSTGRESQL, "16.4", OUTLINE.replace("total: numeric(12,2)", "total: text"));
    c.run(app, scroll(600) + SETTLE + "snap retyped\n");
    String retyped = c.tree("retyped");
    assertTrue(line(retyped, "#column-3").contains("label=\"total\""), retyped);
    assertTrue(retyped.contains("text \"text\""), retyped);
    assertTrue(cell(retyped, 600, 3).contains("\"total 601\""), retyped);
    // A column gone: the read fails, and the table is read anew.
    servers.serve(
        PG,
        Provider.POSTGRESQL,
        "16.4",
        OUTLINE
            .replace("      customer_id: integer\n", "")
            .replace("total: numeric", "total: text"));
    c.run(app, scroll(100) + SETTLE + "snap dropped\n");
    String dropped = c.tree("dropped");
    assertFalse(dropped.contains("label=\"customer_id\""), dropped);
    assertFalse(dropped.contains("role=note"), dropped);
    assertTrue(cell(dropped, 100, 0).contains("\"101\""), dropped);
  }

  @Test
  void aTableDroppedSaysSoAndCanBeTriedAgain() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    var without = OUTLINE.substring(0, OUTLINE.indexOf("    orders"))
        + OUTLINE.substring(OUTLINE.indexOf("    kinds"));
    servers.serve(PG, Provider.POSTGRESQL, "16.4", without);
    c.run(app, scroll(600) + SETTLE + "advance 1\nframe\nsnap gone\n");
    String gone = c.tree("gone");
    assertTrue(line(gone, "role=note").contains("label=\"Not found\""), gone);
    assertTrue(gone.contains("orders is no longer there, or this user may not see it."), gone);
    servers.serve(PG, Provider.POSTGRESQL, "16.4", OUTLINE);
    c.run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap back\n");
    assertFalse(c.tree("back").contains("role=note"), c.tree("back"));
  }

  @Test
  void eachFailureShowsInTheTabWithTryAgain() throws Exception {
    var app = shop();
    var key = "read " + ORDERS + " 0-200";
    String[][] cases = {
      {"ACCESS_DENIED", "Access denied", "The server does not let this user read orders."},
      {"CANCELLED", "Cancelled", "The server cancelled the read."},
      {"OTHER", "Could not be read", "The server could not read orders."}
    };
    c.run(app, table("orders") + SETTLE);
    for (var x : cases) {
      servers.fail(key, FakeServers.queryFailure(QueryException.Failure.valueOf(x[0])));
      c.run(app, "press #refresh\nframe\n" + SETTLE + "advance 1\nframe\nsnap " + x[0] + "\n");
      String tree = c.tree(x[0]);
      assertTrue(line(tree, "role=note").contains("label=\"" + x[1] + "\""), tree);
      assertTrue(tree.contains(x[2]), tree);
      assertTrue(tree.contains("the server's words for " + x[0]), tree);
      assertTrue(tree.contains("role=button label=\"Try again\""), tree);
    }
    servers.heal(key);
    c.run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap end\n");
    assertFalse(c.tree("end").contains("role=note"), c.tree("end"));
    assertTrue(cell(c.tree("end"), 0, 0).contains("\"1\""), c.tree("end"));
  }

  @Test
  void aLostConnectionMarksTheTreeAndTheTabOffersToConnect() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    var key = "read " + ORDERS + " 600-800";
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    c.run(app, scroll(600) + SETTLE + "advance 1\nframe\nsnap lost\n");
    String lost = c.tree("lost");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(lost));
    assertTrue(line(lost, "role=note label=\"Disconnected\"").contains("role=note"), lost);
    assertTrue(lost.contains("The connection was lost"), lost);
    assertTrue(cell(lost, 600, 0).contains("\"…\""), lost);
    servers.heal(key);
    c.run(app, "press role=button label=\"Connect\"\nframe\n" + SETTLE + "snap again\n");
    String again = c.tree("again");
    assertFalse(again.contains("role=note"), again);
    assertTrue(cell(again, 600, 0).contains("\"601\""), again);
    assertTrue(rows(again).contains("  shop"), again);
  }

  @Test
  void aDiscardedConnectionIsTreatedAsLost() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    servers.fail(
        "read " + ORDERS + " 0-200", FakeServers.queryFailure(QueryException.Failure.ABORTED));
    c.run(app, "press #refresh\nframe\n" + SETTLE + "advance 1\nframe\nsnap discarded\n");
    String discarded = c.tree("discarded");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(discarded));
    assertTrue(
        discarded.contains("The connection was closed, as the server did not stop a cancelled"),
        discarded);
    assertTrue(discarded.contains("role=button label=\"Connect\""), discarded);
  }

  @Test
  void disconnectingLeavesTheTabWithWhatItHad() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE + menu("shop-prod", "Disconnect") + "snap off\n");
    String off = c.tree("off");
    assertEquals("orders", tabs(off));
    assertTrue(line(off, "role=note").contains("label=\"Disconnected\""), off);
    assertTrue(off.contains("Its connection is closed. What shows was read before"), off);
    assertTrue(cell(off, 0, 0).contains("\"1\""), off);
    assertTrue(line(off, "#refresh").contains("disabled"), off);
  }

  @Test
  void deletingAConnectionClosesItsTabsAfterAsking() throws Exception {
    var app = shop();
    c.run(
        app,
        table("orders") + SETTLE + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\nadvance 1\nframe\nsnap asked\n"
            + "press role=button label=\"Delete\"\nframe\n" + SETTLE + "snap deleted\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("\"Its open tabs close.\""), asked);
    assertEquals("orders", tabs(asked));
    assertEquals("", tabs(c.tree("deleted")));
    assertTrue(app.state().tables(null).isEmpty());
  }

  @Test
  void closingATabCancelsItsRead() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "read " + BIG + " 600-800";
    servers.hold(key);
    c.run(app, scroll(600));
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n");
    eventually(() -> servers.interrupted.contains(key));
    assertTrue(app.state().tables(null).isEmpty());
  }

  @Test
  void aResultAfterItsTabClosedIsIgnored() throws Exception {
    var app = shop();
    servers.dead = true; // the read answers once the gate opens, cancelled or not
    var key = "read " + ORDERS + " 0-200";
    var gate = servers.hold(key);
    c.run(app, table("orders"));
    eventually(() -> servers.calls.contains(key));
    var tab = tab(app);
    c.run(app, "press #close-0\nframe\n");
    gate.countDown();
    eventually(() -> servers.done.contains(key));
    c.run(app, SETTLE + "snap end\n");
    assertTrue(tab.pages.isEmpty());
    assertTrue(tab.columns == null);
    assertEquals("", tabs(c.tree("end")));
  }

  @Test
  void aTabTornOffKeepsItsRowsAndPlaceAndGetsWhatWasOnItsWay() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("orders") + SETTLE + table("big") + SETTLE + scroll(500) + SETTLE);
    var key = "read " + BIG + " 800-1000";
    servers.hold(key);
    c.run(app, scroll(800));
    eventually(() -> servers.calls.contains(key));
    c.run(
        app,
        "press #tab-1 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "snap torn\n");
    assertEquals("orders", tabs(c.tree("torn")));
    String window = c.tree("big-torn");
    assertEquals("big", tabs(window));
    assertTrue(window.contains("\"big\""), "the title bar: " + window);
    assertTrue(cell(window, 800, 0).contains("\"…\""), window);
    servers.openAll();
    c.run(app, SETTLE + "snap arrived\n");
    window = c.tree("big-arrived");
    assertTrue(cell(window, 800, 0).contains("\"801\""), window);
    assertTrue(line(window, "#rows-status").contains("\"1,000 rows\""), window);
    c.run(
        app,
        "window \"big\"\npress #tab-0 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move back to main window\"\nframe\nadvance 1\nframe\n"
            + "frame\nsnap back\n");
    String back = c.tree("back");
    assertEquals("orders,big", tabs(back));
    assertTrue(cell(back, 800, 0).contains("\"801\""), back);
  }

  @Test
  void readsOfOneConnectionNeverOverlapAndWhatIsInViewGoesFirst() throws Exception {
    servers.rows(BIG, 10_000, OptionalLong.of(10_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "read " + BIG + " 5000-5200";
    var gate = servers.hold(key);
    c.run(app, scroll(5000));
    eventually(() -> servers.calls.contains(key));
    // Behind the read in the server: the pages around it, then the tree's columns.
    c.run(app, open("customers"));
    gate.countDown();
    c.run(app, SETTLE);
    var after = servers.calls.subList(servers.calls.indexOf(key) + 1, servers.calls.size());
    assertEquals("columns " + PG + "/shop.public.customers", after.getFirst(), after.toString());
    assertTrue(after.contains("read " + BIG + " 5200-5400"), after.toString());
    assertFalse(servers.overlapped, "two calls ran at once on one connection");
  }

  // The connections: what a tab asks of one ends with it

  /** A failure on its way when the connection ended is not checked against the next one's. */
  @Test
  void aFailureFromAConnectionThatEndedKeepsItsPasswordOut() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    var key = "read " + ORDERS + " 0-200";
    servers.dead = true; // the read answers once the gate opens, interrupted or not
    var old = servers.hold(key);
    servers.fail(
        key,
        QueryException.of(
            QueryException.Failure.ACCESS_DENIED,
            "the server repeats " + ConnectionsTest.PASSWORD));
    app.state().rememberPassword(SHOP.id(), "another".toCharArray());
    c.run(app, "advance 1\nframe\npress #refresh\nframe\n");
    eventually(() -> servers.calls.stream().filter(key::equals).count() == 2);
    // Disconnect, and connect again with the other password, whose opening waits and then fails,
    // so that nothing read anew hides what the old failure left.
    var opening = servers.hold("open " + PG + "/shop");
    servers.fail(
        "open " + PG + "/shop",
        FakeServers.notConnected(dev.hoori.db.core.connect.ConnectionException.Failure.OTHER));
    c.run(
        app,
        menu("shop-prod", "Disconnect")
            + "press role=treeitem label=\"shop-prod\"\nkey Enter\nframe\n");
    eventually(() -> servers.passwords.contains("another"));
    // The server answers the old read with the password in its words; the tab's work ended with
    // the connection, so that answer never reaches it.
    old.countDown();
    eventually(() -> servers.failed.contains(key));
    servers.heal(key);
    opening.countDown();
    c.run(app, SETTLE + "snap after\n");
    assertEquals(null, tab(app).failure);
    c.noPassword();
  }

  @Test
  void aFailureThatRepeatsThePasswordIsWithheld() throws Exception {
    var app = shop();
    servers.fail(
        "read " + ORDERS + " 0-200",
        QueryException.of(
            QueryException.Failure.ACCESS_DENIED,
            "the server repeats " + ConnectionsTest.PASSWORD));
    c.run(app, table("orders") + SETTLE + "snap denied\n");
    assertEquals(
        new TableTab.Failure(
            TableTab.Problem.DENIED, dev.hoori.db.core.connect.ConnectionException.WITHHELD),
        tab(app).failure);
    c.noPassword();
  }

  @Test
  void anEditThatReachesAnotherServerClosesTheTabsAfterSayingSo() throws Exception {
    var app = shop();
    var s = app.state();
    c.run(app, table("orders") + SETTLE + menu("shop-prod", "Edit…") + "snap asked\n");
    assertTrue(
        c.tree("asked").contains("If its server or database changes, the tabs of its tables"),
        c.tree("asked"));
    c.run(app, "key Escape\nframe\nadvance 1\nframe\n");
    app.post(() -> s.put(s.profile(SHOP.id()).withName("shop-renamed")));
    c.run(app, "await\nframe\n");
    assertEquals(1, s.tables(null).size(), "a new name names the same tables");
    app.post(() -> s.put(s.profile(SHOP.id()).withProvider(Provider.MYSQL).withPort(3306)));
    c.run(app, "await\nframe\nsnap edited\n");
    assertTrue(s.tables(null).isEmpty());
    assertEquals("", tabs(c.tree("edited")));
  }

  /** A count the user stops ends the connection where core gave it up; the tree says so. */
  @Test
  void aConnectionGivenUpByAnyCallIsLostAlsoForTheCallsBehindIt() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "count " + BIG;
    servers.hold(key);
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.ABORTED));
    c.run(app, "advance 1\nframe\npress #count\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, open("customers") + "advance 1\nframe\npress #count\nframe\n" + SETTLE);
    c.run(app, SETTLE + "snap lost\n");
    String lost = c.tree("lost");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(lost));
    assertTrue(lost.contains("as the server did not stop a cancelled read"), lost);
  }

  // The paging and the count

  @Test
  void aTabClosedBeforeTheGridToldItsRowsReadsNothingMore() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var tab = tab(app);
    int before = servers.calls.size();
    // The grid tells the rows it built in the next frame, the one that closes the tab.
    c.run(app, "scroll #rows row 600\nframe\npress #close-0\nframe\n" + SETTLE);
    // Whatever was asked before the close, nothing reaches the closed tab, and nothing more is
    // asked.
    assertTrue(tab.pages.isEmpty() && tab.reading.isEmpty(), tab.pages.keySet().toString());
    int closed = servers.calls.size();
    tab.want(app.state(), 600, 630);
    c.run(app, SETTLE);
    assertEquals(
        closed, servers.calls.size(), servers.calls.subList(before, closed).toString());
  }

  @Test
  void aPageReadWhenTheTableEndedInItIsReadAgainOnceItGrew() throws Exception {
    servers.rows(ORDERS, 50, OptionalLong.of(50));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    servers.rows(ORDERS, 250, OptionalLong.of(50));
    c.run(app, "advance 1\nframe\npress #count\nframe\n" + SETTLE + scroll(100) + SETTLE);
    c.run(app, "snap grown\n");
    String grown = c.tree("grown");
    assertTrue(status(grown).contains("\"250 rows\""), grown);
    assertTrue(cell(grown, 100, 0).contains("\"101\""), grown);
  }

  /** Refresh where the table no longer reaches, without an estimate, finds where it ends. */
  @Test
  void refreshBeyondTheNewEndFindsTheEnd() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.empty());
    var app = shop();
    c.run(app, table("big") + SETTLE);
    for (long row : new long[] {199, 399, 500}) {
      c.run(app, scroll(row) + SETTLE);
    }
    assertTrue(status(snapped(app, "far")).contains("At least"), c.tree("far"));
    servers.rows(BIG, 350, OptionalLong.empty());
    c.run(app, "advance 1\nframe\npress #refresh\nframe\n" + SETTLE + "snap refreshed\n");
    String refreshed = c.tree("refreshed");
    assertTrue(status(refreshed).contains("\"350 rows\""), refreshed);
    assertTrue(cell(refreshed, 349, 0).contains("\"350\""), refreshed);
  }

  /** An estimate far too high is corrected by one count, not by a read per page. */
  @Test
  void anEstimateFarTooHighIsCorrectedInABoundedNumberOfReads() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    int before = servers.calls.size();
    c.run(app, scroll(500_000) + SETTLE + SETTLE + "snap jumped\n");
    var after = servers.calls.subList(before, servers.calls.size());
    String jumped = c.tree("jumped");
    assertTrue(status(jumped).contains("\"1,000 rows\""), jumped);
    assertTrue(cell(jumped, 999, 0).contains("\"1000\""), jumped);
    // The empty pages where the grid was, one count, and the pages at the end.
    assertEquals(1, after.stream().filter(x -> x.startsWith("count")).count(), after.toString());
    assertTrue(after.size() <= 7, after.toString());
  }

  @Test
  void theCountOfAnOpeningDoesNotStopTheUsersCount() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000)); // small: counted on opening too
    var app = shop();
    var held = servers.hold("read " + ORDERS + " 0-200");
    c.run(
        app,
        open("orders") + SETTLE + table("orders")
            + "frame\nadvance 1\nframe\npress #count\nframe\n");
    held.countDown();
    c.run(app, SETTLE + "snap counted\n");
    assertTrue(status(c.tree("counted")).contains("\"1,000 rows\""), c.tree("counted"));
    assertEquals(
        1, servers.calls.stream().filter(x -> x.equals("count " + ORDERS)).count());
    assertFalse(servers.interrupted.contains("count " + ORDERS));
  }

  @Test
  void aTabClosedWhileItOpensNeverStartsItsCount() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    var key = "read " + ORDERS + " 0-200";
    var held = servers.hold(key);
    c.run(
        app,
        open("orders") + SETTLE + table("orders")
            + "frame\nadvance 1\nframe\npress #count\nframe\n");
    eventually(() -> servers.calls.contains(key));
    // The read that runs is interrupted, which lets the lane go on at once: the count behind it
    // has left the lane by then.
    c.run(app, "press #close-0\nframe\n");
    eventually(() -> servers.interrupted.contains(key));
    held.countDown();
    c.run(app, SETTLE);
    assertFalse(servers.calls.contains("count " + ORDERS), servers.calls.toString());
  }

  /** The row more that asks whether there are more is not counted as one that is there. */
  @Test
  void theRowThatAsksForMoreIsNotCounted() throws Exception {
    var logs = PG + "/shop.public.logs";
    servers.rows(logs, 200, OptionalLong.empty());
    var app = shop();
    c.run(app, table("logs") + SETTLE + "snap read\n");
    assertTrue(status(c.tree("read")).contains("\"At least 200 rows\""), c.tree("read"));
    assertTrue(line(c.tree("read"), "#rows ").contains("/201"), c.tree("read"));
    c.run(app, scroll(199) + SETTLE + "snap end\n");
    assertTrue(status(c.tree("end")).contains("\"200 rows\""), c.tree("end"));
  }

  // Paging

  /** Presses a paging button; the grid tells the rows it built in the frame after. */
  static String page(String label) {
    return "press role=button label=\"" + label + "\"\nframe\nframe\n";
  }

  static String inView(String tree) {
    return line(tree, "#rows-in-view");
  }

  static boolean off(String tree, String label) {
    return line(tree, "role=button label=\"" + label + "\"").contains(" disabled");
  }

  @Test
  void thePagingButtonsScrollByTheRowsInViewAndKeepTheSelection() throws Exception {
    servers.rows(BIG, 10_000, OptionalLong.of(10_000));
    var app = shop();
    c.run(app, table("big") + SETTLE + "press #cell-2-1\nadvance 1\nframe\nsnap top\n");
    String top = c.tree("top");
    assertTrue(inView(top).contains("\"Rows 1–23\""), top);
    assertTrue(off(top, "First page") && off(top, "Previous page"), top);
    assertFalse(off(top, "Next page") || off(top, "Last page"), top);
    int before = servers.calls.size();
    c.run(app, page("Next page") + SETTLE + "snap next\n");
    String next = c.tree("next");
    assertTrue(inView(next).contains("\"Rows 24–46\""), next);
    assertFalse(off(next, "First page") || off(next, "Previous page"), next);
    // The rows of the next page were read with the first.
    assertEquals(before, servers.calls.size(), servers.calls.toString());
    c.run(app, page("Previous page") + SETTLE + "snap back\n");
    assertTrue(inView(c.tree("back")).contains("\"Rows 1–23\""), c.tree("back"));
    c.run(app, page("Last page") + SETTLE + "snap last\n");
    String last = c.tree("last");
    assertTrue(inView(last).contains("\"Rows 9,978–10,000\""), last);
    assertTrue(cell(last, 9_999, 0).contains("\"10000\""), last);
    assertTrue(off(last, "Next page") && off(last, "Last page"), last);
    assertFalse(off(last, "First page") || off(last, "Previous page"), last);
    // As scrolling there reads: the page in view, and the one before it ahead.
    assertEquals(
        List.of("read " + BIG + " 9800-10000", "read " + BIG + " 9600-9800"),
        servers.calls.subList(before, servers.calls.size()));
    c.run(app, page("First page") + SETTLE + "snap first\n");
    assertTrue(inView(c.tree("first")).contains("\"Rows 1–23\""), c.tree("first"));
    var selection = tab(app).selection;
    assertEquals(List.of(2, 1), List.of(selection.row(), selection.column()));
    assertFalse(servers.overlapped);
  }

  /** One row on by the wheel shows at once, also within the rows the grid built around. */
  @ParameterizedTest
  @ValueSource(ints = {10_000, 25})
  void theRowsInViewFollowEachStepOfTheWheel(int count) throws Exception {
    servers.rows(BIG, count, OptionalLong.of(count));
    var app = shop();
    // The grid tells the rows in view in the frame after it moved them.
    c.run(app, table("big") + SETTLE + "wheel #rows 0 24\nframe\nframe\nsnap one\n");
    String one = c.tree("one");
    assertTrue(inView(one).contains("\"Rows 2–24\""), one);
    assertFalse(off(one, "First page") || off(one, "Previous page"), one);
    assertFalse(off(one, "Next page") || off(one, "Last page"), one);
    c.run(app, "wheel #rows 0 -24\nframe\nframe\nsnap back\n");
    String back = c.tree("back");
    assertTrue(inView(back).contains("\"Rows 1–23\""), back);
    assertTrue(off(back, "First page") && off(back, "Previous page"), back);
  }

  /** A table of about a page shows its last rows, and their buttons off, as the wheel gets there. */
  @Test
  void theLastRowsOfATableOfAPageShowAsTheWheelGetsThere() throws Exception {
    servers.rows(BIG, 25, OptionalLong.of(25));
    var app = shop();
    c.run(app, table("big") + SETTLE + "wheel #rows 0 48\nframe\nframe\nsnap end\n");
    String end = c.tree("end");
    assertTrue(inView(end).contains("\"Rows 3–25\""), end);
    assertTrue(off(end, "Next page") && off(end, "Last page"), end);
    assertFalse(off(end, "First page") || off(end, "Previous page"), end);
  }

  /** Last page, as Control+End, finds the end where the estimate was far too high. */
  @ParameterizedTest
  @ValueSource(strings = {"press role=button label=\"Last page\"\n", "key End control\n"})
  void theLastPageOfAnEstimateTooHighIsTheLastRow(String last) throws Exception {
    servers.rows(BIG, 1000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE + "press #cell-0-0\nadvance 1\nframe\n");
    int before = servers.calls.size();
    c.run(app, last + "frame\nframe\n" + SETTLE + SETTLE + "snap end\n");
    var after = servers.calls.subList(before, servers.calls.size());
    String end = c.tree("end");
    assertTrue(status(end).contains("\"1,000 rows\""), end);
    assertTrue(inView(end).contains("–1,000\""), end);
    assertTrue(cell(end, 999, 0).contains("\"1000\""), end);
    assertTrue(off(end, "Next page") && off(end, "Last page"), end);
    assertEquals(1, after.stream().filter(x -> x.startsWith("count")).count(), after.toString());
    assertTrue(after.size() <= 7, after.toString());
  }

  /** Each tab keeps its own rows in view, also a table wide enough for a bar along the bottom. */
  @Test
  void eachTabPagesItsOwnGrid() throws Exception {
    servers.rows(BIG, 10_000, OptionalLong.of(10_000));
    servers.rows(KINDS, 1000, OptionalLong.of(1000));
    var app = shop();
    c.run(app, table("big") + SETTLE + "advance 1\nframe\n" + page("Next page") + SETTLE);
    c.run(app, table("kinds") + SETTLE + "advance 1\nframe\nframe\nsnap kinds\n");
    String kinds = c.tree("kinds");
    assertTrue(inView(kinds).contains("\"Rows 1–"), kinds);
    c.run(app, page("Last page") + SETTLE + "snap end\n");
    String end = c.tree("end");
    assertTrue(inView(end).contains("–1,000\""), end);
    assertTrue(off(end, "Next page") && off(end, "Last page"), end);
    c.run(app, "press role=tab label=\"big\"\nframe\nadvance 1\nframe\nframe\nsnap big\n");
    assertTrue(inView(c.tree("big")).contains("\"Rows 24–46\""), c.tree("big"));
  }

  /** The row that asks whether there are more is not said to be in view. */
  @Test
  void theRowThatAsksForMoreIsNotInView() throws Exception {
    var logs = PG + "/shop.public.logs";
    servers.rows(logs, 200, OptionalLong.empty());
    var app = shop();
    c.run(app, table("logs") + SETTLE + "advance 1\nframe\n");
    var held = servers.hold("read " + logs + " 200-400");
    c.run(app, page("Last page") + "snap asking\n");
    String asking = c.tree("asking");
    assertTrue(inView(asking).contains("–200\""), asking);
    assertTrue(off(asking, "Next page") && off(asking, "Last page"), asking);
    held.countDown();
    c.run(app, SETTLE + "snap end\n");
    assertTrue(status(c.tree("end")).contains("\"200 rows\""), c.tree("end"));
  }

  // The lane

  @Test
  void aPageAskedForAheadGoesFirstOnceItIsInView() throws Exception {
    servers.rows(BIG, 10_000, OptionalLong.of(10_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "read " + BIG + " 5000-5200";
    var gate = servers.hold(key);
    c.run(app, scroll(5050));
    eventually(() -> servers.calls.contains(key));
    // Behind the read in the server: the pages ahead, then the tree's columns; then the page
    // ahead comes into view.
    c.run(app, open("customers") + scroll(5250));
    gate.countDown();
    c.run(app, SETTLE);
    var after = servers.calls.subList(servers.calls.indexOf(key) + 1, servers.calls.size());
    assertEquals("read " + BIG + " 5200-5400", after.getFirst(), after.toString());
    assertFalse(after.contains("read " + BIG + " 4800-5000"), "out of view: " + after);
  }

  // Tabs

  @Test
  void aTableReplacedByAViewOfItsNameKeepsItsTab() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    servers.serve(
        PG,
        Provider.POSTGRESQL,
        "16.4",
        OUTLINE.replaceFirst("    orders\n      \\*id: bigint", "    @orders\n      *id: bigint"));
    c.run(app, menu("public", "Refresh") + SETTLE + table("orders") + SETTLE + "snap view\n");
    String view = c.tree("view");
    assertEquals("orders", tabs(view));
    assertTrue(line(view, "role=tab label=\"orders\"").contains("selected"), view);
    assertEquals(dev.hoori.db.core.catalog.Table.Kind.VIEW, tab(app).table.kind());
    assertTrue(view.substring(view.indexOf("#tab-0")).contains("label=\"eye\""), view);
  }

  @Test
  void aRefusedCopySaysSoInTheWindowThatAskedForIt() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(
        app,
        table("big") + SETTLE + "press #tab-0 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "window \"big\"\npress #cell-0-0\nframe\nkey A control\nkey C control\nframe\n"
            + "snap copied\n");
    String window = c.tree("big-copied");
    assertTrue(
        window.contains("Only rows that were read can be copied; select fewer rows."), window);
  }

  @Test
  void rowsBeyondTheGridsLimitAreSaidToBeLeftOut() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    var tab = tab(app);
    tab.counted = TableTab.Counted.EXACT;
    tab.rows = Integer.MAX_VALUE;
    assertEquals("2,147,483,647 rows", tab.rowsText());
    tab.rows = 3_000_000_000L;
    assertEquals("3,000,000,000 rows · the first 2,147,483,647 are shown", tab.rowsText());
    tab.counted = TableTab.Counted.ESTIMATED;
    assertEquals(
        "About 3,000,000,000 rows, as the server estimates · the first 2,147,483,647 are shown",
        tab.rowsText());
  }

  // Values

  @Test
  void anOffsetKeepsItsSecondsAndZeroItsSign() {
    assertEquals(
        "01:02:03+00:00:30",
        Cells.text(java.time.OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofTotalSeconds(30)), ZONE));
    assertEquals(
        "01:02:03+05:30",
        Cells.text(java.time.OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHoursMinutes(5, 30)), ZONE));
    assertEquals("-0", Cells.text(-0.0, ZONE));
    assertEquals("-0", Cells.text(-0.0f, ZONE));
    assertEquals("0", Cells.text(0.0, ZONE));
  }

  @Test
  void aMomentShowsInTheLocalZoneWithTheOffsetOfItsOwnDate() {
    assertEquals(
        "2026-11-14 11:59:32+01",
        Cells.text(OffsetDateTime.of(2026, 11, 14, 10, 59, 32, 0, ZoneOffset.UTC), ZONE));
    assertEquals(
        "2026-07-14 12:59:32.5+02",
        Cells.text(OffsetDateTime.of(2026, 7, 14, 10, 59, 32, 500_000_000, ZoneOffset.UTC), ZONE));
    assertEquals(
        "2026-07-14 12:59:32+02",
        Cells.text(OffsetDateTime.of(2026, 7, 14, 6, 59, 32, 0, ZoneOffset.ofHours(-4)), ZONE));
    // Without a zone there is no moment to move.
    assertEquals(
        "2026-07-14 10:59:32", Cells.text(LocalDateTime.of(2026, 7, 14, 10, 59, 32), ZONE));
  }

  @Test
  void aMomentIsCopiedAsItShows() throws Exception {
    var app = shop();
    servers.rows(ORDERS, 10, OptionalLong.of(10));
    c.run(app, table("orders") + SETTLE + "press #cell-1-2\nframe\nkey C control\nframe\n");
    assertEquals("2026-01-01 10:01:00+01", app.clipboard().read());
  }

  String snapped(App<State> app, String name) throws Exception {
    c.run(app, "snap " + name + "\n");
    return c.tree(name);
  }
}

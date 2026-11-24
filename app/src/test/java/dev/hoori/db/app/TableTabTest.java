package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
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
import static dev.hoori.db.app.LayoutTest.widthOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.kit.Split;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A table's rows in a tab: read in pages, late, on the lane of its connection. */
class TableTabTest {

  private static final Pattern LABEL = Pattern.compile("label=\"([^\"]*)\"");
  private static final Pattern BOX = Pattern.compile(" (-?[\\d.]+),-?[\\d.]+ ([\\d.]+)x");
  private static final Pattern READ = Pattern.compile("\"Read 200 rows in \\d+ ms\"");

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
            .map(
                l -> LABEL.matcher(l).results().map(r -> r.group(1)).findFirst().orElse(l))
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
    assertTrue(READ.matcher(line(opened, "#read-status")).find(), opened);
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
  void withoutAnEstimateThePagesTell() throws Exception {
    servers.rows(ORDERS, 50, OptionalLong.empty()); // as a view on MySQL
    servers.rows(BIG, 1000, OptionalLong.empty());
    var app = shop();
    c.run(app, table("orders") + SETTLE + "snap orders\n" + table("big") + SETTLE + "snap big\n");
    assertTrue(status(c.tree("orders")).contains("\"50 rows\""), c.tree("orders"));
    // Each full page at the end says that there are more, which the grid then asks for.
    assertTrue(status(c.tree("big")).contains("\"At least "), c.tree("big"));
    assertFalse(servers.calls.contains("count " + BIG), "a table of unknown size is not counted");
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
  void theColumnsFitTheirRowsOnceAndAgainWhenARefreshChangesThem() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    var held = servers.hold("read " + ORDERS + " 0-200");
    c.run(app, open("orders") + SETTLE + table("orders") + "frame\n");
    var tab = tab(app);
    float[] guessed = tab.widths.clone();
    assertTrue(tab.fit.isPending());
    held.countDown();
    c.run(app, SETTLE);
    assertFalse(tab.fit.isPending());
    var grid = app.state().theme.grid();
    for (float width : tab.widths) {
      assertTrue(
          width >= grid.minColumn() && width <= grid.maxColumn(), Arrays.toString(tab.widths));
    }
    assertFalse(Arrays.equals(guessed, tab.widths), Arrays.toString(tab.widths));
    // The same columns keep every width, also one set by hand.
    tab.widths[3] = 333.5f;
    float[] kept = tab.widths.clone();
    c.run(app, "press #refresh\nframe\n" + SETTLE);
    assertTrue(Arrays.equals(kept, tab.widths), Arrays.toString(tab.widths));
    // A column gone: all of them fit again.
    servers.serve(
        PG, Provider.POSTGRESQL, "16.4", OUTLINE.replace("      customer_id: integer\n", ""));
    c.run(app, "press #refresh\nframe\n" + SETTLE);
    assertFalse(tab.fit.isPending());
    assertEquals(3, tab.widths.length, Arrays.toString(tab.widths));
    assertTrue(tab.widths[2] != 333.5f, Arrays.toString(tab.widths));
    assertEquals(kept[0], tab.widths[0], Arrays.toString(tab.widths));
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
    c.run(app, """
            press #tab-1 right
            frame
            advance 1
            frame
            press role=menuitem label="Move to new window"
            frame
            advance 1
            frame
            snap torn
            """);
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
    c.run(app, """
            window "big"
            press #tab-0 right
            frame
            advance 1
            frame
            press role=menuitem label="Move back to main window"
            frame
            advance 1
            frame
            frame
            snap back
            """);
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

  /** A read the user scrolls past ends the connection where core gave it up; the tree says so. */
  @Test
  void aConnectionGivenUpByAnyCallIsLostAlsoForTheCallsBehindIt() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "read " + BIG + " 500000-500200";
    servers.hold(key);
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.ABORTED));
    c.run(app, scroll(500_000));
    eventually(() -> servers.calls.contains(key));
    c.run(app, open("customers") + scroll(0) + SETTLE);
    c.run(app, SETTLE + "snap lost\n");
    String lost = c.tree("lost");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(lost));
    assertTrue(lost.contains("as the server did not stop a cancelled read"), lost);
  }

  /**
   * A long count runs on a connection of its own per database: while it is held, the tree can
   * still open a level of the same database, and its loss does not disconnect the tree.
   */
  @Test
  void aHeldCountDoesNotHoldTheTreeOfTheSameDatabase() throws Exception {
    // An estimate far too high: the empty pages where the grid scrolls to are counted.
    servers.rows(BIG, 1000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    assertEquals(
        1,
        servers.calls.stream().filter(("open " + PG + "/shop")::equals).count(),
        "opening a large table opens only the tree's connection");
    var key = "count " + BIG;
    var held = servers.hold(key);
    c.run(app, scroll(500_000) + "await\nframe\n"); // the empty pages arrive and start the count
    eventually(() -> servers.calls.contains(key));
    // While the count is held, the tree still opens customers on the same database.
    c.run(app, open("customers"));
    var columns = "columns " + PG + "/shop.public.customers";
    app.await("read customers", Duration.ofSeconds(10));
    c.run(app, "frame\nadvance 1\nframe\nsnap while-counting\n");
    String tree = c.tree("while-counting");
    assertTrue(rows(tree).contains("        id"), rows(tree).toString());
    assertTrue(servers.linked.contains("1 " + columns), servers.linked.toString());
    assertTrue(servers.linked.contains("2 " + key), servers.linked.toString());
    held.countDown();
    c.run(app, SETTLE + "snap counted\n");
    assertTrue(status(c.tree("counted")).contains("\"1,000 rows\""), c.tree("counted"));
    assertEquals(
        2,
        servers.calls.stream().filter(("open " + PG + "/shop")::equals).count(),
        servers.calls.toString());
    // A count connection that is lost, as one the server closed while idle, is replaced and the
    // count asked again, once; its loss leaves the tree's connection intact. A refresh counts again
    // what was counted.
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.CONNECTION_LOST));
    c.run(app, "advance 1\nframe\npress #refresh\nframe\n" + SETTLE);
    assertTrue(app.state().catalog.connected(SHOP.id()));
    assertEquals(
        3,
        servers.calls.stream().filter(("open " + PG + "/shop")::equals).count(),
        servers.calls.toString());
    assertEquals(2, servers.failed.stream().filter(key::equals).count(), servers.failed.toString());
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
  void aRefreshCountsAgainATableThatGrew() throws Exception {
    servers.rows(ORDERS, 50, OptionalLong.of(50));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    servers.rows(ORDERS, 250, OptionalLong.of(50));
    c.run(app, "advance 1\nframe\npress #refresh\nframe\n" + SETTLE + scroll(100) + SETTLE);
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
    // The empty pages where the grid was, the count connection's open, one count, and the pages
    // at the end.
    assertEquals(1, after.stream().filter(x -> x.startsWith("count")).count(), after.toString());
    assertTrue(after.size() <= 8, after.toString());
  }

  @Test
  void aTabClosedWhileItOpensNeverStartsItsCount() throws Exception {
    servers.rows(ORDERS, 1000, OptionalLong.of(1000));
    var app = shop();
    var key = "read " + ORDERS + " 0-200";
    var held = servers.hold(key);
    c.run(app, open("orders") + SETTLE + table("orders") + "frame\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n");
    eventually(() -> servers.interrupted.contains(key));
    held.countDown();
    c.run(app, SETTLE);
    assertFalse(servers.calls.contains("count " + ORDERS), servers.calls.toString());
  }

  @Test
  void aTabClosedWhileReadingNeverStartsItsPagesAhead() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "read " + BIG + " 500000-500200";
    var held = servers.hold(key);
    c.run(app, scroll(500_000));
    eventually(() -> servers.calls.contains(key));
    // The read that runs is interrupted, which lets the lane go on at once: the pages ahead behind
    // it have left the lane by then.
    c.run(app, "press #close-0\nframe\n");
    eventually(() -> servers.interrupted.contains(key));
    held.countDown();
    c.run(app, SETTLE);
    assertFalse(servers.calls.contains("read " + BIG + " 500200-500400"), servers.calls.toString());
    assertFalse(servers.calls.contains("read " + BIG + " 499600-499800"), servers.calls.toString());
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
    assertTrue(inView(top).contains("\"Rows 1–22\""), top);
    assertTrue(off(top, "First page") && off(top, "Previous page"), top);
    assertFalse(off(top, "Next page") || off(top, "Last page"), top);
    int before = servers.calls.size();
    c.run(app, page("Next page") + SETTLE + "snap next\n");
    String next = c.tree("next");
    assertTrue(inView(next).contains("\"Rows 23–44\""), next);
    assertFalse(off(next, "First page") || off(next, "Previous page"), next);
    // The rows of the next page were read with the first.
    assertEquals(before, servers.calls.size(), servers.calls.toString());
    c.run(app, page("Previous page") + SETTLE + "snap back\n");
    assertTrue(inView(c.tree("back")).contains("\"Rows 1–22\""), c.tree("back"));
    c.run(app, page("Last page") + SETTLE + "snap last\n");
    String last = c.tree("last");
    assertTrue(inView(last).contains("\"Rows 9,979–10,000\""), last);
    assertTrue(cell(last, 9_999, 0).contains("\"10000\""), last);
    assertTrue(off(last, "Next page") && off(last, "Last page"), last);
    assertFalse(off(last, "First page") || off(last, "Previous page"), last);
    // As scrolling there reads: the page in view, and the one before it ahead.
    assertEquals(
        List.of("read " + BIG + " 9800-10000", "read " + BIG + " 9600-9800"),
        servers.calls.subList(before, servers.calls.size()));
    c.run(app, page("First page") + SETTLE + "snap first\n");
    assertTrue(inView(c.tree("first")).contains("\"Rows 1–22\""), c.tree("first"));
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
    assertTrue(inView(one).contains("\"Rows 2–23\""), one);
    assertFalse(off(one, "First page") || off(one, "Previous page"), one);
    assertFalse(off(one, "Next page") || off(one, "Last page"), one);
    c.run(app, "wheel #rows 0 -24\nframe\nframe\nsnap back\n");
    String back = c.tree("back");
    assertTrue(inView(back).contains("\"Rows 1–22\""), back);
    assertTrue(off(back, "First page") && off(back, "Previous page"), back);
  }

  /** A table of about a page shows its last rows, and their buttons off, as the wheel gets there. */
  @Test
  void theLastRowsOfATableOfAPageShowAsTheWheelGetsThere() throws Exception {
    servers.rows(BIG, 25, OptionalLong.of(25));
    var app = shop();
    c.run(app, table("big") + SETTLE + "wheel #rows 0 72\nframe\nframe\nsnap end\n");
    String end = c.tree("end");
    assertTrue(inView(end).contains("\"Rows 4–25\""), end);
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
    assertTrue(inView(c.tree("big")).contains("\"Rows 23–44\""), c.tree("big"));
  }

  /** The row that asks whether there are more is not said to be in view. */
  @Test
  void theRowThatAsksForMoreIsNotInView() throws Exception {
    var logs = PG + "/shop.public.logs";
    servers.rows(logs, 200, OptionalLong.empty());
    var app = shop();
    c.run(app, table("logs") + SETTLE + "advance 1\nframe\n");
    var held = servers.hold("read " + logs + " 200-400");
    // Control+End scrolls to the row that asks; Last page would count first.
    c.run(app, "press #cell-0-0\nadvance 1\nframe\nkey End control\nframe\nframe\nsnap asking\n");
    String asking = c.tree("asking");
    assertTrue(inView(asking).contains("–200\""), asking);
    assertTrue(off(asking, "Next page") && off(asking, "Last page"), asking);
    held.countDown();
    c.run(app, SETTLE + "snap end\n");
    assertTrue(status(c.tree("end")).contains("\"200 rows\""), c.tree("end"));
  }

  /** A failed exact count shows beside the rows and lets scrolling keep reading pages. */
  @Test
  void aFailedCountShowsInStatusAndKeepsPaging() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.empty());
    servers.fail("count " + BIG, FakeServers.queryFailure(QueryException.Failure.OTHER));
    var app = shop();
    c.run(app, table("big") + SETTLE);
    assertFalse(servers.calls.contains("read " + BIG + " 400-600"), servers.calls.toString());

    c.run(app, page("Last page") + SETTLE + "advance 1\nframe\nsnap failed\n");
    String failed = c.tree("failed");
    assertTrue(status(failed).contains("\"Count failed · at least 400 rows\""), failed);
    assertFalse(failed.contains("role=note"), failed);
    assertTrue(cell(failed, 0, 0).contains("\"1\""), failed);
    assertNull(tab(app).failure);

    // Scrolling past the pages already read still reads further pages.
    c.run(app, scroll(399) + SETTLE + "snap scrolled\n");
    String scrolled = c.tree("scrolled");
    assertTrue(servers.calls.contains("read " + BIG + " 400-600"), servers.calls.toString());
    assertTrue(cell(scrolled, 400, 0).contains("\"401\""), scrolled);
    assertTrue(status(scrolled).contains("\"Count failed · at least 800 rows\""), scrolled);
    // Why, in the status line's tooltip.
    c.run(app, "move #rows-status\nframe\nadvance 1\nframe\nframe\nsnap tip\n");
    String tip = c.tree("tip");
    assertFalse(scrolled.contains("the server's words for OTHER"), scrolled);
    assertTrue(
        ConnectionsTest.line(tip, "role=tooltip").contains("the server's words for OTHER"), tip);

    // A later count that succeeds clears the failure note.
    servers.heal("count " + BIG);
    c.run(app, page("Last page") + SETTLE + "frame\nframe\n" + SETTLE + "snap healed\n");
    String healed = c.tree("healed");
    assertTrue(status(healed).contains("\"1,000 rows\""), healed);
    assertFalse(status(healed).contains("Count failed"), healed);
  }

  /** In a narrow tab the status line keeps room for the count; the texts at its end give way. */
  @Test
  void aNarrowFooterKeepsRoomForTheCount() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.empty());
    servers.fail("count " + BIG, FakeServers.queryFailure(QueryException.Failure.OTHER));
    String tree = narrow(shop(), page("Last page") + SETTLE);
    assertTrue(status(tree).contains("\"Count failed · at least 400 rows\""), tree);
    assertTrue(widthOf(tree, "#rows-status ") >= 190, tree);
    assertTrue(widthOf(tree, "#rows-status ") > widthOf(tree, "#read-status "), tree);
  }

  /** A status longer than half a narrow footer is cut and leaves the paging inside the tab. */
  @Test
  void aNarrowFooterCutsALongStatus() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    String tree = narrow(shop(), "");
    assertTrue(status(tree).contains("About 1,000,000 rows, as the server estimates"), tree);
    assertTrue(widthOf(tree, "#rows-status ") >= 190, tree);
    assertTrue(end(tree, "#value-toggle ") <= end(tree, "#grid "), tree);
  }

  /** While a page is read, its spinner stays and its text gives way with the paging inside. */
  @Test
  void aNarrowFooterKeepsThePagingWhileReading() throws Exception {
    servers.rows(BIG, 1_000_000, OptionalLong.of(1_000_000));
    var held = servers.hold("read " + BIG + " 400-600");
    var app = shop();
    String tree = narrow(app, scroll(450));
    assertTrue(line(tree, "#read-status ").contains("\"Reading rows…\""), tree);
    assertTrue(widthOf(tree, "#rows-status ") >= 190, tree);
    assertTrue(end(tree, "#value-toggle ") <= end(tree, "#grid "), tree);
    held.countDown();
    c.run(app, SETTLE);
  }

  /** Opens big beside a 400 px sidebar in an 850 px window, runs the script and snaps it. */
  String narrow(App<State> app, String script) throws Exception {
    app.state().sidebar = Split.Position.first(400);
    c.run(
        app,
        "size 850 760\nframe\n"
            + table("big")
            + SETTLE
            + script
            + "advance 1\nframe\nsnap narrow-footer\n");
    return c.tree("narrow-footer");
  }

  /** Where the first element that matches ends at the right. */
  static float end(String tree, String match) {
    var box = BOX.matcher(line(tree, match));
    assertTrue(box.find(), tree);
    return Float.parseFloat(box.group(1)) + Float.parseFloat(box.group(2));
  }

  /** A count that fails after the pages found the end leaves the exact rows without a warning. */
  @Test
  void aCountThatFailsAfterThePagesEndedIsNotShown() throws Exception {
    servers.rows(BIG, 1000, OptionalLong.empty());
    var app = shop();
    c.run(app, table("big") + SETTLE);
    var key = "count " + BIG;
    var held = servers.hold(key);
    servers.fail(key, FakeServers.queryFailure(QueryException.Failure.OTHER));
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(key));
    for (long row : new long[] {399, 599, 799, 1000}) {
      c.run(app, scroll(row));
      app.await("read rows of big", Duration.ofSeconds(10));
      c.run(app, "frame\nframe\n");
    }
    c.run(app, "snap ended\n");
    assertTrue(status(c.tree("ended")).contains("\"Counting… · 1,000 rows\""), c.tree("ended"));
    held.countDown();
    c.run(app, SETTLE + "snap failed\n");
    assertTrue(status(c.tree("failed")).contains("\"1,000 rows\""), c.tree("failed"));
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

  @Test
  void timeZoneSettingUpdatesMomentsInTheGridAndCopies() throws Exception {
    var app = shop();
    servers.rows(ORDERS, 10, OptionalLong.of(10));
    c.run(app, table("orders") + SETTLE);
    app.post(() -> app.state().choose(app.state().settings.withTimeZone(ZoneId.of("UTC"))));
    c.run(app, "await\nframe\nsnap utc\npress #cell-1-2\nframe\nkey C control\nframe\n");
    assertTrue(cell(c.tree("utc"), 1, 2).contains("\"2026-01-01 09:01:00+00\""), c.tree("utc"));
    assertEquals("2026-01-01 09:01:00+00", app.clipboard().read());
  }

  @Test
  void copyNullSettingControlsHowNullCellsAreCopied() throws Exception {
    var app = shop();
    servers.rows(ORDERS, 10, OptionalLong.of(10));
    c.run(app, table("orders") + SETTLE + "press #cell-6-3\nframe\nkey C control\nframe\n");
    assertEquals("\"\"", app.clipboard().read(), "default copies NULL as empty");

    app.post(() -> app.state().choose(app.state().settings.withCopyNull(Settings.CopyNull.NULL)));
    c.run(app, "await\nframe\nkey C control\nframe\n");
    assertEquals("NULL", app.clipboard().read());

    app.post(
        () -> app.state().choose(app.state().settings.withCopyNull(Settings.CopyNull.BACKSLASH_N)));
    c.run(app, "await\nframe\nkey C control\nframe\n");
    assertEquals("\\N", app.clipboard().read());
  }

  @Test
  void openInConsoleOpensAConsoleWithQualifiedSelectOnTheTablesDatabase() throws Exception {
    var app = shop();
    c.run(
        app,
        open("analytics")
            + SETTLE
            + open("tracking")
            + SETTLE
            + table("events")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap pg-console\n");
    var expected = "SELECT * FROM \"tracking\".\"events\"";
    var console =
        assertInstanceOf(ConsoleTab.class, app.state().tabs.main().active().activeTab());
    assertEquals(SHOP.id(), console.id);
    assertEquals("analytics", console.database);
    assertEquals(expected, console.text);
    assertEquals(expected.length(), console.editor.caret());
    assertEquals(expected.length(), console.editor.anchor());
    var tree = c.tree("pg-console");
    assertTrue(line(tree, "#sql").contains("focused"), tree);
    // Typing goes right after the table name.
    c.run(app, "type \" LIMIT 5\"\nframe\n");
    assertEquals(expected + " LIMIT 5", console.text);
    // Still available while disconnected, and opens in the tab's own window when none matches.
    c.run(
        app,
        "key W control\nframe\nadvance 1\nframe\n"
            + menu("shop-prod", "Disconnect")
            + "press #tab-0 right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n"
            + "window \"events\"\n"
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    var tornGroup = app.state().tabs.groupOf(tab(app));
    var tornConsole = assertInstanceOf(ConsoleTab.class, tornGroup.activeTab());
    assertEquals("analytics", tornConsole.database);
    assertEquals(expected, tornConsole.text);
    assertEquals(expected.length(), tornConsole.editor.caret());
  }

  @Test
  void openInConsoleReusesAnOpenConsoleOfTheTablesDatabase() throws Exception {
    var app = shop();
    // An empty open console on shop takes the SELECT with no leading blank line.
    c.run(
        app,
        menu("shop-prod", "New console")
            + "frame\n"
            + table("orders")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap reused-empty\n");
    var main = app.state().tabs.main().active();
    assertEquals(2, main.tabs().size(), "reused the open console instead of opening a second");
    var console = assertInstanceOf(ConsoleTab.class, main.activeTab());
    // Inserted as a statement of its own, so closed with a terminator.
    var ordersSql = "SELECT * FROM \"public\".\"orders\";";
    assertEquals(ordersSql, console.text);
    assertEquals(ordersSql.length(), console.editor.caret());
    assertEquals(ordersSql.length(), console.editor.anchor());
    var emptyTree = c.tree("reused-empty");
    assertTrue(
        line(emptyTree, "role=tab label=\"shop-prod: shop\"").contains("selected"), emptyTree);

    // A second table appends after a blank line, keeping the console's existing text and caret at
    // the end.
    c.run(
        app,
        table("customers")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap reused-appended\n");
    assertEquals(3, main.tabs().size(), "still only one console tab");
    assertEquals(console, main.activeTab());
    var customersSql = "SELECT * FROM \"public\".\"customers\";";
    var appended = ordersSql + "\n\n" + customersSql;
    assertEquals(appended, console.text);
    assertEquals(appended.length(), console.editor.caret());
    assertEquals(appended.length(), console.editor.anchor());
    var appendedTree = c.tree("reused-appended");
    assertTrue(
        line(appendedTree, "role=tab label=\"shop-prod: shop\"").contains("selected"),
        appendedTree);
    assertTrue(line(appendedTree, "#sql").contains("focused"), appendedTree);
    // Typing goes right after the appended statement.
    c.run(app, "type \" LIMIT 10\"\nframe\n");
    assertEquals(appended + " LIMIT 10", console.text);

    // A statement left open is closed before the blank line; a blank line at the end is kept as
    // the one before the statement.
    var open = "SELECT * FROM \"public\".\"orders\"";
    var again = """
        press role=tab label="customers"
        frame
        advance 1
        frame
        press role=button label="Open in console"
        frame
        advance 1
        frame
        """;
    console.text = open + "\n";
    c.run(app, again);
    assertEquals(ordersSql + "\n\n" + customersSql, console.text);
    console.text = open + "\n\n";
    c.run(app, again);
    assertEquals(ordersSql + "\n\n" + customersSql, console.text);

    // Tear off customers into a second window: with no console in its own window, Open in console
    // reuses the main window's console and raises main.
    c.run(app, """
        press role=tab label="customers"
        frame
        advance 1
        frame
        press role=tab label="customers" right
        frame
        advance 1
        frame
        press role=menuitem label="Move to new window"
        frame
        advance 1
        frame
        window "customers"
        press role=button label="Open in console"
        frame
        advance 1
        frame
        """);
    var tornGroup =
        app.state().tabs.groups().stream().filter(g -> g != main).findFirst().orElseThrow();
    assertEquals(1, tornGroup.tabs().size(), "no new console opened in the torn-off window");
    assertEquals(console, main.activeTab());
    assertEquals(ordersSql + "\n\n" + customersSql + "\n\n" + customersSql, console.text);

    // With a matching console in the torn-off window as well, the table's own window is preferred.
    app.post(() -> app.state().openConsole(SHOP.id(), tornGroup, "shop", "SELECT 1"));
    c.run(app, "await\nframe\n");
    c.run(
        app,
        "window \""
            + tornGroup.window().title()
            + "\"\nframe\n"
            + "press role=tab label=\"customers\"\nframe\nadvance 1\nframe\n"
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    var localConsole = assertInstanceOf(ConsoleTab.class, tornGroup.activeTab());
    assertEquals("SELECT 1;\n\n" + customersSql, localConsole.text);
    assertEquals(
        ordersSql + "\n\n" + customersSql + "\n\n" + customersSql,
        console.text,
        "main window's console left untouched when the table's window has its own");
  }

  @Test
  void openInConsoleDoesNotReuseAConsoleOnAnotherDatabase() throws Exception {
    var app = shop();
    c.run(
        app,
        table("orders")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + open("analytics")
            + SETTLE
            + open("tracking")
            + SETTLE
            + table("events")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap other-db\n");
    var main = app.state().tabs.main().active();
    var consoles = app.state().consoles(SHOP.id());
    assertEquals(2, consoles.size(), "console on shop was not reused for analytics");
    var shopConsole =
        consoles.stream().filter(x -> "shop".equals(x.database)).findFirst().orElseThrow();
    var analyticsConsole = assertInstanceOf(ConsoleTab.class, main.activeTab());
    assertEquals("analytics", analyticsConsole.database);
    assertEquals("SELECT * FROM \"public\".\"orders\"", shopConsole.text);
    assertEquals("SELECT * FROM \"tracking\".\"events\"", analyticsConsole.text);
    var tree = c.tree("other-db");
    assertTrue(line(tree, "role=tab label=\"shop-prod: analytics\"").contains("selected"), tree);
  }

  @Test
  void openInConsoleAlwaysOpensANewConsoleWhenConfigured() throws Exception {
    var app = shop();
    app.post(() ->
        app.state().choose(app.state().settings.withOpenInConsole(Settings.OpenInConsole.NEW)));
    c.run(
        app,
        "await\nframe\n"
            + table("orders")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + table("customers")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap always-new\n");
    var consoles = app.state().consoles(SHOP.id());
    assertEquals(
        2, consoles.size(), "Always a new console opens a second tab on the same database");
    assertEquals("SELECT * FROM \"public\".\"orders\"", consoles.get(0).text);
    var second =
        assertInstanceOf(ConsoleTab.class, app.state().tabs.main().active().activeTab());
    assertEquals(consoles.get(1), second);
    var expected = "SELECT * FROM \"public\".\"customers\"";
    assertEquals(expected, second.text);
    assertEquals(expected.length(), second.editor.caret());
    var tree = c.tree("always-new");
    assertTrue(line(tree, "#sql").contains("focused"), tree);
  }

  @Test
  void openInConsoleUsesBackticksAndDatabaseOnMariaDb() throws Exception {
    var app = c.app(INVENTORY);
    c.ready(app, INVENTORY);
    c.run(
        app,
        START
            + open("inventory")
            + SETTLE
            + "press role=treeitem label=\"inventory\" level=2\nkey ArrowRight\nframe\n"
            + SETTLE
            + table("items")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    var expected = "SELECT * FROM `inventory`.`items`";
    var console =
        assertInstanceOf(ConsoleTab.class, app.state().tabs.main().active().activeTab());
    assertEquals(INVENTORY.id(), console.id);
    assertEquals("inventory", console.database);
    assertEquals(expected, console.text);
    assertEquals(expected.length(), console.editor.caret());
    assertEquals(expected.length(), console.editor.anchor());
  }

  // Filtering and ordering

  /** Replaces the clause field's text in one edit, without opening completions. */
  private static String clause(String label, String text) {
    return "press role=textbox label=\""
        + label
        + "\"\nkey A control\n"
        + (text.isEmpty()
            ? "key Backspace\n"
            : "clipboard \"" + text.replace("\"", "\\\"") + "\"\nkey V control\n")
        + "frame\n";
  }

  @Test
  void whereAndOrderByFilterAndOrderTheRowsAndCount() throws Exception {
    servers.values(
        ORDERS,
        List.of(
            List.of(
                1L,
                10,
                OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                new BigDecimal("10.00")),
            List.of(
                2L,
                20,
                OffsetDateTime.of(2026, 1, 1, 9, 1, 0, 0, ZoneOffset.UTC),
                new BigDecimal("80.00")),
            List.of(
                3L,
                30,
                OffsetDateTime.of(2026, 1, 1, 9, 2, 0, 0, ZoneOffset.UTC),
                new BigDecimal("25.00")),
            List.of(
                4L,
                40,
                OffsetDateTime.of(2026, 1, 1, 9, 3, 0, 0, ZoneOffset.UTC),
                new BigDecimal("120.00"))));
    servers.values(
        ORDERS + " WHERE total > 50 ORDER BY total DESC",
        List.of(
            List.of(
                4L,
                40,
                OffsetDateTime.of(2026, 1, 1, 9, 3, 0, 0, ZoneOffset.UTC),
                new BigDecimal("120.00")),
            List.of(
                2L,
                20,
                OffsetDateTime.of(2026, 1, 1, 9, 1, 0, 0, ZoneOffset.UTC),
                new BigDecimal("80.00"))));
    var app = shop();
    c.run(app, table("orders") + SETTLE + "press #cell-2-0\nframe\n");
    assertEquals(2, tab(app).selection.row());
    int before = servers.calls.size();
    c.run(
        app,
        clause("WHERE", "total > 50")
            + clause("ORDER BY", "total DESC")
            + "key Enter\nframe\n"
            + SETTLE
            + "snap table-filter\n");
    String filtered = c.tree("table-filter");
    assertTrue(status(filtered).contains("\"2 rows\""), filtered);
    assertTrue(cell(filtered, 0, 0).contains("\"4\""), filtered);
    assertTrue(cell(filtered, 0, 3).contains("\"120.00\""), filtered);
    assertTrue(cell(filtered, 1, 0).contains("\"2\""), filtered);
    assertTrue(cell(filtered, 1, 3).contains("\"80.00\""), filtered);
    assertEquals(-1, tab(app).selection.row(), "applying a filter clears the selection");
    var sent = servers.calls.subList(before, servers.calls.size());
    assertFalse(sent.contains("columns " + ORDERS), "reuses known columns: " + sent);
    assertTrue(
        sent.contains("read " + ORDERS + " 0-200 WHERE total > 50 ORDER BY total DESC"),
        sent.toString());
    // Refresh keeps the applied filter.
    int beforeRefresh = servers.calls.size();
    c.run(app, "press #refresh\nframe\n" + SETTLE + "snap refreshed\n");
    var refreshed = servers.calls.subList(beforeRefresh, servers.calls.size());
    assertTrue(
        refreshed.contains("read " + ORDERS + " 0-200 WHERE total > 50 ORDER BY total DESC"),
        refreshed.toString());
    assertTrue(status(c.tree("refreshed")).contains("\"2 rows\""), c.tree("refreshed"));
    // Clearing both fields removes the clauses and shows all rows again.
    c.run(
        app,
        clause("WHERE", "")
            + clause("ORDER BY", "")
            + "key Enter\nframe\n"
            + SETTLE
            + "snap cleared\n");
    String cleared = c.tree("cleared");
    assertTrue(status(cleared).contains("\"4 rows\""), cleared);
    assertTrue(cell(cleared, 0, 0).contains("\"1\""), cleared);
  }

  @Test
  void aRefusedClauseShowsItsReasonAndSendsNothing() throws Exception {
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    int before = servers.calls.size();
    c.run(
        app,
        clause("WHERE", "1=1; DROP TABLE x")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap refused\n");
    String refused = c.tree("refused");
    assertTrue(line(refused, "role=note").contains("label=\"Clause refused\""), refused);
    assertTrue(refused.contains("The WHERE clause holds more than one statement"), refused);
    assertTrue(cell(refused, 0, 0).contains("\"1\""), "the rows read stay: " + refused);
    assertNull(tab(app).failure, "a refused draft is not a failure of the reads");
    assertEquals(
        List.of(),
        servers.calls.subList(before, servers.calls.size()),
        "no call sent for a refused clause");
    // A server error with a filter shows as "Could not be read" with the server's detail.
    var bad = "estimate " + ORDERS + " WHERE bad_expr";
    servers.fail(bad, QueryException.of(QueryException.Failure.OTHER, "ERROR: syntax error"));
    c.run(
        app,
        clause("WHERE", "bad_expr")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap server-error\n");
    String error = c.tree("server-error");
    assertTrue(line(error, "role=note").contains("label=\"Could not be read\""), error);
    assertTrue(error.contains("ERROR: syntax error"), error);
    // A column the clause names that is not there is the clause's error, not a changed table.
    var unknown = "estimate " + ORDERS + " WHERE nosuch = 1";
    servers.fail(
        unknown,
        QueryException.of(
            QueryException.Failure.COLUMNS_CHANGED, "column \"nosuch\" does not exist"));
    c.run(
        app,
        clause("WHERE", "nosuch = 1")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap unknown-column\n");
    String missing = c.tree("unknown-column");
    assertTrue(line(missing, "role=note").contains("label=\"Could not be read\""), missing);
    assertTrue(missing.contains("column \\\"nosuch\\\" does not exist"), missing);
  }

  @Test
  void completionInTheFilterFieldsOffersTheTablesColumnsAndKeywords() throws Exception {
    var app = shop();
    c.run(
        app,
        table("orders")
            + SETTLE
            + "press role=textbox label=\"WHERE\"\nframe\n"
            + "type \"cu\"\nframe\nadvance 1\nframe\nsnap where-complete\n");
    var whereOptions = ConsoleEditorTest.options(c.tree("where-complete"));
    assertTrue(whereOptions.contains("customer_id"), whereOptions.toString());
    assertEquals("customer_id", whereOptions.getFirst(), whereOptions.toString());
    // Enter while the list is open takes the completion instead of applying the filter.
    int before = servers.calls.size();
    c.run(app, "key Enter\nframe\nsnap accepted\n");
    assertTrue(line(c.tree("accepted"), "#where").contains("\"customer_id\""), c.tree("accepted"));
    assertEquals(
        before, servers.calls.size(), "Enter took the completion without querying the server");
    // ORDER BY also completes columns and keywords.
    c.run(app, """
        press role=textbox label="ORDER BY"
        frame
        type "to"
        frame
        advance 1
        frame
        snap order-complete
        """);
    var orderOptions = ConsoleEditorTest.options(c.tree("order-complete"));
    assertEquals("total", orderOptions.getFirst(), orderOptions.toString());
  }

  @Test
  void openInConsoleCarriesAppliedWhereAndOrderByClauses() throws Exception {
    var app = shop();
    c.run(
        app,
        table("orders")
            + SETTLE
            + clause("WHERE", "total > 50")
            + clause("ORDER BY", "id DESC")
            + "key Enter\nframe\n"
            + SETTLE
            // Unapplied edit after applying is ignored by Open in console.
            + clause("WHERE", "total > 999")
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    var console =
        assertInstanceOf(ConsoleTab.class, app.state().tabs.main().active().activeTab());
    var expected = "SELECT * FROM \"public\".\"orders\" AS r\nWHERE total > 50\nORDER BY id DESC";
    assertEquals(expected, console.text);
    assertEquals(expected.length(), console.editor.caret());
  }

  /** Opens the fake MongoDB collection tab used by document filter/sort tests. */
  private App<State> mongoOrders() throws Exception {
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
            customer: string
            total: double
            order name: string
            meta.tag: string
            $score: double
        """);
    var app = c.app(DocumentConsoleTest.DOCS);
    c.ready(app, DocumentConsoleTest.DOCS);
    c.run(app, START + open("docs") + SETTLE + open("hoori") + SETTLE + table("orders") + SETTLE);
    return app;
  }

  @Test
  void lastPageOfAFilteredCollectionCountsFirst() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var many = IntStream.rangeClosed(1, 650)
        .mapToObj(i -> Document.of("_id", i, "customer", "many", "total", (double) i))
        .toList();
    servers.documents(path, many);
    servers.documents(path + " FILTER { total: { $gt: 0 } }", many);
    var app = mongoOrders();
    c.run(
        app,
        clause("Filter", "{ total: { $gt: 0 } }")
            + "key Enter\nframe\n"
            + SETTLE
            + """
            press role=radio label="JSON"
            frame
            advance 1
            frame
            press role=button label="Last page"
            frame
            """
            + SETTLE
            + "advance 1\nframe\nsnap last\n");
    String last = c.tree("last");
    assertTrue(line(last, "#rows-in-view").contains("\"Documents 601–650\""), last);
    assertTrue(status(last).contains("\"650 documents\""), last);
  }

  @Test
  void anotherPageWhileTheLastWaitsForItsCountStays() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var many = IntStream.rangeClosed(1, 650)
        .mapToObj(i -> Document.of("_id", i, "customer", "many", "total", (double) i))
        .toList();
    servers.documents(path, many);
    servers.documents(path + " FILTER { total: { $gt: 0 } }", many);
    var app = mongoOrders();
    c.run(app, clause("Filter", "{ total: { $gt: 0 } }") + "key Enter\nframe\n" + SETTLE + """
            press role=radio label="JSON"
            frame
            advance 1
            frame
            """);
    var held = servers.hold("count " + path + " FILTER { total: { $gt: 0 } }");
    c.run(app, "press role=button label=\"Last page\"\nframe\n");
    c.run(app, "press role=button label=\"Next page\"\nframe\n");
    held.countDown();
    c.run(app, SETTLE + "advance 1\nframe\nsnap stayed\n");
    String stayed = c.tree("stayed");
    assertTrue(line(stayed, "#rows-in-view").contains("\"Documents 201–400\""), stayed);
  }

  private App<State> filteredMongoGrid() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var many = IntStream.rangeClosed(1, 1200)
        .mapToObj(i -> Document.of("_id", i, "customer", "many", "total", (double) i))
        .toList();
    servers.documents(path, many);
    servers.documents(path + " FILTER { total: { $gt: 0 } }", many);
    var app = mongoOrders();
    c.run(app, clause("Filter", "{ total: { $gt: 0 } }") + "key Enter\nframe\n" + SETTLE);
    return app;
  }

  @Test
  void lastPageOfAFilteredGridCountsFirst() throws Exception {
    var app = filteredMongoGrid();
    c.run(app, page("Last page") + SETTLE + "frame\nframe\n" + SETTLE + "snap end\n");
    String end = c.tree("end");
    assertTrue(inView(end).contains("1,200\""), end);
  }

  @Test
  void keyboardNavigationWhileLastPageCountsCancelsTheJump() throws Exception {
    var app = filteredMongoGrid();
    c.run(app, "press #cell-0-0\nadvance 1\nframe\n");
    assertEquals(0, tab(app).selection.row());
    var countKey =
        "count " + DocumentConsoleTest.MONGO + "/hoori.orders FILTER { total: { $gt: 0 } }";
    var held = servers.hold(countKey);
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(countKey));
    c.run(app, "press #cell-0-0\nframe\n");
    assertEquals(0, tab(app).selection.row());
    c.run(app, "key ArrowDown\nframe\nframe\n");
    assertEquals(1, tab(app).selection.row());
    held.countDown();
    c.run(app, SETTLE + "frame\nframe\n" + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertTrue(status(stayed).contains("\"1,200 documents\""), stayed);
    assertFalse(inView(stayed).contains("1,200\""), stayed);
    assertTrue(inView(stayed).contains("\"Documents 1–"), stayed);
  }

  @Test
  void openingStatementLogWhileLastPageCountsKeepsTheJump() throws Exception {
    var app = filteredMongoGrid();
    var countKey =
        "count " + DocumentConsoleTest.MONGO + "/hoori.orders FILTER { total: { $gt: 0 } }";
    var held = servers.hold(countKey);
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(countKey));
    int beforeLast = tab(app).selection.lastRowInView();
    c.run(app, "press role=button label=\"Statement log\"\nframe\nadvance 1\nframe\nframe\n");
    assertTrue(
        tab(app).selection.lastRowInView() < beforeLast,
        "opening the statement log shrinks the rows in view");
    held.countDown();
    c.run(app, SETTLE + "frame\nframe\n" + SETTLE + "snap end\n");
    String end = c.tree("end");
    assertTrue(status(end).contains("\"1,200 documents\""), end);
    assertTrue(inView(end).contains("1,200\""), end);
  }

  @Test
  void wheelScrollWhileLastPageCountsCancelsTheJump() throws Exception {
    var app = filteredMongoGrid();
    c.run(app, "press #cell-0-0\nadvance 1\nframe\n");
    assertEquals(0, tab(app).selection.row());
    var countKey =
        "count " + DocumentConsoleTest.MONGO + "/hoori.orders FILTER { total: { $gt: 0 } }";
    var held = servers.hold(countKey);
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(countKey));
    c.run(app, "wheel #rows 0 72\nframe\nframe\n");
    int scrolledTo = tab(app).selection.firstRowInView();
    assertTrue(scrolledTo > 0, "wheel scrolled the grid");
    assertEquals(0, tab(app).selection.row(), "wheel leaves selection unchanged");
    assertEquals(0, tab(app).selection.column(), "wheel leaves selection unchanged");
    held.countDown();
    c.run(app, SETTLE + "frame\nframe\n" + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertTrue(status(stayed).contains("\"1,200 documents\""), stayed);
    assertFalse(inView(stayed).contains("1,200\""), stayed);
    assertEquals(scrolledTo, tab(app).selection.firstRowInView(), stayed);
  }

  @Test
  void scrollbarTrackPressWhileLastPageCountsCancelsTheJump() throws Exception {
    var app = filteredMongoGrid();
    c.run(app, "press #cell-0-0\nadvance 1\nframe\n");
    assertEquals(0, tab(app).selection.row());
    var countKey =
        "count " + DocumentConsoleTest.MONGO + "/hoori.orders FILTER { total: { $gt: 0 } }";
    var held = servers.hold(countKey);
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(countKey));
    c.run(app, "press #rows at right-4,bottom-24\nframe\nframe\n");
    int scrolledTo = tab(app).selection.firstRowInView();
    assertTrue(scrolledTo > 0, "scrollbar track press scrolled the grid");
    assertEquals(0, tab(app).selection.row(), "scrollbar press leaves selection unchanged");
    assertEquals(0, tab(app).selection.column(), "scrollbar press leaves selection unchanged");
    held.countDown();
    c.run(app, SETTLE + "frame\nframe\n" + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertTrue(status(stayed).contains("\"1,200 documents\""), stayed);
    assertFalse(inView(stayed).contains("1,200\""), stayed);
    assertEquals(scrolledTo, tab(app).selection.firstRowInView(), stayed);
  }

  @Test
  void controlHomeScrollingSidewaysWhileLastPageCountsCancelsTheJump() throws Exception {
    var app = filteredMongoGrid();
    c.run(app, "size 640 760\nframe\npress #cell-0-0\nadvance 1\nframe\n");
    assertEquals(0, tab(app).selection.row());
    assertEquals(0, tab(app).selection.column());
    c.run(app, "wheel #rows 200 0\nframe\nframe\n");
    assertTrue(sideways(app) > 0, "grid scrolled sideways before Last page");
    var countKey =
        "count " + DocumentConsoleTest.MONGO + "/hoori.orders FILTER { total: { $gt: 0 } }";
    var held = servers.hold(countKey);
    c.run(app, page("Last page"));
    eventually(() -> servers.calls.contains(countKey));
    c.run(app, "focus #grid\nframe\n");
    assertTrue(sideways(app) > 0, "focusing the grid kept the sideways scroll");
    c.run(app, "key Home control\nframe\nframe\n");
    assertEquals(0.0, sideways(app), "Control+Home scrolled back to the left");
    assertEquals(0, tab(app).selection.row(), "selection stayed on row 0");
    assertEquals(0, tab(app).selection.column(), "selection stayed on column 0");
    held.countDown();
    c.run(app, SETTLE + "frame\nframe\n" + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertTrue(status(stayed).contains("\"1,200 documents\""), stayed);
    assertFalse(inView(stayed).contains("1,200\""), stayed);
    assertTrue(inView(stayed).contains("\"Documents 1–"), stayed);
  }

  /** How far the grid is scrolled sideways. */
  private static double sideways(App<State> app) {
    return ConsoleEditorTest.find(app.tree(), "grid", new float[2]).scrollX();
  }

  @Test
  void documentCollectionFilterAndSortApplyAcrossViewsAndClearWithEmptyDocument() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var all = IntStream.rangeClosed(1, 210)
        .mapToObj(i -> Document.of("_id", i, "customer", "c" + i, "total", (double) i))
        .toList();
    var matched = List.of(
        Document.of("_id", 210, "customer", "Ada", "total", 210.0),
        Document.of("_id", 205, "customer", "Ada", "total", 205.0));
    var many = IntStream.rangeClosed(1, 205)
        .mapToObj(i -> Document.of("_id", i, "customer", "many", "total", (double) i))
        .toList();
    servers.documents(path, all);
    servers.documents(path + " FILTER { customer: 'Ada' } SORT { total: -1 }", matched);
    servers.documents(path + " FILTER { total: { $gt: 0 } }", many);
    var app = mongoOrders();
    String initial = snapped(app, "mongo-initial");
    assertTrue(initial.contains("#filter-bar"), initial);
    assertTrue(initial.contains("label=\"Filter\""), initial);
    assertTrue(initial.contains("label=\"Sort\""), initial);
    assertFalse(initial.contains("label=\"WHERE\""), initial);
    assertFalse(initial.contains("label=\"ORDER BY\""), initial);

    // Filtered collection with > 200 matches has no estimate ("At least 200 documents").
    c.run(
        app,
        clause("Filter", "{ total: { $gt: 0 } }")
            + "key Enter\nframe\n"
            + SETTLE
            + "snap many-filtered\n");
    assertTrue(
        status(c.tree("many-filtered")).contains("\"At least 200 documents\""),
        c.tree("many-filtered"));

    // Turn to page 2 in JSON, then apply Filter + Sort: resets to page 1 across JSON and Tree.
    c.run(
        app,
        """
        press role=radio label="JSON"
        frame
        advance 1
        frame
        press role=button label="Next page"
        frame
        """
            + SETTLE
            + """
            advance 1
            frame
            """
            + clause("Filter", "{ customer: 'Ada' }")
            + clause("Sort", "{ total: -1 }")
            + "key Enter\nframe\n"
            + SETTLE
            + """
            advance 1
            frame
            snap filtered-json
            press role=radio label="Tree"
            frame
            advance 1
            frame
            snap filtered-tree
            """);
    String json = c.tree("filtered-json");
    assertTrue(line(json, "#rows-in-view").contains("\"Documents 1–2\""), json);
    assertTrue(line(json, "#json").contains("\\\"_id\\\": 210"), json);
    assertTrue(status(json).contains("\"2 documents\""), json);
    String tree = c.tree("filtered-tree");
    assertEquals(List.of("210", "205"), DocumentsServerTest.documents(tree));

    // Clearing with {} returns to the unfiltered collection.
    c.run(
        app,
        clause("Filter", "{}")
            + clause("Sort", "{}")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap cleared\n");
    assertEquals(Rows.Filter.NONE, tab(app).filter);
    assertTrue(status(c.tree("cleared")).contains("\"210 documents\""), c.tree("cleared"));
  }

  @Test
  void documentTreeNumbersDocumentsWithoutIdAcrossPages() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var all = IntStream.rangeClosed(1, TableTab.PAGE + 50)
        .mapToObj(i -> Document.of("customer", "c" + i, "total", (double) i))
        .toList();
    servers.documents(path, all);
    var app = mongoOrders();
    c.run(app, """
        press role=radio label="Tree"
        frame
        advance 1
        frame
        snap tree-page-1
        press role=button label="Next page"
        frame
        """ + SETTLE + """
            advance 1
            frame
            snap tree-page-2
            """);
    String page1 = c.tree("tree-page-1");
    assertTrue(line(page1, "#rows-in-view").contains("\"Documents 1–200\""), page1);
    assertEquals("Document 1", DocumentsServerTest.documents(page1).getFirst(), page1);

    String page2 = c.tree("tree-page-2");
    assertTrue(line(page2, "#rows-in-view").contains("\"Documents 201–250\""), page2);
    var page2Docs = DocumentsServerTest.documents(page2);
    assertEquals("Document 201", page2Docs.getFirst(), page2);
    assertEquals("Document 202", page2Docs.get(1), page2);
  }

  @Test
  void invalidDocumentFilterOrSortIsRefusedWithoutSendingAnything() throws Exception {
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    servers.documents(path, List.of(Document.of("_id", 1, "customer", "Ada", "total", 12.5)));
    var app = mongoOrders();
    int before = servers.calls.size();
    c.run(
        app,
        clause("Filter", "{ customer: }")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap bad-filter\n");
    String badFilter = c.tree("bad-filter");
    assertTrue(line(badFilter, "role=note").contains("label=\"Filter refused\""), badFilter);
    assertTrue(badFilter.contains("Filter:"), badFilter);
    assertTrue(cell(badFilter, 0, 1).contains("\"Ada\""), badFilter);
    assertNull(tab(app).failure);
    assertEquals(before, servers.calls.size(), servers.calls.toString());

    // Non-document in Filter and syntax error in Sort are also refused before anything is sent.
    c.run(
        app,
        clause("Filter", "[1]")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap not-doc\n");
    assertTrue(c.tree("not-doc").contains("A document in braces is needed"), c.tree("not-doc"));
    c.run(
        app,
        clause("Filter", "")
            + clause("Sort", "{ total: -")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap bad-sort\n");
    String badSort = c.tree("bad-sort");
    assertTrue(line(badSort, "role=note").contains("label=\"Filter refused\""), badSort);
    assertTrue(badSort.contains("Sort:"), badSort);
    assertEquals(before, servers.calls.size(), servers.calls.toString());
  }

  @Test
  void completionInDocumentFilterAndSortOffersFieldsOperatorsAndConstructors() throws Exception {
    var app = mongoOrders();
    c.run(app, """
        press role=textbox label="Filter"
        frame
        type "{ cu"
        frame
        advance 1
        frame
        snap field-complete
        """);
    var fieldOptions = ConsoleEditorTest.options(c.tree("field-complete"));
    assertEquals("customer", fieldOptions.getFirst(), fieldOptions.toString());
    int before = servers.calls.size();
    c.run(app, "key Enter\nframe\nsnap accepted-field\n");
    assertTrue(
        line(c.tree("accepted-field"), "#where").contains("\"{ customer\""),
        c.tree("accepted-field"));
    assertEquals(before, servers.calls.size(), "Enter took completion without querying");

    // Dollar opens query operators immediately; value constructors are also offered.
    c.run(
        app,
        clause("Filter", "{ total: { ")
            + "type \"$\"\nframe\nadvance 1\nframe\nsnap op-complete\n"
            + "type \"g\"\nframe\nsnap gt-complete\n");
    var opOptions = ConsoleEditorTest.options(c.tree("op-complete"));
    assertTrue(
        opOptions.containsAll(List.of("$addFields", "$eq", "$exists")), opOptions.toString());
    var gtOptions = ConsoleEditorTest.options(c.tree("gt-complete"));
    assertTrue(gtOptions.containsAll(List.of("$gt", "$gte")), gtOptions.toString());
    c.run(
        app,
        "key Escape\nframe\nadvance 1\nframe\n"
            + clause("Filter", "{ _id: ")
            + "type \"Obj\"\nframe\nadvance 1\nframe\nsnap ctor-complete\n");
    var ctorOptions = ConsoleEditorTest.options(c.tree("ctor-complete"));
    assertEquals("ObjectId", ctorOptions.getFirst(), ctorOptions.toString());

    // Sort field completes the collection's fields too.
    c.run(app, """
        key Escape
        frame
        advance 1
        frame
        press role=textbox label="Sort"
        frame
        type "{ to"
        frame
        advance 1
        frame
        snap sort-complete
        """);
    var sortOptions = ConsoleEditorTest.options(c.tree("sort-complete"));
    assertEquals("total", sortOptions.getFirst(), sortOptions.toString());
  }

  @Test
  void documentGridHeaderSortWritesSortDocumentAndSkipsDottedOrDollarFields() throws Exception {
    var app = mongoOrders();
    c.run(app, "size 1600 760\nframe\n");

    // 1. First press sorts ascending and writes { total: 1 } into Sort.
    c.run(app, "press role=columnheader label=\"total\"\nframe\n" + SETTLE + "snap asc\n");
    String asc = c.tree("asc");
    assertTrue(line(asc, "#column-2").contains("sort=ascending"), asc);
    assertTrue(line(asc, "#order").contains("\"{ total: 1 }\""), asc);
    assertEquals("{ total: 1 }", tab(app).filter.order());

    // 2. Second press flips to descending; unapplied Filter draft stays unapplied.
    c.run(
        app,
        clause("Filter", "{ customer: 'Ada' }")
            + "press role=columnheader label=\"total\"\nframe\n"
            + SETTLE
            + "snap desc\n");
    String desc = c.tree("desc");
    assertTrue(line(desc, "#column-2").contains("sort=descending"), desc);
    assertTrue(line(desc, "#order").contains("\"{ total: -1 }\""), desc);
    assertEquals("", tab(app).filter.where());

    // 3. Shift+press adds a secondary field, quoted by Shell.name where needed.
    c.run(
        app, "press role=columnheader label=\"order name\" shift\nframe\n" + SETTLE + "snap two\n");
    String two = c.tree("two");
    assertTrue(line(two, "#column-2").contains("sort=descending"), two);
    assertTrue(line(two, "#column-3").contains("sort=ascending"), two);
    assertEquals("{ total: -1, 'order name': 1 }", tab(app).filter.order());

    // 4. Hand-typed Sort clears arrows.
    c.run(app, clause("Sort", "{ _id: -1 }") + "key Enter\nframe\n" + SETTLE + "snap typed\n");
    assertFalse(c.tree("typed").contains("sort="), c.tree("typed"));
    assertEquals("{ _id: -1 }", tab(app).filter.order());

    // 5. Fields holding '.' or starting with '$' cannot be sorted from the header.
    int before = servers.calls.size();
    c.run(app, """
        press role=columnheader label="meta.tag"
        frame
        press role=columnheader label="$score"
        frame
        """ + SETTLE + "snap unsortable\n");
    assertEquals(before, servers.calls.size(), servers.calls.toString());
    assertFalse(c.tree("unsortable").contains("sort="), c.tree("unsortable"));
  }

  @Test
  void openInConsoleForDocumentCollectionOpensAndReusesConsoleAsConfigured() throws Exception {
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
            total: double
          customers
            _id: objectId
            name: string
        """);
    var app = c.app(DocumentConsoleTest.DOCS);
    c.ready(app, DocumentConsoleTest.DOCS);
    c.run(
        app,
        START
            + open("docs")
            + SETTLE
            + open("hoori")
            + SETTLE
            + table("orders")
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n"
            + "snap mongo-new-console\n");
    var main = app.state().tabs.main().active();
    var console = assertInstanceOf(ConsoleTab.class, main.activeTab());
    var expectedOrders = "db.getCollection('orders').find()";
    assertEquals(DocumentConsoleTest.DOCS.id(), console.id);
    assertEquals("hoori", console.database);
    assertEquals(expectedOrders, console.text);
    assertEquals(expectedOrders.length(), console.editor.caret());
    assertEquals(expectedOrders.length(), console.editor.anchor());
    assertTrue(line(c.tree("mongo-new-console"), "#sql").contains("focused"));
    assertEquals(Document.of("find", "orders"), Script.parse(console.text).command());

    // A second collection with Sort only reuses the open console on the same database.
    c.run(
        app,
        table("customers")
            + SETTLE
            + clause("Sort", "{ name: 1 }")
            + "key Enter\nframe\n"
            + SETTLE
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    assertEquals(1, app.state().consoles(DocumentConsoleTest.DOCS.id()).size());
    assertEquals(console, main.activeTab());
    var expectedReused =
        expectedOrders + ";\n\ndb.getCollection('customers').find().sort({ name: 1 });";
    assertEquals(expectedReused, console.text);
    assertEquals(expectedReused.length(), console.editor.caret());
    var pieces = Script.split(console.text);
    assertEquals(2, pieces.size(), pieces.toString());
    assertEquals(
        Document.of("find", "customers", "sort", Document.of("name", 1)),
        Script.parse(pieces.get(1).text()).command());

    // Always a new console opens a second console tab instead of reusing.
    app.post(() ->
        app.state().choose(app.state().settings.withOpenInConsole(Settings.OpenInConsole.NEW)));
    c.run(app, """
        await
        frame
        press role=tab label="customers"
        frame
        advance 1
        frame
        press role=button label="Open in console"
        frame
        advance 1
        frame
        """);
    assertEquals(2, app.state().consoles(DocumentConsoleTest.DOCS.id()).size());
    var second = assertInstanceOf(ConsoleTab.class, main.activeTab());
    assertEquals("db.getCollection('customers').find().sort({ name: 1 })", second.text);
  }

  @Test
  void openInConsoleForDocumentCollectionQuotesNameAndFormatsFilterAndSort() throws Exception {
    var weirdName = "weird \"quote\" 'orders'";
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          weird "quote" 'orders'
            _id: objectId
            createdAt: date
            total: double
        """);
    var app = c.app(DocumentConsoleTest.DOCS);
    c.ready(app, DocumentConsoleTest.DOCS);
    c.run(
        app,
        START
            + open("docs")
            + SETTLE
            + open("hoori")
            + SETTLE
            + table(weirdName.replace("\"", "\\\""))
            + SETTLE
            + clause(
                "Filter",
                "{ _id: ObjectId(\"507f1f77bcf86cd799439011\"),"
                    + " createdAt: new Date(\"2026-01-15T10:30:00Z\") }")
            + clause("Sort", "{ total: -1 }")
            + "key Enter\nframe\n"
            + SETTLE
            // Unapplied draft after applying is ignored by Open in console.
            + clause("Filter", "{ total: 999 }")
            + "press role=button label=\"Open in console\"\nframe\nadvance 1\nframe\n");
    var console =
        assertInstanceOf(ConsoleTab.class, app.state().tabs.main().active().activeTab());
    var expected = """
        db.getCollection('weird "quote" \\'orders\\'').find({
          _id: ObjectId('507f1f77bcf86cd799439011'),
          createdAt: ISODate('2026-01-15T10:30:00.000Z')
        }).sort({ total: -1 })""";
    assertEquals(expected, console.text);
    assertEquals(expected.length(), console.editor.caret());
    assertEquals(expected.length(), console.editor.anchor());

    // The shell splits and reads the generated statement back with exact values.
    var pieces = Script.split(console.text);
    assertEquals(1, pieces.size(), pieces.toString());
    var request = Script.parse(pieces.getFirst().text());
    assertEquals(
        Document.of(
            "find",
            weirdName,
            "filter",
            Document.of(
                "_id",
                Bson.ObjectId.of("507f1f77bcf86cd799439011"),
                "createdAt",
                Instant.parse("2026-01-15T10:30:00Z")),
            "sort",
            Document.of("total", -1)),
        request.command());
  }

  @Test
  void pressingSortableHeadersCyclesAndCombinesOrderByWithoutApplyingWhereDraft() throws Exception {
    var r1 = List.<Object>of(
        1L, 20, OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC), new BigDecimal("50.00"));
    var r2 = List.<Object>of(
        2L, 10, OffsetDateTime.of(2026, 1, 1, 9, 1, 0, 0, ZoneOffset.UTC), new BigDecimal("50.00"));
    var r3 = List.<Object>of(
        3L, 30, OffsetDateTime.of(2026, 1, 1, 9, 2, 0, 0, ZoneOffset.UTC), new BigDecimal("10.00"));
    servers.values(ORDERS, List.of(r1, r2, r3));
    servers.values(ORDERS + " ORDER BY \"total\" ASC", List.of(r3, r1, r2));
    servers.values(ORDERS + " ORDER BY \"total\" DESC", List.of(r1, r2, r3));
    servers.values(ORDERS + " ORDER BY \"total\" DESC, \"customer_id\" ASC", List.of(r2, r1, r3));
    servers.values(
        ORDERS + " WHERE total > 20 ORDER BY \"total\" DESC, \"customer_id\" ASC", List.of(r2, r1));
    var app = shop();
    c.run(app, table("orders") + SETTLE);

    // 1. First press sorts ascending and writes the quoted column into ORDER BY.
    c.run(app, "press role=columnheader label=\"total\"\nframe\n" + SETTLE + "snap asc\n");
    String asc = c.tree("asc");
    assertTrue(line(asc, "#column-3").contains("sort=ascending"), asc);
    assertTrue(line(asc, "#order").contains("\\\"total\\\" ASC"), asc);
    assertTrue(cell(asc, 0, 0).contains("\"3\""), asc);

    // 2. Second press flips to descending; an unapplied WHERE draft is kept in the field, not
    // applied.
    c.run(
        app,
        clause("WHERE", "total > 999")
            + "press role=columnheader label=\"total\"\nframe\n"
            + SETTLE
            + "snap desc\n");
    String desc = c.tree("desc");
    assertTrue(line(desc, "#column-3").contains("sort=descending"), desc);
    assertTrue(line(desc, "#order").contains("\\\"total\\\" DESC"), desc);
    assertTrue(line(desc, "#where").contains("\"total > 999\""), desc);
    assertEquals("", tab(app).filter.where(), "unapplied WHERE draft must not be applied");

    // 3. Shift+press adds a secondary sort column.
    c.run(
        app,
        "press role=columnheader label=\"customer_id\" shift\nframe\n" + SETTLE + "snap two\n");
    String two = c.tree("two");
    assertTrue(line(two, "#column-3").contains("sort=descending"), two);
    assertTrue(line(two, "#column-1").contains("sort=ascending"), two);
    assertEquals("\"total\" DESC, \"customer_id\" ASC", tab(app).filter.order());
    assertTrue(cell(two, 0, 0).contains("\"2\""), two);

    // 4. Applying a WHERE keeps the header sort arrows when ORDER BY still matches.
    c.run(app, clause("WHERE", "total > 20") + "key Enter\nframe\n" + SETTLE + "snap filtered\n");
    String filtered = c.tree("filtered");
    assertTrue(line(filtered, "#column-1").contains("sort=ascending"), filtered);
    assertEquals("total > 20", tab(app).filter.where());

    // 5. A hand-typed ORDER BY shows no arrows, and a header press replaces it while keeping the
    // applied WHERE.
    c.run(app, clause("ORDER BY", "id DESC") + "key Enter\nframe\n" + SETTLE + "snap typed\n");
    String typed = c.tree("typed");
    assertFalse(typed.contains("sort="), typed);
    assertEquals("id DESC", tab(app).filter.order());
    c.run(app, "press role=columnheader label=\"total\"\nframe\n" + SETTLE + "snap replaced\n");
    String replaced = c.tree("replaced");
    assertTrue(line(replaced, "#column-3").contains("sort=ascending"), replaced);
    assertEquals("total > 20", tab(app).filter.where());
    assertEquals("\"total\" ASC", tab(app).filter.order());

    // 6. Cycling a single column past descending clears ORDER BY and the arrow.
    c.run(
        app,
        "press role=columnheader label=\"total\"\nframe\n"
            + SETTLE
            + "press role=columnheader label=\"total\"\nframe\n"
            + SETTLE
            + "snap unsorted\n");
    String unsorted = c.tree("unsorted");
    assertFalse(unsorted.contains("sort="), unsorted);
    assertEquals("", tab(app).filter.order());

    // 7. While disconnected, headers keep no interactive sort handler and do nothing.
    c.run(
        app,
        "press role=columnheader label=\"total\"\nframe\n"
            + SETTLE
            + menu("shop-prod", "Disconnect")
            + "press role=columnheader label=\"id\"\nframe\n"
            + SETTLE
            + "snap disconnected\n");
    assertEquals("\"total\" ASC", tab(app).filter.order());
    assertTrue(line(c.tree("disconnected"), "#column-3").contains("sort=ascending"));
  }

  @Test
  void aSecondHeaderPressWhileASortReadsAddsToIt() throws Exception {
    servers.rows(ORDERS, 3, OptionalLong.of(3));
    var app = shop();
    c.run(app, table("orders") + SETTLE);
    var held = servers.hold("read " + ORDERS + " 0-200 ORDER BY \"total\" ASC");
    c.run(app, """
        press role=columnheader label="total"
        frame
        press role=columnheader label="customer_id" shift
        frame
        """);
    held.countDown();
    c.run(app, SETTLE + "snap both\n");
    assertEquals("\"total\" ASC, \"customer_id\" ASC", tab(app).filter.order());
    assertTrue(line(c.tree("both"), "#column-1").contains("sort=ascending"), c.tree("both"));
  }

  @Test
  void headerSortUsesBackticksOnMariaDb() throws Exception {
    var app = c.app(INVENTORY);
    c.ready(app, INVENTORY);
    c.run(
        app,
        START
            + open("inventory")
            + SETTLE
            + "press role=treeitem label=\"inventory\" level=2\nkey ArrowRight\nframe\n"
            + SETTLE
            + table("items")
            + SETTLE
            + "press role=columnheader label=\"name\"\nframe\n"
            + SETTLE
            + "snap maria-sort\n");
    assertEquals("`name` ASC", tab(app).filter.order());
    assertTrue(
        line(c.tree("maria-sort"), "#column-2").contains("sort=ascending"), c.tree("maria-sort"));
  }

  @Test
  void jsonOtherAndConsoleColumnsCannotBeSorted() throws Exception {
    servers.serve(
        PG,
        Provider.POSTGRESQL,
        "16.4",
        OUTLINE.replace("      doc: jsonb\n", "      doc: jsonb\n      shape: box\n"));
    var app = shop();
    c.run(app, "size 2600 760\nframe\n" + table("kinds") + SETTLE);
    int before = servers.calls.size();
    c.run(
        app,
        "press role=columnheader label=\"doc\"\nframe\n"
            + "press role=columnheader label=\"shape\"\nframe\n"
            + SETTLE
            + "snap unsortable\n");
    String unsortable = c.tree("unsortable");
    assertEquals(before, servers.calls.size(), servers.calls.toString());
    assertEquals("", tab(app).filter.order());
    assertFalse(unsortable.contains("sort="), unsortable);

    // A console result grid also stays unsortable.
    servers.statement(
        "SELECT id, total FROM orders",
        List.of(FakeServers.made(2, "id: bigint", "total: numeric(12,2)")));
    c.run(
        app,
        menu("shop-prod", "New console")
            + "frame\n"
            + "press #sql\n"
            + "clipboard \"SELECT id, total FROM orders\"\nkey V control\nframe\n"
            + "key Enter control\nframe\n"
            + SETTLE
            + "press role=columnheader label=\"id\"\nframe\n"
            + "snap console-grid\n");
    assertFalse(c.tree("console-grid").contains("sort="), c.tree("console-grid"));
    assertFalse(c.tree("console-grid").contains("#filter-0"), c.tree("console-grid"));
  }

  /** Opens the header filter popover for the given column index via Alt+ArrowDown on row 0. */
  private static String openFilter(int column) {
    return "press #cell-0-" + column + "\nframe\nkey ArrowDown alt\nframe\nadvance 1\nframe\n";
  }

  @Test
  void sqlHeaderFiltersWriteWhereApplyWithChipsAndPreserveHandWrittenClause() throws Exception {
    servers.serve(
        PG,
        Provider.POSTGRESQL,
        "16.4",
        OUTLINE.replace("      doc: jsonb\n", "      doc: jsonb\n      shape: box\n"));
    var ada = Arrays.<Object>asList(
        1L,
        new BigDecimal("10.00"),
        1.5,
        "Ada",
        "vip",
        true,
        LocalDate.of(2026, 5, 14),
        LocalTime.of(13, 45),
        LocalDateTime.of(2026, 5, 14, 14, 30),
        OffsetDateTime.of(2026, 5, 14, 12, 30, 0, 0, ZoneOffset.UTC),
        new Binary(new byte[] {1, 2}, 2),
        "{}",
        "(1,2)");
    var grace = Arrays.<Object>asList(
        2L,
        new BigDecimal("50.00"),
        2.5,
        "Grace",
        "std",
        false,
        LocalDate.of(2026, 6, 1),
        LocalTime.of(9, 0),
        LocalDateTime.of(2026, 6, 1, 9, 0),
        OffsetDateTime.of(2026, 6, 1, 7, 0, 0, 0, ZoneOffset.UTC),
        new Binary(new byte[] {3, 4}, 2),
        "{}",
        "(3,4)");
    servers.values(KINDS, List.of(ada, grace));
    var nameWhere = "\"name\" ILIKE '%ada%' ESCAPE '!'";
    var bothWhere = nameWhere + " AND \"id\" >= 1 AND \"id\" <= 10";
    var idWhere = "\"id\" >= 1 AND \"id\" <= 10";
    var handWhere = "id > 0 OR paid = TRUE";
    var joinedWhere = "(" + handWhere + ") AND \"name\" ILIKE 'a%' ESCAPE '!'";
    servers.values(KINDS + " WHERE " + nameWhere, List.of(ada));
    servers.values(KINDS + " WHERE " + bothWhere, List.of(ada));
    servers.values(KINDS + " WHERE " + idWhere, List.of(ada, grace));
    servers.values(KINDS + " WHERE " + handWhere, List.of(ada, grace));
    servers.values(KINDS + " WHERE " + joinedWhere, List.of(ada));

    var app = shop();
    c.run(app, "size 2600 760\nframe\n" + table("kinds") + SETTLE);

    // 1. BINARY (col 10), JSON (col 11) and OTHER (col 12) columns offer no header filter.
    String opened = snapped(app, "kinds-opened");
    assertTrue(opened.contains("#filter-3 "), opened);
    assertFalse(opened.contains("#filter-10 "), opened);
    assertFalse(opened.contains("#filter-11 "), opened);
    assertFalse(opened.contains("#filter-12 "), opened);

    // 2. Filter `name` (col 3) with Contains "ada" -> writes WHERE, applies, shows chip.
    c.run(
        app,
        openFilter(3)
            + "type \"ada\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap one-filter\n");
    String one = c.tree("one-filter");
    assertEquals(nameWhere, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + KINDS + " 0-200 WHERE " + nameWhere),
        servers.calls.toString());
    assertTrue(one.contains("#filters role=list label=\"Filters\""), one);
    assertTrue(one.contains("name contains ada"), one);
    assertTrue(cell(one, 0, 3).contains("\"Ada\""), one);

    // 3. Open filter popover on `id` (col 0, Between) while chip bar is visible and snap it,
    // then apply range 1 to 10.
    c.run(
        app,
        openFilter(0)
            + "type \"1\"\nframe\npress #to\nframe\ntype \"10\"\nframe\n"
            + "snap sql-header-filter-popover\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap two-filters\n");
    String popover = c.tree("sql-header-filter-popover");
    assertTrue(popover.contains("#filter role=dialog label=\"Filter id\""), popover);
    assertTrue(popover.contains("#filters role=list label=\"Filters\""), popover);
    String two = c.tree("two-filters");
    assertEquals(bothWhere, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + KINDS + " 0-200 WHERE " + bothWhere),
        servers.calls.toString());
    assertTrue(two.contains("#clear-filters "), two);

    // 4. Removing the `name` chip leaves only the `id` range filter.
    c.run(
        app,
        "press role=button label=\"Remove name contains ada\"\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap removed-one\n");
    String removedOne = c.tree("removed-one");
    assertEquals(idWhere, tab(app).filter.where());
    assertFalse(removedOne.contains("name contains ada"), removedOne);
    assertTrue(removedOne.contains("id 1 – 10"), removedOne);

    // 5. Removing the last chip clears WHERE and hides the chip bar.
    c.run(
        app,
        "press role=button label=\"Remove id 1 – 10\"\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap cleared\n");
    assertEquals("", tab(app).filter.where());
    assertFalse(c.tree("cleared").contains("#filters "), c.tree("cleared"));

    // 6. Hand-written WHERE survives setting and clearing header filters.
    c.run(app, clause("WHERE", handWhere) + "key Enter\nframe\n" + SETTLE);
    assertEquals(handWhere, tab(app).filter.where());
    c.run(
        app,
        openFilter(3)
            + "press #kind\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Starts with\"\nframe\nadvance 1\nframe\n"
            + "press #value\nframe\ntype \"a\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap joined\n");
    assertEquals(joinedWhere, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + KINDS + " 0-200 WHERE " + joinedWhere),
        servers.calls.toString());
    c.run(
        app,
        "press role=button label=\"Remove name starts with a\"\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap hand-restored\n");
    assertEquals(handWhere, tab(app).filter.where());
    assertFalse(c.tree("hand-restored").contains("#filters "), c.tree("hand-restored"));

    // 7. Hand-editing WHERE and pressing Enter drops header filter marks and keeps the text.
    c.run(
        app,
        openFilter(3)
            + "type \"ada\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + clause("WHERE", "id = 2")
            + "frame\nadvance 1\nframe\nsnap hand-draft\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap hand-edited\n");
    // The draft takes the marks off at once, so no chip can remove what the text still holds.
    assertFalse(c.tree("hand-draft").contains("#filters "), c.tree("hand-draft"));
    assertEquals("id = 2", tab(app).filter.where());
    assertFalse(c.tree("hand-edited").contains("#filters "), c.tree("hand-edited"));

    // 8. MariaDB uses backticks and compares UTF-8 text by LIKE with a binary collation.
    var maria = c.app(INVENTORY);
    c.ready(maria, INVENTORY);
    c.run(
        maria,
        START
            + open("inventory")
            + SETTLE
            + "press role=treeitem label=\"inventory\" level=2\nkey ArrowRight\nframe\n"
            + SETTLE
            + table("items")
            + SETTLE
            + openFilter(2)
            + "type \"50%\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE);
    assertEquals(
        "LOWER(CONVERT(`name` USING utf8mb4)) LIKE LOWER(CONVERT('%50!%%' USING utf8mb4)) COLLATE utf8mb4_bin ESCAPE '!'",
        tab(maria).filter.where());
  }

  @Test
  void documentGridHeaderFiltersWriteTypedFilterDocumentAndPreserveHandWrittenFilter()
      throws Exception {
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
            customer: string
            total: double
            count: int
            paid: bool
            created: date
            meta.tag: string
            $score: double
        """);
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var oid1 = new dev.hoori.db.core.document.Bson.ObjectId("507f1f77bcf86cd799439011");
    var oid2 = new dev.hoori.db.core.document.Bson.ObjectId("507f1f77bcf86cd799439012");
    var d1 = Document.of(
        "_id",
        oid1,
        "customer",
        "Ada",
        "total",
        25.5,
        "count",
        3,
        "paid",
        true,
        "created",
        java.time.Instant.parse("2026-05-14T12:30:00Z"));
    var d2 = Document.of(
        "_id",
        oid2,
        "customer",
        "Grace",
        "total",
        80.0,
        "count",
        1,
        "paid",
        false,
        "created",
        java.time.Instant.parse("2026-06-01T07:00:00Z"));
    var customerFilter = "{ customer: { $regex: 'Ada', $options: 'i' } }";
    var rangeAndCustomer =
        "{ customer: { $regex: 'Ada', $options: 'i' }, total: { $gte: 10.0, $lte: 50.0 } }";
    var oidFilter = "{ _id: ObjectId('507f1f77bcf86cd799439011') }";
    var dateFilter = "{ created: ISODate('2026-05-14T12:30:00.000Z') }";
    var boolFilter = "{ paid: { $in: [ true ] } }";
    var handFilter = "{ count: { $gt: 0 } }";
    var joinedFilter =
        "{ $and: [ { count: { $gt: 0 } }, { customer: { $regex: 'Ada', $options: 'i' } } ] }";
    servers.documents(path, List.of(d1, d2));
    servers.documents(path + " FILTER " + customerFilter, List.of(d1));
    servers.documents(path + " FILTER " + rangeAndCustomer, List.of(d1));
    servers.documents(path + " FILTER " + oidFilter, List.of(d1));
    servers.documents(path + " FILTER " + dateFilter, List.of(d1));
    servers.documents(path + " FILTER " + boolFilter, List.of(d1));
    servers.documents(path + " FILTER " + handFilter, List.of(d1, d2));
    servers.documents(path + " FILTER " + joinedFilter, List.of(d1));

    var app = c.app(DocumentConsoleTest.DOCS);
    c.ready(app, DocumentConsoleTest.DOCS);
    c.run(
        app,
        START
            + "size 2200 760\nframe\n"
            + open("docs")
            + SETTLE
            + open("hoori")
            + SETTLE
            + table("orders")
            + SETTLE);

    // 1. Dotted (`meta.tag`, col 6) and `$`-prefixed (`$score`, col 7) fields offer no filter.
    String opened = snapped(app, "mongo-opened");
    assertTrue(opened.contains("#filter-1 "), opened);
    assertFalse(opened.contains("#filter-6 "), opened);
    assertFalse(opened.contains("#filter-7 "), opened);

    // 2. String Contains on `customer` (col 1) writes $regex with $options: 'i'.
    c.run(
        app,
        openFilter(1)
            + "type \"Ada\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap mongo-one\n");
    assertEquals(customerFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + customerFilter),
        servers.calls.toString());
    assertTrue(c.tree("mongo-one").contains("customer contains Ada"), c.tree("mongo-one"));

    // 3. Open range popover on `total` (col 2, double) with chip bar visible, snap, then apply.
    c.run(
        app,
        openFilter(2)
            + "type \"10\"\nframe\npress #to\nframe\ntype \"50\"\nframe\n"
            + "snap mongo-header-filter-popover\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE);
    String mongoPopover = c.tree("mongo-header-filter-popover");
    assertTrue(mongoPopover.contains("#filter role=dialog label=\"Filter total\""), mongoPopover);
    assertTrue(mongoPopover.contains("#filters role=list label=\"Filters\""), mongoPopover);
    assertEquals(rangeAndCustomer, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + rangeAndCustomer),
        servers.calls.toString());

    // Clear all chips.
    c.run(app, "press #clear-filters\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals("", tab(app).filter.where());

    // 4. Boolean OneOf on `paid` (col 4): uncheck false -> { paid: { $in: [ true ] } }.
    c.run(
        app,
        openFilter(4)
            + "press #value-false\nframe\npress role=button label=\"Apply\"\nframe\nadvance 1\nframe\n"
            + SETTLE);
    assertEquals(boolFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + boolFilter),
        servers.calls.toString());
    c.run(
        app, "press role=button label=\"Remove paid = true\"\nframe\nadvance 1\nframe\n" + SETTLE);

    // 5. Date Equals on `created` (col 5): local Europe/Berlin time -> ISODate UTC.
    c.run(
        app,
        openFilter(5)
            + "press #kind\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Equals\"\nframe\nadvance 1\nframe\n"
            + "press #value\nframe\ntype \"2026-05-14 14:30:00\"\nframe\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE);
    assertEquals(dateFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + dateFilter),
        servers.calls.toString());
    c.run(
        app,
        "press role=button label=\"Remove created = 2026-05-14 14:30:00\"\nframe\nadvance 1\nframe\n"
            + SETTLE);

    // 6. ObjectId Equals on `_id` (col 0): valid ObjectId applies; invalid is refused unsent.
    int beforeInvalid = servers.calls.size();
    c.run(
        app,
        openFilter(0)
            + "press #kind\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Equals\"\nframe\nadvance 1\nframe\n"
            + "press #value\nframe\ntype \"not-an-oid\"\nframe\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap bad-oid\n");
    assertTrue(line(c.tree("bad-oid"), "role=note").contains("label=\"Filter refused\""));
    assertEquals(beforeInvalid, servers.calls.size(), servers.calls.toString());
    assertEquals("", tab(app).filter.where());

    c.run(
        app,
        openFilter(0)
            + "press #kind\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Equals\"\nframe\nadvance 1\nframe\n"
            + "press #value\nframe\ntype \"507f1f77bcf86cd799439011\"\nframe\n"
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE);
    assertEquals(oidFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + oidFilter),
        servers.calls.toString());
    c.run(
        app,
        "press role=button label=\"Remove _id = 507f1f77bcf86cd799439011\"\nframe\nadvance 1\nframe\n"
            + SETTLE);

    // 7. Hand-written Filter survives setting and clearing header filters, and hand-editing drops
    // marks.
    c.run(app, clause("Filter", handFilter) + "key Enter\nframe\n" + SETTLE);
    c.run(
        app, openFilter(1) + "type \"Ada\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n" + SETTLE);
    assertEquals(joinedFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + joinedFilter),
        servers.calls.toString());
    c.run(
        app,
        "press role=button label=\"Remove customer contains Ada\"\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap hand-back\n");
    assertEquals(handFilter, tab(app).filter.where());
    assertFalse(c.tree("hand-back").contains("#filters "), c.tree("hand-back"));

    c.run(
        app,
        openFilter(1)
            + "type \"Ada\"\nframe\nkey Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + clause("Filter", "{ count: 1 }")
            + "key Enter\nframe\nadvance 1\nframe\n"
            + SETTLE
            + "snap mongo-edited\n");
    assertEquals("{ count: 1 }", tab(app).filter.where());
    assertFalse(c.tree("mongo-edited").contains("#filters "), c.tree("mongo-edited"));
  }

  @Test
  void sqlTableBrowsingUsesReadOnlySessionsWhileConsoleAndWritesStayWritable() throws Exception {
    servers.writes("nextval(");
    servers.rows(BIG, 1000, OptionalLong.of(1_000_000));
    servers.statement(
        "SELECT nextval('orders_seq')", List.of(FakeServers.made(1, "nextval: bigint")));
    var app = shop();
    assertFalse(SHOP.readOnly(), "profile itself is writable");
    assertTrue(servers.readOnly(1), "tree lane is opened read-only");

    // 1. A WHERE calling nextval() fails at the server on the read-only browsing lane.
    c.run(
        app,
        table("orders")
            + SETTLE
            + clause("WHERE", "id = nextval('orders_seq')")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap write-in-where\n");
    String failed = c.tree("write-in-where");
    assertTrue(line(failed, "role=note").contains("label=\"Could not be read\""), failed);
    assertTrue(failed.contains("cannot execute in a read-only transaction"), failed);

    // 2. A clause attempting to turn read-only off is refused before anything is sent, with
    // wording that does not call a writable connection read-only.
    int beforeRefused = servers.calls.size();
    c.run(
        app,
        clause("WHERE", "set_config('default_transaction_read_only', 'off', false) IS NOT NULL")
            + "key Enter\nframe\n"
            + SETTLE
            + "advance 1\nframe\nsnap bypass-refused\n");
    String bypass = c.tree("bypass-refused");
    assertTrue(line(bypass, "role=note").contains("label=\"Clause refused\""), bypass);
    assertTrue(bypass.contains("The session is read-only"), bypass);
    assertFalse(bypass.contains("The connection is read-only"), bypass);
    assertEquals(beforeRefused, servers.calls.size(), servers.calls.toString());

    // 3. Further database tree lane and count lane are also opened read-only.
    c.run(app, open("analytics") + SETTLE + open("tracking") + SETTLE + table("events") + SETTLE);
    int analyticsLink = linkOf("columns " + PG + "/analytics.tracking.events");
    assertTrue(servers.readOnly(analyticsLink), "further database tree lane is read-only");
    c.run(app, table("big") + SETTLE + scroll(500_000) + SETTLE + SETTLE);
    int countLink = linkOf("count " + BIG);
    assertTrue(servers.readOnly(countLink), "count lane is read-only");

    // 4. Console session on the same writable profile is writable and runs nextval().
    c.run(
        app,
        menu("shop-prod", "New console")
            + "frame\n"
            + "press #sql\n"
            + "clipboard \"SELECT nextval('orders_seq')\"\nkey V control\nframe\n"
            + "key Enter control\nframe\n"
            + SETTLE
            + "snap console-write\n");
    int consoleLink = linkOf("run " + PG + " SELECT nextval('orders_seq')");
    assertFalse(servers.readOnly(consoleLink), "console session keeps writable profile");
    assertTrue(cell(c.tree("console-write"), 0, 0).contains("\"1\""), c.tree("console-write"));

    // 5. Planner session keeps the profile's setting (writable), and table edits write on the
    // writable writer lane.
    var planner = app.state().catalog.planner(app.state(), SHOP.id(), "shop");
    planner.submit(l -> l.estimate("shop", "SELECT 1")).get();
    int plannerLink = linkOf("explain " + PG + " SELECT 1");
    assertFalse(servers.readOnly(plannerLink), "planner session keeps writable profile");

    c.run(
        app,
        table("customers")
            + SETTLE
            + "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n"
            + "press #commit-changes\nframe\nadvance 1\nframe\n"
            + "press #preview-commit\nframe\nadvance 1\nframe\n"
            + SETTLE);
    int writerLink = linkOf("write " + PG + "/shop.public.customers");
    assertFalse(servers.readOnly(writerLink), "writer lane keeps writable profile");
    assertEquals("Alicia", servers.rowsOf(PG + "/shop.public.customers").get(0).get(1));

    // 6. MongoDB tree lane keeps the profile's setting (not forced read-only).
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        shop
          orders
            _id: ObjectId
        """);
    var mongoApp = c.app(DocumentConsoleTest.DOCS);
    c.ready(mongoApp, DocumentConsoleTest.DOCS);
    c.run(mongoApp, START + open("docs") + SETTLE);
    int mongoLink = linkOf("databases " + DocumentConsoleTest.MONGO);
    assertFalse(servers.readOnly(mongoLink), "MongoDB lane stays as profile says");
  }

  @Test
  void sqlConditionBuilderBuildsOrNestedGroupSyncsHeaderFiltersAndGuardsPendingEdits()
      throws Exception {
    servers.serve(
        PG,
        Provider.POSTGRESQL,
        "16.4",
        OUTLINE.replace("      doc: jsonb\n", "      doc: jsonb\n      shape: box\n"));
    var ada = Arrays.<Object>asList(
        1L,
        new BigDecimal("10.00"),
        1.5,
        "Ada",
        "vip",
        true,
        LocalDate.of(2026, 5, 14),
        LocalTime.of(13, 45),
        LocalDateTime.of(2026, 5, 14, 14, 30),
        OffsetDateTime.of(2026, 5, 14, 12, 30, 0, 0, ZoneOffset.UTC),
        new Binary(new byte[] {1, 2}, 2),
        "{}",
        "(1,2)");
    var grace = Arrays.<Object>asList(
        2L,
        new BigDecimal("50.00"),
        2.5,
        "Grace",
        "std",
        false,
        LocalDate.of(2026, 6, 1),
        LocalTime.of(9, 0),
        LocalDateTime.of(2026, 6, 1, 9, 0),
        OffsetDateTime.of(2026, 6, 1, 7, 0, 0, 0, ZoneOffset.UTC),
        new Binary(new byte[] {3, 4}, 2),
        "{}",
        "(3,4)");
    var orWhere = "NOT (LOWER(\"name\") = LOWER('Grace')) OR (\"id\" >= 1 AND \"paid\" = TRUE)";
    var flatWhere = "\"name\" ILIKE '%Ada%' ESCAPE '!' AND \"id\" >= 1 AND \"id\" <= 10";
    var idWhere = "\"id\" >= 1 AND \"id\" <= 10";
    var handWhere = "id > 0";
    var joinedWhere = "(id > 0) AND LOWER(\"name\") = LOWER('Ada')";
    servers.values(KINDS, List.of(ada, grace));
    servers.values(KINDS + " WHERE " + orWhere, List.of(ada));
    servers.values(KINDS + " WHERE " + flatWhere, List.of(ada));
    servers.values(KINDS + " WHERE " + idWhere, List.of(ada, grace));
    servers.values(KINDS + " WHERE " + handWhere, List.of(ada, grace));
    servers.values(KINDS + " WHERE " + joinedWhere, List.of(ada));

    var app = shop();
    c.run(app, "size 1400 760\nframe\n" + table("kinds") + SETTLE);

    // 1. Build OR with nested AND group and Not equal via the Conditions dialog.
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            press #add-condition
            frame
            press role=radio label="OR"
            frame
            press #field-0
            frame
            advance 1
            frame
            press role=menuitem label="name"
            frame
            advance 1
            frame
            press #operator-0
            frame
            advance 1
            frame
            press role=menuitem label="Not equal"
            frame
            advance 1
            frame
            press #value-0
            frame
            type "Grace"
            frame
            press #add-group
            frame
            press #operator-1-0
            frame
            advance 1
            frame
            press role=menuitem label="At least"
            frame
            advance 1
            frame
            press #value-1-0
            frame
            type "1"
            frame
            press #add-condition-1
            frame
            press #field-1-1
            frame
            advance 1
            frame
            press role=menuitem label="paid"
            frame
            advance 1
            frame
            press #value-1-1
            frame
            advance 1
            frame
            press role=menuitem label="true"
            frame
            advance 1
            frame
            snap sql-conditions-dialog
            press #conditions-apply
            frame
            advance 1
            frame
            """ + SETTLE + "snap sql-conditions-applied\n");
    assertTrue(
        c.tree("sql-conditions-dialog").contains("#conditions-dialog role=dialog"),
        c.tree("sql-conditions-dialog"));
    assertEquals(orWhere, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + KINDS + " 0-200 WHERE " + orWhere),
        servers.calls.toString());
    assertFalse(
        c.tree("sql-conditions-applied").contains("#filters "), c.tree("sql-conditions-applied"));

    // 2. Reopening shows the saved nested tree; replacing with a flat AND syncs header chips.
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            snap sql-reopened
            press #remove-1
            frame
            press role=radio label="AND"
            frame
            press #operator-0
            frame
            advance 1
            frame
            press role=menuitem label="Contains"
            frame
            advance 1
            frame
            press #value-0
            frame
            key A control
            type "Ada"
            frame
            press #add-condition
            frame
            press #operator-1
            frame
            advance 1
            frame
            press role=menuitem label="Between"
            frame
            advance 1
            frame
            press #from-1
            frame
            type "1"
            frame
            press #to-1
            frame
            type "10"
            frame
            press #conditions-apply
            frame
            advance 1
            frame
            """ + SETTLE + "snap sql-flat-and\n");
    assertTrue(c.tree("sql-reopened").contains("#clause-1 "), c.tree("sql-reopened"));
    assertEquals(flatWhere, tab(app).filter.where());
    String flatTree = c.tree("sql-flat-and");
    assertTrue(flatTree.contains("name contains Ada"), flatTree);
    assertTrue(flatTree.contains("id 1 – 10"), flatTree);

    // 3. Removing a header chip syncs back to the builder, and removing the last clears WHERE.
    c.run(app, """
            press role=button label="Remove name contains Ada"
            frame
            advance 1
            frame
            """ + SETTLE + """
            press #conditions
            frame
            advance 1
            frame
            snap sql-synced-back
            press #remove-0
            frame
            press #conditions-apply
            frame
            advance 1
            frame
            """ + SETTLE);
    String syncedBack = c.tree("sql-synced-back");
    assertTrue(syncedBack.contains("#from-0 "), syncedBack);
    assertFalse(syncedBack.contains("#field-1 "), syncedBack);
    assertEquals("", tab(app).filter.where());

    // 4. Hand-written WHERE survives applying and clearing via the builder.
    c.run(app, clause("WHERE", handWhere) + "key Enter\nframe\n" + SETTLE + """
                press #conditions
                frame
                advance 1
                frame
                press #add-condition
                frame
                press #field-0
                frame
                advance 1
                frame
                press role=menuitem label="name"
                frame
                advance 1
                frame
                press #value-0
                frame
                type "Ada"
                frame
                press #conditions-apply
                frame
                advance 1
                frame
                """ + SETTLE);
    assertEquals(joinedWhere, tab(app).filter.where());
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            press #remove-0
            frame
            press #conditions-apply
            frame
            advance 1
            frame
            """ + SETTLE);
    assertEquals(handWhere, tab(app).filter.where());

    // 5. Pending edits ask before applying conditions from the builder.
    var custWhere = "LOWER(\"name\") = LOWER('Ada')";
    servers.values(PG + "/shop.public.customers WHERE " + custWhere, List.of(List.of(1L, "Ada")));
    c.run(
        app,
        table("customers")
            + SETTLE
            + "press #cell-0-1\ntype \"Alicia\"\nkey Enter\nframe\n"
            + """
                press #conditions
                frame
                advance 1
                frame
                press #add-condition
                frame
                press #field-0
                frame
                advance 1
                frame
                press role=menuitem label="name"
                frame
                advance 1
                frame
                press #value-0
                frame
                type "Ada"
                frame
                press #conditions-apply
                frame
                advance 1
                frame
                snap sql-pending-guard
                press role=button label="Discard"
                frame
                advance 1
                frame
                """
            + SETTLE);
    assertTrue(
        c.tree("sql-pending-guard").contains("the filter is applied"), c.tree("sql-pending-guard"));
    assertEquals(
        custWhere, ((TableTab) app.state().tabs.main().active().activeTab()).filter.where());

    // 6. Disconnecting disables the Conditions… button.
    c.run(app, menu("shop-prod", "Disconnect") + "frame\n" + SETTLE + "snap sql-disconnected\n");
    assertTrue(
        line(c.tree("sql-disconnected"), "#conditions").contains("disabled"),
        c.tree("sql-disconnected"));
  }

  @Test
  void mongoConditionBuilderSamplesNestedPathsBuildsOrGroupWithExistsAndCancelsOnClose()
      throws Exception {
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", """
        hoori
          orders
            _id: objectId
            customer: string
            total: double
            address: object
            meta.tag: string
            $score: double
        """);
    var path = DocumentConsoleTest.MONGO + "/hoori.orders";
    var oid1 = new dev.hoori.db.core.document.Bson.ObjectId("507f1f77bcf86cd799439011");
    var oid2 = new dev.hoori.db.core.document.Bson.ObjectId("507f1f77bcf86cd799439012");
    var d1 = Document.of(
        "_id",
        oid1,
        "customer",
        "Ada",
        "total",
        25.5,
        "address",
        Document.of("city", "Berlin", "zip", 10115),
        "meta.tag",
        "skip",
        "$score",
        1.0);
    var d2 = Document.of(
        "_id", oid2, "customer", "Grace", "total", 80.0, "address", Document.of("city", "Vienna"));
    var expectedFilter = "{ $or: [ { 'address.city': { $regex: '^Berlin\\\\z', $options: 'i' } },"
        + " { customer: { $not: /^Grace\\z/i }, address: { $exists: true } } ] }";
    servers.documents(path, List.of(d1, d2));
    servers.documents(path + " FILTER " + expectedFilter, List.of(d1));

    var app = c.app(DocumentConsoleTest.DOCS);
    c.ready(app, DocumentConsoleTest.DOCS);
    c.run(
        app,
        START
            + "size 1400 760\nframe\n"
            + open("docs")
            + SETTLE
            + open("hoori")
            + SETTLE
            + table("orders")
            + SETTLE);

    // 1. Hold paths sampling: builder opens with top-level columns (no ./$) and waiting status.
    var pathsGate = servers.hold("paths " + path);
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            press #add-condition
            frame
            press #field-0
            frame
            advance 1
            frame
            snap mongo-paths-loading
            """);
    String loading = c.tree("mongo-paths-loading");
    assertTrue(loading.contains("Reading nested fields"), loading);
    assertTrue(loading.contains("role=menuitem label=\"customer\""), loading);
    assertFalse(loading.contains("role=menuitem label=\"meta.tag\""), loading);
    assertFalse(loading.contains("role=menuitem label=\"$score\""), loading);

    // Release gate: nested path `address.city` arrives and can be selected.
    pathsGate.countDown();
    c.run(app, SETTLE + """
                press role=menuitem label="address.city"
                frame
                advance 1
                frame
                press #value-0
                frame
                type "Berlin"
                frame
                press role=radio label="OR"
                frame
                press #add-group
                frame
                press #field-1-0
                frame
                advance 1
                frame
                press role=menuitem label="customer"
                frame
                advance 1
                frame
                press #operator-1-0
                frame
                advance 1
                frame
                press role=menuitem label="Not equal"
                frame
                advance 1
                frame
                press #value-1-0
                frame
                type "Grace"
                frame
                press #add-condition-1
                frame
                press #field-1-1
                frame
                advance 1
                frame
                press role=menuitem label="address"
                frame
                advance 1
                frame
                press #operator-1-1
                frame
                advance 1
                frame
                press role=menuitem label="Exists"
                frame
                advance 1
                frame
                snap mongo-conditions-dialog
                press #conditions-apply
                frame
                advance 1
                frame
                """ + SETTLE + "snap mongo-conditions-applied\n");
    assertTrue(servers.calls.contains("paths " + path), servers.calls.toString());
    assertEquals(expectedFilter, tab(app).filter.where());
    assertTrue(
        servers.calls.contains("read " + path + " 0-200 FILTER " + expectedFilter),
        servers.calls.toString());
    assertFalse(
        c.tree("mongo-conditions-applied").contains("#filters "),
        c.tree("mongo-conditions-applied"));

    // 2. Invalid ObjectId value in builder shows the same Filter refused callout unsent.
    int beforeInvalid = servers.calls.size();
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            press #remove-1
            frame
            press #field-0
            frame
            advance 1
            frame
            press role=menuitem label="_id"
            frame
            advance 1
            frame
            press #value-0
            frame
            key A control
            type "not-an-oid"
            frame
            press #conditions-apply
            frame
            advance 1
            frame
            """ + SETTLE + "snap mongo-conditions-refused\n");
    assertTrue(
        line(c.tree("mongo-conditions-refused"), "role=note").contains("label=\"Filter refused\""),
        c.tree("mongo-conditions-refused"));
    assertEquals(beforeInvalid, servers.calls.size(), servers.calls.toString());
    assertEquals(expectedFilter, tab(app).filter.where());

    // 3. Refresh clears cached paths; closing the tab while paths is held cancels the read.
    c.run(app, "press #refresh\nframe\n" + SETTLE);
    var cancelGate = servers.hold("paths " + path);
    c.run(app, """
            press #conditions
            frame
            advance 1
            frame
            press #conditions-cancel
            frame
            advance 1
            frame
            key W control
            frame
            """);
    cancelGate.countDown();
    c.run(app, SETTLE);
    assertTrue(servers.interrupted.contains("paths " + path), servers.interrupted.toString());
  }

  private int linkOf(String call) {
    return servers.linked.stream()
        .filter(entry -> entry.endsWith(" " + call))
        .mapToInt(entry -> Integer.parseInt(entry.substring(0, entry.indexOf(' '))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No link for " + call + " in " + servers.linked));
  }

  String snapped(App<State> app, String name) throws Exception {
    c.run(app, "snap " + name + "\n");
    return c.tree(name);
  }
}

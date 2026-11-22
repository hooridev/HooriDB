package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.MISSING;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.FakeServers.session;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.StatementLogStore;
import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.ServerVariable;
import dev.hoori.db.core.sql.Secrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The statement log: what the tree, the grid and the consoles sent, in a panel below them. */
class StatementLogTest {

  static final String TOGGLE =
      "press role=button label=\"Statement log\"\nframe\nadvance 1\nframe\n";

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    servers = k.servers;
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  /** The app with shop's public schema open in the tree. */
  App<State> shop() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    return app;
  }

  @Test
  void thePanelIsCollapsedUntilTheStatusLineOpensIt() throws Exception {
    var app = shop();
    c.run(app, "snap collapsed\n");
    var collapsed = c.tree("collapsed");
    assertFalse(collapsed.contains("#statement-log "), collapsed);
    assertTrue(line(collapsed, "#statement-log-toggle").contains("expanded=false"), collapsed);
    // What the tree read is in the log already.
    assertTrue(
        app.state().statements.entries().stream()
            .anyMatch(e -> e.sent().purpose() == Purpose.CATALOG),
        app.state().statements.entries().toString());

    c.run(app, TOGGLE + "snap open\n");
    var open = c.tree("open");
    assertTrue(line(open, "#statement-log-toggle").contains("expanded"), open);
    assertTrue(line(open, "#statement-log ").contains("label=\"Statement log\""), open);
    assertTrue(open.contains("\"Tree\""), open);
    assertTrue(open.contains("\"tables " + CatalogTreeTest.PG + "/shop.public\""), open);
    assertTrue(open.contains("\"shop-prod\""), open);

    c.run(app, "press #statement-log-close\nframe\nadvance 1\nframe\nsnap closed\n");
    assertFalse(c.tree("closed").contains("#statement-log "), c.tree("closed"));
  }

  @Test
  void theViewMenuAndThePaletteOpenItToo() throws Exception {
    var app = shop();
    c.run(
        app,
        "press role=menuitem label=\"View\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Statement log\"\nframe\nadvance 1\nframe\nsnap menu\n");
    assertTrue(c.tree("menu").contains("#statement-log "), c.tree("menu"));
    c.run(
        app,
        "key P control shift\nframe\nadvance 1\nframe\ntype \"Statement log\"\nframe\n"
            + "key Enter\nframe\nadvance 1\nframe\nsnap palette\n");
    assertFalse(c.tree("palette").contains("#statement-log "), c.tree("palette"));
  }

  @Test
  void theGridsAndTheConsolesStatementsShowWithTheirPartAndOutcome() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT) + RUN + "press #sql\nkey A control\nkey Delete\n" + type(MISSING) + RUN);
    c.run(app, open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, table("orders") + SETTLE + TOGGLE + "snap log\n");
    var log = c.tree("log");
    assertTrue(log.contains("\"" + COUNT + "\""), log);
    assertTrue(log.contains("\"1 row\""), log);
    assertTrue(log.contains("\"Console\""), log);
    assertTrue(log.contains("relation \\\"missing\\\" does not exist"), log);
    assertTrue(log.contains("\"Grid\""), log);
    assertTrue(log.contains("\"read " + CatalogTreeTest.PG + "/shop.public.orders 0-200\""), log);
    // The newest is chosen, its statement whole beside the rows.
    var last = app.state().statements.entries().getLast();
    assertTrue(line(log, "#statement-log-statement").contains(last.sent().text()), log);
    assertEquals("shop", last.sent().database());
  }

  @Test
  void aPasswordNeverShowsInThePanelNorInTheFile() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type("ALTER ROLE app PASSWORD 'role-s3cret'") + RUN);
    c.run(app, "press #sql\nkey A control\nkey Delete\n" + type("SELECT '" + PASSWORD + "'") + RUN);
    c.run(app, TOGGLE + "snap log\n" + SETTLE);
    var log = panel(c.tree("log"));
    assertFalse(log.contains("role-s3cret"), log);
    assertTrue(log.contains("ALTER ROLE app PASSWORD " + Secrets.MASK), log);
    assertFalse(log.contains(PASSWORD), log);
    assertTrue(log.contains(Recorded.WITHHELD), log);
    var file = dir.resolve("config").resolve(StatementLogStore.FILE);
    var kept = Files.readString(file);
    assertTrue(kept.contains("ALTER ROLE app PASSWORD"), kept);
    assertFalse(kept.contains("role-s3cret"), kept);
    assertFalse(kept.contains(PASSWORD), kept);
  }

  /** The panel's part of the element tree, apart from the console's text above it. */
  static String panel(String tree) {
    var lines = tree.lines().toList();
    int from = -1;
    int to = lines.size();
    for (int i = 0; i < lines.size(); i++) {
      if (from < 0 && lines.get(i).contains("#statement-log ")) {
        from = i;
      } else if (from >= 0 && lines.get(i).contains("#statement-log-toggle")) {
        to = i;
        break;
      }
    }
    if (from < 0) {
      throw new AssertionError("no statement log in\n" + tree);
    }
    return String.join("\n", lines.subList(from, to));
  }

  /** Chooses the option of the panel's filter. */
  static String filter(String label, String option) {
    return "press role=combobox label=\"" + label + "\"\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"" + option + "\"\nframe\nadvance 1\nframe\n";
  }

  /** A console that ran a count and a statement that failed, with the tree open to a table. */
  App<State> ran() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT) + RUN + "press #sql\nkey A control\nkey Delete\n" + type(MISSING) + RUN);
    c.run(app, open("shop-prod") + SETTLE + open("shop") + SETTLE + TOGGLE);
    return app;
  }

  @Test
  void theFiltersAndTheSearchNarrowTheEntries() throws Exception {
    var app = ran();
    c.run(app, filter("Part", "Console") + "snap console\n");
    var console = panel(c.tree("console"));
    assertTrue(console.contains("\"" + COUNT + "\""), console);
    assertTrue(console.contains("\"" + MISSING + "\""), console);
    assertFalse(console.contains("\"Tree\""), console);

    c.run(app, filter("Outcome", "Failed") + "snap failed\n");
    var failed = panel(c.tree("failed"));
    assertTrue(failed.contains("\"" + MISSING + "\""), failed);
    assertFalse(failed.contains("\"" + COUNT + "\""), failed);

    c.run(
        app,
        filter("Outcome", "Any outcome") + "press #statement-log-search\ntype \"COUNT(\"\nframe\n"
            + "snap search\n");
    var search = panel(c.tree("search"));
    assertTrue(search.contains("\"" + COUNT + "\""), search);
    assertFalse(search.contains("\"" + MISSING + "\""), search);

    c.run(
        app,
        "press #statement-log-search\nkey A control\ntype \"nothing like it\"\nframe\n"
            + "advance 1\nframe\nsnap none\n");
    assertTrue(c.tree("none").contains("\"No entry matches\""), c.tree("none"));
    c.run(app, "press #statement-log-unfilter\nframe\nadvance 1\nframe\nsnap all\n");
    assertTrue(panel(c.tree("all")).contains("\"Tree\""), c.tree("all"));
  }

  @Test
  void theSearchComparesCharactersInAnyCase() {
    assertTrue(LogFilter.holds("SELECT * FROM t", "from"));
    // The final sigma's upper case is the other sigma's.
    assertTrue(LogFilter.holds("SELECT '\u039f\u03a3\u0391'", "\u03bf\u03c2"));
    assertFalse(LogFilter.holds("SELECT 1", "SELECT 12"));
  }

  @Test
  void copyPutsTheChosenStatementWholeOnTheClipboard() throws Exception {
    var app = ran();
    c.run(
        app,
        "press #statement-log-search\ntype \"count(\"\nframe\npress #statement-log-copy\nframe\n");
    assertEquals(COUNT, app.clipboard().read());
  }

  @Test
  void clearAsksFirstAndThenEmptiesTheLogAndItsFile() throws Exception {
    var app = ran();
    var file = dir.resolve("config").resolve(StatementLogStore.FILE);
    assertTrue(Files.exists(file));
    c.run(app, "press #statement-log-clear\nframe\nadvance 1\nframe\nsnap asked\n");
    assertTrue(c.tree("asked").contains("\"Clear the statement log?\""), c.tree("asked"));
    assertFalse(app.state().statements.entries().isEmpty(), "nothing goes before the answer");
    c.run(app, "press #statement-log-confirm\nframe\n" + SETTLE + "snap cleared\n");
    assertTrue(app.state().statements.entries().isEmpty());
    assertTrue(c.tree("cleared").contains("\"Nothing sent yet\""), c.tree("cleared"));
    assertFalse(Files.exists(file));
  }

  @Test
  void turnedOffNothingIsLoggedAndTheChoiceIsKept() throws Exception {
    var app = ran();
    c.run(app, "press #statement-log-on\nframe\n" + SETTLE + "snap off\n");
    assertTrue(c.tree("off").contains("\"Logging is off\""), c.tree("off"));
    int before = app.state().statements.entries().size();
    c.run(app, open("public") + SETTLE);
    assertEquals(before, app.state().statements.entries().size());
    assertFalse(new StatementLogStore(dir.resolve("config")).enabled());
    c.run(app, "press #statement-log-on\nframe\n" + SETTLE + open("sales") + SETTLE);
    assertTrue(app.state().statements.entries().size() > before);
  }

  private static Logged entry(String sql) {
    return new Logged(
        SHOP.id(),
        new Sent(
            Instant.now(), Purpose.STATEMENT, "shop", sql, Duration.ofMillis(1), Outcome.DONE));
  }

  @Test
  void exitWaitsForAHeldWriteAndClosesOnceItEnds() throws Exception {
    var app = shop();
    var started = new CountDownLatch(1);
    var gate = new CountDownLatch(1);
    app.state().statements.beforeCall = () -> {
      started.countDown();
      try {
        gate.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    };
    try {
      app.state().statements.add(entry("SELECT 41"));
      app.state().requestClose();
      assertTrue(started.await(5, TimeUnit.SECONDS), "the first write started");
      c.run(app, "frame\n");
      assertFalse(app.windows().all().isEmpty(), "Exit waits for the held write");
      // Another entry arrives while the first write runs; both are written before closing.
      app.state().statements.add(entry("SELECT 42"));
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE);
    assertTrue(app.windows().all().isEmpty(), "Exit closes once the writes have ended");
    var kept = Files.readString(dir.resolve("config").resolve(StatementLogStore.FILE));
    assertTrue(kept.contains("SELECT 41"), kept);
    assertTrue(kept.contains("SELECT 42"), kept);
  }

  @Test
  void aFailedWriteDoesNotBlockExit() throws Exception {
    var app = shop();
    var started = new CountDownLatch(1);
    var gate = new CountDownLatch(1);
    var failed = new AtomicBoolean();
    app.state().statements.beforeCall = () -> {
      if (failed.getAndSet(true)) {
        return; // the first write alone fails
      }
      started.countDown();
      try {
        gate.await();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      throw new IOException("disk full");
    };
    try {
      app.state().statements.add(entry("SELECT 41"));
      app.state().requestClose();
      assertTrue(started.await(5, TimeUnit.SECONDS), "the write started");
      // Another entry comes while the write that fails runs: it is written, with what failed.
      app.state().statements.add(entry("SELECT 42"));
      c.run(app, "frame\n");
      assertFalse(app.windows().all().isEmpty(), "Exit waits while the write runs");
    } finally {
      gate.countDown();
    }
    c.run(app, SETTLE);
    assertTrue(app.windows().all().isEmpty(), "a failed write does not keep HooriDB open");
    assertEquals("disk full", app.state().statements.problem());
    var kept = Files.readString(dir.resolve("config").resolve(StatementLogStore.FILE));
    assertTrue(kept.contains("SELECT 41"), kept);
    assertTrue(kept.contains("SELECT 42"), kept);

    // Entries left unsaved from an earlier failed write do not hold a later Exit either.
    var second = shop();
    second.state().statements.beforeCall = () -> {
      throw new IOException("disk full");
    };
    second.state().statements.add(entry("SELECT 42"));
    c.run(second, SETTLE);
    assertEquals("disk full", second.state().statements.problem());
    second.state().requestClose();
    c.run(second, "frame\n");
    assertTrue(second.windows().all().isEmpty(), "earlier unsaved entries do not hold Exit");
  }

  @Test
  void theServerMonitorsEndsAreLoggedAsMonitorWhileItsReadsAreNot() throws Exception {
    servers.sessions(PG, session("101", "ada", "SELECT pg_sleep(60)"), session("102", "bob", ""));
    servers.variables(PG, new ServerVariable("work_mem", "4MB", "default"));
    servers.counters(PG, new Counter("commits", "42"));
    servers.locks(PG, new Block("101", "102", "public.orders", "ShareLock"));
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        START + open("shop-prod") + SETTLE + menu("shop-prod", "Server monitor") + SETTLE
            + "press role=radio label=\"Variables\"\n" + MonitorTabTest.SHOWN + SETTLE
            + "press role=radio label=\"Status\"\n" + MonitorTabTest.SHOWN + SETTLE
            + "press role=radio label=\"Locks\"\n" + MonitorTabTest.SHOWN + SETTLE
            + "press role=radio label=\"Sessions\"\n" + MonitorTabTest.SHOWN + SETTLE
            + MonitorTabTest.pick(2) + "press #end-session\n" + MonitorTabTest.SHOWN
            + "press #question-go\n" + MonitorTabTest.SHOWN + SETTLE + TOGGLE
            + filter("Part", "Monitor") + "snap monitor\n");
    var entries = app.state().statements.entries();
    assertTrue(
        entries.stream()
            .anyMatch(e -> e.sent().purpose() == Purpose.MONITOR
                && e.sent().text().equals("end " + PG + " 102")),
        entries.toString());
    assertFalse(
        entries.stream()
            .anyMatch(e -> e.sent().text().startsWith("sessions ")
                || e.sent().text().startsWith("variables ")
                || e.sent().text().startsWith("status ")
                || e.sent().text().startsWith("locks ")),
        entries.toString());
    var log = panel(c.tree("monitor"));
    assertTrue(log.contains("\"Monitor\""), log);
    assertTrue(log.contains("\"end " + PG + " 102\""), log);
    assertFalse(log.contains("\"Tree\""), log);
  }

  /** Changing the time zone setting updates the times shown in the statement log. */
  @Test
  void timeZoneSettingUpdatesStatementLogTimes() throws Exception {
    var app = c.app(SHOP);
    c.run(app, START);
    app.state()
        .statements
        .add(new Logged(
            SHOP.id(),
            new Sent(
                Instant.parse("2026-01-15T10:30:00Z"),
                Purpose.STATEMENT,
                "shop",
                COUNT,
                Duration.ofMillis(4),
                Outcome.rows(1))));
    c.run(app, SETTLE + TOGGLE + "snap berlin\n");
    assertTrue(panel(c.tree("berlin")).contains("\"11:30:00.000\""), panel(c.tree("berlin")));

    app.post(() -> app.state().choose(Settings.DEFAULT.withTimeZone(ZoneId.of("UTC"))));
    c.run(app, "await\nframe\nsnap utc\n");
    assertTrue(panel(c.tree("utc")).contains("\"10:30:00.000\""), panel(c.tree("utc")));
  }
}

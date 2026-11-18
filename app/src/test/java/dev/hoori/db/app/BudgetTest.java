package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.El;
import com.rethinkj.ui.window.WindowLoop;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Measures the performance budget of AGENTS.md and prints it; it asserts no timing. Only with
 * {@code -Dbudget}: it opens real windows, and its numbers depend on the machine.
 */
@EnabledIfSystemProperty(named = "budget", matches = ".*")
class BudgetTest {

  @TempDir
  Path dir;

  /** HooriDB's real main in a process of its own, with no connections and no drivers. */
  @Test
  void startAndIdleMemory() throws Exception {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    assumeTrue(
        windows || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null,
        "no display");
    var logging = Files.writeString(dir.resolve("logging.properties"), """
        handlers = java.util.logging.ConsoleHandler
        java.util.logging.ConsoleHandler.level = FINE
        com.rethinkj.ui.window.level = FINE
        """);
    var classPath = System.getProperty("java.class.path"); // surefire's, in full
    var firstFrame = Pattern.compile("first frame (\\d+) ms after the process started");
    var starts = new ArrayList<Double>();
    var memory = new ArrayList<Double>();
    for (int run = 0; run <= 6; run++) { // the first warms up
      var home = Files.createDirectories(dir.resolve("run-" + run));
      var out = home.resolve("output.txt");
      var builder = new ProcessBuilder(
              ProcessHandle.current().info().command().orElseThrow(),
              "--enable-native-access=ALL-UNNAMED",
              "-Djava.util.logging.config.file=" + logging,
              "-Drethinkj.ui.natives.cache=" + System.getProperty("rethinkj.ui.natives.cache"),
              "-cp",
              classPath,
              HooriDb.class.getName())
          .redirectErrorStream(true)
          .redirectOutput(out.toFile());
      // Never the user's own connections, drivers or consoles. The keyring stays the user's, as
      // the start being measured looks it up; with no connections nothing is read from it.
      for (var name : List.of("XDG_CONFIG_HOME", "XDG_DATA_HOME", "APPDATA", "LOCALAPPDATA")) {
        builder.environment().put(name, home.toString());
      }
      var child = builder.start();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Matcher shown;
        while (!(shown = firstFrame.matcher(Files.readString(out))).find()) {
          if (!child.isAlive() || System.nanoTime() > deadline) {
            fail("No first frame within 20 s; the app wrote:\n" + Files.readString(out));
          }
          Thread.sleep(20);
        }
        Thread.sleep(10_000);
        double rss = resident(child.pid(), windows) / 1024.0 / 1024.0;
        System.out.printf(
            Locale.ROOT,
            "BUDGET start run %d%s: first frame %s ms, idle %.0f MB%n",
            run,
            run == 0 ? " (warm-up)" : "",
            shown.group(1),
            rss);
        if (run > 0) {
          starts.add(Double.parseDouble(shown.group(1)));
          memory.add(rss);
        }
      } finally {
        child.destroy();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
          child.destroyForcibly().waitFor();
        }
      }
    }
    print("Start to first frame (real window)", starts, 1000, "ms", "");
    print("Memory while idle, 10 s after it (resident)", memory, 150, "MB", "");
  }

  private static long resident(long pid, boolean windows) throws Exception {
    if (windows) {
      var shell = new ProcessBuilder(
              "powershell", "-NoProfile", "-Command", "(Get-Process -Id " + pid + ").WorkingSet64")
          .redirectErrorStream(true)
          .start();
      return Long.parseLong(new String(shell.getInputStream().readAllBytes()).strip());
    }
    return Files.readAllLines(Path.of("/proc/" + pid + "/status")).stream()
            .filter(l -> l.startsWith("VmRSS:"))
            .mapToLong(l -> Long.parseLong(l.replaceAll("\\D", "")))
            .findFirst()
            .orElseThrow()
        * 1024;
  }

  /**
   * From Enter on a table of a million rows in the tree to the frame that shows its first rows, on
   * a real server, without a window. Frames run as a window's would, without {@code await}, which
   * would also wait for the work queued behind the first page.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void firstRowsOfAMillion(Provider provider) throws Exception {
    var server = TestServer.of(provider);
    var c = new CatalogTreeTest();
    c.dir = dir;
    c.setUp();
    boolean postgres = provider == Provider.POSTGRESQL;
    // The table in a schema or database of its own; MariaDB and MySQL need root for that.
    try (var admin = Connector.open(
            postgres ? server.profile() : server.profile().withUser("root"),
            (postgres ? TestServer.PASSWORD : "root-" + TestServer.PASSWORD).toCharArray(),
            server.driver(),
            Duration.ofSeconds(10))
        .connection()) {
      try {
        createMillion(admin, provider);
        var profile = server.profile();
        // The app's tests load made-up drivers; its connections go through the real one.
        Servers real = (p, password, driver) -> Servers.SERVER.open(p, password, server.driver());
        var app = c.app(real, () -> new Keyring.Available(c.secrets), profile);
        c.ready(app, profile);
        app.state().rememberPassword(profile.id(), TestServer.password());
        c.run(
            app,
            START
                + open(profile.name())
                + SETTLE
                + (postgres ? open(TestServer.DATABASE) + SETTLE : "")
                + open("budget")
                + SETTLE);
        var times = new ArrayList<Double>();
        String status = null;
        for (int run = 0; run < 5; run++) {
          c.run(app, "press role=treeitem label=\"million\"\nframe\n");
          long start = System.nanoTime();
          c.run(app, "key Enter\n");
          long last = start;
          while (true) {
            long now = System.nanoTime();
            app.advance((now - last) / 1e9); // the app's clock runs as a window's
            last = now;
            boolean changed = app.frame();
            long shown = System.nanoTime();
            if (changed && firstRow(app.tree().dump())) {
              times.add((shown - start) / 1e6);
              break;
            }
            if (shown - start > TimeUnit.SECONDS.toNanos(60)) {
              fail("No first rows within 60 s:\n" + app.tree().dump());
            }
          }
          c.run(app, SETTLE);
          status = TableTabTest.status(app.tree().dump()).replaceAll(".*?\"(.*)\".*", "$1");
          System.out.printf(
              Locale.ROOT,
              "BUDGET %s first rows run %d: %.0f ms%n",
              provider,
              run,
              times.getLast());
          c.run(app, "key W control\nframe\n" + SETTLE);
          assertTrue(app.state().tables(null).isEmpty(), "the tab closed");
        }
        print("First rows of 1,000,000 on " + provider.displayName(), times, 300, "ms", status);
      } finally {
        c.tearDown();
        drop(admin, provider);
      }
    }
  }

  private static boolean firstRow(String tree) {
    return tree.contains("#cell-0-0 ") && TableTabTest.cell(tree, 0, 0).contains("\"1\"");
  }

  private static void createMillion(Connection admin, Provider provider) throws Exception {
    var statements = switch (provider) {
      case POSTGRESQL ->
        List.of(
            "CREATE SCHEMA budget",
            "CREATE TABLE budget.million AS SELECT i AS id, 'name ' || i AS name,"
                + " timestamp '2026-01-01' + i * interval '1 second' AS at, md5(i::text) AS note"
                + " FROM generate_series(1, 1000000) AS i",
            "ALTER TABLE budget.million ADD PRIMARY KEY (id)",
            "ANALYZE budget.million");
      case MARIADB, MYSQL ->
        List.of(
            "CREATE DATABASE budget",
            "GRANT ALL ON budget.* TO '" + TestServer.USER + "'@'%'",
            "CREATE TABLE budget.million"
                + " (id int PRIMARY KEY, name varchar(40), at datetime, note varchar(40))",
            provider == Provider.MYSQL
                ? "SET SESSION cte_max_recursion_depth = 1000000"
                : "SET SESSION max_recursive_iterations = 1000000",
            "INSERT INTO budget.million WITH RECURSIVE n(i) AS"
                + " (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 1000000)"
                + " SELECT i, CONCAT('name ', i), TIMESTAMP '2026-01-01 00:00:00' + INTERVAL i SECOND,"
                + " CONCAT('note ', i * 7919) FROM n",
            "ANALYZE TABLE budget.million");
    };
    try (var statement = admin.createStatement()) {
      for (var sql : statements) {
        statement.execute(sql);
      }
    }
  }

  private static void drop(Connection admin, Provider provider) throws Exception {
    try (var statement = admin.createStatement()) {
      statement.execute(
          provider == Provider.POSTGRESQL
              ? "DROP SCHEMA IF EXISTS budget CASCADE"
              : "DROP DATABASE IF EXISTS budget");
    }
  }

  /**
   * The app's share of each frame while the wheel scrolls a grid of a million made-up rows, one
   * step a frame at 60 frames a second: dispatching the input, building and laying out the view.
   * Drawing is not in it; the framework measures that.
   */
  @Test
  void scrollingTheGrid() throws Exception {
    var t = new TableTabTest();
    t.dir = dir;
    t.setUp();
    try {
      t.servers.rows(TableTabTest.BIG, 1_000_000, OptionalLong.of(1_000_000));
      var app = t.shop();
      t.c.run(app, TableTabTest.table("big") + SETTLE);
      var grid = center(app.tree(), 0, 0);
      assertNotNull(grid, "no #rows");
      // In a window the pointer rests over what the wheel scrolls, so rows pass under it.
      app.pointerMove(grid[0], grid[1]);
      app.frame();
      var times = new ArrayList<Double>();
      long frame = TimeUnit.SECONDS.toNanos(1) / 60;
      long next = System.nanoTime();
      for (int i = 0; i < 600; i++) {
        app.wheel(grid[0], grid[1], 0, WindowLoop.WHEEL_STEP);
        app.advance(1 / 60.0);
        long start = System.nanoTime();
        app.frame();
        times.add((System.nanoTime() - start) / 1e6);
        next += frame;
        TimeUnit.NANOSECONDS.sleep(next - System.nanoTime());
      }
      String tree = app.tree().dump();
      var view = Pattern.compile("#rows .* rows (\\d+)-(\\d+)/1000000").matcher(tree);
      assertTrue(view.find(), tree);
      long middle = (Long.parseLong(view.group(1)) + Long.parseLong(view.group(2))) / 2;
      assertTrue(middle > 1000, "the wheel scrolled the grid past a few pages: " + view.group());
      assertTrue(
          TableTabTest.cell(tree, middle, 0).contains("\"" + (middle + 1) + "\""),
          "the rows in view arrived while it scrolled");
      long reads = t.servers.calls.stream().filter(x -> x.startsWith("read ")).count();
      var sorted = times.stream().sorted().toList();
      System.out.printf(
          Locale.ROOT,
          "BUDGET Scrolling: %d frames to row %d, %d reads; the app's share of a frame (input,"
              + " build, layout), without drawing, which the framework measures%n",
          times.size(),
          middle,
          reads);
      System.out.printf(
          Locale.ROOT,
          "BUDGET Scrolling frame: median %.2f ms, p99 %.2f ms, max %.2f ms (the first frames"
              + " included); budget max < 16 ms: %s%n",
          sorted.get(sorted.size() / 2),
          sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1),
          sorted.getLast(),
          sorted.getLast() < 16 ? "within" : "OVER");
    } finally {
      t.tearDown();
    }
  }

  /** The middle of the grid's rows, in the window's coordinates. */
  private static float[] center(El el, float x, float y) {
    x += el.x();
    y += el.y();
    if ("rows".equals(el.key())) {
      return new float[] {x + el.width() / 2, y + el.height() / 2};
    }
    for (var child : el.children()) {
      var found = center(child, x, y);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static void print(
      String what, List<Double> values, double budget, String unit, String detail) {
    var sorted = values.stream().sorted().toList();
    double median = (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2;
    System.out.printf(
        Locale.ROOT,
        "BUDGET %s: median %.0f %s (%.0f-%.0f, %d runs); budget < %.0f %s: %s%s%n",
        what,
        median,
        unit,
        sorted.getFirst(),
        sorted.getLast(),
        sorted.size(),
        budget,
        unit,
        median < budget ? "within" : "OVER",
        detail.isEmpty() ? "" : "; " + detail);
  }
}

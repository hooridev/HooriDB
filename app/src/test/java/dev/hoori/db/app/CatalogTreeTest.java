package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.HistoryStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The catalog: connections that open onto what their servers hold. */
class CatalogTreeTest {

  private static final Pattern LEVEL = Pattern.compile(" level=(\\d+)");

  static final String PG = "pg.example.com";
  static final String MARIA = "maria.example.com";
  static final String MYSQL = "mysql.example.com";

  static final String PG_OUTLINE = """
      shop
        public
          customers
            *id: integer
            name: text
            email: character varying(255)
          orders
            *id: bigint
            customer_id: integer
            placed_at: timestamp with time zone
            total: numeric(12,2)
          @order_totals
            customer_id: integer
            total: numeric
        sales
          invoices
            *id: integer
            amount: numeric(12,2)
        ~information_schema
          @columns
            table_name: name
        ~pg_catalog
          pg_class
            oid: oid
        ~pg_toast
      analytics
        tracking
          events
            *id: bigint
            kind: text
      postgres
        reports
      ~template0
      ~template1
      """;

  static final String MARIA_OUTLINE = """
      inventory
        items
          *id: int(11)
          sku: varchar(32)
          name: varchar(200)
        stock
          *item_id: int(11)
          *warehouse: varchar(8)
          quantity: int(11)
        @low_stock
          item_id: int(11)
      ~information_schema
      ~mysql
      ~performance_schema
      """;

  static final String MYSQL_OUTLINE = """
      legacy
        accounts
          *id: int
          login: varchar(64)
      ~mysql
      ~sys
      """;

  @TempDir
  Path dir;

  final ConnectionsTest.Secrets secrets = new ConnectionsTest.Secrets();
  final FakeServers servers = new FakeServers()
      .serve(PG, Provider.POSTGRESQL, "16.4", PG_OUTLINE)
      .serve(MARIA, Provider.MARIADB, "11.4.2-MariaDB", MARIA_OUTLINE)
      .serve(MYSQL, Provider.MYSQL, "8.4.3", MYSQL_OUTLINE);
  FakeDrivers repository;
  final List<App<State>> apps = new ArrayList<>();
  Updater updater; // what the next app updates itself with, or null for none
  String version = HooriDb.version(); // the version the next app runs as

  static final ConnectionProfile SHOP = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("shop-prod")
      .withHost(PG)
      .withDatabase("shop")
      .withUser("shop_app");
  static final ConnectionProfile INVENTORY = ConnectionProfile.of(Provider.MARIADB)
      .withName("inventory")
      .withHost(MARIA)
      .withUser("inv");
  static final ConnectionProfile LEGACY =
      ConnectionProfile.of(Provider.MYSQL).withName("legacy").withHost(MYSQL).withUser("app");

  @BeforeEach
  void setUp() throws Exception {
    repository = new FakeDrivers(Files.createDirectories(dir.resolve("repository"))).publishAll();
  }

  @AfterEach
  void tearDown() throws Exception {
    servers.openAll();
    secrets.release();
    for (var app : apps) {
      app.await(Duration.ofSeconds(10));
      app.state().catalog.closeAll(app.state());
      app.state().drivers.close();
    }
  }

  /** The drivers in the folder, as an earlier session would have left them. */
  void downloaded() throws Exception {
    var store = new DriverStore(dir.resolve("drivers"), repository);
    for (var provider : Provider.values()) {
      store.download(Driver.of(provider), FakeDrivers.VERSION, DriverStore.Progress.NONE);
    }
  }

  /** The app with these connections, the made-up servers and the keyring in memory. */
  App<State> app(ConnectionProfile... profiles) throws IOException {
    return app(servers, () -> new Keyring.Available(secrets), profiles);
  }

  /** The app with these connections, servers and way of finding the keyring. */
  App<State> app(
      Servers servers,
      java.util.concurrent.Callable<Keyring> keyring,
      ConnectionProfile... profiles)
      throws IOException {
    fresh();
    return again(servers, keyring, profiles);
  }

  /** The app with this workspace, the made-up servers and the keyring in memory. */
  App<State> app(Workspace workspace) throws IOException {
    fresh();
    new WorkspaceStore(dir.resolve("config")).save(workspace);
    return start(servers, () -> new Keyring.Available(secrets));
  }

  /** The app started again with what the apps before it saved, once they are done. */
  App<State> restarted() throws IOException {
    settled();
    return start(servers, () -> new Keyring.Available(secrets));
  }

  // A start of its own: what an app before it kept of its consoles is not restored.
  private void fresh() throws IOException {
    settled();
    Files.deleteIfExists(dir.resolve("config").resolve(ConsoleStore.FILE));
    Files.deleteIfExists(dir.resolve("config").resolve(HistoryStore.FILE));
  }

  private void settled() throws IOException {
    try {
      for (var earlier : apps) {
        earlier.await(Duration.ofSeconds(10));
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException();
    }
  }

  /** The app started again with these connections, and the consoles and history kept before. */
  App<State> again(ConnectionProfile... profiles) throws IOException {
    return again(servers, () -> new Keyring.Available(secrets), profiles);
  }

  private App<State> again(
      Servers servers,
      java.util.concurrent.Callable<Keyring> keyring,
      ConnectionProfile... profiles)
      throws IOException {
    new WorkspaceStore(dir.resolve("config")).save(new Workspace(List.of(profiles)));
    return start(servers, keyring);
  }

  private App<State> start(Servers servers, java.util.concurrent.Callable<Keyring> keyring) {
    var app = HooriDb.app(
        dir.resolve("config"),
        keyring,
        repository.drivers(dir.resolve("drivers")),
        Tester.SERVER,
        servers,
        updater,
        version);
    app.state().zone = ZONE;
    apps.add(app);
    return app;
  }

  /** The zone in which moments show, whatever the machine's: an hour from UTC, two in summer. */
  static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

  static final String START = """
      size 1200 760
      frame
      await
      frame
      await
      frame
      advance 1
      frame
      """;

  /** Lets in what the work delivers, and what that starts, several times over. */
  static final String SETTLE = "await\nframe\n".repeat(6) + "advance 1\nframe\n";

  /** Selects the row and opens it. */
  static String open(String label) {
    return "press role=treeitem label=\"" + label + "\"\nkey ArrowRight\nframe\n";
  }

  static String menu(String row, String item) {
    return "press role=treeitem label=\"" + row + "\" right\nframe\nadvance 1\nframe\n"
        + "press role=menuitem label=\"" + item + "\"\nframe\nadvance 1\nframe\n";
  }

  void run(App<State> app, String script) throws IOException {
    Script.run(script, app, dir.resolve("out"));
  }

  String tree(String snap) throws IOException {
    return Files.readString(dir.resolve("out").resolve(snap + ".tree.txt"));
  }

  /** The labels of the tree's rows, in order, with their levels. */
  static List<String> rows(String tree) {
    return tree.lines()
        .filter(l -> l.contains("role=treeitem"))
        .map(l -> {
          var label = l.substring(l.indexOf("label=\"") + 7);
          label = label.substring(0, label.indexOf('"'));
          var level =
              LEVEL.matcher(l).results().map(r -> r.group(1)).findFirst().orElse(l);
          return "  ".repeat(Integer.parseInt(level) - 1) + label;
        })
        .toList();
  }

  void noPassword() throws IOException {
    try (Stream<Path> files = Files.list(dir.resolve("out"))) {
      for (var file : files.filter(f -> f.toString().endsWith(".tree.txt")).toList()) {
        assertFalse(Files.readString(file).contains(PASSWORD), file.toString());
      }
    }
  }

  static void eventually(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; !condition.getAsBoolean(); i++) {
      if (i == 250) {
        throw new AssertionError("It did not happen");
      }
      Thread.sleep(20);
    }
  }

  /** The drivers loaded and the passwords kept for this session, so that opening connects. */
  void ready(App<State> app, ConnectionProfile... profiles) throws Exception {
    downloaded();
    var s = app.state();
    for (var p : profiles) {
      // As after the lookup of a connection opened earlier in this session.
      s.drivers.recommend(Driver.of(p.provider()), FakeDrivers.VERSION);
      s.drivers.load(app, Driver.of(p.provider()), FakeDrivers.VERSION, d -> {}, e -> {});
      s.rememberPassword(p.id(), PASSWORD.toCharArray());
    }
  }

  /** The lines of the tree from the row with the label to the next row. */
  static String row(String tree, String label) {
    return ConnectionsTest.after(tree, "role=treeitem label=\"" + label + "\"");
  }

  static String message(String tree) {
    return line(tree, "#message");
  }

  @Test
  void openingAConnectionShowsItsDatabases() throws Exception {
    downloaded();
    secrets.stored.put(SHOP.id(), PASSWORD);
    var app = app(SHOP);
    var held = servers.hold("open " + PG + "/shop");
    run(app, START + open("shop-prod") + "await\nframe\n".repeat(3) + "snap connecting\n");
    String connecting = tree("connecting");
    assertTrue(row(connecting, "shop-prod").contains("role=progressbar label=\"Connecting\""));
    assertTrue(connecting.contains("\"Connecting…\""), connecting);
    String bar = line(connecting, "#connected"); // the status bar's part
    assertTrue(bar.contains("label=\"Connecting…\"") && bar.contains("value=\"busy\""), bar);
    held.countDown();
    run(app, SETTLE + "snap connected\n");
    var connected = tree("connected");
    assertEquals(
        List.of("shop-prod", "  shop", "  analytics", "  postgres", "  template0", "  template1"),
        rows(connected));
    assertFalse(row(connected, "shop-prod").contains("progressbar"), connected);
    assertTrue(message(connected).contains("\"Connected to shop-prod\""), connected);
    assertEquals(List.of(PASSWORD), servers.passwords);
    noPassword();
  }

  @Test
  void thePasswordKeptForTheSessionComesFirst() throws Exception {
    downloaded();
    secrets.stored.put(SHOP.id(), "from-the-keyring");
    var app = app(SHOP);
    app.state().rememberPassword(SHOP.id(), PASSWORD.toCharArray());
    run(app, START + open("shop-prod") + SETTLE + "snap end\n");
    assertEquals(List.of(PASSWORD), servers.passwords);
    assertTrue(rows(tree("end")).contains("  shop"));
  }

  @Test
  void withoutAPasswordTheAppAsksForIt() throws Exception {
    downloaded();
    var app = app(SHOP);
    run(app, START + open("shop-prod") + SETTLE + """
        snap asked
        press role=textbox label="Password"
        type "hunter2"
        press role=checkbox label="Keep it until HooriDB closes"
        frame
        snap typed
        press role=button label="Show password"
        frame
        snap shown
        press role=button label="Connect"
        frame
        advance 1
        frame
        """ + SETTLE + "snap end\n");
    String asked = tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Password for shop-prod\""), asked);
    assertTrue(asked.contains("\"Enter the password of shop_app on pg.example.com.\""), asked);
    assertTrue(row(asked, "shop-prod").contains("progressbar"), "it waits: " + asked);
    assertTrue(asked.contains("\"Waiting for the password…\""), asked);
    assertTrue(tree("typed").contains("\"" + "•".repeat(7) + "\""), tree("typed"));
    var shown = line(tree("shown"), "label=\"Password\"");
    assertTrue(shown.contains("\"" + "•".repeat(7) + "\"") && shown.contains("password revealed"));
    assertEquals(List.of(PASSWORD), servers.passwords);
    assertTrue(rows(tree("end")).contains("  shop"), tree("end"));
    assertTrue(app.state().remembers(SHOP.id()), "kept for this session");
    assertTrue(secrets.stored.isEmpty(), "the keyring was not written");
    noPassword();
  }

  @Test
  void aPasswordNotKeptIsAskedForAgain() throws Exception {
    downloaded();
    var app = app(SHOP);
    run(
        app,
        START + open("shop-prod") + SETTLE + """
        press role=textbox label="Password"
        type "hunter2"
        key Enter
        frame
        advance 1
        frame
        """ + SETTLE + menu("shop-prod", "Disconnect")
            + open("shop-prod") + SETTLE + "snap end\n");
    assertFalse(app.state().remembers(SHOP.id()));
    assertTrue(line(tree("end"), "role=dialog").contains("\"Password for shop-prod\""));
  }

  @Test
  void aMissingDriverIsAskedAboutAndDownloaded() throws Exception {
    secrets.stored.put(SHOP.id(), PASSWORD);
    secrets.stored.put(LEGACY.id(), PASSWORD);
    var app = app(SHOP, LEGACY);
    run(
        app,
        START + open("shop-prod") + SETTLE + """
        snap asked
        press role=button label="Download"
        frame
        snap downloading
        """ + SETTLE + SETTLE + "snap downloaded\n"
            + open("legacy") + SETTLE + """
        snap mysql
        press role=button label="Not now"
        frame
        advance 1
        frame
        snap declined
        """);
    String asked = tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Driver needed\""), asked);
    assertTrue(
        line(asked, "role=note").contains("label=\"Download PostgreSQL JDBC 42.7.14?\""), asked);
    assertTrue(asked.contains("\"Waiting for the driver…\""), asked);
    String downloading = tree("downloading");
    assertTrue(line(downloading, "role=dialog").contains("label=\"Driver needed\""), downloading);
    assertTrue(downloading.contains("label=\"Downloading PostgreSQL JDBC\""), downloading);
    String downloaded = tree("downloaded");
    assertFalse(downloaded.contains("role=dialog"), downloaded);
    assertTrue(rows(downloaded).contains("  shop"), downloaded);
    assertTrue(
        line(tree("mysql"), "role=note").contains("\"Download MySQL Connector/J 42.7.14?\""));
    String declined = tree("declined");
    assertFalse(declined.contains("role=dialog"), declined);
    assertTrue(row(declined, "legacy").contains("expanded=false"), declined);
    assertTrue(
        message(declined).contains("legacy not connected, as MySQL Connector/J is not downloaded."),
        declined);
  }

  /** A failure, and the words that show for it below the tree. */
  record Case(Failure failure, String title, String text) {}

  @Test
  void eachConnectFailureShowsAtTheNode() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var key = "open " + PG + "/shop";
    var cases = List.of(
        new Case(
            Failure.LOGIN_REFUSED,
            "Login refused",
            "The server refused this user name and password."),
        new Case(
            Failure.NO_SUCH_DATABASE,
            "No such database",
            "The server has no database named shop, or this user may not open it."),
        new Case(
            Failure.UNREACHABLE,
            "The server cannot be reached",
            "Nothing answered at pg.example.com, port 5432. Check the host and the port, and that"
                + " the server is running."),
        new Case(
            Failure.TIMED_OUT,
            "No answer in time",
            "pg.example.com did not answer on port 5432 within 10 seconds. Check the host and the"
                + " port; a firewall may be in the way."),
        new Case(
            Failure.TLS_UNAVAILABLE,
            "The server offers no TLS",
            "The server offers no TLS. Off connects without it, and password and data go over the"
                + " network as they are."),
        new Case(
            Failure.CERTIFICATE_REJECTED,
            "The certificate was not accepted",
            "The server's certificate is not trusted, or not for pg.example.com. “Encrypted, any"
                + " certificate” accepts it, without verifying the server."),
        new Case(
            Failure.UNFIT,
            "The connection failed",
            "Its settings cannot connect as they are. Edit the connection to complete them."),
        new Case(Failure.OTHER, "The connection failed", "FATAL: the server's words for OTHER"));
    run(app, START);
    boolean first = true;
    for (var c : cases) {
      servers.fail(key, FakeServers.notConnected(c.failure()));
      String name = c.failure().name().toLowerCase();
      run(
          app,
          (first ? open("shop-prod") : "press role=button label=\"Try again\"\nframe\n") + SETTLE
              + "snap " + name + "\n");
      first = false;
      String tree = tree(name);
      assertEquals(List.of("shop-prod", "  " + c.title()), rows(tree), tree);
      assertTrue(row(tree, c.title()).contains("label=\"error_circle\""), tree);
      assertTrue(line(tree, "role=note").contains("label=\"" + c.title() + "\""), tree);
      assertTrue(tree.contains(c.text()), tree);
      assertTrue(tree.contains("role=button label=\"Try again\""), tree);
      assertEquals(
          c.failure() == Failure.LOGIN_REFUSED,
          tree.contains("role=button label=\"Enter password…\""),
          tree);
      assertTrue(message(tree).contains("shop-prod: " + c.title()), tree);
    }
    servers.heal(key);
    run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap end\n");
    assertTrue(rows(tree("end")).contains("  shop"), tree("end"));
    assertFalse(tree("end").contains("role=note"), tree("end"));
    noPassword();
  }

  @Test
  void aRefusedLoginLeadsBackToThePassword() throws Exception {
    downloaded();
    servers.serve(PG, Provider.POSTGRESQL, "16.4", PG_OUTLINE, "s3cret");
    secrets.stored.put(SHOP.id(), PASSWORD);
    var app = app(SHOP);
    run(app, START + open("shop-prod") + SETTLE + """
        snap refused
        press role=button label="Enter password…"
        frame
        advance 1
        frame
        press role=textbox label="Password"
        type "s3cret"
        press role=button label="Connect"
        frame
        advance 1
        frame
        """ + SETTLE + "snap end\n");
    assertTrue(tree("refused").contains("\"Login refused\""), tree("refused"));
    assertEquals(List.of(PASSWORD, "s3cret"), servers.passwords);
    assertTrue(rows(tree("end")).contains("  shop"), tree("end"));
    assertFalse(tree("end").contains("s3cret"));
    noPassword();
  }

  @Test
  void eachLevelOpensWithItsLoadingRow() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE);
    var schemas = servers.hold("schemas " + PG + "/shop");
    run(app, open("shop") + "snap loading\n");
    String loading = tree("loading");
    assertTrue(loading.contains("\"Loading…\""), loading);
    schemas.countDown();
    run(
        app,
        SETTLE + "snap schemas\n" + open("public") + SETTLE + "snap tables\n" + open("orders")
            + SETTLE + "snap columns\n");
    assertEquals(
        List.of(
            "shop-prod",
            "  shop",
            "    public",
            "    sales",
            "    information_schema",
            "    pg_catalog",
            "    pg_toast",
            "  analytics",
            "  postgres",
            "  template0",
            "  template1"),
        rows(tree("schemas")));
    String tables = tree("tables");
    assertEquals(
        List.of("      customers", "      orders", "      order_totals"),
        rows(tables).subList(3, 6));
    assertTrue(row(tables, "customers").contains("label=\"table\""), tables);
    assertTrue(row(tables, "order_totals").contains("label=\"eye\""), tables);
    assertTrue(row(tables, "shop").contains("label=\"database\""), tables);
    assertTrue(row(tables, "public").contains("label=\"schema\""), tables);
    String columns = tree("columns");
    assertEquals(
        List.of("        id", "        customer_id", "        placed_at", "        total"),
        rows(columns).subList(5, 9));
    // The type is the row's muted text, after its name.
    assertTrue(line(columns, "label=\"id\"").contains("value=\"bigint\""), columns);
    assertTrue(
        line(columns, "label=\"placed_at\"").contains("value=\"timestamp with time zone\""),
        columns);
    assertTrue(row(columns, "id").contains("label=\"key\""), columns);
    assertTrue(row(columns, "total").contains("label=\"column_single\""), columns);
    assertTrue(line(columns, "label=\"id\"").contains("level=5"), columns);
  }

  @Test
  void aServerWithoutSchemasHasItsTablesUnderTheDatabase() throws Exception {
    var app = app(INVENTORY);
    ready(app, INVENTORY);
    run(app, START + open("inventory") + SETTLE + "snap databases\n");
    // The connection and its database share the name: the database is the second row.
    run(
        app,
        "press #row-1\nkey ArrowRight\nframe\n" + SETTLE + open("stock") + SETTLE + "snap end\n");
    assertEquals(
        List.of(
            "inventory", "  inventory", "  information_schema", "  mysql", "  performance_schema"),
        rows(tree("databases")));
    assertEquals(
        List.of(
            "inventory",
            "  inventory",
            "    items",
            "    stock",
            "      item_id",
            "      warehouse",
            "      quantity",
            "    low_stock",
            "  information_schema",
            "  mysql",
            "  performance_schema"),
        rows(tree("end")));
    String end = tree("end");
    assertTrue(row(end, "warehouse").contains("label=\"key\""), end);
    assertTrue(row(end, "low_stock").contains("label=\"eye\""), end);
    assertFalse(servers.calls.stream().anyMatch(c -> c.startsWith("schemas")), "no schemas");
  }

  @Test
  void aSecondDatabaseOpensASecondConnection() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START + open("shop-prod") + SETTLE + open("analytics") + SETTLE + open("tracking") + SETTLE
            + "snap end\n");
    assertEquals(
        List.of(
            "shop-prod",
            "  shop",
            "  analytics",
            "    tracking",
            "      events",
            "  postgres",
            "  template0",
            "  template1"),
        rows(tree("end")));
    assertEquals(
        List.of(
            "open " + PG + "/shop",
            "databases " + PG,
            "open " + PG + "/analytics",
            "schemas " + PG + "/analytics",
            "tables " + PG + "/analytics.tracking"),
        servers.calls);
    assertEquals(List.of(PASSWORD, PASSWORD), servers.passwords);
  }

  @Test
  void aDatabaseThatCannotBeOpenedFailsAlone() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    servers.fail("open " + PG + "/postgres", FakeServers.notConnected(Failure.NO_SUCH_DATABASE));
    run(
        app,
        START + open("shop-prod") + SETTLE + open("postgres") + SETTLE
            + "press role=treeitem label=\"The database could not be opened\"\nframe\nsnap failed\n"
            + open("analytics") + SETTLE + open("shop") + SETTLE + "snap end\n");
    String failed = tree("failed");
    assertTrue(
        line(failed, "role=note").contains("label=\"The database could not be opened\""), failed);
    assertTrue(
        line(failed, "role=treeitem label=\"The database could not be opened\"")
            .contains(" invalid"),
        failed);
    assertTrue(
        failed.contains("The server has no database named postgres, or this user may not open it."),
        failed);
    List<String> end = rows(tree("end"));
    assertTrue(end.contains("    tracking"), tree("end"));
    assertTrue(end.contains("    public"), tree("end"));
    assertTrue(end.contains("    The database could not be opened"), tree("end"));
    servers.heal("open " + PG + "/postgres");
    run(
        app,
        "press role=treeitem label=\"The database could not be opened\"\nframe\n"
            + "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap again\n");
    assertTrue(rows(tree("again")).contains("    reports"), tree("again"));
  }

  @Test
  void twoConnectionsDoNotWaitForEachOther() throws Exception {
    var app = app(SHOP, LEGACY);
    ready(app, SHOP, LEGACY);
    var slow = servers.hold("databases " + PG);
    run(app, START + open("shop-prod") + open("legacy") + "snap both\n");
    // The other server's databases were read while the first still had not answered.
    eventually(() -> servers.done.contains("databases " + MYSQL));
    assertEquals(1, slow.getCount());
    assertFalse(servers.done.contains("databases " + PG));
    slow.countDown();
    run(app, SETTLE + "snap end\n");
    assertEquals(
        List.of(
            "shop-prod",
            "  shop",
            "  analytics",
            "  postgres",
            "  template0",
            "  template1",
            "legacy",
            "  legacy",
            "  mysql",
            "  sys"),
        rows(tree("end")));
  }

  @Test
  void readsOnOneConnectionNeverOverlapAndKeepTheirOrder() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("sales")
            + SETTLE
            + open("public")
            + SETTLE);
    var first = servers.hold("columns " + PG + "/shop.public.customers");
    run(
        app,
        open("customers") + open("orders") + open("order_totals") + open("invoices")
            + "snap asked\n");
    // The first call is in the server, held, while the others are asked for behind it.
    eventually(() -> servers.calls.contains("columns " + PG + "/shop.public.customers"));
    first.countDown();
    run(app, SETTLE + "snap end\n");
    assertFalse(servers.overlapped, "two calls ran at once on one connection");
    var columns = servers.calls.stream().filter(c -> c.startsWith("columns")).toList();
    assertEquals(
        List.of(
            "columns " + PG + "/shop.public.customers",
            "columns " + PG + "/shop.public.orders",
            "columns " + PG + "/shop.public.order_totals",
            "columns " + PG + "/shop.sales.invoices"),
        columns);
    assertTrue(rows(tree("end")).contains("        amount"), tree("end"));
  }

  @Test
  void eachCatalogFailureShowsAtItsNode() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var key = "tables " + PG + "/shop.sales";
    var cases = List.of(
        new String[] {"NOT_FOUND", "Not found", "sales is no longer there, or this user may not"},
        new String[] {"ACCESS_DENIED", "Access denied", "The server does not let this user read"},
        new String[] {"WRONG_DATABASE", "Wrong database", "The connection is to another database"},
        new String[] {"CANCELLED", "Cancelled", "The server cancelled the read."},
        new String[] {"OTHER", "Could not be read", "The server could not tell what sales holds."});
    run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE);
    boolean first = true;
    for (var c : cases) {
      var failure = dev.hoori.db.core.catalog.CatalogException.Failure.valueOf(c[0]);
      servers.fail(key, FakeServers.notRead(failure));
      String name = c[0].toLowerCase();
      run(
          app,
          (first
                  ? open("sales") + SETTLE + "press role=treeitem label=\"" + c[1] + "\"\nframe\n"
                  : "press role=button label=\"Try again\"\nframe\n" + SETTLE)
              + "snap " + name + "\n");
      first = false;
      String tree = tree(name);
      assertTrue(rows(tree).contains("      " + c[1]), tree);
      assertTrue(line(tree, "role=note").contains("label=\"" + c[1] + "\""), tree);
      assertTrue(tree.contains(c[2]), tree);
      assertTrue(tree.contains("the server's words for " + c[0]), tree);
      assertTrue(rows(tree).contains("    public"), "the rest stays: " + tree);
    }
    servers.heal(key);
    run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap end\n");
    assertTrue(rows(tree("end")).contains("      invoices"), tree("end"));
  }

  @Test
  void aLostConnectionMarksTheConnectionDisconnected() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var key = "tables " + PG + "/shop.public";
    servers.fail(key, FakeServers.notRead(CatalogException.Failure.CONNECTION_LOST));
    run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("analytics") + SETTLE
            + open("public") + SETTLE + "snap lost\n");
    String lost = tree("lost");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(lost));
    assertTrue(message(lost).contains("shop-prod: the connection was lost"), lost);
    assertTrue(
        lost.contains("The connection was lost: the server closed it, or the network to it"), lost);
    eventually(() -> servers.closed.containsAll(List.of(PG + "/shop", PG + "/analytics")));
    servers.heal(key);
    run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap end\n");
    assertTrue(rows(tree("end")).contains("  shop"), tree("end"));
  }

  @Test
  void refreshReadsAgainAndForgetsWhatWasBelow() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE);
    servers.serve(
        PG, Provider.POSTGRESQL, "16.4", PG_OUTLINE.replace("    orders\n", "    refunds\n"));
    run(app, menu("public", "Refresh") + SETTLE + "snap end\n");
    var end = rows(tree("end"));
    assertTrue(end.contains("      refunds"), tree("end"));
    assertFalse(end.contains("      orders"), tree("end"));
    assertFalse(end.stream().anyMatch(r -> r.contains("bigint")), "what was below went");
  }

  @Test
  void copyNameCopiesTheNameAlone() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + open("orders") + SETTLE + menu("placed_at", "Copy name")
            + "snap column\n" + menu("shop-prod", "Copy name") + "snap connection\n");
    assertEquals(
        "placed_at",
        Files.readString(dir.resolve("out").resolve("column.clipboard.txt")).strip());
    assertEquals(
        "shop-prod",
        Files.readString(dir.resolve("out").resolve("connection.clipboard.txt")).strip());
  }

  /** The status bar counts the connections open, and turns while one more connects. */
  @Test
  void theStatusBarCountsTheConnectionsAndTurnsWhileOneConnects() throws Exception {
    var app = app(SHOP, INVENTORY);
    ready(app, SHOP, INVENTORY);
    var held = servers.hold("open " + MARIA);
    run(
        app,
        START + open("shop-prod") + SETTLE + open("inventory") + "frame\n".repeat(3)
            + "snap one\n");
    String one = line(tree("one"), "#connected");
    assertTrue(one.contains("label=\"1 connected\"") && one.contains("value=\"busy\""), one);
    held.countDown();
    run(app, SETTLE + "snap two\n");
    String two = line(tree("two"), "#connected");
    assertTrue(two.contains("label=\"2 connected\"") && two.contains("value=\"ok\""), two);
  }

  @Test
  void disconnectClosesTheConnectionsAndFoldsTheNode() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE + open("analytics") + SETTLE + "snap menu\n");
    run(app, menu("shop-prod", "Disconnect") + "snap end\n");
    String end = tree("end");
    assertEquals(List.of("shop-prod"), rows(end));
    assertTrue(row(end, "shop-prod").contains("expanded=false"), end);
    assertTrue(message(end).contains("Disconnected from shop-prod"), end);
    eventually(() -> servers.closed.containsAll(List.of(PG + "/shop", PG + "/analytics")));
    assertFalse(app.state().catalog.active(SHOP.id()));
  }

  @Test
  void editingOrDeletingAConnectedConnectionAsksToDisconnectFirst() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START + open("shop-prod") + SETTLE + menu("shop-prod", "Edit…") + "snap asked\n"
            + "press role=button label=\"Disconnect\"\nframe\nadvance 1\nframe\nawait\nframe\n"
            + "snap editing\nkey Escape\nframe\nadvance 1\nframe\n" + open("shop-prod") + SETTLE
            + menu("shop-prod", "Delete…") + "snap delete\n"
            + "press role=button label=\"Disconnect\"\nframe\nadvance 1\nframe\nsnap deleting\n");
    String asked = tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"Disconnect shop-prod?\""), asked);
    assertTrue(asked.contains("shop-prod is connected. To edit it, HooriDB disconnects it first."));
    String editing = tree("editing");
    assertTrue(line(editing, "role=dialog").contains("\"Edit connection\""), editing);
    assertEquals(List.of("shop-prod"), rows(editing));
    assertTrue(tree("delete").contains("To delete it, HooriDB disconnects it first."));
    assertTrue(line(tree("deleting"), "role=dialog").contains("\"Delete connection\""));
    assertFalse(app.state().catalog.active(SHOP.id()));
  }

  @Test
  void aResultForANodeRefreshedMeanwhileIsIgnored() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE);
    servers.dead = true; // the first read answers once the gate opens, cancelled or not
    var gate = servers.hold("tables " + PG + "/shop.public");
    run(app, open("public"));
    eventually(() -> servers.calls.contains("tables " + PG + "/shop.public"));
    servers.serve(
        PG, Provider.POSTGRESQL, "16.4", PG_OUTLINE.replace("    orders\n", "    refunds\n"));
    run(app, menu("public", "Refresh"));
    gate.countDown();
    run(app, SETTLE + "snap end\n");
    var end = rows(tree("end"));
    // The first read answered with orders, after the refresh; only the second one counts.
    assertEquals(2, servers.done.stream().filter(c -> c.startsWith("tables")).count());
    assertTrue(end.contains("      refunds"), tree("end"));
    assertFalse(end.contains("      orders"), tree("end"));
    assertFalse(servers.overlapped);
  }

  @Test
  void aRefreshNeverStartsTheReadsItGivesUp() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    var customers = "columns " + PG + "/shop.public.customers";
    var gate = servers.hold(customers);
    run(app, open("customers"));
    eventually(() -> servers.calls.contains(customers));
    run(app, open("orders") + menu("public", "Refresh"));
    // The read that runs is interrupted, which lets the lane go on at once: the one behind it has
    // left the lane by then.
    eventually(() -> servers.interrupted.contains(customers));
    gate.countDown();
    run(app, SETTLE);
    assertFalse(
        servers.calls.contains("columns " + PG + "/shop.public.orders"), servers.calls.toString());
  }

  @Test
  void aResultForAConnectionDisconnectedMeanwhileIsIgnored() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    servers.dead = true;
    var gate = servers.hold("databases " + PG);
    run(app, START + open("shop-prod") + menu("shop-prod", "Disconnect"));
    gate.countDown();
    eventually(() -> servers.done.contains("databases " + PG));
    run(app, SETTLE + "snap end\n");
    String end = tree("end");
    assertEquals(List.of("shop-prod"), rows(end));
    assertFalse(app.state().catalog.active(SHOP.id()));
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  @Test
  void closingTheWindowDoesNotWaitForAServer() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    servers.dead = true; // the read does not even end when it is interrupted
    servers.hold("databases " + PG);
    run(app, START + open("shop-prod"));
    eventually(() -> servers.calls.contains("databases " + PG)); // the read is in the server
    run(app, "close\nframe\n");
    assertTrue(app.windows().all().isEmpty());
    app.await(Duration.ofSeconds(1)); // nothing of the app waits for the server
    assertTrue(servers.closed.isEmpty(), "the connection closes once the read has ended");
  }

  /**
   * Shows that a cancel interrupts the lane's thread in the call, which the made-up server sees.
   * It does not show that a cancel goes through core to the server: the made-up server stands
   * where {@code Catalog} would, and core's own tests show its cancel against real servers.
   */
  @Test
  void aRunningReadIsCancelledOnTheServer() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE);
    servers.hold("schemas " + PG + "/shop");
    run(app, open("shop") + menu("shop-prod", "Disconnect"));
    eventually(() -> servers.interrupted.contains("schemas " + PG + "/shop"));
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  /** Every node that cannot be read shows why, the connection's own databases too. */
  @Test
  void aFailedReadOfTheDatabasesShowsAtTheConnection() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var key = "databases " + PG;
    servers.fail(key, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    run(app, START + open("shop-prod") + SETTLE + "snap denied\n");
    String denied = tree("denied");
    assertEquals(List.of("shop-prod", "  Access denied"), rows(denied));
    assertTrue(line(denied, "role=note").contains("label=\"Access denied\""), denied);
    assertTrue(denied.contains("The server does not let this user read what shop-prod holds."));
    servers.heal(key);
    run(app, "press role=button label=\"Try again\"\nframe\n" + SETTLE + "snap end\n");
    assertTrue(rows(tree("end")).contains("  shop"), tree("end"));
  }

  /** What a server says is checked against the password before anything of it is kept. */
  @Test
  void noFailureKeepsThePassword() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var words = "the server repeats " + PASSWORD;
    servers.fail(
        "tables " + PG + "/shop.public",
        dev.hoori.db.core.catalog.CatalogException.of(
            CatalogException.Failure.ACCESS_DENIED, words));
    servers.fail(
        "open " + PG + "/analytics",
        dev.hoori.db.core.connect.ConnectionException.of(Failure.NO_SUCH_DATABASE, words));
    servers.fail(
        "tables " + PG + "/shop.sales",
        dev.hoori.db.core.catalog.CatalogException.of(
            CatalogException.Failure.CONNECTION_LOST, words));
    run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + "press role=treeitem label=\"Access denied\"\nframe\nsnap denied\n"
            + open("analytics") + SETTLE
            + "press role=treeitem label=\"The database could not be opened\"\nframe\n"
            + "snap unopened\n");
    var kept = new StringBuilder();
    for (var live : app.state().catalog.lives.values()) {
      kept.append(live.phase).append(live.levels);
    }
    run(app, open("sales") + SETTLE + "snap lost\n");
    for (var live : app.state().catalog.lives.values()) {
      kept.append(live.phase).append(live.levels);
    }
    assertTrue(tree("denied").contains(ConnectionException.WITHHELD), tree("denied"));
    assertTrue(tree("unopened").contains(ConnectionException.WITHHELD), tree("unopened"));
    assertTrue(tree("lost").contains(ConnectionException.WITHHELD), tree("lost"));
    assertTrue(kept.toString().contains("Lost["), kept.toString());
    assertFalse(kept.toString().contains(PASSWORD), kept.toString());
    noPassword();
  }

  /** A refresh closes the connections to the databases it throws away, openings included. */
  @Test
  void aRefreshClosesAConnectionStillOpening() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    var key = "open " + PG + "/analytics";
    var opening = servers.hold(key);
    servers.fail(key, FakeServers.notConnected(Failure.NO_SUCH_DATABASE));
    run(app, START + open("shop-prod") + SETTLE + open("analytics"));
    eventually(() -> servers.calls.contains(key));
    run(app, menu("shop-prod", "Refresh"));
    eventually(() -> servers.interrupted.contains(key));
    opening.countDown();
    servers.heal(key);
    run(app, SETTLE + open("analytics") + SETTLE + "snap end\n");
    assertTrue(rows(tree("end")).contains("    tracking"), tree("end"));
    assertEquals(2, servers.calls.stream().filter(key::equals).count());
  }

  /** A password from the keyring is wiped also when its delivery is cancelled. */
  @Test
  void aPasswordReadForADisconnectedConnectionIsWiped() throws Exception {
    downloaded();
    secrets.stored.put(SHOP.id(), PASSWORD);
    var app = app(SHOP);
    app.state().drivers.recommend(Driver.of(Provider.POSTGRESQL), FakeDrivers.VERSION);
    app.state()
        .drivers
        .load(app, Driver.of(Provider.POSTGRESQL), FakeDrivers.VERSION, d -> {}, e -> {});
    run(app, START + open("shop-prod"));
    eventually(() -> secrets.given.size() == 1); // the keyring answered; the app has not taken it
    run(app, menu("shop-prod", "Disconnect") + SETTLE);
    var given = secrets.given.getFirst();
    eventually(() -> new String(given).equals("\0".repeat(PASSWORD.length())));
    assertTrue(servers.passwords.isEmpty(), "nothing connected");
  }

  /** A connection reported as gone is not used again, not even by the calls asked for behind. */
  @Test
  void aConnectionReportedAsGoneIsNotUsedAgain() throws Exception {
    var app = app(SHOP);
    ready(app, SHOP);
    run(app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    var first = "columns " + PG + "/shop.public.customers";
    var gate = servers.hold(first);
    servers.fail(first, FakeServers.notRead(CatalogException.Failure.CONNECTION_LOST));
    run(app, open("customers") + open("orders"));
    eventually(() -> servers.calls.contains(first));
    gate.countDown();
    run(app, SETTLE + "snap end\n");
    assertEquals(List.of("shop-prod", "  Disconnected"), rows(tree("end")));
    assertFalse(servers.calls.contains("columns " + PG + "/shop.public.orders"), "used again");
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  /** A held open fails after the configured connect timeout, and the note names it. */
  @Test
  void aHeldOpenFailsAfterTheConfiguredConnectTimeout() throws Exception {
    new SettingsStore(dir.resolve("config"))
        .save(Settings.DEFAULT.withConnectTimeout(Duration.ofSeconds(1)));
    var app = app(SHOP);
    ready(app, SHOP);
    servers.hold("open " + PG + "/shop");
    run(app, START + open("shop-prod") + SETTLE + "snap timed\n");
    String timed = tree("timed");
    assertTrue(timed.contains("No answer in time"), timed);
    assertTrue(timed.contains("did not answer on port 5432 within 1 second."), timed);
    assertEquals(List.of(Duration.ofSeconds(1)), servers.timeouts);
  }

  static final String FK_OUTLINE = """
      shop
        public
          customers
            *id: integer
            name: text
          orders
            *id: bigint
            customer_id: integer > customers.id on delete cascade
            parent_id: bigint > orders.id on update set null
            region_a: integer > region_fk: billing.regions.a on delete restrict
            region_b: integer > region_fk: billing.regions.b
            total: numeric(12,2)
          order_items
            *order_id: bigint > orders.id on delete cascade
            *line: integer
          @order_totals
            customer_id: integer
            total: numeric
        billing
          regions
            *a: integer
            *b: integer
      """;

  @Test
  void foreignKeysShowOnColumnsAndUnderForeignKeysNodeAndEnterOpensRelatedTable() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", FK_OUTLINE);
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE
            + open("Foreign keys")
            + open("References")
            + open("Referenced by")
            + "snap foreign-keys-tree\n");
    var fkTree = tree("foreign-keys-tree");
    assertEquals(
        List.of(
            "shop-prod",
            "  shop",
            "    public",
            "      customers",
            "      orders",
            "        id",
            "        customer_id",
            "        parent_id",
            "        region_a",
            "        region_b",
            "        total",
            "        Foreign keys",
            "          References",
            "            orders_customer_id_fkey",
            "            orders_parent_id_fkey",
            "            region_fk",
            "          Referenced by",
            "            order_items_order_id_fkey",
            "            orders_parent_id_fkey",
            "      order_items",
            "      order_totals",
            "    billing"),
        rows(fkTree));

    // Column icons and details: key columns show link icon and target(s), plain columns only type.
    assertTrue(
        row(fkTree, "id").contains("label=\"" + Symbol.KEY.label() + "\""), row(fkTree, "id"));
    assertTrue(row(fkTree, "id").contains("\"bigint\""), row(fkTree, "id"));
    assertFalse(row(fkTree, "id").contains("→"), row(fkTree, "id"));
    assertTrue(
        row(fkTree, "customer_id").contains("label=\"" + Symbol.LINK.label() + "\""),
        row(fkTree, "customer_id"));
    assertTrue(
        row(fkTree, "customer_id").contains("\"integer → customers.id\""),
        row(fkTree, "customer_id"));
    assertTrue(
        row(fkTree, "parent_id").contains("label=\"" + Symbol.LINK.label() + "\""),
        row(fkTree, "parent_id"));
    assertTrue(
        row(fkTree, "parent_id").contains("\"bigint → orders.id\""), row(fkTree, "parent_id"));
    assertTrue(
        row(fkTree, "region_a").contains("\"integer → billing.regions.a\""),
        row(fkTree, "region_a"));
    assertTrue(
        row(fkTree, "region_b").contains("\"integer → billing.regions.b\""),
        row(fkTree, "region_b"));
    assertTrue(
        row(fkTree, "total").contains("label=\"" + Symbol.COLUMN_SINGLE.label() + "\""),
        row(fkTree, "total"));
    assertTrue(row(fkTree, "total").contains("\"numeric(12,2)\""), row(fkTree, "total"));

    // Foreign keys node, group icons, and constraint rows with mapping and non-default rules.
    assertTrue(
        row(fkTree, "Foreign keys").contains("label=\"" + Symbol.LINK.label() + "\""),
        row(fkTree, "Foreign keys"));
    assertTrue(
        row(fkTree, "References").contains("label=\"" + Symbol.ARROW_RIGHT.label() + "\""),
        row(fkTree, "References"));
    assertTrue(
        row(fkTree, "Referenced by").contains("label=\"" + Symbol.ARROW_LEFT.label() + "\""),
        row(fkTree, "Referenced by"));
    assertTrue(
        row(fkTree, "orders_customer_id_fkey").contains("label=\"" + Symbol.LINK.label() + "\""),
        row(fkTree, "orders_customer_id_fkey"));
    assertTrue(
        row(fkTree, "orders_customer_id_fkey")
            .contains("\"(customer_id) → customers(id) · ON DELETE CASCADE\""),
        row(fkTree, "orders_customer_id_fkey"));
    assertTrue(
        row(fkTree, "region_fk")
            .contains("\"(region_a, region_b) → billing.regions(a, b) · ON DELETE RESTRICT\""),
        row(fkTree, "region_fk"));
    assertTrue(
        row(fkTree, "order_items_order_id_fkey")
            .contains("\"order_items(order_id) → (id) · ON DELETE CASCADE\""),
        row(fkTree, "order_items_order_id_fkey"));

    // Enter on an outgoing entry opens the referenced table; Enter on incoming opens referencing.
    run(
        app,
        "press role=treeitem label=\"orders_customer_id_fkey\"\nkey Enter\nframe\n"
            + SETTLE
            + "snap opened-ref\n");
    assertTrue(
        line(tree("opened-ref"), "role=tab label=\"customers\"").contains("selected"),
        tree("opened-ref"));

    run(
        app,
        "press role=treeitem label=\"order_items_order_id_fkey\"\nkey Enter\nframe\n"
            + SETTLE
            + "snap opened-inc\n");
    assertTrue(
        line(tree("opened-inc"), "role=tab label=\"order_items\"").contains("selected"),
        tree("opened-inc"));

    // A column that is both primary key and foreign key keeps the primary key icon and shows
    // target.
    run(app, open("order_items") + SETTLE + "snap pk-fk\n");
    var pkFk = tree("pk-fk");
    assertTrue(
        row(pkFk, "order_id").contains("label=\"" + Symbol.KEY.label() + "\""),
        row(pkFk, "order_id"));
    assertFalse(
        row(pkFk, "order_id").contains("label=\"" + Symbol.LINK.label() + "\""),
        row(pkFk, "order_id"));
    assertTrue(row(pkFk, "order_id").contains("\"bigint → orders.id\""), row(pkFk, "order_id"));
  }

  @Test
  void mariadbHidesRestrictRuleAsDefaultInTree() throws Exception {
    servers.serve(MARIA, Provider.MARIADB, "11.4.2-MariaDB", """
        warehouse
          items
            *id: int(11)
          stock
            *item_id: int(11) > items.id on delete restrict on update cascade
        """);
    var app = app(INVENTORY);
    ready(app, INVENTORY);
    run(
        app,
        START
            + open("inventory")
            + SETTLE
            + open("warehouse")
            + SETTLE
            + open("stock")
            + SETTLE
            + open("Foreign keys")
            + open("References")
            + "snap maria-fk\n");
    var mariaFk = row(tree("maria-fk"), "stock_item_id_fkey");
    assertTrue(mariaFk.contains("\"(item_id) → items(id) · ON UPDATE CASCADE\""), mariaFk);
    assertFalse(mariaFk.contains("RESTRICT"), mariaFk);
  }

  @Test
  void refreshReadsForeignKeysAgainAndKeyFailureKeepsColumns() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", FK_OUTLINE);
    var app = app(SHOP);
    ready(app, SHOP);
    var ordersKey = "foreign keys " + PG + "/shop.public.orders";
    var customersKey = "foreign keys " + PG + "/shop.public.customers";
    servers.fail(customersKey, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE
            + open("customers")
            + SETTLE
            + "snap initial\n");
    assertEquals(
        1, servers.calls.stream().filter(ordersKey::equals).count(), servers.calls.toString());
    // customers' key read failed alone: its columns are still shown, no Foreign keys node, not
    // cached.
    var initial = tree("initial");
    assertTrue(rows(initial).contains("        name"), initial);
    assertEquals(
        1, rows(initial).stream().filter("        Foreign keys"::equals).count(), initial);
    var pub = new dev.hoori.db.core.catalog.Schema(
        new dev.hoori.db.core.catalog.Database("shop", false), "public", false);
    var customers = new dev.hoori.db.core.catalog.Table(
        pub, "customers", dev.hoori.db.core.catalog.Table.Kind.TABLE);
    assertEquals(null, app.state().catalog.foreignKeys(SHOP.id(), customers));

    // Refresh on orders reads its foreign keys again.
    run(app, menu("orders", "Refresh") + SETTLE);
    assertEquals(
        2, servers.calls.stream().filter(ordersKey::equals).count(), servers.calls.toString());

    // Healing customers and refreshing it reads and caches its keys.
    servers.heal(customersKey);
    run(app, menu("customers", "Refresh") + SETTLE);
    assertEquals(1, app.state().catalog.foreignKeys(SHOP.id(), customers).size());
  }

  @Test
  void openingATableTabAndExpandingAViewReadNoForeignKeys() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", FK_OUTLINE);
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + "press role=treeitem label=\"orders\"\nkey Enter\nframe\n"
            + SETTLE
            // A double press opens the table's tab and leaves its columns shut.
            + "press role=treeitem label=\"customers\" clicks 2\nframe\n"
            + SETTLE
            + open("order_totals")
            + SETTLE
            + "snap end\n");
    assertTrue(
        servers.calls.stream().anyMatch(c -> c.startsWith("read " + PG + "/shop.public.orders ")),
        servers.calls.toString());
    assertTrue(
        servers.calls.stream()
            .anyMatch(c -> c.startsWith("read " + PG + "/shop.public.customers ")),
        servers.calls.toString());
    assertTrue(
        servers.calls.contains("columns " + PG + "/shop.public.order_totals"),
        servers.calls.toString());
    assertFalse(
        servers.calls.stream().anyMatch(c -> c.startsWith("foreign keys ")),
        servers.calls.toString());
    assertFalse(
        servers.calls.stream().anyMatch(c -> c.startsWith("indexes ")), servers.calls.toString());
  }

  static final String INDEX_OUTLINE = """
      shop
        public
          customers
            *id: integer
            email: text
            #customers_email_uidx (email) unique
            #customers_pkey (id) primary
          orders
            *id: bigint
            customer_id: integer > customers.id on delete cascade
            placed_at: timestamp
            code: text
            #orders_customer_placed_idx (customer_id, placed_at)
            #orders_code_expr_idx (lower(code)) unique
            #orders_pkey (id) primary
          notes
            body: text
      """;

  @Test
  void indexesShowUnderIndexesNodeBesideForeignKeysWithDetailsIconsAndCopyName() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", INDEX_OUTLINE);
    var app = app(SHOP);
    ready(app, SHOP);
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE
            + open("Indexes")
            + open("notes")
            + SETTLE
            + "snap indexes-tree\n");
    var idxTree = tree("indexes-tree");
    assertEquals(
        List.of(
            "shop-prod",
            "  shop",
            "    public",
            "      customers",
            "      orders",
            "        id",
            "        customer_id",
            "        placed_at",
            "        code",
            "        Foreign keys",
            "        Indexes",
            "          orders_pkey",
            "          orders_code_expr_idx",
            "          orders_customer_placed_idx",
            "      notes",
            "        body"),
        rows(idxTree));

    // Icons and muted details for Indexes and its leaf entries.
    assertTrue(
        row(idxTree, "Indexes").contains("label=\"" + Symbol.KEY_MULTIPLE.label() + "\""),
        row(idxTree, "Indexes"));
    assertTrue(
        row(idxTree, "orders_pkey").contains("label=\"" + Symbol.KEY.label() + "\""),
        row(idxTree, "orders_pkey"));
    assertTrue(
        row(idxTree, "orders_pkey").contains("\"(id) · primary key\""),
        row(idxTree, "orders_pkey"));
    assertTrue(
        row(idxTree, "orders_code_expr_idx")
            .contains("label=\"" + Symbol.KEY_MULTIPLE.label() + "\""),
        row(idxTree, "orders_code_expr_idx"));
    assertTrue(
        row(idxTree, "orders_code_expr_idx").contains("\"(lower(code)) · unique\""),
        row(idxTree, "orders_code_expr_idx"));
    assertTrue(
        row(idxTree, "orders_customer_placed_idx").contains("\"(customer_id, placed_at)\""),
        row(idxTree, "orders_customer_placed_idx"));

    // Enter on an index row opens no tab; Copy name copies the index name.
    int tabsBefore = app.state().tabs.main().active().tabs().size();
    run(
        app,
        "press role=treeitem label=\"orders_customer_placed_idx\"\nkey Enter\nframe\n"
            + menu("orders_customer_placed_idx", "Copy name"));
    assertEquals(tabsBefore, app.state().tabs.main().active().tabs().size());
    assertEquals("orders_customer_placed_idx", app.clipboard().read());

    // Refresh on Indexes refreshes the parent table and re-reads its indexes.
    var ordersIdx = "indexes " + PG + "/shop.public.orders";
    assertEquals(
        1, servers.calls.stream().filter(ordersIdx::equals).count(), servers.calls.toString());
    run(app, menu("Indexes", "Refresh") + SETTLE);
    assertEquals(
        2, servers.calls.stream().filter(ordersIdx::equals).count(), servers.calls.toString());
  }

  @Test
  void refreshReadsIndexesAgainAndIndexFailureKeepsColumnsAndForeignKeys() throws Exception {
    servers.serve(PG, Provider.POSTGRESQL, "16.4", INDEX_OUTLINE);
    var app = app(SHOP);
    ready(app, SHOP);
    var customersIdx = "indexes " + PG + "/shop.public.customers";
    servers.fail(customersIdx, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("customers")
            + SETTLE
            + "snap initial\n");
    var initial = tree("initial");
    assertTrue(rows(initial).contains("        email"), initial);
    assertTrue(rows(initial).contains("        Foreign keys"), initial);
    assertFalse(rows(initial).contains("        Indexes"), initial);
    var pub = new dev.hoori.db.core.catalog.Schema(
        new dev.hoori.db.core.catalog.Database("shop", false), "public", false);
    var customers = new dev.hoori.db.core.catalog.Table(
        pub, "customers", dev.hoori.db.core.catalog.Table.Kind.TABLE);
    assertEquals(null, app.state().catalog.indexes(SHOP.id(), customers));

    servers.heal(customersIdx);
    run(app, menu("customers", "Refresh") + SETTLE + "snap healed\n");
    assertEquals(2, app.state().catalog.indexes(SHOP.id(), customers).size());
    assertTrue(rows(tree("healed")).contains("        Indexes"), tree("healed"));
  }

  static final String MONGO_INDEX_OUTLINE = """
      hoori
        orders
          *_id: objectId
          customer: int
          total: decimal
          expiresAt: date
          #customer_1_total_-1 (customer: 1, total: -1) | partial
          #expiresAt_1 (expiresAt: 1) | TTL 3600 s
          #_id_ (_id: 1) primary
        customers
          *_id: objectId
          name: string
          email: string
          tags: array
          #email_1 (email: 1) unique | sparse
          #name_text_tags_text (name: text, tags: text)
          #_id_ (_id: 1) primary
        @paid_orders
          _id: objectId
          total: decimal
      """;

  @Test
  void mongoDbIndexesShowUnderIndexesNodeWithDetailsAndRefusedReadKeepsFields() throws Exception {
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", MONGO_INDEX_OUTLINE);
    var customersIdx = "indexes " + DocumentConsoleTest.MONGO + "/hoori.customers";
    servers.fail(customersIdx, FakeServers.notRead(CatalogException.Failure.ACCESS_DENIED));
    var docs = DocumentConsoleTest.DOCS;
    var app = app(docs);
    ready(app, docs);
    run(
        app,
        START
            + open(docs.name())
            + SETTLE
            + open("hoori")
            + SETTLE
            + open("orders")
            + SETTLE
            + open("Indexes")
            + open("customers")
            + SETTLE
            + open("paid_orders")
            + SETTLE
            + "snap mongo-indexes\n");
    var idxTree = tree("mongo-indexes");
    assertEquals(
        List.of(
            "docs",
            "  hoori",
            "    orders",
            "      _id",
            "      customer",
            "      total",
            "      expiresAt",
            "      Indexes",
            "        _id_",
            "        customer_1_total_-1",
            "        expiresAt_1",
            "    customers",
            "      _id",
            "      name",
            "      email",
            "      tags",
            "    paid_orders",
            "      _id",
            "      total"),
        rows(idxTree));

    assertTrue(
        row(idxTree, "_id_").contains("label=\"" + Symbol.KEY.label() + "\""),
        row(idxTree, "_id_"));
    assertTrue(row(idxTree, "_id_").contains("\"(_id: 1) · primary key\""), row(idxTree, "_id_"));
    assertTrue(
        row(idxTree, "customer_1_total_-1").contains("\"(customer: 1, total: -1) · partial\""),
        row(idxTree, "customer_1_total_-1"));
    assertTrue(
        row(idxTree, "expiresAt_1").contains("\"(expiresAt: 1) · TTL 3600 s\""),
        row(idxTree, "expiresAt_1"));
    assertFalse(
        servers.calls.contains("indexes " + DocumentConsoleTest.MONGO + "/hoori.paid_orders"),
        servers.calls.toString());

    // Healing customers' index read and refreshing shows unique, sparse and text indexes.
    servers.heal(customersIdx);
    run(
        app,
        "press role=treeitem label=\"orders\"\nkey ArrowLeft\nframe\n"
            + menu("customers", "Refresh")
            + SETTLE
            + open("Indexes")
            + "snap healed\n");
    var healed = tree("healed");
    assertTrue(
        row(healed, "email_1").contains("\"(email: 1) · unique · sparse\""),
        row(healed, "email_1"));
    assertTrue(
        row(healed, "name_text_tags_text").contains("\"(name: text, tags: text)\""),
        row(healed, "name_text_tags_text"));
  }

  /**
   * The pictures of a collection's indexes for a review, light and dark, into the folder that {@code
   * -Dreview=<folder>} names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void mongoDbIndexPictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    servers.serve(DocumentConsoleTest.MONGO, Provider.MONGODB, "8.0.4", MONGO_INDEX_OUTLINE);
    var docs = DocumentConsoleTest.DOCS;
    var app = app(docs);
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    app.state().sidebar = Split.Position.first(440); // room for the indexes' keys and details
    ready(app, docs);
    Script.run(
        START + open(docs.name()) + SETTLE + open("hoori") + SETTLE + open("orders") + SETTLE
            + open("Indexes") + open("customers") + SETTLE
            // Down past its fields to its own Indexes, which shares its label with orders'.
            + "key ArrowDown\n".repeat(5) + "key ArrowRight\nframe\n"
            + "snap mongodb-indexes-" + look + "\n",
        app,
        Files.createDirectories(Path.of(review)));
  }
}

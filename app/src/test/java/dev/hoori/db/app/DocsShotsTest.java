package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConsoleEditorTest.COMPLETE;
import static dev.hoori.db.app.ConsoleHistoryTest.CLEAR;
import static dev.hoori.db.app.ConsoleHistoryTest.HISTORY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.ConsoleTransactionTest.MANUAL;
import static dev.hoori.db.app.DriversTest.OPEN_DRIVERS;
import static dev.hoori.db.app.FoldersTest.SHOWN;
import static dev.hoori.db.app.FoldersTest.folder;
import static dev.hoori.db.app.PaletteTest.GO_TO;
import static dev.hoori.db.app.PaletteTest.PALETTE;
import static dev.hoori.db.app.TableTabTest.scroll;
import static dev.hoori.db.app.TableTabTest.table;

import com.rethinkj.ui.App;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.Png;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The screenshots of the website, each in the dark and the light theme, into {@code
 * docs/public/shots}, and the app's icon into {@code docs/public/logo.png}. Only with {@code
 * -Dshots}:
 *
 * <pre>
 * mvn -pl app -am verify -Dshots -Dtest=DocsShotsTest -Dsurefire.failIfNoSpecifiedTests=false
 * npm --prefix docs run shots
 * </pre>
 */
@EnabledIfSystemProperty(named = "shots", matches = ".*")
class DocsShotsTest {

  static final Path DOCS = Path.of(System.getProperty("basedir", ""))
      .toAbsolutePath()
      .getParent()
      .resolve("docs/public");

  static final String PROD = "shop-db.example.com";
  static final String STAGING = "shop-db.staging.example.com";
  static final String LOCAL = "localhost";
  static final String ANALYTICS = "analytics.example.com";
  static final String CRM = "crm.example.com";

  static final ConnectionProfile SHOP = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("shop-prod")
      .withHost(PROD)
      .withDatabase("shop")
      .withUser("shop_app")
      .withTls(Tls.VERIFIED)
      .withColor(Color.RED);
  static final ConnectionProfile SHOP_STAGING = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("shop-staging")
      .withHost(STAGING)
      .withDatabase("shop")
      .withUser("shop_app")
      .withColor(Color.AMBER);
  static final ConnectionProfile SHOP_LOCAL = ConnectionProfile.of(Provider.POSTGRESQL)
      .withName("shop-local")
      .withHost(LOCAL)
      .withDatabase("shop")
      .withUser("postgres")
      .withColor(Color.GREEN);
  static final ConnectionProfile STATS = ConnectionProfile.of(Provider.MYSQL)
      .withName("analytics")
      .withHost(ANALYTICS)
      .withUser("analyst")
      .withColor(Color.VIOLET);
  static final ConnectionProfile STATS_STAGING = ConnectionProfile.of(Provider.MYSQL)
      .withName("analytics-staging")
      .withHost("analytics.staging.example.com")
      .withUser("analyst")
      .withColor(Color.AMBER);
  static final ConnectionProfile CRM_LOCAL = ConnectionProfile.of(Provider.MARIADB)
      .withName("crm-dev")
      .withHost("127.0.0.1")
      .withUser("root");
  static final ConnectionProfile CRM_DB = ConnectionProfile.of(Provider.MARIADB)
      .withName("legacy-crm")
      .withHost(CRM)
      .withUser("crm")
      .withColor(Color.ORANGE);

  static final String SHOP_OUTLINE = """
      shop
        public
          customers
            *id: integer
            email: character varying(255)
            first_name: text
            last_name: text
            country: character(2)
            created_at: timestamp with time zone
            newsletter: boolean
          inventory
            *product_id: integer
            *warehouse: text
            quantity: integer
            updated_at: timestamp with time zone
          order_items
            *order_id: bigint
            *line: integer
            product_id: integer
            quantity: integer
            unit_price: numeric(10,2)
          orders
            *id: bigint
            customer_id: integer
            status: text
            placed_at: timestamp with time zone
            shipped_at: timestamp with time zone
            total: numeric(12,2)
            currency: character(3)
          products
            *id: integer
            name: text
            attributes: jsonb
            price: numeric(10,2)
            sku: character varying(32)
            active: boolean
          @order_totals
            customer_id: integer
            orders: bigint
            total: numeric
        billing
          invoices
            *id: bigint
            order_id: bigint
            issued_at: timestamp with time zone
            amount: numeric(12,2)
          payments
            *id: bigint
            invoice_id: bigint
            method: text
            amount: numeric(12,2)
        ~information_schema
          @columns
            table_name: name
        ~pg_catalog
          pg_class
            oid: oid
      postgres
        public
      ~template0
      ~template1
      """;

  static final String STATS_OUTLINE = """
      analytics
        daily_revenue
          *day: date
          orders: int
          revenue: decimal(14,2)
        events
          *id: bigint
          kind: varchar(32)
          at: datetime
        sessions
          *id: bigint
          started_at: datetime
      ~information_schema
      ~mysql
      ~performance_schema
      ~sys
      """;

  static final String CRM_OUTLINE = """
      crm
        accounts
          *id: int(11)
          name: varchar(200)
        contacts
          *id: int(11)
          account_id: int(11)
          email: varchar(255)
        deals
          *id: int(11)
          stage: varchar(32)
      ~information_schema
      ~mysql
      ~performance_schema
      """;

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
    c.version = "0.1.0";
    servers = c.servers
        .serve(PROD, Provider.POSTGRESQL, "17.2", SHOP_OUTLINE)
        .serve(STAGING, Provider.POSTGRESQL, "17.2", SHOP_OUTLINE)
        .serve(LOCAL, Provider.POSTGRESQL, "17.2", SHOP_OUTLINE)
        .serve(ANALYTICS, Provider.MYSQL, "8.4.3", STATS_OUTLINE)
        .serve(CRM, Provider.MARIADB, "10.11.9-MariaDB", CRM_OUTLINE)
        .values(PROD + "/shop.public.orders", rows(48_213, DocsShotsTest::order))
        .values(PROD + "/shop.public.customers", rows(12_408, DocsShotsTest::customer))
        .values(PROD + "/shop.public.products", rows(1_240, DocsShotsTest::product))
        .statement(REVENUE, List.of(revenue()))
        .statement(PENDING, List.of(rows("pending: bigint", List.of(List.of(37L)))))
        .statement(TYPO, List.of(typo()))
        .statement(RESERVE, List.of(new Result.Count(1)))
        .statement(TOP, List.of(top()))
        .statement(BY_STATUS, List.of(FakeServers.made(5, "status: text", "count: bigint")))
        .statement(OF_CUSTOMER, List.of(FakeServers.made(12, "id: bigint", "total: numeric")))
        .statement(UNSUBSCRIBE, List.of(new Result.Count(1)));
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  // The workspace: folders, one nested, and connections of each kind, most with a color.
  static Workspace workspace() {
    return new Workspace(List.of(
        folder("Production", true, SHOP, STATS, folder("Archive", true, CRM_DB)),
        folder("Staging", true, SHOP_STAGING, STATS_STAGING),
        folder("Local", true, SHOP_LOCAL, CRM_LOCAL)));
  }

  static final String START = """
      size 1280 800 scale 2
      frame
      await
      frame
      await
      frame
      advance 1
      frame
      """;

  /** The tree with shop's public schema open. */
  static final String SHOP_TREE =
      open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE;

  App<State> app(String look) throws Exception {
    var app = start(look);
    c.ready(app, SHOP, STATS, CRM_DB, SHOP_STAGING, STATS_STAGING, SHOP_LOCAL, CRM_LOCAL);
    return app;
  }

  App<State> start(String look) throws Exception {
    var app = c.app(workspace());
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    return app;
  }

  /** Closes the row's children in the tree, as read before. */
  static String close(String label) {
    return "press role=treeitem label=\"" + label + "\"\nkey ArrowLeft\nframe\n";
  }

  /** Runs the script, snaps and keeps its image as {@code <name>-<look>.png}. */
  void shot(App<State> app, String script, String name, String look) throws Exception {
    c.run(app, script + "snap " + name + "\n");
    var shots = Files.createDirectories(DOCS.resolve("shots"));
    Files.copy(
        dir.resolve("out").resolve(name + ".png"),
        shots.resolve(name + "-" + look + ".png"),
        StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  void logo() throws Exception {
    Files.createDirectories(DOCS);
    Files.write(DOCS.resolve("logo.png"), Png.encode(HooriDb.ICON.image(256)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void overview(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + open("analytics") + SETTLE + "key ArrowLeft\nframe\n" + SHOP_TREE
            + table("customers")
            + SETTLE + menu("shop-prod", "New console") + SETTLE + type(REVENUE + ";")
            + "frame\n" + table("orders") + SETTLE,
        "overview",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void connectionDialog(String look) throws Exception {
    c.secrets.stored.put(SHOP.id(), PASSWORD);
    var app = app(look);
    shot(
        app,
        START + menu("shop-prod", "Edit…") + "await\nframe\nadvance 1\nframe\n",
        "connection-dialog",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void workspace(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + table("orders") + SETTLE + close("shop-prod")
            + "press role=treeitem label=\"crm-dev\" at right-8 right\n" + SHOWN
            + "press role=menuitem label=\"Move to\"\n" + SHOWN,
        "workspace",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void tree(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + open("orders") + SETTLE + open("order_items") + SETTLE + table("orders")
            + SETTLE + "press role=treeitem label=\"placed_at\"\nframe\n",
        "tree",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void grid(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + table("customers") + SETTLE + scroll(5_000) + SETTLE
            + "press #cell-5004-1\nframe\n",
        "table",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void valuePanel(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + table("products") + SETTLE + "press #cell-2-2\nkey Enter shift\n"
            + SHOWN + SETTLE,
        "value-panel",
        look);
  }

  /** A console on shop-prod, its tree open to shop's public schema. */
  App<State> withConsole(String look) throws Exception {
    var app = app(look);
    c.run(app, START + SHOP_TREE + menu("shop-prod", "New console") + SETTLE);
    return app;
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void consoleShot(String look) throws Exception {
    var app = withConsole(look);
    shot(app, type(PENDING + ";\n\n" + REVENUE + ";") + RUN_ALL, "console", look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void completion(String look) throws Exception {
    var app = withConsole(look);
    shot(
        app,
        type(PENDING + ";")
            + RUN
            // At the end of the text, where the caret is, not where a press would put it.
            + type("\n\nSELECT o.id, o.total, o.placed_at\nFROM orders o\nJOIN customers c ON"
                    + " c.id = o.customer_id\nWHERE c.")
                .replace("press #sql\n", "key End control\n")
            + (COMPLETE + SETTLE).repeat(3)
            + COMPLETE,
        "completion",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void consoleError(String look) throws Exception {
    var app = withConsole(look);
    shot(app, type(TYPO + ";") + RUN, "console-error", look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void transaction(String look) throws Exception {
    var app = withConsole(look);
    shot(app, MANUAL + type(RESERVE + ";") + RUN, "transaction", look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void historyShot(String look) throws Exception {
    var app = withConsole(look);
    c.run(
        app,
        type(BY_STATUS)
            + RUN
            + CLEAR
            + type(OF_CUSTOMER)
            + RUN
            + CLEAR
            + type(UNSUBSCRIBE)
            + RUN
            + CLEAR
            + type(TOP)
            + RUN
            + CLEAR
            + type(TYPO)
            + RUN
            + CLEAR
            + type(REVENUE)
            + RUN
            + CLEAR
            + type(PENDING)
            + RUN
            + CLEAR
            + type(RESERVE)
            + RUN
            + CLEAR);
    shot(app, HISTORY, "history", look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void palette(String look) throws Exception {
    var app = app(look);
    shot(app, START + SHOP_TREE + table("orders") + SETTLE + PALETTE, "palette", look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void paletteTables(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + open("billing") + SETTLE + table("orders") + SETTLE + GO_TO
            + "type \"ord\"\nframe\nframe\n",
        "palette-tables",
        look);
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void drivers(String look) throws Exception {
    // Drivers of each kind as Maven Central has them, downloaded, of their real sizes.
    publish(Provider.POSTGRESQL, "42.7.11", 1_093_000);
    publish(Provider.POSTGRESQL, FakeDrivers.VERSION, 1_118_000);
    publish(Provider.MARIADB, "3.5.6", 742_000);
    publish(Provider.MYSQL, "9.4.0", 2_571_000);
    var store = new DriverStore(dir.resolve("drivers"), c.repository);
    store.download(Driver.of(Provider.POSTGRESQL), "42.7.11", DriverStore.Progress.NONE);
    store.download(Driver.of(Provider.POSTGRESQL), FakeDrivers.VERSION, DriverStore.Progress.NONE);
    store.download(Driver.of(Provider.MARIADB), "3.5.6", DriverStore.Progress.NONE);
    store.download(Driver.of(Provider.MYSQL), "9.4.0", DriverStore.Progress.NONE);
    var app = start(look);
    var s = app.state();
    s.drivers.recommend(Driver.of(Provider.POSTGRESQL), FakeDrivers.VERSION);
    s.drivers.load(app, Driver.of(Provider.POSTGRESQL), FakeDrivers.VERSION, d -> {}, e -> {});
    s.rememberPassword(SHOP.id(), PASSWORD.toCharArray());
    shot(app, START + SHOP_TREE + table("orders") + SETTLE + OPEN_DRIVERS, "drivers", look);
  }

  /**
   * Puts the version of the provider's made-up driver into the repository, padded to the size,
   * as the only one of its kind there but PostgreSQL's.
   */
  void publish(Provider provider, String version, int size) throws Exception {
    var driver = Driver.of(provider);
    var artifact =
        c.repository.root.resolve(driver.group().replace('.', '/')).resolve(driver.artifact());
    if (provider != Provider.POSTGRESQL && Files.exists(artifact.resolve(FakeDrivers.VERSION))) {
      try (var old = Files.walk(artifact.resolve(FakeDrivers.VERSION))) {
        for (var path : old.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
    var bytes = new ByteArrayOutputStream();
    try (var in = new JarInputStream(new ByteArrayInputStream(FakeDrivers.jar(driver)));
        var out = new JarOutputStream(bytes)) {
      for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
        out.putNextEntry(new JarEntry(entry.getName()));
        in.transferTo(out);
        out.closeEntry();
      }
      var padding = new byte[size];
      new Random(size).nextBytes(padding);
      out.putNextEntry(new JarEntry("padding.bin"));
      out.write(padding);
      out.closeEntry();
    }
    var jar = bytes.toByteArray();
    var file = Files.createDirectories(artifact.resolve(version))
        .resolve(driver.artifact() + "-" + version + ".jar");
    Files.write(file, jar);
    Files.setLastModifiedTime(file, FileTime.from(FakeDrivers.PUBLISHED));
    Files.writeString(
        file.resolveSibling(file.getFileName() + ".sha256"),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)));
    var versions = new StringBuilder();
    try (var entries = Files.list(artifact)) {
      entries
          .filter(Files::isDirectory)
          .forEach(v -> versions.append("<version>").append(v.getFileName()).append("</version>"));
    }
    Files.writeString(
        artifact.resolve("maven-metadata.xml"),
        "<?xml version=\"1.0\"?><metadata><versioning><versions>" + versions
            + "</versions></versioning></metadata>");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void about(String look) throws Exception {
    var app = app(look);
    shot(
        app,
        START + SHOP_TREE + table("orders") + SETTLE + "press role=menuitem label=\"Help\"\n"
            + SHOWN + "press role=menuitem label=\"About HooriDB\"\n" + SHOWN,
        "about",
        look);
  }

  // Statements of the console.

  static final String REVENUE = """
      SELECT c.country,
             count(*)     AS orders,
             sum(o.total) AS revenue
      FROM orders o
      JOIN customers c ON c.id = o.customer_id
      WHERE o.placed_at >= now() - interval '30 days'
      GROUP BY c.country
      ORDER BY revenue DESC""";

  static final String PENDING = "SELECT count(*) AS pending FROM orders WHERE status = 'pending'";

  static final String TYPO = """
      SELECT id, email, created_at
      FROM customers
      WHERE created_at > now() - interval '7 days'
      ORDER BY create_at DESC""";

  static final String RESERVE = """
      UPDATE inventory
      SET quantity = quantity - 3, updated_at = now()
      WHERE product_id = 1042 AND warehouse = 'BER-1'""";

  static final String BY_STATUS =
      "SELECT status, count(*) FROM orders GROUP BY status ORDER BY 2 DESC";
  static final String OF_CUSTOMER =
      "SELECT * FROM orders WHERE customer_id = 4821 ORDER BY placed_at DESC";
  static final String UNSUBSCRIBE =
      "UPDATE customers SET newsletter = false WHERE email = 'nora.bakker@example.net'";

  static final String TOP = """
      SELECT sku, name, price
      FROM products
      WHERE active
      ORDER BY price DESC
      LIMIT 20""";

  static Result.Rows rows(String columns, List<List<Object>> rows) {
    var fields = Arrays.stream(columns.split(", "))
        .map(f -> f.split(": ", 2))
        .map(f -> new Field(f[0], f[1], FakeServers.kind(f[1])))
        .toList();
    return new Result.Rows(fields, 0, rows);
  }

  static Result.Rows revenue() {
    Object[][] by = {
      {"DE", 1_842L, "214380.55"}, {"FR", 1_207L, "141962.10"}, {"NL", 864L, "98415.30"},
      {"AT", 702L, "81233.90"}, {"IT", 655L, "72118.45"}, {"ES", 590L, "63770.20"},
      {"BE", 412L, "47905.65"}, {"PL", 336L, "33512.80"}, {"SE", 241L, "29874.15"},
      {"DK", 188L, "22406.70"}, {"CH", 154L, "21980.35"}, {"IE", 97L, "11342.60"}
    };
    var rows = new ArrayList<List<Object>>();
    for (var row : by) {
      rows.add(List.of(row[0], row[1], new BigDecimal((String) row[2])));
    }
    return rows("country: character(2), orders: bigint, revenue: numeric", rows);
  }

  static Result.Rows top() {
    var rows = new ArrayList<List<Object>>();
    for (int i = 0; i < 20; i++) {
      var p = product(i * 37 % 1240);
      rows.add(List.of(p.get(4), p.get(1), p.get(3)));
    }
    return rows("sku: character varying(32), name: text, price: numeric(10,2)", rows);
  }

  static Failure typo() {
    return new Failure(
        Failure.Kind.INVALID,
        "column \"create_at\" does not exist",
        "42703",
        0,
        TYPO.indexOf("create_at") + 1,
        "",
        0,
        0,
        "",
        "",
        "Perhaps you meant to reference the column \"customers.created_at\".",
        "");
  }

  // Rows made up from their position, the same each time, as a real shop's might be.

  interface Row {
    List<Object> at(int i);
  }

  static List<List<Object>> rows(int count, Row row) {
    return new AbstractList<>() {
      @Override
      public List<Object> get(int i) {
        return row.at(i);
      }

      @Override
      public int size() {
        return count;
      }
    };
  }

  static int mix(long i) {
    long x = i * 0x9E3779B97F4A7C15L;
    x ^= x >>> 31;
    x *= 0xBF58476D1CE4E5B9L;
    return (int) ((x ^ (x >>> 29)) & 0x7fffffff);
  }

  static <T> T pick(T[] from, long i) {
    return from[mix(i) % from.length];
  }

  static final String[] FIRST = {
    "Emma", "Liam", "Sofia", "Noah", "Mia", "Lukas", "Aiko", "Mateo", "Chloé", "Jonas", "Elif",
    "Oskar", "Lena", "Hugo", "Amara", "Finn", "Clara", "Tomás", "Ines", "Leon", "Nora", "Jakub"
  };
  static final String[] LAST = {
    "Schmidt", "Rossi", "Dubois", "Nowak", "García", "Jensen", "Tanaka", "Silva", "Müller",
    "Kowalski", "Bakker", "Lindqvist", "Moreau", "Fischer", "Novak", "Costa", "Weber", "Hansen"
  };
  static final String[] COUNTRIES = {"DE", "DE", "DE", "FR", "FR", "NL", "AT", "IT", "ES", "BE"};
  static final String[] MAIL = {"example.com", "example.org", "example.net"};

  static final OffsetDateTime SINCE = LocalDateTime.of(2024, 3, 4, 8, 12).atOffset(ZoneOffset.UTC);

  static List<Object> customer(int i) {
    var first = pick(FIRST, i);
    var last = pick(LAST, i + 7);
    var email = Normalizer.normalize(first + "." + last, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(java.util.Locale.ROOT)
        + "@" + pick(MAIL, i + 3);
    return Arrays.asList(
        1001L + i,
        email,
        first,
        last,
        pick(COUNTRIES, i + 11),
        SINCE.plusMinutes(i * 61L + mix(i) % 50),
        mix(i + 5) % 3 != 0);
  }

  static final String[] STATUS = {
    "delivered",
    "delivered",
    "delivered",
    "delivered",
    "delivered",
    "shipped",
    "refunded",
    "cancelled",
    "delivered",
    "delivered"
  };

  static List<Object> order(int i) {
    var status = pick(STATUS, i + 1);
    // From August 2025 to November 2026.
    var placed = SINCE.plusDays(522).plusMinutes(i * 14L + mix(i) % 13);
    var shipped = status.equals("cancelled") ? null : placed.plusHours(20 + mix(i + 2) % 30);
    return Arrays.asList(
        100_001L + i,
        1001L + mix(i + 9) % 12_408,
        status,
        placed,
        shipped,
        BigDecimal.valueOf(1_290 + mix(i + 4) % 38_000, 2),
        mix(i + 6) % 9 == 0 ? "CHF" : "EUR");
  }

  static final String[] ITEMS = {
    "Linen Shirt", "Merino Sweater", "Canvas Tote", "Leather Belt", "Wool Scarf", "Denim Jacket",
    "Running Shoe", "Rain Jacket", "Cotton Tee", "Chino Trousers", "Knit Beanie", "Weekender Bag"
  };
  static final String[] COLORS = {"Sand", "Navy", "Olive", "Charcoal", "Rust", "Ivory", "Black"};
  static final String[] MATERIALS = {
    "linen",
    "merino wool",
    "cotton canvas",
    "leather",
    "wool",
    "denim",
    "recycled polyester",
    "recycled nylon",
    "organic cotton",
    "cotton twill",
    "merino wool",
    "waxed canvas"
  };

  static List<Object> product(int i) {
    var item = ITEMS[i % ITEMS.length];
    var color = COLORS[(i / ITEMS.length) % COLORS.length];
    var sku = item.replaceAll("[^A-Z]", "") + "-" + color.substring(0, 3).toUpperCase() + "-"
        + (1000 + i);
    var attributes = """
        {"color": "%s", "material": "%s", "sizes": ["S", "M", "L", "XL"], \
        "fit": "%s", "weight_g": %d, "care": {"wash_c": %d, "tumble_dry": %s}, \
        "tags": ["%s", "new-in"], "origin": {"country": "PT", "factory": "F-%03d"}}""".formatted(
            color,
            MATERIALS[i % ITEMS.length],
            mix(i) % 2 == 0 ? "regular" : "relaxed",
            180 + mix(i + 1) % 600,
            mix(i + 2) % 2 == 0 ? 30 : 40,
            mix(i + 3) % 3 == 0,
            mix(i + 4) % 2 == 0 ? "bestseller" : "autumn",
            mix(i + 5) % 120);
    return Arrays.asList(
        1L + i,
        item + ", " + color,
        attributes,
        BigDecimal.valueOf(1_990 + (mix(i + 6) % 180) * 100 + 90, 2),
        sku,
        mix(i + 7) % 11 != 0);
  }
}

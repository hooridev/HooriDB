package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConsoleEditorTest.COMPLETE;
import static dev.hoori.db.app.ConsoleEditorTest.ODD;
import static dev.hoori.db.app.ConsoleEditorTest.QUOTED;
import static dev.hoori.db.app.ConsoleEditorTest.TREE;
import static dev.hoori.db.app.ConsoleEditorTest.options;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Settings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Foreign-key JOIN and ON completions in the SQL console editor. */
class JoinCompletionTest {

  private static final String OUTLINE = """
      shop
        public
          categories
            *id: integer
            name: text
          customers
            *id: integer
            name: text
          orders
            *id: bigint
            customer_id: integer > customers.id on delete cascade
            category_id: integer > categories.id
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

  @TempDir
  Path dir;

  final ConsoleTest console = new ConsoleTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    console.dir = dir;
    console.setUp();
    servers = console.servers;
    servers.serve(PG, Provider.POSTGRESQL, "16.4", OUTLINE);
  }

  @AfterEach
  void tearDown() throws Exception {
    console.tearDown();
  }

  @Test
  void joinOffersForeignKeysInBothDirectionsWithAliasesAbovePlainTables() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app, TREE + type("SELECT * FROM orders o JOIN ") + COMPLETE + "snap join-completion\n");
    var opts = options(console.c.tree("join-completion"));
    // Foreign-key joins rank ahead of plain tables and schemas: both outgoing and incoming,
    // including composite off-path and both directions of the self-reference.
    var expectedJoins = List.of(
        "categories c ON c.id = o.category_id",
        "customers c ON c.id = o.customer_id",
        "order_items oi ON oi.order_id = o.id",
        "orders o2 ON o2.id = o.parent_id",
        "orders o2 ON o2.parent_id = o.id",
        "billing.regions r ON r.a = o.region_a AND r.b = o.region_b");
    assertEquals(expectedJoins, opts.subList(0, expectedJoins.size()), opts.toString());
    // Plain tables stay listed below the join candidates.
    assertTrue(
        opts.subList(expectedJoins.size(), opts.size()).contains("customers"), opts.toString());

    // Filtering by table name narrows to the matching join first, then the plain table; Enter
    // inserts the full join.
    console.c.run(app, "type \"cu\"\nframe\nsnap filtered\nkey Enter\nframe\n");
    assertEquals(
        List.of("customers c ON c.id = o.customer_id", "customers"),
        options(console.c.tree("filtered")));
    assertEquals(
        "SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id", console.tab(app).text);
  }

  @Test
  void joinWithoutAliasesWritesFullNamesExceptSelfReferenceWhichGetsAnAlias() throws Exception {
    var app = console.console(SHOP);
    console.c.run(app, TREE + type("SELECT * FROM orders JOIN ") + COMPLETE + "snap unaliased\n");
    var opts = options(console.c.tree("unaliased"));
    var expectedJoins = List.of(
        "categories ON categories.id = orders.category_id",
        "customers ON customers.id = orders.customer_id",
        "order_items ON order_items.order_id = orders.id",
        "orders o ON o.id = orders.parent_id",
        "orders o ON o.parent_id = orders.id",
        "billing.regions ON billing.regions.a = orders.region_a"
            + " AND billing.regions.b = orders.region_b");
    assertEquals(expectedJoins, opts.subList(0, expectedJoins.size()), opts.toString());

    // When the statement wrote the schema without an alias, the condition keeps schema.name.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM public.orders JOIN ")
            + COMPLETE
            + "snap qualified\n");
    var qualified = options(console.c.tree("qualified"));
    assertTrue(
        qualified.contains("customers ON customers.id = public.orders.customer_id"),
        qualified.toString());
  }

  @Test
  void aliasClashAppendsANumberAgainstExistingAliasesAndUnaliasedTableNames() throws Exception {
    var app = console.console(SHOP);
    // Alias "c" is already taken by categories: customers gets "c2".
    console.c.run(
        app,
        TREE
            + type("SELECT * FROM orders o JOIN categories c ON c.id = o.category_id JOIN cu")
            + COMPLETE
            + "snap clash\n");
    assertEquals(
        List.of("customers c2 ON c2.id = o.customer_id", "customers"),
        options(console.c.tree("clash")));

    // Existing alias on orders is "c", so customers also gets "c2" and uses c.customer_id.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders c JOIN cu")
            + COMPLETE
            + "snap same-alias\n");
    assertEquals(
        List.of("customers c2 ON c2.id = c.customer_id", "customers"),
        options(console.c.tree("same-alias")));
  }

  @Test
  void lowerCaseKeywordSettingAppliesToOnAndAndInJoinsAndConditions() throws Exception {
    var app = console.console(SHOP);
    app.state().choose(app.state().settings.withKeywordCase(Settings.KeywordCase.LOWER));
    console.c.run(
        app,
        TREE
            + open("billing")
            + SETTLE
            + type("SELECT * FROM orders o JOIN re")
            + COMPLETE
            + "snap lower-join\n");
    assertEquals(
        List.of("billing.regions r on r.a = o.region_a and r.b = o.region_b", "billing.regions"),
        options(console.c.tree("lower-join")));

    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders o JOIN billing.regions r ON ")
            + COMPLETE
            + "snap lower-on\n");
    assertEquals(
        "r.a = o.region_a and r.b = o.region_b",
        options(console.c.tree("lower-on")).getFirst());
  }

  @Test
  void afterOnConditionsComeFirstBesideUsualCandidates() throws Exception {
    var app = console.console(SHOP);
    // Incoming key: order_items.order_id -> orders.id.
    console.c.run(
        app,
        TREE
            + type("SELECT * FROM orders o JOIN order_items oi ON ")
            + COMPLETE
            + "snap on-incoming\n");
    var incoming = options(console.c.tree("on-incoming"));
    assertEquals("oi.order_id = o.id", incoming.getFirst(), incoming.toString());
    // Usual candidates (columns and keywords) remain available below the condition.
    assertTrue(incoming.contains("customer_id"), incoming.toString());
    assertTrue(incoming.contains("ACTION"), incoming.toString());

    // Enter inserts the condition.
    console.c.run(app, "key Enter\nframe\n");
    assertEquals(
        "SELECT * FROM orders o JOIN order_items oi ON oi.order_id = o.id", console.tab(app).text);

    // Unaliased ON condition: typing "cu" ranks the condition above customer_id and customers.
    console.c.run(
        app,
        "key A control\nkey Delete\n"
            + type("SELECT * FROM orders JOIN customers ON cu")
            + COMPLETE
            + "snap on-unaliased\n");
    var unaliased = options(console.c.tree("on-unaliased"));
    assertEquals("customers.id = orders.customer_id", unaliased.getFirst(), unaliased.toString());
    assertTrue(unaliased.contains("customer_id"), unaliased.toString());
    assertTrue(unaliased.contains("customers"), unaliased.toString());

    // Self-reference ON offers both directions.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders o JOIN orders p ON ")
            + COMPLETE
            + "snap on-self\n");
    var self = options(console.c.tree("on-self"));
    assertEquals(
        List.of("p.id = o.parent_id", "p.parent_id = o.id"), self.subList(0, 2), self.toString());

    // Composite key off search path after ON.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders o JOIN billing.regions r ON ")
            + COMPLETE
            + "snap on-composite\n");
    assertEquals(
        "r.a = o.region_a AND r.b = o.region_b",
        options(console.c.tree("on-composite")).getFirst());
  }

  @Test
  void crossAndNaturalJoinOfferPlainTablesOnly() throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app, TREE + type("SELECT * FROM orders o CROSS JOIN cu") + COMPLETE + "snap cross\n");
    assertEquals(List.of("customers"), options(console.c.tree("cross")));

    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders o NATURAL LEFT JOIN cu")
            + COMPLETE
            + "snap natural\n");
    assertEquals(List.of("customers"), options(console.c.tree("natural")));
  }

  @Test
  void unreadForeignKeysAreLoadedInTheBackgroundAndShownAtNextKeystroke() throws Exception {
    var app = console.console(SHOP);
    // Open the database and schema only; orders' columns and foreign keys are not read yet.
    console.c.run(
        app,
        open("shop-prod")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + type("SELECT * FROM orders o JOIN c")
            + "type \"u\"\nframe\nadvance 1\nframe\nsnap before-read\n");
    // Before keys load, only the plain table appears; the background read has started.
    assertEquals(List.of("customers"), options(console.c.tree("before-read")));
    console.c.run(app, SETTLE + "type \"s\"\nframe\nadvance 1\nframe\nsnap after-read\n");
    assertEquals(
        List.of("customers c ON c.id = o.customer_id", "customers"),
        options(console.c.tree("after-read")));
  }

  @Test
  void quotedIdentifiersAreQuotedPerDialect() throws Exception {
    servers.serve(QUOTED, Provider.POSTGRESQL, "16.4", """
        shop
          public
            Order Items
              *Order ID: bigint > Orders.ID
            Orders
              *ID: bigint
        """);
    var pg = console.console(ODD);
    console.c.run(
        pg,
        open("odd")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("public")
            + SETTLE
            + open("Order Items")
            + SETTLE
            + type("SELECT * FROM \"Order Items\" oi JOIN ")
            + COMPLETE
            + "snap pg-quoted\n");
    assertEquals(
        "\"Orders\" o ON o.\"ID\" = oi.\"Order ID\"",
        options(console.c.tree("pg-quoted")).getFirst());

    servers.serve(MARIA, Provider.MARIADB, "11.4", """
        inventory
          order
            *id: int
          order_items
            *order_id: int > order.id
        """);
    var maria = console.console(INVENTORY.withName("maria"));
    console.c.run(
        maria,
        open("maria")
            + SETTLE
            + open("inventory")
            + SETTLE
            + open("order_items")
            + SETTLE
            + type("SELECT * FROM order_items oi JOIN ")
            + COMPLETE
            + "snap maria-quoted\n");
    assertEquals(
        "`order` o ON o.id = oi.order_id",
        options(console.c.tree("maria-quoted")).getFirst());
  }

  @Test
  void theNewAliasAvoidsAliasesAfterTheCaretAndJoinsRankFirstWhateverTheirPrefix()
      throws Exception {
    var app = console.console(SHOP);
    console.c.run(
        app,
        TREE
            + type("SELECT * FROM orders o JOIN cu JOIN categories c ON c.id = o.category_id")
            + "key Home\n"
            + "key ArrowRight\n".repeat(30)
            + COMPLETE
            + "snap later-alias\n");
    assertEquals(
        "customers c2 ON c2.id = o.customer_id",
        options(console.c.tree("later-alias")).getFirst());

    // The condition holds "id" without starting with it, and still ranks above the column id.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM orders o JOIN order_items oi ON id")
            + COMPLETE
            + "snap inner-match\n");
    assertEquals("oi.order_id = o.id", options(console.c.tree("inner-match")).getFirst());

    // A table the FROM list names apart from the join's operands still takes the name.
    console.c.run(
        app,
        "key Escape\nkey A control\nkey Delete\n"
            + type("SELECT * FROM customers, orders JOIN cu")
            + COMPLETE
            + "snap listed\n");
    assertEquals(
        "customers c ON c.id = orders.customer_id",
        options(console.c.tree("listed")).getFirst());
  }

  @Test
  void aTargetThatAnEarlierSchemaOnThePathShadowsIsWrittenWithItsSchema() throws Exception {
    servers.serve(QUOTED, Provider.POSTGRESQL, "16.4", """
        shop
          shop_app
            customers
              *id: integer
          public
            customers
              *id: integer
            orders
              *id: bigint
              customer_id: integer > customers.id
        """);
    var app = console.console(ODD);
    console.c.run(
        app,
        open("odd")
            + SETTLE
            + open("shop")
            + SETTLE
            + open("shop_app")
            + SETTLE
            + open("public")
            + SETTLE
            + open("orders")
            + SETTLE
            + type("SELECT * FROM orders o JOIN ")
            + COMPLETE
            + "snap shadowed\n");
    assertEquals(
        "public.customers c ON c.id = o.customer_id",
        options(console.c.tree("shadowed")).getFirst());
  }
}

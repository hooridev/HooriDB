package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.ForeignKey.Rule;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.catalog.Table.Kind;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** {@link FakeServers} outline foreign keys: outgoing, incoming, self-reference, composite, keys. */
class FakeServersTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void postgresForeignKeysCoverDirectionsCompositeSelfReferenceCleanTypesAndKey() throws Exception {
    var servers = new FakeServers().serve("pg", Provider.POSTGRESQL, "18.0", """
            shop
              public
                customers
                  *id: integer
                  name: text
                orders
                  *id: integer
                  customer_id: integer > customers.id
                  parent_id: integer > orders.id
                  region_a: integer > region_fk: billing.regions.a
                  region_b: integer > region_fk: billing.regions.b
                @recent_orders
                  id: integer
              billing
                regions
                  *a: integer
                  *b: integer
              ~pg_catalog
                pg_class
                  *oid: oid
            """);
    var profile = ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("pg")
        .withDatabase("shop")
        .withUser("hoori");
    var link = servers.open(profile, new char[0], null, TIMEOUT);
    var shop = new Database("shop", false);
    var pub = new Schema(shop, "public", false);
    var billing = new Schema(shop, "billing", false);
    var customers = new Table(pub, "customers", Kind.TABLE);
    var orders = new Table(pub, "orders", Kind.TABLE);
    var regions = new Table(billing, "regions", Kind.TABLE);
    var view = new Table(pub, "recent_orders", Kind.VIEW);

    // Marker is stripped from column types in columns() and in read().
    var cols = link.columns(orders);
    assertEquals(
        List.of("integer", "integer", "integer", "integer", "integer"),
        cols.stream().map(Column::type).toList());
    assertEquals(3, link.read(orders, cols, 0, 10).rows().size());

    var recorded = new CopyOnWriteArrayList<Sent>();
    var orderKeys = Journal.recording(recorded::add, () -> link.foreignKeys(orders));
    assertEquals(
        List.of(
            new ForeignKey(
                "orders_customer_id_fkey",
                orders,
                List.of("customer_id"),
                customers,
                List.of("id"),
                Rule.NO_ACTION,
                Rule.NO_ACTION),
            new ForeignKey(
                "orders_parent_id_fkey",
                orders,
                List.of("parent_id"),
                orders,
                List.of("id"),
                Rule.NO_ACTION,
                Rule.NO_ACTION),
            new ForeignKey(
                "region_fk",
                orders,
                List.of("region_a", "region_b"),
                regions,
                List.of("a", "b"),
                Rule.NO_ACTION,
                Rule.NO_ACTION)),
        orderKeys);
    assertTrue(
        servers.calls.contains("foreign keys pg/shop.public.orders"), servers.calls.toString());
    assertEquals(1, recorded.size());
    assertEquals(Purpose.CATALOG, recorded.getFirst().purpose());

    // Incoming keys from the referenced table's side, including across schemas.
    assertEquals(List.of(orderKeys.get(0)), link.foreignKeys(customers));
    assertEquals(List.of(orderKeys.get(2)), link.foreignKeys(regions));
    assertEquals(List.of(), link.foreignKeys(view));
  }

  @Test
  void mysqlForeignKeysCrossDatabasesAndMarkSystemDatabases() throws Exception {
    var servers = new FakeServers().serve("my", Provider.MYSQL, "9.0", """
            shop
              orders
                *id: int
                account_id: int > billing.accounts.id
                sys_ref: int > mysql.help.id
            billing
              accounts
                *id: int
            ~mysql
              help
                *id: int
            """);
    var profile =
        ConnectionProfile.of(Provider.MYSQL).withHost("my").withDatabase("shop").withUser("hoori");
    var link = servers.open(profile, new char[0], null, TIMEOUT);
    var shop = new Database("shop", false);
    var billing = new Database("billing", false);
    var mysql = new Database("mysql", true);
    var orders = new Table(shop, "orders", Kind.TABLE);
    var accounts = new Table(billing, "accounts", Kind.TABLE);
    var help = new Table(mysql, "help", Kind.TABLE);

    var orderKeys = link.foreignKeys(orders);
    assertEquals(
        List.of(
            new ForeignKey(
                "orders_account_id_fkey",
                orders,
                List.of("account_id"),
                accounts,
                List.of("id"),
                Rule.NO_ACTION,
                Rule.NO_ACTION),
            new ForeignKey(
                "orders_sys_ref_fkey",
                orders,
                List.of("sys_ref"),
                help,
                List.of("id"),
                Rule.NO_ACTION,
                Rule.NO_ACTION)),
        orderKeys);
    assertEquals(List.of(orderKeys.get(0)), link.foreignKeys(accounts));
    assertEquals(List.of(orderKeys.get(1)), link.foreignKeys(help));
    assertTrue(
        servers.calls.contains("foreign keys my/billing.accounts"), servers.calls.toString());
  }
}

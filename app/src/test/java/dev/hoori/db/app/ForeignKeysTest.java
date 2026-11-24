package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.ForeignKey.Rule;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.catalog.Table.Kind;
import dev.hoori.db.core.connection.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Shared foreign key formatting and filtering for the tree and quick documentation. */
class ForeignKeysTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Schema PUBLIC = new Schema(SHOP, "public", false);
  private static final Schema BILLING = new Schema(SHOP, "billing", false);
  private static final Table CUSTOMERS = new Table(PUBLIC, "customers", Kind.TABLE);
  private static final Table ORDERS = new Table(PUBLIC, "orders", Kind.TABLE);
  private static final Table REGIONS = new Table(BILLING, "regions", Kind.TABLE);

  @Test
  void mappingQualifiesOtherTableOnlyWhenContainerDiffers() {
    var same = new ForeignKey(
        "orders_customer_id_fkey",
        ORDERS,
        List.of("customer_id"),
        CUSTOMERS,
        List.of("id"),
        Rule.NO_ACTION,
        Rule.CASCADE);
    var cross = new ForeignKey(
        "region_fk",
        ORDERS,
        List.of("region_a", "region_b"),
        REGIONS,
        List.of("a", "b"),
        Rule.SET_NULL,
        Rule.RESTRICT);

    assertEquals(
        "(customer_id) → customers(id) · ON DELETE CASCADE",
        ForeignKeys.detail(ORDERS, same, false, Provider.POSTGRESQL));
    assertEquals(
        "orders(customer_id) → (id) · ON DELETE CASCADE",
        ForeignKeys.detail(CUSTOMERS, same, true, Provider.POSTGRESQL));
    assertEquals(
        "(region_a, region_b) → billing.regions(a, b) · ON DELETE RESTRICT · ON UPDATE SET NULL",
        ForeignKeys.detail(ORDERS, cross, false, Provider.POSTGRESQL));
    assertEquals(
        "public.orders(region_a, region_b) → (a, b) · ON DELETE RESTRICT · ON UPDATE SET NULL",
        ForeignKeys.detail(REGIONS, cross, true, Provider.POSTGRESQL));
  }

  @Test
  void defaultAndUnknownRulesAreHiddenPerProvider() {
    var restricted = new ForeignKey(
        "fk_r",
        ORDERS,
        List.of("customer_id"),
        CUSTOMERS,
        List.of("id"),
        Rule.RESTRICT,
        Rule.RESTRICT);
    var unknown = new ForeignKey(
        "fk_u", ORDERS, List.of("customer_id"), CUSTOMERS, List.of("id"), null, null);
    var noAction = new ForeignKey(
        "fk_n",
        ORDERS,
        List.of("customer_id"),
        CUSTOMERS,
        List.of("id"),
        Rule.NO_ACTION,
        Rule.NO_ACTION);

    assertEquals(
        " · ON DELETE RESTRICT · ON UPDATE RESTRICT",
        ForeignKeys.rules(restricted, Provider.POSTGRESQL));
    assertEquals("", ForeignKeys.rules(restricted, Provider.MARIADB));
    assertEquals("", ForeignKeys.rules(restricted, Provider.MYSQL));
    assertEquals("", ForeignKeys.rules(unknown, Provider.MARIADB));
    assertEquals("", ForeignKeys.rules(unknown, Provider.POSTGRESQL));
    assertEquals("", ForeignKeys.rules(noAction, Provider.POSTGRESQL));
  }

  @Test
  void columnDetailAndOutgoingCheckCoverMultipleKeysAndNonKeyColumns() {
    var customerId = new Column(ORDERS, "customer_id", "bigint", false, 0);
    var total = new Column(ORDERS, "total", "numeric(12,2)", true, 0);
    var fk1 = new ForeignKey(
        "fk1",
        ORDERS,
        List.of("customer_id"),
        CUSTOMERS,
        List.of("id"),
        Rule.NO_ACTION,
        Rule.CASCADE);
    var fk2 = new ForeignKey(
        "fk2",
        ORDERS,
        List.of("customer_id"),
        REGIONS,
        List.of("a"),
        Rule.NO_ACTION,
        Rule.NO_ACTION);
    var keys = List.of(fk1, fk2);

    assertTrue(ForeignKeys.isOutgoingColumn(customerId, keys));
    assertFalse(ForeignKeys.isOutgoingColumn(total, keys));
    assertEquals(
        "bigint → customers.id, billing.regions.a", ForeignKeys.columnDetail(customerId, keys));
    assertEquals("numeric(12,2)", ForeignKeys.columnDetail(total, keys));
  }
}

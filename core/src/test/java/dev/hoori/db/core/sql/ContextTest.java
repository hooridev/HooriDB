package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Context.Asked;
import dev.hoori.db.core.sql.Context.Reference;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a caret asks for, the tables of a statement, their order and how names are written. */
class ContextTest {

  /** The context at the "|" of the text, which is taken out. */
  static Context at(String text, Provider provider) {
    int caret = text.indexOf('|');
    return Context.at(text.replace("|", ""), provider, caret);
  }

  static Reference ref(String schema, String name, String alias) {
    return new Reference(schema, name, alias);
  }

  @Test
  void theTablesOfAStatementWithTheirSchemasAndAliases() {
    assertEquals(
        List.of(ref(null, "orders", "o"), ref("public", "customers", "c")),
        Context.tables(
            "SELECT * FROM orders o JOIN public.customers AS c ON c.id = o.customer_id",
            POSTGRESQL));
    // Quoted names keep their case; unquoted ones fold on PostgreSQL only.
    assertEquals(
        List.of(ref("Sales", "Order Items", "oi"), ref(null, "invoices", null)),
        Context.tables(
            "select * from \"Sales\".\"Order Items\" oi left join Invoices where 1 = 1",
            POSTGRESQL));
    assertEquals(
        List.of(ref("Shop", "Order Items", "OI"), ref(null, "Stock", null)),
        Context.tables("SELECT * FROM `Shop`.`Order Items` OI, Stock ORDER BY 1", MARIADB));
    // A list after FROM, UPDATE and INSERT, and none in strings or comments.
    assertEquals(
        List.of(ref(null, "a", "x"), ref(null, "b", null), ref(null, "c", "y")),
        Context.tables("SELECT 1 FROM a x, b, c AS y WHERE 'FROM d' -- FROM e", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "items", null)), Context.tables("UPDATE items SET a = 1", MYSQL));
    assertEquals(
        List.of(ref(null, "items", null)),
        Context.tables("INSERT INTO items (a) VALUES (1)", MYSQL));
    assertEquals(List.of(), Context.tables("SELECT 1", POSTGRESQL));
    // A name still being written is none.
    assertEquals(List.of(), Context.tables("SELECT * FROM public.", POSTGRESQL));
    assertEquals(List.of(), at("SELECT * FROM public.|", POSTGRESQL).tables());
  }

  @Test
  void whatTheCaretAsksFor() {
    var columns = at("SELECT o.| FROM orders o", POSTGRESQL);
    assertEquals(Asked.MEMBER, columns.asked());
    assertEquals(List.of("o"), columns.path());
    assertEquals("", columns.typed());
    assertEquals(List.of(ref(null, "orders", "o")), columns.tables());
    var typed = at("SELECT O.cu| FROM orders o", POSTGRESQL);
    assertEquals(List.of("o"), typed.path());
    assertEquals("cu", typed.typed());
    assertEquals(List.of("public"), at("SELECT * FROM public.|", POSTGRESQL).path());
    assertEquals(
        List.of("Shop", "Order Items"),
        at("SELECT `Shop`.`Order Items`.na|", MYSQL).path());
    assertEquals(Asked.TABLE, at("SELECT * FROM ord|", POSTGRESQL).asked());
    assertEquals("ord", at("SELECT * FROM ord|", POSTGRESQL).typed());
    assertEquals(Asked.TABLE, at("SELECT * FROM orders JOIN |", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("SEL|", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("SELECT id, |", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("|", POSTGRESQL).asked());
    // In a string, a comment or a quoted name: nothing.
    assertEquals(Asked.NOTHING, at("SELECT 'o.|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT 1 -- o.|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT \"o|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT \"o.|", MYSQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT 1 # o.|", MARIADB).asked());
    assertEquals(Asked.NOTHING, at("SELECT $$ o.|", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("SELECT $$ o $$, |", POSTGRESQL).asked());
  }

  record Named(String name, boolean ofStatement) {}

  @Test
  void whatStartsWithTheTypedTextComesFirstThenTheStatementsColumns() {
    var candidates = List.of(
        new Named("ORDER", false),
        new Named("orders", false),
        new Named("order_id", true),
        new Named("customer_id", true),
        new Named("record", false),
        new Named("Ordinal", false),
        new Named("total", true));
    assertEquals(
        List.of("order_id", "ORDER", "orders", "Ordinal", "record"),
        Ranking.rank(candidates, Named::name, Named::ofStatement, "ord").stream()
            .map(Named::name)
            .toList());
    assertEquals(
        List.of("customer_id", "order_id", "total", "ORDER", "orders", "Ordinal", "record"),
        Ranking.rank(candidates, Named::name, Named::ofStatement, "").stream()
            .map(Named::name)
            .toList());
  }

  @Test
  void aNameIsQuotedWhereTheServerWouldNotTakeItAsItIs() {
    assertEquals("orders", Names.quoted("orders", POSTGRESQL));
    assertEquals("order_2", Names.quoted("order_2", POSTGRESQL));
    assertEquals("\"Orders\"", Names.quoted("Orders", POSTGRESQL));
    assertEquals("\"order\"", Names.quoted("order", POSTGRESQL));
    assertEquals("\"order items\"", Names.quoted("order items", POSTGRESQL));
    assertEquals("\"2fa\"", Names.quoted("2fa", POSTGRESQL));
    assertEquals("\"say \"\"hi\"\"\"", Names.quoted("say \"hi\"", POSTGRESQL));
    assertEquals("Orders", Names.quoted("Orders", MARIADB));
    assertEquals("`order`", Names.quoted("order", MARIADB));
    assertEquals("`Order`", Names.quoted("Order", MYSQL));
    assertEquals("`low-stock`", Names.quoted("low-stock", MYSQL));
    assertEquals("`a``b`", Names.quoted("a`b", MYSQL));
    assertEquals("`größe`", Names.quoted("größe", MYSQL));
  }
}

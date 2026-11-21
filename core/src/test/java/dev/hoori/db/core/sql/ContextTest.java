package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Context.Asked;
import dev.hoori.db.core.sql.Context.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

  @Test
  void anUnfinishedQuotedNameIsNoName() {
    assertEquals(Asked.NOTHING, at("SELECT * FROM \"|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT * FROM `|", MYSQL).asked());
    var before = at("SELECT co| FROM \"", POSTGRESQL);
    assertEquals(Asked.ANY, before.asked());
    assertEquals("co", before.typed());
    assertEquals(List.of(), before.tables());
    assertEquals(Asked.ANY, at("SELECT co| FROM `", MARIADB).asked());
  }

  /**
   * Every prefix and many cuts of real statements, with every caret: nothing throws, what is typed
   * ends at the caret, and the part around it lies in the text.
   */
  @Test
  void whateverIsTypedNothingThrows() {
    var statements = List.of(
        "SELECT o.id, \"Order Items\".x FROM public.orders AS o JOIN \"a\"\"b\" ab ON ab.id = o.id",
        "SELECT E'it\\'s', $$ FROM $$, $tag$ x $tag$ /* a /* b */ c */ -- d\nFROM t",
        "WITH x AS (SELECT 1) SELECT * FROM x, (SELECT 2) y WHERE 'it''s' <> \"q\"",
        "SELECT `a``b`.c, 'it\\'s', \"str\" # c\nFROM `db`.`t` t /*! STRAIGHT_JOIN */ -- x",
        "UPDATE 𝒜bc SET \"𝒜\" = '😀' WHERE id IN (1, 2) RETURNING *",
        "INSERT INTO t (a) VALUES ($1, $2::text, U&'\\0041', B'01', X'1F')");
    var random = new Random(7);
    for (var provider : Provider.SQL) {
      for (var sql : statements) {
        var texts = new ArrayList<String>();
        for (int end = 0; end <= sql.length(); end++) {
          texts.add(sql.substring(0, end));
        }
        for (int k = 0; k < 200; k++) {
          int a = random.nextInt(sql.length() + 1);
          int b = random.nextInt(sql.length() + 1);
          texts.add(sql.substring(Math.min(a, b), Math.max(a, b)));
        }
        for (var text : texts) {
          Context.tables(text, provider);
          var split = Statements.of(text, provider);
          for (int caret = -1; caret <= text.length() + 1; caret++) {
            var before = text.substring(0, Math.clamp(caret, 0, text.length()));
            var typed = Context.at(text, provider, caret).typed();
            assertTrue(before.endsWith(typed), () -> provider + ": " + text);
            var around = split.around(caret);
            assertTrue(
                0 <= around.start()
                    && around.start() <= around.end()
                    && around.end() <= text.length(),
                () -> provider + ": " + text);
          }
        }
      }
    }
  }

  @Test
  void aStatementWithACteOrASubqueryOffersNothingAfterADot() {
    var cte = at("WITH orders AS (SELECT 1 AS cte_only) SELECT o.| FROM orders o", POSTGRESQL);
    assertEquals(Asked.NOTHING, cte.asked());
    assertEquals(List.of(), cte.tables());
    assertEquals(
        Asked.NOTHING,
        at("SELECT o.| FROM orders o WHERE o.id IN (SELECT o.id FROM items o)", MYSQL)
            .asked());
    // Keywords and plain tables still.
    assertEquals(
        Asked.TABLE, at("WITH x AS (SELECT 1) SELECT * FROM ord|", POSTGRESQL).asked());
    assertEquals(
        Asked.ANY, at("SELECT * FROM t WHERE id IN (SELECT 1) OR|", POSTGRESQL).asked());
    // Parentheses that hold no SELECT are no subquery.
    assertEquals(
        Asked.MEMBER,
        at("SELECT o.| FROM orders o WHERE o.id IN (1, 2)", POSTGRESQL).asked());
  }

  @Test
  void aCommentOrAStringGetsNoCompletionsWhereverItStands() {
    assertEquals(Asked.NOTHING, at("-- se|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT 1 -- se|", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT 1 -- se|\nFROM t", POSTGRESQL).asked());
    assertEquals(Asked.NOTHING, at("# se|", MARIADB).asked());
    assertEquals(Asked.NOTHING, at("/* se|", MYSQL).asked());
    assertEquals(Asked.NOTHING, at("SELECT 'se|'", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("SELECT 1 /* x */ se|", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("-- x\nse|", POSTGRESQL).asked());
    assertEquals(Asked.ANY, at("SELECT 'x' |", POSTGRESQL).asked());
  }

  @Test
  void postgresqlsNestedCommentsEscapedStringsAndDollarQuotes() {
    var nested = at("SELECT /* a /* b */ FROM x */ o.| FROM orders o", POSTGRESQL);
    assertEquals(Asked.MEMBER, nested.asked());
    assertEquals(List.of(ref(null, "orders", "o")), nested.tables());
    var escaped = at("SELECT E'it\\'s FROM x', o.| FROM orders o", POSTGRESQL);
    assertEquals(Asked.MEMBER, escaped.asked());
    assertEquals(List.of(ref(null, "orders", "o")), escaped.tables());
    var dollar = at("SELECT $q$ FROM x $q$, o.| FROM orders o", POSTGRESQL);
    assertEquals(List.of(ref(null, "orders", "o")), dollar.tables());
    // On MariaDB "/* a /* b */" is a comment that ends at the first "*/".
    assertEquals(
        List.of(ref(null, "x", null), ref(null, "orders", "o")),
        Context.tables("SELECT /* a /* b */ 1 FROM x */ FROM orders o", MARIADB));
  }

  @Test
  void theColumnsOfTheStatementComeBeforeTheRestAlsoWhereTheyDoNotStartWithTheText() {
    var candidates = List.of(
        new Named("paid", false),
        new Named("customer_id", true),
        new Named("idle", false),
        new Named("id", true),
        new Named("void", false));
    assertEquals(
        List.of("id", "idle", "customer_id", "paid", "void"),
        Ranking.rank(candidates, Named::name, Named::ofStatement, "id").stream()
            .map(Named::name)
            .toList());
  }
}

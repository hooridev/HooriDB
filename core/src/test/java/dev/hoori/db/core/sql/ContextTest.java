package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Context.Asked;
import dev.hoori.db.core.sql.Context.Reference;
import dev.hoori.db.core.sql.Context.Symbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    assertEquals(Asked.JOIN, at("SELECT * FROM orders JOIN |", POSTGRESQL).asked());
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
        Ranking.rank(candidates, Named::name, c -> c.ofStatement() ? 1 : 0, "ord").stream()
            .map(Named::name)
            .toList());
    assertEquals(
        List.of("customer_id", "order_id", "total", "ORDER", "orders", "Ordinal", "record"),
        Ranking.rank(candidates, Named::name, c -> c.ofStatement() ? 1 : 0, "").stream()
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
            Context.symbol(text, provider, caret);
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
        Ranking.rank(candidates, Named::name, c -> c.ofStatement() ? 1 : 0, "id").stream()
            .map(Named::name)
            .toList());
  }

  /** The symbol at the "|" of the text, which is taken out. */
  static Optional<Symbol> symbolAt(String text, Provider provider) {
    int offset = text.indexOf('|');
    return Context.symbol(text.replace("|", ""), provider, offset);
  }

  @Test
  void symbolFindsIdentifiersWithQualifiersAndTableReferences() {
    var col = symbolAt("SELECT o.to|tal FROM public.orders o", POSTGRESQL).orElseThrow();
    assertEquals(List.of("o"), col.path());
    assertEquals("total", col.name());
    assertEquals(false, col.table());
    assertEquals(false, col.qualifier());
    assertEquals(List.of(ref("public", "orders", "o")), col.tables());

    var alias = symbolAt("SELECT |o.total FROM public.orders o", POSTGRESQL).orElseThrow();
    assertEquals(List.of(), alias.path());
    assertEquals("o", alias.name());
    assertEquals(true, alias.qualifier());

    var schema = symbolAt("SELECT o.total FROM pub|lic.orders o", POSTGRESQL).orElseThrow();
    assertEquals("public", schema.name());
    assertEquals(true, schema.qualifier());
    assertEquals(false, schema.table());

    var table = symbolAt("SELECT o.total FROM public.ord|ers o", POSTGRESQL).orElseThrow();
    assertEquals(List.of("public"), table.path());
    assertEquals("orders", table.name());
    assertEquals(true, table.table());
    assertEquals(false, table.qualifier());
  }

  @Test
  void theFromOfIsDistinctFromNamesNoTable() {
    var column = symbolAt(
            "SELECT o.total IS DISTINCT FROM o.custo|mer_id FROM orders o", POSTGRESQL)
        .orElseThrow();
    assertEquals(false, column.table());
    assertEquals(List.of(ref(null, "orders", "o")), column.tables());
    var not = symbolAt("SELECT a IS NOT DISTINCT FROM o.b|c FROM orders o", POSTGRESQL);
    assertEquals(false, not.orElseThrow().table());
    // A column labeled distinct is followed by a FROM that names tables.
    assertEquals(
        List.of(ref(null, "orders", "o")),
        Context.tables("SELECT o.total AS distinct FROM orders o", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "orders", "o")),
        at("SELECT o.total AS distinct FROM orders o WHERE o.|", POSTGRESQL).tables());
  }

  @Test
  void symbolReadsListsQuotedNamesAndNothingOutsideAName() {
    // Second table in a comma-separated FROM list is also in table position.
    var second = symbolAt("SELECT id FROM orders, cust|omers", POSTGRESQL).orElseThrow();
    assertEquals("customers", second.name());
    assertEquals(true, second.table());

    // Quoted identifiers keep their case and mark quoted=true.
    var quoted =
        symbolAt("SELECT * FROM \"Sales\".\"Order |Items\"", POSTGRESQL).orElseThrow();
    assertEquals(List.of("Sales"), quoted.path());
    assertEquals("Order Items", quoted.name());
    assertEquals(true, quoted.quoted());
    assertEquals(true, quoted.table());

    // MariaDB backtick-quoted identifiers.
    var maria =
        symbolAt("SELECT `i`.`sk|u` FROM `inventory`.`items` i", MARIADB).orElseThrow();
    assertEquals(List.of("i"), maria.path());
    assertEquals("sku", maria.name());
    assertEquals(true, maria.quoted());

    // Comments, strings, numbers, punctuation and whitespace give empty.
    assertTrue(symbolAt("SELECT 1 -- ord|ers", POSTGRESQL).isEmpty());
    assertTrue(symbolAt("SELECT 'ord|ers'", POSTGRESQL).isEmpty());
    assertTrue(symbolAt("SELECT 1|23 FROM orders", POSTGRESQL).isEmpty());
    assertTrue(symbolAt("SELECT | FROM orders", POSTGRESQL).isEmpty());
    assertTrue(symbolAt("SELECT o|.total FROM orders o", POSTGRESQL).isEmpty());
  }

  @Test
  void joinSlotDistinguishesConditionalJoinsFromCrossNaturalAndOtherTableSlots() {
    // Conditional joins ask for JOIN.
    for (var join : List.of(
        "JOIN",
        "INNER JOIN",
        "LEFT JOIN",
        "LEFT OUTER JOIN",
        "RIGHT JOIN",
        "RIGHT OUTER JOIN",
        "FULL JOIN",
        "FULL OUTER JOIN")) {
      var ctx = at("SELECT * FROM orders o " + join + " |", POSTGRESQL);
      assertEquals(Asked.JOIN, ctx.asked(), join);
      assertEquals(1, ctx.left().size(), join);
      assertEquals(List.of(ref(null, "orders", "o")), ctx.tables(), join);
    }
    // Typing in a JOIN slot is not one of the tables to the left.
    var typing = at("SELECT * FROM orders o JOIN cu|", POSTGRESQL);
    assertEquals(Asked.JOIN, typing.asked());
    assertEquals("cu", typing.typed());
    assertEquals(1, typing.left().size());
    assertEquals(List.of(ref(null, "orders", "o")), typing.left());

    // Tables after the caret are not to the left.
    var middle =
        at("SELECT * FROM orders o JOIN | JOIN customers c ON c.id = o.customer_id", POSTGRESQL);
    assertEquals(Asked.JOIN, middle.asked());
    assertEquals(1, middle.left().size());
    assertEquals(List.of(ref(null, "orders", "o")), middle.left());

    // CROSS JOIN, NATURAL […] JOIN, FROM, INTO and UPDATE stay TABLE.
    for (var sql : List.of(
        "SELECT * FROM |",
        "INSERT INTO |",
        "UPDATE |",
        "SELECT * FROM orders o CROSS JOIN |",
        "SELECT * FROM orders o CROSS JOIN cu|",
        "SELECT * FROM orders o NATURAL JOIN |",
        "SELECT * FROM orders o NATURAL INNER JOIN |",
        "SELECT * FROM orders o NATURAL LEFT JOIN |",
        "SELECT * FROM orders o NATURAL LEFT OUTER JOIN |",
        "SELECT * FROM orders o NATURAL RIGHT JOIN |",
        "SELECT * FROM orders o NATURAL RIGHT OUTER JOIN |",
        "SELECT * FROM orders o NATURAL FULL JOIN |",
        "SELECT * FROM orders o NATURAL FULL OUTER JOIN |")) {
      assertEquals(Asked.TABLE, at(sql, POSTGRESQL).asked(), sql);
    }

    // Schema-qualified typing after JOIN still asks for MEMBER.
    var qualified = at("SELECT * FROM orders o JOIN public.cu|", POSTGRESQL);
    assertEquals(Asked.MEMBER, qualified.asked());
    assertEquals(List.of("public"), qualified.path());
    assertEquals(1, qualified.left().size());
  }

  @Test
  void onSlotMatchesOnlyRightAfterTheOnOfAConditionalJoin() {
    var aliased = at("SELECT * FROM orders o JOIN customers c ON |", POSTGRESQL);
    assertEquals(Asked.ON, aliased.asked());
    assertEquals(2, aliased.left().size());
    assertEquals(List.of(ref(null, "orders", "o"), ref(null, "customers", "c")), aliased.tables());

    var typed = at("SELECT * FROM orders o JOIN customers c ON c|", POSTGRESQL);
    assertEquals(Asked.ON, typed.asked());
    assertEquals("c", typed.typed());
    assertEquals(2, typed.left().size());

    var unaliased = at("SELECT * FROM orders LEFT OUTER JOIN public.customers ON |", POSTGRESQL);
    assertEquals(Asked.ON, unaliased.asked());
    assertEquals(2, unaliased.left().size());

    var withAs = at("SELECT * FROM orders o JOIN public.customers AS c ON |", POSTGRESQL);
    assertEquals(Asked.ON, withAs.asked());
    assertEquals(2, withAs.left().size());

    var chained = at(
        "SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id"
            + " JOIN order_items oi ON |",
        POSTGRESQL);
    assertEquals(Asked.ON, chained.asked());
    assertEquals(3, chained.left().size());

    // A later JOIN after the caret is not to the left.
    var beforeLater = at(
        "SELECT * FROM orders o JOIN customers c ON | JOIN order_items oi ON oi.order_id = o.id",
        POSTGRESQL);
    assertEquals(Asked.ON, beforeLater.asked());
    assertEquals(2, beforeLater.left().size());
    assertEquals(
        List.of(ref(null, "orders", "o"), ref(null, "customers", "c")), beforeLater.left());

    // Dot after ON asks for MEMBER, not ON.
    assertEquals(
        Asked.MEMBER,
        at("SELECT * FROM orders o JOIN customers c ON c.|", POSTGRESQL).asked());

    // Anything else after ON or non-join ON asks for ANY.
    for (var sql : List.of(
        "SELECT * FROM orders o JOIN customers c ON|",
        "SELECT * FROM orders o JOIN customers c ON c.id = |",
        "SELECT * FROM orders o JOIN customers c ON c.id = o.customer_id AND |",
        "SELECT * FROM orders o JOIN customers c ON |TRUE",
        "SELECT * FROM orders o JOIN customers c ON | LEFT(o.code, 1) = 'A'",
        "SELECT * FROM orders o CROSS JOIN customers c ON |",
        "SELECT * FROM orders o NATURAL JOIN customers c ON |",
        "SELECT * FROM orders o ON |",
        "CREATE INDEX idx ON |",
        "INSERT INTO orders (id) VALUES (1) ON |")) {
      assertEquals(Asked.ANY, at(sql, POSTGRESQL).asked(), sql);
    }
  }

  @Test
  void aJoinOrOnThatHasItsConditionAlreadyAsksForNoNewOne() {
    for (var sql : List.of(
        "SELECT * FROM orders o JOIN cu|stomers c ON c.id = o.customer_id",
        "SELECT * FROM orders o JOIN | customers c USING (id)",
        "SELECT * FROM orders o JOIN cu| AS buyer")) {
      assertEquals(Asked.TABLE, at(sql, POSTGRESQL).asked(), sql);
    }
    assertEquals(
        Asked.ANY,
        at("SELECT * FROM orders o JOIN order_items oi ON oi|.order_id = o.id", POSTGRESQL)
            .asked());
    assertEquals(
        Asked.ANY,
        at("SELECT * FROM orders o JOIN order_items oi ON |oi.order_id = o.id", POSTGRESQL)
            .asked());
    assertEquals(
        Asked.ON,
        at("SELECT * FROM orders o JOIN order_items oi ON | WHERE o.id = 1", POSTGRESQL)
            .asked());
    assertEquals(
        Asked.ON,
        at("SELECT * FROM orders o JOIN order_items oi ON |;", POSTGRESQL).asked());
    assertEquals(
        Asked.ON,
        at("SELECT * FROM orders o JOIN order_items oi ON | LEFT JOIN items i ON true", POSTGRESQL)
            .asked());
    assertEquals(
        Asked.ON,
        at("SELECT * FROM orders o JOIN order_items oi ON | WHERE (o.id = 1)", POSTGRESQL)
            .asked());
    assertEquals(
        Asked.TABLE,
        at("SELECT * FROM orders o JOIN | ON o.customer_id = 1", POSTGRESQL).asked());
  }

  @Test
  void theTablesToTheLeftAreThoseOfTheFromItemTheCaretIsIn() {
    assertEquals(
        List.of(ref(null, "categories", "ca"), ref(null, "customers", "c")),
        at("SELECT * FROM orders o, categories ca JOIN customers c ON |", POSTGRESQL)
            .left());
    assertEquals(
        List.of(ref(null, "orders", "o")),
        at("SELECT * FROM items i UNION ALL SELECT * FROM orders o JOIN |", POSTGRESQL)
            .left());
    assertEquals(
        List.of(ref(null, "orders", "o")),
        at("INSERT INTO archive SELECT * FROM orders o JOIN |", POSTGRESQL).left());
    // A FROM list continued after a join's ON reads the next item and leaves out the earlier ones.
    var afterOn = at("SELECT * FROM a JOIN b ON a.id = b.a_id, c JOIN d ON |", POSTGRESQL);
    assertEquals(Asked.ON, afterOn.asked());
    assertEquals(List.of(ref(null, "c", null), ref(null, "d", null)), afterOn.left());
    var afterOnJoin = at("SELECT * FROM a JOIN b ON a.id = b.a_id, c JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, afterOnJoin.asked());
    assertEquals(List.of(ref(null, "c", null)), afterOnJoin.left());
    assertEquals(
        List.of(ref(null, "d", null), ref(null, "e", null)),
        at("SELECT * FROM a JOIN b ON a.id = b.a_id, c, d JOIN e ON |", POSTGRESQL)
            .left());
    assertEquals(
        List.of(ref(null, "c", null), ref(null, "d", null)),
        at("SELECT * FROM a JOIN b ON a.offset = b.offset, c JOIN d ON |", POSTGRESQL)
            .left());
    // A column named as a non-reserved word, such as lock or MySQL's offset, does not end the FROM
    // clause.
    assertEquals(
        List.of(ref(null, "c", null), ref(null, "d", null)),
        at("SELECT * FROM a JOIN b ON a.id = b.a_id AND offset = 0, c JOIN d ON |", MYSQL)
            .left());
    assertEquals(
        List.of(ref(null, "c", null), ref(null, "d", null)),
        at("SELECT * FROM a JOIN b ON a.id = b.a_id AND lock IS NULL, c JOIN d ON |", POSTGRESQL)
            .left());
    // Commas after clauses that end FROM are never read as tables.
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        Context.tables("SELECT * FROM a, b GROUP BY a.id, b.id ORDER BY a.id, b.id", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        Context.tables(
            "SELECT * FROM a JOIN b ON a.id = b.a_id WHERE a.x = 1 ORDER BY c, d", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "a", null)),
        Context.tables("SELECT * FROM a HAVING count(*) > 1, b", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "a", null)), Context.tables("SELECT * FROM a LIMIT 1, 2", MYSQL));
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        Context.tables("SELECT * FROM a UNION SELECT x, y FROM b", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "a", null)),
        Context.tables("SELECT * FROM a FOR UPDATE OF a, b", POSTGRESQL));
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        at("SELECT * FROM a JOIN b ON a.id IN (1, 2) JOIN |", POSTGRESQL).left());
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        at("SELECT * FROM a JOIN b ON ARRAY[a.id, 2] = ARRAY[1, 2] JOIN |", POSTGRESQL)
            .left());
    assertEquals(
        List.of(ref(null, "a", null), ref(null, "b", null)),
        at("SELECT * FROM a JOIN b ON EXTRACT(YEAR FROM a.at) = 2020 JOIN |", POSTGRESQL)
            .left()
            .subList(0, 2));
    // A FROM after a call still being typed names its tables.
    assertEquals(
        List.of(ref(null, "orders", "o")),
        at("SELECT COUNT(o.| FROM orders o", POSTGRESQL).tables());
  }

  @Test
  void columnAliasListsAreKeptOutOfLeftWhileReadingContinues() {
    var withAs = at("SELECT * FROM customers AS c(customer_pk) JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, withAs.asked());
    assertEquals(List.of(), withAs.left());

    var withoutAs = at("SELECT * FROM customers c(customer_pk, full_name) JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, withoutAs.asked());
    assertEquals(List.of(), withoutAs.left());

    // A later FROM item after the column alias list is still read.
    var nextItem = at("SELECT * FROM customers AS c(customer_pk), orders o JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, nextItem.asked());
    assertEquals(List.of(ref(null, "orders", "o")), nextItem.left());

    // A non-aliased table in the same FROM item stays in left; the column-aliased one does not.
    var mixed = at(
        "SELECT * FROM orders o JOIN customers AS c(customer_pk) ON c.customer_pk = o.customer_id"
            + " JOIN |",
        POSTGRESQL);
    assertEquals(Asked.JOIN, mixed.asked());
    assertEquals(List.of(ref(null, "orders", "o")), mixed.left());

    // Joining a table with a column alias list does not offer an ON condition for an earlier table.
    var joinedWithCols = at(
        "SELECT * FROM orders o JOIN categories ca ON ca.id = o.category_id"
            + " JOIN customers AS c(customer_pk) ON |",
        POSTGRESQL);
    assertEquals(Asked.ANY, joinedWithCols.asked());
    assertEquals(
        List.of(ref(null, "orders", "o"), ref(null, "categories", "ca")), joinedWithCols.left());

    // Unclosed column alias list while typing does not throw.
    assertEquals(
        List.of(), at("SELECT * FROM customers AS c(customer_pk|", POSTGRESQL).left());
  }

  @Test
  void aliasedParenthesizedJoinsHideTheirInnerTablesFromLeft() {
    var withAs = at(
        "SELECT * FROM (orders o JOIN customers c ON c.id = o.customer_id) AS j JOIN |",
        POSTGRESQL);
    assertEquals(Asked.JOIN, withAs.asked());
    assertEquals(List.of(), withAs.left());

    var withoutAs = at(
        "SELECT * FROM (orders o JOIN customers c ON c.id = o.customer_id) j JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, withoutAs.asked());
    assertEquals(List.of(), withoutAs.left());

    // Nested aliased parenthesized join hides all inner tables.
    assertEquals(
        List.of(),
        at(
                "SELECT * FROM ((orders o JOIN customers c ON c.id = o.customer_id)"
                    + " JOIN categories ca ON ca.id = o.category_id) AS j JOIN |",
                POSTGRESQL)
            .left());

    // A table outside the aliased group stays in left.
    assertEquals(
        List.of(ref(null, "categories", "ca")),
        at(
                "SELECT * FROM categories ca"
                    + " JOIN (orders o JOIN customers c ON c.id = o.customer_id) AS j"
                    + " ON j.category_id = ca.id JOIN |",
                POSTGRESQL)
            .left());

    // A parenthesized join without an alias keeps the tables its JOINs name.
    var unaliased =
        at("SELECT * FROM (orders o JOIN customers c ON c.id = o.customer_id) JOIN |", POSTGRESQL);
    assertEquals(Asked.JOIN, unaliased.asked());
    assertEquals(List.of(ref(null, "customers", "c")), unaliased.left());

    // Inside a group, its own tables alone: the ON there cannot see those before the group.
    var insideOn = at(
        "SELECT * FROM orders o JOIN (categories ca JOIN order_items oi ON |) AS j ON true",
        POSTGRESQL);
    assertEquals(Asked.ON, insideOn.asked());
    assertEquals(List.of(ref(null, "order_items", "oi")), insideOn.left());
  }
}

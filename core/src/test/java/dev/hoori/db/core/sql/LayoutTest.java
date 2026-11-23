package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Statements.Piece;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutTest {

  private static String format(String sql, Provider provider) throws Layout.UnclosedException {
    var laid = Layout.of(sql, provider);
    // Idempotence: formatting formatted text gives the exact same text.
    assertEquals(laid, Layout.of(laid, provider), "idempotence: " + laid);
    // Only white space changes outside strings, quoted identifiers and comments.
    assertEquals(
        nonWhitespacePieces(sql, provider),
        nonWhitespacePieces(laid, provider),
        "only white space may change: " + laid);
    return laid;
  }

  private static List<String> nonWhitespacePieces(String text, Provider provider) {
    return Statements.pieces(text, provider).stream()
        .map(p -> p.kind() + ":" + text.substring(p.start(), p.end()))
        .toList();
  }

  @Test
  void selectWithListFromWhereAndOrderByLimit() throws Layout.UnclosedException {
    assertEquals(
        """
        SELECT ip,
               host,
               count_errors
        FROM performance_schema.host_cache
        WHERE a = 1
          AND b = 2
        ORDER BY sum_errors DESC
        LIMIT 20;""",
        format(
            "SELECT ip, host, count_errors FROM performance_schema.host_cache "
                + "WHERE a = 1 AND b = 2 ORDER BY sum_errors DESC LIMIT 20;",
            MYSQL));
  }

  @Test
  void joinsWithOnGroupByHavingAndOrderBy() throws Layout.UnclosedException {
    assertEquals(
        """
        SELECT u.id,
               u.name,
               count(o.id) AS orders
        FROM users u
        LEFT OUTER JOIN orders o ON o.user_id = u.id
          AND o.status = 'paid'
        INNER JOIN regions r ON r.id = u.region_id
        WHERE u.active = true
          AND u.age >= 18
          OR u.vip = true
        GROUP BY u.id,
                 u.name
        HAVING count(o.id) > 0
          AND sum(o.total) > 100
        ORDER BY orders DESC,
                 u.name ASC
        LIMIT 10
        OFFSET 20;""",
        format(
            "SELECT u.id, u.name, count(o.id) AS orders FROM users u "
                + "LEFT OUTER JOIN orders o ON o.user_id = u.id AND o.status = 'paid' "
                + "INNER JOIN regions r ON r.id = u.region_id "
                + "WHERE u.active = true AND u.age >= 18 OR u.vip = true "
                + "GROUP BY u.id, u.name "
                + "HAVING count(o.id) > 0 AND sum(o.total) > 100 "
                + "ORDER BY orders DESC, u.name ASC LIMIT 10 OFFSET 20;",
            POSTGRESQL));
  }

  @Test
  void insertValuesUpdateSetAndDelete() throws Layout.UnclosedException {
    assertEquals(
        """
        INSERT INTO orders (id, customer, total)
        VALUES (1, 'Ada', 12.50),
               (2, 'Grace', 25.00)
        RETURNING id, total;""",
        format(
            "INSERT INTO orders(id,customer,total) VALUES (1,'Ada',12.50),(2,'Grace',25.00) "
                + "RETURNING id, total;",
            POSTGRESQL));

    assertEquals(
        """
        UPDATE orders
        SET paid = true,
            updated_at = now(),
            note = 'ok'
        WHERE id = 1
          AND paid = false;""",
        format(
            "UPDATE orders SET paid=true, updated_at=now(), note='ok' "
                + "WHERE id=1 AND paid=false;",
            POSTGRESQL));

    assertEquals(
        """
        DELETE FROM orders
        WHERE created_at < '2020-01-01'
          AND status = 'expired';""",
        format(
            "DELETE FROM orders WHERE created_at < '2020-01-01' AND status = 'expired';",
            POSTGRESQL));
  }

  @Test
  void subqueriesAreIndentedWhileFunctionsAndInListsStayOnTheirLine()
      throws Layout.UnclosedException {
    assertEquals(
        """
        SELECT coalesce(nickname, name, 'anon'),
               left(name, 3),
               count(*)
        FROM users
        WHERE status IN ('new', 'open', 'pending')
          AND (total > 10 OR vip = true)
          AND id IN (
            SELECT user_id
            FROM orders
            WHERE total > 100
              AND paid = true
          );""",
        format(
            "SELECT coalesce(nickname,name,'anon'), left(name,3), count(*) FROM users "
                + "WHERE status IN ('new','open','pending') AND (total>10 OR vip=true) "
                + "AND id IN (SELECT user_id FROM orders WHERE total>100 AND paid=true);",
            POSTGRESQL));

    assertEquals(
        """
        SELECT s.user_id,
               s.total
        FROM (
          SELECT user_id,
                 sum(amount) AS total
          FROM orders
          GROUP BY user_id
        ) s
        WHERE s.total > 50;""",
        format(
            "SELECT s.user_id, s.total FROM "
                + "(SELECT user_id, sum(amount) AS total FROM orders GROUP BY user_id) s "
                + "WHERE s.total > 50;",
            POSTGRESQL));
  }

  @Test
  void betweenAndDistinctFromDoNotBreakAsClauses() throws Layout.UnclosedException {
    assertEquals(
        """
        SELECT a IS NOT DISTINCT FROM b
        FROM t
        WHERE x BETWEEN 1 AND 10
          AND y = 2;""",
        format(
            "SELECT a IS NOT DISTINCT FROM b FROM t WHERE x BETWEEN 1 AND 10 AND y = 2;",
            POSTGRESQL));
  }

  @Test
  void unionAndWithCte() throws Layout.UnclosedException {
    assertEquals(
        """
        WITH active AS (
          SELECT id,
                 name
          FROM users
          WHERE active = true
        )
        SELECT id
        FROM active
        UNION ALL
        SELECT id
        FROM archived;""",
        format(
            "WITH active AS (SELECT id, name FROM users WHERE active = true) "
                + "SELECT id FROM active UNION ALL SELECT id FROM archived;",
            POSTGRESQL));
  }

  @Test
  void severalStatementsAreSeparatedByABlankLineAndKeepComments() throws Layout.UnclosedException {
    assertEquals(
        """
        -- first query
        SELECT a, -- column a
               /* inline */ b
        FROM t1; -- done

        -- second query
        SELECT c
        FROM t2;""",
        format(
            "-- first query\nSELECT a, -- column a\n/* inline */ b FROM t1; -- done\n"
                + "-- second query\nSELECT c FROM t2;",
            POSTGRESQL));
  }

  @Test
  void preservesCaseAndFormattingOfLiteralsOperatorsAndCasts() throws Layout.UnclosedException {
    assertEquals(
        """
        select id::text,
               -1,
               1.5e-3,
               meta ->> 'key'
        from items
        where a <> b
          and c != d
          and e <= -5;""",
        format(
            "select id::text, -1, 1.5e-3, meta->>'key' from items "
                + "where a<>b and c!=d and e<=-5;",
            POSTGRESQL));
  }

  @Test
  void operatorsWrittenTogetherStayTogetherButASignComesApart() throws Layout.UnclosedException {
    assertEquals("""
        SELECT a <-> b,
               @-@ c,
               d |&> e,
               f * -1,
               g = +2
        FROM t;""", format("SELECT a<->b, @-@ c, d|&>e, f*-1, g=+2 FROM t;", POSTGRESQL));
    assertEquals(
        "SELECT 1 OPERATOR(public.#++) 2;", format("SELECT 1 OPERATOR(public.#++) 2;", POSTGRESQL));
  }

  @Test
  void whiteSpaceTheServerReadsStaysAsWritten() throws Layout.UnclosedException {
    assertEquals("SELECT 'foo'\n'bar';", format("SELECT 'foo'\n'bar';", POSTGRESQL));
    assertEquals(
        "SELECT U&'12',\n       U&\"x\"\nFROM t;",
        format("SELECT U&'12', U&\"x\" FROM t;", POSTGRESQL));
    assertEquals("CREATE TABLE count (i INT);", format("CREATE TABLE count (i INT);", MYSQL));
    assertEquals("SELECT .5;", format("SELECT .5;", MYSQL));
    assertEquals("SELECT @a. AS result;", format("SELECT @a. AS result;", MYSQL));
    assertEquals("SELECT 1 AS [gross  total];", format("SELECT 1 AS [gross  total];", MARIADB));
    assertEquals("SELECT 1 AS [gross , net];", format("SELECT 1 AS [gross , net];", MARIADB));
    assertEquals(
        "SELECT 1 AS [gross  /* x */  net];",
        format("SELECT 1 AS [gross  /* x */  net];", MARIADB));
    assertEquals("SELECT \\N,\n       1;", format("SELECT \\N, 1;", MARIADB));
    // An escape string's continuation keeps its escapes: no code in it to lay out.
    var continued = "SELECT 'C:\\',\n       E'a'\n'b--\\'\nFROM c  +   d'; -- '";
    assertEquals(continued, format(continued, POSTGRESQL));
    assertEquals("SET @'my-var' = 7;", format("SET @'my-var'=7;", MARIADB));
    // Read without backslash escapes, a layout would change the second string: left as it is.
    var either = "SELECT 'x\\', 'a=b' /* ' */";
    assertEquals(either, Layout.of(either, MYSQL));
    // Unclosed by default, but closed where the server takes no backslash escapes: left as it is.
    var path = "SELECT 'C:\\tmp\\' AS path, id FROM files;";
    assertEquals(path, Layout.of(path, MYSQL));
  }

  @Test
  void keywordsAsNamesAndAssignmentsToThem() throws Layout.UnclosedException {
    assertEquals(
        "SELECT 1 AS limit,\n       2 AS total;",
        format("SELECT 1 AS limit, 2 AS total;", POSTGRESQL));
    assertEquals(
        "UPDATE t\nSET data = 1,\n    title = 'x'\nWHERE id = 1",
        format("UPDATE t SET data=1, title='x' WHERE id=1", POSTGRESQL));
    assertEquals("CREATE VIEW v AS\nSELECT 1;", format("CREATE VIEW v AS SELECT 1;", POSTGRESQL));
  }

  @Test
  void longRunsAndDeepNestingStayBounded() throws Layout.UnclosedException {
    Layout.of("SELECT * FROM a " + "NATURAL ".repeat(10_000) + "JOIN b", POSTGRESQL);
    var deep = Layout.of("SELECT " + "(SELECT ".repeat(2_000) + "1" + ")".repeat(2_000), MYSQL);
    assertTrue(deep.lines().allMatch(line -> line.length() < 200), deep.substring(0, 400));
    var comments = "SELECT\n" + "/* x */\n".repeat(80_000) + "1;";
    assertTimeout(Duration.ofSeconds(2), () -> Layout.of(comments, POSTGRESQL));
  }

  @Test
  void postgresDollarQuotesNestedCommentsAndQuotedNames() throws Layout.UnclosedException {
    assertEquals(
        """
        SELECT $$a, b FROM c$$,
               $fn$WHERE x = 1$fn$,
               E'a\\'b',
               "select"
        FROM "from" /* outer /* inner */ */
        WHERE "where" = 1;""",
        format(
            "SELECT $$a, b FROM c$$, $fn$WHERE x = 1$fn$, E'a\\'b', \"select\" "
                + "FROM \"from\" /* outer /* inner */ */ WHERE \"where\" = 1;",
            POSTGRESQL));
  }

  @Test
  void mysqlAndMariaDbBackticksHashCommentsAndDoubleQuotedStrings()
      throws Layout.UnclosedException {
    for (var provider : List.of(MYSQL, MARIADB)) {
      assertEquals(
          """
          # fetch rows
          SELECT `select`, # first
                 `from`,
                 "a""b"
          FROM `where`
          WHERE `and` = 1
            AND x = 1 - -1;""",
          format(
              "# fetch rows\nSELECT `select`, # first\n`from`, \"a\"\"b\" "
                  + "FROM `where` WHERE `and`=1 AND x=1--1;",
              provider),
          provider.name());
    }
  }

  @Test
  void refusesUnclosedStringsQuotedNamesAndBlockComments() {
    var string =
        assertThrows(Layout.UnclosedException.class, () -> Layout.of("SELECT 'oops", POSTGRESQL));
    assertEquals(7, string.start());
    assertEquals(12, string.end());
    assertEquals(Piece.Kind.STRING, string.kind());

    var dollar = assertThrows(
        Layout.UnclosedException.class, () -> Layout.of("SELECT $fn$open", POSTGRESQL));
    assertEquals(7, dollar.start());
    assertEquals(Piece.Kind.STRING, dollar.kind());

    var quoted =
        assertThrows(Layout.UnclosedException.class, () -> Layout.of("SELECT `open", MYSQL));
    assertEquals(7, quoted.start());
    assertEquals(Piece.Kind.QUOTED, quoted.kind());

    var block = assertThrows(
        Layout.UnclosedException.class, () -> Layout.of("SELECT 1 /* open", POSTGRESQL));
    assertEquals(9, block.start());
    assertEquals(Piece.Kind.COMMENT, block.kind());
  }

  @Test
  void blankAndCommentOnlyTexts() throws Layout.UnclosedException {
    assertEquals("", Layout.of("", POSTGRESQL));
    assertEquals("", Layout.of("  \n\t ", POSTGRESQL));
    assertEquals("-- only a comment", format("-- only a comment", POSTGRESQL));
  }
}

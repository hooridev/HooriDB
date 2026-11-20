package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class EveryRowTest {

  private static void every(Provider provider, String sql, String verb, String table) {
    assertEquals(Optional.of(new EveryRow(verb, table, true)), EveryRow.of(sql, provider), sql);
  }

  /** It asks, as the text cannot tell whether a WHERE bounds it. */
  private static void unsure(Provider provider, String sql, String verb, String table) {
    assertEquals(Optional.of(new EveryRow(verb, table, false)), EveryRow.of(sql, provider), sql);
  }

  private static void bounded(Provider provider, String sql) {
    assertEquals(Optional.empty(), EveryRow.of(sql, provider), sql);
  }

  @Test
  void anUpdateOrDeleteWithoutWhereChangesEveryRow() {
    for (var provider : Provider.SQL) {
      every(provider, "UPDATE orders SET total = 0", "UPDATE", "orders");
      every(provider, "delete from shop.orders", "DELETE", "shop.orders");
      every(provider, "/* first */ UPDATE orders SET total = 0", "UPDATE", "orders");
      bounded(provider, "update orders set total = 0 where id = 1");
      bounded(provider, "DELETE FROM orders\nWHERE id IN (SELECT id FROM old)");
    }
  }

  @Test
  void aWhereInACommentStringOrQuotedNameDoesNotCount() {
    every(POSTGRESQL, "DELETE FROM orders -- WHERE id = 1", "DELETE", "orders");
    every(MYSQL, "DELETE FROM orders # WHERE id = 1", "DELETE", "orders");
    every(POSTGRESQL, "DELETE FROM orders /* WHERE id = 1 */", "DELETE", "orders");
    every(POSTGRESQL, "UPDATE orders SET note = 'WHERE x'", "UPDATE", "orders");
    every(POSTGRESQL, "UPDATE orders SET note = $$WHERE x$$", "UPDATE", "orders");
    every(POSTGRESQL, "UPDATE orders SET \"where\" = 1", "UPDATE", "orders");
    every(MARIADB, "UPDATE orders SET `where` = 1", "UPDATE", "orders");
    every(POSTGRESQL, "UPDATE orders SET where_to = 1", "UPDATE", "orders");
    every(POSTGRESQL, "DELETE FROM \"order lines\"", "DELETE", "\"order lines\"");
    every(MYSQL, "DELETE FROM `shop`.`order lines`", "DELETE", "`shop`.`order lines`");
  }

  @Test
  void aWhereInASubqueryDoesNotCount() {
    every(
        POSTGRESQL,
        "UPDATE orders SET total = (SELECT max(t) FROM other WHERE other.id = orders.id)",
        "UPDATE",
        "orders");
    every(
        POSTGRESQL,
        "WITH old AS (SELECT id FROM orders WHERE paid) DELETE FROM orders",
        "DELETE",
        "orders");
    bounded(
        POSTGRESQL, "WITH old AS (SELECT 1) DELETE FROM orders WHERE id IN (SELECT * FROM old)");
  }

  @Test
  void postgresqlsFormsOfJoinAndCursor() {
    every(POSTGRESQL, "UPDATE orders SET x = other.y FROM other", "UPDATE", "orders");
    every(POSTGRESQL, "DELETE FROM ONLY orders USING other", "DELETE", "orders");
    every(POSTGRESQL, "DELETE FROM orders RETURNING *", "DELETE", "orders");
    bounded(POSTGRESQL, "UPDATE orders SET x = other.y FROM other WHERE other.id = orders.id");
    bounded(POSTGRESQL, "DELETE FROM orders WHERE CURRENT OF c");
  }

  @Test
  void mariaDbsAndMySqlsMultiTableFormsAndLimit() {
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      every(provider, "UPDATE a JOIN b ON a.id = b.id SET a.x = b.x", "UPDATE", "a");
      every(provider, "UPDATE LOW_PRIORITY IGNORE a, b SET a.x = b.x", "UPDATE", "a");
      bounded(provider, "UPDATE a, b SET a.x = b.x WHERE a.id = b.id");
      every(provider, "DELETE a, b FROM a JOIN b ON a.id = b.id", "DELETE", "a");
      every(provider, "DELETE QUICK FROM a USING a JOIN b ON a.id = b.id", "DELETE", "a");
      bounded(provider, "DELETE FROM a USING a JOIN b ON a.id = b.id WHERE b.gone");
      bounded(provider, "DELETE FROM orders ORDER BY id LIMIT 100");
      bounded(provider, "UPDATE orders SET x = 1 LIMIT 10");
    }
  }

  @Test
  void aVariableOrAQualifiedNameIsNoClause() {
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      every(provider, "UPDATE orders SET paid = @where", "UPDATE", "orders");
      every(provider, "UPDATE orders SET paid = @limit", "UPDATE", "orders");
      every(provider, "UPDATE orders SET paid = @@where", "UPDATE", "orders");
      every(provider, "UPDATE orders SET paid = @`where`", "UPDATE", "orders");
      every(provider, "UPDATE orders SET paid = @'limit'", "UPDATE", "orders");
      every(provider, "UPDATE orders o SET o.paid = o.limit", "UPDATE", "orders");
      bounded(provider, "UPDATE orders SET paid = @x WHERE id = @where");
      bounded(provider, "UPDATE orders SET paid = @x LIMIT 1");
    }
    for (var provider : Provider.SQL) {
      every(provider, "UPDATE orders o SET paid = o.where", "UPDATE", "orders");
      every(provider, "UPDATE orders o SET paid = o . where", "UPDATE", "orders");
      every(provider, "DELETE FROM orders USING orders, shop.where", "DELETE", "orders");
    }
  }

  @Test
  void executableCommentsAreCode() {
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      every(provider, "/*! DELETE FROM orders */", "DELETE", "orders");
      every(provider, "/*!50700 DELETE FROM orders */", "DELETE", "orders");
      every(provider, "/*!DELETE*/ FROM orders", "DELETE", "orders");
      every(provider, "DELETE /*! FROM orders */", "DELETE", "orders");
      // Bounded only where the server runs the comment, which an older one does not.
      unsure(provider, "DELETE FROM orders /*! WHERE id = 1 */", "DELETE", "orders");
      unsure(provider, "DELETE FROM orders /*!80000 LIMIT 1 */", "DELETE", "orders");
      // The depth goes on across a comment: as code, its WHERE is the subquery's.
      unsure(
          provider,
          "UPDATE t SET x = /*! ( */ SELECT 7 FROM (SELECT 1 AS k) u WHERE k = 1 /*! ) */",
          "UPDATE",
          "t");
      bounded(provider, "/*! DELETE FROM orders WHERE id = 1 */ ");
      bounded(provider, "DELETE FROM orders WHERE id = 1 /*! AND 1 */");
      assertEquals(
          OptionalLong.empty(), Statements.limit("SELECT * FROM t /*! LIMIT 3 */", provider));
    }
    every(MARIADB, "/*M!100500 DELETE FROM orders */", "DELETE", "orders");
    // MySQL takes it for a comment, as PostgreSQL takes every one.
    bounded(MYSQL, "/*M! DELETE FROM orders */");
    bounded(POSTGRESQL, "/*! DELETE FROM orders */");
  }

  @Test
  void aWithIsReadByItsShape() {
    every(POSTGRESQL, "WITH replace AS (SELECT 1) DELETE FROM orders", "DELETE", "orders");
    every(POSTGRESQL, "WITH update AS (SELECT 1) DELETE FROM orders", "DELETE", "orders");
    every(
        POSTGRESQL,
        "WITH RECURSIVE a (n) AS (SELECT 1), \"delete\" AS NOT MATERIALIZED (SELECT 2)"
            + " UPDATE orders SET x = 1",
        "UPDATE",
        "orders");
    // SEARCH and CYCLE are a shape it does not read: it asks.
    unsure(
        POSTGRESQL,
        "WITH RECURSIVE t (id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM t)"
            + " CYCLE id SET seen USING path DELETE FROM orders",
        "DELETE",
        "orders");
    unsure(
        POSTGRESQL,
        "WITH RECURSIVE r (n) AS (SELECT 1) SEARCH DEPTH FIRST BY n SET replace DELETE FROM t",
        "DELETE",
        "t");
    unsure(
        POSTGRESQL,
        "WITH RECURSIVE r (n) AS (SELECT 1) SEARCH DEPTH FIRST BY n SET o DELETE FROM t WHERE x",
        "DELETE",
        "t");
    bounded(POSTGRESQL, "WITH replace AS (SELECT 1) SELECT * FROM replace");
    bounded(POSTGRESQL, "WITH delete_me AS (SELECT 1) DELETE FROM orders WHERE id = 1");
    // Of no shape it can read: an UPDATE at the top level may be the statement.
    unsure(POSTGRESQL, "WITH broken UPDATE orders SET x = 1", "UPDATE", "orders");
  }

  /** An unquoted name that begins with WHERE or LIMIT is one name, whatever follows in it. */
  @Test
  void aNameThatBeginsWithAKeywordIsOneName() {
    for (var provider : Provider.SQL) {
      every(provider, "UPDATE accounts SET where\u20ac = 5000", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET whereß = 1", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET where\u0300 = 1", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET x = where\u20ac", "UPDATE", "accounts");
      every(provider, "DELETE FROM where\u0301s", "DELETE", "where\u0301s");
      every(provider, "UPDATE accounts SET x = 1 WHERE\u00a0", "UPDATE", "accounts");
      bounded(provider, "UPDATE accounts SET where\u20ac = 5000 WHERE id = 1");
    }
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      every(provider, "UPDATE accounts SET limit\u20ac = 5000", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET limit\u0300 = 5000", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET x = limitß", "UPDATE", "accounts");
      bounded(provider, "UPDATE accounts SET limit\u20ac = 5000 LIMIT 1");
    }
  }

  /**
   * Keywords fold by ASCII's letters alone, as the servers fold them: a Turkish İ or ı, which Java's
   * case folding takes for an I, makes a name. WHERE, UPDATE and DELETE hold no I; LIMIT and WITH
   * do.
   */
  @Test
  void aKeywordIsSpelledInAsciiLetters() {
    for (var provider : Provider.SQL) {
      every(provider, "UPDATE accounts SET L\u0130M\u0130T = 5000", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET l\u0131m\u0131t = 5000", "UPDATE", "accounts");
      bounded(provider, "update accounts set x = 1 where id = 1");
    }
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      every(provider, "UPDATE accounts SET LIM\u0130T = 5000", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET x = l\u0131mit", "UPDATE", "accounts");
      every(provider, "UPDATE accounts SET x = 1 /*! L\u0130MIT 1 */", "UPDATE", "accounts");
      bounded(provider, "UPDATE accounts SET x = 1 limit 1");
      assertEquals(
          OptionalLong.empty(), Statements.limit("SELECT * FROM t L\u0130MIT 3", provider));
    }
    // A WITH spelled so is no WITH, and what follows it no statement of one.
    bounded(POSTGRESQL, "W\u0130TH x AS (SELECT 1) DELETE FROM t");
    // Nor is DELIMITER spelled so the client's command.
    assertEquals(
        2, Statements.split("DEL\u0130MITER //\nSELECT 1; SELECT 2", MYSQL).size());
  }

  /** A WHERE after RETURNING names a column. */
  @Test
  void aWhereAfterReturningIsNone() {
    every(POSTGRESQL, "UPDATE t SET x = 10 RETURNING 1 AS where", "UPDATE", "t");
    every(POSTGRESQL, "DELETE FROM t RETURNING id AS where, 2 AS limit", "DELETE", "t");
    every(MARIADB, "DELETE FROM t RETURNING id AS `where`, 1 AS limit", "DELETE", "t");
    bounded(POSTGRESQL, "UPDATE t SET x = 10 WHERE id = 1 RETURNING 1 AS where");
  }

  /** A backslash read as an escape and as not: where the readings differ, it asks. */
  @Test
  void aStringEndsWhereEitherReadingSays() {
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      // NO_BACKSLASH_ESCAPES ends the first string at its backslash: the WHERE is in a string.
      unsure(provider, "UPDATE t SET note = 'a\\', x = length(' WHERE ')", "UPDATE", "t");
      unsure(provider, "UPDATE t SET note = \"a\\\", x = length(\" WHERE \")", "UPDATE", "t");
      unsure(provider, "UPDATE t SET note = 'a\\'' WHERE id = 1", "UPDATE", "t");
      bounded(provider, "UPDATE t SET note = 'it''s' WHERE id = 1");
    }
    // PostgreSQL with standard_conforming_strings off reads one so too.
    unsure(POSTGRESQL, "UPDATE t SET note = 'a\\' WHERE id = 1 --'", "UPDATE", "t");
    bounded(POSTGRESQL, "UPDATE t SET note = E'a\\'' WHERE id = 1");
  }

  @Test
  void theLimitOfAStatementIsItsOwnAtItsTopLevel() {
    for (var provider : new Provider[] {MARIADB, MYSQL}) {
      assertEquals(OptionalLong.of(10), Statements.limit("SELECT * FROM t LIMIT 10", provider));
      assertEquals(OptionalLong.of(10), Statements.limit("SELECT * FROM t LIMIT 5, 10", provider));
      assertEquals(
          OptionalLong.of(10), Statements.limit("select * from t limit 10 offset 5", provider));
      assertEquals(
          OptionalLong.empty(),
          Statements.limit("SELECT * FROM t WHERE id IN (SELECT id FROM u LIMIT 3)", provider));
      assertEquals(OptionalLong.empty(), Statements.limit("SELECT 'LIMIT 3' FROM t", provider));
      assertEquals(OptionalLong.empty(), Statements.limit("SELECT * FROM t -- LIMIT 3", provider));
      assertEquals(
          OptionalLong.empty(),
          Statements.limit("SELECT * FROM t LIMIT ROWS EXAMINED 9", provider));
    }
  }

  @Test
  void otherStatementsThatSayUpdateOrDeleteAreNone() {
    bounded(POSTGRESQL, "INSERT INTO t VALUES (1) ON CONFLICT (id) DO UPDATE SET x = 1");
    bounded(MYSQL, "INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE x = 1");
    bounded(POSTGRESQL, "SELECT * FROM t FOR UPDATE");
    bounded(POSTGRESQL, "GRANT UPDATE, DELETE ON t TO u");
    bounded(POSTGRESQL, "EXPLAIN UPDATE t SET x = 1");
    bounded(MARIADB, "CREATE TRIGGER tr BEFORE DELETE ON t FOR EACH ROW SET @n = 1");
    bounded(POSTGRESQL, "WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone");
    bounded(POSTGRESQL, "(SELECT 1)");
    bounded(POSTGRESQL, "");
  }
}

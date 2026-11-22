package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MONGODB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditableQueryTest {

  private static void allowed(Provider provider, String sql) {
    subqueries(provider, sql);
  }

  private static void subqueries(Provider provider, String sql, Integer... items) {
    assertEquals(
        Optional.of(Set.of(items)),
        EditableQuery.singleTable(sql, provider).map(EditableQuery.SelectList::subqueries),
        provider + ": " + sql);
  }

  private static void refused(Provider provider, String sql) {
    assertEquals(Optional.empty(), EditableQuery.singleTable(sql, provider), provider + ": " + sql);
  }

  private static void temp(Provider provider, String sql) {
    assertTrue(EditableQuery.createsTemporaryTable(sql, provider), provider + ": " + sql);
  }

  private static void notTemp(Provider provider, String sql) {
    assertFalse(EditableQuery.createsTemporaryTable(sql, provider), provider + ": " + sql);
  }

  @Test
  void singleTableSelectsWithWhitelistedClausesPass() {
    for (var p : Provider.SQL) {
      allowed(p, "SELECT * FROM customer");
      allowed(p, "select id, name as n from customer;");
      allowed(p, "SELECT id, name FROM public.customer c WHERE c.active = true");
      allowed(p, "SELECT id, name FROM db.public.customer AS c ORDER BY c.id LIMIT 10");
      allowed(p, "SELECT DISTINCT id, name FROM customer GROUP BY id, name HAVING count(*) > 0");
      allowed(p, "SELECT id, name FROM customer WINDOW w AS (ORDER BY id) ORDER BY id OFFSET 5");
      allowed(p, "SELECT id, name FROM customer FETCH FIRST 10 ROWS ONLY");
      allowed(p, "SELECT id, name IS NOT DISTINCT FROM 'x' AS same FROM customer");
      allowed(p, "SELECT id, name IS DISTINCT FROM 'x' FROM customer WHERE id > 0");
      allowed(p, "SELECT id, name FROM customer WHERE id IN (SELECT customer_id FROM purchase)");
      allowed(p, "SELECT id, SUBSTRING(name FROM 1 FOR 3), EXTRACT(YEAR FROM born) FROM customer");
      allowed(p, "/* leading */ SELECT id FROM customer -- trailing\n;");
    }
    allowed(POSTGRESQL, "SELECT DISTINCT ON (id) id, name FROM customer ORDER BY id");
    allowed(MARIADB, "SELECT /*!40001 SQL_NO_CACHE */ id, name FROM customer");
    allowed(MYSQL, "SELECT /*!40001 SQL_NO_CACHE */ id, name FROM customer");
  }

  @Test
  void subqueriesInTheSelectListAreFoundByTheirItems() {
    for (var p : Provider.SQL) {
      subqueries(
          p,
          "SELECT id, (SELECT count(*) FROM purchase WHERE customer_id = c.id) FROM customer c",
          1);
      subqueries(
          p, "SELECT (SELECT c.id) AS id, name, coalesce((SELECT 1), 0) FROM customer c", 0, 2);
      subqueries(p, "SELECT DISTINCT id, name, (SELECT max(x) FROM t) FROM customer", 2);
      subqueries(p, "SELECT *, (SELECT 1) FROM customer", 1);
      subqueries(p, "SELECT id, id * 2, (SELECT 1), name FROM customer", 2);
      subqueries(p, "SELECT c.*, (SELECT 1) FROM customer c", 1);
      assertEquals(
          3,
          EditableQuery.singleTable("SELECT id * '2', (SELECT 1), name FROM customer", p)
              .orElseThrow()
              .items());
    }
    subqueries(POSTGRESQL, "SELECT ARRAY[1,2], (SELECT 1), id, name FROM customer", 1);
    for (var p : new Provider[] {MARIADB, MYSQL}) {
      refused(p, "SELECT /*!99999 0, */ /*! 1, */ (SELECT c.id) AS k, 2, name FROM customer c");
      refused(p, "SELECT /*!99999 0 */ /*! , */ (SELECT c.id) AS k, name FROM customer c");
    }
  }

  @Test
  void anAliasSpelledAsAClauseTakesNoJoin() {
    for (var p : Provider.SQL) {
      refused(p, "SELECT c.id FROM customer c JOIN offset ON offset.id = c.id");
      refused(p, "SELECT c.id FROM customer STRAIGHT_JOIN offset");
      refused(p, "SELECT offset.id FROM customer offset JOIN purchase p ON p.customer_id = 1");
      refused(p, "SELECT offset.id FROM customer offset, purchase p");
      refused(p, "SELECT window.id FROM customer window NATURAL JOIN purchase");
    }
  }

  @Test
  void nonSelectsMultiStatementsAndMissingFromRefuse() {
    for (var p : Provider.SQL) {
      refused(p, "");
      refused(p, "   -- only comment");
      refused(p, "SELECT 1");
      refused(p, "SELECT 1 + 1 AS two");
      refused(p, "(SELECT id FROM customer)");
      refused(p, "UPDATE customer SET name = 'a' WHERE id = 1");
      refused(p, "DELETE FROM customer WHERE id = 1");
      refused(p, "INSERT INTO customer (id, name) VALUES (1, 'a')");
      refused(p, "TABLE customer");
      refused(p, "VALUES (1, 'a')");
      refused(p, "SELECT id FROM customer; SELECT 1");
      refused(p, "SELECT id FROM");
      refused(p, "SELECT id FROM customer AS");
      refused(p, "SELECT id FROM customer AS c extra");
      refused(p, "SELECT id FROM customer c (a, b)");
    }
    refused(MONGODB, "SELECT id FROM customer");
  }

  @Test
  void withSubqueriesInFromJoinsAndUnknownFromModifiersRefuse() {
    for (var p : Provider.SQL) {
      refused(p, "WITH x AS (SELECT id, name FROM customer) SELECT id, name FROM x");
      refused(p, "WITH RECURSIVE x AS (SELECT 1) SELECT * FROM customer");
      refused(p, "SELECT id, name FROM (SELECT id, name FROM customer) s");
      refused(p, "SELECT id, name FROM (SELECT id, name FROM customer) AS customer");
      refused(p, "SELECT c.id, c.name FROM customer c, purchase p WHERE c.id = p.customer_id");
      refused(p, "SELECT id, name FROM customer, purchase");
      refused(p, "SELECT c.id, c.name FROM customer c JOIN purchase p ON c.id = p.customer_id");
      refused(p, "SELECT c.id FROM customer c LEFT JOIN purchase p ON c.id = p.customer_id");
      refused(p, "SELECT c.id FROM customer c RIGHT OUTER JOIN purchase p ON c.id = p.customer_id");
      refused(p, "SELECT c.id FROM customer c INNER JOIN purchase p ON c.id = p.customer_id");
      refused(p, "SELECT c.id FROM customer c CROSS JOIN purchase p");
      refused(p, "SELECT c.id FROM customer c NATURAL JOIN purchase p");
      refused(p, "SELECT a.id FROM customer a JOIN customer b ON a.id = b.id");
      refused(p, "SELECT id FROM customer JOIN purchase ON customer.id = purchase.customer_id");
      refused(p, "SELECT c.id FROM customer c, LATERAL (SELECT 1) l");
      refused(p, "SELECT id FROM LATERAL (SELECT 1) l");
      refused(p, "SELECT id FROM generate_series(1, 5)");
    }
  }

  @Test
  void modifiersOfTheTableRefuse() {
    for (var p : Provider.SQL) {
      allowed(p, "SELECT id FROM ONLY customer");
      refused(p, "SELECT id, name FROM customer GROUP BY id, name WITH ROLLUP");
      refused(p, "SELECT id, name FROM customer GROUP BY ROLLUP (id, name)");
      refused(p, "SELECT id FROM customer PARTITION (p0)");
      refused(p, "SELECT id FROM customer USE INDEX (PRIMARY)");
      refused(p, "SELECT id FROM customer c FORCE INDEX (PRIMARY)");
      refused(p, "SELECT id FROM customer IGNORE INDEX (PRIMARY)");
      refused(p, "SELECT id FROM customer TABLESAMPLE BERNOULLI (10)");
      refused(p, "SELECT id FROM customer STRAIGHT_JOIN purchase");
      refused(p, "SELECT id FROM customer FOR SYSTEM_TIME ALL");
    }
  }

  @Test
  void setOperationsIntoAndLockingClausesRefuseAtAnyDepth() {
    for (var p : Provider.SQL) {
      refused(p, "SELECT id, name FROM customer UNION SELECT id, name FROM customer");
      refused(p, "SELECT id, name FROM customer UNION ALL SELECT id, name FROM customer");
      refused(p, "SELECT id, name FROM customer INTERSECT SELECT id, name FROM customer");
      refused(p, "SELECT id, name FROM customer EXCEPT SELECT id, name FROM customer");
      refused(
          p,
          "SELECT id FROM customer WHERE id IN (SELECT id FROM customer UNION SELECT customer_id"
              + " FROM purchase)");
      refused(p, "SELECT id, name INTO tmp FROM customer");
      refused(p, "SELECT id, name FROM customer INTO OUTFILE '/tmp/out'");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 INTO @a, @b");
      refused(p, "SELECT id, name FROM customer FOR UPDATE");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 FOR UPDATE NOWAIT");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 FOR SHARE");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 FOR NO KEY UPDATE");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 FOR KEY SHARE");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 LOCK IN SHARE MODE");
      refused(
          p, "SELECT id FROM customer WHERE id IN (SELECT customer_id FROM purchase FOR UPDATE)");
      refused(p, "SELECT id, name FROM customer WHERE id = 1 FOR /* comment */ UPDATE");
    }
  }

  @Test
  void commentsStringsQuotedNamesQualifiedNamesAndCaseAreHandled() {
    for (var p : Provider.SQL) {
      allowed(p, "SELECT id, name FROM customer WHERE name = 'UNION INTO FOR UPDATE'");
      allowed(p, "SELECT id, name FROM customer -- UNION FOR UPDATE\nWHERE id = 1");
      allowed(p, "SELECT id, name FROM customer /* , purchase FOR UPDATE */ WHERE id = 1");
      allowed(p, "SELECT c.into, c.union, c.for, c.from FROM customer c WHERE c.for = 1");
      char q = p.dialect().syntax().identifierQuote();
      allowed(
          p,
          "SELECT " + q + "union" + q + ", " + q + "for" + q + " FROM " + q + "from" + q + " AS "
              + q + "where" + q + " WHERE " + q + "into" + q + " = 1");
      // Turkish dotted/dotless I does not fold to ASCII I.
      refused(p, "SELECT id FROM customer W\u0130TH x");
      refused(p, "SELECT id FROM customer L\u0130MIT 5");
      refused(p, "SELECT id FROM customer UN\u0130ON SELECT 1");
    }
    allowed(POSTGRESQL, "SELECT id FROM customer WHERE name = $$UNION FOR UPDATE$$");
    allowed(POSTGRESQL, "SELECT id FROM customer WHERE name = E'a\\'' /* nested /* */ */");
    allowed(MYSQL, "SELECT id FROM customer # , purchase FOR UPDATE\nWHERE id = 1");
    // Ambiguous backslash escapes in strings must pass under both readings.
    refused(MYSQL, "SELECT 'a\\' FROM customer, purchase -- ', id FROM customer");
    refused(MARIADB, "SELECT 'a\\' UNION SELECT 1 -- ', id FROM customer");
    refused(POSTGRESQL, "SELECT 'a\\' UNION SELECT 1 -- ', id FROM customer");
    allowed(POSTGRESQL, "SELECT E'a\\'' , id FROM customer");
  }

  @Test
  void executableCommentsMustPassBothAsCodeAndAsComment() {
    for (var p : new Provider[] {MARIADB, MYSQL}) {
      refused(p, "SELECT id, name FROM customer /*! , purchase */");
      refused(p, "SELECT id, name FROM customer /*!50000 FOR UPDATE */");
      refused(p, "SELECT id, name FROM customer /*! UNION SELECT 1, 'a' */");
      refused(p, "SELECT id, name FROM customer /*! ; SELECT 1 */");
      refused(p, "SELECT id, name /*! FROM customer */");
      allowed(p, "SELECT id, name FROM customer /*! WHERE id = 1 */");
    }
    refused(MARIADB, "SELECT id, name FROM customer /*M!100500 FOR UPDATE */");
    allowed(MYSQL, "SELECT id, name FROM customer /*M! FOR UPDATE */");
    allowed(POSTGRESQL, "SELECT id, name FROM customer /*! FOR UPDATE */");
  }

  @Test
  void createsTemporaryTableDetectsEveryFormAndReading() {
    for (var p : Provider.SQL) {
      temp(p, "CREATE TEMPORARY TABLE customer (id int)");
      temp(p, "create temporary table if not exists customer (id int)");
      temp(p, "CREATE /* c */ TEMPORARY TABLE customer AS SELECT 1");
      temp(p, "CREATE TEMP TABLE customer (id int)");
      temp(p, "CREATE LOCAL TEMPORARY TABLE customer (id int)");
      temp(p, "CREATE GLOBAL TEMP TABLE customer (id int)");
      temp(p, "CREATE OR REPLACE TEMPORARY TABLE customer (id int)");
      temp(p, "SELECT 1; CREATE TEMPORARY TABLE customer (id int)");
      temp(p, "CALL make_tables()");
      temp(p, "EXECUTE stmt");
      notTemp(p, "CREATE TABLE customer (id int)");
      notTemp(p, "DROP TEMPORARY TABLE customer");
      notTemp(p, "CREATE TEMPORARY SEQUENCE s");
      notTemp(p, "SELECT 'CREATE TEMPORARY TABLE customer (id int)'");
      notTemp(p, "SELECT 1 -- CREATE TEMPORARY TABLE customer (id int)");
      notTemp(p, "SELECT 1 /* CREATE TEMPORARY TABLE customer (id int) */");
      char q = p.dialect().syntax().identifierQuote();
      notTemp(p, "CREATE " + q + "temporary" + q + " TABLE customer (id int)");
    }
    for (var p : new Provider[] {MARIADB, MYSQL}) {
      temp(p, "/*! CREATE TEMPORARY TABLE customer (id int) */");
      temp(p, "/*!50000 CREATE TEMPORARY TABLE customer (id int) */");
      temp(p, "CREATE /*! TEMPORARY */ TABLE customer (id int)");
      temp(p, "CREATE /*!50000 OR REPLACE */ TEMPORARY TABLE customer (id int)");
    }
    temp(MARIADB, "/*M!100500 CREATE OR REPLACE TEMPORARY TABLE customer (id int) */");
    notTemp(MYSQL, "/*M! CREATE TEMPORARY TABLE customer (id int) */");
    notTemp(POSTGRESQL, "/*! CREATE TEMPORARY TABLE customer (id int) */");
    notTemp(MONGODB, "CREATE TEMPORARY TABLE customer (id int)");
  }
}

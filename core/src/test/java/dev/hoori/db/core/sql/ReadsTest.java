package dev.hoori.db.core.sql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadsTest {

  private static void read(Provider provider, String sql) {
    assertTrue(Reads.plain(sql, provider), provider + ": " + sql);
  }

  private static void write(Provider provider, String sql) {
    assertFalse(Reads.plain(sql, provider), provider + ": " + sql);
  }

  @Test
  void plainReadsAndWithSelectsAreTakenForReads() {
    for (var p : Provider.SQL) {
      var q = p.dialect().syntax().identifierQuote();
      for (var sql : List.of(
          "SELECT * FROM orders",
          "select 1",
          "/* note */ SELECT 1",
          "(SELECT 1)",
          "((SELECT 1))",
          "(SELECT 1) UNION (SELECT 2)",
          "SHOW search_path",
          "VALUES (1)",
          "TABLE orders",
          "SELECT 1;",
          "SELECT 'into'",
          "SELECT 1 -- into",
          "SELECT /* into */ 1",
          "SELECT " + q + "into" + q + " FROM orders",
          "SELECT now()::timestamp with time zone",
          "WITH x AS (SELECT 1) SELECT * FROM x",
          "with x as (select 1) select 1",
          "WITH RECURSIVE x(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM x WHERE n < 5) SELECT * FROM"
              + " x",
          "WITH a AS (SELECT 1), b AS NOT MATERIALIZED (VALUES (2)), c AS MATERIALIZED (TABLE"
              + " orders) SELECT * FROM a, b, c",
          "WITH a AS (WITH b AS (SELECT 1) SELECT * FROM b) SELECT * FROM a",
          "(WITH x AS (SELECT 1) SELECT * FROM x)",
          "WITH x AS (SELECT 1) (SELECT * FROM x)",
          "WITH x AS (SELECT 'into') SELECT 1 -- into")) {
        read(p, sql);
      }
    }
  }

  @Test
  void writesDataModifyingCtesIntoAndUnknownFormsStayWrites() {
    for (var p : Provider.SQL) {
      for (var sql : List.of(
          "UPDATE orders SET paid = true WHERE id < 4",
          "DELETE FROM orders WHERE id = 1",
          "INSERT INTO orders VALUES (1)",
          "WITH changed AS (UPDATE orders SET paid = true RETURNING id) SELECT id FROM changed",
          "WITH ins AS (INSERT INTO orders VALUES (1) RETURNING id) SELECT id FROM ins",
          "WITH a AS (SELECT 1), del AS (DELETE FROM orders RETURNING id) SELECT * FROM a, del",
          "WITH a AS (WITH b AS (UPDATE orders SET paid = true RETURNING id) SELECT * FROM b)"
              + " SELECT * FROM a",
          "WITH x AS (SELECT 1) INSERT INTO orders SELECT * FROM x",
          "WITH x AS (SELECT 1) UPDATE orders SET paid = true FROM x",
          "WITH x AS (SELECT 1) DELETE FROM orders USING x",
          "WITH x AS (SELECT 1) MERGE INTO orders USING x ON orders.id = x.id WHEN MATCHED THEN"
              + " DELETE",
          "WITH x AS (SELECT 1) SELECT * INTO copy FROM x",
          "WITH x AS (SELECT 1)",
          "WITH AS (SELECT 1) SELECT 1",
          // Reads, but SEARCH and CYCLE are not followed: taken for writes, the cautious side.
          "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 3)"
              + " SEARCH DEPTH FIRST BY n SET ord SELECT * FROM t",
          "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 3)"
              + " CYCLE n SET is_cycle USING path SELECT * FROM t",
          "SELECT * INTO copy FROM orders",
          // INTO after a decimal point; a column t.into is taken for it too, a needless caution.
          "SELECT 1. INTO copy",
          "SELECT t.into FROM t",
          "SELECT 1 INTO @x",
          "SELECT * FROM orders INTO OUTFILE '/tmp/orders.csv'",
          "TABLE orders INTO OUTFILE '/tmp/orders.csv'",
          "SELECT 'a\\' INTO @x",
          "SELECT 1; DELETE FROM orders",
          "CALL report()",
          "COMMIT",
          "ROLLBACK TO SAVEPOINT a",
          "BEGIN",
          "SET autocommit=1",
          "CREATE TABLE t (id int)",
          "EXPLAIN ANALYZE DELETE FROM orders",
          "EXPLAIN (U&\"\\0061nalyze\" true) UPDATE orders SET paid = true",
          "EXPLAIN SELECT 1",
          "DESCRIBE orders",
          "DESC orders",
          "/*! COMMIT */",
          "/*M! COMMIT */",
          "IF 1 THEN COMMIT; END IF",
          "SHUTDOWN",
          "",
          "   ",
          "-- only a comment",
          "DO $$ BEGIN COMMIT; END $$",
          "ſELECT 1",
          "wıth x as (select 1) select 1")) {
        write(p, sql);
      }
    }
    for (var p : List.of(Provider.MARIADB, Provider.MYSQL)) {
      write(p, "SELECT 1 /*! INTO @x */");
      write(p, "SELECT 1 /*! ; DELETE FROM orders */");
      write(p, "/*! SELECT 1 */");
    }
    write(Provider.MONGODB, "SELECT 1");
  }
}

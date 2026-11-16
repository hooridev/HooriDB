package dev.hoori.db.core.sql;

import static dev.hoori.db.core.connection.Provider.MARIADB;
import static dev.hoori.db.core.connection.Provider.MYSQL;
import static dev.hoori.db.core.connection.Provider.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.connection.Provider;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EveryRowTest {

  private static void every(Provider provider, String sql, String verb, String table) {
    assertEquals(Optional.of(new EveryRow(verb, table)), EveryRow.of(sql, provider), sql);
  }

  private static void bounded(Provider provider, String sql) {
    assertEquals(Optional.empty(), EveryRow.of(sql, provider), sql);
  }

  @Test
  void anUpdateOrDeleteWithoutWhereChangesEveryRow() {
    for (var provider : Provider.values()) {
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

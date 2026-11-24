package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** What needs no server: wrong arguments, and binary data that compare by their content. */
class RowsTest {

  private static final Database HOORI = new Database("hoori", false);

  @Test
  void positionsGoForwardFromZeroAndColumnsAreTheTables() {
    var table = new Table(HOORI, "t", Table.Kind.TABLE);
    var other = new Table(HOORI, "u", Table.Kind.TABLE);
    var none = List.<Column>of();
    var others = List.of(new Column(other, "id", "int", false, 1));
    assertThrows(
        IllegalArgumentException.class, () -> Rows.read(null, Provider.MYSQL, table, none, -1, 10));
    assertThrows(
        IllegalArgumentException.class, () -> Rows.read(null, Provider.MYSQL, table, none, 10, 9));
    assertThrows(
        IllegalArgumentException.class,
        () -> Rows.read(null, Provider.MYSQL, table, others, 0, 10));
  }

  @Test
  void aPlanSaysTheRowsOfItsTopOnItsFirstLine() {
    assertEquals(
        OptionalLong.of(900), Rows.planned("Seq Scan on t  (cost=0.00..19.00 rows=900 width=4)"));
    assertEquals(
        OptionalLong.of(10),
        Rows.planned("-> Limit: 10 row(s)  (cost=4900 rows=10)\n    -> Table scan on t  (rows=5)"));
    assertEquals(
        OptionalLong.of(238_000_000),
        Rows.planned("-> Inner hash join (b.v = a.v)  (cost=238e+6 rows=238e+6)"));
    assertEquals(OptionalLong.empty(), Rows.planned("-> Count rows in t\n  -> Scan (rows=9)"));
    assertEquals(OptionalLong.empty(), Rows.planned("Seq Scan on \"rows=5\"  (cost=0.00..1.00)"));
  }

  @Test
  void binaryDataCompareByContentAndCannotBeChanged() {
    var bytes = new byte[] {1, 2};
    var binary = new Binary(bytes, 5);
    bytes[0] = 9;
    binary.bytes()[1] = 9;
    assertEquals(new Binary(new byte[] {1, 2}, 5), binary);
    assertEquals(new Binary(new byte[] {1, 2}, 5).hashCode(), binary.hashCode());
    assertNotEquals(new Binary(new byte[] {1, 2}, 2), binary);
    assertEquals(true, binary.cut());
  }

  @Test
  void aFilterRefusesBadClausesBeforeTouchingTheConnection() throws QueryException {
    var table = new Table(HOORI, "t", Table.Kind.TABLE);
    var bad = new Rows.Filter("1=1; DROP TABLE x", "");
    for (var provider : Provider.SQL) {
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(
                  QueryException.class,
                  () -> Rows.read(null, provider, table, List.of(), 0, 10, bad, false))
              .failure());
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(QueryException.class, () -> Rows.count(null, provider, table, bad, false))
              .failure());
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(QueryException.class, () -> Rows.estimate(null, provider, table, bad, false))
              .failure());
    }
    var empty = new Rows.Filter(null, "  ");
    assertEquals(true, empty.empty());
    assertEquals("", empty.where());
    Rows.check(Provider.POSTGRESQL, empty, true);
    assertEquals(
        QueryException.Failure.CLAUSE_REFUSED,
        assertThrows(QueryException.class, () -> Rows.check(Provider.POSTGRESQL, bad, false))
            .failure());
  }

  @Test
  void qualifiedQuotesContainerAndTableForTheProvider() {
    var pgTable = new Table(new Schema(HOORI, "public", false), "orders", Table.Kind.TABLE);
    var mariaTable = new Table(HOORI, "items", Table.Kind.TABLE);
    assertEquals("\"public\".\"orders\"", Rows.qualified(Provider.POSTGRESQL, pgTable));
    assertEquals("`hoori`.`items`", Rows.qualified(Provider.MARIADB, mariaTable));
    assertEquals("`hoori`.`items`", Rows.qualified(Provider.MYSQL, mariaTable));
  }

  @Test
  void whereFormatsLiteralsAndQuotesColumnsPerProvider() throws QueryException {
    var date = LocalDate.of(2026, 5, 14);
    var time = LocalTime.of(13, 45, 5);
    var offsetTime = OffsetTime.of(13, 45, 5, 0, ZoneOffset.ofHours(2));
    var localDateTime = LocalDateTime.of(2026, 5, 14, 13, 45, 5);
    var binary = new Binary(new byte[] {0x0a, (byte) 0xff}, 2);

    // PostgreSQL: double-quoted identifiers, doubled single quotes, hex bytea, BC eras, AND.
    assertEquals(
        "\"name\" = 'O''Reilly; -- /*'",
        Rows.where(Provider.POSTGRESQL, List.of("name"), List.of("O'Reilly; -- /*")));
    assertEquals(
        "\"a\"\"b\" = 42 AND \"big\" = 99999999999999999999 AND \"price\" = 12.50 AND \"flag\" = TRUE",
        Rows.where(
            Provider.POSTGRESQL,
            List.of("a\"b", "big", "price", "flag"),
            List.of(42L, new BigInteger("99999999999999999999"), new BigDecimal("12.50"), true)));
    assertEquals(
        "\"d\" = '2026-05-14' AND \"bc\" = '0044-03-15 BC' AND \"t\" = '13:45:05'"
            + " AND \"ot\" = '13:45:05+02:00' AND \"dt\" = '2026-05-14 13:45:05'"
            + " AND \"raw\" = '\\x0aff'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of("d", "bc", "t", "ot", "dt", "raw"),
            List.of(date, LocalDate.of(-43, 3, 15), time, offsetTime, localDateTime, binary)));

    // MySQL and MariaDB: backtick-quoted identifiers, doubled quotes, X'...' binary.
    for (var provider : List.of(Provider.MYSQL, Provider.MARIADB)) {
      var stringWhere = Rows.where(provider, List.of("na`me"), List.of("O'Reilly; --"));
      assertEquals("`na``me` = 'O''Reilly; --'", stringWhere);
      Rows.check(provider, new Rows.Filter(stringWhere, ""), true);

      assertEquals(
          "`id` = 7 AND `ratio` = 1.5 AND `ok` = FALSE",
          Rows.where(provider, List.of("id", "ratio", "ok"), List.of(7L, 1.5d, false)));
      assertEquals(
          "`d` = '2026-05-14' AND `dt` = '2026-05-14 13:45:05'" + " AND `raw` = X'0aff'",
          Rows.where(provider, List.of("d", "dt", "raw"), List.of(date, localDateTime, binary)));
    }
    Rows.check(
        Provider.POSTGRESQL,
        new Rows.Filter(
            Rows.where(Provider.POSTGRESQL, List.of("name"), List.of("O'Reilly; DROP TABLE x")),
            ""),
        true);
  }

  @Test
  void whereRejectsInvalidArgumentsAndIncompleteValues() {
    var pg = Provider.POSTGRESQL;
    refused(pg, List.of(), List.of());
    refused(pg, List.of("a", "b"), List.of(1L));
    refused(pg, List.of(""), List.of(1L));
    refused(pg, List.of("a"), Arrays.asList((Object) null));
    refused(pg, List.of("a"), List.of(Missing.FIELD));
    refused(pg, List.of("a"), List.of(new Cut("abc", OptionalLong.of(2000))));
    refused(pg, List.of("a"), List.of(new Binary(new byte[] {1}, 5)));
    refused(pg, List.of("a"), List.of(1));
    // Values whose literal may not equal what is stored: a FLOAT, a moment, a backslash.
    var moment = OffsetDateTime.of(2024, 3, 31, 4, 30, 0, 0, ZoneOffset.ofHours(2));
    for (var provider : List.of(Provider.POSTGRESQL, Provider.MYSQL, Provider.MARIADB)) {
      for (Object value : List.of(0.1f, moment, "a\\b")) {
        refused(provider, List.of("a"), List.of(value));
      }
    }
    var columns = List.of("a");
    List<Object> values = List.of(1L);
    assertThrows(IllegalStateException.class, () -> Rows.where(Provider.MONGODB, columns, values));
  }

  private static void refused(Provider provider, List<String> columns, List<Object> values) {
    assertThrows(IllegalArgumentException.class, () -> Rows.where(provider, columns, values));
  }
}

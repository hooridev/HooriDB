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
import java.time.ZoneId;
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

  @Test
  void columnFiltersFormatSqlConditionsPerProvider() throws QueryException {
    var berlin = ZoneId.of("Europe/Berlin");
    var name = new Field("na\"me", "text", Kind.TEXT);
    var plain = new Field("name", "character varying(64)", Kind.TEXT);
    var id = new Field("id", "bigint", Kind.INTEGER);
    var total = new Field("total", "numeric", Kind.DECIMAL);
    var paid = new Field("paid", "boolean", Kind.BOOLEAN);
    var due = new Field("due", "date", Kind.DATE);
    var at = new Field("at", "time", Kind.TIME);
    var local = new Field("local", "timestamp", Kind.DATE_TIME);
    var moment = new Field("moment", "timestamp with time zone", Kind.DATE_TIME_ZONED);

    // PostgreSQL: ILIKE with ! escape, LOWER = LOWER for text equals, ~* for regex, typed literals.
    assertEquals(
        "\"na\"\"me\" ILIKE E'%O''Reilly 50!%!_!!\\\\x%' ESCAPE '!'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(new ColumnFilter(
                name, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "O'Reilly 50%_!\\x"))),
            berlin));
    assertEquals(
        "\"name\" ILIKE 'Ada!%%' ESCAPE '!'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(new ColumnFilter(
                plain, new ColumnFilter.Text(ColumnFilter.Match.STARTS_WITH, "Ada%"))),
            berlin));
    assertEquals(
        "LOWER(\"name\") = LOWER('Ada')",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(
                new ColumnFilter(plain, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "Ada"))),
            berlin));
    assertEquals(
        "\"name\" ~* '^ada.*$'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(new ColumnFilter(plain, new ColumnFilter.Regex("^ada.*$"))),
            berlin));
    // An enum, a TEXT of the grid, is text to ILIKE only once cast.
    assertEquals(
        "CAST(\"mood\" AS pg_catalog.text) ILIKE '%ok%' ESCAPE '!'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(new ColumnFilter(
                new Field("mood", "mood", Kind.TEXT),
                new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "ok"))),
            berlin));
    assertEquals(
        "\"id\" = 42 AND \"total\" >= '10.50' AND \"total\" <= '99.95'"
            + " AND \"paid\" IN (TRUE) AND \"due\" = '2026-05-14'"
            + " AND \"at\" = '13:45:05' AND \"local\" = '2026-05-14 14:30:00'"
            + " AND \"moment\" = '2026-05-14 12:30:00+00:00'",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(
                new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "42")),
                new ColumnFilter(total, new ColumnFilter.Between("10.50", "99.95")),
                new ColumnFilter(paid, new ColumnFilter.OneOf(List.of("true"))),
                new ColumnFilter(
                    due, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14")),
                new ColumnFilter(at, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "13:45:05")),
                new ColumnFilter(
                    local, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14 14:30:00")),
                new ColumnFilter(
                    moment,
                    new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14 14:30:00"))),
            berlin));
    // Open-ended ranges, empty OneOf, NULL checks, and joining onto hand-written base.
    assertEquals(
        "\"id\" >= 10 AND \"total\" <= '50' AND FALSE AND \"name\" IS NULL AND \"paid\" IS NOT NULL",
        Rows.where(
            Provider.POSTGRESQL,
            List.of(
                new ColumnFilter(id, new ColumnFilter.Between("10", "")),
                new ColumnFilter(total, new ColumnFilter.Between("", "50")),
                new ColumnFilter(paid, new ColumnFilter.OneOf(List.of())),
                new ColumnFilter(plain, new ColumnFilter.Null(true)),
                new ColumnFilter(paid, new ColumnFilter.Null(false))),
            berlin));
    var joined = Rows.where(
        Provider.POSTGRESQL,
        "total > 20 OR id = 1",
        List.of(new ColumnFilter(plain, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "ada"))),
        berlin);
    assertEquals("(total > 20 OR id = 1) AND \"name\" ILIKE '%ada%' ESCAPE '!'", joined);
    Rows.check(Provider.POSTGRESQL, new Rows.Filter(joined, ""), true);
    var commented = Rows.where(
        Provider.POSTGRESQL,
        "id > 0 -- active rows",
        List.of(new ColumnFilter(plain, new ColumnFilter.Null(false))),
        berlin);
    assertEquals("(id > 0 -- active rows\n) AND \"name\" IS NOT NULL", commented);
    Rows.check(Provider.POSTGRESQL, new Rows.Filter(commented, ""), true);
    assertEquals(
        "(id > 0 --\n) AND `name` IS NOT NULL",
        Rows.where(
            Provider.MYSQL,
            "id > 0 --",
            List.of(new ColumnFilter(plain, new ColumnFilter.Null(false))),
            berlin));
    assertEquals(
        "(note = 'a--b') AND \"name\" IS NOT NULL",
        Rows.where(
            Provider.POSTGRESQL,
            "note = 'a--b'",
            List.of(new ColumnFilter(plain, new ColumnFilter.Null(false))),
            berlin));

    // MySQL and MariaDB: backticks, LOWER LIKE LOWER ESCAPE '!', REGEXP '(?i)...', CAST AS CHAR.
    var tick = new Field("na`me", "varchar(64)", Kind.TEXT);
    for (var provider : List.of(Provider.MYSQL, Provider.MARIADB)) {
      var like = Rows.where(
          provider,
          List.of(new ColumnFilter(
              tick, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "50%_!\\x"))),
          berlin);
      assertEquals(
          "LOWER(CONVERT(`na``me` USING utf8mb4)) LIKE LOWER(CONVERT('%50!%!_!!\\\\x%' USING"
              + " utf8mb4)) COLLATE utf8mb4_bin ESCAPE '!'",
          like);
      Rows.check(provider, new Rows.Filter(like, ""), true);

      var regex = Rows.where(
          provider, List.of(new ColumnFilter(plain, new ColumnFilter.Regex("^ada"))), berlin);
      assertEquals("CONVERT(`name` USING utf8mb4) REGEXP '(?i)^ada'", regex);
      Rows.check(provider, new Rows.Filter(regex, ""), true);

      // Equals ignores case, Equals exactly does not, both by UTF-8 bytes; a moment as the
      // seconds it stores, as MariaDB reads no offset; a FLOAT at its own precision.
      var real = new Field("ratio", "float", Kind.DECIMAL);
      var exact = Rows.where(
          provider,
          List.of(
              new ColumnFilter(plain, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "Ada")),
              new ColumnFilter(plain, new ColumnFilter.Text(ColumnFilter.Match.EXACTLY, "Ada")),
              new ColumnFilter(
                  moment, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14 14:30")),
              new ColumnFilter(real, new ColumnFilter.Between("", "0.1"))),
          berlin);
      assertEquals(
          "CAST(LOWER(CONVERT(`name` USING utf8mb4)) AS BINARY) = CAST(LOWER(CONVERT('Ada' USING"
              + " utf8mb4)) AS BINARY) AND CAST(CONVERT(`name` USING utf8mb4) AS BINARY) ="
              + " CAST(CONVERT('Ada' USING utf8mb4) AS BINARY) AND UNIX_TIMESTAMP(`moment`) ="
              + " 1778761800.000000000 AND CAST(CAST(`ratio` AS CHAR) AS FLOAT) <= CAST(0.1 AS FLOAT)",
          exact);
      Rows.check(provider, new Rows.Filter(exact, ""), true);
    }

    // PostgreSQL: exact equality, a real at its own precision, and a value ending in a backslash
    // as an escape string, which the clause check takes.
    var exact = Rows.where(
        Provider.POSTGRESQL,
        List.of(
            new ColumnFilter(plain, new ColumnFilter.Text(ColumnFilter.Match.EXACTLY, "a\\")),
            new ColumnFilter(
                new Field("ratio", "real", Kind.DECIMAL),
                new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "0.1"))),
        berlin);
    assertEquals("\"name\" COLLATE pg_catalog.\"C\" = E'a\\\\' AND \"ratio\" = '0.1'", exact);
    Rows.check(Provider.POSTGRESQL, new Rows.Filter(exact, ""), true);
  }

  @Test
  void columnFiltersRefuseInvalidValuesAndUnsupportedColumns() {
    var berlin = ZoneId.of("Europe/Berlin");
    var id = new Field("id", "integer", Kind.INTEGER);
    var total = new Field("total", "numeric", Kind.DECIMAL);
    var paid = new Field("paid", "boolean", Kind.BOOLEAN);
    var due = new Field("due", "date", Kind.DATE);
    var at = new Field("at", "time", Kind.TIME);
    var local = new Field("local", "timestamp", Kind.DATE_TIME);
    var moment = new Field("moment", "timestamptz", Kind.DATE_TIME_ZONED);
    var doc = new Field("doc", "jsonb", Kind.JSON);
    var other = new Field("shape", "box", Kind.OTHER);

    List<ColumnFilter> bad = List.of(
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "abc")),
        new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "1.5")),
        new ColumnFilter(total, new ColumnFilter.Between("abc", "10")),
        new ColumnFilter(paid, new ColumnFilter.OneOf(List.of("maybe"))),
        new ColumnFilter(due, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-02-30")),
        new ColumnFilter(at, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "25:00:00")),
        new ColumnFilter(
            local, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-05-14 10:00:00+02:00")),
        new ColumnFilter(
            moment, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "2026-03-29 02:30:00")),
        new ColumnFilter(id, new ColumnFilter.Between("", "  ")),
        new ColumnFilter(new Field("name", "text", Kind.TEXT), new ColumnFilter.Regex("")),
        new ColumnFilter(doc, new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "a")),
        new ColumnFilter(
            new Field("photo", "bytea", Kind.BINARY),
            new ColumnFilter.Text(ColumnFilter.Match.CONTAINS, "0x41")),
        new ColumnFilter(other, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "a")));
    for (var filter : bad) {
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(
                  QueryException.class,
                  () -> Rows.where(Provider.POSTGRESQL, List.of(filter), berlin))
              .failure(),
          filter.toString());
    }
    // Unbalanced parentheses or multiple statements in hand-written base are refused before
    // wrapping.
    var valid = new ColumnFilter(id, new ColumnFilter.Text(ColumnFilter.Match.EQUALS, "1"));
    for (var badBase : List.of("1=1; DROP TABLE x", "1=1) OR (1=1")) {
      assertEquals(
          QueryException.Failure.CLAUSE_REFUSED,
          assertThrows(
                  QueryException.class,
                  () -> Rows.where(Provider.POSTGRESQL, badBase, List.of(valid), berlin))
              .failure(),
          badBase);
    }
    // Exists is MongoDB-only and refused by SQL.
    assertEquals(
        QueryException.Failure.CLAUSE_REFUSED,
        assertThrows(
                QueryException.class,
                () -> Rows.where(
                    Provider.POSTGRESQL,
                    new ColumnFilter(id, new ColumnFilter.Exists(true)),
                    berlin))
            .failure());
  }

  @Test
  void conditionGroupsNegationAndOperatorsWorkAcrossSqlDialects() throws Exception {
    var berlin = ZoneId.of("Europe/Berlin");
    var id = new Field("id", "integer", Kind.INTEGER);
    var name = new Field("name", "text", Kind.TEXT);
    var paid = new Field("paid", "boolean", Kind.BOOLEAN);
    var doc = new Field("doc", "jsonb", Kind.JSON);
    var bin = new Field("photo", "bytea", Kind.BINARY);
    var other = new Field("shape", "box", Kind.OTHER);

    // Operators per kind.
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.CONTAINS,
            ColumnFilter.Operator.STARTS_WITH,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL),
        Rows.operators(Provider.POSTGRESQL, name));
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.AT_LEAST,
            ColumnFilter.Operator.AT_MOST,
            ColumnFilter.Operator.BETWEEN,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL),
        Rows.operators(Provider.MYSQL, id));
    assertEquals(
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL),
        Rows.operators(Provider.MARIADB, paid));
    for (var unsupported : List.of(doc, bin, other)) {
      assertEquals(
          List.of(ColumnFilter.Operator.IS_NULL, ColumnFilter.Operator.IS_NOT_NULL),
          Rows.operators(Provider.POSTGRESQL, unsupported));
    }

    // OR with nested AND group and Not equal.
    var condition = Condition.Group.or(List.of(
        new ColumnFilter(name, ColumnFilter.Operator.NOT_EQUAL.spec(List.of("Grace"))),
        Condition.Group.and(List.of(
            new ColumnFilter(id, ColumnFilter.Operator.AT_LEAST.spec(List.of("10"))),
            new ColumnFilter(paid, ColumnFilter.Operator.EQUALS.spec(List.of("true")))))));
    var pgWhere = Rows.where(Provider.POSTGRESQL, condition, berlin);
    assertEquals(
        "NOT (LOWER(\"name\") = LOWER('Grace')) OR (\"id\" >= 10 AND \"paid\" = TRUE)", pgWhere);
    Rows.check(Provider.POSTGRESQL, new Rows.Filter(pgWhere, ""), true);

    // Joining a top-level OR condition onto hand-written base parenthesizes the OR group.
    var joinedOr = Rows.where(Provider.POSTGRESQL, "id > 0", condition, berlin);
    assertEquals(
        "(id > 0) AND (NOT (LOWER(\"name\") = LOWER('Grace')) OR (\"id\" >= 10 AND \"paid\" ="
            + " TRUE))",
        joinedOr);
    Rows.check(Provider.POSTGRESQL, new Rows.Filter(joinedOr, ""), true);

    // Two-bound Between inside OR is parenthesized; empty nested groups are ignored.
    var rangeOr = Condition.Group.or(List.of(
        new ColumnFilter(id, ColumnFilter.Operator.BETWEEN.spec(List.of("1", "5"))),
        new ColumnFilter(id, ColumnFilter.Operator.AT_MOST.spec(List.of("0"))),
        Condition.Group.and(List.of())));
    assertEquals(
        "(\"id\" >= 1 AND \"id\" <= 5) OR \"id\" <= 0",
        Rows.where(Provider.POSTGRESQL, rangeOr, berlin));
    assertEquals("", Rows.where(Provider.POSTGRESQL, Condition.Group.or(List.of()), berlin));
    assertEquals(
        "id > 0",
        Rows.where(Provider.POSTGRESQL, "id > 0", Condition.Group.and(List.of()), berlin));

    // MySQL / MariaDB dialect with backticks and NOT (...).
    var mysqlWhere = Rows.where(
        Provider.MYSQL,
        Condition.Group.and(List.of(
            new ColumnFilter(id, ColumnFilter.Operator.NOT_EQUAL.spec(List.of("7"))),
            Condition.Group.or(List.of(
                new ColumnFilter(paid, ColumnFilter.Operator.IS_NULL.spec(List.of())),
                new ColumnFilter(paid, ColumnFilter.Operator.EQUALS.spec(List.of("false"))))))),
        berlin);
    assertEquals("NOT (`id` = 7) AND (`paid` IS NULL OR `paid` = FALSE)", mysqlWhere);
    Rows.check(Provider.MYSQL, new Rows.Filter(mysqlWhere, ""), true);
  }
}

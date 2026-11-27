package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.dialect.FindDialect.Holds;
import dev.hoori.db.core.find.Search.Match;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** What a search in data reads and how, without a server. */
class FinderTest {

  private static Candidate candidate(Table table, String... columns) {
    var list = new java.util.ArrayList<Column>();
    for (var column : columns) {
      var parts = column.split(": ", 2);
      list.add(new Column(table, parts[0], parts[1], true, 0));
    }
    return new Candidate(table, OptionalLong.empty(), OptionalLong.empty(), list);
  }

  @Test
  void eachProviderTellsWhatAColumnHolds() {
    var pg = Provider.POSTGRESQL.dialect().find();
    assertEquals(Holds.TEXT, pg.holds("character varying(100)"));
    assertEquals(Holds.TEXT, pg.holds("text"));
    assertEquals(Holds.TEXT, pg.holds("character(3)"));
    assertEquals(Holds.NUMBER, pg.holds("numeric(12,2)"));
    assertEquals(Holds.DOUBLE, pg.holds("double precision"));
    assertEquals(Holds.FLOAT, pg.holds("real"));
    assertEquals(Holds.NUMBER, pg.holds("oid"));
    assertEquals(Holds.OTHER, pg.holds("text[]"));
    assertEquals(Holds.OTHER, pg.holds("timestamp without time zone"));
    assertEquals(Holds.OTHER, pg.holds("jsonb"));
    var my = Provider.MYSQL.dialect().find();
    assertEquals(Holds.TEXT, my.holds("varchar(200)"));
    assertEquals(Holds.TEXT, my.holds("enum('a','b')"));
    assertEquals(Holds.TEXT, Provider.MARIADB.dialect().find().holds("longtext"));
    assertEquals(Holds.NUMBER, my.holds("int unsigned"));
    assertEquals(Holds.NUMBER, my.holds("decimal(12,2)"));
    assertEquals(Holds.DOUBLE, my.holds("double unsigned"));
    assertEquals(Holds.FLOAT, my.holds("float(7,4)"));
    assertEquals(Holds.OTHER, my.holds("datetime"));
    assertEquals(Holds.OTHER, my.holds("blob"));
  }

  @Test
  void textColumnsAreSearchedForTextNumbersForANumberAndOthersOnlyWhereChosen() {
    var table =
        new Table(new Schema(new Database("shop", false), "public", false), "t", Table.Kind.TABLE);
    var candidate =
        candidate(table, "name: character varying(10)", "total: numeric(12,2)", "made: date");
    var text = new Search("ada", Match.CONTAINS, false);
    assertEquals(
        List.of("name"),
        Finder.searched(Provider.POSTGRESQL, candidate, text, false).stream()
            .map(Column::name)
            .toList());
    assertEquals(
        List.of("name", "total", "made"),
        Finder.searched(Provider.POSTGRESQL, candidate, text, true).stream()
            .map(Column::name)
            .toList());
    var number = new Search("42", Match.EQUALS, false);
    assertEquals(
        List.of("name", "total"),
        Finder.searched(Provider.POSTGRESQL, candidate, number, false).stream()
            .map(Column::name)
            .toList());
  }

  @Test
  void aNumberIsComparedInTheColumnsTypeAndOnlyWhereTheTypeCanHoldIt() throws Exception {
    var table =
        new Table(new Schema(new Database("shop", false), "public", false), "t", Table.Kind.TABLE);
    var candidate =
        candidate(table, "id: oid", "r: real", "d: double precision", "n: numeric(12,2)");
    assertEquals(
        Optional.of("CAST(\"id\" AS pg_catalog.int8) OPERATOR(pg_catalog.=) 7"),
        where(Provider.POSTGRESQL, candidate, "id", new Search("7", Match.EQUALS, true), false));
    assertEquals(
        Optional.of("\"r\" OPERATOR(pg_catalog.=) CAST(0.1 AS pg_catalog.float4)"),
        where(Provider.POSTGRESQL, candidate, "r", new Search("0.1", Match.EQUALS, true), false));
    assertEquals(
        Optional.of("\"d\" OPERATOR(pg_catalog.=) 1.0E300"),
        where(Provider.POSTGRESQL, candidate, "d", new Search("1e300", Match.EQUALS, true), false));
    assertEquals(List.of("id", "r", "d", "n"), searched(candidate, "0.1"));
    // Beyond a double's range, or more digits than a double or a float holds: no such column.
    assertEquals(List.of("id", "n"), searched(candidate, "1e400"));
    assertEquals(List.of("id", "n"), searched(candidate, "0.10000000000000000001"));
    assertEquals(List.of("id", "d", "n"), searched(candidate, "16777217"));
    assertEquals(
        Optional.empty(),
        where(Provider.POSTGRESQL, candidate, "d", new Search("1e400", Match.EQUALS, true), true));
    var db = new Table(new Database("shop", false), "t", Table.Kind.TABLE);
    assertEquals(
        Optional.of("`f` = CAST(0.1 AS FLOAT)"),
        where(
            Provider.MYSQL,
            candidate(db, "f: float"),
            "f",
            new Search("0.1", Match.EQUALS, true),
            false));
  }

  // The condition a table's WHERE takes, which a read-only table tab accepts as it is.
  private static Optional<String> where(
      Provider provider, Candidate candidate, String column, Search search, boolean others)
      throws Exception {
    var where = Finder.where(provider, candidate, column, search, others);
    if (where.isPresent()) {
      Rows.check(provider, new Rows.Filter(where.get(), ""), true);
    }
    return where;
  }

  private static List<String> searched(Candidate candidate, String number) {
    return Finder.searched(
            Provider.POSTGRESQL, candidate, new Search(number, Match.EQUALS, true), true)
        .stream()
        .map(Column::name)
        .toList();
  }

  @Test
  void theRowsOfAHitAreTheTablesConditionWithTheValueWrittenIn() throws Exception {
    var table =
        new Table(new Schema(new Database("shop", false), "Public", false), "t", Table.Kind.TABLE);
    var candidate = candidate(table, "name: text", "total: integer", "made: date");
    assertEquals(
        Optional.of("CAST(\"name\" AS pg_catalog.text) COLLATE pg_catalog.\"default\""
            + " ILIKE '%it''s 50!%%' ESCAPE '!'"),
        where(
            Provider.POSTGRESQL,
            candidate,
            "name",
            new Search("it's 50%", Match.CONTAINS, false),
            false));
    assertEquals(
        Optional.of("CAST(\"name\" AS pg_catalog.text) COLLATE pg_catalog.\"default\""
            + " COLLATE pg_catalog.\"C\" LIKE '%Ada%' ESCAPE '!'"),
        where(
            Provider.POSTGRESQL,
            candidate,
            "name",
            new Search("Ada", Match.CONTAINS, true),
            false));
    assertEquals(
        Optional.of("\"total\" OPERATOR(pg_catalog.=) 7"),
        where(Provider.POSTGRESQL, candidate, "total", new Search("7", Match.EQUALS, true), false));
    assertEquals(
        Optional.empty(),
        where(Provider.POSTGRESQL, candidate, "made", new Search("7", Match.EQUALS, true), false));
    var db = new Table(new Database("shop", false), "t", Table.Kind.TABLE);
    assertEquals(
        Optional.of("LOWER(CONVERT(CAST(`made` AS CHAR) USING utf8mb4)) LIKE"
            + " LOWER(CONVERT('%2024!_%' USING utf8mb4)) COLLATE utf8mb4_bin ESCAPE '!'"),
        where(
            Provider.MARIADB,
            candidate(db, "made: date"),
            "made",
            new Search("2024_", Match.CONTAINS, false),
            true));
  }

  @Test
  void aBackslashMeansItselfWhateverTheServersEscapesSay() throws Exception {
    // PostgreSQL's E'…' reads alike with standard_conforming_strings on or off.
    var table =
        new Table(new Schema(new Database("shop", false), "public", false), "t", Table.Kind.TABLE);
    assertEquals(
        Optional.of("CAST(\"name\" AS pg_catalog.text) COLLATE pg_catalog.\"default\""
            + " COLLATE pg_catalog.\"C\" = E'path\\\\name'"),
        where(
            Provider.POSTGRESQL,
            candidate(table, "name: text"),
            "name",
            new Search("path\\name", Match.EQUALS, true),
            false));
    // MariaDB and MySQL read a text with one as its bytes, as NO_BACKSLASH_ESCAPES or not.
    var db = new Table(new Database("shop", false), "t", Table.Kind.TABLE);
    for (var provider : List.of(Provider.MYSQL, Provider.MARIADB)) {
      assertEquals(
          Optional.of("CONVERT(`name` USING utf8mb4) REGEXP"
              + " CONVERT(X'283f69295e615c64' USING utf8mb4)"),
          where(
              provider,
              candidate(db, "name: varchar(10)"),
              "name",
              new Search("^a\\d", Match.PATTERN, false),
              false));
    }
  }

  @Test
  void aCollectionsDocumentsAreFoundWithRegexAndANumberAsItself() {
    assertEquals(
        "{ $or: [ { 'address.city': { $regex: '\\\\A42\\\\z', $options: 'i' } },"
            + " { 'address.city': Long('42') } ] }",
        DocumentFinder.filter("address.city", new Search("42", Match.EQUALS, false)));
    // A decimal as a double and as a Decimal128, which is equal to no double that is not it.
    assertEquals(
        "{ $or: [ { total: { $regex: '0\\\\.1' } }, { total: { $in: [ 0.1, Decimal128('0.1') ]"
            + " } } ] }",
        DocumentFinder.filter("total", new Search("0.1", Match.CONTAINS, true)));
    assertEquals(
        "{ name: { $regex: 'a\\\\.b' } }",
        DocumentFinder.filter("name", new Search("a.b", Match.CONTAINS, true)));
  }

  @Test
  void aDecimalGoesOnlyAsWhatCanHoldIt() {
    // More digits than a Decimal128 or a double holds: as text alone, since no number equals it.
    var digits = "0.12345678901234567890123456789012345";
    assertEquals(
        "{ total: { $regex: '" + digits.replace(".", "\\\\.") + "' } }",
        DocumentFinder.filter("total", new Search(digits, Match.CONTAINS, true)));
    // Below a double's range, where it would be 0.0, and beyond a Decimal128's: as text alone.
    assertEquals(
        "{ total: { $regex: '1E-6177' } }",
        DocumentFinder.filter("total", new Search("1E-6177", Match.CONTAINS, true)));
    // Below a double's range but within a Decimal128's: as a Decimal128 alone.
    assertEquals(
        "{ $or: [ { total: { $regex: '1E-400' } }, { total: { $in: [ Decimal128('1E-400') ] } }"
            + " ] }",
        DocumentFinder.filter("total", new Search("1E-400", Match.CONTAINS, true)));
    // Beyond a double's range: as a Decimal128 alone.
    assertEquals(
        "{ $or: [ { total: { $regex: '1\\\\.5E400' } }, { total: { $in: [ Decimal128('1.5E+400')"
            + " ] } } ] }",
        DocumentFinder.filter("total", new Search("1.5E400", Match.CONTAINS, true)));
    assertTrue(DocumentFinder.decimal128(new BigDecimal("1234567890123456789012345678901234")));
    assertFalse(DocumentFinder.decimal128(new BigDecimal("12345678901234567890123456789012345")));
    assertTrue(DocumentFinder.decimal128(new BigDecimal("1.20000000000000000000000000000000000")));
    assertTrue(DocumentFinder.decimal128(new BigDecimal("1E6144")));
    assertFalse(DocumentFinder.decimal128(new BigDecimal("1E6145")));
    assertTrue(DocumentFinder.decimal128(new BigDecimal("1E-6176")));
    assertFalse(DocumentFinder.decimal128(new BigDecimal("1E-6177")));
  }
}

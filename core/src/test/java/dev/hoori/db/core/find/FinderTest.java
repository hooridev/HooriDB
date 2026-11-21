package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.FindDialect.Holds;
import dev.hoori.db.core.find.Search.Match;
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
    assertEquals(Holds.NUMBER, pg.holds("double precision"));
    assertEquals(Holds.OTHER, pg.holds("text[]"));
    assertEquals(Holds.OTHER, pg.holds("timestamp without time zone"));
    assertEquals(Holds.OTHER, pg.holds("jsonb"));
    var my = Provider.MYSQL.dialect().find();
    assertEquals(Holds.TEXT, my.holds("varchar(200)"));
    assertEquals(Holds.TEXT, my.holds("enum('a','b')"));
    assertEquals(Holds.TEXT, Provider.MARIADB.dialect().find().holds("longtext"));
    assertEquals(Holds.NUMBER, my.holds("int unsigned"));
    assertEquals(Holds.NUMBER, my.holds("decimal(12,2)"));
    assertEquals(Holds.OTHER, my.holds("datetime"));
    assertEquals(Holds.OTHER, my.holds("blob"));
  }

  @Test
  void caseFollowsTheCollationOnMariaDbAndMySqlAlone() {
    assertFalse(Provider.POSTGRESQL.dialect().find().caseByCollation());
    assertTrue(Provider.MYSQL.dialect().find().caseByCollation());
    assertTrue(Provider.MARIADB.dialect().find().caseByCollation());
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
  void theRowsOfAHitAreAQueryWithTheValueWrittenIn() {
    var table =
        new Table(new Schema(new Database("shop", false), "Public", false), "t", Table.Kind.TABLE);
    var candidate = candidate(table, "name: text", "total: integer", "made: date");
    assertEquals(
        Optional.of("SELECT * FROM \"Public\".\"t\" WHERE CAST(\"name\" AS pg_catalog.text)"
            + " OPERATOR(pg_catalog.~~*) '%it''s 50\\%%'"),
        Finder.rows(
            Provider.POSTGRESQL,
            candidate,
            "name",
            new Search("it's 50%", Match.CONTAINS, false),
            false));
    assertEquals(
        Optional.of("SELECT * FROM \"Public\".\"t\" WHERE \"total\" OPERATOR(pg_catalog.=) 7"),
        Finder.rows(
            Provider.POSTGRESQL, candidate, "total", new Search("7", Match.EQUALS, true), false));
    assertEquals(
        Optional.empty(),
        Finder.rows(
            Provider.POSTGRESQL, candidate, "made", new Search("7", Match.EQUALS, true), false));
    var db = new Table(new Database("shop", false), "t", Table.Kind.TABLE);
    assertEquals(
        Optional.of("SELECT * FROM `shop`.`t` WHERE `name` REGEXP '^a\\\\d'"),
        Finder.rows(
            Provider.MYSQL,
            candidate(db, "name: varchar(10)"),
            "name",
            new Search("^a\\d", Match.PATTERN, false),
            false));
    assertEquals(
        Optional.of(
            "SELECT * FROM `shop`.`t` WHERE CAST(`made` AS CHAR) LIKE '%2024!_%' ESCAPE" + " '!'"),
        Finder.rows(
            Provider.MARIADB,
            candidate(db, "made: date"),
            "made",
            new Search("2024_", Match.CONTAINS, false),
            true));
  }

  @Test
  void aCollectionsDocumentsAreFoundWithRegexAndANumberAsItself() {
    var collection = new Table(new Database("hoori", false), "my orders", Table.Kind.TABLE);
    assertEquals(
        Optional.of("db.getCollection('my orders').find({ $or: [ { 'address.city': { $regex:"
            + " '^42$', $options: 'i' } }, { 'address.city': Long('42') } ] })"),
        DocumentFinder.rows(collection, "address.city", new Search("42", Match.EQUALS, false)));
    assertEquals(
        Optional.of("db.getCollection('my orders').find({ name: { $regex: 'a\\\\.b' } })"),
        DocumentFinder.rows(collection, "name", new Search("a.b", Match.CONTAINS, true)));
  }
}

package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.RowsServerTest.columns;
import static dev.hoori.db.core.data.RowsServerTest.execute;
import static dev.hoori.db.core.data.RowsServerTest.open;
import static dev.hoori.db.core.data.RowsServerTest.qualified;
import static dev.hoori.db.core.data.RowsServerTest.sample;
import static dev.hoori.db.core.data.RowsServerTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the catalog and the rows send to the server means what it says, whatever the names in it
 * and whatever a user has created on PostgreSQL.
 */
class GeneratedSqlServerTest {

  // A function, operator, aggregate, type and collation under each name the generated statements
  // use, in a schema searched before pg_catalog, with argument types that match better or alike.
  private static final List<String> HOSTILE = List.of(
      "CREATE SCHEMA hostile",
      "CREATE FUNCTION hostile.boom() RETURNS boolean LANGUAGE plpgsql"
          + " AS $$BEGIN RAISE EXCEPTION 'a hostile function ran'; END$$",
      "CREATE FUNCTION hostile.unnest(pg_catalog.int2vector) RETURNS SETOF pg_catalog.int2"
          + " LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'hostile unnest'; END$$",
      "CREATE FUNCTION hostile.format_type(pg_catalog.oid, pg_catalog.int4)"
          + " RETURNS pg_catalog.text LANGUAGE sql AS $$SELECT 'hostile'$$",
      "CREATE FUNCTION hostile.substr(pg_catalog.text, pg_catalog.int4, pg_catalog.int4)"
          + " RETURNS pg_catalog.text LANGUAGE sql AS $$SELECT 'hostile'$$",
      "CREATE FUNCTION hostile.substr(pg_catalog.varchar, pg_catalog.int4, pg_catalog.int4)"
          + " RETURNS pg_catalog.text LANGUAGE sql AS $$SELECT 'hostile'$$",
      "CREATE FUNCTION hostile.substr(pg_catalog.bytea, pg_catalog.int4, pg_catalog.int4)"
          + " RETURNS pg_catalog.bytea LANGUAGE sql AS $$SELECT '\\x00'::pg_catalog.bytea$$",
      "CREATE FUNCTION hostile.octet_length(pg_catalog.varchar) RETURNS pg_catalog.int4"
          + " LANGUAGE sql AS $$SELECT -1$$",
      "CREATE FUNCTION hostile.octet_length(pg_catalog.text) RETURNS pg_catalog.int4"
          + " LANGUAGE sql AS $$SELECT -1$$",
      "CREATE FUNCTION hostile.concat(pg_catalog.jsonb) RETURNS pg_catalog.text"
          + " LANGUAGE sql AS $$SELECT 'hostile'$$",
      "CREATE FUNCTION hostile.num_nulls(pg_catalog.jsonb) RETURNS pg_catalog.int4"
          + " LANGUAGE sql AS $$SELECT 1$$",
      "CREATE FUNCTION hostile.timezone(pg_catalog.text, pg_catalog.timestamptz)"
          + " RETURNS pg_catalog.timestamp LANGUAGE sql AS $$SELECT 'epoch'::pg_catalog.timestamp$$",
      "CREATE FUNCTION hostile.counted(pg_catalog.int8) RETURNS pg_catalog.int8"
          + " LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'hostile count'; END$$",
      "CREATE AGGREGATE hostile.count(*) (SFUNC = hostile.counted, STYPE = pg_catalog.int8)",
      "CREATE FUNCTION hostile.same(pg_catalog.name, pg_catalog.varchar) RETURNS boolean"
          + " LANGUAGE sql AS $$SELECT hostile.boom()$$",
      "CREATE OPERATOR hostile.= (LEFTARG = pg_catalog.name, RIGHTARG = pg_catalog.varchar,"
          + " FUNCTION = hostile.same)",
      "CREATE FUNCTION hostile.same(pg_catalog.oid, pg_catalog.oid) RETURNS boolean"
          + " LANGUAGE sql AS $$SELECT hostile.boom()$$",
      "CREATE OPERATOR hostile.= (LEFTARG = pg_catalog.oid, RIGHTARG = pg_catalog.oid,"
          + " FUNCTION = hostile.same)",
      "CREATE FUNCTION hostile.same(pg_catalog.int2, pg_catalog.int2) RETURNS boolean"
          + " LANGUAGE sql AS $$SELECT hostile.boom()$$",
      "CREATE OPERATOR hostile.= (LEFTARG = pg_catalog.int2, RIGHTARG = pg_catalog.int2,"
          + " FUNCTION = hostile.same)",
      "CREATE FUNCTION hostile.above(pg_catalog.int2, pg_catalog.int4) RETURNS boolean"
          + " LANGUAGE sql AS $$SELECT hostile.boom()$$",
      "CREATE OPERATOR hostile.> (LEFTARG = pg_catalog.int2, RIGHTARG = pg_catalog.int4,"
          + " FUNCTION = hostile.above)",
      "CREATE TYPE hostile.text AS ENUM ('hostile')",
      "CREATE COLLATION hostile.\"C\" (provider = icu, locale = 'und-u-ks-level1',"
          + " deterministic = false)");

  @Test
  void nothingAUserCreatedUnderTheSameNamesRunsOnPostgresql() throws Exception {
    var provider = Provider.POSTGRESQL;
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE plain (id integer PRIMARY KEY, t text, v varchar(10), c char(3), b bytea,"
              + " j jsonb, z timestamptz)",
          "INSERT INTO plain VALUES (1, 'text', 'varchar', 'ab', '\\x01', '{\"a\": 1}',"
              + " '2024-01-01 00:00:00+00')",
          "CREATE VIEW plain_view AS SELECT * FROM plain");
      try {
        execute(connection, HOSTILE.toArray(String[]::new));
        execute(connection, "SET search_path = hostile, public, pg_catalog");
        var hoori = new Database("hoori", false);
        assertTrue(Catalog.databases(connection, provider).contains(hoori));
        assertTrue(Catalog.schemas(connection, provider, hoori).contains(sample(provider)));
        var expected = List.<Object>of(
            1L,
            "text",
            "varchar",
            "ab ",
            new Binary(new byte[] {1}, 1),
            "{\"a\": 1}",
            OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        for (var table : List.of(
            table(provider, "plain", Table.Kind.TABLE),
            table(provider, "plain_view", Table.Kind.VIEW))) {
          assertTrue(Catalog.tables(connection, provider, sample(provider)).contains(table));
          var columns = Catalog.columns(connection, provider, table);
          // Named with its schema, as the user's text type of the same name comes first.
          assertEquals("pg_catalog.text", columns.get(1).type());
          assertEquals(table.kind() == Table.Kind.TABLE ? 1 : 0, columns.get(0).keyPosition());
          var page = Rows.read(connection, provider, table, columns, 0, 10);
          assertEquals(List.of(expected), page.rows());
          assertEquals(1, Rows.count(connection, provider, table));
          assertTrue(Rows.estimate(connection, provider, table).isPresent());
        }
      } finally {
        execute(
            connection,
            "RESET search_path",
            "DROP SCHEMA IF EXISTS hostile CASCADE",
            "DROP VIEW plain_view",
            "DROP TABLE plain");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aNameWithAJdbcEscapeIsItsOwnTable(Provider provider) throws Exception {
    var escaped = "a{fn now()}";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, escaped) + " (id integer PRIMARY KEY)",
          "INSERT INTO " + qualified(provider, escaped) + " VALUES (1)",
          // What the escape would turn the name into.
          "CREATE TABLE " + qualified(provider, "anow()") + " (id integer PRIMARY KEY)",
          "INSERT INTO " + qualified(provider, "anow()") + " VALUES (2), (3)");
      try {
        var table = table(provider, escaped, Table.Kind.TABLE);
        var columns = Catalog.columns(connection, provider, table);
        assertEquals(1, columns.size());
        assertEquals(
            List.of(List.of(1L)),
            Rows.read(connection, provider, table, columns, 0, 10).rows());
        assertEquals(1, Rows.count(connection, provider, table));
      } finally {
        execute(
            connection,
            "DROP TABLE " + qualified(provider, escaped),
            "DROP TABLE " + qualified(provider, "anow()"));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aNameEndingInABackslashIsRead(Provider provider) throws Exception {
    var name = "back\\";
    var column = "c\\";
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE TABLE " + qualified(provider, name) + " (" + provider.quote(column)
              + " integer PRIMARY KEY)",
          "INSERT INTO " + qualified(provider, name) + " VALUES (1), (2)");
      try {
        var modes = provider == Provider.POSTGRESQL
            ? List.of("SELECT 1")
            : List.of("SELECT 1", "SET SESSION sql_mode = CONCAT(@@sql_mode, ',ANSI_QUOTES')");
        for (var mode : modes) {
          execute(connection, mode);
          var table = table(provider, name, Table.Kind.TABLE);
          var columns = Catalog.columns(connection, provider, table);
          assertEquals(List.of(column), columns.stream().map(c -> c.name()).toList(), mode);
          assertEquals(
              List.of(List.of(2L)),
              Rows.read(connection, provider, table, columns, 1, 2).rows(),
              mode);
          assertEquals(2, Rows.count(connection, provider, table));
          assertTrue(Rows.estimate(connection, provider, table).isPresent());
        }
      } finally {
        execute(connection, "DROP TABLE " + qualified(provider, name));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("dev.hoori.db.core.TestServer#sql")
  void aColumnNamedAfterAFunctionOrdersByItsValue(Provider provider) throws Exception {
    try (var connection = open(provider)) {
      // The texts are read through substr() and the like, whose outputs take their names; one is
      // the key's.
      execute(
          connection,
          "CREATE TABLE labeled (t text, j " + (provider == Provider.POSTGRESQL ? "jsonb" : "json")
              + ", " + provider.quote("substr") + " integer PRIMARY KEY, "
              + provider.quote("substring") + " integer, " + provider.quote("octet_length")
              + " integer, " + provider.quote("concat") + " integer)",
          "INSERT INTO labeled VALUES ('b', '2', 2, 20, 200, 2000),"
              + " ('a', '1', 1, 30, 300, 3000), ('c', '3', 3, 10, 100, 1000)");
      try {
        var table = table(provider, "labeled", Table.Kind.TABLE);
        assertEquals(
            List.of(1L, 2L, 3L),
            Rows.read(connection, provider, table, columns(connection, provider, table), 0, 10)
                .rows()
                .stream()
                .map(row -> row.get(2))
                .toList());
      } finally {
        execute(connection, "DROP TABLE labeled");
      }
    }
  }

  @Test
  void aSchemaThatNeedsQuotingHoldsItsTables() throws Exception {
    var provider = Provider.POSTGRESQL;
    var schema = new Schema(new Database("hoori", false), "s{fn now()}\\", false);
    try (var connection = open(provider)) {
      execute(
          connection,
          "CREATE SCHEMA " + provider.quote(schema.name()),
          "CREATE TABLE " + provider.quote(schema.name()) + ".t (id integer PRIMARY KEY)",
          "INSERT INTO " + provider.quote(schema.name()) + ".t VALUES (1)");
      try {
        var table = new Table(schema, "t", Table.Kind.TABLE);
        assertEquals(List.of(table), Catalog.tables(connection, provider, schema));
        assertEquals(
            List.of(List.of(1L)),
            Rows.read(connection, provider, table, columns(connection, provider, table), 0, 1)
                .rows());
      } finally {
        execute(connection, "DROP SCHEMA " + provider.quote(schema.name()) + " CASCADE");
      }
    }
  }
}

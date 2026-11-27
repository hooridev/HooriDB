package dev.hoori.db.core.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.catalog.Routine.Kind;
import dev.hoori.db.core.catalog.Routine.Mode;
import dev.hoori.db.core.catalog.Routine.Parameter;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connection.Provider;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What Save, Drop and Run send, without a server. */
class RoutinesTest {

  private static final Database SHOP = new Database("shop", false);
  private static final Schema PUBLIC = new Schema(SHOP, "public", false);

  @Test
  void aBodyWithSemicolonsIsOneStatementWithoutItsTerminatorOrDelimiter() {
    var body = "CREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\n  SELECT 2;\nEND";
    assertEquals(body, Routines.statement("-- note\n" + body + ";\n", Provider.MYSQL));
    assertEquals(
        body, Routines.statement("DELIMITER //\n" + body + "//\nDELIMITER ;\n", Provider.MARIADB));
    var dollars = "CREATE FUNCTION f() RETURNS int LANGUAGE sql AS $$ SELECT 1; $$";
    assertEquals(dollars, Routines.statement(dollars + ";", Provider.POSTGRESQL));
    assertEquals("", Routines.statement("  -- nothing\n", Provider.POSTGRESQL));
  }

  @Test
  void saveReplacesWhereTheServerCanAndElseDropsFirstAndKeepsTheSource() {
    var p = new Routine(SHOP, "p", "", Kind.PROCEDURE, "", List.of());
    var source = "CREATE PROCEDURE p() SELECT 1";
    var text = "CREATE PROCEDURE p() SELECT 2;";
    assertEquals(
        new Routines.Change(List.of("CREATE PROCEDURE p() SELECT 2"), null),
        Routines.save(Provider.MARIADB, p, source, text));
    assertEquals(
        new Routines.Change(
            List.of("DROP PROCEDURE IF EXISTS shop.p", "CREATE PROCEDURE p() SELECT 2"), source),
        Routines.save(Provider.MYSQL, p, source, text));
    // A new routine replaces none.
    assertEquals(
        new Routines.Change(List.of("CREATE PROCEDURE p() SELECT 2"), null),
        Routines.save(Provider.MYSQL, null, null, text));
  }

  @Test
  void dropNamesAnOverloadByItsArguments() {
    var add = new Routine(
        PUBLIC,
        "Add",
        "a integer, b integer",
        Kind.FUNCTION,
        "integer",
        List.of(new Parameter("a", "integer", Mode.IN), new Parameter("b", "integer", Mode.IN)));
    assertEquals(
        List.of("DROP FUNCTION public.\"Add\"(a integer, b integer)"),
        Routines.drop(Provider.POSTGRESQL, add).statements());
  }

  @Test
  void aCallBindsEachValueToAPlaceOfItsOwn() {
    var fill = new Routine(
        PUBLIC,
        "fill",
        "n integer, INOUT seen text, OUT total bigint",
        Kind.PROCEDURE,
        "",
        List.of(
            new Parameter("n", "integer", Mode.IN),
            new Parameter("seen", "text", Mode.INOUT),
            new Parameter("total", "bigint", Mode.OUT)));
    var values = Arrays.asList("4", null);
    assertEquals(
        List.of(new Call(
            "CALL public.fill(CAST(? AS integer), CAST(? AS text), CAST(NULL AS bigint))", values)),
        Provider.POSTGRESQL.dialect().routines().call(fill, values));
    var mysql = new Routine(SHOP, "fill", "", Kind.PROCEDURE, "", fill.parameters());
    var calls = Provider.MYSQL.dialect().routines().call(mysql, values);
    assertEquals(
        List.of(
            new Call("SET @seen = ?", Arrays.asList((String) null)),
            new Call("CALL shop.fill(?, @seen, @total)", List.of("4")),
            Call.of("SELECT @seen AS seen, @total AS total")),
        calls);
    assertNull(calls.getFirst().values().getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () -> Provider.MYSQL.dialect().routines().call(mysql, List.of("4")));
  }

  @Test
  void aFunctionIsCalledAsAQuery() {
    var add = new Routine(
        SHOP,
        "add",
        "",
        Kind.FUNCTION,
        "int",
        List.of(new Parameter("a", "int", Mode.IN), new Parameter("b", "int", Mode.IN)));
    assertEquals(
        List.of(new Call("SELECT shop.`add`(?, ?) AS `add`", List.of("1", "2"))),
        Provider.MARIADB.dialect().routines().call(add, List.of("1", "2")));
  }
}

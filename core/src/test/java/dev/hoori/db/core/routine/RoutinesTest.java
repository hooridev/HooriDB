package dev.hoori.db.core.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.catalog.Routine.Kind;
import dev.hoori.db.core.catalog.Routine.Mode;
import dev.hoori.db.core.catalog.Routine.Parameter;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.routine.Routines.Role;
import dev.hoori.db.core.routine.Routines.Step;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What Save, Drop and Run send, and in which order, without a server. */
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

  private static final Routine P = new Routine(SHOP, "p", "", Kind.PROCEDURE, "", List.of());
  private static final String MODE = "SET SESSION sql_mode = 'ANSI_QUOTES'";
  private static final Routine.Source P_SOURCE =
      new Routine.Source("CREATE DEFINER=`root`@`%` PROCEDURE `p`() SELECT 1", List.of(MODE));

  @Test
  void saveReplacesWhereTheServerCanInTheSessionTheRoutineWasCreatedIn() {
    var text = "CREATE OR REPLACE PROCEDURE p() SELECT 2;";
    assertEquals(
        List.of(
            new Step(Role.SESSION, MODE),
            new Step(Role.CREATE, "CREATE OR REPLACE PROCEDURE p() SELECT 2")),
        Routines.save(Provider.MARIADB, P, P_SOURCE, text).steps());
    // A new routine replaces none, and its session is as it is.
    assertEquals(
        List.of(new Step(Role.CREATE, "CREATE PROCEDURE p() SELECT 2")),
        Routines.save(Provider.MYSQL, null, null, "CREATE PROCEDURE p() SELECT 2")
            .steps());
  }

  @Test
  void whereTheServerDropsFirstTheTextIsTriedUnderANameOfItsOwnBeforeTheDrop() {
    var text = "CREATE DEFINER=`root`@`%` PROCEDURE `p`() SELECT 2;";
    var change = Routines.save(Provider.MYSQL, P, P_SOURCE, text);
    var steps = change.steps();
    assertEquals(
        List.of(Role.SESSION, Role.TRIAL, Role.TRIAL_DROP, Role.DROP_FIRST, Role.CREATE),
        steps.stream().map(Step::role).toList());
    var trial = steps.get(1).sql();
    assertTrue(
        trial.matches(
            "CREATE DEFINER=`root`@`%` PROCEDURE hooridb_check_[0-9a-f]{12}\\(\\)" + " SELECT 2"),
        trial);
    var name = trial.substring(trial.indexOf("hooridb_check_"), trial.indexOf('('));
    assertEquals("DROP PROCEDURE IF EXISTS shop." + name, steps.get(2).sql());
    assertEquals("DROP PROCEDURE IF EXISTS shop.p", steps.get(3).sql());
    assertEquals(
        Optional.of(new Routines.Restore(
            P_SOURCE.statement(), Optional.of("CREATE PROCEDURE `p`() SELECT 1"))),
        change.restore());
  }

  @Test
  void saveWhereTheServerDropsFirstRefusesATextOfAnotherRoutine() {
    for (var other : List.of(
        "CREATE PROCEDURE q() SELECT 2",
        "CREATE FUNCTION p() RETURNS INT RETURN 2",
        "CREATE PROCEDURE other.p() SELECT 2",
        "SELECT 2")) {
      var refused = Routines.refused(Provider.MYSQL, P, other);
      assertTrue(refused.isPresent(), other);
      assertThrows(
          IllegalArgumentException.class, () -> Routines.save(Provider.MYSQL, P, P_SOURCE, other));
    }
    // Its name in another case, quoted or with its database, is its own.
    for (var same : List.of(
        "CREATE PROCEDURE `P`() SELECT 2",
        "CREATE PROCEDURE shop.p() SELECT 2",
        "/* x */ create"
            + " definer = current_user procedure if not exists `shop`.`p`() SELECT 2")) {
      assertEquals(Optional.empty(), Routines.refused(Provider.MYSQL, P, same), same);
    }
    // Where the server replaces in one step, another name only makes another routine.
    assertEquals(
        Optional.empty(), Routines.refused(Provider.MARIADB, P, "CREATE PROCEDURE q() SELECT 2"));
  }

  @Test
  void aFailedTrialDropsItsRoutineAndLeavesTheRoutineAsItWas() {
    var change = Routines.save(Provider.MYSQL, P, P_SOURCE, "CREATE PROCEDURE p() SELECT +");
    var sent = new ArrayList<Role>();
    var trialFails = server(change, sent, Role.TRIAL);
    var failed =
        assertInstanceOf(Routines.Outcome.Failed.class, Routines.apply(trialFails, change, ok()));
    assertEquals(Role.TRIAL, failed.step().role());
    assertEquals(Optional.empty(), failed.put());
    assertEquals(List.of(Role.SESSION, Role.TRIAL, Role.TRIAL_DROP), sent);
  }

  @Test
  void aCreateThatFailsAfterTheDropPutsTheRoutineBackWithoutItsDefinerWhereItMust() {
    var change = Routines.save(Provider.MYSQL, P, P_SOURCE, "CREATE PROCEDURE p() SELECT 2");
    var restore = change.restore().orElseThrow();
    var sent = new ArrayList<String>();
    // As it was.
    var outcome = Routines.apply(fails(sent, change.statement(Role.CREATE).get()), change, ok());
    assertEquals(
        Optional.of(new Routines.PutBack.AsItWas()),
        assertInstanceOf(Routines.Outcome.Failed.class, outcome).put());
    assertEquals(restore.source(), sent.getLast());
    // Without its definer, which the server does not let the user name.
    sent.clear();
    outcome = Routines.apply(
        fails(sent, change.statement(Role.CREATE).get(), restore.source()), change, ok());
    var put = assertInstanceOf(Routines.Outcome.Failed.class, outcome).put().orElseThrow();
    assertInstanceOf(Routines.PutBack.WithoutDefiner.class, put);
    assertEquals(restore.withoutDefiner().get(), sent.getLast());
    // Not at all.
    sent.clear();
    outcome = Routines.apply(
        fails(
            sent,
            change.statement(Role.CREATE).get(),
            restore.source(),
            restore.withoutDefiner().get()),
        change,
        ok());
    put = assertInstanceOf(Routines.Outcome.Failed.class, outcome).put().orElseThrow();
    assertInstanceOf(Routines.PutBack.Not.class, put);
  }

  @Test
  void aCancelStopsTheChangeUntilTheRoutineIsDropped() {
    var change = Routines.save(Provider.MYSQL, P, P_SOURCE, "CREATE PROCEDURE p() SELECT 2");
    // Cancelled as the trial runs: its routine is dropped, the routine is not.
    var applying = new Routines.Applying();
    var sent = new ArrayList<String>();
    var outcome = Routines.apply(
        sql -> {
          sent.add(sql);
          if (sql.equals(change.statement(Role.TRIAL).get())) {
            assertTrue(applying.cancel());
            assertTrue(Thread.interrupted(), "the statement under way is interrupted");
            return Optional.of(Failure.cancelled());
          }
          return Optional.empty();
        },
        change,
        applying);
    var failed = assertInstanceOf(Routines.Outcome.Failed.class, outcome);
    assertEquals(Role.TRIAL, failed.step().role());
    assertEquals(Failure.Kind.CANCELLED, failed.failure().kind());
    assertTrue(sent.contains(change.statement(Role.TRIAL_DROP).get()), sent.toString());
    assertFalse(sent.contains(change.statement(Role.DROP_FIRST).get()), sent.toString());
    assertFalse(Thread.currentThread().isInterrupted());
    // Once the DROP is sent, a cancel changes nothing, and the CREATE runs.
    var firm = new Routines.Applying();
    var created = new ArrayList<String>();
    outcome = Routines.apply(
        sql -> {
          created.add(sql);
          if (sql.equals(change.statement(Role.DROP_FIRST).get())) {
            assertFalse(firm.cancel());
            assertTrue(firm.firm());
          }
          return Optional.empty();
        },
        change,
        firm);
    assertEquals(new Routines.Outcome.Done(), outcome);
    assertEquals(change.statement(Role.CREATE).get(), created.getLast());
  }

  @Test
  void aPlaceInTheTrialIsThePlaceInTheText() {
    var text = "CREATE PROCEDURE p() SELECT +";
    var change = Routines.save(Provider.MYSQL, P, P_SOURCE, text);
    var trial = change.statement(Role.TRIAL).get();
    int at = trial.indexOf('+');
    var failure = new Failure(
        Failure.Kind.INVALID, "near +", "42000", 1064, at + 1, "", 0, 0, "", "", "", "");
    var failed =
        new Routines.Outcome.Failed(new Step(Role.TRIAL, trial), failure, Optional.empty());
    var place = Routines.place(change, failed, Provider.POSTGRESQL).orElseThrow();
    assertEquals(text.indexOf('+'), place.start());
    // One in its name is the text's name.
    var named = new Failure(
        Failure.Kind.INVALID,
        "",
        "42000",
        1064,
        trial.indexOf("hooridb") + 3,
        "",
        0,
        0,
        "",
        "",
        "",
        "");
    var name = Routines.place(
            change,
            new Routines.Outcome.Failed(new Step(Role.TRIAL, trial), named, Optional.empty()),
            Provider.POSTGRESQL)
        .orElseThrow();
    assertEquals(new Failure.Place(text.indexOf("p("), text.indexOf("p(") + 1), name);
  }

  // A server whose statements all run, but those of the roles given, which fail; what it was sent
  // is added to the roles.
  private static Routines.Sender server(Routines.Change change, List<Role> sent, Role... failing) {
    return sql -> {
      var step = change.steps().stream().filter(s -> s.sql().equals(sql)).findFirst();
      var role = step.map(Step::role).orElse(null);
      sent.add(role);
      return List.of(failing).contains(role)
          ? Optional.of(failure(Failure.Kind.INVALID))
          : Optional.empty();
    };
  }

  // A server where the statements given fail as the user may not name a definer, the CREATE first.
  private static Routines.Sender fails(List<String> sent, String... failing) {
    return sql -> {
      sent.add(sql);
      return List.of(failing).contains(sql)
          ? Optional.of(failure(Failure.Kind.ACCESS_DENIED))
          : Optional.empty();
    };
  }

  private static Failure failure(Failure.Kind kind) {
    return new Failure(kind, "refused", "42000", 1227, 0, "", 0, 0, "", "", "", "");
  }

  private static Routines.Applying ok() {
    return new Routines.Applying();
  }

  @Test
  void theRoutineSavedIsFoundByTheTextsNameNotByWhatElseAppeared() {
    var pg = Provider.POSTGRESQL;
    var add = new Routine(
        PUBLIC,
        "add",
        "a integer",
        Kind.FUNCTION,
        "integer",
        List.of(new Parameter("a", "integer", Mode.IN)));
    var other = new Routine(PUBLIC, "other", "", Kind.FUNCTION, "integer", List.of());
    var body = "CREATE OR REPLACE FUNCTION public.add(a integer) RETURNS integer"
        + " LANGUAGE sql AS $$ SELECT a + 1 $$";
    // Only the body changed, while someone else created another routine: it is the one replaced.
    assertEquals(
        Optional.of(add), Routines.saved(pg, add, body, List.of(add), List.of(add, other)));
    // The text added an overload of its name: that one, also where another appeared too.
    var overload = new Routine(
        PUBLIC,
        "add",
        "a bigint",
        Kind.FUNCTION,
        "integer",
        List.of(new Parameter("a", "bigint", Mode.IN)));
    assertEquals(
        Optional.of(overload),
        Routines.saved(
            pg,
            add,
            body.replace("a integer", "a bigint"),
            List.of(add),
            List.of(add, overload, other)));
    // A new routine, created by its name and kind; a procedure of that name is not it.
    var made = new Routine(PUBLIC, "made", "", Kind.FUNCTION, "integer", List.of());
    var procedure = new Routine(PUBLIC, "made", "", Kind.PROCEDURE, "", List.of());
    assertEquals(
        Optional.of(made),
        Routines.saved(
            pg,
            null,
            "CREATE FUNCTION made() RETURNS integer LANGUAGE sql AS $$ SELECT 1 $$",
            List.of(),
            List.of(other, procedure, made)));
    // Where two of its name appeared, it cannot tell which.
    assertEquals(
        Optional.empty(),
        Routines.saved(
            pg, add, body.replace("a integer", "a bigint"), List.of(), List.of(add, overload)));
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
    assertEquals(Optional.empty(), Provider.POSTGRESQL.dialect().routines().dropFirst(add));
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
    var routines = Provider.MYSQL.dialect().routines();
    var tooFew = List.of("4");
    assertThrows(IllegalArgumentException.class, () -> routines.call(mysql, tooFew));
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

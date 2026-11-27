package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.routine.Call;
import dev.hoori.db.core.sql.Names;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL's routines: functions, procedures since 11, and aggregates, from {@code pg_proc}, told
 * apart by their identity arguments, as {@code pg_get_function_identity_arguments} writes them.
 */
final class PostgreSqlRoutines implements RoutineDialect {

  private static final String EQ = " OPERATOR(pg_catalog.=) ";

  // The routine p by the names of its schema and its own, and its identity arguments.
  private static final String NAMED = " FROM pg_catalog.pg_proc p"
      + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "p.pronamespace"
      + " WHERE n.nspname" + EQ + "? AND p.proname" + EQ + "?"
      + " AND pg_catalog.pg_get_function_identity_arguments(p.oid)" + EQ + "?";

  // A parameter's mode from proargmodes, where all are IN when it is NULL; a column of RETURNS
  // TABLE is an OUT one.
  private static final String MODE = "CASE"
      + " WHEN COALESCE(p.proargmodes[a.ordinal], 'i')" + EQ
      + "ANY ('{o,t}'::pg_catalog.\"char\"[])"
      + " THEN 'OUT'"
      + " WHEN p.proargmodes[a.ordinal]" + EQ + "'b' THEN 'INOUT'"
      + " WHEN p.proargmodes[a.ordinal]" + EQ + "'v' THEN 'VARIADIC'"
      + " ELSE 'IN' END";

  // The types that a call's other values decide, as anyelement, and the pseudo-type of any value.
  private static final Set<String> POLYMORPHIC = Set.of(
      "anyelement",
      "anyarray",
      "anynonarray",
      "anyenum",
      "anyrange",
      "anymultirange",
      "anycompatible",
      "anycompatiblearray",
      "anycompatiblenonarray",
      "anycompatiblerange",
      "anycompatiblemultirange",
      "\"any\"");

  // What a function returns whose columns the caller names, where no OUT parameter names them.
  private static final Set<String> RECORDS = Set.of("record", "SETOF record");

  @Override
  public String routines() {
    return "SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid),"
        + " CASE WHEN p.prokind" + EQ + "ANY ('{p,a,w}'::pg_catalog.\"char\"[])"
        + " THEN p.prokind::pg_catalog.text ELSE 'f' END,"
        + " CASE WHEN p.prokind" + EQ + "'p' THEN NULL"
        + " ELSE pg_catalog.pg_get_function_result(p.oid) END,"
        + " p.oid::pg_catalog.text,"
        + " CASE WHEN a.ordinal IS NOT NULL THEN COALESCE(p.proargnames[a.ordinal], '') END,"
        + " pg_catalog.format_type(a.argument, NULL),"
        + " CASE WHEN a.ordinal IS NOT NULL THEN " + MODE + " END"
        + " FROM pg_catalog.pg_proc p"
        + " JOIN pg_catalog.pg_namespace n ON n.oid" + EQ + "p.pronamespace"
        + " LEFT JOIN LATERAL pg_catalog.unnest("
        + "COALESCE(p.proallargtypes, p.proargtypes::pg_catalog.oid[]))"
        + " WITH ORDINALITY AS a (argument, ordinal) ON true"
        + " WHERE n.nspname" + EQ + "?"
        // Not a member of an extension, as PostGIS installs a thousand into public.
        + " AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d"
        + " WHERE d.classid" + EQ + "'pg_catalog.pg_proc'::pg_catalog.regclass"
        + " AND d.objid" + EQ + "p.oid AND d.deptype" + EQ + "'e')"
        + " ORDER BY p.proname, 2, p.oid, a.ordinal";
  }

  @Override
  public Lookup source(Routine routine) {
    return new Lookup(
        "SELECT pg_catalog.pg_get_functiondef(p.oid)" + NAMED,
        List.of(routine.container().name(), routine.name(), routine.signature()));
  }

  // pg_get_functiondef writes CREATE OR REPLACE, with a line break at its end, and the settings
  // the routine keeps as SET clauses of its own.
  @Override
  public Routine.Source created(ResultSet rows) throws SQLException {
    var text = rows.getString(1);
    return text == null ? null : Routine.Source.of(text.stripTrailing());
  }

  @Override
  public Optional<String> dropFirst(Routine routine) {
    return Optional.empty();
  }

  // Names are compared as written once unquoted, which is how the head reads them.
  @Override
  public boolean sameName(String name, String other) {
    return name.equals(other);
  }

  @Override
  public String drop(Routine routine) {
    return "DROP " + word(routine.kind()) + " " + name(routine) + "(" + routine.signature() + ")";
  }

  @Override
  public List<Call> call(Routine routine, List<String> values) {
    var arguments = routine.arguments();
    if (uncalled(routine).isPresent() || values.size() != arguments.size()) {
      throw new IllegalArgumentException(
          values.size() + " values for " + arguments.size() + " arguments of " + routine.label());
    }
    // Each value is text that the server reads as the parameter's type, so that an overload is
    // found by its types; an OUT parameter of a procedure is given a NULL of its type.
    var parts = new ArrayList<String>();
    for (var parameter : routine.parameters()) {
      if (parameter.takes()) {
        var cast = "CAST(? AS " + parameter.type() + ")";
        parts.add(parameter.mode() == Routine.Mode.VARIADIC ? "VARIADIC " + cast : cast);
      } else if (routine.kind() == Routine.Kind.PROCEDURE) {
        parts.add("CAST(NULL AS " + parameter.type() + ")");
      }
    }
    var list = String.join(", ", parts);
    var sql = routine.kind() == Routine.Kind.PROCEDURE
        ? "CALL " + name(routine) + "(" + list + ")"
        // As a source of rows: the values of OUT parameters, of SETOF and of TABLE as columns.
        : "SELECT * FROM " + name(routine) + "(" + list + ")";
    return List.of(new Call(sql, values));
  }

  @Override
  public Optional<String> uncalled(Routine routine) {
    switch (routine.kind()) {
      case AGGREGATE -> {
        return Optional.of("An aggregate runs over the rows of a query, as in SELECT "
            + routine.name() + "(x) FROM t, not by a call of its own.");
      }
      case WINDOW -> {
        return Optional.of(
            "A window function runs over the rows of a query's window, as in" + " SELECT "
                + routine.name() + "() OVER (ORDER BY x) FROM t, not by a call of its own.");
      }
      case FUNCTION, PROCEDURE -> {
        // As it says below.
      }
    }
    if (routine.returns().equals("trigger") || routine.returns().equals("event_trigger")) {
      return Optional.of("A trigger function runs as its trigger fires, not by a call.");
    }
    if (RECORDS.contains(routine.returns())
        && routine.parameters().stream().noneMatch(Routine.Parameter::gives)) {
      return Optional.of("It returns rows of a type of record, whose columns a query names as it"
          + " calls it, as in SELECT * FROM " + routine.name() + "(…) AS r(a integer).");
    }
    for (var parameter : routine.parameters()) {
      var type = parameter.type();
      var element = type.endsWith("[]") ? type.substring(0, type.length() - 2) : type;
      if (POLYMORPHIC.contains(element)) {
        return Optional.of(
            "Its argument " + (parameter.name().isEmpty() ? "" : parameter.name() + " ")
                + "is of the type " + type + ", which the types of a call's values decide: call it"
                + " in a console, with a value of a type, as " + routine.name() + "(1).");
      }
    }
    return Optional.empty();
  }

  // The types of text, whose empty value is one: an empty string of bytes is written '\x'.
  @Override
  public boolean takesEmpty(String type) {
    int paren = type.indexOf('(');
    var base = (paren < 0 ? type : type.substring(0, paren)).strip();
    return switch (base) {
      case "text",
          "character varying",
          "varchar",
          "character",
          "char",
          "bpchar",
          "name",
          "citext" -> true;
      default -> false;
    };
  }

  @Override
  public String template(Routine.Kind kind, Container container) {
    var schema = quoted(container.name()) + ".";
    return switch (kind) {
      case FUNCTION, AGGREGATE, WINDOW -> """
        CREATE FUNCTION %snew_function(a integer, b integer)
        RETURNS integer
        LANGUAGE sql
        IMMUTABLE
        AS $$
          SELECT a + b
        $$""".formatted(schema);
      case PROCEDURE -> """
        CREATE PROCEDURE %snew_procedure(a integer)
        LANGUAGE plpgsql
        AS $$
        BEGIN
          RAISE NOTICE 'a is %%', a;
        END
        $$""".formatted(schema);
    };
  }

  private static String word(Routine.Kind kind) {
    return switch (kind) {
      case FUNCTION -> "FUNCTION";
      case PROCEDURE -> "PROCEDURE";
      case AGGREGATE -> "AGGREGATE";
      case WINDOW -> "FUNCTION";
    };
  }

  private static String name(Routine routine) {
    return quoted(routine.container().name()) + "." + quoted(routine.name());
  }

  private static String quoted(String name) {
    return Names.quoted(name, Provider.POSTGRESQL);
  }
}

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
  private static final String MODE = "CASE COALESCE(p.proargmodes[a.ordinal], 'i')"
      + " WHEN 'o' THEN 'OUT' WHEN 't' THEN 'OUT' WHEN 'b' THEN 'INOUT' WHEN 'v' THEN 'VARIADIC'"
      + " ELSE 'IN' END";

  @Override
  public String routines() {
    return "SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid),"
        + " CASE p.prokind WHEN 'p' THEN 'p' WHEN 'a' THEN 'a' ELSE 'f' END,"
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
        + " ORDER BY p.proname, 2, p.oid, a.ordinal";
  }

  @Override
  public Lookup source(Routine routine) {
    return new Lookup(
        "SELECT pg_catalog.pg_get_functiondef(p.oid)" + NAMED,
        List.of(routine.container().name(), routine.name(), routine.signature()));
  }

  // pg_get_functiondef writes CREATE OR REPLACE, with a line break at its end.
  @Override
  public String created(ResultSet rows) throws SQLException {
    var text = rows.getString(1);
    return text == null ? null : text.stripTrailing();
  }

  @Override
  public boolean replaces() {
    return true;
  }

  @Override
  public String dropFirst(Routine routine) {
    throw new UnsupportedOperationException("PostgreSQL replaces a routine in one step");
  }

  @Override
  public String drop(Routine routine) {
    return "DROP " + word(routine.kind()) + " " + name(routine) + "(" + routine.signature() + ")";
  }

  @Override
  public List<Call> call(Routine routine, List<String> values) {
    var arguments = routine.arguments();
    if (routine.kind() == Routine.Kind.AGGREGATE || values.size() != arguments.size()) {
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
  public String template(Routine.Kind kind, Container container) {
    var schema = quoted(container.name()) + ".";
    return switch (kind) {
      case FUNCTION, AGGREGATE -> """
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
    };
  }

  private static String name(Routine routine) {
    return quoted(routine.container().name()) + "." + quoted(routine.name());
  }

  private static String quoted(String name) {
    return Names.quoted(name, Provider.POSTGRESQL);
  }
}

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
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The routines of MariaDB and MySQL, from {@code information_schema}: functions and procedures,
 * without overloads, so that a routine is known by its kind and name. Their source is what {@code
 * SHOW CREATE} gives, which names the routine without its database: it runs on a session on that
 * database. MariaDB replaces a routine in one step with {@code CREATE OR REPLACE}; MySQL has none,
 * so the routine is dropped first.
 */
final class MySqlFamilyRoutines implements RoutineDialect {

  // Asked for as names are quoted, not as the dialect is made, which the provider's own making may
  // be what makes it.
  private final Supplier<Provider> provider;
  private final boolean orReplace;

  /**
   * @param orReplace whether the server takes {@code CREATE OR REPLACE} of a routine
   */
  MySqlFamilyRoutines(Supplier<Provider> provider, boolean orReplace) {
    this.provider = provider;
    this.orReplace = orReplace;
  }

  // The parameter at position 0 is a function's result, which ROUTINES has too.
  @Override
  public String routines() {
    return "SELECT r.ROUTINE_NAME, '', CASE r.ROUTINE_TYPE WHEN 'PROCEDURE' THEN 'p' ELSE 'f' END,"
        + " CASE WHEN r.ROUTINE_TYPE = 'FUNCTION' THEN r.DTD_IDENTIFIER END,"
        + " CONCAT(r.ROUTINE_TYPE, ' ', r.ROUTINE_NAME),"
        + " p.PARAMETER_NAME, p.DTD_IDENTIFIER,"
        + " CASE WHEN p.ORDINAL_POSITION IS NOT NULL THEN COALESCE(p.PARAMETER_MODE, 'IN') END"
        + " FROM information_schema.ROUTINES r"
        + " LEFT JOIN information_schema.PARAMETERS p"
        + " ON p.SPECIFIC_SCHEMA = r.ROUTINE_SCHEMA AND p.SPECIFIC_NAME = r.SPECIFIC_NAME"
        + " AND p.ROUTINE_TYPE = r.ROUTINE_TYPE AND p.ORDINAL_POSITION > 0"
        + " WHERE r.ROUTINE_SCHEMA = ? AND r.ROUTINE_TYPE IN ('FUNCTION', 'PROCEDURE')"
        + " ORDER BY r.ROUTINE_NAME, r.ROUTINE_TYPE, p.ORDINAL_POSITION";
  }

  @Override
  public Lookup source(Routine routine) {
    return new Lookup("SHOW CREATE " + word(routine.kind()) + " " + name(routine), List.of());
  }

  // Its third column, Create Function or Create Procedure, is NULL where the user has no right to
  // see the routine's body.
  @Override
  public String created(ResultSet rows) throws SQLException {
    var text = rows.getString(3);
    if (text == null) {
      return null;
    }
    text = text.strip();
    if (orReplace && text.regionMatches(true, 0, "CREATE ", 0, 7)) {
      return text.substring(0, 7) + "OR REPLACE " + text.substring(7);
    }
    return text;
  }

  @Override
  public boolean replaces() {
    return orReplace;
  }

  @Override
  public String dropFirst(Routine routine) {
    return "DROP " + word(routine.kind()) + " IF EXISTS " + name(routine);
  }

  @Override
  public String drop(Routine routine) {
    return "DROP " + word(routine.kind()) + " " + name(routine);
  }

  // A function is a value of a query. A procedure's OUT and INOUT parameters are variables of the
  // session, which the CALL sets and a query after it reads, as their clients do: the server takes
  // no ? for them.
  @Override
  public List<Call> call(Routine routine, List<String> values) {
    var arguments = routine.arguments();
    if (values.size() != arguments.size()) {
      throw new IllegalArgumentException(
          values.size() + " values for " + arguments.size() + " arguments of " + routine.label());
    }
    if (routine.kind() == Routine.Kind.FUNCTION) {
      var marks = String.join(", ", values.stream().map(v -> "?").toList());
      return List.of(new Call(
          "SELECT " + name(routine) + "(" + marks + ") AS " + quoted(routine.name()), values));
    }
    var calls = new ArrayList<Call>();
    var parts = new ArrayList<String>();
    var bound = new ArrayList<String>();
    var given = new ArrayList<String>();
    int next = 0;
    for (var parameter : routine.parameters()) {
      var variable = "@" + quoted(parameter.name());
      switch (parameter.mode()) {
        case IN, VARIADIC -> {
          parts.add("?");
          bound.add(values.get(next++));
        }
        case INOUT -> {
          calls.add(new Call("SET " + variable + " = ?", listOf(values.get(next++))));
          parts.add(variable);
          given.add(variable + " AS " + quoted(parameter.name()));
        }
        case OUT -> {
          parts.add(variable);
          given.add(variable + " AS " + quoted(parameter.name()));
        }
      }
    }
    calls.add(new Call("CALL " + name(routine) + "(" + String.join(", ", parts) + ")", bound));
    if (!given.isEmpty()) {
      calls.add(Call.of("SELECT " + given.stream().collect(Collectors.joining(", "))));
    }
    return List.copyOf(calls);
  }

  @Override
  public String template(Routine.Kind kind, Container container) {
    var database = quoted(container.name()) + ".";
    return switch (kind) {
      case FUNCTION, AGGREGATE -> """
        CREATE FUNCTION %snew_function(a INT, b INT)
        RETURNS INT
        DETERMINISTIC
        RETURN a + b""".formatted(database);
      case PROCEDURE -> """
        CREATE PROCEDURE %snew_procedure(IN a INT)
        BEGIN
          SELECT a;
        END""".formatted(database);
    };
  }

  private static List<String> listOf(String value) {
    var one = new ArrayList<String>(1);
    one.add(value); // NULL too
    return one;
  }

  private static String word(Routine.Kind kind) {
    return kind.name().toUpperCase(Locale.ROOT);
  }

  private String name(Routine routine) {
    return quoted(routine.container().name()) + "." + quoted(routine.name());
  }

  private String quoted(String name) {
    return Names.quoted(name, provider.get());
  }
}

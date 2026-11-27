package dev.hoori.db.core.catalog;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A function or a procedure, in the schema or database that holds it, with its parameters in their
 * order and what it returns. Overloads of one name are told apart by their signature.
 *
 * @param signature what tells the routine apart from others of its name and kind: on PostgreSQL
 *     its arguments as {@code pg_get_function_identity_arguments} writes them, such as {@code a
 *     integer, b text}, and empty where the server has no overloads
 * @param returns the type it returns as the server writes it, such as {@code integer} or {@code
 *     TABLE(id integer)}; empty for a procedure
 */
public record Routine(
    Container container,
    String name,
    String signature,
    Kind kind,
    String returns,
    List<Parameter> parameters) {

  public Routine {
    parameters = List.copyOf(parameters);
  }

  /** What a routine is. */
  public enum Kind {
    /** A function, called in a query; on PostgreSQL also a window function. */
    FUNCTION,
    /** A procedure, run with {@code CALL}. */
    PROCEDURE,
    /** An aggregate function of PostgreSQL, whose source the server does not write. */
    AGGREGATE
  }

  /** How a parameter passes its value. */
  public enum Mode {
    IN,
    OUT,
    INOUT,
    /** PostgreSQL's last parameter that takes any number of values, as an array. */
    VARIADIC
  }

  /**
   * A parameter of the routine.
   *
   * @param name its name, or empty where it has none
   * @param type its type as the server writes it, such as {@code character varying} or {@code
   *     varchar(20)}
   */
  public record Parameter(String name, String type, Mode mode) {

    /** Whether the caller gives it a value. */
    public boolean takes() {
      return mode != Mode.OUT;
    }

    /** Whether the routine gives a value back through it. */
    public boolean gives() {
      return mode == Mode.OUT || mode == Mode.INOUT;
    }
  }

  /** The parameters the caller gives a value for, in their order. */
  public List<Parameter> arguments() {
    return parameters.stream().filter(Parameter::takes).toList();
  }

  /**
   * Its name with the types of what tells it apart, as {@code add(integer, integer)}: the
   * parameters a caller gives, and those a procedure gives back, as {@code OUT bigint}, and a
   * variadic one as {@code VARIADIC integer[]}.
   */
  public String label() {
    return parameters.stream()
        .filter(p -> p.takes() || kind == Kind.PROCEDURE)
        .map(p -> switch (p.mode()) {
          case IN, INOUT -> p.type();
          case OUT -> "OUT " + p.type();
          case VARIADIC -> "VARIADIC " + p.type();
        })
        .collect(Collectors.joining(", ", name + "(", ")"));
  }
}

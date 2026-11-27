package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.routine.Call;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * How a provider's functions and procedures are listed, read, replaced, dropped and called.
 */
public interface RoutineDialect {

  /** A query of the catalog with the values of its parameters, in order. */
  record Lookup(String sql, List<String> parameters) {

    public Lookup {
      parameters = List.copyOf(parameters);
    }
  }

  /**
   * The routines of the schema or database named, its only parameter. Each row is one parameter of
   * a routine, those of one routine consecutive and in their order: the routine's name, its
   * signature, its kind ({@code f} for a function, {@code p} for a procedure, {@code a} for an
   * aggregate), what it returns or NULL for a procedure, a key that is the same in the rows of one
   * routine alone, and the parameter's name, type and mode ({@code IN}, {@code OUT}, {@code INOUT}
   * or {@code VARIADIC}). A routine without parameters has one row, whose last three are NULL.
   * Routines come by name, then by signature.
   */
  String routines();

  /** The query whose first row holds the routine's CREATE statement, as {@link #created} reads. */
  Lookup source(Routine routine);

  /**
   * The routine's CREATE statement from the row of {@link #source}, which replaces the routine as
   * it runs where it {@link #replaces}; null where the server withholds it from the user.
   */
  String created(ResultSet rows) throws SQLException;

  /**
   * Whether the statement {@link #created} gives replaces the routine in one step, as {@code CREATE
   * OR REPLACE} does; else the routine is dropped first, as {@link #dropFirst} says.
   */
  boolean replaces();

  /**
   * The statement that drops the routine where it still exists, before its CREATE statement runs
   * again with changes, where the server does not {@link #replaces replace} it in one step.
   */
  String dropFirst(Routine routine);

  /** The statement that drops the routine. */
  String drop(Routine routine);

  /**
   * The statements that call the routine with these values, one for each parameter the caller
   * gives in order, null for NULL; each statement with the values bound to its {@code ?}. Its
   * results, and the values its OUT parameters give back, come as the last statement's rows.
   *
   * @throws IllegalArgumentException if the routine is an aggregate, or the values are not one for
   *     each argument
   */
  List<Call> call(Routine routine, List<String> values);

  /** The CREATE statement of a new routine of the kind in the container, to start from. */
  String template(Routine.Kind kind, Container container);
}

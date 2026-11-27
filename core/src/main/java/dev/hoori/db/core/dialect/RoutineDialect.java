package dev.hoori.db.core.dialect;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.routine.Call;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

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
   * aggregate, {@code w} for a window function), what it returns or NULL for a procedure, a key
   * that is the same in the rows of one routine alone, and the parameter's name, type and mode
   * ({@code IN}, {@code OUT}, {@code INOUT} or {@code VARIADIC}). A routine without parameters has
   * one row, whose last three are NULL. Routines come by name, then by signature; those that an
   * extension installed, and keeps as its own, are left out.
   */
  String routines();

  /** The query whose first row holds the routine's CREATE statement, as {@link #created} reads. */
  Lookup source(Routine routine);

  /**
   * The routine's source from the row of {@link #source}: its CREATE statement, which replaces
   * the routine as it runs where it needs no {@link #dropFirst}, and the settings of the session it
   * was created in that the server keeps with it; null where the server withholds it from the
   * user.
   */
  Routine.Source created(ResultSet rows) throws SQLException;

  /**
   * The statement that drops the routine where it still exists, before its CREATE statement runs
   * again with changes, where the server does not replace a routine in one step; empty where it
   * does, as with {@code CREATE OR REPLACE}.
   */
  Optional<String> dropFirst(Routine routine);

  /**
   * Whether two names of routines name the same, as the server compares them: MariaDB and MySQL
   * by their letters whatever their case, PostgreSQL as they are.
   */
  boolean sameName(String name, String other);

  /** The statement that drops the routine. */
  String drop(Routine routine);

  /**
   * The statements that call the routine with these values, one for each parameter the caller
   * gives in order, null for NULL; each statement with the values bound to its {@code ?}. Its
   * results, and the values its OUT parameters give back, come as the last statement's rows.
   *
   * @throws IllegalArgumentException if the routine is {@link #uncalled}, or the values are not
   *     one for each argument
   */
  List<Call> call(Routine routine, List<String> values);

  /**
   * Why the routine is not called with values of a form, as an aggregate, which runs over the rows
   * of a query, or one whose arguments' types only a call's other values decide; empty where it
   * is.
   */
  Optional<String> uncalled(Routine routine);

  /**
   * Whether an empty text is a value of the type, as of a text type; an empty field of another
   * type is no value, which the form asks for, or NULL.
   */
  boolean takesEmpty(String type);

  /** The CREATE statement of a new routine of the kind in the container, to start from. */
  String template(Routine.Kind kind, Container container);
}

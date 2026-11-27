package dev.hoori.db.core.dialect;

/**
 * How a provider's servers are searched: the queries that read the objects of a database or schema
 * with their definitions, for a search of their names and texts, and the conditions that find a
 * value in a table's columns.
 *
 * <p>Each query of objects takes the container's name as its one parameter where it is {@link
 * Scope#CONTAINER}, the schema or the database that holds tables, and none where it is {@link
 * Scope#USER_SCHEMAS}, every schema of the connection's database that is not the server's own; its
 * rows come by schema, then by name. The conditions on text are those of the filters of a table,
 * {@link dev.hoori.db.core.data.Rows}'s.
 */
public interface FindDialect {

  /** What a query of objects reads. */
  enum Scope {
    /** The schema, or the database where the provider has no schemas, named by the parameter. */
    CONTAINER,
    /** Every schema of the connection's database that is not the server's own. */
    USER_SCHEMAS
  }

  /** What a column's values are, for which values it can hold. */
  enum Holds {
    /** Text, which a text is found in. */
    TEXT,
    /** Numbers held exactly, integers and decimals, which a number is found in as it is. */
    NUMBER,
    /**
     * Binary floating-point numbers of double precision, which a number is found in only where it
     * is the shortest decimal of a double, as 0.1 is, since no other one equals it, and which is
     * given to the condition as that double.
     */
    DOUBLE,
    /**
     * Binary floating-point numbers of single precision, which a number is found in only where it
     * is the shortest decimal of a float, compared as one, as a stored 0.1 is no double's 0.1.
     */
    FLOAT,
    /** Anything else, searched only as its text where the user chooses so. */
    OTHER
  }

  /**
   * The tables and views: the schema, the name, the kind as {@link CatalogDialect} gives it, the
   * server's estimate of the rows and the bytes of the data, each NULL where it has none, and
   * whether its rows are read with those of another the query lists, as a partition's are with its
   * partitioned table's.
   */
  String tables(Scope scope);

  /**
   * The columns of the tables and views: the schema, the table, the column and its type as its
   * values are held, which {@link #holds} reads: a domain's as the type it is over, an enum's as
   * text where the provider compares it so.
   */
  String columns(Scope scope);

  /** The views' definitions: the schema, the view and the text of its query. */
  String views(Scope scope);

  /**
   * The routines, functions and procedures, without those of extensions: the schema, the name, the
   * kind, {@code f} for a function and {@code p} for a procedure, and the text of its definition,
   * NULL where the user may not see it.
   */
  String routines(Scope scope);

  /** The triggers: the schema, the table, the trigger and the text of its definition. */
  String triggers(Scope scope);

  /** What the column of this type, as {@link CatalogDialect#columns} names it, holds. */
  Holds holds(String type);

  /**
   * The column's value as text, for the conditions of {@link dev.hoori.db.core.data.Rows} that find
   * a text in it, which take it as of type {@code text}: in a collation they can compare with,
   * whatever the column holds.
   */
  String asText(String column, Holds holds);

  /**
   * The condition that the number in the column, of the type that {@link #holds} reads, equals the
   * number, a literal of the server's: in the column's own type where it holds {@link
   * Holds#FLOAT}.
   */
  String equalNumber(String column, String type, String number);
}

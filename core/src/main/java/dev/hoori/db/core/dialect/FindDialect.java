package dev.hoori.db.core.dialect;

/**
 * How a provider's servers are searched: the queries that read the objects of a database or schema
 * with their definitions, for a search of their names and texts, and the conditions that find a
 * value in a table's columns.
 *
 * <p>Each query of objects takes the container's name as its one parameter where it is {@link
 * Scope#CONTAINER}, the schema or the database that holds tables, and none where it is {@link
 * Scope#USER_SCHEMAS}, every schema of the connection's database that is not the server's own; its
 * rows come by schema, then by name. A condition is written with a {@code ?} for each value it is
 * given, which {@link dev.hoori.db.core.find.Finder} binds.
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
    /** Numbers, which a number is found in. */
    NUMBER,
    /** Anything else, searched only as its text where the user chooses so. */
    OTHER
  }

  /**
   * Whether the case of a text search follows each column's collation, so that the search cannot
   * ask for it to be told apart or not, as with MariaDB's and MySQL's {@code LIKE}.
   */
  boolean caseByCollation();

  /**
   * The tables and views: the schema, the name, the kind as {@link CatalogDialect} gives it, the
   * server's estimate of the rows and the bytes of the data, each NULL where it has none.
   */
  String tables(Scope scope);

  /** The columns of the tables and views: the schema, the table, the column and its type. */
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

  /** The column's value as text, for a search of a column that does not hold text. */
  String asText(String column);

  /**
   * The condition that the column's text holds the value that {@link #contained} makes, case
   * sensitive or not, where {@link #caseByCollation} lets it say.
   */
  String contains(String column, boolean caseSensitive);

  /** The value that {@link #contains} looks for, with the text taken as it is. */
  String contained(String text);

  /** The condition that the column's text equals the value. */
  String equal(String column, boolean caseSensitive);

  /** The condition that the server's regular expression, the value, matches in the column's text. */
  String matches(String column, boolean caseSensitive);

  /** The condition that the number in the column equals the value, a number. */
  String equalNumber(String column);
}

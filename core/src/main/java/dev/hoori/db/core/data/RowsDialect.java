package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * How {@link Rows} reads a provider's tables and results: the estimate and the count, the order
 * and the checks of a page, and how each type's values are read; and how {@link Changes} writes
 * them back. A provider's dialect hands one out; what it does is this package's own, as it builds
 * on the ways {@link Rows} reads a value.
 */
public abstract class RowsDialect {

  RowsDialect() {}

  /** The planner's estimate of the rows that {@code select}, all rows of the table, gives. */
  abstract OptionalLong estimate(
      Connection connection, Provider provider, Table table, String select) throws QueryException;

  /** The expression that counts the rows. */
  abstract String count();

  /**
   * How the table's columns are read. Where the server is asked for them before the page, a read
   * fails as {@link QueryException.Failure#COLUMNS_CHANGED} where they are not the given ones.
   */
  abstract Rows.Described describe(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws QueryException;

  /**
   * Whether a page brings the table's columns as they are now with its rows, from a join to none of
   * them, which {@link #witnessed} then checks.
   */
  abstract boolean witness();

  /**
   * The page's fields, the reads' own or of kind {@link Kind#JSON} where only the witness tells
   * that, where the witness's columns, after the {@code selected} expressions of the reads, are the
   * given columns, by name and in order, each read the way its catalog type is read; null where
   * they are not.
   */
  abstract List<Field> witnessed(
      List<Column> columns, List<Rows.Read> reads, ResultSetMetaData metadata, int selected)
      throws SQLException;

  /**
   * The expressions of where each of the table's rows is stored, which order a page where nothing
   * else does; empty where it has none to rely on.
   */
  abstract List<String> places(Table table);

  /**
   * Whether the table, as {@link Rows.Described#table} names it, has or had children that inherit
   * from it, which may repeat its keys. Asked only of a table with places and a primary key.
   */
  abstract boolean children(Connection connection, Provider provider, long table)
      throws QueryException;

  /** How the column of a result, or of the table as {@link #describe} reads it, is read. */
  abstract Rows.Read read(Rows.Source source, ResultSetMetaData metadata, int column)
      throws SQLException;

  /**
   * The table column that the column of a statement's result was read from, without querying the
   * server; null where it has none, such as an expression or a system column.
   */
  abstract Origin origin(ResultSetMetaData metadata, int column) throws SQLException;

  /**
   * Whether a session's temporary table can shadow a permanent table of the same name in result
   * metadata, so that another connection cannot tell them apart.
   */
  abstract boolean temporaryTablesShadow();

  /** A resolved base table and how an origin of one of its columns names that column. */
  record Resolved(Table table, Function<Origin, String> columnName) {}

  /**
   * Resolves the origin's table on the given connection to a permanent base table and a mapping
   * from each origin of that table to its column name; empty where it is not a permanent base
   * table (such as a view, materialized view, foreign table or temporary table) or no longer
   * exists.
   */
  abstract Optional<Resolved> resolve(Connection connection, Provider provider, Origin sample)
      throws QueryException;

  /** Whether a result field of the first kind edits a table column described as the second. */
  boolean compatibleKind(Kind result, Kind table) {
    return result == table;
  }

  /** The operator that compares a column of a key with its value, as the reads name theirs. */
  abstract String keyEquality();

  /**
   * Binds the value, of the Java type of its kind or null, to the parameter, so that the server
   * takes it for the value that was read.
   *
   * @throws IllegalArgumentException if it is of no kind's type
   */
  abstract void bind(PreparedStatement statement, int index, Object value) throws SQLException;

  /**
   * The value as a literal of the provider's SQL, which its server reads back as the value: for
   * people to read, and in an export's {@code INSERT}s. A text is quoted as the server reads it:
   * on PostgreSQL, one with a backslash as {@code E'…'}, whatever {@code
   * standard_conforming_strings} says; on MariaDB and MySQL with backslashes as escapes.
   *
   * @throws IllegalArgumentException if it is of no kind's type
   */
  public abstract String literal(Object value);

  /** A case-insensitive {@code LIKE} of the quoted column as text, {@code !} escaping. */
  abstract String like(String column, Field field, String pattern);

  /** The quoted column as text equal to {@code value}, whatever the case. */
  abstract String equalsIgnoringCase(String column, Field field, String value);

  /** The quoted column as text equal to {@code value}, case and all. */
  abstract String equalsExactly(String column, Field field, String value);

  /** A case-insensitive regular expression match of the quoted column as text. */
  abstract String matches(String column, Field field, String expression);

  /**
   * The quoted column compared by {@code operator} ({@code =}, {@code >=} or {@code <=}) with a
   * value of its kind, at the precision the column keeps.
   */
  String compare(String column, Field field, String operator, Object value) {
    return column + " " + operator + " " + literal(value);
  }

  /**
   * The condition builder operators offered for a column of {@code field}'s kind, in display
   * order. Columns that cannot be filtered offer only the null checks.
   */
  List<ColumnFilter.Operator> operators(Field field) {
    if (field.kind() == null) {
      return List.of(ColumnFilter.Operator.IS_NULL, ColumnFilter.Operator.IS_NOT_NULL);
    }
    return switch (field.kind()) {
      case TEXT ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.CONTAINS,
            ColumnFilter.Operator.STARTS_WITH,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL);
      case INTEGER, DECIMAL, DATE, TIME, DATE_TIME, DATE_TIME_ZONED ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.AT_LEAST,
            ColumnFilter.Operator.AT_MOST,
            ColumnFilter.Operator.BETWEEN,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL);
      case BOOLEAN ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL);
      case JSON, OTHER, BINARY ->
        List.of(ColumnFilter.Operator.IS_NULL, ColumnFilter.Operator.IS_NOT_NULL);
    };
  }

  /** The parameter that takes the value in a statement: {@code ?}, or an expression of it. */
  abstract String parameter(Object value);

  /**
   * The condition that the quoted column still holds the value as it was read, with any value
   * bound through {@code parameter}.
   */
  abstract String unchanged(String column, Object value, Function<Object, String> parameter);

  /**
   * Whether changes of the table's rows, with the values bound, can be all or nothing, each meeting
   * the row its key names: not where a rollback would not undo them, where the table's statements
   * also meet rows of other tables, which may repeat its keys, or where the server takes a value
   * for another one.
   */
  abstract boolean changeable(
      Connection connection, Provider provider, Table table, List<Object> values)
      throws QueryException;
}

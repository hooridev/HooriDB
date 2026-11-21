package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.Rows.ALIAS;
import static dev.hoori.db.core.data.Rows.LIMIT;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;

/** How PostgreSQL's tables and results are read, with pgjdbc. */
public final class PostgreSqlRows extends RowsDialect {

  // PostgreSQL's built-in types, by the OIDs it gives them for good; a user's type may have any of
  // their names.
  private static final int BOOL = 16;
  private static final int BYTEA = 17;
  private static final int NAME = 19;
  private static final int INT8 = 20;
  private static final int INT2 = 21;
  private static final int INT4 = 23;
  private static final int TEXT = 25;
  private static final int OID = 26;
  private static final int JSON = 114;
  private static final int FLOAT4 = 700;
  private static final int FLOAT8 = 701;
  private static final int BPCHAR = 1042;
  private static final int VARCHAR = 1043;
  private static final int DATE = 1082;
  private static final int TIME_OF_DAY = 1083;
  private static final int TIMESTAMP = 1114;
  private static final int TIMESTAMPTZ = 1184;
  private static final int TIMETZ = 1266;
  private static final int NUMERIC = 1700;
  private static final int JSONB = 3802;

  public PostgreSqlRows() {
    // Nothing to set up.
  }

  // The first line of the plan is its top: "Seq Scan on t  (cost=0.00..19.00 rows=900 ...".
  @Override
  OptionalLong estimate(Connection connection, Provider provider, Table table, String select)
      throws QueryException {
    return Query.query(
        connection,
        provider,
        "EXPLAIN " + select,
        rows -> Rows.planned(rows.next() ? rows.getString(1) : ""));
  }

  @Override
  String count() {
    return "pg_catalog.count(*)";
  }

  // PostgreSQL's types are told from what pgjdbc reports of a query that reads no row, which is
  // the base type of a domain.
  @Override
  Rows.Described describe(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws QueryException {
    // All the table's columns, so that the given ones can be told to be them still.
    var sql = "SELECT " + ALIAS + ".* FROM " + Rows.qualified(provider, table) + " AS " + ALIAS
        + " WHERE false";
    var described = Query.query(connection, provider, sql, rows -> {
      var metadata = rows.getMetaData();
      if (metadata.getColumnCount() != columns.size()) {
        return null;
      }
      for (var i = 0; i < columns.size(); i++) {
        if (!metadata.getColumnLabel(i + 1).equals(columns.get(i).name())) {
          return null;
        }
      }
      if (columns.isEmpty()) {
        return new Rows.Described(List.of(), 0);
      }
      var reads = new ArrayList<Rows.Read>();
      for (var i = 0; i < columns.size(); i++) {
        reads.add(read(Rows.source(provider, columns.get(i)), metadata, i + 1));
      }
      return new Rows.Described(reads, Integer.toUnsignedLong(field(metadata, 1, "getTableOid")));
    });
    if (described == null) {
      throw Rows.changed(table);
    }
    return described;
  }

  // PostgreSQL's values are read by the types the server reports for that very read.
  @Override
  boolean witness() {
    return false;
  }

  @Override
  List<Field> witnessed(
      List<Column> columns, List<Rows.Read> reads, ResultSetMetaData metadata, int selected) {
    return reads.stream().map(Rows.Read::field).toList(); // never asked, without a witness
  }

  // Real places of rows: a foreign table's may all be the same, and a view has none.
  @Override
  List<String> places(Table table) {
    return table.kind() == Table.Kind.TABLE
        ? List.of(ALIAS + ".tableoid", ALIAS + ".ctid")
        : List.of();
  }

  // Whether the PostgreSQL table has or had children, or may have, where its OID is not known.
  @Override
  boolean children(Connection connection, Provider provider, long table) throws QueryException {
    if (table == 0) {
      return true;
    }
    var sql = "SELECT c.relhassubclass AND c.relkind OPERATOR(pg_catalog.=) 'r'"
        + " FROM pg_catalog.pg_class c WHERE c.oid OPERATOR(pg_catalog.=) " + table;
    return Query.query(connection, provider, sql, rows -> !rows.next() || rows.getBoolean(1));
  }

  @Override
  Rows.Read read(Rows.Source source, ResultSetMetaData metadata, int column) throws SQLException {
    return read(source, field(metadata, column, "getOID"), metadata.getColumnType(column));
  }

  // pgjdbc's own field of the result has the OIDs of the column's type and table, which JDBC does
  // not give; where they cannot be read, 0 makes every column text, and a table one with children.
  private static int field(ResultSetMetaData metadata, int column, String getter) {
    try {
      var field = getField(metadata.getClass());
      field.setAccessible(true);
      var value = field.invoke(metadata, column);
      return (int) value.getClass().getMethod(getter).invoke(value);
    } catch (ReflectiveOperationException | RuntimeException _) {
      return 0;
    }
  }

  // The getField(int) of the type or of the nearest superclass that declares it.
  private static Method getField(Class<?> type) throws NoSuchMethodException {
    for (Class<?> declaring = type; declaring != null; declaring = declaring.getSuperclass()) {
      try {
        return declaring.getDeclaredMethod("getField", int.class);
      } catch (NoSuchMethodException _) {
        // Declared further up, if at all.
      }
    }
    throw new NoSuchMethodException("getField(int)");
  }

  private static Rows.Read read(Rows.Source source, int oid, int jdbcType) {
    return switch (oid) {
      case INT2, INT4, INT8, OID -> Rows.plain(source, Kind.INTEGER, Long::valueOf);
      case NUMERIC -> Rows.plain(source, Kind.DECIMAL, PostgreSqlRows::decimal);
      // shortcut: a float loses digits if a statement on the connection lowered pgjdbc's
      // extra_float_digits; read it in binary if the app ever lets users set it.
      case FLOAT4 -> Rows.plain(source, Kind.DECIMAL, Float::valueOf);
      case FLOAT8 -> Rows.plain(source, Kind.DECIMAL, Double::valueOf);
      case BOOL -> Rows.plain(source, Kind.BOOLEAN, "t"::equals);
      case TEXT, VARCHAR, NAME ->
        Rows.text(
                source, Kind.TEXT, name -> "pg_catalog.substr(" + name, "pg_catalog.octet_length(")
            .withBytes(Rows.bytes(source, name -> name + " COLLATE pg_catalog.\"C\""));
      // As the server writes it, with the spaces it is padded with, which a cast to text drops.
      case BPCHAR ->
        Rows.text(
                source,
                Kind.TEXT,
                name -> "pg_catalog.substr(pg_catalog.concat(" + name + ")",
                "pg_catalog.octet_length(")
            .withBytes(Rows.bytes(source, name -> name + " COLLATE pg_catalog.\"C\""));
      case BYTEA ->
        Rows.binary(
            source,
            name -> "pg_catalog.substr(" + name + ", 1, " + LIMIT + ")",
            "pg_catalog.octet_length");
      case DATE -> Rows.plain(source, Kind.DATE, Rows::date);
      case TIME_OF_DAY, TIMETZ -> Rows.plain(source, Kind.TIME, Rows::time);
      case TIMESTAMP -> Rows.plain(source, Kind.DATE_TIME, Rows::dateTime);
      // In UTC rather than in the session's zone, whose offsets in the past have seconds; a
      // statement's result has them, and they are taken off here.
      case TIMESTAMPTZ ->
        Rows.plain(
            source,
            Kind.DATE_TIME_ZONED,
            name -> "(" + name + " AT TIME ZONE 'UTC')",
            ResultSet::getString,
            PostgreSqlRows::utc);
      case JSON, JSONB -> anyText(source, Kind.JSON);
      // Any other type as its own output function writes it, which a cast may not: concat() calls
      // that, where a cast to text can be a user's function; it gives NULL as an empty text, which
      // num_nulls() tells apart. pgjdbc reports an enum as VARCHAR, and money as DOUBLE, which it
      // is not.
      default ->
        anyText(
            source, jdbcType == Types.VARCHAR || jdbcType == Types.CHAR ? Kind.TEXT : Kind.OTHER);
    };
  }

  @Override
  String equal() {
    return "OPERATOR(pg_catalog.=)";
  }

  // As the server's own text, of no type, which it reads as the type of the column it goes into or
  // is compared with: what was read goes back as it came.
  @Override
  void bind(PreparedStatement statement, int index, Object value) throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.OTHER);
    } else {
      statement.setObject(index, text(value), Types.OTHER);
    }
  }

  @Override
  String literal(Object value) {
    return switch (value) {
      case null -> "NULL";
      case Boolean truth -> truth.booleanValue() ? "TRUE" : "FALSE";
      case Long number -> text(number);
      case BigInteger number -> text(number);
      case BigDecimal number -> text(number);
      case Float number when Float.isFinite(number) -> text(number);
      case Double number when Double.isFinite(number) -> text(number);
      default -> "'" + text(value).replace("'", "''") + "'";
    };
  }

  private static String text(Object value) {
    return value instanceof Binary binary
        ? "\\x" + HexFormat.of().formatHex(binary.bytes())
        : Rows.text(value, true);
  }

  @Override
  String parameter(Object value) {
    return "?";
  }

  // An UPDATE or DELETE of a table that others inherit from meets their rows too, whose keys may
  // be its own; a partitioned table's keys are unique across its partitions.
  @Override
  boolean changeable(Connection connection, Provider provider, Table table, List<Object> values)
      throws QueryException {
    var sql = "SELECT c.relhassubclass AND c.relkind OPERATOR(pg_catalog.=) 'r'"
        + " FROM pg_catalog.pg_class c"
        + " WHERE c.oid OPERATOR(pg_catalog.=) pg_catalog.to_regclass(?)";
    return Query.run(connection, provider, sql, statement -> {
      statement.setString(1, Rows.qualified(provider, table));
      try (var rows = statement.executeQuery()) {
        return !rows.next() || !rows.getBoolean(1);
      }
    });
  }

  // The output function's text of a PostgreSQL value of any type, which counts as NULL where
  // num_nulls() says so.
  private static Rows.Read anyText(Rows.Source source, Kind kind) {
    if (source.sql() == null) {
      return Rows.whole(source, kind);
    }
    var text = "pg_catalog.concat(" + source.sql() + ")";
    return new Rows.Read(
        Rows.field(source, kind),
        source.sql(),
        List.of(
            "pg_catalog.substr(" + text + ", 1, " + (LIMIT + 1) + ")",
            "pg_catalog.octet_length(" + text + ")",
            "pg_catalog.num_nulls(" + source.sql() + ")"),
        (rows, index) ->
            rows.getInt(index + 2) == 1 ? null : Rows.cut(rows.getString(index), rows, index + 1));
  }

  private static Object decimal(String text) {
    return switch (text) {
      case "NaN" -> Double.NaN;
      case "Infinity" -> Double.POSITIVE_INFINITY;
      case "-Infinity" -> Double.NEGATIVE_INFINITY;
      default -> new BigDecimal(text);
    };
  }

  // A moment in UTC, or with the offset it was written in.
  private static Object utc(String text) {
    return Rows.parsed(text, Rows.MOMENT, m -> {
      if (m.group(4) == null) {
        return null;
      }
      var offset = m.group(8) == null ? ZoneOffset.UTC : ZoneOffset.of(m.group(8));
      return LocalDateTime.of(Rows.localDate(m), Rows.localTime(m, 4))
          .atOffset(offset)
          .withOffsetSameInstant(ZoneOffset.UTC);
    });
  }
}

package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the rows of a table or view, a page at a time, and how many there are.
 *
 * <p>A page holds the rows from one position to another, from 0, in an order that gives each
 * position the same row while the table does not change: by the primary key where there is one,
 * and on PostgreSQL then by the table and place of each row, as the children of a table that
 * inherit from it may repeat its keys. Without a key, a PostgreSQL table is read in the order of
 * its rows' tables and physical places, which an update or a {@code VACUUM FULL} changes; anything
 * else, a foreign table among them, by all its columns but those of kind {@link Kind#OTHER}, which
 * may have no order, and then by the bytes of its texts, so that texts a collation takes for equal
 * keep their order; rows alike in all those columns may swap places between two reads. That order
 * sorts the whole table for every page.
 *
 * <p>Values are typed as {@link Kind} says. They are read from the server's own text rather than
 * through the drivers' conversions to Java types, which shift a time in a daylight saving gap of
 * the JVM's zone, and turn zero dates and times beyond a day into other values or fail on them. A
 * text that is no value of its kind is kept as it is, and no failure carries a value. Text and
 * binary data are cut to {@link #LIMIT} on the server, so that a long value costs no more than
 * that here.
 *
 * <p>The statements are plain ones, without parameters or JDBC escapes, so that no driver reads
 * anything into the names in them; every column is named through an alias of the table, and every
 * function, operator, type and collation of PostgreSQL with its schema, so that nothing a user
 * has created under the same names runs.
 *
 * <p>Each call blocks, and runs through {@link Query}: an interrupt of the calling thread ends it
 * with {@link QueryException.Failure#CANCELLED}, or {@link QueryException.Failure#ABORTED} where
 * the connection had to be given up. On PostgreSQL a connection reads only the tables of its own
 * database.
 */
public final class Rows {

  /** How many characters of a text, and how many bytes of binary data, a page holds. */
  public static final int LIMIT = 1024;

  // A date, and a date and time, as PostgreSQL writes them with DateStyle ISO, which pgjdbc sets,
  // and as MariaDB and MySQL write them; PostgreSQL's years go beyond 9999, and before the common
  // era they end in BC. A timestamp with time zone of a statement's result has the offset of the
  // session's zone.
  private static final Pattern MOMENT = Pattern.compile(
      "(\\d{4,7})-(\\d\\d)-(\\d\\d)(?: (\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d{1,9}))?)?"
          + "([+-]\\d\\d(?::\\d\\d){0,2})?( BC)?");
  // PostgreSQL writes an offset in hours, then minutes and seconds where they are not 0.
  private static final Pattern TIME =
      Pattern.compile("(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d{1,9}))?([+-]\\d\\d(?::\\d\\d){0,2})?");
  private static final DateTimeFormatter MYSQL_DATE_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS");
  private static final Pattern PLAN_ROWS = Pattern.compile(" rows=(\\d+) ");

  // The alias of the table in every statement that reads its rows, and of the table joined to it
  // whose columns say what the table's are now.
  private static final String ALIAS = "r";
  private static final String WITNESS = "w";

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

  private Rows() {}

  interface Value {
    Object read(ResultSet rows, int index) throws SQLException;
  }

  // How a column is read: the expressions it takes in the select list, and how its value is made
  // from them, the first one at the index given; and, of a table's column, its name through the
  // alias, and an expression of its bytes for an order beyond its collation, or null. A column of a
  // statement's result takes no expressions: it is read as it comes, at its own index.
  record Read(Field field, String column, List<String> sql, Value value, String bytes) {

    Read(Field field, String column, List<String> sql, Value value) {
      this(field, column, sql, value, null);
    }

    Read withBytes(String bytes) {
      return new Read(field, column, sql, value, bytes);
    }
  }

  // What a column is read from: the name and type its field shows, and its name through the alias,
  // or null for a column of a statement's result.
  private record Source(String name, String type, String sql) {}

  /**
   * The number of rows of the table or view as the server's planner estimates it, without reading
   * them; empty where it has no estimate, as MariaDB and MySQL have none for a view. It can be far
   * off: PostgreSQL takes an empty table that was never analyzed for one of ten pages, and every
   * server reports at least 1 for an empty table; after many rows were inserted or deleted, it
   * lags behind until the server gathers its statistics again.
   */
  public static OptionalLong estimate(Connection connection, Provider provider, Table table)
      throws QueryException {
    requireConnectedTo(connection, provider, table);
    var select = "SELECT * FROM " + qualified(provider, table);
    return switch (provider) {
      // The first line of the plan is its top: "Seq Scan on t  (cost=0.00..19.00 rows=900 ...".
      case POSTGRESQL ->
        Query.query(connection, provider, "EXPLAIN " + select, rows -> {
          var matcher = PLAN_ROWS.matcher(rows.next() ? rows.getString(1) : "");
          return matcher.find()
              ? OptionalLong.of(Long.parseLong(matcher.group(1)))
              : OptionalLong.empty();
        });
      // Rather than information_schema.TABLES.TABLE_ROWS, which MySQL keeps for a day.
      case MARIADB, MYSQL -> {
        if (table.kind() == Table.Kind.VIEW) {
          yield OptionalLong.empty();
        }
        var explain = provider == Provider.MYSQL ? "EXPLAIN FORMAT=TRADITIONAL " : "EXPLAIN ";
        yield Query.query(connection, provider, explain + select, rows -> {
          if (!rows.next()) {
            return OptionalLong.empty();
          }
          var estimate = rows.getLong("rows");
          return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(estimate);
        });
      }
    };
  }

  /** The exact number of rows of the table or view, which the server counts one by one. */
  public static long count(Connection connection, Provider provider, Table table)
      throws QueryException {
    requireConnectedTo(connection, provider, table);
    var count = provider == Provider.POSTGRESQL ? "pg_catalog.count(*)" : "count(*)";
    var sql = "SELECT " + count + " FROM " + qualified(provider, table);
    return Query.query(connection, provider, sql, rows -> {
      rows.next();
      return rows.getLong(1);
    });
  }

  /**
   * The rows from the position {@code from} up to, not including, {@code to}, fewer where the
   * table ends before, with the given columns.
   *
   * <p>Each page is read on its own, in a snapshot of its own: rows inserted, deleted or given
   * other keys before a position between two reads move the rows after it, so that the next page
   * can repeat or leave out a row, and a count and the pages need not agree. A position names a
   * row only for as long as the table does not change.
   *
   * <p>The columns may have changed since they were read. The read notices it from what the
   * server reports with the rows, without asking anything more, and fails with {@link
   * QueryException.Failure#COLUMNS_CHANGED} where their names, number or order differ, or on
   * MariaDB and MySQL where a column's type is now read another way: of another kind, or a {@code
   * bigint} that became unsigned or signed. A type that keeps how its values are read, such as a
   * longer {@code varchar}, is not told apart, and on MySQL neither are {@code tinyint(1)} and
   * {@code bit(1)}, which its driver reports alike. PostgreSQL's values are read by the types the
   * server reports for that very read, so a page's fields give the kinds the columns have now.
   *
   * @param columns all the table's columns, in their order, as {@link
   *     dev.hoori.db.core.catalog.Catalog#columns} read them, which give the primary key
   * @throws IllegalArgumentException if the positions are not {@code 0 <= from <= to}, a column is
   *     not the table's, or a PostgreSQL connection is to another database than the table's
   */
  public static Page read(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      long from,
      long to)
      throws QueryException {
    if (from < 0 || to < from) {
      throw new IllegalArgumentException("Rows from " + from + " to " + to);
    }
    for (var column : columns) {
      if (!column.table().equals(table)) {
        throw new IllegalArgumentException(column.name() + " is not a column of " + table.name());
      }
    }
    requireConnectedTo(connection, provider, table);
    var described = reads(connection, provider, table, columns);
    var reads = described.reads();
    var key = columns.stream().anyMatch(column -> column.keyPosition() > 0);
    // The table of a primary key and the places of its rows, where children inherit from it,
    // which may repeat its keys: elsewhere they would slow a page far into the table by half.
    var children = provider == Provider.POSTGRESQL
        && table.kind() == Table.Kind.TABLE
        && key
        && children(connection, provider, described.table());
    var pg = provider == Provider.POSTGRESQL;
    var selected = reads.stream().flatMap(r -> r.sql().stream()).toList();
    // On MariaDB and MySQL the table's columns as they are now come with the rows, from a join to
    // none of its rows, whose columns only say what they are.
    var sql = "SELECT "
        + String.join(", ", selected)
        + (pg ? "" : (selected.isEmpty() ? "" : ", ") + WITNESS + ".*")
        + " FROM " + qualified(provider, table) + " AS " + ALIAS
        + (pg
            ? ""
            : " LEFT JOIN (SELECT * FROM " + qualified(provider, table) + " LIMIT 0) AS " + WITNESS
                + " ON FALSE")
        + order(provider, table, columns, reads, children)
        + " LIMIT " + (to - from) + " OFFSET " + from;
    List<List<Object>> rows;
    try {
      rows = Query.query(connection, provider, sql, result -> {
        if (!pg && !same(provider, columns, reads, result.getMetaData(), selected.size())) {
          return null;
        }
        return rows(result, reads);
      });
    } catch (QueryException e) {
      throw columnGone(e) ? changed(table) : e;
    }
    if (rows == null) {
      throw changed(table);
    }
    return new Page(reads.stream().map(Read::field).toList(), List.copyOf(rows));
  }

  private static List<List<Object>> rows(ResultSet result, List<Read> reads) throws SQLException {
    var read = new ArrayList<List<Object>>();
    while (result.next()) {
      var values = new Object[reads.size()];
      var index = 1;
      for (var i = 0; i < values.length; i++) {
        values[i] = reads.get(i).value().read(result, index);
        index += reads.get(i).sql().size();
      }
      read.add(Collections.unmodifiableList(Arrays.asList(values)));
    }
    return read;
  }

  private static QueryException changed(Table table) {
    return QueryException.of(
        QueryException.Failure.COLUMNS_CHANGED, "The columns of " + table.name() + " changed");
  }

  // A column named in the statement is gone: PostgreSQL's undefined column, MariaDB's and MySQL's
  // unknown column.
  private static boolean columnGone(QueryException e) {
    return e.failure() == QueryException.Failure.NOT_FOUND
        && e.getCause() instanceof SQLException sql
        && ("42703".equals(sql.getSQLState()) || "42S22".equals(sql.getSQLState()));
  }

  /**
   * Whether the witness's columns, after the {@code selected} expressions of the reads, are the
   * given columns, by name and in order, each read the way its catalog type is read.
   */
  private static boolean same(
      Provider provider,
      List<Column> columns,
      List<Read> reads,
      ResultSetMetaData metadata,
      int selected)
      throws SQLException {
    if (metadata.getColumnCount() - selected != columns.size()) {
      return false;
    }
    for (var i = 0; i < columns.size(); i++) {
      var at = selected + i + 1;
      var type = metadata.getColumnTypeName(at);
      var given = columns.get(i).type().toLowerCase(Locale.ROOT);
      var precision = metadata.getPrecision(at);
      // MySQL Connector/J reports tinyint(1) and bit(1) alike.
      var bit = provider == Provider.MYSQL
          && type.equalsIgnoreCase("bit")
          && precision == 1
          && (given.equals("bit(1)") || given.startsWith("tinyint(1)"));
      var now = mysql(
          provider,
          new Source(metadata.getColumnLabel(at), type, ALIAS),
          catalogType(provider, type, precision));
      if (!metadata.getColumnLabel(at).equals(columns.get(i).name())
          || !bit && now.field().kind() != reads.get(i).field().kind()
          || unsignedBigint(type.toLowerCase(Locale.ROOT)) != unsignedBigint(given)) {
        return false;
      }
    }
    return true;
  }

  private static boolean unsignedBigint(String type) {
    return type.startsWith("bigint") && type.contains("unsigned");
  }

  private static String order(
      Provider provider, Table table, List<Column> columns, List<Read> reads, boolean children) {
    var order = new ArrayList<>(columns.stream()
        .filter(column -> column.keyPosition() > 0)
        .sorted(Comparator.comparingInt(Column::keyPosition))
        .map(column -> column(provider, column.name()))
        .toList());
    // Real places of rows: a foreign table's may all be the same, and a view has none.
    var places = provider == Provider.POSTGRESQL && table.kind() == Table.Kind.TABLE;
    if (places && (order.isEmpty() || children)) {
      order.add(ALIAS + ".tableoid");
      order.add(ALIAS + ".ctid");
    } else if (order.isEmpty() && !places) {
      for (var read : reads) {
        if (read.field().kind() != Kind.OTHER) {
          order.add(read.column());
        }
      }
      reads.stream().map(Read::bytes).filter(Objects::nonNull).forEach(order::add);
    }
    return order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order);
  }

  // MariaDB's and MySQL's catalog names each type exactly, where their drivers report tinyint(1),
  // bit(1) and bit(8) alike as BIT. PostgreSQL's types are told from what pgjdbc reports of a
  // query that reads no row, which is the base type of a domain.
  private static Described reads(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws QueryException {
    if (provider != Provider.POSTGRESQL) {
      return new Described(
          columns.stream()
              .map(column -> mysql(provider, source(provider, column), column.type()))
              .toList(),
          0);
    }
    // All the table's columns, so that the given ones can be told to be them still.
    var sql = "SELECT " + ALIAS + ".* FROM " + qualified(provider, table) + " AS " + ALIAS
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
        return new Described(List.of(), 0);
      }
      var reads = new ArrayList<Read>();
      for (var i = 0; i < columns.size(); i++) {
        reads.add(postgresql(
            source(provider, columns.get(i)),
            field(metadata, i + 1, "getOID"),
            metadata.getColumnType(i + 1)));
      }
      return new Described(reads, Integer.toUnsignedLong(field(metadata, 1, "getTableOid")));
    });
    if (described == null) {
      throw changed(table);
    }
    return described;
  }

  // How a table's columns are read, and on PostgreSQL the OID of the table, or 0.
  private record Described(List<Read> reads, long table) {}

  // Whether the PostgreSQL table has or had children, or may have, where its OID is not known.
  private static boolean children(Connection connection, Provider provider, long table)
      throws QueryException {
    if (table == 0) {
      return true;
    }
    var sql = "SELECT c.relhassubclass AND c.relkind OPERATOR(pg_catalog.=) 'r'"
        + " FROM pg_catalog.pg_class c WHERE c.oid OPERATOR(pg_catalog.=) " + table;
    return Query.query(connection, provider, sql, rows -> !rows.next() || rows.getBoolean(1));
  }

  /**
   * How the columns of a statement's result are read, as {@link #read} reads a table's where the
   * metadata of the result tells as much as the catalog: see {@link RowReader}.
   */
  static List<Read> reads(Provider provider, ResultSetMetaData metadata) throws SQLException {
    var reads = new ArrayList<Read>();
    for (var i = 1; i <= metadata.getColumnCount(); i++) {
      var type = metadata.getColumnTypeName(i);
      var source = new Source(metadata.getColumnLabel(i), type, null);
      reads.add(
          provider == Provider.POSTGRESQL
              ? postgresql(source, field(metadata, i, "getOID"), metadata.getColumnType(i))
              : mysql(provider, source, catalogType(provider, type, metadata.getPrecision(i))));
    }
    return reads;
  }

  // pgjdbc's own field of the result has the OIDs of the column's type and table, which JDBC does
  // not give; where they cannot be read, 0 makes every column text, and a table one with children.
  private static int field(ResultSetMetaData metadata, int column, String getter) {
    try {
      Method field = null;
      for (Class<?> type = metadata.getClass(); field == null && type != null; ) {
        try {
          field = type.getDeclaredMethod("getField", int.class);
        } catch (NoSuchMethodException e) {
          type = type.getSuperclass();
        }
      }
      Objects.requireNonNull(field).setAccessible(true);
      var value = field.invoke(metadata, column);
      return (int) value.getClass().getMethod(getter).invoke(value);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return 0;
    }
  }

  // The type as the catalog would name it, from what the driver reports: MariaDB Connector/J
  // names tinyint(1) BOOLEAN and its json JSON, which the catalog calls longtext, while MySQL
  // Connector/J reports tinyint(1) and bit(1) alike as a BIT of precision 1, which is taken for the
  // tinyint(1), as a whole number holds either.
  private static String catalogType(Provider provider, String type, int precision) {
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "boolean" -> "tinyint(1)";
      case "bit" ->
        provider == Provider.MYSQL && precision == 1 ? "tinyint(1)" : "bit(" + precision + ")";
      case "json" -> provider == Provider.MARIADB ? "longtext" : "json";
      case String other -> other;
    };
  }

  private static Source source(Provider provider, Column column) {
    return new Source(column.name(), column.type(), column(provider, column.name()));
  }

  private static String column(Provider provider, String name) {
    return ALIAS + "." + provider.quote(name);
  }

  // A table's column as its bytes, for an order beyond its collation.
  private static String bytes(Source source, UnaryOperator<String> bytes) {
    return source.sql() == null ? null : bytes.apply(source.sql());
  }

  private static Read postgresql(Source source, int oid, int jdbcType) {
    return switch (oid) {
      case INT2, INT4, INT8, OID -> plain(source, Kind.INTEGER, Long::valueOf);
      case NUMERIC -> plain(source, Kind.DECIMAL, Rows::decimal);
      // shortcut: a float loses digits if a statement on the connection lowered pgjdbc's
      // extra_float_digits; read it in binary if the app ever lets users set it.
      case FLOAT4 -> plain(source, Kind.DECIMAL, Float::valueOf);
      case FLOAT8 -> plain(source, Kind.DECIMAL, Double::valueOf);
      case BOOL -> plain(source, Kind.BOOLEAN, "t"::equals);
      case TEXT, VARCHAR, NAME ->
        text(source, Kind.TEXT, name -> "pg_catalog.substr(" + name, "pg_catalog.octet_length(")
            .withBytes(bytes(source, name -> name + " COLLATE pg_catalog.\"C\""));
      // As the server writes it, with the spaces it is padded with, which a cast to text drops.
      case BPCHAR ->
        text(
                source,
                Kind.TEXT,
                name -> "pg_catalog.substr(pg_catalog.concat(" + name + ")",
                "pg_catalog.octet_length(")
            .withBytes(bytes(source, name -> name + " COLLATE pg_catalog.\"C\""));
      case BYTEA ->
        binary(
            source,
            name -> "pg_catalog.substr(" + name + ", 1, " + LIMIT + ")",
            "pg_catalog.octet_length");
      case DATE -> plain(source, Kind.DATE, Rows::date);
      case TIME_OF_DAY, TIMETZ -> plain(source, Kind.TIME, Rows::time);
      case TIMESTAMP -> plain(source, Kind.DATE_TIME, Rows::dateTime);
      // In UTC rather than in the session's zone, whose offsets in the past have seconds; a
      // statement's result has them, and they are taken off here.
      case TIMESTAMPTZ ->
        plain(
            source,
            Kind.DATE_TIME_ZONED,
            name -> "(" + name + " AT TIME ZONE 'UTC')",
            ResultSet::getString,
            Rows::utc);
      // Any other type as its own output function writes it, which a cast may not: concat() calls
      // that, where a cast to text can be a user's function; it gives NULL as an empty text, which
      // num_nulls() tells apart. pgjdbc reports an enum as VARCHAR, and money as DOUBLE, which it
      // is not.
      default ->
        anyText(
            source, jdbcType == Types.VARCHAR || jdbcType == Types.CHAR ? Kind.TEXT : Kind.OTHER);
    };
  }

  private static Read mysql(Provider provider, Source source, String catalogType) {
    var type = catalogType.toLowerCase(Locale.ROOT);
    UnaryOperator<String> asText = name -> "CAST(" + name + " AS CHAR)";
    // Of a statement's result, MySQL Connector/J's getString() writes a time in its own way, and
    // its getBytes() gives the server's text. MariaDB Connector/J's getBytes() refuses a time, and
    // its getString() moves one out of a daylight saving gap of the JVM's zone, where its
    // LocalDateTime does not; that has none for a zero date and fails on a zero month or day,
    // which getString() then writes as the server does.
    Text raw = provider == Provider.MYSQL ? Rows::ascii : ResultSet::getString;
    Text dateTime = provider == Provider.MYSQL
        ? Rows::ascii
        : (rows, index) -> {
          try {
            var local = rows.getObject(index, LocalDateTime.class);
            return local == null ? rows.getString(index) : MYSQL_DATE_TIME.format(local);
          } catch (SQLException e) {
            return rows.getString(index);
          }
        };
    UnaryOperator<String> start = name -> "SUBSTRING(" + name;
    return switch (type.split("[( ]", 2)[0]) {
      case "tinyint", "smallint", "mediumint", "int", "integer" ->
        plain(source, Kind.INTEGER, Long::valueOf);
      case "bigint" ->
        plain(source, Kind.INTEGER, type.contains("unsigned") ? BigInteger::new : Long::valueOf);
      // MySQL Connector/J's getString() writes a year as a date.
      case "year" ->
        plain(
            source,
            Kind.INTEGER,
            name -> "CAST(" + name + " AS UNSIGNED)",
            provider == Provider.MYSQL ? Rows::ascii : ResultSet::getString,
            Long::valueOf);
      // Of a statement's result, MariaDB Connector/J's getString() writes true or false.
      case "bit" ->
        type.equals("bit(1)")
            ? plain(source, Kind.BOOLEAN, name -> name + " + 0", Rows::bit, "1"::equals)
            : binary(source, name -> name, "LENGTH");
      case "decimal", "numeric" -> plain(source, Kind.DECIMAL, BigDecimal::new);
      case "float" -> plain(source, Kind.DECIMAL, Float::valueOf);
      case "double", "real" -> plain(source, Kind.DECIMAL, Double::valueOf);
      // As the server writes them.
      case "date" -> plain(source, Kind.DATE, asText, raw, Rows::date);
      case "time" -> plain(source, Kind.TIME, asText, raw, Rows::time);
      case "datetime" -> plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime);
      // The server writes a timestamp in the session's zone; UNIX_TIMESTAMP() gives the moment it
      // stores, and 0 for a zero date, which no timestamp is otherwise. A statement's result has
      // only the text, which is the time in the session's zone.
      case "timestamp" ->
        source.sql() == null
            ? plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime)
            : new Read(
                field(source, Kind.DATE_TIME_ZONED),
                source.sql(),
                List.of(asText.apply(source.sql()), "UNIX_TIMESTAMP(" + source.sql() + ")"),
                (rows, index) -> {
                  var text = rows.getString(index);
                  if (text == null) {
                    return null;
                  }
                  try {
                    var seconds = new BigDecimal(rows.getString(index + 1));
                    return seconds.signum() == 0
                        ? text
                        : Instant.ofEpochSecond(
                                seconds.longValue(),
                                seconds
                                    .remainder(BigDecimal.ONE)
                                    .movePointRight(9)
                                    .longValue())
                            .atOffset(ZoneOffset.UTC);
                  } catch (RuntimeException e) {
                    return text;
                  }
                });
      case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set" ->
        text(source, Kind.TEXT, start, "LENGTH(")
            .withBytes(bytes(source, name -> "CAST(" + name + " AS BINARY)"));
      // The spatial types are sent as the server stores them: an SRID and the geometry's WKB.
      case "binary",
          "varbinary",
          "tinyblob",
          "blob",
          "mediumblob",
          "longblob",
          "geometry",
          "point",
          "linestring",
          "polygon",
          "multipoint",
          "multilinestring",
          "multipolygon",
          "geometrycollection",
          "geomcollection" ->
        binary(source, name -> "SUBSTRING(" + name + ", 1, " + LIMIT + ")", "LENGTH");
      default -> text(source, Kind.OTHER, start, "LENGTH(");
    };
  }

  private interface Text {
    String read(ResultSet rows, int index) throws SQLException;
  }

  private static Field field(Source source, Kind kind) {
    return new Field(source.name(), source.type(), kind);
  }

  private static Read plain(Source source, Kind kind, Function<String, Object> parse) {
    return plain(source, kind, name -> name, parse);
  }

  private static Read plain(
      Source source, Kind kind, UnaryOperator<String> sql, Function<String, Object> parse) {
    return plain(source, kind, sql, ResultSet::getString, parse);
  }

  // The table's column is read as the text of the expression, a statement's as the text the
  // given way gives. A text that is no value of the kind stays text, and never fails the page.
  private static Read plain(
      Source source,
      Kind kind,
      UnaryOperator<String> sql,
      Text text,
      Function<String, Object> parse) {
    var table = source.sql() != null;
    return new Read(
        field(source, kind),
        source.sql(),
        table ? List.of(sql.apply(source.sql())) : List.of(),
        (rows, i) -> {
          var read = table ? rows.getString(i) : text.read(rows, i);
          if (read == null) {
            return null;
          }
          try {
            return parse.apply(read);
          } catch (RuntimeException e) {
            return read;
          }
        });
  }

  // One character more than the limit tells that there is more; the server counts characters, as
  // code points, where Java counts UTF-16 units. A NULL has no length, also where the start, such
  // as concat()'s, is an empty text. A statement's result has the whole text, which is cut here.
  private static Read text(Source source, Kind kind, UnaryOperator<String> start, String length) {
    if (source.sql() == null) {
      return whole(source, kind);
    }
    return new Read(
        field(source, kind),
        source.sql(),
        List.of(
            start.apply(source.sql()) + ", 1, " + (LIMIT + 1) + ")", length + source.sql() + ")"),
        (rows, index) ->
            rows.getString(index + 1) == null ? null : cut(rows.getString(index), rows, index + 1));
  }

  // The output function's text of a PostgreSQL value of any type, which counts as NULL where
  // num_nulls() says so.
  private static Read anyText(Source source, Kind kind) {
    if (source.sql() == null) {
      return whole(source, kind);
    }
    var text = "pg_catalog.concat(" + source.sql() + ")";
    return new Read(
        field(source, kind),
        source.sql(),
        List.of(
            "pg_catalog.substr(" + text + ", 1, " + (LIMIT + 1) + ")",
            "pg_catalog.octet_length(" + text + ")",
            "pg_catalog.num_nulls(" + source.sql() + ")"),
        (rows, index) ->
            rows.getInt(index + 2) == 1 ? null : cut(rows.getString(index), rows, index + 1));
  }

  private static Read whole(Source source, Kind kind) {
    return new Read(field(source, kind), null, List.of(), (rows, index) -> {
      var text = rows.getString(index);
      return text == null || text.codePointCount(0, text.length()) <= LIMIT
          ? text
          : new Cut(text.substring(0, text.offsetByCodePoints(0, LIMIT)), OptionalLong.empty());
    });
  }

  private static Object cut(String text, ResultSet rows, int length) throws SQLException {
    if (text == null || text.codePointCount(0, text.length()) <= LIMIT) {
      return text;
    }
    return new Cut(
        text.substring(0, text.offsetByCodePoints(0, LIMIT)),
        OptionalLong.of(rows.getLong(length)));
  }

  private static Read binary(Source source, UnaryOperator<String> sql, String length) {
    if (source.sql() == null) {
      return new Read(field(source, Kind.BINARY), null, List.of(), (rows, index) -> {
        var bytes = rows.getBytes(index);
        return bytes == null
            ? null
            : new Binary(Arrays.copyOf(bytes, Math.min(bytes.length, LIMIT)), bytes.length);
      });
    }
    return new Read(
        field(source, Kind.BINARY),
        source.sql(),
        List.of(sql.apply(source.sql()), length + "(" + source.sql() + ")"),
        (rows, index) -> {
          var bytes = rows.getBytes(index);
          return bytes == null ? null : new Binary(bytes, rows.getLong(index + 1));
        });
  }

  private static String ascii(ResultSet rows, int index) throws SQLException {
    var bytes = rows.getBytes(index);
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }

  private static String bit(ResultSet rows, int index) throws SQLException {
    var bytes = rows.getBytes(index);
    return bytes == null ? null : bytes.length == 1 && bytes[0] == 1 ? "1" : "0";
  }

  private static Object decimal(String text) {
    return switch (text) {
      case "NaN" -> Double.NaN;
      case "Infinity" -> Double.POSITIVE_INFINITY;
      case "-Infinity" -> Double.NEGATIVE_INFINITY;
      default -> new BigDecimal(text);
    };
  }

  private static Object date(String text) {
    return parsed(
        text, MOMENT, m -> m.group(4) == null && m.group(8) == null ? localDate(m) : null);
  }

  private static Object dateTime(String text) {
    return parsed(
        text,
        MOMENT,
        m -> m.group(4) == null || m.group(8) != null
            ? null
            : LocalDateTime.of(localDate(m), localTime(m, 4)));
  }

  // A moment in UTC, or with the offset it was written in.
  private static Object utc(String text) {
    return parsed(
        text,
        MOMENT,
        m -> m.group(4) == null
            ? null
            : LocalDateTime.of(localDate(m), localTime(m, 4))
                .atOffset(m.group(8) == null ? ZoneOffset.UTC : ZoneOffset.of(m.group(8)))
                .withOffsetSameInstant(ZoneOffset.UTC));
  }

  private static Object time(String text) {
    return parsed(text, TIME, m -> {
      var time = localTime(m, 1);
      return m.group(5) == null ? time : OffsetTime.of(time, ZoneOffset.of(m.group(5)));
    });
  }

  // The value, or the text where it is none that the Java type can hold.
  private static Object parsed(String text, Pattern pattern, Function<Matcher, Object> value) {
    var matcher = pattern.matcher(text);
    if (!matcher.matches()) {
      return text;
    }
    try {
      var parsed = value.apply(matcher);
      return parsed == null ? text : parsed;
    } catch (DateTimeException e) {
      return text;
    }
  }

  // 1 BC is the year 0, 2 BC the year -1.
  private static LocalDate localDate(Matcher m) {
    var year = Integer.parseInt(m.group(1));
    return LocalDate.of(
        m.group(9) == null ? year : 1 - year,
        Integer.parseInt(m.group(2)),
        Integer.parseInt(m.group(3)));
  }

  private static LocalTime localTime(Matcher m, int hour) {
    var fraction = m.group(hour + 3);
    return LocalTime.of(
        Integer.parseInt(m.group(hour)),
        Integer.parseInt(m.group(hour + 1)),
        Integer.parseInt(m.group(hour + 2)),
        fraction == null ? 0 : Integer.parseInt((fraction + "00000000").substring(0, 9)));
  }

  private static String qualified(Provider provider, Table table) {
    return provider.quote(table.container().name()) + "." + provider.quote(table.name());
  }

  // A PostgreSQL connection would read a table of the same name in its own database.
  private static void requireConnectedTo(Connection connection, Provider provider, Table table)
      throws QueryException {
    if (provider != Provider.POSTGRESQL) {
      return;
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw Query.failure(connection, e);
    }
    var database = table.container().database().name();
    if (!database.equals(current)) {
      throw new IllegalArgumentException(
          "A table of " + database + " on a connection to " + current);
    }
  }
}

package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
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
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the rows of a table or view, a page at a time, and how many there are.
 *
 * <p>A page holds the rows from one position to another, from 0, in an order that gives each
 * position the same row while the table does not change: by the primary key where there is one.
 * Without one, a PostgreSQL table is read in the order of its rows' physical places, which an
 * update or a {@code VACUUM FULL} changes; anything else, by all its columns but those of kind
 * {@link Kind#OTHER}, which may have no order, so rows alike in all the others may swap places
 * between two reads. That order sorts the whole table for every page.
 *
 * <p>Values are typed as {@link Kind} says. They are read from the server's own text rather than
 * through the drivers' conversions to Java types, which shift a time in a daylight saving gap of
 * the JVM's zone, and turn zero dates and times beyond a day into other values or fail on them. Text and
 * binary data are cut to {@link #LIMIT} on the server, so that a long value costs no more than
 * that here.
 *
 * <p>Each call blocks, and runs through {@link Query#run}: an interrupt of the calling thread
 * cancels it with {@link QueryException.Failure#CANCELLED} and leaves the connection usable. On
 * PostgreSQL a connection reads only the tables of its own database.
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

  private Rows() {}

  interface Value {
    Object read(ResultSet rows, int index) throws SQLException;
  }

  // How a column is read: the expressions it takes in the select list, and how its value is made
  // from them, the first one at the index given. A column of a statement's result takes none: it
  // is read as it comes, at its own index.
  record Read(Field field, List<String> sql, Value value) {}

  // What a column is read from: the name and type its field shows, and its quoted name in a select
  // list, or null for a column of a statement's result.
  private record Source(String name, String type, String sql) {}

  /**
   * The number of rows of the table or view as the server's planner estimates it, without reading
   * them; empty where it has no estimate, as MariaDB and MySQL have none for a view. It can be far
   * off: PostgreSQL takes an empty table that was never analysed for one of ten pages, and every
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
        Query.run(connection, provider, "EXPLAIN " + select, statement -> {
          try (var rows = statement.executeQuery()) {
            var matcher = PLAN_ROWS.matcher(rows.next() ? rows.getString(1) : "");
            return matcher.find()
                ? OptionalLong.of(Long.parseLong(matcher.group(1)))
                : OptionalLong.empty();
          }
        });
      // Rather than information_schema.TABLES.TABLE_ROWS, which MySQL keeps for a day.
      case MARIADB, MYSQL -> {
        if (table.kind() == Table.Kind.VIEW) {
          yield OptionalLong.empty();
        }
        var explain = provider == Provider.MYSQL ? "EXPLAIN FORMAT=TRADITIONAL " : "EXPLAIN ";
        yield Query.run(connection, provider, explain + select, statement -> {
          try (var rows = statement.executeQuery()) {
            if (!rows.next()) {
              return OptionalLong.empty();
            }
            var estimate = rows.getLong("rows");
            return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(estimate);
          }
        });
      }
    };
  }

  /** The exact number of rows of the table or view, which the server counts one by one. */
  public static long count(Connection connection, Provider provider, Table table)
      throws QueryException {
    requireConnectedTo(connection, provider, table);
    return Query.run(
        connection, provider, "SELECT count(*) FROM " + qualified(provider, table), statement -> {
          try (var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
          }
        });
  }

  /**
   * The rows from the position {@code from} up to, not including, {@code to}, fewer where the
   * table ends before, with the given columns.
   *
   * @param columns the table's columns as {@link dev.hoori.db.core.catalog.Catalog#columns} read
   *     them, which give the primary key
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
    var reads = reads(connection, provider, table, columns);
    var sql = "SELECT "
        + reads.stream().flatMap(r -> r.sql().stream()).collect(Collectors.joining(", "))
        + " FROM " + qualified(provider, table) + order(provider, table, columns, reads)
        + " LIMIT ? OFFSET ?";
    var rows = Query.run(connection, provider, sql, statement -> {
      statement.setLong(1, to - from);
      statement.setLong(2, from);
      try (var result = statement.executeQuery()) {
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
    });
    return new Page(reads.stream().map(Read::field).toList(), List.copyOf(rows));
  }

  private static String order(
      Provider provider, Table table, List<Column> columns, List<Read> reads) {
    var order = columns.stream()
        .filter(column -> column.keyPosition() > 0)
        .sorted(Comparator.comparingInt(Column::keyPosition))
        .map(column -> provider.quote(column.name()))
        .toList();
    if (order.isEmpty() && provider == Provider.POSTGRESQL && table.kind() == Table.Kind.TABLE) {
      // The rows of the partitions of a partitioned table share their places.
      order = List.of("tableoid", "ctid");
    }
    if (order.isEmpty()) {
      order = reads.stream()
          .filter(read -> read.field().kind() != Kind.OTHER)
          .map(read -> provider.quote(read.field().name()))
          .toList();
    }
    return order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order);
  }

  // MariaDB's and MySQL's catalogue names each type exactly, where their drivers report tinyint(1),
  // bit(1) and bit(8) alike as BIT. PostgreSQL's names enums and domains by their own names, where
  // pgjdbc reports an enum as VARCHAR and a domain as its base type, from a query that reads no
  // row.
  private static List<Read> reads(
      Connection connection, Provider provider, Table table, List<Column> columns)
      throws QueryException {
    if (provider != Provider.POSTGRESQL) {
      return columns.stream()
          .map(column -> mysql(provider, source(provider, column), column.type()))
          .toList();
    }
    if (columns.isEmpty()) {
      return List.of();
    }
    var names = columns.stream()
        .map(column -> provider.quote(column.name()))
        .collect(Collectors.joining(", "));
    var sql = "SELECT " + names + " FROM " + qualified(provider, table) + " WHERE false";
    return Query.run(connection, provider, sql, statement -> {
      try (var rows = statement.executeQuery()) {
        var metadata = rows.getMetaData();
        var reads = new ArrayList<Read>();
        for (var i = 0; i < columns.size(); i++) {
          reads.add(postgresql(
              source(provider, columns.get(i)),
              metadata.getColumnTypeName(i + 1),
              metadata.getColumnType(i + 1)));
        }
        return reads;
      }
    });
  }

  /**
   * How the columns of a statement's result are read, as {@link #read} reads a table's where the
   * metadata of the result tells as much as the catalogue: see {@link RowReader}.
   */
  static List<Read> reads(Provider provider, ResultSetMetaData metadata) throws SQLException {
    var reads = new ArrayList<Read>();
    for (var i = 1; i <= metadata.getColumnCount(); i++) {
      var type = metadata.getColumnTypeName(i);
      var source = new Source(metadata.getColumnLabel(i), type, null);
      reads.add(
          provider == Provider.POSTGRESQL
              ? postgresql(source, type, metadata.getColumnType(i))
              : mysql(provider, source, catalogueType(provider, type, metadata.getPrecision(i))));
    }
    return reads;
  }

  // The type as the catalogue would name it, from what the driver reports: MariaDB Connector/J
  // names tinyint(1) BOOLEAN and its json JSON, which the catalogue calls longtext, while MySQL
  // Connector/J reports tinyint(1) and bit(1) alike as a BIT of precision 1, which is taken for the
  // tinyint(1), as a whole number holds either.
  private static String catalogueType(Provider provider, String type, int precision) {
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "boolean" -> "tinyint(1)";
      case "bit" ->
        provider == Provider.MYSQL && precision == 1 ? "tinyint(1)" : "bit(" + precision + ")";
      case "json" -> provider == Provider.MARIADB ? "longtext" : "json";
      case String other -> other;
    };
  }

  private static Source source(Provider provider, Column column) {
    return new Source(column.name(), column.type(), provider.quote(column.name()));
  }

  private static Read postgresql(Source source, String type, int jdbcType) {
    return switch (type) {
      case "int2", "int4", "int8", "oid" -> plain(source, Kind.INTEGER, Long::valueOf);
      case "numeric" -> plain(source, Kind.DECIMAL, Rows::decimal);
      case "float4" -> plain(source, Kind.DECIMAL, Float::valueOf);
      case "float8" -> plain(source, Kind.DECIMAL, Double::valueOf);
      case "bool" -> plain(source, Kind.BOOLEAN, "t"::equals);
      // shortcut: char(n) is read whole, as substr() would drop its trailing spaces; cut it too if
      // someone keeps long texts in one.
      case "bpchar" -> plain(source, Kind.TEXT, text -> text);
      case "bytea" ->
        binary(source, name -> "substr(" + name + ", 1, " + LIMIT + ")", "octet_length");
      case "date" -> plain(source, Kind.DATE, Rows::date);
      case "time", "timetz" -> plain(source, Kind.TIME, Rows::time);
      case "timestamp" -> plain(source, Kind.DATE_TIME, Rows::dateTime);
      // In UTC rather than in the session's zone, whose offsets in the past have seconds; a
      // statement's result has them, and they are taken off here.
      case "timestamptz" ->
        plain(
            source,
            Kind.DATE_TIME_ZONED,
            name -> "(" + name + " AT TIME ZONE 'UTC')",
            ResultSet::getString,
            Rows::utc);
      // pgjdbc reports money as DOUBLE, which it is not; it is written in the server's locale.
      case "money" -> text(source, Kind.OTHER, name -> name + "::text", "octet_length");
      default -> {
        var kind = jdbcType == Types.VARCHAR || jdbcType == Types.CHAR ? Kind.TEXT : Kind.OTHER;
        yield text(source, kind, name -> name + "::text", "octet_length");
      }
    };
  }

  private static Read mysql(Provider provider, Source source, String catalogueType) {
    var type = catalogueType.toLowerCase(Locale.ROOT);
    UnaryOperator<String> asText = name -> "CAST(" + name + " AS CHAR)";
    // MariaDB Connector/J's getString() moves a time out of a daylight saving gap of the JVM's
    // zone, where its LocalDateTime does not; it has none for a zero date.
    Text dateTime = provider == Provider.MYSQL
        ? ResultSet::getString
        : (rows, index) -> {
          var local = rows.getObject(index, LocalDateTime.class);
          return local == null ? rows.getString(index) : MYSQL_DATE_TIME.format(local);
        };
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
      case "date" -> plain(source, Kind.DATE, asText, Rows::date);
      case "time" -> plain(source, Kind.TIME, asText, Rows::time);
      case "datetime" -> plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime);
      // The server writes a timestamp in the session's zone; UNIX_TIMESTAMP() gives the moment it
      // stores, and 0 for a zero date, which no timestamp is otherwise. A statement's result has
      // only the text, which is the time in the session's zone.
      case "timestamp" ->
        source.sql() == null
            ? plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime)
            : new Read(
                field(source, Kind.DATE_TIME_ZONED),
                List.of(asText.apply(source.sql()), "UNIX_TIMESTAMP(" + source.sql() + ")"),
                (rows, index) -> {
                  var text = rows.getString(index);
                  if (text == null) {
                    return null;
                  }
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
                });
      case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set" ->
        text(source, Kind.TEXT, name -> name, "LENGTH");
      case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob" ->
        binary(source, name -> "SUBSTRING(" + name + ", 1, " + LIMIT + ")", "LENGTH");
      default -> text(source, Kind.OTHER, name -> name, "LENGTH");
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
  // given way gives.
  private static Read plain(
      Source source,
      Kind kind,
      UnaryOperator<String> sql,
      Text text,
      Function<String, Object> parse) {
    var table = source.sql() != null;
    return new Read(
        field(source, kind), table ? List.of(sql.apply(source.sql())) : List.of(), (rows, i) -> {
          var read = table ? rows.getString(i) : text.read(rows, i);
          return read == null ? null : parse.apply(read);
        });
  }

  // One character more than the limit tells that there is more; the server counts characters, as
  // code points, where Java counts UTF-16 units. A statement's result has the whole text, which is
  // cut here and counted in UTF-8.
  private static Read text(Source source, Kind kind, UnaryOperator<String> sql, String length) {
    if (source.sql() == null) {
      return new Read(field(source, kind), List.of(), (rows, index) -> {
        var text = rows.getString(index);
        return text == null || text.codePointCount(0, text.length()) <= LIMIT
            ? text
            : new Cut(text.substring(0, text.offsetByCodePoints(0, LIMIT)), utf8Length(text));
      });
    }
    var expression = sql.apply(source.sql());
    var start = "SUBSTRING(" + expression + " FROM 1 FOR " + (LIMIT + 1) + ")";
    return new Read(
        field(source, kind), List.of(start, length + "(" + expression + ")"), (rows, index) -> {
          var text = rows.getString(index);
          if (text == null || text.codePointCount(0, text.length()) <= LIMIT) {
            return text;
          }
          return new Cut(
              text.substring(0, text.offsetByCodePoints(0, LIMIT)), rows.getLong(index + 1));
        });
  }

  private static Read binary(Source source, UnaryOperator<String> sql, String length) {
    if (source.sql() == null) {
      return new Read(field(source, Kind.BINARY), List.of(), (rows, index) -> {
        var bytes = rows.getBytes(index);
        return bytes == null
            ? null
            : new Binary(Arrays.copyOf(bytes, Math.min(bytes.length, LIMIT)), bytes.length);
      });
    }
    return new Read(
        field(source, Kind.BINARY),
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

  private static long utf8Length(String text) {
    long length = 0;
    for (var i = 0; i < text.length(); i++) {
      var c = text.charAt(i);
      if (c < 0x80) {
        length += 1;
      } else if (c < 0x800) {
        length += 2;
      } else if (Character.isHighSurrogate(c)) {
        length += 4;
        i++;
      } else {
        length += 3;
      }
    }
    return length;
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

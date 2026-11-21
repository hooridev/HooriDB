package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.Rows.ALIAS;
import static dev.hoori.db.core.data.Rows.LIMIT;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.UnaryOperator;

/** How MariaDB's and MySQL's tables and results are read alike; their drivers differ. */
abstract class MySqlFamilyRows extends RowsDialect {

  /** The {@code EXPLAIN} whose result has a column {@code rows}. */
  abstract String tabularExplain();

  /** How the driver gives the server's text of a value of a statement's result. */
  abstract Rows.Text raw();

  /** How the driver gives the server's text of a datetime of a statement's result. */
  abstract Rows.Text dateTime();

  /** Whether the driver reports a {@code tinyint(1)} and a {@code bit(1)} alike, as a BIT(1). */
  abstract boolean bitLikeTinyint();

  // Rather than information_schema.TABLES.TABLE_ROWS, which MySQL keeps for a day.
  @Override
  OptionalLong estimate(Connection connection, Provider provider, Table table, String select)
      throws QueryException {
    if (table.kind() == Table.Kind.VIEW) {
      return OptionalLong.empty();
    }
    var sql = tabularExplain() + select;
    return Journal.sql(
        provider,
        Purpose.ROWS,
        sql,
        estimate -> Outcome.rows(1),
        () -> Query.query(connection, provider, sql, rows -> {
          if (!rows.next()) {
            return OptionalLong.empty();
          }
          var estimate = rows.getLong("rows");
          return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(estimate);
        }));
  }

  @Override
  String count() {
    return "count(*)";
  }

  // MariaDB's and MySQL's catalog names each type exactly, where their drivers report tinyint(1),
  // bit(1) and bit(8) alike as BIT.
  @Override
  Rows.Described describe(
      Connection connection, Provider provider, Table table, List<Column> columns) {
    return new Rows.Described(
        columns.stream()
            .map(column -> read(Rows.source(provider, column), column.type()))
            .toList(),
        0);
  }

  // On MariaDB and MySQL the table's columns as they are now come with the rows, from a join to
  // none of its rows, whose columns only say what they are.
  @Override
  boolean witness() {
    return true;
  }

  // MariaDB's catalog calls its json longtext, which its driver tells apart: a field has the kind
  // the witness says, of the two read alike.
  @Override
  List<Field> witnessed(
      List<Column> columns, List<Rows.Read> reads, ResultSetMetaData metadata, int selected)
      throws SQLException {
    if (metadata.getColumnCount() - selected != columns.size()) {
      return null;
    }
    var fields = new ArrayList<Field>();
    for (var i = 0; i < columns.size(); i++) {
      var at = selected + i + 1;
      var type = metadata.getColumnTypeName(at);
      var given = columns.get(i).type().toLowerCase(Locale.ROOT);
      var precision = metadata.getPrecision(at);
      // MySQL Connector/J reports tinyint(1) and bit(1) alike.
      var bit = bitLikeTinyint()
          && type.equalsIgnoreCase("bit")
          && precision == 1
          && (given.equals("bit(1)") || given.startsWith("tinyint(1)"));
      var now = read(
              new Rows.Source(metadata.getColumnLabel(at), type, ALIAS),
              catalogType(type, precision))
          .field()
          .kind();
      var was = reads.get(i).field();
      if (!metadata.getColumnLabel(at).equals(columns.get(i).name())
          || !bit && !alike(now, was.kind())
          || unsignedBigint(type.toLowerCase(Locale.ROOT)) != unsignedBigint(given)) {
        return null;
      }
      fields.add(now == was.kind() || bit ? was : new Field(was.name(), was.type(), now));
    }
    return List.copyOf(fields);
  }

  // JSON is read as text is.
  private static boolean alike(Kind now, Kind was) {
    return now == was || Set.of(Kind.TEXT, Kind.JSON).containsAll(List.of(now, was));
  }

  private static boolean unsignedBigint(String type) {
    return type.startsWith("bigint") && type.contains("unsigned");
  }

  @Override
  List<String> places(Table table) {
    return List.of();
  }

  @Override
  boolean children(Connection connection, Provider provider, long table) {
    return false;
  }

  @Override
  Rows.Read read(Rows.Source source, ResultSetMetaData metadata, int column) throws SQLException {
    return read(source, catalogType(source.type(), metadata.getPrecision(column)));
  }

  // The type as the catalog would name it, from what the driver reports: MariaDB Connector/J
  // names tinyint(1) BOOLEAN, and its json JSON as MySQL's, which its catalog calls longtext,
  // while MySQL Connector/J reports tinyint(1) and bit(1) alike as a BIT of precision 1, which is
  // taken for the tinyint(1), as a whole number holds either.
  private String catalogType(String type, int precision) {
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "boolean" -> "tinyint(1)";
      case "bit" -> bitLikeTinyint() && precision == 1 ? "tinyint(1)" : "bit(" + precision + ")";
      case String other -> other;
    };
  }

  private Rows.Read read(Rows.Source source, String catalogType) {
    var type = catalogType.toLowerCase(Locale.ROOT);
    UnaryOperator<String> asText = name -> "CAST(" + name + " AS CHAR)";
    Rows.Text raw = raw();
    Rows.Text dateTime = dateTime();
    UnaryOperator<String> start = name -> "SUBSTRING(" + name;
    var length = "LENGTH(";
    return switch (type.split("[( ]", 2)[0]) {
      case "tinyint", "smallint", "mediumint", "int", "integer" ->
        Rows.plain(source, Kind.INTEGER, Long::valueOf);
      case "bigint" ->
        Rows.plain(
            source, Kind.INTEGER, type.contains("unsigned") ? BigInteger::new : Long::valueOf);
      // MySQL Connector/J's getString() writes a year as a date.
      case "year" ->
        Rows.plain(
            source, Kind.INTEGER, name -> "CAST(" + name + " AS UNSIGNED)", raw, Long::valueOf);
      // Of a statement's result, MariaDB Connector/J's getString() writes true or false.
      case "bit" ->
        type.equals("bit(1)")
            ? Rows.plain(
                source, Kind.BOOLEAN, name -> name + " + 0", MySqlFamilyRows::bit, "1"::equals)
            : Rows.binary(source, name -> name, "LENGTH");
      case "decimal", "numeric" -> Rows.plain(source, Kind.DECIMAL, BigDecimal::new);
      case "float" -> Rows.plain(source, Kind.DECIMAL, Float::valueOf);
      case "double", "real" -> Rows.plain(source, Kind.DECIMAL, Double::valueOf);
      // As the server writes them.
      case "date" -> Rows.plain(source, Kind.DATE, asText, raw, Rows::date);
      case "time" -> Rows.plain(source, Kind.TIME, asText, raw, Rows::time);
      case "datetime" -> Rows.plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime);
      // The server writes a timestamp in the session's zone; UNIX_TIMESTAMP() gives the moment it
      // stores, and 0 for a zero date, which no timestamp is otherwise. A statement's result has
      // only the text, which is the time in the session's zone.
      case "timestamp" ->
        source.sql() == null
            ? Rows.plain(source, Kind.DATE_TIME, asText, dateTime, Rows::dateTime)
            : new Rows.Read(
                Rows.field(source, Kind.DATE_TIME_ZONED),
                source.sql(),
                List.of(asText.apply(source.sql()), "UNIX_TIMESTAMP(" + source.sql() + ")"),
                MySqlFamilyRows::timestamp);
      case "json" -> Rows.text(source, Kind.JSON, start, length);
      case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set" ->
        Rows.text(source, Kind.TEXT, start, length)
            .withBytes(Rows.bytes(source, name -> "CAST(" + name + " AS BINARY)"));
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
        Rows.binary(source, name -> "SUBSTRING(" + name + ", 1, " + LIMIT + ")", "LENGTH");
      default -> Rows.text(source, Kind.OTHER, start, length);
    };
  }

  @Override
  String keyEquality() {
    return "=";
  }

  // Numbers as numbers, as the servers compare a number with a text as floating point; binary data
  // as bytes, and anything else as the server's text, which it reads as the column's type.
  @Override
  void bind(PreparedStatement statement, int index, Object value) throws SQLException {
    switch (value) {
      case null -> statement.setNull(index, Types.NULL);
      case Long number -> statement.setLong(index, number);
      case BigInteger number -> statement.setBigDecimal(index, new BigDecimal(number));
      case BigDecimal number -> statement.setBigDecimal(index, number);
      case Float number -> statement.setFloat(index, number);
      case Double number -> statement.setDouble(index, number);
      case Boolean truth -> statement.setBoolean(index, truth);
      case Binary binary -> statement.setBytes(index, binary.bytes());
      case OffsetDateTime moment -> statement.setBigDecimal(index, seconds(moment));
      default -> statement.setString(index, Rows.text(value, false));
    }
  }

  // A text as the servers read it unless their SQL mode says NO_BACKSLASH_ESCAPES, and a moment
  // with its offset, as MySQL reads it, which tells that it is in UTC.
  @Override
  String literal(Object value) {
    return switch (value) {
      case null -> "NULL";
      case Boolean truth -> truth.booleanValue() ? "TRUE" : "FALSE";
      case Long number -> Rows.text(number, false);
      case BigInteger number -> Rows.text(number, false);
      case BigDecimal number -> Rows.text(number, false);
      case Float number -> Rows.text(number, false);
      case Double number -> Rows.text(number, false);
      case Binary binary -> "X'" + HexFormat.of().formatHex(binary.bytes()) + "'";
      default -> "'" + Rows.text(value, false).replace("\\", "\\\\").replace("'", "''") + "'";
    };
  }

  // A moment as the seconds since 1970, as UNIX_TIMESTAMP() reads it: the server takes a text of a
  // timestamp in the session's zone, and the session must keep it, which a DATETIME's DEFAULT
  // CURRENT_TIMESTAMP is written in. FROM_UNIXTIME() gives the time in that zone, which in an
  // hour that the zone repeats can be the other moment's, as changeable() asks.
  @Override
  String parameter(Object value) {
    return value instanceof OffsetDateTime ? "FROM_UNIXTIME(?)" : "?";
  }

  // Engines such as MyISAM, MEMORY and Aria keep no transactions; a table that is not found fails
  // the statement that names it. A moment must come back as itself from the session's time.
  @Override
  boolean changeable(Connection connection, Provider provider, Table table, List<Object> values)
      throws QueryException {
    for (var value : values) {
      if (value instanceof OffsetDateTime moment) {
        var seconds = seconds(moment);
        var back = Query.run(
            connection, provider, "SELECT UNIX_TIMESTAMP(FROM_UNIXTIME(?))", statement -> {
              statement.setBigDecimal(1, seconds);
              try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
              }
            });
        if (back == null || back.compareTo(seconds) != 0) {
          return false;
        }
      }
    }
    var sql = "SELECT e.TRANSACTIONS FROM information_schema.TABLES t"
        + " JOIN information_schema.ENGINES e ON e.ENGINE = t.ENGINE"
        + " WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ?";
    return Query.run(connection, provider, sql, statement -> {
      statement.setString(1, table.container().name());
      statement.setString(2, table.name());
      try (var rows = statement.executeQuery()) {
        return !rows.next() || "YES".equalsIgnoreCase(rows.getString(1));
      }
    });
  }

  private static BigDecimal seconds(OffsetDateTime moment) {
    return BigDecimal.valueOf(moment.toEpochSecond()).add(BigDecimal.valueOf(moment.getNano(), 9));
  }

  private static String bit(ResultSet rows, int index) throws SQLException {
    var bytes = rows.getBytes(index);
    if (bytes == null) {
      return null;
    }
    return bytes.length == 1 && bytes[0] == 1 ? "1" : "0";
  }

  // The moment of a timestamp's seconds since 1970, its text where they are 0, a zero date.
  private static Object timestamp(ResultSet rows, int index) throws SQLException {
    var text = rows.getString(index);
    if (text == null) {
      return null;
    }
    try {
      var seconds = new BigDecimal(rows.getString(index + 1));
      if (seconds.signum() == 0) {
        return text;
      }
      var nanos = seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue();
      return Instant.ofEpochSecond(seconds.longValue(), nanos).atOffset(ZoneOffset.UTC);
    } catch (RuntimeException _) {
      return text;
    }
  }
}

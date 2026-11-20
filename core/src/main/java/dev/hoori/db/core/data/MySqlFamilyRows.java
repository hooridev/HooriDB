package dev.hoori.db.core.data;

import static dev.hoori.db.core.data.Rows.ALIAS;
import static dev.hoori.db.core.data.Rows.LIMIT;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
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

  /** The catalog's name of a type the driver reports as JSON. */
  abstract String json();

  // Rather than information_schema.TABLES.TABLE_ROWS, which MySQL keeps for a day.
  @Override
  OptionalLong estimate(Connection connection, Provider provider, Table table, String select)
      throws QueryException {
    if (table.kind() == Table.Kind.VIEW) {
      return OptionalLong.empty();
    }
    return Query.query(connection, provider, tabularExplain() + select, rows -> {
      if (!rows.next()) {
        return OptionalLong.empty();
      }
      var estimate = rows.getLong("rows");
      return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(estimate);
    });
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

  @Override
  boolean same(
      List<Column> columns, List<Rows.Read> reads, ResultSetMetaData metadata, int selected)
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
      var bit = bitLikeTinyint()
          && type.equalsIgnoreCase("bit")
          && precision == 1
          && (given.equals("bit(1)") || given.startsWith("tinyint(1)"));
      var now = read(
          new Rows.Source(metadata.getColumnLabel(at), type, ALIAS), catalogType(type, precision));
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
  // names tinyint(1) BOOLEAN and its json JSON, which the catalog calls longtext, while MySQL
  // Connector/J reports tinyint(1) and bit(1) alike as a BIT of precision 1, which is taken for the
  // tinyint(1), as a whole number holds either.
  private String catalogType(String type, int precision) {
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "boolean" -> "tinyint(1)";
      case "bit" -> bitLikeTinyint() && precision == 1 ? "tinyint(1)" : "bit(" + precision + ")";
      case "json" -> json();
      case String other -> other;
    };
  }

  private Rows.Read read(Rows.Source source, String catalogType) {
    var type = catalogType.toLowerCase(Locale.ROOT);
    UnaryOperator<String> asText = name -> "CAST(" + name + " AS CHAR)";
    Rows.Text raw = raw();
    Rows.Text dateTime = dateTime();
    UnaryOperator<String> start = name -> "SUBSTRING(" + name;
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
        Rows.text(source, Kind.TEXT, start, "LENGTH(")
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
      default -> Rows.text(source, Kind.OTHER, start, "LENGTH(");
    };
  }

  @Override
  String equal() {
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
      // In UTC, which the session takes it in while it writes.
      case OffsetDateTime moment ->
        statement.setString(
            index,
            Rows.text(moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), false));
      default -> statement.setString(index, Rows.text(value, false));
    }
  }

  // A text as the servers read it unless their SQL mode says NO_BACKSLASH_ESCAPES, and a moment
  // with its offset, as MySQL reads it, which tells that it is in UTC.
  @Override
  String literal(Object value) {
    return switch (value) {
      case null -> "NULL";
      case Boolean truth -> truth ? "TRUE" : "FALSE";
      case Long number -> Rows.text(number, false);
      case BigInteger number -> Rows.text(number, false);
      case BigDecimal number -> Rows.text(number, false);
      case Float number -> Rows.text(number, false);
      case Double number -> Rows.text(number, false);
      case Binary binary -> "X'" + HexFormat.of().formatHex(binary.bytes()) + "'";
      default -> "'" + Rows.text(value, false).replace("\\", "\\\\").replace("'", "''") + "'";
    };
  }

  // Engines such as MyISAM, MEMORY and Aria keep no transactions; a table that is not found fails
  // the statement that names it.
  @Override
  boolean rollsBack(Connection connection, Provider provider, Table table) throws QueryException {
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

  // The session reads the text of a timestamp in its zone, which may repeat an hour.
  @Override
  String utc(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      String zone;
      try (var rows = statement.executeQuery("SELECT @@session.time_zone")) {
        rows.next();
        zone = rows.getString(1);
      }
      statement.execute("SET time_zone = '+00:00'");
      return zone;
    }
  }

  @Override
  void restore(Connection connection, String zone) throws SQLException {
    try (var statement = connection.prepareStatement("SET time_zone = ?")) {
      statement.setString(1, zone);
      statement.execute();
    }
  }

  private static String bit(ResultSet rows, int index) throws SQLException {
    var bytes = rows.getBytes(index);
    return bytes == null ? null : bytes.length == 1 && bytes[0] == 1 ? "1" : "0";
  }
}

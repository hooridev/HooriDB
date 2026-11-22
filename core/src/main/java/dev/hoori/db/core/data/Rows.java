package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.dialect.ErrorDialect.Condition;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
 * else, a foreign table among them, by all its columns but those of kind {@link Kind#OTHER} or
 * {@link Kind#JSON}, which may have no order, and then by the bytes of its texts, so that texts a
 * collation takes for equal keep their order; rows alike in all those columns may swap places
 * between two reads. That order sorts the whole table for every page.
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

  /**
   * The most characters of one text that a statement's result or a document's value keeps whole
   * for the value panel; a longer one is cut to {@link #LIMIT}, as the panel lays it out whole.
   */
  public static final int WHOLE_LIMIT = 1024 * 1024;

  // A date, and a date and time, as PostgreSQL writes them with DateStyle ISO, which pgjdbc sets,
  // and as MariaDB and MySQL write them; PostgreSQL's years go beyond 9999, and before the common
  // era they end in BC. A timestamp with time zone of a statement's result has the offset of the
  // session's zone.
  static final Pattern MOMENT = Pattern.compile(
      "(\\d{4,7})-(\\d\\d)-(\\d\\d)(?: (\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d{1,9}))?)?"
          + "([+-]\\d\\d(?::\\d\\d){0,2})?( BC)?");
  // PostgreSQL writes an offset in hours, then minutes and seconds where they are not 0.
  private static final Pattern TIME =
      Pattern.compile("(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d{1,9}))?([+-]\\d\\d(?::\\d\\d){0,2})?");
  // The rows of a plan's node, "rows=900" in PostgreSQL's and "rows=238e+6" in MySQL's tree.
  private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xxxxx");
  private static final Pattern PLAN_ROWS =
      Pattern.compile("[( ]rows=(\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?)[ )]");

  // The alias of the table in every statement that reads its rows, and of the table joined to it
  // whose columns say what the table's are now.
  static final String ALIAS = "r";
  private static final String WITNESS = "w";

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
  record Source(String name, String type, String sql) {}

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
    return provider.dialect().rows().estimate(connection, provider, table, select);
  }

  /**
   * The rows the top of a plan estimates, from its first line as PostgreSQL's {@code EXPLAIN} and
   * MySQL's {@code EXPLAIN FORMAT=TREE} write it, such as {@code Limit  (cost=0.00..0.14 rows=10
   * width=8)}; empty where it says none.
   */
  public static OptionalLong planned(String plan) {
    var matcher = PLAN_ROWS.matcher(plan.lines().findFirst().orElse(""));
    return matcher.find()
        ? OptionalLong.of((long) Double.parseDouble(matcher.group(1)))
        : OptionalLong.empty();
  }

  /** The exact number of rows of the table or view, which the server counts one by one. */
  public static long count(Connection connection, Provider provider, Table table)
      throws QueryException {
    requireConnectedTo(connection, provider, table);
    var sql = "SELECT " + provider.dialect().rows().count() + " FROM " + qualified(provider, table);
    return Journal.sql(
        provider,
        Purpose.ROWS,
        sql,
        n -> Outcome.rows(1),
        () -> Query.query(connection, provider, sql, rows -> {
          rows.next();
          return rows.getLong(1);
        }));
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
   * MariaDB and MySQL where a column's type is now read another way: of another kind, apart from
   * {@link Kind#JSON} and {@link Kind#TEXT}, which are read alike, or a {@code bigint} that became
   * unsigned or signed. A type that keeps how its values are read, such as a longer {@code
   * varchar}, is not told apart, and on MySQL neither are {@code tinyint(1)} and {@code bit(1)},
   * which its driver reports alike. PostgreSQL's values are read by the types the server reports
   * for that very read, so a page's fields give the kinds the columns have now; on MariaDB, a
   * column its catalog calls {@code longtext} is of kind {@link Kind#JSON} where the rows say so.
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
    requireColumnsOf(table, columns);
    requireConnectedTo(connection, provider, table);
    var dialect = provider.dialect().rows();
    var described = dialect.describe(connection, provider, table, columns);
    var reads = described.reads();
    var key = columns.stream().anyMatch(column -> column.keyPosition() > 0);
    var places = dialect.places(table);
    // The table of a primary key and the places of its rows, where children inherit from it,
    // which may repeat its keys: elsewhere they would slow a page far into the table by half.
    var children =
        !places.isEmpty() && key && dialect.children(connection, provider, described.table());
    var witness = dialect.witness();
    var selected = reads.stream().flatMap(r -> r.sql().stream()).toList();
    var expressions = new ArrayList<>(selected);
    if (witness) {
      expressions.add(WITNESS + ".*");
    }
    var sql = "SELECT "
        + String.join(", ", expressions)
        + " FROM " + qualified(provider, table) + " AS " + ALIAS
        + (witness
            ? " LEFT JOIN (SELECT * FROM " + qualified(provider, table) + " LIMIT 0) AS " + WITNESS
                + " ON FALSE"
            : "")
        + order(provider, columns, reads, places, children)
        + " LIMIT " + (to - from) + " OFFSET " + from;
    var fields = reads.stream().map(Read::field).toList();
    Page page;
    try {
      page = Journal.sql(
          provider,
          Purpose.ROWS,
          sql,
          read -> read == null ? Outcome.DONE : Outcome.rows(read.rows().size()),
          () -> Query.query(connection, provider, sql, result -> {
            var shown = witness
                ? dialect.witnessed(columns, reads, result.getMetaData(), selected.size())
                : fields;
            return shown == null ? null : new Page(shown, List.copyOf(rows(result, reads)));
          }));
    } catch (QueryException e) {
      throw columnGone(provider, e) ? changed(table) : e;
    }
    if (page == null) {
      throw changed(table);
    }
    return page;
  }

  /**
   * The whole text of the column of the row that has the values of the given row, as it was read,
   * in its primary key, as the driver gives it; null for NULL. It reads what a page holds only in
   * part, a {@link Cut}, of a text, JSON or any other value as the server writes it.
   *
   * @param columns all the table's columns, as {@link #read} takes them
   * @param row a row of a page of the table, with those columns
   * @param column the column's index among them
   * @throws QueryException as {@link Query} reports it; {@link QueryException.Failure#NOT_FOUND}
   *     also where no row has the key's values now, or more than one, as children that inherit
   *     from a PostgreSQL table may
   * @throws IllegalArgumentException if no column is in a primary key, a column is not the table's,
   *     the row has a value for another number of columns, a value of its key cannot name it, as
   *     NULL or one read in part, or a PostgreSQL connection is to another database than the
   *     table's
   */
  public static String whole(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      List<Object> row,
      int column)
      throws QueryException {
    var keyed = Changes.of(table, columns)
        .orElseThrow(() -> new IllegalArgumentException(table.name() + " has no primary key"));
    Changes.requireRow(columns, row);
    requireConnectedTo(connection, provider, table);
    var dialect = provider.dialect().rows();
    var values = new ArrayList<Object>();
    // shortcut: on MariaDB and MySQL a moment of the key in an hour that the session's zone repeats
    // can name the other moment's row, as Changes.write refuses; check it if such keys show up.
    var sql = "SELECT " + provider.quote(columns.get(column).name()) + " FROM "
        + qualified(provider, table)
        + keyed.where(provider, row, dialect.keyEquality(), Changes.parameters(dialect, values));
    var texts = Journal.sql(
        provider,
        Purpose.ROWS,
        sql,
        read -> Outcome.rows(read.size()),
        () -> Query.run(connection, provider, sql, statement -> {
          for (var i = 0; i < values.size(); i++) {
            dialect.bind(statement, i + 1, values.get(i));
          }
          var read = new ArrayList<String>();
          try (var rows = statement.executeQuery()) {
            while (read.size() < 2 && rows.next()) {
              read.add(rows.getString(1));
            }
          }
          return read;
        }));
    if (texts.size() != 1) {
      throw QueryException.of(
          QueryException.Failure.NOT_FOUND,
          (texts.isEmpty() ? "No row" : "More than one row") + " of " + table.name()
              + " has the key");
    }
    return texts.getFirst();
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

  static QueryException changed(Table table) {
    return QueryException.of(
        QueryException.Failure.COLUMNS_CHANGED, "The columns of " + table.name() + " changed");
  }

  // A column named in the statement is gone.
  private static boolean columnGone(Provider provider, QueryException e) {
    return e.failure() == QueryException.Failure.NOT_FOUND
        && e.getCause() instanceof SQLException sql
        && Condition.COLUMN_GONE.in(
            provider, sql.getSQLState() == null ? "" : sql.getSQLState(), sql.getErrorCode());
  }

  private static String order(
      Provider provider,
      List<Column> columns,
      List<Read> reads,
      List<String> places,
      boolean children) {
    var order = new ArrayList<>(columns.stream()
        .filter(column -> column.keyPosition() > 0)
        .sorted(Comparator.comparingInt(Column::keyPosition))
        .map(column -> column(provider, column.name()))
        .toList());
    if (!places.isEmpty() && (order.isEmpty() || children)) {
      order.addAll(places);
    } else if (order.isEmpty() && places.isEmpty()) {
      for (var read : reads) {
        if (read.field().kind() != Kind.OTHER && read.field().kind() != Kind.JSON) {
          order.add(read.column());
        }
      }
      reads.stream().map(Read::bytes).filter(Objects::nonNull).forEach(order::add);
    }
    return order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order);
  }

  // How a table's columns are read, and the table as the dialect's children() names it, or 0.
  record Described(List<Read> reads, long table) {}

  /**
   * How the columns of a statement's result are read, as {@link #read} reads a table's where the
   * metadata of the result tells as much as the catalog: see {@link RowReader}.
   */
  static List<Read> reads(Provider provider, ResultSetMetaData metadata) throws SQLException {
    var dialect = provider.dialect().rows();
    var reads = new ArrayList<Read>();
    for (var i = 1; i <= metadata.getColumnCount(); i++) {
      var type = metadata.getColumnTypeName(i);
      reads.add(dialect.read(new Source(metadata.getColumnLabel(i), type, null), metadata, i));
    }
    return reads;
  }

  static Source source(Provider provider, Column column) {
    return new Source(column.name(), column.type(), column(provider, column.name()));
  }

  private static String column(Provider provider, String name) {
    return ALIAS + "." + provider.quote(name);
  }

  // A table's column as its bytes, for an order beyond its collation.
  static String bytes(Source source, UnaryOperator<String> bytes) {
    return source.sql() == null ? null : bytes.apply(source.sql());
  }

  interface Text {
    String read(ResultSet rows, int index) throws SQLException;
  }

  static Field field(Source source, Kind kind) {
    return new Field(source.name(), source.type(), kind);
  }

  static Read plain(Source source, Kind kind, Function<String, Object> parse) {
    return plain(source, kind, name -> name, parse);
  }

  static Read plain(
      Source source, Kind kind, UnaryOperator<String> sql, Function<String, Object> parse) {
    return plain(source, kind, sql, ResultSet::getString, parse);
  }

  // The table's column is read as the text of the expression, a statement's as the text the
  // given way gives. A text that is no value of the kind stays text, and never fails the page.
  static Read plain(
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
          } catch (RuntimeException _) {
            return read;
          }
        });
  }

  // One character more than the limit tells that there is more; the server counts characters, as
  // code points, where Java counts UTF-16 units. A NULL has no length, also where the start, such
  // as concat()'s, is an empty text. A statement's result reads the whole text, which RowReader
  // cuts beyond its budget.
  static Read text(Source source, Kind kind, UnaryOperator<String> start, String length) {
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

  /** Reads a column of a statement's result as its whole text, which {@link RowReader} bounds. */
  static final Value WHOLE_TEXT = ResultSet::getString;

  /**
   * A column of a statement's result read whole as text; {@link RowReader} keeps a value longer
   * than {@link #LIMIT} code points whole while its budget allows and cuts the rest.
   */
  static Read whole(Source source, Kind kind) {
    return new Read(field(source, kind), null, List.of(), WHOLE_TEXT);
  }

  static Object cut(String text, ResultSet rows, int length) throws SQLException {
    return tooLong(text) ? new Cut(start(text), OptionalLong.of(rows.getLong(length))) : text;
  }

  static Cut cut(String text) {
    return new Cut(start(text), OptionalLong.empty());
  }

  static boolean tooLong(String text) {
    return text != null
        && text.length() > LIMIT
        && (text.length() > 2 * LIMIT || text.codePointCount(0, text.length()) > LIMIT);
  }

  private static String start(String text) {
    return text.substring(0, text.offsetByCodePoints(0, LIMIT));
  }

  static Read binary(Source source, UnaryOperator<String> sql, String length) {
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

  static Object date(String text) {
    return parsed(
        text, MOMENT, m -> m.group(4) == null && m.group(8) == null ? localDate(m) : null);
  }

  static Object dateTime(String text) {
    return parsed(
        text,
        MOMENT,
        m -> m.group(4) == null || m.group(8) != null
            ? null
            : LocalDateTime.of(localDate(m), localTime(m, 4)));
  }

  static Object time(String text) {
    return parsed(text, TIME, m -> {
      var time = localTime(m, 1);
      return m.group(5) == null ? time : OffsetTime.of(time, ZoneOffset.of(m.group(5)));
    });
  }

  // The value, or the text where it is none that the Java type can hold.
  static Object parsed(String text, Pattern pattern, Function<Matcher, Object> value) {
    var matcher = pattern.matcher(text);
    if (!matcher.matches()) {
      return text;
    }
    try {
      var parsed = value.apply(matcher);
      return parsed == null ? text : parsed;
    } catch (DateTimeException _) {
      return text;
    }
  }

  // 1 BC is the year 0, 2 BC the year -1.
  static LocalDate localDate(Matcher m) {
    var year = Integer.parseInt(m.group(1));
    return LocalDate.of(
        m.group(9) == null ? year : 1 - year,
        Integer.parseInt(m.group(2)),
        Integer.parseInt(m.group(3)));
  }

  static LocalTime localTime(Matcher m, int hour) {
    var fraction = m.group(hour + 3);
    return LocalTime.of(
        Integer.parseInt(m.group(hour)),
        Integer.parseInt(m.group(hour + 1)),
        Integer.parseInt(m.group(hour + 2)),
        fraction == null ? 0 : Integer.parseInt((fraction + "00000000").substring(0, 9)));
  }

  // A value of a kind's Java type as the servers write it, which they read back as that value: a
  // moment in UTC, with its offset; with eras, a year before the common era as PostgreSQL writes
  // it, the year 0 as 1 BC.
  static String text(Object value, boolean eras) {
    return switch (value) {
      case String text -> text;
      case Long number -> number.toString();
      case BigInteger number -> number.toString();
      case BigDecimal number -> number.toPlainString();
      case Float number -> number.toString();
      case Double number -> number.toString();
      case Boolean truth -> truth.toString();
      case LocalDate date -> date(date, eras) + era(date, eras);
      case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
      case OffsetTime time ->
        DateTimeFormatter.ISO_LOCAL_TIME.format(time.toLocalTime()) + OFFSET.format(time);
      case LocalDateTime moment -> dateTime(moment, eras, "");
      case OffsetDateTime moment ->
        dateTime(moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), eras, "+00:00");
      default ->
        throw new IllegalArgumentException(
            "A value of " + value.getClass().getName() + " cannot be written");
    };
  }

  private static String dateTime(LocalDateTime moment, boolean eras, String offset) {
    return date(moment.toLocalDate(), eras)
        + " "
        + DateTimeFormatter.ISO_LOCAL_TIME.format(moment.toLocalTime())
        + offset
        + era(moment.toLocalDate(), eras);
  }

  private static String date(LocalDate date, boolean eras) {
    var year = eras && date.getYear() <= 0 ? 1 - date.getYear() : date.getYear();
    return String.format(
        Locale.ROOT, "%04d-%02d-%02d", year, date.getMonthValue(), date.getDayOfMonth());
  }

  private static String era(LocalDate date, boolean eras) {
    return eras && date.getYear() <= 0 ? " BC" : "";
  }

  /** The table's qualified SQL name: its container and name, each quoted for the provider. */
  public static String qualified(Provider provider, Table table) {
    return provider.quote(table.container().name()) + "." + provider.quote(table.name());
  }

  static void requireColumnsOf(Table table, List<Column> columns) {
    for (var column : columns) {
      if (!column.table().equals(table)) {
        throw new IllegalArgumentException(column.name() + " is not a column of " + table.name());
      }
    }
  }

  // A connection to one database would read a table of the same name in its own.
  static void requireConnectedTo(Connection connection, Provider provider, Table table)
      throws QueryException {
    if (!provider.supports(Capability.CONNECTION_PER_DATABASE, ServerVersion.UNKNOWN)) {
      return;
    }
    String current;
    try {
      current = connection.getCatalog();
    } catch (SQLException e) {
      throw Query.failure(connection, provider, e);
    }
    var database = table.container().database().name();
    if (!database.equals(current)) {
      throw new IllegalArgumentException(
          "A table of " + database + " on a connection to " + current);
    }
  }
}

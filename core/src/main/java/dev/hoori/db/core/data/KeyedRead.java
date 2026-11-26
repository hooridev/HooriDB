package dev.hoori.db.core.data;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.StringJoiner;

/**
 * A read of every row of a table, whole, in the order of a key, as a comparison of two tables
 * merges them. Each column of the key orders the rows by its value, and a text of any type, of
 * kind {@link Kind#TEXT}, {@link Kind#JSON} or {@link Kind#OTHER}, by the code points of its text
 * rather than by the column's collation, so that {@link #compare} puts the keys of two tables,
 * also of two servers, in the order their reads give them.
 *
 * <p>The values are of the kinds that {@link Rows#read} gives the columns, but read whole: no text
 * or binary data is cut, so that each can be compared and written back as it is. The read is one
 * statement, without parameters, which {@link dev.hoori.db.core.execution.Execution} runs and reads
 * a portion at a time, with this {@link #reader}.
 */
public final class KeyedRead {

  private final String sql;
  private final List<Rows.Read> reads;

  private KeyedRead(String sql, List<Rows.Read> reads) {
    this.sql = sql;
    this.reads = reads;
  }

  /**
   * The read of the table's rows in the order of the key, which is not sent yet; on PostgreSQL the
   * server is asked how the columns' types are read, which fails as {@link
   * QueryException.Failure#COLUMNS_CHANGED} where they are not the given ones.
   *
   * @param columns all the table's columns, in their order, as {@link
   *     dev.hoori.db.core.catalog.Catalog#columns} read them
   * @param key the indexes among them of the columns of a key whose values name each row once, in
   *     the key's order
   * @throws IllegalArgumentException if the key is empty, names a column twice or one out of
   *     range, a column is not the table's, or a PostgreSQL connection is to another database than
   *     the table's
   */
  public static KeyedRead of(
      Connection connection,
      Provider provider,
      Table table,
      List<Column> columns,
      List<Integer> key)
      throws QueryException {
    Rows.requireColumnsOf(table, columns);
    if (key.isEmpty()
        || new HashSet<>(key).size() != key.size()
        || key.stream().anyMatch(i -> i < 0 || i >= columns.size())) {
      throw new IllegalArgumentException("No key of the columns: " + key);
    }
    Rows.requireConnectedTo(connection, provider, table);
    var dialect = provider.dialect().rows();
    var reads = dialect.describe(connection, provider, table, columns, true).reads();
    var order = new StringJoiner(", ");
    for (var i : key) {
      var read = reads.get(i);
      order.add(textual(read.field().kind()) ? dialect.textOrder(read.column()) : read.column());
    }
    var selected = reads.stream().flatMap(read -> read.sql().stream()).toList();
    var sql = "SELECT " + String.join(", ", selected) + " FROM " + Rows.qualified(provider, table)
        + " AS " + Rows.ALIAS + " ORDER BY " + order;
    return new KeyedRead(sql, List.copyOf(reads));
  }

  /** The statement that reads the rows. */
  public String sql() {
    return sql;
  }

  /** The table's columns, as the rows' values are read. */
  public List<Field> fields() {
    return reads.stream().map(Rows.Read::field).toList();
  }

  /** How a row of the statement's result is read, with a value per column of the table. */
  public RowReader reader() {
    return RowReader.of(reads);
  }

  /** Whether the values of the kind are ordered by the code points of their text. */
  public static boolean textual(Kind kind) {
    return kind == Kind.TEXT || kind == Kind.JSON || kind == Kind.OTHER;
  }

  /**
   * The order of the values of a key's column, as a keyed read gives them: texts by their code
   * points, numbers by their value, whatever their Java type, with NaN above all others as
   * PostgreSQL has it; booleans false first; dates, times and moments by when they are; binary
   * data by its bytes, unsigned. Values of other types, such as the text a server writes for a date
   * that the Java type cannot hold, come by their type and then by their text, which need not be
   * the server's order: a merge finds that in keys out of order.
   */
  public static int compare(Object a, Object b) {
    record Pair(Object a, Object b) {}
    return switch (new Pair(a, b)) {
      case Pair(String x, String y) -> codePoints(x, y);
      case Pair(Number x, Number y) -> numbers(x, y);
      case Pair(Boolean x, Boolean y) -> Boolean.compare(x, y);
      case Pair(LocalDate x, LocalDate y) -> x.compareTo(y);
      case Pair(LocalTime x, LocalTime y) -> x.compareTo(y);
      case Pair(LocalDateTime x, LocalDateTime y) -> x.compareTo(y);
      case Pair(OffsetTime x, OffsetTime y) -> x.compareTo(y);
      case Pair(OffsetDateTime x, OffsetDateTime y) -> x.compareTo(y);
      case Pair(Binary x, Binary y) -> Arrays.compareUnsigned(x.bytes(), y.bytes());
      default -> {
        var by = String.valueOf(a == null ? null : a.getClass().getName())
            .compareTo(String.valueOf(b == null ? null : b.getClass().getName()));
        yield by != 0 ? by : codePoints(String.valueOf(a), String.valueOf(b));
      }
    };
  }

  // By code points, as UTF-8's bytes go, where String.compareTo puts a surrogate before U+E000.
  private static int codePoints(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      int x = a.codePointAt(i);
      int y = b.codePointAt(j);
      if (x != y) {
        return Integer.compare(x, y);
      }
      i += Character.charCount(x);
      j += Character.charCount(y);
    }
    return Boolean.compare(i < a.length(), j < b.length());
  }

  // Exactly, as decimals; infinities beyond them, and NaN above all, as Double.compare puts them.
  private static int numbers(Number a, Number b) {
    return finite(a) && finite(b)
        ? decimal(a).compareTo(decimal(b))
        : Double.compare(a.doubleValue(), b.doubleValue());
  }

  private static boolean finite(Number value) {
    return switch (value) {
      case Double d -> Double.isFinite(d);
      case Float f -> Float.isFinite(f);
      default -> true;
    };
  }

  /** The number as a decimal; a finite one of floating point as the shortest text that is it. */
  public static BigDecimal decimal(Number value) {
    return switch (value) {
      case BigDecimal d -> d;
      case BigInteger i -> new BigDecimal(i);
      case Long l -> BigDecimal.valueOf(l);
      case Integer i -> BigDecimal.valueOf(i);
      case Double d -> new BigDecimal(Double.toString(d));
      case Float f -> new BigDecimal(Float.toString(f));
      default -> new BigDecimal(value.toString());
    };
  }
}

package dev.hoori.db.core.data;

import dev.hoori.db.core.connection.Provider;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads the rows of the result of any statement, with the kinds and values that {@link Rows#read}
 * gives a table's columns of the same types, as far as the result's metadata tells the types
 * apart. Of a statement's result, a field's name is the column's label and its type is as the
 * driver names it, and these come out otherwise:
 *
 * <ul>
 *   <li>On MySQL, a {@code bit(1)} is of kind {@link Kind#INTEGER}, 0 or 1, rather than {@link
 *       Kind#BOOLEAN}, as MySQL Connector/J reports it and {@code tinyint(1)} alike.
 *   <li>On MariaDB and MySQL, a {@code timestamp} is of kind {@link Kind#DATE_TIME}: the time in
 *       the session's zone, as the server writes it, from which the moment cannot be told without
 *       asking the server for its zone.
 *   <li>Text, JSON and other values come whole from the server and are kept whole while their
 *       lengths fit in {@link #BUDGET} characters per result, each up to {@link
 *       Rows#WHOLE_LIMIT}; a value longer than {@link Rows#LIMIT} code points that is longer still
 *       or would exceed what is left is cut to {@link Rows#LIMIT} as a
 *       {@link Cut} without a length, as the size the server stores is not known. Binary data is
 *       cut to {@link Rows#LIMIT} bytes.
 * </ul>
 *
 * <p>One reader serves one result on one thread, as it counts the characters of long texts kept
 * whole across the rows it reads.
 */
public final class RowReader {

  /** How many characters of long texts one result keeps whole before cutting the rest. */
  public static final int BUDGET = 16 * 1024 * 1024;

  private final List<Rows.Read> reads;
  private long remaining;

  private RowReader(List<Rows.Read> reads, long budget) {
    this.reads = reads;
    this.remaining = budget;
  }

  /** How the result with this metadata, from a server of this provider, is read. */
  public static RowReader of(Provider provider, ResultSetMetaData metadata) throws SQLException {
    return of(provider, metadata, BUDGET);
  }

  static RowReader of(Provider provider, ResultSetMetaData metadata, long budget)
      throws SQLException {
    return new RowReader(Rows.reads(provider, metadata), budget);
  }

  /** The result's columns. */
  public List<Field> fields() {
    return reads.stream().map(Rows.Read::field).toList();
  }

  /** The values of the row the result set is on, which cannot be changed. */
  public List<Object> read(ResultSet rows) throws SQLException {
    var values = new Object[reads.size()];
    for (var i = 0; i < values.length; i++) {
      var read = reads.get(i);
      var value = read.value().read(rows, i + 1);
      values[i] =
          read.value() == Rows.WHOLE_TEXT && value instanceof String text ? keep(text) : value;
    }
    return Collections.unmodifiableList(Arrays.asList(values));
  }

  private Object keep(String text) {
    if (!Rows.tooLong(text)) {
      return text;
    }
    if (text.length() <= Math.min(remaining, Rows.WHOLE_LIMIT)) {
      remaining -= text.length();
      return text;
    }
    return Rows.cut(text);
  }
}

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
 *   <li>Text and binary data come whole from the server and are cut here to {@link Rows#LIMIT};
 *       the length of a {@link Cut} is that of the text in UTF-8, which is the server's where the
 *       column is in UTF-8.
 * </ul>
 */
public final class RowReader {

  private final List<Rows.Read> reads;

  private RowReader(List<Rows.Read> reads) {
    this.reads = reads;
  }

  /** How the result with this metadata, from a server of this provider, is read. */
  public static RowReader of(Provider provider, ResultSetMetaData metadata) throws SQLException {
    return new RowReader(Rows.reads(provider, metadata));
  }

  /** The result's columns. */
  public List<Field> fields() {
    return reads.stream().map(Rows.Read::field).toList();
  }

  /** The values of the row the result set is on, which cannot be changed. */
  public List<Object> read(ResultSet rows) throws SQLException {
    var values = new Object[reads.size()];
    for (var i = 0; i < values.length; i++) {
      values[i] = reads.get(i).value().read(rows, i + 1);
    }
    return Collections.unmodifiableList(Arrays.asList(values));
  }
}

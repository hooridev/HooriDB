package dev.hoori.db.core.dialect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

// The values of the rows that the monitors' queries give, as their sessions hold them.
final class MonitorRows {

  private MonitorRows() {}

  /** The text of the column, empty for NULL. */
  static String text(ResultSet row, int column) throws SQLException {
    var text = row.getString(column);
    return text == null ? "" : text;
  }

  /** The seconds of the column, to the nanosecond, as a duration; null for NULL. */
  static Duration seconds(ResultSet row, int column) throws SQLException {
    double seconds = row.getDouble(column);
    return row.wasNull() ? null : Duration.ofNanos(Math.round(Math.max(0, seconds) * 1e9));
  }

  /** The texts that are not empty, joined by the separator. */
  static String joined(String separator, String... texts) {
    var out = new StringBuilder();
    for (var text : texts) {
      if (!text.isEmpty()) {
        out.append(out.isEmpty() ? "" : separator).append(text);
      }
    }
    return out.toString();
  }
}

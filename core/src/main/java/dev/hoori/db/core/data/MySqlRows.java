package dev.hoori.db.core.data;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

/** How MySQL's tables and results are read, with MySQL Connector/J. */
public final class MySqlRows extends MySqlFamilyRows {

  public MySqlRows() {
    // Nothing to set up.
  }

  @Override
  String tabularExplain() {
    return "EXPLAIN FORMAT=TRADITIONAL ";
  }

  // Of a statement's result, MySQL Connector/J's getString() writes a time in its own way, and
  // its getBytes() gives the server's text.
  @Override
  Rows.Text raw() {
    return MySqlRows::ascii;
  }

  @Override
  Rows.Text dateTime() {
    return MySqlRows::ascii;
  }

  @Override
  boolean bitLikeTinyint() {
    return true;
  }

  private static String ascii(ResultSet rows, int index) throws SQLException {
    var bytes = rows.getBytes(index);
    return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
  }
}

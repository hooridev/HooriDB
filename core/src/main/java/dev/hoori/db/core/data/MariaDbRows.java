package dev.hoori.db.core.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** How MariaDB's tables and results are read, with MariaDB Connector/J. */
public final class MariaDbRows extends MySqlFamilyRows {

  // A date and time as MariaDB writes it.
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS");

  public MariaDbRows() {}

  @Override
  String tabularExplain() {
    return "EXPLAIN ";
  }

  @Override
  Rows.Text raw() {
    return ResultSet::getString;
  }

  // MariaDB Connector/J's getBytes() refuses a time, and its getString() moves one out of a
  // daylight saving gap of the JVM's zone, where its LocalDateTime does not; that has none for a
  // zero date and fails on a zero month or day, which getString() then writes as the server does.
  @Override
  Rows.Text dateTime() {
    return (rows, index) -> {
      try {
        var local = rows.getObject(index, LocalDateTime.class);
        return local == null ? rows.getString(index) : DATE_TIME.format(local);
      } catch (SQLException e) {
        return rows.getString(index);
      }
    };
  }

  @Override
  boolean bitLikeTinyint() {
    return false;
  }

  // MariaDB Connector/J names its json JSON, which the catalog calls longtext.
  @Override
  String json() {
    return "longtext";
  }
}

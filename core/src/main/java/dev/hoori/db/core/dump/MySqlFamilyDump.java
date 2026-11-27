package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.definition.Definition;
import dev.hoori.db.core.definition.Item;
import dev.hoori.db.core.definition.MySqlFamilyDefinitions;
import dev.hoori.db.core.definition.Sql;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How MariaDB's and MySQL's objects are exported, besides their definitions, which {@link
 * MySqlFamilyDefinitions} reads in its snapshot, in UTC: the file's session writes in it, so that a
 * {@code timestamp} comes back as the same moment, and reads with backslashes as escapes and a 0
 * as a value of an {@code AUTO_INCREMENT} column, as its literals are written. A table's rows are
 * those of now: of a table versioned by the system, its history stays behind.
 *
 * <p>A statement of DDL or a {@code TRUNCATE} of another session waits for the export to end only
 * on a table that it has read already; on one it has not read yet it goes through, and the export
 * then fails as the table's definition changed, or writes the definition it read before, as {@code
 * mysqldump --single-transaction} does.
 *
 * <p>These servers cannot keep a table's triggers from firing for the rows read into it: an export
 * of the data alone, which reads them into tables that have their triggers, refuses a table that
 * has any.
 */
public final class MySqlFamilyDump extends DumpDialect {

  private static final String MODE = MySqlFamilyDefinitions.MODE;
  private static final String NO_KEY_CHECKS = "SET SESSION foreign_key_checks = 0";

  public MySqlFamilyDump() {
    // Nothing to set up.
  }

  @Override
  public boolean foreignKeyChecks() {
    return true;
  }

  @Override
  public boolean quietsTriggers() {
    return false;
  }

  // A snapshot of these servers locks no table, as the class says.
  @Override
  void lock(Sql sql, List<Item> tables) {
    // Nothing to lock.
  }

  private record Column(String name, String type) {}

  private record Selected(String select, Value value) {}

  // Of each column that is not generated, a value of a kind the literal writes as such: whole
  // numbers, decimals, floating point and binary data; dates and times as the server writes them
  // in the snapshot's zone, anything else as its text. A float is read as a double, which these
  // servers write with all its digits, where they write a float rounded to six. A table without
  // such a column has its rows as INSERTs of empty rows, which take its defaults.
  @Override
  Optional<Data> data(Sql sql, Item table, boolean alone) throws QueryException {
    var provider = sql.provider();
    if (alone) {
      refuseTriggers(sql, table);
    }
    var columns = sql.some(
        """
        SELECT COLUMN_NAME, DATA_TYPE, EXTRA FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION""",
        rows -> generated(rows.getString(3))
            ? Optional.empty()
            : Optional.of(
                new Column(rows.getString(1), rows.getString(2).toLowerCase(Locale.ROOT))),
        table.container().name(),
        table.name());
    var selected = new ArrayList<String>();
    var names = new ArrayList<String>();
    var values = new ArrayList<Value>();
    for (var column : columns) {
      var quoted = provider.quote(column.name());
      var read = selected(quoted, column.type());
      names.add(quoted);
      selected.add(read.select());
      values.add(read.value());
    }
    var name = provider.quote(table.name());
    return Optional.of(new Data(
        "SELECT " + (selected.isEmpty() ? "1" : String.join(", ", selected)) + " FROM "
            + Rows.qualified(provider, table.container(), table.name()),
        "INSERT INTO " + name + " (" + String.join(", ", names) + ") VALUES",
        false,
        transactional(sql, table),
        metadata -> values,
        Around.NOTHING));
  }

  // Whether the table's engine keeps transactions; one that the server does not tell of is taken
  // to.
  private static boolean transactional(Sql sql, Item table) throws QueryException {
    var keeps = sql.rows("""
        SELECT e.TRANSACTIONS FROM information_schema.TABLES t
        JOIN information_schema.ENGINES e ON e.ENGINE = t.ENGINE
        WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ?""", rows -> rows.getString(1), table.container().name(), table.name());
    return keeps.isEmpty() || !"NO".equalsIgnoreCase(keeps.getFirst());
  }

  // The rows of an export of the data alone go into a table that has the triggers it has here,
  // which these servers cannot keep from firing for them.
  private static void refuseTriggers(Sql sql, Item table) throws QueryException {
    var provider = sql.provider();
    var triggers = sql.rows("""
        SELECT TRIGGER_NAME FROM information_schema.TRIGGERS
        WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? ORDER BY TRIGGER_NAME""", rows -> rows.getString(1), table.container().name(), table.name());
    if (!triggers.isEmpty()) {
      throw QueryException.of(
          QueryException.Failure.OTHER,
          provider.displayName() + " cannot keep the triggers of " + table.name() + " ("
              + String.join(", ", triggers) + ") from firing for the rows read into it, which"
              + " would change them: export its data with its structure, or leave it out.");
    }
  }

  // Their keys are not checked while the rows go in, which come from one snapshot.
  @Override
  Around circle(List<Definition> tables) {
    return tables.isEmpty()
        ? Around.NOTHING
        : new Around(
            List.of("SET @HOORIDB_CIRCLE_CHECKS = @@SESSION.foreign_key_checks", NO_KEY_CHECKS),
            List.of("SET SESSION foreign_key_checks = @HOORIDB_CIRCLE_CHECKS"));
  }

  private static boolean generated(String extra) {
    var upper = extra == null ? "" : extra.toUpperCase(Locale.ROOT);
    return upper.contains("VIRTUAL GENERATED")
        || upper.contains("STORED GENERATED")
        || upper.contains("PERSISTENT GENERATED");
  }

  private static Selected selected(String quoted, String type) {
    return switch (type) {
      case "tinyint", "smallint", "mediumint", "int", "integer", "bigint" ->
        new Selected(quoted, MySqlFamilyDump::whole);
      case "bit" -> new Selected("CAST(" + quoted + " AS UNSIGNED)", MySqlFamilyDump::whole);
      case "decimal", "numeric" -> new Selected(quoted, MySqlFamilyDump::decimal);
      case "float" -> new Selected(quoted + " + 0E0", MySqlFamilyDump::floating);
      case "double", "real" -> new Selected(quoted, MySqlFamilyDump::floating);
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
          "geomcollection" -> new Selected(quoted, MySqlFamilyDump::binary);
      default -> new Selected("CAST(" + quoted + " AS CHAR)", ResultSet::getString);
    };
  }

  private static Object whole(ResultSet rows, int i) throws SQLException {
    var text = rows.getString(i);
    return text == null ? null : new BigInteger(text);
  }

  private static Object decimal(ResultSet rows, int i) throws SQLException {
    var text = rows.getString(i);
    return text == null ? null : new BigDecimal(text);
  }

  private static Object floating(ResultSet rows, int i) throws SQLException {
    var text = rows.getString(i);
    return text == null ? null : Double.valueOf(text);
  }

  private static Object binary(ResultSet rows, int i) throws SQLException {
    var bytes = rows.getBytes(i);
    return bytes == null ? null : new Binary(bytes, bytes.length);
  }

  // The character set and the collation that SET NAMES changes go back too.
  @Override
  List<String> header(Dump.Options options) {
    var header = new ArrayList<>(List.of(
        "SET @HOORIDB_CHARACTER_SET_CLIENT = @@SESSION.character_set_client,"
            + " @HOORIDB_CHARACTER_SET_RESULTS = @@SESSION.character_set_results,"
            + " @HOORIDB_COLLATION_CONNECTION = @@SESSION.collation_connection",
        "SET NAMES utf8mb4",
        "SET @HOORIDB_TIME_ZONE = @@SESSION.time_zone",
        "SET SESSION time_zone = '+00:00'",
        "SET @HOORIDB_SQL_MODE = @@SESSION.sql_mode",
        "SET SESSION sql_mode = '" + MODE + "'"));
    if (options.noForeignKeyChecks()) {
      header.add("SET @HOORIDB_FOREIGN_KEY_CHECKS = @@SESSION.foreign_key_checks");
      header.add(NO_KEY_CHECKS);
    }
    return header;
  }

  @Override
  List<String> footer(Dump.Options options) {
    var footer = new ArrayList<String>();
    if (options.noForeignKeyChecks()) {
      footer.add("SET SESSION foreign_key_checks = @HOORIDB_FOREIGN_KEY_CHECKS");
    }
    footer.add("SET SESSION sql_mode = @HOORIDB_SQL_MODE");
    footer.add("SET SESSION time_zone = @HOORIDB_TIME_ZONE");
    footer.add("SET SESSION character_set_client = @HOORIDB_CHARACTER_SET_CLIENT,"
        + " SESSION character_set_results = @HOORIDB_CHARACTER_SET_RESULTS,"
        + " SESSION collation_connection = @HOORIDB_COLLATION_CONNECTION");
    return footer;
  }

  // A table that another refers to drops only without the checks, which these servers have no
  // way around but turning them off.
  @Override
  List<String> beforeDrops() {
    return List.of("SET @HOORIDB_DROP_CHECKS = @@SESSION.foreign_key_checks", NO_KEY_CHECKS);
  }

  @Override
  List<String> afterDrops() {
    return List.of("SET SESSION foreign_key_checks = @HOORIDB_DROP_CHECKS");
  }

  // As the server shows the database, with its character set and collation.
  @Override
  List<String> create(Sql sql, List<Container> containers) throws QueryException {
    var provider = sql.provider();
    var statements = new ArrayList<String>();
    for (var container : containers) {
      var name = provider.quote(container.name());
      var shown =
          sql.one(container.name(), "SHOW CREATE DATABASE " + name, rows -> rows.getString(2));
      statements.add(shown.replaceFirst("^CREATE DATABASE ", "CREATE DATABASE IF NOT EXISTS "));
      statements.add("USE " + name);
    }
    return statements;
  }

  @Override
  String beginTransaction() {
    return "START TRANSACTION";
  }
}

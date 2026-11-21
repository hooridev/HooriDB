package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * How MariaDB's and MySQL's objects are exported: their definitions as {@code SHOW CREATE} gives
 * them, on a session on their database, which names them without it; a table's foreign keys, and
 * the indexes that are no keys, taken out of its {@code CREATE TABLE} to be added later. What a
 * view needs is found by the names its definition quotes, which these servers do not otherwise
 * tell alike; a definer is left out, so that the user who reads the file back defines.
 *
 * <p>The snapshot reads in UTC, and the file's session writes in it, so that a {@code timestamp}
 * comes back as the same moment; it reads the file with backslashes as escapes and a 0 as a value
 * of an {@code AUTO_INCREMENT} column, as its literals are written. The snapshot reads in the SQL
 * mode the file's session reads it back in, so that {@code SHOW CREATE} quotes names in backticks
 * whatever the session's own mode, as {@code ANSI_QUOTES} would otherwise change. A table's rows
 * are those of now: of a table versioned by the system, its history stays behind.
 *
 * <p>The snapshot keeps the rows of one moment, but not the definitions of the tables, which these
 * servers change apart from any snapshot: a statement of DDL or a {@code TRUNCATE} of another
 * session waits for the export to end only on a table that it has read already; on one it has not
 * read yet it goes through, and the export then fails as the table's definition changed, or writes
 * the definition it read before, as {@code mysqldump --single-transaction} does.
 */
public final class MySqlFamilyDump extends DumpDialect {

  private static final String MODE = "NO_AUTO_VALUE_ON_ZERO";
  private static final String NO_DEFINITION =
      "The server does not show this user the definition of ";

  // The session's zone and SQL mode are kept on the server, for end() to put back, also where
  // begin() did not get to keep them.
  private static final String SESSION_BACK =
      "SET SESSION time_zone = COALESCE(@hooridb_time_zone, @@SESSION.time_zone),"
          + " SESSION sql_mode = COALESCE(@hooridb_sql_mode, @@SESSION.sql_mode),"
          + " @hooridb_time_zone = NULL, @hooridb_sql_mode = NULL";

  private final boolean sequences;

  /** @param sequences whether its servers have sequences, as MariaDB's do */
  public MySqlFamilyDump(boolean sequences) {
    this.sequences = sequences;
  }

  @Override
  public boolean foreignKeyChecks() {
    return true;
  }

  // Only REPEATABLE READ reads in one snapshot; SET TRANSACTION sets it for this transaction alone.
  @Override
  void begin(Connection connection, Provider provider) throws QueryException {
    try {
      Sql.execute(
          connection,
          provider,
          "SET @hooridb_time_zone = @@SESSION.time_zone, @hooridb_sql_mode = @@SESSION.sql_mode,"
              + " SESSION time_zone = '+00:00', SESSION sql_mode = '" + MODE + "'");
    } catch (QueryException e) {
      throw Sql.undone(e, () -> Sql.ending(connection, provider, SESSION_BACK));
    }
    try {
      Sql.execute(connection, provider, "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
      Sql.execute(connection, provider, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
    } catch (QueryException e) {
      throw Sql.undone(e, () -> end(connection, provider));
    }
  }

  // A snapshot of these servers locks no table, as the class says.
  @Override
  void lock(Connection connection, Provider provider, List<Item> tables) {
    // Nothing to lock.
  }

  @Override
  void end(Connection connection, Provider provider) throws QueryException {
    try {
      Sql.ending(connection, provider, "ROLLBACK");
    } finally {
      Sql.ending(connection, provider, SESSION_BACK);
    }
  }

  private static String literal(Provider provider, String text) {
    return provider.dialect().rows().literal(text);
  }

  @Override
  List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException {
    var database = container.database();
    var listed = new ArrayList<Listed>();
    var tables = Sql.some(
        connection,
        provider,
        """
        SELECT t.TABLE_NAME, t.TABLE_TYPE, t.TABLE_ROWS, t.DATA_LENGTH, e.TRANSACTIONS
        FROM information_schema.TABLES t
        LEFT JOIN information_schema.ENGINES e ON e.ENGINE = t.ENGINE
        WHERE t.TABLE_SCHEMA = ? ORDER BY t.TABLE_NAME""",
        rows -> {
          var kind = kind(rows.getString(2));
          return kind.isPresent()
              ? Optional.of(listed(database, kind.get(), rows))
              : Optional.<Listed>empty();
        },
        database.name());
    for (var kind : List.of(Item.Kind.TABLE, Item.Kind.VIEW, Item.Kind.SEQUENCE)) {
      tables.stream().filter(l -> l.item().kind() == kind).forEach(listed::add);
    }
    var routines = Sql.rows(
        connection,
        provider,
        """
        SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES
        WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME""",
        rows -> Listed.of(Item.of(
            database,
            rows.getString(2).equalsIgnoreCase("PROCEDURE")
                ? Item.Kind.PROCEDURE
                : Item.Kind.FUNCTION,
            rows.getString(1))),
        database.name());
    for (var kind : List.of(Item.Kind.FUNCTION, Item.Kind.PROCEDURE)) {
      routines.stream().filter(l -> l.item().kind() == kind).forEach(listed::add);
    }
    // In the order they run in, which creating them in it keeps.
    listed.addAll(Sql.rows(
        connection,
        provider,
        """
        SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS
        WHERE TRIGGER_SCHEMA = ?
        ORDER BY EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION, ACTION_ORDER""",
        rows -> Listed.of(
            new Item(database, Item.Kind.TRIGGER, rows.getString(1), "", rows.getString(2))),
        database.name()));
    return List.copyOf(listed);
  }

  // What a table's type lists as; nothing of the server's own, or of a temporary table.
  private Optional<Item.Kind> kind(String type) {
    return switch (type) {
      case "BASE TABLE", "SYSTEM VERSIONED" -> Optional.of(Item.Kind.TABLE);
      case "VIEW" -> Optional.of(Item.Kind.VIEW);
      case "SEQUENCE" -> sequences ? Optional.of(Item.Kind.SEQUENCE) : Optional.empty();
      default -> Optional.empty();
    };
  }

  private static Listed listed(Database database, Item.Kind kind, ResultSet rows)
      throws SQLException {
    var item = Item.of(database, kind, rows.getString(1));
    if (kind != Item.Kind.TABLE) {
      return Listed.of(item);
    }
    var estimate = rows.getLong(3);
    var unknown = rows.wasNull();
    var bytes = rows.getLong(4);
    return new Listed(
        item,
        unknown ? OptionalLong.empty() : OptionalLong.of(estimate),
        rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(bytes),
        "YES".equalsIgnoreCase(rows.getString(5)));
  }

  @Override
  List<Definition> define(Connection connection, Provider provider, List<Item> items)
      throws QueryException {
    var known = new HashSet<Item>();
    var references = new HashMap<String, Set<String>>(); // a table's, by its name
    for (var database :
        items.stream().map(i -> i.container().database()).distinct().toList()) {
      for (var listed : list(connection, provider, database)) {
        known.add(listed.item());
      }
      for (var reference : Sql.rows(
          connection,
          provider,
          """
          SELECT TABLE_NAME, REFERENCED_TABLE_NAME
          FROM information_schema.REFERENTIAL_CONSTRAINTS
          WHERE CONSTRAINT_SCHEMA = ? AND UNIQUE_CONSTRAINT_SCHEMA = ?""",
          rows -> List.of(rows.getString(1), rows.getString(2)),
          database.name(),
          database.name())) {
        references.computeIfAbsent(reference.get(0), k -> new HashSet<>()).add(reference.get(1));
      }
    }
    var definitions = new ArrayList<Definition>();
    for (var item : items) {
      if (!known.contains(item)) {
        throw QueryException.of(
            QueryException.Failure.NOT_FOUND, item.shown() + " no longer exists");
      }
      definitions.add(define(connection, provider, item, known, references));
    }
    return definitions;
  }

  private Definition define(
      Connection connection,
      Provider provider,
      Item item,
      Set<Item> known,
      Map<String, Set<String>> references)
      throws QueryException {
    var name = provider.quote(item.name());
    var qualified = provider.quote(item.container().name()) + "." + name;
    return switch (item.kind()) {
      case TABLE -> {
        var created = withoutDatabase(
            shown(connection, provider, item, "SHOW CREATE TABLE " + qualified),
            provider.quote(item.container().name()));
        var table = Created.of(created, name);
        var refers = new HashSet<Item>();
        for (var to : references.getOrDefault(item.name(), Set.of())) {
          var other = Item.of(item.container(), Item.Kind.TABLE, to);
          if (known.contains(other)) {
            refers.add(other);
          }
        }
        yield new Definition(
            item,
            List.of(table.create()),
            false,
            table.indexes(),
            table.keys(),
            List.of(),
            "DROP TABLE IF EXISTS " + name,
            List.of(),
            Set.of(),
            refers);
      }
      case VIEW -> {
        var created =
            withoutDefiner(shown(connection, provider, item, "SHOW CREATE VIEW " + qualified));
        yield new Definition(
            item,
            List.of(created),
            false,
            List.of(),
            List.of(),
            List.of(),
            "DROP VIEW IF EXISTS " + name,
            List.of(),
            quoted(provider, created, item, known),
            Set.of());
      }
      case SEQUENCE -> {
        var created = shown(connection, provider, item, "SHOW CREATE SEQUENCE " + qualified);
        var next = Sql.one(
            connection,
            provider,
            item.shown(),
            "SELECT next_not_cached_value FROM " + qualified,
            rows -> rows.getString(1));
        yield new Definition(
            item,
            List.of(created),
            false,
            List.of(),
            List.of(),
            List.of(),
            "DROP SEQUENCE IF EXISTS " + name,
            List.of("DO SETVAL(" + name + ", " + next + ", 0)"),
            Set.of(),
            Set.of());
      }
      case FUNCTION, PROCEDURE, TRIGGER -> routine(connection, provider, item, known);
    };
  }

  // A routine or trigger as it was created, in the SQL mode of its own, which reads its body.
  private static Definition routine(
      Connection connection, Provider provider, Item item, Set<Item> known) throws QueryException {
    var what = switch (item.kind()) {
      case FUNCTION -> "FUNCTION ";
      case PROCEDURE -> "PROCEDURE ";
      default -> "TRIGGER ";
    };
    var name = provider.quote(item.name());
    record Shown(String mode, String created) {}
    var shown = Sql.one(
        connection,
        provider,
        item.shown(),
        "SHOW CREATE " + what + provider.quote(item.container().name()) + "." + name,
        rows -> new Shown(rows.getString(2), rows.getString(3)));
    if (shown.created() == null) {
      throw QueryException.of(QueryException.Failure.ACCESS_DENIED, NO_DEFINITION + item.shown());
    }
    var needs = new HashSet<Item>();
    if (item.kind() == Item.Kind.TRIGGER) {
      var table = Item.of(item.container(), Item.Kind.TABLE, item.table());
      if (known.contains(table)) {
        needs.add(table);
      }
    }
    return new Definition(
        item,
        List.of(
            "SET SESSION sql_mode = " + literal(provider, shown.mode()),
            withoutDefiner(shown.created()),
            "SET SESSION sql_mode = '" + MODE + "'"),
        true,
        List.of(),
        List.of(),
        List.of(),
        "DROP " + what + "IF EXISTS " + name,
        List.of(),
        needs,
        Set.of());
  }

  // The definition in the second column of what SHOW CREATE shows.
  private static String shown(Connection connection, Provider provider, Item item, String sql)
      throws QueryException {
    var shown = Sql.one(connection, provider, item.shown(), sql, rows -> {
      var text = rows.getString(2);
      return text == null ? "" : text;
    });
    if (shown.isEmpty()) {
      throw QueryException.of(QueryException.Failure.ACCESS_DENIED, NO_DEFINITION + item.shown());
    }
    return shown;
  }

  /**
   * The table's definition without the name of its database where it names a sequence's next
   * value, as a column's default of MariaDB {@code nextval(`db`.`seq`)}, so that the table takes
   * that of the database it is read into.
   */
  static String withoutDatabase(String created, String database) {
    return created.replace("nextval(" + database + ".", "nextval(");
  }

  /** The definition without its {@code DEFINER=`user`@`host` }, where it has one. */
  static String withoutDefiner(String created) {
    var at = created.indexOf("DEFINER=`");
    if (at < 0) {
      return created;
    }
    var end = quotedEnd(created, at + "DEFINER=".length());
    if (end > 0 && created.startsWith("@`", end)) {
      end = quotedEnd(created, end + 1);
    }
    return end > 0 && created.startsWith(" ", end)
        ? created.substring(0, at) + created.substring(end + 1)
        : created;
  }

  // Where the name quoted in backticks at the index ends, past its closing one; -1 for none.
  private static int quotedEnd(String text, int at) {
    if (!text.startsWith("`", at)) {
      return -1;
    }
    var from = at + 1;
    while (true) {
      var close = text.indexOf('`', from);
      if (close < 0) {
        return -1;
      }
      if (!text.startsWith("``", close)) {
        return close + 1;
      }
      from = close + 2;
    }
  }

  // What a view needs: the tables, views and functions of its database whose quoted names it
  // holds, a function's before a parenthesis; a column of the same name as one adds it in vain.
  private static Set<Item> quoted(Provider provider, String created, Item item, Set<Item> known) {
    var needs = new HashSet<Item>();
    for (var other : known) {
      if (other.equals(item)) {
        continue;
      }
      var name = provider.quote(other.name());
      var found = switch (other.kind()) {
        case TABLE, VIEW -> created.contains(name);
        case FUNCTION -> created.contains(name + "(");
        default -> false;
      };
      if (found) {
        needs.add(other);
      }
    }
    return needs;
  }

  /**
   * A {@code CREATE TABLE} as {@code SHOW CREATE TABLE} writes it, each column, key and constraint
   * on a line of its own: without the foreign keys and the indexes that are no keys, which follow
   * as {@code ALTER TABLE}s, but an index on the {@code AUTO_INCREMENT} column, which the table
   * cannot be created without.
   */
  record Created(String create, List<String> indexes, List<String> keys) {

    static Created of(String created, String name) {
      var lines = created.split("\n", -1);
      var open = -1;
      var close = -1;
      for (var i = 0; i < lines.length; i++) {
        if (open < 0 && lines[i].endsWith("(")) {
          open = i;
        } else if (lines[i].startsWith(")")) {
          close = i;
        }
      }
      if (open < 0 || close < open) {
        return new Created(created, List.of(), List.of());
      }
      var inside = List.of(lines).subList(open + 1, close);
      var counted = counted(inside);
      var kept = new ArrayList<String>();
      var indexes = new ArrayList<String>();
      var keys = new ArrayList<String>();
      for (var line : inside) {
        var part = withoutComma(line.strip());
        if (foreignKey(part)) {
          keys.add("ALTER TABLE " + name + " ADD " + part);
        } else if (index(part)
            .filter(first -> counted.isEmpty() || !first.equals(counted))
            .isPresent()) {
          indexes.add("ALTER TABLE " + name + " ADD " + part);
        } else {
          kept.add(withoutComma(line));
        }
      }
      var create = new StringBuilder();
      for (var i = 0; i <= open; i++) {
        create.append(lines[i]).append('\n');
      }
      create.append(String.join(",\n", kept)).append('\n');
      create.append(String.join("\n", List.of(lines).subList(close, lines.length)));
      return new Created(create.toString(), List.copyOf(indexes), List.copyOf(keys));
    }

    private static String withoutComma(String line) {
      return line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
    }

    // The AUTO_INCREMENT column's quoted name, or empty: a column's line, which begins with its
    // name, says so before its comment.
    private static String counted(List<String> lines) {
      for (var line : lines) {
        var part = line.strip();
        var end = quotedEnd(part, 0);
        var comment = part.indexOf(" COMMENT '");
        var before = comment < 0 ? part : part.substring(0, comment);
        if (end > 0 && before.contains(" AUTO_INCREMENT")) {
          return part.substring(0, end);
        }
      }
      return "";
    }

    private static boolean foreignKey(String part) {
      if (!part.startsWith("CONSTRAINT `")) {
        return false;
      }
      var end = quotedEnd(part, "CONSTRAINT ".length());
      return end > 0 && part.startsWith(" FOREIGN KEY ", end);
    }

    // Of an index that is no key, its first column's quoted name, empty for an expression.
    private static Optional<String> index(String part) {
      var rest = part;
      for (var kind : List.of("FULLTEXT ", "SPATIAL ", "VECTOR ")) {
        if (rest.startsWith(kind)) {
          rest = rest.substring(kind.length());
        }
      }
      var word = List.of("KEY ", "INDEX ").stream().filter(rest::startsWith).findFirst();
      if (word.isEmpty()) {
        return Optional.empty();
      }
      var named = quotedEnd(rest, word.get().length());
      var column = named > 0 && rest.startsWith(" (", named) ? quotedEnd(rest, named + 2) : -1;
      return Optional.of(column > 0 ? rest.substring(named + 2, column) : "");
    }
  }

  private record Column(String name, String type) {}

  private record Selected(String select, Value value) {}

  // Of each column that is not generated, a value of a kind the literal writes as such: whole
  // numbers, decimals, floating point and binary data; dates and times as the server writes them
  // in the snapshot's zone, anything else as its text. A float is read as a double, which these
  // servers write with all its digits, where they write a float rounded to six.
  @Override
  Optional<Data> data(Connection connection, Provider provider, Item table) throws QueryException {
    var columns = Sql.some(
        connection,
        provider,
        """
        SELECT COLUMN_NAME, DATA_TYPE, EXTRA FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION""",
        rows -> generated(rows.getString(3))
            ? Optional.empty()
            : Optional.of(
                new Column(rows.getString(1), rows.getString(2).toLowerCase(Locale.ROOT))),
        table.container().name(),
        table.name());
    if (columns.isEmpty()) {
      return Optional.empty();
    }
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
        "SELECT " + String.join(", ", selected) + " FROM "
            + provider.quote(table.container().name()) + "." + name,
        "INSERT INTO " + name + " (" + String.join(", ", names) + ") VALUES",
        metadata -> values));
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
      header.add("SET SESSION foreign_key_checks = 0");
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
    return List.of(
        "SET @HOORIDB_DROP_CHECKS = @@SESSION.foreign_key_checks",
        "SET SESSION foreign_key_checks = 0");
  }

  @Override
  List<String> afterDrops() {
    return List.of("SET SESSION foreign_key_checks = @HOORIDB_DROP_CHECKS");
  }

  // As the server shows the database, with its character set and collation.
  @Override
  List<String> create(Connection connection, Provider provider, List<Container> containers)
      throws QueryException {
    var statements = new ArrayList<String>();
    for (var container : containers) {
      var name = provider.quote(container.name());
      var shown = Sql.one(
          connection,
          provider,
          container.name(),
          "SHOW CREATE DATABASE " + name,
          rows -> rows.getString(2));
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

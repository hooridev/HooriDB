package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How MariaDB's and MySQL's objects are exported: their definitions as {@code SHOW CREATE} gives
 * them, on a session on their database, which names them without it; a table's foreign keys, and
 * the indexes that are no keys, taken out of its {@code CREATE TABLE} to be added later. What a
 * view needs is found by the names its definition quotes, which these servers do not otherwise
 * tell alike; a definer is left out, so that the user who reads the file back defines.
 *
 * <p>The snapshot reads in UTC, and the file's session writes in it, so that a {@code timestamp}
 * comes back as the same moment; it reads the file with backslashes as escapes and a 0 as a value
 * of an {@code AUTO_INCREMENT} column, as its literals are written.
 */
public final class MySqlFamilyDump extends DumpDialect {

  private static final String MODE = "NO_AUTO_VALUE_ON_ZERO";
  private static final Pattern DEFINER =
      Pattern.compile("DEFINER=`(?:[^`]|``)*`(?:@`(?:[^`]|``)*`)? ");

  private final boolean sequences;

  /** @param sequences whether its servers have sequences, as MariaDB's do */
  public MySqlFamilyDump(boolean sequences) {
    this.sequences = sequences;
  }

  @Override
  public boolean foreignKeyChecks() {
    return true;
  }

  // The session's zone is kept on the server, for end() to put back.
  @Override
  void begin(Connection connection, Provider provider) throws QueryException {
    Sql.execute(
        connection,
        provider,
        "SET @hooridb_time_zone = @@SESSION.time_zone, SESSION time_zone = '+00:00'");
    try {
      Sql.execute(connection, provider, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
    } catch (QueryException e) {
      Sql.execute(connection, provider, "SET SESSION time_zone = @hooridb_time_zone");
      throw e;
    }
  }

  @Override
  void end(Connection connection, Provider provider) throws QueryException {
    try {
      Sql.ending(connection, provider, "ROLLBACK");
    } finally {
      Sql.ending(connection, provider, "SET SESSION time_zone = @hooridb_time_zone");
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
    var tables = Sql.rows(
        connection,
        provider,
        "SELECT t.TABLE_NAME, t.TABLE_TYPE, t.TABLE_ROWS, t.DATA_LENGTH, e.TRANSACTIONS"
            + " FROM information_schema.TABLES t"
            + " LEFT JOIN information_schema.ENGINES e ON e.ENGINE = t.ENGINE"
            + " WHERE t.TABLE_SCHEMA = ? ORDER BY t.TABLE_NAME",
        rows -> {
          var kind = switch (rows.getString(2)) {
            case "BASE TABLE", "SYSTEM VERSIONED" -> Item.Kind.TABLE;
            case "VIEW" -> Item.Kind.VIEW;
            case "SEQUENCE" -> sequences ? Item.Kind.SEQUENCE : null;
            default -> null;
          };
          if (kind == null) {
            return null; // the server's own, or a temporary table
          }
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
        },
        database.name());
    for (var kind : List.of(Item.Kind.TABLE, Item.Kind.VIEW, Item.Kind.SEQUENCE)) {
      tables.stream().filter(l -> l.item().kind() == kind).forEach(listed::add);
    }
    var routines = Sql.rows(
        connection,
        provider,
        "SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES"
            + " WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME",
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
        "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS"
            + " WHERE TRIGGER_SCHEMA = ? ORDER BY EVENT_OBJECT_TABLE, ACTION_TIMING,"
            + " EVENT_MANIPULATION, ACTION_ORDER",
        rows -> Listed.of(
            new Item(database, Item.Kind.TRIGGER, rows.getString(1), "", rows.getString(2))),
        database.name()));
    return List.copyOf(listed);
  }

  @Override
  List<Definition> define(Connection connection, Provider provider, List<Item> items)
      throws QueryException {
    var databases = new ArrayList<Database>();
    for (var item : items) {
      if (!databases.contains(item.container().database())) {
        databases.add(item.container().database());
      }
    }
    var known = new HashSet<Item>();
    var references = new HashMap<String, Set<String>>(); // a table's, by its name
    for (var database : databases) {
      for (var listed : list(connection, provider, database)) {
        known.add(listed.item());
      }
      for (var reference : Sql.rows(
          connection,
          provider,
          "SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS"
              + " WHERE CONSTRAINT_SCHEMA = ? AND UNIQUE_CONSTRAINT_SCHEMA = ?",
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
        var created = shown(connection, provider, item, "SHOW CREATE TABLE " + qualified, 2);
        var table = Created.of(created, name);
        var refers = new HashSet<Item>();
        for (var to : references.getOrDefault(item.name(), Set.of())) {
          var other = Item.of(item.container(), Item.Kind.TABLE, to);
          if (!other.equals(item) && known.contains(other)) {
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
            withoutDefiner(shown(connection, provider, item, "SHOW CREATE VIEW " + qualified, 2));
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
        var created = shown(connection, provider, item, "SHOW CREATE SEQUENCE " + qualified, 2);
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
      case FUNCTION, PROCEDURE, TRIGGER -> {
        var what = switch (item.kind()) {
          case FUNCTION -> "FUNCTION ";
          case PROCEDURE -> "PROCEDURE ";
          default -> "TRIGGER ";
        };
        record Shown(String mode, String created) {}
        var shown = Sql.one(
            connection,
            provider,
            item.shown(),
            "SHOW CREATE " + what + qualified,
            rows -> new Shown(rows.getString(2), rows.getString(3)));
        if (shown.created() == null) {
          throw QueryException.of(
              QueryException.Failure.ACCESS_DENIED,
              "The server does not show this user the definition of " + item.shown());
        }
        var created = withoutDefiner(shown.created());
        var needs = new HashSet<Item>();
        if (item.kind() == Item.Kind.TRIGGER) {
          var table = Item.of(item.container(), Item.Kind.TABLE, item.table());
          if (known.contains(table)) {
            needs.add(table);
          }
        }
        // As it was created, in the SQL mode of its own, which reads its body.
        yield new Definition(
            item,
            List.of(
                "SET SESSION sql_mode = " + literal(provider, shown.mode()),
                created,
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
    };
  }

  private static String shown(
      Connection connection, Provider provider, Item item, String sql, int column)
      throws QueryException {
    var shown = Sql.one(connection, provider, item.shown(), sql, rows -> {
      var text = rows.getString(column);
      return text == null ? "" : text;
    });
    if (shown.isEmpty()) {
      throw QueryException.of(
          QueryException.Failure.ACCESS_DENIED,
          "The server does not show this user the definition of " + item.shown());
    }
    return shown;
  }

  static String withoutDefiner(String created) {
    return DEFINER.matcher(created).replaceFirst("");
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
   * as {@code ALTER TABLE}s.
   */
  record Created(String create, List<String> indexes, List<String> keys) {

    private static final Pattern INDEX =
        Pattern.compile("(?:(?:FULLTEXT|SPATIAL|VECTOR) )?(?:KEY|INDEX) .*");
    private static final Pattern KEY = Pattern.compile("CONSTRAINT `(?:[^`]|``)*` FOREIGN KEY .*");

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
      var kept = new ArrayList<String>();
      var indexes = new ArrayList<String>();
      var keys = new ArrayList<String>();
      for (var i = open + 1; i < close; i++) {
        var line = lines[i].strip();
        var part = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
        if (KEY.matcher(part).matches()) {
          keys.add("ALTER TABLE " + name + " ADD " + part);
        } else if (INDEX.matcher(part).matches()) {
          indexes.add("ALTER TABLE " + name + " ADD " + part);
        } else {
          kept.add(
              lines[i].endsWith(",") ? lines[i].substring(0, lines[i].length() - 1) : lines[i]);
        }
      }
      var create = new StringBuilder();
      for (var i = 0; i <= open; i++) {
        create.append(lines[i]).append('\n');
      }
      create.append(String.join(",\n", kept)).append('\n');
      for (var i = close; i < lines.length; i++) {
        create.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
      }
      return new Created(create.toString(), List.copyOf(indexes), List.copyOf(keys));
    }
  }

  private record Column(String name, String type) {}

  // Of each column that is not generated, a value of a kind the literal writes as such: whole
  // numbers, decimals, floating point and binary data; dates and times as the server writes them
  // in the snapshot's zone, anything else as its text.
  @Override
  Data data(Connection connection, Provider provider, Item table) throws QueryException {
    var columns = Sql.rows(
        connection,
        provider,
        "SELECT COLUMN_NAME, DATA_TYPE, EXTRA FROM information_schema.COLUMNS"
            + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
        rows -> {
          var extra = rows.getString(3) == null ? "" : rows.getString(3).toUpperCase(Locale.ROOT);
          return extra.contains("VIRTUAL GENERATED")
                  || extra.contains("STORED GENERATED")
                  || extra.contains("PERSISTENT GENERATED")
              ? null
              : new Column(rows.getString(1), rows.getString(2).toLowerCase(Locale.ROOT));
        },
        table.container().name(),
        table.name());
    if (columns.isEmpty()) {
      return null;
    }
    var selected = new ArrayList<String>();
    var names = new ArrayList<String>();
    var values = new ArrayList<Value>();
    for (var column : columns) {
      var quoted = provider.quote(column.name());
      names.add(quoted);
      switch (column.type()) {
        case "tinyint", "smallint", "mediumint", "int", "integer", "bigint" -> {
          selected.add(quoted);
          values.add((rows, i) -> {
            var text = rows.getString(i);
            return text == null ? null : new BigInteger(text);
          });
        }
        case "bit" -> {
          selected.add("CAST(" + quoted + " AS UNSIGNED)");
          values.add((rows, i) -> {
            var text = rows.getString(i);
            return text == null ? null : new BigInteger(text);
          });
        }
        case "decimal", "numeric" -> {
          selected.add(quoted);
          values.add((rows, i) -> {
            var text = rows.getString(i);
            return text == null ? null : new BigDecimal(text);
          });
        }
        case "float", "double", "real" -> {
          selected.add(quoted);
          values.add((rows, i) -> {
            var text = rows.getString(i);
            return text == null ? null : Double.valueOf(text);
          });
        }
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
            "geomcollection" -> {
          selected.add(quoted);
          values.add((rows, i) -> {
            var bytes = rows.getBytes(i);
            return bytes == null ? null : new Binary(bytes, bytes.length);
          });
        }
        default -> {
          selected.add("CAST(" + quoted + " AS CHAR)");
          values.add((rows, i) -> rows.getString(i));
        }
      }
    }
    var name = provider.quote(table.name());
    return new Data(
        "SELECT " + String.join(", ", selected) + " FROM "
            + provider.quote(table.container().name()) + "." + name,
        "INSERT INTO " + name + " (" + String.join(", ", names) + ") VALUES",
        metadata -> values);
  }

  @Override
  List<String> header(Dump.Options options) {
    var header = new ArrayList<>(List.of(
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
    return footer;
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

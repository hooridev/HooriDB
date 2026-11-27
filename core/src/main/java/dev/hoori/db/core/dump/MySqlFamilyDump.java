package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
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
 * view needs is found by the names its definition quotes where they name a table, view or function,
 * which these servers do not otherwise tell alike; a definer is left out, so that the user who reads
 * the file back defines.
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
 *
 * <p>These servers cannot keep a table's triggers from firing for the rows read into it: an export
 * of the data alone, which reads them into tables that have their triggers, refuses a table that
 * has any.
 */
public final class MySqlFamilyDump extends DumpDialect {

  private static final String MODE = "NO_AUTO_VALUE_ON_ZERO";
  private static final String NO_KEY_CHECKS = "SET SESSION foreign_key_checks = 0";
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

  @Override
  public boolean quietsTriggers() {
    return false;
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
            provider,
            shown(connection, provider, item, "SHOW CREATE TABLE " + qualified),
            provider.quote(item.container().name()));
        var table = Created.of(provider, created, name);
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
            List.of(),
            viewNeeds(provider, created, item, known),
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
            List.of(),
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
   * that of the database it is read into; a text that holds the same stays as it is.
   */
  static String withoutDatabase(Provider provider, String created, String database) {
    var without = new StringBuilder();
    var pieces = Statements.pieces(created, provider);
    var from = 0;
    for (var k = 0; k + 1 < pieces.size(); k++) {
      var piece = pieces.get(k);
      if (piece.kind() == Statements.Piece.Kind.QUOTED
          && created.substring(piece.start(), piece.end()).equals(database)
          && code(created, pieces.get(k + 1), '.')
          && created.substring(0, piece.start()).toLowerCase(Locale.ROOT).endsWith("nextval(")) {
        without.append(created, from, piece.start());
        from = pieces.get(k + 1).end();
      }
    }
    return without.append(created, from, created.length()).toString();
  }

  // Whether the piece is the one character of code.
  private static boolean code(String text, Statements.Piece piece, char c) {
    return piece.kind() == Statements.Piece.Kind.CODE && text.charAt(piece.start()) == c;
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

  /**
   * What a view needs: the tables, views and functions of its database that it names, a function
   * before a parenthesis. As these servers write a view, a name it reads from stands alone or after
   * its database's: one before a period qualifies a column, one after another name or a closing
   * parenthesis is an alias of what it reads, as one after {@code AS} is of a column, and a name in
   * a text is none.
   */
  static Set<Item> viewNeeds(Provider provider, String created, Item item, Set<Item> known) {
    var database = provider.quote(item.container().name());
    var pieces = Statements.pieces(created, provider);
    var tables = new HashSet<String>();
    var functions = new HashSet<String>();
    for (var k = 0; k < pieces.size(); k++) {
      if (readFrom(created, pieces, k, database)) {
        var piece = pieces.get(k);
        var name = unquoted(created.substring(piece.start(), piece.end()));
        (followedBy(created, pieces, k, '(') ? functions : tables).add(name);
      }
    }
    var needs = new HashSet<Item>();
    for (var other : known) {
      var found = switch (other.kind()) {
        case TABLE, VIEW -> tables.contains(other.name());
        case FUNCTION -> functions.contains(other.name());
        default -> false;
      };
      if (found && !other.equals(item)) {
        needs.add(other);
      }
    }
    return needs;
  }

  // Whether the piece at the index is a quoted name the view reads from: alone, or after the
  // database's quoted name and a period.
  private static boolean readFrom(
      String created, List<Statements.Piece> pieces, int k, String database) {
    var piece = pieces.get(k);
    if (piece.kind() != Statements.Piece.Kind.QUOTED || followedBy(created, pieces, k, '.')) {
      return false;
    }
    if (k == 0) {
      return true;
    }
    var before = pieces.get(k - 1);
    if (code(created, before, '.')) {
      var qualifier = k > 1 ? pieces.get(k - 2) : null;
      return qualifier != null
          && qualifier.kind() == Statements.Piece.Kind.QUOTED
          && created.substring(qualifier.start(), qualifier.end()).equals(database);
    }
    return before.kind() != Statements.Piece.Kind.QUOTED
        && !code(created, before, ')')
        && !afterAs(created, piece.start());
  }

  // Whether the piece after the index is the code character.
  private static boolean followedBy(String created, List<Statements.Piece> pieces, int k, char c) {
    return k + 1 < pieces.size() && code(created, pieces.get(k + 1), c);
  }

  // Whether the code right before the offset ends in the word AS.
  private static boolean afterAs(String text, int at) {
    var before = text.substring(0, at).stripTrailing();
    return before.length() >= 2
        && before.regionMatches(true, before.length() - 2, "AS", 0, 2)
        && (before.length() == 2
            || !Character.isLetterOrDigit(before.charAt(before.length() - 3))
                && before.charAt(before.length() - 3) != '_');
  }

  // A name quoted in backticks, as it is.
  private static String unquoted(String quoted) {
    return quoted.substring(1, quoted.length() - 1).replace("``", "`");
  }

  /**
   * A {@code CREATE TABLE} as {@code SHOW CREATE TABLE} writes it, each column, key and constraint
   * on a line of its own: without the foreign keys and the indexes that are no keys, which follow
   * as {@code ALTER TABLE}s, but an index on the {@code AUTO_INCREMENT} column, which the table
   * cannot be created without.
   */
  record Created(String create, List<String> indexes, List<String> keys) {

    static Created of(Provider provider, String created, String name) {
      var outline = Outline.of(created, Statements.pieces(created, provider));
      if (outline.isEmpty()) {
        return new Created(created, List.of(), List.of());
      }
      var open = outline.get().open();
      var close = outline.get().close();
      var parts = new ArrayList<String>();
      var from = open + 1;
      for (var comma : outline.get().commas()) {
        parts.add(created.substring(from, comma).strip());
        from = comma + 1;
      }
      parts.add(created.substring(from, close).strip());
      var counted = counted(parts);
      var kept = new ArrayList<String>();
      var indexes = new ArrayList<String>();
      var keys = new ArrayList<String>();
      for (var part : parts) {
        if (foreignKey(part)) {
          keys.add("ALTER TABLE " + name + " ADD " + part);
        } else if (index(part)
            .filter(first -> counted.isEmpty() || !first.equals(counted))
            .isPresent()) {
          indexes.add("ALTER TABLE " + name + " ADD " + part);
        } else {
          kept.add(part);
        }
      }
      return new Created(
          created.substring(0, open + 1) + "\n  " + String.join(",\n  ", kept) + "\n"
              + created.substring(close),
          List.copyOf(indexes),
          List.copyOf(keys));
    }

    /**
     * The parenthesis that holds the columns, keys and constraints, and the commas between them,
     * outside names and texts.
     */
    private record Outline(int open, int close, List<Integer> commas) {

      static Optional<Outline> of(String created, List<Statements.Piece> pieces) {
        var open = -1;
        var depth = 0;
        var commas = new ArrayList<Integer>();
        for (var piece : pieces) {
          var at = piece.start();
          var c = piece.kind() == Statements.Piece.Kind.CODE ? created.charAt(at) : ' ';
          if (c == '(' && depth == 0 && open < 0) {
            open = at;
          }
          if (c == ',' && depth == 1 && open >= 0) {
            commas.add(at);
          }
          depth += switch (c) {
            case '(' -> 1;
            case ')' -> -1;
            default -> 0;
          };
          if (c == ')' && depth == 0 && open >= 0) {
            return Optional.of(new Outline(open, at, List.copyOf(commas)));
          }
        }
        return Optional.empty();
      }
    }

    // The AUTO_INCREMENT column's quoted name, or empty: a column's part, which begins with its
    // name, says so before its comment.
    private static String counted(List<String> parts) {
      for (var part : parts) {
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
  // servers write with all its digits, where they write a float rounded to six. A table without
  // such a column has its rows as INSERTs of empty rows, which take its defaults.
  @Override
  Optional<Data> data(Connection connection, Provider provider, Item table, boolean alone)
      throws QueryException {
    if (alone) {
      refuseTriggers(connection, provider, table);
    }
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
        transactional(connection, provider, table),
        metadata -> values,
        Around.NOTHING));
  }

  // Whether the table's engine keeps transactions; one that the server does not tell of is taken
  // to.
  private static boolean transactional(Connection connection, Provider provider, Item table)
      throws QueryException {
    var keeps = Sql.rows(
        connection,
        provider,
        """
        SELECT e.TRANSACTIONS FROM information_schema.TABLES t
        JOIN information_schema.ENGINES e ON e.ENGINE = t.ENGINE
        WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ?""",
        rows -> rows.getString(1),
        table.container().name(),
        table.name());
    return keeps.isEmpty() || !"NO".equalsIgnoreCase(keeps.getFirst());
  }

  // The rows of an export of the data alone go into a table that has the triggers it has here,
  // which these servers cannot keep from firing for them.
  private static void refuseTriggers(Connection connection, Provider provider, Item table)
      throws QueryException {
    var triggers = Sql.rows(
        connection,
        provider,
        """
        SELECT TRIGGER_NAME FROM information_schema.TRIGGERS
        WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ? ORDER BY TRIGGER_NAME""",
        rows -> rows.getString(1),
        table.container().name(),
        table.name());
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

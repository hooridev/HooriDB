package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How MariaDB's and MySQL's views are read and changed. These servers keep no dependencies of a
 * view: what it uses is what its definition, as {@code information_schema.VIEWS} gives it with
 * every name qualified, names, a routine by its name before a parenthesis, looked up in the
 * databases it names alone; what uses it is each view whose definition quotes its name. Each
 * statement of DDL commits by itself, a view takes whatever columns its new query gives, and a
 * drop leaves what used the view without it.
 */
public final class MySqlFamilyViews extends ViewDialect {

  // The head of a statement that creates a view: CREATE, its options, each after white space and
  // followed by it, and the word VIEW.
  private static final Pattern CREATE =
      Pattern.compile("(?is)CREATE(?<replace>\\s+OR\\s+REPLACE)?");
  private static final Pattern ALGORITHM =
      Pattern.compile("(?is)\\s+ALGORITHM\\s*=\\s*\\w+(?=\\s)");
  private static final Pattern SECURITY =
      Pattern.compile("(?is)\\s+SQL\\s+SECURITY\\s+\\w+(?=\\s)");
  private static final Pattern DEFINER = Pattern.compile("(?is)\\s+DEFINER\\s*=\\s*");
  private static final Pattern VIEW = Pattern.compile("(?is)\\s+VIEW\\s+");

  // A definer's user: the current one, but where a host follows, as a name then takes it; else a
  // name, and a host after an at sign. A name is in backticks, single or double quotes, or bare.
  private static final Pattern CURRENT_USER =
      Pattern.compile("(?i)CURRENT_USER(?:\\s*\\(\\s*\\))?(?!\\s*@)(?=\\s)");
  private static final Pattern AT = Pattern.compile("\\s*@\\s*");
  private static final List<Pattern> NAMES = List.of(
      Pattern.compile("(?s)`(?:[^`]|``)*`"),
      Pattern.compile("(?s)'(?:[^'\\\\]|\\\\.|'')*'"),
      Pattern.compile("(?s)\"(?:[^\"\\\\]|\\\\.|\"\")*\""),
      Pattern.compile("[^\\s@`'\"=]+"));

  // The databases that are the server's own, whose objects no view of the user's is about.
  private static final Set<String> SYSTEM =
      Set.of("mysql", "sys", "performance_schema", "information_schema");

  public MySqlFamilyViews() {
    // Nothing to set up.
  }

  @Override
  public References references(Sql sql, Table view) throws QueryException {
    var on = view.container().database().name();
    var definition = sql.rows(
        """
        SELECT VIEW_DEFINITION FROM information_schema.VIEWS
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?""",
        rows -> {
          var text = rows.getString(1);
          return text == null ? "" : text;
        },
        on,
        view.name());
    var named = named(sql.provider(), definition.isEmpty() ? "" : definition.getFirst());
    var databases = new LinkedHashSet<String>();
    named.forEach(n -> databases.add(n.database()));
    databases.removeAll(SYSTEM);
    var uses = databases.isEmpty() ? List.<Reference>of() : uses(sql, view, named, databases);
    // Compared as bytes, as the names are, whatever the collation of information_schema.
    var usedBy = sql.rows(
        """
        SELECT v.TABLE_SCHEMA, v.TABLE_NAME FROM information_schema.VIEWS v
        WHERE LOCATE(CAST(CONCAT('`', REPLACE(?, '`', '``'), '`.`', REPLACE(?, '`', '``'), '`')
          AS BINARY), CAST(v.VIEW_DEFINITION AS BINARY)) > 0
        AND NOT (v.TABLE_SCHEMA = ? AND v.TABLE_NAME = ?)
        ORDER BY v.TABLE_SCHEMA, v.TABLE_NAME""",
        rows -> new Reference(
            new Database(rows.getString(1), false), Reference.Kind.VIEW, rows.getString(2), ""),
        on,
        view.name(),
        on,
        view.name());
    return new References(uses, usedBy);
  }

  // The tables, views and routines of the databases that the definition names.
  private static List<Reference> uses(Sql sql, Table view, Set<Named> named, Set<String> databases)
      throws QueryException {
    var on = view.container().database().name();
    var uses = new ArrayList<Reference>();
    var in = String.join(", ", Collections.nCopies(databases.size(), "?"));
    var parameters = databases.toArray(String[]::new);
    uses.addAll(sql.some(
        """
        SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES
        WHERE TABLE_SCHEMA IN (%s) ORDER BY TABLE_SCHEMA, TABLE_NAME""".formatted(in),
        rows -> {
          var database = rows.getString(1);
          var name = rows.getString(2);
          var found = new Named(database, name, false);
          return named.contains(found) && !(database.equals(on) && name.equals(view.name()))
              ? relation(rows.getString(3))
                  .map(kind -> new Reference(new Database(database, false), kind, name, ""))
              : Optional.<Reference>empty();
        },
        parameters));
    uses.addAll(sql.some(
        """
        SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES
        WHERE ROUTINE_SCHEMA IN (%s) ORDER BY ROUTINE_SCHEMA, ROUTINE_NAME""".formatted(in),
        rows -> {
          var database = rows.getString(1);
          var name = rows.getString(2);
          return named.contains(new Named(database, name, true))
              ? Optional.of(new Reference(
                  new Database(database, false), routineKind(rows.getString(3)), name, ""))
              : Optional.<Reference>empty();
        },
        parameters));
    return uses;
  }

  private static Reference.Kind routineKind(String type) {
    return type.equalsIgnoreCase("PROCEDURE") ? Reference.Kind.PROCEDURE : Reference.Kind.FUNCTION;
  }

  // An object a definition names with its database, a routine by a parenthesis after it.
  record Named(String database, String name, boolean called) {}

  /**
   * The objects that the definition, every name qualified, names: of each name of two or three
   * parts its first two, a database's and an object's, which a column's third follows.
   */
  static Set<Named> named(Provider provider, String definition) {
    var pieces = Statements.pieces(definition, provider);
    var named = new LinkedHashSet<Named>();
    var k = 0;
    while (k < pieces.size()) {
      var chain = new ArrayList<String>();
      var at = k;
      while (at < pieces.size() && pieces.get(at).kind() == Statements.Piece.Kind.QUOTED) {
        var piece = pieces.get(at);
        chain.add(unquote(definition.substring(piece.start(), piece.end())));
        if (at + 2 < pieces.size() && code(definition, pieces.get(at + 1), '.')) {
          at += 2;
        } else {
          at++;
          break;
        }
      }
      if (chain.size() >= 2) {
        var called =
            chain.size() == 2 && at < pieces.size() && code(definition, pieces.get(at), '(');
        named.add(new Named(chain.get(0), chain.get(1), called));
      }
      k = Math.max(at, k + 1);
    }
    return named;
  }

  private static boolean code(String text, Statements.Piece piece, char c) {
    return piece.kind() == Statements.Piece.Kind.CODE && text.charAt(piece.start()) == c;
  }

  private static String unquote(String quoted) {
    return quoted.substring(1, quoted.length() - 1).replace("``", "`");
  }

  private static Optional<Reference.Kind> relation(String type) {
    return switch (type) {
      case "BASE TABLE", "SYSTEM VERSIONED" -> Optional.of(Reference.Kind.TABLE);
      case "VIEW" -> Optional.of(Reference.Kind.VIEW);
      default -> Optional.empty();
    };
  }

  // These servers have no materialized views.
  @Override
  public boolean refreshesConcurrently(Sql sql, Table view) {
    return false;
  }

  // Nothing is dropped and created anew in one change here.
  @Override
  public List<String> restore(Sql sql, Table view) {
    return List.of();
  }

  // A replace takes the whole definition, its options too.
  @Override
  public List<String> unset(Sql sql, Table view) {
    return List.of();
  }

  // Each statement of DDL commits what went before it, and itself.
  @Override
  public boolean changesInTransaction() {
    return false;
  }

  // RESTRICT and CASCADE are taken and do nothing: a view that used the one dropped stays, invalid.
  @Override
  public boolean dropsDependents() {
    return false;
  }

  // A view's columns are whatever its new SELECT gives.
  @Override
  public boolean keepsColumns(QueryException refusal) {
    return false;
  }

  // Nothing keeps a view from being dropped.
  @Override
  public boolean dependedOn(QueryException refusal) {
    return false;
  }

  @Override
  Optional<Head> head(String statement) {
    var create = CREATE.matcher(statement);
    if (!create.lookingAt()) {
      return Optional.empty();
    }
    var at = create.end();
    for (var next = option(statement, at); next >= 0; next = option(statement, at)) {
      at = next;
    }
    var end = matchAt(VIEW, statement, at);
    return end < 0
        ? Optional.empty()
        : Optional.of(new Head(end, create.group("replace") != null, false));
  }

  // Where the option before VIEW that begins at the offset ends, or -1 where none does.
  private static int option(String statement, int at) {
    for (var option : List.of(ALGORITHM, SECURITY)) {
      var end = matchAt(option, statement, at);
      if (end >= 0) {
        return end;
      }
    }
    var user = matchAt(DEFINER, statement, at);
    return user < 0 ? -1 : user(statement, user);
  }

  // Where a definer's user that begins at the offset ends before white space, or -1.
  private static int user(String statement, int at) {
    var current = matchAt(CURRENT_USER, statement, at);
    if (current >= 0) {
      return current;
    }
    var name = name(statement, at);
    if (name < 0) {
      return -1;
    }
    var host = matchAt(AT, statement, name);
    var end = host < 0 ? -1 : name(statement, host);
    if (end >= 0 && spaceAt(statement, end)) {
      return end;
    }
    return spaceAt(statement, name) ? name : -1;
  }

  // Where the name that begins at the offset ends, or -1.
  private static int name(String statement, int at) {
    for (var name : NAMES) {
      var end = matchAt(name, statement, at);
      if (end >= 0) {
        return end;
      }
    }
    return -1;
  }

  private static boolean spaceAt(String statement, int at) {
    return at < statement.length() && Character.isWhitespace(statement.charAt(at));
  }

  @Override
  String unquoted(String part) {
    return unquote(part);
  }

  @Override
  String bare(String word) {
    return word;
  }

  @Override
  public String drop(Provider provider, Table view, boolean dependents) {
    return "DROP VIEW " + Rows.qualified(provider, view);
  }

  @Override
  public String refresh(Provider provider, Table view, boolean concurrently) {
    throw new IllegalArgumentException(provider.displayName() + " has no materialized views");
  }
}

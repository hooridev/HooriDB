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

  // A name in backticks, single or double quotes, or bare, as a definer's user and host are.
  private static final String NAME = "(?:`(?:[^`]|``)*`|'(?:[^'\\\\]|\\\\.|'')*'"
      + "|\"(?:[^\"\\\\]|\\\\.|\"\")*\"|[^\\s@`'\"=]+)";
  private static final String USER =
      "(?:CURRENT_USER(?:\\s*\\(\\s*\\))?|" + NAME + "(?:\\s*@\\s*" + NAME + ")?)";
  private static final Pattern HEAD = Pattern.compile("(?is)CREATE(?<replace>\\s+OR\\s+REPLACE)?"
      + "(?:\\s+(?:ALGORITHM\\s*=\\s*\\w+|DEFINER\\s*=\\s*" + USER + "|SQL\\s+SECURITY\\s+\\w+))*"
      + "\\s+VIEW\\s+");

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
    var uses = new ArrayList<Reference>();
    if (!databases.isEmpty()) {
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
                    new Database(database, false),
                    rows.getString(3).equalsIgnoreCase("PROCEDURE")
                        ? Reference.Kind.PROCEDURE
                        : Reference.Kind.FUNCTION,
                    name,
                    ""))
                : Optional.<Reference>empty();
          },
          parameters));
    }
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
  Pattern head() {
    return HEAD;
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

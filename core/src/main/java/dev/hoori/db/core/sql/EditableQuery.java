package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Set;

/**
 * Lexical checks on SQL statements for deciding whether a statement's result can be edited and
 * whether a session has created a temporary table.
 *
 * <p>A statement passes {@link #singleTable} only where every way the server may read its text
 * ({@link Tokens#readings}) agrees: backslash escapes on or off, and MariaDB's and MySQL's
 * executable comments read as code and as comments. {@link #createsTemporaryTable} reports true
 * where any reading sees one.
 */
public final class EditableQuery {

  // Clauses allowed directly after the single table reference in FROM.
  private static final Set<String> AFTER_FROM =
      Set.of("WHERE", "GROUP", "HAVING", "WINDOW", "ORDER", "LIMIT", "OFFSET", "FETCH");

  // Forbidden at any depth: what writes, combines results, or repeats a row as with ROLLUP.
  private static final Set<String> FORBIDDEN =
      Set.of("INTO", "UNION", "INTERSECT", "EXCEPT", "ROLLUP", "CUBE", "GROUPING");

  private EditableQuery() {}

  /**
   * Whether the text is a single {@code SELECT} statement reading directly from one named table,
   * with no {@code WITH}, subquery in {@code FROM}, join, set operation, {@code INTO} or locking
   * clause. Subqueries in {@code WHERE} are allowed; one in the select list is not, as MariaDB and
   * MySQL report the origin of a column it selects as if the outer query did.
   */
  public static boolean singleTable(String sql, Provider provider) {
    if (provider.keepsDocuments() || Statements.split(sql, provider).size() != 1) {
      return false;
    }
    for (var reading : Tokens.readings(provider)) {
      if (!singleTable(Tokens.of(sql, provider, reading))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether any reading of the SQL text may create a temporary table: {@code CREATE … TEMPORARY
   * TABLE}, including {@code CREATE OR REPLACE TEMPORARY TABLE} and {@code CREATE TEMP TABLE}, or a
   * {@code CALL} or {@code EXECUTE}, whose procedure or prepared statement may.
   */
  // shortcut: a stored function or trigger that creates one is not seen; make the server tell, if
  // it ever can, from the console's session.
  public static boolean createsTemporaryTable(String sql, Provider provider) {
    if (provider.keepsDocuments()) {
      return false;
    }
    for (var reading : Tokens.readings(provider)) {
      if (createsTemporaryTable(Tokens.of(sql, provider, reading))) {
        return true;
      }
    }
    return false;
  }

  private static boolean singleTable(List<Tokens.Token> tokens) {
    int n = tokens.size();
    if (n == 0 || !tokens.getFirst().is("SELECT")) {
      return false;
    }
    int from = -1;
    int parens = 0;
    for (int i = 0; i < n; i++) {
      var token = tokens.get(i);
      if (token.is("(")) {
        parens++;
      } else if (token.is(")")) {
        if (--parens < 0) {
          return false;
        }
      } else if (token.is(";")) {
        if (i != n - 1 || token.depth() != 0) {
          return false;
        }
      } else if (forbidden(tokens, i)) {
        return false;
      } else if (from < 0 && token.depth() > 0 && token.is("SELECT")) {
        return false;
      } else if (from < 0 && token.depth() == 0 && token.is("FROM") && !distinct(tokens, i)) {
        from = i;
      }
    }
    if (parens != 0 || from < 0) {
      return false;
    }
    return singleTableFrom(tokens, from + 1);
  }

  // Whether the FROM at the index ends IS [NOT] DISTINCT FROM.
  private static boolean distinct(List<Tokens.Token> tokens, int from) {
    return from >= 2
        && tokens.get(from - 1).is("DISTINCT")
        && (tokens.get(from - 2).is("IS") || tokens.get(from - 2).is("NOT"));
  }

  // Forbidden anywhere, at any depth: the words above, and locking clauses.
  private static boolean forbidden(List<Tokens.Token> tokens, int i) {
    var token = tokens.get(i);
    if (!token.ident() && FORBIDDEN.contains(Tokens.keyword(token.text()))) {
      return true;
    }
    int n = tokens.size();
    if (token.is("FOR") && i + 1 < n) {
      var next = tokens.get(i + 1);
      if (next.is("UPDATE") || next.is("SHARE") || next.is("NO") || next.is("KEY")) {
        return true;
      }
    }
    return token.is("LOCK")
        && i + 3 < n
        && tokens.get(i + 1).is("IN")
        && tokens.get(i + 2).is("SHARE")
        && tokens.get(i + 3).is("MODE");
  }

  // One table name (name tokens joined by "."), an optional alias ("AS name" or bare name), and
  // then only the end, a trailing ";", or a whitelisted top-level clause.
  private static boolean singleTableFrom(List<Tokens.Token> tokens, int start) {
    int n = tokens.size();
    int k = start;
    if (k >= n || tokens.get(k).depth() != 0 || !tokens.get(k).name()) {
      return false;
    }
    k++;
    while (k + 1 < n
        && tokens.get(k).depth() == 0
        && tokens.get(k).is(".")
        && tokens.get(k + 1).depth() == 0
        && tokens.get(k + 1).name()) {
      k += 2;
    }
    if (k < n && tokens.get(k).depth() == 0 && tokens.get(k).is("AS")) {
      k++;
      if (k >= n || tokens.get(k).depth() != 0 || !tokens.get(k).name()) {
        return false;
      }
      k++;
    } else if (k < n && tokens.get(k).depth() == 0 && bareAlias(tokens.get(k))) {
      k++;
    }
    if (k == n || (k == n - 1 && tokens.get(k).is(";"))) {
      return true;
    }
    return tokens.get(k).depth() == 0 && afterFrom(tokens.get(k));
  }

  // A word that is no clause after FROM nor a join of one word, whose right side may be a table
  // named as a clause, such as MySQL's offset; a keyword taken for an alias, as LEFT, leaves a word
  // that no clause allows.
  private static boolean bareAlias(Tokens.Token token) {
    return token.name() && !afterFrom(token) && !token.is("JOIN") && !token.is("STRAIGHT_JOIN");
  }

  private static boolean afterFrom(Tokens.Token token) {
    return !token.ident() && AFTER_FROM.contains(Tokens.keyword(token.text()));
  }

  private static boolean createsTemporaryTable(List<Tokens.Token> tokens) {
    int n = tokens.size();
    if (n > 0 && (tokens.getFirst().is("CALL") || tokens.getFirst().is("EXECUTE"))) {
      return true;
    }
    for (int i = 0; i < n; i++) {
      if (!tokens.get(i).is("CREATE")) {
        continue;
      }
      int k = i + 1;
      if (k + 1 < n && tokens.get(k).is("OR") && tokens.get(k + 1).is("REPLACE")) {
        k += 2;
      }
      if (k < n && (tokens.get(k).is("LOCAL") || tokens.get(k).is("GLOBAL"))) {
        k++;
      }
      if (k < n && (tokens.get(k).is("TEMPORARY") || tokens.get(k).is("TEMP"))) {
        k++;
      } else {
        continue;
      }
      if (k < n && tokens.get(k).is("TABLE")) {
        return true;
      }
    }
    return false;
  }
}

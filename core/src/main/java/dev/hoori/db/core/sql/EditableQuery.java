package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

  // First words of a statement that runs others.
  private static final Set<String> RUNS_OTHERS =
      Set.of("CALL", "EXECUTE", "IF", "CASE", "LOOP", "REPEAT", "WHILE", "FOR");

  private EditableQuery() {}

  /**
   * The select list of a single {@code SELECT} statement that reads directly from one named table.
   *
   * @param items the number of its items, which is the number of the result's columns where no
   *     {@code *} stands for more than one
   * @param subqueries the positions, from 0, of the items that hold a subquery, whose columns have
   *     no origin, as MariaDB and MySQL report the origin of a column such a subquery selects as if
   *     the outer query did
   */
  public record SelectList(int items, Set<Integer> subqueries) {}

  /**
   * The select list where the text is a single {@code SELECT} statement reading directly from one
   * named table, with no {@code WITH}, subquery in {@code FROM}, join, set operation, {@code INTO}
   * or locking clause; subqueries in {@code WHERE} and in the select list are allowed.
   */
  public static Optional<SelectList> singleTable(String sql, Provider provider) {
    if (provider.keepsDocuments() || Statements.split(sql, provider).size() != 1) {
      return Optional.empty();
    }
    // Every reading must find the same code: a server may run some executable comments and not
    // others, a mix that no single reading shows, and a string may end elsewhere by its escapes.
    Shape shape = null;
    for (var reading : Tokens.readings(provider)) {
      var found = singleTable(Tokens.of(sql, provider, reading));
      if (found == null || shape != null && !shape.equals(found)) {
        return Optional.empty();
      }
      shape = found;
    }
    return Optional.of(shape.list());
  }

  // The select list, and the code of the whole statement.
  private record Shape(List<String> code, SelectList list) {}

  /**
   * Whether any reading of the text of one statement, as the console runs each on its own, may
   * create a temporary table: {@code CREATE … TEMPORARY TABLE}, including {@code CREATE OR REPLACE
   * TEMPORARY TABLE} and {@code CREATE TEMP TABLE}, or one that runs others, which may: a {@code
   * CALL}, an {@code EXECUTE}, or a compound statement of MariaDB's outside a stored program.
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

  // The select list's shape; null where it is no such statement.
  private static Shape singleTable(List<Tokens.Token> tokens) {
    int n = tokens.size();
    if (n == 0 || !tokens.getFirst().is("SELECT") || !balanced(tokens)) {
      return null;
    }
    for (int i = 0; i < n; i++) {
      if (forbidden(tokens, i) || tokens.get(i).is(";") && i != n - 1) {
        return null;
      }
    }
    int from = from(tokens);
    if (from < 0 || !singleTableFrom(tokens, from + 1)) {
      return null;
    }
    int item = 0;
    int brackets = 0;
    var subqueries = new HashSet<Integer>();
    var list = tokens.subList(0, from);
    for (int i = 0; i < from; i++) {
      var token = list.get(i);
      if (token.is("[") || token.is("]")) {
        brackets += token.is("[") ? 1 : -1;
      } else if (token.depth() > 0
          && (token.is("SELECT") || token.is("TABLE") && list.get(i - 1).is("("))) {
        subqueries.add(item);
      } else if (token.depth() == 0 && brackets == 0 && token.is(",")) {
        item++;
      }
    }
    var code = tokens.stream().map(Tokens.Token::text).toList();
    return new Shape(code, new SelectList(item + 1, Set.copyOf(subqueries)));
  }

  // Whether the parentheses pair up, as the depths of the tokens cannot tell where a reading leaves
  // an opening one out, as in an executable comment the server skips.
  private static boolean balanced(List<Tokens.Token> tokens) {
    int depth = 0;
    for (var token : tokens) {
      depth += token.is("(") ? 1 : token.is(")") ? -1 : 0;
      if (depth < 0) {
        return false;
      }
    }
    return depth == 0;
  }

  // The index of the FROM at the top level that ends the select list, or -1.
  private static int from(List<Tokens.Token> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).depth() == 0 && tokens.get(i).is("FROM") && !distinct(tokens, i)) {
        return i;
      }
    }
    return -1;
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
    int k = afterTable(tokens, start);
    if (k < 0) {
      return false;
    }
    if (k == n || (k == n - 1 && tokens.get(k).is(";"))) {
      return true;
    }
    // An alias spelled as a clause, as MySQL's offset, takes a join, an index hint or a comma
    // after it for one.
    if (!top(tokens, k)
        || !afterFrom(tokens.get(k))
        || k + 1 < n && tokens.get(k + 1).is(",")) {
      return false;
    }
    for (int i = start; i < n; i++) {
      var token = tokens.get(i);
      boolean hint = i >= k
          && (token.is("USE") || token.is("FORCE") || token.is("IGNORE"))
          && i + 1 < n
          && (tokens.get(i + 1).is("INDEX") || tokens.get(i + 1).is("KEY"));
      if (top(tokens, i) && (token.is("JOIN") || token.is("STRAIGHT_JOIN") || hint)) {
        return false;
      }
    }
    return true;
  }

  // The index after the table's name and alias from the index on; -1 where no name begins there.
  private static int afterTable(List<Tokens.Token> tokens, int start) {
    int n = tokens.size();
    if (start >= n || !top(tokens, start) || !tokens.get(start).name()) {
      return -1;
    }
    int k = start + 1;
    while (k + 1 < n
        && top(tokens, k)
        && tokens.get(k).is(".")
        && tokens.get(k + 1).name()) {
      k += 2;
    }
    if (k < n && top(tokens, k) && tokens.get(k).is("AS")) {
      return k + 1 < n && top(tokens, k + 1) && tokens.get(k + 1).name() ? k + 2 : -1;
    }
    return k < n && top(tokens, k) && bareAlias(tokens.get(k)) ? k + 1 : k;
  }

  private static boolean top(List<Tokens.Token> tokens, int k) {
    return tokens.get(k).depth() == 0;
  }

  // A word that is no clause after FROM; a keyword taken for an alias, as LEFT, leaves a word that
  // no clause allows.
  private static boolean bareAlias(Tokens.Token token) {
    return token.name() && !afterFrom(token);
  }

  private static boolean afterFrom(Tokens.Token token) {
    return !token.ident() && AFTER_FROM.contains(Tokens.keyword(token.text()));
  }

  private static boolean createsTemporaryTable(List<Tokens.Token> tokens) {
    if (!tokens.isEmpty() && runsOthers(tokens)) {
      return true;
    }
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).is("CREATE") && temporaryTable(tokens, i + 1)) {
        return true;
      }
    }
    return false;
  }

  // Whether the statement runs others: a CALL, an EXECUTE, or one of MariaDB's compound statements
  // outside a stored program, as BEGIN NOT ATOMIC, IF or WHILE, also after a label.
  private static boolean runsOthers(List<Tokens.Token> tokens) {
    var first = tokens.getFirst();
    var second = tokens.size() > 1 ? tokens.get(1) : first;
    return !first.ident() && RUNS_OTHERS.contains(Tokens.keyword(first.text()))
        || first.is("BEGIN") && second.is("NOT")
        || second.is(":");
  }

  // Whether [OR REPLACE] [LOCAL | GLOBAL] TEMPORARY | TEMP, then TABLE, begin at the index.
  private static boolean temporaryTable(List<Tokens.Token> tokens, int from) {
    int n = tokens.size();
    int k = from;
    if (k + 1 < n && tokens.get(k).is("OR") && tokens.get(k + 1).is("REPLACE")) {
      k += 2;
    }
    if (k < n && (tokens.get(k).is("LOCAL") || tokens.get(k).is("GLOBAL"))) {
      k++;
    }
    return k + 1 < n
        && (tokens.get(k).is("TEMPORARY") || tokens.get(k).is("TEMP"))
        && tokens.get(k + 1).is("TABLE");
  }
}

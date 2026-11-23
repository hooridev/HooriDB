package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Set;

/**
 * Whether a SQL statement is a plain read: a {@code SELECT}, {@code SHOW}, {@code VALUES}, {@code
 * TABLE} or a {@code WITH} whose common table expressions and main statement are plain reads, with
 * no {@code INTO} and no second statement, by every way the server may read its text ({@link
 * Tokens#readings}). Only ever conservative: a statement taken for a change that is none costs a
 * needless caution.
 */
public final class Reads {

  // First words of statements that only read, the same on every provider; anything else may change
  // data or the transaction, by itself, inside or with an implicit commit, and is treated as such.
  // SELECT reads, but for what a function it calls does, which no word tells, and INTO, below.
  // SHOW reads settings and the catalog, on PostgreSQL, MariaDB and MySQL alike. VALUES and TABLE
  // are a SELECT's own forms. EXPLAIN is not on it: its options, ANALYZE however spelled, run what
  // it explains; nor DESCRIBE and DESC, the same statement on MariaDB and MySQL.
  private static final Set<String> TOP = Set.of("SELECT", "SHOW", "VALUES", "TABLE");

  // Queries that may stand in a WITH's body, after a WITH, or in parentheses.
  private static final Set<String> QUERY = Set.of("SELECT", "VALUES", "TABLE");

  private Reads() {}

  /** Whether the statement is a plain read on the provider. */
  public static boolean plain(String sql, Provider provider) {
    if (provider.keepsDocuments()) {
      return false;
    }
    for (var reading : Tokens.readings(provider)) {
      if (!plain(Tokens.of(sql, provider, reading))) {
        return false;
      }
    }
    return true;
  }

  private static boolean plain(List<Tokens.Token> tokens) {
    int n = tokens.size();
    if (n == 0) {
      return false;
    }
    // A trailing semicolon ends the statement; any earlier one begins another statement.
    if (tokens.getLast().is(";")) {
      n--;
    }
    if (n == 0) {
      return false;
    }
    for (int k = 0; k < n; k++) {
      var token = tokens.get(k);
      // SELECT … INTO makes a table on PostgreSQL and sets variables or writes a file on MariaDB
      // and MySQL. A keyword cannot be quoted, so no spelling hides it; one after a period is
      // taken for it too, as in 1. INTO t, a needless caution for a column t.into.
      if (Tokens.keyword(token.text()).equals("INTO") || token.is(";")) {
        return false;
      }
    }
    return query(tokens, 0, n, true);
  }

  // Whether the span [from, to) is a plain read; top allows SHOW as well as queries.
  private static boolean query(List<Tokens.Token> tokens, int from, int to, boolean top) {
    if (from >= to) {
      return false;
    }
    var first = tokens.get(from);
    if (first.is("(")) {
      int close = match(tokens, from, to);
      return close >= 0 && query(tokens, from + 1, close, false);
    }
    if (first.is("WITH")) {
      return with(tokens, from, to);
    }
    if (first.ident()) {
      return false;
    }
    var word = Tokens.keyword(first.text());
    return top ? TOP.contains(word) : QUERY.contains(word);
  }

  // Whether a WITH at from has only plain-read CTE bodies and leads into a plain read.
  private static boolean with(List<Tokens.Token> tokens, int from, int to) {
    int base = tokens.get(from).depth();
    int k = from + 1;
    if (k < to && tokens.get(k).is("RECURSIVE")) {
      k++;
    }
    while (true) {
      k = cte(tokens, k, to, base);
      if (k < 0 || k >= to) {
        return false;
      }
      if (!tokens.get(k).is(",")) {
        return query(tokens, k, to, false);
      }
      k++;
    }
  }

  // Past one common table expression from its name, if its body is a plain read: the index after
  // its closing parenthesis, or -1.
  private static int cte(List<Tokens.Token> tokens, int from, int to, int base) {
    // Past the CTE's name and optional column list to its AS.
    int as = atDepth(tokens, from, to, base, "AS");
    if (as <= from) {
      return -1;
    }
    int k = as + 1;
    while (k < to && (tokens.get(k).is("NOT") || tokens.get(k).is("MATERIALIZED"))) {
      k++;
    }
    if (k >= to || !tokens.get(k).is("(")) {
      return -1;
    }
    int close = match(tokens, k, to);
    return close >= 0 && query(tokens, k + 1, close, false) ? close + 1 : -1;
  }

  // The matching closing parenthesis at the same depth as the opening one at open, or -1.
  private static int match(List<Tokens.Token> tokens, int open, int to) {
    return atDepth(tokens, open + 1, to, tokens.get(open).depth(), ")");
  }

  // The first token in [from, to) at depth that is word, or -1.
  private static int atDepth(List<Tokens.Token> tokens, int from, int to, int depth, String word) {
    for (int k = from; k < to; k++) {
      var token = tokens.get(k);
      if (token.depth() == depth && token.is(word)) {
        return k;
      }
    }
    return -1;
  }
}

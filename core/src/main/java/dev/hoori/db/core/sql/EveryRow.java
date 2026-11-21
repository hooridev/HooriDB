package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * An {@code UPDATE} or {@code DELETE} that may change every row of its table: one that is bounded
 * only where every way the server may read its text finds a bare {@code WHERE} at its top level
 * before any {@code RETURNING}, and on MariaDB and MySQL also a {@code LIMIT}. One in a subquery
 * does not count, nor one in a comment, a string or a quoted name, nor a word after a period, as
 * {@code t.where}, nor a MariaDB or MySQL user variable of that name. The ways it may be read are
 * with a backslash in a string an escape and not, and MariaDB's and MySQL's executable comments
 * read as code and as comments. Read from the statement's text alone, by the lexical rules of
 * {@link Statements}; whatever it cannot read for certain, as a WITH of another shape, asks. A
 * command of MongoDB that updates many or deletes all with an empty filter is one too, of its
 * collection, as {@link dev.hoori.db.core.document.Commands#every} reads it.
 *
 * @param verb {@code UPDATE} or {@code DELETE}
 * @param table the first table it names, as written, such as {@code shop."order lines"}, or the
 *     collection; empty where none can be read
 * @param certain whether every way of reading it finds no such bound, so that it has none; else
 *     the text could not tell
 */
public record EveryRow(String verb, String table, boolean certain) {

  // What may stand before the table: MariaDB's and MySQL's modifiers, PostgreSQL's ONLY, FROM.
  private static final Set<String> BEFORE_TABLE =
      Set.of("LOW_PRIORITY", "QUICK", "IGNORE", "ONLY", "FROM");

  // The statements a WITH may lead into.
  private static final Set<String> VERBS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "VALUES", "TABLE");

  /** The text's one statement as such a change, or empty where it is none. */
  public static Optional<EveryRow> of(String sql, Provider provider) {
    EveryRow found = null;
    boolean certain = true;
    for (var reading : Tokens.readings(provider)) {
      var tokens = Tokens.of(sql, provider, reading);
      int verb = start(tokens);
      boolean read = verb >= 0;
      if (!read) {
        // Of a shape it does not read: an UPDATE or DELETE at its top level may be the statement.
        verb = topLevel(tokens, 1, EveryRow::changes);
      }
      if (verb >= tokens.size() || !changes(tokens.get(verb))) {
        continue; // no such change, as read so
      }
      if (read && bounded(tokens, verb, provider)) {
        certain = false;
      } else {
        certain &= read;
        if (found == null) {
          found =
              new EveryRow(Tokens.keyword(tokens.get(verb).text()), table(sql, tokens, verb), true);
        }
      }
    }
    return found == null
        ? Optional.empty()
        : Optional.of(new EveryRow(found.verb(), found.table(), certain));
  }

  // The index of the statement's verb, past a WITH; -1 where the WITH is of a shape it does not
  // read.
  private static int start(List<Tokens.Token> tokens) {
    return !tokens.isEmpty() && tokens.getFirst().is("WITH") ? afterWith(tokens) : 0;
  }

  // The index of the first token at the top level from the index on that is such, or the size.
  private static int topLevel(List<Tokens.Token> tokens, int from, Predicate<Tokens.Token> such) {
    int k = from;
    while (k < tokens.size() && !(tokens.get(k).depth() == 0 && such.test(tokens.get(k)))) {
      k++;
    }
    return k;
  }

  private static boolean changes(Tokens.Token token) {
    return token.is("UPDATE") || token.is("DELETE");
  }

  // Whether a bare WHERE, or MariaDB's and MySQL's LIMIT, stands at its top level, before any
  // RETURNING, whose names may be spelled so.
  private static boolean bounded(List<Tokens.Token> tokens, int verb, Provider provider) {
    for (int k = verb + 1; k < tokens.size(); k++) {
      var token = tokens.get(k);
      if (token.depth() != 0) {
        continue;
      }
      if (token.is("RETURNING")) {
        return false;
      }
      if (token.is("WHERE")
          || provider.dialect().syntax().limitBoundsChanges() && token.is("LIMIT")) {
        return true;
      }
    }
    return false;
  }

  private static String table(String sql, List<Tokens.Token> tokens, int verb) {
    int at = verb + 1;
    while (at < tokens.size()
        && !tokens.get(at).ident()
        && BEFORE_TABLE.contains(Tokens.keyword(tokens.get(at).text()))) {
      at++;
    }
    var table = "";
    if (at < tokens.size() && tokens.get(at).name()) {
      int end = at;
      while (end + 2 < tokens.size()
          && tokens.get(end + 1).text().equals(".")
          && !tokens.get(end + 1).ident()
          && tokens.get(end + 2).name()) {
        end += 2;
      }
      table = sql.substring(tokens.get(at).start(), tokens.get(end).end());
    }
    return table;
  }

  /**
   * The index of the statement a WITH leads into, past its common table expressions, each a name,
   * its columns, AS, [NOT] MATERIALIZED and the body in parentheses; -1 where they are of another
   * shape, as with PostgreSQL's SEARCH or CYCLE.
   */
  private static int afterWith(List<Tokens.Token> tokens) {
    int n = tokens.size();
    int k = n > 1 && tokens.get(1).is("RECURSIVE") ? 2 : 1;
    while (true) {
      k = pastBody(tokens, k);
      if (k < 0 || k >= n) {
        return -1;
      }
      if (verb(tokens.get(k))) {
        return k;
      }
      if (!tokens.get(k).is(",")) {
        return -1;
      }
      k++;
    }
  }

  // The index after the body of the common table expression from the index on; -1 where no body
  // in parentheses follows its AS.
  private static int pastBody(List<Tokens.Token> tokens, int from) {
    int n = tokens.size();
    int k = topLevel(tokens, from, token -> token.is("AS")) + 1;
    while (k < n && (tokens.get(k).is("NOT") || tokens.get(k).is("MATERIALIZED"))) {
      k++;
    }
    if (k >= n || !tokens.get(k).is("(")) {
      return -1;
    }
    return topLevel(tokens, k + 1, token -> token.is(")")) + 1;
  }

  private static boolean verb(Tokens.Token token) {
    return !token.ident() && VERBS.contains(Tokens.keyword(token.text()));
  }
}

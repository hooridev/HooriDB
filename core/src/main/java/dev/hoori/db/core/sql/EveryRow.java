package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * An {@code UPDATE} or {@code DELETE} without a {@code WHERE} at its top level, which changes every
 * row of its table: one in a subquery does not count, nor one in a comment, a string or a quoted
 * name, nor a word after a period, as {@code t.where}, nor a MariaDB or MySQL user variable of
 * that name. On MariaDB and MySQL a {@code LIMIT} at its top level bounds it as a {@code WHERE} does;
 * one in an executable comment does not, unless the statement is in the same one, while an UPDATE
 * or DELETE in one counts. Read from the
 * statement's text alone, by the lexical rules of {@link Statements}; where it cannot be read for
 * certain, it is taken for such a change.
 *
 * @param verb {@code UPDATE} or {@code DELETE}
 * @param table the first table it names, as written, such as {@code shop."order lines"}; empty
 *     where none can be read
 */
public record EveryRow(String verb, String table) {

  // What may stand before the table: MariaDB's and MySQL's modifiers, PostgreSQL's ONLY, FROM.
  private static final Set<String> BEFORE_TABLE =
      Set.of("LOW_PRIORITY", "QUICK", "IGNORE", "ONLY", "FROM");

  // The statements a WITH may lead into.
  private static final Set<String> VERBS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "VALUES", "TABLE");

  /** The text's one statement as such a change, or empty where it is none. */
  public static Optional<EveryRow> of(String sql, Provider provider) {
    var tokens = Tokens.of(sql, provider);
    int verb = 0;
    if (!tokens.isEmpty() && tokens.getFirst().is("WITH")) {
      verb = afterWith(tokens);
      if (verb < 0) {
        // Not read with certainty: an UPDATE or DELETE at the top level is taken for the statement.
        verb = 1;
        while (verb < tokens.size()
            && !(tokens.get(verb).depth() == 0
                && (tokens.get(verb).is("UPDATE") || tokens.get(verb).is("DELETE")))) {
          verb++;
        }
      }
    }
    if (verb >= tokens.size()
        || !tokens.get(verb).is("UPDATE") && !tokens.get(verb).is("DELETE")) {
      return Optional.empty();
    }
    // One in an executable comment but its own does not bound it: an older server skips it.
    int comment = tokens.get(verb).comment();
    for (int k = verb + 1; k < tokens.size(); k++) {
      var token = tokens.get(k);
      if (token.depth() == 0
          && (!token.commented() || token.comment() == comment)
          && (token.is("WHERE") || provider != Provider.POSTGRESQL && token.is("LIMIT"))) {
        return Optional.empty();
      }
    }
    int at = verb + 1;
    while (at < tokens.size()
        && !tokens.get(at).ident()
        && BEFORE_TABLE.contains(tokens.get(at).text().toUpperCase(Locale.ROOT))) {
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
    return Optional.of(new EveryRow(tokens.get(verb).text().toUpperCase(Locale.ROOT), table));
  }

  /**
   * The index of the statement a WITH leads into, past its common table expressions: each a name,
   * its columns, AS, the body in parentheses, and PostgreSQL's SEARCH or CYCLE; -1 where they are
   * not of that shape.
   */
  private static int afterWith(List<Tokens.Token> tokens) {
    int n = tokens.size();
    int k = n > 1 && tokens.get(1).is("RECURSIVE") ? 2 : 1;
    while (true) {
      while (k < n && !(tokens.get(k).depth() == 0 && tokens.get(k).is("AS"))) {
        k++;
      }
      k++;
      while (k < n && (tokens.get(k).is("NOT") || tokens.get(k).is("MATERIALIZED"))) {
        k++;
      }
      if (k >= n || !tokens.get(k).is("(")) {
        return -1;
      }
      k++;
      while (k < n && !(tokens.get(k).depth() == 0 && tokens.get(k).is(")"))) {
        k++;
      }
      k++;
      while (k < n
          && !(tokens.get(k).depth() == 0 && (tokens.get(k).is(",") || verb(tokens.get(k))))) {
        k++;
      }
      if (k >= n) {
        return -1;
      }
      if (verb(tokens.get(k))) {
        return k;
      }
      k++;
    }
  }

  private static boolean verb(Tokens.Token token) {
    return !token.ident() && VERBS.contains(token.text().toUpperCase(Locale.ROOT));
  }
}

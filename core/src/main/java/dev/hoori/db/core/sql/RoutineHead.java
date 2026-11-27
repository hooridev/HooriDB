package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.Optional;

/**
 * The head of a statement that creates a function or a procedure, as its code reads: {@code
 * CREATE [OR REPLACE] [DEFINER = …] [AGGREGATE] FUNCTION | PROCEDURE [IF NOT EXISTS]
 * [schema.]name}, and where its definer clause and its name are written, so that the routine it
 * creates is known before it runs, and the statement can be sent under another name or without
 * its definer.
 *
 * @param procedure whether it creates a procedure, else a function
 * @param qualifier the schema's or database's name before the routine's, unquoted; empty where
 *     there is none
 * @param name the routine's name, unquoted
 * @param nameAt where the name is written, quoted or not
 * @param definer where the {@code DEFINER} clause is written, from its word up to the word after
 *     it; empty where there is none
 */
public record RoutineHead(
    boolean procedure, String qualifier, String name, Range nameAt, Optional<Range> definer) {

  /** A range of the statement's text, in chars from {@code start} up to {@code end}. */
  public record Range(int start, int end) {}

  /** The head of the statement, by the provider's rules; empty where it creates no routine. */
  public static Optional<RoutineHead> of(String sql, Provider provider) {
    var tokens = Tokens.of(sql, provider, Tokens.readings(provider).getFirst());
    int k = 0;
    if (!is(tokens, k++, "CREATE")) {
      return Optional.empty();
    }
    if (is(tokens, k, "OR") && is(tokens, k + 1, "REPLACE")) {
      k += 2;
    }
    Range definer = null;
    if (is(tokens, k, "DEFINER")) {
      int start = tokens.get(k).start();
      k = pastDefiner(tokens, k);
      if (k == tokens.size()) {
        return Optional.empty();
      }
      definer = new Range(start, tokens.get(k).start());
    }
    if (is(tokens, k, "AGGREGATE")) {
      k++;
    }
    if (k >= tokens.size() || !kind(tokens.get(k))) {
      return Optional.empty();
    }
    boolean procedure = is(tokens, k++, "PROCEDURE");
    if (is(tokens, k, "IF") && is(tokens, k + 1, "NOT") && is(tokens, k + 2, "EXISTS")) {
      k += 3;
    }
    if (k >= tokens.size() || !tokens.get(k).name()) {
      return Optional.empty();
    }
    var first = tokens.get(k++);
    var qualifier = "";
    var named = first;
    if (is(tokens, k, ".") && k + 1 < tokens.size() && tokens.get(k + 1).name()) {
      qualifier = unquoted(first.text(), provider);
      named = tokens.get(k + 1);
    }
    return Optional.of(new RoutineHead(
        procedure,
        qualifier,
        unquoted(named.text(), provider),
        new Range(named.start(), named.end()),
        Optional.ofNullable(definer)));
  }

  /** The statement with its routine's name written as given in place of its own. */
  public String renamed(String sql, String written) {
    return sql.substring(0, nameAt.start()) + written + sql.substring(nameAt.end());
  }

  /** The statement without its definer clause, which makes the user who runs it the definer. */
  public Optional<String> withoutDefiner(String sql) {
    return definer.map(d -> sql.substring(0, d.start()) + sql.substring(d.end()));
  }

  // Where the DEFINER clause that begins at the token ends: at the routine's kind or AGGREGATE,
  // else at the end.
  private static int pastDefiner(java.util.List<Tokens.Token> tokens, int from) {
    int k = from;
    while (k < tokens.size() && !kind(tokens.get(k)) && !is(tokens, k, "AGGREGATE")) {
      k++;
    }
    return k;
  }

  private static boolean is(java.util.List<Tokens.Token> tokens, int k, String word) {
    return k < tokens.size() && tokens.get(k).is(word);
  }

  private static boolean kind(Tokens.Token token) {
    return token.is("FUNCTION") || token.is("PROCEDURE");
  }

  // A name without the quotes it is written in, a quote doubled within it once.
  private static String unquoted(String text, Provider provider) {
    var quote = provider.dialect().syntax().identifierQuote();
    if (text.length() > 1 && text.charAt(0) == quote && text.charAt(text.length() - 1) == quote) {
      var q = String.valueOf(quote);
      return text.substring(1, text.length() - 1).replace(q + q, q);
    }
    return text;
  }
}

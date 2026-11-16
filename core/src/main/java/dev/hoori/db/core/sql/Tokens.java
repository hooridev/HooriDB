package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;

/** The code of a statement as words, quoted names and other characters, by {@link Statements}. */
final class Tokens {

  private Tokens() {}

  /**
   * A word, a name or one other character of code, at its depth of parentheses.
   *
   * @param ident a name however it is spelled: quoted, or a word after a period, as {@code t.where}
   * @param comment where the executable comment of MariaDB or MySQL it is in begins, which the
   *     server runs unless it is older than the version the comment names; -1 for none
   */
  record Token(String text, int start, int end, int depth, boolean ident, int comment) {

    boolean commented() {
      return comment >= 0;
    }

    boolean is(String word) {
      return !ident && text.equalsIgnoreCase(word);
    }

    boolean name() {
      return ident || Character.isLetterOrDigit(text.charAt(0)) || text.charAt(0) == '_';
    }
  }

  // The code of the text, without its comments and strings: words joined from their characters,
  // a MariaDB or MySQL variable such as @where one word, and executable comments as their code.
  static List<Token> of(String sql, Provider provider) {
    var tokens = new ArrayList<Token>();
    add(tokens, sql, 0, sql.length(), provider, 0, -1);
    return tokens;
  }

  private static void add(
      List<Token> tokens, String sql, int from, int to, Provider provider, int depth, int comment) {
    var pieces = Statements.pieces(sql.substring(from, to), provider);
    for (int k = 0; k < pieces.size(); k++) {
      var piece = pieces.get(k);
      int start = from + piece.start();
      switch (piece.kind()) {
        case STRING -> {}
        case COMMENT -> {
          int body = executable(sql, start, provider);
          if (body > 0) {
            int end = from + piece.end() - (piece.open() ? 0 : 2);
            while (body < end && Character.isDigit(sql.charAt(body))) {
              body++; // the version from which the server runs it
            }
            add(tokens, sql, body, Math.max(body, end), provider, depth, start);
          }
        }
        case QUOTED ->
          tokens.add(new Token(
              sql.substring(start, from + piece.end()),
              start,
              from + piece.end(),
              depth,
              true,
              comment));
        case CODE -> {
          char c = sql.charAt(start);
          if (c == ')') {
            depth = Math.max(0, depth - 1);
          }
          int end = from + piece.end();
          boolean word = word(c, provider);
          if (word) {
            while (k + 1 < pieces.size()
                && pieces.get(k + 1).kind() == Statements.Piece.Kind.CODE
                && from + pieces.get(k + 1).start() == end
                && word(sql.charAt(end), provider)) {
              end = from + pieces.get(++k).end();
            }
          }
          var before = tokens.isEmpty() ? null : tokens.getLast();
          boolean afterPeriod = word && before != null && !before.ident() && before.is(".");
          tokens.add(new Token(sql.substring(start, end), start, end, depth, afterPeriod, comment));
          if (c == '(') {
            depth++;
          }
        }
      }
    }
  }

  // Where the code of an executable comment at the offset begins, or 0 where it is none.
  private static int executable(String sql, int at, Provider provider) {
    if (provider == Provider.POSTGRESQL) {
      return 0;
    }
    if (sql.startsWith("/*!", at)) {
      return at + 3;
    }
    return provider == Provider.MARIADB && sql.startsWith("/*M!", at) ? at + 4 : 0;
  }

  private static boolean word(char c, Provider provider) {
    return Character.isLetterOrDigit(c)
        || c == '_'
        || c == '$'
        || c == '@' && provider != Provider.POSTGRESQL;
  }
}

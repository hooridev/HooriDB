package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;

/** The code of a statement as words, quoted names and other characters, by {@link Statements}. */
final class Tokens {

  private Tokens() {}

  // A word, a quoted name or one other character of code, at its depth of parentheses.
  record Token(String text, int start, int end, int depth, boolean quoted) {

    boolean is(String word) {
      return !quoted && text.equalsIgnoreCase(word);
    }

    boolean name() {
      return quoted || Character.isLetterOrDigit(text.charAt(0)) || text.charAt(0) == '_';
    }
  }

  // The code of the text, without its comments and strings: words joined from their characters.
  static List<Token> of(String sql, Provider provider) {
    var pieces = Statements.pieces(sql, provider);
    var tokens = new ArrayList<Token>();
    int depth = 0;
    for (int k = 0; k < pieces.size(); k++) {
      var piece = pieces.get(k);
      switch (piece.kind()) {
        case COMMENT, STRING -> {}
        case QUOTED ->
          tokens.add(new Token(
              sql.substring(piece.start(), piece.end()), piece.start(), piece.end(), depth, true));
        case CODE -> {
          char c = sql.charAt(piece.start());
          if (c == ')') {
            depth = Math.max(0, depth - 1);
          }
          int end = piece.end();
          if (word(c)) {
            while (k + 1 < pieces.size()
                && pieces.get(k + 1).kind() == Statements.Piece.Kind.CODE
                && pieces.get(k + 1).start() == end
                && word(sql.charAt(end))) {
              end = pieces.get(++k).end();
            }
          }
          tokens.add(
              new Token(sql.substring(piece.start(), end), piece.start(), end, depth, false));
          if (c == '(') {
            depth++;
          }
        }
      }
    }
    return tokens;
  }

  private static boolean word(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}

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
   */
  record Token(String text, int start, int end, int depth, boolean ident) {

    boolean is(String word) {
      return !ident && keyword(text).equals(word);
    }

    boolean name() {
      char c = text.charAt(0);
      return ident || c >= 0x80 || c == '_' || ascii(c) && Character.isLetterOrDigit(c);
    }
  }

  /**
   * One way a server may read the text, by settings the text does not show.
   *
   * @param escapes whether a backslash escapes in a plain string
   * @param code whether MariaDB's and MySQL's executable comments are code, as a server new enough
   *     for the version they name reads them, or comments, as an older one does
   */
  record Reading(boolean escapes, boolean code) {}

  /** Every way it may be read, the provider's default first. */
  static List<Reading> readings(Provider provider) {
    boolean mysql = provider != Provider.POSTGRESQL;
    var all = new ArrayList<Reading>();
    for (boolean code : mysql ? new boolean[] {true, false} : new boolean[] {false}) {
      for (boolean escapes : new boolean[] {mysql, !mysql}) {
        all.add(new Reading(escapes, code));
      }
    }
    return all;
  }

  // The code of the text, as the reading reads it, without its comments and strings: words joined
  // from their characters, a MariaDB or MySQL variable such as @where one word.
  static List<Token> of(String sql, Provider provider, Reading reading) {
    var tokens = new ArrayList<Token>();
    add(tokens, sql, 0, sql.length(), provider, reading, 0);
    return tokens;
  }

  // Adds the tokens of the text between the offsets, from the depth given; the depth after them.
  private static int add(
      List<Token> tokens,
      String sql,
      int from,
      int to,
      Provider provider,
      Reading reading,
      int depth) {
    var pieces = Statements.pieces(sql.substring(from, to), provider, reading.escapes());
    for (int k = 0; k < pieces.size(); k++) {
      var piece = pieces.get(k);
      int start = from + piece.start();
      switch (piece.kind()) {
        case STRING -> {}
        case COMMENT -> {
          int body = reading.code() ? executable(sql, start, provider) : 0;
          if (body > 0) {
            int end = from + piece.end() - (piece.open() ? 0 : 2);
            while (body < end && sql.charAt(body) >= '0' && sql.charAt(body) <= '9') {
              body++; // the version from which the server runs it
            }
            depth = add(tokens, sql, body, Math.max(body, end), provider, reading, depth);
          }
        }
        case QUOTED ->
          tokens.add(new Token(
              sql.substring(start, from + piece.end()), start, from + piece.end(), depth, true));
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
          boolean afterPeriod = word && before != null && before.is(".");
          tokens.add(new Token(sql.substring(start, end), start, end, depth, afterPeriod));
          if (c == '(') {
            depth++;
          }
        }
      }
    }
    return depth;
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

  // Of an unquoted identifier: ASCII letters, digits, _ and $, and every character beyond ASCII,
  // as both servers take them (combining marks among them); on MariaDB and MySQL a variable's @.
  private static boolean word(char c, Provider provider) {
    return ascii(c) && Character.isLetterOrDigit(c)
        || c == '_'
        || c == '$'
        || c >= 0x80
        || c == '@' && provider != Provider.POSTGRESQL;
  }

  private static boolean ascii(char c) {
    return c < 0x80;
  }

  /**
   * The word in upper case as a keyword, by ASCII's letters alone, as the servers fold keywords; a
   * word with any other character, such as {@code LİMİT} or {@code lımıt}, is a name, and is given
   * back unchanged, so that it equals no keyword.
   */
  static String keyword(String word) {
    var upper = new StringBuilder(word.length());
    for (int k = 0; k < word.length(); k++) {
      char c = word.charAt(k);
      if (!ascii(c)) {
        return word;
      }
      upper.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
    }
    return upper.toString();
  }
}

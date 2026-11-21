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
    var syntax = provider.dialect().syntax();
    boolean executable = !syntax.executableComments().isEmpty();
    boolean backslash = syntax.backslashEscapes();
    var all = new ArrayList<Reading>();
    for (boolean code : executable ? new boolean[] {true, false} : new boolean[] {false}) {
      for (boolean escapes : new boolean[] {backslash, !backslash}) {
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
    int k = 0;
    while (k < pieces.size()) {
      var piece = pieces.get(k);
      int start = from + piece.start();
      int next = k + 1;
      switch (piece.kind()) {
        case STRING -> {
          // left out
        }
        case COMMENT -> depth = comment(tokens, sql, from, piece, provider, reading, depth);
        case QUOTED ->
          tokens.add(new Token(
              sql.substring(start, from + piece.end()), start, from + piece.end(), depth, true));
        case CODE -> {
          char c = sql.charAt(start);
          if (c == ')') {
            depth = Math.max(0, depth - 1);
          }
          next = code(tokens, sql, from, pieces, k, provider, depth);
          if (c == '(') {
            depth++;
          }
        }
      }
      k = next;
    }
    return depth;
  }

  // Adds the code of the comment where it is executable; the depth after it.
  private static int comment(
      List<Token> tokens,
      String sql,
      int from,
      Statements.Piece piece,
      Provider provider,
      Reading reading,
      int depth) {
    int body = reading.code() ? executable(sql, from + piece.start(), provider) : 0;
    if (body == 0) {
      return depth;
    }
    int close = from + piece.end() - (piece.open() ? 0 : 2);
    while (body < close && sql.charAt(body) >= '0' && sql.charAt(body) <= '9') {
      body++; // the version from which the server runs it
    }
    return add(tokens, sql, body, Math.max(body, close), provider, reading, depth);
  }

  // Adds the token of code that begins at piece k, a word joined from its characters; the index of
  // the piece after it.
  private static int code(
      List<Token> tokens,
      String sql,
      int from,
      List<Statements.Piece> pieces,
      int k,
      Provider provider,
      int depth) {
    int start = from + pieces.get(k).start();
    boolean word = word(sql.charAt(start), provider);
    int last = k;
    while (word
        && last + 1 < pieces.size()
        && pieces.get(last + 1).kind() == Statements.Piece.Kind.CODE
        && pieces.get(last + 1).start() == pieces.get(last).end()
        && word(sql.charAt(from + pieces.get(last).end()), provider)) {
      last++;
    }
    int end = from + pieces.get(last).end();
    boolean afterPeriod = word && !tokens.isEmpty() && tokens.getLast().is(".");
    tokens.add(new Token(sql.substring(start, end), start, end, depth, afterPeriod));
    return last + 1;
  }

  // Where the code of an executable comment at the offset begins, or 0 where it is none.
  private static int executable(String sql, int at, Provider provider) {
    for (var opening : provider.dialect().syntax().executableComments()) {
      if (sql.startsWith(opening, at)) {
        return at + opening.length();
      }
    }
    return 0;
  }

  // Of an unquoted identifier: ASCII letters, digits, _ and $, and every character beyond ASCII,
  // as both servers take them (combining marks among them); on MariaDB and MySQL a variable's @.
  private static boolean word(char c, Provider provider) {
    return ascii(c) && Character.isLetterOrDigit(c)
        || c == '_'
        || c == '$'
        || c >= 0x80
        || c == '@' && provider.dialect().syntax().userVariables();
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

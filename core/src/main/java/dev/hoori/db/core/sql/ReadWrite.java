package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a statement may turn a read-only session back to read-write, so that a read-only
 * connection does not send it: one that holds what the dialect's {@link
 * dev.hoori.db.core.dialect.ConnectDialect#readWrite} names, in its words, quoted names and
 * variables or in a string, by every way the server may read its text, as {@link EveryRow} reads
 * it, and one with a {@code START TRANSACTION} after its first word. A {@code SHOW}, which only
 * reads a setting, is let through.
 */
// shortcut: a name put together at run time, as from two strings, or a setting changed by a
// function, procedure or trigger that already exists, is not seen. The server still refuses each
// write while the session stays read-only.
public final class ReadWrite {

  private static final Pattern SPACES = Pattern.compile("\\s+");

  private ReadWrite() {}

  /** Whether the statement may turn the session's read-only off. */
  public static boolean in(String sql, Provider provider) {
    if (Tokens.keyword(Statements.firstWord(sql, provider)).equals("SHOW")) {
      return false;
    }
    var phrases = provider.dialect().connecting().readWrite();
    for (var reading : Tokens.readings(provider)) {
      var words = new StringBuilder(" ");
      for (var token : Tokens.of(sql, provider, reading)) {
        words.append(bare(token.text())).append(' ');
      }
      var strings = new StringBuilder();
      for (var piece : Statements.pieces(sql, provider, reading.escapes())) {
        // The strings in an executable comment, which the server may run, go with its text.
        if (piece.kind() == Statements.Piece.Kind.STRING
            || piece.kind() == Statements.Piece.Kind.COMMENT && executable(sql, piece, provider)) {
          strings.append(sql, piece.start(), piece.end()).append('\n');
        }
      }
      // A START TRANSACTION inside another statement, as MySQL's CREATE TABLE … START TRANSACTION,
      // opens a transaction that is not read-only; elsewhere it is no statement at all.
      if (words.indexOf(" start transaction ", 1) > 0) {
        return true;
      }
      var text = SPACES.matcher(strings).replaceAll(" ").toLowerCase(Locale.ROOT);
      for (var phrase : phrases) {
        if (words.indexOf(" " + phrase + " ") >= 0 || text.contains(phrase)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean executable(String sql, Statements.Piece piece, Provider provider) {
    for (var opening : provider.dialect().syntax().executableComments()) {
      if (sql.startsWith(opening, piece.start())) {
        return true;
      }
    }
    return false;
  }

  // A word in lower case, without the quotes of a name or the @ of a variable.
  private static String bare(String token) {
    var word = token.toLowerCase(Locale.ROOT);
    while (word.startsWith("@")) {
      word = word.substring(1);
    }
    if (word.length() > 1 && "\"`[".indexOf(word.charAt(0)) >= 0) {
      // One name, which its spaces do not split into words such as START TRANSACTION; trimmed, as
      // MySQL finds a variable whose name has trailing spaces.
      word = SPACES.matcher(word.substring(1, word.length() - 1).strip()).replaceAll("_");
    }
    return word;
  }
}

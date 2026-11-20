package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.Set;

/**
 * A statement's text with the passwords it carries masked, before it goes where a statement's
 * text is kept, as the statement log. Which keywords a password follows is the dialect's {@link
 * dev.hoori.db.core.dialect.SyntaxDialect#passwordWords}: after the first of them, as in {@code
 * CREATE ROLE r PASSWORD 'secret'} or {@code ALTER USER u IDENTIFIED BY 'secret'}, every string is
 * masked, also one that is not the password, such as a user's name in {@code SET PASSWORD FOR
 * 'u'@'h' = '…'}; and so is every string that names a password itself, as a connection string's
 * {@code password=…} does.
 *
 * <p>A string is masked wherever the server could read one: by each way of reading backslashes, and
 * within comments, which MariaDB and MySQL may run.
 */
public final class Secrets {

  /** What a masked string becomes. */
  public static final String MASK = "'***'";

  private Secrets() {}

  /** The text with its passwords masked; the text itself where it carries none. */
  public static String masked(String sql, Provider provider) {
    if (provider.keepsDocuments()) {
      return sql;
    }
    var words = provider.dialect().syntax().passwordWords();
    if (words.isEmpty() || !mentions(sql, words)) {
      return sql;
    }
    var hidden = new boolean[sql.length()];
    boolean any = false;
    for (var reading : Tokens.readings(provider)) {
      int after = Integer.MAX_VALUE;
      for (var token : Tokens.of(sql, provider, reading)) {
        if (words.stream().anyMatch(token::is)) {
          after = token.start();
          break;
        }
      }
      for (var piece : Statements.pieces(sql, provider, reading.escapes())) {
        var text = sql.substring(piece.start(), piece.end());
        if (piece.kind() == Statements.Piece.Kind.STRING
            && (piece.start() >= after || namesPassword(text, words))) {
          any |= hide(hidden, piece.start(), piece.end());
        } else if (piece.kind() == Statements.Piece.Kind.COMMENT && piece.end() > after) {
          any |= hideQuoted(sql, hidden, Math.max(after, piece.start()), piece.end());
        }
      }
    }
    return any ? replaced(sql, hidden) : sql;
  }

  // Whether a word is in the text, in any case, wherever it stands.
  private static boolean mentions(String text, Set<String> words) {
    for (var word : words) {
      for (int i = 0; i + word.length() <= text.length(); i++) {
        if (text.regionMatches(true, i, word, 0, word.length())) {
          return true;
        }
      }
    }
    return false;
  }

  // Whether a string names a password and gives it, as password=secret in a connection string.
  private static boolean namesPassword(String text, Set<String> words) {
    for (var word : words) {
      for (int i = 0; i + word.length() <= text.length(); i++) {
        if (text.regionMatches(true, i, word, 0, word.length())) {
          int j = i + word.length();
          while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
          }
          if (j < text.length() && (text.charAt(j) == '=' || text.charAt(j) == ':')) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean hide(boolean[] hidden, int from, int to) {
    for (int i = from; i < to; i++) {
      hidden[i] = true;
    }
    return to > from;
  }

  // The quoted runs between the offsets, as a comment that the server may run holds them, each up
  // to its closing quote or the end.
  private static boolean hideQuoted(String sql, boolean[] hidden, int from, int to) {
    boolean any = false;
    for (int i = from; i < to; i++) {
      char quote = sql.charAt(i);
      if (quote != '\'' && quote != '"') {
        continue;
      }
      int end = i + 1;
      while (end < to) {
        char c = sql.charAt(end);
        if (c == '\\' && end + 1 < to) {
          end += 2;
        } else if (c == quote && end + 1 < to && sql.charAt(end + 1) == quote) {
          end += 2;
        } else if (c == quote) {
          end++;
          break;
        } else {
          end++;
        }
      }
      any |= hide(hidden, i, end);
      i = end - 1;
    }
    return any;
  }

  // The text with each run of hidden characters replaced by the mask.
  private static String replaced(String sql, boolean[] hidden) {
    var out = new StringBuilder(sql.length());
    for (int i = 0; i < sql.length(); i++) {
      if (!hidden[i]) {
        out.append(sql.charAt(i));
      } else if (i == 0 || !hidden[i - 1]) {
        out.append(MASK);
      }
    }
    return out.toString();
  }
}

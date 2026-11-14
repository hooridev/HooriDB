package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Where the statements of a text of SQL begin and end, by the lexical rules of a provider's server.
 *
 * <p>A statement ends at its terminator, which is not part of it, or at the end of the text. A
 * terminator does not count inside a string, a quoted identifier or a comment; one that is not
 * closed runs to the end of the text. For MariaDB and MySQL the client command {@code DELIMITER}
 * changes the terminator, as their command-line clients do: a line where a statement would begin
 * and whose first word is {@code DELIMITER}, in any case, is no statement, and its argument is the
 * terminator from then on.
 *
 * <p>The scan is linear in the length of the text and never throws.
 */
// shortcut: this scans characters and is no SQL parser, so a body that holds semicolons without
// being quoted, such as PostgreSQL's BEGIN ATOMIC ... END or a MariaDB/MySQL routine written
// without DELIMITER, is split at its inner semicolons; parse such bodies if users trip over it.
public final class Statements {

  // What the clients of MariaDB and MySQL keep of a delimiter; they cut a longer one off.
  private static final int MAX_DELIMITER = 15;

  private final String text;
  private final Provider provider;
  private final boolean postgres;
  private final List<Statement> statements = new ArrayList<>();
  // For each statement, the offset after its terminator, or its end if it has none.
  private final List<Integer> afters = new ArrayList<>();
  private String delimiter = ";";
  private int i;

  private Statements(String text, Provider provider) {
    this.text = text;
    this.provider = provider;
    this.postgres = provider == Provider.POSTGRESQL;
  }

  /** The statements of the text, in order. */
  public static List<Statement> split(String text, Provider provider) {
    return scan(text, provider).statements;
  }

  /**
   * The statement that runs for a caret at the given offset, as with Control+Enter:
   *
   * <ul>
   *   <li>A caret from a statement's start up to its end, both included, is in that statement.
   *   <li>A caret after a statement's end up to the end of its terminator, or after that with
   *       nothing but spaces and tabs before it on the same line, means that statement, which has
   *       just ended.
   *   <li>Anywhere else, such as on an empty line or in a comment between statements, it means the
   *       next statement, or the last one if none follows.
   *   <li>A text without statements has none.
   * </ul>
   *
   * A caret outside the text counts as at its nearest end.
   */
  public static Optional<Statement> at(String text, Provider provider, int caret) {
    return scan(text, provider).at(caret);
  }

  /**
   * The text scanned once, for a caret that moves while the text stays: {@link #at(int)} then
   * costs no scan.
   */
  public static Statements of(String text, Provider provider) {
    return scan(text, provider);
  }

  /** The statements of the text, in order. */
  public List<Statement> statements() {
    return Collections.unmodifiableList(statements);
  }

  /** The statement that runs for a caret at the offset, as {@link #at(String, Provider, int)}. */
  public Optional<Statement> at(int caret) {
    int at = Math.clamp(caret, 0, text.length());
    for (int k = 0; k < statements.size(); k++) {
      var statement = statements.get(k);
      if (at <= statement.end()) {
        return Optional.of(
            at < statement.start() && k > 0 && justEnded(k - 1, at)
                ? statements.get(k - 1)
                : statement);
      }
    }
    return statements.isEmpty() ? Optional.empty() : Optional.of(statements.getLast());
  }

  /**
   * The first word of the text's first statement, after any opening parentheses and the comments
   * between them, as it is written; empty where the statement starts with anything else, or the
   * text holds none.
   */
  public static String firstWord(String text, Provider provider) {
    var scan = scan(text, provider);
    if (scan.statements.isEmpty()) {
      return "";
    }
    int n = text.length();
    scan.i = scan.statements.getFirst().start();
    while (scan.i < n) {
      char c = text.charAt(scan.i);
      if (whitespace(c) || c == '(') {
        scan.i++;
        continue;
      }
      int from = scan.i;
      if (scan.token()) {
        int to = from;
        while (to < n && (tagStart(text.charAt(to)) || (to > from && digit(text.charAt(to))))) {
          to++;
        }
        return text.substring(from, to);
      }
    }
    return "";
  }

  private static Statements scan(String text, Provider provider) {
    var scan = new Statements(text, provider);
    scan.run();
    return scan;
  }

  private boolean justEnded(int k, int caret) {
    int after = afters.get(k);
    for (int j = after; j < caret; j++) {
      char c = text.charAt(j);
      if (c != ' ' && c != '\t') {
        return false;
      }
    }
    return true;
  }

  private void run() {
    int n = text.length();
    // The first and the past-last offset of what the pending statement holds besides whitespace
    // and comments; -1 while it holds nothing.
    int start = -1;
    int end = -1;
    while (i < n) {
      if (start < 0 && !postgres && delimiterCommand()) {
        continue;
      }
      if (text.startsWith(delimiter, i)) {
        i += delimiter.length();
        finish(start, end, i);
        start = -1;
        continue;
      }
      char c = text.charAt(i);
      if (whitespace(c)) {
        i++;
        continue;
      }
      int from = i;
      if (token()) {
        if (start < 0) {
          start = from;
        }
        end = i;
      }
    }
    finish(start, end, end);
  }

  private void finish(int start, int end, int after) {
    if (start >= 0) {
      statements.add(new Statement(start, end));
      afters.add(after);
    }
  }

  // Skips what begins at i: a comment, a quoted string or identifier, or one character. Whether
  // that was more than a comment.
  private boolean token() {
    char c = text.charAt(i);
    char next = charAt(i + 1);
    if (c == '-' && next == '-' && (postgres || controlOrSpace(charAt(i + 2)))) {
      skipLine();
      return false;
    }
    if (c == '/' && next == '*') {
      if (postgres) {
        skipNestedComment();
        return false;
      }
      // Executable comments are code for the server, but a terminator in them does not count.
      char third = charAt(i + 2);
      boolean code =
          third == '!' || (third == 'M' && charAt(i + 3) == '!' && provider == Provider.MARIADB);
      int close = text.indexOf("*/", i + 2);
      i = close < 0 ? text.length() : close + 2;
      return code;
    }
    if (postgres) {
      if (c == '\'') {
        skipQuoted('\'', false);
      } else if ((c == 'E' || c == 'e') && next == '\'' && !identifierBefore()) {
        i++;
        skipQuoted('\'', true);
      } else if (c == '"') {
        skipQuoted('"', false);
      } else if (c != '$' || identifierBefore() || !skipDollarQuoted()) {
        i++;
      }
    } else if (c == '#') {
      skipLine();
      return false;
    } else if (c == '\'' || c == '"') {
      skipQuoted(c, true);
    } else if (c == '`') {
      skipQuoted('`', false);
    } else {
      i++;
    }
    return true;
  }

  private void skipLine() {
    while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
      i++;
    }
  }

  private void skipNestedComment() {
    int depth = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (c == '/' && charAt(i + 1) == '*') {
        depth++;
        i += 2;
      } else if (c == '*' && charAt(i + 1) == '/') {
        i += 2;
        if (--depth == 0) {
          return;
        }
      } else {
        i++;
      }
    }
  }

  private void skipQuoted(char quote, boolean backslash) {
    int n = text.length();
    i++;
    while (i < n) {
      char c = text.charAt(i++);
      if (backslash && c == '\\') {
        i++;
      } else if (c == quote) {
        if (charAt(i) != quote) {
          return;
        }
        i++;
      }
    }
    i = n;
  }

  // At a "$" that begins a token: skips a dollar-quoted string, or returns false if there is none.
  private boolean skipDollarQuoted() {
    int n = text.length();
    int j = i + 1;
    if (j < n && tagStart(text.charAt(j))) {
      j++;
      while (j < n && (tagStart(text.charAt(j)) || digit(text.charAt(j)))) {
        j++;
      }
    }
    if (j >= n || text.charAt(j) != '$') {
      return false;
    }
    int length = j - i + 1;
    // Each failed comparison stops at a character that is no "$", so the scan stays linear.
    for (int k = text.indexOf('$', j + 1); k >= 0; k = text.indexOf('$', k + 1)) {
      if (text.regionMatches(k, text, i, length)) {
        i = k + length;
        return true;
      }
    }
    i = n;
    return true;
  }

  // At a word DELIMITER where a statement could begin: skips its line and takes its argument as
  // the delimiter, as the clients do; returns false if there is no such word.
  private boolean delimiterCommand() {
    int n = text.length();
    int j = i + "DELIMITER".length();
    if (!text.regionMatches(true, i, "DELIMITER", 0, "DELIMITER".length())
        || (j < n && text.charAt(j) != ' ' && text.charAt(j) != '\t' && !lineEnd(text.charAt(j)))) {
      return false;
    }
    while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
      j++;
    }
    char quote = charAt(j);
    boolean quoted = quote == '\'' || quote == '"' || quote == '`';
    int from = quoted ? j + 1 : j;
    int to = from;
    while (to < n
        && !lineEnd(text.charAt(to))
        && (quoted ? text.charAt(to) != quote : !whitespace(text.charAt(to)))) {
      to++;
    }
    // The clients refuse an empty delimiter and one with a backslash, and keep the old one.
    boolean valid = to > from;
    for (int k = from; k < to; k++) {
      valid &= text.charAt(k) != '\\';
    }
    if (valid) {
      delimiter = text.substring(from, Math.min(to, from + MAX_DELIMITER));
    }
    i = to;
    while (i < n && text.charAt(i) != '\n') {
      i++;
    }
    return true;
  }

  // Whether the character before i belongs to an identifier, so that i does not begin a token.
  private boolean identifierBefore() {
    if (i == 0) {
      return false;
    }
    char c = text.charAt(i - 1);
    return tagStart(c) || digit(c) || c == '$';
  }

  private char charAt(int index) {
    return index < text.length() ? text.charAt(index) : '\0';
  }

  // Letters and "_" as PostgreSQL's identifiers begin with them; every non-ASCII character counts.
  private static boolean tagStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean whitespace(char c) {
    return c == ' ' || (c >= '\t' && c <= '\r');
  }

  private static boolean lineEnd(char c) {
    return c == '\n' || c == '\r';
  }

  // The end of the text reads as NUL, a control character, as it does in the servers' lexers.
  private static boolean controlOrSpace(char c) {
    return c <= ' ' || c == 0x7f;
  }
}

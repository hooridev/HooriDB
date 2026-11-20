package dev.hoori.db.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * The tokens of the shell's syntax, as JavaScript reads them: names, numbers, strings, regular
 * expressions, comments, and each other character as punctuation. Splitting a text into
 * statements, reading one, coloring and completing it all go by these.
 *
 * <ul>
 *   <li>A string in {@code '} or {@code "} ends at its quote, and where it is not closed before
 *       its line ends, before the line break; a backslash escapes the next character, a line
 *       break too. One in backticks goes on over lines.
 *   <li>A slash starts a regular expression unless a value came last, after which it divides: a
 *       name, a number, a string, a regular expression, or {@code )}, {@code ]} or <code>}</code>.
 *       Its flags are part of it; one not closed ends before the line break.
 *   <li>A line comment ends before its line break, a block comment where it is closed.
 * </ul>
 */
public final class Lexer {

  /** What a token is. */
  public enum Kind {
    NAME,
    NUMBER,
    STRING,
    REGEX,
    COMMENT,
    PUNCTUATION
  }

  /**
   * A token: what it is, where it starts and ends, as offsets of characters, and whether it is
   * open: a string, regular expression or block comment that is not closed, and a line comment,
   * which a caret at its end is still in.
   */
  public record Token(Kind kind, int start, int end, boolean open) {

    /** Whether it is this one character of punctuation. */
    public boolean is(String text, char c) {
      return kind == Kind.PUNCTUATION && text.charAt(start) == c;
    }
  }

  /**
   * Where a line starts, as the lines before it leave it: inside a block comment ({@link
   * Kind#COMMENT}) or a string ({@link Kind#STRING}) in backticks or whose line break was escaped,
   * with its quote, or else in code ({@code null}); and whether a value came last, after which a
   * slash divides.
   */
  public record State(Kind within, char quote, boolean value) {

    /** Where a text starts. */
    public static final State START = new State(null, '\0', false);
  }

  private Lexer() {}

  /** The tokens of the text, in order. */
  public static List<Token> tokens(String text) {
    var tokens = new ArrayList<Token>();
    tokens(text, State.START, tokens);
    return tokens;
  }

  /** Adds the tokens of a line that starts in the given state, and gives the state at its end. */
  public static State tokens(String text, State from, List<Token> into) {
    int n = text.length();
    int i = 0;
    boolean value = from.value();
    var within = State.START;
    if (from.within() != null) {
      var rest =
          from.within() == Kind.COMMENT ? block(text, 0, 0) : string(text, 0, 0, from.quote());
      into.add(rest);
      i = rest.end();
      value |= rest.kind() == Kind.STRING;
      within = goesOn(text, rest, from.quote());
    }
    while (i < n) {
      if (whitespace(text.charAt(i))) {
        i++;
        continue;
      }
      var token = token(text, i, value);
      into.add(token);
      i = token.end();
      value = switch (token.kind()) {
        case COMMENT -> value;
        case PUNCTUATION -> ")]}".indexOf(text.charAt(token.start())) >= 0;
        default -> true;
      };
      within = goesOn(text, token, text.charAt(token.start()));
    }
    return new State(within.within(), within.quote(), value);
  }

  // Whether the token goes on on the next line, as a block comment or a string in backticks not
  // closed, and a string whose line break a backslash escapes: as which.
  private static State goesOn(String text, Token token, char quote) {
    int n = text.length();
    if (!token.open() || token.end() != n) {
      return State.START;
    }
    if (token.kind() == Kind.COMMENT) {
      return text.startsWith("//", token.start())
          ? State.START
          : new State(Kind.COMMENT, '\0', false);
    }
    if (token.kind() != Kind.STRING) {
      return State.START;
    }
    int backslashes = 0;
    while (n - backslashes - 1 > token.start() && text.charAt(n - backslashes - 1) == '\\') {
      backslashes++;
    }
    return quote == '`' || backslashes % 2 == 1
        ? new State(Kind.STRING, quote, false)
        : State.START;
  }

  /** Whether the character is space between tokens, a line break too. */
  public static boolean whitespace(char c) {
    return Character.isWhitespace(c) || c == '\u00a0' || c == '\ufeff';
  }

  /**
   * The token that starts at the offset, which is no space; a slash there divides where a value
   * came before it.
   */
  static Token token(String text, int at, boolean value) {
    int n = text.length();
    char c = text.charAt(at);
    if (text.startsWith("//", at)) {
      int end = text.indexOf('\n', at);
      return new Token(Kind.COMMENT, at, end < 0 ? n : end, true);
    }
    if (text.startsWith("/*", at)) {
      return block(text, at, at + 2);
    }
    if (c == '\'' || c == '"' || c == '`') {
      return string(text, at, at + 1, c);
    }
    if (c == '/' && !value) {
      return regex(text, at);
    }
    if (digit(c) || c == '.' && at + 1 < n && digit(text.charAt(at + 1))) {
      int end = at + 1;
      while (end < n
          && (Character.isLetterOrDigit(text.charAt(end))
              || text.charAt(end) == '.'
              || text.charAt(end) == '_')) {
        end++;
      }
      return new Token(Kind.NUMBER, at, end, false);
    }
    if (Character.isJavaIdentifierStart(c)) {
      int end = at + 1;
      while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) {
        end++;
      }
      return new Token(Kind.NAME, at, end, false);
    }
    return new Token(Kind.PUNCTUATION, at, at + 1, false);
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  // A block comment that starts at the offset, its closer looked for from the other.
  private static Token block(String text, int start, int from) {
    int close = text.indexOf("*/", from);
    return close < 0
        ? new Token(Kind.COMMENT, start, text.length(), true)
        : new Token(Kind.COMMENT, start, close + 2, false);
  }

  // A string that starts at the offset, its closing quote looked for from the other.
  private static Token string(String text, int start, int from, char quote) {
    int n = text.length();
    int i = from;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == quote) {
        return new Token(Kind.STRING, start, i + 1, false);
      } else if (c == '\n' && quote != '`') {
        return new Token(Kind.STRING, start, i, true);
      } else {
        i++;
      }
    }
    return new Token(Kind.STRING, start, n, true);
  }

  private static Token regex(String text, int at) {
    int n = text.length();
    boolean inClass = false;
    int i = at + 1;
    while (i < n) {
      char c = text.charAt(i);
      if (c == '\n') {
        break;
      }
      if (c == '\\') {
        i += i + 1 < n && text.charAt(i + 1) != '\n' ? 2 : 1;
        continue;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '/' && !inClass) {
        int end = i + 1;
        while (end < n && Character.isLetter(text.charAt(end))) {
          end++;
        }
        return new Token(Kind.REGEX, at, end, false);
      }
      i++;
    }
    return new Token(Kind.REGEX, at, Math.min(i, n), true);
  }
}

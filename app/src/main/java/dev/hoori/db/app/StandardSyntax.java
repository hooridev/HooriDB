package dev.hoori.db.app;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.dialect.SyntaxDialect;
import java.util.Locale;

/**
 * Colors SQL by the standard's rules alone, as {@link SyntaxDialect#STANDARD} splits it, for a
 * workspace query before it has a target: the key words all servers share, strings in single
 * quotes with doubled quotes, identifiers in double quotes in the text's color, numbers, {@code --}
 * comments and block comments that do not nest, operators and brackets. A string, a quoted
 * identifier and a block comment go on over lines, which the state of a line carries.
 */
record StandardSyntax(Theme.Code colors) implements Highlighter {

  private static final String OPERATORS = "+-*/%<>=!~^&|#@:;,.()[]{}";
  private static final String BRACKETS = "()[]{}";

  /** What a line ends inside of: a string, a quoted identifier or a block comment. */
  private enum Open {
    STRING(1),
    QUOTED(1),
    COMMENT(2);

    final int opening; // the length of what opens it

    Open(int opening) {
      this.opening = opening;
    }
  }

  /** The colors of the theme in use; called in a view. */
  static StandardSyntax of() {
    return new StandardSyntax(Theme.CURRENT.get().code());
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    int n = line.length();
    int i = 0;
    var open = (Open) state;
    if (open != null) {
      i = close(line, 0, open);
      color(out, 0, i < 0 ? n : i, open);
      if (i < 0) {
        return open;
      }
    }
    while (i < n) {
      open = opens(line, i);
      if (open == null) {
        i = code(line, i, out);
      } else {
        int end = close(line, i + open.opening, open);
        color(out, i, end < 0 ? n : end, open);
        if (end < 0) {
          return open;
        }
        i = end;
      }
    }
    return null;
  }

  // What starts at the offset and may go on over lines; null for anything else.
  private static Open opens(String line, int at) {
    char c = line.charAt(at);
    if (c == '\'') {
      return Open.STRING;
    } else if (c == '"') {
      return Open.QUOTED;
    }
    return c == '/' && line.startsWith("/*", at) ? Open.COMMENT : null;
  }

  // A quoted identifier keeps the text's color.
  private void color(Spans out, int from, int to, Open open) {
    switch (open) {
      case STRING -> out.color(from, to, colors.string());
      case COMMENT -> out.color(from, to, colors.comment());
      case QUOTED -> {
        // the text's color
      }
    }
  }

  // The token of code at the offset colored; the offset after it.
  private int code(String line, int at, Spans out) {
    int n = line.length();
    char c = line.charAt(at);
    char next = at + 1 < n ? line.charAt(at + 1) : 0;
    if (c == '-' && next == '-') {
      out.color(at, n, colors.comment());
      return n;
    }
    if (Character.isDigit(c) || c == '.' && Character.isDigit(next)) {
      int end = number(line, at);
      out.color(at, end, colors.number());
      return end;
    }
    if (Character.isLetter(c) || c == '_') {
      int end = at + 1;
      while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
        end++;
      }
      var word = line.substring(at, end).toUpperCase(Locale.ROOT);
      if (SyntaxDialect.STANDARD.offeredWords().contains(word)) {
        out.color(at, end, colors.keyword());
      }
      return end;
    }
    if (OPERATORS.indexOf(c) >= 0) {
      out.color(at, at + 1, colors.operator());
      if (BRACKETS.indexOf(c) >= 0) {
        out.bracket(at);
      }
    }
    return at + 1;
  }

  /** The end of what is open, after its closing quote or {@code *}{@code /}; -1 for none here. */
  private static int close(String line, int from, Open open) {
    if (open == Open.COMMENT) {
      int end = line.indexOf("*/", from);
      return end < 0 ? -1 : end + 2;
    }
    char quote = open == Open.STRING ? '\'' : '"';
    int i = from;
    while (i < line.length()) {
      if (line.charAt(i) != quote) {
        i++;
      } else if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
        i += 2; // doubled: the quote itself
      } else {
        return i + 1;
      }
    }
    return -1;
  }

  /** The end of a number at {@code at}: digits, a fraction and an exponent. */
  private static int number(String line, int at) {
    int n = line.length();
    int i = digits(line, at);
    if (i < n && line.charAt(i) == '.') {
      i = digits(line, i + 1);
    }
    if (i < n && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
      int e = i + 1 < n && (line.charAt(i + 1) == '+' || line.charAt(i + 1) == '-') ? i + 2 : i + 1;
      if (e < n && Character.isDigit(line.charAt(e))) {
        i = digits(line, e);
      }
    }
    return i;
  }

  private static int digits(String line, int at) {
    int i = at;
    while (i < line.length() && Character.isDigit(line.charAt(i))) {
      i++;
    }
    return i;
  }

  @Override
  public boolean marksBrackets() {
    return true;
  }

  @Override
  public String lineComment() {
    return "--";
  }
}

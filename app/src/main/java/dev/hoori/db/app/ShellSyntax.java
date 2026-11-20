package dev.hoori.db.app;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import java.util.Set;

/**
 * Colors the statements of the MongoDB shell, as a console of documents takes them: the shell's
 * words and constructors, the operators that start with a dollar, strings, numbers, regular
 * expressions and comments, and marks the brackets outside them. A block comment goes on over
 * lines.
 */
record ShellSyntax(Theme.Code colors) implements Highlighter {

  /** The words of the shell, colored as keywords. */
  static final Set<String> WORDS =
      Set.of("db", "use", "show", "new", "true", "false", "null", "undefined", "NaN", "Infinity");

  /** The constructors of values that the shell takes. */
  static final Set<String> CONSTRUCTORS = Set.of(
      "ObjectId",
      "ISODate",
      "Date",
      "NumberLong",
      "Long",
      "NumberInt",
      "Int32",
      "Double",
      "NumberDecimal",
      "Decimal128",
      "UUID",
      "BinData",
      "Binary",
      "Timestamp",
      "MinKey",
      "MaxKey",
      "RegExp",
      "Code");

  /** The colors of the theme in use; called in a view. */
  static ShellSyntax of() {
    return new ShellSyntax(Theme.CURRENT.get().code());
  }

  // Inside a block comment from the line before.
  private enum Within {
    COMMENT
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    int i = 0;
    int n = line.length();
    if (state == Within.COMMENT) {
      int close = line.indexOf("*/");
      if (close < 0) {
        out.color(0, n, colors.comment());
        return Within.COMMENT;
      }
      out.color(0, close + 2, colors.comment());
      i = close + 2;
    }
    boolean value = false; // whether a value came last, after which a slash divides
    while (i < n) {
      char c = line.charAt(i);
      if (line.startsWith("//", i)) {
        out.color(i, n, colors.comment());
        return null;
      }
      if (line.startsWith("/*", i)) {
        int close = line.indexOf("*/", i + 2);
        if (close < 0) {
          out.color(i, n, colors.comment());
          return Within.COMMENT;
        }
        out.color(i, close + 2, colors.comment());
        i = close + 2;
        continue;
      }
      if (c == '\'' || c == '"' || c == '`') {
        int end = string(line, i);
        out.color(i, end, colors.string());
        i = end;
        value = true;
        continue;
      }
      if (c == '/' && !value) {
        int end = regex(line, i);
        out.color(i, end, colors.string());
        i = end;
        value = true;
        continue;
      }
      if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1))) {
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '.')) {
          end++;
        }
        out.color(i, end, colors.number());
        i = end;
        value = true;
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int end = i + 1;
        while (end < n && Character.isJavaIdentifierPart(line.charAt(end))) {
          end++;
        }
        var word = line.substring(i, end);
        if (c == '$') {
          out.color(i, end, colors.operator());
        } else if (WORDS.contains(word) || CONSTRUCTORS.contains(word)) {
          out.color(i, end, colors.keyword());
        }
        i = end;
        value = true;
        continue;
      }
      if ("([{".indexOf(c) >= 0) {
        out.bracket(i);
        value = false;
      } else if (")]}".indexOf(c) >= 0) {
        out.bracket(i);
        value = true;
      } else if (!Character.isWhitespace(c)) {
        value = false;
      }
      i++;
    }
    return null;
  }

  private static int string(String line, int at) {
    char quote = line.charAt(at);
    int i = at + 1;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) {
        return i + 1;
      }
      i++;
    }
    return line.length();
  }

  private static int regex(String line, int at) {
    int i = at + 1;
    boolean inClass = false;
    while (i < line.length()) {
      char c = line.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '/' && !inClass) {
        i++;
        while (i < line.length() && Character.isLetter(line.charAt(i))) {
          i++;
        }
        return i;
      }
      i++;
    }
    return line.length();
  }

  @Override
  public boolean marksBrackets() {
    return true;
  }

  @Override
  public String lineComment() {
    return "//";
  }
}

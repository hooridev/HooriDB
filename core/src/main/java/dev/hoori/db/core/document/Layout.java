package dev.hoori.db.core.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lays out the statements of a console of documents as {@link Shell#pretty} writes values: each
 * statement on a line of its own, a document or array that fits {@link Shell#LINE} columns on one
 * line, as <code>{ a: 1 }</code>, and else over lines, indented by two spaces a level, with a field
 * or element a line. It changes white space alone: every token, a comment, a string, a regular
 * expression or a constructor, stays as it is written, and so does what each statement means.
 */
public final class Layout {

  private final String text;
  private final List<Lexer.Token> tokens;
  private final int[] match; // per opening bracket the index of its closing one, else -1
  private final boolean[] ends; // per token whether it ends a statement
  private final boolean[] kept; // per token whether the white space before it stays, as in use
  private final StringBuilder out = new StringBuilder();
  private final ArrayDeque<Boolean> open = new ArrayDeque<>(); // per bracket whether broken
  private int depth; // of the brackets broken over lines

  private Layout(String text, List<Lexer.Token> tokens, List<Script.Piece> pieces, boolean[] uses) {
    this.text = text;
    this.tokens = tokens;
    match = new int[tokens.size()];
    ends = new boolean[tokens.size()];
    kept = new boolean[tokens.size()];
    Arrays.fill(match, -1);
    var openers = new ArrayDeque<Integer>();
    int piece = 0;
    for (int k = 0; k < tokens.size(); k++) {
      var token = tokens.get(k);
      while (piece < pieces.size() && pieces.get(piece).end() < token.end()) {
        piece++;
      }
      boolean in = piece < pieces.size() && pieces.get(piece).start() <= token.start();
      ends[k] = in && pieces.get(piece).end() == token.end();
      // use takes the name as it is written, up to white space: its statement stays as it is.
      kept[k] = in && uses[piece] && pieces.get(piece).start() < token.start();
      if (kept[k] || token.kind() != Lexer.Kind.PUNCTUATION) {
        continue;
      }
      char c = text.charAt(token.start());
      if ("([{".indexOf(c) >= 0) {
        openers.push(k);
      } else if (")]}".indexOf(c) >= 0 && !openers.isEmpty()) {
        match[openers.pop()] = k;
      }
    }
  }

  /**
   * The text laid out; the first statement that does not parse, with its offset in the text,
   * leaves it as it is.
   */
  public static String of(String text) throws SyntaxException {
    var pieces = Script.split(text);
    var uses = new boolean[pieces.size()];
    for (int i = 0; i < pieces.size(); i++) {
      var piece = pieces.get(i);
      try {
        uses[i] = Script.parse(piece.text()).use() != null;
      } catch (SyntaxException e) {
        throw new SyntaxException(e.getMessage(), piece.start() + e.offset());
      }
    }
    var tokens = Lexer.tokens(text);
    var laid = new Layout(text, tokens, pieces, uses).write();
    return same(text, tokens, pieces, laid) ? laid : text;
  }

  // Whether the text laid out has the same tokens, comments too, in the same statements.
  private static boolean same(
      String text, List<Lexer.Token> tokens, List<Script.Piece> pieces, String laid) {
    var after = Lexer.tokens(laid);
    if (after.size() != tokens.size()) {
      return false;
    }
    for (int k = 0; k < tokens.size(); k++) {
      var a = tokens.get(k);
      var b = after.get(k);
      if (a.kind() != b.kind()
          || !text.substring(a.start(), a.end()).equals(laid.substring(b.start(), b.end()))) {
        return false;
      }
    }
    return counts(tokens, pieces).equals(counts(after, Script.split(laid)));
  }

  // How many tokens each statement has, which with the same tokens says where each starts.
  private static List<Integer> counts(List<Lexer.Token> tokens, List<Script.Piece> pieces) {
    var counts = new ArrayList<Integer>(pieces.size());
    int k = 0;
    for (var piece : pieces) {
      while (tokens.get(k).start() < piece.start()) {
        k++;
      }
      int first = k;
      while (k < tokens.size() && tokens.get(k).end() <= piece.end()) {
        k++;
      }
      counts.add(k - first);
    }
    return counts;
  }

  private String write() {
    int code = -1; // the last token that is no comment
    boolean ownLine = false; // whether the comment before starts its line
    for (int k = 0; k < tokens.size(); k++) {
      var token = tokens.get(k);
      boolean comment = token.kind() == Lexer.Kind.COMMENT;
      if (k > 0 && kept[k]) {
        out.append(text, tokens.get(k - 1).end(), token.start());
        ownLine = false;
      } else if (k > 0) {
        var gap = comment ? commentGap(k) : gap(code, k);
        if (lineComment(k - 1) && gap.indexOf('\n') < 0) {
          gap = newline();
        } else if (!comment && ownLine && breaks(k) == 0 && gap.indexOf('\n') >= 0) {
          gap = " "; // after a block comment that starts its line, as it stood before the code
        }
        ownLine = comment && gap.indexOf('\n') >= 0;
        out.append(gap);
      }
      if (!comment) {
        code = k;
      }
      if (!comment && !kept[k]) {
        close(k);
      }
      out.append(text, token.start(), token.end());
      if (!comment && !kept[k]) {
        open(k);
      }
    }
    return out.toString();
  }

  /** What goes before a comment: its own line where it had one, else a space. */
  private String commentGap(int k) {
    int breaks = breaks(k);
    if (breaks == 0) {
      return " ";
    }
    return depth == 0 && open.isEmpty() && breaks > 1 ? "\n" + newline() : newline();
  }

  /** What goes between the code token {@code a}, -1 before the first, and the code token at k. */
  private String gap(int a, int k) {
    if (a < 0 || ends[a]) { // a statement's first token, after comments or a statement
      int breaks = breaks(k);
      return breaks > 1 ? "\n\n" : breaks > 0 || a >= 0 ? "\n" : " ";
    }
    char before = punctuation(a);
    char c = punctuation(k);
    if (open.isEmpty() || !open.peek()) { // outside brackets, or in one on a line
      return inline(a, k, !open.isEmpty());
    }
    if (c == '}' || c == ']') {
      return indent(depth - 1);
    }
    return before == '{' || before == '[' || before == ',' ? newline() : inline(a, k, true);
  }

  /**
   * The gap between two code tokens on a line: none in an empty bracket, before a dot, a comma, a
   * semicolon or a parenthesis and after a dot or an opening parenthesis; a space inside a bracket
   * and after a comma or a colon, but outside brackets, where {@code use} takes a name as it is
   * written, only where there was white space, as between other tokens.
   */
  private String inline(int a, int k, boolean inBracket) {
    char before = punctuation(a);
    char c = punctuation(k);
    if ((before == '{' || before == '[') && match[a] == k) {
      return "";
    }
    if (inBracket
        && (before == '{'
            || before == '['
            || c == '}'
            || c == ']'
            || before == ','
            || before == ':')) {
      return " ";
    }
    if (c != 0 && ".,;:()".indexOf(c) >= 0 || before == '(' || before == '.') {
      return "";
    }
    return tokens.get(a).end() == tokens.get(k).start() ? "" : " ";
  }

  private void open(int k) {
    char c = punctuation(k);
    if (c == '(') {
      open.push(false);
    } else if (c == '{' || c == '[') {
      boolean broken = match[k] < 0 || match[k] > k + 1 && !fits(k);
      open.push(broken);
      if (broken) {
        depth++;
      }
    }
  }

  private void close(int k) {
    char c = punctuation(k);
    if ((c == ')' || c == ']' || c == '}') && !open.isEmpty() && open.pop()) {
      depth--;
    }
  }

  /** Whether the bracket at k, from the column it opens at, fits on the line, with no comment. */
  private boolean fits(int k) {
    int room = Shell.LINE - column() + 1;
    int length = 1;
    for (int j = k + 1; j <= match[k] && length <= room; j++) {
      var token = tokens.get(j);
      if (token.kind() == Lexer.Kind.COMMENT || word(j).indexOf('\n') >= 0) {
        return false;
      }
      length += inline(j - 1, j, true).length() + token.end() - token.start();
    }
    return length <= room;
  }

  private int column() {
    return out.length() - out.lastIndexOf("\n") - 1;
  }

  private String newline() {
    return indent(depth);
  }

  private static String indent(int depth) {
    return "\n" + "  ".repeat(Math.max(0, depth));
  }

  // The line breaks between the token at k and the one before it.
  private int breaks(int k) {
    int from = k == 0 ? 0 : tokens.get(k - 1).end();
    int count = 0;
    for (int i = from; i < tokens.get(k).start(); i++) {
      count += text.charAt(i) == '\n' ? 1 : 0;
    }
    return count;
  }

  private boolean lineComment(int k) {
    var token = tokens.get(k);
    return token.kind() == Lexer.Kind.COMMENT && text.startsWith("//", token.start());
  }

  private char punctuation(int k) {
    var token = tokens.get(k);
    return token.kind() == Lexer.Kind.PUNCTUATION ? text.charAt(token.start()) : 0;
  }

  private String word(int k) {
    return text.substring(tokens.get(k).start(), tokens.get(k).end());
  }
}

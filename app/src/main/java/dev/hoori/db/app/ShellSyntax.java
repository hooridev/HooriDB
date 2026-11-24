package dev.hoori.db.app;

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.document.Layout;
import dev.hoori.db.core.document.Lexer;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.document.SyntaxException;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Function;

/**
 * Colors the statements of the MongoDB shell, as a console of documents takes them, by the
 * tokens of core's {@link Lexer}: the shell's words and constructors, the operators that start
 * with a dollar, strings, numbers, regular expressions and comments, and marks the brackets
 * outside them. A block comment and a string in backticks go on over lines. Control+Alt+L lays
 * the statements out as core's {@link Layout} does.
 */
record ShellSyntax(Theme.Code colors) implements Highlighter {

  /** The words of the shell, colored as keywords. */
  static final Set<String> WORDS =
      Set.of("db", "use", "show", "new", "true", "false", "null", "undefined", "NaN", "Infinity");

  /** The names that start the shell's constructors, as Binary of Binary.createFromBase64. */
  static final Set<String> CONSTRUCTORS = Shell.CONSTRUCTORS.stream()
      .map(name -> name.substring(0, name.indexOf('.') < 0 ? name.length() : name.indexOf('.')))
      .collect(toUnmodifiableSet());

  /** The colors of the theme in use; called in a view. */
  static ShellSyntax of() {
    return new ShellSyntax(Theme.CURRENT.get().code());
  }

  @Override
  public Object line(String line, Object state, Spans out) {
    var tokens = new ArrayList<Lexer.Token>();
    var next = Lexer.tokens(line, state instanceof Lexer.State s ? s : Lexer.State.START, tokens);
    for (var token : tokens) {
      int from = token.start();
      int to = token.end();
      switch (token.kind()) {
        case COMMENT -> out.color(from, to, colors.comment());
        case STRING, REGEX -> out.color(from, to, colors.string());
        case NUMBER -> out.color(from, to, colors.number());
        case NAME -> {
          var word = line.substring(from, to);
          if (word.startsWith("$")) {
            out.color(from, to, colors.operator());
          } else if (WORDS.contains(word) || CONSTRUCTORS.contains(word)) {
            out.color(from, to, colors.keyword());
          }
        }
        case PUNCTUATION -> bracket(line, from, out);
      }
    }
    return next;
  }

  private static void bracket(String line, int at, Spans out) {
    if ("()[]{}".indexOf(line.charAt(at)) >= 0) {
      out.bracket(at);
    }
  }

  @Override
  public boolean marksBrackets() {
    return true;
  }

  @Override
  public String lineComment() {
    return "//";
  }

  @Override
  public Function<String, Formatted> formatter() {
    return ShellSyntax::format;
  }

  /** The text laid out, or where its first statement that does not parse goes wrong. */
  static Formatted format(String text) {
    try {
      return new Formatted(Layout.of(text), null);
    } catch (SyntaxException e) {
      if (e.tooLarge()) {
        return new Formatted(null, new Formatted.Problem(0, 0, Formatted.Kind.TOO_LARGE));
      }
      return new Formatted(null, problem(text, Math.clamp(e.offset(), 0, text.length())));
    }
  }

  /**
   * The problem at the offset: a string or block comment not closed that it is in or ends at, else
   * the token there or after it in the statement, else the statement's end.
   */
  private static Formatted.Problem problem(String text, int at) {
    int end = Script.split(text).stream()
        .mapToInt(Script.Piece::end)
        .filter(e -> e >= at)
        .findFirst()
        .orElse(text.length());
    for (var token : Lexer.tokens(text)) {
      if (token.start() > at && token.start() >= end) {
        break;
      }
      var problem = problem(text, at, token);
      if (problem != null) {
        return problem;
      }
    }
    return new Formatted.Problem(at, at, Formatted.Kind.UNEXPECTED_END);
  }

  // The problem at the token; null for one before the offset and a comment that is closed.
  private static Formatted.Problem problem(String text, int at, Lexer.Token token) {
    if (token.end() < at || token.end() == at && !token.open()) {
      return null;
    }
    boolean block = token.kind() == Lexer.Kind.COMMENT && text.startsWith("/*", token.start());
    if (token.open() && token.start() <= at && (block || token.kind() == Lexer.Kind.STRING)) {
      var kind = block ? Formatted.Kind.UNEXPECTED_END : Formatted.Kind.UNCLOSED_STRING;
      return new Formatted.Problem(token.start(), token.end(), kind);
    }
    return token.kind() == Lexer.Kind.COMMENT
        ? null
        : new Formatted.Problem(
            Math.max(at, token.start()), token.end(), Formatted.Kind.UNEXPECTED);
  }
}

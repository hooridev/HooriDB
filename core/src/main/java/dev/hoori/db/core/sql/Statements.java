package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.document.Lexer;
import dev.hoori.db.core.document.Script;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Where the statements of a text of SQL begin and end, by the lexical rules of a provider's server.
 *
 * <p>A statement ends at its terminator, which is not part of it, or at the end of the text. A
 * terminator does not count inside a string, a quoted identifier or a comment; one that is not
 * closed runs to the end of the text. For MariaDB and MySQL the client command {@code DELIMITER}
 * changes the terminator, as their command-line clients do: a line where a statement would begin
 * and whose first word is {@code DELIMITER}, in any case, is no statement, and its argument is the
 * terminator from then on. Where the syntax has {@linkplain SyntaxDialect#atomicBodies bodies of
 * BEGIN ATOMIC … END}, a terminator does not count inside one, as psql tracks it: in a statement
 * that begins {@code CREATE [OR REPLACE] FUNCTION} or {@code PROCEDURE}, each {@code BEGIN} outside
 * parentheses opens a block, and so does a {@code CASE} within one, which an {@code END} closes.
 *
 * <p>The scan is linear in the length of the text and never throws.
 */
// shortcut: this scans characters and is no SQL parser, so a MariaDB or MySQL routine written
// without DELIMITER, whose body holds semicolons, is split at them, as their clients split it.
public final class Statements {

  // What the clients of MariaDB and MySQL keep of a delimiter; they cut a longer one off.
  private static final int MAX_DELIMITER = 15;

  private final String text;
  private final SyntaxDialect syntax;
  private final List<Statement> found = new ArrayList<>();
  // For each statement, the offset after its terminator, or its end if it has none.
  private final List<Integer> afters = new ArrayList<>();
  private String delimiter = ";";
  private int i;
  // A file's scan: what it finds, its client's commands among them; null for a text of the editor.
  private List<Part> parts;
  // Whether a backslash where a statement could begin is a command of the client, to its line end.
  private boolean backslashCommands;
  // What the last token() skipped, and whether a caret right after it is still inside it.
  private Piece.Kind kind;
  private boolean open;
  // For bodies of BEGIN ATOMIC … END: how far the pending statement's first words are those of
  // CREATE [OR REPLACE] FUNCTION or PROCEDURE, and how deep it is in parentheses and in blocks.
  private Head head = Head.START;
  private int parens;
  private int blocks;

  /**
   * A piece of a text of SQL as the server reads it: a comment, a string, a quoted name, or one
   * character of code; whitespace between them is none.
   *
   * @param open whether a caret right at its end is still inside it: a comment to the end of the
   *     line, and a string, quoted name or comment that the text ends in before it is closed
   */
  public record Piece(Kind kind, int start, int end, boolean open) {

    /** What a piece is. */
    public enum Kind {
      COMMENT,
      STRING,
      QUOTED,
      CODE
    }

    /** Whether a caret at the offset is inside it, other than at its start. */
    public boolean holds(int caret) {
      return start < caret && (caret < end || caret == end && open);
    }
  }

  // How far a statement's first words are those of CREATE [OR REPLACE] FUNCTION or PROCEDURE:
  // before any, after CREATE, after OR, after REPLACE, ROUTINE once they are, OTHER once they are
  // not.
  private enum Head {
    START,
    CREATE,
    OR,
    REPLACE,
    ROUTINE,
    OTHER;

    // Where the next word of the statement leads.
    Head next(String word) {
      return switch (this) {
        case START -> word.equals("CREATE") ? CREATE : OTHER;
        case CREATE -> word.equals("OR") ? OR : routine(word);
        case OR -> word.equals("REPLACE") ? REPLACE : OTHER;
        case REPLACE -> routine(word);
        case ROUTINE, OTHER -> this;
      };
    }

    private static Head routine(String word) {
      return word.equals("FUNCTION") || word.equals("PROCEDURE") ? ROUTINE : OTHER;
    }
  }

  // Whether a backslash escapes in a plain string: as MariaDB and MySQL read one by default, and
  // PostgreSQL only with standard_conforming_strings off.
  private boolean escapes;

  /**
   * What the scan of part of a file found, in order, as offsets in its text: a statement, which ends
   * where {@link Statement} does; a line with a command of the provider's client, from its
   * backslash to the end of the line; or a line {@code DELIMITER}, which the scan took.
   *
   * @param after the offset after its terminator, or after its line for a command, or its end
   *     where the text ends first
   * @param delimiter the terminator from after it on
   */
  record Part(Kind kind, int start, int end, int after, String delimiter) {

    /** What a part is. */
    enum Kind {
      STATEMENT,
      COMMAND,
      DELIMITER
    }
  }

  private Statements(String text, SyntaxDialect syntax) {
    this.text = text;
    this.syntax = syntax;
    this.escapes = syntax != null && syntax.backslashEscapes();
  }

  /** The statements of the text, in order. */
  public static List<Statement> split(String text, Provider provider) {
    return scan(text, provider).found;
  }

  /** The statements of the SQL text, in order, split by the lexical rules of {@code syntax}. */
  public static List<Statement> split(String text, SyntaxDialect syntax) {
    return of(text, syntax).found;
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

  /** As {@link #at(String, Provider, int)}, split by the lexical rules of {@code syntax}. */
  public static Optional<Statement> at(String text, SyntaxDialect syntax, int caret) {
    return of(text, syntax).at(caret);
  }

  /**
   * The text scanned once, for a caret that moves while the text stays: {@link #at(int)} then
   * costs no scan.
   */
  public static Statements of(String text, Provider provider) {
    return scan(text, provider);
  }

  /** As {@link #of(String, Provider)}, split by the lexical rules of {@code syntax}. */
  public static Statements of(String text, SyntaxDialect syntax) {
    var scan = new Statements(text, Objects.requireNonNull(syntax, "syntax"));
    scan.run();
    return scan;
  }

  /** The statements of a document shell text, as {@link Script#split} splits them. */
  public static Statements ofShell(String text) {
    var scan = new Statements(text, null);
    scan.shell();
    return scan;
  }

  /** The statements of the text, in order. */
  public List<Statement> statements() {
    return Collections.unmodifiableList(found);
  }

  /** The statement that runs for a caret at the offset, as {@link #at(String, Provider, int)}. */
  public Optional<Statement> at(int caret) {
    int at = Math.clamp(caret, 0, text.length());
    for (int k = 0; k < found.size(); k++) {
      var statement = found.get(k);
      if (at <= statement.end()) {
        return Optional.of(
            at < statement.start() && k > 0 && justEnded(k - 1, at) ? found.get(k - 1) : statement);
      }
    }
    return found.isEmpty() ? Optional.empty() : Optional.of(found.getLast());
  }

  /**
   * The first word of the text's first statement, after any opening parentheses and the comments
   * between them, as it is written; empty where the statement starts with anything else, or the
   * text holds none.
   */
  public static String firstWord(String text, Provider provider) {
    var scan = scan(text, provider);
    if (scan.found.isEmpty()) {
      return "";
    }
    if (scan.syntax == null) {
      var first = scan.found.getFirst();
      return Script.method(text.substring(first.start(), first.end()));
    }
    int n = text.length();
    scan.i = scan.found.getFirst().start();
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

  /**
   * The rows the statement's own {@code LIMIT} lets through, of MariaDB's and MySQL's forms {@code
   * LIMIT n}, {@code LIMIT m, n} and {@code LIMIT n OFFSET m}: the last at its top level, as one of
   * a subquery limits only that; empty where it has none.
   */
  public static OptionalLong limit(String sql, Provider provider) {
    // Not one in an executable comment, which an older server skips.
    var tokens = Tokens.of(
        sql, provider, new Tokens.Reading(provider.dialect().syntax().backslashEscapes(), false));
    var limit = OptionalLong.empty();
    for (int k = 0; k + 1 < tokens.size(); k++) {
      if (tokens.get(k).depth() == 0 && tokens.get(k).is("LIMIT")) {
        int at = k + 1;
        if (at + 2 < tokens.size() && tokens.get(at + 1).text().equals(",")) {
          at += 2;
        }
        var count = tokens.get(at).text();
        limit = count.chars().allMatch(Character::isDigit) && count.length() < 19
            ? OptionalLong.of(Long.parseLong(count))
            : OptionalLong.empty();
      }
    }
    return limit;
  }

  /**
   * The pieces of the text, in order, by the same rules as the statements are split by; a
   * {@code DELIMITER} line is not told apart, so this is for the text of one statement.
   */
  public static List<Piece> pieces(String text, Provider provider) {
    if (provider.keepsDocuments()) {
      return shellPieces(text);
    }
    return pieces(text, provider, provider.dialect().syntax().backslashEscapes());
  }

  // The comments and strings of the shell's syntax, its regular expressions as strings, and the
  // code between them a character each.
  private static List<Piece> shellPieces(String text) {
    var pieces = new ArrayList<Piece>();
    for (var token : Lexer.tokens(text)) {
      switch (token.kind()) {
        case COMMENT ->
          pieces.add(new Piece(Piece.Kind.COMMENT, token.start(), token.end(), token.open()));
        case STRING, REGEX ->
          pieces.add(new Piece(Piece.Kind.STRING, token.start(), token.end(), token.open()));
        default -> {
          for (int i = token.start(); i < token.end(); i++) {
            pieces.add(new Piece(Piece.Kind.CODE, i, i + 1, false));
          }
        }
      }
    }
    return pieces;
  }

  /**
   * As {@link #pieces(String, Provider)}, with a backslash in a plain string an escape or not, as
   * MariaDB's and MySQL's NO_BACKSLASH_ESCAPES or PostgreSQL's standard_conforming_strings say.
   */
  static List<Piece> pieces(String text, Provider provider, boolean escapes) {
    return pieces(text, provider.keepsDocuments() ? null : provider.dialect().syntax(), escapes);
  }

  /** As {@link #pieces(String, Provider)}, by the lexical rules of {@code syntax}. */
  public static List<Piece> pieces(String text, SyntaxDialect syntax) {
    return pieces(text, Objects.requireNonNull(syntax, "syntax"), syntax.backslashEscapes());
  }

  private static List<Piece> pieces(String text, SyntaxDialect syntax, boolean escapes) {
    var scan = new Statements(text, syntax);
    scan.escapes = escapes;
    var pieces = new ArrayList<Piece>();
    int n = text.length();
    while (scan.i < n) {
      if (whitespace(text.charAt(scan.i))) {
        scan.i++;
        continue;
      }
      int from = scan.i;
      scan.kind = Piece.Kind.CODE;
      scan.open = false;
      scan.token();
      pieces.add(new Piece(scan.kind, from, scan.i, scan.open));
    }
    return pieces;
  }

  /**
   * The part of the text that a caret at the offset writes in: from after the terminator of the
   * statement before up to after the terminator of its own, or to the end of the text where its
   * statement has none or there is no statement after the last terminator. It holds the comments
   * around the statement, and is empty of statements after the last terminator.
   */
  public Statement around(int caret) {
    int at = Math.clamp(caret, 0, text.length());
    int size = found.size();
    // The first statement whose terminator ends after the caret.
    int low = 0;
    int high = size;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (afters.get(middle) > at) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    boolean unterminated = open();
    if (low == size && unterminated) {
      low = size - 1; // the last statement goes on to the end of the text
    }
    int start = low == 0 ? 0 : afters.get(low - 1);
    int end = low >= size || low == size - 1 && unterminated ? text.length() : afters.get(low);
    return new Statement(start, end);
  }

  /** Whether the text's last statement has no terminator, as one still being written. */
  public boolean open() {
    int size = found.size();
    return size > 0 && afters.get(size - 1) == found.get(size - 1).end();
  }

  /**
   * The parts of the text from the offset on, as a file is read: the statements by the same rules
   * as {@link #split}, from the delimiter given on, and the lines of client commands, where the
   * provider's client has commands that begin with a backslash. The text before the offset is only
   * looked at as what a token there follows.
   */
  static List<Part> parts(String text, int from, Provider provider, String delimiter) {
    var scan = new Statements(text, provider);
    scan.parts = new ArrayList<>();
    scan.delimiter = delimiter;
    scan.backslashCommands = provider.dialect().client().backslashCommands();
    scan.i = from;
    scan.run();
    return scan.parts;
  }

  private static Statements scan(String text, Provider provider) {
    return provider.keepsDocuments()
        ? ofShell(text)
        : of(text, provider.dialect().syntax());
  }

  // The statements of the MongoDB shell, as Script splits them, without their semicolons.
  private void shell() {
    for (var piece : Script.split(text)) {
      int end = piece.end();
      if (piece.terminated()) {
        end--;
        while (end > piece.start() && whitespace(text.charAt(end - 1))) {
          end--;
        }
      }
      if (end > piece.start()) {
        found.add(new Statement(piece.start(), end));
        afters.add(piece.end());
      }
    }
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
    boolean bodies = syntax.atomicBodies();
    while (i < n) {
      int from = i;
      if (start < 0 && clientLine()) {
        // A line of the client's own, where a statement could begin, is none.
      } else if (blocks == 0 && text.startsWith(delimiter, i)) {
        i += delimiter.length();
        finish(start, end, i);
        start = -1;
        head = Head.START;
        parens = 0;
      } else if (whitespace(text.charAt(i))) {
        i++;
      } else if (code(bodies)) {
        if (start < 0) {
          start = from;
        }
        end = i;
      }
    }
    finish(start, end, end);
  }

  // Takes a word or another token at i, and follows the parentheses where the syntax has bodies of
  // BEGIN ATOMIC … END; whether it was more than a comment.
  private boolean code(boolean bodies) {
    if (bodies && word()) {
      return true;
    }
    char c = text.charAt(i);
    if (!token()) {
      return false;
    }
    if (bodies && c == '(') {
      parens++;
    } else if (bodies && c == ')' && parens > 0) {
      parens--;
    }
    return true;
  }

  // At a word that begins at i, as a token of code and no string such as E'…': takes it, and
  // follows the blocks of a routine's body that it opens or closes; whether there was one.
  private boolean word() {
    int n = text.length();
    if (!tagStart(text.charAt(i))
        || identifierBefore()
        || syntax.escapeStrings() && charAt(i + 1) == '\'') {
      return false;
    }
    int from = i;
    while (i < n && (tagStart(text.charAt(i)) || digit(text.charAt(i)) || text.charAt(i) == '$')) {
      i++;
    }
    if (head == Head.OTHER || head == Head.ROUTINE && parens > 0) {
      return true; // nothing to follow, nor a word to make
    }
    var word = Tokens.keyword(text.substring(from, i));
    if (head != Head.ROUTINE) {
      head = head.next(word);
    } else if (word.equals("BEGIN") || word.equals("CASE") && blocks > 0) {
      blocks++;
    } else if (word.equals("END") && blocks > 0) {
      blocks--;
    }
    return true;
  }

  // Takes a line that the client reads as its own at i, where a statement could begin: a
  // DELIMITER, or a command that begins with a backslash; whether there was one.
  private boolean clientLine() {
    int from = i;
    if (syntax.clientDelimiter() && delimiterCommand()) {
      if (parts != null) {
        parts.add(new Part(Part.Kind.DELIMITER, from, i, i, delimiter));
      }
      return true;
    }
    if (backslashCommands && text.charAt(i) == '\\') {
      command();
      return true;
    }
    return false;
  }

  private void finish(int start, int end, int after) {
    if (start >= 0) {
      found.add(new Statement(start, end));
      afters.add(after);
      if (parts != null) {
        parts.add(new Part(Part.Kind.STATEMENT, start, end, after, delimiter));
      }
    }
  }

  // At a backslash where a statement could begin: takes its line as a command of the client, as
  // psql does with its meta-commands, without the line's end.
  private void command() {
    int n = text.length();
    int from = i;
    while (i < n && !lineEnd(text.charAt(i))) {
      i++;
    }
    int end = i;
    if (i < n && text.charAt(i) == '\r') {
      i++;
    }
    if (i < n && text.charAt(i) == '\n') {
      i++;
    }
    parts.add(new Part(Part.Kind.COMMAND, from, end, i, delimiter));
  }

  // Skips what begins at i: a comment, a quoted string or identifier, or one character. Whether
  // that was more than a comment.
  private boolean token() {
    char c = text.charAt(i);
    char next = charAt(i + 1);
    if (c == '-'
        && next == '-'
        && (!syntax.dashCommentNeedsSpace() || controlOrSpace(charAt(i + 2)))) {
      skipLine();
      return false;
    }
    if (c == '/' && next == '*') {
      return skipBlockComment();
    }
    if (c == '#' && syntax.hashComments()) {
      skipLine();
      return false;
    }
    skipCode(c, next);
    return true;
  }

  // Skips a comment that begins with "/*"; whether it is executable, code for the server.
  private boolean skipBlockComment() {
    kind = Piece.Kind.COMMENT;
    if (syntax.nestedComments()) {
      skipNestedComment();
      return false;
    }
    // Executable comments are code for the server, but a terminator in them does not count.
    boolean code = false;
    for (var opening : syntax.executableComments()) {
      code |= text.startsWith(opening, i);
    }
    int close = text.indexOf("*/", i + 2);
    open = close < 0;
    i = close < 0 ? text.length() : close + 2;
    return code;
  }

  // Skips a string, a quoted identifier or one character of code.
  private void skipCode(char c, char next) {
    if (c == '\'' || c == '"' && syntax.doubleQuotedStrings()) {
      kind = Piece.Kind.STRING;
      skipQuoted(c, escapes);
    } else if (syntax.escapeStrings()
        && (c == 'E' || c == 'e')
        && next == '\''
        && !identifierBefore()) {
      kind = Piece.Kind.STRING;
      i++;
      skipQuoted('\'', true);
      // Continued over a line break, as 'b' in E'a'\n'b', it is read by the same rules.
      for (int at = continued(); !open && at > 0; at = continued()) {
        i = at;
        skipQuoted('\'', true);
      }
    } else if (c == syntax.identifierQuote()) {
      kind = Piece.Kind.QUOTED;
      skipQuoted(c, false);
    } else if (!syntax.dollarQuotedStrings()
        || c != '$'
        || identifierBefore()
        || !skipDollarQuoted()) {
      i++;
    }
  }

  // Where the quote of a string's continuation is, after white space with a line break; 0 if none.
  // PostgreSQL takes line comments in that white space too.
  private int continued() {
    boolean broken = false;
    int at = i;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == '-' && charAt(at + 1) == '-') {
        while (at < text.length() && text.charAt(at) != '\n' && text.charAt(at) != '\r') {
          at++;
        }
      } else if (whitespace(c)) {
        broken |= c == '\n' || c == '\r';
        at++;
      } else {
        break;
      }
    }
    return broken && charAt(at) == '\'' ? at : 0;
  }

  private void skipLine() {
    kind = Piece.Kind.COMMENT;
    open = true;
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
    open = true;
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
    open = true;
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
    kind = Piece.Kind.STRING;
    int length = j - i + 1;
    // Each failed comparison stops at a character that is no "$", so the scan stays linear.
    for (int k = text.indexOf('$', j + 1); k >= 0; k = text.indexOf('$', k + 1)) {
      if (text.regionMatches(k, text, i, length)) {
        i = k + length;
        return true;
      }
    }
    open = true;
    i = n;
    return true;
  }

  // At a word DELIMITER where a statement could begin: skips its line and takes its argument as
  // the delimiter, as the clients do; returns false if there is no such word.
  private boolean delimiterCommand() {
    int n = text.length();
    int j = i + "DELIMITER".length();
    if (j > n
        || !Tokens.keyword(text.substring(i, j)).equals("DELIMITER")
        || (j < n && text.charAt(j) != ' ' && text.charAt(j) != '\t' && !lineEnd(text.charAt(j)))) {
      return false;
    }
    takeDelimiter(j);
    return true;
  }

  // Takes the argument of a DELIMITER command from the offset on, and skips its line.
  private void takeDelimiter(int from) {
    int n = text.length();
    int j = from;
    while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
      j++;
    }
    char quote = charAt(j);
    boolean quoted = quote == '\'' || quote == '"' || quote == '`';
    int start = quoted ? j + 1 : j;
    int to = start;
    while (to < n
        && !lineEnd(text.charAt(to))
        && (quoted ? text.charAt(to) != quote : !whitespace(text.charAt(to)))) {
      to++;
    }
    // The clients refuse an empty delimiter and one with a backslash, and keep the old one.
    boolean valid = to > start;
    for (int k = start; k < to; k++) {
      valid &= text.charAt(k) != '\\';
    }
    if (valid) {
      delimiter = text.substring(start, Math.min(to, start + MAX_DELIMITER));
    }
    i = to;
    while (i < n && text.charAt(i) != '\n') {
      i++;
    }
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

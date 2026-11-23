package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.sql.Statements.Piece;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lays out the statements of a text of SQL by the lexical rules of a provider's server: each main
 * clause starts a line, items of {@code SELECT}, {@code SET}, {@code GROUP BY}, {@code ORDER BY}
 * and {@code VALUES} lists go one per line aligned after the clause's keyword, {@code AND}/{@code
 * OR} of a condition start an indented line, subqueries in parentheses are laid out the same way
 * indented, other parentheses, function calls and lists stay on their line, and statements are
 * separated by a blank line.
 *
 * <p>It changes white space alone: every token, keyword case, quoting, string and comment stays as
 * it is written.
 */
public final class Layout {

  /** A string, quoted identifier or block comment that the text ends in without closing it. */
  public static final class UnclosedException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int start;
    private final int end;
    private final Piece.Kind kind;

    UnclosedException(int start, int end, Piece.Kind kind) {
      super("Unclosed " + kind.name().toLowerCase(Locale.ROOT) + " at " + start);
      this.start = start;
      this.end = end;
      this.kind = kind;
    }

    /** Where the unclosed piece begins in the text. */
    public int start() {
      return start;
    }

    /** Where the unclosed piece ends in the text. */
    public int end() {
      return end;
    }

    /** What was left open: {@link Piece.Kind#STRING}, {@code QUOTED} or {@code COMMENT}. */
    public Piece.Kind kind() {
      return kind;
    }
  }

  private enum Kind {
    WORD,
    NUMBER,
    QUOTED,
    STRING,
    COMMENT,
    OPERATOR,
    PUNCTUATION
  }

  private record Token(Kind kind, int start, int end, boolean afterDot, boolean executable) {}

  private enum Clause {
    NONE,
    SELECT,
    FROM,
    JOIN,
    CONDITION,
    GROUP_BY,
    ORDER_BY,
    INSERT_HEAD,
    VALUES,
    SET,
    WITH,
    OTHER
  }

  private static final class Frame {
    final int base;
    final int closeIndent;
    final boolean subquery;
    Clause clause = Clause.NONE;
    int listIndent;
    int parenDepth;
    int bracketDepth;
    int caseDepth;
    boolean inBetween;
    boolean inAlter;
    boolean inDeleteHead;
    boolean inJoinPrefix;
    boolean hadToken;

    Frame(int base, int closeIndent, boolean subquery) {
      this.base = base;
      this.closeIndent = closeIndent;
      this.subquery = subquery;
    }
  }

  private static final Set<String> FOUR_CHAR_OPS = Set.of("!~~*");
  private static final Set<String> THREE_CHAR_OPS =
      Set.of("<=>", "->>", "#>>", "!~*", "!~~", "~~*", "||/");
  private static final Set<String> TWO_CHAR_OPS = Set.of(
      "<>", "!=", "<=", ">=", "||", "&&", "::", "->", "#>", "#-", "@>", "<@", "?&", "?|", "<<",
      ">>", ":=", "=>", "~*", "!~", "~~", "@@", "|/", "^@", "**");

  // Keywords after which '(' takes a leading space (not a function call).
  private static final Set<String> SPACE_BEFORE_PAREN = Set.of(
      "SELECT",
      "FROM",
      "WHERE",
      "AND",
      "OR",
      "NOT",
      "IN",
      "AS",
      "ON",
      "HAVING",
      "WHEN",
      "THEN",
      "ELSE",
      "CASE",
      "BETWEEN",
      "LIKE",
      "ILIKE",
      "IS",
      "INTO",
      "VALUES",
      "SET",
      "USING",
      "EXISTS",
      "ANY",
      "ALL",
      "SOME",
      "OVER",
      "FILTER",
      "WITHIN",
      "DISTINCT",
      "UNION",
      "INTERSECT",
      "EXCEPT",
      "JOIN",
      "STRAIGHT_JOIN",
      "CONFLICT",
      "TABLE",
      "INDEX",
      "CHECK",
      "UNIQUE",
      "KEY",
      "REFERENCES",
      "RETURN",
      "RETURNING",
      "LIMIT",
      "OFFSET",
      "BY",
      "TO",
      "FOR",
      "WITH",
      "DO",
      "OF");

  // Keywords after which '+', '-', '~', '!' is unary (no space between it and its operand).
  private static final Set<String> UNARY_AFTER = Set.of(
      "SELECT",
      "WHERE",
      "AND",
      "OR",
      "NOT",
      "WHEN",
      "THEN",
      "ELSE",
      "CASE",
      "BETWEEN",
      "IN",
      "IS",
      "LIKE",
      "ILIKE",
      "HAVING",
      "ON",
      "AS",
      "BY",
      "SET",
      "VALUES",
      "RETURN",
      "RETURNING",
      "LIMIT",
      "OFFSET",
      "DISTINCT",
      "ALL");

  private static final Set<String> DELETE_MODIFIERS =
      Set.of("DELETE", "LOW_PRIORITY", "QUICK", "IGNORE");
  private static final Set<String> SET_DDL_NEXT =
      Set.of("NULL", "DEFAULT", "NOT", "DATA", "STATISTICS", "STORAGE", "SCHEMA", "TABLESPACE");

  private static final int MAX_INDENT = 80;
  private static final Pattern WHITE = Pattern.compile("\\s+");
  private static final Set<String> QUERY_AFTER_AS = Set.of("SELECT", "WITH", "VALUES");

  private final String text;
  private final SyntaxDialect syntax;
  private final List<Token> tokens;
  private final int[]
      stmtOf; // statement index per token, -1 for non-executable comments/delimiters
  private final boolean[] kept; // tokens on a MySQL/MariaDB DELIMITER command line
  // The first token from k on, and the last up to k, that is no comment; -1 for none. Found once,
  // so that a long run of comments is not walked again for each of them.
  private final int[] codeFrom;
  private final int[] codeUpTo;
  private final StringBuilder out = new StringBuilder();
  private final ArrayDeque<Frame> frames = new ArrayDeque<>();
  private int lastCode = -1;
  private boolean afterStatement;
  private boolean separated;
  private boolean ownLine; // whether the comment written last starts its line

  private Layout(
      String text, SyntaxDialect syntax, List<Token> tokens, List<Statement> statements) {
    this.text = text;
    this.syntax = syntax;
    this.tokens = tokens;
    this.stmtOf = new int[tokens.size()];
    this.kept = new boolean[tokens.size()];
    int n = tokens.size();
    this.codeFrom = new int[n];
    this.codeUpTo = new int[n];
    for (int k = n - 1, code = -1; k >= 0; k--) {
      code = isPlainComment(tokens.get(k)) ? code : k;
      codeFrom[k] = code;
    }
    for (int k = 0, code = -1; k < n; k++) {
      code = isPlainComment(tokens.get(k)) ? code : k;
      codeUpTo[k] = code;
    }
    Arrays.fill(stmtOf, -1);
    mapStatements(statements);
    frames.push(new Frame(0, 0, false));
  }

  /**
   * The text laid out by the provider's lexical rules; throws {@link UnclosedException} where a
   * string, quoted identifier or block comment is not closed under either reading of a backslash.
   */
  public static String of(String text, Provider provider) throws UnclosedException {
    var syntax = provider.dialect().syntax();
    var pieces = Statements.pieces(text, provider);
    var open = unclosed(text, pieces);
    if (open != null) {
      if (unclosed(text, Statements.pieces(text, provider, !syntax.backslashEscapes())) == null) {
        return text; // closed as a server in the other mode reads it, as 'C:\tmp\'
      }
      throw new UnclosedException(open.start(), open.end(), open.kind());
    }
    var tokens = tokenize(text, pieces, syntax);
    if (tokens.isEmpty()) {
      return "";
    }
    var statements = Statements.split(text, provider);
    var laid = new Layout(text, syntax, tokens, statements).write();
    return same(text, tokens, statements, laid, provider, syntax) ? laid : text;
  }

  private static Piece unclosed(String text, List<Piece> pieces) {
    for (var piece : pieces) {
      if (piece.open()
          && (piece.kind() != Piece.Kind.COMMENT || text.startsWith("/*", piece.start()))) {
        return piece;
      }
    }
    return null;
  }

  // Maps every statement token (and its trailing semicolon) to its statement index, and marks any
  // MySQL/MariaDB DELIMITER command line so its spacing stays as written.
  private void mapStatements(List<Statement> statements) {
    int p = 0;
    for (int k = 0; k < tokens.size(); k++) {
      var token = tokens.get(k);
      if (isPlainComment(token)) {
        continue;
      }
      while (p < statements.size() && statements.get(p).end() <= token.start()) {
        p++;
      }
      if (p < statements.size() && token.start() >= statements.get(p).start()) {
        stmtOf[k] = p;
      } else if (p > 0
          && isPunct(k, ';')
          && token.start() >= statements.get(p - 1).end()) {
        stmtOf[k] = p - 1;
      } else if (syntax.clientDelimiter()
          && token.kind() == Kind.WORD
          && keyword(k).equals("DELIMITER")
          && (k == 0 || breaks(k) > 0 || isPunct(k - 1, ';'))) {
        int lineEnd = text.indexOf('\n', token.start());
        if (lineEnd < 0) {
          lineEnd = text.length();
        }
        kept[k] = true;
        while (k + 1 < tokens.size() && tokens.get(k + 1).start() < lineEnd) {
          k++;
          kept[k] = true;
        }
      }
    }
  }

  private static List<Token> tokenize(String text, List<Piece> pieces, SyntaxDialect syntax) {
    var tokens = new ArrayList<Token>();
    int k = 0;
    while (k < pieces.size()) {
      var piece = pieces.get(k);
      int next = k + 1;
      switch (piece.kind()) {
        case COMMENT ->
          tokens.add(new Token(
              Kind.COMMENT,
              piece.start(),
              piece.end(),
              false,
              isExecutable(text, piece.start(), syntax)));
        case STRING -> tokens.add(new Token(Kind.STRING, piece.start(), piece.end(), false, false));
        case QUOTED -> tokens.add(new Token(Kind.QUOTED, piece.start(), piece.end(), false, false));
        case CODE -> next = codeToken(text, pieces, k, syntax, tokens);
      }
      k = next;
    }
    return tokens;
  }

  private static boolean isExecutable(String text, int at, SyntaxDialect syntax) {
    for (var opening : syntax.executableComments()) {
      if (text.startsWith(opening, at)) {
        return true;
      }
    }
    return false;
  }

  private static int codeToken(
      String text, List<Piece> pieces, int k, SyntaxDialect syntax, List<Token> tokens) {
    int start = pieces.get(k).start();
    char c = text.charAt(start);
    if (isDigit(c)) {
      return numberToken(text, pieces, k, tokens);
    }
    if (isWordStart(c, syntax)) {
      int last = k;
      while (last + 1 < pieces.size()
          && adjacentCode(pieces, last)
          && isWordPart(text.charAt(pieces.get(last + 1).start()), syntax)) {
        last++;
      }
      boolean afterDot = afterDot(text, tokens);
      tokens.add(new Token(Kind.WORD, start, pieces.get(last).end(), afterDot, false));
      return last + 1;
    }
    if ("()[]{},;.".indexOf(c) >= 0) {
      tokens.add(new Token(Kind.PUNCTUATION, start, start + 1, false, false));
      return k + 1;
    }
    int opLen = operatorLength(text, pieces, k);
    tokens.add(new Token(Kind.OPERATOR, start, start + opLen, false, false));
    return k + opLen;
  }

  private static int numberToken(String text, List<Piece> pieces, int k, List<Token> tokens) {
    int start = pieces.get(k).start();
    int last = k;
    boolean hex = last + 1 < pieces.size()
        && adjacentCode(pieces, last)
        && text.charAt(start) == '0'
        && (text.charAt(start + 1) == 'x' || text.charAt(start + 1) == 'X');
    while (last + 1 < pieces.size()
        && adjacentCode(pieces, last)
        && isNumberPart(text.charAt(pieces.get(last + 1).start()))) {
      last++;
    }
    if (!hex
        && last + 1 < pieces.size()
        && adjacentCode(pieces, last)
        && text.charAt(pieces.get(last + 1).start()) == '.'
        && !(last + 2 < pieces.size()
            && adjacentCode(pieces, last + 1)
            && text.charAt(pieces.get(last + 2).start()) == '.')) {
      last++;
      while (last + 1 < pieces.size()
          && adjacentCode(pieces, last)
          && isNumberPart(text.charAt(pieces.get(last + 1).start()))) {
        last++;
      }
    }
    // Scientific notation sign: e.g. 1e-3 or 1.5E+2.
    char endChar = text.charAt(pieces.get(last).start());
    if (!hex
        && (endChar == 'e' || endChar == 'E')
        && last + 2 < pieces.size()
        && adjacentCode(pieces, last)
        && adjacentCode(pieces, last + 1)) {
      char sign = text.charAt(pieces.get(last + 1).start());
      char after = text.charAt(pieces.get(last + 2).start());
      if ((sign == '+' || sign == '-') && isDigit(after)) {
        last += 2;
        while (last + 1 < pieces.size()
            && adjacentCode(pieces, last)
            && isNumberPart(text.charAt(pieces.get(last + 1).start()))) {
          last++;
        }
      }
    }
    tokens.add(new Token(Kind.NUMBER, start, pieces.get(last).end(), false, false));
    return last + 1;
  }

  private static int operatorLength(String text, List<Piece> pieces, int k) {
    int start = pieces.get(k).start();
    int max = 1;
    while (max < 4 && k + max < pieces.size() && adjacentCode(pieces, k + max - 1)) {
      max++;
    }
    if (max >= 4 && FOUR_CHAR_OPS.contains(text.substring(start, start + 4))) {
      return 4;
    }
    if (max >= 3 && THREE_CHAR_OPS.contains(text.substring(start, start + 3))) {
      return 3;
    }
    if (max >= 2 && TWO_CHAR_OPS.contains(text.substring(start, start + 2))) {
      return 2;
    }
    return 1;
  }

  private static boolean adjacentCode(List<Piece> pieces, int k) {
    return pieces.get(k + 1).kind() == Piece.Kind.CODE
        && pieces.get(k + 1).start() == pieces.get(k).end();
  }

  private static boolean afterDot(String text, List<Token> tokens) {
    for (int i = tokens.size() - 1; i >= 0; i--) {
      var token = tokens.get(i);
      if (isPlainComment(token)) {
        continue;
      }
      return token.kind() == Kind.PUNCTUATION && text.charAt(token.start()) == '.';
    }
    return false;
  }

  // Whether the laid-out text has the same strings, names and comments under either reading of a
  // backslash, as the server's mode may choose, the same tokens, and the same statements.
  private static boolean same(
      String text,
      List<Token> tokens,
      List<Statement> statements,
      String laid,
      Provider provider,
      SyntaxDialect syntax) {
    for (boolean escapes : new boolean[] {true, false}) {
      if (!samePieces(
          text,
          Statements.pieces(text, provider, escapes),
          laid,
          Statements.pieces(laid, provider, escapes))) {
        return false;
      }
    }
    var afterTokens = tokenize(laid, Statements.pieces(laid, provider), syntax);
    if (afterTokens.size() != tokens.size()) {
      return false;
    }
    for (int k = 0; k < tokens.size(); k++) {
      var a = tokens.get(k);
      var b = afterTokens.get(k);
      if (a.kind() != b.kind()
          || !text.substring(a.start(), a.end()).equals(laid.substring(b.start(), b.end()))) {
        return false;
      }
    }
    return code(text, statements).equals(code(laid, Statements.split(laid, provider)));
  }

  private static boolean samePieces(
      String text, List<Piece> pieces, String laid, List<Piece> after) {
    if (after.size() != pieces.size()) {
      return false;
    }
    for (int k = 0; k < pieces.size(); k++) {
      var a = pieces.get(k);
      var b = after.get(k);
      if (a.kind() != b.kind()
          || a.open() != b.open()
          || !text.substring(a.start(), a.end()).equals(laid.substring(b.start(), b.end()))) {
        return false;
      }
    }
    return true;
  }

  // Each statement's text without its white space.
  private static List<String> code(String text, List<Statement> statements) {
    return statements.stream()
        .map(st -> WHITE.matcher(text.substring(st.start(), st.end())).replaceAll(""))
        .toList();
  }

  private String write() {
    for (int k = 0; k < tokens.size(); k++) {
      var token = tokens.get(k);
      boolean comment = isPlainComment(token);
      if (k > 0) {
        out.append(before(k, comment));
      }
      if (!comment && !kept[k]) {
        beforeAppend(k);
      }
      out.append(text, token.start(), token.end());
      if (!comment) {
        if (!kept[k]) {
          afterAppend(k);
        } else if (k + 1 == tokens.size() || !kept[k + 1]) {
          // End of a DELIMITER line: start the next statement fresh.
          resetFrames();
          afterStatement = true;
          separated = false;
        }
        lastCode = k;
      }
    }
    return out.toString();
  }

  private String before(int k, boolean comment) {
    if (kept[k]) {
      ownLine = false;
      if (kept[k - 1]) {
        return text.substring(tokens.get(k - 1).end(), tokens.get(k).start());
      }
      return afterStatement ? "\n\n" : "\n";
    }
    if (frames.peek().bracketDepth > 0) {
      // As written, comments too: MariaDB may quote a name in [ ], as [gross  /* x */ net].
      ownLine = false;
      return text.substring(tokens.get(k - 1).end(), tokens.get(k).start());
    }
    var gap = comment ? commentGap(k) : codeGap(k);
    if (isLineComment(k - 1) && gap.indexOf('\n') < 0) {
      gap = indent(comment ? commentIndent(k) : continuationIndent());
    } else if (!comment && ownLine && breaks(k) == 0 && gap.indexOf('\n') >= 0) {
      gap = " "; // after a block comment that starts its line, as it stood before the code
    }
    ownLine = comment && gap.indexOf('\n') >= 0;
    return gap;
  }

  private String commentGap(int k) {
    int b = breaks(k);
    if (b == 0) {
      return " ";
    }
    if (afterStatement) {
      int next = nextCode(k);
      if (next >= 0) {
        afterStatement = false;
        separated = true;
        return "\n\n";
      }
      return b > 1 ? "\n\n" : "\n";
    }
    if (b > 1 && frames.size() == 1 && !frames.peek().hadToken) {
      return "\n\n";
    }
    return indent(commentIndent(k));
  }

  // Indentation for an own-line comment, matching what follows it.
  private int commentIndent(int k) {
    int next = nextCode(k);
    if (next < 0) {
      return frames.peek().base;
    }
    var frame = frames.peek();
    if (lastCode >= 0 && startsNewStatement(next)) {
      return 0;
    }
    if (frame.parenDepth == 0 && frame.bracketDepth == 0) {
      if (isPunct(next, ')') && frame.subquery) {
        return frame.base;
      }
      if (startsMainClause(next, frame)) {
        return frame.base;
      }
      if (startsConditionOp(next, frame)) {
        return frame.base + 2;
      }
      if (lastCode >= 0 && isPunct(lastCode, ',') && isListClause(frame.clause)) {
        return frame.listIndent;
      }
    }
    return frame.base;
  }

  // Fallback indentation when a line comment forces the next code token onto a new line.
  private int continuationIndent() {
    var frame = frames.peek();
    if (frame.parenDepth == 0 && frame.bracketDepth == 0 && isListClause(frame.clause)) {
      return frame.listIndent;
    }
    return frame.base + 2;
  }

  private String codeGap(int k) {
    if (lastCode == k - 1 && isString(lastCode) && isString(k)) {
      // PostgreSQL joins 'a' and 'b' into one string only over a line break between them.
      return text.substring(tokens.get(lastCode).end(), tokens.get(k).start());
    }
    if (lastCode < 0) {
      // First code token after leading comment(s).
      int b = breaks(k);
      if (b > 1) {
        return "\n\n";
      }
      return b > 0 ? "\n" : " ";
    }
    if (startsNewStatement(k)) {
      resetFrames();
      boolean alreadySeparated = separated;
      afterStatement = false;
      separated = false;
      if (alreadySeparated) {
        return breaks(k) > 1 ? "\n\n" : "\n";
      }
      return "\n\n";
    }
    var frame = frames.peek();
    if (frame.subquery && !frame.hadToken) {
      return indent(frame.base);
    }
    if (isPunct(k, ')') && frame.parenDepth == 0 && frame.subquery) {
      return indent(frame.closeIndent);
    }
    if (isPunct(k, ';')) {
      return "";
    }
    if (frame.parenDepth == 0 && frame.bracketDepth == 0) {
      if (isPunct(lastCode, ',') && isListClause(frame.clause)) {
        return indent(frame.listIndent);
      }
      if (startsMainClause(k, frame)) {
        return indent(frame.base);
      }
      if (startsConditionOp(k, frame)) {
        return indent(frame.base + 2);
      }
    }
    return inline(lastCode, k, frame);
  }

  private boolean startsNewStatement(int k) {
    if (kept[lastCode]) {
      return true;
    }
    if (isPunct(k, ';')) {
      return false;
    }
    int prevStmt = stmtOf[lastCode];
    int currStmt = stmtOf[k];
    if (prevStmt >= 0 && currStmt >= 0 && prevStmt != currStmt) {
      return true;
    }
    return isPunct(lastCode, ';') && !isPunct(k, ';');
  }

  private void beforeAppend(int k) {
    var frame = frames.peek();
    if (isPunct(k, ')')) {
      if (frame.parenDepth > 0) {
        frame.parenDepth--;
      } else if (frame.subquery && frames.size() > 1) {
        frames.pop();
      }
    } else if (isPunct(k, ']') && frame.bracketDepth > 0) {
      frame.bracketDepth--;
    }
  }

  private void afterAppend(int k) {
    var token = tokens.get(k);
    var frame = frames.peek();
    if (isPunct(k, ';') && frames.size() == 1 && frame.parenDepth == 0) {
      afterStatement = true;
      separated = false;
      return;
    }
    boolean first = !frame.hadToken;
    frame.hadToken = true;
    if (isPunct(k, '(')) {
      if (opensSubquery(k)) {
        int lineIndent = currentLineIndent();
        frames.push(new Frame(lineIndent + 2, lineIndent, true));
      } else {
        frame.parenDepth++;
      }
      return;
    }
    if (isPunct(k, '[')) {
      frame.bracketDepth++;
      return;
    }
    if (token.kind() != Kind.WORD
        || token.afterDot()
        || frame.parenDepth > 0
        || frame.bracketDepth > 0) {
      return;
    }
    var w = keyword(k);
    if (isAlias(k)) {
      return;
    }
    if (w.equals("CASE")) {
      frame.caseDepth++;
      return;
    }
    if (w.equals("END")) {
      if (frame.caseDepth > 0) {
        frame.caseDepth--;
      }
      return;
    }
    if (frame.caseDepth > 0) {
      return;
    }
    if (w.equals("BETWEEN")) {
      frame.inBetween = true;
      return;
    }
    if (w.equals("AND") && frame.inBetween) {
      frame.inBetween = false;
      return;
    }
    updateClause(k, w, first, frame);
  }

  private void updateClause(int k, String w, boolean first, Frame frame) {
    if (first) {
      if (w.equals("ALTER")) {
        frame.inAlter = true;
      } else if (w.equals("DELETE")) {
        frame.inDeleteHead = true;
        frame.clause = Clause.OTHER;
        return;
      } else if (w.equals("INSERT") || w.equals("REPLACE")) {
        frame.clause = Clause.INSERT_HEAD;
        return;
      } else if (w.equals("WITH")) {
        frame.clause = Clause.WITH;
        frame.listIndent = frame.base;
        return;
      }
    }
    if (frame.inDeleteHead && !DELETE_MODIFIERS.contains(w) && !w.equals("FROM")) {
      frame.inDeleteHead = false;
    }
    switch (w) {
      case "SELECT" -> {
        frame.clause = Clause.SELECT;
        frame.listIndent = frame.base + "SELECT ".length();
        frame.inBetween = false;
      }
      case "DISTINCT" -> {
        if (frame.clause == Clause.SELECT
            && lastCode >= 0
            && isWord(lastCode, "SELECT")
            && !isWord(nextCode(k), "ON")) {
          frame.listIndent = frame.base + "SELECT DISTINCT ".length();
        }
      }
      case "FROM" -> {
        if (frame.inDeleteHead) {
          frame.inDeleteHead = false;
          frame.clause = Clause.FROM;
        } else if (!isDistinctFrom(k)) {
          frame.clause = Clause.FROM;
          frame.inBetween = false;
        }
      }
      case "JOIN", "STRAIGHT_JOIN" -> {
        frame.inJoinPrefix = false;
        frame.clause = Clause.JOIN;
        frame.inBetween = false;
      }
      case "INNER", "CROSS", "LEFT", "RIGHT", "FULL", "NATURAL" -> {
        if (!frame.inJoinPrefix && startsJoin(k)) {
          frame.inJoinPrefix = true;
          frame.clause = Clause.JOIN;
          frame.inBetween = false;
        }
      }
      case "ON" -> {
        if (frame.clause == Clause.JOIN) {
          frame.clause = Clause.CONDITION;
          frame.inBetween = false;
        } else if (isWord(nextCode(k), "CONFLICT") || isWord(nextCode(k), "DUPLICATE")) {
          frame.clause = Clause.OTHER;
          frame.inBetween = false;
        }
      }
      case "WHERE", "HAVING" -> {
        frame.clause = Clause.CONDITION;
        frame.inBetween = false;
      }
      case "GROUP" -> {
        if (isWord(nextCode(k), "BY")) {
          frame.clause = Clause.GROUP_BY;
          frame.listIndent = frame.base + "GROUP BY ".length();
          frame.inBetween = false;
        }
      }
      case "ORDER" -> {
        if (isWord(nextCode(k), "BY")) {
          frame.clause = Clause.ORDER_BY;
          frame.listIndent = frame.base + "ORDER BY ".length();
          frame.inBetween = false;
        }
      }
      case "SET" -> {
        if (isSetClause(k, frame)) {
          frame.clause = Clause.SET;
          frame.listIndent = frame.base + "SET ".length();
          frame.inBetween = false;
        }
      }
      case "VALUES" -> {
        if (isValuesClause(k, frame)) {
          frame.clause = Clause.VALUES;
          frame.listIndent = frame.base + "VALUES ".length();
          frame.inBetween = false;
        }
      }
      case "INSERT", "REPLACE" -> {
        if (lastCode >= 0 && isPunct(lastCode, ')')) {
          frame.clause = Clause.INSERT_HEAD;
          frame.inBetween = false;
        }
      }
      case "DELETE" -> {
        if (lastCode >= 0 && isPunct(lastCode, ')')) {
          frame.inDeleteHead = true;
          frame.clause = Clause.OTHER;
          frame.inBetween = false;
        }
      }
      case "LIMIT",
          "OFFSET",
          "FETCH",
          "FOR",
          "RETURNING",
          "UNION",
          "INTERSECT",
          "EXCEPT",
          "WINDOW",
          "UPDATE" -> {
        if (w.equals("FETCH") && !(isWord(nextCode(k), "FIRST") || isWord(nextCode(k), "NEXT"))) {
          return;
        }
        if (w.equals("FOR") && !isLockingFor(k)) {
          return;
        }
        if (w.equals("UPDATE") && !isUpdateClause()) {
          return;
        }
        frame.clause = Clause.OTHER;
        frame.inBetween = false;
      }
      default -> {
        // stay in current clause
      }
    }
  }

  private boolean startsMainClause(int k, Frame frame) {
    if (!frame.hadToken || frame.caseDepth > 0) {
      return false;
    }
    var token = tokens.get(k);
    if (token.kind() != Kind.WORD || token.afterDot() || isAlias(k)) {
      return false;
    }
    var w = keyword(k);
    return switch (w) {
      case "SELECT",
          "WHERE",
          "HAVING",
          "LIMIT",
          "OFFSET",
          "RETURNING",
          "UNION",
          "INTERSECT",
          "EXCEPT",
          "WINDOW" -> true;
      case "FROM" -> !frame.inDeleteHead && !isDistinctFrom(k);
      case "GROUP", "ORDER" -> isWord(nextCode(k), "BY");
      case "FETCH" -> isWord(nextCode(k), "FIRST") || isWord(nextCode(k), "NEXT");
      case "FOR" -> isLockingFor(k);
      case "SET" -> isSetClause(k, frame);
      case "VALUES" -> isValuesClause(k, frame);
      case "ON" -> isWord(nextCode(k), "CONFLICT") || isWord(nextCode(k), "DUPLICATE");
      case "USING" -> frame.clause == Clause.FROM && nextCode(k) >= 0 && !isPunct(nextCode(k), '(');
      case "INSERT", "REPLACE", "DELETE" -> lastCode >= 0 && isPunct(lastCode, ')');
      case "UPDATE" -> isUpdateClause();
      case "JOIN", "STRAIGHT_JOIN", "INNER", "CROSS", "LEFT", "RIGHT", "FULL", "NATURAL" ->
        !frame.inJoinPrefix && startsJoin(k);
      default -> false;
    };
  }

  // A word after AS names something, as in SELECT 1 AS limit, but for CREATE VIEW v AS SELECT.
  private boolean isAlias(int k) {
    return isWord(prevCode(k), "AS") && !QUERY_AFTER_AS.contains(keyword(k));
  }

  private boolean startsConditionOp(int k, Frame frame) {
    if (frame.clause != Clause.CONDITION || frame.caseDepth > 0) {
      return false;
    }
    var token = tokens.get(k);
    if (token.kind() != Kind.WORD || token.afterDot()) {
      return false;
    }
    var w = keyword(k);
    return w.equals("OR") || w.equals("AND") && !frame.inBetween;
  }

  private boolean startsJoin(int k) {
    var w = keyword(k);
    if (w.equals("JOIN") || w.equals("STRAIGHT_JOIN")) {
      return true;
    }
    int next = nextCode(k);
    if (next < 0) {
      return false;
    }
    var nw = keyword(next);
    if (w.equals("INNER") || w.equals("CROSS")) {
      return nw.equals("JOIN");
    }
    if (w.equals("LEFT") || w.equals("RIGHT") || w.equals("FULL")) {
      return nw.equals("JOIN") || nw.equals("OUTER") && isWord(nextCode(next), "JOIN");
    }
    if (w.equals("NATURAL")) {
      return nw.equals("JOIN") || !nw.equals("NATURAL") && startsJoin(next);
    }
    return false;
  }

  private boolean isDistinctFrom(int k) {
    int prev = prevCode(k);
    if (prev < 0 || !isWord(prev, "DISTINCT")) {
      return false;
    }
    int before = prevCode(prev);
    return isWord(before, "IS") || isWord(before, "NOT") && isWord(prevCode(before), "IS");
  }

  private boolean isLockingFor(int k) {
    int next = nextCode(k);
    return isWord(next, "UPDATE")
        || isWord(next, "SHARE")
        || isWord(next, "NO")
        || isWord(next, "KEY");
  }

  private boolean isSetClause(int k, Frame frame) {
    if (frame.inAlter) {
      return false;
    }
    int prev = prevCode(k);
    if (isWord(prev, "CHARACTER") || isWord(prev, "CHAR")) {
      return false;
    }
    int next = nextCode(k);
    return next < 0
        || !SET_DDL_NEXT.contains(keyword(next))
        || nextCode(next) >= 0 && isOp(nextCode(next), "="); // as SET data = 1
  }

  private boolean isValuesClause(int k, Frame frame) {
    if (frame.clause == Clause.SET
        || frame.clause == Clause.CONDITION
        || frame.clause == Clause.SELECT) {
      return false;
    }
    int prev = prevCode(k);
    if (prev < 0) {
      return false;
    }
    return tokens.get(prev).kind() != Kind.OPERATOR
        && !isPunct(prev, ',')
        && !isPunct(prev, '(')
        && !isWord(prev, "DEFAULT");
  }

  private boolean isUpdateClause() {
    return lastCode >= 0 && isPunct(lastCode, ')');
  }

  private boolean opensSubquery(int k) {
    int next = nextCode(k);
    while (next >= 0 && isPunct(next, '(')) {
      next = nextCode(next);
    }
    return next >= 0 && (isWord(next, "SELECT") || isWord(next, "WITH"));
  }

  private String inline(int a, int k, Frame frame) {
    var prev = tokens.get(a);
    var curr = tokens.get(k);
    if (isPunct(k, '.')) {
      // Kept apart where written so, as in SELECT .5, which MySQL reads as one name joined.
      return prev.end() < curr.start() ? " " : "";
    }
    if (isPunct(k, ',') || isPunct(k, ';') || isPunct(k, ')') || isPunct(k, ']') || isOp(k, "::")) {
      return "";
    }
    if (a == k - 1 && (isPunct(a, '.') || isOp(a, "\\") || isOp(k, "\\"))) {
      // As written: MySQL's @a. AS b names @a., and MariaDB's \N is NULL.
      return text.substring(prev.end(), curr.start());
    }
    if (isPunct(a, '(') || isPunct(a, '[') || isOp(a, "::")) {
      return "";
    }
    if (prev.kind() == Kind.OPERATOR && curr.kind() == Kind.OPERATOR) {
      return prev.end() < curr.start() || signAfter(a, k) ? " " : "";
    }
    if (isPunct(k, '[')) {
      boolean subscript = prev.kind() == Kind.WORD
          || prev.kind() == Kind.QUOTED
          || isPunct(a, ')')
          || isPunct(a, ']');
      return subscript && prev.end() == curr.start() ? "" : " ";
    }
    // A word or U& written right before a quote prefixes it, as N'x', _utf8mb4'x', @'v' or U&"x".
    boolean touching = prev.end() == curr.start();
    if (touching
        && (curr.kind() == Kind.STRING || curr.kind() == Kind.QUOTED)
        && (prev.kind() == Kind.WORD || isOp(a, "&"))) {
      return "";
    }
    if (touching && isOp(k, "&") && keyword(a).equals("U")) {
      return "";
    }
    if (isPunct(k, '(')) {
      // MySQL reads a name with a space before its ( as no built-in function, as count (i INT).
      boolean spaced = prev.kind() == Kind.WORD && !touching;
      return spaced || spaceBeforeParen(a, frame) ? " " : "";
    }
    if (frame.bracketDepth > 0 && (isOp(a, ":") || isOp(k, ":"))) {
      return "";
    }
    // Preserve bind parameters (:name) when written without space.
    if (isOp(a, ":") && curr.kind() == Kind.WORD && prev.end() == curr.start()) {
      return "";
    }
    // Unary +, -, ~, !: no space between the operator and its operand.
    if (isUnaryOp(a)) {
      return "";
    }
    return " ";
  }

  private boolean spaceBeforeParen(int a, Frame frame) {
    var prev = tokens.get(a);
    if (prev.kind() == Kind.OPERATOR || isPunct(a, ',') || isPunct(a, ';')) {
      return true;
    }
    if (prev.kind() == Kind.WORD) {
      if (!prev.afterDot() && SPACE_BEFORE_PAREN.contains(keyword(a))) {
        return true;
      }
      if (frame.clause == Clause.INSERT_HEAD && frame.parenDepth == 0) {
        return true;
      }
    }
    return prev.kind() == Kind.QUOTED
        && frame.clause == Clause.INSERT_HEAD
        && frame.parenDepth == 0;
  }

  // Whether operator k, written right after operator a, is a sign the server reads apart from it,
  // as in <=-5; operators written together otherwise may be one it knows, as PostgreSQL's <->, and
  // stay together. PostgreSQL ends an operator before a last + or - unless it has one of
  // ~!@#%^&|`?.
  private boolean signAfter(int a, int k) {
    int next = k + 1;
    boolean last = next == tokens.size()
        || tokens.get(next).kind() != Kind.OPERATOR
        || tokens.get(next).start() > tokens.get(k).end();
    if (!last || !isOp(k, "-") && !isOp(k, "+")) {
      return false;
    }
    int first = a;
    while (first > 0
        && tokens.get(first - 1).kind() == Kind.OPERATOR
        && tokens.get(first - 1).end() == tokens.get(first).start()) {
      first--;
    }
    return text.substring(tokens.get(first).start(), tokens.get(a).end())
        .chars()
        .allMatch(c -> "+-*/<>=".indexOf(c) >= 0);
  }

  private boolean isUnaryOp(int a) {
    if (!isOp(a, "-") && !isOp(a, "+") && !isOp(a, "~") && !isOp(a, "!")) {
      return false;
    }
    int before = prevCode(a);
    if (before < 0) {
      return true;
    }
    var prev = tokens.get(before);
    if (prev.kind() == Kind.OPERATOR
        || isPunct(before, '(')
        || isPunct(before, '[')
        || isPunct(before, ',')
        || isPunct(before, ';')) {
      return true;
    }
    return prev.kind() == Kind.WORD && !prev.afterDot() && UNARY_AFTER.contains(keyword(before));
  }

  private void resetFrames() {
    frames.clear();
    frames.push(new Frame(0, 0, false));
  }

  private int currentLineIndent() {
    int lineStart = out.lastIndexOf("\n") + 1;
    int spaces = 0;
    while (lineStart + spaces < out.length() && out.charAt(lineStart + spaces) == ' ') {
      spaces++;
    }
    return spaces;
  }

  private int nextCode(int k) {
    return k + 1 < tokens.size() ? codeFrom[k + 1] : -1;
  }

  private int prevCode(int k) {
    return k > 0 ? codeUpTo[k - 1] : -1;
  }

  private int breaks(int k) {
    int from = k == 0 ? 0 : tokens.get(k - 1).end();
    int to = tokens.get(k).start();
    int count = 0;
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r' && (i + 1 >= to || text.charAt(i + 1) != '\n')) {
        count++;
      }
    }
    return count;
  }

  private boolean isLineComment(int k) {
    var token = tokens.get(k);
    return token.kind() == Kind.COMMENT && !text.startsWith("/*", token.start());
  }

  private boolean isString(int k) {
    return tokens.get(k).kind() == Kind.STRING;
  }

  private static boolean isPlainComment(Token token) {
    return token.kind() == Kind.COMMENT && !token.executable();
  }

  private static boolean isListClause(Clause clause) {
    return clause == Clause.SELECT
        || clause == Clause.SET
        || clause == Clause.GROUP_BY
        || clause == Clause.ORDER_BY
        || clause == Clause.VALUES
        || clause == Clause.WITH;
  }

  private boolean isPunct(int k, char c) {
    var token = tokens.get(k);
    return token.kind() == Kind.PUNCTUATION && text.charAt(token.start()) == c;
  }

  private boolean isOp(int k, String op) {
    var token = tokens.get(k);
    return token.kind() == Kind.OPERATOR
        && token.end() - token.start() == op.length()
        && text.regionMatches(token.start(), op, 0, op.length());
  }

  private boolean isWord(int k, String upper) {
    return k >= 0
        && tokens.get(k).kind() == Kind.WORD
        && !tokens.get(k).afterDot()
        && keyword(k).equals(upper);
  }

  private String keyword(int k) {
    var token = tokens.get(k);
    return token.kind() == Kind.WORD
        ? Tokens.keyword(text.substring(token.start(), token.end()))
        : "";
  }

  private static String indent(int spaces) {
    // Capped, so that deep nesting does not grow the text by the square of its depth.
    return "\n" + " ".repeat(Math.clamp(spaces, 0, MAX_INDENT));
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isNumberPart(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isWordStart(char c, SyntaxDialect syntax) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || c == '_'
        || c == '$'
        || c >= 0x80
        || c == '@' && syntax.userVariables();
  }

  private static boolean isWordPart(char c, SyntaxDialect syntax) {
    return isWordStart(c, syntax) || isDigit(c);
  }
}

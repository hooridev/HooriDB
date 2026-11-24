package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.sql.Statements.Piece;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a caret in a statement asks for, and the tables the statement names, read from its words
 * by the lexical rules of a provider's server, without a parser.
 *
 * @param asked what kind of name fits at the caret
 * @param path for {@link Asked#MEMBER}, the names before the dot, such as {@code [public]} after
 *     {@code public.} or {@code [o]} after {@code o.}; else empty
 * @param typed the part of the word before the caret, as written
 * @param tables the tables the statement names, also after the caret, but not the word typed
 *     where a JOIN asks for a table
 * @param left the tables of the FROM item the caret is in, named before its word: those a JOIN
 *     there joins to, the last of them the one an ON there joins; not one whose column alias list
 *     renames its columns, nor one in a parenthesized join that carries an alias
 * @param parenthesized the table right after the opening parenthesis of each parenthesized FROM
 *     item and the alias of each aliased one (with null {@code schema} and {@code name}), kept
 *     out of {@code tables} so that only alias generation sees them
 */
// shortcut: no parser, so a statement with a CTE or a subquery is not read: it names no tables,
// and nothing follows a dot in it; parse statements once completion should reach into them.
public record Context(
    Asked asked,
    List<String> path,
    String typed,
    List<Reference> tables,
    List<Reference> left,
    List<Reference> parenthesized) {

  /** What kind of name fits at the caret. */
  public enum Asked {
    /**
     * None: the caret is in a string, a comment or a quoted name, or after a dot in a statement
     * with a CTE or a subquery, whose names it cannot tell apart.
     */
    NOTHING,
    /** A keyword, a schema, a table or a column. */
    ANY,
    /**
     * A table or a schema: after {@code FROM}, {@code INTO}, {@code UPDATE}, {@code CROSS JOIN},
     * or {@code NATURAL […] JOIN}.
     */
    TABLE,
    /**
     * A table after a conditional {@code JOIN}, one that takes an {@code ON} condition, which has
     * none yet.
     */
    JOIN,
    /** Right after the {@code ON} of a conditional {@code JOIN}, where no condition follows. */
    ON,
    /** What is in the thing the path names: a schema's tables, or a table's or alias's columns. */
    MEMBER
  }

  /**
   * A table the statement names after {@code FROM}, {@code JOIN}, {@code INTO} or {@code UPDATE},
   * with its schema and alias where it has them, else null; in {@link Context#parenthesized()}, a
   * parenthesized group's alias has null {@code schema} and {@code name}. Names are as the server
   * takes them: a quoted one as quoted, an unquoted one on PostgreSQL in lower case.
   */
  public record Reference(String schema, String name, String alias) {}

  /**
   * The identifier at an offset in a statement, with any dot-separated qualifiers before it,
   * whether it is quoted, whether it stands where a table is named, whether a dot follows it, and
   * the tables the statement names.
   */
  public record Symbol(
      List<String> path,
      String name,
      boolean quoted,
      boolean table,
      boolean qualifier,
      List<Reference> tables) {}

  private static final String SELECT = "SELECT";
  // The words that end a table's name in a FROM list, so that they are not taken for its alias.
  private static final Set<String> CLAUSES = Set.of(
      "WHERE",
      "JOIN",
      "INNER",
      "LEFT",
      "RIGHT",
      "FULL",
      "CROSS",
      "NATURAL",
      "OUTER",
      "ON",
      "USING",
      "GROUP",
      "ORDER",
      "HAVING",
      "LIMIT",
      "OFFSET",
      "UNION",
      "EXCEPT",
      "INTERSECT",
      "WINDOW",
      "FOR",
      "SET",
      "VALUES",
      "RETURNING",
      "LATERAL",
      "STRAIGHT_JOIN",
      "FETCH",
      SELECT,
      "PARTITION",
      "TABLESAMPLE",
      "DEFAULT",
      "LOCK",
      "INTO",
      "WITH",
      "AS");
  // Top-level words that end a FROM clause, so commas after them (as in GROUP BY or ORDER BY)
  // are not read as FROM-list items; only where the server reserves them, as a column may be named
  // LOCK, or on MySQL OFFSET or RETURNING.
  private static final Set<String> FROM_ENDERS = Set.of(
      "WHERE",
      "GROUP",
      "ORDER",
      "HAVING",
      "LIMIT",
      "UNION",
      "EXCEPT",
      "INTERSECT",
      "WINDOW",
      "FOR",
      "RETURNING",
      "FETCH",
      SELECT,
      "INTO");
  private static final Set<String> INTRODUCERS = Set.of("FROM", "JOIN", "INTO", "UPDATE");
  private static final Context NOTHING =
      new Context(Asked.NOTHING, List.of(), "", List.of(), List.of(), List.of());

  /**
   * What the caret, an offset in the statement's text, asks for. The text may hold comments
   * around the statement, and may be cut anywhere, as while it is typed.
   */
  public static Context at(String statement, Provider provider, int caret) {
    return at(statement, provider.dialect().syntax(), caret);
  }

  /** As {@link #at(String, Provider, int)}, by the lexical rules of {@code syntax}. */
  public static Context at(String statement, SyntaxDialect syntax, int caret) {
    int at = Math.clamp(caret, 0, statement.length());
    var pieces = Statements.pieces(statement, syntax);
    if (pieces.stream().anyMatch(piece -> piece.kind() != Piece.Kind.CODE && piece.holds(at))) {
      return NOTHING;
    }
    var tokens = tokens(statement, pieces, syntax);
    boolean readable = readable(tokens);
    int from = wordStart(statement, at);
    var typed = statement.substring(from, at);
    // The tokens before the word that is typed.
    int n = 0;
    while (n < tokens.size() && tokens.get(n).end() <= from) {
      n++;
    }
    int cursor = n;
    var path = new ArrayList<String>();
    while (n >= 2 && tokens.get(n - 1).kind() == Kind.DOT && tokens.get(n - 2).name()) {
      path.addFirst(tokens.get(n - 2).identifier());
      n -= 2;
    }
    int skip =
        path.isEmpty() && !typed.isEmpty() && asked(tokens, n, false) == Asked.JOIN ? cursor : -1;
    var scan = readable ? scan(tokens, syntax.reservedWords(), null, cursor, skip) : Scan.EMPTY;
    if (!path.isEmpty()) {
      return readable
          ? new Context(
              Asked.MEMBER, List.copyOf(path), typed, scan.tables, scan.left, scan.parenthesized)
          : NOTHING;
    }
    var asked = asked(tokens, n, scan.joinOn && free(statement, tokens, at));
    return new Context(asked, List.of(), typed, scan.tables, scan.left, scan.parenthesized);
  }

  // What the word after the n tokens asks for, where no dot comes before it.
  private static Asked asked(List<Token> tokens, int n, boolean on) {
    if (n > 0 && introduces(tokens, n - 1)) {
      return conditionalJoin(tokens, n - 1) && !conditioned(tokens, n) ? Asked.JOIN : Asked.TABLE;
    }
    return on ? Asked.ON : Asked.ANY;
  }

  // Whether the table named at the index already has its alias, ON or USING, as while one is
  // replaced.
  private static boolean conditioned(List<Token> tokens, int i) {
    if (condition(tokens, i)) {
      return true;
    }
    if (i >= tokens.size()
        || !tokens.get(i).name()
        || tokens.get(i).kind() == Kind.WORD && CLAUSES.contains(tokens.get(i).upper())) {
      return false;
    }
    var parsed = reference(tokens, i, null);
    return parsed != null
        && (parsed.reference().alias() != null || condition(tokens, parsed.end()));
  }

  // Whether the token begins a join's condition.
  private static boolean condition(List<Token> tokens, int i) {
    return word(tokens, i, "ON") || word(tokens, i, "USING");
  }

  // Whether no condition follows the caret: nothing, a clause (not the function LEFT() or
  // RIGHT()), a comma, a closing parenthesis or the statement's end; and no word goes on from the
  // caret.
  private static boolean free(String statement, List<Token> tokens, int at) {
    if (at < statement.length() && word(statement.codePointAt(at))) {
      return false;
    }
    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);
      if (token.start() >= at) {
        boolean call = (word(tokens, i, "LEFT") || word(tokens, i, "RIGHT"))
            && i + 1 < tokens.size()
            && tokens.get(i + 1).kind() == Kind.OPEN;
        boolean clause = token.kind() == Kind.WORD && CLAUSES.contains(token.upper()) && !call;
        return clause
            || token.kind() == Kind.COMMA
            || token.text().equals(")")
            || token.text().equals(";");
      }
    }
    return true;
  }

  // Where the word that ends at the offset begins.
  private static int wordStart(String statement, int at) {
    int from = at;
    while (from > 0 && word(statement.codePointBefore(from))) {
      from -= Character.charCount(statement.codePointBefore(from));
    }
    return from;
  }

  /** The tables the statement names, in order; none in a statement with a CTE or a subquery. */
  public static List<Reference> tables(String statement, Provider provider) {
    var syntax = provider.dialect().syntax();
    var tokens = tokens(statement, Statements.pieces(statement, provider), syntax);
    return readable(tokens) ? scan(tokens, syntax.reservedWords(), null, -1, -1).tables : List.of();
  }

  /**
   * The identifier at the character {@code offset} in the statement, or empty where the offset
   * lies outside an identifier (such as in whitespace, punctuation, a number, a comment or a
   * string).
   */
  public static Optional<Symbol> symbol(String statement, Provider provider, int offset) {
    if (offset < 0 || offset >= statement.length()) {
      return Optional.empty();
    }
    var pieces = Statements.pieces(statement, provider);
    var tokens = tokens(statement, pieces, provider.dialect().syntax());
    int index = tokenAt(tokens, offset);
    if (index < 0) {
      return Optional.empty();
    }
    var token = tokens.get(index);
    if (!token.name()
        || token.kind() == Kind.WORD && Character.isDigit(token.text().charAt(0))) {
      return Optional.empty();
    }
    boolean readable = readable(tokens);
    var tableTokens = new HashSet<Integer>();
    var reserved = provider.dialect().syntax().reservedWords();
    var tables =
        readable ? scan(tokens, reserved, tableTokens, -1, -1).tables : List.<Reference>of();
    var path = new ArrayList<String>();
    int left = index;
    while (left >= 2
        && tokens.get(left - 1).kind() == Kind.DOT
        && tokens.get(left - 2).name()) {
      path.addFirst(tokens.get(left - 2).identifier());
      left -= 2;
    }
    boolean qualifier = index + 1 < tokens.size() && tokens.get(index + 1).kind() == Kind.DOT;
    boolean table =
        tableTokens.contains(index) || !qualifier && left > 0 && introduces(tokens, left - 1);
    return Optional.of(new Symbol(
        List.copyOf(path),
        token.identifier(),
        token.kind() == Kind.QUOTED,
        table,
        qualifier,
        tables));
  }

  // Whether the token names a table after it: not the FROM of IS [NOT] DISTINCT FROM, nor the
  // UPDATE of FOR UPDATE or ON UPDATE.
  private static boolean introduces(List<Token> tokens, int i) {
    var upper = tokens.get(i).upper();
    if (!INTRODUCERS.contains(upper)) {
      return false;
    }
    if (upper.equals("UPDATE") && (word(tokens, i - 1, "FOR") || word(tokens, i - 1, "ON"))) {
      return false;
    }
    boolean distinct = upper.equals("FROM")
        && word(tokens, i - 1, "DISTINCT")
        && (word(tokens, i - 2, "IS") || word(tokens, i - 2, "NOT") && word(tokens, i - 3, "IS"));
    return !distinct;
  }

  // Whether the token is a JOIN that takes an ON condition (not CROSS JOIN or NATURAL […] JOIN).
  private static boolean conditionalJoin(List<Token> tokens, int i) {
    if (!word(tokens, i, "JOIN") || word(tokens, i - 1, "CROSS")) {
      return false;
    }
    int k = i - 1;
    if (word(tokens, k, "OUTER")) {
      k--;
    }
    if (word(tokens, k, "INNER")
        || word(tokens, k, "LEFT")
        || word(tokens, k, "RIGHT")
        || word(tokens, k, "FULL")) {
      k--;
    }
    return !word(tokens, k, "NATURAL");
  }

  private static boolean word(List<Token> tokens, int i, String upper) {
    return i >= 0 && i < tokens.size() && tokens.get(i).upper().equals(upper);
  }

  // The index of the token the offset is in, or -1 for none.
  private static int tokenAt(List<Token> tokens, int offset) {
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.get(i).start() <= offset && offset < tokens.get(i).end()) {
        return i;
      }
    }
    return -1;
  }

  // Whether it is no statement with a CTE, WITH at its start, or a subquery, "(" before SELECT.
  private static boolean readable(List<Token> tokens) {
    if (!tokens.isEmpty() && tokens.getFirst().upper().equals("WITH")) {
      return false;
    }
    for (int i = 0; i + 1 < tokens.size(); i++) {
      if (tokens.get(i).kind() == Kind.OPEN && tokens.get(i + 1).upper().equals(SELECT)) {
        return false;
      }
    }
    return true;
  }

  // The index of the last FROM, or comma between the items of its list, or of the "(" still open
  // before the cursor: what a JOIN at the cursor joins comes after it. FROM a JOIN b ON a.id =
  // b.a_id, c JOIN d: d joins c; FROM a JOIN (b JOIN c: c joins b alone.
  private static int item(List<Token> tokens, Set<String> reserved, int cursor) {
    int item = -1;
    int depth = 0;
    boolean inFrom = false;
    var open = new ArrayDeque<Integer>();
    for (int i = 0; i < cursor; i++) {
      var token = tokens.get(i);
      int nesting = nesting(token);
      if (nesting > 0) {
        open.push(i);
      } else if (nesting < 0 && !open.isEmpty()) {
        open.pop();
      }
      depth = Math.max(0, depth + nesting);
      // Not the FROM of EXTRACT(YEAR FROM …); a FROM after an unclosed call while it is typed.
      if (token.upper().equals("FROM") && introduces(tokens, i) && depth == 0) {
        item = i;
        inFrom = true;
      } else if (depth == 0 && inFrom) {
        if (token.kind() == Kind.COMMA) {
          item = i;
        } else if (endsFrom(tokens, reserved, i)) {
          inFrom = false;
        }
      }
    }
    return open.isEmpty() ? item : Math.max(item, open.peek());
  }

  private static boolean endsFrom(List<Token> tokens, Set<String> reserved, int i) {
    var token = tokens.get(i);
    if (token.text().equals(";")) {
      return true;
    }
    return token.kind() == Kind.WORD
        && FROM_ENDERS.contains(token.upper())
        && reserved.contains(token.upper())
        && (i == 0 || tokens.get(i - 1).kind() != Kind.DOT);
  }

  // How the token changes the depth of parentheses and brackets.
  private static int nesting(Token token) {
    if (token.kind() == Kind.OPEN || token.text().equals("[")) {
      return 1;
    }
    return token.text().equals(")") || token.text().equals("]") ? -1 : 0;
  }

  private record Scan(
      List<Reference> tables, List<Reference> left, List<Reference> parenthesized, boolean joinOn) {
    static final Scan EMPTY = new Scan(List.of(), List.of(), List.of(), false);
  }

  // Where a table is named: the index of its introducer, of its first token, of the next, and
  // whether its columns are not the table's: renamed by a column alias list, or those of a call.
  private record Span(int introducer, int start, int end, boolean hidesColumns) {}

  private record Parsed(Reference reference, int end, boolean hidesColumns) {}

  private static Scan scan(
      List<Token> tokens, Set<String> reserved, Set<Integer> tableTokens, int cursor, int skip) {
    var found = new ArrayList<Reference>();
    var spans = new ArrayList<Span>();
    var parenthesized = new ArrayList<Reference>();
    named(tokens, reserved, tableTokens, skip, found, spans, parenthesized);
    int item = item(tokens, reserved, cursor);
    var hidden = cursor > 0 ? aliasedGroups(tokens, cursor) : List.<int[]>of();
    var left = new ArrayList<Reference>();
    boolean joinOn = false;
    for (int k = 0; k < found.size(); k++) {
      var span = spans.get(k);
      boolean usable = !span.hidesColumns() && !inside(hidden, span);
      if (cursor >= 0 && usable && span.start > item && span.end <= cursor) {
        left.add(found.get(k));
      }
      joinOn |= usable
          && span.end == cursor - 1
          && word(tokens, span.end, "ON")
          && conditionalJoin(tokens, span.introducer);
    }
    return new Scan(List.copyOf(found), List.copyOf(left), List.copyOf(parenthesized), joinOn);
  }

  // Reads the tables named after each introducer, and after each comma of a FROM list.
  private static void named(
      List<Token> tokens,
      Set<String> reserved,
      Set<Integer> tableTokens,
      int skip,
      List<Reference> found,
      List<Span> spans,
      List<Reference> parenthesized) {
    int depth = 0;
    boolean inFrom = false;
    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);
      depth = Math.max(0, depth + nesting(token));
      if (depth == 0 && inFrom && endsFrom(tokens, reserved, i)) {
        inFrom = false;
      }
      if (introduces(tokens, i)) {
        boolean from = token.upper().equals("FROM");
        if (from && depth == 0) {
          inFrom = true;
        }
        readTable(tokens, i + 1, i, skip, found, spans, tableTokens);
        // Not the FROM of EXTRACT(YEAR FROM (…)).
        if (!from || depth == 0) {
          readParen(tokens, i + 1, skip, parenthesized);
        }
      } else if (depth == 0 && inFrom && token.kind() == Kind.COMMA) {
        readTable(tokens, i + 1, i, skip, found, spans, tableTokens);
        readParen(tokens, i + 1, skip, parenthesized);
      }
    }
  }

  // Reads, for alias generation alone, what a parenthesized FROM item at from names that tables
  // leaves out: each group's alias, the table after its opening parentheses, and the table after
  // each comma of a group's own list, as MySQL's (a, b).
  private static void readParen(
      List<Token> tokens, int from, int skip, List<Reference> parenthesized) {
    int j = from;
    while (j < tokens.size() && tokens.get(j).kind() == Kind.OPEN) {
      int close = skipParens(tokens, j);
      int after = aliasAfter(tokens, close);
      if (after >= 0) {
        parenthesized.add(new Reference(null, null, tokens.get(after - 1).identifier()));
      }
      int depth = 0;
      for (int k = j + 1; k < close; k++) {
        depth += nesting(tokens.get(k));
        if (depth == 0 && tokens.get(k).kind() == Kind.COMMA) {
          readTable(tokens, k + 1, k, skip, parenthesized, new ArrayList<>(), null);
          readParen(tokens, k + 1, skip, parenthesized);
        }
      }
      j++;
    }
    if (j > from) {
      readTable(tokens, j, j - 1, skip, parenthesized, new ArrayList<>(), null);
    }
  }

  // Reads the table reference after an introducer or FROM-list comma, but not the one at skip.
  private static void readTable(
      List<Token> tokens,
      int j,
      int introducer,
      int skip,
      List<Reference> found,
      List<Span> spans,
      Set<Integer> tableTokens) {
    // LATERAL reads a subquery or a call, whose names are not a table's.
    if (j >= tokens.size() || j == skip || !tokens.get(j).name() || word(tokens, j, "LATERAL")) {
      return;
    }
    var parsed = reference(tokens, j, tableTokens);
    if (parsed == null) {
      return;
    }
    found.add(parsed.reference());
    spans.add(new Span(introducer, j, parsed.end(), parsed.hidesColumns()));
  }

  // Parenthesized groups closed before the cursor that carry an alias (") AS j" or ") j"), whose
  // inner tables are hidden behind that alias.
  private static List<int[]> aliasedGroups(List<Token> tokens, int cursor) {
    var open = new ArrayDeque<Integer>();
    var hidden = new ArrayList<int[]>();
    for (int i = 0; i < cursor; i++) {
      var token = tokens.get(i);
      if (token.kind() == Kind.OPEN) {
        open.push(i);
      } else if (token.text().equals(")") && !open.isEmpty()) {
        int start = open.pop();
        if (aliasAfter(tokens, i + 1) >= 0) {
          hidden.add(new int[] {start, i});
        }
      }
    }
    return hidden;
  }

  private static boolean inside(List<int[]> hidden, Span span) {
    for (var group : hidden) {
      if (group[0] < span.start && span.end <= group[1]) {
        return true;
      }
    }
    return false;
  }

  // The index after an optional [AS] alias starting at j, or -1 for none.
  private static int aliasAfter(List<Token> tokens, int j) {
    if (j < tokens.size() && tokens.get(j).upper().equals("AS")) {
      j++;
    }
    if (j < tokens.size()
        && tokens.get(j).name()
        && (tokens.get(j).kind() == Kind.QUOTED
            || !CLAUSES.contains(tokens.get(j).upper()))) {
      return j + 1;
    }
    return -1;
  }

  // The index after the balanced parenthesized group starting at from, or tokens.size() if
  // unclosed.
  private static int skipParens(List<Token> tokens, int from) {
    int depth = 0;
    int j = from;
    while (j < tokens.size()) {
      var token = tokens.get(j++);
      if (token.kind() == Kind.OPEN) {
        depth++;
      } else if (token.text().equals(")")) {
        depth--;
        if (depth <= 0) {
          return j;
        }
      }
    }
    return j;
  }

  // The table named at the index, with its alias and optional column alias list; null for a name
  // still being written, such as "public." before the caret.
  private static Parsed reference(List<Token> tokens, int from, Set<Integer> tableTokens) {
    var names = new ArrayList<String>();
    int j = from;
    names.add(tokens.get(j++).identifier());
    while (j + 1 < tokens.size()
        && tokens.get(j).kind() == Kind.DOT
        && tokens.get(j + 1).name()) {
      names.add(tokens.get(j + 1).identifier());
      j += 2;
    }
    if (j < tokens.size() && tokens.get(j).kind() == Kind.DOT) {
      return null;
    }
    if (tableTokens != null) {
      tableTokens.add(j - 1);
    }
    // A call, as of a function that returns a table: customers() AS c.
    boolean hidesColumns = j < tokens.size() && tokens.get(j).kind() == Kind.OPEN;
    String alias = null;
    int afterAlias = aliasAfter(tokens, j);
    if (afterAlias >= 0) {
      alias = tokens.get(afterAlias - 1).identifier();
      j = afterAlias;
    }
    if (alias != null && j < tokens.size() && tokens.get(j).kind() == Kind.OPEN) {
      hidesColumns = true;
      j = skipParens(tokens, j);
    }
    var name = names.getLast();
    var schema = names.size() > 1 ? names.get(names.size() - 2) : null;
    return new Parsed(new Reference(schema, name, alias), j, hidesColumns);
  }

  /** Whether the code point belongs to a word as the kit's completion takes it. */
  static boolean word(int c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private enum Kind {
    WORD,
    QUOTED,
    DOT,
    COMMA,
    OPEN,
    OTHER
  }

  /**
   * A token of code: a word, a quoted name, or a character. {@code identifier} is what the server
   * takes a word or a quoted name for, null for a name whose closing quote is missing.
   */
  private record Token(Kind kind, String text, String identifier, int start, int end) {
    boolean name() {
      return identifier != null;
    }

    String upper() {
      return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : "";
    }
  }

  // The statement's tokens from its pieces, by the server's lexical rules; comments and strings
  // are left out, or kept as OTHER where they stand between tokens.
  private static List<Token> tokens(String text, List<Piece> pieces, SyntaxDialect syntax) {
    var tokens = new ArrayList<Token>();
    boolean folds = syntax.foldsNamesToLowerCase();
    int k = 0;
    while (k < pieces.size()) {
      var piece = pieces.get(k);
      int next = k + 1;
      switch (piece.kind()) {
        case COMMENT -> {
          // left out
        }
        case STRING -> tokens.add(new Token(Kind.OTHER, "", null, piece.start(), piece.end()));
        case QUOTED -> tokens.add(quoted(text, piece));
        case CODE -> {
          char c = text.charAt(piece.start());
          if (part(c)) {
            next = wordEnd(text, pieces, k);
            int end = pieces.get(next - 1).end();
            var word = text.substring(piece.start(), end);
            var name = folds ? word.toLowerCase(Locale.ROOT) : word;
            tokens.add(new Token(Kind.WORD, word, name, piece.start(), end));
          } else {
            tokens.add(new Token(kind(c), String.valueOf(c), null, piece.start(), piece.end()));
          }
        }
      }
      k = next;
    }
    return tokens;
  }

  private static Token quoted(String text, Piece piece) {
    var quoted = text.substring(piece.start(), piece.end());
    String name = null;
    if (!piece.open() && quoted.length() >= 2) {
      var quote = quoted.substring(0, 1);
      name = quoted.substring(1, quoted.length() - 1).replace(quote + quote, quote);
    }
    return new Token(Kind.QUOTED, quoted, name, piece.start(), piece.end());
  }

  // The index after the last piece of the word that begins at piece k: the characters of code
  // that follow each other without a gap.
  private static int wordEnd(String text, List<Piece> pieces, int k) {
    int last = k;
    while (last + 1 < pieces.size()
        && pieces.get(last + 1).kind() == Piece.Kind.CODE
        && pieces.get(last + 1).start() == pieces.get(last).end()
        && part(text.charAt(pieces.get(last).end()))) {
      last++;
    }
    return last + 1;
  }

  private static Kind kind(char c) {
    return switch (c) {
      case '.' -> Kind.DOT;
      case ',' -> Kind.COMMA;
      case '(' -> Kind.OPEN;
      default -> Kind.OTHER;
    };
  }

  // A character of a word or a name: letters, digits, "_", "$", and both halves of a character
  // beyond the BMP.
  private static boolean part(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || Character.isSurrogate(c);
  }
}

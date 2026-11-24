package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.SyntaxDialect;
import dev.hoori.db.core.sql.Statements.Piece;
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
 * @param tables the tables the statement names, also after the caret
 * @param left the tables of the FROM item the caret is in, named before its word: those a JOIN
 *     there joins to, the last of them the one an ON there joins
 */
// shortcut: no parser, so a statement with a CTE or a subquery is not read: it names no tables,
// and nothing follows a dot in it; parse statements once completion should reach into them.
public record Context(
    Asked asked, List<String> path, String typed, List<Reference> tables, List<Reference> left) {

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
   * with its schema and alias where it has them, else null. Names are as the server takes them:
   * a quoted one as quoted, an unquoted one on PostgreSQL in lower case.
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
      "SELECT",
      "PARTITION",
      "TABLESAMPLE",
      "DEFAULT",
      "LOCK",
      "INTO",
      "WITH",
      "AS");
  private static final Set<String> INTRODUCERS = Set.of("FROM", "JOIN", "INTO", "UPDATE");
  private static final Context NOTHING =
      new Context(Asked.NOTHING, List.of(), "", List.of(), List.of());

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
    var scan = readable ? scan(tokens, null, cursor) : Scan.EMPTY;
    if (!path.isEmpty()) {
      return readable
          ? new Context(Asked.MEMBER, List.copyOf(path), typed, scan.tables, scan.left)
          : NOTHING;
    }
    Asked asked;
    if (n > 0 && introduces(tokens, n - 1)) {
      asked = conditionalJoin(tokens, n - 1) && !conditioned(tokens, n) ? Asked.JOIN : Asked.TABLE;
    } else if (scan.joinOn && free(tokens, wordEnd(statement, at))) {
      asked = Asked.ON;
    } else {
      asked = Asked.ANY;
    }
    return new Context(asked, List.of(), typed, scan.tables, scan.left);
  }

  // Whether the table named at the index already has its ON or USING, as while one is replaced.
  private static boolean conditioned(List<Token> tokens, int i) {
    if (word(tokens, i, "ON") || word(tokens, i, "USING")) {
      return true;
    }
    if (i >= tokens.size()
        || !tokens.get(i).name()
        || tokens.get(i).kind() == Kind.WORD && CLAUSES.contains(tokens.get(i).upper())) {
      return false;
    }
    int j = reference(tokens, i, new ArrayList<>(), null);
    return j >= 0 && (word(tokens, j, "ON") || word(tokens, j, "USING"));
  }

  // Whether no condition follows the offset: nothing, a clause, a comma or the statement's end.
  private static boolean free(List<Token> tokens, int offset) {
    for (var token : tokens) {
      if (token.start() >= offset) {
        return token.kind() == Kind.WORD && CLAUSES.contains(token.upper())
            || token.kind() == Kind.COMMA
            || token.text().equals(";");
      }
    }
    return true;
  }

  // Where the word that goes on from the offset ends.
  private static int wordEnd(String statement, int at) {
    int to = at;
    while (to < statement.length() && word(statement.codePointAt(to))) {
      to += Character.charCount(statement.codePointAt(to));
    }
    return to;
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
    var tokens = tokens(
        statement, Statements.pieces(statement, provider), provider.dialect().syntax());
    return readable(tokens) ? scan(tokens, null, -1).tables : List.of();
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
    var tables = readable ? scan(tokens, tableTokens, -1).tables : List.<Reference>of();
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

  // Whether the token names a table after it: not the FROM of IS [NOT] DISTINCT FROM.
  private static boolean introduces(List<Token> tokens, int i) {
    boolean distinct = word(tokens, i - 1, "DISTINCT")
        && (word(tokens, i - 2, "IS") || word(tokens, i - 2, "NOT") && word(tokens, i - 3, "IS"));
    return INTRODUCERS.contains(tokens.get(i).upper()) && !distinct;
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
      if (tokens.get(i).kind() == Kind.OPEN && tokens.get(i + 1).upper().equals("SELECT")) {
        return false;
      }
    }
    return true;
  }

  // The index of the last FROM, or comma between the items of its list, before the cursor: what
  // a JOIN at the cursor joins comes after it. FROM a JOIN b ON a.id = b.a_id, c JOIN d: d joins c.
  private static int item(List<Token> tokens, int cursor) {
    int item = -1;
    int depth = 0;
    for (int i = 0; i < cursor; i++) {
      var token = tokens.get(i);
      if (token.kind() == Kind.OPEN || token.text().equals("[")) {
        depth++;
      } else if (token.text().equals(")") || token.text().equals("]")) {
        depth--;
      } else if (token.upper().equals("FROM") && introduces(tokens, i)) {
        item = i;
        depth = 0;
      } else if (token.kind() == Kind.COMMA && depth == 0 && item >= 0) {
        item = i;
      }
    }
    return item;
  }

  private record Scan(List<Reference> tables, List<Reference> left, boolean joinOn) {
    static final Scan EMPTY = new Scan(List.of(), List.of(), false);
  }

  private static Scan scan(List<Token> tokens, Set<Integer> tableTokens, int cursor) {
    var found = new ArrayList<Reference>();
    var left = new ArrayList<Reference>();
    int item = item(tokens, cursor);
    boolean joinOn = false;
    for (int i = 0; i < tokens.size(); i++) {
      var introducer = tokens.get(i).upper();
      if (!introduces(tokens, i)) {
        continue;
      }
      boolean list = introducer.equals("FROM");
      boolean join = conditionalJoin(tokens, i);
      int j = i + 1;
      while (j < tokens.size() && tokens.get(j).name()) {
        int at = j;
        j = reference(tokens, j, found, tableTokens);
        if (j < 0) {
          break;
        }
        if (cursor >= 0 && at > item && j <= cursor) {
          left.add(found.getLast());
        }
        if (join && cursor > 0 && j == cursor - 1 && word(tokens, j, "ON")) {
          joinOn = true;
        }
        // FROM a x, b y: the list goes on after a comma.
        if (!list || j + 1 >= tokens.size() || tokens.get(j).kind() != Kind.COMMA) {
          break;
        }
        j++;
      }
    }
    return new Scan(List.copyOf(found), List.copyOf(left), joinOn);
  }

  // Adds the table named at the index, with its alias; the index after it, or -1 for a name still
  // being written, such as "public." before the caret.
  private static int reference(
      List<Token> tokens, int from, List<Reference> found, Set<Integer> tableTokens) {
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
      return -1;
    }
    if (tableTokens != null) {
      tableTokens.add(j - 1);
    }
    if (j < tokens.size() && tokens.get(j).upper().equals("AS")) {
      j++;
    }
    String alias = null;
    if (j < tokens.size()
        && tokens.get(j).name()
        && (tokens.get(j).kind() == Kind.QUOTED
            || !CLAUSES.contains(tokens.get(j).upper()))) {
      alias = tokens.get(j++).identifier();
    }
    var name = names.getLast();
    var schema = names.size() > 1 ? names.get(names.size() - 2) : null;
    found.add(new Reference(schema, name, alias));
    return j;
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

package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Statements.Piece;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 */
// shortcut: no parser, so a statement with a CTE or a subquery is not read: it names no tables,
// and nothing follows a dot in it; parse statements once completion should reach into them.
public record Context(Asked asked, List<String> path, String typed, List<Reference> tables) {

  /** What kind of name fits at the caret. */
  public enum Asked {
    /**
     * None: the caret is in a string, a comment or a quoted name, or after a dot in a statement
     * with a CTE or a subquery, whose names it cannot tell apart.
     */
    NOTHING,
    /** A keyword, a schema, a table or a column. */
    ANY,
    /** A table or a schema: after {@code FROM}, {@code JOIN}, {@code INTO}, {@code UPDATE}. */
    TABLE,
    /** What is in the thing the path names: a schema's tables, or a table's or alias's columns. */
    MEMBER
  }

  /**
   * A table the statement names after {@code FROM}, {@code JOIN}, {@code INTO} or {@code UPDATE},
   * with its schema and alias where it has them, else null. Names are as the server takes them:
   * a quoted one as quoted, an unquoted one on PostgreSQL in lower case.
   */
  public record Reference(String schema, String name, String alias) {}

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

  /**
   * What the caret, an offset in the statement's text, asks for. The text may hold comments
   * around the statement, and may be cut anywhere, as while it is typed.
   */
  public static Context at(String statement, Provider provider, int caret) {
    int at = Math.clamp(caret, 0, statement.length());
    var pieces = Statements.pieces(statement, provider);
    for (var piece : pieces) {
      if (piece.kind() != Piece.Kind.CODE && piece.holds(at)) {
        return new Context(Asked.NOTHING, List.of(), "", List.of());
      }
    }
    var tokens = tokens(statement, pieces, provider);
    boolean readable = readable(tokens);
    var tables = readable ? tables(tokens, provider) : List.<Reference>of();
    int from = at;
    while (from > 0 && word(statement.codePointBefore(from))) {
      from -= Character.charCount(statement.codePointBefore(from));
    }
    var typed = statement.substring(from, at);
    // The tokens before the word that is typed.
    int n = 0;
    while (n < tokens.size() && tokens.get(n).end() <= from) {
      n++;
    }
    var path = new ArrayList<String>();
    while (n >= 2 && tokens.get(n - 1).kind() == Kind.DOT && tokens.get(n - 2).name()) {
      path.addFirst(tokens.get(n - 2).identifier());
      n -= 2;
    }
    if (!path.isEmpty()) {
      return readable
          ? new Context(Asked.MEMBER, List.copyOf(path), typed, tables)
          : new Context(Asked.NOTHING, List.of(), "", List.of());
    }
    boolean table = n > 0 && INTRODUCERS.contains(tokens.get(n - 1).upper());
    return new Context(table ? Asked.TABLE : Asked.ANY, List.of(), typed, tables);
  }

  /** The tables the statement names, in order; none in a statement with a CTE or a subquery. */
  public static List<Reference> tables(String statement, Provider provider) {
    var tokens = tokens(statement, Statements.pieces(statement, provider), provider);
    return readable(tokens) ? tables(tokens, provider) : List.of();
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

  private static List<Reference> tables(List<Token> tokens, Provider provider) {
    var found = new ArrayList<Reference>();
    for (int i = 0; i < tokens.size(); i++) {
      var introducer = tokens.get(i).upper();
      if (!INTRODUCERS.contains(introducer)) {
        continue;
      }
      int j = i + 1;
      while (j < tokens.size() && tokens.get(j).name()) {
        var names = new ArrayList<String>();
        names.add(tokens.get(j++).identifier());
        while (j + 1 < tokens.size()
            && tokens.get(j).kind() == Kind.DOT
            && tokens.get(j + 1).name()) {
          names.add(tokens.get(j + 1).identifier());
          j += 2;
        }
        if (j < tokens.size() && tokens.get(j).kind() == Kind.DOT) {
          break; // a name still being written, such as "public." before the caret
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
        // FROM a x, b y: the list goes on after a comma.
        if (!introducer.equals("FROM")
            || j + 1 >= tokens.size()
            || tokens.get(j).kind() != Kind.COMMA) {
          break;
        }
        j++;
      }
    }
    return List.copyOf(found);
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
  private record Token(Kind kind, String text, String identifier, int end) {
    boolean name() {
      return identifier != null;
    }

    String upper() {
      return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : "";
    }
  }

  // The statement's tokens from its pieces, by the server's lexical rules; comments and strings
  // are left out, or kept as OTHER where they stand between tokens.
  private static List<Token> tokens(String text, List<Piece> pieces, Provider provider) {
    var tokens = new ArrayList<Token>();
    boolean postgres = provider == Provider.POSTGRESQL;
    for (int k = 0; k < pieces.size(); k++) {
      var piece = pieces.get(k);
      switch (piece.kind()) {
        case COMMENT -> {}
        case STRING -> tokens.add(new Token(Kind.OTHER, "", null, piece.end()));
        case QUOTED -> {
          var quoted = text.substring(piece.start(), piece.end());
          String name = null;
          if (!piece.open() && quoted.length() >= 2) {
            var quote = quoted.substring(0, 1);
            name = quoted.substring(1, quoted.length() - 1).replace(quote + quote, quote);
          }
          tokens.add(new Token(Kind.QUOTED, quoted, name, piece.end()));
        }
        case CODE -> {
          char c = text.charAt(piece.start());
          if (part(c)) {
            // A word: the characters of code that follow each other without a gap.
            int end = piece.end();
            while (k + 1 < pieces.size()
                && pieces.get(k + 1).kind() == Piece.Kind.CODE
                && pieces.get(k + 1).start() == end
                && part(text.charAt(end))) {
              end = pieces.get(++k).end();
            }
            var word = text.substring(piece.start(), end);
            var name = postgres ? word.toLowerCase(Locale.ROOT) : word;
            tokens.add(new Token(Kind.WORD, word, name, end));
          } else {
            var kind = switch (c) {
              case '.' -> Kind.DOT;
              case ',' -> Kind.COMMA;
              case '(' -> Kind.OPEN;
              default -> Kind.OTHER;
            };
            tokens.add(new Token(kind, String.valueOf(c), null, piece.end()));
          }
        }
      }
    }
    return tokens;
  }

  // A character of a word or a name: letters, digits, "_", "$", and both halves of a character
  // beyond the BMP.
  private static boolean part(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || Character.isSurrogate(c);
  }
}

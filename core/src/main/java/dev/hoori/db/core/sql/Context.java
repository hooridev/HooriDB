package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
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
// shortcut: no parser, so subqueries, CTEs and the tables of a function are not told apart; read
// the statement's syntax tree once completion needs to know which query the caret is in.
public record Context(Asked asked, List<String> path, String typed, List<Reference> tables) {

  /** What kind of name fits at the caret. */
  public enum Asked {
    /** None: the caret is in a string, a comment or a quoted name. */
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

  /** What the caret, an offset in the statement's text, asks for. */
  public static Context at(String statement, Provider provider, int caret) {
    int at = Math.clamp(caret, 0, statement.length());
    var tables = tables(statement, provider);
    var before = new Lexer(statement.substring(0, at), provider);
    var tokens = before.all();
    if (before.open) {
      return new Context(Asked.NOTHING, List.of(), "", tables);
    }
    int from = at;
    while (from > 0 && word(statement.charAt(from - 1))) {
      from--;
    }
    var typed = statement.substring(from, at);
    // The tokens before the word that is typed.
    int n = tokens.size();
    if (!typed.isEmpty() && n > 0 && tokens.get(n - 1).end() == at) {
      n--;
    }
    var path = new ArrayList<String>();
    while (n >= 2 && tokens.get(n - 1).kind() == Kind.DOT && tokens.get(n - 2).name()) {
      path.addFirst(tokens.get(n - 2).identifier(provider));
      n -= 2;
    }
    if (!path.isEmpty()) {
      return new Context(Asked.MEMBER, List.copyOf(path), typed, tables);
    }
    boolean table = n > 0 && INTRODUCERS.contains(tokens.get(n - 1).upper());
    return new Context(table ? Asked.TABLE : Asked.ANY, List.of(), typed, tables);
  }

  /** The tables the statement names, in order. */
  public static List<Reference> tables(String statement, Provider provider) {
    var tokens = new Lexer(statement, provider).all();
    var found = new ArrayList<Reference>();
    for (int i = 0; i < tokens.size(); i++) {
      var introducer = tokens.get(i).upper();
      if (!INTRODUCERS.contains(introducer)) {
        continue;
      }
      int j = i + 1;
      while (j < tokens.size() && tokens.get(j).name()) {
        var names = new ArrayList<String>();
        names.add(tokens.get(j++).identifier(provider));
        while (j + 1 < tokens.size()
            && tokens.get(j).kind() == Kind.DOT
            && tokens.get(j + 1).name()) {
          names.add(tokens.get(j + 1).identifier(provider));
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
          alias = tokens.get(j++).identifier(provider);
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

  /** Whether the character belongs to a word as the kit's completion takes it. */
  static boolean word(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private enum Kind {
    WORD,
    QUOTED,
    DOT,
    COMMA,
    OTHER
  }

  private record Token(Kind kind, String text, int end) {
    boolean name() {
      return kind == Kind.WORD || kind == Kind.QUOTED;
    }

    String upper() {
      return kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : "";
    }

    /** The name the server takes it for. */
    String identifier(Provider provider) {
      if (kind == Kind.QUOTED) {
        var quote = text.substring(0, 1);
        return text.substring(1, text.length() - 1).replace(quote + quote, quote);
      }
      return provider == Provider.POSTGRESQL ? text.toLowerCase(Locale.ROOT) : text;
    }
  }

  /** The tokens of a text, without whitespace and comments; strings are {@code OTHER}. */
  private static final class Lexer {
    private final String text;
    private final boolean postgres;
    private int i;
    boolean open; // the text ends inside a string, a quoted name or a comment

    Lexer(String text, Provider provider) {
      this.text = text;
      this.postgres = provider == Provider.POSTGRESQL;
    }

    List<Token> all() {
      var tokens = new ArrayList<Token>();
      int n = text.length();
      while (i < n) {
        char c = text.charAt(i);
        char next = i + 1 < n ? text.charAt(i + 1) : '\0';
        int from = i;
        if (Character.isWhitespace(c)) {
          i++;
        } else if (c == '-' && next == '-' && (postgres || i + 2 >= n || text.charAt(i + 2) <= ' ')
            || c == '#' && !postgres) {
          int end = text.indexOf('\n', i);
          open = end < 0;
          i = end < 0 ? n : end;
        } else if (c == '/' && next == '*') {
          int end = text.indexOf("*/", i + 2);
          open = end < 0;
          i = end < 0 ? n : end + 2;
        } else if (c == '\'' || c == '"' && !postgres) {
          skip(c, !postgres);
          tokens.add(new Token(Kind.OTHER, "", i));
        } else if (c == '"' || c == '`' && !postgres) {
          skip(c, false);
          tokens.add(new Token(Kind.QUOTED, text.substring(from, i), i));
        } else if (c == '$' && postgres && dollar()) {
          tokens.add(new Token(Kind.OTHER, "", i));
        } else if (word(c) || c == '$') {
          while (i < n && (word(text.charAt(i)) || text.charAt(i) == '$')) {
            i++;
          }
          tokens.add(new Token(Kind.WORD, text.substring(from, i), i));
        } else {
          i++;
          var kind = c == '.' ? Kind.DOT : c == ',' ? Kind.COMMA : Kind.OTHER;
          tokens.add(new Token(kind, String.valueOf(c), i));
        }
      }
      return tokens;
    }

    // Past a quoted text that begins at i; open where it is not closed.
    private void skip(char quote, boolean backslash) {
      int n = text.length();
      i++;
      while (i < n) {
        char c = text.charAt(i++);
        if (backslash && c == '\\') {
          i++;
        } else if (c == quote) {
          if (i >= n || text.charAt(i) != quote) {
            return;
          }
          i++;
        }
      }
      open = true;
      i = n;
    }

    // Past a dollar-quoted string that begins at i, or false where none begins.
    private boolean dollar() {
      int n = text.length();
      int j = i + 1;
      while (j < n && word(text.charAt(j))) {
        j++;
      }
      if (j >= n || text.charAt(j) != '$' || j > i + 1 && Character.isDigit(text.charAt(i + 1))) {
        return false;
      }
      var tag = text.substring(i, j + 1);
      int end = text.indexOf(tag, j + 1);
      open = end < 0;
      i = end < 0 ? n : end + tag.length();
      return true;
    }
  }
}

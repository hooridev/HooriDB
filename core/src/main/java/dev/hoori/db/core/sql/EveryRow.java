package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * An {@code UPDATE} or {@code DELETE} without a {@code WHERE} at its top level, which changes every
 * row of its table: one in a subquery does not count, nor one in a comment, a string or a quoted
 * name. On MariaDB and MySQL a {@code LIMIT} at its top level bounds it as a {@code WHERE} does.
 * Read from the statement's text alone, by the lexical rules of {@link Statements}.
 *
 * @param verb {@code UPDATE} or {@code DELETE}
 * @param table the first table it names, as written, such as {@code shop."order lines"}; empty
 *     where none can be read
 */
public record EveryRow(String verb, String table) {

  // What may stand before the table: MariaDB's and MySQL's modifiers, PostgreSQL's ONLY, FROM.
  private static final Set<String> BEFORE_TABLE =
      Set.of("LOW_PRIORITY", "QUICK", "IGNORE", "ONLY", "FROM");

  // The statements a WITH may lead into.
  private static final Set<String> VERBS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "VALUES", "TABLE");

  // A word, a quoted name or one other character of code, at its depth of parentheses.
  private record Token(String text, int start, int end, int depth, boolean quoted) {

    boolean is(String word) {
      return !quoted && text.equalsIgnoreCase(word);
    }

    boolean name() {
      return quoted || Character.isLetterOrDigit(text.charAt(0)) || text.charAt(0) == '_';
    }
  }

  /** The text's one statement as such a change, or empty where it is none. */
  public static Optional<EveryRow> of(String sql, Provider provider) {
    var tokens = tokens(sql, provider);
    int verb = 0;
    if (verb < tokens.size() && tokens.get(verb).is("WITH")) {
      do {
        verb++;
      } while (verb < tokens.size()
          && (tokens.get(verb).depth() > 0
              || tokens.get(verb).quoted()
              || !VERBS.contains(tokens.get(verb).text().toUpperCase(Locale.ROOT))));
    }
    if (verb >= tokens.size()
        || !tokens.get(verb).is("UPDATE") && !tokens.get(verb).is("DELETE")) {
      return Optional.empty();
    }
    for (int k = verb + 1; k < tokens.size(); k++) {
      var token = tokens.get(k);
      if (token.depth() == 0
          && (token.is("WHERE") || provider != Provider.POSTGRESQL && token.is("LIMIT"))) {
        return Optional.empty();
      }
    }
    int at = verb + 1;
    while (at < tokens.size()
        && !tokens.get(at).quoted()
        && BEFORE_TABLE.contains(tokens.get(at).text().toUpperCase(Locale.ROOT))) {
      at++;
    }
    var table = "";
    if (at < tokens.size() && tokens.get(at).name()) {
      int end = at;
      while (end + 2 < tokens.size()
          && tokens.get(end + 1).text().equals(".")
          && !tokens.get(end + 1).quoted()
          && tokens.get(end + 2).name()) {
        end += 2;
      }
      table = sql.substring(tokens.get(at).start(), tokens.get(end).end());
    }
    return Optional.of(new EveryRow(tokens.get(verb).text().toUpperCase(Locale.ROOT), table));
  }

  // The code of the text, without its comments and strings: words joined from their characters.
  private static List<Token> tokens(String sql, Provider provider) {
    var pieces = Statements.pieces(sql, provider);
    var tokens = new ArrayList<Token>();
    int depth = 0;
    for (int k = 0; k < pieces.size(); k++) {
      var piece = pieces.get(k);
      switch (piece.kind()) {
        case COMMENT, STRING -> {}
        case QUOTED ->
          tokens.add(new Token(
              sql.substring(piece.start(), piece.end()), piece.start(), piece.end(), depth, true));
        case CODE -> {
          char c = sql.charAt(piece.start());
          if (c == ')') {
            depth = Math.max(0, depth - 1);
          }
          int end = piece.end();
          if (word(c)) {
            while (k + 1 < pieces.size()
                && pieces.get(k + 1).kind() == Statements.Piece.Kind.CODE
                && pieces.get(k + 1).start() == end
                && word(sql.charAt(end))) {
              end = pieces.get(++k).end();
            }
          }
          tokens.add(
              new Token(sql.substring(piece.start(), end), piece.start(), end, depth, false));
          if (c == '(') {
            depth++;
          }
        }
      }
    }
    return tokens;
  }

  private static boolean word(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}

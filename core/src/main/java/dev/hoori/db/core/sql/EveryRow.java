package dev.hoori.db.core.sql;

import dev.hoori.db.core.connection.Provider;
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

  /** The text's one statement as such a change, or empty where it is none. */
  public static Optional<EveryRow> of(String sql, Provider provider) {
    var tokens = Tokens.of(sql, provider);
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
}

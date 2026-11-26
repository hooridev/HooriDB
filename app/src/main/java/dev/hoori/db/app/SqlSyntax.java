package dev.hoori.db.app;

import com.rethinkj.ui.Formatted;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.sql.Sql;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Layout;
import dev.hoori.db.core.sql.Statements;
import java.util.function.Function;

/**
 * Colors SQL as the framework's {@link Sql} does, and lays it out on Control+Alt+L with core's
 * {@link Layout} by the provider's lexical rules.
 */
record SqlSyntax(Sql sql, Provider provider) implements Highlighter {

  @Override
  public Object line(String line, Object state, Spans out) {
    return sql.line(line, state, out);
  }

  @Override
  public boolean marksBrackets() {
    return sql.marksBrackets();
  }

  @Override
  public String lineComment() {
    return sql.lineComment();
  }

  @Override
  public String wordCharacters() {
    return sql.wordCharacters();
  }

  @Override
  public Function<String, Formatted> formatter() {
    return text -> format(text, provider);
  }

  /** The text laid out, or where an unclosed string, quoted name or block comment starts. */
  static Formatted format(String text, Provider provider) {
    try {
      return new Formatted(Layout.of(text, provider), null);
    } catch (Layout.UnclosedException e) {
      var kind = e.kind() == Statements.Piece.Kind.STRING
          ? Formatted.Kind.UNCLOSED_STRING
          : Formatted.Kind.UNEXPECTED_END;
      return new Formatted(null, new Formatted.Problem(e.start(), e.end(), kind));
    } catch (StackOverflowError _) {
      return new Formatted(null, new Formatted.Problem(0, 0, Formatted.Kind.TOO_LARGE));
    }
  }
}

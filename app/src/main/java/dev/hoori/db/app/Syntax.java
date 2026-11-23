package dev.hoori.db.app;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.sql.Sql;
import dev.hoori.db.core.connection.Provider;

/** The syntax colors of a provider's statements: its SQL, or the shell's of a server of documents. */
final class Syntax {

  private Syntax() {}

  static Highlighter of(Provider provider) {
    return switch (provider) {
      case POSTGRESQL -> Sql.of(Sql.Dialect.POSTGRES);
      case MARIADB, MYSQL -> Sql.of(Sql.Dialect.MYSQL);
      case MONGODB -> ShellSyntax.of();
    };
  }

  /** The dialect-neutral syntax colors of a language, before a target connection is chosen. */
  static Highlighter of(Language language) {
    return switch (language) {
      case Language.Sql _ -> StandardSyntax.of();
      case Language.Shell _ -> ShellSyntax.of();
    };
  }
}

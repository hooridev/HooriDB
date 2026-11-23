package dev.hoori.db.app;

import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.sql.Sql;
import dev.hoori.db.core.connection.Provider;

/** The syntax colors of a provider's statements: its SQL, or the shell's of a server of documents. */
final class Syntax {

  private Syntax() {}

  static Highlighter of(Provider provider) {
    return switch (provider) {
      case POSTGRESQL -> new SqlSyntax(Sql.of(Sql.Dialect.POSTGRES), provider);
      case MARIADB, MYSQL -> new SqlSyntax(Sql.of(Sql.Dialect.MYSQL), provider);
      case MONGODB -> ShellSyntax.of();
    };
  }
}

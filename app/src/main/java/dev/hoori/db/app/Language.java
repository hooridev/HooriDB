package dev.hoori.db.app;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.dialect.Dialect;
import dev.hoori.db.core.dialect.DocumentDialect;

/**
 * The language a connection's statements are written in, chosen once from its provider: SQL, or
 * the shell of a server of documents. What a console, its completions and a table's tab do their
 * own way for it, they ask of it or switch over it, never of the provider.
 */
sealed interface Language {

  static Language of(Provider provider) {
    return switch (provider.traits()) {
      case Dialect _ -> new Sql();
      case DocumentDialect _ -> new Shell();
    };
  }

  /** Whether its statements reach documents, as texts call them, rather than rows. */
  boolean documents();

  /** The characters after which the completion list opens at once. */
  String opensCompletion();

  /** The label of the console's editor. */
  String label();

  /** Whether a console may hold a transaction open, and so offers Auto and Manual. */
  boolean transactions();

  /** Whether the server's planner estimates a console's reads. */
  boolean estimates();

  /** What a console's editor suggests at the caret. */
  Suggestions.Strategy completion();

  record Sql() implements Language {
    @Override
    public boolean documents() {
      return false;
    }

    @Override
    public String opensCompletion() {
      return ".";
    }

    @Override
    public String label() {
      return "SQL";
    }

    @Override
    public boolean transactions() {
      return true;
    }

    @Override
    public boolean estimates() {
      return true;
    }

    @Override
    public Suggestions.Strategy completion() {
      return new SqlCompletion();
    }
  }

  record Shell() implements Language {
    @Override
    public boolean documents() {
      return true;
    }

    // Its operators come after a dollar, which the kit's word leaves out.
    @Override
    public String opensCompletion() {
      return ".$";
    }

    @Override
    public String label() {
      return "Statements";
    }

    // A server of documents commits each command by itself.
    @Override
    public boolean transactions() {
      return false;
    }

    @Override
    public boolean estimates() {
      return false;
    }

    @Override
    public Suggestions.Strategy completion() {
      return new ShellCompletion();
    }
  }
}

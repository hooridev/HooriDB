package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a provider of SQL reads what its views use and changes them: what it reads besides their
 * definitions, which {@link DefinitionDialect} reads, the statements that a {@link ViewChange}
 * sends, and how its servers take them. A provider's dialect hands one out, {@code
 * provider.dialect().views()}; what it reads goes through the {@link Sql} its caller gives, in the
 * snapshot that {@link DefinitionDialect#begin} began.
 */
public abstract sealed class ViewDialect permits MySqlFamilyViews, PostgreSqlViews {

  // A name of one part, as it stands without quotes.
  private static final String BARE = "[\\p{L}_][\\p{L}\\p{N}_$]*";

  ViewDialect() {}

  /** What the view uses and what uses it, as far as the server records it. */
  public abstract References references(Sql sql, Table view) throws QueryException;

  /**
   * Whether the materialized view's rows can be refreshed without keeping others from reading it,
   * {@code CONCURRENTLY}: where it holds rows and has a unique index of columns over all of them;
   * false for any other view.
   */
  public abstract boolean refreshesConcurrently(Sql sql, Table view) throws QueryException;

  /**
   * What gives the view, once it is dropped and created anew from its definition, what a drop
   * takes and its definition does not give back: on PostgreSQL its owner, its triggers and its
   * rules. None where the server drops and creates no view in one change.
   */
  public abstract List<String> restore(Sql sql, Table view) throws QueryException;

  /**
   * What takes from the view what its definition sets besides its query, which a replace in place
   * keeps: on PostgreSQL its comments and its columns' defaults, so that a definition that no
   * longer sets one leaves the view without it. None where a replace takes all of it.
   */
  public abstract List<String> unset(Sql sql, Table view) throws QueryException;

  /**
   * Whether its servers change definitions within a transaction, all or none, as PostgreSQL does;
   * MariaDB and MySQL commit each statement of DDL by itself.
   */
  public abstract boolean changesInTransaction();

  /**
   * Whether a drop can take what depends on the view with it, {@code CASCADE}; where it cannot,
   * as on MariaDB and MySQL, the drop leaves what used the view without it.
   */
  public abstract boolean dropsDependents();

  /**
   * Whether the server refused a view's {@code CREATE OR REPLACE} because its columns would no
   * longer be those it has, as PostgreSQL does where a column is dropped, renamed or moved or
   * changes its type: such a change needs the view dropped and created anew.
   */
  public abstract boolean keepsColumns(QueryException refusal);

  /**
   * Whether the server refused a drop because something depends on the view, which then says
   * what: a drop of what depends on it too, {@link #drop} with dependents, would take that.
   */
  public abstract boolean dependedOn(QueryException refusal);

  /**
   * What a statement that creates a view begins with, up to its name: {@code CREATE}, {@code OR
   * REPLACE} in a group {@code replace} where it has it, the options before {@code VIEW} and, in a
   * group {@code materialized}, the word that makes the view keep its rows.
   */
  abstract Pattern head();

  /** The part of a name, quoted as the provider quotes one, as it names an object. */
  abstract String unquoted(String part);

  /** The name of one part as the server takes it without quotes. */
  abstract String bare(String word);

  /** The statement that creates the view from the query. */
  public String create(Provider provider, Table view, String query) {
    return "CREATE VIEW " + Rows.qualified(provider, view) + " AS\n" + query;
  }

  /**
   * The statement as one that replaces the view where it creates one, {@code CREATE OR REPLACE},
   * which keeps what was granted on it; as it is where it creates no view, already replaces one,
   * or creates a view that keeps its rows, which nothing replaces in place.
   */
  public String orReplace(String statement) {
    var head = head().matcher(statement);
    if (!head.lookingAt() || head.group("replace") != null || group(head, "materialized") != null) {
      return statement;
    }
    var at = head.start() + "CREATE".length();
    return statement.substring(0, at) + " OR REPLACE" + statement.substring(at);
  }

  /**
   * The name of the view the statement creates, its parts as the server takes them, as {@code
   * [schema, view]}; empty where it creates none.
   */
  public Optional<List<String>> creates(Provider provider, String statement) {
    var head = head().matcher(statement);
    if (!head.lookingAt()) {
      return Optional.empty();
    }
    var part = Pattern.compile(quoted(provider) + "|" + BARE).matcher(statement);
    var dot = Pattern.compile("\\s*\\.\\s*").matcher(statement);
    var parts = new ArrayList<String>();
    var at = head.end();
    while (part.region(at, statement.length()).lookingAt()) {
      var word = part.group();
      parts.add(word.matches(BARE) ? bare(word) : unquoted(word));
      at = part.end();
      if (!dot.region(at, statement.length()).lookingAt()) {
        break;
      }
      at = dot.end();
    }
    return parts.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(parts));
  }

  /** Whether the statement creates the view itself, as its name says where it has one. */
  public boolean createsItself(Provider provider, String statement, Table view) {
    return creates(provider, statement).map(parts -> names(parts, view)).orElse(true);
  }

  private static boolean names(List<String> parts, Table view) {
    var n = parts.size();
    if (!parts.getLast().equals(view.name())) {
      return false;
    }
    if (n >= 2 && !parts.get(n - 2).equals(view.container().name())) {
      return false;
    }
    return n < 3 || parts.get(n - 3).equals(view.container().database().name());
  }

  /**
   * The statement that drops the view: with what depends on it where asked and the server can,
   * {@link #dropsDependents}; else one that the server refuses while something depends on it, or
   * that leaves what used it without it where it cannot.
   */
  public abstract String drop(Provider provider, Table view, boolean dependents);

  /**
   * The statement that refreshes the materialized view's rows from what it uses, without keeping
   * others from reading it where asked.
   *
   * @throws IllegalArgumentException where the server has no materialized views
   */
  public abstract String refresh(Provider provider, Table view, boolean concurrently);

  // A name quoted as the provider quotes one, its quote doubled within.
  private static String quoted(Provider provider) {
    var quote = provider.quote("").substring(0, 1);
    return quote + "(?:[^" + quote + "]|" + quote + quote + ")*" + quote;
  }

  private static String group(Matcher matcher, String name) {
    try {
      return matcher.group(name);
    } catch (IllegalArgumentException _) {
      return null; // a head without it
    }
  }
}

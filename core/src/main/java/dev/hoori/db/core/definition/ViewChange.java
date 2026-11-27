package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A change of a view's definition, as the statements that make it, which are shown before they
 * run: the view created, its definition replaced, the view dropped and created anew, dropped, or
 * its rows refreshed.
 *
 * @param view the view it changes, or creates
 * @param transaction whether its statements run in one transaction, all or none, as {@link
 *     DefinitionDialect#changesInTransaction} lets them
 */
public record ViewChange(Table view, Kind kind, List<String> statements, boolean transaction) {

  /** What a change does. */
  public enum Kind {
    CREATE,
    /** Replaces the definition in place, {@code CREATE OR REPLACE}, which keeps its privileges. */
    REPLACE,
    /**
     * Drops the view and creates it anew from its definition: what a materialized view needs for
     * any change, and a view whose columns change on a server that keeps them, which loses the
     * privileges granted on it.
     */
    RECREATE,
    DROP,
    REFRESH
  }

  // What creates a view, up to the word VIEW: the options of MariaDB and MySQL before it, and
  // PostgreSQL's RECURSIVE.
  private static final Pattern CREATES_VIEW = Pattern.compile(
      "(?is)CREATE(?!\\s+OR\\s+REPLACE\\b)(?=\\s+(?:(?:ALGORITHM\\s*=\\s*\\w+|DEFINER\\s*=\\s*\\S+"
          + "|SQL\\s+SECURITY\\s+\\w+|RECURSIVE)\\s+)*VIEW\\b)");

  public ViewChange {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(kind, "kind");
    statements = List.copyOf(statements);
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("A change of " + view.name() + " without statements");
    }
  }

  /**
   * The {@code SELECT} as a new view of the name, in the schema or the database.
   *
   * @throws IllegalArgumentException where the name or the {@code SELECT} is blank
   */
  public static ViewChange create(
      Provider provider, Container container, String name, String select) {
    if (name.isBlank()) {
      throw new IllegalArgumentException("A view without a name");
    }
    var query = statements(select, provider);
    if (query.size() != 1) {
      throw new IllegalArgumentException("A view of " + query.size() + " statements");
    }
    var view = new Table(container, name, Table.Kind.VIEW);
    return new ViewChange(
        view,
        Kind.CREATE,
        List.of("CREATE VIEW " + Rows.qualified(provider, view) + " AS\n" + query.getFirst()),
        false);
  }

  /**
   * The view's definition, as edited, in place of the one it has: each of its statements that
   * creates a view replaces it, {@code CREATE OR REPLACE}, where the view is not materialized; a
   * materialized view, which has no such statement, is dropped and created anew, as {@link
   * #recreate}.
   *
   * @throws IllegalArgumentException where the text holds no statement
   */
  public static ViewChange replace(Provider provider, ViewDefinition was, String text) {
    if (was.materialized()) {
      return recreate(provider, was, text);
    }
    var statements = statements(text, provider).stream()
        .map(sql -> CREATES_VIEW.matcher(sql).lookingAt() ? orReplace(sql) : sql)
        .toList();
    return new ViewChange(
        was.view(),
        Kind.REPLACE,
        statements,
        provider.dialect().definitions().changesInTransaction() && statements.size() > 1);
  }

  /**
   * Drops the view and creates it anew from the definition, as edited, in one transaction; a
   * materialized view's rows refreshed after, as its definition creates it without them.
   *
   * @throws IllegalArgumentException where the text holds no statement, or the server changes no
   *     definition within a transaction, so that a failure would leave the view dropped
   */
  public static ViewChange recreate(Provider provider, ViewDefinition was, String text) {
    if (!provider.dialect().definitions().changesInTransaction()) {
      throw new IllegalArgumentException(
          provider.displayName() + " drops and creates a view in no transaction");
    }
    var created = statements(text, provider);
    if (created.isEmpty()) {
      throw new IllegalArgumentException("A definition without statements");
    }
    var statements = new ArrayList<String>();
    statements.add(dropping(provider, was.view(), false));
    statements.addAll(created);
    statements.addAll(was.definition().restore());
    return new ViewChange(was.view(), Kind.RECREATE, statements, true);
  }

  /**
   * Drops the view, with what depends on it where asked and the server can, as {@link
   * DefinitionDialect#dropsDependents} says.
   */
  public static ViewChange drop(Provider provider, Table view, boolean dependents) {
    var cascade = dependents && provider.dialect().definitions().dropsDependents();
    return new ViewChange(view, Kind.DROP, List.of(dropping(provider, view, cascade)), false);
  }

  /**
   * Refreshes the materialized view's rows from what it uses, without keeping others from reading
   * it where asked, as {@link ViewDefinition#concurrently} says it can.
   *
   * @throws IllegalArgumentException where the view is not materialized
   */
  public static ViewChange refresh(Provider provider, Table view, boolean concurrently) {
    if (view.kind() != Table.Kind.MATERIALIZED) {
      throw new IllegalArgumentException(view.name() + " keeps no rows to refresh");
    }
    return new ViewChange(
        view,
        Kind.REFRESH,
        List.of("REFRESH MATERIALIZED VIEW " + (concurrently ? "CONCURRENTLY " : "")
            + Rows.qualified(provider, view)),
        false);
  }

  private static String dropping(Provider provider, Table view, boolean cascade) {
    var what = view.kind() == Table.Kind.MATERIALIZED ? "DROP MATERIALIZED VIEW " : "DROP VIEW ";
    return what + Rows.qualified(provider, view) + (cascade ? " CASCADE" : "");
  }

  private static String orReplace(String sql) {
    return "CREATE OR REPLACE" + sql.substring("CREATE".length());
  }

  // The statements of the text, each as it goes to the server, without its terminator.
  private static List<String> statements(String text, Provider provider) {
    return Statements.split(text, provider).stream()
        .map(s -> text.substring(s.start(), s.end()))
        .toList();
  }
}

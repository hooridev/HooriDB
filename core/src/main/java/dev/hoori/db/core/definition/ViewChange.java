package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A change of a view's definition, as the statements that make it, which are shown before they
 * run: the view created, its definition replaced, the view dropped and created anew, dropped, or
 * its rows refreshed. The statements are the provider's, as its {@link ViewDialect} writes them.
 *
 * @param view the view it changes, or creates
 * @param transaction whether its statements run in one transaction, all or none, as {@link
 *     ViewDialect#changesInTransaction} lets them
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

  /** A definition, as edited, that creates a view of another name than the one it replaces. */
  public static final class OtherView extends IllegalArgumentException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String named;

    OtherView(String named) {
      super("The definition creates the view " + named);
      this.named = named;
    }

    /** The name of the view it creates, as the text writes it. */
    public String named() {
      return named;
    }
  }

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
        List.of(provider.dialect().views().create(provider, view, query.getFirst())),
        false);
  }

  /**
   * The view's definition, as edited, in place of the one it has: each of its statements that
   * creates a view replaces it, {@code CREATE OR REPLACE}, where the view is not materialized,
   * followed by what takes from the view what its definition set besides its query ({@link
   * ViewDefinition#unset}), which the text's other statements set again where they keep it; a
   * materialized view, which has no such statement, is dropped and created anew, as {@link
   * #recreate}.
   *
   * @throws OtherView where a statement creates a view of another name
   * @throws IllegalArgumentException where the text holds no statement
   */
  public static ViewChange replace(Provider provider, ViewDefinition was, String text) {
    if (was.materialized()) {
      return recreate(provider, was, text);
    }
    var dialect = provider.dialect().views();
    var statements = new ArrayList<String>();
    var unset = true;
    for (var sql : itself(provider, was.view(), text)) {
      var replacing = dialect.orReplace(sql);
      statements.add(replacing);
      if (unset && dialect.creates(provider, sql).isPresent()) {
        statements.addAll(was.unset());
        unset = false;
      }
    }
    return new ViewChange(
        was.view(),
        Kind.REPLACE,
        statements,
        dialect.changesInTransaction() && statements.size() > 1);
  }

  /**
   * Drops the view and creates it anew from the definition, as edited, in one transaction, and
   * gives it back what the drop took ({@link ViewDefinition#restore}); a materialized view's rows
   * refreshed after, as its definition creates it without them.
   *
   * @throws OtherView where a statement creates a view of another name
   * @throws IllegalArgumentException where the text holds no statement, or the server changes no
   *     definition within a transaction, so that a failure would leave the view dropped
   */
  public static ViewChange recreate(Provider provider, ViewDefinition was, String text) {
    var dialect = provider.dialect().views();
    if (!dialect.changesInTransaction()) {
      throw new IllegalArgumentException(
          provider.displayName() + " drops and creates a view in no transaction");
    }
    var created = itself(provider, was.view(), text);
    var statements = new ArrayList<String>();
    statements.add(dialect.drop(provider, was.view(), false));
    statements.addAll(created);
    statements.addAll(was.definition().restore());
    statements.addAll(was.restore());
    return new ViewChange(was.view(), Kind.RECREATE, statements, true);
  }

  /**
   * Drops the view, with what depends on it where asked and the server can, as {@link
   * ViewDialect#dropsDependents} says; without, a server that can refuses it while something
   * depends on it ({@link Views.DependedOn}).
   */
  public static ViewChange drop(Provider provider, Table view, boolean dependents) {
    var dialect = provider.dialect().views();
    var cascade = dependents && dialect.dropsDependents();
    return new ViewChange(view, Kind.DROP, List.of(dialect.drop(provider, view, cascade)), false);
  }

  /**
   * Refreshes the materialized view's rows from what it uses, without keeping others from reading
   * it where asked, as {@link ViewDefinition#concurrently} says it can.
   *
   * @throws IllegalArgumentException where the view is not materialized
   */
  public static ViewChange refresh(Provider provider, Table view, boolean concurrently) {
    return new ViewChange(
        view,
        Kind.REFRESH,
        List.of(provider.dialect().views().refresh(provider, view, concurrently)),
        false);
  }

  // The statements of the text, none of which creates a view of another name.
  private static List<String> itself(Provider provider, Table view, String text) {
    var dialect = provider.dialect().views();
    var statements = statements(text, provider);
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("A definition without statements");
    }
    for (var sql : statements) {
      if (!dialect.createsItself(provider, sql, view)) {
        throw new OtherView(String.join(".", dialect.creates(provider, sql).orElseThrow()));
      }
    }
    return statements;
  }

  // The statements of the text, each as it goes to the server, without its terminator.
  private static List<String> statements(String text, Provider provider) {
    return Statements.split(text, provider).stream()
        .map(s -> text.substring(s.start(), s.end()))
        .toList();
  }
}

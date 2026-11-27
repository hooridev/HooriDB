package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.util.List;

/**
 * How a provider of SQL reads the definitions of its objects, the statements that create them as
 * they are now, and what its views use: the one place that the export to a file ({@link
 * dev.hoori.db.core.dump.Dump}), a view's tab ({@link Views}) and any tool that copies an object's
 * structure build on. A provider's dialect hands one out, {@code provider.dialect().definitions()};
 * what it sends goes through {@link Sql}, as the purpose its caller bound.
 *
 * <p>Everything is read in one transaction, read only and in one snapshot, which {@link #begin}
 * begins and {@link #end} ends, with the settings in which the server writes the definitions as
 * they are read back: every name qualified where the provider has schemas, and on a connection on
 * the object's database where it has none, which the server then names without it.
 */
public abstract class DefinitionDialect {

  DefinitionDialect() {}

  /**
   * Begins the transaction, read only and in one snapshot, that definitions are read in, on a
   * connection in auto-commit. Where it fails, also as it is interrupted, it has put back what it
   * changed.
   */
  public abstract void begin(Connection connection, Provider provider) throws QueryException;

  /** Ends what {@link #begin} began, and puts back what it changed of the session. */
  public abstract void end(Connection connection, Provider provider) throws QueryException;

  /**
   * The objects of the schema or database, those of the database's own schemas where it has
   * schemas, by schema and then as {@link Item.Kind} orders them; read in the snapshot.
   */
  public abstract List<Listed> list(Connection connection, Provider provider, Container container)
      throws QueryException;

  /**
   * The definitions of the objects, in their order, as they are now; read in the snapshot.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where one no longer exists
   */
  public abstract List<Definition> define(
      Connection connection, Provider provider, List<Item> items) throws QueryException;

  /**
   * What the view uses and what uses it, as far as the server records it; read in the snapshot.
   */
  public abstract References references(Connection connection, Provider provider, Table view)
      throws QueryException;

  /**
   * Whether the materialized view's rows can be refreshed without keeping others from reading it,
   * {@code CONCURRENTLY}: where it holds rows and has a unique index of columns over all of them;
   * false for any other view. Read in the snapshot.
   */
  public abstract boolean refreshesConcurrently(
      Connection connection, Provider provider, Table view) throws QueryException;

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
}

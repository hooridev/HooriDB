package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.query.QueryException;
import java.util.List;

/**
 * How a provider of SQL reads the definitions of its objects, the statements that create them as
 * they are now: the one place that the export to a file ({@link dev.hoori.db.core.dump.Dump}), a
 * view's tab ({@link Views}) and any tool that copies an object's structure build on. A provider's
 * dialect hands one out, {@code provider.dialect().definitions()}; what it sends goes through the
 * {@link Sql} its caller gives, as that one's purpose.
 *
 * <p>Everything is read in one transaction, read only and in one snapshot, which {@link #begin}
 * begins and {@link #end} ends, with the settings in which the server writes the definitions as
 * they are read back: every name qualified where the provider has schemas, and on a connection on
 * the object's database where it has none, which the server then names without it.
 */
public abstract sealed class DefinitionDialect
    permits MySqlFamilyDefinitions, PostgreSqlDefinitions {

  DefinitionDialect() {}

  /** What definitions are read for, which decides what of the server's own they keep. */
  public enum Mode {
    /**
     * For a file that another server reads back: without what binds them to this one where the
     * provider writes that, as MariaDB's and MySQL's definer and their own database's name and
     * a routine in the SQL mode it was created in; each object with what it needs among those of
     * its schema or database, which are all read for it.
     */
    EXPORT,
    /**
     * For a change on this server: as the server has them, its definer kept, and nothing read but
     * the objects asked for, without what they need.
     */
    EDIT
  }

  /**
   * Begins the transaction, read only and in one snapshot, that definitions are read in, on a
   * connection in auto-commit. Where it fails, also as it is interrupted, it has put back what it
   * changed.
   */
  public abstract void begin(Sql sql) throws QueryException;

  /** Ends what {@link #begin} began, and puts back what it changed of the session. */
  public abstract void end(Sql sql) throws QueryException;

  /**
   * The objects of the schema or database, those of the database's own schemas where it has
   * schemas, by schema and then as {@link Item.Kind} orders them; read in the snapshot.
   */
  public abstract List<Listed> list(Sql sql, Container container) throws QueryException;

  /**
   * The definitions of the objects, in their order, as they are now and as the mode has them;
   * read in the snapshot.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where one no longer exists
   */
  public abstract List<Definition> define(Sql sql, List<Item> items, Mode mode)
      throws QueryException;
}

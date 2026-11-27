package dev.hoori.db.core.definition;

import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.query.QueryException;

/**
 * What reads a view's definition and changes it, on one connection: {@link SqlViews}, or a made-up
 * one in a test. Each call blocks.
 */
public interface Views {

  /**
   * The view's definition, what it uses and what uses it, read in one snapshot.
   *
   * @throws QueryException {@link QueryException.Failure#NOT_FOUND} where it no longer exists
   */
  ViewDefinition read(Table view) throws QueryException;

  /**
   * Makes the change, in one transaction where it says so. A read-only connection refuses it
   * without sending anything.
   *
   * @throws QueryException as the server failed it; where it ran in a transaction, nothing of it
   *     was kept
   */
  Changed change(ViewChange change) throws QueryException;

  /** What a change came to. */
  sealed interface Changed {}

  /** It was made. */
  record Done() implements Changed {}

  /**
   * The server refused to replace the view in place, as its columns would change, which it does
   * not let them, {@link DefinitionDialect#keepsColumns}; nothing was changed. Only a {@link
   * ViewChange.Kind#REPLACE} comes to this, which {@link ViewChange#recreate} can then make.
   *
   * @param words what the server said
   */
  record KeepsColumns(String words) implements Changed {}
}

package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.definition.Listed;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What exports a server's objects, on one connection: {@link SqlExporter} with {@link Dump}, or a
 * made-up one in a test. Each call blocks, as {@link Dump}'s.
 */
public interface Exporter {

  /** The objects of the schema or database, as {@link Dump#list}. */
  List<Listed> list(Container container) throws QueryException;

  /** Writes the chosen objects to the file, as {@link Dump#write}. */
  Dump.Written write(Dump.Choice choice, Path file, Dump.Progress progress)
      throws QueryException, IOException;
}

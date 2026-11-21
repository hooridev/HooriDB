package dev.hoori.db.core.dump;

import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.QueryException;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/** The exporter of a server of SQL, on a JDBC connection, by {@link Dump}. */
public record SqlExporter(Connection connection, Provider provider) implements Exporter {

  @Override
  public List<Listed> list(Container container) throws QueryException {
    return Dump.list(connection, provider, container);
  }

  @Override
  public Dump.Written write(Dump.Choice choice, Path file, Dump.Progress progress)
      throws QueryException, IOException {
    return Dump.write(connection, provider, choice, file, progress);
  }
}

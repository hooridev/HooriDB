package dev.hoori.db.app;

import dev.hoori.db.core.catalog.Catalog;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Connector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.driver.LoadedDriver;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Opens connections to servers and reads their catalogue. Each call blocks, off the app's thread,
 * and an interrupt of the calling thread cancels it. The app gets it from outside, so that a test
 * can put made-up servers in its place.
 */
@FunctionalInterface
interface Servers {

  /** Through the driver to the real server, with {@link Connector} and {@link Catalog}. */
  Servers SERVER = (profile, password, driver) -> {
    Connection connection;
    try {
      connection = Connector.open(profile, password, driver, Tester.TIMEOUT);
    } catch (ConnectionException e) {
      throw new NotConnected(e.failure(), e.detail());
    }
    try {
      var metadata = connection.getMetaData();
      var version = ServerVersion.parse(metadata.getDatabaseProductVersion());
      // shortcut: core has no capability that says a connection reads one database only; ask it
      // for one once it has, instead of naming the provider.
      var database = profile.provider() == Provider.POSTGRESQL ? connection.getCatalog() : null;
      return new Real(connection, profile.provider(), version, database);
    } catch (SQLException | RuntimeException e) {
      Connector.close(connection);
      throw new NotConnected(ConnectionException.Failure.OTHER, State.message(e));
    }
  };

  /** Opens a connection to the profile's server and database. */
  Link open(ConnectionProfile profile, char[] password, LoadedDriver driver) throws NotConnected;

  /** An open connection. It serves one call at a time. */
  interface Link {

    ServerVersion version();

    /** The one database this connection reads, or null where it reads every database. */
    String database();

    List<Database> databases() throws NotRead;

    List<Schema> schemas(Database database) throws NotRead;

    /** Of a schema, or of a database where the server has no schemas. */
    List<Table> tables(Container container) throws NotRead;

    List<Column> columns(Table table) throws NotRead;

    /** Closes it; a failure to close is not reported. */
    void close();
  }

  /** Why a connection could not be opened, as {@link ConnectionException} says it. */
  final class NotConnected extends Exception {
    final ConnectionException.Failure failure;
    final String detail;

    NotConnected(ConnectionException.Failure failure, String detail) {
      super(failure.toString(), null, false, false);
      this.failure = failure;
      this.detail = detail;
    }
  }

  /** Why the catalogue could not be read, as {@link CatalogException} says it. */
  final class NotRead extends Exception {
    final CatalogException.Failure failure;
    final String detail;

    NotRead(CatalogException.Failure failure, String detail) {
      super(failure.toString(), null, false, false);
      this.failure = failure;
      this.detail = detail;
    }
  }

  /** A connection of a driver. */
  record Real(Connection connection, Provider provider, ServerVersion version, String database)
      implements Link {

    private interface Read<T> {
      T read() throws CatalogException;
    }

    private static <T> T read(Read<T> read) throws NotRead {
      try {
        return read.read();
      } catch (CatalogException e) {
        throw new NotRead(e.failure(), e.detail());
      }
    }

    @Override
    public List<Database> databases() throws NotRead {
      return read(() -> Catalog.databases(connection, provider));
    }

    @Override
    public List<Schema> schemas(Database database) throws NotRead {
      return read(() -> Catalog.schemas(connection, provider, database));
    }

    @Override
    public List<Table> tables(Container container) throws NotRead {
      return read(() -> Catalog.tables(connection, provider, container));
    }

    @Override
    public List<Column> columns(Table table) throws NotRead {
      return read(() -> Catalog.columns(connection, provider, table));
    }

    @Override
    public void close() {
      Connector.close(connection);
    }
  }
}

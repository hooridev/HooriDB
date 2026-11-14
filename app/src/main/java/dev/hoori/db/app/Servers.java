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
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.util.List;
import java.util.OptionalLong;

/**
 * Opens connections to servers and reads their catalogue and the rows of their tables. Each call blocks, off the app's thread,
 * and an interrupt of the calling thread cancels it. The app gets it from outside, so that a test
 * can put made-up servers in its place.
 */
@FunctionalInterface
interface Servers {

  /** Through the driver to the real server, with {@link Connector} and {@link Catalog}. */
  Servers SERVER = (profile, password, driver) -> {
    var opened = Connector.open(profile, password, driver, Tester.TIMEOUT);
    return new Real(
        opened.connection(), profile.provider(), opened.server().parsed(), opened.database());
  };

  /** Opens a connection to the profile's server and database. */
  Link open(ConnectionProfile profile, char[] password, LoadedDriver driver)
      throws ConnectionException;

  /** An open connection. It serves one call at a time. */
  interface Link {

    ServerVersion version();

    /** The database the connection is on, or null where the driver names none. */
    String database();

    List<Database> databases() throws CatalogException;

    List<Schema> schemas(Database database) throws CatalogException;

    /** Of a schema, or of a database where the server has no schemas. */
    List<Table> tables(Container container) throws CatalogException;

    List<Column> columns(Table table) throws CatalogException;

    /** The server's estimate of the table's rows, or empty where it has none, as {@link Rows}. */
    OptionalLong estimate(Table table) throws QueryException;

    /** The exact number of the table's rows. */
    long count(Table table) throws QueryException;

    /** The rows from {@code from} up to {@code to}, fewer at the end, as {@link Rows#read}. */
    Page read(Table table, List<Column> columns, long from, long to) throws QueryException;

    /** Closes it; a failure to close is not reported. */
    void close();
  }

  /** A connection of a driver. */
  record Real(Connection connection, Provider provider, ServerVersion version, String database)
      implements Link {

    @Override
    public List<Database> databases() throws CatalogException {
      return Catalog.databases(connection, provider);
    }

    @Override
    public List<Schema> schemas(Database database) throws CatalogException {
      return Catalog.schemas(connection, provider, database);
    }

    @Override
    public List<Table> tables(Container container) throws CatalogException {
      return Catalog.tables(connection, provider, container);
    }

    @Override
    public List<Column> columns(Table table) throws CatalogException {
      return Catalog.columns(connection, provider, table);
    }

    @Override
    public OptionalLong estimate(Table table) throws QueryException {
      return Rows.estimate(connection, provider, table);
    }

    @Override
    public long count(Table table) throws QueryException {
      return Rows.count(connection, provider, table);
    }

    @Override
    public Page read(Table table, List<Column> columns, long from, long to) throws QueryException {
      return Rows.read(connection, provider, table, columns, from, to);
    }

    @Override
    public void close() {
      Connector.close(connection);
    }
  }
}

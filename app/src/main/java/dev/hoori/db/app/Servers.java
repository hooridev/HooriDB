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
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens connections to servers, reads their catalogue and the rows of their tables, and runs
 * statements. Each call blocks, off the app's thread, and an interrupt of the calling thread cancels it. The app gets it from outside, so that a test
 * can put made-up servers in its place.
 */
@FunctionalInterface
interface Servers {

  /** Through the driver to the real server, with {@link Connector} and {@link Catalog}. */
  Servers SERVER = (profile, password, driver) -> {
    var opened = Connector.open(profile, password, driver, Tester.TIMEOUT);
    return new Real(
        opened.connection(),
        profile.provider(),
        opened.server().parsed(),
        opened.database(),
        new AtomicReference<>());
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

    /** Of every schema of the database that is not the system's, in one call. */
    List<Table> allTables(Database database) throws CatalogException;

    List<Column> columns(Table table) throws CatalogException;

    /** The server's estimate of the table's rows, or empty where it has none, as {@link Rows}. */
    OptionalLong estimate(Table table) throws QueryException;

    /** The exact number of the table's rows. */
    long count(Table table) throws QueryException;

    /** The rows from {@code from} up to {@code to}, fewer at the end, as {@link Rows#read}. */
    Page read(Table table, List<Column> columns, long from, long to) throws QueryException;

    /** A statement to run, as {@link Execution} runs it; nothing runs before its first next. */
    Run run(String sql);

    /** Makes the database the connection's own, where the server switches on one connection. */
    void use(String database) throws QueryException;

    /**
     * Turns auto-commit off for a transaction that the user ends, or on again, where it is not so
     * already. Turned on, pgjdbc commits a transaction that is open: the caller ends it first.
     */
    void manual(boolean manual) throws QueryException;

    /** The connection's transaction and auto-commit as they are now, as {@link Status#of}. */
    Status status() throws QueryException;

    /**
     * Gives up the statement whose rows are not all read, if one is: what that failed with, as a
     * commit of what it changed, or empty.
     */
    Optional<Failure> giveUp();

    /**
     * Closes it, giving up a statement whose rows are not all read first; what giving that up failed
     * with, as a commit of what it changed, or empty. A failure to close is not reported.
     */
    Optional<Failure> close();
  }

  /** A statement run on a link, as {@link Execution}: it has the link until it is over. */
  interface Run {

    /** Its results up to the given number of rows, then as many more, as {@link Execution#next}. */
    Portion next(int count);

    /**
     * Ends it, giving up the rows not read; what giving up failed with, as a commit of what the
     * statement changed, or empty.
     */
    Optional<Failure> close();
  }

  /** A connection of a driver. */
  record Real(
      Connection connection,
      Provider provider,
      ServerVersion version,
      String database,
      AtomicReference<Execution> open) // the statement whose rows are not all read, or null
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
    public List<Table> allTables(Database database) throws CatalogException {
      return Catalog.allTables(connection, provider, database);
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
    public Run run(String sql) {
      var execution = new Execution(connection, provider, sql);
      open.set(execution);
      return new Run() {
        @Override
        public Portion next(int count) {
          return execution.next(count);
        }

        @Override
        public Optional<Failure> close() {
          open.compareAndSet(execution, null);
          return execution.abandon();
        }
      };
    }

    @Override
    public void use(String database) throws QueryException {
      // Through core's worker, so that an interrupt never lands in the driver's socket I/O.
      Query.run(connection, provider, () -> {
        connection.setCatalog(database);
        return null;
      });
    }

    @Override
    public void manual(boolean manual) throws QueryException {
      Query.run(connection, provider, () -> {
        if (connection.getAutoCommit() == manual) {
          connection.setAutoCommit(!manual);
        }
        return null;
      });
    }

    @Override
    public Status status() throws QueryException {
      return Status.of(connection, provider);
    }

    @Override
    public Optional<Failure> giveUp() {
      // A statement that ran well keeps what it changed, though its rows were not all read.
      var left = open.getAndSet(null);
      return left == null ? Optional.empty() : left.abandon();
    }

    @Override
    public Optional<Failure> close() {
      var failure = giveUp();
      Connector.close(connection);
      return failure;
    }
  }
}

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
import dev.hoori.db.core.connect.DocumentConnector;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.DocumentRun;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Plan;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.Query;
import dev.hoori.db.core.query.QueryException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens connections to servers, reads their catalog and the rows of their tables, and runs
 * statements. Each call blocks, off the app's thread, and an interrupt of the calling thread cancels it. The app gets it from outside, so that a test
 * can put made-up servers in its place.
 */
@FunctionalInterface
interface Servers {

  /**
   * Through the driver to the real server: with {@link Connector} and {@link Catalog} to one of
   * SQL, with {@link DocumentConnector} and a {@link DocumentSession} to one of documents.
   */
  Servers SERVER = (profile, password, driver) -> {
    if (driver.documents()) {
      var opened = DocumentConnector.open(profile, password, driver, Tester.TIMEOUT);
      var session =
          new DocumentSession(opened.client(), profile.provider().documents(), profile.readOnly());
      return Documents.of(
          session,
          opened.server().parsed(),
          opened.database(),
          profile.provider().documents().defaultDatabase());
    }
    var opened = Connector.open(profile, password, driver, Tester.TIMEOUT);
    return Real.of(
        opened.connection(),
        profile.provider(),
        opened.server().parsed(),
        opened.database(),
        profile.readOnly());
  };

  String GONE = "The connection was lost or given up: nothing more is sent on it.";

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

    /**
     * The planner's estimate of the rows the statement gives, without running it, its names read on
     * the database, as {@link Plan#rows}: the transaction stays as it was.
     */
    OptionalLong estimate(String database, String sql) throws QueryException;

    /** The rows from {@code from} up to {@code to}, fewer at the end, as {@link Rows#read}. */
    Page read(Table table, List<Column> columns, long from, long to) throws QueryException;

    /** The whole text of a column of the row its key names, as {@link Rows#whole}. */
    String whole(Table table, List<Column> columns, List<Object> row, int column)
        throws QueryException;

    /**
     * A statement to run, as {@link Execution} runs it; nothing runs before its first next.
     *
     * @throws QueryException {@link QueryException.Failure#CONNECTION_LOST} on a link that core
     *     reported lost
     */
    Run run(String sql) throws QueryException;

    /** A statement of the MongoDB shell, which runs as it was read: by its text elsewhere. */
    default Run run(Script.Parsed statement) throws QueryException {
      return run(statement.statement());
    }

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
     * Gives up the statement whose rows are not all read, if one is, as {@link Execution#abandon}:
     * what its end failed with, as a commit of what it changed, and what the server said.
     */
    Abandoned giveUp();

    /**
     * Closes it, giving up a statement whose rows are not all read first, as {@link #giveUp} says;
     * each within a limit, after which the connection is aborted. A failure to close is not
     * reported.
     */
    Abandoned close();

    /** Gives the connection up at once, from any thread, as one whose call never ends is. */
    void abort();
  }

  /** A statement run on a link, as {@link Execution}: it has the link until it is over. */
  interface Run {

    /** Its results up to the given number of rows, then as many more, as {@link Execution#next}. */
    Portion next(int count);

    /** Ends it, giving up the rows not read, as {@link Execution#abandon} says. */
    Abandoned close();
  }

  /**
   * A client of a server of documents. Its collections are its tables, as the tree and a table's
   * tab read them, and the fields its documents have its columns; it has no schemas, no plans and
   * no transactions. Once a call found the connection lost, it is gone for good, as {@link Real}.
   */
  record Documents(
      DocumentSession session,
      ServerVersion version,
      String database,
      AtomicReference<String> current, // the database a run is on
      AtomicReference<DocumentRun> open, // the statement whose documents are not all read
      AtomicBoolean gone)
      implements Link {

    /**
     * @param database the database the profile names, or null for none
     * @param otherwise the database a run is on where the profile names none
     */
    static Documents of(
        DocumentSession session, ServerVersion version, String database, String otherwise) {
      return new Documents(
          session,
          version,
          database,
          new AtomicReference<>(database != null ? database : otherwise),
          new AtomicReference<>(),
          new AtomicBoolean());
    }

    private void usable() throws QueryException {
      if (gone.get()) {
        throw QueryException.of(QueryException.Failure.CONNECTION_LOST, GONE);
      }
    }

    private void readable() throws CatalogException {
      if (gone.get()) {
        throw CatalogException.of(CatalogException.Failure.CONNECTION_LOST, GONE);
      }
    }

    private CatalogException saw(CatalogException e) {
      if (e.failure() == CatalogException.Failure.CONNECTION_LOST) {
        gone.set(true);
      }
      return e;
    }

    private QueryException saw(QueryException e) {
      if (e.failure() == QueryException.Failure.CONNECTION_LOST) {
        gone.set(true);
      }
      return e;
    }

    @Override
    public List<Database> databases() throws CatalogException {
      readable();
      try {
        return session.databases();
      } catch (CatalogException e) {
        throw saw(e);
      }
    }

    @Override
    public List<Schema> schemas(Database database) {
      return List.of();
    }

    @Override
    public List<Table> tables(Container container) throws CatalogException {
      readable();
      try {
        return session.collections(container.database());
      } catch (CatalogException e) {
        throw saw(e);
      }
    }

    @Override
    public List<Table> allTables(Database database) throws CatalogException {
      return tables(database);
    }

    @Override
    public List<Column> columns(Table table) throws CatalogException {
      readable();
      try {
        return session.fields(table);
      } catch (CatalogException e) {
        throw saw(e);
      }
    }

    @Override
    public OptionalLong estimate(Table table) throws QueryException {
      usable();
      try {
        return session.estimate(table);
      } catch (QueryException e) {
        throw saw(e);
      }
    }

    @Override
    public long count(Table table) throws QueryException {
      usable();
      try {
        return session.count(table);
      } catch (QueryException e) {
        throw saw(e);
      }
    }

    /** A server of documents has no planner to ask. */
    @Override
    public OptionalLong estimate(String database, String sql) {
      return OptionalLong.empty();
    }

    @Override
    public Page read(Table table, List<Column> columns, long from, long to) throws QueryException {
      usable();
      try {
        return session.read(table, columns, from, to);
      } catch (QueryException e) {
        throw saw(e);
      }
    }

    @Override
    public String whole(Table table, List<Column> columns, List<Object> row, int column)
        throws QueryException {
      usable();
      try {
        return session.whole(table, columns, row, column);
      } catch (QueryException e) {
        throw saw(e);
      }
    }

    /** A statement of the MongoDB shell, on the database the link is on now. */
    @Override
    public Run run(String sql) throws QueryException {
      return run(Script.Parsed.of(sql));
    }

    @Override
    public Run run(Script.Parsed statement) throws QueryException {
      usable();
      var running = new DocumentRun(session, current.get(), statement, current::set);
      open.set(running);
      return new Run() {
        @Override
        public Portion next(int count) {
          var portion = running.next(count);
          for (var result : portion.results()) {
            if (result instanceof Failure f && f.kind() == Failure.Kind.CONNECTION_LOST) {
              gone.set(true);
            }
          }
          return portion;
        }

        @Override
        public Abandoned close() {
          open.compareAndSet(running, null);
          return ended(running.close());
        }
      };
    }

    // Freeing a cursor that found the connection lost leaves the link gone, as a call does.
    private Abandoned ended(Abandoned abandoned) {
      if (abandoned.failure().isPresent()
          && abandoned.failure().get().kind() == Failure.Kind.CONNECTION_LOST) {
        gone.set(true);
      }
      return abandoned;
    }

    @Override
    public void use(String database) throws QueryException {
      usable();
      current.set(database);
    }

    @Override
    public void manual(boolean manual) throws QueryException {
      if (manual) {
        throw QueryException.of(
            QueryException.Failure.OTHER, "A server of documents has no transactions to end");
      }
    }

    /** Each command commits by itself, and none is ever open. */
    @Override
    public Status status() {
      return new Status(Transaction.NONE, true);
    }

    @Override
    public Abandoned giveUp() {
      var left = open.getAndSet(null);
      return left == null ? Abandoned.NOTHING : ended(left.close());
    }

    /** Each within {@link DocumentSession#GRACE}, after which it is given up. */
    @Override
    public Abandoned close() {
      var ended = giveUp();
      gone.set(true);
      session.close();
      return ended;
    }

    @Override
    public void abort() {
      gone.set(true);
      session.close();
    }
  }

  /**
   * A connection of a driver. Once core has said that it is lost or given up, as when ending a
   * statement found the server gone or ran past its deadline, it is gone for good: every later
   * call fails as lost, before it touches the connection.
   */
  record Real(
      Connection connection,
      Provider provider,
      ServerVersion version,
      String database,
      boolean readOnly, // of a read-only profile, whose session the server keeps read-only
      AtomicReference<Execution> open, // the statement whose rows are not all read, or null
      AtomicBoolean gone)
      implements Link {

    static Real of(
        Connection connection,
        Provider provider,
        ServerVersion version,
        String database,
        boolean readOnly) {
      return new Real(
          connection,
          provider,
          version,
          database,
          readOnly,
          new AtomicReference<>(),
          new AtomicBoolean());
    }

    private void usable() throws QueryException {
      if (gone.get()) {
        throw QueryException.of(QueryException.Failure.CONNECTION_LOST, GONE);
      }
    }

    private void readable() throws CatalogException {
      if (gone.get()) {
        throw CatalogException.of(CatalogException.Failure.CONNECTION_LOST, GONE);
      }
    }

    /** What core said: a failure that leaves the connection lost makes the link gone. */
    private <T> T saw(T said, Failure failure) {
      if (failure != null && failure.kind() == Failure.Kind.CONNECTION_LOST) {
        gone.set(true);
      }
      return said;
    }

    private Abandoned saw(Abandoned ended) {
      return saw(ended, ended.failure().orElse(null));
    }

    @Override
    public List<Database> databases() throws CatalogException {
      readable();
      return Catalog.databases(connection, provider);
    }

    @Override
    public List<Schema> schemas(Database database) throws CatalogException {
      readable();
      return Catalog.schemas(connection, provider, database);
    }

    @Override
    public List<Table> tables(Container container) throws CatalogException {
      readable();
      return Catalog.tables(connection, provider, container);
    }

    @Override
    public List<Table> allTables(Database database) throws CatalogException {
      readable();
      return Catalog.allTables(connection, provider, database);
    }

    @Override
    public List<Column> columns(Table table) throws CatalogException {
      readable();
      return Catalog.columns(connection, provider, table);
    }

    @Override
    public OptionalLong estimate(Table table) throws QueryException {
      usable();
      return Rows.estimate(connection, provider, table);
    }

    @Override
    public OptionalLong estimate(String database, String sql) throws QueryException {
      usable();
      return Plan.rows(connection, provider, database, sql);
    }

    @Override
    public long count(Table table) throws QueryException {
      usable();
      return Rows.count(connection, provider, table);
    }

    @Override
    public Page read(Table table, List<Column> columns, long from, long to) throws QueryException {
      usable();
      return Rows.read(connection, provider, table, columns, from, to);
    }

    @Override
    public String whole(Table table, List<Column> columns, List<Object> row, int column)
        throws QueryException {
      usable();
      return Rows.whole(connection, provider, table, columns, row, column);
    }

    @Override
    public Run run(String sql) throws QueryException {
      usable();
      var execution = new Execution(connection, provider, sql, readOnly);
      open.set(execution);
      return new Run() {
        @Override
        public Portion next(int count) {
          var portion = execution.next(count);
          Failure lost = null;
          for (var result : portion.results()) {
            if (result instanceof Failure f && f.kind() == Failure.Kind.CONNECTION_LOST) {
              lost = f;
            }
          }
          return saw(portion, lost);
        }

        @Override
        public Abandoned close() {
          open.compareAndSet(execution, null);
          return saw(execution.abandon());
        }
      };
    }

    @Override
    public void use(String database) throws QueryException {
      usable();
      // Through core's worker, so that an interrupt never lands in the driver's socket I/O.
      Journal.call(
          Purpose.SESSION,
          Journal.catalog(database),
          () -> Query.run(connection, provider, () -> {
            connection.setCatalog(database);
            return null;
          }));
    }

    @Override
    public void manual(boolean manual) throws QueryException {
      usable();
      Query.run(connection, provider, () -> {
        if (connection.getAutoCommit() == manual) {
          Journal.call(Purpose.TRANSACTION, Journal.autoCommit(!manual), () -> {
            connection.setAutoCommit(!manual);
            return null;
          });
        }
        return null;
      });
    }

    @Override
    public Status status() throws QueryException {
      usable();
      return Status.of(connection, provider);
    }

    @Override
    public Abandoned giveUp() {
      // A statement that ran well keeps what it changed, though its rows were not all read.
      var left = open.getAndSet(null);
      return left == null ? Abandoned.NOTHING : saw(left.abandon());
    }

    @Override
    public Abandoned close() {
      var ended = giveUp();
      try {
        Query.within(connection, provider, Query.GRACE, () -> {
          Connector.close(connection);
          return null;
        });
      } catch (QueryException _) {
        // Given up: what mattered, the statement's end, is said already.
      }
      return ended;
    }

    @Override
    public void abort() {
      var aborter = Thread.ofVirtual().name("abort a connection").start(() -> {
        try {
          connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException _) {
          // Not even an abort is possible; the connection is given up all the same.
        }
      });
      try {
        aborter.join(Query.GRACE);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }
}

package dev.hoori.db.app;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Editable;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.Monitor;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.ServerVariable;
import dev.hoori.db.core.query.QueryException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Servers whose connections log what they send: each call on a link binds core's {@link Journal}
 * to the statement log, with the connection and the database the call is about, while logging is
 * on. What holds the connection's password, a statement or a server's words, is withheld, as a
 * console withholds it.
 */
record Recorded(Servers servers, StatementLog log) implements Servers {

  /** What a statement that holds the connection's password is logged as. */
  static final String WITHHELD = "/* Not shown, as it holds the connection's password. */";

  @Override
  public Link open(
      ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout)
      throws ConnectionException {
    // What opening sends, as a read-only profile's statement, is logged as the link's calls are.
    var id = profile.id();
    var secret = new Secret(password.clone());
    try {
      var link = log.on()
          ? Journal.recording(
              sent -> secret.log(log, id, sent, null),
              () -> servers.open(profile, password, driver, timeout))
          : servers.open(profile, password, driver, timeout);
      return new RecordedLink(link, id, secret, log);
    } catch (ConnectionException | RuntimeException e) {
      secret.wipe();
      throw e;
    }
  }

  /**
   * The connection's password, as long as what its link sends is logged: work that outlives the
   * link, as a give-up whose worker the server keeps waiting, logs nothing once it is wiped, as the
   * password could no longer be found in what it sent.
   */
  static final class Secret {

    private final char[] password;
    private boolean wiped;

    Secret(char[] password) {
      this.password = password;
    }

    // Logs the entry without the password, unless the password went.
    void log(StatementLog log, UUID connection, Sent sent, String database) {
      Sent checked;
      synchronized (this) {
        if (wiped) {
          return;
        }
        checked = checked(sent, database, password);
      }
      log.add(new Logged(connection, checked));
    }

    synchronized void wipe() {
      wiped = true;
      Arrays.fill(password, '\0');
    }
  }

  // The entry as it is logged: on the database it was sent to, and without what holds the
  // password, in its text, its database or its failure's message.
  private static Sent checked(Sent sent, String database, char[] password) {
    var on = sent.database() != null ? sent.database() : database;
    if (on != null && Probe.contains(on, password)) {
      on = null;
    }
    var text = Probe.contains(sent.text(), password) ? WITHHELD : sent.text();
    var outcome =
        sent.outcome() instanceof Outcome.Failed f && Probe.contains(f.message(), password)
            ? new Outcome.Failed(ConnectionException.WITHHELD)
            : sent.outcome();
    return new Sent(sent.at(), sent.purpose(), on, text, sent.took(), outcome);
  }

  /** A link that logs each of its calls; its copy of the password goes when it closes or aborts. */
  static final class RecordedLink implements Link {

    private final Link link;
    private final UUID connection;
    private final Secret secret;
    private final StatementLog log;
    private volatile String current; // the database the session is on, as far as is known

    RecordedLink(Link link, UUID connection, Secret secret, StatementLog log) {
      this.link = link;
      this.connection = connection;
      this.secret = secret;
      this.log = log;
      this.current = link.database();
    }

    // The call, with what it sends logged about the database; the session's where null.
    private <T, E extends Exception> T recorded(String database, Journal.Call<T, E> call) throws E {
      if (!log.on()) {
        return call.call();
      }
      return Journal.recording(
          sent -> secret.log(log, connection, sent, database != null ? database : current), call);
    }

    private static String of(Container container) {
      return container.database().name();
    }

    private static String of(Table table) {
      return of(table.container());
    }

    @Override
    public ServerVersion version() {
      return link.version();
    }

    @Override
    public String database() {
      return link.database();
    }

    @Override
    public List<Database> databases() throws CatalogException {
      return recorded(null, link::databases);
    }

    @Override
    public List<Schema> schemas(Database database) throws CatalogException {
      return recorded(database.name(), () -> link.schemas(database));
    }

    @Override
    public List<Table> tables(Container container) throws CatalogException {
      return recorded(of(container), () -> link.tables(container));
    }

    @Override
    public List<Table> allTables(Database database) throws CatalogException {
      return recorded(database.name(), () -> link.allTables(database));
    }

    @Override
    public List<Column> columns(Table table) throws CatalogException {
      return recorded(of(table), () -> link.columns(table));
    }

    @Override
    public List<ForeignKey> foreignKeys(Table table) throws CatalogException {
      return recorded(of(table), () -> link.foreignKeys(table));
    }

    @Override
    public OptionalLong estimate(Table table, Rows.Filter filter) throws QueryException {
      return recorded(of(table), () -> link.estimate(table, filter));
    }

    @Override
    public long count(Table table, Rows.Filter filter) throws QueryException {
      return recorded(of(table), () -> link.count(table, filter));
    }

    @Override
    public OptionalLong estimate(String database, String sql) throws QueryException {
      var on = database == null || database.isEmpty() ? null : database;
      return recorded(on, () -> link.estimate(database, sql));
    }

    @Override
    public Page read(Table table, List<Column> columns, long from, long to, Rows.Filter filter)
        throws QueryException {
      return recorded(of(table), () -> link.read(table, columns, from, to, filter));
    }

    @Override
    public String whole(Table table, List<Column> columns, List<Object> row, int column)
        throws QueryException {
      return recorded(of(table), () -> link.whole(table, columns, row, column));
    }

    @Override
    public Optional<Editable> editable(
        String sql, List<Field> fields, boolean createdTemporaryTable) throws QueryException {
      return recorded(null, () -> link.editable(sql, fields, createdTemporaryTable));
    }

    @Override
    public Changes.Outcome write(Changes changes) throws QueryException {
      return recorded(of(changes.table()), () -> link.write(changes));
    }

    @Override
    public Run run(String sql) throws QueryException {
      return recorded(link.run(sql));
    }

    @Override
    public Run run(Script.Parsed statement) throws QueryException {
      return recorded(link.run(statement));
    }

    // A run whose portions and end are logged as the link's calls are.
    private Run recorded(Run run) {
      return new Run() {
        @Override
        public Portion next(int count) {
          return recorded(null, () -> run.next(count));
        }

        @Override
        public Abandoned close() {
          return recorded(null, run::close);
        }
      };
    }

    @Override
    public void use(String database) throws QueryException {
      recorded(database, () -> {
        link.use(database);
        return null;
      });
      current = database;
    }

    @Override
    public void manual(boolean manual) throws QueryException {
      recorded(null, () -> {
        link.manual(manual);
        return null;
      });
    }

    // Only cancels and ends are logged: the monitor's periodic reads run unbound so that they do
    // not fill the statement log.
    @Override
    public Monitor monitor() throws QueryException {
      var monitor = link.monitor();
      return new Monitor() {
        @Override
        public List<ServerSession> sessions() throws QueryException {
          return monitor.sessions();
        }

        @Override
        public boolean cancel(ServerSession session) throws QueryException {
          return recorded(null, () -> monitor.cancel(session));
        }

        @Override
        public boolean end(ServerSession session) throws QueryException {
          return recorded(null, () -> monitor.end(session));
        }

        @Override
        public List<ServerVariable> variables() throws QueryException {
          return monitor.variables();
        }

        @Override
        public List<Counter> status() throws QueryException {
          return monitor.status();
        }

        @Override
        public Optional<List<Block>> locks() throws QueryException {
          return monitor.locks();
        }
      };
    }

    @Override
    public Status status() throws QueryException {
      return recorded(null, link::status);
    }

    @Override
    public Abandoned giveUp() {
      return recorded(null, link::giveUp);
    }

    @Override
    public Abandoned close() {
      try {
        return recorded(null, link::close);
      } finally {
        secret.wipe();
      }
    }

    @Override
    public void abort() {
      try {
        link.abort();
      } finally {
        secret.wipe();
      }
    }
  }
}

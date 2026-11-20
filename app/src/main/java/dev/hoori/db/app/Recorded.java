package dev.hoori.db.app;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Sent;
import dev.hoori.db.core.query.QueryException;
import java.util.Arrays;
import java.util.List;
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
  public Link open(ConnectionProfile profile, char[] password, LoadedDriver driver)
      throws ConnectionException {
    var link = servers.open(profile, password, driver);
    return new RecordedLink(link, profile.id(), password.clone(), log);
  }

  /** A link that logs each of its calls; its copy of the password goes when it closes. */
  static final class RecordedLink implements Link {

    private final Link link;
    private final UUID connection;
    private final char[] password;
    private final StatementLog log;
    private volatile String current; // the database the session is on, as far as is known

    RecordedLink(Link link, UUID connection, char[] password, StatementLog log) {
      this.link = link;
      this.connection = connection;
      this.password = password;
      this.log = log;
      this.current = link.database();
    }

    // The call, with what it sends logged about the database; the session's where null.
    private <T, E extends Exception> T recorded(String database, Journal.Call<T, E> call) throws E {
      if (!log.on()) {
        return call.call();
      }
      return Journal.recording(
          sent -> log.add(new Logged(connection, checked(sent, database))), call);
    }

    private Sent checked(Sent sent, String database) {
      var on = sent.database() != null ? sent.database() : database != null ? database : current;
      var text = Probe.contains(sent.text(), password) ? WITHHELD : sent.text();
      var outcome =
          sent.outcome() instanceof Outcome.Failed f && Probe.contains(f.message(), password)
              ? new Outcome.Failed(ConnectionException.WITHHELD)
              : sent.outcome();
      return new Sent(sent.at(), sent.purpose(), on, text, sent.took(), outcome);
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
    public OptionalLong estimate(Table table) throws QueryException {
      return recorded(of(table), () -> link.estimate(table));
    }

    @Override
    public long count(Table table) throws QueryException {
      return recorded(of(table), () -> link.count(table));
    }

    @Override
    public OptionalLong estimate(String database, String sql) throws QueryException {
      var on = database == null || database.isEmpty() ? null : database;
      return recorded(on, () -> link.estimate(database, sql));
    }

    @Override
    public Page read(Table table, List<Column> columns, long from, long to) throws QueryException {
      return recorded(of(table), () -> link.read(table, columns, from, to));
    }

    @Override
    public String whole(Table table, List<Column> columns, List<Object> row, int column)
        throws QueryException {
      return recorded(of(table), () -> link.whole(table, columns, row, column));
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
        Arrays.fill(password, '\0');
      }
    }

    @Override
    public void abort() {
      link.abort();
    }
  }
}

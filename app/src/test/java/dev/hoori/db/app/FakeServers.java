package dev.hoori.db.app;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Execution;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.Credentials;
import dev.hoori.db.core.monitor.Monitor;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.ServerVariable;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Made-up servers, by host: what they hold is written as an outline, each call can be held at a
 * gate the test opens, or fail as the test says. They note every call, and whether two calls ever
 * ran at once on one connection.
 *
 * <p>An outline has a database per line, its schemas indented under it where the provider has
 * schemas, then the tables, then the columns as {@code name: type}. A {@code ~} before a name marks
 * a system database or schema, {@code @} a view and {@code *} a column of the primary key.
 *
 * <p>A table has rows made up from their position, of the kinds its columns' types say, 3 unless
 * {@link #rows} says how many, or the rows {@link #values} gives. Its rows' calls are keyed {@code
 * estimate <host>/<path>}, {@code count <host>/<path>} and {@code read <host>/<path> <from>-<to>},
 * the path as of {@code columns}, such as {@code pg.example.com/shop.public.orders}, and a whole
 * value's, which {@link #whole} gives, {@code whole <host>/<path> <column> <key>}, the key's values
 * joined by commas.
 *
 * <p>A statement gives what {@link #statement} says, on any server, and one it does not know fails
 * as invalid. Its calls are keyed {@code run <host> <sql>} for the first portion, {@code next
 * <host> <sql> <from>} for the next ones, and a switch of the database {@code use <host>
 * <database>}; a statement closed with rows unread notes {@code close <host> <sql>} among the
 * calls. A held statement that is interrupted ends as core's does: with a cancelled failure, the
 * thread left interrupted. Each portion took {@link #TOOK}.
 *
 * <p>Each connection keeps a transaction and auto-commit as the servers do. {@code BEGIN}, {@code
 * START TRANSACTION}, {@code COMMIT} and {@code ROLLBACK} are known statements without results; so
 * are, on MariaDB and MySQL, {@code SET autocommit=0} and {@code =1}, of which the second commits
 * what is open where auto-commit was off, and {@code SET completion_type=1}, after which a COMMIT
 * or ROLLBACK begins the next
 * transaction at once. With auto-commit off any other statement that ends well opens one, as does
 * one that {@link #begins} says begins one by itself, as a procedure may. On MariaDB and MySQL one
 * that begins with {@code CREATE}, {@code ALTER} or {@code DROP} commits it, also where it fails,
 * and a deadlock ({@link #deadlock}) or another failure of class 40 ({@link #rolledBack}) rolls
 * it back, also a COMMIT's; another failure leaves it open, a failed COMMIT or ROLLBACK among them.
 * {@link #rollBackBehind} ends it as a server does by itself. On PostgreSQL a failure or a cancel within it fails it, and then every
 * statement but a COMMIT, which rolls back, or a ROLLBACK fails as PostgreSQL's do; a failed COMMIT
 * ends it. A portion says it as core's do: on MariaDB and MySQL not after a failure or a cancel,
 * nor while rows are still to come, and nowhere after a lost connection. Turning auto-commit on
 * commits what is open, which notes {@code commit by auto-commit <host>} among the calls; a change
 * notes {@code autocommit <host> on} or {@code off}. Asking for it is keyed {@code transaction
 * <host>}.
 *
 * <p>A server's monitor lists the sessions {@link #sessions} gives after the monitor's own, whose
 * id is the number of its link; a read of them is keyed {@code sessions <host>}. Cancelling what one
 * runs, keyed {@code cancel <host> <operation>}, leaves it idle, and ending one, keyed {@code end
 * <host> <id>}, takes it out; either says false for a session the server does not have, or has
 * with another start ({@link #session(String, String, String, String)}) or, for a cancel, another
 * operation. Its
 * variables, status counters and locks are those {@link #variables}, {@link #counters} and {@link
 * #locks} give, read keyed {@code variables <host>}, {@code status <host>} and {@code locks
 * <host>}; a server whose locks are {@code null} does not tell them.
 */
final class FakeServers implements Servers {

  /** A made-up server: its kind, version and content, and the password it wants, or null. */
  record Made(Provider provider, String version, Outline root, String password) {}

  /** A line of the outline, with the lines indented under it. */
  record Outline(String text, List<Outline> children) {}

  private final Map<String, Made> servers = new ConcurrentHashMap<>();
  private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> passes = new ConcurrentHashMap<>(); // before the gate
  private final Map<String, Exception> failures = new ConcurrentHashMap<>();
  final List<String> calls = new CopyOnWriteArrayList<>(); // in the order they began
  // The calls of the links, each after the link's number, from 1 in the order they were opened.
  final List<String> linked = new CopyOnWriteArrayList<>();
  final List<String> done = new CopyOnWriteArrayList<>(); // the reads that returned
  final List<String> passwords = new CopyOnWriteArrayList<>();
  final List<String> closed = new CopyOnWriteArrayList<>(); // the links closed, by database
  final List<String> interrupted = new CopyOnWriteArrayList<>();
  final List<String> failed = new CopyOnWriteArrayList<>(); // the calls that failed as set
  volatile boolean overlapped; // two calls ran at once on one connection
  volatile boolean dead; // a gate ignores interrupts, as a server that no longer answers
  private final Map<String, Long> counts = new ConcurrentHashMap<>();
  private final Map<String, OptionalLong> estimates = new ConcurrentHashMap<>();
  private final Map<String, List<List<Object>>> values = new ConcurrentHashMap<>();
  private final Map<String, String> wholes = new ConcurrentHashMap<>();
  private final Map<String, Answer> statements = new ConcurrentHashMap<>();

  /** How long each portion of a statement took, as the server would say. */
  static final Duration TOOK = Duration.ofMillis(4);

  /** What a statement gives: its results, whose rows are read in portions, and its messages. */
  record Answer(List<Result> results, List<Message> messages) {}

  /** The statement, as written, gives these results, and the server says these messages. */
  FakeServers statement(String sql, List<Result> results, Message... messages) {
    statements.put(sql, new Answer(List.copyOf(results), List.of(messages)));
    return this;
  }

  /** The statement no longer gives what {@link #statement} said, as the server changed. */
  void forget(String sql) {
    statements.remove(sql);
  }

  private final java.util.Set<String> beginning = ConcurrentHashMap.newKeySet();

  /** The statement begins a transaction by itself and leaves it open, as a procedure may. */
  FakeServers begins(String sql) {
    beginning.add(sql);
    return this;
  }

  private final java.util.Set<String> turningOn = ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> blind = ConcurrentHashMap.newKeySet();
  private final Map<String, Failure> givingUp = new ConcurrentHashMap<>();
  private final Map<String, Message> givingUpSays = new ConcurrentHashMap<>();

  /**
   * The statement turns the server's auto-commit on, as a procedure that runs {@code SET
   * autocommit=1} does, also where it then fails, and without the driver knowing.
   */
  FakeServers turnsAutoCommitOn(String sql) {
    turningOn.add(sql);
    return this;
  }

  /** The host's MariaDB or MySQL driver cannot read the server's status, as old ones cannot. */
  FakeServers cannotReport(String host) {
    blind.add(host);
    return this;
  }

  /** Giving up the rest of the statement's rows says this, as a commit's notice may. */
  FakeServers warnsAtGiveUp(String sql, Message message) {
    givingUpSays.put(sql, message);
    return this;
  }

  /** Giving up the rest of the statement's rows fails so, as a commit of what it changed may. */
  FakeServers failsAtGiveUp(String sql, Failure failure) {
    givingUp.put(sql, failure);
    return this;
  }

  /** MariaDB's and MySQL's deadlock, which rolls the whole transaction back. */
  static Failure deadlock() {
    return new Failure(
        Failure.Kind.OTHER,
        "Deadlock found when trying to get lock; try restarting transaction",
        "40001",
        DEADLOCK,
        0,
        "",
        0,
        0,
        "",
        "",
        "",
        "");
  }

  static final int DEADLOCK = 1213;

  /** A failure of class 40, transaction rollback: the server rolled the transaction back. */
  static Failure rolledBack(String message) {
    return new Failure(Failure.Kind.OTHER, message, "40000", 0, 0, "", 0, 0, "", "", "", "");
  }

  private static boolean rolledBack(Failure failure) {
    return failure.code() == DEADLOCK || failure.state().startsWith("40");
  }

  private final List<FakeLink> links = new CopyOnWriteArrayList<>();

  /**
   * The server ends the open transactions of its connections by itself, rolled back, as after an
   * idle-in-transaction timeout or a kill of another session; the console learns of it only when
   * it asks.
   */
  void rollBackBehind(String host) {
    for (var link : links) {
      if (link.host.equals(host)) {
        link.transaction = Transaction.NONE;
      }
    }
  }

  private final Map<String, List<ServerSession>> sessions = new ConcurrentHashMap<>();

  /** The host's server has these sessions besides the monitor's own, as a test may change. */
  FakeServers sessions(String host, ServerSession... others) {
    sessions.put(host, List.of(others));
    return this;
  }

  private final Map<String, List<ServerVariable>> variables = new ConcurrentHashMap<>();
  private final Map<String, List<Counter>> counters = new ConcurrentHashMap<>();
  private final Map<String, Optional<List<Block>>> locks = new ConcurrentHashMap<>();

  /** The host's server has these variables. */
  FakeServers variables(String host, ServerVariable... all) {
    variables.put(host, List.of(all));
    return this;
  }

  /** The host's server has these status counters. */
  FakeServers counters(String host, Counter... all) {
    counters.put(host, List.of(all));
    return this;
  }

  /** These sessions of the host's server wait for locks; null for a server that does not tell. */
  FakeServers locks(String host, Block... all) {
    locks.put(host, all == null ? Optional.empty() : Optional.of(List.of(all)));
    return this;
  }

  /**
   * A session of another client, which runs the statement, cancellable, or is idle where it is
   * empty; it started at its first start.
   */
  static ServerSession session(String id, String user, String statement) {
    return session(id, user, statement, "start of " + id);
  }

  /** A session as {@link #session(String, String, String)}, with the start the server tells. */
  static ServerSession session(String id, String user, String statement, String started) {
    boolean runs = !statement.isEmpty();
    return new ServerSession(
        id,
        runs ? id : "",
        started,
        false,
        true,
        user,
        "psql (10.0.0." + id + ":5000)",
        "shop",
        runs ? "active" : "idle",
        statement,
        Duration.ofSeconds(Integer.parseInt(id)),
        "");
  }

  /** Rows of the columns, given as {@code name: type}, made up from their position. */
  static Result.Rows made(long count, String... columns) {
    var fields = Arrays.stream(columns)
        .map(c -> c.split(": ", 2))
        .map(c -> new Field(c[0], c[1], kind(c[1])))
        .toList();
    var rows = new ArrayList<List<Object>>();
    for (long i = 0; i < count; i++) {
      var row = new ArrayList<Object>();
      for (var field : fields) {
        row.add(value(field, i));
      }
      rows.add(Collections.unmodifiableList(row));
    }
    return new Result.Rows(fields, 0, List.copyOf(rows));
  }

  /** A failure of a statement, as the server says it. */
  static Failure serverError(Failure.Kind kind, String message) {
    return new Failure(kind, message, "", 0, 0, "", 0, 0, "", "", "", "");
  }

  /** A server at the host, of the outline. */
  FakeServers serve(String host, Provider provider, String version, String outline) {
    return serve(host, provider, version, outline, null);
  }

  FakeServers serve(
      String host, Provider provider, String version, String outline, String password) {
    servers.put(host, new Made(provider, version, parse(outline), password));
    return this;
  }

  /** Holds the calls of this key, such as {@code "tables pg/shop.public"}, until it opens. */
  CountDownLatch hold(String key) {
    var gate = new CountDownLatch(1);
    gates.put(key, gate);
    return gate;
  }

  /**
   * Holds the calls of this key once so many more of them have passed: as {@code hold("transaction
   * <host>", 1)} lets a statement read the status fresh and holds the question asked after it.
   */
  CountDownLatch hold(String key, int passing) {
    passes.put(key, new AtomicInteger(passing));
    return hold(key);
  }

  /** The calls of this key fail so, until {@link #heal}. */
  void fail(String key, Exception failure) {
    failures.put(key, failure);
  }

  void heal(String key) {
    failures.remove(key);
  }

  private final Map<String, OptionalLong> plans = new ConcurrentHashMap<>();

  /**
   * The server's plan of the statement estimates so many rows, none without this; its call is
   * keyed {@code explain <host> <sql>}, and leaves the transaction as it was, as core's does on a
   * connection of the app's own.
   */
  FakeServers plans(String sql, long rows) {
    plans.put(sql, OptionalLong.of(rows));
    return this;
  }

  /** The database the last estimate read its names on. */
  volatile String estimatedOn;

  /** The table at the path has so many rows, of which the server estimates so many, or none. */
  FakeServers rows(String path, long count, OptionalLong estimate) {
    counts.put(path, count);
    estimates.put(path, estimate);
    return this;
  }

  /** The table at the path has these rows, which the server estimates right. */
  FakeServers values(String path, List<List<Object>> rows) {
    values.put(path, rows);
    return rows(path, rows.size(), OptionalLong.of(rows.size()));
  }

  /**
   * The whole text of the column of the table at the path, for the row whose key has the values,
   * joined by commas; a row without one is not found.
   */
  FakeServers whole(String path, String column, String key, String text) {
    wholes.put(path + " " + column + " " + key, text);
    return this;
  }

  /** A failure of a call on rows, as core reports it, with the server's words. */
  static QueryException queryFailure(QueryException.Failure failure) {
    return QueryException.of(failure, "the server's words for " + failure);
  }

  void openAll() {
    gates.values().forEach(CountDownLatch::countDown);
  }

  static CatalogException notRead(CatalogException.Failure failure) {
    return CatalogException.of(failure, "the server's words for " + failure);
  }

  static ConnectionException notConnected(ConnectionException.Failure failure) {
    return ConnectionException.of(failure, "FATAL: the server's words for " + failure);
  }

  private void call(String key, boolean connecting) throws Exception {
    call(
        key,
        connecting
            ? ConnectionException.of(ConnectionException.Failure.CANCELLED, "")
            : CatalogException.of(CatalogException.Failure.CANCELLED, ""));
  }

  private void call(String key, Exception cancelled) throws Exception {
    calls.add(key);
    var gate = gates.get(key);
    var passing = passes.get(key);
    if (gate != null && passing != null && passing.getAndDecrement() > 0) {
      gate = null;
    }
    if (gate != null) {
      if (dead) {
        boolean interrupt = false;
        while (true) {
          try {
            gate.await();
            break;
          } catch (InterruptedException _) {
            interrupt = true;
          }
        }
        if (interrupt) {
          Thread.currentThread().interrupt();
        }
      } else {
        try {
          gate.await();
        } catch (InterruptedException _) {
          interrupted.add(key);
          Thread.currentThread().interrupt();
          // A failure the test set is how the cancel ended, as a connection given up.
          throw failures.getOrDefault(key, cancelled);
        }
      }
    }
    var failure = failures.get(key);
    if (failure != null) {
      failed.add(key);
      throw failure;
    }
  }

  @Override
  public Link open(ConnectionProfile profile, char[] password, LoadedDriver driver)
      throws ConnectionException {
    var made = servers.get(profile.host());
    if (made == null) {
      throw ConnectionException.of(ConnectionException.Failure.UNREACHABLE, "no such host");
    }
    passwords.add(new String(password));
    boolean perDatabase = made.provider() == Provider.POSTGRESQL;
    var database = profile.database().isBlank() ? profile.user() : profile.database();
    var key = "open " + profile.host() + (perDatabase ? "/" + database : "");
    try {
      call(key, true);
    } catch (ConnectionException e) {
      throw e;
    } catch (Exception e) {
      throw ConnectionException.of(ConnectionException.Failure.OTHER, e.toString());
    }
    if (made.password() != null && !made.password().equals(new String(password))) {
      throw ConnectionException.of(
          ConnectionException.Failure.LOGIN_REFUSED, "FATAL: password authentication failed");
    }
    if (perDatabase && find(made.root(), database) == null) {
      throw ConnectionException.of(
          ConnectionException.Failure.NO_SUCH_DATABASE,
          "FATAL: database \"" + database + "\" does not exist");
    }
    var link = new FakeLink(profile.host(), made, perDatabase ? database : null);
    link.current = perDatabase || profile.database().isBlank() ? null : profile.database();
    links.add(link);
    return link;
  }

  private final class FakeLink implements Link {
    private final int number = links.size() + 1;

    private void note(String key) {
      linked.add(number + " " + key);
    }

    private void noted(String key) {
      calls.add(key);
      note(key);
    }

    private final String host;
    private final Made made;
    private final String database;
    private final AtomicInteger busy = new AtomicInteger();
    // The session's auto-commit and transaction, on the lane's thread only.
    private boolean manual; // the server's auto-commit off, as its status says it
    private boolean seen; // the driver's own idea of manual, which may lag the server's
    private Transaction transaction = Transaction.NONE;
    // MariaDB's and MySQL's database, as the profile named it or an estimate left it; null for
    // none.
    private String current;
    private boolean lost; // a statement said the connection is gone: nothing is known of it
    private boolean chain; // MariaDB's and MySQL's completion_type=1
    // A statement whose rows are not all read, which closing the link gives up first, as Real's.
    private Run giving;
    // PostgreSQL's query streaming in a transaction core opened for it in auto-commit, while
    // pgjdbc has auto-commit off: core's portions say neither, the driver's state both.
    private boolean own;

    // What the server holds now, which a test may change between two reads.
    private Outline root() {
      return servers.get(host).root();
    }

    FakeLink(String host, Made made, String database) {
      this.host = host;
      this.made = made;
      this.database = database;
    }

    @Override
    public ServerVersion version() {
      return ServerVersion.parse(made.version());
    }

    @Override
    public String database() {
      return database;
    }

    // A catalog read, sent through core's seam as core's are, by its key.
    private <T> List<T> read(String key, Database of, Reader<T> reader) throws CatalogException {
      return Journal.sql(
          made.provider(),
          Purpose.CATALOG,
          key,
          list -> Outcome.rows(list.size()),
          () -> catalog(key, of, reader));
    }

    private <T> List<T> catalog(String key, Database of, Reader<T> reader) throws CatalogException {
      if (busy.incrementAndGet() > 1) {
        overlapped = true;
      }
      try {
        if (database != null && of != null && !of.name().equals(database)) {
          throw CatalogException.of(CatalogException.Failure.WRONG_DATABASE, of.name());
        }
        // What the server holds when the call begins, as a query sees it.
        var read = reader.read();
        try {
          note(key);
          call(key, false);
        } catch (CatalogException e) {
          throw e;
        } catch (Exception e) {
          throw CatalogException.of(CatalogException.Failure.OTHER, e.toString());
        }
        done.add(key);
        return read;
      } finally {
        busy.decrementAndGet();
      }
    }

    @Override
    public List<Database> databases() throws CatalogException {
      return read("databases " + host, null, () -> {
        var list = new ArrayList<Database>();
        for (var line : root().children()) {
          list.add(new Database(name(line), line.text().startsWith("~")));
        }
        return list;
      });
    }

    @Override
    public List<Schema> schemas(Database d) throws CatalogException {
      return read("schemas " + host + "/" + d.name(), d, () -> {
        var list = new ArrayList<Schema>();
        for (var line : find(root(), d.name()).children()) {
          list.add(new Schema(d, name(line), line.text().startsWith("~")));
        }
        return list;
      });
    }

    @Override
    public List<Table> tables(Container c) throws CatalogException {
      var path = c instanceof Schema s ? s.database().name() + "." + s.name() : c.name();
      return read("tables " + host + "/" + path, c.database(), () -> {
        var list = new ArrayList<Table>();
        for (var line : container(c).children()) {
          var kind = line.text().startsWith("@") ? Table.Kind.VIEW : Table.Kind.TABLE;
          list.add(new Table(c, name(line), kind));
        }
        return list;
      });
    }

    @Override
    public List<Table> allTables(Database d) throws CatalogException {
      return read("all tables " + host + "/" + d.name(), d, () -> {
        var list = new ArrayList<Table>();
        var db = find(root(), d.name());
        boolean schemas = made.provider() == Provider.POSTGRESQL;
        for (var line : db.children()) {
          if (!schemas) {
            var kind = line.text().startsWith("@") ? Table.Kind.VIEW : Table.Kind.TABLE;
            list.add(new Table(d, name(line), kind));
          } else if (!line.text().startsWith("~")) {
            var schema = new Schema(d, name(line), false);
            for (var table : line.children()) {
              var kind = table.text().startsWith("@") ? Table.Kind.VIEW : Table.Kind.TABLE;
              list.add(new Table(schema, name(table), kind));
            }
          }
        }
        return list;
      });
    }

    @Override
    public List<Column> columns(Table t) throws CatalogException {
      var c = t.container();
      var path = (c instanceof Schema s ? s.database().name() + "." + s.name() : c.name()) + "."
          + t.name();
      return read("columns " + host + "/" + path, c.database(), () -> {
        var list = new ArrayList<Column>();
        int key = 0;
        var table = find(container(c), t.name());
        if (table == null) {
          throw CatalogException.of(CatalogException.Failure.NOT_FOUND, t.name());
        }
        for (var line : table.children()) {
          var text = line.text();
          boolean inKey = text.startsWith("*");
          var parts = (inKey ? text.substring(1) : text).split(": ", 2);
          list.add(new Column(t, parts[0], parts[1], !inKey, inKey ? ++key : 0));
        }
        return list;
      });
    }

    private String path(Table t) {
      var c = t.container();
      return host + "/"
          + (c instanceof Schema s ? s.database().name() + "." + s.name() : c.name()) + "."
          + t.name();
    }

    // A call on a table's rows: on its database, one at a time, held and failed by its key.
    private <T> T rows(String key, Table t, RowsCall<T> call) throws QueryException {
      return Journal.sql(
          made.provider(),
          Purpose.ROWS,
          key,
          read -> read instanceof Page page ? Outcome.rows(page.rows().size()) : Outcome.rows(1),
          () -> table(key, t, call));
    }

    private <T> T table(String key, Table t, RowsCall<T> call) throws QueryException {
      if (busy.incrementAndGet() > 1) {
        overlapped = true;
      }
      try {
        if (database != null && !t.container().database().name().equals(database)) {
          throw new IllegalArgumentException("A table of another database");
        }
        var table =
            container(t.container()) == null ? null : find(container(t.container()), t.name());
        var result = table == null ? null : call.call(table);
        try {
          note(key);
          call(key, queryFailure(QueryException.Failure.CANCELLED));
        } catch (QueryException e) {
          throw e;
        } catch (Exception _) {
          throw queryFailure(QueryException.Failure.OTHER);
        }
        if (table == null) {
          throw queryFailure(QueryException.Failure.NOT_FOUND);
        }
        if (result == null) {
          throw queryFailure(QueryException.Failure.COLUMNS_CHANGED);
        }
        done.add(key);
        return result;
      } finally {
        busy.decrementAndGet();
      }
    }

    @Override
    public OptionalLong estimate(Table t) throws QueryException {
      var path = path(t);
      return rows("estimate " + path, t, table -> estimates.getOrDefault(path, OptionalLong.of(3)));
    }

    @Override
    public OptionalLong estimate(String on, String sql) throws QueryException {
      return Journal.sql(
          made.provider(),
          Purpose.ESTIMATE,
          "EXPLAIN " + sql,
          rows -> Outcome.rows(rows.isPresent() ? 1 : 0),
          () -> explain(on, sql));
    }

    private OptionalLong explain(String on, String sql) throws QueryException {
      var key = "explain " + host + " " + sql;
      // A PostgreSQL link is on its one database; MariaDB's and MySQL's switch to the one given,
      // and stay on it where they were on none.
      String at;
      if (database != null) {
        if (on != null && !on.isEmpty() && !on.equals(database)) {
          throw queryFailure(QueryException.Failure.OTHER);
        }
        at = database;
      } else {
        at = on == null || on.isEmpty() ? current : on;
      }
      if (at == null) {
        throw queryFailure(QueryException.Failure.OTHER); // no database selected
      }
      if (find(root(), at) == null) {
        throw queryFailure(QueryException.Failure.NOT_FOUND);
      }
      if (database == null && current == null) {
        current = at;
      }
      estimatedOn = at;
      try {
        note(key);
        call(key, queryFailure(QueryException.Failure.CANCELLED));
      } catch (QueryException e) {
        throw e;
      } catch (Exception _) {
        throw queryFailure(QueryException.Failure.OTHER);
      }
      done.add(key);
      return plans.getOrDefault(sql, OptionalLong.empty());
    }

    @Override
    public long count(Table t) throws QueryException {
      var path = path(t);
      return rows("count " + path, t, table -> counts.getOrDefault(path, 3L));
    }

    @Override
    public Page read(Table t, List<Column> columns, long from, long to) throws QueryException {
      var path = path(t);
      return rows("read " + path + " " + from + "-" + to, t, table -> {
        // As core reads: the columns given must be the table's now, by name, order and type.
        var now = table.children().stream()
            .map(line -> List.of(line.text().replaceFirst("^\\*", "").split(": ", 2)))
            .toList();
        var asked = columns.stream().map(c -> List.of(c.name(), c.type())).toList();
        if (!now.equals(asked)) {
          return null;
        }
        var fields = columns.stream()
            .map(c -> new Field(c.name(), c.type(), kind(c.type())))
            .toList();
        var given = values.get(path);
        long count = given != null ? given.size() : counts.getOrDefault(path, 3L);
        var rows = new ArrayList<List<Object>>();
        for (long i = from; i < Math.min(to, count); i++) {
          if (given != null) {
            rows.add(given.get((int) i));
            continue;
          }
          var row = new Object[fields.size()];
          for (int c = 0; c < row.length; c++) {
            row[c] = columns.get(c).nullable() && i % 7 == 6 ? null : value(fields.get(c), i);
          }
          rows.add(Collections.unmodifiableList(Arrays.asList(row)));
        }
        return new Page(List.copyOf(fields), List.copyOf(rows));
      });
    }

    @Override
    public String whole(Table t, List<Column> columns, List<Object> row, int column)
        throws QueryException {
      var key = new StringBuilder();
      for (int i = 0; i < columns.size(); i++) {
        if (columns.get(i).keyPosition() > 0) {
          key.append(key.isEmpty() ? "" : ",").append(row.get(i));
        }
      }
      var at = path(t) + " " + columns.get(column).name() + " " + key;
      return rows("whole " + at, t, table -> Optional.ofNullable(wholes.get(at)))
          .orElseThrow(() -> queryFailure(QueryException.Failure.NOT_FOUND));
    }

    private Outline container(Container c) {
      return switch (c) {
        case Database d -> find(root(), d.name());
        case Schema s -> find(find(root(), s.database().name()), s.name());
      };
    }

    @Override
    public Run run(String sql) {
      var word = sql.strip().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
      boolean ends = word.equals("COMMIT") || word.equals("ROLLBACK");
      boolean pg = made.provider() == Provider.POSTGRESQL;
      var setting = pg ? "" : sql.replace(" ", "").toLowerCase(Locale.ROOT);
      boolean known = ends
          || word.equals("BEGIN")
          || word.equals("START")
          || setting.matches("setautocommit=[01]|setcompletion_type=[01]");
      var answer = pg && transaction == Transaction.FAILED && !ends
          ? new Answer(
              List.of(new Failure(
                  Failure.Kind.OTHER,
                  "current transaction is aborted, commands ignored until end of transaction"
                      + " block",
                  "25P02",
                  0,
                  0,
                  "",
                  0,
                  0,
                  "",
                  "",
                  "",
                  "")),
              List.of())
          : statements.getOrDefault(
              sql,
              known
                  ? new Answer(List.of(new Result.Count(0)), List.of())
                  : new Answer(
                      List.of(serverError(
                          Failure.Kind.INVALID,
                          "syntax error at or near \"" + sql.split("\\s+", 2)[0] + "\"")),
                      List.of()));
      return new Run() {
        private int result; // of the answer, the one read next
        private long position; // in its rows
        private boolean started;
        private boolean over;

        // The statement is sent with its first portion, as core's.
        @Override
        public Portion next(int count) {
          return started
              ? portion(count)
              : Journal.sql(
                  made.provider(),
                  Purpose.STATEMENT,
                  sql,
                  Execution::outcome,
                  () -> portion(count));
        }

        private Portion portion(int count) {
          if (busy.incrementAndGet() > 1) {
            overlapped = true;
          }
          try {
            var key =
                started ? "next " + host + " " + sql + " " + position : "run " + host + " " + sql;
            started = true;
            try {
              note(key);
              call(key, queryFailure(QueryException.Failure.CANCELLED));
            } catch (Exception _) {
              over = true;
              var cancelled =
                  new Failure(Failure.Kind.CANCELLED, "", "57014", 0, 0, "", 0, 0, "", "", "", "");
              if (pg && (manual || transaction == Transaction.OPEN)) {
                transaction = Transaction.FAILED;
              }
              return new Portion(
                  List.of(cancelled), List.of(), false, TOOK, said(false, true), mode(false, true));
            }
            var failure =
                !answer.results().isEmpty() && answer.results().getLast() instanceof Failure f
                    ? f
                    : null;
            boolean fails = failure != null;
            lost = fails && failure.kind() == Failure.Kind.CONNECTION_LOST;
            if (key.startsWith("run ") && !lost) {
              transaction = after(word, setting, sql, failure, pg);
            }
            var out = new ArrayList<Result>();
            boolean more = false;
            while (result < answer.results().size()) {
              var next = answer.results().get(result);
              if (next instanceof Result.Rows r) {
                int from = (int) position;
                int to = (int) Math.min(r.rows().size(), position + count);
                out.add(new Result.Rows(
                    r.fields(),
                    from,
                    r.rows().subList(from, to),
                    r.documents().isEmpty() ? List.of() : r.documents().subList(from, to)));
                position = to;
                if (to < r.rows().size()) {
                  more = true;
                  break;
                }
                position = 0;
              } else {
                out.add(next);
              }
              result++;
            }
            over = !more;
            if (more) {
              giving = this;
              own |= key.startsWith("run ") && pg && !manual && transaction == Transaction.NONE;
            } else if (giving == this) {
              giving = null;
              own = false;
            }
            done.add(key);
            var messages = key.startsWith("run ") ? answer.messages() : List.<Message>of();
            return new Portion(out, messages, more, TOOK, said(more, fails), mode(more, fails));
          } finally {
            busy.decrementAndGet();
          }
        }

        @Override
        public Abandoned close() {
          boolean gaveUp = !over;
          if (gaveUp) {
            noted("close " + host + " " + sql);
          }
          over = true;
          if (giving == this) {
            giving = null;
          }
          var failure = gaveUp ? Optional.ofNullable(givingUp.get(sql)) : Optional.<Failure>empty();
          if (own) {
            own = false; // committed, or rolled back where that failed: nothing stays open
          } else if (failure.isPresent() && pg && transaction == Transaction.OPEN) {
            transaction = Transaction.FAILED; // in the user's transaction, the failure fails it
          }
          var says = gaveUp ? givingUpSays.get(sql) : null;
          return new Abandoned(failure, says == null ? List.of() : List.of(says));
        }
      };
    }

    // The transaction as the server leaves it after the statement, with its auto-commit.
    private Transaction after(
        String word, String setting, String sql, Failure failure, boolean pg) {
      boolean fails = failure != null;
      if (!fails && !pg) {
        seen = manual; // the driver follows the server's status after what ended well
      }
      if (beginning.contains(sql)) {
        return Transaction.OPEN;
      }
      if (turningOn.contains(sql) && manual) {
        manual = false; // behind the driver's back, also where the statement then fails
        return Transaction.NONE; // turned on from off, it commits
      }
      if (!fails && setting.startsWith("setautocommit=")) {
        boolean on = setting.endsWith("1");
        boolean was = manual;
        manual = !on;
        seen = manual;
        return on && was ? Transaction.NONE : transaction; // turned on from off, it commits
      }
      if (!fails && setting.startsWith("setcompletion_type=")) {
        chain = setting.endsWith("1");
        return transaction;
      }
      switch (word) {
        case "BEGIN", "START" -> {
          return fails ? transaction : Transaction.OPEN;
        }
        case "COMMIT", "ROLLBACK" -> {
          if (!fails && sql.toUpperCase(Locale.ROOT).matches("ROLLBACK\\b.*\\bTO\\b.*")) {
            return transaction; // to a savepoint: what came before it stays
          }
          if (!fails) {
            return chain ? Transaction.OPEN : Transaction.NONE;
          }
          return pg && word.equals("COMMIT") || rolledBack(failure)
              ? Transaction.NONE
              : transaction;
        }
        case "CREATE", "ALTER", "DROP" -> {
          if (!pg) {
            return Transaction.NONE; // committed before it runs, also where it fails
          }
        }
        default -> {
          // Every other statement goes on below.
        }
      }
      if (fails) {
        if (pg) {
          return manual || transaction == Transaction.OPEN ? Transaction.FAILED : transaction;
        }
        return rolledBack(failure) ? Transaction.NONE : transaction;
      }
      return manual ? Transaction.OPEN : transaction;
    }

    private boolean blind() {
      return made.provider() != Provider.POSTGRESQL && blind.contains(host);
    }

    // What a portion says of the transaction, as core's do.
    private Transaction said(boolean more, boolean failed) {
      return lost || blind() || made.provider() != Provider.POSTGRESQL && (more || failed)
          ? Transaction.UNKNOWN
          : transaction;
    }

    // What a portion says of the auto-commit: as of the transaction, from the same status.
    private Boolean mode(boolean more, boolean failed) {
      return said(more, failed) == Transaction.UNKNOWN && made.provider() != Provider.POSTGRESQL
              || lost
          ? null
          : !manual;
    }

    @Override
    public void manual(boolean manual) {
      // As JDBC drivers do: nothing is sent where the driver thinks it has that mode already.
      if (seen == manual) {
        return;
      }
      Journal.call(Purpose.TRANSACTION, Journal.autoCommit(!manual), () -> {
        noted("autocommit " + host + (manual ? " off" : " on"));
        return null;
      });
      seen = manual;
      boolean was = this.manual;
      this.manual = manual;
      if (was && !manual && transaction != Transaction.NONE) {
        noted("commit by auto-commit " + host); // turned on, it commits what is open
        transaction = Transaction.NONE;
      }
    }

    @Override
    public Status status() throws QueryException {
      return Journal.sql(
          made.provider(), Purpose.TRANSACTION, "DO 0", status -> Outcome.DONE, this::asked);
    }

    private Status asked() throws QueryException {
      var key = "transaction " + host;
      try {
        note(key);
        call(key, queryFailure(QueryException.Failure.CANCELLED));
      } catch (QueryException e) {
        throw e;
      } catch (Exception _) {
        throw queryFailure(QueryException.Failure.OTHER);
      }
      done.add(key);
      if (blind()) {
        return new Status(Transaction.UNKNOWN, null);
      }
      seen = manual; // as core sets the driver's idea from the server's status
      if (own) {
        return new Status(Transaction.OPEN, false); // pgjdbc's, while core reads in its own
      }
      return new Status(transaction, !manual);
    }

    @Override
    public Monitor monitor() {
      return new Monitor() {
        @Override
        public List<ServerSession> sessions() throws QueryException {
          var key = "sessions " + host;
          return Journal.sql(
              made.provider(), Purpose.MONITOR, key, list -> Outcome.rows(list.size()), () -> {
                try {
                  note(key);
                  call(key, queryFailure(QueryException.Failure.CANCELLED));
                } catch (QueryException e) {
                  throw e;
                } catch (Exception e) {
                  throw queryFailure(QueryException.Failure.OTHER);
                }
                done.add(key);
                var all = new ArrayList<ServerSession>();
                all.add(new ServerSession(
                    String.valueOf(number),
                    "",
                    "",
                    true,
                    true,
                    "hoori",
                    "HooriDB (10.0.0.1:5000)",
                    "shop",
                    "active",
                    "SELECT the sessions",
                    Duration.ZERO,
                    ""));
                // Masked as core's monitors mask them, which keeps each statement's digest.
                var provider = servers.get(host).provider();
                for (var session : FakeServers.this.sessions.getOrDefault(host, List.of())) {
                  all.add(session.withStatement(Credentials.mask(session.statement(), provider)));
                }
                return all;
              });
        }

        @Override
        public List<ServerVariable> variables() throws QueryException {
          read("variables " + host);
          return FakeServers.this.variables.getOrDefault(host, List.of());
        }

        @Override
        public List<Counter> status() throws QueryException {
          read("status " + host);
          return FakeServers.this.counters.getOrDefault(host, List.of());
        }

        @Override
        public Optional<List<Block>> locks() throws QueryException {
          read("locks " + host);
          return FakeServers.this.locks.getOrDefault(host, Optional.of(List.of()));
        }

        private void read(String key) throws QueryException {
          Journal.sql(made.provider(), Purpose.MONITOR, key, _ -> Outcome.DONE, () -> {
            try {
              note(key);
              call(key, queryFailure(QueryException.Failure.CANCELLED));
            } catch (QueryException e) {
              throw e;
            } catch (Exception e) {
              throw queryFailure(QueryException.Failure.OTHER);
            }
            done.add(key);
            return null;
          });
        }

        @Override
        public boolean cancel(ServerSession session) throws QueryException {
          return stop("cancel " + host + " " + session.operation(), session, true);
        }

        @Override
        public boolean end(ServerSession session) throws QueryException {
          return stop("end " + host + " " + session.id(), session, false);
        }

        private boolean stop(String key, ServerSession session, boolean idle)
            throws QueryException {
          return Journal.sql(made.provider(), Purpose.MONITOR, key, found -> Outcome.DONE, () -> {
            try {
              note(key);
              call(key, queryFailure(QueryException.Failure.CANCELLED));
            } catch (QueryException e) {
              throw e;
            } catch (Exception e) {
              throw queryFailure(QueryException.Failure.OTHER);
            }
            done.add(key);
            var now = new ArrayList<ServerSession>();
            boolean found = false;
            for (var other : FakeServers.this.sessions.getOrDefault(host, List.of())) {
              // As a server that checks the session's start, and for a cancel its statement.
              if (!other.id().equals(session.id())
                  || !other.started().equals(session.started())
                  || idle && !other.operation().equals(session.operation())) {
                now.add(other);
                continue;
              }
              found = true;
              if (idle) {
                now.add(FakeServers.session(other.id(), other.user(), "", other.started()));
              }
            }
            FakeServers.this.sessions.put(host, List.copyOf(now));
            return found;
          });
        }
      };
    }

    @Override
    public void use(String name) throws QueryException {
      Journal.call(Purpose.SESSION, Journal.catalog(name), () -> {
        switched(name);
        return null;
      });
    }

    private void switched(String name) throws QueryException {
      var key = "use " + host + " " + name;
      try {
        note(key);
        call(key, queryFailure(QueryException.Failure.CANCELLED));
      } catch (QueryException e) {
        throw e;
      } catch (Exception _) {
        throw queryFailure(QueryException.Failure.OTHER);
      }
      done.add(key);
    }

    @Override
    public Abandoned giveUp() {
      var left = giving;
      giving = null;
      return left == null ? Abandoned.NOTHING : left.close();
    }

    @Override
    public Abandoned close() {
      closed.add(host + (database == null ? "" : "/" + database));
      return giveUp();
    }

    @Override
    public void abort() {
      closed.add("aborted " + host + (database == null ? "" : "/" + database));
    }
  }

  private interface Reader<T> {
    List<T> read() throws CatalogException;
  }

  private interface RowsCall<T> {
    /** Of the table as the server has it; null where its columns are not those asked with. */
    T call(Outline table) throws QueryException;
  }

  /** The kind of values a type of the outline holds, as core would read it. */
  static Kind kind(String type) {
    var t = type.toLowerCase(Locale.ROOT);
    if (t.startsWith("timestamp with time zone")) {
      return Kind.DATE_TIME_ZONED;
    }
    if (t.startsWith("timestamp") || t.startsWith("datetime")) {
      return Kind.DATE_TIME;
    }
    if (t.matches("(big|small|tiny)?int(eger)?\\b.*|oid")) {
      return Kind.INTEGER;
    }
    if (t.startsWith("numeric") || t.startsWith("decimal") || t.startsWith("double")) {
      return Kind.DECIMAL;
    }
    if (t.startsWith("bool")) {
      return Kind.BOOLEAN;
    }
    if (t.equals("date")) {
      return Kind.DATE;
    }
    if (t.startsWith("time")) {
      return Kind.TIME;
    }
    if (t.equals("bytea") || t.endsWith("blob") || t.contains("binary")) {
      return Kind.BINARY;
    }
    if (t.startsWith("json")) {
      return Kind.JSON;
    }
    if (t.startsWith("text")
        || t.startsWith("char")
        || t.startsWith("varchar")
        || t.equals("name")) {
      return Kind.TEXT;
    }
    return Kind.OTHER;
  }

  /** A value made up from the row's position. */
  static Object value(Field field, long i) {
    return switch (field.kind()) {
      case INTEGER -> i + 1;
      case DECIMAL -> BigDecimal.valueOf(i * 125 + 99, 2);
      case TEXT -> field.name() + " " + (i + 1);
      case BOOLEAN -> i % 2 == 0;
      case DATE -> LocalDate.of(2026, 1, 1).plusDays(i % 365);
      case TIME -> LocalTime.of(9, 0).plusMinutes(i % 600);
      case DATE_TIME -> LocalDateTime.of(2026, 1, 1, 9, 0).plusMinutes(i);
      case DATE_TIME_ZONED ->
        LocalDateTime.of(2026, 1, 1, 9, 0).plusMinutes(i).atOffset(ZoneOffset.UTC);
      case BINARY -> new Binary(new byte[] {(byte) i, (byte) (i >> 8), 0x0d, 0x0a}, 4);
      case JSON, OTHER -> "{\"n\": " + (i + 1) + "}";
    };
  }

  private static String name(Outline node) {
    var line = node.text();
    return line.startsWith("~") || line.startsWith("@") ? line.substring(1) : line;
  }

  private static Outline find(Outline parent, String name) {
    for (var child : parent.children()) {
      if (name(child).equals(name)) {
        return child;
      }
    }
    return null;
  }

  /** The outline as a tree under a root without a line. */
  static Outline parse(String outline) {
    var root = new Outline("", new ArrayList<>());
    var path = new ArrayList<Outline>();
    var depths = new ArrayList<Integer>();
    for (var line : outline.lines().toList()) {
      if (line.isBlank()) {
        continue;
      }
      int depth = line.length() - line.stripLeading().length();
      while (!depths.isEmpty() && depths.getLast() >= depth) {
        depths.removeLast();
        path.removeLast();
      }
      var node = new Outline(line.strip(), new ArrayList<>());
      (path.isEmpty() ? root : path.getLast()).children().add(node);
      path.add(node);
      depths.add(depth);
    }
    return root;
  }
}

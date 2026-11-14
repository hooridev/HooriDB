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
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
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
 * the path as of {@code columns}, such as {@code pg.example.com/shop.public.orders}.
 */
final class FakeServers implements Servers {

  /** A made-up server: its kind, version and content, and the password it wants, or null. */
  record Made(Provider provider, String version, Outline root, String password) {}

  /** A line of the outline, with the lines indented under it. */
  record Outline(String text, List<Outline> children) {}

  private final Map<String, Made> servers = new ConcurrentHashMap<>();
  private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
  private final Map<String, Exception> failures = new ConcurrentHashMap<>();
  final List<String> calls = new CopyOnWriteArrayList<>(); // in the order they began
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

  /** The calls of this key fail so, until {@link #heal}. */
  void fail(String key, Exception failure) {
    failures.put(key, failure);
  }

  void heal(String key) {
    failures.remove(key);
  }

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
    if (gate != null) {
      if (dead) {
        boolean interrupt = false;
        while (true) {
          try {
            gate.await();
            break;
          } catch (InterruptedException e) {
            interrupt = true;
          }
        }
        if (interrupt) {
          Thread.currentThread().interrupt();
        }
      } else {
        try {
          gate.await();
        } catch (InterruptedException e) {
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
    return new FakeLink(profile.host(), made, perDatabase ? database : null);
  }

  private final class FakeLink implements Link {
    private final String host;
    private final Made made;
    private final String database;
    private final AtomicInteger busy = new AtomicInteger();

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

    private <T> List<T> read(String key, Database of, Reader<T> reader) throws CatalogException {
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
          call(key, queryFailure(QueryException.Failure.CANCELLED));
        } catch (QueryException e) {
          throw e;
        } catch (Exception e) {
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

    private Outline container(Container c) {
      return switch (c) {
        case Database d -> find(root(), d.name());
        case Schema s -> find(find(root(), s.database().name()), s.name());
      };
    }

    @Override
    public void close() {
      closed.add(host + (database == null ? "" : "/" + database));
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
      case OTHER -> "{\"n\": " + (i + 1) + "}";
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

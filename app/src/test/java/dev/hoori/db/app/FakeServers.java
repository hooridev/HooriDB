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
import dev.hoori.db.core.driver.LoadedDriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  volatile boolean overlapped; // two calls ran at once on one connection
  volatile boolean dead; // a gate ignores interrupts, as a server that no longer answers

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
          throw connecting
              ? ConnectionException.of(ConnectionException.Failure.CANCELLED, "")
              : CatalogException.of(CatalogException.Failure.CANCELLED, "");
        }
      }
    }
    var failure = failures.get(key);
    if (failure != null) {
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
        for (var line : find(container(c), t.name()).children()) {
          var text = line.text();
          boolean inKey = text.startsWith("*");
          var parts = (inKey ? text.substring(1) : text).split(": ", 2);
          list.add(new Column(t, parts[0], parts[1], !inKey, inKey ? ++key : 0));
        }
        return list;
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
    List<T> read();
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

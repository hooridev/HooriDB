package dev.hoori.db.app;

import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The workspace as a tree: its folders, which the user opens, closes and moves, and its
 * connections with what their servers hold: opening a connection connects, and each level below
 * it is read when it is opened. Each connection to a server is a {@link Lane}, which
 * serves one call at a time; connections do not wait for one another. Only the app's thread uses
 * it.
 */
final class CatalogTree {

  static final String TRY_AGAIN = "Try again";

  /** What is known of a node's children. */
  sealed interface Level {}

  /** They are being read; the call is set once it is asked for. */
  static final class Reading implements Level {
    Lane.Asked asked;
  }

  record Read(List<Node> children) implements Level {}

  record NotRead(CatalogException.Failure failure, String detail) implements Level {}

  /** A database that needs a connection of its own, which could not be opened. */
  record NotOpened(ConnectionException.Failure failure, String detail) implements Level {}

  /** What the question before an edit or a delete of a connected connection leads to. */
  enum Then {
    EDIT,
    DELETE
  }

  record Disconnecting(ConnectionProfile profile, Then then) {}

  private final Servers servers;
  final Map<UUID, Live> lives = new LinkedHashMap<>(); // the connections opened, in that order
  final Set<Node> open = new HashSet<>();
  /** The driver a connection needs: of its kind, in the version chosen or the recommended one. */
  record Want(Provider provider, String version) {
    static Want of(ConnectionProfile profile) {
      return new Want(profile.provider(), profile.driverVersion());
    }
  }

  private final Map<Want, Fetch> fetches = new HashMap<>();
  final Menu.State menu = new Menu.State();
  final Menu.State adding = new Menu.State(); // the menu of the sidebar's "+"
  final Tree.Dragging dragging = new Tree.Dragging();
  Disconnecting disconnecting; // the question before an edit or a delete, or null

  CatalogTree(Servers servers) {
    this.servers = servers;
  }

  /** Whether the connection is connected or connecting. */
  boolean active(UUID id) {
    var live = lives.get(id);
    return live != null && !ended(live.phase);
  }

  static boolean ended(Live.Phase phase) {
    return phase instanceof Live.Failed
        || phase instanceof Live.Lost
        || phase instanceof Live.WithoutDrivers;
  }

  // Handlers

  /** The user opens or closes a node: opening a connection connects, opening a level reads it. */
  void toggle(State s, Node node, boolean opens) {
    if (node instanceof Node.Folder(var id)) {
      s.openFolder(id, opens);
      return;
    }
    if (!opens) {
      open.remove(node);
      return;
    }
    open.add(node);
    var live = lives.get(node.id());
    if (node instanceof Node.Connection) {
      if (live == null || ended(live.phase)) {
        connect(s, node.id());
      }
      return;
    }
    if (live != null && live.phase instanceof Live.Connected && !(node instanceof Node.Problem)) {
      var level = live.levels.get(node);
      if (level == null || level instanceof NotRead || level instanceof NotOpened) {
        read(s, live, node); // opening it again tries again
      }
    }
  }

  /** A double press or Enter: a connection that is not open connects, a table opens its rows. */
  void activate(State s, Node node) {
    switch (node) {
      case Node.Connection(var id) -> {
        var live = lives.get(id);
        if (live == null || ended(live.phase)) {
          open.add(node);
          connect(s, id);
        }
      }
      case Node.OfTable(var id, var table) -> s.openTable(id, table);
      default -> {
        // The rest only open and close, which the tree does.
      }
    }
  }

  /** Connects anew, as far as is needed: the driver, the password, the server. */
  void connect(State s, UUID id) {
    connect(s, id, false);
  }

  /** As {@link #connect(State, UUID)}; with {@code typing}, the password is the user's to type. */
  private void connect(State s, UUID id, boolean typing) {
    s.acted();
    var profile = s.profile(id);
    if (profile == null) {
      return;
    }
    end(lives.remove(id));
    s.ended(id);
    forgetBelow(s, new Node.Connection(id));
    open.add(new Node.Connection(id));
    var live = new Live(profile);
    live.typing = typing;
    lives.put(id, live);
    if (s.drivers == null) {
      live.phase = new Live.WithoutDrivers();
      return;
    }
    var want = Want.of(profile);
    var loaded = s.drivers.loaded(Driver.of(want.provider()), want.version());
    if (loaded != null) {
      password(s, live, loaded);
      return;
    }
    live.phase = new Live.ForDriver();
    var fetch = fetches.computeIfAbsent(want, w -> new Fetch((st, d) -> fetched(st, w, d)));
    if (fetch.step == null) {
      fetch.lookUp(s, want.provider(), want.version());
      fetch.start(s);
    }
  }

  private void fetched(State s, Want want, LoadedDriver driver) {
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof Live.ForDriver && Want.of(live.profile).equals(want)) {
        password(s, live, driver);
      }
    }
  }

  /** Not now, Cancel download or Escape: the connections that wait for the driver stay closed. */
  void stopFetch(State s, Want want) {
    fetches.get(want).stop();
    var names = new ArrayList<String>();
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof Live.ForDriver && Want.of(live.profile).equals(want)) {
        names.add(State.name(live.profile));
        drop(s, live.id());
      }
    }
    if (!names.isEmpty()) {
      s.tell(String.join(", ", names) + " not connected, as " + Fetch.name(want.provider())
          + " is not downloaded.");
    }
  }

  /** The fetch that shows something for a connection that waits for it, or null. */
  Fetch asking() {
    for (var entry : fetches.entrySet()) {
      if (!entry.getValue().shows()) {
        continue;
      }
      for (var live : lives.values()) {
        if (live.phase instanceof Live.ForDriver && Want.of(live.profile).equals(entry.getKey())) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  Want want(Fetch fetch) {
    for (var entry : fetches.entrySet()) {
      if (entry.getValue() == fetch) {
        return entry.getKey();
      }
    }
    throw new IllegalArgumentException("Not a fetch of the catalog");
  }

  /**
   * The password kept for this session, else the one in the keyring, else the user's; only the
   * user's where the user asked to type it.
   */
  private void password(State s, Live live, LoadedDriver driver) {
    live.driver = driver;
    if (live.typing) {
      live.phase = new Live.ForPassword(false);
      return;
    }
    var id = live.id();
    char[] kept = s.rememberedCopy(id);
    if (kept != null) {
      connect(s, live, kept);
      return;
    }
    if (s.keyring == null) {
      live.phase = new Live.ForKeyring();
      patience(s, live);
      return;
    }
    var secrets = s.secrets();
    if (secrets == null) {
      live.phase = new Live.ForPassword(false);
      return;
    }
    live.phase = new Live.ReadingPassword();
    patience(s, live);
    var handover = new Live.Handover();
    live.handover = handover;
    live.work = s.app.background(
        "read a password",
        () -> {
          secrets.read(id).ifPresent(handover::put);
          return handover;
        },
        given -> {
          live.work = null;
          live.handover = null;
          char[] password = given.take();
          if (password == null) {
            live.phase = new Live.ForPassword(false);
          } else {
            connect(s, live, password);
          }
        },
        error -> {
          live.work = null;
          live.handover = null;
          live.phase = new Live.ForPassword(false); // the user can still type it
        });
  }

  /**
   * Waiting for the keyring, to be found or to give the password, lasts at most {@link
   * State#KEYRING_SECONDS}: then the connection asks the user, and what the keyring says later is
   * dropped. A call to it that never returns keeps no connection from its password.
   */
  private void patience(State s, Live live) {
    var waiting = live.phase;
    s.app.after(State.KEYRING_SECONDS, () -> {
      if (live.phase == waiting && lives.get(live.id()) == live) {
        if (live.work != null) {
          live.work.cancel();
          live.work = null;
        }
        if (live.handover != null) {
          live.handover.drop();
          live.handover = null;
        }
        live.phase = new Live.ForPassword(true);
      }
    });
  }

  /** The system keyring was found: connections that waited for it go on. */
  void keyringFound(State s) {
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof Live.ForKeyring) {
        password(s, live, live.driver);
      }
    }
  }

  /** The password dialog's Connect: with what was typed, kept for this session if asked to. */
  void connectWith(State s, Live live) {
    char[] password = live.typed.toCharArray();
    live.typed = "";
    if (live.keep) {
      s.rememberPassword(live.id(), password.clone());
    }
    connect(s, live, password);
  }

  /**
   * Back to the password, after the server refused it; where its driver was deleted meanwhile, it
   * connects anew, which finds the driver again and then asks for the password.
   */
  void askPassword(State s, Live live) {
    if (s.drivers.version(live.driver) == null) {
      connect(s, live.id(), true);
      return;
    }
    live.typed = "";
    live.revealed = false;
    live.keep = false;
    live.phase = new Live.ForPassword(false);
  }

  /** Opens the first connection, with the password, which is the live's now. */
  private void connect(State s, Live live, char[] password) {
    live.password = password;
    live.phase = new Live.Connecting();
    live.lane = lane(s, live);
    live.lane.open(servers, live.profile, password, live.driver);
    record Opened(ServerVersion version, String database) {}
    // Right behind the opening, which it fails as.
    live.connecting = live.lane.ask(
        s.app,
        "connect to " + State.name(live.profile),
        Lane.Priority.VIEW,
        link -> new Opened(link.version(), link.database()),
        opened -> {
          live.connecting = null;
          var provider = live.profile.provider();
          var version = opened.version();
          // Where a connection reads one database only, the others get connections of their own.
          var database = provider.supports(Capability.CONNECTION_PER_DATABASE, version)
              ? opened.database()
              : null;
          live.phase =
              new Live.Connected(version, database, provider.supports(Capability.SCHEMAS, version));
          s.tell("Connected to " + State.name(live.profile));
          s.connected(live.id());
        },
        error -> {
          live.connecting = null;
          var checked = checked(live, error);
          var failure = checked instanceof ConnectionException e
              ? e.failure()
              : ConnectionException.Failure.OTHER;
          live.phase = new Live.Failed(failure, detail(checked), live.password.length > 0);
          end(live);
          s.unconnected(live.id());
          s.tell(State.name(live.profile) + ": " + Words.title(failure));
        });
    // Its databases are asked for right behind it, on its lane: they need no answer before.
    read(s, live, new Node.Connection(live.id()));
  }

  /** Reads the children of the node on the connection that reads its database. */
  private void read(State s, Live live, Node node) {
    // The databases are read on the first connection, also while it opens.
    var c = live.phase instanceof Live.Connected connected ? connected : null;
    if (live.lane == null || c == null && !(node instanceof Node.Connection)) {
      return;
    }
    var call = children(live.id(), node, c);
    if (call == null) {
      return;
    }
    // The columns are read once at a time: a read of them in the background gives way.
    if (node instanceof Node.OfTable
        && live.scans.remove(node) instanceof Scan x
        && x.asked != null) {
      x.asked.cancel();
    }
    var lane = lane(s, live, database(node));
    var reading = new Reading();
    live.levels.put(node, reading);
    reading.asked = lane.ask(
        s.app,
        "read " + label(s, node),
        Lane.Priority.VIEW,
        call,
        children -> {
          if (live.levels.get(node) == reading) {
            live.levels.put(node, new Read(children));
            databasesRead(s, node);
          }
        },
        error -> {
          if (live.levels.get(node) == reading) {
            readFailed(s, live, node, lane, error);
          }
        });
  }

  /**
   * The call that reads the node's children, or null for a node that has none to read; the
   * connection is connected for any node but a connection's own.
   */
  private static Lane.Call<List<Node>> children(UUID id, Node node, Live.Connected c) {
    return switch (node) {
      case Node.Connection n ->
        link -> link.databases().stream()
            .map(d -> (Node) new Node.OfDatabase(id, d))
            .toList();
      case Node.OfDatabase n
      when c.schemas() ->
        link -> link.schemas(n.database()).stream()
            .map(x -> (Node) new Node.OfSchema(id, x))
            .toList();
      case Node.OfDatabase n -> tables(id, n.database());
      case Node.OfSchema n -> tables(id, n.schema());
      case Node.OfTable n ->
        link -> link.columns(n.table()).stream()
            .map(x -> (Node) new Node.OfColumn(id, x))
            .toList();
      case Node.OfColumn n -> null;
      case Node.Problem n -> null;
      case Node.Folder n -> null;
    };
  }

  /** A read of the node's children failed: the node says why, unless the connection was lost. */
  private void readFailed(State s, Live live, Node node, Lane lane, Exception error) {
    switch (checked(live, error)) {
      case ConnectionException e -> {
        // The next read opens it again.
        if (live.lanes.remove(database(node), lane)) {
          lane.close();
        }
        live.levels.put(node, new NotOpened(e.failure(), e.detail()));
      }
      case CatalogException e
      when readAlone(e) -> live.levels.put(node, new NotRead(e.failure(), e.detail()));
      case Exception e -> lose(s, live, detail(e), discarded(e));
    }
    databasesRead(s, node);
  }

  /** Whether the failure is the read's alone, and leaves the connection as it was. */
  private static boolean readAlone(CatalogException e) {
    return e.failure() != CatalogException.Failure.CONNECTION_LOST
        && e.failure() != CatalogException.Failure.ABORTED;
  }

  /** A read in the background: under way while it has work, else why it failed. */
  static final class Scan {
    Lane.Asked asked;
    String failed;
  }

  /**
   * Reads every table of the database that the tree has not read, without opening its node, in one
   * call that waits behind what the tree and the tabs ask for ({@link Lane.Priority#AHEAD}): the
   * database's schemas, and the tables of all of them that are not the system's. What the tree has
   * read, or reads meanwhile, stays as it is. Go to table asks for it, and {@link #stop}s it when
   * the palette closes; null where nothing is read, as the connection is not connected or the
   * database is being read so already.
   */
  Scan scan(State s, Node.OfDatabase node) {
    var live = lives.get(node.id());
    if (!(live != null && live.phase instanceof Live.Connected c)) {
      return null;
    }
    var database = node.database();
    boolean schemas = c.schemas();
    record Found(List<Schema> schemas, List<Table> tables) {}
    return scan(
        s,
        live,
        node,
        "read the tables of " + database.name(),
        link -> new Found(schemas ? link.schemas(database) : null, link.allTables(database)),
        found -> fill(live, node, found.schemas(), found.tables()));
  }

  /**
   * Reads the table's columns where the tree has not, as {@link #scan(State, Node.OfDatabase)}
   * reads tables, for the completions of a console, which {@link #stop}s it as it closes.
   */
  Scan scan(State s, Node.OfTable node) {
    var live = lives.get(node.id());
    if (live == null
        || !(live.phase instanceof Live.Connected)
        || live.levels.get(node) instanceof Reading) {
      return null; // the tree reads them already
    }
    var id = live.id();
    return scan(
        s,
        live,
        node,
        "read the columns of " + node.table().name(),
        link -> link.columns(node.table()),
        columns -> {
          if (unread(live.levels.get(node))) {
            live.levels.put(
                node,
                new Read(
                    columns.stream().map(x -> (Node) new Node.OfColumn(id, x)).toList()));
          }
        });
  }

  private <T> Scan scan(
      State s, Live live, Node node, String name, Lane.Call<T> call, Consumer<T> fill) {
    if (live.scans.get(node) instanceof Scan x && x.asked != null) {
      return null;
    }
    var database = database(node);
    var lane = lane(s, live, database);
    var scan = new Scan();
    live.scans.put(node, scan);
    scan.asked = lane.ask(
        s.app,
        name,
        Lane.Priority.AHEAD,
        call,
        found -> {
          if (live.scans.get(node) == scan) {
            live.scans.remove(node);
            fill.accept(found);
          }
        },
        error -> {
          if (live.scans.get(node) != scan) {
            return;
          }
          scan.asked = null;
          switch (checked(live, error)) {
            case ConnectionException _ -> {
              if (live.lanes.remove(database, lane)) {
                lane.close(); // the next read opens it again
              }
              scan.failed = "The database could not be opened";
            }
            case CatalogException e when readAlone(e) -> scan.failed = title(e.failure());
            case Exception e -> {
              live.scans.remove(node);
              lose(s, live, detail(e), discarded(e));
            }
          }
        });
    return scan;
  }

  /** Ends the scan if it has not delivered: what it asked of its lane never runs or is cancelled. */
  void stop(Node node, Scan scan) {
    var live = lives.get(node.id());
    if (live != null && live.scans.get(node) == scan) {
      live.scans.remove(node);
      if (scan.asked != null) {
        scan.asked.cancel();
      }
    }
  }

  /** Puts what a scan read where the tree has not read it. */
  private static void fill(
      Live live, Node.OfDatabase node, List<Schema> schemas, List<Table> tables) {
    var id = live.id();
    if (unread(live.levels.get(node))) {
      live.levels.put(
          node,
          new Read(
              schemas == null
                  ? tables.stream().map(t -> (Node) new Node.OfTable(id, t)).toList()
                  : schemas.stream().map(x -> (Node) new Node.OfSchema(id, x)).toList()));
    }
    if (schemas == null || !(live.levels.get(node) instanceof Read(var children))) {
      return;
    }
    var bySchema = new HashMap<Schema, List<Node>>();
    for (var table : tables) {
      bySchema
          .computeIfAbsent((Schema) table.container(), x -> new ArrayList<>())
          .add(new Node.OfTable(id, table));
    }
    for (var child : children) {
      if (child instanceof Node.OfSchema x
          && !x.schema().system()
          && unread(live.levels.get(child))) {
        live.levels.put(child, new Read(List.copyOf(bySchema.getOrDefault(x.schema(), List.of()))));
      }
    }
  }

  /**
   * Takes the scans of databases at or below the node, or of the database the node is in, and adds
   * what they asked for to what is given up.
   */
  private static void stopScans(Live live, Node node, List<Lane.Asked> gone) {
    for (var entry : List.copyOf(live.scans.entrySet())) {
      if (entry.getKey().within(node) || node.within(entry.getKey())) {
        live.scans.remove(entry.getKey());
        if (entry.getValue().asked != null) {
          gone.add(entry.getValue().asked);
        }
      }
    }
  }

  /** Whether a level is to be read: not asked for yet, or failed. */
  static boolean unread(Level level) {
    return level == null || level instanceof NotRead || level instanceof NotOpened;
  }

  /**
   * The lane that reads the database's content: the first connection's, or where a connection
   * reads one database only, the database's own; null as the database stands for the first's.
   */
  private Lane lane(State s, Live live, String database) {
    if (database == null) {
      return live.lane; // the databases themselves, read also while it opens
    }
    var c = (Live.Connected) live.phase;
    return c.database() == null || database.equals(c.database())
        ? live.lane
        : further(s, live, database);
  }

  /** The lane that reads the table's rows, or null while its connection is not connected. */
  Lane lane(State s, UUID id, Table table) {
    var live = lives.get(id);
    return live != null && live.phase instanceof Live.Connected
        ? lane(s, live, table.container().database().name())
        : null;
  }

  /**
   * What the connection is now, while it lasts: a connection opened again is another, so that what
   * was asked of the one before is told apart from what is asked of it. Null while closed.
   */
  Live live(UUID id) {
    return lives.get(id);
  }

  /**
   * The connection for a console's estimates on the database, opened the first time it is asked
   * for, and anew where it could not be or is gone; null while the tree's connection is not
   * connected. Nothing else runs on it, as a function the plan runs may change its session for
   * good: on PostgreSQL one per database, on MariaDB and MySQL one, which each estimate puts on
   * its database. It is opened as the tree's further connections are, with the password the tree
   * keeps while connected, and closes with them.
   */
  Lane planner(UUID id, String database) {
    return auxiliary(id, database, live -> live.planners);
  }

  /**
   * The connection for counts that may take long on the database, opened the first time it is
   * asked for, and anew where it could not be or is gone; null while the tree's connection is not
   * connected. Nothing else runs on it, so that a slow count does not hold up the tree or other
   * tables' pages: on PostgreSQL one per database, else one, as {@link #planner} does.
   */
  Lane counter(UUID id, String database) {
    return auxiliary(id, database, live -> live.counters);
  }

  private Lane auxiliary(UUID id, String database, Function<Live, Map<String, Lane>> map) {
    var live = lives.get(id);
    if (live == null || !(live.phase instanceof Live.Connected c) || live.password == null) {
      return null;
    }
    var key = c.database() == null ? "" : database;
    var lanes = map.apply(live);
    var lane = lanes.get(key);
    if (lane == null || lane.broken()) {
      if (lane != null) {
        lane.close();
      }
      // Its loss is the call's alone, which fails without losing the tree's connection.
      lane = new Lane(e -> {});
      var profile = key.isEmpty() ? live.profile : live.profile.withDatabase(key);
      lane.open(servers, profile, live.password, live.driver);
      lanes.put(key, lane);
    }
    return lane;
  }

  /** Whether the connection is connected, so that its lanes can be used. */
  boolean connected(UUID id) {
    var live = lives.get(id);
    return live != null && live.phase instanceof Live.Connected;
  }

  /**
   * A console's session: its own connection, the driver it uses, and a copy of the password, which
   * it wipes.
   */
  record Session(Lane lane, LoadedDriver driver, char[] password) {

    // The password's content counts, as for any record; it never shows.
    @Override
    public boolean equals(Object o) {
      return o instanceof Session(var l, var d, var p)
          && Objects.equals(lane, l)
          && Objects.equals(driver, d)
          && Arrays.equals(password, p);
    }

    @Override
    public int hashCode() {
      return Objects.hash(lane, driver, Arrays.hashCode(password));
    }

    @Override
    public String toString() {
      return "Session[lane=" + lane + ", driver=" + driver + "]";
    }
  }

  /**
   * Opens a connection of the console's own to the database, with the password and driver the
   * tree's connection got; null while that is not connected. It is no lane of the tree's: what
   * runs on it never holds up the tree or a table tab.
   */
  Session session(UUID id, String database) {
    var live = lives.get(id);
    if (live == null || !(live.phase instanceof Live.Connected)) {
      return null;
    }
    // A console finds a lost connection in what its statements give, not through the tree.
    var lane = new Lane(e -> {});
    var profile = database.isEmpty() ? live.profile : live.profile.withDatabase(database);
    lane.open(servers, profile, live.password, live.driver);
    return new Session(lane, live.driver, live.password.clone());
  }

  /** The names of the connection's databases as the tree read them, or none where it has not. */
  List<String> databases(UUID id) {
    var live = lives.get(id);
    if (live != null && live.levels.get(new Node.Connection(id)) instanceof Read(var children)) {
      return children.stream().map(n -> ((Node.OfDatabase) n).database().name()).toList();
    }
    return List.of();
  }

  /**
   * The connection's one database that is not a system database, as the tree read them; empty
   * where it read none or several.
   */
  String onlyDatabase(UUID id) {
    var live = lives.get(id);
    if (live != null && live.levels.get(new Node.Connection(id)) instanceof Read(var children)) {
      var names = children.stream()
          .map(n -> ((Node.OfDatabase) n).database())
          .filter(d -> !d.system())
          .map(Database::name)
          .toList();
      return names.size() == 1 ? names.getFirst() : "";
    }
    return "";
  }

  /** Whether the tree reads the connection's databases now. */
  boolean readsDatabases(UUID id) {
    var live = lives.get(id);
    return live != null && live.levels.get(new Node.Connection(id)) instanceof Reading;
  }

  // The consoles that waited for the databases, to take the one there is, go on, read or not.
  private static void databasesRead(State s, Node node) {
    if (node instanceof Node.Connection(var id)) {
      s.consoles(id).forEach(c -> c.databasesRead(s));
    }
  }

  /** The version of the connected server, or null while it is not connected. */
  ServerVersion version(UUID id) {
    var live = lives.get(id);
    return live != null && live.phase instanceof Live.Connected c ? c.version() : null;
  }

  /** The table's columns as the tree read them, or null where it has not. */
  List<Column> columns(UUID id, Table table) {
    var live = lives.get(id);
    if (live != null
        && live.levels.get(new Node.OfTable(id, table)) instanceof Read(var children)) {
      return children.stream().map(n -> ((Node.OfColumn) n).column()).toList();
    }
    return null; // not read, which differs from a table without columns
  }

  /** The table's columns as read anew elsewhere, which the tree shows where it showed them. */
  void columnsRead(UUID id, Table table, List<Column> columns) {
    var live = lives.get(id);
    var node = new Node.OfTable(id, table);
    if (live != null && live.levels.get(node) instanceof Read) {
      live.levels.put(
          node,
          new Read(columns.stream().map(c -> (Node) new Node.OfColumn(id, c)).toList()));
    }
  }

  /**
   * A failure's own words, withheld where they hold the password of the connection they came
   * from, or where that connection has ended and its password is gone.
   */
  static String words(Live live, String detail) {
    return live == null || live.password == null || Probe.contains(detail, live.password)
        ? ConnectionException.WITHHELD
        : detail;
  }

  /** A call found the connection gone: it is closed, and its node says so; once, and only it. */
  void lost(State s, Live live, String detail, boolean discarded) {
    if (live != null && lives.get(live.id()) == live && live.phase instanceof Live.Connected) {
      lose(s, live, detail, discarded);
    }
  }

  private static boolean discarded(Exception e) {
    return e instanceof CatalogException c && c.failure() == CatalogException.Failure.ABORTED
        || e instanceof QueryException q && q.failure() == QueryException.Failure.ABORTED;
  }

  /** Why the connection broke, in the words the tree and the tabs say it. */
  static String why(Live.Lost lost) {
    return lost.discarded()
        ? "The connection was closed, as the server did not stop a cancelled read."
        : "The connection was lost: the server closed it, or the network to it failed.";
  }

  /** Why the connection broke while it was open, or null where it did not. */
  Live.Lost lost(UUID id) {
    var live = lives.get(id);
    return live != null && live.phase instanceof Live.Lost l ? l : null;
  }

  /**
   * A lane of the connection, which tells it when a call, anyone's, found the connection gone,
   * also where whoever asked no longer waits.
   */
  private Lane lane(State s, Live live) {
    return new Lane(e -> s.app.post(() -> lost(s, live, detail(checked(live, e)), discarded(e))));
  }

  /** The connection to a further database, opened anew where it could not be or is gone. */
  private Lane further(State s, Live live, String database) {
    var lane = live.lanes.get(database);
    if (lane == null || lane.broken()) {
      if (lane != null) {
        lane.close();
      }
      lane = lane(s, live);
      lane.open(servers, live.profile.withDatabase(database), live.password, live.driver);
      live.lanes.put(database, lane);
    }
    return lane;
  }

  /**
   * Every failure of a connection passes here before anything of it is kept: as core's typed
   * failure, with its detail withheld where it holds the password the connection was given.
   */
  private static Exception checked(Live live, Exception error) {
    var detail = detail(error);
    // Without the password at hand nothing can be told apart, so nothing is shown.
    boolean withheld = live.password == null || Probe.contains(detail, live.password);
    var shown = withheld ? ConnectionException.WITHHELD : detail;
    return switch (error) {
      case ConnectionException e when e.failure() == ConnectionException.Failure.UNFIT -> e;
      case ConnectionException e -> withheld ? ConnectionException.of(e.failure(), shown) : e;
      case CatalogException e -> withheld ? CatalogException.of(e.failure(), shown) : e;
      // A call of rows on the lane before failed it so; its kind of failure stays.
      case QueryException e ->
        CatalogException.of(
            switch (e.failure()) {
              case CONNECTION_LOST -> CatalogException.Failure.CONNECTION_LOST;
              case ABORTED -> CatalogException.Failure.ABORTED;
              case NOT_FOUND -> CatalogException.Failure.NOT_FOUND;
              case ACCESS_DENIED -> CatalogException.Failure.ACCESS_DENIED;
              case CANCELLED -> CatalogException.Failure.CANCELLED;
              case COLUMNS_CHANGED, OTHER -> CatalogException.Failure.OTHER;
            },
            shown);
      default -> CatalogException.of(CatalogException.Failure.OTHER, shown);
    };
  }

  private static String detail(Exception error) {
    return switch (error) {
      case ConnectionException e -> e.detail();
      case CatalogException e -> e.detail();
      case QueryException e -> e.detail();
      default -> State.messageOf(error);
    };
  }

  private static Lane.Call<List<Node>> tables(
      UUID id, dev.hoori.db.core.catalog.Container container) {
    return link ->
        link.tables(container).stream().map(x -> (Node) new Node.OfTable(id, x)).toList();
  }

  /** The database whose content the node is, or null for a connection or a folder. */
  static String database(Node node) {
    return switch (node) {
      case Node.Folder n -> null;
      case Node.Connection n -> null;
      case Node.OfDatabase n -> n.database().name();
      case Node.OfSchema n -> n.schema().database().name();
      case Node.OfTable n -> n.table().container().database().name();
      case Node.OfColumn n -> n.column().table().container().database().name();
      case Node.Problem(var of) -> database(of);
    };
  }

  /** The connection broke: it is closed, and its node says so. */
  private void lose(State s, Live live, String detail, boolean discarded) {
    end(live);
    s.ended(live.id());
    live.phase = new Live.Lost(detail, discarded);
    s.unconnected(live.id()); // a console that waited for the databases, whose read ended too
    var root = new Node.Connection(live.id());
    forgetBelow(s, root);
    s.tell(State.name(live.profile) + ": the connection was lost");
  }

  /** Refresh: reads the node's children again, and forgets what was below them. */
  void refresh(State s, Node node) {
    var live = lives.get(node.id());
    if (node instanceof Node.Problem(var of)) {
      retry(s, of);
      return;
    }
    if (live == null || ended(live.phase)) {
      if (node instanceof Node.Connection) {
        connect(s, node.id());
      }
      return;
    }
    if (!(live.phase instanceof Live.Connected)) {
      return;
    }
    forgetBelow(s, node);
    var gone = new ArrayList<Lane.Asked>();
    stopScans(live, node, gone);
    for (var entry : List.copyOf(live.levels.entrySet())) {
      if (entry.getKey().within(node)) {
        if (entry.getValue() instanceof Reading r) {
          gone.add(r.asked);
        }
        live.levels.remove(entry.getKey());
      }
    }
    Lane.cancel(gone);
    if (node instanceof Node.Connection) {
      // The databases are read anew: their connections go, those still opening too.
      live.lanes.values().forEach(Lane::close);
      live.lanes.clear();
    }
    open.add(node);
    read(s, live, node);
  }

  /** Try again at a node that failed: connects again, or reads again. */
  void retry(State s, Node node) {
    var live = lives.get(node.id());
    if (node instanceof Node.Connection || live == null || ended(live.phase)) {
      connect(s, node.id());
    } else {
      refresh(s, node);
    }
  }

  /** Closes what is open below the node, and selects the node if the selection was there. */
  private void forgetBelow(State s, Node node) {
    open.removeIf(n -> !n.equals(node) && n.within(node));
    if (s.selected != null && s.selected.within(node)) {
      s.selected = node;
    }
  }

  /**
   * Disconnect: cancels what runs, closes its connections in the background, folds its node, and
   * ends its consoles' sessions, after their questions where something may be open in them.
   */
  void disconnect(State s, UUID id) {
    disconnect(s, new Guard.Act("disconnect", id), id, () -> {});
  }

  private void disconnect(State s, Guard.Act act, UUID id, Runnable then) {
    var profile = s.profile(id);
    var name = profile == null ? "the connection" : State.name(profile);
    if (s.guard.asks(
        s, act, name + " disconnects", s.consoles(id), () -> disconnect(s, act, id, then))) {
      return;
    }
    var live = lives.get(id);
    boolean was = live != null && live.phase instanceof Live.Connected;
    drop(s, id);
    s.consoles(id).forEach(ConsoleTab::end);
    if (was) {
      s.tell("Disconnected from " + State.name(live.profile));
    }
    then.run();
  }

  /**
   * Forgets the tree's connection: what runs is cancelled and its connections closed; no word of
   * it. Its consoles' sessions are their own and stay; a console that waited for it says why.
   */
  void drop(State s, UUID id) {
    end(lives.remove(id));
    s.ended(id);
    s.unconnected(id);
    open.removeIf(n -> n.id().equals(id));
    if (s.selected != null && s.selected.id().equals(id)) {
      s.selected = new Node.Connection(id);
    }
    for (var entry : fetches.entrySet()) {
      if (entry.getValue().step != null && waiting(entry.getKey()) == 0) {
        entry.getValue().stop(); // no connection waits for it any more
      }
    }
  }

  private int waiting(Want want) {
    int n = 0;
    for (var live : lives.values()) {
      if (live.phase instanceof Live.ForDriver && Want.of(live.profile).equals(want)) {
        n++;
      }
    }
    return n;
  }

  /**
   * Ends what the connection began: its work is cancelled, its reads too, which interrupts them on
   * their lanes, and its connections close on threads of their own; nothing here waits for a
   * server.
   */
  private static void end(Live live) {
    if (live == null) {
      return;
    }
    if (live.work != null) {
      live.work.cancel();
      live.work = null;
    }
    if (live.handover != null) {
      live.handover.drop();
      live.handover = null;
    }
    var gone = new ArrayList<Lane.Asked>();
    if (live.connecting != null) {
      gone.add(live.connecting);
      live.connecting = null;
    }
    for (var level : live.levels.values()) {
      if (level instanceof Reading r) {
        gone.add(r.asked);
      }
    }
    stopScans(live, new Node.Connection(live.id()), gone);
    Lane.cancel(gone);
    live.levels.clear();
    if (live.lane != null) {
      live.lane.close();
      live.lane = null;
    }
    live.lanes.values().forEach(Lane::close);
    live.lanes.clear();
    live.planners.values().forEach(Lane::close);
    live.planners.clear();
    live.counters.values().forEach(Lane::close);
    live.counters.clear();
    if (live.password != null) {
      Arrays.fill(live.password, '\0');
      live.password = null;
    }
    live.typed = "";
  }

  /** A window of tabs closed: what it was to ask goes to the main window. */
  void closed(TabGroups.Group<Tab> window) {
    for (var live : lives.values()) {
      if (live.window == window) {
        live.window = null;
      }
    }
  }

  /** The window closes: everything ends, without waiting for a server. */
  void closeAll() {
    lives.values().forEach(CatalogTree::end);
    lives.clear();
    fetches.values().forEach(Fetch::close);
  }

  /** The tree's connections that use the version of the driver or decided on it, by name. */
  List<String> users(State s, Driver driver, String version) {
    return lives.values().stream()
        .filter(live -> !ended(live.phase)
            && Driver.of(live.profile.provider()).equals(driver)
            && version.equals(version(s, live)))
        .map(live -> State.name(live.profile))
        .toList();
  }

  /** The version the connection decided on: its driver's, or the one its fetch found; or null. */
  private String version(State s, Live live) {
    if (live.driver != null) {
      return s.drivers.version(live.driver);
    }
    var fetch = fetches.get(Want.of(live.profile));
    return live.phase instanceof Live.ForDriver
            && fetch != null
            && fetch.known instanceof Fetch.Found f
        ? f.version()
        : null;
  }

  /** Edit or Delete on a connection: asks to disconnect first while it is connected. */
  void change(State s, ConnectionProfile profile, Then then) {
    if (active(profile.id())) {
      disconnecting = new Disconnecting(profile, then);
      return;
    }
    // Its consoles' sessions end too, as their connection changes or goes.
    disconnect(s, act(profile, then), profile.id(), () -> go(s, profile, then));
  }

  /** Edit's or Delete's act, which its disconnect asks for. */
  private static Guard.Act act(ConnectionProfile profile, Then then) {
    return then == Then.DELETE ? State.deleteAct(profile) : new Guard.Act("edit", profile.id());
  }

  /**
   * The edit or the delete, once disconnected: refused where a dialog was opened meanwhile, as
   * while a console's question waited, which it would replace.
   */
  private static void go(State s, ConnectionProfile profile, Then then) {
    if (s.dialogs.anyOpen()) {
      s.tell("Another dialog is open, so " + State.name(profile) + " was not "
          + (then == Then.EDIT ? "opened for editing." : "asked about deleting."));
      return;
    }
    switch (then) {
      case EDIT -> s.edit(profile);
      case DELETE -> s.askDelete(profile);
    }
  }

  /** The question's Disconnect: and then the edit or the delete. */
  void disconnectAndGo(State s) {
    var d = disconnecting;
    disconnecting = null;
    disconnect(s, act(d.profile(), d.then()), d.profile().id(), () -> go(s, d.profile(), d.then()));
  }

  /** Copy name: the node's own name, without its type. */
  void copyName(State s, Node node) {
    s.app.clipboard().write(name(s, node));
    s.tell("Copied " + name(s, node));
  }

  private static String name(State s, Node node) {
    return switch (node) {
      case Node.Folder(var id) -> s.folder(id).name();
      case Node.Connection(var id) -> State.name(s.profile(id));
      case Node.OfDatabase n -> n.database().name();
      case Node.OfSchema n -> n.schema().name();
      case Node.OfTable n -> n.table().name();
      case Node.OfColumn n -> n.column().name();
      case Node.Problem(var of) -> name(s, of);
    };
  }

  // The view

  /** The text of a node's row. */
  String label(State s, Node node) {
    return switch (node) {
      case Node.Problem(var of) -> problem(s, of).title();
      default -> name(s, node);
    };
  }

  /** The muted text after a node's name: a column's type, a connection's kind of server. */
  String detail(State s, Node node) {
    return switch (node) {
      case Node.OfColumn n -> n.column().type();
      case Node.Connection(var id) -> s.profile(id).provider().displayName();
      default -> null;
    };
  }

  /**
   * Why a node could not be opened or read, and what the user can do.
   *
   * @param password whether the user can enter the password again
   */
  record Trouble(String title, String text, boolean password) {}

  /** What went wrong at the node, or null. */
  Trouble problem(State s, Node node) {
    var live = lives.get(node.id());
    if (live == null) {
      return null;
    }
    if (failedWhole(live, node)) {
      var p = live.profile;
      return switch (live.phase) {
        case Live.Failed(var failure, var detail, var withPassword) -> {
          var note = failure == ConnectionException.Failure.UNFIT
              ? "Its settings cannot connect as they are. Edit the connection to complete them."
              : Words.note(failure, p, withPassword);
          yield new Trouble(
              Words.title(failure),
              join(note, detail),
              failure == ConnectionException.Failure.LOGIN_REFUSED);
        }
        case Live.Lost l -> new Trouble("Disconnected", join(why(l), l.detail()), false);
        case Live.WithoutDrivers _ ->
          new Trouble(
              "No driver",
              "A script run downloads no drivers, so connections cannot be opened.",
              false);
        default -> null;
      };
    }
    return switch (live.levels.get(node)) {
      case NotRead(var failure, var detail) ->
        new Trouble(title(failure), join(text(failure, s, node), detail), false);
      case NotOpened(var failure, var detail) ->
        new Trouble(
            "The database could not be opened",
            join(
                Words.note(
                    failure,
                    database(node) == null
                        ? live.profile
                        : live.profile.withDatabase(database(node)),
                    true),
                detail),
            false);
      case null, default -> null;
    };
  }

  /**
   * Whether the node is a connection that failed as a whole, shown by a row of its own and the
   * callout's actions. Any other node's failed read, a connected connection's databases too, shows
   * in the tree's row of its failure, with its action.
   */
  static boolean failedWhole(Live live, Node node) {
    return node instanceof Node.Connection && !(live.phase instanceof Live.Connected);
  }

  /** A note and the server's words below it, either of which may be missing. */
  static String join(String note, String detail) {
    if (note == null) {
      return detail;
    }
    return detail == null || detail.isEmpty() ? note : note + "\n" + detail;
  }

  private static String title(CatalogException.Failure failure) {
    return switch (failure) {
      case CONNECTION_LOST, ABORTED -> "Disconnected";
      case NOT_FOUND -> "Not found";
      case ACCESS_DENIED -> "Access denied";
      case WRONG_DATABASE -> "Wrong database";
      case CANCELLED -> "Cancelled";
      case OTHER -> "Could not be read";
    };
  }

  private String text(CatalogException.Failure failure, State s, Node node) {
    var name = name(s, node);
    return switch (failure) {
      case CONNECTION_LOST -> "The connection was lost.";
      case ABORTED -> "The connection was closed, as the server did not stop a cancelled read.";
      case NOT_FOUND ->
        name + " is no longer there, or this user may not see it. Refresh what holds it.";
      case ACCESS_DENIED -> "The server does not let this user read what " + name + " holds.";
      case WRONG_DATABASE -> "The connection is to another database than the one of " + name + ".";
      case CANCELLED -> "The server cancelled the read.";
      case OTHER -> "The server could not tell what " + name + " holds.";
    };
  }

  /** The callout under the tree for the selected node or its failure row, or null. */
  El callout(State s) {
    var node = s.selected instanceof Node.Problem(var of) ? of : s.selected;
    if (node == null) {
      return null;
    }
    var trouble = problem(s, node);
    if (trouble == null) {
      return null;
    }
    var live = lives.get(node.id());
    // A console in front that could not run says it already, where the run was asked for.
    for (var group : s.tabs.groups()) {
      if (failedWhole(live, node)
          && group.activeTab() instanceof ConsoleTab x
          && x.activity instanceof ConsoleTab.Stranded
          && x.id.equals(node.id())) {
        return null;
      }
    }
    var actions = new ArrayList<El>();
    if (trouble.password()) {
      actions.add(button(SECONDARY, "Enter password…", () -> askPassword(s, live)));
    }
    // The tree's row of a node's failed read has Try again itself.
    if (failedWhole(live, node)) {
      actions.add(button(SECONDARY, TRY_AGAIN, () -> retry(s, node)));
    }
    return Callout.callout(
        Callout.Kind.DANGER, trouble.title(), trouble.text(), actions.toArray(El[]::new));
  }

  /** The context menu of the selected node. */
  List<Menu.Item> items(State s, Node node) {
    if (node == null) {
      return List.of();
    }
    var live = lives.get(node.id());
    boolean connected = live != null && live.phase instanceof Live.Connected;
    Menu.Item refresh = Menu.item("Refresh", connected ? () -> refresh(s, node) : null)
        .icon(Symbol.ARROW_CLOCKWISE);
    Menu.Item copy = Menu.item("Copy name", () -> copyName(s, node)).icon(Symbol.COPY);
    return switch (node) {
      case Node.Folder n -> {
        if (s.folder(n.id()) == null) {
          yield List.of();
        }
        yield List.of(
            Menu.item("New connection…", s.canChange() ? s::newConnection : null)
                .icon(Symbol.ADD),
            Menu.item("New folder…", s.canAddFolder() ? s::newFolder : null).icon(Symbol.FOLDER),
            Menu.separator(),
            Menu.item("Rename…", () -> s.askRenameFolder(n.id())).icon(Symbol.EDIT),
            moveTo(s, n),
            Menu.separator(),
            Menu.item("Delete…", () -> s.askDeleteFolder(n.id())).icon(Symbol.DELETE));
      }
      case Node.Connection n -> {
        var p = s.profile(n.id());
        yield List.of(
            Menu.item("New console", () -> s.openConsole(n.id())).icon(Symbol.CODE),
            Menu.item("Server monitor", () -> s.openMonitor(n.id(), s.tabs.main()))
                .icon(Symbol.SERVER),
            Menu.separator(),
            Menu.item("Disconnect", active(n.id()) ? () -> disconnect(s, n.id()) : null)
                .icon(Symbol.PLUG_DISCONNECTED),
            refresh,
            copy,
            Menu.separator(),
            Menu.item("Edit…", () -> change(s, p, Then.EDIT)).icon(Symbol.EDIT),
            Menu.submenu("Color", colors(s, p)),
            Menu.item("Duplicate", () -> s.duplicate(p)).icon(Symbol.COPY),
            moveTo(s, n),
            Menu.separator(),
            Menu.item("Delete…", () -> change(s, p, Then.DELETE)).icon(Symbol.DELETE));
      }
      case Node.Problem n ->
        List.of(Menu.item(TRY_AGAIN, () -> retry(s, n.of())).icon(Symbol.ARROW_CLOCKWISE));
      case Node.OfTable n ->
        List.of(
            Menu.item("Open", () -> s.openTable(n.id(), n.table())).icon(Symbol.TABLE),
            refresh,
            copy);
      default -> List.of(refresh, copy);
    };
  }

  /**
   * Move to: the top and every folder by its path, but the node itself and the folders it holds;
   * where it is now checked, and where it does not fit disabled.
   */
  private static Menu.Item moveTo(State s, Node node) {
    var path = s.workspace().path(node.id());
    UUID now = path == null || path.isEmpty() ? null : path.getLast().id();
    var items = new ArrayList<Menu.Item>();
    items.add(Menu.item(
            "Top level",
            now != null && s.canMove(node, null, Workspace.Place.INTO)
                ? () -> s.move(node, null, Workspace.Place.INTO)
                : null)
        .checked(now == null));
    folders(s, node, now, s.workspace().entries(), "", items);
    return Menu.submenu("Move to", items);
  }

  private static void folders(
      State s,
      Node node,
      UUID now,
      List<Workspace.Entry> entries,
      String above,
      List<Menu.Item> items) {
    for (var entry : entries) {
      if (!(entry instanceof Workspace.Folder f) || f.id().equals(node.id())) {
        continue;
      }
      var target = new Node.Folder(f.id());
      String name = above + f.name();
      items.add(Menu.item(
              name,
              !f.id().equals(now) && s.canMove(node, target, Workspace.Place.INTO)
                  ? () -> s.move(node, target, Workspace.Place.INTO)
                  : null)
          .checked(f.id().equals(now)));
      folders(s, node, now, f.entries(), name + " / ", items);
    }
  }

  /** Whether the user may drag the node: a folder or a connection, not what is below one. */
  static boolean canDrag(Node node) {
    return node instanceof Node.Folder || node instanceof Node.Connection;
  }

  /** Whether the node can go where the pointer is; asked at every step of a drag. */
  static boolean canDrop(State s, Tree.Move<Node> move) {
    return s.canMove(move.node(), move.target(), place(move.place()));
  }

  /** Moves the dropped node and selects it, so that the rows follow it. */
  static void dropped(State s, Tree.Move<Node> move) {
    s.move(move.node(), move.target(), place(move.place()));
  }

  private static Workspace.Place place(Tree.Place place) {
    return switch (place) {
      case BEFORE -> Workspace.Place.BEFORE;
      case AFTER -> Workspace.Place.AFTER;
      case INTO -> Workspace.Place.INTO;
    };
  }

  // Also while connected: a color changes neither the connection nor its tabs.
  private static List<Menu.Item> colors(State s, ConnectionProfile p) {
    return ConnectionColors.CHOICES.stream()
        .map(c -> {
          var item = Menu.item(ConnectionColors.label(c), () -> s.put(p.withColor(c)))
              .checked(c == p.color());
          return c == null ? item : item.leading(() -> HuePicker.swatch(ConnectionColors.hue(c)));
        })
        .toList();
  }

  /** The dialog that asks something for the catalog, or null: one at a time. */
  El dialog(State s, TabGroups.Group<Tab> group) {
    boolean main = group == s.tabs.main();
    if (main && disconnecting != null) {
      return CatalogDialogs.disconnect(s, disconnecting);
    }
    var fetch = main ? asking() : null;
    if (fetch != null) {
      return CatalogDialogs.driver(s, fetch, want(fetch));
    }
    var live = forPassword(s, group);
    return live == null ? null : CatalogDialogs.password(s, live);
  }

  /** Whether {@link #dialog} shows one in the window of these tabs, without making it. */
  boolean asks(State s, TabGroups.Group<Tab> group) {
    boolean main = group == s.tabs.main();
    return main && (disconnecting != null || asking() != null) || forPassword(s, group) != null;
  }

  /** The connection whose password the window of these tabs asks for, or null. */
  private Live forPassword(State s, TabGroups.Group<Tab> group) {
    for (var live : lives.values()) {
      // In the window that asked, while it is open; else in the main one, where its tabs went.
      var asked = live.window != null ? live.window : s.tabs.main();
      if (live.phase instanceof Live.ForPassword && asked == group) {
        return live;
      }
    }
    return null;
  }
}

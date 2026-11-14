package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.kit.Tree;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.LoadedDriver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The connections as a tree of what their servers hold: opening a connection connects, and each
 * level below it is read when it is opened. Each connection to a server is a {@link Lane}, which
 * serves one call at a time; connections do not wait for one another. Only the app's thread uses
 * it.
 */
final class Catalogue {

  /** Where a connection the user opened is. */
  sealed interface Phase {}

  /** For its driver, which the {@link Fetch} of its kind gets. */
  record ForDriver() implements Phase {}

  /** For the system keyring to be found. */
  record ForKeyring() implements Phase {}

  /** For the keyring to give the password. */
  record ReadingPassword() implements Phase {}

  /** For the user to give the password. */
  record ForPassword() implements Phase {}

  record Connecting() implements Phase {}

  /**
   * @param database the one database the first connection reads, or null where it reads them all
   * @param schemas whether the server has a level of schemas
   */
  record Connected(ServerVersion version, String database, boolean schemas) implements Phase {}

  /** @param withPassword whether a password was sent */
  record Failed(ConnectionException.Failure failure, String detail, boolean withPassword)
      implements Phase {}

  /** The connection broke while it was open. */
  record Lost(String detail) implements Phase {}

  /** A script run has no drivers. */
  record WithoutDrivers() implements Phase {}

  /** What is known of a node's children. */
  sealed interface Level {}

  /** They are being read; the work is set once it is asked for. */
  static final class Reading implements Level {
    Work work;
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

  /** A connection the user opened: its server connections and what was read of them. */
  static final class Live {
    final ConnectionProfile profile; // as it was when opened
    Phase phase;
    Work work; // the step under way: reading the password or connecting; or null
    LoadedDriver driver;
    // While connected, for the connections to further databases; wiped when it ends.
    char[] password;
    Lane lane; // the first connection
    final Map<String, Lane> lanes = new HashMap<>(); // further connections, by database
    final Map<Node, Level> levels = new HashMap<>();
    // The password dialog; the kit's field holds a String, which goes with the dialog.
    String typed = "";
    boolean keep;

    Live(ConnectionProfile profile) {
      this.profile = profile;
    }

    UUID id() {
      return profile.id();
    }
  }

  private final Servers servers;
  final Map<UUID, Live> lives = new LinkedHashMap<>(); // the connections opened, in that order
  final Set<Node> open = new HashSet<>();
  private final Map<Provider, Fetch> fetches = new EnumMap<>(Provider.class);
  final Menu.State menu = new Menu.State();
  Disconnecting disconnecting; // the question before an edit or a delete, or null

  Catalogue(Servers servers) {
    this.servers = servers;
  }

  /** Whether the connection is connected or connecting. */
  boolean active(UUID id) {
    var live = lives.get(id);
    return live != null && !ended(live.phase);
  }

  private static boolean ended(Phase phase) {
    return phase instanceof Failed || phase instanceof Lost || phase instanceof WithoutDrivers;
  }

  // Handlers

  /** The user opens or closes a node: opening a connection connects, opening a level reads it. */
  void toggle(State s, Node node, boolean opens) {
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
    if (live != null && live.phase instanceof Connected && !(node instanceof Node.Problem)) {
      var level = live.levels.get(node);
      if (level == null || level instanceof NotRead || level instanceof NotOpened) {
        read(s, live, node); // opening it again tries again
      }
    }
  }

  /** A double press or Enter: a connection that is not open connects. */
  void activate(State s, Node node) {
    if (node instanceof Node.Connection c) {
      var live = lives.get(c.id());
      if (live == null || ended(live.phase)) {
        open.add(node);
        connect(s, c.id());
      }
    }
  }

  /** Connects anew, as far as is needed: the driver, the password, the server. */
  void connect(State s, UUID id) {
    var profile = s.profile(id);
    if (profile == null) {
      return;
    }
    end(lives.remove(id));
    forgetBelow(s, new Node.Connection(id));
    open.add(new Node.Connection(id));
    var live = new Live(profile);
    lives.put(id, live);
    if (s.drivers == null) {
      live.phase = new WithoutDrivers();
      return;
    }
    var provider = profile.provider();
    var loaded = s.drivers.loaded(Driver.of(provider));
    if (loaded != null) {
      password(s, live, loaded);
      return;
    }
    live.phase = new ForDriver();
    var fetch = fetches.computeIfAbsent(provider, p -> new Fetch((st, d) -> fetched(st, p, d)));
    if (fetch.step == null) {
      fetch.lookUp(s, provider);
      fetch.start(s);
    }
  }

  private void fetched(State s, Provider provider, LoadedDriver driver) {
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof ForDriver && live.profile.provider() == provider) {
        password(s, live, driver);
      }
    }
  }

  /** Not now, Cancel download or Escape: the connections that wait for the driver stay closed. */
  void stopFetch(State s, Provider provider) {
    fetches.get(provider).stop();
    var names = new ArrayList<String>();
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof ForDriver && live.profile.provider() == provider) {
        names.add(State.name(live.profile));
        drop(s, live.id());
      }
    }
    if (!names.isEmpty()) {
      s.tell(String.join(", ", names) + " not connected, as " + Fetch.name(provider)
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
        if (live.phase instanceof ForDriver && live.profile.provider() == entry.getKey()) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private Provider provider(Fetch fetch) {
    for (var entry : fetches.entrySet()) {
      if (entry.getValue() == fetch) {
        return entry.getKey();
      }
    }
    throw new IllegalArgumentException("Not a fetch of the catalogue");
  }

  /** The password kept for this session, else the one in the keyring, else the user's. */
  private void password(State s, Live live, LoadedDriver driver) {
    live.driver = driver;
    var id = live.id();
    char[] kept = s.rememberedCopy(id);
    if (kept != null) {
      connect(s, live, kept);
      return;
    }
    if (s.keyring == null) {
      live.phase = new ForKeyring();
      return;
    }
    var secrets = s.secrets();
    if (secrets == null) {
      live.phase = new ForPassword();
      return;
    }
    live.phase = new ReadingPassword();
    live.work = s.app.background(
        "read a password",
        () -> secrets.read(id).orElse(null),
        password -> {
          live.work = null;
          if (password == null) {
            live.phase = new ForPassword();
          } else {
            connect(s, live, password);
          }
        },
        error -> {
          live.work = null;
          live.phase = new ForPassword(); // the user can still type it
        });
  }

  /** The system keyring was found: connections that waited for it go on. */
  void keyringFound(State s) {
    for (var live : List.copyOf(lives.values())) {
      if (live.phase instanceof ForKeyring) {
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

  /** Back to the password, after the server refused it. */
  void askPassword(Live live) {
    live.typed = "";
    live.keep = false;
    live.phase = new ForPassword();
  }

  /** Opens the first connection, with the password, which is the live's now. */
  private void connect(State s, Live live, char[] password) {
    live.password = password;
    live.phase = new Connecting();
    live.lane = new Lane();
    var opening = live.lane.open(servers, live.profile, password, live.driver);
    // Its databases are asked for right behind it, on its lane: they need no answer before.
    read(s, live, new Node.Connection(live.id()));
    record Opened(ServerVersion version, String database) {}
    live.work = s.app.background(
        "connect to " + State.name(live.profile),
        () -> {
          var link = Lane.await(opening);
          return new Opened(link.version(), link.database());
        },
        opened -> {
          live.work = null;
          boolean schemas = live.profile.provider().supports(Capability.SCHEMAS, opened.version());
          live.phase = new Connected(opened.version(), opened.database(), schemas);
          s.tell("Connected to " + State.name(live.profile));
        },
        error -> {
          live.work = null;
          var refused = error instanceof Servers.NotConnected n
              ? n
              : new Servers.NotConnected(ConnectionException.Failure.OTHER, State.message(error));
          String detail =
              Probe.contains(refused.detail, live.password) ? Probe.HIDDEN : refused.detail;
          live.phase = new Failed(refused.failure, detail, live.password.length > 0);
          end(live);
          s.tell(State.name(live.profile) + ": " + Words.title(refused.failure));
        });
  }

  /** Reads the children of the node on the connection that reads its database. */
  private void read(State s, Live live, Node node) {
    // The databases are read on the first connection, also while it opens.
    var c = live.phase instanceof Connected connected ? connected : null;
    if (live.lane == null || c == null && !(node instanceof Node.Connection)) {
      return;
    }
    var id = live.id();
    Lane.Call<List<Node>> call = switch (node) {
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
    };
    if (call == null) {
      return;
    }
    var database = database(node);
    var lane = database == null || c.database() == null || database.equals(c.database())
        ? live.lane
        : live.lanes.computeIfAbsent(database, name -> {
          var further = new Lane();
          further.open(servers, live.profile.withDatabase(name), live.password, live.driver);
          return further;
        });
    var reading = new Reading();
    live.levels.put(node, reading);
    var asked = lane.submit(call);
    reading.work = s.app.background(
        "read " + label(s, node),
        () -> Lane.await(asked),
        children -> {
          if (live.levels.get(node) == reading) {
            live.levels.put(node, new Read(children));
          }
        },
        error -> {
          if (live.levels.get(node) != reading) {
            return;
          }
          switch (error) {
            case Servers.NotRead e
            when e.failure == CatalogException.Failure.CONNECTION_LOST -> lose(s, live, e.detail);
            case Servers.NotRead e -> live.levels.put(node, new NotRead(e.failure, e.detail));
            case Servers.NotConnected e -> {
              // The next read opens it again.
              var lost = live.lanes.remove(database);
              if (lost != null) {
                lost.close();
              }
              live.levels.put(node, new NotOpened(e.failure, e.detail));
            }
            default ->
              live.levels.put(
                  node, new NotRead(CatalogException.Failure.OTHER, State.message(error)));
          }
        });
  }

  private static Lane.Call<List<Node>> tables(
      UUID id, dev.hoori.db.core.catalog.Container container) {
    return link ->
        link.tables(container).stream().map(x -> (Node) new Node.OfTable(id, x)).toList();
  }

  /** The database whose content the node is, or null for a connection. */
  private static String database(Node node) {
    return switch (node) {
      case Node.Connection n -> null;
      case Node.OfDatabase n -> n.database().name();
      case Node.OfSchema n -> n.schema().database().name();
      case Node.OfTable n -> n.table().container().database().name();
      case Node.OfColumn n -> n.column().table().container().database().name();
      case Node.Problem n -> database(n.of());
    };
  }

  /** The connection broke: it is closed, and its node says so. */
  private void lose(State s, Live live, String detail) {
    end(live);
    live.phase = new Lost(detail);
    var root = new Node.Connection(live.id());
    forgetBelow(s, root);
    s.tell(State.name(live.profile) + ": the connection was lost");
  }

  /** Refresh: reads the node's children again, and forgets what was below them. */
  void refresh(State s, Node node) {
    var live = lives.get(node.id());
    if (node instanceof Node.Problem p) {
      retry(s, p.of());
      return;
    }
    if (live == null || ended(live.phase)) {
      if (node instanceof Node.Connection) {
        connect(s, node.id());
      }
      return;
    }
    if (!(live.phase instanceof Connected)) {
      return;
    }
    forgetBelow(s, node);
    for (var entry : List.copyOf(live.levels.entrySet())) {
      if (entry.getKey().within(node)) {
        if (entry.getValue() instanceof Reading r) {
          r.work.cancel();
        }
        live.levels.remove(entry.getKey());
      }
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

  /** Disconnect: cancels what runs, closes its connections in the background, folds its node. */
  void disconnect(State s, UUID id) {
    var live = lives.get(id);
    boolean was = live != null && live.phase instanceof Connected;
    drop(s, id);
    if (was) {
      s.tell("Disconnected from " + State.name(live.profile));
    }
  }

  /** Forgets the connection: what runs is cancelled and its connections closed; no word of it. */
  void drop(State s, UUID id) {
    end(lives.remove(id));
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

  private int waiting(Provider provider) {
    int n = 0;
    for (var live : lives.values()) {
      if (live.phase instanceof ForDriver && live.profile.provider() == provider) {
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
    for (var level : live.levels.values()) {
      if (level instanceof Reading r) {
        r.work.cancel();
      }
    }
    live.levels.clear();
    if (live.lane != null) {
      live.lane.close();
      live.lane = null;
    }
    live.lanes.values().forEach(Lane::close);
    live.lanes.clear();
    if (live.password != null) {
      Arrays.fill(live.password, '\0');
      live.password = null;
    }
    live.typed = "";
  }

  /** The window closes: everything ends, without waiting for a server. */
  void closeAll() {
    lives.values().forEach(Catalogue::end);
    lives.clear();
    fetches.values().forEach(Fetch::close);
  }

  /** Edit or Delete on a connection: asks to disconnect first while it is connected. */
  void change(State s, ConnectionProfile profile, Then then) {
    if (active(profile.id())) {
      disconnecting = new Disconnecting(profile, then);
      return;
    }
    drop(s, profile.id());
    go(s, profile, then);
  }

  private static void go(State s, ConnectionProfile profile, Then then) {
    switch (then) {
      case EDIT -> s.edit(profile);
      case DELETE -> s.askDelete(profile);
    }
  }

  /** The question's Disconnect: and then the edit or the delete. */
  void disconnectAndGo(State s) {
    var d = disconnecting;
    disconnecting = null;
    disconnect(s, d.profile().id());
    go(s, d.profile(), d.then());
  }

  /** Copy name: the node's own name, without its type. */
  void copyName(State s, Node node) {
    s.app.clipboard().write(name(s, node));
    s.tell("Copied " + name(s, node));
  }

  private static String name(State s, Node node) {
    return switch (node) {
      case Node.Connection n -> State.name(s.profile(n.id()));
      case Node.OfDatabase n -> n.database().name();
      case Node.OfSchema n -> n.schema().name();
      case Node.OfTable n -> n.table().name();
      case Node.OfColumn n -> n.column().name();
      case Node.Problem n -> name(s, n.of());
    };
  }

  // The view

  /** The text of a node's row. */
  String label(State s, Node node) {
    return switch (node) {
      // shortcut: the kit's tree has no muted text after a row's name, so the type follows the
      // name in the label; move it there once the tree has it.
      case Node.OfColumn n -> n.column().name() + ": " + n.column().type();
      case Node.Problem n -> problem(s, n.of()).title();
      default -> name(s, node);
    };
  }

  /** Why a node could not be opened or read, and what the user can do. */
  /** @param password whether the user can enter the password again */
  record Trouble(String title, String text, boolean password) {}

  /** What went wrong at the node, or null. */
  Trouble problem(State s, Node node) {
    var live = lives.get(node.id());
    if (live == null) {
      return null;
    }
    if (node instanceof Node.Connection) {
      var p = live.profile;
      return switch (live.phase) {
        case Failed f -> {
          var note = f.failure() == ConnectionException.Failure.UNFIT
              ? "Its settings cannot connect as they are. Edit the connection to complete them."
              : Words.note(f.failure(), p, f.withPassword());
          yield new Trouble(
              Words.title(f.failure()),
              join(note, f.detail()),
              f.failure() == ConnectionException.Failure.LOGIN_REFUSED);
        }
        case Lost l ->
          new Trouble(
              "Disconnected",
              join(
                  "The connection was lost: the server closed it, or the network to it failed.",
                  l.detail()),
              false);
        case WithoutDrivers w ->
          new Trouble(
              "No driver",
              "A script run downloads no drivers, so connections cannot be opened.",
              false);
        default -> null;
      };
    }
    return switch (live.levels.get(node)) {
      case NotRead r ->
        new Trouble(title(r.failure()), join(text(r.failure(), s, node), r.detail()), false);
      case NotOpened o ->
        new Trouble(
            "The database could not be opened",
            join(
                Words.note(o.failure(), live.profile.withDatabase(database(node)), true),
                o.detail()),
            false);
      case null, default -> null;
    };
  }

  private static String join(String note, String detail) {
    if (note == null) {
      return detail;
    }
    return detail == null || detail.isEmpty() ? note : note + "\n" + detail;
  }

  private static String title(CatalogException.Failure failure) {
    return switch (failure) {
      case CONNECTION_LOST -> "Disconnected";
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
      case NOT_FOUND ->
        name + " is no longer there, or this user may not see it. Refresh what holds it.";
      case ACCESS_DENIED -> "The server does not let this user read what " + name + " holds.";
      case WRONG_DATABASE -> "The connection is to another database than the one of " + name + ".";
      case CANCELLED -> "The server cancelled the read.";
      case OTHER -> "The server could not tell what " + name + " holds.";
    };
  }

  /** The callout under the tree for the selected node or its failure row, or null. */
  El callout(Theme t, State s) {
    var node = s.selected instanceof Node.Problem p ? p.of() : s.selected;
    if (node == null) {
      return null;
    }
    var trouble = problem(s, node);
    if (trouble == null) {
      return null;
    }
    var actions = new ArrayList<El>();
    if (trouble.password()) {
      var live = lives.get(node.id());
      actions.add(button(t, SECONDARY, "Enter password…", () -> askPassword(live)));
    }
    actions.add(button(t, SECONDARY, "Try again", () -> retry(s, node)));
    // shortcut: a callout puts its buttons beside its text, which squeezes it into a narrow column
    // in the sidebar, so they go below it; pass them to the callout once it can put them there.
    return col()
        .wFull()
        .gap(t.space().s())
        .child(Callout.callout(t, Callout.Kind.DANGER, trouble.title(), trouble.text()))
        .child(Form.buttons(t, actions.toArray(El[]::new)));
  }

  /** The tree's nodes, as the state holds them. */
  record Model(Theme t, State s) implements Tree.Model<Node> {

    @Override
    public List<Node> roots() {
      return s.profiles.stream().map(p -> (Node) new Node.Connection(p.id())).toList();
    }

    @Override
    public List<Node> children(Node node) {
      var live = s.catalogue.lives.get(node.id());
      if (live == null) {
        return List.of();
      }
      if (node instanceof Node.Connection && !(live.phase instanceof Connected)) {
        return ended(live.phase) ? List.of(new Node.Problem(node)) : null;
      }
      return switch (live.levels.get(node)) {
        case Read r -> r.children();
        case NotRead r -> List.of(new Node.Problem(node));
        case NotOpened o -> List.of(new Node.Problem(node));
        case Reading r -> null;
        case null -> null;
      };
    }

    @Override
    public boolean isLeaf(Node node) {
      return node instanceof Node.OfColumn || node instanceof Node.Problem;
    }

    @Override
    public String label(Node node) {
      return s.catalogue.label(s, node);
    }

    @Override
    public String loading(Node node) {
      var live = s.catalogue.lives.get(node.id());
      if (!(node instanceof Node.Connection) || live == null) {
        return "Loading…";
      }
      return switch (live.phase) {
        case ForDriver f -> "Waiting for the driver…";
        case ForKeyring k -> "Looking for the system keyring…";
        case ReadingPassword r -> "Reading the password…";
        case ForPassword p -> "Waiting for the password…";
        case Connecting c -> "Connecting…";
        default -> "Loading…";
      };
    }

    @Override
    public El icon(Node node) {
      return switch (node) {
        case Node.Connection n -> connection(n);
        case Node.OfDatabase n -> icon(Symbol.DATABASE, n.database().system());
        case Node.OfSchema n -> icon(Symbol.FOLDER, n.schema().system());
        case Node.OfTable n ->
          icon(n.table().kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE, system(n.table()));
        case Node.OfColumn n ->
          n.column().keyPosition() > 0
              ? Icon.icon(t, Symbol.KEY, Icon.Variant.WARNING)
              : icon(Symbol.COLUMN_SINGLE, false);
        case Node.Problem n -> Icon.icon(t, Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
      };
    }

    // shortcut: the kit's tree has no muted label, so only the icon of a system object is quieter;
    // mute its name too once the tree can.
    private El icon(Symbol symbol, boolean system) {
      return Icon.icon(t, symbol, system ? Icon.Variant.DISABLED : Icon.Variant.MUTED);
    }

    private static boolean system(Table table) {
      return switch (table.container()) {
        case dev.hoori.db.core.catalog.Database d -> d.system();
        case dev.hoori.db.core.catalog.Schema x -> x.system();
      };
    }

    // shortcut: the kit has no symbol per database, so a badge names the provider; its colour
    // says whether it is connected.
    private El connection(Node.Connection n) {
      var profile = s.profile(n.id());
      var provider = profile.provider();
      String code = switch (provider) {
        case POSTGRESQL -> "PG";
        case MARIADB -> "MA";
        case MYSQL -> "MY";
      };
      var live = s.catalogue.lives.get(n.id());
      Phase phase = live == null ? null : live.phase;
      Badge.Kind kind = switch (phase) {
        case Connected c -> Badge.Kind.SUCCESS;
        case Failed f -> Badge.Kind.DANGER;
        case Lost l -> Badge.Kind.DANGER;
        case null, default -> Badge.Kind.NEUTRAL;
      };
      // One width for the three, so that the names line up.
      El badge = Badge.badge(t, kind, code)
          .label(provider.displayName())
          .w(t.size().control() + t.space().s())
          .justify(Style.Content.CENTER);
      El icon = Tooltip.tooltip(t, badge, provider.displayName());
      if (phase == null || phase instanceof Connected || ended(phase)) {
        return icon;
      }
      return row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(icon)
          .child(Progress.spinner(t).label("Connecting"));
    }
  }

  /** The context menu of the selected node. */
  List<Menu.Item> items(State s, Node node) {
    if (node == null) {
      return List.of();
    }
    var live = lives.get(node.id());
    boolean connected = live != null && live.phase instanceof Connected;
    Menu.Item refresh = Menu.item("Refresh", connected ? () -> refresh(s, node) : null)
        .icon(Symbol.ARROW_CLOCKWISE);
    Menu.Item copy = Menu.item("Copy name", () -> copyName(s, node)).icon(Symbol.COPY);
    return switch (node) {
      case Node.Connection n -> {
        var p = s.profile(n.id());
        yield List.of(
            Menu.item("Disconnect", active(n.id()) ? () -> disconnect(s, n.id()) : null)
                .icon(Symbol.PLUG_DISCONNECTED),
            refresh,
            copy,
            Menu.separator(),
            Menu.item("Edit…", () -> change(s, p, Then.EDIT)).icon(Symbol.EDIT),
            Menu.item("Duplicate", () -> s.duplicate(p)).icon(Symbol.COPY),
            Menu.separator(),
            Menu.item("Delete…", () -> change(s, p, Then.DELETE)).icon(Symbol.DELETE));
      }
      case Node.Problem n ->
        List.of(Menu.item("Try again", () -> retry(s, n.of())).icon(Symbol.ARROW_CLOCKWISE));
      default -> List.of(refresh, copy);
    };
  }

  /** The dialog that asks something for the catalogue, or null: one at a time. */
  El dialog(Theme t, State s) {
    if (disconnecting != null) {
      return disconnectDialog(t, s);
    }
    var fetch = asking();
    if (fetch != null) {
      var provider = provider(fetch);
      Runnable stop = () -> stopFetch(s, provider);
      List<El> buttons = fetch.step instanceof Fetch.Failed
          ? List.of(button(t, SECONDARY, "Cancel", stop))
          : List.of();
      // As wide as the connection dialog, so that the question wraps.
      El content = col().w(2 * t.size().field()).child(fetch.view(t, s, stop));
      return Dialog.dialog(t, "Driver needed", content, buttons, stop);
    }
    for (var live : lives.values()) {
      if (live.phase instanceof ForPassword) {
        return passwordDialog(t, s, live);
      }
    }
    return null;
  }

  private El disconnectDialog(Theme t, State s) {
    var d = disconnecting;
    String name = State.name(d.profile());
    String what = switch (d.then()) {
      case EDIT -> "To edit it, HooriDB disconnects it first.";
      case DELETE -> "To delete it, HooriDB disconnects it first.";
    };
    El disconnect = button(t, PRIMARY, "Disconnect", () -> disconnectAndGo(s));
    return Dialog.dialog(
        t,
        "Disconnect " + name + "?",
        Label.label(t, name + " is connected. " + what).wrapText(),
        List.of(button(t, SECONDARY, "Cancel", () -> disconnecting = null), disconnect),
        disconnect,
        () -> disconnecting = null);
  }

  private El passwordDialog(Theme t, State s, Live live) {
    var p = live.profile;
    String who = p.user().isBlank() ? p.host() : p.user() + " on " + p.host();
    El connect = button(t, PRIMARY, "Connect", () -> connectWith(s, live));
    El content = col()
        .w(t.size().field())
        .gap(t.space().m())
        .child(Label.label(t, "Enter the password of " + who + ".").wrapText())
        .child(TextField.passwordField(t, Prop.of(() -> live.typed, v -> live.typed = v), null)
            .key("password")
            .label("Password")
            .wFull())
        .child(Checkbox.checkbox(
            t, Prop.of(() -> live.keep, v -> live.keep = v), "Keep it until HooriDB closes"))
        .child(Label.label(t, Label.Variant.MUTED, "It is not saved in the system keyring.")
            .wrapText());
    return Dialog.dialog(
        t,
        "Password for " + State.name(p),
        content,
        List.of(button(t, SECONDARY, "Cancel", () -> drop(s, live.id())), connect),
        connect,
        () -> drop(s, live.id()));
  }
}

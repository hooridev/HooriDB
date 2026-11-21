package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryStore;
import dev.hoori.db.core.connection.QueryStore;
import dev.hoori.db.core.connection.SavedQuery;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.dialect.Dialect;
import dev.hoori.db.core.dialect.DocumentDialect;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.secret.SecretStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

/** The app's state, which only the handlers change, on the app's thread. */
final class State {

  static final Split.Position SIDEBAR = Split.Position.first(320); // room for a column and its type
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line
  // How long a change of the keyring may keep its dialog waiting before Cancel gives it up.
  static final double KEYRING_SECONDS = 5;

  App<State> app; // runs the clock and the windows, and says whether the window is maximized
  Theme theme = Theme.DARK;
  ZoneId zone = ZoneId.systemDefault(); // the computer's, in which moments show
  Split.Position sidebar = SIDEBAR;
  final MenuBar.State bar = new MenuBar.State();
  // The tabs of every window: the first window's, and those torn off into windows of their own.
  final TabGroups<Tab> tabs = new TabGroups<Tab>(List.of(), this::openWindow)
      .onCloseTab(this::closeTab)
      .onClosed(this::tabClosed);
  // The command palette of each window, by its tabs, and what Go to table began reading there.
  final Map<TabGroups.Group<Tab>, Palette> palettes = new HashMap<>();

  static final class Palette {
    final List<Runnable> reads = new ArrayList<>(); // stops what it began reading
    final CommandPalette.State state = new CommandPalette.State().onClose(this::stop);

    void stop() {
      reads.forEach(Runnable::run);
      reads.clear();
    }
  }

  String message; // in the status line for a while, or null
  private int messages; // told so far

  private final WorkspaceStore store;
  private final Callable<Keyring> findKeyring;
  // Finds the keyring, and makes every call of it, one after another.
  final KeyringWorker keyringWorker = new KeyringWorker();
  final Drivers drivers; // null in a script run, which downloads none
  final Tester tester;
  final CatalogTree catalog;
  private Workspace workspace = Workspace.EMPTY; // what is saved: the connections in folders
  // The workspace's connections from the top down, in the order shown; set with it in change().
  List<ConnectionProfile> profiles = List.of();
  // Its folders by id, for the tree, which asks for them at every frame; set with it too.
  private Map<UUID, Workspace.Folder> folders = Map.of();
  boolean loaded; // whether the workspace was loaded, or failed to
  String loadError; // why the workspace could not be loaded, or null
  // Where what a file that could not be understood held is kept once a save replaced it, or null.
  private String setAside;
  String saveError; // why the workspace could not be saved, or null
  private boolean unsaved; // the workspace differs from what was last written
  Keyring keyring; // null while it is looked up
  private boolean lookingForKeyring;
  // Passwords typed but not saved, for this session only.
  private final Map<UUID, char[]> passwords = new HashMap<>();
  Node selected; // the selected node of the catalog, or null
  ConnectionDialog dialog; // null while closed
  DriversDialog driverList; // the Drivers dialog, null while closed
  ConnectionProfile deleting; // the connection the delete dialog asks about, or null
  FolderDialog folderDialog; // a folder's name or the question before its delete; null while closed
  private UUID placing; // the folder the new connection's dialog puts it in, or null for the top
  boolean about; // whether the About dialog is open
  Pending pending; // the one act that waits, or null
  final Removal removal = new Removal(); // the deletion of connections, out of the keyring first
  // What finishing something on a console's session did, failures and warnings, in the order they
  // came, each until the user dismisses it: the app's from the moment it exists.
  final List<Notice> notices = new ArrayList<>();
  private int noticed; // counted, also those dismissed
  private int closing; // consoles' sessions whose closing has not said how it ended
  private int exitAt = -1; // the notices counted when Exit began to wait for the sessions, or -1
  private int exits; // counted, so that a late patience is dropped
  // Sessions that did not say in time how they closed, which the next Exit does not wait for.
  private int waived;
  private Work saving; // the save of the workspace on its way, or null
  private boolean saveAgain; // the workspace changed while it ran
  private boolean quitAnyway; // the last save at the end failed and the user was told

  // How long after the last edit of a console its text is saved.
  static final double EDIT_SECONDS = 1;

  /**
   * A file kept beside the connections, saved as they are ({@link #persist()}): one save at a time,
   * a change meanwhile saves again with what is there then. None before its load has ended, nor
   * after a load that failed with the file still there, which nothing may replace.
   */
  static final class Kept {
    final String what; // "consoles", in messages
    final Callable<Callable<?>> next; // on the app's thread: the save of what is there now
    boolean canSave; // its load ended, and for the consoles they were restored
    boolean unsaved; // differs from what was last written
    String saveError; // why the last save failed, or null
    private Work saving;
    private boolean again;
    boolean quitAnyway; // the last Exit stopped for it, and the next goes without it

    Kept(String what, Callable<Callable<?>> next) {
      this.what = what;
      this.next = next;
    }

    /** Whether Exit waits for it: a save runs, or one is due. */
    boolean waits() {
      return saving != null || unsaved && canSave && !quitAnyway;
    }

    /** What notices call it: "Consoles", "History", "Saved queries". */
    String title() {
      return what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1);
    }
  }

  /** What writes a file: a store's save. */
  interface Store<T> {
    void save(T value) throws IOException;
  }

  /** The save of the value by the store, which gets only these two, off the app's thread. */
  static <T> Callable<T> writing(T value, Store<T> store) {
    return () -> {
      store.save(value);
      return value;
    };
  }

  private final ConsoleStore consoleStore;
  private final HistoryStore historyStore;
  private final QueryStore queryStore;
  final Kept consolesKept;
  final Kept historyKept;
  final Kept queriesKept;
  // The queries saved on purpose, of every connection, in the order they were first saved.
  List<SavedQuery> queries = List.of();
  // Until the file is read nothing is saved or deleted, so that each name and id is the file's.
  boolean queriesLoading = true;

  /**
   * Whether a query can be saved or deleted now: once the file was read, and not where it could not
   * be, as nothing would be written.
   */
  boolean queriesUsable() {
    return !queriesLoading && queriesKept.canSave;
  }
  // The statements run from consoles, of every connection, the oldest first.
  List<HistoryEntry> history = List.of();
  // Connections deleted: what is loaded of their history and consoles after that goes too.
  private final Set<UUID> forgotten = new HashSet<>();
  // Connections deleted: their saved queries go, also those loaded after.
  private final Set<UUID> deletedConnections = new HashSet<>();
  // The consoles loaded and not yet restored, which waits for the profiles; null before and after.
  private List<StoredConsole> restoring;
  // The consoles kept of connections not known, saved along with the others and not shown.
  private List<StoredConsole> unseen = List.of();
  private int edits; // counted, so that a late patience is dropped

  final String version; // the version that runs, as About shows it
  final Updates updates;

  /**
   * @param folder where the workspace is kept
   * @param keyring finds the system keyring, off the app's thread
   * @param drivers where drivers are kept and come from, or null for none
   * @param tester tests a connection, off the app's thread
   * @param servers opens connections and reads their catalog, off the app's thread
   * @param updater updates HooriDB, off the app's thread; null for none
   * @param version the version that runs
   */
  State(
      Path folder,
      Callable<Keyring> keyring,
      Drivers drivers,
      Tester tester,
      Servers servers,
      Updater updater,
      String version) {
    this.store = new WorkspaceStore(folder);
    this.consoleStore = new ConsoleStore(folder);
    this.historyStore = new HistoryStore(folder);
    this.queryStore = new QueryStore(folder);
    consolesKept = new Kept("consoles", () -> {
      var list = new ArrayList<StoredConsole>();
      consoles(null).forEach(x -> list.add(x.stored()));
      list.addAll(unseen);
      return writing(list, consoleStore::save);
    });
    historyKept = new Kept("history", () -> writing(history, historyStore::save));
    queriesKept = new Kept("saved queries", () -> writing(queries, queryStore::save));
    this.updates = new Updates(folder, updater, version);
    this.version = version;
    this.findKeyring = keyring;
    this.drivers = drivers;
    this.tester = tester;
    this.catalog = new CatalogTree(servers);
  }

  /**
   * Loads the workspace, then looks up the keyring; and the consoles, the history and what updates
   * keep beside.
   */
  void start() {
    load();
    startUpdates();
    var consoles = consoleStore;
    app.background(
        "load consoles",
        consoles::load,
        list -> {
          restoring = list;
          restore();
        },
        error -> {
          loadFailed(consolesKept, error);
          // Not restored; what the user opens is saved where the old file is set aside.
          consolesKept.canSave = error instanceof WorkspaceStore.UnreadableException;
        });
    app.background(
        "load history",
        historyStore::load,
        list -> {
          var all =
              list.stream().filter(e -> !forgotten.contains(e.connection())).toList();
          for (var entry : this.history) { // run before the load
            all = HistoryStore.add(all, entry);
          }
          this.history = all;
          historyLoaded(true);
        },
        error -> {
          loadFailed(historyKept, error);
          historyLoaded(error instanceof WorkspaceStore.UnreadableException);
        });
    var saved = queryStore;
    app.background(
        "load saved queries",
        saved::load,
        list -> {
          var all = list.stream()
              .filter(q -> !deletedConnections.contains(q.connection()))
              .toList();
          // Those of a connection deleted while they loaded are deleted from the file too.
          boolean dropped = all.size() != list.size();
          this.queries = all;
          if (dropped) {
            queriesKept.unsaved = true;
          }
          queriesLoaded(true);
        },
        error -> {
          loadFailed(queriesKept, error);
          queriesLoaded(error instanceof WorkspaceStore.UnreadableException);
        });
  }

  // Apart, so that what start() calls reads as one list.
  private void startUpdates() {
    updates.start(this);
  }

  private void queriesLoaded(boolean canSave) {
    queriesLoading = false;
    queriesKept.canSave = canSave;
    dropOrphanQueries();
    if (queriesKept.unsaved) {
      save(queriesKept);
    }
  }

  /**
   * Deletes the queries of connections the workspace does not have, as those of one deleted while
   * HooriDB closed before they were read; once both files are read, and not where the workspace
   * failed to load, which then has none.
   */
  private void dropOrphanQueries() {
    if (!loaded || loadError != null || queriesLoading || !queriesKept.canSave) {
      return;
    }
    var known = new HashSet<UUID>();
    workspace.connections().forEach(p -> known.add(p.id()));
    var left = queries.stream().filter(q -> known.contains(q.connection())).toList();
    if (left.size() != queries.size()) {
      queries = left;
      persist(queriesKept);
    }
  }

  /** Keeps the query, in place of one of its connection and name. */
  void saveQuery(SavedQuery query) {
    queries = QueryStore.put(queries, query);
    persist(queriesKept);
  }

  /** Deletes the saved query. */
  void deleteQuery(UUID id) {
    queries = queries.stream().filter(q -> !q.id().equals(id)).toList();
    persist(queriesKept);
  }

  private void loadFailed(Kept kept, Exception error) {
    notice(kept.title(), "not read", message(error), true, null);
  }

  private void historyLoaded(boolean canSave) {
    historyKept.canSave = canSave;
    if (historyKept.unsaved) {
      save(historyKept);
    }
  }

  /**
   * Opens the consoles loaded in the main window, in their order, once the profiles have loaded as
   * well; those of a connection not known stay in the file unseen, as its connection may come
   * back, as from a file of connections set aside and mended. Not after the profiles failed to load:
   * a save would drop them all.
   */
  private void restore() {
    if (restoring == null || !loaded || loadError != null) {
      return;
    }
    var main = tabs.main();
    var hidden = new ArrayList<StoredConsole>();
    for (var stored : restoring) {
      if (profile(stored.connection()) != null) {
        var console = new ConsoleTab(stored.connection(), stored.database());
        console.text = stored.text();
        tabs.add(main, main.tabs().size(), console);
      } else if (forgotten.contains(stored.connection())) {
        consolesKept.unsaved = true; // its connection was deleted before they were read
      } else {
        hidden.add(stored);
      }
    }
    unseen = List.copyOf(hidden);
    restoring = null;
    consolesKept.canSave = true;
    if (consolesKept.unsaved) {
      save(consolesKept);
    }
  }

  /** A console's text or database changed: it is saved once it has not changed for a while. */
  void edited() {
    if (!quitting()) {
      consolesKept.quitAnyway = false; // a change since: the next Exit tries to save it
    }
    consolesKept.unsaved = true;
    int at = ++edits;
    app.after(EDIT_SECONDS, () -> {
      if (edits == at && !consolesKept.quitAnyway) { // the last Exit went without it
        save(consolesKept);
      }
    });
  }

  /** A statement of a console's ran: it goes into the history of its connection. */
  void ran(HistoryEntry entry) {
    var before = history;
    history = HistoryStore.add(history, entry);
    if (history != before) {
      persist(historyKept);
    }
  }

  /** A statement's outcome turned out otherwise, as a read that failed in its later rows. */
  void ranAgain(HistoryEntry was, HistoryEntry now) {
    int at = history.lastIndexOf(was);
    if (at >= 0) { // else gone, by the cap or a clear
      var list = new ArrayList<>(history);
      list.set(at, now);
      history = List.copyOf(list);
      persist(historyKept);
    }
  }

  /** Deletes the history of the connection. */
  void forget(UUID id) {
    forgotten.add(id);
    history = history.stream().filter(e -> !e.connection().equals(id)).toList();
    persist(historyKept);
  }

  /** A tab closed, by the user or with its connection: a console's text is no longer kept. */
  private void tabClosed(Tab tab) {
    tab.close();
    if (tab instanceof ConsoleTab) {
      persist(consolesKept);
    }
  }

  /**
   * Writes the file as the state is now, off the app's thread, as {@link #persist()} writes the
   * profiles; a failure goes to the notices once, until a save succeeds.
   */
  void persist(Kept kept) {
    if (!quitting()) {
      kept.quitAnyway = false;
    }
    kept.unsaved = true;
    save(kept);
  }

  /** Writes what changed before, as {@link #persist(Kept)} does; a change since waits for it. */
  private void save(Kept kept) {
    if (!kept.canSave) {
      return; // its load saves it, if it may
    }
    if (kept.saving != null) {
      kept.again = true;
      return;
    }
    Callable<?> save;
    try {
      save = kept.next.call();
    } catch (Exception e) {
      throw new IllegalStateException(e); // it only reads the state
    }
    int at = edits; // an edit after this is not in what it writes, and saves after its pause
    kept.saving = app.background(
        "save " + kept.what, save, done -> saved(kept, at), error -> saveFailed(kept, error));
  }

  /** The save of the file ended well: a change meanwhile saves again. */
  private void saved(Kept kept, int edit) {
    kept.saving = null;
    kept.saveError = null;
    if (kept.again) {
      kept.again = false;
      save(kept);
      return;
    }
    kept.unsaved = kept == consolesKept && edits != edit;
    settled();
  }

  /** The save of the file failed: an Exit stays, else the first failure is told. */
  private void saveFailed(Kept kept, Exception error) {
    kept.saving = null;
    boolean first = kept.saveError == null;
    kept.saveError = message(error);
    if (kept.again) {
      kept.again = false;
      save(kept);
    } else if (quitting()) {
      kept.quitAnyway = true;
      stay("HooriDB stayed open, as the " + kept.what + " could not be saved. Close it again"
          + " to quit without "
          + them(kept == historyKept));
    } else if (kept == updates.kept) {
      tell("The update settings could not be saved: " + kept.saveError);
    } else if (first) {
      notice(kept.title(), "not saved", kept.saveError, true, null);
    }
  }

  /** The end of a sentence about what Exit would go without: one thing, or several. */
  private static String them(boolean one) {
    return one ? "it." : "them.";
  }

  /** Loads the workspace again after a failure that left the file as it was. */
  void reload() {
    loaded = false;
    loadError = null;
    load();
  }

  private void load() {
    app.background(
        "load connections",
        store::load,
        found -> {
          change(found);
          loaded = true;
          setAside = null;
          dropOrphanQueries();
          lookForKeyring();
          restore();
        },
        error -> {
          loaded = true;
          loadError = message(error);
          setAside = error instanceof WorkspaceStore.UnreadableException unreadable
              ? WorkspaceStore.kept(unreadable)
              : null;
          lookForKeyring();
        });
  }

  // After the load, so that the list does not wait for it and a script can await the load alone.
  private void lookForKeyring() {
    if (keyring != null || lookingForKeyring) {
      return;
    }
    lookingForKeyring = true;
    var worker = keyringWorker;
    var find = findKeyring;
    app.background(
        "find the system keyring",
        () -> worker.call(find), // its first call: what is asked of it waits behind it
        found -> keyringFound(
            found instanceof Keyring.Available(var secrets)
                ? new Keyring.Available(worker.serial(secrets))
                : found),
        error -> keyringFound(new Keyring.Unavailable(Keyring.Reason.NO_SERVICE, message(error))));
  }

  private void keyringFound(Keyring found) {
    keyring = found;
    if (dialog != null) {
      dialog.lookUp(this);
      dialog.probe.keyringFound(this);
    }
    catalog.keyringFound(this);
    if (removal.running) {
      removal.next(this);
    }
  }

  /**
   * Whether the list can change: not after a load that failed for a reason other than a file it
   * could not understand, as a save would replace the file that is still there.
   */
  boolean canChange() {
    return loaded && (loadError == null || setAside != null);
  }

  /** The keyring's store, or null while there is none. */
  SecretStore secrets() {
    return keyring instanceof Keyring.Available(var secrets) ? secrets : null;
  }

  /** Opens the dialog for a new connection, which goes where {@link #folderFor} the selection. */
  void newConnection() {
    placing = folderFor(selected);
    dialog = ConnectionDialog.create(this);
  }

  /** What is saved: the connections in folders. */
  Workspace workspace() {
    return workspace;
  }

  /** The folder of the id, or null where there is none. */
  Workspace.Folder folder(UUID id) {
    return folders.get(id);
  }

  /**
   * The folder something new goes into from the node: the node itself where it is a folder, else
   * the folder its connection is in; null for the top.
   */
  UUID folderFor(Node node) {
    if (node instanceof Node.Folder(var id)) {
      return id;
    }
    var path = node == null ? null : workspace.path(node.id());
    return path == null || path.isEmpty() ? null : path.getLast().id();
  }

  private static final Workspace.Folder NEW_FOLDER =
      new Workspace.Folder(new UUID(0, 0), "", true, List.of());

  /** Whether a new folder fits where {@link #folderFor} the selection, folders nesting so deep. */
  boolean canAddFolder() {
    return canChange() && workspace.fits(folderFor(selected), NEW_FOLDER);
  }

  /** Whether the folder of the id is open in the tree; not where there is none. */
  boolean folderOpen(UUID id) {
    var folder = folders.get(id);
    return folder != null && folder.open();
  }

  /** Opens the dialog for a new folder, which goes where {@link #folderFor} the selection. */
  void newFolder() {
    folderDialog = FolderDialog.create(folderFor(selected));
  }

  /** A new folder of the name, open and last in the folder of the id or at the top; selected. */
  void createFolder(UUID in, String name) {
    folderDialog = null;
    var folder = new Workspace.Folder(UUID.randomUUID(), name, true, List.of());
    var at = folder(in) == null ? null : in; // gone meanwhile: at the top
    if (!workspace.fits(at, folder)) {
      tell("The folder was not made: folders nest at most " + Workspace.DEPTH + " deep.");
      return;
    }
    change(revealing(workspace.into(at, folder), folder.id()));
    selected = new Node.Folder(folder.id());
    persist();
  }

  void askRenameFolder(UUID id) {
    var folder = folder(id);
    if (folder != null) {
      folderDialog = FolderDialog.rename(id, folder.name());
    }
  }

  void renameFolder(UUID id, String name) {
    folderDialog = null;
    if (folder(id) != null) {
      change(workspace.rename(id, name));
      persist();
    }
  }

  /** The user opens or closes a folder in the tree, which is saved. */
  void openFolder(UUID id, boolean open) {
    var folder = folder(id);
    if (folder != null && folder.open() != open) {
      change(workspace.open(id, open));
      persist();
    }
  }

  /**
   * Whether the node can go to the place at the target, or last at the top where the target is
   * null: folders and connections alone, as {@link Workspace#canMove} allows.
   */
  boolean canMove(Node node, Node target, Workspace.Place place) {
    return entry(node)
        && (target == null || entry(target))
        && workspace.canMove(node.id(), target == null ? null : target.id(), place);
  }

  private static boolean entry(Node node) {
    return node instanceof Node.Folder || node instanceof Node.Connection;
  }

  /**
   * Moves the node, where it can go, and selects it with the folders on its way open. A connection
   * keeps what is open and read below it, its tabs and its consoles, which none of them names.
   */
  void move(Node node, Node target, Workspace.Place place) {
    if (!canMove(node, target, place)) {
      return;
    }
    var moved = workspace.move(node.id(), target == null ? null : target.id(), place);
    change(revealing(moved, node.id()));
    selected = node;
    persist();
  }

  // The workspace with the folders on the way to the entry of the id open, so that its row shows.
  private static Workspace revealing(Workspace workspace, UUID id) {
    for (var folder : workspace.path(id)) {
      if (!folder.open()) {
        workspace = workspace.open(folder.id(), true);
      }
    }
    return workspace;
  }

  /** Delete…: an empty folder goes at once, else the dialog asks what becomes of what it holds. */
  void askDeleteFolder(UUID id) {
    var folder = folder(id);
    if (folder == null) {
      return;
    }
    if (folder.entries().isEmpty()) {
      dissolveFolder(id);
    } else {
      removal.kept.clear();
      removal.error = null;
      folderDialog = FolderDialog.delete(id, folder.name());
    }
  }

  /** Deletes the folder, and what it held takes its place, one level up. */
  void dissolveFolder(UUID id) {
    if (removal.running) {
      return;
    }
    folderDialog = null;
    drop(deleteFolderAct(id)); // and a console's question it asked
    if (folder(id) == null) {
      return;
    }
    change(workspace.dissolve(id));
    if (new Node.Folder(id).equals(selected)) {
      selected = null;
    }
    persist();
  }

  void edit(ConnectionProfile profile) {
    dialog = ConnectionDialog.edit(this, profile);
  }

  /** A copy of the profile under a new id, after it; without its password. */
  void duplicate(ConnectionProfile profile) {
    var copy = new ConnectionProfile(
        UUID.randomUUID(),
        name(profile) + " (copy)",
        profile.provider(),
        profile.host(),
        profile.port(),
        profile.database(),
        profile.user(),
        profile.tls(),
        profile.color(),
        profile.driverVersion(),
        profile.readOnly(),
        profile.srv(),
        profile.authDatabase());
    change(workspace.after(profile.id(), copy));
    selected = new Node.Connection(copy.id());
    persist();
  }

  /**
   * Puts the profile in place of the one with its id, and selects it; a new one goes last into the
   * folder its dialog was opened for, which opens, else at the top. Where it reaches another
   * server or database now, the tabs of its tables close: their records name tables of the old
   * one. Its consoles stay, with their texts and databases.
   */
  void put(ConnectionProfile profile) {
    var old = profile(profile.id());
    if (old != null
        && (old.provider() != profile.provider()
            || !old.host().equals(profile.host())
            || old.port() != profile.port()
            || old.srv() != profile.srv()
            || !database(old).equals(database(profile)))) {
      close(tables(profile.id()));
    }
    if (old == null && workspace.fits(placing, profile)) { // a folder gone meanwhile: the top
      change(revealing(workspace.into(placing, profile), profile.id()));
    } else {
      change(workspace.put(profile));
    }
    selected = new Node.Connection(profile.id());
    // A color alone leaves a connection connected, and its consoles' sessions; its tabs take it.
    boolean color = old != null && old.withColor(profile.color()).equals(profile);
    if (!color || !catalog.active(profile.id())) {
      catalog.drop(this, profile.id()); // what failed before the change is out of date
    }
    if (!color) {
      // Their sessions were opened with what changed; the save asked them first, and locked them.
      consoles(profile.id()).forEach(ConsoleTab::end);
    }
    persist();
  }

  /**
   * What finishing something on a console's session did: a give-up's commit that failed, Commit's
   * or Rollback's failure and warnings, a session's closing that failed. On the connection, of the
   * statement's first words; the text was checked against the password. A failure means that
   * what was asked may not have happened.
   */
  record Notice(int number, String connection, String statement, String what, boolean failure) {}

  /** Takes a notice, posted at once by what saw it; its console's Messages show it too. */
  void notice(String connection, String statement, String what, boolean failure, ConsoleTab of) {
    notices.add(new Notice(++noticed, connection, statement, what, failure));
    if (consoles(null).contains(of)) {
      of.noticed(statement, what, failure);
    }
    tell(connection + ": " + what);
  }

  void dismiss(Notice notice) {
    notices.remove(notice);
  }

  /** Dismiss all: those up to the newest the user was shown, by their numbers. */
  void dismissUpTo(int shown) {
    notices.removeIf(n -> n.number() <= shown);
  }

  /** A console's session begins to close, off the app's thread; {@link #closed} when it has. */
  void closing() {
    closing++;
  }

  /** A console's session has closed, after what its closing had to say. */
  void closed() {
    closing--;
    waived = Math.min(waived, closing);
    settled();
  }

  /**
   * The user did something other than exit, such as run a statement or connect: what an Exit was
   * told to waive no longer holds.
   */
  void acted() {
    waived = 0;
    if (!quitting()) {
      quitAnyway = false;
    }
  }

  // How long Exit waits for the sessions to say how they closed: longer than their closing takes.
  static final double EXIT_SECONDS = (double) Lane.CLOSES.toSeconds();

  /** Whether a dialog is open, which an act that opens one must not replace. */
  boolean dialogOpen() {
    return dialog != null
        || deleting != null
        || folderDialog != null
        || about
        || driverList != null;
  }

  /** The act of deleting the connection, which its disconnect and its delete share. */
  static Act deleteAct(ConnectionProfile profile) {
    return new Act("delete", profile.id());
  }

  /** Asks before the connection is deleted. */
  void askDelete(ConnectionProfile profile) {
    deleting = profile;
    removal.error = null;
  }

  /**
   * Deletes the connection the dialog asks about, its password out of the keyring first, once the
   * keyring is known. Without a keyring it goes, and the status line says what may be left.
   */
  void delete() {
    var profile = deleting;
    if (profile == null
        || removal.running
        || asks(
            deleteAct(profile),
            name(profile) + " is deleted",
            consoles(profile.id()),
            this::delete)) {
      return;
    }
    remove(deleteAct(profile), name(profile) + " is being deleted", List.of(profile), null);
  }

  /** The act of deleting the folder with what it holds. */
  static Act deleteFolderAct(UUID folder) {
    return new Act("delete folder", folder);
  }

  /**
   * Delete contents: every connection in the folder and its folders, each as {@link #delete} does
   * it, after the questions of all their consoles; then the folder. A connection whose password
   * could not be deleted stays, and with it the folder; what went stays gone.
   */
  void deleteFolder(UUID id) {
    var folder = folder(id);
    if (folder == null) {
      drop(deleteFolderAct(id)); // gone while a console's question waited
      return;
    }
    if (removal.running) {
      return;
    }
    var held = folder.connections();
    var consoles = held.stream().flatMap(p -> consoles(p.id()).stream()).toList();
    var act = deleteFolderAct(id);
    if (asks(act, folder.name() + " is deleted", consoles, () -> deleteFolder(id))) {
      return;
    }
    remove(act, folder.name() + " is being deleted", held, id);
  }

  /**
   * Takes the connections out one after another, their consoles locked meanwhile, and then the
   * folder where it is one's.
   */
  private void remove(Act act, String what, List<ConnectionProfile> profiles, UUID folder) {
    var ids = new HashSet<UUID>();
    profiles.forEach(p -> ids.add(p.id()));
    if (!lock(act, what, ids)) {
      return;
    }
    removal.start(this, act, profiles, folder);
  }

  /** Its password is out of the keyring: the connection goes, with its tabs and its history. */
  void discard(ConnectionProfile profile) {
    forgetPassword(profile.id());
    catalog.drop(this, profile.id());
    closeTabs(profile.id()); // its consoles, and with them their texts
    forget(profile.id());
    change(workspace.remove(profile.id()));
    // Its saved queries go too; those not loaded yet go as they load.
    deletedConnections.add(profile.id());
    var before = queries;
    queries = queries.stream().filter(q -> !q.connection().equals(profile.id())).toList();
    if (queries.size() != before.size()) {
      persist(queriesKept);
    }
    if (selected != null && selected.id().equals(profile.id())) {
      selected = null;
    }
    persist();
  }

  /**
   * The deletion is over, and nothing stayed: its dialog closes. A folder goes only with nothing
   * left in it.
   *
   * @param id the folder deleted with what it held, or null for one connection
   */
  void deleted(UUID id) {
    if (id == null) {
      deleting = null;
      return;
    }
    folderDialog = null;
    var folder = folder(id);
    if (folder != null && folder.connections().isEmpty()) {
      change(workspace.remove(id));
      if (selected instanceof Node.Folder(var at) && folder(at) == null) {
        selected = null;
      }
      persist();
    }
  }

  /**
   * Closes the delete dialog, unless its deletion has begun: that one ends first, or is given up
   * once the keyring has kept it waiting.
   */
  void cancelDelete() {
    if (removal.running && removal.slow) {
      removal.giveUp(this);
      return;
    }
    if (deleting != null && !removal.running) {
      drop(deleteAct(deleting)); // and the way out it asked for
      deleting = null;
    }
  }

  /** Cancel or Escape in the question before a folder's delete, as {@link #cancelDelete}. */
  void cancelFolderDialog() {
    if (removal.running && removal.slow) {
      removal.giveUp(this);
      return;
    }
    if (folderDialog != null && !removal.running) {
      drop(deleteFolderAct(folderDialog.folder));
      folderDialog = null;
    }
  }

  /** Keeps a password for this session; the array is the state's now. */
  void rememberPassword(UUID id, char[] password) {
    forgetPassword(id);
    passwords.put(id, password);
  }

  void forgetPassword(UUID id) {
    char[] old = passwords.remove(id);
    if (old != null) {
      Arrays.fill(old, '\0');
    }
  }

  /** Whether a password for the connection is kept for this session. */
  boolean remembers(UUID id) {
    return passwords.containsKey(id);
  }

  /** A copy of the password kept for this session, for its caller to wipe; null for none. */
  char[] rememberedCopy(UUID id) {
    char[] password = passwords.get(id);
    return password == null ? null : password.clone();
  }

  /**
   * Writes the workspace as it is now, off the app's thread. One save runs at a time: a change
   * meanwhile saves again once it is done, with the workspace as it is then. It counts as unsaved
   * until a write of it has succeeded.
   */
  void persist() {
    if (!quitting()) {
      quitAnyway = false; // a change since: the next Exit tries to save it
    }
    unsaved = true;
    if (saving != null) {
      saveAgain = true;
      return;
    }
    saving = app.background(
        "save connections",
        writing(workspace, store::save),
        done -> {
          saving = null;
          saveError = null;
          if (loadError != null) { // the file is readable now
            loadError = null;
            tell("The connections that could not be read were kept in " + setAside);
            restore();
          }
          if (saveAgain) {
            saveAgain = false;
            persist();
            return;
          }
          unsaved = false;
          settled();
        },
        error -> {
          saving = null;
          saveError = message(error);
          if (saveAgain) {
            saveAgain = false;
            persist();
          } else if (quitting()) {
            pending = null;
            quitAnyway = true;
            tell("HooriDB stayed open, as the connections could not be saved. Close it again to"
                + " quit without them.");
          }
        });
  }

  /** Whether a change of the keyring runs, which no one may leave halfway. */
  private boolean keyringBusy() {
    return removal.running || dialog != null && dialog.changing;
  }

  /**
   * A request to close the window: it closes once what was begun has ended and the list is
   * written, so that no password is in the keyring for a connection that is not saved.
   */
  void requestClose() {
    if (updates.restarting()) {
      return; // the helper is being started: HooriDB closes once it is
    }
    updates.restartWith = null; // a restart that was cancelled or stayed open is over
    close();
  }

  /** Closes HooriDB as {@link #requestClose()} does, and restarts it with the update downloaded. */
  void restartToUpdate() {
    if (updates.ready == null || updates.restarting()) {
      return;
    }
    updates.restartWith = updates.ready;
    close();
  }

  private void close() {
    // The consoles first: their answers come back here, and Cancel gives up closing.
    if (asks(EXIT, "HooriDB closes", consoles(null), this::close)) {
      return;
    }
    if (dialog != null) {
      // A test or a download can always be done again: closing cancels them instead of waiting.
      dialog.probe.stop();
    }
    if (driverList != null) {
      driverList.close(this); // a download can be done again
    }
    // A keyring that keeps a change waiting is given up, as its Cancel does: the window stays, and
    // says what that left; the next close closes. A slower one is given up once it is slow.
    if (dialog != null && dialog.slow) {
      dialog.giveUp(this);
      return;
    }
    if (removal.slow) {
      removal.giveUp(this);
      return;
    }
    if (!lock(EXIT, "HooriDB is closing", null)) {
      return;
    }
    if (keyringBusy() || saving != null || unsaved && !quitAnyway) {
      tell("HooriDB closes once the connections are saved.");
    }
    settled();
  }

  /** What was on its way has ended: the window closes now if it was asked to. */
  void settled() {
    if (!quitting() || keyringBusy() || saving != null || updates.busy()) {
      return;
    }
    // The one way out: the file saved, unless the user chose to go without it ("anyway" waives
    // that alone); then every console's session ends, and HooriDB waits for each to say how, as
    // long as their closing is bounded, waiving only those it said it could not confirm. The
    // consoles and the history are saved last, as ending a session may add to them.
    if (unsaved && !quitAnyway) {
      persist();
      return;
    }
    if (exitAt < 0) {
      exitAt = noticed;
      consoles(null).forEach(ConsoleTab::end);
      int at = ++exits;
      app.after(EXIT_SECONDS, () -> {
        if (exits == at && quitting() && closing > waived) {
          int silent = closing - waived;
          stay(silent + (silent == 1 ? " session" : " sessions")
              + " did not say in time that it closed, so what it finished is not confirmed."
              + " HooriDB stayed open; close it again to quit without waiting for "
              + them(silent == 1));
          waived = closing;
        }
      });
    }
    if (closing > waived) {
      return;
    }
    if (noticed > exitAt) {
      stay("HooriDB stayed open: a session reported a problem as it closed. See the connections.");
      return;
    }
    if (!keptWaits()) { // what the sessions' ends added
      if (updates.restartWith != null) {
        updates.restartNow(this);
      } else {
        closeAll();
      }
    }
  }

  /**
   * Whether Exit waits for the consoles, the history or the saved queries to be saved; a save due
   * begins. Changes that cannot be saved, as the file was not read, stop it once.
   */
  private boolean keptWaits() {
    for (var each : List.of(consolesKept, historyKept, queriesKept, updates.kept)) {
      if (each.waits()) {
        if (each.saving == null) {
          save(each);
        }
        return true;
      }
      if (each.unsaved && !each.canSave && !each.quitAnyway) {
        unreadStops(each);
        return true;
      }
    }
    return false;
  }

  /** Changes to a file that was not read stop Exit once, as they cannot be saved. */
  private void unreadStops(Kept kept) {
    kept.quitAnyway = true;
    boolean one = kept == historyKept;
    stay("HooriDB stayed open: the " + kept.what + (one ? " was" : " were") + " not read, so "
        + (one ? "it" : "they") + " cannot be saved. Close it again to quit without "
        + them(one));
  }

  /** Exit stops, and says why; the next one asks again. */
  void stay(String why) {
    pending = null;
    exitAt = -1;
    exits++;
    tell(why);
  }

  /** The keyring refused a change: the window stays open, so that the user sees why. */
  void keyringFailed() {
    if (quitting()) {
      pending = null;
      tell("HooriDB stayed open, as the system keyring reported a problem.");
    }
  }

  /**
   * Opens the table's rows in a tab, or goes to the tab of its connection and qualified name, in
   * whichever window; a table that became a view of its name, or the reverse, is read anew there.
   */
  void openTable(UUID id, Table table) {
    openTable(id, table, tabs.main());
  }

  /**
   * As {@link #openTable(UUID, Table)}, a new tab in the window of these tabs; returns the tabs
   * that hold it.
   */
  TabGroups.Group<Tab> openTable(UUID id, Table table, TabGroups.Group<Tab> into) {
    var open = tables(id).stream()
        .filter(x ->
            x.table.container().equals(table.container()) && x.table.name().equals(table.name()))
        .findFirst();
    if (open.isEmpty()) {
      var tab = new TableTab(id, table);
      tabs.add(into, into.tabs().size(), tab);
      tab.open(this, false);
      return into;
    }
    var tab = open.get();
    var group = tabs.groupOf(tab);
    tabs.select(group, group.tabs().indexOf(tab));
    var window = group.window() != null ? group.window() : app.windows().of(app);
    if (window != null) {
      window.raise();
    }
    if (!tab.table.equals(table)) {
      tab.table = table;
      tab.open(this, true);
    }
    return group;
  }

  /** The table tabs of every window, of the connection or of all where it is null. */
  List<TableTab> tables(UUID id) {
    var all = new ArrayList<TableTab>();
    for (var group : tabs.groups()) {
      for (var tab : group.tabs()) {
        if (tab instanceof TableTab x && (id == null || x.id.equals(id))) {
          all.add(x);
        }
      }
    }
    return all;
  }

  /**
   * Opens a console on the connection: on the database of the node selected in the tree under it,
   * else on the one its profile names, else on the one it finds by itself.
   */
  void openConsole(UUID id) {
    openConsole(id, tabs.main());
  }

  /** As {@link #openConsole(UUID)}, in the window of these tabs. */
  void openConsole(UUID id, TabGroups.Group<Tab> group) {
    var profile = profile(id);
    if (profile == null) {
      return;
    }
    var at = group == tabs.main() && selected != null && selected.id().equals(id)
        ? CatalogTree.database(selected)
        : null;
    var console = new ConsoleTab(id, at == null ? database(profile) : at);
    console.take(this);
    tabs.add(group, group.tabs().size(), console);
    persist(consolesKept);
  }

  /**
   * The connection a new console in the window is for: in the main window the one selected in the
   * tree, or that of the node selected, else that of the window's active tab; null for none.
   */
  UUID consoleFor(TabGroups.Group<Tab> group) {
    if (group == tabs.main() && selected != null && !(selected instanceof Node.Folder)) {
      return selected.id();
    }
    return switch (group.activeTab()) {
      case null -> null;
      case TableTab x -> x.id;
      case ConsoleTab x -> x.id;
    };
  }

  /** The command palette of the window of these tabs. */
  CommandPalette.State palette(TabGroups.Group<Tab> group) {
    return palettes.computeIfAbsent(group, g -> new Palette()).state;
  }

  /** Ends with the palette of the window: what it began reading, stopped by this. */
  void readForPalette(TabGroups.Group<Tab> group, Runnable stop) {
    palettes.computeIfAbsent(group, g -> new Palette()).reads.add(stop);
  }

  /** Forgets what the window of these tabs kept, which has closed, and stops what it read. */
  private void closed(TabGroups.Group<Tab> group) {
    var palette = palettes.remove(group);
    if (palette != null) {
      palette.stop();
    }
    catalog.closed(group);
  }

  /** Closes the active tab of the window, as its cross does: a console asks first. */
  void closeTab(TabGroups.Group<Tab> group) {
    if (group.activeTab() != null) {
      closeTab(group, group.active());
    }
  }

  /** Goes to the next tab of the window, or with -1 to the one before, around at the ends. */
  void nextTab(TabGroups.Group<Tab> group, int step) {
    int n = group.tabs().size();
    if (n > 1) {
      tabs.select(group, Math.floorMod(group.active() + step, n));
    }
  }

  /**
   * The one act in the app that waits, with what owns it and what it is: a {@link Question} while a
   * console asks, or {@link Locked} once it is armed.
   */
  sealed interface Pending {
    Object owner();

    String what();

    /** The act waits on the question of a console, and goes on by {@code then} once it is settled. */
    record Question(Object owner, String what, ConsoleTab console, Runnable then)
        implements Pending {}

    /**
     * The act is armed and locks the consoles of a scope until it is done: those of these
     * connections, or all of them for null.
     */
    record Locked(Object owner, String what, Set<UUID> scope) implements Pending {}
  }

  /**
   * An act that may wait, as the owner of {@link Pending}: what it does, and to what. Two are the
   * same act only where both are the same; another act, on the same connection or console, is
   * refused while one waits.
   */
  record Act(String kind, Object target) {}

  private static final Object EXIT = new Object();

  /**
   * The one guard of every way out that would end consoles' sessions or their transactions: the
   * first of the consoles where something is at stake ({@link ConsoleTab#atStake}) asks in its
   * window what becomes of it, and {@code then} runs again once that is settled. True while one
   * asks, and the caller goes no further. While another act waits, one that would ask is refused,
   * visibly, instead of taking its place.
   */
  boolean asks(Object owner, String what, List<ConsoleTab> consoles, Runnable then) {
    var asking = consoles.stream().filter(ConsoleTab::atStake).findFirst().orElse(null);
    boolean mine = pending != null && Objects.equals(pending.owner(), owner);
    if (asking == null) {
      if (mine && pending instanceof Pending.Question) {
        pending = null;
      }
      return false;
    }
    if (pending != null && !mine) {
      refuse();
      return true;
    }
    pending = new Pending.Question(owner, what, asking, then);
    asking.show(this);
    return true;
  }

  /**
   * Arms the act once nothing of its consoles is at stake: from now until it is done or given up
   * ({@link #drop}), those consoles start nothing, so that nothing comes between the answers and the
   * act. False, refused visibly, while another act waits.
   *
   * @param scope the connections whose consoles it locks, or null for all
   */
  boolean lock(Object owner, String what, Set<UUID> scope) {
    if (pending != null && !Objects.equals(pending.owner(), owner)) {
      refuse();
      return false;
    }
    pending = new Pending.Locked(owner, what, scope);
    return true;
  }

  private void refuse() {
    switch (pending) {
      case Pending.Locked locked -> tell(locked.what() + ": that comes first.");
      case Pending.Question question -> {
        tell("A question about a console's transaction waits for its answer.");
        question.console().show(this);
      }
    }
  }

  /** The act is done, or given up: with what owns it, or with the console it waited on. */
  void drop(Object owner) {
    if (pending != null
        && (Objects.equals(pending.owner(), owner)
            || pending instanceof Pending.Question question && question.console() == owner)) {
      pending = null;
    }
  }

  /** Whether the act waits: for a console's answer, or until it is done. */
  boolean pends(Object owner) {
    return pending != null && Objects.equals(pending.owner(), owner);
  }

  /** Why the console may start nothing now, or null. */
  String locked(ConsoleTab console) {
    return pending instanceof Pending.Locked locked
            && (locked.scope() == null || locked.scope().contains(console.id))
        ? locked.what()
        : null;
  }

  boolean quitting() {
    return pending instanceof Pending.Locked locked && locked.owner() == EXIT;
  }

  /** Whether Exit, or Restart now, asks or is under way: nothing comes between. */
  boolean exiting() {
    return pending != null && pending.owner() == EXIT;
  }

  /** The user closes a tab, by its cross or Control+W: a console asks first. */
  private void closeTab(TabGroups.Group<Tab> group, int index) {
    if (!(group.tabs().get(index) instanceof ConsoleTab x
        && asks(new Act("close", x), "the console closes", List.of(x), () -> closeTab(x)))) {
      tabs.close(group, index);
    }
  }

  private void closeTab(Tab tab) {
    var group = tabs.groupOf(tab);
    if (group != null) {
      closeTab(group, group.tabs().indexOf(tab));
    }
  }

  /** The consoles of every window, of the connection or of all where it is null. */
  List<ConsoleTab> consoles(UUID id) {
    var all = new ArrayList<ConsoleTab>();
    for (var group : tabs.groups()) {
      for (var tab : group.tabs()) {
        if (tab instanceof ConsoleTab x && (id == null || x.id.equals(id))) {
          all.add(x);
        }
      }
    }
    return all;
  }

  // The database the server opens where none is given.
  static String database(ConnectionProfile p) {
    // A server of documents takes any name it is given, also one of spaces alone.
    if (p.provider().keepsDocuments() ? !p.database().isEmpty() : !p.database().isBlank()) {
      return p.database();
    }
    return switch (p.provider().traits()) {
      case Dialect d -> d.connecting().defaultDatabaseIsUser() ? p.user() : p.database();
      case DocumentDialect d -> d.defaultDatabase();
    };
  }

  /** Closes the tabs of the connection, in every window. */
  private void closeTabs(UUID id) {
    var all = new ArrayList<Tab>(tables(id));
    all.addAll(consoles(id));
    close(all);
  }

  /** Closes these tabs, in whichever window each is. */
  private void close(List<? extends Tab> all) {
    for (var tab : all) {
      var group = tabs.groupOf(tab);
      tabs.close(group, group.tabs().indexOf(tab));
    }
  }

  /** The connection ended: what its tabs asked of it ends too, and what they read stays. */
  void ended(UUID id) {
    tables(id).forEach(TableTab::stop);
  }

  /** The connection could not be connected: its consoles that waited for it say so. */
  void unconnected(UUID id) {
    consoles(id).forEach(ConsoleTab::unconnected);
  }

  /** The connection connected anew: its tabs read again what they showed, its consoles run. */
  void connected(UUID id) {
    for (var tab : tables(id)) {
      tab.open(this, true);
    }
    for (var console : consoles(id)) {
      console.connected(this);
    }
  }

  /**
   * A tab's title: the table's name, with as much of where it lives as tells it apart from the
   * other open tables of the same name.
   */
  String title(TableTab tab) {
    var others = tables(null).stream()
        .filter(x -> x != tab && x.table.name().equals(tab.table.name()))
        .toList();
    if (others.isEmpty()) {
      return tab.table.name();
    }
    if (others.stream().noneMatch(x -> x.path().equals(tab.path()))) {
      return tab.path();
    }
    var profile = profile(tab.id);
    return (profile == null ? "" : name(profile) + ": ") + tab.path();
  }

  /**
   * A console's title: its connection and its database, numbered where another console has the
   * same.
   */
  String title(ConsoleTab tab) {
    var profile = profile(tab.id);
    var title = (profile == null ? "" : name(profile))
        + (tab.database.isEmpty() ? "" : ": " + tab.database);
    var same = consoles(null).stream()
        .filter(x -> x.id.equals(tab.id) && x.database.equals(tab.database))
        .toList();
    return same.size() < 2 ? title : title + " (" + (same.indexOf(tab) + 1) + ")";
  }

  /**
   * Opens the window of a tab torn off or moved to a new window: its title bar, its tabs and their
   * status lines, over the same state.
   */
  Windows.Handle openWindow(TabGroups.Group<Tab> group, Window.Options options) {
    App<State> window =
        new App<>(this, s -> Frame.window(s, group)).provide(Theme.CURRENT, s -> s.theme);
    window.textRendering(app.textRendering());
    var handle = app.windows()
        .open(
            Frame.title(this, group.activeTab()),
            900,
            600,
            options.frameless().minimumSize(480, 320).icon(HooriDb.ICON),
            window);
    // Not at exit, the last window's close, which ends everything anyway.
    handle.onClosed(() -> closed(group));
    return handle;
  }

  /**
   * Who uses the version of the driver, by name: every connection from the moment its attempt to
   * connect decided on the version until it ended, and a load of it under way. Each kind of
   * connection that holds a driver answers here: the tree's, and each console's own from when it
   * is opened, which may outlive the tree's, until it is closed.
   */
  List<String> users(Driver driver, String version) {
    var named = new LinkedHashSet<>(catalog.users(this, driver, version));
    for (var console : consoles(null)) {
      var profile = profile(console.id);
      if (console.driver != null
          && profile != null
          && Driver.of(profile.provider()).equals(driver)
          && version.equals(drivers.version(console.driver))) {
        named.add(name(profile));
      }
    }
    var users = new ArrayList<>(named);
    if (users.isEmpty() && drivers.loading(driver, version)) {
      users.add("a connection being opened");
    }
    return users;
  }

  /**
   * Claims the version for a delete, which no one may use or begin to use until {@code
   * drivers.deleting(driver, version, false)}; false while someone uses it.
   */
  boolean claimForDelete(Driver driver, String version) {
    if (!users(driver, version).isEmpty()) {
      return false;
    }
    drivers.deleting(driver, version, true);
    return true;
  }

  /** The color of the connection, null for none or for a connection that is gone. */
  Color color(UUID id) {
    var profile = profile(id);
    return profile == null ? null : profile.color();
  }

  // The one place that sets the workspace, and with it the connections shown.
  private void change(Workspace changed) {
    workspace = changed;
    profiles = changed.connections();
    var byId = new HashMap<UUID, Workspace.Folder>();
    index(changed.entries(), byId);
    folders = byId;
  }

  private static void index(List<Workspace.Entry> entries, Map<UUID, Workspace.Folder> byId) {
    for (var entry : entries) {
      if (entry instanceof Workspace.Folder f) {
        byId.put(f.id(), f);
        index(f.entries(), byId);
      }
    }
  }

  ConnectionProfile profile(UUID id) {
    int at = indexOf(id);
    return at < 0 ? null : profiles.get(at);
  }

  private int indexOf(UUID id) {
    for (int i = 0; i < profiles.size(); i++) {
      if (profiles.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  /** What a connection is called in the list: its name, or its host while it has none. */
  static String name(ConnectionProfile profile) {
    return profile.name().isBlank() ? profile.host() : profile.name();
  }

  static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** Switches to a theme and says so in the status line. */
  void useTheme(Theme theme, String name) {
    this.theme = theme;
    tell(name + " on");
  }

  /** Shows a message in the status line for {@link #MESSAGE_SECONDS}, on the app's clock. */
  void tell(String text) {
    message = text;
    int told = ++messages;
    app.after(MESSAGE_SECONDS, () -> {
      if (messages == told) { // not replaced meanwhile
        message = null;
      }
    });
  }

  /** File, Exit: as the window's close button. */
  void quit() {
    requestClose();
  }

  void closeAll() {
    if (dialog != null) {
      dialog.close(this); // and what it reads, its driver's dialog's too
    }
    consoles(null).forEach(ConsoleTab::close);
    catalog.closeAll(); // nothing waits for a server
    for (Windows.Handle window : app.windows().all()) {
      window.close();
    }
  }
}

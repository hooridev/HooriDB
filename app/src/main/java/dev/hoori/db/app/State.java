package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.secret.SecretStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** The app's state, which only the handlers change, on the app's thread. */
final class State {

  static final Split.Position SIDEBAR = Split.Position.first(320); // room for a column and its type
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line

  App<State> app; // runs the clock and the windows, and says whether the window is maximized
  Theme theme = Theme.DARK;
  Split.Position sidebar = SIDEBAR;
  final MenuBar.State bar = new MenuBar.State();
  // The tabs of every window: the first window's, and those torn off into windows of their own.
  final TabGroups<Tab> tabs = new TabGroups<Tab>(List.of(), this::openWindow).onClosed(Tab::close);
  String message; // in the status line for a while, or null
  private int messages; // told so far

  private final ProfileStore store;
  private final Callable<Keyring> findKeyring;
  final Drivers drivers; // null in a script run, which downloads none
  final Tester tester;
  final Catalogue catalogue;
  List<ConnectionProfile> profiles = List.of(); // in the order shown and saved
  boolean loaded; // whether the profiles were loaded, or failed to
  String loadError; // why the profiles could not be loaded, or null
  private boolean unreadable; // whether the file was there but could not be understood
  String saveError; // why the profiles could not be saved, or null
  private boolean unsaved; // the list differs from what was last written
  Keyring keyring; // null while it is looked up
  private boolean lookingForKeyring;
  // Passwords typed but not saved, for this session only.
  private final Map<UUID, char[]> passwords = new HashMap<>();
  Node selected; // the selected node of the catalogue, or null
  ConnectionDialog dialog; // null while closed
  ConnectionProfile deleting; // the connection the delete dialog asks about, or null
  // Its deletion runs: it waits for the keyring to be found, then for its password to go.
  boolean removing;
  String removeError; // why its password could not be removed, or null
  private Work saving; // the save of the profiles on its way, or null
  private boolean saveAgain; // the profiles changed while it ran
  private boolean quitting; // the window closes once nothing is on its way and all is saved
  private boolean quitAnyway; // the last save at the end failed and the user was told

  /**
   * @param folder where the profiles are kept
   * @param keyring finds the system keyring, off the app's thread
   * @param drivers where drivers are kept and come from, or null for none
   * @param tester tests a connection, off the app's thread
   * @param servers opens connections and reads their catalogue, off the app's thread
   */
  State(Path folder, Callable<Keyring> keyring, Drivers drivers, Tester tester, Servers servers) {
    this.store = new ProfileStore(folder);
    this.findKeyring = keyring;
    this.drivers = drivers;
    this.tester = tester;
    this.catalogue = new Catalogue(servers);
  }

  /** Loads the profiles, then looks up the keyring. */
  void start() {
    load();
  }

  /** Loads the profiles again after a failure that left the file as it was. */
  void reload() {
    loaded = false;
    loadError = null;
    load();
  }

  private void load() {
    app.background(
        "load connections",
        store::load,
        list -> {
          profiles = list;
          loaded = true;
          unreadable = false;
          lookForKeyring();
        },
        error -> {
          loaded = true;
          loadError = message(error);
          unreadable = error instanceof ProfileStore.UnreadableException;
          lookForKeyring();
        });
  }

  // After the load, so that the list does not wait for it and a script can await the load alone.
  private void lookForKeyring() {
    if (keyring != null || lookingForKeyring) {
      return;
    }
    lookingForKeyring = true;
    app.background(
        "find the system keyring",
        findKeyring,
        this::keyringFound,
        error -> keyringFound(new Keyring.Unavailable(Keyring.Reason.NO_SERVICE, message(error))));
  }

  private void keyringFound(Keyring found) {
    keyring = found;
    if (dialog != null) {
      dialog.lookUp(this);
      dialog.probe.keyringFound(this);
    }
    catalogue.keyringFound(this);
    if (removing) {
      delete();
    }
  }

  /**
   * Whether the list can change: not after a load that failed for a reason other than a file it
   * could not understand, as a save would replace the file that is still there.
   */
  boolean canChange() {
    return loaded && (loadError == null || unreadable);
  }

  /** The keyring's store, or null while there is none. */
  SecretStore secrets() {
    return keyring instanceof Keyring.Available(var secrets) ? secrets : null;
  }

  /** Opens the dialog for a new connection. */
  void newConnection() {
    dialog = ConnectionDialog.create(this);
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
        profile.colour(),
        profile.driverVersion());
    var list = new ArrayList<>(profiles);
    list.add(list.indexOf(profile) + 1, copy);
    profiles = List.copyOf(list);
    selected = new Node.Connection(copy.id());
    persist();
  }

  /**
   * Puts the profile in place of the one with its id, or at the end, and selects it. Where it
   * reaches another server or database now, the tabs of its tables close: their records name
   * tables of the old one.
   */
  void put(ConnectionProfile profile) {
    var old = profile(profile.id());
    if (old != null
        && (old.provider() != profile.provider()
            || !old.host().equals(profile.host())
            || old.port() != profile.port()
            || !database(old).equals(database(profile)))) {
      closeTabs(profile.id());
    }
    var list = new ArrayList<>(profiles);
    int at = indexOf(profile.id());
    if (at < 0) {
      list.add(profile);
    } else {
      list.set(at, profile);
    }
    profiles = List.copyOf(list);
    selected = new Node.Connection(profile.id());
    // A colour alone leaves a connection connected; its tabs take it.
    boolean colour = old != null && old.withColour(profile.colour()).equals(profile);
    if (!colour || !catalogue.active(profile.id())) {
      catalogue.drop(this, profile.id()); // what failed before the change is out of date
    }
    persist();
  }

  /** Asks before the connection is deleted. */
  void askDelete(ConnectionProfile profile) {
    deleting = profile;
    removeError = null;
  }

  /**
   * Deletes the connection the dialog asks about, its password out of the keyring first, once the
   * keyring is known. Without a keyring it goes, and the status line says what may be left.
   */
  void delete() {
    var profile = deleting;
    removing = true;
    removeError = null;
    if (keyring == null) {
      return; // keyringFound goes on
    }
    var secrets = secrets();
    if (secrets == null) {
      deleted(profile);
      tell("Without the system keyring, a password saved for " + name(profile)
          + " could not be removed.");
      return;
    }
    var id = profile.id();
    app.background(
        "delete a password",
        () -> {
          secrets.delete(id);
          return id;
        },
        done -> deleted(profile),
        error -> {
          removing = false;
          removeError = message(error);
          keyringFailed();
        });
  }

  private void deleted(ConnectionProfile profile) {
    removing = false;
    deleting = null;
    forgetPassword(profile.id());
    catalogue.drop(this, profile.id());
    closeTabs(profile.id());
    profiles = profiles.stream().filter(p -> !p.id().equals(profile.id())).toList();
    if (selected != null && selected.id().equals(profile.id())) {
      selected = null;
    }
    persist();
  }

  /** Closes the delete dialog, unless its deletion has begun: that one ends first. */
  void cancelDelete() {
    if (!removing) {
      deleting = null;
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
    char[] kept = passwords.get(id);
    return kept == null ? null : kept.clone();
  }

  /**
   * Writes the profiles as they are now, off the app's thread. One save runs at a time: a change
   * meanwhile saves again once it is done, with the list as it is then. The list counts as unsaved
   * until a write of it has succeeded.
   */
  void persist() {
    unsaved = true;
    if (saving != null) {
      saveAgain = true;
      return;
    }
    var list = profiles;
    var store = this.store;
    saving = app.background(
        "save connections",
        () -> {
          store.save(list);
          return list;
        },
        done -> {
          saving = null;
          saveError = null;
          if (loadError != null) { // the file is readable now
            loadError = null;
            tell("The connections that could not be read were kept in " + ProfileStore.UNREADABLE);
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
          } else if (quitting) {
            quitting = false;
            quitAnyway = true;
            tell("HooriDB stayed open, as the connections could not be saved. Close it again to"
                + " quit without them.");
          }
        });
  }

  /** Whether a change of the keyring runs, which no one may leave halfway. */
  private boolean keyringBusy() {
    return removing || dialog != null && dialog.changing;
  }

  /**
   * A request to close the window: it closes once what was begun has ended and the list is
   * written, so that no password is in the keyring for a connection that is not saved.
   */
  void requestClose() {
    if (dialog != null) {
      // A test or a download can always be done again: closing cancels them instead of waiting.
      dialog.probe.stop();
    }
    if (quitAnyway && !keyringBusy() && saving == null) {
      closeAll();
      return;
    }
    quitting = true;
    if (keyringBusy() || saving != null || unsaved) {
      tell("HooriDB closes once the connections are saved.");
    }
    settled();
  }

  /** What was on its way has ended: the window closes now if it was asked to. */
  void settled() {
    if (!quitting || keyringBusy() || saving != null) {
      return;
    }
    if (unsaved) {
      persist();
      return;
    }
    closeAll();
  }

  /** The keyring refused a change: the window stays open, so that the user sees why. */
  void keyringFailed() {
    if (quitting) {
      quitting = false;
      tell("HooriDB stayed open, as the system keyring reported a problem.");
    }
  }

  /**
   * Opens the table's rows in a tab, or goes to the tab of its connection and qualified name, in
   * whichever window; a table that became a view of its name, or the reverse, is read anew there.
   */
  void openTable(UUID id, Table table) {
    for (var group : tabs.groups()) {
      for (int i = 0; i < group.tabs().size(); i++) {
        if (group.tabs().get(i) instanceof TableTab x
            && x.id.equals(id)
            && x.table.container().equals(table.container())
            && x.table.name().equals(table.name())) {
          tabs.select(group, i);
          if (group.window() != null) {
            group.window().raise();
          }
          if (!x.table.equals(table)) {
            x.table = table;
            x.open(this, true);
          }
          return;
        }
      }
    }
    var tab = new TableTab(id, table);
    tabs.add(tabs.main(), tabs.main().tabs().size(), tab);
    tab.open(this, false);
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

  // PostgreSQL opens the database named after the user where none is given.
  private static String database(ConnectionProfile p) {
    return p.database().isBlank() && p.provider() == Provider.POSTGRESQL ? p.user() : p.database();
  }

  /** Closes the tabs of the connection, in every window. */
  private void closeTabs(UUID id) {
    for (var tab : tables(id)) {
      var group = tabs.groupOf(tab);
      tabs.close(group, group.tabs().indexOf(tab));
    }
  }

  /** The connection ended: what its tabs asked of it ends too, and what they read stays. */
  void ended(UUID id) {
    tables(id).forEach(TableTab::stop);
  }

  /** The connection connected anew: its tabs read again what they showed. */
  void connected(UUID id) {
    for (var tab : tables(id)) {
      tab.open(this, true);
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
   * Opens the window of a tab torn off or moved to a new window: its title bar, its tabs and their
   * status lines, over the same state.
   */
  Windows.Handle openWindow(TabGroups.Group<Tab> group, Window.Options options) {
    App<State> window = new App<>(this, s -> Frame.window(s, group));
    window.textRendering(app.textRendering());
    return app.windows()
        .open(
            Frame.title(this, group.activeTab()),
            900,
            600,
            options.frameless().minimumSize(480, 320).icon(HooriDb.ICON),
            window);
  }

  /** The colour of the connection, null for none or for a connection that is gone. */
  Colour colour(UUID id) {
    var profile = profile(id);
    return profile == null ? null : profile.colour();
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
  void theme(Theme theme, String name) {
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

  private void closeAll() {
    catalogue.closeAll(); // nothing waits for a server
    for (Windows.Handle window : app.windows().all()) {
      window.close();
    }
  }
}

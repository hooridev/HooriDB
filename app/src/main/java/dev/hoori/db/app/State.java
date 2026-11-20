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
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.secret.SecretStore;
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

  private final ProfileStore store;
  private final Callable<Keyring> findKeyring;
  // Finds the keyring, and makes every call of it, one after another.
  final KeyringWorker keyringWorker = new KeyringWorker();
  final Drivers drivers; // null in a script run, which downloads none
  final Tester tester;
  final CatalogTree catalog;
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
  Node selected; // the selected node of the catalog, or null
  ConnectionDialog dialog; // null while closed
  DriversDialog driverList; // the Drivers dialog, null while closed
  ConnectionProfile deleting; // the connection the delete dialog asks about, or null
  boolean about; // whether the About dialog is open
  Pending pending; // the one act that waits, or null
  // Its deletion runs: it waits for the keyring to be found, then for its password to go.
  boolean removing;
  boolean removingSlow; // the keyring has not answered for a while: Cancel gives up waiting
  private Work removal; // the removal of the password, while it runs
  private int removals; // counted, so that a late patience is dropped
  String removeError; // why its password could not be removed, or null
  // What finishing something on a console's session did, failures and warnings, in the order they
  // came, each until the user dismisses it: the app's from the moment it exists.
  final List<Notice> notices = new ArrayList<>();
  private int noticed; // counted, also those dismissed
  private int closing; // consoles' sessions whose closing has not said how it ended
  private int exitAt = -1; // the notices counted when Exit began to wait for the sessions, or -1
  private int exits; // counted, so that a late patience is dropped
  // Sessions that did not say in time how they closed, which the next Exit does not wait for.
  private int waived;
  private Work saving; // the save of the profiles on its way, or null
  private boolean saveAgain; // the profiles changed while it ran
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
  }

  private final ConsoleStore consoleStore;
  private final HistoryStore historyStore;
  final Kept consolesKept;
  final Kept historyKept;
  // The statements run from consoles, of every connection, the oldest first.
  List<HistoryEntry> history = List.of();
  // Connections deleted: what is loaded of their history and consoles after that goes too.
  private final Set<UUID> forgotten = new HashSet<>();
  // The consoles loaded and not yet restored, which waits for the profiles; null before and after.
  private List<StoredConsole> restoring;
  // The consoles kept of connections not known, saved along with the others and not shown.
  private List<StoredConsole> unseen = List.of();
  private int edits; // counted, so that a late patience is dropped

  /**
   * @param folder where the profiles are kept
   * @param keyring finds the system keyring, off the app's thread
   * @param drivers where drivers are kept and come from, or null for none
   * @param tester tests a connection, off the app's thread
   * @param servers opens connections and reads their catalog, off the app's thread
   */
  State(Path folder, Callable<Keyring> keyring, Drivers drivers, Tester tester, Servers servers) {
    this.store = new ProfileStore(folder);
    this.consoleStore = new ConsoleStore(folder);
    this.historyStore = new HistoryStore(folder);
    consolesKept = new Kept("consoles", () -> {
      var list = new ArrayList<StoredConsole>();
      consoles(null).forEach(x -> list.add(x.stored()));
      list.addAll(unseen);
      var store = consoleStore;
      return () -> {
        store.save(list);
        return list;
      };
    });
    historyKept = new Kept("history", () -> {
      var list = this.history;
      var store = historyStore;
      return () -> {
        store.save(list);
        return list;
      };
    });
    this.findKeyring = keyring;
    this.drivers = drivers;
    this.tester = tester;
    this.catalog = new CatalogTree(servers);
  }

  /** Loads the profiles, then looks up the keyring; and the consoles and the history beside. */
  void start() {
    load();
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
          consolesKept.canSave = error instanceof ProfileStore.UnreadableException;
        });
    var history = historyStore;
    app.background(
        "load history",
        history::load,
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
          historyLoaded(error instanceof ProfileStore.UnreadableException);
        });
  }

  private void loadFailed(Kept kept, Exception error) {
    notice(
        kept.what.substring(0, 1).toUpperCase(Locale.ROOT) + kept.what.substring(1),
        "not read",
        message(error),
        true,
        null);
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
        "save " + kept.what,
        save,
        done -> {
          kept.saving = null;
          kept.saveError = null;
          if (kept.again) {
            kept.again = false;
            save(kept);
            return;
          }
          kept.unsaved = kept == consolesKept && edits != at;
          settled();
        },
        error -> {
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
                + (kept == historyKept ? "it." : "them."));
          } else if (first) {
            notice(
                kept == historyKept ? "History" : "Consoles",
                "not saved",
                kept.saveError,
                true,
                null);
          }
        });
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
          restore();
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
        profile.color(),
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
   * tables of the old one. Its consoles stay, with their texts and databases.
   */
  void put(ConnectionProfile profile) {
    var old = profile(profile.id());
    if (old != null
        && (old.provider() != profile.provider()
            || !old.host().equals(profile.host())
            || old.port() != profile.port()
            || !database(old).equals(database(profile)))) {
      close(tables(profile.id()));
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
  static final double EXIT_SECONDS = Lane.CLOSES.toSeconds();

  /** Whether a dialog is open, which an act that opens one must not replace. */
  boolean dialogOpen() {
    return dialog != null || deleting != null || about || driverList != null;
  }

  /** The act of deleting the connection, which its disconnect and its delete share. */
  static Act deleteAct(ConnectionProfile profile) {
    return new Act("delete", profile.id());
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
    if (profile == null
        || asks(
            deleteAct(profile), name(profile) + " is deleted", consoles(profile.id()), this::delete)
        || !lock(deleteAct(profile), name(profile) + " is being deleted", profile.id())) {
      return;
    }
    removing = true;
    removeError = null;
    // Patience from the start, also while the keyring is still being found.
    int at = ++removals;
    app.after(KEYRING_SECONDS, () -> {
      if (removing && removals == at) {
        removingSlow = true;
        if (quitting()) {
          giveUpDelete(); // closing never waits for a keyring that does not answer
        }
      }
    });
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
    removal = app.background(
        "delete a password",
        () -> {
          secrets.delete(id);
          return id;
        },
        done -> deleted(profile),
        error -> {
          removal = null;
          removingSlow = false;
          removing = false;
          removeError = message(error);
          drop(deleteAct(profile));
          keyringFailed();
        });
  }

  private void deleted(ConnectionProfile profile) {
    removal = null;
    removingSlow = false;
    removing = false;
    drop(deleteAct(profile)); // its consoles were locked meanwhile, and close now
    deleting = null;
    forgetPassword(profile.id());
    catalog.drop(this, profile.id());
    closeTabs(profile.id()); // its consoles, and with them their texts
    forget(profile.id());
    profiles = profiles.stream().filter(p -> !p.id().equals(profile.id())).toList();
    if (selected != null && selected.id().equals(profile.id())) {
      selected = null;
    }
    persist();
  }

  static final String DELETE_GAVE_UP =
      "The system keyring did not answer, and the deletion was given up: whether the password is"
          + " still in it is not known. The connection was not deleted. What is asked of the"
          + " keyring next waits until it has answered this.";

  /**
   * Cancel once the keyring has not answered for a while: the removal of the password is given
   * up, whatever it did, and so is the deletion; the dialog stays and says what that leaves.
   */
  private void giveUpDelete() {
    if (removal != null) {
      removal.cancel(); // stops waiting; the removal itself runs on, before any later call
      removal = null;
    }
    removals++;
    removing = false;
    removingSlow = false;
    removeError = DELETE_GAVE_UP;
    drop(deleteAct(deleting));
    keyringFailed();
  }

  /**
   * Closes the delete dialog, unless its deletion has begun: that one ends first, or is given up
   * once the keyring has kept it waiting.
   */
  void cancelDelete() {
    if (removing && removingSlow) {
      giveUpDelete();
      return;
    }
    if (deleting != null && !removing) {
      drop(deleteAct(deleting)); // and the way out it asked for
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
    if (!quitting()) {
      quitAnyway = false; // a change since: the next Exit tries to save it
    }
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
    return removing || dialog != null && dialog.changing;
  }

  /**
   * A request to close the window: it closes once what was begun has ended and the list is
   * written, so that no password is in the keyring for a connection that is not saved.
   */
  void requestClose() {
    // The consoles first: their answers come back here, and Cancel gives up closing.
    if (asks(EXIT, "HooriDB closes", consoles(null), this::requestClose)) {
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
    if (removingSlow) {
      giveUpDelete();
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
    if (!quitting() || keyringBusy() || saving != null) {
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
              + (silent == 1 ? "it." : "them."));
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
      closeAll();
    }
  }

  /**
   * Whether Exit waits for the consoles or the history to be saved; a save due begins. Changes that
   * cannot be saved, as the file was not read, stop it once.
   */
  private boolean keptWaits() {
    for (var kept : List.of(consolesKept, historyKept)) {
      if (kept.waits()) {
        if (kept.saving == null) {
          save(kept);
        }
        return true;
      }
      if (kept.unsaved && !kept.canSave && !kept.quitAnyway) {
        kept.quitAnyway = true;
        boolean one = kept == historyKept;
        stay("HooriDB stayed open: the " + kept.what + (one ? " was" : " were") + " not read, so "
            + (one ? "it" : "they") + " cannot be saved. Close it again to quit without "
            + (one ? "it." : "them."));
        return true;
      }
    }
    return false;
  }

  /** Exit stops, and says why; the next one asks again. */
  private void stay(String why) {
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
    for (var group : tabs.groups()) {
      for (int i = 0; i < group.tabs().size(); i++) {
        if (group.tabs().get(i) instanceof TableTab x
            && x.id.equals(id)
            && x.table.container().equals(table.container())
            && x.table.name().equals(table.name())) {
          tabs.select(group, i);
          var window = group.window() != null ? group.window() : app.windows().of(app);
          if (window != null) {
            window.raise();
          }
          if (!x.table.equals(table)) {
            x.table = table;
            x.open(this, true);
          }
          return group;
        }
      }
    }
    var tab = new TableTab(id, table);
    tabs.add(into, into.tabs().size(), tab);
    tab.open(this, false);
    return into;
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
   * tree, else that of the window's active tab; null for none.
   */
  UUID consoleFor(TabGroups.Group<Tab> group) {
    if (group == tabs.main() && selected != null) {
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
   * The one act in the app that waits: what owns it, what it is, the console whose question it
   * waits on and how it goes on once that is settled, or, once armed, which consoles it locks: those
   * of a connection, or all of them for null.
   */
  record Pending(
      Object owner, String what, Runnable then, ConsoleTab console, boolean armed, UUID scope) {}

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
      if (mine && !pending.armed()) {
        pending = null;
      }
      return false;
    }
    if (pending != null && !mine) {
      refuse();
      return true;
    }
    pending = new Pending(owner, what, then, asking, false, null);
    asking.show(this);
    return true;
  }

  /**
   * Arms the act once nothing of its consoles is at stake: from now until it is done or given up
   * ({@link #drop}), those consoles start nothing, so that nothing comes between the answers and the
   * act. False, refused visibly, while another act waits.
   */
  boolean lock(Object owner, String what, UUID scope) {
    if (pending != null && !Objects.equals(pending.owner(), owner)) {
      refuse();
      return false;
    }
    pending = new Pending(owner, what, null, null, true, scope);
    return true;
  }

  private void refuse() {
    if (pending.armed()) {
      tell(pending.what() + ": that comes first.");
    } else {
      tell("A question about a console's transaction waits for its answer.");
      pending.console().show(this);
    }
  }

  /** The act is done, or given up: with what owns it, or with the console it waited on. */
  void drop(Object owner) {
    if (pending != null && (Objects.equals(pending.owner(), owner) || pending.console() == owner)) {
      pending = null;
    }
  }

  /** Why the console may start nothing now, or null. */
  String locked(ConsoleTab console) {
    return pending != null
            && pending.armed()
            && (pending.scope() == null || pending.scope().equals(console.id))
        ? pending.what()
        : null;
  }

  boolean quitting() {
    return pending != null && pending.owner() == EXIT && pending.armed();
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
    return p.database().isBlank() && p.provider().dialect().connecting().defaultDatabaseIsUser()
        ? p.user()
        : p.database();
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
    consoles(null).forEach(ConsoleTab::close);
    catalog.closeAll(); // nothing waits for a server
    for (Windows.Handle window : app.windows().all()) {
      window.close();
    }
  }
}

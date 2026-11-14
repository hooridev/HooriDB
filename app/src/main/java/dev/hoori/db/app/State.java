package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ProfileStore;
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

  static final Split.Position SIDEBAR = Split.Position.first(260);
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line

  App<State> app; // runs the clock and the windows, and says whether the window is maximized
  Theme theme = Theme.DARK;
  Split.Position sidebar = SIDEBAR;
  final MenuBar.State bar = new MenuBar.State();
  // shortcut: nothing opens a tab yet, so none can be torn off; give the opener a window of its
  // own when the first kind of tab comes.
  final TabGroups<Object> tabs = new TabGroups<>(List.of(), (group, options) -> {
    throw new UnsupportedOperationException("No tab can be torn off yet");
  });
  String message; // in the status line for a while, or null
  private int messages; // told so far

  private final ProfileStore store;
  private final Callable<Keyring> findKeyring;
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
  UUID selected; // the selected connection's id, or null
  final Menu.State connectionMenu = new Menu.State();
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
   */
  State(Path folder, Callable<Keyring> keyring) {
    this.store = new ProfileStore(folder);
    this.findKeyring = keyring;
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
    }
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
    dialog = ConnectionDialog.create();
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
        profile.tls());
    var list = new ArrayList<>(profiles);
    list.add(list.indexOf(profile) + 1, copy);
    profiles = List.copyOf(list);
    selected = copy.id();
    persist();
  }

  /** Puts the profile in place of the one with its id, or at the end, and selects it. */
  void put(ConnectionProfile profile) {
    var list = new ArrayList<>(profiles);
    int at = indexOf(profile.id());
    if (at < 0) {
      list.add(profile);
    } else {
      list.set(at, profile);
    }
    profiles = List.copyOf(list);
    selected = profile.id();
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
    profiles = profiles.stream().filter(p -> !p.id().equals(profile.id())).toList();
    if (profile.id().equals(selected)) {
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
    for (Windows.Handle window : app.windows().all()) {
      window.close();
    }
  }
}

package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.SystemAppearance;
import com.rethinkj.ui.window.Window;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryStore;
import dev.hoori.db.core.connection.QueryStore;
import dev.hoori.db.core.connection.SavedQuery;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.connection.UiState;
import dev.hoori.db.core.connection.UiStateStore;
import dev.hoori.db.core.connection.WorkspaceStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * The files kept beside the workspace, a part of the state: the consoles, the history, the saved
 * queries and the settings, each a {@link Kept}, read at the start and saved as they change; the
 * remembered layout ({@link UiStateStore}), read before the first frame and saved silently; and
 * what Exit waits for of them and of the update settings. Only the app's thread uses it.
 */
final class Keeping {

  // How long after the last edit of a console or a setting it is saved.
  static final double EDIT_SECONDS = 1;

  /** What a window's start reads before the first frame: settings (or its error) and UI state. */
  record Start(SettingsStore.Loaded settings, Exception settingsError) {}

  private final State s;
  private final ConsoleStore consoleStore;
  private final HistoryStore historyStore;
  private final QueryStore queryStore;
  private final SettingsStore settingsStore;
  private final UiStateStore uiStateStore;
  final Kept consolesKept;
  final Kept historyKept;
  final Kept queriesKept;
  final Kept settingsKept;
  private boolean preloaded; // the settings were already read before the first frame
  // Until the file is read no setting changes, so that none replaces the file's others.
  boolean settingsLoading = true;
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
  private UiState uiState;
  private boolean uiStateUnsaved;
  private Work uiStateSaving;
  private boolean uiStateSaveAgain;
  private boolean uiStateExitFailed; // a failed save during Exit is not retried
  private int uiStateChanges;

  /**
   * @param folder where the workspace is kept
   * @param s the state, whose consoles and settings it saves
   */
  Keeping(Path folder, State s) {
    this.s = s;
    this.consoleStore = new ConsoleStore(folder);
    this.historyStore = new HistoryStore(folder);
    this.queryStore = new QueryStore(folder);
    this.settingsStore = new SettingsStore(folder);
    this.uiStateStore = new UiStateStore(folder);
    this.uiState = uiStateStore.load();
    consolesKept = new Kept("consoles", () -> {
      var list = new ArrayList<StoredConsole>();
      s.consoles(null).forEach(x -> list.add(x.stored()));
      list.addAll(unseen);
      return Kept.writing(list, consoleStore::save);
    });
    historyKept = new Kept("history", () -> Kept.writing(history, historyStore::save));
    queriesKept = new Kept("saved queries", () -> Kept.writing(queries, queryStore::save));
    settingsKept = new Kept("settings", () -> Kept.writing(s.settings, settingsStore::save));
  }

  /**
   * Starts reading {@code settings.json} on a thread of its own, so that a window's start overlaps
   * it with the GPU's.
   */
  static FutureTask<Start> startReading(Path folder) {
    var task = new FutureTask<>(() -> readStart(folder));
    Thread.ofPlatform().daemon(true).name("hooridb-settings").start(task);
    return task;
  }

  private static Start readStart(Path folder) {
    try {
      return new Start(new SettingsStore(folder).load(), null);
    } catch (Exception e) {
      return new Start(null, e);
    }
  }

  /** Takes what {@link #startReading} read before the window opens, skipping the background read. */
  void takeStart(FutureTask<Start> initial) {
    Start loaded;
    try {
      loaded = initial.get();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      loaded = new Start(null, (Exception) e.getCause());
    }
    preloaded = true;
    if (loaded.settingsError() != null) {
      settingsFailed(loaded.settingsError());
    } else {
      settingsLoaded(loaded.settings());
    }
  }

  /** Resolves the theme from the settings and the operating system's appearance. */
  static Theme theme(Settings settings, SystemAppearance appearance) {
    boolean light = switch (settings.look()) {
      case LIGHT -> true;
      case DARK -> false;
      case SYSTEM -> appearance != null && appearance.scheme() == SystemAppearance.Scheme.LIGHT;
    };
    Theme resolved = light ? Theme.LIGHT : Theme.DARK;
    if (settings.accent() != Settings.DEFAULT.accent()) {
      resolved = resolved.withAccent(ConnectionColors.hue(settings.accent()));
    }
    var g = resolved.grid();
    float rowHeight = switch (settings.density()) {
      case COMPACT -> g.rowHeight() - resolved.space().s();
      case DEFAULT -> g.rowHeight();
      case COMFORTABLE -> g.rowHeight() + resolved.space().s();
    };
    if (rowHeight != g.rowHeight()) {
      resolved = resolved.withGrid(new Theme.Grid(
          rowHeight,
          g.minColumn(),
          g.maxColumn(),
          g.handle(),
          g.mark(),
          g.header(),
          g.row(),
          g.stripe(),
          g.selected(),
          g.selectedInactive(),
          g.cursor(),
          g.cursorWidth(),
          g.changed(),
          g.added(),
          g.deleted(),
          g.error()));
    }
    return resolved;
  }

  /** Applies the layout loaded from {@link UiStateStore#FILE} to the sidebar and statement log. */
  void restoreLayout() {
    if (uiState.sidebar() != null) {
      s.sidebar = Split.Position.first(uiState.sidebar());
    }
    s.logPanel.restore(uiState);
  }

  /** The main window's remembered placement, or null where none is stored or it cannot be read. */
  Window.Placement windowPlacement() {
    var line = uiState.window();
    if (line == null) {
      return null;
    }
    try {
      return Window.Placement.parse(line);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  UiState uiState() {
    return uiState;
  }

  /** Keeps the sidebar's width, a moment after the last move. */
  void sidebar(Split.Position position) {
    if (position.kind() == Split.Position.Kind.FIRST) {
      uiState(uiState.withSidebar(position.value()));
    }
  }

  /** Keeps the layout once it has not changed for a moment, and at Exit; failures are silent. */
  void uiState(UiState changed) {
    if (changed.equals(uiState)) {
      return;
    }
    uiState = changed;
    uiStateUnsaved = true;
    if (!s.guard.quitting()) {
      uiStateExitFailed = false;
    }
    int at = ++uiStateChanges;
    s.app.after(EDIT_SECONDS, () -> {
      if (uiStateChanges == at && uiStateUnsaved) {
        saveUiState();
      }
    });
  }

  private void saveUiState() {
    if (uiStateSaving != null) {
      uiStateSaveAgain = true;
      return;
    }
    if (!uiStateUnsaved) {
      return;
    }
    var snapshot = uiState;
    uiStateUnsaved = false;
    uiStateSaving = s.app.background(
        "save UI state",
        Kept.writing(snapshot, uiStateStore::save),
        done -> uiStateSaved(false),
        error -> uiStateSaved(true));
  }

  private void uiStateSaved(boolean failed) {
    uiStateSaving = null;
    if (failed) {
      uiStateUnsaved = true; // the next change or Exit tries again
      if (s.guard.quitting()) {
        uiStateExitFailed = true;
      }
    } else {
      uiStateExitFailed = false;
    }
    if (uiStateSaveAgain) {
      uiStateSaveAgain = false;
      if (!uiStateExitFailed) {
        saveUiState();
        return;
      }
    }
    s.settled();
  }

  /** Reads the main window's placement before Exit closes it; none in a script run. */
  private void captureWindow() {
    var handle = s.app == null ? null : s.app.windows().of(s.app);
    var window = handle == null ? null : handle.window();
    if (window != null && window.isOpen()) {
      var placement = window.placement().toString();
      if (!placement.equals(uiState.window())) {
        uiState = uiState.withWindow(placement);
        uiStateUnsaved = true;
      }
    }
  }

  /** Loads the consoles, the history, the saved queries, and where not preloaded, the settings. */
  void start() {
    if (!preloaded) {
      s.app.background(
          "load settings", settingsStore::load, this::settingsLoaded, this::settingsFailed);
    }
    var consoles = consoleStore;
    s.app.background(
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
    s.app.background(
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
    s.app.background(
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
  void dropOrphanQueries() {
    if (!s.loaded || s.loadError != null || queriesLoading || !queriesKept.canSave) {
      return;
    }
    var known = new HashSet<UUID>();
    s.workspace().connections().forEach(p -> known.add(p.id()));
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

  /** The connection was deleted: its saved queries go too; those not loaded yet go as they load. */
  void forgetQueries(UUID id) {
    deletedConnections.add(id);
    var before = queries;
    queries = queries.stream().filter(q -> !q.connection().equals(id)).toList();
    if (queries.size() != before.size()) {
      persist(queriesKept);
    }
  }

  private void settingsLoaded(SettingsStore.Loaded loaded) {
    settingsLoading = false;
    s.applySettings(loaded.settings());
    var fellBack = loaded.fellBack();
    if (!fellBack.isEmpty()) {
      s.notice(
          settingsKept.title(),
          "defaults used",
          fellBack.size() == 1
              ? "The setting " + fellBack.getFirst() + " could not be read, so its default is used."
              : "The settings "
                  + String.join(", ", fellBack)
                  + " could not be read, so their defaults are used.",
          false,
          null);
    }
    settingsKept.canSave = true;
    s.updates.settingsReady(s);
    restore();
  }

  private void settingsFailed(Exception error) {
    settingsLoading = false;
    loadFailed(settingsKept, error);
    // A file not understood is set aside by the next save; one not read at all stays as it is.
    settingsKept.canSave = error instanceof WorkspaceStore.UnreadableException;
    s.updates.settingsReady(s);
    restore();
  }

  private void loadFailed(Kept kept, Exception error) {
    s.notice(kept.title(), "not read", State.messageOf(error), true, null);
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
  void restore() {
    if (restoring == null || !s.loaded || s.loadError != null || settingsLoading) {
      return;
    }
    var main = s.tabs.main();
    var hidden = new ArrayList<StoredConsole>();
    for (var stored : restoring) {
      if (s.profile(stored.connection()) != null) {
        var console = ConsoleTab.of(s, stored.connection(), stored.database());
        console.text = stored.text();
        s.tabs.add(main, main.tabs().size(), console);
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
    edited(consolesKept);
  }

  /** A setting changed: it is saved once nothing else changes for a while. */
  void settingsChanged() {
    edited(settingsKept);
  }

  private void edited(Kept kept) {
    if (!s.guard.quitting()) {
      kept.quitAnyway = false; // a change since: the next Exit tries to save it
    }
    kept.unsaved = true;
    int at = ++kept.edits;
    s.app.after(EDIT_SECONDS, () -> {
      if (kept.edits == at && kept.unsaved && !kept.quitAnyway) { // the last Exit went without it
        save(kept);
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

  /**
   * Writes the file as the state is now, off the app's thread, as {@link State#persist()} writes
   * the profiles; a failure goes to the notices once, until a save succeeds.
   */
  void persist(Kept kept) {
    if (!s.guard.quitting()) {
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
    int at = kept.edits; // an edit after this is not in what it writes, and saves after its pause
    kept.saving = s.app.background(
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
    kept.unsaved = kept.edits != edit;
    s.settled();
  }

  /** The save of the file failed: an Exit stays, else the first failure is told. */
  private void saveFailed(Kept kept, Exception error) {
    kept.saving = null;
    boolean first = kept.saveError == null;
    kept.saveError = State.messageOf(error);
    if (kept.again) {
      kept.again = false;
      save(kept);
    } else if (s.guard.quitting()) {
      kept.quitAnyway = true;
      s.stay("HooriDB stayed open, as the " + kept.what + " could not be saved. Close it again"
          + " to quit without "
          + State.them(kept == historyKept));
    } else if (kept == s.updates.kept || kept == settingsKept) {
      s.tell("The " + kept.what + " could not be saved: " + kept.saveError);
    } else if (first) {
      s.notice(kept.title(), "not saved", kept.saveError, true, null);
    }
  }

  /**
   * Whether Exit waits for the consoles, the history, the saved queries, the settings, the update
   * settings or the remembered layout to be saved; a save due begins. Changes to the others that
   * cannot be saved, as the file was not read, stop Exit once; a failure to save the layout never
   * stops it.
   */
  boolean waits() {
    for (var each : List.of(consolesKept, historyKept, queriesKept, settingsKept, s.updates.kept)) {
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
    if (!uiStateExitFailed) {
      captureWindow();
      if (uiStateUnsaved) {
        saveUiState();
      }
    }
    return uiStateSaving != null;
  }

  /** Changes to a file that was not read stop Exit once, as they cannot be saved. */
  private void unreadStops(Kept kept) {
    kept.quitAnyway = true;
    boolean one = kept == historyKept;
    s.stay("HooriDB stayed open: the " + kept.what + (one ? " was" : " were") + " not read, so "
        + (one ? "it" : "they") + " cannot be saved. Close it again to quit without "
        + State.them(one));
  }
}

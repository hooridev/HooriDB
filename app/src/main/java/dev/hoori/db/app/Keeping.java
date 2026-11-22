package dev.hoori.db.app;

import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.window.Window;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryStore;
import dev.hoori.db.core.connection.QueryStore;
import dev.hoori.db.core.connection.SavedQuery;
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

/**
 * The files kept beside the workspace, a part of the state: the consoles, the history and the saved
 * queries, each a {@link Kept}, read at the start and saved as they change; the remembered layout
 * ({@link UiStateStore}), read before the first frame and saved silently; and what Exit waits for
 * of them and of the update settings. Only the app's thread uses it.
 */
final class Keeping {

  // How long after the last edit of a console its text is saved.
  static final double EDIT_SECONDS = 1;

  private final State s;
  private final ConsoleStore consoleStore;
  private final HistoryStore historyStore;
  private final QueryStore queryStore;
  private final UiStateStore uiStateStore;
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
  private UiState uiState;
  private boolean uiStateUnsaved;
  private Work uiStateSaving;
  private boolean uiStateSaveAgain;
  private boolean uiStateExitFailed; // a failed save during Exit is not retried
  private int uiStateChanges;

  /**
   * @param folder where the workspace is kept
   * @param s the state, whose consoles it saves
   */
  Keeping(Path folder, State s) {
    this.s = s;
    this.consoleStore = new ConsoleStore(folder);
    this.historyStore = new HistoryStore(folder);
    this.queryStore = new QueryStore(folder);
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

  /** Loads the consoles, the history and the saved queries. */
  void start() {
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
    if (restoring == null || !s.loaded || s.loadError != null) {
      return;
    }
    var main = s.tabs.main();
    var hidden = new ArrayList<StoredConsole>();
    for (var stored : restoring) {
      if (s.profile(stored.connection()) != null) {
        var console = new ConsoleTab(stored.connection(), stored.database());
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
    if (!s.guard.quitting()) {
      consolesKept.quitAnyway = false; // a change since: the next Exit tries to save it
    }
    consolesKept.unsaved = true;
    int at = ++edits;
    s.app.after(EDIT_SECONDS, () -> {
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
    int at = edits; // an edit after this is not in what it writes, and saves after its pause
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
    kept.unsaved = kept == consolesKept && edits != edit;
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
    } else if (kept == s.updates.kept) {
      s.tell("The update settings could not be saved: " + kept.saveError);
    } else if (first) {
      s.notice(kept.title(), "not saved", kept.saveError, true, null);
    }
  }

  /**
   * Whether Exit waits for the consoles, the history, the saved queries, the update settings or
   * the remembered layout to be saved; a save due begins. Changes to the first four that cannot be
   * saved, as the file was not read, stop Exit once; a failure to save the layout never stops it.
   */
  boolean waits() {
    for (var each : List.of(consolesKept, historyKept, queriesKept, s.updates.kept)) {
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

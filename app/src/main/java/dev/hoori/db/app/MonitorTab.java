package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.Credentials;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.ServerVariable;
import dev.hoori.db.core.query.QueryException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a server does now, in a tab of its connection: the sessions of its clients, its variables,
 * its status counters and who blocks whom. They are read on a session and lane of the monitor's
 * own, never the tree's or a console's, so that a read never waits behind theirs; it opens once the
 * tree's connection is connected, and closing the tab ends it. The view shown is read again at the
 * interval chosen while the tab is shown, not while it is hidden or paused or a question is open,
 * and a tab shown again reads at once; the variables, which seldom change, only when shown first
 * and by Refresh, the locks only where the server tells them, and a view the server refused only
 * by hand. A read at the interval is quiet: it shows that it is busy only once it takes long. Only
 * the app's thread uses it.
 */
final class MonitorTab implements Tab {

  /** Why the view could not be read, in words for the user. */
  record Problem(String title, String text) {}

  /** What the tab shows of the server. */
  enum View {
    SESSIONS("Sessions", "sessions"),
    VARIABLES("Variables", "variables"),
    STATUS("Status", "status counters"),
    LOCKS("Locks", "locks");

    final String title;
    final String what;

    View(String title, String what) {
      this.title = title;
      this.what = what;
    }
  }

  static final List<View> VIEWS = List.of(View.values());

  /** How long, in seconds, a read at the interval runs before it shows that it is busy. */
  static final double SLOW = 0.5;

  private static final Format<LocalTime> TIME = Format.time(0);

  /** What a read of a view gave. */
  private sealed interface Got {}

  private record Sessions(List<ServerSession> sessions) implements Got {}

  private record Variables(List<ServerVariable> variables) implements Got {}

  private record Status(List<Counter> counters) implements Got {}

  private record Locks(Optional<List<Block>> blocks) implements Got {}

  /** What can be done to a session, after a question. */
  enum Action {
    CANCEL("Cancel statement", "Cancel the statement?", "Cancel"),
    END("End session", "End the session?", "End");

    final String title;
    final String question;
    final String requested; // as the status line says it was asked for

    Action(String title, String question, String requested) {
      this.title = title;
      this.question = question;
      this.requested = requested;
    }
  }

  /** The question before an action on a session, as it was when asked. */
  record Asked(Action action, ServerSession session) {}

  final UUID id;
  final Refresh refresh = new Refresh();
  View view = View.SESSIONS;
  List<ServerSession> sessions = List.of();
  List<ServerVariable> variables; // null until read
  List<Counter> counters; // null until read
  Optional<List<Block>> blocks; // null until read; empty where the server does not tell
  private final Map<View, Instant> updated = new EnumMap<>(View.class); // the last read of each
  private final Set<View> denied = EnumSet.noneOf(View.class); // refused: read only by hand
  private final Map<View, String> searches = new EnumMap<>(View.class);
  private final Map<View, MonitorGrids.Widths> widths = new EnumMap<>(View.class);
  Lane.Asked reading; // the read under way, or null
  private boolean loud; // whether the read under way shows that it is busy
  private int reads; // counts the reads, so that the timer of one before does nothing
  Problem problem; // why the last read failed, or null
  String said; // what the tab says for a while in its status line; or null
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final DataGrid.Selection variableSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection statusSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection lockSelection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  // The item at the selection's row in Variables, Status and Locks, by its key, which a read or a
  // search that puts another there clears.
  private final Map<View, String> picked = new EnumMap<>(View.class);
  final Menu.State intervals = new Menu.State();
  final Menu.State menu = new Menu.State(); // the sessions' context menu
  final Menu.State lockMenu = new Menu.State(); // the locks' context menu
  Asked asked; // the question before an action, or null
  Lane.Asked acting; // the action under way, or null
  Problem refused; // why the last action failed, until the next or a dismiss; or null
  private String selected; // the id of the session selected, which a read keeps selected
  private boolean listed; // the sessions were read on the session the monitor has now
  private int says;
  private Lane lane; // the monitor's own session, or null while it has none
  private char[] password; // a copy, for masking it where a statement holds it; wiped as it ends
  private boolean due; // a tick or a connect came while the tab was hidden: it reads once shown
  private boolean closed;

  private final State state; // for close and decline, which are called without one

  MonitorTab(State state, UUID id) {
    this.state = state;
    this.id = id;
  }

  /**
   * Opens the monitor's session where it has none and reads the view; nothing while the tree's
   * connection is not connected, which {@link #connected} follows. What was read on a session
   * before, which may be another server's, goes.
   */
  void open(State s) {
    if (closed) {
      return;
    }
    if (lane == null) {
      var session = s.catalog.session(id, "");
      if (session == null) {
        return; // the view says that it is not connected
      }
      lane = session.lane();
      password = session.password();
      sessions = List.of();
      variables = null;
      counters = null;
      blocks = null;
      updated.clear();
      denied.clear();
      selected = null;
      selection.clear();
      problem = null;
    }
    read(s);
  }

  /**
   * The tree's connection connected: the monitor opens its session and reads, where the tab is
   * shown, else once it is.
   */
  void connected(State s) {
    if (shown(s)) {
      open(s);
    } else if (!closed) {
      due = true;
    }
  }

  /** Reads the view shown now, as the user asked, unless a read is under way. */
  void read(State s) {
    read(s, true);
  }

  /**
   * Reads the view shown now, unless a read is under way; the next comes at the interval, where
   * the view repeats. One the user did not ask for, of a view read before, is quiet until it takes
   * {@link #SLOW}.
   */
  private void read(State s, boolean byHand) {
    if (closed || lane == null || reading != null) {
      return;
    }
    due = false;
    refresh.stop();
    var live = s.catalog.live(id);
    var of = view;
    loud = byHand || !updated.containsKey(of);
    int at = ++reads;
    if (!loud) {
      s.app.after(SLOW, () -> loud |= reads == at && reading != null);
    }
    reading = lane.ask(
        s.app,
        "read the " + of.what,
        Lane.Priority.VIEW,
        link -> {
          var monitor = link.monitor();
          return switch (of) {
            case SESSIONS -> new Sessions(monitor.sessions());
            case VARIABLES -> new Variables(monitor.variables());
            case STATUS -> new Status(monitor.status());
            case LOCKS -> new Locks(monitor.locks());
          };
        },
        got -> {
          reading = null;
          problem = null;
          denied.remove(of);
          updated.put(of, Instant.now());
          switch ((Got) got) {
            case Sessions x -> show(s, x.sessions());
            case Variables x -> variables = x.variables();
            case Status x -> counters = x.counters();
            case Locks x -> blocks = x.blocks();
          }
          keep(of);
          next(s);
        },
        error -> {
          reading = null;
          if (error instanceof InterruptedException) {
            return; // its session ended under it
          }
          failed(s, error, live, of);
        });
  }

  /**
   * The sessions as read, the connection's password masked in them; the selection stays, and a
   * question about a session that is gone closes.
   */
  private void show(State s, List<ServerSession> read) {
    var shown = new ArrayList<ServerSession>(read.size());
    for (var session : read) {
      shown.add(session.withStatement(Credentials.without(session.statement(), password)));
    }
    sessions = List.copyOf(shown);
    listed = true;
    int at = indexOf(selected);
    if (at >= 0) {
      if (selection.row() != at) {
        selection.select(at, Math.max(0, selection.column()));
      }
    } else if (selected != null) {
      selected = null;
      selection.clear();
    }
    if (asked != null && !has(asked.session())) {
      say(s, "Session " + asked.session().id() + " has ended");
      asked = null;
    }
  }

  // Whether the sessions read last hold this one, by its id and start.
  private boolean has(ServerSession session) {
    int at = indexOf(session.id());
    return at >= 0 && sessions.get(at).started().equals(session.started());
  }

  private int indexOf(String session) {
    for (int i = 0; session != null && i < sessions.size(); i++) {
      if (sessions.get(i).id().equals(session)) {
        return i;
      }
    }
    return -1;
  }

  /** Whether the failure says that the monitor's connection is gone. */
  private static boolean lost(Exception error) {
    return error instanceof ConnectionException
        || error instanceof QueryException q
            && (q.failure() == QueryException.Failure.CONNECTION_LOST
                || q.failure() == QueryException.Failure.ABORTED);
  }

  private void failed(State s, Exception error, CatalogTree.Live live, View of) {
    var detail = CatalogTree.words(live, detail(error));
    if (lost(error)) {
      closeSession(s);
      problem = new Problem(
          "Disconnected",
          "The monitor's connection was lost or could not be opened."
              + (detail.isEmpty() ? "" : "\n" + detail));
      return;
    }
    problem = switch (error) {
      case QueryException q
      when q.failure() == QueryException.Failure.ACCESS_DENIED -> {
        denied.add(of);
        yield new Problem(
            "Access denied",
            "The server does not let this user see its " + of.what + "."
                + (detail.isEmpty() ? "" : "\n" + detail));
      }
      case QueryException q
      when q.failure() == QueryException.Failure.CANCELLED ->
        new Problem("Cancelled", "The server cancelled the read.");
      default -> new Problem("Could not be read", detail);
    };
    next(s);
  }

  /**
   * Whether the view shown is read again at the interval: not the variables, nor locks where the
   * server does not tell them.
   */
  private boolean repeats() {
    return switch (view) {
      case SESSIONS, STATUS -> true;
      case VARIABLES -> false;
      case LOCKS -> blocks == null || blocks.isPresent();
    };
  }

  /**
   * The next read at the interval, where the view repeats, the server did not refuse it, and no
   * question is open.
   */
  private void next(State s) {
    if (repeats() && !denied.contains(view) && asked == null) {
      refresh.next(s.app, () -> tick(s));
    }
  }

  /**
   * Shows the view, read at once where it repeats or was not read yet; what was read for another
   * is given up. Without a session, the tab keeps saying why, with what the user can do.
   */
  void show(State s, View next) {
    if (next == view) {
      return;
    }
    view = next;
    refresh.stop();
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    if (lane == null) {
      return;
    }
    problem = null;
    if (repeats() || !updated.containsKey(next)) {
      read(s, false);
    }
  }

  /** Goes to the session in the sessions view, selected, as from the locks. */
  void goTo(State s, String session) {
    selected = session;
    int at = indexOf(session);
    if (at >= 0) {
      selection.select(at, 0);
    } else {
      selection.clear();
    }
    show(s, View.SESSIONS);
  }

  /** What is searched for in the view. */
  String search(View of) {
    return searches.getOrDefault(of, "");
  }

  private static String detail(Exception error) {
    return switch (error) {
      case QueryException e -> e.detail();
      case ConnectionException e -> e.detail();
      default -> State.message(error);
    };
  }

  /** The interval passed: it reads now where the tab is shown, else once it is. */
  private void tick(State s) {
    if (closed) {
      return;
    }
    if (shown(s)) {
      read(s, false);
    } else {
      due = true;
    }
  }

  /** Whether the tab is the one its window shows. */
  boolean shown(State s) {
    var group = s.tabs.groupOf(this);
    return group != null && group.activeTab() == this;
  }

  /** Whether a tick or a connect came while the tab was hidden, so that it reads once shown. */
  boolean due() {
    return due;
  }

  /** Reads again at this interval, counted from now. */
  void every(State s, Refresh.Interval interval) {
    refresh.interval = interval;
    if (!refresh.paused() && reading == null && lane != null) {
      next(s);
    }
  }

  /** Pauses the reading at the interval, or reads at once and goes on with it. */
  void pause(State s, boolean pause) {
    refresh.pause(pause);
    if (!pause) {
      read(s, false);
    }
  }

  /** The session selected, as read last; null for none. */
  ServerSession selected() {
    int at = indexOf(selected);
    return at < 0 ? null : sessions.get(at);
  }

  /**
   * The session an action acts on: the one row selected, in a list read on the monitor's session
   * now; null for none.
   */
  ServerSession chosen() {
    if (!listed || lane == null || selection.isEmpty() || selection.rowCount() != 1) {
      return null;
    }
    return selected();
  }

  /**
   * Asks before the action on the session, and reads nothing at the interval meanwhile; nothing
   * where it cannot be done to it.
   */
  void ask(Action action, ServerSession session) {
    if (session != null && can(action, session) && lane != null && acting == null) {
      asked = new Asked(action, session);
      refresh.stop();
    }
  }

  private static boolean can(Action action, ServerSession session) {
    return switch (action) {
      case CANCEL -> session.cancellable();
      case END -> session.ends();
    };
  }

  /** The question was declined: nothing is done, and the reading at the interval goes on. */
  void decline() {
    asked = null;
    if (reading == null && lane != null) {
      next(state);
    }
  }

  /**
   * The question was answered: the action runs on the monitor's session, the status line says
   * what came of it, and the list is read again.
   */
  void proceed(State s) {
    var what = asked;
    asked = null;
    if (what == null || lane == null || acting != null) {
      decline();
      return;
    }
    refused = null;
    var live = s.catalog.live(id);
    var session = what.session();
    acting = lane.ask(
        s.app,
        what.action().title.toLowerCase(Locale.ROOT),
        Lane.Priority.VIEW,
        link -> switch (what.action()) {
          case CANCEL -> link.monitor().cancel(session);
          case END -> link.monitor().end(session);
        },
        found -> {
          acting = null;
          // The servers answer once they have passed the request on, before the work stops.
          say(
              s,
              found
                  ? what.action().requested + " requested for session " + session.id()
                  : what.action() == Action.CANCEL
                      ? "Session " + session.id() + " had ended or runs something else now"
                      : "Session " + session.id() + " had ended already");
          read(s, false);
        },
        error -> {
          acting = null;
          if (error instanceof InterruptedException) {
            return;
          }
          if (lost(error)) {
            failed(s, error, live, view);
            return;
          }
          var detail = CatalogTree.words(live, detail(error));
          refused = new Problem(
              what.action() == Action.CANCEL ? "Not cancelled" : "Not ended",
              error instanceof QueryException q
                      && q.failure() == QueryException.Failure.ACCESS_DENIED
                  ? "The server does not let this user do that to session " + session.id() + "."
                      + (detail.isEmpty() ? "" : "\n" + detail)
                  : detail);
          read(s, false);
        });
  }

  /** Whether anything of the tab is read or done now that shows: not a quiet read. */
  boolean busy() {
    return reading != null && loud || acting != null;
  }

  /** Says something in the tab's status line for a while. */
  void say(State s, String text) {
    said = text;
    int told = ++says;
    s.app.after(State.MESSAGE_SECONDS, () -> {
      if (says == told) {
        said = null;
      }
    });
  }

  /**
   * Its session ends, as its connection was disconnected or changed: what runs is given up and the
   * connection closed, which Exit waits for. What was read stays, and Connect opens it anew.
   */
  void end(State s) {
    closeSession(s);
  }

  private void closeSession(State s) {
    refresh.stop();
    due = false;
    listed = false;
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    if (acting != null) {
      acting.cancel();
      acting = null;
    }
    asked = null;
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the monitor's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      lane = null;
    }
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  /** Whether it has a session of its own. */
  boolean connected() {
    return lane != null;
  }

  @Override
  public void close() {
    closed = true;
    closeSession(state);
    sessions = List.of();
  }

  // The selections of Variables, Status and Locks

  // shortcut: a read-only grid keeps its selection by row, not by key (rethinkj/ui#309), so a
  // read or a search that puts another item at the selected row clears it; follow the item by key
  // once the grid can.

  private DataGrid.Selection selectionOf(View of) {
    return switch (of) {
      case SESSIONS -> selection;
      case VARIABLES -> variableSelection;
      case STATUS -> statusSelection;
      case LOCKS -> lockSelection;
    };
  }

  /** The keys of the items the view shows, in order: a name, or who waits for whom. */
  private List<String> keys(View of) {
    return switch (of) {
      case SESSIONS -> sessions.stream().map(ServerSession::id).toList();
      case VARIABLES ->
        variables == null
            ? List.of()
            : shownVariables().stream().map(ServerVariable::name).toList();
      case STATUS ->
        counters == null
            ? List.of()
            : shownCounters().stream().map(Counter::name).toList();
      case LOCKS ->
        blocks == null
            ? List.of()
            : blocks.orElse(List.of()).stream()
                .map(b -> b.waiting() + " " + b.blocking())
                .toList();
    };
  }

  /** The user selected in the view: its item is kept by its key. */
  private void picked(View of) {
    int row = selectionOf(of).row();
    var keys = keys(of);
    if (row >= 0 && row < keys.size()) {
      picked.put(of, keys.get(row));
    } else {
      picked.remove(of);
    }
  }

  /** The view's selection goes where another item stands at its row now. */
  private void keep(View of) {
    var chosen = selectionOf(of);
    if (of == View.SESSIONS || chosen.isEmpty()) {
      return;
    }
    int row = chosen.row();
    var keys = keys(of);
    if (row < 0 || row >= keys.size() || !keys.get(row).equals(picked.get(of))) {
      chosen.clear();
      picked.remove(of);
    }
  }

  // The view

  /** The tab's content: its toolbar, why it cannot read, the view, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    if (due && shown(s)) {
      open(s); // shown again after a tick or a connect it missed
    }
    var profile = s.profile(id);
    String where = profile == null ? null : State.name(profile);
    boolean can = lane != null;
    // shortcut: the toolbar puts its actions at its end, and has no slot at its start
    // (rethinkj/ui#310), and Segmented's choices slide where the control moves (rethinkj/ui#311);
    // so the view's switch goes last, Refresh before it, and what differs by view before those,
    // that they stay in place. Move the switch to the start once the toolbar has the slot.
    var actions = new ArrayList<El>();
    if (view == View.VARIABLES || view == View.STATUS) {
      var of = view;
      actions.add(TextField.withIcon(
              TextField.textField(search(of), "Search", v -> {
                searches.put(of, v);
                keep(of);
              }),
              Symbol.SEARCH)
          .key("search")
          .label("Search " + of.what)
          .w(8 * t.size().control()));
    }
    if (view == View.SESSIONS) {
      actions.add(action(Action.CANCEL, Symbol.DISMISS_CIRCLE).key("cancel-statement"));
      actions.add(action(Action.END, Symbol.PROHIBITED).key("end-session"));
    }
    actions.add(button(
            SECONDARY,
            "Refresh",
            can ? () -> read(s) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(reading != null && loud ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    actions.add(Segmented.segmented(VIEWS, view, v -> v.title, v -> show(s, v))
        .key("view")
        .label("Show"));
    // The view's name is on its switch: the toolbar names the connection.
    El top = Card.toolbar(where == null ? "" : where, null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    // Each part keyed, so that a callout that comes or goes leaves the others as they are.
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    El note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note).key("note"));
    }
    if (refused != null) {
      content.child(col()
          .padding(t.space().m())
          .shrink(0)
          .key("refused-note")
          .child(Callout.callout(
                  Callout.Kind.DANGER,
                  refused.title(),
                  refused.text(),
                  button(SECONDARY, "Dismiss", () -> refused = null))
              .key("refused")));
    }
    if (updated.containsKey(view)) {
      content.child(shown(s, app));
    } else if (reading != null && problem == null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the " + view.what)
          .key("reading")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the " + view.what + "…")));
    } else {
      content.child(col().grow(1).key("nothing"));
    }
    El count = Label.label(Label.Variant.MUTED, count()).key("count").ellipsis();
    return content.child(Card.footer(count, footer(s, t)).key("footer"));
  }

  /** The end of the status line: what it says, when the view was read, and how often. */
  private El[] footer(State s, Theme t) {
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (refresh.paused()) {
      end.add(Badge.badge("Paused", Badge.options().kind(Badge.Kind.WARNING).icon(Symbol.PAUSE))
          .key("paused"));
    }
    var at = updated.get(view);
    end.add(Label.label(
            Label.Variant.MUTED,
            at == null ? "" : "Updated " + TIME.text(LocalTime.ofInstant(at, s.zone)))
        .key("read-status"));
    var off = scheduleOff();
    El interval = Select.select(
            Refresh.INTERVALS,
            refresh.interval,
            i -> "Every " + i.title(),
            intervals,
            off == null ? i -> every(s, i) : null)
        .key("interval")
        .label("Refresh interval")
        .w(4 * t.size().control());
    boolean paused = refresh.paused();
    El pause = Button.toggleIcon(
            paused ? Symbol.PLAY : Symbol.PAUSE,
            paused ? "Resume" : "Pause",
            paused,
            off == null ? p -> pause(s, p) : null)
        .key("pause");
    end.add(off == null ? interval : Tooltip.tooltip(interval, off));
    end.add(off == null ? pause : Tooltip.tooltip(pause, off));
    return end.toArray(El[]::new);
  }

  /** Why the view is not read at an interval, which turns its controls off; null where it is. */
  private String scheduleOff() {
    if (lane == null) {
      return "The monitor is not connected";
    }
    if (view == View.VARIABLES) {
      return "The variables are read when shown first and by Refresh";
    }
    if (view == View.LOCKS && blocks != null && blocks.isEmpty()) {
      return "The server does not tell who holds a lock";
    }
    return null;
  }

  /** How many the view shows, as "4 sessions" or "12 of 312 variables". */
  private String count() {
    if (!updated.containsKey(view)) {
      return "";
    }
    return switch (view) {
      case SESSIONS -> count(sessions.size(), sessions.size(), "session", "sessions");
      case VARIABLES -> count(shownVariables().size(), variables.size(), "variable", "variables");
      case STATUS -> count(shownCounters().size(), counters.size(), "counter", "counters");
      case LOCKS ->
        blocks.isEmpty() ? "" : count(blocks.get().size(), blocks.get().size(), "wait", "waits");
    };
  }

  private static String count(int shown, int all, String one, String many) {
    var of = shown == all ? "" : String.format(Locale.ROOT, "%,d of ", shown);
    return of + String.format(Locale.ROOT, "%,d %s", all, all == 1 ? one : many);
  }

  private List<ServerVariable> shownVariables() {
    return MonitorGrids.found(
        variables, search(View.VARIABLES), v -> v.name() + " " + v.value() + " " + v.source());
  }

  private List<Counter> shownCounters() {
    return MonitorGrids.found(counters, search(View.STATUS), c -> c.name() + " " + c.value());
  }

  private MonitorGrids.Widths widths(View of) {
    return widths.computeIfAbsent(of, v -> new MonitorGrids.Widths());
  }

  /** The view as read. */
  private El shown(State s, App<State> app) {
    var of = view;
    Runnable onSelect = () -> picked(of);
    return switch (of) {
      case SESSIONS ->
        MonitorGrids.sessions(
            List.copyOf(sessions),
            widths(of),
            selection,
            () -> {
              int row = selection.row();
              selected = row >= 0 && row < sessions.size() ? sessions.get(row).id() : null;
            },
            menu,
            menuItems(s),
            app);
      case VARIABLES ->
        MonitorGrids.variables(shownVariables(), widths(of), variableSelection, onSelect, app);
      case STATUS ->
        MonitorGrids.status(shownCounters(), widths(of), statusSelection, onSelect, app);
      case LOCKS -> {
        if (blocks.isEmpty()) {
          yield EmptyState.emptyState(
                  Symbol.INFO,
                  "No lock information",
                  "The server does not tell who holds a lock.",
                  null)
              .key("no-locks");
        }
        if (blocks.get().isEmpty()) {
          yield EmptyState.emptyState(
                  Symbol.LOCK_OPEN,
                  "No session waits for a lock",
                  "A session that waits for a lock another holds shows here, beside the one"
                      + " that blocks it.",
                  null)
              .key("no-locks");
        }
        var shown = blocks.get();
        yield MonitorGrids.locks(
            shown,
            widths(of),
            lockSelection,
            onSelect,
            row -> goTo(s, shown.get(row).blocking()),
            lockMenu,
            lockItems(s, shown),
            app);
      }
    };
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(State s) {
    if (!s.catalog.connected(id)) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      String text = lost != null ? CatalogTree.why(lost) : "Its connection is closed.";
      if (updated.containsKey(view)) {
        text += " What shows was read before, and may have changed since.";
      }
      return Callout.callout(
          Callout.Kind.WARNING,
          connecting ? "Connecting…" : "Disconnected",
          text,
          connecting
              ? new El[0]
              : new El[] {button(SECONDARY, "Connect", () -> s.catalog.connect(s, id))});
    }
    if (problem == null) {
      return null;
    }
    return Callout.callout(
        Callout.Kind.DANGER,
        problem.title(),
        problem.text(),
        button(SECONDARY, "Try again", () -> open(s)));
  }

  /**
   * The button of an action on the session chosen: off, and saying why, where it cannot be done
   * to it. It is neutral, as only the question's button warns.
   */
  private El action(Action action, Symbol icon) {
    var session = chosen();
    boolean can = session != null && can(action, session) && acting == null;
    El button = button(
        SECONDARY,
        action.title,
        can ? () -> ask(action, session) : null,
        Button.options().icon(icon).state(acting != null ? Button.State.BUSY : Button.State.READY));
    var why = selection.rowCount() > 1 ? "Select one session" : why(action, session);
    return why == null ? button : Tooltip.tooltip(button, why);
  }

  /** Why the action cannot be done to the session, or null where it can or none is selected. */
  static String why(Action action, ServerSession session) {
    if (session == null) {
      return null;
    }
    if (session.own()) {
      return "The monitor's own session";
    }
    return switch (action) {
      case CANCEL -> session.cancellable() ? null : "It runs nothing that can be cancelled";
      case END ->
        session.ends()
            ? null
            : "The server does not end another client's connection; cancel what it runs instead";
    };
  }

  /** The question before an action on a session, naming it; null while none is asked. */
  El question(State s) {
    Theme t = Theme.CURRENT.get();
    var what = asked;
    if (what == null) {
      return null;
    }
    var session = what.session();
    var who = "Session " + session.id()
        + (session.user().isEmpty() ? "" : " of " + session.user())
        + (session.client().isEmpty() ? "" : ", from " + session.client());
    var text = switch (what.action()) {
      case CANCEL ->
        who + ", runs:\n" + brief(session.statement())
            + "\n\nThe server stops the statement; the session stays connected.";
      case END ->
        who + (session.state().isEmpty() ? "" : ", " + session.state())
            + (session.statement().isEmpty()
                ? "."
                : (session.operation().isEmpty() ? ", ran last:\n" : ", runs:\n")
                    + brief(session.statement()))
            + "\n\nThe server closes its connection and rolls back what it did not commit.";
    };
    return Dialog.dialog(
            what.action().question,
            col()
                .w(ConnectionDialog.width())
                .gap(t.space().m())
                .child(Label.label(text).wrapText()),
            List.of(
                button(SECONDARY, "Keep it", this::decline).key("question-keep"),
                button(
                        PRIMARY,
                        what.action().title,
                        () -> proceed(s),
                        Button.options().tone(Button.Tone.DANGER))
                    .key("question-go")),
            this::decline)
        .key("monitor-question");
  }

  /** At most {@link #BRIEF} characters of the statement, as a question names it. */
  static String brief(String statement) {
    return statement.length() <= BRIEF ? statement : statement.substring(0, BRIEF) + "…";
  }

  static final int BRIEF = 400;

  /** The sessions' context menu, of the one session chosen. */
  private List<Menu.Item> menuItems(State s) {
    var session = acting != null ? null : chosen();
    return List.of(
        Menu.item(
                Action.CANCEL.title + "…",
                session != null && session.cancellable() ? () -> ask(Action.CANCEL, session) : null)
            .icon(Symbol.DISMISS_CIRCLE),
        Menu.item(
                Action.END.title + "…",
                session != null && session.ends() ? () -> ask(Action.END, session) : null)
            .icon(Symbol.PROHIBITED),
        Menu.separator(),
        Menu.item("Refresh", lane == null ? null : () -> read(s)).icon(Symbol.ARROW_CLOCKWISE));
  }

  /** The locks' context menu, of the one wait selected. */
  private List<Menu.Item> lockItems(State s, List<Block> shown) {
    int row = lockSelection.row();
    var block =
        lockSelection.rowCount() == 1 && row >= 0 && row < shown.size() ? shown.get(row) : null;
    return List.of(
        Menu.item("Go to blocking session", block == null ? null : () -> goTo(s, block.blocking()))
            .icon(Symbol.ARROW_RIGHT),
        Menu.separator(),
        Menu.item("Refresh", lane == null ? null : () -> read(s)).icon(Symbol.ARROW_CLOCKWISE));
  }
}

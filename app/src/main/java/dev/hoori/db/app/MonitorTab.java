package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
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
import java.util.TreeSet;
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

  /**
   * Why the view could not be read, in words for the user.
   *
   * @param lasting whether it stays until the user acts, as a lost connection or a refusal, which
   *     a callout says; a passing one, where the view shows what it read before, its status line
   */
  record Problem(String title, String text, boolean lasting) {

    Problem(String title, String text) {
      this(title, text, false);
    }
  }

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
  final Refresh refresh;
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
  private final Map<View, Problem> problems = new EnumMap<>(View.class); // why a read failed
  Problem disconnected; // why the monitor's own connection is gone, or null
  String said; // what the tab says for a while in its status line; or null
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final DataGrid.Selection variableSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection statusSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection lockSelection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final Menu.State intervals = new Menu.State();
  final Menu.State menu = new Menu.State(); // the sessions' context menu
  final Menu.State lockMenu = new Menu.State(); // the locks' context menu
  Asked asked; // the question before an action, or null
  Lane.Asked acting; // the action under way, or null
  Action doing; // the action under way or waiting for the read under way, or null
  private Asked answered; // an answer that waits for the read under way, checked against it
  Problem refused; // why the last action failed, until the next or a dismiss; or null
  private String selected; // the id of the session selected, which a read keeps selected
  private String selectedStart; // and its start, null where not known yet, as from the locks
  private boolean went; // the selection came from the locks, so a read that misses it says so
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
    this.refresh = new Refresh(Refresh.Interval.of(state.settings.monitorInterval()));
  }

  /**
   * Opens the monitor's session where it has none and reads the view; nothing while the tree's
   * connection is not connected, which {@link #connected} follows. What was read on a session
   * before, which may be another server's, goes.
   */
  void open(State s) {
    open(s, true);
  }

  /** As {@link #open(State)}, quiet where the user did not ask, as when the tab is shown again. */
  private void open(State s, boolean byHand) {
    if (closed) {
      return;
    }
    if (lane == null) {
      var session = s.catalog.session(s, id, "");
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
      selectedStart = null;
      selection.clear();
      variableSelection.clear();
      statusSelection.clear();
      lockSelection.clear();
      problems.clear();
      disconnected = null;
      refused = null;
    }
    read(s, byHand);
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
   * the view repeats. One the user did not ask for, of a view read before or whose first read
   * failed, is quiet until it takes {@link #SLOW}; asked for by hand while one runs, that one shows.
   */
  private void read(State s, boolean byHand) {
    if (closed || lane == null) {
      return;
    }
    if (reading != null) {
      loud |= byHand;
      return;
    }
    due = false;
    refresh.stop();
    var live = s.catalog.live(id);
    var of = view;
    loud = byHand || !updated.containsKey(of) && !problems.containsKey(of);
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
          problems.remove(of);
          denied.remove(of);
          updated.put(of, Instant.now());
          switch ((Got) got) {
            case Sessions x -> show(s, x.sessions());
            case Variables x -> variables = x.variables();
            case Status x -> counters = x.counters();
            case Locks x -> blocks = x.blocks();
          }
          next(s);
        },
        error -> {
          reading = null;
          unanswered(s, "the sessions could not be read");
          if (error instanceof InterruptedException) {
            return; // its session ended under it
          }
          failed(s, error, live, of);
        });
  }

  // An answer that waited for the read is not sent where the read did not come, for the reason.
  private void unanswered(State s, String why) {
    if (answered != null) {
      say(s, answered.action().requested + " not sent: " + why);
      answered = null;
      doing = null;
    }
  }

  // Gives up the read under way, and an answer that waited for it, for the reason.
  private void abandon(State s, String why) {
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    unanswered(s, why);
  }

  /** Why the view cannot be read now: the monitor's connection gone, or its last read failed. */
  Problem problem() {
    return disconnected != null ? disconnected : problems.get(view);
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
    // Several sessions selected, none of which is acted on, are let go where any of them is gone.
    var several = rows(selection, sessions.size());
    if (several != null && several.size() > 1) {
      var was = several.stream().map(sessions::get).map(x -> x.id() + "\n" + x.started());
      var now = shown.stream().map(x -> x.id() + "\n" + x.started()).toList();
      if (!was.allMatch(now::contains)) {
        selection.clear();
      }
    }
    sessions = List.copyOf(shown);
    listed = true;
    // The same session, by its id and its start, as a server may give the id to another.
    int at = indexOf(selected);
    if (at >= 0 && selectedStart != null && !sessions.get(at).started().equals(selectedStart)) {
      at = -1;
    }
    if (at >= 0) {
      selectedStart = sessions.get(at).started();
      // The grid follows the selected row by its key; one gone to before it was read is put there.
      if (selection.isEmpty()) {
        selection.select(at, Math.max(0, selection.column()));
      }
    } else if (selected != null) {
      if (went) {
        say(s, "Session " + selected + " has ended or is not among the sessions this user can see");
      }
      selected = null;
      selectedStart = null;
      selection.clear();
    }
    went = false;
    if (asked != null) {
      var why = changed(asked);
      if (why != null) {
        say(s, why);
        asked = null;
      }
    }
    if (answered != null) {
      var what = answered;
      answered = null;
      var why = changed(what);
      if (why == null) {
        send(s, what);
      } else {
        doing = null;
        say(s, why);
      }
    }
  }

  /**
   * What makes the action asked about no longer the one the question named, as the sessions read
   * last show it: the session gone, or for a cancel another statement or none; null where none.
   * Statements are told apart by their digest too, as two may read alike once masked.
   */
  private String changed(Asked asked) {
    var was = asked.session();
    int at = indexOf(was.id());
    var now = at < 0 ? null : sessions.get(at);
    if (now == null || !now.started().equals(was.started())) {
      return "Session " + was.id() + " has ended";
    }
    if (asked.action() != Action.CANCEL) {
      return null;
    }
    if (!now.cancellable()) {
      return "Session " + was.id() + " runs nothing now";
    }
    if (!now.operation().equals(was.operation())
        || !now.statement().equals(was.statement())
        || !now.digest().equals(was.digest())) {
      return "Session " + was.id() + " runs something else now";
    }
    return null;
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

  private void failed(State s, Exception error, Live live, View of) {
    var detail = CatalogTree.words(live, detail(error));
    if (lost(error)) {
      closeSession(s);
      disconnected = new Problem(
          "Disconnected",
          "The monitor's connection was lost or could not be opened."
              + (detail.isEmpty() ? "" : "\n" + detail),
          true);
      return;
    }
    var problem = switch (error) {
      case QueryException q
      when q.failure() == QueryException.Failure.ACCESS_DENIED -> {
        denied.add(of);
        yield new Problem(
            "Access denied",
            "The server does not let this user see its " + of.what + "."
                + (detail.isEmpty() ? "" : "\n" + detail),
            true);
      }
      case QueryException q
      when q.failure() == QueryException.Failure.CANCELLED ->
        new Problem("Cancelled", "The server cancelled the read.");
      default -> new Problem("Could not be read", detail);
    };
    problems.put(of, problem);
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
    abandon(s, "the view changed");
    if (lane == null) {
      return;
    }
    // A view the server refused is read again only by hand.
    if (!denied.contains(next) && (repeats() || !updated.containsKey(next))) {
      read(s, false);
    }
  }

  /** Goes to the session in the sessions view, selected, as from the locks. */
  void goTo(State s, String session) {
    selected = session;
    went = true;
    int at = indexOf(session);
    selectedStart = at < 0 ? null : sessions.get(at).started();
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
      default -> State.messageOf(error);
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
   * The session an action acts on: the one the selection holds, by its id and start, also before
   * the grid moved the selection to where a read put it, in a list read on the monitor's session
   * now; null for none.
   */
  ServerSession chosen() {
    return listed && lane != null && selection.rowCount() == 1 ? selected() : null;
  }

  /**
   * Asks before the action on the session, and reads nothing at the interval meanwhile; nothing
   * where it cannot be done to it.
   */
  void ask(Action action, ServerSession session) {
    if (session != null && can(action, session) && lane != null && doing == null) {
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
   * The question was answered: the sessions are read, or the read under way is waited for, and
   * the action runs on the monitor's session only where they show the session as the question
   * named it; the status line says what came of it, and the list is read again.
   */
  void proceed(State s) {
    var what = asked;
    asked = null;
    if (what == null || lane == null || doing != null) {
      decline();
      return;
    }
    if (view != View.SESSIONS) {
      decline();
      return;
    }
    refused = null;
    // Sent only once a read of the sessions after the answer shows the session as asked about:
    // the one under way, or one started for it.
    doing = what.action();
    answered = what;
    read(s, false);
  }

  private void send(State s, Asked what) {
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
          doing = null;
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
          doing = null;
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
    return reading != null && loud || doing != null;
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
    abandon(s, "the monitor's connection closed");
    problems.clear();
    if (acting != null) {
      acting.cancel();
      acting = null;
    }
    doing = null;
    answered = null;
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

  // The rows that the selection holds, each once; null where one is not among the items.
  private static Set<Integer> rows(DataGrid.Selection chosen, int items) {
    var rows = new TreeSet<Integer>();
    for (var range : chosen.ranges()) {
      if (range.top() < 0 || range.bottom() >= items) {
        return null;
      }
      for (int row = range.top(); row <= range.bottom(); row++) {
        rows.add(row);
      }
    }
    return rows;
  }

  // The view

  /** The tab's content: its toolbar, why it cannot read, the view, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    if (due && shown(s)) {
      open(s, false); // shown again after a tick or a connect it missed
    }
    var profile = s.profile(id);
    String where = profile == null ? null : State.name(profile);
    boolean can = lane != null;
    // The view's switch at the start, so that it stays in place while the actions change by view.
    var actions = new ArrayList<El>();
    actions.add(Segmented.segmented(VIEWS, view, v -> v.title, v -> show(s, v))
        .key("view")
        .label("Show"));
    actions.add(Card.spacer());
    if (view == View.VARIABLES || view == View.STATUS) {
      var of = view;
      actions.add(TextField.withIcon(
              TextField.textField(search(of), "Search", v -> searches.put(of, v)), Symbol.SEARCH)
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
    // The view's name is on its switch: the toolbar names the connection.
    El top = Card.toolbar(where, null, actions.toArray(El[]::new));
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
    } else if (reading != null && problem() == null) {
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
    var problem = problem();
    if (problem != null && !problem.lasting() && at != null && lane != null) {
      // A read that failed where the view shows what it read before: the grid stays in place.
      El failed = row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(Icon.icon(Symbol.WARNING, Icon.Variant.WARNING))
          .child(Label.label(problem.title()));
      var last = "Last read " + TIME.text(LocalTime.ofInstant(at, s.zone));
      end.add(
          Tooltip.tooltip(failed, problem.text().isEmpty() ? last : problem.text() + "\n" + last)
              .key("read-problem"));
      // Off while a read shows that it runs; a quiet one it makes show.
      end.add(button(SECONDARY, "Try again", reading != null && loud ? null : () -> open(s))
          .key("try-again"));
    } else {
      // As wide as the time at its widest, so that what stands beside it stays as it changes.
      end.add(Label.label(
              Label.Variant.MUTED,
              at == null ? "" : "Updated " + TIME.text(LocalTime.ofInstant(at, s.zone)))
          .minSize(5 * t.size().control(), 0)
          .key("read-status"));
    }
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
    if (denied.contains(view)) {
      return "The server refused the " + view.what + ": they are read again by Refresh";
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
    return MonitorGrids.found(
        counters,
        search(View.STATUS),
        c -> c.name() + " " + c.value() + " " + MonitorGrids.grouped(c.value()));
  }

  private MonitorGrids.Widths widths(View of) {
    return widths.computeIfAbsent(of, v -> new MonitorGrids.Widths());
  }

  /** The view as read. */
  private El shown(State s, App<State> app) {
    var of = view;
    return switch (of) {
      case SESSIONS ->
        MonitorGrids.sessions(
            List.copyOf(sessions),
            widths(of),
            selection,
            () -> {
              // The one row the selection holds, which a read keeps by its id and start.
              var rows = rows(selection, sessions.size());
              var at = rows != null && rows.size() == 1
                  ? sessions.get(rows.iterator().next())
                  : null;
              selected = at == null ? null : at.id();
              selectedStart = at == null ? null : at.started();
              went = false;
            },
            menu,
            menuItems(s),
            app);
      case VARIABLES ->
        MonitorGrids.variables(shownVariables(), widths(of), variableSelection, app);
      case STATUS -> MonitorGrids.status(shownCounters(), widths(of), statusSelection, app);
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
    var problem = problem();
    if (problem == null || !problem.lasting() && updated.containsKey(view) && lane != null) {
      return null; // a passing failure shows in the status line, where the view has rows
    }
    return Callout.callout(
        Callout.Kind.DANGER,
        problem.title(),
        problem.text(),
        button(SECONDARY, "Try again", reading != null && loud ? null : () -> open(s)));
  }

  /**
   * The button of an action on the session chosen: off, and saying why, where it cannot be done
   * to it. It is neutral, as only the question's button warns.
   */
  private El action(Action action, Symbol icon) {
    var session = chosen();
    boolean can = session != null && can(action, session) && doing == null;
    El button = button(
        SECONDARY,
        action.title,
        can ? () -> ask(action, session) : null,
        Button.options()
            .icon(icon)
            .state(doing == action ? Button.State.BUSY : Button.State.READY));
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
    var session = doing != null ? null : chosen();
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
    var rows = rows(lockSelection, shown.size());
    var block = rows != null && rows.size() == 1 ? shown.get(rows.iterator().next()) : null;
    return List.of(
        Menu.item("Go to blocking session", block == null ? null : () -> goTo(s, block.blocking()))
            .icon(Symbol.ARROW_RIGHT),
        Menu.separator(),
        Menu.item("Refresh", lane == null ? null : () -> read(s)).icon(Symbol.ARROW_CLOCKWISE));
  }
}

package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a server does now, in a tab of its connection: the sessions of its clients, its variables,
 * its status counters and who blocks whom. They are read on a session and lane of the monitor's
 * own, never the tree's or a console's, so that a read never waits behind theirs; it opens once the
 * tree's connection is connected, and closing the tab ends it. The view shown is read again at the
 * interval chosen while the tab is shown, not while it is hidden or paused, and a tab shown again
 * reads at once; the variables, which seldom change, only when shown first and by Refresh. Only
 * the app's thread uses it.
 */
final class MonitorTab implements Tab {

  /** Why the view could not be read, in words for the user. */
  record Problem(String title, String text) {}

  /** What the tab shows of the server. */
  enum View {
    SESSIONS("Sessions", "sessions", true),
    VARIABLES("Variables", "variables", false),
    STATUS("Status", "status counters", true),
    LOCKS("Locks", "locks", true);

    final String title;
    final String what;
    final boolean repeats; // read again at the interval

    View(String title, String what, boolean repeats) {
      this.title = title;
      this.what = what;
      this.repeats = repeats;
    }
  }

  static final List<View> VIEWS = List.of(View.values());

  /** What a read of a view gave. */
  private sealed interface Got {}

  private record Sessions(List<ServerSession> sessions) implements Got {}

  private record Variables(List<ServerVariable> variables) implements Got {}

  private record Status(List<Counter> counters) implements Got {}

  private record Locks(Optional<List<Block>> blocks) implements Got {}

  /** What can be done to a session, after a question. */
  enum Action {
    CANCEL("Cancel statement", "Cancel the statement?"),
    END("End session", "End the session?");

    final String title;
    final String question;

    Action(String title, String question) {
      this.title = title;
      this.question = question;
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
  private final Map<View, Long> took = new EnumMap<>(View.class); // nanoseconds of the last read
  private final Map<View, String> searches = new EnumMap<>(View.class);
  Lane.Asked reading; // the read under way, or null
  Problem problem; // why the last read failed, or null
  String said; // what the tab says for a while in its status line; or null
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final DataGrid.Selection variableSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection statusSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final DataGrid.Selection lockSelection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final Menu.State intervals = new Menu.State();
  final Menu.State menu = new Menu.State(); // the grid's context menu
  Asked asked; // the question before an action, or null
  Lane.Asked acting; // the action under way, or null
  Problem refused; // why the last action failed, until the next or a dismiss; or null
  private String selected; // the id of the session selected, which a read keeps selected
  private int says;
  private Lane lane; // the monitor's own session, or null while it has none
  private char[] password; // a copy, for masking it where a statement holds it; wiped as it ends
  private boolean due; // a tick or a connect came while the tab was hidden: it reads once shown
  private boolean closed;

  private final State state; // for close, which its tab groups call without one

  MonitorTab(State state, UUID id) {
    this.state = state;
    this.id = id;
  }

  /**
   * Opens the monitor's session where it has none and reads the sessions; nothing while the tree's
   * connection is not connected, which {@link #connected} follows.
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
      // The variables, which are not read again by themselves, may be another server's now.
      variables = null;
      took.remove(View.VARIABLES);
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

  /**
   * Reads the view shown now, unless a read is under way; the next comes at the interval, where
   * the view repeats.
   */
  void read(State s) {
    if (closed || lane == null || reading != null) {
      return;
    }
    due = false;
    refresh.stop();
    var live = s.catalog.live(id);
    var of = view;
    record Read(Got got, long took) {}
    reading = lane.ask(
        s.app,
        "read the " + of.what,
        Lane.Priority.VIEW,
        link -> {
          long start = System.nanoTime();
          var monitor = link.monitor();
          Got got = switch (of) {
            case SESSIONS -> new Sessions(monitor.sessions());
            case VARIABLES -> new Variables(monitor.variables());
            case STATUS -> new Status(monitor.status());
            case LOCKS -> new Locks(monitor.locks());
          };
          return new Read(got, System.nanoTime() - start);
        },
        read -> {
          reading = null;
          problem = null;
          took.put(of, read.took());
          switch (read.got()) {
            case Sessions x -> show(x.sessions());
            case Variables x -> variables = x.variables();
            case Status x -> counters = x.counters();
            case Locks x -> blocks = x.blocks();
          }
          next(s);
        },
        error -> {
          reading = null;
          if (error instanceof InterruptedException) {
            return; // its session ended under it
          }
          failed(s, error, live);
        });
  }

  /** The sessions as read, the connection's password masked in them; the selection stays. */
  private void show(List<ServerSession> read) {
    var shown = new ArrayList<ServerSession>(read.size());
    for (var session : read) {
      shown.add(session.withStatement(Credentials.without(session.statement(), password)));
    }
    sessions = List.copyOf(shown);
    int at = indexOf(selected);
    if (at >= 0) {
      if (selection.row() != at) {
        selection.select(at, Math.max(0, selection.column()));
      }
    } else if (selected != null) {
      selected = null;
      selection.clear();
    }
  }

  private int indexOf(String session) {
    for (int i = 0; session != null && i < sessions.size(); i++) {
      if (sessions.get(i).id().equals(session)) {
        return i;
      }
    }
    return -1;
  }

  private void failed(State s, Exception error, CatalogTree.Live live) {
    var detail = CatalogTree.words(live, detail(error));
    boolean lost = error instanceof ConnectionException
        || error instanceof QueryException q
            && (q.failure() == QueryException.Failure.CONNECTION_LOST
                || q.failure() == QueryException.Failure.ABORTED);
    if (lost) {
      closeSession(s);
      problem = new Problem(
          "Disconnected",
          "The monitor's connection was lost or could not be opened."
              + (detail.isEmpty() ? "" : "\n" + detail));
      return;
    }
    problem = switch (error) {
      case QueryException q
      when q.failure() == QueryException.Failure.ACCESS_DENIED ->
        new Problem(
            "Access denied",
            "The server does not let this user see its " + view.what + "."
                + (detail.isEmpty() ? "" : "\n" + detail));
      case QueryException q
      when q.failure() == QueryException.Failure.CANCELLED ->
        new Problem("Cancelled", "The server cancelled the read.");
      default -> new Problem("Could not be read", detail);
    };
    next(s);
  }

  /** The next read at the interval, where the view repeats. */
  private void next(State s) {
    if (view.repeats) {
      refresh.next(s.app, () -> tick(s));
    }
  }

  /**
   * Shows the view, read at once where it repeats or was not read yet; what was read for another
   * is given up.
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
    problem = null;
    if (next.repeats || !took.containsKey(next)) {
      read(s);
    }
  }

  /** Goes to the session in the sessions view, selected, as from the locks. */
  void goTo(State s, String session) {
    selected = session;
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
      read(s);
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
      read(s);
    }
  }

  /** The session selected, as read last; null for none. */
  ServerSession selected() {
    int at = indexOf(selected);
    return at < 0 ? null : sessions.get(at);
  }

  /** Asks before the action on the session; nothing where it cannot be done to it. */
  void ask(Action action, ServerSession session) {
    if (session != null && can(action, session) && lane != null && acting == null) {
      asked = new Asked(action, session);
    }
  }

  private static boolean can(Action action, ServerSession session) {
    return switch (action) {
      case CANCEL -> session.cancellable();
      case END -> session.ends();
    };
  }

  /** The question was declined: nothing is done. */
  void decline() {
    asked = null;
  }

  /**
   * The question was answered: the action runs on the monitor's session, the status line says
   * what came of it, and the list is read again.
   */
  void proceed(State s) {
    var what = asked;
    asked = null;
    if (what == null || lane == null || acting != null) {
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
          say(
              s,
              !found
                  ? "Session " + session.id() + " had ended already"
                  : what.action() == Action.CANCEL
                      ? "Cancelled the statement of session " + session.id()
                      : "Ended session " + session.id());
          read(s);
        },
        error -> {
          acting = null;
          if (error instanceof InterruptedException) {
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
          read(s);
        });
  }

  /** Whether anything of the tab is read or done now. */
  boolean busy() {
    return reading != null || acting != null;
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
    var actions = new ArrayList<El>();
    actions.add(Segmented.segmented(VIEWS, view, v -> v.title, v -> show(s, v))
        .key("view")
        .label("Show"));
    if (view == View.VARIABLES || view == View.STATUS) {
      var of = view;
      actions.add(TextField.withIcon(
              TextField.textField(search(of), "Search", v -> searches.put(of, v)), Symbol.SEARCH)
          .key("search")
          .label("Search " + of.what)
          .w(8 * t.size().control()));
    }
    actions.add(button(
            SECONDARY,
            "Refresh",
            can ? () -> read(s) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(reading != null ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    if (view == View.SESSIONS) {
      var chosen = can ? selected() : null;
      actions.add(action(s, Action.CANCEL, Symbol.STOP, chosen).key("cancel-statement"));
      actions.add(action(s, Action.END, Symbol.PROHIBITED, chosen).key("end-session"));
    }
    // The view's name is on its switch: the toolbar names the connection.
    El top = Card.toolbar(where == null ? "" : where, null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    El note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note));
    }
    if (refused != null) {
      content.child(col()
          .padding(t.space().m())
          .shrink(0)
          .child(Callout.callout(
                  Callout.Kind.DANGER,
                  refused.title(),
                  refused.text(),
                  button(SECONDARY, "Dismiss", () -> refused = null))
              .key("refused")));
    }
    long nanos = took.getOrDefault(view, -1L);
    if (nanos < 0 && reading != null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the " + view.what)
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the " + view.what + "…")));
    } else if (nanos >= 0) {
      content.child(shown(s, app));
    } else {
      content.child(col().grow(1));
    }
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    end.add(
        reading != null
            ? row()
                .gap(t.space().s())
                .align(Style.Align.CENTER)
                .child(Progress.spinner().label("Reading"))
                .child(Label.label(Label.Variant.MUTED, "Reading…"))
                .key("read-status")
            : Label.label(
                    Label.Variant.MUTED,
                    nanos < 0 ? "" : "Read in " + Math.round(nanos / 1e6) + " ms")
                .key("read-status"));
    end.add(Label.label(Label.Variant.MUTED, schedule()).key("schedule"));
    if (view.repeats) {
      end.add(Select.select(
              Refresh.INTERVALS,
              refresh.interval,
              i -> "Every " + i.title(),
              intervals,
              i -> every(s, i))
          .key("interval")
          .label("Refresh interval")
          .w(4 * t.size().control()));
      end.add(Button.toggleIcon(Symbol.PAUSE, "Pause", refresh.paused(), pause -> pause(s, pause))
          .key("pause"));
    }
    El count = Label.label(Label.Variant.MUTED, count()).key("count").ellipsis();
    return content.child(Card.footer(count, end.toArray(El[]::new)));
  }

  /** When the view is read again. */
  private String schedule() {
    if (!view.repeats) {
      return "By Refresh";
    }
    return refresh.paused() ? "Paused" : "";
  }

  /** How many the view shows, as "4 sessions" or "12 of 312 variables". */
  private String count() {
    if (!took.containsKey(view)) {
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

  /** The view as read. */
  private El shown(State s, App<State> app) {
    return switch (view) {
      case SESSIONS ->
        MonitorGrids.sessions(
            List.copyOf(sessions),
            selection,
            () -> {
              int row = selection.row();
              selected = row >= 0 && row < sessions.size() ? sessions.get(row).id() : null;
            },
            menu,
            menuItems(s),
            app);
      case VARIABLES -> MonitorGrids.variables(shownVariables(), variableSelection, app);
      case STATUS -> MonitorGrids.status(shownCounters(), statusSelection, app);
      case LOCKS -> {
        if (blocks.isEmpty() || blocks.get().isEmpty()) {
          yield col()
              .grow(1)
              .center()
              .child(Label.label(
                      Label.Variant.MUTED,
                      blocks.isEmpty()
                          ? "The server does not tell who holds a lock."
                          : "No session waits for a lock.")
                  .key("no-locks"));
        }
        var shown = blocks.get();
        yield MonitorGrids.locks(
            shown, lockSelection, row -> goTo(s, shown.get(row).blocking()), app);
      }
    };
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(State s) {
    if (!s.catalog.connected(id)) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      String text = lost != null ? CatalogTree.why(lost) : "Its connection is closed.";
      if (took.containsKey(view)) {
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
   * The button of an action on the session selected: off, and saying why, where it cannot be done
   * to it.
   */
  private El action(State s, Action action, Symbol icon, ServerSession session) {
    boolean can = session != null && can(action, session) && acting == null;
    El button = button(
        SECONDARY,
        action.title,
        can ? () -> ask(action, session) : null,
        Button.options()
            .icon(icon)
            .tone(Button.Tone.DANGER)
            .state(acting != null ? Button.State.BUSY : Button.State.READY));
    var why = why(action, session);
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
        who + "." + "\n\nThe server closes its connection and rolls back what it did not commit.";
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

  /** The grid's context menu, of the session selected. */
  private List<Menu.Item> menuItems(State s) {
    var session = lane == null || acting != null ? null : selected();
    return List.of(
        Menu.item(
                Action.CANCEL.title + "…",
                session != null && session.cancellable() ? () -> ask(Action.CANCEL, session) : null)
            .icon(Symbol.STOP),
        Menu.item(
                Action.END.title + "…",
                session != null && session.ends() ? () -> ask(Action.END, session) : null)
            .icon(Symbol.PROHIBITED),
        Menu.separator(),
        Menu.item("Refresh", lane == null ? null : () -> read(s)).icon(Symbol.ARROW_CLOCKWISE));
  }
}

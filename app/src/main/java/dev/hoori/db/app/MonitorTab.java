package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.monitor.Credentials;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.query.QueryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a server does now, in a tab of its connection: the sessions of its clients. They are read
 * on a session and lane of the monitor's own, never the tree's or a console's, so that a read
 * never waits behind theirs; it opens once the tree's connection is connected, and closing the tab
 * ends it. The list is read again at the interval chosen while the tab is shown, not while it is
 * hidden or paused; a tab shown again reads at once. Only the app's thread uses it.
 */
final class MonitorTab implements Tab {

  /** Why the sessions could not be read, in words for the user. */
  record Problem(String title, String text) {}

  final UUID id;
  final Refresh refresh = new Refresh();
  List<ServerSession> sessions = List.of();
  Lane.Asked reading; // the read under way, or null
  Problem problem; // why the last read failed, or null
  long took = -1; // nanoseconds the last read took, -1 before the first
  String said; // what the tab says for a while in its status line; or null
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  final Menu.State intervals = new Menu.State();
  private String selected; // the id of the session selected, which a read keeps selected
  private int says;
  private Lane lane; // the monitor's own session, or null while it has none
  private char[] password; // a copy, for masking it where a statement holds it; wiped as it ends
  private boolean due; // a tick came while the tab was hidden: it reads once shown
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
    }
    read(s);
  }

  /** The tree's connection connected: the monitor opens its session and reads. */
  void connected(State s) {
    open(s);
  }

  /** Reads the sessions now, unless a read is under way; the next comes at the interval. */
  void read(State s) {
    if (closed || lane == null || reading != null) {
      return;
    }
    due = false;
    refresh.stop();
    var live = s.catalog.live(id);
    record Read(List<ServerSession> sessions, long took) {}
    reading = lane.ask(
        s.app,
        "read the sessions",
        Lane.Priority.VIEW,
        link -> {
          long start = System.nanoTime();
          var read = link.monitor().sessions();
          return new Read(read, System.nanoTime() - start);
        },
        read -> {
          reading = null;
          problem = null;
          took = read.took();
          show(read.sessions());
          refresh.next(s.app, () -> tick(s));
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
        new Problem("Access denied", "The server does not let this user see its sessions.");
      case QueryException q
      when q.failure() == QueryException.Failure.CANCELLED ->
        new Problem("Cancelled", "The server cancelled the read.");
      default -> new Problem("Could not be read", detail);
    };
    refresh.next(s.app, () -> tick(s));
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

  /** Whether a tick came while the tab was hidden, so that it reads once shown. */
  boolean due() {
    return due;
  }

  /** Reads again at this interval, counted from now. */
  void every(State s, Refresh.Interval interval) {
    refresh.interval = interval;
    if (!refresh.paused() && reading == null && lane != null) {
      refresh.next(s.app, () -> tick(s));
    }
  }

  /** Pauses the reading at the interval, or reads at once and goes on with it. */
  void pause(State s, boolean pause) {
    refresh.pause(pause);
    if (!pause) {
      read(s);
    }
  }

  /** Whether anything of the tab is read now. */
  boolean busy() {
    return reading != null;
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

  /** The tab's content: its toolbar, why it cannot read, the sessions, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    if (due && shown(s)) {
      read(s); // shown again after a tick it missed
    }
    var profile = s.profile(id);
    String where = profile == null ? null : State.name(profile);
    boolean can = lane != null;
    var actions = new ArrayList<El>();
    actions.add(Select.select(
            Refresh.INTERVALS,
            refresh.interval,
            i -> "Every " + i.title(),
            intervals,
            i -> every(s, i))
        .key("interval")
        .label("Refresh interval"));
    actions.add(Button.toggle(Symbol.PAUSE, "Pause", refresh.paused(), pause -> pause(s, pause))
        .key("pause"));
    actions.add(button(
            SECONDARY,
            "Refresh",
            can ? () -> read(s) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(reading != null ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    El top = Card.toolbar("Sessions", where, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    El note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note));
    }
    if (took < 0 && reading != null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the sessions")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the sessions…")));
    } else if (took >= 0) {
      content.child(grid(app));
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
                    took < 0 ? "" : "Read in " + Math.round(took / 1e6) + " ms")
                .key("read-status"));
    end.add(Label.label(
            Label.Variant.MUTED, refresh.paused() ? "Paused" : "Every " + refresh.interval.title())
        .key("schedule"));
    El count = Label.label(Label.Variant.MUTED, count()).key("count").ellipsis();
    return content.child(Card.footer(count, end.toArray(El[]::new)));
  }

  private String count() {
    if (took < 0) {
      return "";
    }
    int n = sessions.size();
    return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? "session" : "sessions");
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(State s) {
    if (!s.catalog.connected(id)) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      String text = lost != null ? CatalogTree.why(lost) : "Its connection is closed.";
      if (took >= 0) {
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

  /** The sessions, a row each; the monitor's own marked. */
  private El grid(App<State> app) {
    var shown = List.copyOf(sessions);
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column("ID", 4, DataGrid.DataType.INTEGER, shown, ServerSession::id)
        .cellIcon(row -> shown.get(row).own()
            ? DataGrid.CellIcon.before(Symbol.PERSON, Icon.Variant.ACCENT)
                .tooltip("The monitor's own session")
            : null));
    columns.add(column("User", 5, DataGrid.DataType.TEXT, shown, ServerSession::user));
    columns.add(column("Client", 8, DataGrid.DataType.TEXT, shown, ServerSession::client));
    columns.add(column("Database", 5, DataGrid.DataType.TEXT, shown, ServerSession::database));
    columns.add(column("State", 6, DataGrid.DataType.TEXT, shown, ServerSession::state));
    columns.add(column("Time", 4, DataGrid.DataType.INTERVAL, shown, x -> elapsed(x.elapsed())));
    columns.add(column("Waiting for", 6, DataGrid.DataType.TEXT, shown, ServerSession::waiting));
    columns.add(column("Statement", 20, DataGrid.DataType.TEXT, shown, ServerSession::statement));
    var options = DataGrid.options()
        .select(selection)
        .onSelect(() -> {
          int row = selection.row();
          selected = row >= 0 && row < shown.size() ? shown.get(row).id() : null;
        })
        .copy(app.clipboard());
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("sessions")
        .label("Sessions")
        .grow(1)
        .defaultFocus();
  }

  private static DataGrid.Column column(
      String title,
      float width,
      DataGrid.DataType type,
      List<ServerSession> shown,
      Function<ServerSession, String> text) {
    Theme t = Theme.CURRENT.get();
    return new DataGrid.Column(
            title, width * t.size().control(), type, row -> text.apply(shown.get(row)))
        .sortable(false);
  }

  /** How long, as "850 ms", "12 s", "4 min 3 s" or "2 h 5 min"; empty where not known. */
  static String elapsed(Duration elapsed) {
    if (elapsed == null) {
      return "";
    }
    long ms = elapsed.toMillis();
    if (ms < 1000) {
      return ms + " ms";
    }
    long seconds = elapsed.toSeconds();
    if (seconds < 60) {
      return seconds + " s";
    }
    if (seconds < 3600) {
      return seconds / 60 + " min " + seconds % 60 + " s";
    }
    long hours = seconds / 3600;
    return hours < 24
        ? hours + " h " + seconds % 3600 / 60 + " min"
        : hours / 24 + " d " + hours % 24 + " h";
  }
}

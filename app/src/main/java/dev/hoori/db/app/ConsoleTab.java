package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Decorator;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.sql.Statement;
import dev.hoori.db.core.sql.Statements;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * A console: SQL written in an editor, run on a connection of the console's own, and what it gave
 * below. The connection is opened from the tree's once the console first runs something, with the
 * password and driver the tree got, and serves the console alone, one call at a time on its lane:
 * a long statement holds up neither the tree nor a table tab. Auto-commit only. Rows are read a
 * portion at a time, the next as the grid nears the end of those read. Only the app's thread uses
 * it.
 */
final class ConsoleTab implements Tab {

  /** Rows read at a time, as the first rows of a result and then as the grid scrolls on. */
  static final int PORTION = 200;

  /** How much of a statement a line of Messages shows. */
  static final int SHORT = 80;

  static final Split.Position SPLIT = Split.Position.share(0.45f);

  /** A result of a run, in a tab of its own below the editor: rows, or a count of changed rows. */
  static final class Output {
    final int number; // counted on through the console's runs, so that a pinned one keeps its own
    final String statement;
    final List<Field> fields; // null for a count
    final long count; // rows changed, for a count
    // shortcut: every row scrolled to stays in memory; drop far portions, as TableTab drops pages,
    // once results of millions of rows are scrolled through.
    final List<List<Object>> rows = new ArrayList<>();
    boolean more; // its statement has more rows of it, read as the grid scrolls on
    boolean cut; // rows were left unread, as the session went on to something else
    boolean pinned; // the next run leaves it
    float[] widths;
    final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);

    Output(int number, String statement, List<Field> fields, long count) {
      this.number = number;
      this.statement = statement;
      this.fields = fields;
      this.count = count;
      if (fields != null) {
        widths = new float[fields.size()];
        Arrays.fill(widths, -1);
      }
    }
  }

  /** What a line of Messages is. */
  enum Level {
    RAN,
    WARNING,
    ERROR
  }

  /** A line of Messages; took is null where it says nothing of time. */
  record Logged(Level level, String statement, Duration took, String text) {}

  /** What the results area says went wrong, the server's words checked for the password. */
  record Problem(String title, String text) {}

  final UUID id;
  // shortcut: a USE the user runs is not followed, so this may name another database than the
  // session's; ask the session for its database after each run once that misleads.
  String database; // the one it runs on: the profile's, or the one chosen; empty for none
  String text = "";
  final CodeEditor.State editor = new CodeEditor.State();
  Split.Position split = SPLIT;
  final Menu.State databases = new Menu.State();
  private Lane lane; // the session, or null until it is needed
  private char[] password; // its copy, which checks what the server says; wiped with the session
  // The statement that may still have the session, from when it is asked to run until a portion
  // says it is over: set on the lane, so that it is closed also where its portion never arrives.
  private AtomicReference<Servers.Run> open;
  private Output reading; // the result whose rows it has
  private final ArrayDeque<String> queue = new ArrayDeque<>(); // the run's statements still to run
  private Work work; // the statement, the rows or the switch under way, or null
  private Future<?> call; // its call on the lane
  private String running; // the statement under way, or the one whose rows are read
  boolean waiting; // for the tree's connection, which the session is opened from
  boolean stranded; // the tree's connection failed while it waited: Try again goes on
  String switching; // the database it switches to, while it does; the old one shows meanwhile
  private Statements scanned; // the statements of the text it was made from, as the caret moves
  private String scannedText;
  final List<Output> outputs = new ArrayList<>(); // the pinned results first
  Output shown; // the results tab shown; null for Messages
  final List<Logged> log = new ArrayList<>();
  final DataGrid.Selection logSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  Problem problem; // of the last run, or null
  private int results; // numbered so far
  private int firstOfRun = 1; // the number of the last run's first result
  private int statements; // of the last run that ran
  private Duration took; // the last run's time on the server, null before the first
  private boolean cancelled; // the last run was cancelled
  private boolean closed;
  String said; // what the tab says for a while in its status line, or null
  private int says;

  ConsoleTab(UUID id, String database) {
    this.id = id;
    this.database = database;
  }

  // Handlers

  /**
   * Control+Enter and Run: the selection's statements, or the statement at the caret alone, also
   * where the caret is not at its end.
   */
  void run(State s) {
    var provider = provider(s);
    if (provider == null) {
      return;
    }
    int from = Math.min(editor.caret(), editor.anchor());
    int to = Math.max(editor.caret(), editor.anchor());
    if (from < to) {
      start(s, texts(text.substring(from, Math.min(to, text.length())), provider));
    } else {
      start(
          s,
          statements(provider)
              .at(editor.caret())
              .map(at -> List.of(text.substring(at.start(), at.end())))
              .orElse(List.of()));
    }
  }

  /** Control+Shift+Enter and Run all: every statement, one after another, to the first error. */
  void runAll(State s) {
    var provider = provider(s);
    if (provider != null) {
      start(
          s,
          statements(provider).statements().stream()
              .map(at -> text.substring(at.start(), at.end()))
              .toList());
    }
  }

  private static List<String> texts(String text, Provider provider) {
    return Statements.split(text, provider).stream()
        .map(at -> text.substring(at.start(), at.end()))
        .toList();
  }

  private Provider provider(State s) {
    var profile = s.profile(id);
    return profile == null ? null : profile.provider();
  }

  private void start(State s, List<String> sql) {
    if (busy()) {
      return;
    }
    if (sql.isEmpty()) {
      say(s, "No statement to run");
      return;
    }
    // A session open goes on, also after the tree lost its connection.
    if (lane == null && !s.catalogue.connected(id) && !s.catalogue.active(id)) {
      s.catalogue.connect(s, id); // as the tree connects: the driver, the password, the server
    }
    stranded = false;
    outputs.removeIf(o -> !o.pinned);
    if (!outputs.contains(shown)) {
      shown = null;
    }
    problem = null;
    cancelled = false;
    statements = 0;
    took = Duration.ZERO;
    firstOfRun = results + 1;
    queue.clear();
    queue.addAll(sql);
    follow(s);
  }

  /** Runs on where it can, waits for the tree's connection where it connects, else is stranded. */
  private void follow(State s) {
    if (lane != null || s.catalogue.connected(id)) {
      next(s);
    } else if (s.catalogue.active(id)) {
      waiting = true;
    } else {
      stranded = true;
    }
  }

  /** The tree's connection connected: what waited for it runs; what was stranded is let go. */
  void connected(State s) {
    stranded = false;
    if (waiting) {
      waiting = false;
      next(s);
    } else {
      queue.clear();
    }
  }

  /** The tree's connection failed while the console waited for it: it says so, with Try again. */
  void unconnected() {
    if (waiting) {
      waiting = false;
      stranded = true;
    }
  }

  /** Try again, or Enter password…: connects the tree's connection again, and goes on. */
  void retry(State s, boolean password) {
    stranded = false;
    var live = s.catalogue.live(id);
    if (password && live != null) {
      s.catalogue.askPassword(live);
    } else {
      s.catalogue.connect(s, id);
    }
    follow(s);
  }

  /** Runs the next statement of the run, on the session; its rows before are left as read. */
  private void next(State s) {
    var sql = queue.poll();
    if (sql == null) {
      return;
    }
    var lane = session(s);
    if (lane == null) {
      queue.clear();
      return;
    }
    leave();
    running = sql;
    var held = new AtomicReference<Servers.Run>();
    open = held;
    var asked = lane.submit(link -> {
      var run = link.run(sql);
      held.set(run);
      return run.next(PORTION);
    });
    call = asked;
    work = s.app.background(
        "run a statement",
        () -> Lane.await(asked),
        portion -> {
          work = null;
          ran(s, sql, portion);
        },
        error -> {
          work = null;
          failed(s, sql, error);
        });
  }

  /** The session, opened now where it is not; null while the tree's connection is not connected. */
  private Lane session(State s) {
    if (lane == null) {
      var session = s.catalogue.session(id, database);
      if (session == null) {
        return null;
      }
      lane = session.lane();
      password = session.password();
    }
    return lane;
  }

  /**
   * The statement whose rows went on ends, on the session before what comes next; the rows read of
   * it stay.
   */
  private void leave() {
    if (open != null) {
      var held = open;
      // Behind the call that ran it, whether that ended, was cancelled, or was never delivered.
      lane.submit(link -> {
        var run = held.getAndSet(null);
        if (run != null) {
          run.close();
        }
        return null;
      });
      open = null;
    }
    if (reading != null && reading.more) {
      reading.more = false;
      reading.cut = true;
    }
    reading = null;
  }

  private void ran(State s, String sql, Portion portion) {
    statements++;
    took = took.plus(portion.elapsed());
    var before = outputs.size();
    var failure = failure(portion);
    take(sql, portion);
    if (before < outputs.size()) {
      shown = outputs.get(before);
    }
    heard(failure == null ? Level.RAN : Level.ERROR, sql, portion.elapsed(), outcome(portion));
    messages(portion);
    if (failure != null) {
      queue.clear();
      fail(failure);
      if (before == outputs.size()) {
        shown = null; // nothing but the error: Messages says what ran
      }
      return;
    }
    next(s);
  }

  /** Puts the portion's results in tabs: rows from a position on go on with the result before. */
  private void take(String sql, Portion portion) {
    for (var result : portion.results()) {
      switch (result) {
        case Result.Rows r when r.from() > 0 && reading != null -> reading.rows.addAll(r.rows());
        case Result.Rows r -> {
          finished();
          var output = new Output(++results, sql, r.fields(), 0);
          output.rows.addAll(r.rows());
          outputs.add(output);
          reading = output;
        }
        case Result.Count c -> {
          finished();
          outputs.add(new Output(++results, sql, null, c.count()));
        }
        case Failure f -> {}
      }
    }
    if (portion.more() && reading != null) {
      reading.more = true;
    } else {
      finished();
      open = null; // over: nothing is left to close
    }
  }

  /** The result whose rows it read has ended, as another came after it or the statement ended. */
  private void finished() {
    if (reading != null) {
      reading.more = false;
      reading = null;
    }
  }

  private void messages(Portion portion) {
    for (var m : portion.messages()) {
      var code = !m.state().isEmpty() ? m.state() : m.code() != 0 ? String.valueOf(m.code()) : "";
      heard(Level.WARNING, null, null, m.text() + (code.isEmpty() ? "" : " (" + code + ")"));
    }
  }

  /**
   * A line of Messages with what the server said: the one place where its words go into
   * Messages, checked whole for the password.
   */
  private void heard(Level level, String sql, Duration took, String said) {
    log.add(new Logged(level, sql == null ? "" : brief(sql), took, words(said)));
  }

  /** What the results area says went wrong, the server's part checked whole for the password. */
  private Problem heard(String title, String said) {
    return new Problem(title, words(said));
  }

  private static Failure failure(Portion portion) {
    for (var result : portion.results()) {
      if (result instanceof Failure f) {
        return f;
      }
    }
    return null;
  }

  /** The server's failure: the results area says it, and a lost session is opened anew next. */
  private void fail(Failure f) {
    var text = new StringBuilder(f.message());
    if (!f.detail().isEmpty()) {
      text.append('\n').append(f.detail());
    }
    if (!f.hint().isEmpty()) {
      text.append("\nHint: ").append(f.hint());
    }
    problem = heard(title(f.kind()), text.toString());
    if (f.kind() == Failure.Kind.CONNECTION_LOST) {
      closeSession();
    }
  }

  private static String title(Failure.Kind kind) {
    return switch (kind) {
      case INVALID -> "The statement is not valid";
      case CONSTRAINT -> "A constraint was violated";
      case ACCESS_DENIED -> "Access denied";
      case CONNECTION_LOST -> "Disconnected";
      case CANCELLED -> "Cancelled";
      case TIMEOUT -> "Timed out";
      case OTHER -> "The statement failed";
    };
  }

  /** A call failed rather than gave a result: the session could not be opened, or it closed. */
  private void failed(State s, String sql, Exception error) {
    queue.clear();
    if (closed || error instanceof InterruptedException) {
      return; // the session closed under it, which says why where it says anything
    }
    var profile = s.profile(id);
    if (error instanceof ConnectionException e && profile != null) {
      var on = database.isEmpty() ? profile : profile.withDatabase(database);
      var note = Words.note(e.failure(), on, true);
      var detail = e.detail();
      problem = heard(
          Words.title(e.failure()),
          note == null ? detail : detail.isEmpty() ? note : note + "\n" + detail);
    } else {
      problem = heard("The statement could not be run", State.message(error));
    }
    log.add(new Logged(Level.ERROR, brief(sql), null, problem.title()));
    closeSession();
  }

  /**
   * The grid built these rows of the result: near the end of those read, the next portion is read,
   * where its statement has more.
   */
  void want(State s, Output output, int first, int last) {
    if (output != reading || !output.more || work != null || last < output.rows.size() - 50) {
      return;
    }
    var held = open;
    var sql = running;
    var asked = lane.submit(link -> held.get().next(PORTION)); // leave() clears it only after this
    call = asked;
    work = s.app.background(
        "read rows",
        () -> Lane.await(asked),
        portion -> {
          work = null;
          int before = outputs.size();
          take(sql, portion);
          messages(portion);
          var failure = failure(portion);
          if (failure != null) {
            heard(Level.ERROR, sql, portion.elapsed(), outcome(portion));
            fail(failure);
          } else if (before < outputs.size()) {
            // Results after the rows: what the statement gave next.
            heard(Level.RAN, sql, portion.elapsed(), outcome(portion));
          }
        },
        error -> {
          work = null;
          failed(s, sql, error);
        });
  }

  /** Cancel: what runs is cancelled on the server; the rows read stay, and nothing more runs. */
  void cancel(State s) {
    if (waiting) {
      waiting = false;
      queue.clear();
      say(s, "Not run");
      return;
    }
    if (work == null) {
      return;
    }
    stop();
    queue.clear();
    leave(); // the statement may have finished its portion, which then never arrives
    if (switching != null) {
      // Whether a switch cut short took effect cannot be known: the next run connects anew, to the
      // database the tab names.
      switching = null;
      closeSession();
    }
    cancelled = true;
    log.add(new Logged(Level.WARNING, brief(running), null, "Cancelled"));
  }

  /**
   * Switches the database: on the open connection where the server can, else the session closes
   * and the next run opens one to it.
   */
  void use(State s, String name) {
    if (busy() || name.equals(database)) {
      return;
    }
    var profile = s.profile(id);
    var version = s.catalogue.version(id);
    if (lane == null || profile == null) {
      database = name; // the next session opens on it
      return;
    }
    if (version == null
        || profile.provider().supports(Capability.CONNECTION_PER_DATABASE, version)) {
      closeSession();
      database = name;
      return;
    }
    leave();
    switching = name;
    running = "Database " + name;
    var asked = lane.submit(link -> {
      link.use(name);
      return name;
    });
    call = asked;
    work = s.app.background(
        "switch the database",
        () -> Lane.await(asked),
        done -> {
          work = null;
          switching = null;
          database = done;
          log.add(new Logged(Level.RAN, "Database " + done, null, "Switched"));
        },
        error -> {
          work = null;
          switching = null;
          failed(s, "Database " + name, error);
        });
  }

  /**
   * Gives up the work and its call on the lane, which core turns into a cancel on the server where
   * it runs. The call is cancelled itself, as a work cancelled before it began to wait for it would
   * leave it to run.
   */
  private void stop() {
    work.cancel();
    work = null;
    call.cancel(true);
  }

  /** The tree's connection was disconnected: what the console began ends with it. */
  void end() {
    if (work != null) {
      stop();
    }
    queue.clear();
    waiting = false;
    stranded = false;
    switching = null;
    closeSession();
  }

  private void closeSession() {
    open = null; // closed with the connection
    if (reading != null && reading.more) {
      reading.more = false;
      reading.cut = true;
    }
    reading = null;
    if (lane != null) {
      lane.close(); // interrupts what runs, which core cancels, and closes the connection
      lane = null;
    }
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    end();
  }

  /** Whether something runs or waits to. */
  boolean busy() {
    return work != null || waiting;
  }

  /** Whether the session is open. */
  boolean connected() {
    return lane != null;
  }

  /** Says something in the tab's status line, for a while. */
  void say(State s, String text) {
    said = text;
    int told = ++says;
    s.app.after(State.MESSAGE_SECONDS, () -> {
      if (says == told) {
        said = null;
      }
    });
  }

  /** The server's words, withheld where they hold the password. */
  private String words(String words) {
    return password == null || Probe.contains(words, password)
        ? ConnectionException.WITHHELD
        : words;
  }

  /** A statement on one line, cut short. */
  static String brief(String sql) {
    var line = sql.strip().replaceAll("\\s+", " ");
    return line.length() <= SHORT ? line : line.substring(0, SHORT - 1) + "…";
  }

  private String outcome(Portion portion) {
    var parts = new ArrayList<String>();
    for (var result : portion.results()) {
      switch (result) {
        case Result.Rows r -> parts.add(rows(r.rows().size()) + (portion.more() ? " so far" : ""));
        case Result.Count c -> parts.add(rows(c.count()) + " changed");
        case Failure f -> parts.add(f.kind() == Failure.Kind.CANCELLED ? "Cancelled" : f.message());
      }
    }
    return parts.isEmpty() ? "Done" : String.join(", ", parts);
  }

  static String rows(long n) {
    return String.format(Locale.ROOT, "%,d", n) + (n == 1 ? " row" : " rows");
  }

  // The view

  /** The statement Control+Enter would run, or null where it would run the selection or none. */
  private Statement target(Provider provider) {
    if (editor.caret() != editor.anchor()) {
      return null;
    }
    return statements(provider).at(editor.caret()).orElse(null);
  }

  /** The text's statements, scanned again only when the text changed. */
  private Statements statements(Provider provider) {
    if (scanned == null || scannedText != text) { // each edit hands over a new string
      scanned = Statements.of(text, provider);
      scannedText = text;
    }
    return scanned;
  }

  /** The tab's content: its toolbar, the editor over the results, and its status line. */
  El view(Theme t, State s, App<State> app) {
    var profile = s.profile(id);
    var provider = profile.provider();
    boolean busy = busy();
    El top = Card.toolbar(
        t,
        State.name(profile),
        provider.displayName(),
        databases(t, s),
        button(
                t,
                PRIMARY,
                "Run",
                () -> run(s),
                Button.options().icon(Symbol.PLAY).busy(busy))
            .key("run")
            .keepFocus(),
        button(t, SECONDARY, "Run all", busy ? null : () -> runAll(s))
            .key("run-all")
            .keepFocus(),
        button(t, SECONDARY, "Cancel", busy ? () -> cancel(s) : null)
            .key("cancel")
            .keepFocus());
    El line = Colours.line(t, s.colour(id));
    El editor = editor(t, s, app, provider);
    El split = Split.split(
            t,
            Split.Direction.COLUMN,
            this.split,
            SPLIT,
            p -> this.split = p,
            editor,
            3 * t.size().control(),
            results(t, s, app),
            4 * t.size().control())
        .grow(1);
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(top)
        .child(line == null ? Divider.divider(t) : line)
        .child(split)
        .child(footer(t, s));
  }

  /** The databases to switch to: those the tree read, and the one it is on. */
  private El databases(Theme t, State s) {
    var names = new LinkedHashSet<String>();
    names.add(database);
    names.addAll(s.catalogue.databases(id));
    return Select.select(
            t,
            List.copyOf(names),
            database,
            n -> n.isEmpty() ? "No database" : n,
            databases,
            n -> use(s, n))
        .key("database")
        .label("Database");
  }

  private El editor(Theme t, State s, App<State> app, Provider provider) {
    var dialect = provider == Provider.POSTGRESQL ? Sql.Dialect.POSTGRES : Sql.Dialect.MYSQL;
    El editor = CodeEditor.codeEditor(t, app, text, this.editor, Sql.of(t, dialect), v -> text = v)
        .key("sql")
        .label("SQL")
        .autofocus(this)
        .wFull()
        .grow(1);
    Predicate<Key> keys = key -> {
      if (!key.name().equals("Enter")) {
        return false;
      }
      if (key.modifiers() == Key.CONTROL) {
        run(s);
        return true;
      }
      if (key.modifiers() == (Key.CONTROL | Key.SHIFT)) {
        runAll(s);
        return true;
      }
      return false;
    };
    editor.onKey(editor.onKey() == null ? keys : keys.or(editor.onKey()));
    // The lines of the statement Control+Enter runs, as the find bar marks the lines it keeps to.
    var at = target(provider);
    Decorator own = editor.decorator();
    int mark = t.code().line();
    return editor.decorate((index, start, line, out) -> {
      if (own != null) {
        own.line(index, start, line, out);
      }
      if (at != null && start <= at.end() && start + line.length() >= at.start()) {
        out.lineBackground(mark);
      }
    });
  }

  /** The results of the last runs in tabs, with Messages; what went wrong above them. */
  private El results(Theme t, State s, App<State> app) {
    El note = note(t, s);
    if (outputs.isEmpty() && log.isEmpty() && busy()) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .label("Results")
          .child(Progress.spinner(t))
          .child(Label.label(t, Label.Variant.MUTED, waiting ? "Connecting…" : "Running…"));
    }
    if (outputs.isEmpty() && log.isEmpty()) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .label("Results")
          .child(note == null ? null : col().padding(t.space().m()).shrink(0).child(note))
          .child(EmptyState.emptyState(
              t,
              Symbol.PLAY,
              "Nothing run yet",
              "Control+Enter runs the statement at the caret, Control+Shift+Enter all of them.",
              null));
    }
    var tabs = new ArrayList<Tabs.Tab>();
    for (var o : outputs) {
      var icon = o.pinned ? Symbol.PIN : o.fields == null ? Symbol.CHECKMARK_CIRCLE : Symbol.TABLE;
      tabs.add(new Tabs.Tab("Result " + o.number, false, false, icon));
    }
    tabs.add(new Tabs.Tab("Messages", false, false, Symbol.TEXT_BULLET_LIST_LTR));
    int at = shown == null ? -1 : outputs.indexOf(shown);
    El content = at < 0 ? messages(t) : output(t, s, app, shown);
    El bar = Tabs.tabs(
            t,
            tabs,
            at < 0 ? outputs.size() : at,
            i -> shown = i < outputs.size() ? outputs.get(i) : null,
            i -> {},
            content)
        .key("results")
        .grow(1);
    return col()
        .grow(1)
        .minSize(0, 0)
        .label("Results")
        .child(note == null ? null : col().padding(t.space().m()).shrink(0).child(note))
        .child(bar);
  }

  /** Why nothing runs or what failed, with what the user can do; null for nothing. */
  private El note(Theme t, State s) {
    if (stranded) {
      var trouble = s.catalogue.problem(s, new Node.Connection(id));
      var actions = new ArrayList<El>();
      if (trouble != null && trouble.password()) {
        actions.add(button(t, SECONDARY, "Enter password…", () -> retry(s, true)));
      }
      actions.add(button(t, SECONDARY, "Try again", () -> retry(s, false)).key("try-again"));
      return Callout.callout(
              t,
              Callout.Kind.DANGER,
              trouble == null ? "Not connected" : trouble.title(),
              trouble == null ? null : trouble.text(),
              actions.toArray(El[]::new))
          .key("unconnected");
    }
    if (problem != null) {
      return Callout.callout(t, Callout.Kind.DANGER, problem.title(), problem.text())
          .key("problem");
    }
    return null;
  }

  /** A result: its rows in the grid, or the number of rows it changed; whether it is pinned. */
  private El output(Theme t, State s, App<State> app, Output o) {
    El pin = Button.toggleIcon(
            t, Symbol.PIN, o.pinned ? "Unpin" : "Pin", o.pinned, on -> o.pinned = on)
        .key("pin");
    if (o.fields == null) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .child(EmptyState.emptyState(
                  t, Symbol.CHECKMARK_CIRCLE, rows(o.count) + " changed", null, null)
              .key("changed"))
          .child(Card.footer(
              t, Label.label(t, Label.Variant.MUTED, brief(o.statement)).ellipsis(), pin));
    }
    String count = rows(o.rows.size());
    String about = brief(o.statement) + " · "
        + (o.more
            ? count + " so far, more as it scrolls"
            : o.cut
                ? "the first " + count + ", the rest left unread as the console went on"
                : count);
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(grid(t, s, app, o).grow(1))
        .child(Card.footer(
            t, Label.label(t, Label.Variant.MUTED, about).key("result-status").ellipsis(), pin));
  }

  /** The result's rows, read-only, with the table tab's cells. */
  private El grid(Theme t, State s, App<State> app, Output o) {
    var columns = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < o.fields.size(); c++) {
      var field = o.fields.get(c);
      var kind = field.kind();
      int at = c;
      if (o.widths[c] < 0) {
        o.widths[c] = Cells.width(kind) * t.size().control();
      }
      var column = new DataGrid.Column(
              field.name(),
              o.widths[c],
              Cells.type(kind),
              row -> Cells.text(o.rows.get(row).get(at)))
          .typeName(field.type())
          .sortable(false);
      if (kind == Kind.TEXT || kind == Kind.OTHER) {
        column = column.cellIcon(row -> Cells.icon(o.rows.get(row).get(at)));
      }
      columns.add(column);
    }
    var options = DataGrid.options()
        .select(o.selection)
        .onResize((c, w) -> o.widths[c] = w)
        .rowNumbers()
        .copy(app.clipboard())
        .loading(row -> row < o.rows.size(), (first, last) -> want(s, o, first, last));
    int shown = (int) Math.min(o.rows.size() + (o.more ? 1L : 0L), TableTab.GRID_ROWS);
    return DataGrid.dataGrid(t, columns, shown, options)
        .key("grid")
        .label("Rows of result " + o.number);
  }

  /** What ran, how long it took, what it gave, and what the server said along the way. */
  private El messages(Theme t) {
    float unit = t.size().control();
    var columns = List.of(
        new DataGrid.Column(
                "Statement",
                16 * unit,
                DataGrid.DataType.TEXT,
                row -> log.get(row).statement())
            .sortable(false),
        new DataGrid.Column("Took", 4 * unit, DataGrid.DataType.INTEGER, row -> {
              var took = log.get(row).took();
              return took == null ? "" : took.toMillis() + " ms";
            })
            .sortable(false),
        new DataGrid.Column(
                "Result", 20 * unit, DataGrid.DataType.TEXT, row -> log.get(row).text())
            .sortable(false)
            .cellIcon(row -> switch (log.get(row).level()) {
              case RAN -> null;
              case WARNING -> CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING);
              case ERROR -> CellIcon.before(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
            }));
    return DataGrid.dataGrid(
            t, columns, log.size(), DataGrid.options().select(logSelection).rowNumbers())
        .key("messages")
        .label("Messages")
        .grow(1);
  }

  /** The last run's time and rows, or that it runs. */
  private El footer(Theme t, State s) {
    El status;
    if (busy()) {
      var what = waiting
          ? "Connecting…"
          : switching != null
              ? "Switching to " + (switching.isEmpty() ? "no database" : switching) + "…"
              : "Running…";
      status = row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(Progress.spinner(t).label("Running"))
          .child(Label.label(t, Label.Variant.MUTED, what))
          .key("run-status");
    } else {
      status = Label.label(t, Label.Variant.MUTED, status()).key("run-status").ellipsis();
    }
    return said == null
        ? Card.footer(t, status)
        : Card.footer(t, status, Label.label(t, Label.Variant.MUTED, said).key("said"));
  }

  /** "Ran in 12 ms · 3 rows": the last run's time on the server, and its rows. */
  String status() {
    if (took == null || statements == 0 && !cancelled && problem == null) {
      return connected() ? "Connected" : "";
    }
    if (statements == 0 && !cancelled) {
      return "Not run"; // the session could not be opened
    }
    var ms = took.toMillis() + " ms";
    var text = new StringBuilder();
    if (cancelled) {
      text.append("Cancelled");
    } else if (problem != null && statements == 1) {
      text.append("Failed after ").append(ms);
    } else {
      text.append("Ran");
      if (statements > 1) {
        text.append(' ').append(statements).append(" statements");
      }
      text.append(" in ").append(ms).append(problem != null ? ", the last failed" : "");
    }
    long read = 0;
    long changed = 0;
    boolean rows = false;
    boolean counts = false;
    boolean more = false;
    for (var o : outputs) {
      if (o.number < firstOfRun) {
        continue; // pinned, of a run before
      }
      if (o.fields == null) {
        counts = true;
        changed += o.count;
      } else {
        rows = true;
        read += o.rows.size();
        more |= o.more;
      }
    }
    if (rows) {
      text.append(" · ").append(rows(read)).append(more ? " so far" : "");
    }
    if (counts) {
      text.append(" · ").append(rows(changed)).append(" changed");
    }
    return text.toString();
  }
}

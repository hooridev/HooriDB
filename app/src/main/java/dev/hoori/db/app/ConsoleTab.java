package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static dev.hoori.db.app.Session.failure;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.sql.Sql;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Status;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.EveryRow;
import dev.hoori.db.core.sql.Statement;
import dev.hoori.db.core.sql.Statements;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A console: SQL written in an editor, run on a connection of the console's own, and what it gave
 * below. The connection is opened from the tree's once the console first runs something, with the
 * password and driver the tree got, and serves the console alone, one call at a time on its lane:
 * a long statement holds up neither the tree nor a table tab. Rows are read a portion at a time,
 * the next as the grid nears the end of those read. Only the app's thread uses it.
 *
 * <p>In {@link Mode#AUTO} each statement commits by itself, in {@link Mode#MANUAL} the user ends
 * the transaction with Commit or Rollback, which run as the statements {@code COMMIT} and {@code
 * ROLLBACK} and so also end one that a {@code BEGIN} began in Auto. The console holds to these:
 *
 * <ul>
 *   <li>What is at stake is one test, {@link #atStake}: an outcome in doubt until read; a call
 *       that may change data or the transaction for its whole life; a question to the server; a
 *       transaction the server did not say is closed, with a change in it. Only a plain read
 *       ({@link #reads}, a short allowlist) in Auto with nothing open is read as the grid scrolls,
 *       and is none; every other statement, and every one in Manual or in a transaction, is read
 *       to its end in its one call, so that none is ever left open inside a transaction.
 *   <li>Each call gives up first what is still open on the session and reads its status fresh
 *       ({@link Session#after}, {@link Session#start}); nothing the app's thread believed
 *       authorizes it. The mode is verified, or the statement is not sent: by the server's word on
 *       MariaDB and MySQL, pgjdbc's on PostgreSQL. Auto-commit changes only in {@link
 *       Session#setMode}, where the server has just said that nothing is open; the mode shown
 *       follows the session's. A driver that cannot say makes Manual unavailable.
 *   <li>Committed is said only where the server answered so, after it said a transaction was
 *       open; Rolled back only where nothing of the session is in doubt. What may have changed
 *       something and was cut short or lost is in doubt, said until read.
 *   <li>Commit is offered only where the server said a transaction is open and nothing runs;
 *       Rollback, the safe way out, wherever something may be open and no statement that may
 *       change something runs; neither twice at once.
 *   <li>Every act that would end the session or its transaction asks first, through {@link
 *       State#asks}; one act waits at most, and once armed it locks the consoles it concerns.
 *       Every statement and portion goes to the session through one gate ({@link #gate}).
 * </ul>
 */
final class ConsoleTab implements Tab {

  /** Rows read at a time, as the first rows of a result and then as the grid scrolls on. */
  static final int PORTION = 200;

  /** How much of a statement a line of Messages shows. */
  static final int SHORT = 80;

  static final Split.Position FIRST_SPLIT = Split.Position.share(0.45f);

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
    long dropped; // rows beyond those kept, read to the statement's end and not kept
    boolean pinned; // the next run leaves it
    float[] widths;
    final DataGrid.Fit fit = new DataGrid.Fit(); // once, in the first frame with rows
    final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
    // Of a server of documents, the documents of the rows, whole, and how they show.
    final List<Document> documents = new ArrayList<>();
    TableTab.Shown shown = TableTab.Shown.GRID;
    int page; // of the documents shown whole, as many as a portion
    final DocumentTree whole = new DocumentTree();

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

  /** Whether each statement commits by itself, or the user ends the transaction. */
  enum Mode {
    AUTO("Auto"),
    MANUAL("Manual");

    final String title;

    Mode(String title) {
      this.title = title;
    }

    /** The mode a session's auto-commit makes: on, Auto. */
    static Mode of(boolean autoCommit) {
      return autoCommit ? AUTO : MANUAL;
    }
  }

  /** A question to the server about the transaction: on which session, and after what was known. */
  record Check(Lane lane, int at) {}

  /**
   * What is asked before a statement is sent: a read of more rows than the console keeps, or a
   * change of every row, which is a danger.
   */
  record Confirm(String sql, String title, String text, boolean danger) {}

  static final String UNVERIFIABLE =
      "The driver of this connection does not report the server's auto-commit, so Manual cannot"
          + " be made sure of: choose a newer driver version in the connection's Driver field.";

  // shortcut: a statement not on the read list is read to its end, of which this many rows are
  // kept to show; show its first rows as they come once long ones of them are common.
  static final int KEPT = 100_000;

  static final String COMMIT = "COMMIT";
  static final String ROLLBACK = "ROLLBACK";

  // What the console says in more than one place.
  static final String NOT_RUN = "Not run";
  static final String CANCELLED_TEXT = "Cancelled";
  static final String DISCONNECTED = "Disconnected";
  static final String CHANGED = " changed";
  static final String AUTO_COMMIT = "Auto-commit";
  static final String ROLLBACK_BUTTON = "Rollback";
  static final String CANCEL_BUTTON = "Cancel";
  static final String ROLL_BACK_FIRST = "Roll back the transaction?";

  final UUID id;
  // The one it runs on: the tree node's, the profile's, the one chosen, or else the one database of
  // the user's the tree read, until the session opens; empty for none.
  // shortcut: a USE the user runs is not followed, so this may name another database than the
  // session's; ask the session for its database after each run once that misleads.
  String database;
  String text = "";
  final CodeEditor.State editor = new CodeEditor.State();
  final ConsoleEditor help = new ConsoleEditor(this); // the error's place, completions
  private final ResultsArea area = new ResultsArea(this);
  private final ConsoleView ui = new ConsoleView(this);
  Split.Position split = FIRST_SPLIT;
  private Lane lane; // the session, or null until it is needed
  // Tells the app what finishing something on the session did, from the thread that sees it.
  private Teller teller;
  private char[] password; // its copy, which checks what the server says; wiped with the session
  LoadedDriver driver; // the session's, from when it is opened until it is closed
  // The statement that may still have the session, from when it is asked to run until a portion
  // says it is over: set on the lane, so that it is closed also where its portion never arrives.
  private AtomicReference<Servers.Run> open;
  // The statement under way, set on the lane once it is about to be sent: null, it never was.
  private AtomicReference<Servers.Run> sent = new AtomicReference<>();
  private Output reading; // the result whose rows it has
  private final ArrayDeque<String> queue = new ArrayDeque<>(); // the run's statements still to run
  Asking work; // the statement, the rows or the switch under way, or null
  String running; // the statement under way, or the one whose rows are read
  // The shell's statement under way as it was read once, on taking it from the queue; null for SQL.
  private Script.Parsed parsed;
  boolean waiting; // for the tree's connection, which the session is opened from
  private boolean chosen; // the user chose the database: none is taken for them
  boolean stranded; // the tree's connection failed while it waited: Try again goes on
  String switching; // the database it switches to, while it does; the old one shows meanwhile
  private Statements scanned; // the statements of the text it was made from, as the caret moves
  private String scannedText;
  private Provider scannedProvider; // whose rules split it: the connection may change
  final List<Output> outputs = new ArrayList<>(); // the pinned results first
  Output shown; // the results tab shown; null for Messages
  final List<Logged> log = new ArrayList<>();
  final ValuePanel value = new ValuePanel(); // of the result shown
  Problem problem; // of the last run, or null
  private int results; // numbered so far
  private int firstOfRun = 1; // the number of the last run's first result
  private int statements; // of the last run that ran
  private Duration took; // the last run's time on the server, null before the first
  private boolean cancelled; // the last run was cancelled
  private boolean failedRun; // a statement of the last run failed, or could not be run
  private boolean closed;
  // The statement held before it is sent until the user says whether it runs, or null.
  Confirm confirming;
  String said; // what the tab says for a while in its status line, or null
  private int says;
  // Auto-commit as the session last had it from the server, or what the next session takes.
  Mode mode = Mode.AUTO;
  // As the server last said, of the session; NONE without one. UNKNOWN: one may be open.
  Transaction transaction = Transaction.NONE;
  int inTransaction; // the user's statements run in it
  // What is open is the server's own, begun as the user's Commit or Rollback ended theirs, as with
  // MySQL's completion_type=1: no statement ran in it, and it holds nothing.
  boolean empty;
  boolean firm; // the call is Commit, Rollback or a switch of auto-commit: none stops it
  // The call under way may change data or the transaction: a statement not on the read list, read
  // to its end, or Commit, Rollback, a switch of auto-commit or of the database. Cut short or
  // lost, what it did is not known.
  boolean writes;
  boolean overdue; // the Commit, Rollback or switch under way has not answered for long
  private int firms; // counted, so that a late patience is dropped
  // The last Commit or Rollback failed, the connection still there: ending it is a way out.
  boolean endFailed;
  // The call is a plain read ({@link #reads}): read whole in a transaction, and at stake while it
  // runs there, but what it did, cut short, is the transaction's, which the server says.
  private boolean plain;
  String unavailable; // why Manual is not to be had on this session, or null
  // The session has the console's mode, as a statement sent on it or a switch confirmed: from then
  // on the mode shown follows the session's, before it the user's choice stands.
  private boolean applied;
  private String openSql; // the read whose rest is still to be read, or given up
  // Why the outcome of what may have ended the transaction is not known, until the user has read
  // it; null for nothing in doubt.
  String doubt;
  private Asking probe; // the question to the server about the transaction, or null
  // The calls whose outcomes have not reached the console yet, given up when the session ends.
  private final List<Delivery> outstanding = new ArrayList<>();
  Check probing; // which one it is
  // The probe asked before a statement sent since: its answer cannot say that what is open holds
  // nothing, as the statement may have run in it.
  private Check overtaken;
  private int learned; // what was learned of it, counted: an answer that comes late is dropped
  // The user's statement sent and not yet ended, for the history; null for none.
  private Sent recording;
  private HistoryEntry recorded; // the last statement that went into the history, or null
  HistoryDialog history; // the History dialog, null while closed
  QueriesDialog queries; // Save query or Saved queries, null while closed

  /** A statement of the user's on its way to the server, and since when. */
  private record Sent(String sql, Instant started) {}

  ConsoleTab(UUID id, String database) {
    this.id = id;
    this.database = database;
  }

  /** The console as it is kept until the next start. */
  StoredConsole stored() {
    return new StoredConsole(id, database, text);
  }

  /**
   * The statement sent ended so: it goes into the history, with the time on the server where the
   * server said it, else the time until now.
   */
  private void remember(State s, Outcome outcome, Duration took) {
    var entry = recording;
    if (entry == null) {
      return;
    }
    recording = null;
    var time = took != null ? took : Duration.between(entry.started(), Instant.now());
    recorded = new HistoryEntry(
        id,
        entry.sql(),
        entry.started(),
        time.truncatedTo(ChronoUnit.MILLIS), // as it is kept
        outcome);
    s.ran(recorded);
  }

  /** How the first part of a statement's outcome ended, its failure first. */
  private Outcome outcomeOf(Portion portion) {
    var failure = failure(portion);
    if (failure != null) {
      return failure.kind() == Failure.Kind.CANCELLED
          ? new Outcome.Cancelled()
          : new Outcome.Failed(words(failure.message()));
    }
    for (var result : portion.results()) {
      if (result instanceof Result.Rows) {
        return new Outcome.Rows();
      }
      if (result instanceof Result.Count(var count)) {
        return new Outcome.Changed(count);
      }
    }
    return new Outcome.Changed(0);
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
      var selected = text.substring(from, Math.min(to, text.length()));
      start(s, from, Statements.split(selected, provider));
    } else {
      start(s, 0, statements(provider).at(editor.caret()).map(List::of).orElse(List.of()));
    }
  }

  /** Control+Shift+Enter and Run all: every statement, one after another, to the first error. */
  void runAll(State s) {
    var provider = provider(s);
    if (provider != null) {
      start(s, 0, statements(provider).statements());
    }
  }

  private Provider provider(State s) {
    var profile = s.profile(id);
    return profile == null ? null : profile.provider();
  }

  boolean keepsDocuments(State s) {
    var provider = provider(s);
    return provider != null && provider.keepsDocuments();
  }

  /** The statements, with offsets from {@code from} in the text, run once the console takes them. */
  private void start(State s, int from, List<Statement> chosen) {
    s.acted();
    if (busy() || locked(s) != null) {
      return; // refused: what the run under way sent stays its own
    }
    if (chosen.isEmpty()) {
      say(s, "No statement to run");
      return;
    }
    var sql = help.sent(text, from, chosen); // where an error of each is, from now on
    // A session open goes on, also after the tree lost its connection.
    if (lane == null && !s.catalog.connected(id) && !s.catalog.active(id)) {
      s.catalog.connect(s, id); // as the tree connects: the driver, the password, the server
      askHere(s);
    }
    stranded = false;
    outputs.removeIf(o -> !o.pinned);
    if (!outputs.contains(shown)) {
      shown = null;
    }
    problem = null;
    cancelled = false;
    failedRun = false;
    statements = 0;
    took = Duration.ZERO;
    firstOfRun = results + 1;
    queue.clear();
    queue.addAll(sql);
    follow(s);
  }

  /** Runs on where it can, waits for the tree's connection where it connects, else is stranded. */
  private void follow(State s) {
    if (lane != null || s.catalog.connected(id) && !picks(s)) {
      next(s);
    } else if (s.catalog.active(id)) {
      waiting = true;
    } else {
      stranded = true;
    }
  }

  /** The tree's connection connected: what waited for it runs; what was stranded is let go. */
  void connected(State s) {
    stranded = false;
    if (waiting) {
      if (picks(s)) {
        return; // the databases are read right behind the connection
      }
      waiting = false;
      next(s);
    } else {
      queue.clear();
    }
  }

  /** The tree read the connection's databases, or could not: what waited for them goes on. */
  void databasesRead(State s) {
    take(s);
    if (waiting) {
      waiting = false;
      follow(s);
    }
  }

  /** Takes the one database of the user's the tree read, while no session or choice says else. */
  void take(State s) {
    if (lane == null && database.isEmpty() && !chosen) {
      database = s.catalog.onlyDatabase(id);
      if (!database.isEmpty()) {
        s.edited(); // kept, so that it opens there next time too
      }
    }
  }

  /** Whether the session waits for the tree's databases, to open on the one there is. */
  private boolean picks(State s) {
    return lane == null && database.isEmpty() && !chosen && s.catalog.readsDatabases(id);
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
    var live = s.catalog.live(id);
    if (password && live != null) {
      s.catalog.askPassword(s, live);
    } else {
      s.catalog.connect(s, id);
    }
    askHere(s);
    follow(s);
  }

  /** What the tree's connection asks for, its password, it asks in this console's window. */
  private void askHere(State s) {
    var live = s.catalog.live(id);
    if (live != null) {
      live.window = s.tabs.groupOf(this);
    }
  }

  /**
   * Runs the next statement of the run, on the session; its rows before are left as read. An UPDATE
   * or DELETE of every row, and a read the console would read whole of more rows than it keeps,
   * wait for the user's answer first ({@link #confirming}).
   */
  private void next(State s) {
    var sql = queue.poll();
    if (sql == null || !gate(s, sql)) {
      return;
    }
    if (session(s) == null) {
      queue.clear();
      return;
    }
    var provider = s.profile(id).provider();
    parsed = provider.keepsDocuments() ? Script.Parsed.of(sql) : null;
    var every = parsed != null ? parsed.every() : EveryRow.of(sql, provider);
    if (every.isPresent()) {
      running = sql;
      confirming = everyRow(sql, every.get(), parsed != null);
    } else {
      send(s, sql, true);
    }
  }

  /** The question before a statement that would change every row, or document, it reaches. */
  private static Confirm everyRow(String sql, EveryRow every, boolean documents) {
    var verb = every.verb().equals("DELETE") ? "delete" : "update";
    var row = documents ? "document" : "row";
    var unbounded = documents ? " has an empty filter" : " has no WHERE clause";
    var its = documents ? "its collection" : "its table";
    var table = every.table();
    var of = table.isEmpty() ? its : table;
    return new Confirm(
        sql,
        (verb.equals("delete") ? "Delete" : "Update") + " every " + row + "?",
        every.certain()
            ? brief(sql) + unbounded + ": it would " + verb + " every " + row + " of " + of + "."
            : "Could not tell whether " + brief(sql) + " is limited by a WHERE clause; it may "
                + verb + " every row of " + of + ".",
        true);
  }

  /**
   * Sends the statement on the session. A plain read that the session reads whole is first
   * estimated, unless {@code estimate} is off as the user said to run it anyway.
   */
  private void send(State s, String sql, boolean estimate) {
    leave();
    running = sql;
    var provider = s.profile(id).provider();
    if (provider.keepsDocuments()) {
      // A server of documents has no transactions, and no Manual: also where the console was in
      // Manual on a connection of SQL before its connection changed.
      mode = Mode.AUTO;
    }
    // Only a plain read in Auto with nothing open may stay open between calls; the lane call
    // decides that from the server's word, and all else is read to its end, at stake meanwhile.
    var statement = provider.keepsDocuments() ? parsed : null;
    plain = statement != null ? statement.readsCursor() : reads(sql, provider);
    boolean lazy = plain && mode == Mode.AUTO && transaction == Transaction.NONE;
    writes = !lazy;
    // Not for a read Auto keeps open: no connection is opened for an estimate never asked for.
    var planner = estimate && plain && !lazy && !provider.keepsDocuments()
        ? s.catalog.planner(id, database)
        : null;
    var on = database;
    // Whatever it is, what it leaves open is at stake: a read may write through a function or a
    // trigger, or hold locks on purpose, which no first word tells.
    boolean wasEmpty = empty;
    empty = false;
    overtaken = probing;
    var held = new AtomicReference<Servers.Run>();
    sent = held;
    var asked = mode;
    recording = new Sent(sql, Instant.now());
    work = ask(
        s,
        "run a statement",
        link -> Session.start(
            link, new Session.Send(sql, statement, asked, lazy, KEPT, planner, on), held),
        ran -> {
          work = null;
          ran(s, sql, ran);
        },
        error -> {
          work = null;
          sendFailed(s, sql, wasEmpty, error);
        });
    open = held; // after ask, which gives up the one open before
    openSql = sql;
  }

  /**
   * The statement's call failed, or did not send it: a read estimated too long is asked about,
   * one not run says why, and any other failure is said and goes into the history.
   */
  private void sendFailed(State s, String sql, boolean wasEmpty, Exception error) {
    if (error instanceof Session.TooMany e) {
      recording = null; // not sent: it is asked about first
      knew(e.status);
      empty |= wasEmpty; // nothing was sent
      confirming = new Confirm(
          sql,
          "Read about " + rows(e.rows) + "?",
          brief(sql) + " may give about " + rows(e.rows) + ", as its plan estimates. In"
              + " Manual, and while a transaction may be open, the console reads a result"
              + " whole: it would read all of them from the server and keep the first "
              + rows(KEPT) + ".",
          false);
      return;
    }
    failedRun = true;
    if (error instanceof Session.NotRun e) {
      recording = null;
      notRun(s, sql, e);
      return;
    }
    failed(s, sql, error);
    if (error instanceof InterruptedException || closed) {
      remember(s, new Outcome.Cancelled(), null);
    } else if (error instanceof Session.GaveUp) {
      recording = null; // not sent
    } else {
      remember(
          s, new Outcome.Failed(problem.text().isEmpty() ? problem.title() : problem.text()), null);
    }
  }

  /**
   * The one gate before the user's work goes to the session: a statement, a first one that waited
   * for its connection, the next of a Run all, or, with a null {@code sql}, a further portion of
   * rows. Not while an act holds its connection or a question about this console waits. A
   * statement held back is refused, and the run stops there; a portion is read once the grid asks
   * again.
   */
  private boolean gate(State s, String sql) {
    var why = s.locked(this);
    var pending = s.pending;
    if (why == null && pending != null && !pending.armed() && pending.console() == this) {
      why = "A question about this console waits for its answer";
    }
    if (why == null) {
      return true;
    }
    if (sql == null) {
      return false;
    }
    queue.clear();
    failedRun = true;
    problem = new Problem(NOT_RUN, why + ": nothing more runs until that is done.");
    log.add(new Logged(Level.ERROR, brief(sql), null, NOT_RUN));
    return false;
  }

  /** The session, opened now where it is not; null while the tree's connection is not connected. */
  private Lane session(State s) {
    if (lane == null) {
      take(s);
      var session = s.catalog.session(id, database);
      if (session == null) {
        return null;
      }
      lane = session.lane();
      driver = session.driver();
      password = session.password();
      teller = new Teller(
          s.app, s, State.name(s.profile(id)), password == null ? null : password.clone(), this);
    }
    return lane;
  }

  /**
   * The one way the console's calls reach the session: in the same lane call, the read still open
   * on it is given up first, and what that failed with is said; then the call, which reads the
   * session's status itself, as nothing the app's thread believed authorizes anything on it. Once
   * its result or failure is taken, the question sees whether what it waits for may go on.
   */
  private <T> Asking ask(
      State s, String what, Lane.Call<T> call, Consumer<T> done, Consumer<Exception> failed) {
    return ask(s, what, running, call, done, failed);
  }

  /** As {@link #ask}, its outcome named in a notice by {@code sql}; null for none. */
  private <T> Asking ask(
      State s,
      String what,
      String sql,
      Lane.Call<T> call,
      Consumer<T> done,
      Consumer<Exception> failed) {
    var leftSql = openSql;
    open = null; // given up on the lane, also where this call never starts
    leave(); // and its rows end where they are
    var tell = teller;
    return through(s, what, sql, link -> Session.after(link, tell, leftSql, call), done, failed);
  }

  /**
   * The one way the console's calls reach the lane. Each call's outcome either reaches the console
   * or, once the console no longer waits for it, the app's notices: never neither ({@link
   * Delivery}). Once the outcome is taken, the question sees whether what it waits for may go on.
   */
  private <T> Asking through(
      State s,
      String what,
      String sql,
      Lane.Call<T> call,
      Consumer<T> done,
      Consumer<Exception> failed) {
    var delivery = new Delivery(teller, sql, plain);
    outstanding.add(delivery);
    var asked = lane.ask(
        s.app,
        what,
        Lane.Priority.VIEW,
        link -> {
          T result;
          try {
            result = call.call(link);
          } catch (Exception e) {
            delivery.done(e);
            throw e;
          }
          delivery.done(result);
          return result;
        },
        result -> {
          outstanding.remove(delivery);
          if (delivery.deliver()) {
            done.accept(result);
            settle(s);
          }
        },
        error -> {
          outstanding.remove(delivery);
          if (delivery.deliver()) {
            failed.accept(error);
            settle(s);
          }
        });
    return new Asking(asked, delivery);
  }

  /** A call asked for through {@link #through}: giving it up gives up its outcome's delivery. */
  record Asking(Lane.Asked asked, Delivery delivery) {
    void cancel() {
      asked.cancel(); // a call that waits never begins; one that runs is cancelled on the server
      delivery.giveUp();
    }
  }

  /** For a test: whether an outcome is there that the console has not taken. */
  boolean outcomeWaiting() {
    return outstanding.stream().anyMatch(Delivery::waiting);
  }

  static final String SESSION_ENDED = "As its session ended, giving up its rest failed: ";

  // First words of statements that only read, the same on every provider; anything else may change
  // data or the transaction, by itself, inside or with an implicit commit, and is treated as such.
  // SELECT reads, but for what a function it calls does, which no word tells, and INTO, below.
  // SHOW reads settings and the catalog, on PostgreSQL, MariaDB and MySQL alike. VALUES and
  // TABLE are a SELECT's own forms. EXPLAIN is not on it: its options, ANALYZE however spelled,
  // run what it explains; nor DESCRIBE and DESC, the same statement on MariaDB and MySQL.
  private static final Set<String> READS = Set.of("SELECT", "SHOW", "VALUES", "TABLE");
  // A word that makes a read write: SELECT … INTO makes a table on PostgreSQL and sets variables or
  // writes a file on MariaDB and MySQL. A keyword cannot be quoted, so no spelling hides it.
  private static final Pattern WRITING = Pattern.compile("\\bINTO\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Whether the statement is a plain read: its first word is on the read list, and no word of it
   * makes it write. Only ever conservative: a statement taken for a change that is none costs a
   * needless caution.
   */
  static boolean reads(String sql, Provider provider) {
    var word = Statements.firstWord(sql, provider);
    // ASCII's case alone, as the servers fold keywords: ſELECT is no SELECT.
    word = word.chars().allMatch(c -> c < 0x80) ? word.toUpperCase(Locale.ROOT) : "";
    // shortcut: INTO in a literal or comment makes a read count as a change, a needless caution.
    return READS.contains(word) && !WRITING.matcher(sql).find();
  }

  // The database that the shell's use switched the session to, or null for any other statement.
  private String used(State s, String sql, Portion portion) {
    var profile = s.profile(id);
    if (profile == null
        || !profile.provider().keepsDocuments()
        || failure(portion) != null
        || parsed == null
        || !parsed.statement().equals(sql)) {
      return null;
    }
    return parsed.use();
  }

  // A statement that ends the transaction when it ends well, as Commit and Rollback do; a ROLLBACK
  // TO a savepoint does not.
  private static final Pattern ENDS = Pattern.compile(
      "\\s*(COMMIT|END|ABORT|ROLLBACK)\\b(?!.*\\bTO\\b).*",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /**
   * Whether the statement, typed, ends the transaction as Commit and Rollback do, so that one the
   * server begins after it, as with chained completion, holds nothing.
   */
  static boolean ends(String sql) {
    return ENDS.matcher(sql).matches();
  }

  /**
   * The read whose rows went on stops showing more; the next call on the session gives it up
   * ({@link #ask}). The rows read of it stay.
   */
  private void leave() {
    if (reading != null && reading.more) {
      reading.more = false;
      reading.cut = true;
    }
    reading = null;
  }

  private void ran(State s, String sql, Session.Ran ran) {
    var portion = ran.portion();
    var used = used(s, sql, portion);
    if (used != null) {
      database = used; // the shell's use: the console runs on there from now on
      s.edited();
    }
    var failure = failure(portion);
    remember(s, outcomeOf(portion), portion.elapsed());
    applied = true; // it was sent, after the session's mode was made sure of
    statements++;
    took = took.plus(portion.elapsed());
    var before = outputs.size();
    learn(portion, ran.lazy());
    follow(portion.autoCommit());
    if (changes() && failure == null && ends(sql)) {
      empty = true; // what is open after a typed COMMIT or ROLLBACK, the server began by itself
    }
    inTransaction = transaction == Transaction.NONE ? 0 : inTransaction + 1;
    take(sql, portion, ran.dropped());
    if (before < outputs.size()) {
      shown = outputs.get(before);
    }
    heard(failure == null ? Level.RAN : Level.ERROR, sql, portion.elapsed(), outcome(portion));
    messages(portion);
    if (incomplete(portion)) {
      partly(s, sql);
      log.add(new Logged(Level.ERROR, brief(sql), null, PARTLY));
    }
    if (failure != null) {
      queue.clear();
      failedRun = true;
      if (changes() && failure.kind() == Failure.Kind.CONNECTION_LOST) {
        doubt(sql, "the connection was lost while it ran");
      }
      help.failed(sql, failure, provider(s), words(failure.message()));
      fail(s, failure);
      if (before == outputs.size()) {
        shown = null; // nothing but the error: Messages says what ran
      }
    }
    if (transaction == Transaction.UNKNOWN && !portion.more() && lane != null) {
      check(s);
    }
    next(s);
  }

  /** A statement not sent: the console says why, and takes what the session has. */
  private void notRun(State s, String sql, Session.NotRun e) {
    queue.clear();
    problem = new Problem(
        NOT_RUN,
        e.unverifiable
            ? "It was not sent, as Manual cannot be made sure of on this connection."
            : e.getMessage());
    log.add(new Logged(Level.ERROR, brief(sql), null, NOT_RUN));
    knew(e.status);
    if (e.unverifiable) {
      unavailable = UNVERIFIABLE;
      mode = Mode.AUTO;
    }
    s.drop(this);
  }

  /**
   * Commit's or Rollback's call ended: the results tabs stay as they were. It says Committed or
   * Rolled back only where the server answered so, and Rolled back only where nothing of the
   * session's is in doubt; a connection lost during a COMMIT leaves its outcome in doubt. A way
   * out that waited for it goes on only where it succeeded.
   */
  private void ended(State s, String sql, Session.Ended ended) {
    var portion = ended.portion();
    if (portion == null) { // a COMMIT not sent: nothing it could commit was open
      notCommitted(s, sql, ended.before());
      return;
    }
    var failure = failure(portion);
    boolean partly = incomplete(portion);
    var done = endedAs(sql, portion, partly);
    heard(
        failure == null ? Level.RAN : Level.ERROR,
        sql,
        portion.elapsed(),
        failure == null ? done : outcome(portion));
    // Its warnings came as notices, which Messages shows too ({@link #noticed}).
    endFailed = failure != null && failure.kind() != Failure.Kind.CONNECTION_LOST;
    if (failure == null) {
      learn(portion, false);
      follow(portion.autoCommit());
      // What is open now the server began by itself, as with MySQL's completion_type=1.
      empty = true;
      inTransaction = 0;
      say(s, done);
      if (partly) {
        partly(s, sql);
      }
    } else {
      unended(s, sql, portion, failure);
    }
    if (transaction == Transaction.UNKNOWN && lane != null) {
      check(s);
    }
  }

  /** A COMMIT not sent, as the server did not say that a transaction is open: it says why. */
  private void notCommitted(State s, String sql, Transaction before) {
    knew(new Status(before, null));
    var why = switch (before) {
      case FAILED -> "The transaction failed: the server takes nothing more in it but a rollback.";
      case NONE ->
        "No transaction was open: the server ended it before, as it does after a deadlock.";
      default -> "The server did not say that a transaction is open.";
    };
    problem = new Problem("Not committed", why);
    log.add(new Logged(Level.WARNING, sql, null, "Not committed"));
    s.drop(this);
  }

  /** What Commit's or Rollback's statement did, as Messages and the status line say it. */
  private String endedAs(String sql, Portion portion, boolean partly) {
    boolean warned = !portion.messages().isEmpty();
    if (sql.equals(COMMIT)) {
      return warned ? "Committed, with warnings" : "Committed";
    }
    if (partly) {
      return PARTLY;
    }
    if (doubt != null) {
      return "ROLLBACK ran; what is in doubt still is";
    }
    return warned ? "Rolled back, with warnings" : "Rolled back";
  }

  /**
   * Commit's or Rollback's statement failed: a COMMIT that lost its connection is in doubt, a
   * ROLLBACK that lost it is what the server does anyway, and any other failure is said.
   */
  private void unended(State s, String sql, Portion portion, Failure failure) {
    if (failure.kind() == Failure.Kind.CONNECTION_LOST && sql.equals(COMMIT)) {
      doubt(sql, "the connection was lost while it committed");
      closeSession();
    } else if (failure.kind() == Failure.Kind.CONNECTION_LOST) {
      problem = new Problem(
          DISCONNECTED,
          "The connection was lost while it rolled back: the server rolls back what was not"
              + " committed.");
      s.drop(this);
      closeSession();
    } else {
      learn(portion, false);
      fail(s, failure);
    }
  }

  // MariaDB's and MySQL's warning that changes to tables outside transactions, as MyISAM ones,
  // could not be rolled back: ER_WARNING_NOT_COMPLETE_ROLLBACK.
  static final int NOT_ROLLED_BACK = 1196;

  /** Whether the server said that a rollback, typed or Rollback's, left changes in place. */
  private static boolean incomplete(Portion portion) {
    return portion.messages().stream().anyMatch(m -> m.code() == NOT_ROLLED_BACK);
  }

  static final String PARTLY = "Rolled back in part";

  /**
   * A rollback left changes in place: the results area says so until the next run, and an act
   * that waited on it does not go on, as its outcome is not what was asked.
   */
  private void partly(State s, String sql) {
    s.drop(this);
    problem = new Problem(
        PARTLY,
        brief(sql) + " could not undo everything: changes to tables that keep no transactions,"
            + " such as MyISAM ones, stay as they were made. Check those tables.");
  }

  /** A notice about this console's session, which Messages keeps too while the console lives. */
  void noticed(String statement, String what, boolean failure) {
    log.add(new Logged(failure ? Level.ERROR : Level.WARNING, statement, null, what));
  }

  static final String UNSURE = "The outcome is not known";

  /**
   * What may have changed data or the transaction was cut short, given up or lost its connection:
   * whether it took effect is not known, which the console says until the user has read it, and
   * Messages keeps.
   */
  private void doubt(String sql, String why) {
    doubt = brief(sql) + ": " + why + ". Whether it took effect is not known; check the data"
        + " before relying on it.";
    log.add(new Logged(Level.ERROR, brief(sql), null, "Outcome not known: " + why));
  }

  /**
   * What the portion says of the transaction, which stays UNKNOWN where it does not say; a plain
   * read, which changes nothing, leaves it as it was where it does not say, as while MariaDB's and
   * MySQL's rows are still to come. A lost connection says nothing of it, and the failure says what
   * is gone.
   */
  private void learn(Portion portion, boolean read) {
    var failure = failure(portion);
    if (failure != null && failure.kind() == Failure.Kind.CONNECTION_LOST
        || read && portion.transaction() == Transaction.UNKNOWN) {
      return;
    }
    knew(new Status(portion.transaction(), null));
  }

  /** What the server confirmed of the session: its transaction, and its mode where it says. */
  private void knew(Status status) {
    transaction = status.transaction();
    learned++;
    if (transaction == Transaction.NONE) {
      inTransaction = 0;
      empty = true;
    }
    follow(status.autoCommit());
  }

  /** Follows the session's auto-commit as the server, or pgjdbc, has it, where it says. */
  private void follow(Boolean autoCommit) {
    if (autoCommit == null || !applied) {
      return;
    }
    var now = Mode.of(autoCommit);
    if (now != mode && lane != null) {
      log.add(new Logged(
          Level.WARNING,
          "",
          null,
          "The session's auto-commit is " + (autoCommit ? "on" : "off") + ": the console shows "
              + now.title));
    }
    mode = now;
  }

  /**
   * Asks the server for the transaction and the auto-commit, where a portion did not say; on the
   * lane, before what is asked after it, while the console goes on. Its answer, or its failure, is
   * dropped where something said more since or the session is another; a failure that leaves the
   * connection leaves the transaction unknown.
   */
  private void check(State s) {
    var asked = new Check(lane, ++learned);
    probing = asked;
    probe = ask(
        s,
        "ask for the transaction",
        null, // its answer, or its failure, tells nothing of a statement
        Servers.Link::status,
        now -> probed(asked, now),
        e -> probed(s, asked, e));
  }

  /** The server's answer to the probe, dropped where something said more since. */
  void probed(Check asked, Status now) {
    if (probing == asked) {
      probe = null;
      probing = null;
    }
    if (learned == asked.at() && lane == asked.lane()) {
      knew(now);
      if (asked == overtaken) {
        empty = false; // what the statement sent since leaves open is at stake
      }
      if (now.autoCommit() == null) {
        unavailable = UNVERIFIABLE; // the driver cannot say: Manual could not be made sure of
      }
    }
  }

  /**
   * The probe failed: dropped where something said more since; where the connection is gone, as a
   * lost one; else the transaction is unknown, and the connection stays.
   */
  void probed(State s, Check asked, Exception error) {
    if (probing == asked) {
      probe = null;
      probing = null;
    }
    if (learned != asked.at() || lane != asked.lane() || error instanceof InterruptedException) {
      return;
    }
    if (lost(error) || error instanceof Session.GaveUp) {
      failed(s, "Transaction", error);
    } else {
      transaction = Transaction.UNKNOWN;
      learned++;
      log.add(new Logged(
          Level.WARNING,
          "",
          null,
          words("The server did not say whether a transaction is open: " + State.message(error))));
    }
  }

  /** Puts the portion's results in tabs: rows from a position on go on with the result before. */
  private void take(String sql, Portion portion, List<Long> dropped) {
    var all = portion.results();
    for (int i = 0; i < all.size(); i++) {
      var result = all.get(i);
      switch (result) {
        case Result.Rows r
        when r.from() > 0 && reading != null -> {
          reading.rows.addAll(r.rows());
          reading.documents.addAll(r.documents());
        }
        case Result.Rows r -> {
          finished();
          var output = new Output(++results, sql, r.fields(), 0);
          output.rows.addAll(r.rows());
          output.documents.addAll(r.documents());
          output.dropped = dropped == null ? 0 : dropped.get(i);
          outputs.add(output);
          reading = output;
        }
        case Result.Count(var count) -> {
          finished();
          outputs.add(new Output(++results, sql, null, count));
        }
        case Failure _ -> {
          // no tab: the failure is said where the portion is taken, through failure(portion)
        }
      }
    }
    if (portion.more() && reading != null) {
      reading.more = true;
    } else {
      finished();
      open = null; // over: nothing is left to close
    }
  }

  /** Whether the next rows of the result are being read. */
  boolean readsOn(Output o) {
    return o == reading && work != null;
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
      heard(Level.WARNING, null, null, m.text() + Delivery.coded(m));
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

  /**
   * The server's failure: the results area says it, a way out that waited gives up so that it is
   * seen, and a lost session is opened anew next.
   */
  private void fail(State s, Failure f) {
    var message = new StringBuilder(f.message());
    if (!f.detail().isEmpty()) {
      message.append('\n').append(f.detail());
    }
    if (!f.hint().isEmpty()) {
      message.append("\nHint: ").append(f.hint());
    }
    // What may have changed something leaves it in doubt, not gone.
    var gone = f.kind() == Failure.Kind.CONNECTION_LOST && !changes() ? gone() : null;
    if (gone != null) {
      message.append('\n').append(gone);
    }
    problem = heard(title(f.kind()), message.toString());
    if (doubt == null) {
      s.drop(this);
    }
    if (f.kind() == Failure.Kind.CONNECTION_LOST) {
      closeSession();
    }
  }

  static final String GONE =
      "The open transaction is gone with the connection: the server rolls back what it did not"
          + " commit.";

  /** What a lost connection took with it of the transaction; null where nothing may have been. */
  private String gone() {
    return switch (transaction) {
      case OPEN, FAILED -> empty ? null : GONE;
      case UNKNOWN ->
        "If a transaction was open, it is gone with the connection: the server rolls back what it"
            + " did not commit.";
      case NONE ->
        mode == Mode.MANUAL
            ? "If the statement began a transaction, it is gone with the connection: the server"
                + " rolls back what it did not commit."
            : null;
    };
  }

  private static String title(Failure.Kind kind) {
    return switch (kind) {
      case INVALID -> "The statement is not valid";
      case CONSTRAINT -> "A constraint was violated";
      case ACCESS_DENIED -> "Access denied";
      case CONNECTION_LOST -> DISCONNECTED;
      case CANCELLED -> CANCELLED_TEXT;
      case TIMEOUT -> "Timed out";
      case OTHER -> "The statement failed";
    };
  }

  /** Whether the failure of a call means the session is gone. */
  private static boolean lost(Exception error) {
    return error instanceof ConnectionException
        || error instanceof IllegalStateException // a lane without a connection
        || error instanceof QueryException e
            && (e.failure() == QueryException.Failure.CONNECTION_LOST
                || e.failure() == QueryException.Failure.ABORTED);
  }

  /**
   * A call failed rather than gave a result. Where the session could not be opened or is gone, it
   * closes; else it stays, and what the call did to the transaction is not known.
   */
  private void failed(State s, String sql, Exception error) {
    queue.clear();
    if (closed || error instanceof InterruptedException) {
      return; // the session closed under it, which says why where it says anything
    }
    if (error instanceof Session.GaveUp g) {
      gaveUp(s, sql, g);
      return;
    }
    var profile = s.profile(id);
    boolean lost = lost(error);
    if (doubt == null) {
      s.drop(this);
    }
    problem = problem(profile, error, lost);
    log.add(new Logged(Level.ERROR, brief(sql), null, problem.title()));
    if (lost) {
      closeSession();
    } else {
      transaction = Transaction.UNKNOWN;
      learned++;
    }
  }

  /**
   * What was still open could not be given up, so the call was not made: the console stops, and
   * an act that waited too. Where that lost the connection, the session is over.
   */
  private void gaveUp(State s, String sql, Session.GaveUp g) {
    s.drop(this);
    failedRun = true;
    if (g.failure.kind() == Failure.Kind.CONNECTION_LOST) {
      // Ending it lost the connection, or ran past its deadline: the session is over, at once
      // and for good, as with any lost connection; the next run opens another.
      var gone = gone();
      problem = heard(
          DISCONNECTED,
          brief(g.sql) + " could not be ended, and the connection was given up: "
              + g.failure.message() + (gone == null ? "" : "\n" + gone));
      log.add(new Logged(Level.ERROR, brief(sql), null, "Not run: the connection ended"));
      closeSession();
      return;
    }
    // Its notice is the app's already.
    problem = heard(
        NOT_RUN,
        brief(g.sql) + " could not be ended, so " + brief(sql) + " was not sent and nothing"
            + " more runs: " + g.failure.message() + Session.MAY_NOT_STAND);
    log.add(new Logged(Level.ERROR, brief(sql), null, NOT_RUN));
    transaction = Transaction.UNKNOWN;
    learned++;
  }

  /** What the results area says of a call that failed rather than gave a result. */
  private Problem problem(ConnectionProfile profile, Exception error, boolean lost) {
    if (error instanceof ConnectionException e && profile != null) {
      var on = database.isEmpty() ? profile : profile.withDatabase(database);
      return heard(Words.title(e.failure()), lines(Words.note(e.failure(), on, true), e.detail()));
    }
    if (lost) {
      var detail = error instanceof QueryException e ? e.detail() : State.message(error);
      return heard(DISCONNECTED, lines(detail, gone()));
    }
    return heard("The statement could not be run", State.message(error));
  }

  /** Two texts on lines of their own, where either is null or empty the other alone. */
  private static String lines(String first, String second) {
    if (first == null || first.isEmpty()) {
      return second == null ? "" : second;
    }
    return second == null || second.isEmpty() ? first : first + "\n" + second;
  }

  /**
   * The grid built the rows of the result up to {@code last}: near the end of those read, the next
   * portion is read, where its statement has more.
   */
  void want(State s, Output output, int last) {
    if (output != reading
        || !output.more
        || work != null
        || last < output.rows.size() - 50
        || !gate(s, null)) {
      return; // asked again once the grid builds these rows anew
    }
    if (lane == null || open == null || open.get() == null) {
      leave(); // its statement was given up, or its session ended: no more rows of it
      return;
    }
    var held = open;
    var sql = running;
    writes = false; // only a plain read in Auto, with nothing open, is read so
    plain = true;
    // Not through ask, as the read goes on rather than being given up.
    work = through(
        s,
        "read rows",
        sql,
        link -> held.get().next(PORTION),
        portion -> {
          work = null;
          if (!portion.more()) {
            open = null; // over: nothing is left to give up
          }
          int before = outputs.size();
          take(sql, portion, null);
          learn(portion, true);
          follow(portion.autoCommit());
          messages(portion);
          var failure = failure(portion);
          if (failure != null) {
            heard(Level.ERROR, sql, portion.elapsed(), outcome(portion));
            if (recorded != null) { // the read's own entry, as nothing was sent after it
              var was = recorded;
              recorded = new HistoryEntry(
                  was.connection(), was.statement(), was.started(), was.took(), outcomeOf(portion));
              s.ranAgain(was, recorded);
            }
            // Where the server says, also in its later rows.
            help.failed(sql, failure, provider(s), words(failure.message()));
            fail(s, failure);
          } else if (before < outputs.size()) {
            // Results after the rows: what the statement gave next.
            heard(Level.RAN, sql, portion.elapsed(), outcome(portion));
          }
          if (transaction == Transaction.UNKNOWN && !portion.more() && lane != null) {
            check(s);
          }
        },
        error -> {
          work = null;
          failed(s, sql, error);
        });
  }

  /**
   * Cancel: what runs is cancelled on the server; the rows read stay, nothing more runs, and the
   * server is asked what the cancel left of the transaction. Commit, Rollback and a switch of
   * auto-commit run to their end: cut short, their outcome would not be known. A statement that is
   * not a plain read may be cancelled, as a long one must stay stoppable: what it did is then in
   * doubt until the user has read that.
   */
  void cancel(State s) {
    if (confirming != null) {
      decline();
      return;
    }
    if (waiting) {
      waiting = false;
      queue.clear();
      say(s, NOT_RUN);
      return;
    }
    if (work == null || firm) {
      return;
    }
    stop();
    queue.clear();
    leave(); // the statement may have finished its portion, which then never arrives
    if (switching != null) {
      // Whether a switch cut short took effect cannot be known: the next run connects anew, to the
      // database the tab names. Nothing was at stake, as it asked first.
      switching = null;
      closeSession();
    }
    cancelled = true;
    log.add(new Logged(Level.WARNING, brief(running), null, CANCELLED_TEXT));
    // Not where it was never sent, as one cancelled while the session opened.
    if (changes() && !firm && sent.get() != null) {
      doubt(running, "it was cancelled");
    }
    if (lane != null) {
      check(s);
    }
  }

  /** Why the console may start nothing now: the act that holds its connection; or null. */
  private String locked(State s) {
    var why = s.locked(this);
    if (why != null) {
      say(s, why + ": nothing runs until that is done");
    }
    return why;
  }

  /**
   * Switches the database: on the open connection where the server can, else the session closes
   * and the next run opens one to it. Asks first where something is at stake.
   */
  void use(State s, String name) {
    if (busy() || name.equals(database) || locked(s) != null) {
      return;
    }
    chosen = true;
    var profile = s.profile(id);
    var version = s.catalog.version(id);
    if (lane == null || profile == null) {
      database = name; // the next session opens on it
      s.edited();
      return;
    }
    if (s.asks(
        new State.Act("switch the database to " + name, this),
        "it switches to " + (name.isEmpty() ? "no database" : name),
        List.of(this),
        () -> use(s, name))) {
      return;
    }
    if (version == null
        || profile.provider().supports(Capability.CONNECTION_PER_DATABASE, version)) {
      closeSession();
      database = name;
      s.edited();
      return;
    }
    leave();
    switching = name;
    var switched = "Database " + name; // what Messages names the switch by
    running = switched;
    writes = true;
    plain = false;
    sent = new AtomicReference<>(); // no statement of the user's
    work = ask(
        s,
        "switch the database",
        link -> {
          link.use(name);
          return name;
        },
        done -> {
          work = null;
          switching = null;
          database = done;
          s.edited();
          log.add(new Logged(Level.RAN, switched, null, "Switched"));
        },
        error -> {
          work = null;
          switching = null;
          failed(s, switched, error);
        });
  }

  /**
   * Gives up the work and its call on the lane, which core turns into a cancel on the server where
   * it runs. The call is cancelled itself, as a work cancelled before it began to wait for it would
   * leave it to run.
   */
  private void stop() {
    if (recording != null && teller != null) { // none comes from here on
      switch (work.delivery().untaken()) {
        case Session.Ran r -> // it ran to its end before the cancel
          remember(teller.state(), outcomeOf(r.portion()), r.portion().elapsed());
        case Session.TooMany _ -> recording = null; // not sent, as in run's own outcome
        case Session.NotRun _ -> recording = null;
        case Session.GaveUp _ -> recording = null;
        case null, default -> remember(teller.state(), new Outcome.Cancelled(), null);
      }
    }
    work.cancel(); // its outcome, if the lane has it already, becomes a notice
    outstanding.remove(work.delivery());
    work = null;
    firm = false;
  }

  /**
   * The console's session ends: the tree's connection was disconnected, its profile changed, or
   * the tab closed. Every way there asked first where something was at stake, or was told plainly
   * what going on costs; where a way did not, the console says what that cost.
   */
  void end() {
    boolean stake = atStake();
    if (work != null) {
      stop();
    }
    queue.clear();
    waiting = false;
    stranded = false;
    switching = null;
    confirming = null;
    if (stake) {
      problem = new Problem(
          "The connection ended",
          "The console's connection ended while something was at stake: the server rolls back"
              + " what it did not commit.");
      log.add(new Logged(Level.WARNING, "", null, "The connection ended"));
    }
    closeSession();
  }

  private void closeSession() {
    transaction = Transaction.NONE;
    inTransaction = 0;
    empty = true;
    firm = false;
    learned++;
    unavailable = null; // a new session, perhaps with another driver, says it anew
    applied = false;
    if (probe != null) {
      probe.cancel();
      probe = null;
      probing = null;
    }
    endFailed = false;
    overdue = false;
    // What the session still owes the console it owes the app now.
    outstanding.forEach(Delivery::giveUp);
    outstanding.clear();
    open = null; // closed with the connection
    leave();
    if (lane != null) {
      // Interrupts what runs, which core cancels, gives up what is still open, as a read whose
      // rest was not read, and closes the connection, each within a bound. What that says goes
      // to the app's notices. The app counts the session until it has, which a background work
      // waits for, so that Exit can wait for it as for any work.
      var sql = openSql;
      var tell = teller;
      var reported = new CompletableFuture<Void>();
      lane.close(ended -> {
        try {
          Session.told(ended, sql, SESSION_ENDED, tell);
        } finally {
          tell.wipe();
          reported.complete(null);
        }
      });
      var state = tell.state();
      state.closing();
      tell.app()
          .background(
              "close a console's session", reported::get, _ -> state.closed(), _ -> state.closed());
      lane = null;
    }
    driver = null;
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  @Override
  public void close() {
    closed = true;
    help.close();
    end();
  }

  /** Whether the call under way may change data or the transaction beyond a plain read. */
  boolean changes() {
    return writes && !plain;
  }

  /** Whether something runs or waits to. */
  boolean busy() {
    return working() || confirming != null;
  }

  /** Whether something runs or waits for its connection, as a spinner shows: not a question. */
  boolean working() {
    return work != null || waiting;
  }

  /** Whether the session is open. */
  boolean connected() {
    return lane != null;
  }

  /**
   * Whether something is at stake on the console, the one test of every way out, the questions,
   * the tab's mark and Rollback: an outcome in doubt until the user has read it; on its session, a
   * call that may change data or the transaction for as long as it runs, as a statement that is not
   * a plain read runs until it is read to its end; a question to the server; or a transaction the
   * server did not say is closed and that a statement which may have changed something ran in. A
   * plain read, running or with rows left, is none.
   */
  boolean atStake() {
    if (doubt != null) {
      return true; // until the user has read it
    }
    if (lane == null) {
      return false;
    }
    if (work != null && writes || probe != null) {
      return true;
    }
    return switch (transaction) {
      case NONE -> false;
      case OPEN, FAILED -> !empty;
      case UNKNOWN -> true;
    };
  }

  /** Whether Commit is offered: only where the server said that a transaction is open. */
  boolean canCommit() {
    return work == null && probe == null && transaction == Transaction.OPEN && !empty;
  }

  /**
   * Whether Rollback is offered: the safe way out wherever something may be open on the session,
   * also while the server is asked.
   */
  boolean canRollback() {
    return work == null && lane != null && atStake();
  }

  /** Auto or Manual; asks first where something is at stake. */
  void switchMode(State s, Mode mode) {
    if (busy() || mode == this.mode || locked(s) != null) {
      return;
    }
    if (mode == Mode.MANUAL && unavailable != null) {
      say(s, unavailable);
      return;
    }
    if (lane == null) {
      this.mode = mode; // the next session takes it, before any statement of the user's
      return;
    }
    var before = "it switches to " + mode.title;
    var act = new State.Act("switch to " + mode, this);
    if (s.asks(act, before, List.of(this), () -> switchMode(s, mode))) {
      return;
    }
    boolean wasEmpty = empty;
    firm = true;
    watchFirm(s);
    writes = true;
    plain = false;
    running = AUTO_COMMIT;
    work = ask(
        s,
        "switch auto-commit",
        link -> Session.setMode(link, mode == Mode.MANUAL, wasEmpty),
        switched -> {
          work = null;
          firm = false;
          applied |= switched.done();
          knew(switched.now());
          empty = wasEmpty || transaction == Transaction.NONE;
          if (switched.now().autoCommit() == null) {
            unavailable = UNVERIFIABLE;
            say(s, UNVERIFIABLE);
          } else if (switched.done()) {
            applied = true;
            log.add(new Logged(
                Level.RAN, AUTO_COMMIT, null, mode == Mode.AUTO ? "Turned on" : "Turned off"));
          } else {
            // The server had one open after all.
            s.asks(act, before, List.of(this), () -> switchMode(s, mode));
          }
        },
        error -> {
          work = null;
          firm = false;
          failed(s, AUTO_COMMIT, error);
        });
  }

  /** Commit: as the statement COMMIT, on the session's lane, where the server said one is open. */
  void commitTransaction(State s) {
    if (canCommit() && locked(s) == null) {
      end(s, COMMIT);
    }
  }

  /** Rollback: as the statement ROLLBACK. */
  void rollBackTransaction(State s) {
    if (canRollback() && locked(s) == null) {
      end(s, ROLLBACK);
    }
  }

  private void end(State s, String sql) {
    leave();
    queue.clear();
    problem = null;
    firm = true;
    watchFirm(s);
    writes = true;
    plain = false;
    running = sql;
    var tell = teller;
    work = ask(
        s,
        sql.equals(COMMIT) ? "commit" : "roll back",
        link -> Session.finish(link, sql, tell),
        ended -> {
          work = null;
          firm = false;
          ended(s, sql, ended);
        },
        error -> {
          work = null;
          firm = false;
          failed(s, sql, error);
          endFailed = lane != null; // it failed, and the connection is still there
        });
  }

  /**
   * Commit, Rollback or a switch of auto-commit runs to its end, which nothing stops; once it has
   * not answered for {@link #FIRM_SECONDS}, the question offers to end the connection instead.
   */
  private void watchFirm(State s) {
    int at = ++firms;
    overdue = false;
    s.app.after(FIRM_SECONDS, () -> {
      if (firm && firms == at) {
        overdue = true;
      }
    });
  }

  static final double FIRM_SECONDS = 10;

  /** Shows the console's tab, in its window raised, for the question {@link State#asks} put. */
  void show(State s) {
    var group = s.tabs.groupOf(this);
    if (group == null) {
      return;
    }
    s.tabs.select(group, group.tabs().indexOf(this));
    // The main window's tabs have no window of their own: the app's is theirs.
    var window = group.window();
    if (window == null && s.app != null) {
      window = s.app.windows().of(s.app);
    }
    if (window != null) {
      window.raise();
    }
  }

  /** The act that waits for this console's question, or null. */
  State.Pending asked(State s) {
    var pending = s.pending;
    return pending != null && !pending.armed() && pending.console() == this ? pending : null;
  }

  /**
   * The question's answer. Commit or Rollback run, and the act goes on once nothing is at stake any
   * more, only where they succeeded; a Rollback while a plain read runs cancels it first, and none
   * is offered while anything else runs. Go on ends the session at once, as the question said,
   * and goes on. Continue goes on past an outcome in doubt, which the user has read. Cancel, null,
   * gives the act up.
   */
  void answer(State s, String choice) {
    var asked = asked(s);
    if (asked == null) {
      return;
    }
    if (choice == null) {
      s.pending = null;
      return;
    }
    switch (choice) {
      case CONTINUE -> {
        s.pending = null;
        doubt = null;
        asked.then().run();
        return;
      }
      case GO_ON -> {
        s.pending = null;
        var cut = running;
        if (teller != null) {
          teller.tell(cut, WENT_ON, true); // the app keeps it, whatever becomes of the console
        }
        end();
        asked.then().run();
        return;
      }
      case COMMIT -> commitTransaction(s);
      case ROLLBACK -> {
        if (firm || work != null && changes()) {
          return;
        }
        if (work != null) {
          stop();
          queue.clear();
          leave();
          cancelled = true;
          log.add(new Logged(Level.WARNING, brief(running), null, CANCELLED_TEXT));
        }
        if (lane != null) {
          end(s, ROLLBACK);
        }
      }
      default -> throw new IllegalArgumentException(choice);
    }
    settle(s);
  }

  static final String WENT_ON =
      "The connection was ended before this was done, as asked: the server rolls back what was"
          + " not committed, and what this statement did is not known.";

  static final String CONTINUE = "CONTINUE";
  static final String GO_ON = "GO_ON";

  /** Acknowledge: the user has read that an outcome is not known. */
  void acknowledge(State s) {
    doubt = null;
    settle(s);
  }

  /**
   * The act that waited for this console goes on once nothing is at stake any more; after every
   * call's result, and every answer. A failure gives it up before ({@link State#drop}).
   */
  private void settle(State s) {
    var asked = asked(s);
    if (asked != null && !atStake()) {
      s.pending = null;
      asked.then().run();
    }
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
    return words(password, words);
  }

  /** The server's words, or that they were withheld where they hold the password. */
  static String words(char[] password, String words) {
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
        case Result.Count(var count) -> parts.add(rows(count) + CHANGED);
        case Failure f ->
          parts.add(f.kind() == Failure.Kind.CANCELLED ? CANCELLED_TEXT : f.message());
      }
    }
    return parts.isEmpty() ? "Done" : String.join(", ", parts);
  }

  static String rows(long n) {
    return rows(n, false);
  }

  /** How many rows, or documents, as "1,024 rows". */
  static String rows(long n, boolean documents) {
    var noun = documents ? " document" : " row";
    return String.format(Locale.ROOT, "%,d", n) + noun + (n == 1 ? "" : "s");
  }

  // The view

  /** The statement Control+Enter would run, or null where it would run the selection or none. */
  private Statement target(Provider provider) {
    if (editor.caret() != editor.anchor()) {
      return null;
    }
    return statements(provider).at(editor.caret()).orElse(null);
  }

  /** The text's statements, scanned again only when the text or its provider changed. */
  Statements statements(Provider provider) {
    // Each edit hands over a new string, which equals takes as the same at once where it is.
    if (scanned == null || !Objects.equals(scannedText, text) || scannedProvider != provider) {
      scanned = Statements.of(text, provider);
      scannedText = text;
      scannedProvider = provider;
    }
    return scanned;
  }

  /** The tab's content: its toolbar, the editor over the results, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(id);
    var provider = profile.provider();
    El top =
        // Without the provider's name, which the tree says: Commit and Rollback need its room.
        Card.toolbar(State.name(profile), null, ui.actions(s, provider).toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El code = editor(s, app, provider);
    El panes = Split.split(
            Split.Direction.COLUMN,
            split,
            FIRST_SPLIT,
            p -> split = p,
            code,
            3 * t.size().control(),
            area.view(s, app),
            4 * t.size().control())
        .grow(1);
    return col()
        .grow(1)
        .minSize(0, 0)
        .onKey(ui.keys(s))
        .child(top)
        .child(line == null ? Divider.divider() : line)
        .child(panes)
        .child(ui.footer(s));
  }

  private El editor(State s, App<State> app, Provider provider) {
    Highlighter syntax = switch (provider) {
      case POSTGRESQL -> Sql.of(Sql.Dialect.POSTGRES);
      case MARIADB, MYSQL -> Sql.of(Sql.Dialect.MYSQL);
      case MONGODB -> ShellSyntax.of();
    };
    var at = target(provider); // the lines of the statement Control+Enter runs
    this.editor.block(at == null ? 0 : at.start(), at == null ? 0 : at.end());
    El code = CodeEditor.codeEditor(app, text, this.editor, syntax, v -> {
          text = v;
          s.edited();
        })
        .key("sql")
        .label(provider.keepsDocuments() ? "Statements" : "SQL")
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
    code.onKey(code.onKey() == null ? keys : keys.or(code.onKey()));
    return help.editor(s, app, provider, code);
  }

  /**
   * The question about what may be open, while it is asked; in the console's window ({@link
   * ConsoleView#question}).
   */
  El question(State s) {
    return ui.question(s);
  }

  /** What is asked before a statement is sent, while it is; in the console's window. */
  El confirmation(State s) {
    return ui.confirmation(s);
  }

  /** Run anyway: the statement asked about is sent, through the gate again. */
  void proceed(State s) {
    var asked = confirming;
    if (asked == null) {
      return;
    }
    confirming = null;
    if (!gate(s, asked.sql())) {
      return;
    }
    if (session(s) == null) {
      queue.clear();
      return;
    }
    send(s, asked.sql(), false);
  }

  /** Cancel: neither the statement asked about nor those after it run. */
  void decline() {
    var asked = confirming;
    if (asked == null) {
      return;
    }
    confirming = null;
    queue.clear();
    cancelled = true;
    log.add(new Logged(Level.WARNING, brief(asked.sql()), null, NOT_RUN));
  }

  /** History: the statements run on the connection, to put one into the editor. */
  void openHistory() {
    history = new HistoryDialog();
  }

  /** Save query: the selection, or else the whole text, under a name. */
  void saveQuery() {
    int from = selectionStart();
    int to = selectionEnd();
    var saved = from < to ? text.substring(from, to) : text;
    if (!saved.isBlank()) {
      queries = QueriesDialog.save(saved);
    }
  }

  /** Where the selection starts in the text. */
  private int selectionStart() {
    return Math.min(editor.caret(), editor.anchor());
  }

  /** Where the selection ends in the text, at most at its end. */
  private int selectionEnd() {
    int end = Math.max(editor.caret(), editor.anchor());
    return Math.min(end, text.length());
  }

  /** Saved queries: those of the connection, to put one into the editor. */
  void openQueries() {
    queries = QueriesDialog.list();
  }

  /**
   * Puts the statement in place of the selection; without one, as a statement of its own on a line
   * of its own: at the caret, or after the statement the caret is in, closing the statement before
   * it where that has no terminator yet. As one step that Undo takes back; the caret after it,
   * where Run runs it.
   */
  void insert(App<State> app, String sql) {
    int from = selectionStart();
    int to = selectionEnd();
    var provider = provider(app.state());
    if (from != to || provider == null) {
      editor.replace(from, to, sql, sql.stripTrailing().length()); // the caret after it
      return;
    }
    var all = statements(provider);
    int at = place(all, provider, from);
    var before = at > 0 && text.charAt(at - 1) != '\n' ? "\n" : "";
    var ending = ending(sql, provider);
    var put = before
        + sql
        + ending.terminator()
        + (at < text.length() && text.charAt(at) != '\n' ? "\n" : "");
    int caret = before.length() + ending.caret();
    int start = at;
    var unterminated = all.open() ? all.statements().getLast() : null; // none to end it
    if (unterminated != null && unterminated.start() < at) {
      // Closed right after its last word before the entry, not after its comments.
      int end = Math.min(unterminated.end(), at);
      while (end > unterminated.start() && Character.isWhitespace(text.charAt(end - 1))) {
        end--;
      }
      var closing = ";" + text.substring(end, at);
      put = closing + put;
      caret += closing.length();
      start = end;
    }
    editor.replace(start, at, put, caret);
  }

  /**
   * Where a statement inserted with the caret at {@code here} goes: after the statement the caret
   * writes in, or after the comment it is in, else at the caret; before the opener of a block
   * comment not closed, executable ones too, which holds all after it.
   */
  private int place(Statements all, Provider provider, int here) {
    var part = all.around(here); // the statement the caret writes in, up to its terminator
    var unterminated = all.open() ? all.statements().getLast() : null;
    var comment = Statements.pieces(text, provider).stream()
        .filter(x -> x.kind() == Statements.Piece.Kind.COMMENT && x.holds(here))
        .findFirst();
    var unclosed = comment.filter(x -> x.open() && text.startsWith("/*", x.start()));
    if (unclosed.isPresent()) {
      return unclosed.get().start();
    }
    boolean inside = all.statements().stream()
        .anyMatch(x -> part.start() <= x.start()
            && x.start() < here
            && (here <= x.end() || !x.equals(unterminated)));
    return inside ? part.end() : comment.map(Statements.Piece::end).orElse(here);
  }

  /**
   * What ends the text as a statement, and where the caret goes in the text so ended: a semicolon
   * where its last statement has no terminator, on a line of its own after a comment to the end of
   * the line, which would take it in, the caret after it; none where it has one, as a saved query
   * may, the caret at its last statement's end, before the comments after it, so that Run runs it.
   * As the provider's statements are split, so that an executable comment is code.
   */
  record Ending(String terminator, int caret) {}

  static Ending ending(String sql, Provider provider) {
    var scan = Statements.of(sql, provider);
    var statements = scan.statements();
    if (statements.isEmpty()) {
      return new Ending("", sql.length());
    }
    if (!scan.open()) {
      return new Ending("", statements.getLast().end());
    }
    var pieces = Statements.pieces(sql, provider);
    var last = pieces.getLast();
    boolean lineComment = last.kind() == Statements.Piece.Kind.COMMENT
        && last.end() == sql.length()
        && !sql.startsWith("/*", last.start());
    var terminator = lineComment ? "\n;" : ";";
    return new Ending(terminator, sql.length() + terminator.length());
  }

  /**
   * "Ran in 12 ms · 3 rows": the last run's time on the server, and its rows; "Not connected"
   * while neither the console nor the tree is, and "Ready" before the first run.
   */
  String status(State s) {
    var status = !connected() && !s.catalog.connected(id) ? "Not connected" : ran(s);
    // shortcut: Manual reads every result whole before showing it, as it may not stay open in a
    // transaction; read lazily in Manual too once giving up a read can keep the transaction.
    return mode == Mode.MANUAL ? status + " · " + WHOLE : status;
  }

  static final String WHOLE = "Manual reads each result whole";

  /** How many statements, as "1 statement" or "3 statements". */
  static String counted(int statements) {
    return statements + (statements == 1 ? " statement" : " statements");
  }

  private String ran(State s) {
    if (took == null || statements == 0 && !cancelled && !failedRun) {
      return "Ready";
    }
    if (statements == 0 && !cancelled) {
      return NOT_RUN; // the session could not be opened
    }
    return how() + tally(keepsDocuments(s));
  }

  /** How the last run went, and its time on the server. */
  private String how() {
    var ms = took.toMillis() + " ms";
    if (cancelled) {
      return CANCELLED_TEXT;
    }
    if (failedRun && statements == 1) {
      return "Failed after " + ms;
    }
    return "Ran"
        + (statements > 1 ? " " + counted(statements) : "")
        + " in "
        + ms
        + (failedRun ? ", the last failed" : "");
  }

  /** The rows the last run's results read and changed, each after a " · ". */
  private String tally(boolean whole) {
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
    var parts = new StringBuilder();
    if (rows) {
      parts.append(" · ").append(rows(read, whole)).append(more ? " so far" : "");
    }
    if (counts) {
      parts.append(" · ").append(rows(changed, whole)).append(CHANGED);
    }
    return parts.toString();
  }
}

package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
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
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.sql.Sql;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.document.Commands;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Request;
import dev.hoori.db.core.document.Script;
import dev.hoori.db.core.document.SyntaxException;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
 *       ({@link #after}, {@link #start}); nothing the app's thread believed authorizes it. The
 *       mode is verified, or the statement is not sent: by the server's word on MariaDB and
 *       MySQL, pgjdbc's on PostgreSQL. Auto-commit changes only in {@link
 *       #setMode}, where the server has just said that nothing is open; the mode shown follows the
 *       session's. A driver that cannot say makes Manual unavailable.
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
    long dropped; // rows beyond those kept, read to the statement's end and not kept
    boolean pinned; // the next run leaves it
    float[] widths;
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
    AUTO,
    MANUAL
  }

  /** A question to the server about the transaction: on which session, and after what was known. */
  record Check(Lane lane, int at) {}

  /**
   * What a statement's call read: its first portion where it was read lazily, else all of it as
   * one; for each of the portion's results, how many of its rows were read and not kept.
   */
  record Ran(Portion portion, List<Long> dropped, boolean lazy) {}

  /**
   * Commit's or Rollback's call: the transaction the server said before a COMMIT, null before a
   * ROLLBACK; the statement's portion, null where the COMMIT was not sent.
   */
  record Ended(Transaction before, Portion portion) {}

  /**
   * What is asked before a statement is sent: a read of more rows than the console keeps, or a
   * change of every row, which is a danger.
   */
  record Confirm(String sql, String title, String text, boolean danger) {}

  /** A switch of auto-commit: whether the session has the mode asked for now, and its status. */
  record Switched(boolean done, Status now) {}

  /**
   * A statement not sent, as the session's mode is not the one shown, or cannot be known; with what
   * the session has.
   */
  static final class NotRun extends Exception {
    final Status status;
    final boolean unverifiable; // the driver cannot say the server's auto-commit

    NotRun(String why, Status status, boolean unverifiable) {
      super(why);
      this.status = status;
      this.unverifiable = unverifiable;
    }
  }

  /**
   * A read not sent, as the console would read it whole and the planner estimates more rows than
   * it keeps: the user is asked first. With the session's status, read before anything was sent.
   */
  static final class TooMany extends Exception {
    final long rows;
    final Status status;

    TooMany(long rows, Status status) {
      super(rows(rows));
      this.rows = rows;
      this.status = status;
    }
  }

  static final String UNVERIFIABLE =
      "The driver of this connection does not report the server's auto-commit, so Manual cannot"
          + " be made sure of: choose a newer driver version in the connection's Driver field.";

  // shortcut: a statement not on the read list is read to its end, of which this many rows are
  // kept to show; show its first rows as they come once long ones of them are common.
  static final int KEPT = 100_000;

  static final String COMMIT = "COMMIT";
  static final String ROLLBACK = "ROLLBACK";

  final UUID id;
  // The one it runs on: the tree node's, the profile's, the one chosen, or else the one database of
  // the user's the tree read, until the session opens; empty for none.
  // shortcut: a USE the user runs is not followed, so this may name another database than the
  // session's; ask the session for its database after each run once that misleads.
  String database;
  String text = "";
  final CodeEditor.State editor = new CodeEditor.State();
  final ConsoleEditor help = new ConsoleEditor(this); // the error's place, completions
  Split.Position split = SPLIT;
  final Menu.State databases = new Menu.State();
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
  private Asking work; // the statement, the rows or the switch under way, or null
  private String running; // the statement under way, or the one whose rows are read
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
  final DataGrid.Selection logSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
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
  private boolean empty;
  private boolean firm; // the call is Commit, Rollback or a switch of auto-commit: none stops it
  // The call under way may change data or the transaction: a statement not on the read list, read
  // to its end, or Commit, Rollback, a switch of auto-commit or of the database. Cut short or
  // lost, what it did is not known.
  private boolean writes;
  private boolean overdue; // the Commit, Rollback or switch under way has not answered for long
  private int firms; // counted, so that a late patience is dropped
  // The last Commit or Rollback failed, the connection still there: ending it is a way out.
  private boolean endFailed;
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
  private int learned; // what was learned of it, counted: an answer that comes late is dropped
  // The user's statement sent and not yet ended, for the history; null for none.
  private Sent recording;
  private HistoryEntry recorded; // the last statement that went into the history, or null
  HistoryDialog history; // the History dialog, null while closed

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
  private void record(State s, Outcome outcome, Duration took) {
    var sent = recording;
    if (sent == null) {
      return;
    }
    recording = null;
    recorded = new HistoryEntry(
        id,
        sent.sql(),
        sent.started(),
        (took != null ? took : Duration.between(sent.started(), Instant.now()))
            .truncatedTo(ChronoUnit.MILLIS), // as it is kept
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
      if (result instanceof Result.Count c) {
        return new Outcome.Changed(c.count());
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

  private boolean keepsDocuments(State s) {
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
    if (provider.keepsDocuments()) {
      var all = everyDocument(sql);
      if (all.isPresent()) {
        var verb = all.get().verb();
        running = sql;
        confirming = new Confirm(
            sql,
            (verb.equals("delete") ? "Delete" : "Update") + " every document?",
            brief(sql) + " has an empty filter: it would " + verb + " every document of "
                + all.get().collection() + ".",
            true);
      } else {
        send(s, sql, true);
      }
      return;
    }
    var every = EveryRow.of(sql, provider);
    if (every.isPresent()) {
      var verb = every.get().verb().equals("DELETE") ? "delete" : "update";
      var table = every.get().table();
      running = sql;
      var of = table.isEmpty() ? "its table" : table;
      confirming = new Confirm(
          sql,
          (verb.equals("delete") ? "Delete" : "Update") + " every row?",
          every.get().certain()
              ? brief(sql) + " has no WHERE clause: it would " + verb + " every row of " + of + "."
              : "Could not tell whether " + brief(sql) + " is limited by a WHERE clause; it may "
                  + verb + " every row of " + of + ".",
          true);
    } else {
      send(s, sql, true);
    }
  }

  /** A change of every document of a collection that the statement asks for, if it does. */
  static java.util.Optional<Commands.Every> everyDocument(String sql) {
    try {
      var request = Script.parse(sql);
      return request.command() == null
          ? java.util.Optional.empty()
          : Commands.every(request.command());
    } catch (SyntaxException e) {
      return java.util.Optional.empty();
    }
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
    boolean lazy = reads(sql, provider) && mode == Mode.AUTO && transaction == Transaction.NONE;
    writes = !lazy;
    plain = reads(sql, provider);
    // Not for a read Auto keeps open: no connection is opened for an estimate never asked for.
    var planner = estimate && plain && !lazy && !provider.keepsDocuments()
        ? s.catalog.planner(id, database)
        : null;
    var on = database;
    // Whatever it is, what it leaves open is at stake: a read may write through a function or a
    // trigger, or hold locks on purpose, which no first word tells.
    boolean wasEmpty = empty;
    empty = false;
    var held = new AtomicReference<Servers.Run>();
    sent = held;
    var mode = this.mode;
    recording = new Sent(sql, Instant.now());
    work = ask(
        s,
        "run a statement",
        link -> start(link, sql, mode, lazy, held, KEPT, planner, on),
        ran -> {
          work = null;
          ran(s, sql, ran);
        },
        error -> {
          work = null;
          if (error instanceof TooMany e) {
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
          if (error instanceof NotRun e) {
            recording = null;
            notRun(s, sql, e);
          } else {
            failed(s, sql, error);
            if (error instanceof InterruptedException || closed) {
              record(s, new Outcome.Cancelled(), null);
            } else if (error instanceof GaveUp) {
              recording = null; // not sent
            } else {
              record(
                  s,
                  new Outcome.Failed(problem.text().isEmpty() ? problem.title() : problem.text()),
                  null);
            }
          }
        });
    open = held; // after ask, which gives up the one open before
    openSql = sql;
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
    problem = new Problem("Not run", why + ": nothing more runs until that is done.");
    log.add(new Logged(Level.ERROR, brief(sql), null, "Not run"));
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
    return through(s, what, sql, link -> after(link, tell, leftSql, call), done, failed);
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
          T value;
          try {
            value = call.call(link);
          } catch (Exception e) {
            delivery.done(e);
            throw e;
          }
          delivery.done(value);
          return value;
        },
        value -> {
          outstanding.remove(delivery);
          if (delivery.deliver()) {
            done.accept(value);
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

  /**
   * One call's outcome between the lane and the console: pending, done (the lane has it),
   * delivered (the console took it), or given up (cancelled, or its session ended). The two
   * threads meet here atomically, and whichever comes second of the outcome and the giving up
   * posts what matters of it as a notice ({@link #matters}): the lane where it was given up
   * before, the app's thread where the outcome was there and not taken.
   */
  static final class Delivery {
    static final int PENDING = 0;
    private static final int DONE = 1;
    private static final int DELIVERED = 2;
    private static final int GIVEN_UP = 3;

    private final Cell state;
    private final Tell tell;
    private final String sql; // what a notice names; null for a call whose outcome tells nothing
    private final boolean plain;
    private volatile Object outcome; // the value, or the exception

    Delivery(Tell tell, String sql, boolean plain) {
      this(tell, sql, plain, Cell.of(PENDING));
    }

    Delivery(Tell tell, String sql, boolean plain, Cell state) {
      this.tell = tell;
      this.sql = sql;
      this.plain = plain;
      this.state = state;
    }

    /** The one atomic variable of its state; a test steps the two threads through it. */
    interface Cell {
      int get();

      boolean compareAndSet(int expected, int next);

      static Cell of(int initial) {
        var value = new AtomicInteger(initial);
        return new Cell() {
          @Override
          public int get() {
            return value.get();
          }

          @Override
          public boolean compareAndSet(int expected, int next) {
            return value.compareAndSet(expected, next);
          }
        };
      }
    }

    /** On the lane: the call has its outcome. */
    void done(Object outcome) {
      this.outcome = outcome;
      while (true) {
        int now = state.get();
        if (now == PENDING && state.compareAndSet(PENDING, DONE)) {
          return; // the console takes it, or gives it up and posts it
        }
        if (now == GIVEN_UP) {
          post(); // the console gave it up before
          return;
        }
        if (now == DELIVERED) {
          return; // the lane failed before the call, which the console took as its outcome
        }
      }
    }

    /**
     * On the app's thread, with the call's result or failure: whether it goes to the console, which
     * then has it. Also where the lane ended the call without making it, as when it could not open
     * its connection or was closed: what came instead is the outcome.
     */
    boolean deliver() {
      while (true) {
        int now = state.get();
        if (now == GIVEN_UP || now == DELIVERED) {
          return false;
        }
        if (state.compareAndSet(now, DELIVERED)) {
          return true;
        }
      }
    }

    /** On the app's thread: the console no longer waits for it. */
    void giveUp() {
      while (true) {
        int now = state.get();
        if (now == PENDING && state.compareAndSet(PENDING, GIVEN_UP)) {
          return; // the lane posts it, once it has it
        }
        if (now == DONE && state.compareAndSet(DONE, GIVEN_UP)) {
          post(); // there, and not taken
          return;
        }
        if (now == GIVEN_UP || now == DELIVERED) {
          return;
        }
      }
    }

    /** Whether the lane has the outcome and the console has not taken it yet. */
    boolean waiting() {
      return state.get() == DONE;
    }

    private void post() {
      if (sql != null && tell != null) {
        matters(outcome, plain).forEach(m -> tell.tell(sql, m.what(), m.failure()));
      }
    }
  }

  /** What a notice says of an outcome: its words, and whether it is a failure. */
  record Matter(String what, boolean failure) {}

  /**
   * What matters of an outcome the console did not take: any failure, and any warning the server
   * sent with a statement that is not a plain read. Not what the giving up itself made, a cancel,
   * nor what is said already: a statement not sent, a give-up's failure, Commit's and Rollback's
   * own outcome ({@link #finish} tells it).
   */
  static List<Matter> matters(Object outcome, boolean plain) {
    return switch (outcome) {
      case Ran r -> matters(r.portion(), plain);
      case Portion p -> matters(p, plain);
      case InterruptedException e -> List.of();
      case NotRun e -> List.of();
      case TooMany e -> List.of();
      case GaveUp e -> List.of();
      case QueryException e when e.failure() == QueryException.Failure.CANCELLED -> List.of();
      case ConnectionException e
      when e.failure() == ConnectionException.Failure.CANCELLED -> List.of();
      case Exception e -> List.of(new Matter("It failed: " + State.message(e), true));
      case null, default -> List.of();
    };
  }

  private static List<Matter> matters(Portion portion, boolean plain) {
    var all = new ArrayList<Matter>();
    var failure = failure(portion);
    if (failure != null && failure.kind() != Failure.Kind.CANCELLED) {
      all.add(new Matter("It failed: " + failure.message(), true));
    }
    if (!plain) {
      for (var m : portion.messages()) {
        all.add(
            m.code() == NOT_ROLLED_BACK
                ? new Matter(PARTLY + ": " + m.text() + ". Those changes stand.", true)
                : new Matter(warned(m), false));
      }
    }
    return all;
  }

  /** A warning of the server's, with its code. */
  static String warned(Message m) {
    var code = !m.state().isEmpty() ? m.state() : m.code() != 0 ? String.valueOf(m.code()) : "";
    return "The server warned: " + m.text() + (code.isEmpty() ? "" : " (" + code + ")");
  }

  /** For a test: whether an outcome is there that the console has not taken. */
  boolean outcomeWaiting() {
    return outstanding.stream().anyMatch(Delivery::waiting);
  }

  /**
   * The lane call of every console call but a further portion, as one unit: the statement still
   * open on the session is given up first, whatever the app's thread knew of it, as a step of its
   * own. Where that fails, as the commit of a read's own transaction does on a deferred
   * constraint, the failure goes to the app at once ({@link Tell}), from here, and the call is not
   * made: {@link GaveUp} says why.
   */
  static <T> T after(Servers.Link link, Tell tell, String left, Lane.Call<T> call)
      throws Exception {
    var ended = link.giveUp();
    told(ended, left, ENDED, tell);
    if (ended.failure().isPresent()) {
      throw new GaveUp(left, ended.failure().get());
    }
    return call.call(link);
  }

  /** What giving up a statement said, its failure and the server's warnings, told as notices. */
  static void told(Abandoned ended, String sql, String how, Tell tell) {
    ended.failure().ifPresent(f -> tell.tell(sql, how + f.message() + MAY_NOT_STAND, true));
    for (var m : ended.messages()) {
      tell.tell(sql, warned(m), false);
    }
  }

  static final String ENDED = "Its end, as the console went on, failed: ";
  static final String MAY_NOT_STAND = ". What it changed may not stand.";

  /** Giving up what was still open on the session failed: what wanted to run after it did not. */
  static final class GaveUp extends Exception {
    final String sql;
    final Failure failure;

    GaveUp(String sql, Failure failure) {
      super(failure.message());
      this.sql = sql;
      this.failure = failure;
    }
  }

  /**
   * Says what finishing something on a session did, from whichever thread sees it, at once: to the
   * app's notices ({@link State#notice}), which nothing that can be cancelled, closed or replaced
   * stands between.
   */
  interface Tell {
    void tell(String sql, String what, boolean failure);
  }

  /**
   * The session's {@link Tell}: the server's words checked against its own copy of the password,
   * which it wipes once the session has closed and said so. A call given up may still answer
   * after that: a wiped Teller then posts no word of the server's, only that the outcome came too
   * late to be shown. The check and the wipe hold the same lock, so no text ever goes unchecked.
   */
  static final class Teller implements Tell {
    private final App<State> app;
    private final State state;
    private final String connection;
    private final char[] password; // null where the session has none
    private final ConsoleTab of;
    private boolean wiped;

    Teller(App<State> app, State state, String connection, char[] password, ConsoleTab of) {
      this.app = app;
      this.state = state;
      this.connection = connection;
      this.password = password;
      this.of = of;
    }

    App<State> app() {
      return app;
    }

    State state() {
      return state;
    }

    @Override
    public void tell(String sql, String what, boolean failure) {
      String said;
      synchronized (this) {
        said = wiped ? TOO_LATE : words(password, what);
      }
      var statement = brief(sql == null ? "" : sql);
      app.post(() -> state.notice(connection, statement, said, failure, of));
    }

    /** The session has closed and said all it had to: the password goes. */
    synchronized void wipe() {
      wiped = true;
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }
  }

  static final String TOO_LATE =
      "Its outcome arrived after its session had closed, too late to be shown; check what it did.";

  static final String SESSION_ENDED = "As its session ended, giving up its rest failed: ";

  // On the lane: what the console's calls do with the session.

  /**
   * The one place where the session's auto-commit changes, as JDBC commits a transaction that is
   * open where it is turned on: only where the server says, right before, that none is open, or
   * where the one open is empty, the server's own begun as the user's Commit or Rollback ended
   * theirs. Done where the session has the mode afterwards, by the server's word.
   */
  static Switched setMode(Servers.Link link, boolean manual, boolean empty) throws QueryException {
    return setMode(link, manual, empty, link.status());
  }

  private static Switched setMode(Servers.Link link, boolean manual, boolean empty, Status now)
      throws QueryException {
    if (now.autoCommit() != null && now.autoCommit() == !manual) {
      return new Switched(true, now);
    }
    if (now.autoCommit() == null
        || now.transaction() != Transaction.NONE
            && !(now.transaction() == Transaction.OPEN && empty)) {
      return new Switched(false, now);
    }
    link.manual(manual);
    now = link.status();
    return new Switched(now.autoCommit() != null && now.autoCommit() == !manual, now);
  }

  /**
   * A statement on the session, as one lane call. It reads the session's status fresh, by the
   * server's word on MariaDB and MySQL and by pgjdbc's on PostgreSQL, and is sent only where the
   * session has the mode shown; a session without it takes it where nothing is open, else the
   * statement is not sent. Auto runs also where the driver cannot say, as nothing then stays open
   * that the console would not ask about. A plain read asked for lazily, in Auto with nothing open
   * by the server's word, gives its first portion and the rest as the grid scrolls; any other
   * statement is read to its end here, so that no execution is ever left open inside a
   * transaction or of anything that may change data, keeping at most {@code keep} rows a result.
   * Such a read is not sent where the planner estimates more rows than that on {@code planner}, a
   * connection of the app's own, or null for no estimate, with its names read on {@code database}:
   * {@link TooMany}.
   */
  static Ran start(
      Servers.Link link,
      String sql,
      Mode mode,
      boolean lazy,
      AtomicReference<Servers.Run> held,
      int keep,
      Lane planner,
      String database)
      throws QueryException, NotRun, TooMany, InterruptedException {
    var now = link.status();
    if (now.autoCommit() == null && mode == Mode.MANUAL) {
      throw new NotRun(UNVERIFIABLE, now, true);
    }
    // Read whole, as it is unless it is a lazy read with nothing open, once the session has the
    // mode; estimated before anything is sent on the session, which then stays as it was.
    if (planner != null
        && !(lazy && now.transaction() == Transaction.NONE && now.autoCommit() != null)) {
      long rows = estimate(planner, database, sql);
      if (rows > keep) {
        throw new TooMany(rows, now);
      }
    }
    if (now.autoCommit() != null && now.autoCommit() != (mode == Mode.AUTO)) {
      var switched = setMode(link, mode == Mode.MANUAL, false, now);
      if (!switched.done()) {
        throw new NotRun(
            "The session's auto-commit is "
                + (Boolean.TRUE.equals(switched.now().autoCommit()) ? "on" : "off")
                + " with a transaction open, so it was not sent in "
                + (mode == Mode.AUTO ? "Auto" : "Manual")
                + ". The console shows the session's mode now.",
            switched.now(),
            false);
      }
      now = switched.now();
    }
    var run = link.run(sql);
    held.set(run); // sent: cut short now, what it did is in doubt; and the rest as the grid scrolls
    var first = run.next(PORTION);
    if (lazy && Boolean.TRUE.equals(now.autoCommit()) && now.transaction() == Transaction.NONE) {
      return new Ran(first, Collections.nCopies(first.results().size(), 0L), true);
    }
    return drained(run, first, keep);
  }

  /**
   * The planner's estimate of the read's rows, asked on the app's own connection to the database,
   * so that nothing of it touches the session or its transaction; 0 where it has none, so that the
   * read runs without a question. An interrupt gives it up there too.
   */
  // shortcut: that connection does not see the session's uncommitted DDL, temporary tables or
  // settings such as search_path or a USE run in the console: there the EXPLAIN fails or plans
  // something else, and no question or another is asked. Plan on the session once that misleads.
  private static long estimate(Lane planner, String database, String sql)
      throws InterruptedException {
    Future<OptionalLong> asked;
    try {
      asked = planner.submit(link -> link.estimate(database, sql));
    } catch (RejectedExecutionException e) {
      return 0; // its lane has closed
    }
    try {
      return asked.get().orElse(0);
    } catch (InterruptedException e) {
      asked.cancel(true);
      throw e;
    } catch (ExecutionException | CancellationException e) {
      return 0;
    }
  }

  /**
   * Reads the statement to its end: rows of a result that go on joined as they come, at most
   * {@code keep} of each result kept and those beyond dropped as they arrive, counted. An interrupt
   * ends it, as core cancels the statement on the server and gives no more.
   */
  static Ran drained(Servers.Run run, Portion first, int keep) throws InterruptedException {
    var results = new ArrayList<Result>();
    var dropped = new ArrayList<Long>();
    var documents = new ArrayList<List<Document>>(); // those kept of each result's rows, or null
    var messages = new ArrayList<Message>();
    var took = Duration.ZERO;
    var portion = first;
    while (true) {
      took = took.plus(portion.elapsed());
      messages.addAll(portion.messages());
      for (var result : portion.results()) {
        if (!(result instanceof Result.Rows r)) {
          results.add(result);
          dropped.add(0L);
          documents.add(null);
          continue;
        }
        int at = results.size() - 1;
        if (r.from() == 0 || at < 0 || !(results.get(at) instanceof Result.Rows)) {
          results.add(new Result.Rows(r.fields(), 0, new ArrayList<>()));
          dropped.add(0L);
          documents.add(new ArrayList<>());
          at++;
        }
        var kept = ((Result.Rows) results.get(at)).rows();
        int room = Math.max(0, Math.min(r.rows().size(), keep - kept.size()));
        kept.addAll(r.rows().subList(0, room));
        if (!r.documents().isEmpty()) {
          documents.get(at).addAll(r.documents().subList(0, room));
        }
        dropped.set(at, dropped.get(at) + r.rows().size() - room);
      }
      if (!portion.more()) {
        break;
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException(); // Cancel, also where core did not notice it
      }
      portion = run.next(PORTION);
    }
    // A server of documents' rows keep their documents, as many as the rows kept.
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i) instanceof Result.Rows r
          && documents.get(i) != null
          && !documents.get(i).isEmpty()) {
        results.set(i, new Result.Rows(r.fields(), 0, r.rows(), documents.get(i)));
      }
    }
    return new Ran(
        new Portion(results, messages, false, took, portion.transaction(), portion.autoCommit()),
        dropped,
        false);
  }

  /**
   * Commit or Rollback. A COMMIT is sent only where the server says, right before, that a
   * transaction is open: PostgreSQL answers one in a failed transaction by rolling it back, without
   * an error, and MariaDB and MySQL roll one back by themselves, as after a deadlock.
   */
  static Ended finish(Servers.Link link, String sql, Tell tell) throws QueryException {
    Transaction before = null;
    if (sql.equals(COMMIT)) {
      var now = link.status();
      before = now.transaction();
      if (before != Transaction.OPEN) {
        return new Ended(before, null);
      }
    }
    var run = link.run(sql);
    Portion portion;
    try {
      portion = run.next(1);
    } finally {
      run.close();
    }
    told(portion, sql, tell);
    return new Ended(before, portion);
  }

  /**
   * What Commit's or Rollback's statement came back with beyond its plain outcome, its failure or
   * the server's warnings, goes to the app's notices at once, from the lane.
   */
  private static void told(Portion portion, String sql, Tell tell) {
    var failure = failure(portion);
    if (failure != null) {
      tell.tell(
          sql,
          (sql.equals(COMMIT) ? "Commit failed: " : "Rollback failed: ") + failure.message(),
          true);
    }
    for (var m : portion.messages()) {
      if (m.code() == NOT_ROLLED_BACK) {
        tell.tell(sql, PARTLY + ": " + m.text() + ". Those changes stand.", true);
      } else {
        var code = !m.state().isEmpty() ? m.state() : m.code() != 0 ? String.valueOf(m.code()) : "";
        tell.tell(
            sql,
            "The server warned: " + m.text() + (code.isEmpty() ? "" : " (" + code + ")"),
            false);
      }
    }
  }

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
    if (provider.keepsDocuments()) {
      // A cursor that only reads, as find and aggregate without $out give.
      try {
        var request = Script.parse(sql);
        return request.shape() == Request.Shape.CURSOR && request.reads();
      } catch (SyntaxException e) {
        return false;
      }
    }
    var word = Statements.firstWord(sql, provider);
    // ASCII's case alone, as the servers fold keywords: ſELECT is no SELECT.
    word = word.chars().allMatch(c -> c < 0x80) ? word.toUpperCase(Locale.ROOT) : "";
    // shortcut: INTO in a literal or comment makes a read count as a change, a needless caution.
    return READS.contains(word) && !WRITING.matcher(sql).find();
  }

  // The database that the shell's use switched the session to, or null for any other statement.
  private String used(State s, String sql, Portion portion) {
    var profile = s.profile(id);
    if (profile == null || !profile.provider().keepsDocuments() || failure(portion) != null) {
      return null;
    }
    try {
      return Script.parse(sql).use();
    } catch (SyntaxException e) {
      return null;
    }
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

  private void ran(State s, String sql, Ran ran) {
    var portion = ran.portion();
    var used = used(s, sql, portion);
    if (used != null) {
      database = used; // the shell's use: the console runs on there from now on
      s.edited();
    }
    var failure = failure(portion);
    record(s, outcomeOf(portion), portion.elapsed());
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
  private void notRun(State s, String sql, NotRun e) {
    queue.clear();
    problem = new Problem(
        "Not run",
        e.unverifiable
            ? "It was not sent, as Manual cannot be made sure of on this connection."
            : e.getMessage());
    log.add(new Logged(Level.ERROR, brief(sql), null, "Not run"));
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
  private void ended(State s, String sql, Ended ended) {
    var portion = ended.portion();
    if (portion == null) { // a COMMIT not sent: nothing it could commit was open
      knew(new Status(ended.before(), null));
      var why = switch (ended.before()) {
        case FAILED ->
          "The transaction failed: the server takes nothing more in it but a rollback.";
        case NONE ->
          "No transaction was open: the server ended it before, as it does after a deadlock.";
        default -> "The server did not say that a transaction is open.";
      };
      problem = new Problem("Not committed", why);
      log.add(new Logged(Level.WARNING, sql, null, "Not committed"));
      s.drop(this);
      return;
    }
    var failure = failure(portion);
    boolean partly = incomplete(portion);
    var done = sql.equals(COMMIT)
        ? portion.messages().isEmpty() ? "Committed" : "Committed, with warnings"
        : partly
            ? "Rolled back in part"
            : doubt != null
                ? "ROLLBACK ran; what is in doubt still is"
                : portion.messages().isEmpty() ? "Rolled back" : "Rolled back, with warnings";
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
    } else if (failure.kind() == Failure.Kind.CONNECTION_LOST && sql.equals(COMMIT)) {
      doubt(sql, "the connection was lost while it committed");
      closeSession();
    } else if (failure.kind() == Failure.Kind.CONNECTION_LOST) {
      problem = new Problem(
          "Disconnected",
          "The connection was lost while it rolled back: the server rolls back what was not"
              + " committed.");
      s.drop(this);
      closeSession();
    } else {
      learn(portion, false);
      fail(s, failure);
    }
    if (transaction == Transaction.UNKNOWN && lane != null) {
      check(s);
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
    var now = autoCommit ? Mode.AUTO : Mode.MANUAL;
    if (now != mode && lane != null) {
      log.add(new Logged(
          Level.WARNING,
          "",
          null,
          "The session's auto-commit is " + (autoCommit ? "on" : "off") + ": the console shows "
              + (autoCommit ? "Auto" : "Manual")));
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
    if (lost(error) || error instanceof GaveUp) {
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
    var results = portion.results();
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      switch (result) {
        case Result.Rows r
        when r.from() > 0 && reading != null -> {
          reading.rows.addAll(r.rows());
          reading.documents.addAll(r.documents());
        }
        case Result.Rows r -> {
          finished();
          var output = new Output(++this.results, sql, r.fields(), 0);
          output.rows.addAll(r.rows());
          output.documents.addAll(r.documents());
          output.dropped = dropped == null ? 0 : dropped.get(i);
          outputs.add(output);
          reading = output;
        }
        case Result.Count c -> {
          finished();
          outputs.add(new Output(++this.results, sql, null, c.count()));
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

  /**
   * The server's failure: the results area says it, a way out that waited gives up so that it is
   * seen, and a lost session is opened anew next.
   */
  private void fail(State s, Failure f) {
    var text = new StringBuilder(f.message());
    if (!f.detail().isEmpty()) {
      text.append('\n').append(f.detail());
    }
    if (!f.hint().isEmpty()) {
      text.append("\nHint: ").append(f.hint());
    }
    // What may have changed something leaves it in doubt, not gone.
    var gone = f.kind() == Failure.Kind.CONNECTION_LOST && !changes() ? gone() : null;
    if (gone != null) {
      text.append('\n').append(gone);
    }
    problem = heard(title(f.kind()), text.toString());
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
      case CONNECTION_LOST -> "Disconnected";
      case CANCELLED -> "Cancelled";
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
    if (error instanceof GaveUp g && g.failure.kind() == Failure.Kind.CONNECTION_LOST) {
      // Ending it lost the connection, or ran past its deadline: the session is over, at once
      // and for good, as with any lost connection; the next run opens another.
      s.drop(this);
      failedRun = true;
      var gone = gone();
      problem = heard(
          "Disconnected",
          brief(g.sql) + " could not be ended, and the connection was given up: "
              + g.failure.message() + (gone == null ? "" : "\n" + gone));
      log.add(new Logged(Level.ERROR, brief(sql), null, "Not run: the connection ended"));
      closeSession();
      return;
    }
    if (error instanceof GaveUp g) {
      // Its notice is the app's already; here the console stops, and an act that waited too.
      s.drop(this);
      failedRun = true;
      problem = heard(
          "Not run",
          brief(g.sql) + " could not be ended, so " + brief(sql) + " was not sent and nothing"
              + " more runs: " + g.failure.message() + MAY_NOT_STAND);
      log.add(new Logged(Level.ERROR, brief(sql), null, "Not run"));
      transaction = Transaction.UNKNOWN;
      learned++;
      return;
    }
    var profile = s.profile(id);
    boolean lost = lost(error);
    if (doubt == null) {
      s.drop(this);
    }
    if (error instanceof ConnectionException e && profile != null) {
      var on = database.isEmpty() ? profile : profile.withDatabase(database);
      var note = Words.note(e.failure(), on, true);
      var detail = e.detail();
      problem = heard(
          Words.title(e.failure()),
          note == null ? detail : detail.isEmpty() ? note : note + "\n" + detail);
    } else if (lost) {
      var detail = error instanceof QueryException e ? e.detail() : State.message(error);
      var gone = gone();
      problem = heard(
          "Disconnected", gone == null ? detail : detail.isEmpty() ? gone : detail + "\n" + gone);
    } else {
      problem = heard("The statement could not be run", State.message(error));
    }
    log.add(new Logged(Level.ERROR, brief(sql), null, problem.title()));
    if (lost) {
      closeSession();
    } else {
      transaction = Transaction.UNKNOWN;
      learned++;
    }
  }

  /**
   * The grid built these rows of the result: near the end of those read, the next portion is read,
   * where its statement has more.
   */
  void want(State s, Output output, int first, int last) {
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
      say(s, "Not run");
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
    log.add(new Logged(Level.WARNING, brief(running), null, "Cancelled"));
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
    running = "Database " + name;
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
    if (recording != null && teller != null) { // none comes from here on
      switch (work.delivery().waiting() ? work.delivery().outcome : null) {
        case Ran r -> // it ran to its end before the cancel
          record(teller.state(), outcomeOf(r.portion()), r.portion().elapsed());
        case TooMany e -> recording = null; // not sent, as in run's own outcome
        case NotRun e -> recording = null;
        case GaveUp e -> recording = null;
        case null, default -> record(teller.state(), new Outcome.Cancelled(), null);
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
    if (reading != null && reading.more) {
      reading.more = false;
      reading.cut = true;
    }
    reading = null;
    if (lane != null) {
      // Interrupts what runs, which core cancels, gives up what is still open, as a read whose
      // rest was not read, and closes the connection, each within a bound. What that says goes
      // to the app's notices. The app counts the session until it has, which a background work
      // waits for, so that Exit can wait for it as for any work.
      var sql = openSql;
      var tell = teller;
      var said = new CompletableFuture<Void>();
      lane.close(ended -> {
        try {
          told(ended, sql, SESSION_ENDED, tell);
        } finally {
          tell.wipe();
          said.complete(null);
        }
      });
      var state = tell.state();
      state.closing();
      tell.app()
          .background(
              "close a console's session", said::get, done -> state.closed(), e -> state.closed());
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
  private boolean changes() {
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
  void mode(State s, Mode mode) {
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
    var before = "it switches to " + (mode == Mode.AUTO ? "Auto" : "Manual");
    var act = new State.Act("switch to " + mode, this);
    if (s.asks(act, before, List.of(this), () -> mode(s, mode))) {
      return;
    }
    boolean empty = this.empty;
    firm = true;
    watchFirm(s);
    writes = true;
    plain = false;
    running = "Auto-commit";
    work = ask(
        s,
        "switch auto-commit",
        link -> setMode(link, mode == Mode.MANUAL, empty),
        switched -> {
          work = null;
          firm = false;
          applied |= switched.done();
          knew(switched.now());
          this.empty = empty || transaction == Transaction.NONE;
          if (switched.now().autoCommit() == null) {
            unavailable = UNVERIFIABLE;
            say(s, UNVERIFIABLE);
          } else if (switched.done()) {
            applied = true;
            log.add(new Logged(
                Level.RAN, "Auto-commit", null, mode == Mode.AUTO ? "Turned on" : "Turned off"));
          } else {
            // The server had one open after all.
            s.asks(act, before, List.of(this), () -> mode(s, mode));
          }
        },
        error -> {
          work = null;
          firm = false;
          failed(s, "Auto-commit", error);
        });
  }

  /** Commit: as the statement COMMIT, on the session's lane, where the server said one is open. */
  void commit(State s) {
    if (canCommit() && locked(s) == null) {
      end(s, COMMIT);
    }
  }

  /** Rollback: as the statement ROLLBACK. */
  void rollback(State s) {
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
        link -> finish(link, sql, tell),
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
    if (group != null) {
      s.tabs.select(group, group.tabs().indexOf(this));
      // The main window's tabs have no window of their own: the app's is theirs.
      var window = group.window() != null
          ? group.window()
          : s.app == null ? null : s.app.windows().of(s.app);
      if (window != null) {
        window.raise();
      }
    }
  }

  /** The act that waits for this console's question, or null. */
  private State.Pending asked(State s) {
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
      case COMMIT -> commit(s);
      case ROLLBACK -> {
        if (firm || work != null && changes()) {
          return;
        }
        if (work != null) {
          stop();
          queue.clear();
          leave();
          cancelled = true;
          log.add(new Logged(Level.WARNING, brief(running), null, "Cancelled"));
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
  private static String words(char[] password, String words) {
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
    return rows(n, false);
  }

  /** How many rows, or documents, as "1,024 rows". */
  static String rows(long n, boolean documents) {
    return String.format(Locale.ROOT, "%,d", n)
        + (documents ? n == 1 ? " document" : " documents" : n == 1 ? " row" : " rows");
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
    boolean busy = busy();
    var locked = s.locked(this);
    var actions = new ArrayList<El>();
    actions.add(databases(s));
    // Off where Manual cannot be made sure of, which its tooltip and a note say.
    boolean switchable = !busy && locked == null && unavailable == null;
    // A server of documents commits each command by itself, and has no Manual to offer.
    if (!provider.keepsDocuments()) {
      actions.add(Tooltip.tooltip(
          Segmented.segmented(
                  List.of(Mode.values()),
                  mode,
                  m -> m == Mode.AUTO ? "Auto" : "Manual",
                  switchable ? m -> mode(s, m) : null)
              .key("mode")
              .label("Transactions"),
          unavailable != null
              ? "Manual is unavailable: " + unavailable
              : "Auto commits each statement by itself; in Manual, Commit or Rollback ends the"
                  + " transaction"));
    }
    if (mode == Mode.MANUAL || transaction != Transaction.NONE) {
      actions.add(Tooltip.tooltip(
          button(
                  SECONDARY,
                  "Commit",
                  canCommit() ? () -> commit(s) : null,
                  Button.options()
                      .icon(Symbol.CHECKMARK)
                      .state(
                          firm && COMMIT.equals(running) ? Button.State.BUSY : Button.State.READY))
              .key("commit")
              .keepFocus(),
          "Commit (Control+Shift+C)"));
      actions.add(Tooltip.tooltip(
          button(
                  SECONDARY,
                  "Rollback",
                  canRollback() ? () -> rollback(s) : null,
                  Button.options()
                      .icon(Symbol.ARROW_UNDO)
                      .state(
                          firm && ROLLBACK.equals(running)
                              ? Button.State.BUSY
                              : Button.State.READY))
              .key("rollback")
              .keepFocus(),
          "Roll back (Control+Shift+R)"));
    }
    actions.add(button(
            PRIMARY,
            "Run",
            firm || locked != null ? null : () -> run(s),
            Button.options()
                .icon(Symbol.PLAY)
                // Off, not busy, while a change of its connection is saved or deleted.
                .state(
                    working() && !firm && locked == null ? Button.State.BUSY : Button.State.READY))
        .key("run")
        .keepFocus());
    actions.add(button(SECONDARY, "Run all", busy || locked != null ? null : () -> runAll(s))
        .key("run-all")
        .keepFocus());
    actions.add(button(SECONDARY, "Cancel", busy && !firm ? () -> cancel(s) : null)
        .key("cancel")
        .keepFocus());
    actions.add(Tooltip.tooltip(
        Button.icon(Button.Variant.GHOST, Symbol.HISTORY, "History", this::openHistory)
            .key("history-open")
            .keepFocus(),
        "History"));
    El top =
        // Without the provider's name, which the tree says: Commit and Rollback need its room.
        Card.toolbar(State.name(profile), null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El editor = editor(s, app, provider);
    El split = Split.split(
            Split.Direction.COLUMN,
            this.split,
            SPLIT,
            p -> this.split = p,
            editor,
            3 * t.size().control(),
            results(s, app),
            4 * t.size().control())
        .grow(1);
    // Keys go up from the focused element: these work from the editor and from the grids.
    Predicate<Key> keys = key -> {
      if (key.modifiers() != (Key.CONTROL | Key.SHIFT)) {
        return false;
      }
      switch (key.name()) {
        case "C" -> commit(s);
        case "R" -> rollback(s);
        default -> {
          return false;
        }
      }
      return true;
    };
    return col()
        .grow(1)
        .minSize(0, 0)
        .onKey(keys)
        .child(top)
        .child(line == null ? Divider.divider() : line)
        .child(split)
        .child(footer(s));
  }

  /** The databases to switch to: those the tree read, and the one it is on. */
  private El databases(State s) {
    Theme t = Theme.CURRENT.get();
    var names = new LinkedHashSet<String>();
    names.add(database);
    names.addAll(s.catalog.databases(id));
    return Select.select(
            List.copyOf(names),
            database,
            n -> n.isEmpty() ? "No database" : n,
            databases,
            n -> use(s, n))
        .key("database")
        .label("Database")
        .w(6 * t.size().control());
  }

  private El editor(State s, App<State> app, Provider provider) {
    Highlighter syntax = switch (provider) {
      case POSTGRESQL -> Sql.of(Sql.Dialect.POSTGRES);
      case MARIADB, MYSQL -> Sql.of(Sql.Dialect.MYSQL);
      case MONGODB -> ShellSyntax.of();
    };
    var at = target(provider); // the lines of the statement Control+Enter runs
    this.editor.block(at == null ? 0 : at.start(), at == null ? 0 : at.end());
    El editor = CodeEditor.codeEditor(app, text, this.editor, syntax, v -> {
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
    editor.onKey(editor.onKey() == null ? keys : keys.or(editor.onKey()));
    return help.editor(s, app, provider, editor);
  }

  /** The results of the last runs in tabs, with Messages; what went wrong above them. */
  private El results(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    El note = note(s);
    if (outputs.isEmpty() && log.isEmpty() && working()) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .label("Results")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, waiting ? "Connecting…" : "Running…"));
    }
    if (outputs.isEmpty() && log.isEmpty()) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .label("Results")
          .child(note == null ? null : col().padding(t.space().m()).shrink(0).child(note))
          .child(EmptyState.emptyState(
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
    El content = at < 0 ? messages() : output(s, app, shown);
    El bar = Tabs.tabs(
            tabs,
            at < 0 ? outputs.size() : at,
            i -> shown = i < outputs.size() ? outputs.get(i) : null,
            i -> {},
            content,
            Tabs.options().switchKeys(false))
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
  private El note(State s) {
    Theme t = Theme.CURRENT.get();
    if (stranded) {
      var trouble = s.catalog.problem(s, new Node.Connection(id));
      var actions = new ArrayList<El>();
      if (trouble != null && trouble.password()) {
        actions.add(button(SECONDARY, "Enter password…", () -> retry(s, true)));
      }
      actions.add(button(SECONDARY, "Try again", () -> retry(s, false)).key("try-again"));
      return Callout.callout(
              Callout.Kind.DANGER,
              trouble == null ? "Not connected" : trouble.title(),
              trouble == null ? null : trouble.text(),
              actions.toArray(El[]::new))
          .key("unconnected");
    }
    // An outcome in doubt stays said, also over later runs, until the user has read it.
    // The act that holds the connection, as a save of it: nothing runs until it is done.
    var locked = s.locked(this);
    El lock = locked == null
        ? null
        : Callout.callout(
                Callout.Kind.INFO,
                locked,
                "Nothing runs on this console until that is done or given up.")
            .key("locked");
    El manual = unavailable == null || mode != Mode.AUTO
        ? null
        : Callout.callout(Callout.Kind.WARNING, "Manual is unavailable", unavailable)
            .key("manual-unavailable");
    El doubted = doubt == null
        ? null
        : Callout.callout(
                Callout.Kind.DANGER,
                UNSURE,
                doubt,
                button(SECONDARY, "Acknowledge", () -> acknowledge(s)).key("acknowledge"))
            .key("doubt");
    El failed = transaction != Transaction.FAILED
        ? null
        : Callout.callout(
                Callout.Kind.WARNING,
                "The transaction failed",
                "The server ignores every statement in it until it is rolled back; its changes are"
                    + " lost.",
                button(SECONDARY, "Rollback", canRollback() ? () -> rollback(s) : null)
                    .key("roll-back"))
            .key("failed");
    El shown = problem == null
        ? null
        : Callout.callout(Callout.Kind.DANGER, problem.title(), problem.text()).key("problem");
    var notes = new ArrayList<El>();
    for (var note : new El[] {lock, doubted, shown, manual, failed}) {
      if (note != null) {
        notes.add(note);
      }
    }
    return notes.isEmpty()
        ? null
        : notes.size() == 1
            ? notes.getFirst()
            : col().gap(t.space().s()).children(notes.toArray(El[]::new));
  }

  /** A result: its rows in the grid, or the number of rows it changed; whether it is pinned. */
  private El output(State s, App<State> app, Output o) {
    El pin = Button.toggleIcon(
            Symbol.PIN, o.pinned ? "Unpin" : "Pin", o.pinned, on -> o.pinned = on)
        .key("pin");
    if (o.fields == null) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .child(EmptyState.emptyState(
                  Symbol.CHECKMARK_CIRCLE,
                  rows(o.count, keepsDocuments(s)) + " changed",
                  null,
                  null)
              .key("changed"))
          .child(
              Card.footer(Label.label(Label.Variant.MUTED, brief(o.statement)).ellipsis(), pin));
    }
    boolean whole = !o.documents.isEmpty();
    String count = rows(o.rows.size(), whole);
    String about = brief(o.statement) + " · "
        + (o.more
            ? count + " so far, more as it scrolls"
            : o.cut
                ? "the first " + count + "; the rest was not read"
                : o.dropped > 0
                    ? "the first " + count + " of " + rows(o.rows.size() + o.dropped, whole)
                        + "; the rest was read and not kept"
                    : count);
    if (o.documents.isEmpty()) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .child(withValue(s, app, o, grid(s, app, o)))
          .child(Card.footer(
              Label.label(Label.Variant.MUTED, about).key("result-status").ellipsis(),
              value.button(),
              pin));
    }
    // Documents: as a grid, or whole, a page of those read so far, with the table's pager.
    var footer = new ArrayList<El>();
    footer.add(Segmented.segmented(
            List.of(TableTab.Shown.values()), o.shown, v -> v.title, v -> o.shown = v)
        .key("result-shown")
        .label("Show documents as"));
    El body;
    if (o.shown == TableTab.Shown.GRID) {
      footer.add(value.button());
      body = withValue(s, app, o, grid(s, app, o));
    } else {
      int from = o.page * PORTION;
      int to = Math.min(from + PORTION, o.documents.size());
      // As the table's: one more where its statement has more, which the next page reads.
      int shown = o.documents.size() + (o.more ? 1 : 0);
      footer.add(Label.label(
              Label.Variant.MUTED, to <= from ? "No documents" : TableTab.range(from + 1, to, true))
          .key("rows-in-view"));
      footer.add(TableTab.paging(
          o.page > 0,
          (o.page + 1) * PORTION < shown,
          () -> turn(s, o, 0),
          () -> turn(s, o, o.page - 1),
          () -> turn(s, o, o.page + 1),
          () -> turn(s, o, (shown - 1) / PORTION)));
      if (to > from) {
        o.whole.show("p" + o.page, o.documents, from, to);
        body = o.whole.view(app, o.shown, s.zone, "Documents of result " + o.number);
      } else {
        body = col()
            .center()
            .child(Label.label(Label.Variant.MUTED, o.more ? "Reading documents…" : "No documents")
                .key("no-documents"));
      }
    }
    footer.add(pin);
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(body.grow(1))
        .child(Card.footer(
            Label.label(Label.Variant.MUTED, about).key("result-status").ellipsis(),
            footer.toArray(El[]::new)));
  }

  /** Shows the page of the result's documents, reading the next portion where it is not read. */
  private void turn(State s, Output o, int page) {
    o.page = Math.max(0, page);
    int at = o.documents.size();
    if (o.page * PORTION >= at) {
      want(s, o, at, at + PORTION - 1);
    }
  }

  /**
   * The result's grid with the value panel, which shows the current cell's value as the result
   * holds it: a text cut short stays so, as the statement may not name a row to read it by.
   */
  private El withValue(State s, App<State> app, Output o, El grid) {
    int row = o.selection.row();
    int column = o.selection.column();
    ValuePanel.Cell cell = null;
    String note = null;
    // A row beyond those read is the one that asks for more.
    if (value.open
        && row >= 0
        && row < o.rows.size() + (o.more ? 1 : 0)
        && column >= 0
        && column < o.fields.size()) {
      var field = o.fields.get(column);
      var v = row < o.rows.size() ? o.rows.get(row).get(column) : null;
      cell = new ValuePanel.Cell(field.name(), field.type(), field.kind(), v);
      if (v instanceof Cut) {
        note = Cells.icon(v).tooltip() + "; a result keeps no more";
      }
    }
    return value.around(app, s.zone, grid, cell, note, cell != null && row >= o.rows.size());
  }

  /** The result's rows, read-only, with the table tab's cells. */
  private El grid(State s, App<State> app, Output o) {
    Theme t = Theme.CURRENT.get();
    var zone = s.zone;
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
              row -> Cells.text(o.rows.get(row).get(at), zone))
          .typeName(field.type())
          .sortable(false);
      if (kind == Kind.TEXT || kind == Kind.JSON || kind == Kind.OTHER) {
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
    return DataGrid.dataGrid(columns, shown, options)
        .key("grid")
        .label("Rows of result " + o.number);
  }

  /** What ran, how long it took, what it gave, and what the server said along the way. */
  private El messages() {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    var columns = List.of(
        new DataGrid.Column(
                "Statement",
                16 * unit,
                DataGrid.DataType.TEXT,
                row -> log.get(row).statement())
            .sortable(false),
        new DataGrid.Column(
                "Took",
                4 * unit,
                Format.DURATION.type(),
                row -> Format.DURATION.text(log.get(row).took()))
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
            columns, log.size(), DataGrid.options().select(logSelection).rowNumbers())
        .key("messages")
        .label("Messages")
        .grow(1);
  }

  /** The last run's time and rows, or that it runs. */
  private El footer(State s) {
    Theme t = Theme.CURRENT.get();
    El status;
    if (confirming != null) {
      status = Label.label(Label.Variant.MUTED, "Waiting for your answer").key("run-status");
    } else if (busy()) {
      var what = waiting
          ? "Connecting…"
          : switching != null
              ? "Switching to " + (switching.isEmpty() ? "no database" : switching) + "…"
              : !firm
                  ? writes ? "Running; rows show once all are read…" : "Running…"
                  : COMMIT.equals(running)
                      ? "Committing…"
                      : ROLLBACK.equals(running) ? "Rolling back…" : "Switching auto-commit…";
      status = row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(Progress.spinner().label("Running"))
          .child(Label.label(Label.Variant.MUTED, what))
          .key("run-status");
    } else {
      status = Label.label(Label.Variant.MUTED, status(s)).key("run-status").ellipsis();
    }
    El badge = badge();
    if (said == null) {
      return badge == null ? Card.footer(status) : Card.footer(status, badge);
    }
    El words = Label.label(Label.Variant.MUTED, said).key("said");
    return badge == null ? Card.footer(status, words) : Card.footer(status, words, badge);
  }

  /** The transaction, as the server last said it; in Auto none while none is open. */
  private El badge() {
    var o = Badge.options().toolbar();
    if (doubt != null) {
      o.kind(Badge.Kind.DANGER).strong().icon(Symbol.ERROR_CIRCLE);
      return Badge.badge("Outcome not known", o).key("transaction");
    }
    String text = switch (transaction) {
      case NONE -> {
        o.kind(Badge.Kind.NEUTRAL);
        yield mode == Mode.MANUAL ? "No transaction" : null;
      }
      case OPEN -> {
        if (empty) {
          o.kind(Badge.Kind.NEUTRAL);
          yield "Transaction open · empty";
        }
        o.kind(Badge.Kind.WARNING).strong().icon(Symbol.LOCK_OPEN);
        yield "Transaction open"
            + (inTransaction > 0
                ? " · " + inTransaction + (inTransaction == 1 ? " statement" : " statements")
                : "");
      }
      case FAILED -> {
        o.kind(Badge.Kind.DANGER).strong().icon(Symbol.ERROR_CIRCLE);
        yield "Transaction failed";
      }
      case UNKNOWN -> {
        o.kind(Badge.Kind.WARNING).icon(Symbol.LOCK_OPEN);
        yield "Transaction may be open";
      }
    };
    return text == null ? null : Badge.badge(text, o).key("transaction");
  }

  /**
   * The question about what may be open, while it is asked; in the console's window. What it
   * offers follows what is known: Commit only where the server said a transaction is open,
   * Rollback where anything may be, and while Commit or Rollback runs nothing but waiting.
   */
  El question(State s) {
    Theme t = Theme.CURRENT.get();
    var asked = asked(s);
    if (asked == null) {
      return null;
    }
    var name = s.title(this);
    var before = asked.what();
    var buttons = new ArrayList<El>();
    buttons.add(button(SECONDARY, "Cancel", () -> answer(s, null)).key("answer-cancel"));
    El rollback = button(PRIMARY, "Rollback", () -> answer(s, ROLLBACK)).key("answer-rollback");
    String title;
    String text;
    boolean waits = firm || work != null && changes();
    if (doubt != null) {
      title = UNSURE;
      text = "On " + name + ", " + doubt + " Go on, and " + before + "?";
      buttons.add(button(PRIMARY, "Continue", () -> answer(s, CONTINUE)).key("answer-continue"));
    } else if (firm) {
      title = COMMIT.equals(running)
          ? "Waiting for the commit"
          : ROLLBACK.equals(running) ? "Waiting for the rollback" : "Waiting for the server";
      text = name + " waits for the server's answer. Once nothing is left open, " + before + ".";
      if (overdue) {
        text += " It has not answered for a while. Or end the connection now: it is closed, the"
            + " server rolls back what was not committed, and what this did is not known.";
        buttons.add(endIt(s));
      }
    } else if (waits) {
      // What it promises nothing of: no Rollback, which could not say what it undid.
      title = "Waiting for " + brief(running);
      text = brief(running) + " still runs on " + name + ". Wait: once it has ended with nothing"
          + " left open, " + before + ". Or go on now: the connection ends, the server rolls back"
          + " what was not committed, and what it did with this statement will not be known.";
      buttons.add(button(
              SECONDARY,
              "Go on now",
              () -> answer(s, GO_ON),
              Button.options().tone(Button.Tone.DANGER))
          .key("answer-go-on"));
    } else if (work != null) {
      title = "Roll back the transaction?";
      text = "A statement still runs on " + name + ", and a transaction may be open. Roll back,"
          + " which cancels the statement, or wait: once it has ended with nothing open, " + before
          + ".";
      buttons.add(rollback);
    } else if (transaction == Transaction.FAILED) {
      title = "Roll back the transaction?";
      text = "The transaction of " + name + " failed: the server takes nothing more in it but a"
          + " rollback. Roll it back before " + before + ".";
      buttons.add(rollback);
    } else if (!canCommit()) {
      title = "Roll back the transaction?";
      text = "A transaction may be open on " + name + ": the server has not said. Roll it back"
          + " before " + before + ".";
      buttons.add(rollback);
    } else {
      title = "Commit the transaction?";
      // A transaction is open, which is not to say that anything in it changed.
      text = name + " has a transaction open"
          + (inTransaction > 0
              ? ", after " + inTransaction + (inTransaction == 1 ? " statement" : " statements")
              : "")
          + ". Commit it or roll it back before " + before + ".";
      buttons.add(button(SECONDARY, "Rollback", () -> answer(s, ROLLBACK)).key("answer-rollback"));
      buttons.add(button(PRIMARY, "Commit", () -> answer(s, COMMIT)).key("answer-commit"));
    }
    if (endFailed && !firm && work == null && doubt == null) {
      text += " Or end the connection: it is closed, and the server rolls back what was not"
          + " committed.";
      buttons.add(1, endIt(s));
    }
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(text).wrapText())
        .child(
            waits && doubt == null
                ? row()
                    .gap(t.space().s())
                    .align(Style.Align.CENTER)
                    .child(Progress.spinner().label("Waiting"))
                    .child(Label.label(Label.Variant.MUTED, "Waiting for the server…"))
                : null)
        // Why the last Commit or Rollback did not end it, where it did not.
        .child(
            problem == null || waits || doubt != null
                ? null
                : Callout.callout(Callout.Kind.DANGER, problem.title(), problem.text())
                    .key("question-problem"));
    return Dialog.dialog(title, content, buttons, () -> answer(s, null))
        .key("transaction-question");
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
    log.add(new Logged(Level.WARNING, brief(asked.sql()), null, "Not run"));
  }

  /** History: the statements run on the connection, to put one into the editor. */
  void openHistory() {
    history = new HistoryDialog();
  }

  /**
   * Puts the statement in place of the selection; without one, as a statement of its own on a line
   * of its own: at the caret, or after the statement the caret is in, closing the statement before
   * it where that has no terminator yet. As one step that Undo takes back; the caret after it,
   * where Run runs it.
   */
  void insert(App<State> app, String sql) {
    int from = Math.min(editor.caret(), editor.anchor());
    int to = Math.min(Math.max(editor.caret(), editor.anchor()), text.length());
    var put = sql;
    var provider = provider(app.state());
    if (from == to && provider != null) {
      int here = from;
      var all = statements(provider);
      var part = all.around(from); // the statement the caret writes in, up to its terminator
      var open = all.open() ? all.statements().getLast() : null; // none to end it
      var comment = Statements.pieces(text, provider).stream()
          .filter(x -> x.kind() == Statements.Piece.Kind.COMMENT && x.holds(here))
          .findFirst();
      // A block comment not closed, executable ones too, holds all after it: before its opener.
      var unclosed = comment.filter(x -> x.open() && text.startsWith("/*", x.start()));
      boolean inside = unclosed.isEmpty()
          && all.statements().stream()
              .anyMatch(x -> part.start() <= x.start()
                  && x.start() < here
                  && (here <= x.end() || !x.equals(open)));
      int at = unclosed.isPresent()
          ? unclosed.get().start()
          : inside ? part.end() : comment.map(Statements.Piece::end).orElse(from);
      put = (at > 0 && text.charAt(at - 1) != '\n' ? "\n" : "") + sql + ";"
          + (at < text.length() && text.charAt(at) != '\n' ? "\n" : "");
      from = at;
      to = at;
      if (open != null && open.start() < at) {
        // Closed right after its last word before the entry, not after its comments.
        int end = Math.min(open.end(), at);
        while (end > open.start() && Character.isWhitespace(text.charAt(end - 1))) {
          end--;
        }
        put = ";" + text.substring(end, at) + put;
        from = end;
      }
    }
    editor.replace(from, to, put, put.stripTrailing().length());
  }

  /** What is asked before a statement is sent, while it is; in the console's window. */
  El confirmation(State s) {
    Theme t = Theme.CURRENT.get();
    var asked = confirming;
    if (asked == null) {
      return null;
    }
    var options = asked.danger() ? Button.options().tone(Button.Tone.DANGER) : Button.options();
    return Dialog.dialog(
            asked.title(),
            col()
                .w(ConnectionDialog.width())
                .gap(t.space().m())
                .child(Label.label(asked.text()).wrapText()),
            List.of(
                button(SECONDARY, "Cancel", this::decline).key("confirm-cancel"),
                button(PRIMARY, "Run anyway", () -> proceed(s), options).key("confirm-run")),
            this::decline)
        .key("run-question");
  }

  /** The way out where the server does not answer, or its answer keeps failing. */
  private El endIt(State s) {
    return button(
            SECONDARY,
            "End the connection",
            () -> answer(s, GO_ON),
            Button.options().tone(Button.Tone.DANGER))
        .key("answer-go-on");
  }

  /**
   * "Ran in 12 ms · 3 rows": the last run's time on the server, and its rows; "Not connected"
   * while neither the console nor the tree is, and "Ready" before the first run.
   */
  private String status(State s) {
    var status = !connected() && !s.catalog.connected(id) ? "Not connected" : ran(s);
    // shortcut: Manual reads every result whole before showing it, as it may not stay open in a
    // transaction; read lazily in Manual too once giving up a read can keep the transaction.
    return mode != Mode.MANUAL ? status : status + " · " + WHOLE;
  }

  static final String WHOLE = "Manual reads each result whole";

  private String ran(State s) {
    if (took == null || statements == 0 && !cancelled && !failedRun) {
      return "Ready";
    }
    if (statements == 0 && !cancelled) {
      return "Not run"; // the session could not be opened
    }
    var ms = took.toMillis() + " ms";
    var text = new StringBuilder();
    if (cancelled) {
      text.append("Cancelled");
    } else if (failedRun && statements == 1) {
      text.append("Failed after ").append(ms);
    } else {
      text.append("Ran");
      if (statements > 1) {
        text.append(' ').append(statements).append(" statements");
      }
      text.append(" in ").append(ms).append(failedRun ? ", the last failed" : "");
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
    boolean whole = keepsDocuments(s);
    if (rows) {
      text.append(" · ").append(rows(read, whole)).append(more ? " so far" : "");
    }
    if (counts) {
      text.append(" · ").append(rows(changed, whole)).append(" changed");
    }
    return text.toString();
  }
}

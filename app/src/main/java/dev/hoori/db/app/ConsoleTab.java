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
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Sql;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.Statement;
import dev.hoori.db.core.sql.Statements;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 *   <li>What is not known from the server may be open: {@link #atStake} is the one test, and it
 *       holds while anything runs, while the server is asked, where it did not say, and while an
 *       outcome is in doubt and the user has not read it.
 *   <li>Auto-commit changes only in {@link #setMode}, where the server has just said that nothing
 *       is open; every statement's call applies the console's mode there first, and the mode
 *       shown follows the session's as the driver has it from the server.
 *   <li>Committed and Rolled back are said only where the server answered so; a COMMIT is sent
 *       only where it said a transaction is open. What may end a transaction ({@link #mayEnd}),
 *       cut short or lost, leaves its outcome in doubt, said until read.
 *   <li>Commit is offered only where the server said a transaction is open and nothing runs;
 *       Rollback, the safe way out, wherever something may be open on the session, also while the
 *       server is asked; neither runs twice at once, and no Rollback cuts short what may end one.
 *   <li>Every way out that would end the session or its transaction asks first, through {@link
 *       State#asks}, which holds the one way out that waits, and again at the moment of its act.
 * </ul>
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

  /** Whether each statement commits by itself, or the user ends the transaction. */
  enum Mode {
    AUTO,
    MANUAL
  }

  /**
   * The one way out in the app that waits for a console's question: the console that asks, what
   * the way out is, how it goes on, and what asked for it where that can be given up, as a dialog;
   * see {@link State#asks}.
   */
  record Asked(ConsoleTab console, String before, Runnable then, Object owner) {}

  /** A question to the server about the transaction: on which session, and after what was known. */
  record Check(Lane lane, int at) {}

  /** A portion, and the session's auto-commit after it; null while rows are still to come. */
  record Ran(Portion portion, Boolean autoCommit) {}

  /**
   * Commit's or Rollback's call: the transaction the server said before a COMMIT, null before a
   * ROLLBACK; the statement's portion, null where the COMMIT was not sent; the auto-commit after.
   */
  record Ended(Transaction before, Portion portion, Boolean autoCommit) {}

  /** A switch of auto-commit: whether it was made, and the transaction after. */
  record Switched(boolean done, Transaction now) {}

  static final String COMMIT = "COMMIT";
  static final String ROLLBACK = "ROLLBACK";

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
  LoadedDriver driver; // the session's, from when it is opened until it is closed
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
  private boolean failedRun; // a statement of the last run failed, or could not be run
  private boolean closed;
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
  // The statement under way may end a transaction, by itself or inside: if it is cut short or its
  // connection is lost, whether it took effect is not known.
  private boolean careful;
  // Why the outcome of what may have ended the transaction is not known, until the user has read
  // it; null for nothing in doubt.
  String doubt;
  private Work probe; // the question to the server about the transaction, or null
  Check probing; // which one it is
  private int learned; // what was learned of it, counted: an answer that comes late is dropped

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
    askHere(s);
    follow(s);
  }

  /** What the tree's connection asks for, its password, it asks in this console's window. */
  private void askHere(State s) {
    var live = s.catalogue.live(id);
    if (live != null) {
      live.window = s.tabs.groupOf(this);
    }
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
    empty = false;
    running = sql;
    careful = mayEnd(sql, s.profile(id).provider());
    var held = new AtomicReference<Servers.Run>();
    open = held;
    var mode = this.mode;
    var asked = lane.submit(link -> start(link, sql, mode, held));
    call = asked;
    work = background(
        s,
        "run a statement",
        asked,
        ran -> {
          work = null;
          ran(s, sql, ran);
        },
        error -> {
          work = null;
          failedRun = true;
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
      driver = session.driver();
      password = session.password();
    }
    return lane;
  }

  /**
   * Waits for a call on the session off the app's thread; once its result or failure is taken,
   * the question sees whether what it asked about may go on.
   */
  private <T> Work background(
      State s, String what, Future<T> asked, Consumer<T> done, Consumer<Exception> failed) {
    return s.app.background(
        what,
        () -> Lane.await(asked),
        value -> {
          done.accept(value);
          settle(s);
        },
        error -> {
          failed.accept(error);
          settle(s);
        });
  }

  // On the lane: what the console's calls do with the session.

  /**
   * The one place where the session's auto-commit changes, as JDBC commits a transaction that is
   * open where it is turned on: only where the server says, right before, that none is open, or
   * where the one open is empty, the server's own begun as the user's Commit or Rollback ended
   * theirs.
   */
  static Switched setMode(Servers.Link link, boolean manual, boolean empty) throws QueryException {
    var now = link.transaction();
    if (now != Transaction.NONE && !(now == Transaction.OPEN && empty)) {
      return new Switched(false, now);
    }
    link.manual(manual);
    return new Switched(true, link.transaction());
  }

  /**
   * A statement's first portion. The session takes the console's mode first, wherever its
   * auto-commit is not that, as on a session just opened, also after its first statement was
   * cancelled: never under a transaction, where it stays as the server has it, and the console
   * follows it.
   */
  static Ran start(Servers.Link link, String sql, Mode mode, AtomicReference<Servers.Run> held)
      throws QueryException {
    var auto = link.autoCommit();
    if (auto != null && auto != (mode == Mode.AUTO)) {
      setMode(link, mode == Mode.MANUAL, false);
    }
    var run = link.run(sql);
    held.set(run);
    return ran(link, run.next(PORTION));
  }

  // First words of statements that end or may end a transaction: by themselves, as COMMIT, END
  // and ABORT, PREPARE TRANSACTION and XA do; with an implicit commit, as BEGIN and START do on
  // MariaDB and MySQL; or inside, as a procedure, a DO block or a prepared statement may.
  private static final Set<String> ENDING = Set.of(
      "COMMIT",
      "ROLLBACK",
      "END",
      "ABORT",
      "BEGIN",
      "START",
      "PREPARE",
      "XA",
      "CALL",
      "DO",
      "EXECUTE",
      "EXEC");

  // MariaDB's and MySQL's that commit implicitly before they run.
  private static final Set<String> COMMITTING = Set.of(
      "CREATE",
      "ALTER",
      "DROP",
      "RENAME",
      "TRUNCATE",
      "GRANT",
      "REVOKE",
      "LOCK",
      "UNLOCK",
      "SET",
      "LOAD",
      "ANALYZE",
      "OPTIMIZE",
      "REPAIR",
      "CHECK",
      "CACHE",
      "FLUSH",
      "RESET",
      "INSTALL",
      "UNINSTALL",
      "STOP",
      "CHANGE",
      "PURGE");

  /**
   * Whether the statement may end a transaction, by its first word. Taking one for such that does
   * not costs a careful wait; missing one would cost data, so the lists err to that side.
   */
  static boolean mayEnd(String sql, Provider provider) {
    var word = Statements.firstWord(sql, provider).toUpperCase(Locale.ROOT);
    return ENDING.contains(word) || provider != Provider.POSTGRESQL && COMMITTING.contains(word);
  }

  /** The portion, with the session's auto-commit after it where its statement is over. */
  static Ran ran(Servers.Link link, Portion portion) {
    return new Ran(portion, portion.more() ? null : link.autoCommit());
  }

  /**
   * Commit or Rollback. A COMMIT is sent only where the server says, right before, that a
   * transaction is open: PostgreSQL answers one in a failed transaction by rolling it back, without
   * an error, and MariaDB and MySQL roll one back by themselves, as after a deadlock.
   */
  static Ended finish(Servers.Link link, String sql) throws QueryException {
    Transaction before = null;
    if (sql.equals(COMMIT)) {
      before = link.transaction();
      if (before != Transaction.OPEN) {
        return new Ended(before, null, null);
      }
    }
    var run = link.run(sql);
    try {
      var portion = run.next(1);
      return new Ended(before, portion, portion.more() ? null : link.autoCommit());
    } finally {
      run.close();
    }
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

  private void ran(State s, String sql, Ran ran) {
    var portion = ran.portion();
    var failure = failure(portion);
    statements++;
    took = took.plus(portion.elapsed());
    var before = outputs.size();
    learn(portion);
    follow(ran.autoCommit());
    inTransaction = transaction == Transaction.NONE ? 0 : inTransaction + 1;
    take(sql, portion);
    if (before < outputs.size()) {
      shown = outputs.get(before);
    }
    heard(failure == null ? Level.RAN : Level.ERROR, sql, portion.elapsed(), outcome(portion));
    messages(portion);
    if (failure != null) {
      queue.clear();
      failedRun = true;
      if (careful && failure.kind() == Failure.Kind.CONNECTION_LOST) {
        doubt(sql, "the connection was lost while it ran");
      }
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

  /**
   * Commit's or Rollback's call ended: the results tabs stay as they were. It says Committed or
   * Rolled back only where the server answered so; a connection lost during a COMMIT leaves its
   * outcome in doubt. A way out that waited for it goes on only where it succeeded.
   */
  private void ended(State s, String sql, Ended ended) {
    var portion = ended.portion();
    if (portion == null) { // a COMMIT not sent: nothing it could commit was open
      transaction = ended.before();
      learned++;
      if (transaction == Transaction.NONE) {
        inTransaction = 0;
      }
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
    var done = sql.equals(COMMIT) ? "Committed" : "Rolled back";
    heard(
        failure == null ? Level.RAN : Level.ERROR,
        sql,
        portion.elapsed(),
        failure == null ? done : outcome(portion));
    messages(portion);
    if (failure == null) {
      learn(portion);
      follow(ended.autoCommit());
      // What is open now the server began by itself, as with MySQL's completion_type=1.
      empty = transaction != Transaction.NONE;
      inTransaction = 0;
      say(s, done);
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
      learn(portion);
      fail(s, failure);
    }
    if (transaction == Transaction.UNKNOWN && lane != null) {
      check(s);
    }
  }

  static final String UNSURE = "The outcome is not known";

  /**
   * What may have ended the transaction was cut short or lost its connection: whether it took
   * effect is not known, which the console says until the user has read it, and Messages keeps.
   */
  private void doubt(String sql, String why) {
    doubt = brief(sql) + ": " + why + ". Whether it took effect is not known; check the data"
        + " before relying on it.";
    log.add(new Logged(Level.ERROR, brief(sql), null, "Outcome not known: " + why));
  }

  /**
   * What the portion says of the transaction, which stays UNKNOWN where it does not say, as while
   * MariaDB's and MySQL's rows are still to come; a lost connection says nothing of it, and the
   * failure says what is gone.
   */
  private void learn(Portion portion) {
    var failure = failure(portion);
    if (failure != null && failure.kind() == Failure.Kind.CONNECTION_LOST) {
      return;
    }
    transaction = portion.transaction();
    learned++;
  }

  /** Follows the session's auto-commit as the driver has it from the server, where it says. */
  private void follow(Boolean autoCommit) {
    if (autoCommit != null) {
      mode = autoCommit ? Mode.AUTO : Mode.MANUAL;
    }
  }

  /**
   * Asks the server for the transaction, where a portion did not say; on the lane, before what is
   * asked after it, while the console goes on. Its answer, or its failure, is dropped where
   * something said more since or the session is another; a failure that leaves the connection
   * leaves the transaction unknown.
   */
  private void check(State s) {
    var asked = new Check(lane, ++learned);
    probing = asked;
    var question = lane.submit(Servers.Link::transaction);
    probe = background(
        s,
        "ask for the transaction",
        question,
        now -> probed(asked, now),
        e -> probed(s, asked, e));
  }

  /** The server's answer to the probe, dropped where something said more since. */
  void probed(Check asked, Transaction now) {
    if (probing == asked) {
      probe = null;
      probing = null;
    }
    if (learned == asked.at() && lane == asked.lane()) {
      transaction = now;
      if (now == Transaction.NONE) {
        inTransaction = 0;
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
    if (lost(error)) {
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
    // What may have ended the transaction leaves it in doubt, not gone.
    var gone = f.kind() == Failure.Kind.CONNECTION_LOST && !careful ? gone() : null;
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
    if (output != reading || !output.more || work != null || last < output.rows.size() - 50) {
      return;
    }
    var held = open;
    var sql = running;
    // leave() clears it only after this
    var asked = lane.submit(link -> ran(link, held.get().next(PORTION)));
    call = asked;
    work = background(
        s,
        "read rows",
        asked,
        ran -> {
          work = null;
          var portion = ran.portion();
          int before = outputs.size();
          take(sql, portion);
          learn(portion);
          follow(ran.autoCommit());
          messages(portion);
          var failure = failure(portion);
          if (failure != null) {
            heard(Level.ERROR, sql, portion.elapsed(), outcome(portion));
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
   * auto-commit run to their end: cut short, their outcome would not be known.
   */
  void cancel(State s) {
    // shortcut: a careful statement may be cancelled, which leaves its outcome in doubt; a long
    // CALL must stay stoppable, and the doubt is said until read.
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
    if (careful) {
      doubt(running, "it was cancelled");
    }
    if (lane != null) {
      check(s);
    }
  }

  /**
   * Switches the database: on the open connection where the server can, else the session closes
   * and the next run opens one to it. Asks first where a transaction may be open.
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
    if (s.asks(
        List.of(this),
        "it switches to " + (name.isEmpty() ? "no database" : name),
        () -> use(s, name))) {
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
    work = background(
        s,
        "switch the database",
        asked,
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
    firm = false;
  }

  /**
   * The console's session ends: the tree's connection was disconnected, its profile changed, or
   * the tab closed. Every way there asked first where something was at stake; where a way did not,
   * the console says what that cost.
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
    if (stake) {
      problem = new Problem(
          "The connection ended",
          "The console's connection ended while a transaction may have been open: the server"
              + " rolls back what it did not commit.");
      log.add(new Logged(Level.WARNING, "", null, "The connection ended"));
    }
    closeSession();
  }

  private void closeSession() {
    transaction = Transaction.NONE;
    inTransaction = 0;
    empty = false;
    firm = false;
    learned++;
    if (probe != null) {
      probe.cancel();
      probe = null;
      probing = null;
    }
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
    driver = null;
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

  /**
   * Whether a transaction may be open, the one test of every way out, the questions, the tab's
   * mark and Rollback. Not without a session; always while a call runs or the server is asked, as
   * the server may have moved on since it last said; else as it last said, where a transaction it
   * began by itself as the user's Commit or Rollback ended theirs holds nothing.
   */
  boolean atStake() {
    if (doubt != null) {
      return true; // until the user has read it
    }
    if (lane == null) {
      return false;
    }
    if (work != null || probe != null) {
      return true;
    }
    return switch (transaction) {
      case NONE -> false;
      case OPEN -> !empty;
      case FAILED, UNKNOWN -> true;
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

  /** Auto or Manual; asks first where a transaction may be open. */
  void mode(State s, Mode mode) {
    if (busy() || mode == this.mode) {
      return;
    }
    if (lane == null) {
      this.mode = mode; // the next session takes it
      return;
    }
    var before = "it switches to " + (mode == Mode.AUTO ? "Auto" : "Manual");
    if (s.asks(List.of(this), before, () -> mode(s, mode))) {
      return;
    }
    boolean empty = this.empty;
    firm = true;
    running = "Auto-commit";
    var asked = lane.submit(link -> setMode(link, mode == Mode.MANUAL, empty));
    call = asked;
    work = background(
        s,
        "switch auto-commit",
        asked,
        switched -> {
          work = null;
          firm = false;
          transaction = switched.now();
          learned++;
          this.empty = empty && transaction != Transaction.NONE;
          if (transaction == Transaction.NONE) {
            inTransaction = 0;
          }
          if (switched.done()) {
            this.mode = mode;
            log.add(new Logged(
                Level.RAN, "Auto-commit", null, mode == Mode.AUTO ? "Turned on" : "Turned off"));
          } else {
            s.asks(List.of(this), before, () -> mode(s, mode)); // the server had one open after all
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
    if (canCommit()) {
      end(s, COMMIT);
    }
  }

  /** Rollback: as the statement ROLLBACK. */
  void rollback(State s) {
    if (canRollback()) {
      end(s, ROLLBACK);
    }
  }

  private void end(State s, String sql) {
    leave();
    queue.clear();
    problem = null;
    firm = true;
    careful = false;
    running = sql;
    var asked = lane.submit(link -> finish(link, sql));
    call = asked;
    work = background(
        s,
        sql.equals(COMMIT) ? "commit" : "roll back",
        asked,
        ended -> {
          work = null;
          firm = false;
          ended(s, sql, ended);
        },
        error -> {
          work = null;
          firm = false;
          failed(s, sql, error);
        });
  }

  /** Shows the console's tab, in its window raised, for the question {@link State#asks} put. */
  void show(State s) {
    var group = s.tabs.groupOf(this);
    if (group != null) {
      s.tabs.select(group, group.tabs().indexOf(this));
      if (group.window() != null) {
        group.window().raise();
      }
    }
  }

  /** The way out that waits for this console's question, or null. */
  private Asked asked(State s) {
    return s.asked != null && s.asked.console() == this ? s.asked : null;
  }

  /**
   * The question's answer. Commit or Rollback run, and the way out goes on once nothing is at stake
   * any more, only where they succeeded; a Rollback while a statement runs cancels it first, but
   * none is queued behind a Commit or a statement that may end the transaction. Continue goes on
   * past an outcome in doubt, which the user has read. Cancel, null, gives the way out up.
   */
  void answer(State s, String choice) {
    var asked = asked(s);
    if (asked == null) {
      return;
    }
    if (choice == null) {
      s.asked = null;
      return;
    }
    switch (choice) {
      case CONTINUE -> {
        s.asked = null;
        doubt = null;
        asked.then().run();
        return;
      }
      case COMMIT -> commit(s);
      case ROLLBACK -> {
        if (firm || careful && work != null) {
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

  static final String CONTINUE = "CONTINUE";

  /** Acknowledge: the user has read that an outcome is not known. */
  void acknowledge(State s) {
    doubt = null;
    settle(s);
  }

  /**
   * The way out that waited for this console goes on once nothing is at stake any more; after
   * every call's result, and every answer. A failure gives it up before ({@link State#drop}).
   */
  private void settle(State s) {
    var asked = asked(s);
    if (asked != null && !atStake()) {
      s.asked = null;
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
    var actions = new ArrayList<El>();
    actions.add(databases(t, s));
    actions.add(Tooltip.tooltip(
        t,
        Segmented.segmented(
                t,
                List.of(Mode.values()),
                mode,
                m -> m == Mode.AUTO ? "Auto" : "Manual",
                busy ? null : m -> mode(s, m))
            .key("mode")
            .label("Transactions"),
        "Auto commits each statement by itself; in Manual, Commit or Rollback ends the"
            + " transaction"));
    if (mode == Mode.MANUAL || transaction != Transaction.NONE) {
      actions.add(Tooltip.tooltip(
          t,
          button(
                  t,
                  SECONDARY,
                  "Commit",
                  // A busy button needs its handler, which does nothing while it runs.
                  canCommit() || firm && COMMIT.equals(running) ? () -> commit(s) : null,
                  Button.options().icon(Symbol.CHECKMARK).busy(firm && COMMIT.equals(running)))
              .key("commit")
              .keepFocus(),
          "Commit (Control+Shift+C)"));
      actions.add(Tooltip.tooltip(
          t,
          button(
                  t,
                  SECONDARY,
                  "Rollback",
                  canRollback() || firm && ROLLBACK.equals(running) ? () -> rollback(s) : null,
                  Button.options().icon(Symbol.ARROW_UNDO).busy(firm && ROLLBACK.equals(running)))
              .key("rollback")
              .keepFocus(),
          "Roll back (Control+Shift+R)"));
    }
    actions.add(button(
            t,
            PRIMARY,
            "Run",
            firm ? null : () -> run(s),
            Button.options().icon(Symbol.PLAY).busy(busy && !firm))
        .key("run")
        .keepFocus());
    actions.add(button(t, SECONDARY, "Run all", busy ? null : () -> runAll(s))
        .key("run-all")
        .keepFocus());
    actions.add(button(t, SECONDARY, "Cancel", busy && !firm ? () -> cancel(s) : null)
        .key("cancel")
        .keepFocus());
    El top =
        // Without the provider's name, which the tree says: Commit and Rollback need its room.
        Card.toolbar(t, State.name(profile), null, actions.toArray(El[]::new));
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
        .label("Database")
        .w(6 * t.size().control());
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
    // An outcome in doubt stays said, also over later runs, until the user has read it.
    El doubted = doubt == null
        ? null
        : Callout.callout(
                t,
                Callout.Kind.DANGER,
                UNSURE,
                doubt,
                button(t, SECONDARY, "Acknowledge", () -> acknowledge(s)).key("acknowledge"))
            .key("doubt");
    El failed = transaction != Transaction.FAILED
        ? null
        : Callout.callout(
                t,
                Callout.Kind.WARNING,
                "The transaction failed",
                "The server ignores every statement in it until it is rolled back; its changes are"
                    + " lost.",
                button(t, SECONDARY, "Rollback", canRollback() ? () -> rollback(s) : null)
                    .key("roll-back"))
            .key("failed");
    El shown = problem == null
        ? null
        : Callout.callout(t, Callout.Kind.DANGER, problem.title(), problem.text())
            .key("problem");
    var notes = new ArrayList<El>();
    for (var note : new El[] {doubted, shown, failed}) {
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
              : !firm
                  ? "Running…"
                  : COMMIT.equals(running)
                      ? "Committing…"
                      : ROLLBACK.equals(running) ? "Rolling back…" : "Switching auto-commit…";
      status = row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(Progress.spinner(t).label("Running"))
          .child(Label.label(t, Label.Variant.MUTED, what))
          .key("run-status");
    } else {
      status = Label.label(t, Label.Variant.MUTED, status()).key("run-status").ellipsis();
    }
    El badge = badge(t);
    if (said == null) {
      return badge == null ? Card.footer(t, status) : Card.footer(t, status, badge);
    }
    El words = Label.label(t, Label.Variant.MUTED, said).key("said");
    return badge == null ? Card.footer(t, status, words) : Card.footer(t, status, words, badge);
  }

  /** The transaction, as the server last said it; in Auto none while none is open. */
  private El badge(Theme t) {
    var o = Badge.options().toolbar();
    if (doubt != null) {
      o.kind(Badge.Kind.DANGER).strong().icon(Symbol.ERROR_CIRCLE);
      return Badge.badge(t, "Outcome not known", o).key("transaction");
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
    return text == null ? null : Badge.badge(t, text, o).key("transaction");
  }

  /**
   * The question about what may be open, while it is asked; in the console's window. What it
   * offers follows what is known: Commit only where the server said a transaction is open,
   * Rollback where anything may be, and while Commit or Rollback runs nothing but waiting.
   */
  El question(Theme t, State s) {
    var asked = asked(s);
    if (asked == null) {
      return null;
    }
    var name = s.title(this);
    var before = asked.before();
    var buttons = new ArrayList<El>();
    buttons.add(button(t, SECONDARY, "Cancel", () -> answer(s, null)).key("answer-cancel"));
    El rollback = button(t, PRIMARY, "Rollback", () -> answer(s, ROLLBACK)).key("answer-rollback");
    String title;
    String text;
    boolean waits = firm || careful && work != null;
    if (doubt != null) {
      title = UNSURE;
      text = "On " + name + ", " + doubt + " Go on, and " + before + "?";
      buttons.add(button(t, PRIMARY, "Continue", () -> answer(s, CONTINUE)).key("answer-continue"));
    } else if (waits) {
      title = COMMIT.equals(running)
          ? "Waiting for the commit"
          : ROLLBACK.equals(running)
              ? "Waiting for the rollback"
              : careful ? "Waiting for " + brief(running) : "Waiting for the server";
      text = name + " waits for the server's answer, as what runs may end the transaction. Once"
          + " nothing is left open, " + before + ".";
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
      text = name + " has a transaction open"
          + (inTransaction > 0
              ? ", after " + inTransaction + (inTransaction == 1 ? " statement" : " statements")
              : "")
          + ". Commit its changes or roll them back before " + before + ".";
      buttons.add(
          button(t, SECONDARY, "Rollback", () -> answer(s, ROLLBACK)).key("answer-rollback"));
      buttons.add(button(t, PRIMARY, "Commit", () -> answer(s, COMMIT)).key("answer-commit"));
    }
    El content = col()
        .w(ConnectionDialog.width(t))
        .gap(t.space().m())
        .child(Label.label(t, text).wrapText())
        .child(
            waits && doubt == null
                ? row()
                    .gap(t.space().s())
                    .align(Style.Align.CENTER)
                    .child(Progress.spinner(t).label("Waiting"))
                    .child(Label.label(t, Label.Variant.MUTED, "Waiting for the server…"))
                : null)
        // Why the last Commit or Rollback did not end it, where it did not.
        .child(
            problem == null || waits || doubt != null
                ? null
                : Callout.callout(t, Callout.Kind.DANGER, problem.title(), problem.text())
                    .key("question-problem"));
    return Dialog.dialog(t, title, content, buttons, () -> answer(s, null))
        .key("transaction-question");
  }

  /** "Ran in 12 ms · 3 rows": the last run's time on the server, and its rows. */
  String status() {
    if (took == null || statements == 0 && !cancelled && !failedRun) {
      return connected() ? "Connected" : "";
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
    if (rows) {
      text.append(" · ").append(rows(read)).append(more ? " so far" : "");
    }
    if (counts) {
      text.append(" · ").append(rows(changed)).append(" changed");
    }
    return text.toString();
  }
}

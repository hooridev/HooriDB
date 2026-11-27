package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ServerVersion;
import dev.hoori.db.core.execution.FileRun;
import dev.hoori.db.core.monitor.Credentials;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run SQL file: a file chosen in the system's dialog runs against a connection and database, on a
 * session of its own, as {@link FileRun} runs it; on an error it stops or goes on, optionally in
 * one transaction. While it runs, the dialog shows how far it is and cancels it, and can be hidden,
 * which the status line then shows; at its end, how it went and the failed statements with their
 * lines. Its statements go into the statement log, not the console's history. One file runs at a
 * time.
 */
final class FileRunDialog {

  static final String TITLE = "Run SQL file";

  // How often a run tells how far it is, at most.
  private static final long TOLD = Duration.ofMillis(100).toNanos();

  /** Where the dialog is. */
  enum Phase {
    CHOOSING,
    RUNNING,
    ENDED
  }

  /** What the user does on an error. */
  enum OnError {
    STOP("Stop"),
    GO_ON("Go on");

    final String label;

    OnError(String label) {
      this.label = label;
    }
  }

  UUID id; // the connection
  String database; // its database the file runs on, empty for the one it connects to
  Path file; // null until one is chosen
  OnError onError = OnError.STOP;
  boolean transaction;
  boolean hidden; // while it runs, or once it ended hidden
  String problem; // why the file could not be chosen or the run not begun, or null
  final Menu.State connectionMenu = new Menu.State();
  final Menu.State databaseMenu = new Menu.State();
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);

  private FileRun run; // while it runs and after
  private boolean cancelling;
  private String endedBy; // what ended the run, where the app did, or null
  private Lane lane; // the run's own session, or null
  private char[] password; // a copy, to mask it in what failed; wiped once that is masked

  FileRun.Progress progress; // the last told, or null before the first
  FileRun.Report report; // once it ended
  String failure; // once it ended without a report, why
  private List<FileRun.Failed> failed = List.of(); // the report's, the password masked

  private FileRunDialog(UUID id, String database) {
    this.id = id;
    this.database = database;
  }

  /** The dialog for the connection, on the database given, or on its own where that is empty. */
  static FileRunDialog open(State s, UUID id, String database) {
    var profile = s.profile(id);
    return new FileRunDialog(
        id, database.isEmpty() && profile != null ? State.database(profile) : database);
  }

  Phase phase() {
    if (report != null || failure != null) {
      return Phase.ENDED;
    }
    return run != null ? Phase.RUNNING : Phase.CHOOSING;
  }

  /** Whether a file runs, or its session closes. */
  boolean busy() {
    return phase() == Phase.RUNNING;
  }

  /** Whether it shows in the window, as a dialog that the others wait for. */
  boolean shown() {
    return !hidden;
  }

  // Choosing

  /** The connections a file can run on: of SQL, and connected. */
  private static List<UUID> connections(State s) {
    var ids = new ArrayList<UUID>();
    for (var profile : s.profiles) {
      if (!profile.provider().keepsDocuments() && s.catalog.connected(profile.id())) {
        ids.add(profile.id());
      }
    }
    return ids;
  }

  /** The databases to choose from: those the tree read, and the one chosen. */
  private List<String> databases(State s) {
    var names = new ArrayList<>(s.catalog.databases(id));
    if (!names.contains(database)) {
      names.addFirst(database);
    }
    return names;
  }

  private void choose(State s) {
    var dialog =
        FileDialog.open("Choose an SQL file").filter("SQL files", "sql").filter("All files", "*");
    if (file != null && file.getParent() != null) {
      dialog = dialog.in(file.getParent());
    }
    s.app.fileDialog(
        dialog,
        choice -> {
          if (!choice.cancelled()) {
            file = choice.path();
            problem = null;
          }
        },
        error -> problem = "The file could not be chosen: " + error);
  }

  private void connection(State s, UUID chosen) {
    if (!chosen.equals(id)) {
      id = chosen;
      database = State.database(s.profile(chosen));
    }
  }

  /** Run: opens the run's session on the database and runs the file there. */
  void start(State s) {
    var profile = s.profile(id);
    if (file == null || profile == null || busy()) {
      return;
    }
    var session = s.catalog.session(s, id, database);
    if (session == null) {
      problem = State.name(profile) + " is not connected.";
      return;
    }
    problem = null;
    lane = session.lane();
    password = session.password();
    var app = s.app;
    var told = new AtomicLong(System.nanoTime() - TOLD);
    var options = new FileRun.Options(onError == OnError.GO_ON && !transaction, transaction);
    run = FileRun.of(file, profile.provider(), options, at -> {
      long now = System.nanoTime();
      long last = told.get();
      if (now - last >= TOLD && told.compareAndSet(last, now)) {
        app.post(() -> progress = at);
      }
    });
    var running = run;
    lane.ask(
        s.app,
        "run " + file.getFileName(),
        Lane.Priority.VIEW,
        link -> link.runFile(running),
        ended -> ended(s, ended),
        error -> failed(s, error));
  }

  // Ran: what failed, the connection's password masked in it, and the session closes.
  private void ended(State s, FileRun.Report ended) {
    var masked = new ArrayList<FileRun.Failed>(ended.failures().size());
    for (var f : ended.failures()) {
      masked.add(new FileRun.Failed(
          f.line(),
          Credentials.without(f.statement(), password),
          Credentials.without(f.message(), password)));
    }
    failed = List.copyOf(masked);
    report = ended;
    progress = ended.progress();
    finish(s);
  }

  private void failed(State s, Exception error) {
    failure = error instanceof InterruptedException || error.getMessage() == null
        ? "The run was given up before it began."
        : Credentials.without(error.getMessage(), password);
    finish(s);
  }

  // The run is over, and what it said masked: its session closes, and the dialog shows how it
  // went, unless another dialog is open now, in which case the status line says so.
  private void finish(State s) {
    closeSession(s);
    wipe();
    cancelling = false;
    if (hidden && !s.dialogs.anyOpen()) {
      hidden = false;
    }
  }

  /** Cancel: the run stops, and rolls back its transaction. */
  void cancel() {
    if (run != null && busy()) {
      cancelling = true;
      run.cancel();
    }
  }

  /**
   * The app ends the run, as its connection ended or HooriDB closes: it is cancelled and its
   * session closed, which Exit waits for. The password stays until what the run said is masked.
   */
  void end(State s, String why) {
    if (busy()) {
      endedBy = why;
      cancel();
      closeSession(s);
    }
  }

  /** Close: the dialog goes; not while the file runs. */
  void close(State s) {
    if (busy()) {
      return;
    }
    closeSession(s);
    wipe();
    s.dialogs.fileRun = null;
  }

  /** HooriDB closes: the run, if any, is cancelled and its session closed. */
  void closeAll(State s) {
    cancel();
    closeSession(s);
  }

  private void closeSession(State s) {
    if (lane != null) {
      s.closing();
      var closing = lane.close(ended -> {});
      s.app.background(
          "close the file's session",
          () -> {
            closing.join();
            return null;
          },
          done -> s.closed(),
          e -> s.closed());
      lane = null;
    }
  }

  private void wipe() {
    if (password != null) {
      Arrays.fill(password, '\0');
      password = null;
    }
  }

  // The view

  /** The dialog, where one is open and not hidden. */
  static El overlay(State s) {
    return s.dialogs.fileRun == null ? null : s.dialogs.fileRun.view(s);
  }

  /** The status line's item of a run that is hidden, where there is one. */
  static El statusItem(State s, boolean modal) {
    return s.dialogs.fileRun == null ? null : s.dialogs.fileRun.status(modal);
  }

  El view(State s) {
    if (hidden) {
      return null;
    }
    return switch (phase()) {
      case CHOOSING -> choosing(s);
      case RUNNING -> running(s);
      case ENDED -> ended(s);
    };
  }

  /** The status line's item while the run is hidden: how far it is, or that it ended. */
  El status(boolean modal) {
    if (!hidden) {
      return null;
    }
    Runnable show = modal ? null : () -> hidden = false;
    var name = file.getFileName().toString();
    if (phase() == Phase.ENDED) {
      return StatusBar.status(
              failure != null || report.ending() != FileRun.Ending.RAN
                  ? StatusBar.State.ERROR
                  : StatusBar.State.OK,
              name + ": " + outcome(),
              show)
          .key("file-run");
    }
    return StatusBar.status(
            StatusBar.State.BUSY,
            "Running " + name + "… " + Progress.percent(fraction(progress)),
            show)
        .key("file-run");
  }

  private El choosing(State s) {
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(id);
    var ids = connections(s);
    if (profile != null && !ids.contains(id)) {
      ids.addFirst(id);
    }
    boolean connected = s.catalog.connected(id);
    El chosen = row()
        .wFull()
        .gap(t.space().s())
        .child(label(file == null ? "No file chosen" : file.toString())
            .key("file")
            .grow(1)
            .wrapText())
        .child(button(SECONDARY, "Choose…", () -> choose(s)).key("choose"));
    El connection = Select.select(
            ids, id, x -> State.name(s.profile(x)), connectionMenu, x -> connection(s, x))
        .key("connection")
        .wFull();
    El databases = Select.select(
            databases(s),
            database,
            x -> x.isEmpty() ? "The connection's own" : x,
            databaseMenu,
            x -> database = x)
        .key("database")
        .wFull();
    El errors = Segmented.segmented(
            List.of(OnError.values()),
            transaction ? OnError.STOP : onError,
            x -> x.label,
            transaction ? null : x -> onError = x)
        .key("on-error");
    El inOne = checkbox(Prop.of(() -> transaction, v -> transaction = v), "In one transaction")
        .key("transaction");
    var form = Form.form(
        Form.Labels.ABOVE,
        Form.field("File", chosen).required().error(problem),
        Form.field("Connection", connection)
            .error(
                connected || profile == null
                    ? null
                    : State.name(profile) + " is not connected: connect it in the tree first."),
        Form.field("Database", databases),
        Form.field("On an error", errors)
            .help(
                transaction
                    ? "In one transaction an error stops the run, and what ran is rolled back."
                    : "The failed statements are listed at the end, with their lines."),
        Form.field("Transaction", inOne).help(transactionHelp(s, profile)));
    Runnable close = () -> close(s);
    El start = button(PRIMARY, "Run", file != null && connected ? () -> start(s) : null)
        .key("run");
    return Dialog.dialog(
        TITLE,
        col().w(ConnectionDialog.width()).child(form),
        List.of(button(SECONDARY, "Cancel", close).key("close"), start),
        start,
        close);
  }

  // What one transaction covers on the connection's server.
  private static String transactionHelp(State s, ConnectionProfile profile) {
    if (profile == null) {
      return null;
    }
    var provider = profile.provider();
    if (transactionalDdl(s, profile)) {
      return "Commits at the end of the file, and rolls back on an error or a cancel.";
    }
    return provider.displayName() + " commits DDL, such as CREATE, ALTER and DROP, and statements"
        + " such as LOCK TABLES, UNLOCK TABLES and GRANT by itself, with what ran before them: one"
        + " transaction does not cover them.";
  }

  private El running(State s) {
    Theme t = Theme.CURRENT.get();
    var name = file.getFileName().toString();
    var at = progress == null ? new FileRun.Progress(0, -1, 0, 0) : progress;
    var detail = new StringBuilder(Format.BYTES.text(at.bytes()));
    if (at.size() >= 0) {
      detail.append(" of ").append(Format.BYTES.text(at.size()));
    }
    detail.append(" · ").append(statements(at.statements()));
    if (at.failed() > 0) {
      detail.append(" · ").append(Format.LONG.text(at.failed())).append(" failed");
    }
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(label("Runs on " + where(s) + ".").wrapText())
        .child(Progress.labeled(name, fraction(progress), detail.toString())
            .key("progress")
            .wFull());
    if (cancelling) {
      content.child(label(
              Label.Variant.MUTED,
              transaction
                  ? "Cancelling: the statement that runs is stopped, and the transaction rolled"
                      + " back."
                  : "Cancelling: the statement that runs is stopped.")
          .wrapText());
    }
    El cancel = button(
            SECONDARY,
            "Cancel run",
            cancelling ? null : this::cancel,
            Button.options()
                .tone(Button.Tone.DANGER)
                .state(cancelling ? Button.State.BUSY : Button.State.READY))
        .key("cancel");
    Runnable hide = () -> hidden = true;
    return Dialog.dialog(
        TITLE, content, List.of(button(SECONDARY, "Hide", hide).key("hide"), cancel), null, hide);
  }

  private El ended(State s) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    El content = col().w(2 * ConnectionDialog.width()).gap(t.space().m());
    content.child(label(file.getFileName() + " on " + where(s)).wrapText());
    if (failure != null) {
      content.child(Callout.callout(Callout.Kind.DANGER, "Not run", failure));
    } else {
      var kind = switch (report.ending()) {
        case RAN -> report.progress().failed() == 0 ? Callout.Kind.SUCCESS : Callout.Kind.WARNING;
        case STOPPED -> Callout.Kind.DANGER;
        case CANCELLED -> Callout.Kind.WARNING;
      };
      content.child(Callout.callout(kind, outcome(), summary()).key("outcome"));
    }
    if (!failed.isEmpty()) {
      var columns = List.of(
          new DataGrid.Column(
                  "Line",
                  3 * unit,
                  DataGrid.DataType.INTEGER,
                  row -> failed.get(row).line() == 0
                      ? ""
                      : Format.LONG.text(failed.get(row).line()))
              .sortable(false),
          new DataGrid.Column(
                  "Statement", 12 * unit, DataGrid.DataType.TEXT, row -> first(failed.get(row)))
              .sortable(false),
          new DataGrid.Column(
                  "Error",
                  16 * unit,
                  DataGrid.DataType.TEXT,
                  row -> failed.get(row).message().replace('\n', ' '))
              .sortable(false));
      El grid = DataGrid.dataGrid(
              columns, failed.size(), DataGrid.options().select(selection).copy(s.app.clipboard()))
          .key("failures")
          .label("Failed statements")
          .grow(1);
      content.child(Card.flush(grid).wFull().h(8 * unit));
      long more = report.progress().failed() - failed.size();
      if (more > 0) {
        content.child(label(
                Label.Variant.MUTED,
                "And " + Format.LONG.text(more) + " more, which are not listed: the first "
                    + Format.LONG.text((long) FileRun.LISTED) + " are.")
            .wrapText());
      }
    }
    Runnable close = () -> close(s);
    El ok = button(PRIMARY, "Close", close).key("close");
    return Dialog.dialog(TITLE, content, List.of(ok), ok, close);
  }

  /** How the run ended, in a few words. */
  private String outcome() {
    if (failure != null) {
      return "Not run";
    }
    return switch (report.ending()) {
      case RAN ->
        report.progress().failed() == 0
            ? "Ran to its end"
            : "Ran to its end, " + Format.LONG.text(report.progress().failed()) + " failed";
      case STOPPED -> "Stopped at an error";
      case CANCELLED -> "Cancelled";
    };
  }

  // What ran, where it stopped, and what became of the transaction.
  private String summary() {
    var p = report.progress();
    var text = new StringBuilder(
        switch (report.ending()) {
          case RAN -> statements(p.statements()) + " ran in " + took(report.took()) + ".";
          case STOPPED -> {
            // The failure it stopped at is the last listed, where none was left out.
            var line = failed.isEmpty() || failed.size() < p.failed()
                ? 0
                : failed.getLast().line();
            yield (line > 0
                    ? "It stopped at line " + Format.LONG.text(line) + ", after "
                    : "After ")
                + statements(p.statements()) + ".";
          }
          case CANCELLED ->
            (endedBy != null ? "Cancelled as " + endedBy + ", after " : "Cancelled after ")
                + statements(p.statements()) + ".";
        });
    boolean stopped = report.ending() != FileRun.Ending.RAN && p.statements() > 0;
    switch (report.transaction()) {
      case NONE ->
        text.append(
            stopped
                ? " What ran before stays, as each statement committed by itself, but for what"
                    + " ran in a transaction the file began and did not end, which is rolled back"
                    + " as the session closes."
                : "");
      case COMMITTED -> text.append(" The transaction was committed.");
      case ROLLED_BACK -> text.append(rolledBack());
      case IN_DOUBT ->
        text.append(" The transaction's end failed: what is still open is rolled back as the"
            + " session closes, and a commit whose connection was lost may have taken effect.");
    }
    return text.toString();
  }

  // What a rollback of the run's transaction left of the file: what ran up to where the server
  // committed by itself, as at DDL on MariaDB and MySQL, stays.
  private String rolledBack() {
    long line = report.committedAt();
    if (line > 0) {
      return " The transaction was rolled back, but what ran up to line " + Format.LONG.text(line)
          + " stays, as the server committed it by itself there.";
    }
    return " The transaction was rolled back: nothing of the file was kept.";
  }

  // Whether the connected server rolls back DDL with the transaction, as far as it is known.
  private static boolean transactionalDdl(State s, ConnectionProfile profile) {
    var version = s.catalog.version(profile.id());
    return profile
        .provider()
        .supports(Capability.TRANSACTIONAL_DDL, version == null ? ServerVersion.UNKNOWN : version);
  }

  /** The connection and the database the file runs on. */
  private String where(State s) {
    var profile = s.profile(id);
    var name = profile == null ? "a deleted connection" : State.name(profile);
    return database.isEmpty() ? name : name + ", " + database;
  }

  private static double fraction(FileRun.Progress at) {
    return at == null || at.size() <= 0 ? 0 : Math.min(1, (double) at.bytes() / at.size());
  }

  static String statements(long n) {
    return Format.LONG.text(n) + (n == 1 ? " statement" : " statements");
  }

  private static String took(Duration took) {
    return took.toMillis() < 1_000
        ? took.toMillis() + " ms"
        : Format.DURATION.text(took.withNanos(0));
  }

  // The first line of a failed statement, as the grid shows it.
  private static String first(FileRun.Failed failed) {
    var text = failed.statement().strip();
    int end = text.indexOf('\n');
    return end < 0 ? text : text.substring(0, end).stripTrailing() + " …";
  }
}

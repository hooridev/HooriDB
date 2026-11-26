package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sync.Choice;
import dev.hoori.db.core.sync.CompareException;
import dev.hoori.db.core.sync.Difference;
import dev.hoori.db.core.sync.Pairing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Compare data: the rows of a table of SQL, the source, compared with those of another, the
 * target, of the same provider, by the key both share, and the target made equal to the source.
 * Both tables are read on read-only sessions of the tab's own, never the tree's, and the target is
 * written on a session that opens for the writing alone and closes after it, in one transaction.
 * Only the app's thread uses it.
 */
final class SyncTab implements Tab {

  /** The command that opens the tab, in a table's menu, the File menu and the palette. */
  static final String COMPARE_DATA = "Compare data…";

  /** Another table: the target, by its connection. */
  record Target(UUID id, Table table) {}

  /** What runs now in the background, as the view says it. */
  enum Running {
    PAIRING("Reading the tables' columns"),
    COMPARING("Comparing the rows"),
    APPLYING("Writing the changes"),
    SCRIPTING("Writing the script");

    final String title;

    Running(String title) {
      this.title = title;
    }
  }

  /** Why something could not be done, in words for the user. */
  record Problem(String title, String text) {}

  /** The statements that the choice makes of the differences kept, before they are applied. */
  static final class Preview {
    final String sql;
    final int statements;
    final CodeEditor.State editor = new CodeEditor.State().readOnly(true);

    Preview(List<String> statements) {
      this.sql = String.join("\n", statements);
      this.statements = statements.size();
    }
  }

  final UUID id;
  final Table table;
  Target target; // the table it is compared with, or null until one is chosen
  final ComboBox.State chooser = new ComboBox.State();
  Pairing.Found found; // how the tables pair, as last read; null until then
  Sync.Compared compared; // what the last comparison found, or null
  Sync.Progress progress; // how far what runs has come, or null
  Running running; // what runs now, or null
  Choice choice = Choice.ALL;
  Preview preview; // the dialog before applying, while it is open
  Problem problem; // why the last run failed, until the next
  String said; // what the tab says for a while in its status line, or null
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  private Work work; // the run under way, or null
  private int runs; // counts the runs, so that what one that was given up sends goes nowhere
  private int says;
  private Lane sourceLane; // the source's session, or null while it has none
  private Lane targetLane; // the target's session, or null
  private Lane writerLane; // the session that writes the target, while it does
  private Target targetOf; // the target its session is of
  private float[] widths = new float[0];
  private boolean closed;

  private final State state; // for close, which is called without one

  SyncTab(State state, UUID id, Table table) {
    this.state = state;
    this.id = id;
    this.table = table;
  }

  /**
   * Whether the table of the connection can be compared: a table, not a view, of a server of SQL
   * whose connection is connected.
   */
  static boolean comparable(State s, UUID id, Table table) {
    var profile = s.profile(id);
    return profile != null
        && !profile.provider().keepsDocuments()
        && table.kind() == Table.Kind.TABLE
        && s.catalog.connected(id);
  }

  /** The tables it can be compared with: those the tree read of connections of its provider. */
  List<Target> candidates(State s) {
    var provider = provider(s);
    var all = new ArrayList<Target>();
    for (var live : s.catalog.lives.values()) {
      if (live.phase instanceof Live.Connected && live.profile.provider() == provider) {
        for (var other : s.catalog.tablesRead(live.id())) {
          if (!(live.id().equals(id) && other.equals(table))) {
            all.add(new Target(live.id(), other));
          }
        }
      }
    }
    // Those of the same name first, as a copy of the table elsewhere is the likely one.
    all.sort(Comparator.comparing((Target x) -> !x.table().name().equals(table.name()))
        .thenComparing(x -> label(s, x)));
    return all;
  }

  /** What the chooser calls a table: its connection, then its database, schema and name. */
  String label(State s, Target x) {
    var profile = s.profile(x.id());
    return (profile == null ? "" : State.name(profile) + " · ") + path(x.table());
  }

  /** The table's database, schema where it has one, and name. */
  static String path(Table table) {
    return switch (table.container()) {
      case Database d -> d.name() + "." + table.name();
      case Schema x -> x.database().name() + "." + x.name() + "." + table.name();
    };
  }

  private Provider provider(State s) {
    var profile = s.profile(id);
    return profile == null ? null : profile.provider();
  }

  /** The target chosen: what was found of another goes, and the tables are paired. */
  void choose(State s, Target chosen) {
    if (chosen.equals(target) && running == null) {
      return;
    }
    stop();
    target = chosen;
    found = null;
    compared = null;
    problem = null;
    pair(s);
  }

  /** Reads both tables' columns and indexes, and says how their rows pair. */
  void pair(State s) {
    var sides = sides(s);
    if (sides == null) {
      return;
    }
    start(
        s,
        Running.PAIRING,
        progress -> Sync.pair(sides[0], sides[1]),
        got -> found = (Pairing.Found) got);
  }

  /** Compares the rows of both tables anew, pairing them first. */
  void compare(State s) {
    var sides = sides(s);
    if (sides == null) {
      return;
    }
    compared = null;
    start(
        s,
        Running.COMPARING,
        progress -> {
          var paired = Sync.pair(sides[0], sides[1]);
          return paired instanceof Pairing.Paired(var pairing)
              ? Sync.compare(sides[0], sides[1], pairing, progress)
              : paired;
        },
        got -> {
          switch (got) {
            case Sync.Compared c -> {
              compared = c;
              found = new Pairing.Paired(c.pairing());
              selection.clear();
              widths = new float[0];
            }
            case Pairing.Found f -> found = f;
            default -> throw new IllegalStateException(String.valueOf(got));
          }
        });
  }

  /** Opens the dialog of the statements that applying makes, of the differences kept. */
  void openPreview(State s) {
    if (compared == null || running != null || !choice.any()) {
      return;
    }
    preview = new Preview(Sync.preview(compared, choice, provider(s)));
  }

  /**
   * Makes the target equal to the source as far as the choice goes, in one transaction on a
   * session that opens for it; then compares them again.
   */
  void apply(State s) {
    preview = null;
    var why = cannotApply(s);
    if (why != null) {
      say(s, why);
      return;
    }
    var sides = sides(s);
    if (sides == null) {
      return;
    }
    var session = s.catalog.session(s, target.id(), database(target.table()), false);
    if (session == null) {
      problem = notConnected(s, target.id());
      return;
    }
    Arrays.fill(session.password(), '\0');
    var writer = session.lane();
    var pairing = compared.pairing();
    var chosen = choice;
    start(
        s,
        Running.APPLYING,
        progress -> Sync.apply(sides[0], sides[1], writer, pairing, chosen, progress),
        got -> applied(s, (Sync.Applied) got));
    writerLane = writer;
  }

  private void applied(State s, Sync.Applied applied) {
    closeWriter(s);
    switch (applied.outcome()) {
      case Changes.Outcome.Committed _ -> {
        say(s, "Wrote " + count(applied.written(), "change") + " to " + table(target) + ".");
        compare(s);
      }
      case Changes.Outcome.Missed _ ->
        problem = new Problem(
            "Nothing was written",
            "A row of " + table(target) + " changed while the changes were written, so all was"
                + " rolled back. Compare the tables again.");
      case Changes.Outcome.Refused _ ->
        problem = new Problem(
            "Nothing was written",
            table(target) + " cannot take the changes all or nothing, as its storage keeps no"
                + " transactions, or a value of its key is a moment in an hour that the"
                + " session's zone repeats.");
    }
  }

  /** Why the changes cannot be applied now, or null where they can. */
  String cannotApply(State s) {
    if (compared == null) {
      return "Compare the tables first.";
    }
    if (running != null) {
      return "Wait until " + running.title.toLowerCase(Locale.ROOT) + " is done.";
    }
    var profile = target == null ? null : s.profile(target.id());
    if (profile == null) {
      return "The target's connection is gone.";
    }
    if (profile.readOnly()) {
      return State.name(profile) + " is read-only: its tables can be compared, not changed.";
    }
    if (!choice.any() || compared.tally().taken(choice) == 0) {
      return "There is nothing to apply.";
    }
    return null;
  }

  /** Asks for a file, and writes the statements that make the target equal to it. */
  void saveScript(State s) {
    if (compared == null || running != null || !choice.any()) {
      return;
    }
    var dialog = FileDialog.save("Save as script")
        .fileName(target.table().name() + "-sync.sql")
        .filter("SQL", "sql");
    s.app.fileDialog(
        dialog,
        chosen -> {
          if (!chosen.cancelled()) {
            script(s, sql(chosen.path()));
          }
        },
        error -> s.tell("The system's file dialog did not open: " + error));
  }

  // The file chosen, with .sql where its name has none.
  private static Path sql(Path path) {
    var name = path.getFileName().toString();
    return name.contains(".") ? path : path.resolveSibling(name + ".sql");
  }

  private void script(State s, Path file) {
    var sides = sides(s);
    if (sides == null || compared == null) {
      return;
    }
    var pairing = compared.pairing();
    var chosen = choice;
    var provider = provider(s);
    start(
        s,
        Running.SCRIPTING,
        progress -> Sync.script(sides[0], sides[1], pairing, chosen, provider, file, progress),
        got -> say(s, "Saved the script to " + file.getFileName() + "."));
  }

  /** Gives up what runs, which rolls back what it wrote. */
  void cancel(State s) {
    if (running == Running.APPLYING
        && progress != null
        && progress.stage() == Sync.Stage.COMMITTING) {
      return; // the commit runs: its end tells what came of it
    }
    if (work != null) {
      say(s, running.title + " was cancelled.");
    }
    stop();
  }

  // What runs is given up, and nothing it sends arrives; a writing ends with its session, which
  // the server rolls back as it closes where the writing could not.
  private void stop() {
    runs++;
    if (work != null) {
      work.cancel();
      work = null;
    }
    running = null;
    progress = null;
    closeWriter(state);
  }

  private void closeWriter(State s) {
    if (writerLane != null) {
      close(s, writerLane);
      writerLane = null;
    }
  }

  // Runs the call in the background as what runs now, its progress and result handed over.
  private void start(
      State s, Running what, Work.Reporting<Object, Sync.Progress> call, Handler done) {
    stop();
    running = what;
    problem = null;
    int at = runs;
    work = s.app.background(
        what.title.toLowerCase(Locale.ROOT),
        call,
        p -> {
          if (runs == at) {
            progress = p;
          }
        },
        got -> {
          if (runs == at) {
            work = null;
            running = null;
            progress = null;
            done.done(got);
          }
        },
        error -> {
          if (runs == at) {
            work = null;
            running = null;
            progress = null;
            failed(s, error);
          }
        });
  }

  @FunctionalInterface
  private interface Handler {
    void done(Object got);
  }

  private void failed(State s, Exception error) {
    closeWriter(s);
    if (error instanceof InterruptedException) {
      return; // its session ended under it
    }
    var live = s.catalog.live(target == null ? id : target.id());
    if (error instanceof QueryException e && gone(e)) {
      closeSessions(s); // opened anew by the next run
    }
    problem = switch (error) {
      case CompareException e -> new Problem("The rows cannot be paired", e.getMessage());
      case QueryException e
      when e.failure() == QueryException.Failure.ACCESS_DENIED ->
        new Problem("Refused", CatalogTree.words(live, e.detail()));
      case QueryException e
      when e.failure() == QueryException.Failure.CANCELLED ->
        new Problem("Cancelled", "The server cancelled it. Nothing was written.");
      case QueryException e
      when gone(e) ->
        new Problem(
            "Connection lost",
            "A session of the comparison was lost. Nothing was written that was not committed.");
      case ConnectionException e ->
        new Problem("Not connected", CatalogTree.words(live, e.getMessage()));
      case IOException e ->
        new Problem("The file could not be written", String.valueOf(e.getMessage()));
      default ->
        new Problem("It failed", CatalogTree.words(live, String.valueOf(error.getMessage())));
    };
  }

  private static boolean gone(QueryException e) {
    return e.failure() == QueryException.Failure.CONNECTION_LOST
        || e.failure() == QueryException.Failure.ABORTED;
  }

  /** The sides on the tab's sessions, opened where it has none; null where that cannot be. */
  private Sync.Side[] sides(State s) {
    if (target == null || closed) {
      return null;
    }
    if (targetLane != null && !target.equals(targetOf)) {
      close(s, targetLane);
      targetLane = null;
    }
    if (sourceLane == null || sourceLane.broken()) {
      sourceLane = open(s, id, table);
      if (sourceLane == null) {
        problem = notConnected(s, id);
        return null;
      }
    }
    if (targetLane == null || targetLane.broken()) {
      targetLane = open(s, target.id(), target.table());
      targetOf = target;
      if (targetLane == null) {
        problem = notConnected(s, target.id());
        return null;
      }
    }
    return new Sync.Side[] {
      new Sync.Side(sourceLane, table), new Sync.Side(targetLane, target.table())
    };
  }

  // A read-only session on the table's database, whose copy of the password is not needed.
  private static Lane open(State s, UUID id, Table table) {
    var session = s.catalog.session(s, id, database(table), true);
    if (session == null) {
      return null;
    }
    Arrays.fill(session.password(), '\0');
    return session.lane();
  }

  private static String database(Table table) {
    return table.container().database().name();
  }

  private static Problem notConnected(State s, UUID id) {
    var profile = s.profile(id);
    return new Problem(
        "Not connected",
        "Connect " + (profile == null ? "the connection" : State.name(profile))
            + " in Workspace to compare its table.");
  }

  /**
   * A connection of its tables ended: what runs is given up, which rolls back what it wrote, and
   * its sessions close; what was found stays.
   */
  void end(State s) {
    if (running != null) {
      stop();
      problem = new Problem("Not connected", "A connection of the comparison ended.");
    }
    closeSessions(s);
  }

  /** Whether it uses the connection, for the source or the target. */
  boolean uses(UUID connection) {
    return id.equals(connection) || target != null && target.id().equals(connection);
  }

  /** The target's connection was changed or deleted: the target goes. */
  void forget(State s, UUID connection) {
    if (target != null && target.id().equals(connection) && !id.equals(connection)) {
      end(s);
      target = null;
      found = null;
      compared = null;
    }
  }

  private void closeSessions(State s) {
    if (sourceLane != null) {
      close(s, sourceLane);
      sourceLane = null;
    }
    if (targetLane != null) {
      close(s, targetLane);
      targetLane = null;
    }
  }

  // Closes the lane without waiting, as Exit waits for it.
  private static void close(State s, Lane lane) {
    s.closing();
    var closing = lane.close(ended -> {});
    s.app.background(
        "close a session of the comparison",
        () -> {
          closing.join();
          return null;
        },
        done -> s.closed(),
        e -> s.closed());
  }

  /** Whether anything of the tab runs now. */
  boolean busy() {
    return running != null;
  }

  @Override
  public void close() {
    closed = true;
    preview = null;
    stop();
    closeSessions(state);
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

  private static String count(long n, String what) {
    return String.format(Locale.ROOT, "%,d %s%s", n, what, n == 1 ? "" : "s");
  }

  private static String table(Target target) {
    return target == null ? "the target" : target.table().name();
  }

  // The view

  /** A line of the differences' grid: a row of one of the tables, as the comparison found it. */
  private record Line(
      String what, List<Object> values, DataGrid.Status status, List<Integer> cells) {}

  /** The tab's content: the tables, how they pair, what was found, and the status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var actions = new ArrayList<El>();
    if (running != null) {
      boolean committing = progress != null && progress.stage() == Sync.Stage.COMMITTING;
      actions.add(
          button(SECONDARY, "Cancel", committing ? null : () -> cancel(s)).key("cancel"));
    }
    boolean can = target != null && running == null && !(found instanceof Pairing.Unpaired);
    actions.add(button(
            PRIMARY,
            "Compare",
            can ? () -> compare(s) : null,
            Button.options()
                .icon(Symbol.ARROW_SYNC)
                .state(running == Running.COMPARING ? Button.State.BUSY : Button.State.READY))
        .key("compare"));
    El top = Card.toolbar("Compare data", null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    var content = col()
        .grow(1)
        .minSize(0, 0)
        .child(top)
        .child(line == null ? Divider.divider() : line)
        .child(tables(s, t).key("tables"));
    var note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note).key("note"));
    }
    if (compared != null) {
      content.child(summary(s, t).key("found"));
      content.child(differences(s, app).key("differences"));
    } else if (running != null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label(running.title)
          .key("running")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, running.title + "…")));
    } else if (target == null) {
      content.child(EmptyState.emptyState(
              Symbol.ARROW_SYNC,
              "Choose a target",
              "Choose the table to compare " + table.name() + " with. Its rows are paired by the"
                  + " key both tables share, and the target can be made equal to the source.",
              null)
          .key("empty"));
    } else {
      content.child(col().grow(1).key("nothing"));
    }
    El status = Label.label(Label.Variant.MUTED, status()).key("status").ellipsis();
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    return content.child(Card.footer(status, end.toArray(El[]::new)).key("footer"));
  }

  // The source, and the chooser of the target.
  private El tables(State s, Theme t) {
    var profile = s.profile(id);
    var candidates = candidates(s);
    El chooser = ComboBox.comboBox(
            candidates,
            target,
            x -> label(s, x),
            this.chooser,
            running == Running.APPLYING ? null : x -> choose(s, x))
        .key("target")
        .label("Target table")
        .w(14 * t.size().control());
    return col()
        .padding(t.space().m())
        .gap(t.space().s())
        .shrink(0)
        .child(row()
            .gap(t.space().m())
            .align(Style.Align.CENTER)
            .child(Label.label(Label.Variant.MUTED, "Source").w(4 * t.size().control()))
            .child(Label.label((profile == null ? "" : State.name(profile) + " · ") + path(table))
                .key("source")))
        .child(row()
            .gap(t.space().m())
            .align(Style.Align.CENTER)
            .child(Label.label(Label.Variant.MUTED, "Target").w(4 * t.size().control()))
            .child(
                candidates.isEmpty() && target == null
                    ? Label.label(
                            Label.Variant.MUTED,
                            "No other table of a connected connection of this kind is read yet:"
                                + " open its schema in Workspace.")
                        .key("no-target")
                    : chooser));
  }

  // What stands in the way, or how the tables pair; null for nothing to say.
  private El note(State s) {
    if (problem != null) {
      return Callout.callout(
              Callout.Kind.DANGER,
              problem.title(),
              problem.text(),
              button(SECONDARY, "Dismiss", () -> problem = null))
          .key("problem");
    }
    return switch (found) {
      case null -> null;
      case Pairing.Unpaired(var why) ->
        Callout.callout(Callout.Kind.WARNING, "These tables cannot be compared", why)
            .key("unpaired");
      case Pairing.Paired(var pairing) ->
        Label.label(Label.Variant.MUTED, paired(pairing)).wrapText().key("pairing");
    };
  }

  // How the rows pair and which columns are left out.
  private static String paired(Pairing pairing) {
    var key = pairing.key().stream()
        .map(pair -> pairing.sourceColumns().get(pair.source()).name())
        .toList();
    var text = new StringBuilder("Rows are paired by the ")
        .append(
            Pairing.PRIMARY_KEY.equals(pairing.keyName())
                ? Pairing.PRIMARY_KEY
                : "unique key " + pairing.keyName())
        .append(" (")
        .append(String.join(", ", key))
        .append("), and ")
        .append(count(pairing.columns().size(), "column"))
        .append(" of the same name are compared.");
    if (!pairing.onlyInSource().isEmpty()) {
      text.append(" Only in the source, left out: ")
          .append(String.join(", ", pairing.onlyInSource()))
          .append('.');
    }
    if (!pairing.onlyInTarget().isEmpty()) {
      text.append(" Only in the target, kept as they are: ")
          .append(String.join(", ", pairing.onlyInTarget()))
          .append('.');
    }
    if (!pairing.unlike().isEmpty()) {
      text.append(" Of other kinds in the target, left out: ")
          .append(String.join(", ", pairing.unlike()))
          .append('.');
    }
    return text.toString();
  }

  // The counts, what to apply, and the actions.
  private El summary(State s, Theme t) {
    var tally = compared.tally();
    var counts = row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(badge(count(tally.onlyInSource(), "row") + " only in the source", Badge.Kind.SUCCESS)
            .key("only-source"))
        .child(badge(count(tally.onlyInTarget(), "row") + " only in the target", Badge.Kind.DANGER)
            .key("only-target"))
        .child(badge(count(tally.different(), "row") + " different", Badge.Kind.WARNING)
            .key("different"))
        .child(badge(count(tally.equal(), "row") + " equal", Badge.Kind.NEUTRAL).key("equal"));
    boolean idle = running == null;
    var choices = row()
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .child(Checkbox.checkbox(
                choice.insert(),
                "Insert rows only in the source",
                idle
                    ? () -> choice = new Choice(!choice.insert(), choice.update(), choice.delete())
                    : null)
            .key("insert"))
        .child(Checkbox.checkbox(
                choice.update(),
                "Update rows that differ",
                idle
                    ? () -> choice = new Choice(choice.insert(), !choice.update(), choice.delete())
                    : null)
            .key("update"))
        .child(Checkbox.checkbox(
                choice.delete(),
                "Delete rows only in the target",
                idle
                    ? () -> choice = new Choice(choice.insert(), choice.update(), !choice.delete())
                    : null)
            .key("delete"))
        .child(Card.spacer());
    var why = cannotApply(s);
    El apply =
        button(SECONDARY, "Apply…", why == null ? () -> openPreview(s) : null).key("apply");
    choices.child(why == null ? apply : Tooltip.tooltip(apply, why));
    choices.child(button(
            SECONDARY,
            "Save as script…",
            idle && choice.any() && tally.taken(choice) > 0 ? () -> saveScript(s) : null)
        .key("save-script"));
    return col()
        .padding(0, t.space().m(), t.space().m(), t.space().m())
        .gap(t.space().m())
        .shrink(0)
        .child(counts)
        .child(choices);
  }

  private static El badge(String text, Badge.Kind kind) {
    return Badge.badge(text, Badge.options().kind(kind));
  }

  // The differences kept, a line each, or two for rows that differ: the source's and the
  // target's, with the cells that differ marked.
  private El differences(State s, App<State> app) {
    var pairing = compared.pairing();
    var lines = lines(pairing);
    if (lines.isEmpty()) {
      return EmptyState.emptyState(
          Symbol.CHECKMARK_CIRCLE,
          "The rows are equal",
          "Every row of " + table.name() + " has a row of " + table(target) + " with the same"
              + " key and the same values in the compared columns.",
          null);
    }
    var columns = new ArrayList<DataGrid.Column>();
    Theme t = Theme.CURRENT.get();
    columns.add(new DataGrid.Column(
        "Row",
        6 * t.size().control(),
        DataGrid.Align.LEFT,
        false,
        false,
        row -> lines.get(row).what()));
    if (widths.length != pairing.columns().size()) {
      widths = new float[pairing.columns().size()];
      Arrays.fill(widths, -1);
    }
    for (int c = 0; c < pairing.columns().size(); c++) {
      int at = c;
      var pair = pairing.columns().get(c);
      var column = pairing.sourceColumns().get(pair.source());
      var kind = compared.fields().size() > pair.source()
          ? compared.fields().get(pair.source()).kind()
          : null;
      columns.add(
          Cells.column(column, kind, widths, c, row -> lines.get(row).values().get(at), s.zone));
    }
    var options = DataGrid.options()
        .select(selection)
        .copy(app.clipboard())
        .frozen(1)
        .onResize((c, width) -> {
          if (c > 0 && c - 1 < widths.length) {
            widths[c - 1] = width;
          }
        })
        .status(
            row -> lines.get(row).status(),
            (row, column) -> column > 0 && lines.get(row).cells().contains(column - 1)
                ? DataGrid.Status.CHANGED
                : DataGrid.Status.UNCHANGED);
    return DataGrid.dataGrid(columns, lines.size(), options)
        .key("differences-grid")
        .label("Differences")
        .grow(1);
  }

  private List<Line> lines(Pairing pairing) {
    var lines = new ArrayList<Line>();
    var pairs = pairing.columns();
    for (var difference : compared.kept()) {
      switch (difference) {
        case Difference.OnlyInSource(var row) ->
          lines.add(new Line(
              "Only in source",
              pairs.stream().map(p -> row.get(p.source())).toList(),
              DataGrid.Status.NEW,
              List.of()));
        case Difference.OnlyInTarget(var row) ->
          lines.add(new Line(
              "Only in target",
              pairs.stream().map(p -> row.get(p.target())).toList(),
              DataGrid.Status.DELETED,
              List.of()));
        case Difference.Different(var mine, var theirs, var cells) -> {
          var marked = cells.stream().map(pairs::indexOf).toList();
          lines.add(new Line(
              "Source",
              pairs.stream().map(p -> mine.get(p.source())).toList(),
              DataGrid.Status.CHANGED,
              marked));
          lines.add(new Line(
              "Target",
              pairs.stream().map(p -> theirs.get(p.target())).toList(),
              DataGrid.Status.UNCHANGED,
              marked));
        }
      }
    }
    return lines;
  }

  // What the status line says: how far a run has come, or what was found.
  private String status() {
    if (running != null && progress != null) {
      var tally = progress.tally();
      var read = "Read " + count(tally.sourceRows(), "row") + " of the source and "
          + count(tally.targetRows(), "row") + " of the target";
      return switch (progress.stage()) {
        case READING -> read + ", " + count(tally.differences(), "difference") + " so far";
        case WRITING -> read + ", " + count(progress.written(), "change") + " written";
        case COMMITTING -> "Committing " + count(progress.written(), "change") + "…";
      };
    }
    if (running != null) {
      return running.title + "…";
    }
    if (compared == null) {
      return "";
    }
    var tally = compared.tally();
    var shown = compared.kept().size();
    var all = tally.differences();
    return count(tally.sourceRows(), "row") + " in the source, " + count(tally.targetRows(), "row")
        + " in the target"
        + (shown < all
            ? "; showing the first " + count(shown, "difference") + " of "
                + String.format(Locale.ROOT, "%,d", all)
            : "");
  }

  /** The dialog of the statements before they are applied, while it is open; or null. */
  El previewDialog(State s, App<State> app) {
    if (preview == null || compared == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    long all = compared.tally().taken(choice);
    var summary = count(all, "change") + " will be written to " + table(target)
        + " in one transaction, after the tables are compared again.";
    if (preview.statements < all) {
      summary +=
          " Those of the first " + count(compared.kept().size(), "difference") + " are shown.";
    }
    El code = CodeEditor.embedded(CodeEditor.codeEditor(
            app, preview.sql, preview.editor, provider == null ? null : Syntax.of(provider), null)
        .key("sync-sql")
        .label("SQL preview"));
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary).wrapText())
        .child(code.h(
                Math.min(10, Math.max(3, preview.statements + 1)) * t.size().control())
            .wFull());
    El apply = button(PRIMARY, "Apply", () -> apply(s)).key("sync-apply");
    El cancel =
        button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> preview = null).key("sync-cancel");
    return Dialog.dialog(
            "Apply changes",
            content,
            List.of(cancel, apply),
            () -> preview = null,
            Dialog.options().defaultButton(apply))
        .key("sync-preview");
  }
}

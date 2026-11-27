package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Container;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.routine.Routines;
import dev.hoori.db.core.sql.Statements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A function or a procedure in a tab of its own: its source, the CREATE statement its server
 * writes, in an editor with the console's colors, read-only until the user edits it; Save runs it,
 * Drop drops the routine, each after the statements are shown, and Run calls the routine in a
 * console with the values of a form. The source is read on the tree's connection; a change runs on
 * a session opened for it alone, never the tree's, which is closed when the change ends, and the
 * tree reads the routines again after it. A new routine starts from its dialect's template. Only
 * the app's thread uses it.
 *
 * <p>Edits not saved are guarded as a table's changes are: closing the tab, Exit and deleting the
 * connection ask first. A change that runs goes on to its end once it was sent, also where the tab
 * closes, and Exit waits for it; what it ends with then goes to the notices. Where the server
 * drops the routine before it creates it again, Cancel stops the change only until the DROP.
 */
final class RoutineTab implements Tab, Guard.Guarded {

  private static final String ERROR_LAYER = "error";
  private static final String CANCEL = ConsoleTab.CANCEL_BUTTON;
  private static final String NOT_SAVED = "Not saved";
  private static final String NOT_DROPPED = "Not dropped";
  private static final String NOT_CONNECTED = "Not connected";

  /** Why something failed, in words for the user. */
  record Problem(String title, String text) {}

  /** What the tab asks before it acts, shown over its window. */
  sealed interface Asked {}

  /** The statements of a Save or a Drop, shown before they run. */
  record Preview(boolean drop, Routines.Change change) implements Asked {}

  /** The form of a Run: a value for each argument, or NULL. */
  static final class Arguments implements Asked {
    final List<Routine.Parameter> parameters;
    final String[] values;
    final boolean[] nulls;
    boolean tried; // Run was pressed, so that a field without a value says so

    Arguments(Routine routine) {
      parameters = routine.arguments();
      values = new String[parameters.size()];
      Arrays.fill(values, "");
      nulls = new boolean[parameters.size()];
    }

    /** The values as the call binds them, null for NULL. */
    List<String> given() {
      var given = new ArrayList<String>(values.length);
      for (int i = 0; i < values.length; i++) {
        given.add(nulls[i] ? null : values[i]);
      }
      return given;
    }

    /**
     * Why the argument has no value, as a field of a type whose values are never empty that is
     * empty and not NULL; else null.
     */
    String missing(int i, Provider provider) {
      return !nulls[i]
              && values[i].isEmpty()
              && !provider.dialect().routines().takesEmpty(parameters.get(i).type())
          ? "Enter a value of " + parameters.get(i).type() + ", or check NULL."
          : null;
    }
  }

  /** What a change did: how it went, and the routine and its source as they are after a Save. */
  private record Changed(Routines.Outcome outcome, Routine routine, Routine.Source source) {}

  final UUID id;
  final Container container;
  Routine routine; // null for a new one, and for one dropped
  private final Routine.Kind kind; // of a new one
  String text;
  private Routine.Source source; // as read, or as saved; null for a new one
  private String initial = ""; // the text a new routine began with, its template
  // The routine was dropped and could not be created again: its source is all that is left.
  private boolean lost;
  boolean editing; // read-only until the user edits it
  final CodeEditor.State editor = new CodeEditor.State().inlineDiagnostics(true);
  Lane.Asked reading; // the source on its way, or null
  Lane.Asked changing; // the change under way, or null
  private Routines.Applying applying; // what cancels the change under way, or null
  private boolean cancelling; // Cancel was pressed, and the change says how it ended
  Problem problem; // why the source could not be read, or the last change failed; or null
  Asked asked; // the question before a change, or the form of a run; or null
  String said; // what the status line says for a while, or null
  private int says;
  private boolean errorMarked;
  private boolean closed;

  private RoutineTab(UUID id, Container container, Routine routine, Routine.Kind kind) {
    this.id = id;
    this.container = container;
    this.routine = routine;
    this.kind = kind;
  }

  /** The tab of a routine, whose source {@link #read} reads. */
  static RoutineTab of(UUID id, Routine routine) {
    var tab = new RoutineTab(id, routine.container(), routine, routine.kind());
    tab.text = "";
    return tab;
  }

  /** The tab of a new routine of the kind, from the template of the connection's server. */
  static RoutineTab create(UUID id, Container container, Routine.Kind kind, Provider provider) {
    var tab = new RoutineTab(id, container, null, kind);
    tab.text = provider.dialect().routines().template(kind, container);
    tab.initial = tab.text;
    tab.editing = true;
    return tab;
  }

  /** The name the tab goes by: the routine's with its types, else what a new one is. */
  String name() {
    if (routine != null) {
      return routine.label();
    }
    return kind == Routine.Kind.PROCEDURE ? "New procedure" : "New function";
  }

  /** Whether it shows the routine; a tab of a new or dropped one shows none. */
  boolean shows(UUID connection, Routine other) {
    return id.equals(connection) && Objects.equals(routine, other);
  }

  /** Whether it is under way: reading the source or changing the routine. */
  boolean busy() {
    return reading != null || changing != null;
  }

  /** Whether the text holds edits that are not saved. */
  boolean edited() {
    return editing && !text.equals(base());
  }

  // The text as it was read or saved, or as a new routine began.
  private String base() {
    return source != null ? source.statement() : initial;
  }

  /**
   * The tab closes. A change under way goes on to its end, as one between its DROP and its CREATE
   * must, on its own session, which closes then; how it ended goes to the notices.
   */
  @Override
  public void close() {
    closed = true;
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
  }

  // Guarded: edits not saved, which closing the tab, Exit or deleting the connection would lose

  @Override
  public UUID connectionId() {
    return id;
  }

  /**
   * Edits not saved are at stake, and the source of a routine that could not be created again;
   * there is no transaction.
   */
  @Override
  public boolean atStake(Guard.Scope scope) {
    return scope != Guard.Scope.TRANSACTION && (edited() || lost);
  }

  /** What an act that would lose the edits asks first: discard them, and go on, or keep them. */
  @Override
  public El question(State s) {
    if (!(s.guard.pending instanceof Guard.Pending.Question pending) || pending.tab() != this) {
      return null;
    }
    Runnable keep = () -> s.guard.pending = null;
    El keeping = button(SECONDARY, "Keep editing", keep).key("answer-keep");
    El discard = button(
            SECONDARY,
            "Discard",
            () -> {
              s.guard.pending = null;
              discard();
              pending.then().run();
            },
            Button.options().tone(Button.Tone.DANGER))
        .key("answer-discard");
    var what = lost
        ? name() + " is no longer on the server, and its source is only here."
        : name() + " has edits that are not saved.";
    return Dialog.dialog(
            "Discard the edits?",
            col()
                .w(ConnectionDialog.width())
                .child(Label.label(what + " Discard them, and " + pending.what() + "?")
                    .wrapText()),
            List.of(keeping, discard),
            keeping,
            keep)
        .key("routine-question");
  }

  // The edits are given up: the text as it was read, or as the new routine began.
  private void discard() {
    lost = false;
    text = base();
    editing = routine == null && source == null;
    clearError();
  }

  // Handlers

  /** Reads the routine's source anew, on the tree's connection, leaving what was edited. */
  void read(State s) {
    if (routine == null || closed || routine.kind() == Routine.Kind.AGGREGATE) {
      return; // the server writes no source of an aggregate, as the note says
    }
    var lane = s.catalog.lane(s, id, container);
    if (lane == null) {
      problem = notConnected(s);
      return;
    }
    if (reading != null) {
      reading.cancel();
    }
    problem = null;
    var of = routine;
    reading = lane.ask(
        s.app,
        "read the source of " + of.name(),
        Lane.Priority.VIEW,
        link -> link.source(of),
        read -> {
          reading = null;
          if (closed || !Objects.equals(routine, of)) {
            return;
          }
          source = read;
          if (!editing) {
            show(read.statement());
          }
        },
        error -> {
          reading = null;
          if (closed || error instanceof InterruptedException) {
            return;
          }
          problem = switch (error) {
            case CatalogException e
            when e.failure() == CatalogException.Failure.NOT_FOUND ->
              new Problem(
                  "Not found",
                  of.label() + " is no longer there: it was dropped or changed elsewhere.");
            case CatalogException e
            when e.failure() == CatalogException.Failure.ACCESS_DENIED ->
              new Problem("Source not shown", e.detail());
            case CatalogException e -> new Problem("Source not read", e.detail());
            default -> new Problem("Source not read", String.valueOf(error.getMessage()));
          };
        });
  }

  /** The tree's connection connected anew: a source that could not be read is read now. */
  void connected(State s) {
    if (routine != null && source == null && reading == null && !editing && !lost) {
      read(s);
    }
  }

  /** Edit: the source becomes the user's to change. */
  void edit(State s) {
    if (s.catalog.changesRoutines(s, id) && reading == null && source != null) {
      editing = true;
    }
  }

  /** Revert: the source as it was read, read-only again. */
  void revert() {
    if (changing != null || source == null) {
      return;
    }
    // A routine that could not be created again stays editable, so that Save creates it.
    editing = lost;
    problem = null;
    show(source.statement());
  }

  /** Save…: the statements that save the text, shown first. */
  void askSave(State s) {
    var provider = provider(s);
    if (provider == null || !saves(s)) {
      return;
    }
    if (Routines.statement(text, provider).isEmpty()) {
      say(s, "No statement to save");
      return;
    }
    var refused = Routines.refused(provider, routine, text);
    if (refused.isPresent()) {
      problem = new Problem(NOT_SAVED, refused.get());
      return;
    }
    asked = new Preview(false, Routines.save(provider, routine, source, text));
  }

  /**
   * Whether Save… is open: edits not saved, or a routine to create again, on a connection that
   * changes routines, while nothing reads or changes it.
   */
  private boolean saves(State s) {
    return (edited() || lost || routine == null && editing)
        && changing == null
        && reading == null
        && s.catalog.changesRoutines(s, id);
  }

  /** Control+S in the editor: Save…, where its button is open. */
  private boolean key(State s, Key key) {
    if (key.modifiers() != Key.CONTROL || !key.name().equals("S")) {
      return false;
    }
    if (editing && saves(s) && asked == null) {
      askSave(s);
    }
    return true;
  }

  /** Drop…: the statement that drops the routine, shown first. */
  void askDrop(State s) {
    var provider = provider(s);
    if (provider == null || routine == null || changing != null) {
      return;
    }
    asked = new Preview(true, Routines.drop(provider, routine));
  }

  /** Run…: the form of the values to call the routine with. */
  void askRun(State s) {
    var provider = provider(s);
    if (routine == null
        || provider == null
        || provider.dialect().routines().uncalled(routine).isPresent()) {
      return;
    }
    asked = new Arguments(routine);
  }

  /** The question or the form is dismissed. */
  void dismiss() {
    asked = null;
  }

  /** Run: the call, in a console of its own on the routine's connection and database. */
  void run(State s) {
    if (!(asked instanceof Arguments form) || routine == null) {
      return;
    }
    var provider = provider(s);
    if (provider == null) {
      return;
    }
    form.tried = true;
    for (int i = 0; i < form.parameters.size(); i++) {
      if (form.missing(i, provider) != null) {
        return; // the field says why
      }
    }
    asked = null;
    var calls = provider.dialect().routines().call(routine, form.given());
    s.openCall(id, s.tabs.groupOf(this), container.database().name(), calls);
  }

  /**
   * Save or Drop, as the preview shows them, on a session opened for the change alone, which Exit
   * waits for while the change runs and closes.
   */
  void proceed(State s) {
    if (!(asked instanceof Preview(var drop, var change)) || changing != null) {
      return;
    }
    if (!s.catalog.changesRoutines(s, id)) {
      asked = null;
      problem = readOnly(s) ? readOnlyProblem() : notConnected(s);
      return;
    }
    var opened = s.catalog.session(s, id, container.database().name());
    if (opened == null) {
      asked = null;
      problem = notConnected(s);
      return;
    }
    // Its copy of the password masks a console's messages; a change has none to mask.
    Arrays.fill(opened.password(), '\0');
    asked = null;
    problem = null;
    cancelling = false;
    clearError();
    var lane = opened.lane();
    var replaced = routine;
    var in = container;
    var sent = text;
    var cancels = new Routines.Applying();
    applying = cancels;
    s.closing(); // Exit waits for the change, then for its session's closing
    changing = lane.ask(
        s.app,
        drop ? "drop " + replaced.name() : "save a routine",
        Lane.Priority.VIEW,
        link -> drop
            ? new Changed(link.define(in, change, cancels), null, null)
            : saved(link, change, in, cancels),
        changed -> {
          ended(s, lane);
          if (closed || s.guard.quitting()) {
            untaken(s, drop, replaced, changed.outcome());
          }
          if (!closed) {
            changed(s, drop, replaced, sent, change, changed);
          }
        },
        error -> {
          ended(s, lane);
          failed(s, replaced, whyNot(drop, error));
        });
  }

  // Why the change could not run.
  private static Problem whyNot(boolean drop, Exception error) {
    return switch (error) {
      case ConnectionException e ->
        new Problem(NOT_CONNECTED, "The session for the change could not be opened: " + e.detail());
      case QueryException e -> new Problem(notDone(drop), e.detail());
      default -> new Problem(notDone(drop), String.valueOf(error.getMessage()));
    };
  }

  private static String notDone(boolean drop) {
    return drop ? NOT_DROPPED : NOT_SAVED;
  }

  // The change could not run: the tab says why, or the notices where it is no longer shown.
  private void failed(State s, Routine replaced, Problem failed) {
    if (closed || s.guard.quitting()) {
      s.notice(connection(s), part(replaced), failed.title() + ": " + failed.text(), true, null);
    }
    if (!closed) {
      problem = failed;
    }
  }

  // The change ended: its session closes, which Exit waits for in its place.
  private void ended(State s, Lane lane) {
    changing = null;
    applying = null;
    cancelling = false;
    s.closeSession(lane, connection(s), "the routine's session", part(routine), null);
    s.closed();
  }

  /**
   * Cancel while a change runs: the statement under way is cancelled, and the change says how it
   * ended; once the routine it replaces was dropped, the change runs to its end.
   */
  void cancel(State s) {
    if (changing == null || applying == null || cancelling) {
      return;
    }
    if (applying.cancel()) {
      cancelling = true;
    } else {
      say(s, "The routine was dropped already: its CREATE runs to its end");
    }
  }

  // The change, then the routines of the container, by which the routine saved is found: the one
  // that is new, as one whose name or arguments the text changed; else the one replaced.
  private static Changed saved(
      Servers.Link link, Routines.Change change, Container container, Routines.Applying applying)
      throws QueryException, CatalogException {
    var before = link.routines(container);
    var outcome = link.define(container, change, applying);
    if (!(outcome instanceof Routines.Outcome.Done)) {
      return new Changed(outcome, null, null);
    }
    try {
      var after = link.routines(container);
      var added = after.stream().filter(r -> !before.contains(r)).toList();
      var found = added.size() == 1 ? added.getFirst() : null;
      return new Changed(outcome, found, found == null ? null : link.source(found));
    } catch (CatalogException e) {
      return new Changed(outcome, null, null); // saved all the same
    }
  }

  // How a change ended, where the tab no longer shows it, as it closed or HooriDB closes: a
  // failure goes to the notices, which keeps Exit open, and a change done to the status line.
  private void untaken(State s, boolean drop, Routine replaced, Routines.Outcome outcome) {
    var part = part(replaced);
    switch (outcome) {
      case Routines.Outcome.Done _ -> s.tell(part + (drop ? ": dropped" : ": saved"));
      case Routines.Outcome.Refused(var why) -> s.notice(connection(s), part, why, true, null);
      case Routines.Outcome.Failed f -> s.notice(connection(s), part, failedText(f), true, null);
    }
  }

  private void changed(
      State s,
      boolean drop,
      Routine replaced,
      String sent,
      Routines.Change change,
      Changed changed) {
    var provider = provider(s);
    switch (changed.outcome()) {
      case Routines.Outcome.Done _
      when drop -> {
        // The source stays, to create it again: Save does.
        routine = null;
        editing = true;
        lost = false;
        initial = source == null ? "" : source.statement();
        source = null;
        say(s, "Dropped " + replaced.label());
      }
      case Routines.Outcome.Done _ -> showSaved(s, replaced, sent, changed);
      case Routines.Outcome.Refused(var why) -> problem = new Problem("Read-only", why);
      case Routines.Outcome.Failed f -> {
        var title =
            f.failure().kind() == Failure.Kind.CANCELLED ? cancelledTitle(f) : notDone(drop);
        problem = new Problem(title, failedText(f));
        if (!drop && provider != null) {
          markError(provider, sent, change, f);
        }
        if (f.put().orElse(null) instanceof Routines.PutBack.Not) {
          // The routine is gone; its source stays, which Revert shows and Save creates again.
          routine = null;
          lost = true;
          editing = true;
        }
      }
    }
    s.catalog.routinesChanged(s, id, container);
  }

  // A Save was done: the tab shows the routine saved.
  private void showSaved(State s, Routine replaced, String sent, Changed changed) {
    lost = false;
    if (changed.routine() != null) {
      routine = changed.routine();
      source = changed.source();
      editing = false;
      show(source.statement());
    } else if (replaced != null) {
      // The same routine, replaced: read what the server keeps of it.
      editing = false;
      source = new Routine.Source(sent, source == null ? List.of() : source.session());
      read(s);
    }
    say(s, "Saved");
  }

  // A cancel before the routine was dropped left it as it was, as did one of the trial; of a
  // statement that ran, the server alone knows whether it took effect.
  private static String cancelledTitle(Routines.Outcome.Failed failed) {
    return switch (failed.step().role()) {
      case SESSION, TRIAL, TRIAL_DROP, DROP_FIRST -> "Cancelled";
      case CREATE, DROP -> ConsoleTab.UNSURE;
    };
  }

  /** What the failure of a change says, with how the routine was put back where it was dropped. */
  static String failedText(Routines.Outcome.Failed failed) {
    var failure = failed.failure();
    var words = failure.message().isEmpty() ? said(failure.kind()) : failure.message();
    var role = failed.step().role();
    if (failure.kind() == Failure.Kind.CANCELLED) {
      return switch (role) {
        case SESSION, TRIAL, TRIAL_DROP, DROP_FIRST ->
          "The change was cancelled before the routine was dropped: nothing of it changed.";
        case CREATE, DROP ->
          "The change was cancelled: if the server had already run it, it took effect. Refresh"
              + " the routines in the tree to see.";
      };
    }
    var dropped = " The routine was dropped first, as the server cannot replace it in one step,";
    var put = failed
        .put()
        .map(p -> switch (p) {
          case Routines.PutBack.AsItWas _ -> dropped + " and was created again as it was.";
          case Routines.PutBack.WithoutDefiner(var refused) ->
            dropped + " and was created again as it was, but with you as its definer: the server"
                + " did not let you name the one it had (" + refused.message() + ").";
          case Routines.PutBack.Not(var why) ->
            dropped + " and could not be created again (" + why.message() + "). It is no longer"
                + " on the server: Revert shows its source as it was read, and Save creates it"
                + " from there.";
        })
        .orElse("");
    return switch (role) {
      case TRIAL ->
        words + " Nothing was dropped: the server was given the text under a name of its own"
            + " first, and did not take it.";
      case TRIAL_DROP ->
        "The text was created under a name of its own, to try it, and that could not be dropped"
            + " again: " + words + " Nothing else was changed.";
      case SESSION ->
        "The session could not be set as the routine was created: " + words + " Nothing was"
            + " changed.";
      case DROP_FIRST -> words + " Nothing was changed.";
      case CREATE, DROP -> words + put;
    };
  }

  private static String said(Failure.Kind kind) {
    return switch (kind) {
      case CONNECTION_LOST -> "The connection was lost.";
      case ACCESS_DENIED -> "The server refused it.";
      default -> "It failed.";
    };
  }

  // The place the server names in the CREATE statement, or in its trial, in the text it was sent
  // from.
  private void markError(
      Provider provider, String sent, Routines.Change change, Routines.Outcome.Failed failed) {
    var role = failed.step().role();
    if (role != Routines.Role.CREATE && role != Routines.Role.TRIAL) {
      return;
    }
    var statements = Statements.split(sent, provider);
    if (statements.isEmpty()) {
      return;
    }
    int from = statements.getFirst().start();
    var words = failed.failure().message();
    Routines.place(change, failed, provider).ifPresent(place -> {
      var error = new CodeEditor.Diagnostic(
          from + place.start(), from + place.end(), CodeEditor.Severity.ERROR, words);
      editor.markup(ERROR_LAYER, sent, List.of(error));
      errorMarked = true;
      if (sent.equals(text)) {
        editor.select(from + place.start(), from + place.start());
      }
    });
  }

  private void clearError() {
    if (errorMarked) {
      editor.noMarkup(ERROR_LAYER);
      errorMarked = false;
    }
  }

  private void show(String read) {
    text = read;
    editor.select(0, 0);
    clearError();
  }

  // The connection's name, as the notices name it.
  private String connection(State s) {
    var profile = s.profile(id);
    return profile == null ? "A deleted connection" : State.name(profile);
  }

  // What the notices name the change by: its routine, or what a new one is.
  private String part(Routine of) {
    return of != null ? of.label() : name();
  }

  /** The tab says it in its status line for a while. */
  void say(State s, String words) {
    said = words;
    int at = ++says;
    s.app.after(State.MESSAGE_SECONDS, () -> {
      if (says == at) {
        said = null;
      }
    });
  }

  private Provider provider(State s) {
    var profile = s.profile(id);
    return profile == null ? null : profile.provider();
  }

  private boolean readOnly(State s) {
    var profile = s.profile(id);
    return profile != null && profile.readOnly();
  }

  private static Problem readOnlyProblem() {
    return new Problem(
        "Read-only",
        "The connection is read-only, so routines are not created, changed or dropped.");
  }

  private Problem notConnected(State s) {
    var profile = s.profile(id);
    var name = profile == null ? "the connection" : State.name(profile);
    return new Problem(NOT_CONNECTED, "Connect " + name + " in Workspace first.");
  }

  // The view

  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    var profile = s.profile(id);
    var where = (profile == null ? "" : State.name(profile) + " · ") + path();
    El top = Card.toolbar(where, null, actions(s, provider).toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    var note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).key("note").child(note));
    }
    content.child(body(s, app, provider));
    El status = Label.label(Label.Variant.MUTED, status(s)).key("state").ellipsis();
    return content.child(Card.footer(status, footerEnd(s).toArray(El[]::new)).key("footer"));
  }

  // The toolbar's buttons: Save… and Revert while editing, else Edit, then Run…, Drop… and Refresh.
  private List<El> actions(State s, Provider provider) {
    boolean changes = s.catalog.changesRoutines(s, id);
    boolean free = changing == null && reading == null;
    var actions =
        new ArrayList<El>(editing ? saving(s, free) : List.of(editButton(s, changes, free)));
    actions.add(runButton(s, provider));
    actions.add(button(
            SECONDARY,
            "Drop…",
            changes && free && routine != null ? () -> askDrop(s) : null,
            Button.options().icon(Symbol.DELETE))
        .key("drop"));
    actions.add(button(
            SECONDARY,
            "Refresh",
            routine != null && free && !edited() ? () -> read(s) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(reading != null ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    return actions;
  }

  // Save… while editing, and Revert where there is a source to go back to.
  private List<El> saving(State s, boolean free) {
    var actions = new ArrayList<El>();
    actions.add(button(
            PRIMARY,
            "Save…",
            saves(s) ? () -> askSave(s) : null,
            Button.options()
                .icon(Symbol.SAVE)
                .state(changing != null ? Button.State.BUSY : Button.State.READY))
        .key("save"));
    if (source != null) {
      actions.add(button(SECONDARY, "Revert", free ? this::revert : null).key("revert"));
    }
    return actions;
  }

  private El editButton(State s, boolean changes, boolean free) {
    return button(
            SECONDARY,
            "Edit",
            changes && free && source != null ? () -> edit(s) : null,
            Button.options().icon(Symbol.EDIT))
        .key("edit");
  }

  // Run…, with why it cannot call the routine where it cannot.
  private El runButton(State s, Provider provider) {
    var uncalled = routine == null || provider == null
        ? null
        : provider.dialect().routines().uncalled(routine).orElse(null);
    boolean runs = routine != null && uncalled == null;
    El run = button(
            SECONDARY,
            "Run…",
            runs && s.catalog.connected(id) && changing == null ? () -> askRun(s) : null,
            Button.options().icon(Symbol.PLAY))
        .key("run");
    return uncalled == null ? run : Tooltip.tooltip(run, uncalled);
  }

  // The source in its editor, or that it is being read.
  private El body(State s, App<State> app, Provider provider) {
    Theme t = Theme.CURRENT.get();
    if (reading != null && text.isEmpty()) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the source")
          .key("reading")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the source…"));
    }
    editor.readOnly(!editing || changing != null);
    editor.occurrences(s.settings.highlightOccurrences());
    var syntax = provider != null ? Syntax.of(provider) : Syntax.of(new Language.Sql());
    return CodeEditor.codeEditor(app, text, editor, syntax, v -> {
          text = v;
          clearError();
        })
        .key("source")
        .label("Source")
        // Before the editor's own keys, as Control+S saves.
        .onKeyFirst(key -> key(s, key))
        .wFull()
        .grow(1);
  }

  // The end of the footer: what the tab says for a while, and the change under way with Cancel.
  private List<El> footerEnd(State s) {
    Theme t = Theme.CURRENT.get();
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (changing != null) {
      // Not once the routine was dropped, as the change then runs to its end.
      boolean stops = applying != null && !applying.firm();
      end.add(row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .key("changing")
          .child(Progress.spinner().label("Changing"))
          .child(button(
                  SECONDARY,
                  CANCEL,
                  stops && !cancelling ? () -> cancel(s) : null,
                  Button.options().state(cancelling ? Button.State.BUSY : Button.State.READY))
              .key("cancel-change")));
    }
    return end;
  }

  /** Where the routine is: its schema or database. */
  String path() {
    return switch (container) {
      case dev.hoori.db.core.catalog.Schema x -> x.database().name() + "." + x.name();
      case dev.hoori.db.core.catalog.Database d -> d.name();
    };
  }

  private String status(State s) {
    if (changing != null) {
      return "Changing the routine…";
    }
    if (routine == null) {
      return editing ? "Not saved yet: Save creates it" : "";
    }
    if (!editing) {
      return readOnly(s) ? "Read-only connection" : "Read-only: Edit to change it";
    }
    return edited() ? "Edited, not saved" : "Editing";
  }

  private El note(State s) {
    if (problem != null) {
      return Callout.callout(
              Callout.Kind.DANGER,
              problem.title(),
              problem.text(),
              button(SECONDARY, "Dismiss", () -> problem = null))
          .key("problem");
    }
    if (!s.catalog.connected(id)) {
      var profile = s.profile(id);
      var name = profile == null ? "the connection" : State.name(profile);
      return Callout.callout(
              Callout.Kind.INFO,
              NOT_CONNECTED,
              "Connect " + name + " in Workspace to read, save or run the routine.")
          .key("unconnected");
    }
    if (routine != null && routine.kind() == Routine.Kind.AGGREGATE) {
      return Callout.callout(
              Callout.Kind.INFO,
              "No source",
              "PostgreSQL does not write the source of an aggregate, so it is not shown here."
                  + " Drop… drops it.")
          .key("aggregate");
    }
    return null;
  }

  /** The question before Save or Drop, or the form of Run, over the tab's window; or null. */
  El dialog(State s, App<State> app) {
    return switch (asked) {
      case null -> null;
      case Preview p -> preview(s, app, p);
      case Arguments f -> form(s, f);
    };
  }

  private final CodeEditor.State previewEditor = new CodeEditor.State().readOnly(true);

  private El preview(State s, App<State> app, Preview p) {
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    var statements = String.join(";\n\n", p.change().statements()) + ";";
    int lines = (int) statements.lines().count();
    var syntax = provider != null ? Syntax.of(provider) : Syntax.of(new Language.Sql());
    El code =
        CodeEditor.embedded(CodeEditor.codeEditor(app, statements, previewEditor, syntax, null)
            .key("preview-statements")
            .label("Statements"));
    var change = p.change();
    String summary;
    if (p.drop()) {
      summary = "Drop " + routine.label() + " from " + path() + "?";
    } else if (change.dropsFirst()) {
      summary = "The server cannot replace a routine in one step. The text is created under a name"
          + " of its own first and dropped again, to see that the server takes it; then "
          + routine.label() + " is dropped and created from the text. Should the CREATE fail, the"
          + " routine is created again as it was.";
    } else {
      int count = change.steps().size();
      summary = (count == 1 ? "1 statement runs" : count + " statements run")
          + " on a session of its own:";
    }
    if (!p.drop() && change.statement(Routines.Role.SESSION).isPresent()) {
      summary += " The session is set first as the routine was created, as the server reads its"
          + " body by those settings.";
    }
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary).wrapText())
        .child(code.h(Math.clamp(lines + 1L, 4, 14) * t.size().control()).wFull());
    if (change.dropsFirst()) {
      // What the server takes with the DROP, which no statement here grants again.
      content.child(Callout.callout(
              Callout.Kind.WARNING,
              "Privileges",
              "The privileges granted on " + routine.label() + " alone go with its DROP and are"
                  + " not granted again.")
          .key("preview-grants"));
    }
    El go = button(
            PRIMARY,
            p.drop() ? "Drop" : "Save",
            () -> proceed(s),
            p.drop() ? Button.options().tone(Button.Tone.DANGER) : Button.options())
        .key("preview-go");
    return Dialog.dialog(
            p.drop() ? "Drop routine" : "Save routine",
            content,
            List.of(button(SECONDARY, CANCEL, this::dismiss).key("preview-cancel"), go),
            this::dismiss,
            p.drop() ? Dialog.options() : Dialog.options().defaultButton(go))
        .key("routine-preview");
  }

  // A row for each argument: its field, named by its name, mode and type, with its NULL beside it,
  // and why it has no value once Run was pressed.
  private El form(State s, Arguments f) {
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    var content = col().w(ConnectionDialog.width()).gap(t.space().m());
    if (f.parameters.isEmpty()) {
      content.child(Label.label(routine.label() + " takes no arguments.").wrapText());
    } else {
      content.child(Label.label("A value for each argument, which the server reads as its type:")
          .wrapText());
    }
    var rows = new ArrayList<Form.Entry>();
    for (int i = 0; i < f.parameters.size(); i++) {
      var parameter = f.parameters.get(i);
      int at = i;
      var name = parameter.name().isEmpty() ? "$" + (i + 1) : parameter.name();
      var mode = parameter.mode() == Routine.Mode.IN ? "" : parameter.mode().name() + " ";
      El field = TextField.textField(
              Prop.of(() -> f.values[at], v -> f.values[at] = v), f.nulls[at] ? "NULL" : "")
          .key("argument-" + i)
          .disabled(f.nulls[at])
          .wFull();
      El isNull = Checkbox.checkbox(Prop.of(() -> f.nulls[at], v -> f.nulls[at] = v), "NULL")
          .key("null-" + i)
          .label("NULL " + name);
      var missing = f.tried && provider != null ? f.missing(i, provider) : null;
      rows.add(Form.row(
          Form.field(name + " (" + mode + parameter.type() + ")", field).grow().error(missing),
          Form.field("", isNull)));
    }
    if (!rows.isEmpty()) {
      content.child(Form.form(Form.Labels.ABOVE, rows.toArray(Form.Entry[]::new)));
    }
    content.child(Label.label(
            Label.Variant.MUTED,
            "The call opens in a console of its own, which shows its results and what it gives"
                + " back.")
        .wrapText());
    El go =
        button(PRIMARY, "Run", s.catalog.connected(id) ? () -> run(s) : null).key("form-run");
    return Dialog.dialog(
            "Run " + routine.label(),
            content,
            List.of(button(SECONDARY, CANCEL, this::dismiss).key("form-cancel"), go),
            go,
            this::dismiss)
        .key("routine-form");
  }
}

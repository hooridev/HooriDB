package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Checkbox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
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
 */
final class RoutineTab implements Tab {

  private static final String ERROR_LAYER = "error";
  private static final String CANCEL = ConsoleTab.CANCEL_BUTTON;

  /** Why something failed, in words for the user. */
  record Problem(String title, String text) {}

  /** What the tab asks before it acts, shown over its window. */
  sealed interface Asked {}

  /** The statements of a Save or a Drop, shown before they run. */
  record Preview(boolean drop, Routines.Change change) implements Asked {}

  /** The form of a Run: a value for each argument, or NULL. */
  static final class Form implements Asked {
    final List<Routine.Parameter> arguments;
    final String[] values;
    final boolean[] nulls;

    Form(Routine routine) {
      arguments = routine.arguments();
      values = new String[arguments.size()];
      Arrays.fill(values, "");
      nulls = new boolean[arguments.size()];
    }

    /** The values as the call binds them, null for NULL. */
    List<String> values() {
      var given = new ArrayList<String>(values.length);
      for (int i = 0; i < values.length; i++) {
        given.add(nulls[i] ? null : values[i]);
      }
      return given;
    }
  }

  /** What a change did: how it went, and the routine and its source as they are after a Save. */
  private record Changed(Routines.Outcome outcome, Routine routine, String source) {}

  final UUID id;
  final Container container;
  Routine routine; // null for a new one, and for one dropped
  private final Routine.Kind kind; // of a new one
  String text;
  private String source; // as read, or as saved; null for a new one
  boolean editing; // read-only until the user edits it
  final CodeEditor.State editor = new CodeEditor.State().inlineDiagnostics(true);
  Lane.Asked reading; // the source on its way, or null
  Lane.Asked changing; // the change under way, or null
  private Lane session; // the change's own session while it runs
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
    return editing && !text.equals(source == null ? "" : source);
  }

  @Override
  public void close() {
    closed = true;
    if (reading != null) {
      reading.cancel();
      reading = null;
    }
    // A change that was sent may well take effect: what it does is the server's.
    if (changing != null) {
      changing.cancel();
      changing = null;
    }
    if (session != null) {
      session.close();
      session = null;
    }
  }

  // Handlers

  /** Reads the routine's source anew, on the tree's connection, leaving what was edited. */
  void read(State s) {
    if (routine == null || closed) {
      return;
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
            show(read);
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
    if (routine != null && source == null && reading == null && !editing) {
      read(s);
    }
  }

  /** Edit: the source becomes the user's to change. */
  void edit(State s) {
    if (s.catalog.changesRoutines(s, id) && reading == null) {
      editing = true;
    }
  }

  /** Revert: the source as it was read, read-only again. */
  void revert() {
    if (changing != null || source == null) {
      return;
    }
    editing = false;
    problem = null;
    show(source);
  }

  /** Save…: the statements that save the text, shown first. */
  void askSave(State s) {
    var provider = provider(s);
    if (provider == null || !editing || changing != null) {
      return;
    }
    var change = Routines.save(provider, routine, source, text);
    if (change.statements().getLast().isEmpty()) {
      say(s, "No statement to save");
      return;
    }
    asked = new Preview(false, change);
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
    if (routine == null || routine.kind() == Routine.Kind.AGGREGATE || provider(s) == null) {
      return;
    }
    asked = new Form(routine);
  }

  /** The question or the form is dismissed. */
  void dismiss() {
    asked = null;
  }

  /** Run: the call, in a console of its own on the routine's connection and database. */
  void run(State s) {
    if (!(asked instanceof Form form) || routine == null) {
      return;
    }
    var provider = provider(s);
    if (provider == null) {
      return;
    }
    asked = null;
    var calls = provider.dialect().routines().call(routine, form.values());
    s.openCall(id, s.tabs.groupOf(this), container.database().name(), calls);
  }

  /** Save or Drop, as the preview shows them, on a session opened for the change alone. */
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
    session = opened.lane();
    asked = null;
    problem = null;
    clearError();
    var lane = session;
    var replaced = routine;
    var in = container;
    var sent = text;
    changing = lane.ask(
        s.app,
        drop ? "drop " + replaced.name() : "save a routine",
        Lane.Priority.VIEW,
        link -> drop ? new Changed(link.define(change), null, null) : saved(link, change, in),
        changed -> {
          changing = null;
          closeSession(lane);
          if (!closed) {
            changed(s, drop, replaced, sent, changed);
          }
        },
        error -> {
          changing = null;
          closeSession(lane);
          if (closed || error instanceof InterruptedException) {
            return;
          }
          problem = switch (error) {
            case ConnectionException e ->
              new Problem(
                  "Not connected", "The session for the change could not be opened: " + e.detail());
            case QueryException e -> new Problem(drop ? "Not dropped" : "Not saved", e.detail());
            default ->
              new Problem(drop ? "Not dropped" : "Not saved", String.valueOf(error.getMessage()));
          };
        });
  }

  /** Cancel while a change runs: whether it took effect is the server's to say. */
  void cancel(State s) {
    if (changing == null) {
      return;
    }
    changing.cancel();
    changing = null;
    if (session != null) {
      closeSession(session);
    }
    problem = new Problem(
        ConsoleTab.UNSURE,
        "The change was cancelled: if the server had already run it, it took effect. Refresh"
            + " the routines in the tree to see.");
    s.catalog.routinesChanged(s, id, container);
  }

  // The change, then the routines of the container, by which the routine saved is found: the one
  // that is new, as one whose name or arguments the text changed; else the one replaced.
  private static Changed saved(Servers.Link link, Routines.Change change, Container container)
      throws Exception {
    var before = link.routines(container);
    var outcome = link.define(change);
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

  private void changed(State s, boolean drop, Routine replaced, String sent, Changed changed) {
    var provider = provider(s);
    switch (changed.outcome()) {
      case Routines.Outcome.Done _
      when drop -> {
        // The source stays, to create it again: Save does.
        routine = null;
        editing = true;
        source = null;
        say(s, "Dropped " + replaced.label());
      }
      case Routines.Outcome.Done _ -> {
        if (changed.routine() != null) {
          routine = changed.routine();
          source = changed.source();
          editing = false;
          show(source);
        } else if (replaced != null) {
          // The same routine, replaced: read what the server keeps of it.
          editing = false;
          source = sent;
          read(s);
        }
        say(s, "Saved");
      }
      case Routines.Outcome.Refused(var why) -> problem = new Problem("Read-only", why);
      case Routines.Outcome.Failed(var statement, var failure, var restored) -> {
        var words = failure.message().isEmpty() ? failure.kind().name() : failure.message();
        var back = restored
            .map(well -> well
                ? " The routine was dropped first, as the server cannot replace it in one step,"
                    + " and was created again as it was."
                : " The routine was dropped first, as the server cannot replace it in one step,"
                    + " and could not be created again: its source is in the editor.")
            .orElse("");
        problem = new Problem(drop ? "Not dropped" : "Not saved", words + back);
        if (!drop && provider != null && statement.equals(Routines.statement(sent, provider))) {
          markError(provider, sent, failure, words);
        }
        if (restored.isPresent() && !restored.get()) {
          routine = null;
          source = null;
        }
      }
    }
    s.catalog.routinesChanged(s, id, container);
  }

  // The place the server names in the CREATE statement, in the text it was sent from.
  private void markError(Provider provider, String sent, Failure failure, String words) {
    var statements = Statements.split(sent, provider);
    if (statements.isEmpty()) {
      return;
    }
    int from = statements.getFirst().start();
    var sql = Routines.statement(sent, provider);
    failure.place(sql, provider).ifPresent(place -> {
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

  private void closeSession(Lane lane) {
    lane.close();
    if (session == lane) {
      session = null;
    }
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
    return new Problem("Not connected", "Connect " + name + " in Workspace first.");
  }

  // The view

  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var provider = provider(s);
    boolean changes = s.catalog.changesRoutines(s, id);
    boolean free = changing == null && reading == null;
    var actions = new ArrayList<El>();
    if (editing) {
      actions.add(button(
              PRIMARY,
              "Save…",
              changes && free ? () -> askSave(s) : null,
              Button.options()
                  .icon(Symbol.SAVE)
                  .state(changing != null ? Button.State.BUSY : Button.State.READY))
          .key("save"));
      if (source != null) {
        actions.add(button(SECONDARY, "Revert", free ? this::revert : null).key("revert"));
      }
    } else {
      actions.add(button(
              SECONDARY,
              "Edit",
              changes && free && routine != null ? () -> edit(s) : null,
              Button.options().icon(Symbol.EDIT))
          .key("edit"));
    }
    boolean runs = routine != null && routine.kind() != Routine.Kind.AGGREGATE;
    actions.add(button(
            SECONDARY,
            "Run…",
            runs && s.catalog.connected(id) && changing == null ? () -> askRun(s) : null,
            Button.options().icon(Symbol.PLAY))
        .key("run"));
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
    var profile = s.profile(id);
    var where = (profile == null ? "" : State.name(profile) + " · ") + path();
    El top = Card.toolbar(where, null, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    var note = note(s);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).key("note").child(note));
    }
    if (reading != null && text.isEmpty()) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading the source")
          .key("reading")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading the source…")));
    } else {
      editor.readOnly(!editing || changing != null);
      editor.occurrences(s.settings.highlightOccurrences());
      var syntax = provider != null ? Syntax.of(provider) : Syntax.of(new Language.Sql());
      content.child(CodeEditor.codeEditor(app, text, editor, syntax, v -> {
            text = v;
            clearError();
          })
          .key("source")
          .label("Source")
          .wFull()
          .grow(1));
    }
    El status = Label.label(Label.Variant.MUTED, status(s)).key("state").ellipsis();
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    if (changing != null) {
      end.add(row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .key("changing")
          .child(Progress.spinner().label("Changing"))
          .child(button(SECONDARY, CANCEL, () -> cancel(s)).key("cancel-change")));
    }
    return content.child(Card.footer(status, end.toArray(El[]::new)).key("footer"));
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
              "Not connected",
              "Connect " + name + " in Workspace to read, save or run the routine.")
          .key("unconnected");
    }
    return null;
  }

  /** The question before Save or Drop, or the form of Run, over the tab's window; or null. */
  El question(State s, App<State> app) {
    return switch (asked) {
      case null -> null;
      case Preview p -> preview(s, app, p);
      case Form f -> form(s, f);
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
    var count = p.change().statements().size();
    String summary;
    if (p.drop()) {
      summary = "Drop " + routine.label() + " from " + path() + "?";
    } else if (count > 1) {
      summary = "The server cannot replace a routine in one step, so it is dropped first. Should"
          + " the CREATE fail, the routine is created again as it was.";
    } else {
      summary = count + " statement runs on a session of its own:";
    }
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary).wrapText())
        .child(code.h(Math.clamp(lines + 1L, 4, 14) * t.size().control()).wFull());
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

  private El form(State s, Form f) {
    Theme t = Theme.CURRENT.get();
    var content = col().w(ConnectionDialog.width()).gap(t.space().m());
    if (f.arguments.isEmpty()) {
      content.child(Label.label(routine.label() + " takes no arguments.").wrapText());
    } else {
      content.child(Label.label("A value for each argument, which the server reads as its type:")
          .wrapText());
    }
    for (int i = 0; i < f.arguments.size(); i++) {
      var parameter = f.arguments.get(i);
      int at = i;
      var name = parameter.name().isEmpty() ? "$" + (i + 1) : parameter.name();
      var mode = parameter.mode() == Routine.Mode.IN ? "" : parameter.mode().name() + " ";
      var label = name + " (" + mode + parameter.type() + ")";
      El field = TextField.textField(
              Prop.of(() -> f.values[at], v -> f.values[at] = v), f.nulls[at] ? "NULL" : "")
          .key("argument-" + i)
          .label(label)
          .disabled(f.nulls[at])
          .grow(1);
      El isNull = Checkbox.checkbox(Prop.of(() -> f.nulls[at], v -> f.nulls[at] = v), "NULL")
          .key("null-" + i)
          .label("NULL " + name);
      content.child(col()
          .gap(t.space().xs())
          .child(Label.label(label))
          .child(row().gap(t.space().s()).align(Style.Align.CENTER).child(field).child(isNull)));
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

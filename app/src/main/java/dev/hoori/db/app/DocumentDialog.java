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
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentEdit;
import dev.hoori.db.core.document.Lexer;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.document.SyntaxException;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dialog for editing, inserting, copying, or deleting one document in a collection tab: an editor
 * in MongoDB shell notation with syntax colors, completion, and inline error marks, followed by a
 * command preview before anything is sent on the connection's write lane. Only the app's thread
 * uses it.
 */
final class DocumentDialog {

  private static final String ERROR_LAYER = "error";
  private static final String CANCEL = ConsoleTab.CANCEL_BUTTON;

  enum Mode {
    EDIT,
    INSERT,
    COPY,
    DELETE
  }

  final Mode mode;
  private final TableTab tab;
  private final Table collection;
  private final Document original;
  private final String initial;
  String text;
  final CodeEditor.State editor = new CodeEditor.State().inlineDiagnostics(true);
  private final Completion.State completion = new Completion.State().openAfter(".$");
  private final Suggestions suggestions;
  DocumentEdit pending;
  private final CodeEditor.State previewEditor = new CodeEditor.State().readOnly(true);
  Lane.Asked writing;
  ConsoleTab.Problem problem;
  private boolean errorMarked;
  private boolean conflict;
  private Bson.ObjectId insertId; // the _id this dialog makes for a new document without one
  private DocumentEdit sent; // the last command sent, and the text it was read from
  private String sentText;

  private DocumentDialog(
      Mode mode, TableTab tab, Document original, String text, DocumentEdit pending) {
    this.mode = mode;
    this.tab = tab;
    this.collection = tab.table;
    this.original = original;
    this.initial = text;
    this.text = text;
    this.pending = pending;
    this.suggestions = new Suggestions(tab);
  }

  static DocumentDialog edit(TableTab tab, Document original, String initialText) {
    return new DocumentDialog(Mode.EDIT, tab, original, initialText, null);
  }

  static DocumentDialog insert(TableTab tab) {
    var dialog = new DocumentDialog(Mode.INSERT, tab, null, "{\n  \n}", null);
    dialog.editor.select(4, 4);
    return dialog;
  }

  static DocumentDialog copy(TableTab tab, String initialText) {
    return new DocumentDialog(Mode.COPY, tab, null, initialText, null);
  }

  static DocumentDialog delete(TableTab tab, Document original) {
    return new DocumentDialog(
        Mode.DELETE, tab, original, "", new DocumentEdit.Delete(tab.table, original));
  }

  /** Whether closing the dialog would lose what the user typed or a write left unsure or unsaid. */
  boolean draft() {
    // A command sent and not settled by closing may have taken effect, whatever the text says.
    return sent != null || problem != null || mode != Mode.DELETE && !text.equals(initial);
  }

  /** Cancels any in-flight write and stops completion reads when the dialog or tab closes. */
  void close() {
    if (writing != null) {
      writing.cancel();
      writing = null;
    }
    completion.close();
    suggestions.close();
  }

  /** Cancels an in-flight write requested by the user or Guard; outcome is then unknown. */
  void cancelWrite(State s) {
    if (writing == null) {
      return;
    }
    writing.cancel();
    writing = null;
    if (mode != Mode.DELETE) {
      pending = null;
    }
    problem = new ConsoleTab.Problem(
        ConsoleTab.UNSURE,
        "The write was cancelled: if the server had already begun applying it, whether it took"
            + " effect is not known. Refresh the collection to check.");
    tab.settledFromDocumentEdit(s, false);
  }

  /** The dialog overlay for the tab's window: either the document editor or the command preview. */
  El view(State s, App<State> app) {
    return pending != null ? previewView(s, app) : editorView(s, app);
  }

  private El editorView(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(tab.id);
    var provider = profile == null ? null : profile.provider();
    editor.occurrences(s.settings.highlightOccurrences());
    El code = CodeEditor.codeEditor(app, text, editor, ShellSyntax.document(), this::edited)
        .key("document-editor")
        .label("Document");
    if (provider != null && s.catalog.connected(tab.id)) {
      code = completing(s, app, provider, code);
    } else if (completion.isOpen()) {
      completion.close();
    }
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(editorSummary()).wrapText());
    if (problem != null) {
      content.child(Callout.callout(Callout.Kind.DANGER, problem.title(), problem.text())
          .key("document-problem"));
    }
    content.child(CodeEditor.embedded(code).h(12 * t.size().control()).wFull());
    String title = switch (mode) {
      case EDIT -> "Edit document";
      case INSERT -> "Insert document";
      case COPY -> "Copy document";
      case DELETE -> "";
    };
    String action = mode == Mode.EDIT ? "Save…" : "Insert…";
    boolean connected = profile != null && s.catalog.connected(tab.id);
    boolean locked = s.guard.locked(tab) != null;
    Runnable dismiss = () -> tab.closeDocumentDialog();
    El cancel = button(SECONDARY, CANCEL, dismiss).key("document-cancel");
    El save = button(PRIMARY, action, connected && !locked ? () -> preview(s) : null)
        .key("document-save");
    // No defaultButton: Enter inserts a new line in the document editor.
    return Dialog.dialog(title, content, List.of(cancel, save), dismiss).key("document-dialog");
  }

  private El completing(State s, App<State> app, Provider provider, El code) {
    if (!completion.isOpen()) {
      suggestions.forget();
      completion.loading(false);
    }
    var prefix = "db.getCollection(" + Shell.quote(collection.name()) + ").insertOne(";
    var database = collection.container().database().name();
    El completing = Completion.complete(app, code, editor.caret(), completion, word -> {
      var entries = suggestions.at(
          s,
          provider,
          database,
          prefix + text,
          prefix.length() + Math.min(editor.caret(), text.length()),
          word);
      completion.loading(suggestions.loading(s));
      return entries;
    });
    return completing.onKeyFirst(k -> {
      if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
        suggestions.forget();
      }
      return false;
    });
  }

  private El previewView(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var command = pending.preview();
    int lines = (int) command.lines().count();
    El code = CodeEditor.embedded(
        CodeEditor.codeEditor(app, command, previewEditor, ShellSyntax.of(), null)
            .key("preview-command")
            .label("Command preview"));
    El content = col()
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(previewSummary()).wrapText());
    if (problem != null) {
      content.child(Callout.callout(Callout.Kind.DANGER, problem.title(), problem.text())
          .key("document-problem"));
    }
    content.child(code.h(Math.clamp(lines + 1L, 4, 12) * t.size().control()).wFull());
    if (writing != null) {
      content.child(progress(t));
    }
    Runnable dismiss = writing != null ? () -> cancelWrite(s) : () -> tab.closeDocumentDialog();
    var buttons = new ArrayList<El>();
    if (mode != Mode.DELETE) {
      buttons.add(button(SECONDARY, "Back", writing == null ? this::back : null)
          .key("document-preview-back"));
    }
    buttons.add(button(SECONDARY, CANCEL, dismiss).key("document-preview-cancel"));
    El confirm = confirm(s);
    buttons.add(confirm);
    String title = mode == Mode.DELETE ? "Delete document" : "Command Preview";
    String key = mode == Mode.DELETE ? "document-delete" : "document-preview";
    return Dialog.dialog(
            title,
            content,
            buttons,
            dismiss,
            mode == Mode.DELETE ? Dialog.options() : Dialog.options().defaultButton(confirm))
        .key(key);
  }

  private El progress(Theme t) {
    boolean deleting = mode == Mode.DELETE;
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(Progress.spinner().label(deleting ? "Deleting" : "Saving"))
        .child(
            Label.label(Label.Variant.MUTED, deleting ? "Deleting document…" : "Saving document…"));
  }

  private El confirm(State s) {
    boolean free = writing == null && s.guard.locked(tab) == null && s.catalog.connected(tab.id);
    String label = switch (mode) {
      case EDIT -> "Replace";
      case INSERT, COPY -> "Insert";
      case DELETE -> "Delete";
    };
    var options = Button.options().state(writing != null ? Button.State.BUSY : Button.State.READY);
    if (mode == Mode.DELETE) {
      options.tone(Button.Tone.DANGER);
    }
    boolean sendable = free && !(mode == Mode.DELETE && conflict);
    return button(PRIMARY, label, sendable ? () -> send(s) : null, options)
        .key("document-preview-confirm");
  }

  private String editorSummary() {
    return switch (mode) {
      case EDIT -> "Edit the document in " + tab.path() + ".";
      case INSERT -> "Insert a new document into " + tab.path() + ".";
      case COPY -> "Insert a copy into " + tab.path() + " (without _id).";
      case DELETE -> "";
    };
  }

  private String previewSummary() {
    return switch (mode) {
      case EDIT -> "1 command will replace this document in " + tab.path() + ".";
      case INSERT, COPY -> "1 command will insert 1 document into " + tab.path() + ".";
      case DELETE ->
        "Delete the document with _id "
            + Shell.compact(original.get("_id"))
            + " from "
            + tab.path()
            + "?";
    };
  }

  private void edited(String next) {
    text = next;
    if (errorMarked) {
      editor.noMarkup(ERROR_LAYER);
      errorMarked = false;
    }
    if (problem != null) {
      problem = null;
      conflict = false;
    }
  }

  private void back() {
    pending = null;
    problem = null;
  }

  /** Parses and validates the editor text, then opens the command preview. */
  private void preview(State s) {
    clearError();
    problem = null;
    conflict = false;
    if (!tab.canEditDocuments(s)) {
      problem = new ConsoleTab.Problem(
          "Collection cannot be edited", "This collection is read-only or a view.");
      return;
    }
    // The same text sends the same command again, as read the first time: ObjectId() in it would
    // otherwise make another _id, and a retry after an unknown outcome insert twice.
    if (sent != null && text.equals(sentText)) {
      pending = sent;
      previewEditor.select(0, 0);
      return;
    }
    Document parsed;
    try {
      parsed = Shell.document(text);
    } catch (SyntaxException e) {
      markSyntaxError(e);
      problem = new ConsoleTab.Problem("Invalid document", e.getMessage());
      return;
    }
    if (mode == Mode.EDIT
        && parsed.has("_id")
        && !Objects.equals(parsed.get("_id"), original.get("_id"))) {
      markIdError();
      problem = new ConsoleTab.Problem(
          "_id cannot be changed",
          "Keep the original _id ("
              + Shell.compact(original.get("_id"))
              + ") or omit _id from the document.");
      return;
    }
    try {
      pending = mode == Mode.EDIT
          ? new DocumentEdit.Replace(collection, original, parsed)
          : new DocumentEdit.Insert(collection, identified(parsed));
    } catch (IllegalArgumentException e) {
      problem = new ConsoleTab.Problem("Invalid document", e.getMessage());
      return;
    }
    previewEditor.select(0, 0);
  }

  // A new document without _id gets one here, the same for each save of this dialog: a retry
  // after a write whose outcome is unknown then fails as a duplicate instead of inserting twice.
  private Document identified(Document parsed) {
    if (parsed.has("_id")) {
      return parsed;
    }
    Object id;
    if (sent instanceof DocumentEdit.Insert last) {
      id = last.document().get("_id"); // what the server may have taken already, null too
    } else {
      if (insertId == null) {
        insertId = Bson.ObjectId.next();
      }
      id = insertId;
    }
    var entries = new ArrayList<Document.Entry>(parsed.entries().size() + 1);
    entries.add(new Document.Entry("_id", id));
    entries.addAll(parsed.entries());
    return new Document(entries);
  }

  private void send(State s) {
    if (pending == null || writing != null || s.guard.locked(tab) != null) {
      return;
    }
    if (!tab.canEditDocuments(s)) {
      returnToEditor(new ConsoleTab.Problem(
          "Collection cannot be edited", "This collection is read-only or a view."));
      return;
    }
    var lane = s.catalog.writer(s, tab.id, collection.container().database().name());
    if (lane == null) {
      var profile = s.profile(tab.id);
      var name = profile == null ? "the connection" : State.name(profile);
      returnToEditor(new ConsoleTab.Problem(
          "Not connected", "Reconnect " + name + " in Workspace before saving."));
      return;
    }
    var live = s.catalog.live(tab.id);
    var edit = pending;
    if (edit instanceof DocumentEdit.Insert insert) {
      // The text takes the _id sent, as ObjectId() in it made it: whatever the outcome, a later
      // save of this text, edited or not, cannot insert the document again under another _id.
      text = Shell.pretty(insert.document());
      editor.select(0, 0);
    }
    sent = edit;
    sentText = text;
    problem = null;
    conflict = false;
    writing = lane.ask(
        s.app,
        "write document in " + collection.name(),
        Lane.Priority.VIEW,
        link -> link.write(edit),
        outcome -> {
          writing = null;
          if (tab.documentDialog != this) {
            return;
          }
          handleOutcome(s, outcome);
        },
        error -> {
          writing = null;
          if (tab.documentDialog != this) {
            return;
          }
          handleFailure(s, live, error);
        });
  }

  private void handleOutcome(State s, DocumentEdit.Outcome outcome) {
    switch (outcome) {
      case DocumentEdit.Outcome.Written _ -> {
        tab.closeDocumentDialog();
        tab.say(
            s,
            switch (mode) {
              case EDIT -> "Document saved";
              case INSERT, COPY -> "Document inserted";
              case DELETE -> "Document deleted";
            });
        tab.settledFromDocumentEdit(s, true);
      }
      case DocumentEdit.Outcome.Changed _ -> {
        conflict = true;
        if (mode == Mode.DELETE) {
          problem = new ConsoleTab.Problem(
              "Document changed",
              "The document changed or was deleted on the server since it was read. Nothing was"
                  + " deleted; refresh the collection to see the current document.");
        } else {
          pending = null;
          problem = new ConsoleTab.Problem(
              "Document changed",
              "The document changed or was deleted on the server since it was read. Nothing was"
                  + " written; your text is kept.");
        }
        tab.settledFromDocumentEdit(s, false);
      }
      case DocumentEdit.Outcome.Unconfirmed(var message) -> {
        returnToEditor(new ConsoleTab.Problem(
            ConsoleTab.UNSURE,
            "The server could not confirm the write, so whether it took effect is not known."
                + " Refresh the collection before trying again.\n"
                + CatalogTree.words(s.catalog.live(tab.id), message)));
        tab.settledFromDocumentEdit(s, false);
      }
    }
  }

  private void handleFailure(State s, Live live, Exception error) {
    var next = switch (error) {
      case InterruptedException _ ->
        new ConsoleTab.Problem(
            ConsoleTab.UNSURE,
            "The write was given up as its connection closed: whether it took effect is not known."
                + " Refresh the collection to check.");
      case QueryException qe when lost(qe) -> lostProblem(CatalogTree.words(live, qe.detail()));
      case QueryException qe ->
        new ConsoleTab.Problem(failedTitle(qe), written(CatalogTree.words(live, qe.detail())));
      case ConnectionException ce -> connectionProblem(s, live, ce);
      default ->
        new ConsoleTab.Problem(failed(), written(CatalogTree.words(live, State.messageOf(error))));
    };
    returnToEditor(next);
    tab.settledFromDocumentEdit(s, false);
  }

  private static boolean lost(QueryException e) {
    // A cancel the user did not ask for, as when its connection closed, may follow a sent command.
    return e.failure() == QueryException.Failure.CONNECTION_LOST
        || e.failure() == QueryException.Failure.ABORTED
        || e.failure() == QueryException.Failure.CANCELLED;
  }

  private static ConsoleTab.Problem lostProblem(String detail) {
    String body = "The write ended before the server confirmed it: whether it took effect is not"
        + " known. Refresh the collection before trying again.";
    return new ConsoleTab.Problem(
        ConsoleTab.UNSURE, detail.isEmpty() ? body : body + "\n" + detail);
  }

  private String failedTitle(QueryException e) {
    return switch (e.failure()) {
      case ACCESS_DENIED -> "Access denied";
      case NOT_FOUND -> "Collection not found";
      default -> failed();
    };
  }

  private String failed() {
    return mode == Mode.DELETE ? "Delete failed" : "Save failed";
  }

  private static String written(String detail) {
    return detail.isEmpty() ? "Nothing was written." : detail;
  }

  private ConsoleTab.Problem connectionProblem(State s, Live live, ConnectionException e) {
    var profile = s.profile(tab.id);
    String detail = profile == null
        ? CatalogTree.words(live, e.detail())
        : Words.note(e.failure(), profile, true, s.settings.connectTimeout());
    return new ConsoleTab.Problem(Words.title(e.failure()), detail);
  }

  private void returnToEditor(ConsoleTab.Problem next) {
    if (mode != Mode.DELETE) {
      pending = null;
    }
    problem = next;
  }

  private void clearError() {
    if (errorMarked) {
      editor.noMarkup(ERROR_LAYER);
      errorMarked = false;
    }
  }

  private void markSyntaxError(SyntaxException e) {
    int at = Math.clamp(e.offset(), 0, text.length());
    var found = ShellSyntax.problem(text, at);
    int start = Math.clamp(found.from(), 0, text.length());
    int end = Math.clamp(found.to(), start, text.length());
    editor.markup(
        ERROR_LAYER,
        text,
        List.of(new CodeEditor.Diagnostic(start, end, CodeEditor.Severity.ERROR, e.getMessage())));
    editor.select(start, start);
    errorMarked = true;
  }

  private void markIdError() {
    var tokens = Lexer.tokens(text);
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);
      depth += nesting(token);
      if (depth == 1 && isIdKey(tokens, i)) {
        editor.markup(
            ERROR_LAYER,
            text,
            List.of(new CodeEditor.Diagnostic(
                token.start(), token.end(), CodeEditor.Severity.ERROR, "_id cannot be changed")));
        editor.select(token.start(), token.start());
        errorMarked = true;
        return;
      }
    }
  }

  // How far a token opens (1) or closes (-1) a document, an array or a call.
  private int nesting(Lexer.Token token) {
    if (token.kind() != Lexer.Kind.PUNCTUATION) {
      return 0;
    }
    char c = text.charAt(token.start());
    if ("{[(".indexOf(c) >= 0) {
      return 1;
    }
    return ")]}".indexOf(c) >= 0 ? -1 : 0;
  }

  // Whether the token is the name _id, bare or quoted, before its colon.
  private boolean isIdKey(List<Lexer.Token> tokens, int at) {
    var token = tokens.get(at);
    var word = text.substring(token.start(), token.end());
    boolean id = token.kind() == Lexer.Kind.NAME && word.equals("_id")
        || token.kind() == Lexer.Kind.STRING && isIdString(word);
    return id && nextCodeIsColon(tokens, at);
  }

  private boolean nextCodeIsColon(List<Lexer.Token> tokens, int from) {
    for (int j = from + 1; j < tokens.size(); j++) {
      var next = tokens.get(j);
      if (next.kind() != Lexer.Kind.COMMENT) {
        return next.is(text, ':');
      }
    }
    return false;
  }

  private static boolean isIdString(String quoted) {
    try {
      return "_id".equals(Shell.parse(quoted));
    } catch (SyntaxException _) {
      return false;
    }
  }
}

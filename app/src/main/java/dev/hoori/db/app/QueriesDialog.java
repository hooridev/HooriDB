package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.QueryStore;
import dev.hoori.db.core.connection.SavedQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The dialogs of a console's saved queries: Save query, which keeps the selection, or else the
 * whole text, under a name the user gives, in place of one of that name; and Saved queries, which
 * lists those of the console's connection, found by a part of their name or text, puts the one
 * chosen into the editor at its caret, and deletes one after asking.
 */
final class QueriesDialog {

  private static final Format<LocalDateTime> WHEN = Format.dateTime(0);

  /** Whether it asks for the name of a query to save, rather than listing those saved. */
  final boolean saving;

  /** What Save query keeps. */
  final String text;

  String name;
  boolean triedToSave; // an empty name shows why Save does not go
  String search = "";
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  SavedQuery deleting; // the query whose delete asks first, or null
  // The queries shown, and what they were found from.
  private List<SavedQuery> shown = List.of();
  private List<SavedQuery> of;
  private String found;

  private QueriesDialog(boolean saving, String text, String name) {
    this.saving = saving;
    this.text = text;
    this.name = name;
  }

  /** Save query: the text, under a name that its first line suggests. */
  static QueriesDialog save(String text) {
    var first = text.strip().lines().findFirst().orElse("");
    return new QueriesDialog(true, text, first.length() > 60 ? first.substring(0, 60) : first);
  }

  /** Saved queries: those of the console's connection. */
  static QueriesDialog list() {
    return new QueriesDialog(false, "", "");
  }

  /** The queries of the console's connection that hold the search, by name. */
  List<SavedQuery> shown(State s, ConsoleTab console) {
    if (s.queries != of || !search.equals(found)) { // each change hands over a new list
      var chosen = chosen();
      var part = search.toLowerCase(Locale.ROOT);
      shown = s.queries.stream()
          .filter(q -> q.connection().equals(console.id)
              && (q.name().toLowerCase(Locale.ROOT).contains(part)
                  || q.text().toLowerCase(Locale.ROOT).contains(part)))
          .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
          .toList();
      of = s.queries;
      found = search;
      int at = 0;
      for (int i = 0; chosen != null && i < shown.size(); i++) {
        if (shown.get(i).id().equals(chosen.id())) {
          at = i;
          break;
        }
      }
      if (shown.isEmpty()) {
        selection.clear();
      } else {
        selection.select(at, 0);
      }
    }
    return shown;
  }

  /** The query chosen, or null. */
  private SavedQuery chosen() {
    int row = selection.row();
    return selection.isEmpty() || row < 0 || row >= shown.size() ? null : shown.get(row);
  }

  /** Save: the query is kept, in place of one of its name, and the dialog closes. */
  private void save(State s, ConsoleTab console) {
    if (name.isBlank()) {
      triedToSave = true;
      return;
    }
    boolean replaced = QueryStore.named(s.queries, console.id, name);
    s.saveQuery(new SavedQuery(UUID.randomUUID(), console.id, name.strip(), text, Instant.now()));
    console.queries = null;
    s.tell((replaced ? "Replaced the saved query " : "Saved the query ") + name.strip());
  }

  /** Insert, Enter or a double press: the query goes in at the caret, and the dialog closes. */
  private void insert(ConsoleTab console, App<State> app) {
    var query = chosen();
    if (query != null) {
      console.insert(app, query.text());
      console.queries = null;
    }
  }

  El view(State s, ConsoleTab console, App<State> app) {
    return saving ? saveView(s, console) : listView(s, console, app);
  }

  private El saveView(State s, ConsoleTab console) {
    Theme t = Theme.CURRENT.get();
    boolean replaces = !name.isBlank() && QueryStore.named(s.queries, console.id, name);
    var field = Form.field(
            "Name",
            TextField.textField(name, "Name of the query", v -> {
                  name = v;
                  triedToSave = false;
                })
                .key("query-name")
                .wFull())
        .required()
        .error(triedToSave && name.isBlank() ? "A saved query needs a name." : null)
        .warning(replaces ? "A saved query of this name is replaced." : null);
    El preview = label(Label.Variant.MUTED, first(text)).ellipsis().key("query-preview");
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Form.form(Form.Labels.ABOVE, Form.group(null, field)))
        .child(preview);
    Runnable close = () -> console.queries = null;
    El save =
        button(PRIMARY, replaces ? "Replace" : "Save", () -> save(s, console)).key("query-save");
    return Dialog.dialog(
            "Save query",
            content,
            List.of(button(SECONDARY, "Cancel", close).key("query-cancel"), save),
            close,
            Dialog.options().defaultButton(save))
        .key("save-query-dialog");
  }

  private El listView(State s, ConsoleTab console, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var queries = shown(s, console);
    boolean any = s.queries.stream().anyMatch(q -> q.connection().equals(console.id));
    float unit = t.size().control();
    El list;
    if (queries.isEmpty()) {
      list = EmptyState.emptyState(
          Symbol.SAVE,
          any ? "No saved query holds “" + search + "”" : "No saved queries yet",
          any
              ? "The search finds queries whose name or text holds it, in any case."
              : "Save query, beside Run, keeps what the editor holds under a name.",
          null);
    } else {
      var columns = List.of(
          new DataGrid.Column(
                  "Name",
                  9 * unit,
                  DataGrid.DataType.TEXT,
                  row -> queries.get(row).name())
              .sortable(false),
          new DataGrid.Column(
                  "Query",
                  15 * unit,
                  DataGrid.DataType.TEXT,
                  row -> first(queries.get(row).text()))
              .sortable(false),
          new DataGrid.Column(
                  "Saved",
                  6 * unit,
                  DataGrid.DataType.DATE_TIME,
                  row -> WHEN.text(
                      LocalDateTime.ofInstant(queries.get(row).saved(), ZoneId.systemDefault())))
              .sortable(false));
      list = DataGrid.dataGrid(
              columns,
              queries.size(),
              DataGrid.options()
                  .select(selection)
                  .onActivate(row -> insert(console, app))
                  .copy(app.clipboard()))
          .key("queries")
          .label("Saved queries")
          .grow(1);
    }
    El content = col()
        .gap(t.space().m())
        .child(TextField.withIcon(
            TextField.textField(search, "Search", v -> search = v)
                .key("queries-search")
                .label("Search")
                .w(34 * unit),
            Symbol.SEARCH))
        .child(Card.flush(list).w(34 * unit).h(10 * unit));
    Runnable close = () -> console.queries = null;
    var chosen = chosen();
    El insert = button(PRIMARY, "Insert", chosen == null ? null : () -> insert(console, app))
        .key("query-insert");
    return Dialog.dialog(
            "Saved queries of " + State.name(s.profile(console.id)),
            content,
            List.of(button(SECONDARY, "Close", close).key("queries-close"), insert),
            close,
            Dialog.options()
                .start(button(
                        SECONDARY,
                        "Delete",
                        chosen == null ? null : () -> deleting = chosen,
                        Button.options().tone(Button.Tone.DANGER))
                    .key("query-delete"))
                .defaultButton(insert))
        .key("queries-dialog");
  }

  /** The question before Delete, or null while none is asked. */
  El question(State s) {
    Theme t = Theme.CURRENT.get();
    var query = deleting;
    if (query == null) {
      return null;
    }
    Runnable cancel = () -> deleting = null;
    return Dialog.dialog(
            "Delete the saved query " + query.name() + "?",
            col()
                .w(ConnectionDialog.width())
                .gap(t.space().m())
                .child(label("It is deleted from this computer; the consoles keep their text.")
                    .wrapText()),
            List.of(
                button(SECONDARY, "Cancel", cancel).key("delete-query-cancel"),
                button(
                        PRIMARY,
                        "Delete",
                        () -> {
                          s.deleteQuery(query.id());
                          deleting = null;
                          s.tell("Deleted the saved query " + query.name());
                        },
                        Button.options().tone(Button.Tone.DANGER))
                    .key("delete-query-confirm")),
            cancel)
        .key("delete-query");
  }

  /** The text's first line that holds more than blanks. */
  static String first(String text) {
    return text.strip().lines().findFirst().orElse("");
  }
}

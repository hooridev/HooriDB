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
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.HistoryEntry;
import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The History dialog of a console: the statements run on its connection, the newest first, found
 * by a part of their text; the one chosen goes into the editor at its caret. Clear history deletes
 * them, after asking.
 */
final class HistoryDialog {

  private static final Format<LocalDateTime> WHEN = Format.dateTime(0);

  String search = "";
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  boolean clearing; // Clear history asks first
  // The entries shown, the newest first, and what they were found from.
  private List<HistoryEntry> shown = List.of();
  private List<HistoryEntry> of;
  private String found;

  /** The entries of the console's connection that hold the search, the newest first. */
  List<HistoryEntry> shown(State s, ConsoleTab console) {
    if (s.history != of || !search.equals(found)) { // each change hands over a new list
      var chosen = chosen();
      var part = search.toLowerCase(Locale.ROOT);
      shown = s.history.reversed().stream()
          .filter(e -> e.connection().equals(console.id)
              && e.statement().toLowerCase(Locale.ROOT).contains(part))
          .sorted(Comparator.comparing(HistoryEntry::started).reversed()) // by when each began
          .toList();
      of = s.history;
      found = search;
      // The entry chosen stays chosen where it is still shown, also with an outcome learned since,
      // else the newest is.
      int at = 0;
      for (int i = 0; chosen != null && i < shown.size(); i++) {
        var e = shown.get(i);
        if (e.started().equals(chosen.started()) && e.statement().equals(chosen.statement())) {
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

  /** The entry chosen, or null. */
  private HistoryEntry chosen() {
    int row = selection.row();
    return selection.isEmpty() || row < 0 || row >= shown.size() ? null : shown.get(row);
  }

  /** Insert, Enter or a double press: the statement goes in at the caret, and the dialog closes. */
  private void insert(ConsoleTab console, App<State> app) {
    var entry = chosen();
    if (entry != null) {
      console.insert(app, entry.statement());
      console.history = null;
    }
  }

  /** Clear history, once asked: the connection's statements are deleted, here and on the disk. */
  private void clear(State s, ConsoleTab console) {
    s.forget(console.id);
    clearing = false;
    s.tell("The history of " + State.name(s.profile(console.id)) + " was deleted");
  }

  El view(State s, ConsoleTab console, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var entries = shown(s, console);
    boolean any = s.history.stream().anyMatch(e -> e.connection().equals(console.id));
    float unit = t.size().control();
    El list;
    if (entries.isEmpty()) {
      list = EmptyState.emptyState(
          Symbol.HISTORY,
          any ? "No statement holds “" + search + "”" : "No statements yet",
          any
              ? "The search finds statements that hold its text, in any case."
              : "The statements run in a console of this connection are listed here.",
          null);
    } else {
      var columns = List.of(
          new DataGrid.Column(
                  "Statement", 13 * unit, DataGrid.DataType.TEXT, row -> first(entries.get(row)))
              .sortable(false),
          new DataGrid.Column(
                  "Ran",
                  6 * unit,
                  DataGrid.DataType.DATE_TIME,
                  row -> WHEN.text(
                      LocalDateTime.ofInstant(entries.get(row).started(), ZoneId.systemDefault())))
              .sortable(false),
          new DataGrid.Column(
                  "Took",
                  3 * unit,
                  DataGrid.DataType.INTEGER,
                  // shortcut: milliseconds as text, as Messages has; use the kit's Format once it
                  // has one for a Duration.
                  row -> entries.get(row).took().toMillis() + " ms")
              .sortable(false),
          new DataGrid.Column(
                  "Outcome", 10 * unit, DataGrid.DataType.TEXT, row -> outcome(entries.get(row)))
              .sortable(false)
              .cellIcon(row -> switch (entries.get(row).outcome()) {
                case Outcome.Failed f -> CellIcon.before(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
                case Outcome.Cancelled c -> CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING);
                default -> null;
              }));
      // shortcut: Enter and a double press insert through onEdit, the grid's only handler for
      // them; use the grid's own once it has one for a row that is chosen.
      list = DataGrid.dataGrid(
              columns,
              entries.size(),
              DataGrid.options()
                  .select(selection)
                  .onEdit((row, column) -> insert(console, app))
                  .copy(app.clipboard()))
          .key("history")
          .label("Statements")
          .grow(1);
    }
    El content = col()
        .gap(t.space().m())
        .child(TextField.withIcon(
            TextField.textField(search, "Search", v -> search = v)
                .key("history-search")
                .label("Search")
                .w(33 * unit),
            Symbol.SEARCH))
        .child(Card.flush(list).w(33 * unit).h(10 * unit));
    Runnable close = () -> console.history = null;
    El insert = button(PRIMARY, "Insert", chosen() == null ? null : () -> insert(console, app))
        .key("history-insert");
    return Dialog.dialog(
            "History of " + State.name(s.profile(console.id)),
            content,
            List.of(button(SECONDARY, "Close", close).key("history-close"), insert),
            close,
            Dialog.options()
                .start(button(
                        SECONDARY,
                        "Clear history",
                        any ? () -> clearing = true : null,
                        Button.options().tone(Button.Tone.DANGER))
                    .key("history-clear"))
                .defaultButton(insert))
        .key("history-dialog");
  }

  /** The question before Clear history, or null while none is asked. */
  El question(State s, ConsoleTab console) {
    Theme t = Theme.CURRENT.get();
    if (!clearing) {
      return null;
    }
    long n = s.history.stream().filter(e -> e.connection().equals(console.id)).count();
    Runnable cancel = () -> clearing = false;
    return Dialog.dialog(
            "Clear the history of " + State.name(s.profile(console.id)) + "?",
            col()
                .w(ConnectionDialog.width())
                .gap(t.space().m())
                .child(label((n == 1 ? "Its statement is" : "Its " + n + " statements are")
                        + " deleted from this computer.")
                    .wrapText())
                .child(label(
                        Label.Variant.MUTED,
                        "The consoles keep their text; only the list of what ran goes.")
                    .wrapText()),
            List.of(
                button(SECONDARY, "Cancel", cancel).key("clear-cancel"),
                button(
                        PRIMARY,
                        "Clear",
                        () -> clear(s, console),
                        Button.options().tone(Button.Tone.DANGER))
                    .key("clear-confirm")),
            cancel)
        .key("clear-history");
  }

  /** The statement's first line that holds more than blanks. */
  static String first(HistoryEntry entry) {
    return entry.statement().strip().lines().findFirst().orElse("");
  }

  static String outcome(HistoryEntry entry) {
    return switch (entry.outcome()) {
      case Outcome.Rows r -> "Rows";
      case Outcome.Changed c -> ConsoleTab.rows(c.count()) + " changed";
      case Outcome.Failed f -> f.message();
      case Outcome.Cancelled c -> "Cancelled";
    };
  }
}

package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import java.time.LocalTime;
import java.util.List;

/**
 * The statement log at the bottom of the main window, below the workspace and the tabs: what
 * HooriDB sent, the newest last, as a console's Messages lists what ran, and the chosen entry's
 * statement whole, in its dialect's colors. It is collapsed until it is opened, from the status
 * line, the View menu or the palette.
 */
final class LogPanel {

  /** The panel's height where it was never moved. */
  static final Split.Position SPLIT = Split.Position.second(220);

  private static final Format<LocalTime> TIME = Format.time(3);

  boolean open;
  Split.Position split = SPLIT;
  Split.Position detail = Split.Position.share(0.6f);
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  private CodeEditor.State editor = new CodeEditor.State().readOnly(true);
  private Logged chosen; // the entry whose statement shows, or null
  private int seen = -1; // the log's changes when the rows were last followed
  private Logged newest; // the last entry then

  /** Opens it, or closes it. */
  void toggle() {
    open = !open;
  }

  /** The workspace and the tabs, with the panel below them while it is open. */
  El around(State s, El work) {
    if (!open) {
      return work;
    }
    Theme t = Theme.CURRENT.get();
    return Split.split(
        Split.Direction.COLUMN,
        split,
        SPLIT,
        p -> split = p,
        work,
        8 * t.size().control(),
        view(s).margin(t.space().m(), 0, 0, 0),
        4 * t.size().control());
  }

  /** What opens and closes it in the status line; expanded while it is open. */
  El toggle(State s, boolean modal) {
    return StatusBar.item(Symbol.TEXT_BULLET_LIST_LTR, "Statement log", modal ? null : this::toggle)
        .key("statement-log-toggle")
        .expanded(open);
  }

  private El view(State s) {
    Theme t = Theme.CURRENT.get();
    var log = s.statements;
    var entries = log.entries();
    follow(log, entries);
    El top = Card.toolbar(
        "Statement log",
        entries.size() == 1 ? "1 entry" : entries.size() + " entries",
        button(SECONDARY, Symbol.DISMISS, "Close", this::toggle).key("statement-log-close"));
    El problem = log.problem() == null
        ? null
        : col()
            .padding(t.space().s())
            .child(Callout.callout(
                Callout.Kind.WARNING, "The statement log is not kept on the disk", log.problem()));
    El body;
    if (entries.isEmpty()) {
      body = EmptyState.emptyState(
          Symbol.TEXT_BULLET_LIST_LTR,
          "Nothing sent yet",
          "What HooriDB sends to a server, from the tree, a table or a console, is listed here.",
          null);
    } else {
      body = Split.split(
          Split.Direction.ROW,
          detail,
          Split.Position.share(0.6f),
          p -> detail = p,
          grid(s, entries),
          12 * t.size().control(),
          statement(s),
          6 * t.size().control());
    }
    return Card.flush(top, problem, body.grow(1).minSize(0, 0))
        .key("statement-log")
        .label("Statement log")
        .minSize(0, 0);
  }

  // The newest entry is chosen as it comes, unless another was chosen; the rows follow it.
  private void follow(StatementLog log, List<Logged> entries) {
    if (log.changes() == seen) {
      return;
    }
    seen = log.changes();
    if (entries.isEmpty()) {
      selection.clear();
      choose(null);
      newest = null;
      return;
    }
    int at = chosen == null || chosen.equals(newest) ? -1 : entries.indexOf(chosen);
    if (at < 0) {
      at = entries.size() - 1;
      selection.scrollToRow(at);
      choose(entries.get(at));
    }
    selection.select(at, 0);
    newest = entries.getLast();
  }

  private void choose(Logged entry) {
    if (entry != chosen) {
      chosen = entry;
      editor = new CodeEditor.State().readOnly(true);
    }
  }

  private El grid(State s, List<Logged> entries) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    var zone = s.zone;
    var columns = List.of(
        new DataGrid.Column(
                "Time",
                4 * unit,
                TIME.type(),
                row -> TIME.text(LocalTime.ofInstant(entries.get(row).sent().at(), zone)))
            .sortable(false),
        new DataGrid.Column(
                "Connection",
                6 * unit,
                DataGrid.DataType.TEXT,
                row -> connection(s, entries.get(row)))
            .sortable(false),
        new DataGrid.Column(
                "Part",
                4 * unit,
                DataGrid.DataType.TEXT,
                row -> part(entries.get(row).sent().purpose()))
            .sortable(false),
        new DataGrid.Column(
                "Statement",
                16 * unit,
                DataGrid.DataType.TEXT,
                row -> ConsoleTab.brief(entries.get(row).sent().text()))
            .sortable(false),
        new DataGrid.Column(
                "Took",
                3 * unit,
                Format.DURATION.type(),
                row -> Format.DURATION.text(entries.get(row).sent().took()))
            .sortable(false),
        new DataGrid.Column(
                "Outcome",
                8 * unit,
                DataGrid.DataType.TEXT,
                row -> outcome(entries.get(row).sent().outcome()))
            .sortable(false)
            .cellIcon(row -> switch (entries.get(row).sent().outcome()) {
              case Outcome.Failed f -> CellIcon.before(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
              case Outcome.Cancelled c -> CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING);
              default -> null;
            }));
    return DataGrid.dataGrid(
            columns,
            entries.size(),
            DataGrid.options()
                .select(selection)
                .rowNumbers()
                .copy(s.app.clipboard())
                .onSelect(() -> {
                  int row = selection.row();
                  choose(
                      selection.isEmpty() || row < 0 || row >= entries.size()
                          ? null
                          : entries.get(row));
                }))
        .key("statement-log-entries")
        .label("Statements sent")
        .grow(1);
  }

  // The chosen entry's statement whole, with its connection in its color.
  private El statement(State s) {
    Theme t = Theme.CURRENT.get();
    if (chosen == null) {
      return EmptyState.emptyState(
          Symbol.CODE, "No statement", "Choose an entry to see its statement whole.", null);
    }
    var profile = s.profile(chosen.connection());
    var sent = chosen.sent();
    var where = part(sent.purpose()) + (sent.database() == null ? "" : " · " + sent.database());
    El head = row()
        .gap(t.space().s())
        .padding(t.space().s(), t.space().m())
        .align(Style.Align.CENTER)
        .child(ConnectionColors.mark(profile == null ? null : profile.color()))
        .child(
            Label.label(connection(s, chosen)).key("statement-log-connection").ellipsis())
        .child(Label.label(Label.Variant.MUTED, where).ellipsis());
    El code = CodeEditor.embedded(CodeEditor.codeEditor(
            s.app,
            sent.text(),
            editor,
            profile == null ? null : Syntax.of(profile.provider()),
            null)
        .key("statement-log-statement")
        .label("Statement"));
    return col().grow(1).minSize(0, 0).child(head).child(code.grow(1).minSize(0, 0));
  }

  private static String connection(State s, Logged entry) {
    var profile = s.profile(entry.connection());
    return profile == null ? "Deleted connection" : State.name(profile);
  }

  /** The part of the app that sends for the purpose, as the log names it. */
  static String part(Purpose purpose) {
    return switch (purpose) {
      case CATALOG -> "Tree";
      case ROWS -> "Grid";
      case ESTIMATE -> "Estimate";
      case STATEMENT -> "Console";
      case TRANSACTION -> "Transaction";
      case SESSION -> "Session";
    };
  }

  /** How it ended, as a console's Messages says it. */
  static String outcome(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Rows r -> ConsoleTab.rows(r.count()) + (r.more() ? " so far" : "");
      case Outcome.Changed c -> ConsoleTab.rows(c.count()) + " changed";
      case Outcome.Done d -> "Done";
      case Outcome.Failed f -> f.message();
      case Outcome.Cancelled c -> "Cancelled";
    };
  }
}

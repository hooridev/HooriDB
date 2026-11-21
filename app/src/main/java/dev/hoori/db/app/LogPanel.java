package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toggle;
import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
  private static final List<Purpose> PARTS = List.of(Purpose.values());
  private static final List<LogFilter.Ended> ENDINGS = List.of(LogFilter.Ended.values());

  boolean open;
  Split.Position split = SPLIT;
  Split.Position detail = Split.Position.share(0.6f);
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.ROWS);
  LogFilter filter = LogFilter.ALL;
  boolean clearing; // Clear asks first
  private final Menu.State connections = new Menu.State();
  private final Menu.State parts = new Menu.State();
  private final Menu.State endings = new Menu.State();
  private CodeEditor.State editor = new CodeEditor.State().readOnly(true);
  private Logged chosen; // the entry whose statement shows, or null
  // The entries shown, and the changes of the log and the filter they were found from.
  private List<Logged> shown = List.of();
  private int seen = -1;
  private LogFilter seenFilter;
  private Logged newest; // the last entry shown then

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
    var all = log.entries();
    var entries = shown(log);
    El top = Card.toolbar(
        "Statement log",
        filter.narrows()
            ? entries.size() + " of " + all.size()
            : all.size() == 1 ? "1 entry" : all.size() + " entries",
        Toggle.toggle(log.on(), "Logging", () -> log.turn(!log.on())).key("statement-log-on"),
        button(SECONDARY, Symbol.COPY, "Copy statement", chosen == null ? null : () -> copy(s))
            .key("statement-log-copy"),
        button(
                SECONDARY,
                Symbol.DELETE,
                "Clear…",
                // With a problem, the file may hold what does not show, or what a clear left.
                (all.isEmpty() && log.problem() == null) || clearing ? null : () -> clearing = true)
            .key("statement-log-clear"),
        button(SECONDARY, Symbol.DISMISS, "Close", this::toggle).key("statement-log-close"));
    El body;
    if (entries.isEmpty()) {
      body = empty(log, all.isEmpty());
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
    return Card.flush(top, Card.filters(filters(s, all)), said(s), body.grow(1).minSize(0, 0))
        .key("statement-log")
        .label("Statement log")
        .minSize(0, 0);
  }

  // The entries the filter admits, found again where the log or the filter changed; the rows
  // follow the newest as it comes, unless another was chosen.
  private List<Logged> shown(StatementLog log) {
    if (log.changes() == seen && filter.equals(seenFilter)) {
      return shown;
    }
    seen = log.changes();
    seenFilter = filter;
    shown = log.entries().stream().filter(filter::admits).toList();
    if (shown.isEmpty()) {
      selection.clear();
      choose(null);
      newest = null;
      return shown;
    }
    int at = chosen == null || chosen.equals(newest) ? -1 : shown.indexOf(chosen);
    if (at < 0) {
      at = shown.size() - 1;
      selection.scrollToRow(at);
      choose(shown.get(at));
    }
    selection.select(at, 0);
    newest = shown.getLast();
    return shown;
  }

  private void choose(Logged entry) {
    if (entry != chosen) {
      chosen = entry;
      editor = new CodeEditor.State().readOnly(true);
    }
  }

  // The connection, the part and how it ended, each of all or one, and the search.
  private El filters(State s, List<Logged> all) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    var ids = new ArrayList<>(all.stream().map(Logged::connection).distinct().toList());
    // The chosen connection stays to choose while its entries have gone, as the filter holds it.
    if (filter.connection() != null && !ids.contains(filter.connection())) {
      ids.add(filter.connection());
    }
    var names = new ArrayList<String>(List.of("All connections"));
    ids.forEach(id -> names.add(connection(s, id)));
    var partNames = new ArrayList<String>(List.of("All parts"));
    PARTS.forEach(p -> partNames.add(part(p)));
    var endingNames = new ArrayList<String>(List.of("Any outcome"));
    ENDINGS.forEach(e -> endingNames.add(e.label));
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(Select.select(
                names,
                filter.connection() == null ? 0 : ids.indexOf(filter.connection()) + 1,
                connections,
                i -> filter = filter.withConnection(i == 0 ? null : ids.get(i - 1)))
            .label("Connection")
            .w(7 * unit))
        .child(Select.select(
                partNames,
                filter.part() == null ? 0 : PARTS.indexOf(filter.part()) + 1,
                parts,
                i -> filter = filter.withPart(i == 0 ? null : PARTS.get(i - 1)))
            .label("Part")
            .w(5 * unit))
        .child(Select.select(
                endingNames,
                filter.ended() == null ? 0 : ENDINGS.indexOf(filter.ended()) + 1,
                endings,
                i -> filter = filter.withEnded(i == 0 ? null : ENDINGS.get(i - 1)))
            .label("Outcome")
            .w(5 * unit))
        .child(TextField.withIcon(
            TextField.textField(filter.search(), "Search", v -> filter = filter.withSearch(v))
                .key("statement-log-search")
                .label("Search")
                .w(10 * unit),
            Symbol.SEARCH));
  }

  // What the panel has to say above the entries: the question before Clear, that logging is off,
  // or that the file could not be read or written; null for nothing.
  private El said(State s) {
    Theme t = Theme.CURRENT.get();
    var log = s.statements;
    El callout;
    if (clearing) {
      int n = log.entries().size();
      callout = Callout.callout(
              Callout.Kind.WARNING,
              "Clear the statement log?",
              n == 0
                  ? "What it kept is deleted from this computer."
                  : (n == 1 ? "Its entry is" : "Its " + n + " entries are")
                      + " deleted, here and from this computer.",
              button(SECONDARY, "Cancel", () -> clearing = false).key("statement-log-keep"),
              button(
                      SECONDARY,
                      "Clear",
                      () -> {
                        clearing = false;
                        log.clear();
                      },
                      Button.options().tone(Button.Tone.DANGER))
                  .key("statement-log-confirm"))
          .key("statement-log-question");
    } else {
      // That logging is off, and also why the file could not be written, as the choice itself.
      var said = col().padding(t.space().s()).gap(t.space().s());
      if (!log.on()) {
        said.child(Callout.callout(
                Callout.Kind.INFO, "Logging is off", "Nothing that is sent now is logged.")
            .key("statement-log-off"));
      }
      if (log.problem() != null) {
        said.child(Callout.callout(
                Callout.Kind.WARNING, "The statement log is not kept on the disk", log.problem())
            .key("statement-log-problem"));
      }
      return log.on() && log.problem() == null ? null : said;
    }
    return col().padding(t.space().s()).child(callout);
  }

  private El empty(StatementLog log, boolean none) {
    if (!none) {
      return EmptyState.emptyState(
          Symbol.FILTER,
          "No entry matches",
          "The filters and the search narrow what was sent to what they name.",
          button(SECONDARY, "Clear filters", () -> filter = LogFilter.ALL)
              .key("statement-log-unfilter"));
    }
    return EmptyState.emptyState(
        Symbol.TEXT_BULLET_LIST_LTR,
        log.on() ? "Nothing sent yet" : "Logging is off",
        log.on()
            ? "What HooriDB sends to a server, from the tree, a table or a console, is listed here."
            : "Turned on, what HooriDB sends to a server is listed here.",
        null);
  }

  /** Copies the chosen entry's statement, whole. */
  private void copy(State s) {
    s.app.clipboard().write(chosen.sent().text());
    s.tell("Statement copied");
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
                5 * unit,
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
                11 * unit,
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
    return connection(s, entry.connection());
  }

  // Its name, told apart from another of the same name as the palette does; a deleted one by the
  // start of its id.
  private static String connection(State s, UUID id) {
    var profile = s.profile(id);
    return profile == null
        ? "Deleted connection (" + id.toString().substring(0, 8) + ")"
        : GoTo.label(profile, s.profiles);
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

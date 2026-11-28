package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.Values;
import dev.hoori.db.core.execution.Transaction;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * A console's results area: the results of its last runs in tabs, each rows in a grid, documents
 * or a count, beside Messages, and above them what went wrong or keeps the console from running.
 * It shows what the console holds and changes only how that is shown.
 */
final class ResultsArea {

  private static final long COPY_LIMIT = 100_000;

  private final ConsoleTab tab;
  private final DataGrid.Selection logSelection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  // The whole document value last read for the value panel, reused across frames while on the same
  // cell so JSON is neither serialized nor formatted anew on every frame.
  private Document wholeDocument;
  private Field wholeField;
  private Object wholeValue;

  ResultsArea(ConsoleTab tab) {
    this.tab = tab;
  }

  /** The results of the last runs in tabs, with Messages; what went wrong above them. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    El note = note(s);
    boolean none = tab.outputs.isEmpty() && tab.log.isEmpty();
    if (none && tab.working()) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .label("Results")
          .child(Progress.spinner())
          .child(Label.label(
              Label.Variant.MUTED,
              tab.activity instanceof ConsoleTab.Waiting ? "Connecting…" : "Running…"));
    }
    El body = none
        ? EmptyState.emptyState(
            Symbol.PLAY,
            "Nothing run yet",
            "Control+Enter runs the statement at the caret, Control+Shift+Enter all of them.",
            null)
        : tabs(s, app);
    return col()
        .grow(1)
        .minSize(0, 0)
        .label("Results")
        .child(note == null ? null : col().padding(t.space().m()).shrink(0).child(note))
        .child(body);
  }

  /** A tab for each result and one for Messages, with the one shown. */
  private El tabs(State s, App<State> app) {
    var tabs = new ArrayList<Tabs.Tab>();
    for (var o : tab.outputs) {
      tabs.add(new Tabs.Tab("Result " + o.number, false, false, icon(o)));
    }
    tabs.add(new Tabs.Tab("Messages", false, false, Symbol.TEXT_BULLET_LIST_LTR));
    int at = tab.shown == null ? -1 : tab.outputs.indexOf(tab.shown);
    El content = at < 0 ? messages() : output(s, app, tab.shown);
    return Tabs.tabs(
            tabs,
            at < 0 ? tab.outputs.size() : at,
            i -> tab.shown = i < tab.outputs.size() ? tab.outputs.get(i) : null,
            i -> {},
            content,
            Tabs.options().switchKeys(false))
        .key("results")
        .grow(1);
  }

  /** A result's tab's icon: pinned, a count, or rows. */
  private static Symbol icon(ConsoleTab.Output o) {
    if (o.pinned) {
      return Symbol.PIN;
    }
    return o.fields == null ? Symbol.CHECKMARK_CIRCLE : Symbol.TABLE;
  }

  /** Why nothing runs or what failed, with what the user can do; null for nothing. */
  private El note(State s) {
    Theme t = Theme.CURRENT.get();
    if (tab.activity instanceof ConsoleTab.Stranded) {
      return strandedNote(s);
    }
    // An outcome in doubt stays said, also over later runs, until the user has read it.
    // The act that holds the connection, as a save of it: nothing runs until it is done.
    var locked = s.guard.locked(tab);
    El lock = locked == null
        ? null
        : Callout.callout(
                Callout.Kind.INFO,
                locked,
                "Nothing runs on this console until that is done or given up.")
            .key("locked");
    El manual = tab.unavailable == null || tab.mode != ConsoleTab.Mode.AUTO
        ? null
        : Callout.callout(Callout.Kind.WARNING, "Manual is unavailable", tab.unavailable)
            .key("manual-unavailable");
    El doubted = tab.doubt == null
        ? null
        : Callout.callout(
                Callout.Kind.DANGER,
                ConsoleTab.UNSURE,
                tab.doubt,
                button(SECONDARY, "Acknowledge", () -> tab.acknowledge(s)).key("acknowledge"))
            .key("doubt");
    El failed = tab.transaction == Transaction.FAILED ? failedNote(s) : null;
    El reported = tab.problem == null
        ? null
        : Callout.callout(Callout.Kind.DANGER, tab.problem.title(), tab.problem.text())
            .key("problem");
    var notes = new ArrayList<El>();
    for (var note : new El[] {lock, doubted, reported, manual, failed}) {
      if (note != null) {
        notes.add(note);
      }
    }
    if (notes.size() <= 1) {
      return notes.isEmpty() ? null : notes.getFirst();
    }
    return col().gap(t.space().s()).children(notes.toArray(El[]::new));
  }

  /** The tree's connection failed while the console waited for it: why, with Try again. */
  private El strandedNote(State s) {
    var trouble = tab.id == null ? null : s.catalog.problem(s, new Node.Connection(tab.id));
    var actions = new ArrayList<El>();
    if (trouble != null && trouble.password()) {
      actions.add(button(SECONDARY, "Enter password…", () -> tab.retry(s, true)));
    }
    actions.add(button(SECONDARY, "Try again", () -> tab.retry(s, false)).key("try-again"));
    return Callout.callout(
            Callout.Kind.DANGER,
            trouble == null ? "Not connected" : trouble.title(),
            trouble == null ? null : trouble.text(),
            actions.toArray(El[]::new))
        .key("unconnected");
  }

  /** The transaction failed: the server takes nothing more in it but a rollback. */
  private El failedNote(State s) {
    return Callout.callout(
            Callout.Kind.WARNING,
            "The transaction failed",
            "The server ignores every statement in it until it is rolled back; its changes are"
                + " lost.",
            button(
                    SECONDARY,
                    ConsoleTab.ROLLBACK_BUTTON,
                    tab.canRollback() ? () -> tab.rollBackTransaction(s) : null)
                .key("roll-back"))
        .key("failed");
  }

  /** A result: its rows in the grid, documents whole, or the number of rows it changed. */
  private El output(State s, App<State> app, ConsoleTab.Output o) {
    El pin = Button.toggleIcon(
            Symbol.PIN, o.pinned ? "Unpin" : "Pin", o.pinned, on -> o.pinned = on)
        .key("pin");
    if (o.fields == null) {
      return changed(s, o, pin);
    }
    boolean ofDocuments = o.ofDocuments;
    String about = about(o, ofDocuments);
    var presented = o.presentation.view(
        app, s.zone, ofDocuments, () -> gridView(s, app, o), () -> documentPage(s, o));
    var footer = new ArrayList<El>();
    if (tab.canStopReading(s, o)) {
      // An icon, as Pin: the footer of an open MongoDB cursor holds the switcher and paging too.
      footer.add(
          Button.icon(Button.Variant.GHOST, Symbol.STOP, "Stop reading", () -> tab.stopReading(s))
              .key("stop-reading"));
    }
    if (ofDocuments) {
      footer.add(o.presentation.picker("result-shown", o.presentation::mode));
    }
    ResultEdits.editControls(s, tab, o, footer, footer);
    // The edit feedback may shorten to nothing, so the status's tooltip has it too.
    String why = null;
    for (El e : footer) {
      if ("edit-status".equals(e.key())) {
        why = e.text().string();
      }
    }
    footer.addAll(presented.footer());
    footer.add(pin);
    El label = Label.label(Label.Variant.MUTED, about).key("result-status").ellipsis();
    El status = why == null
        ? Tooltip.whenShortened(label, about)
        : Tooltip.tooltip(label, about + "\n" + why);
    // As in a table's tab: the status keeps half the footer, so the texts at the end shorten first.
    status.style().minWidth = Len.percent(0.5f);
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(ResultEdits.writeProblemCallout(o.edits))
        .child(presented.body().grow(1))
        .child(Card.footer(status, footer.toArray(El[]::new)));
  }

  /** A count: the number of rows, or documents, the statement changed. */
  private El changed(State s, ConsoleTab.Output o, El pin) {
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(EmptyState.emptyState(
                Symbol.CHECKMARK_CIRCLE,
                ConsoleTab.rows(o.count, tab.documents(s)) + ConsoleTab.CHANGED,
                null,
                null)
            .key("changed"))
        .child(Card.footer(
            Label.label(Label.Variant.MUTED, ConsoleTab.brief(o.statement)).ellipsis(), pin));
  }

  /** What a result's status line says: how many of its rows it holds, and its statement. */
  private static String about(ConsoleTab.Output o, boolean documents) {
    // Of a read left open, the server's estimate, short and first, as the footer may be narrow;
    // none
    // below the rows read.
    String count = o.estimated > 0 && o.estimated >= o.rows.size() && (o.more || o.cut)
        ? String.format(Locale.ROOT, "%,d", o.rows.size()) + " of about "
            + ConsoleTab.rows(o.estimated, documents)
        : ConsoleTab.rows(o.rows.size(), documents);
    String held;
    if (o.more) {
      held = count + " so far · cursor open, more as it scrolls";
    } else if (o.cut) {
      held = "the first " + count + "; "
          + switch (o.cutReason) {
            case LEFT -> "the rest was not read";
            case STOPPED -> "reading stopped";
            case IDLE -> "reading stopped after being idle";
            case LIMIT -> "limit reached, the rest was not read";
          };
    } else if (o.dropped > 0) {
      held = "the first " + count + " of " + ConsoleTab.rows(o.rows.size() + o.dropped, documents)
          + "; the rest was read and not kept";
    } else {
      held = count;
    }
    // The rows first: a long statement is cut short at the end, not the state of its read.
    return held + " · " + ConsoleTab.brief(o.statement);
  }

  /** The page of the result's documents for the JSON and Tree strategies. */
  private DocumentPresentation.DocumentPage documentPage(State s, ConsoleTab.Output o) {
    int last = Math.max(0, (o.documents.size() - 1) / ConsoleTab.PORTION);
    if (o.presentation.page() > last && !tab.readsOn(o)) {
      o.presentation.page(
          last); // the next page did not come: nothing was left, or the read stopped
    }
    int page = (int) o.presentation.page();
    int from = page * ConsoleTab.PORTION;
    int to = Math.min(from + ConsoleTab.PORTION, o.documents.size());
    var paging = new DocumentPresentation.Paging(
        page > 0,
        (page + 1) * ConsoleTab.PORTION < o.documents.size() || o.more,
        () -> turn(s, o, 0),
        () -> turn(s, o, page - 1),
        () -> turn(s, o, page + 1),
        o.more ? null : () -> turn(s, o, last)); // the last is known once all is read
    return new DocumentPresentation.DocumentPage(
        "p" + page,
        o.documents,
        from,
        to,
        from,
        to,
        to <= from && o.more,
        "Documents of result " + o.number,
        paging);
  }

  /**
   * Shows the page of the result's documents, reading the next portion where it is not full: not
   * past one that is not, which shows what comes first.
   */
  private void turn(State s, ConsoleTab.Output o, int page) {
    int at = o.documents.size();
    int clamped = Math.clamp(page, 0, at / ConsoleTab.PORTION);
    o.presentation.page(clamped);
    if ((clamped + 1) * ConsoleTab.PORTION > at) {
      tab.want(s, o, at + ConsoleTab.PORTION - 1);
    }
  }

  /**
   * The result's grid and value panel input: where the result holds documents whole, the panel
   * takes the cell's value from the document; a text cut short beyond a SQL result's budget stays
   * so, as the statement may not name a row to read it by.
   */
  private DocumentPresentation.GridView gridView(State s, App<State> app, ConsoleTab.Output o) {
    int row = o.selection.row();
    int column = o.selection.column();
    ValuePanel.Cell cell = null;
    String note = null;
    if (!tab.value.open && wholeDocument != null) {
      wholeDocument = null;
      wholeField = null;
      wholeValue = null;
    }
    // A row beyond those read is the one that asks for more.
    if (tab.value.open
        && row >= 0
        && row < o.rows.size() + (o.more ? 1 : 0)
        && column >= 0
        && column < o.fields.size()) {
      var field = o.fields.get(column);
      var v = row < o.rows.size() ? panelValue(o, row, column) : null;
      cell = new ValuePanel.Cell(field.name(), field.type(), field.kind(), v);
      if (v instanceof Cut) {
        note = Cells.icon(v).tooltip() + "; a result keeps no more";
      }
    }
    return new DocumentPresentation.GridView(
        () -> grid(s, app, o),
        tab.value,
        cell,
        note,
        cell != null && row >= o.rows.size(),
        null,
        null);
  }

  /** The cell's whole value for the value panel, cached across frames while on the same cell. */
  private Object panelValue(ConsoleTab.Output o, int row, int column) {
    if (o.edits.changes != null
        && o.edits.changes.cellStatus(row, column) != DataGrid.Status.UNCHANGED) {
      wholeDocument = null;
      wholeField = null;
      wholeValue = null;
      return o.edits.changes.cell(row, column);
    }
    if (o.documents.isEmpty() || row >= o.documents.size()) {
      wholeDocument = null;
      wholeField = null;
      wholeValue = null;
      return o.rows.get(row).get(column);
    }
    var document = o.documents.get(row);
    var field = o.fields.get(column);
    if (document != wholeDocument || !field.equals(wholeField)) {
      wholeDocument = document;
      wholeField = field;
      wholeValue = Values.whole(field, document);
    }
    return wholeValue;
  }

  // shortcut: an editable result keeps the kit's copy limit for paste and clear, so Control+C of
  // one
  // cell copies it whole here; drop this once they have a limit of their own (rethinkj/ui#511).
  private static boolean copiesOneWhole(
      App<State> app, ConsoleTab.Output o, Key k, ZoneId zone, String nullText) {
    boolean copy =
        k.modifiers() == Key.CONTROL && (k.name().equals("C") || k.name().equals("Insert"));
    if (!copy || o.selection.isEditing()) {
      return false;
    }
    var ranges = o.selection.ranges();
    var only = ranges.size() == 1 ? ranges.getFirst() : null;
    if (!ranges.isEmpty()
        && (only == null || only.top() != only.bottom() || only.left() != only.right())) {
      return false;
    }
    int row = only == null ? o.selection.row() : only.top();
    int column = only == null ? o.selection.column() : only.left();
    if (row < 0 || row >= o.rows.size() || column < 0 || column >= o.fields.size()) {
      return false;
    }
    var at = new DataGrid.Range(row, column, row, column);
    copy(app, o, new DataGrid.Copy(List.of(at), false, 1), zone, nullText);
    return true;
  }

  /** The cell's whole value: from the document where the result holds documents, else the row's. */
  private static Object cellValue(ConsoleTab.Output o, int row, int column) {
    return !o.documents.isEmpty() && row < o.documents.size()
        ? Values.whole(o.fields.get(column), o.documents.get(row))
        : o.rows.get(row).get(column);
  }

  /** The result's rows, editable where core found the result editable, with the table tab's cells. */
  private El grid(State s, App<State> app, ConsoleTab.Output o) {
    var zone = s.zone;
    var nullText = Cells.nullText(s.settings.copyNull());
    var editable = o.edits.editable;
    var changes = o.edits.changes;
    // While a write runs, an act holds the console or its connection was opened again, no edit,
    // paste, undo or redo comes between; the changes staged stay shown.
    BooleanSupplier free = () ->
        o.edits.writing == null && s.guard.locked(tab) == null && ResultEdits.current(s, tab, o);
    var columns = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < o.fields.size(); c++) {
      int at = c;
      var field = o.fields.get(c);
      var col = Cells.column(
          field, o.widths, c, row -> row < o.rows.size() ? o.rows.get(row).get(at) : null, zone);
      columns.add(
          editable != null && changes != null
              ? ResultEdits.editedColumn(o, col, at, free, true)
              : col);
    }
    var options = DataGrid.options()
        .select(o.selection)
        .onResize((c, w) -> o.widths[c] = w)
        .fitColumns(o.fit)
        .rowNumbers()
        .copy(app.clipboard())
        .nullText(nullText)
        // Every copy whole; an editable result keeps the kit's limit, which also bounds a paste or
        // clear of a selection (rethinkj/ui#511).
        .copyLimit(editable == null ? 0 : 100_000, copy -> copy(app, o, copy, zone, nullText))
        .loading(row -> row < o.rows.size(), (_, last) -> tab.want(s, o, last));
    int count;
    if (editable != null && changes != null) {
      ResultEdits.configureGrid(s, o, options, free, false);
      count = changes.rowCount();
    } else {
      count = (int) Math.min(o.rows.size() + (o.more ? 1L : 0L), DocumentPresentation.GRID_ROWS);
    }
    El grid =
        DataGrid.dataGrid(columns, count, options).key("grid").label("Rows of result " + o.number);
    if (editable != null && changes != null) {
      Predicate<Key> guard = k ->
          ResultEdits.rejectUnloadedKey(app, o, k) || copiesOneWhole(app, o, k, zone, nullText);
      grid.onKeyFirst(guard);
    }
    return grid;
  }

  /**
   * Copies the selected cells: one cell whole, as the grid's own cells hold only the start of a
   * long text; more cells as the grid shows them, which bounds the text of a large selection.
   */
  // shortcut: the grid copies what it shows (rethinkj/ui#414); drop this once it copies it whole.
  private static void copy(
      App<State> app, ConsoleTab.Output o, DataGrid.Copy copy, ZoneId zone, String nullText) {
    if (copy.cells() > COPY_LIMIT) {
      return;
    }
    for (var range : copy.ranges()) {
      if (range.bottom() >= o.rows.size()) {
        return;
      }
    }
    boolean one = copy.cells() == 1;
    var changes = o.edits.changes;
    var whole = new ArrayList<DataGrid.Column>(o.fields.size());
    for (int c = 0; c < o.fields.size(); c++) {
      var field = o.fields.get(c);
      int at = c;
      whole.add(new DataGrid.Column(field.name(), 0, Cells.type(field.kind()), row -> {
        if (changes != null && changes.cellStatus(row, at) != DataGrid.Status.UNCHANGED) {
          return changes.cell(row, at);
        }
        return one
            ? Cells.text(cellValue(o, row, at), zone)
            : Cells.cell(o.rows.get(row).get(at), zone);
      }));
    }
    app.clipboard().write(DataGrid.copyText(whole, copy.ranges(), copy.headers(), nullText));
  }

  /** What ran, how long it took, what it gave, and what the server said along the way. */
  private El messages() {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    var columns = List.of(
        new DataGrid.Column(
                "Statement",
                16 * unit,
                DataGrid.DataType.TEXT,
                row -> tab.log.get(row).statement())
            .sortable(false),
        new DataGrid.Column(
                "Took",
                4 * unit,
                Format.DURATION.type(),
                row -> Format.DURATION.text(tab.log.get(row).took()))
            .sortable(false),
        new DataGrid.Column(
                "Result",
                20 * unit,
                DataGrid.DataType.TEXT,
                row -> tab.log.get(row).text())
            .sortable(false)
            .cellIcon(row -> switch (tab.log.get(row).level()) {
              case RAN -> null;
              case WARNING -> CellIcon.before(Symbol.WARNING, Icon.Variant.WARNING);
              case ERROR -> CellIcon.before(Symbol.ERROR_CIRCLE, Icon.Variant.DANGER);
            }));
    return DataGrid.dataGrid(
            columns, tab.log.size(), DataGrid.options().select(logSelection).rowNumbers())
        .key("messages")
        .label("Messages")
        .grow(1);
  }
}

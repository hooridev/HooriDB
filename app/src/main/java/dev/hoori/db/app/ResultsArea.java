package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
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
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Transaction;
import java.util.ArrayList;
import java.util.List;

/**
 * A console's results area: the results of its last runs in tabs, each rows in a grid, documents
 * or a count, beside Messages, and above them what went wrong or keeps the console from running.
 * It shows what the console holds and changes only how that is shown.
 */
final class ResultsArea {

  private final ConsoleTab tab;

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
          .child(Label.label(Label.Variant.MUTED, tab.waiting ? "Connecting…" : "Running…"));
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
    if (tab.stranded) {
      return strandedNote(s);
    }
    // An outcome in doubt stays said, also over later runs, until the user has read it.
    // The act that holds the connection, as a save of it: nothing runs until it is done.
    var locked = s.locked(tab);
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
    var trouble = s.catalog.problem(s, new Node.Connection(tab.id));
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

  /** A result: its rows in the grid, or the number of rows it changed; whether it is pinned. */
  private El output(State s, App<State> app, ConsoleTab.Output o) {
    El pin = Button.toggleIcon(
            Symbol.PIN, o.pinned ? "Unpin" : "Pin", o.pinned, on -> o.pinned = on)
        .key("pin");
    if (o.fields == null) {
      return changed(s, o, pin);
    }
    String about = about(o);
    if (o.documents.isEmpty()) {
      return col()
          .grow(1)
          .minSize(0, 0)
          .child(withValue(s, app, o, grid(s, app, o)))
          .child(Card.footer(
              Label.label(Label.Variant.MUTED, about).key("result-status").ellipsis(),
              tab.value.button(),
              pin));
    }
    return documents(s, app, o, about, pin);
  }

  /** A count: the number of rows, or documents, the statement changed. */
  private El changed(State s, ConsoleTab.Output o, El pin) {
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(EmptyState.emptyState(
                Symbol.CHECKMARK_CIRCLE,
                ConsoleTab.rows(o.count, tab.keepsDocuments(s)) + ConsoleTab.CHANGED,
                null,
                null)
            .key("changed"))
        .child(Card.footer(
            Label.label(Label.Variant.MUTED, ConsoleTab.brief(o.statement)).ellipsis(), pin));
  }

  /** What a result's status line says: its statement, and how many of its rows it holds. */
  private static String about(ConsoleTab.Output o) {
    boolean whole = !o.documents.isEmpty();
    String count = ConsoleTab.rows(o.rows.size(), whole);
    String held;
    if (o.more) {
      held = count + " so far, more as it scrolls";
    } else if (o.cut) {
      held = "the first " + count + "; the rest was not read";
    } else if (o.dropped > 0) {
      held = "the first " + count + " of " + ConsoleTab.rows(o.rows.size() + o.dropped, whole)
          + "; the rest was read and not kept";
    } else {
      held = count;
    }
    return ConsoleTab.brief(o.statement) + " · " + held;
  }

  /** Documents: as a grid, or whole, a page of those read so far, with the table's pager. */
  private El documents(State s, App<State> app, ConsoleTab.Output o, String about, El pin) {
    var footer = new ArrayList<El>();
    footer.add(Segmented.segmented(
            List.of(TableTab.Shown.values()), o.shown, v -> v.title, v -> o.shown = v)
        .key("result-shown")
        .label("Show documents as"));
    El body;
    if (o.shown == TableTab.Shown.GRID) {
      footer.add(tab.value.button());
      body = withValue(s, app, o, grid(s, app, o));
    } else {
      body = page(s, app, o, footer);
    }
    footer.add(pin);
    return col()
        .grow(1)
        .minSize(0, 0)
        .child(body.grow(1))
        .child(Card.footer(
            Label.label(Label.Variant.MUTED, about).key("result-status").ellipsis(),
            footer.toArray(El[]::new)));
  }

  /** The page of the documents shown whole; its range and pager go into the footer. */
  private El page(State s, App<State> app, ConsoleTab.Output o, List<El> footer) {
    int last = Math.max(0, (o.documents.size() - 1) / ConsoleTab.PORTION);
    if (o.page > last && !tab.readsOn(o)) {
      o.page = last; // the next page did not come: nothing was left, or the read stopped
    }
    int from = o.page * ConsoleTab.PORTION;
    int to = Math.min(from + ConsoleTab.PORTION, o.documents.size());
    footer.add(Label.label(
            Label.Variant.MUTED, to <= from ? "No documents" : TableTab.range(from + 1L, to, true))
        .key("rows-in-view"));
    footer.add(TableTab.paging(
        o.page > 0,
        (o.page + 1) * ConsoleTab.PORTION < o.documents.size() || o.more,
        () -> turn(s, o, 0),
        () -> turn(s, o, o.page - 1),
        () -> turn(s, o, o.page + 1),
        o.more ? null : () -> turn(s, o, last))); // the last is known once all is read
    if (to <= from) {
      return col()
          .center()
          .child(Label.label(Label.Variant.MUTED, o.more ? "Reading documents…" : "No documents")
              .key("no-documents"));
    }
    o.whole.show("p" + o.page, o.documents, from, to);
    return o.whole.view(app, o.shown, s.zone, "Documents of result " + o.number);
  }

  /**
   * Shows the page of the result's documents, reading the next portion where it is not full: not
   * past one that is not, which shows what comes first.
   */
  private void turn(State s, ConsoleTab.Output o, int page) {
    int at = o.documents.size();
    o.page = Math.clamp(page, 0, at / ConsoleTab.PORTION);
    if ((o.page + 1) * ConsoleTab.PORTION > at) {
      tab.want(s, o, at + ConsoleTab.PORTION - 1);
    }
  }

  /**
   * The result's grid with the value panel, which shows the current cell's value as the result
   * holds it: a text cut short stays so, as the statement may not name a row to read it by.
   */
  private El withValue(State s, App<State> app, ConsoleTab.Output o, El grid) {
    int row = o.selection.row();
    int column = o.selection.column();
    ValuePanel.Cell cell = null;
    String note = null;
    // A row beyond those read is the one that asks for more.
    if (tab.value.open
        && row >= 0
        && row < o.rows.size() + (o.more ? 1 : 0)
        && column >= 0
        && column < o.fields.size()) {
      var field = o.fields.get(column);
      var v = row < o.rows.size() ? o.rows.get(row).get(column) : null;
      cell = new ValuePanel.Cell(field.name(), field.type(), field.kind(), v);
      if (v instanceof Cut) {
        note = Cells.icon(v).tooltip() + "; a result keeps no more";
      }
    }
    return tab.value.around(app, s.zone, grid, cell, note, cell != null && row >= o.rows.size());
  }

  /** The result's rows, read-only, with the table tab's cells. */
  private El grid(State s, App<State> app, ConsoleTab.Output o) {
    Theme t = Theme.CURRENT.get();
    var zone = s.zone;
    var columns = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < o.fields.size(); c++) {
      var field = o.fields.get(c);
      var kind = field.kind();
      int at = c;
      if (o.widths[c] < 0) {
        o.widths[c] = Cells.width(kind) * t.size().control();
      }
      var column = new DataGrid.Column(
              field.name(),
              o.widths[c],
              Cells.type(kind),
              row -> Cells.text(o.rows.get(row).get(at), zone))
          .typeName(field.type())
          .sortable(false);
      if (kind == Kind.TEXT || kind == Kind.JSON || kind == Kind.OTHER) {
        column = column.cellIcon(row -> Cells.icon(o.rows.get(row).get(at)));
      }
      columns.add(column);
    }
    var options = DataGrid.options()
        .select(o.selection)
        .onResize((c, w) -> o.widths[c] = w)
        .fitColumns(o.fit)
        .rowNumbers()
        .copy(app.clipboard())
        .loading(row -> row < o.rows.size(), (_, last) -> tab.want(s, o, last));
    int count = (int) Math.min(o.rows.size() + (o.more ? 1L : 0L), TableTab.GRID_ROWS);
    return DataGrid.dataGrid(columns, count, options)
        .key("grid")
        .label("Rows of result " + o.number);
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
            columns, tab.log.size(), DataGrid.options().select(tab.logSelection).rowNumbers())
        .key("messages")
        .label("Messages")
        .grow(1);
  }
}

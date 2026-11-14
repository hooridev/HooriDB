package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * A table's rows in a tab. They are read in pages, on the lane of the table's connection, as the
 * grid builds the rows in view: the pages in view first, one more on each side behind them, and
 * no more than {@link #KEPT} are held. A read the grid has scrolled away from is cancelled. Each
 * page is read on its own, so rows may repeat or go missing while others change the table;
 * Refresh reads it anew. Only the app's thread uses it.
 */
final class TableTab implements Tab {

  /**
   * Rows a page holds: some seven screens. A read far into a table costs the server mostly the
   * rows it skips, so twice the rows cost little more, and the next screens are there already.
   */
  static final int PAGE = 200;

  /**
   * Pages read beyond those in view, on each side, so that scrolling on finds them read; none for a
   * table without a primary key.
   */
  static final int AROUND = 1;

  /** Pages held at most: those in view and around them, the farthest dropped first. */
  static final int KEPT = 10;

  /** An estimate below this is counted exactly at once, which then costs the server little. */
  static final long COUNT_BELOW = 100_000;

  /** What the number of rows rests on. */
  enum Counted {
    /** The server's estimate, before the pages said otherwise. */
    ESTIMATED,
    /** A page reached the number thought, and the rows may go on. */
    AT_LEAST,
    /** Counted, or a page ended before it was full. */
    EXACT
  }

  /** Why the rows could not be read. */
  enum Problem {
    GONE,
    DENIED,
    LOST,
    DISCARDED,
    UNOPENED,
    CANCELLED,
    OTHER
  }

  /** @param detail the server's or the driver's words, withheld where they hold the password */
  record Failure(Problem problem, String detail) {}

  final UUID id;
  final Table table;
  List<Column> columns; // as the catalogue read them; null until then
  float[] widths; // of the columns, which the user may change
  private List<Field> fields; // as the last read gave them, which each page has to match
  long rows; // as far as known, which the grid shows
  Counted counted = Counted.ESTIMATED;
  final Map<Long, List<List<Object>>> pages = new HashMap<>(); // the rows read, by page
  final Map<Long, Work> reading = new HashMap<>(); // the pages asked for
  Work opening; // the columns, the estimate and the first page, or null
  Work counting; // the exact count, or null
  private int first; // the rows the grid built last
  private int last = -1;
  Failure failure; // why the last read failed, or null
  long took = -1; // nanoseconds the last read took on the server, -1 before the first
  int got; // rows of the last read
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final Menu.State menu = new Menu.State();

  TableTab(UUID id, Table table) {
    this.id = id;
    this.table = table;
  }

  /**
   * Reads the table: its columns from the catalogue, anew unless the tree has them and this is
   * not a refresh, the server's estimate of its rows, and the page at the first row in view, all
   * in one call. The rows read before go; the place in view stays.
   */
  void open(State s, boolean anew) {
    var lane = s.catalogue.lane(id, table);
    if (lane == null) {
      return; // what was read stays, and the view says that it is not connected
    }
    cancel();
    failure = null;
    pages.clear();
    if (columns == null) {
      columns = s.catalogue.columns(id, table);
      if (columns != null) {
        shape(columns);
        rows = PAGE; // loading rows until the estimate comes
      }
    }
    var known = anew ? null : columns;
    boolean recount = anew && counted == Counted.EXACT; // a refresh counts again what was counted
    var of = table;
    long from = (long) first / PAGE * PAGE;
    record Opened(List<Column> columns, OptionalLong estimate, Page page, long took) {}
    var asked = lane.submit(Lane.Priority.VIEW, link -> {
      var read = known != null ? known : link.columns(of);
      var estimate = link.estimate(of);
      long start = System.nanoTime();
      var page = link.read(of, read, from, from + PAGE);
      return new Opened(read, estimate, page, System.nanoTime() - start);
    });
    opening = s.app.background(
        "open " + table.name(),
        () -> Lane.await(asked),
        opened -> {
          opening = null;
          if (!opened.columns().equals(columns)) {
            shape(opened.columns());
            s.catalogue.columnsRead(id, table, opened.columns());
          }
          columns = opened.columns();
          fields = opened.page().fields();
          rows = opened.estimate().orElse(0);
          counted = opened.estimate().isPresent() ? Counted.ESTIMATED : Counted.AT_LEAST;
          arrived(s, from / PAGE, opened.page(), opened.took());
          want(s, first, last);
          var estimate = opened.estimate();
          if (counted != Counted.EXACT
              && (recount || estimate.isPresent() && estimate.getAsLong() < COUNT_BELOW)) {
            count(s);
          }
        },
        error -> {
          opening = null;
          failed(s, error, false);
        });
  }

  /** The widths of new columns: those of the same name keep theirs. */
  private void shape(List<Column> next) {
    var old = new HashMap<String, Float>();
    for (int i = 0; columns != null && widths != null && i < columns.size(); i++) {
      old.put(columns.get(i).name(), widths[i]);
    }
    widths = new float[next.size()];
    for (int i = 0; i < widths.length; i++) {
      widths[i] = old.getOrDefault(next.get(i).name(), -1f);
    }
  }

  /**
   * The grid built these rows: the pages they are on are read first, then one on each side, and
   * what is read for rows far from them is given up.
   */
  void want(State s, int first, int last) {
    this.first = first;
    this.last = last;
    if (columns == null || opening != null || failure != null || last < first) {
      return;
    }
    long top = first / PAGE;
    long bottom = last / PAGE;
    for (var entry : List.copyOf(reading.entrySet())) {
      if (entry.getKey() < top - AROUND || entry.getKey() > bottom + AROUND) {
        entry.getValue().cancel();
        reading.remove(entry.getKey());
      }
    }
    for (long page = top; page <= bottom; page++) {
      read(s, page, Lane.Priority.VIEW);
    }
    // Without a primary key every read sorts the whole table, so one ahead would hold the lane as
    // long as one in view.
    int around = columns.stream().anyMatch(c -> c.keyPosition() > 0) ? AROUND : 0;
    for (int i = 1; i <= around; i++) {
      read(s, bottom + i, Lane.Priority.AHEAD);
      read(s, top - i, Lane.Priority.AHEAD);
    }
  }

  private void read(State s, long page, Lane.Priority priority) {
    if (page < 0 || page * PAGE >= rows || pages.containsKey(page) || reading.containsKey(page)) {
      return;
    }
    var lane = s.catalogue.lane(id, table);
    if (lane == null) {
      return;
    }
    var of = table;
    var with = columns;
    long from = page * PAGE;
    record Read(Page page, long took) {}
    var asked = lane.submit(priority, link -> {
      long start = System.nanoTime();
      var read = link.read(of, with, from, from + PAGE);
      return new Read(read, System.nanoTime() - start);
    });
    var work = s.app.background(
        "read rows of " + table.name(),
        () -> Lane.await(asked),
        read -> {
          reading.remove(page);
          arrived(s, page, read.page(), read.took());
        },
        error -> {
          reading.remove(page);
          failed(s, error, true);
        });
    reading.put(page, work);
  }

  /** A page came: it corrects the number of rows where it ends early or reaches beyond it. */
  private void arrived(State s, long page, Page read, long nanos) {
    if (!read.fields().equals(fields)) {
      open(s, true); // the columns changed since they were read: the grid's no longer fit
      return;
    }
    took = nanos;
    got = read.rows().size();
    long from = page * PAGE;
    if (got > 0) {
      pages.put(page, read.rows());
    }
    if (got < PAGE) {
      if (got > 0 || page == 0 || pages.containsKey(page - 1)) {
        rows = from + got;
        counted = Counted.EXACT;
      } else if (rows > from) {
        rows = from; // it ends before: the page before says where
      }
      pages.keySet().removeIf(p -> p * PAGE >= rows);
      for (var entry : List.copyOf(reading.entrySet())) {
        if (entry.getKey() * PAGE >= rows) {
          entry.getValue().cancel();
          reading.remove(entry.getKey());
        }
      }
    } else if (from + PAGE > rows || from + PAGE == rows && counted != Counted.EXACT) {
      rows = from + PAGE + 1; // a row more, so that the grid asks whether there are more
      counted = Counted.AT_LEAST;
    }
    keep();
  }

  /** Drops the pages farthest from those in view, beyond {@link #KEPT}. */
  private void keep() {
    if (pages.size() <= KEPT) {
      return;
    }
    long middle = ((long) first + Math.max(first, last)) / 2 / PAGE;
    var far = new ArrayList<>(pages.keySet());
    far.sort(Comparator.comparingLong(p -> -Math.abs(p - middle)));
    for (int i = 0; pages.size() > KEPT; i++) {
      pages.remove(far.get(i));
    }
  }

  /** Count rows: the exact number, on the lane behind the pages; again, it stops counting. */
  void count(State s) {
    if (counting != null) {
      counting.cancel();
      counting = null;
      return;
    }
    var lane = s.catalogue.lane(id, table);
    if (lane == null) {
      return;
    }
    var of = table;
    var asked = lane.submit(Lane.Priority.COUNT, link -> link.count(of));
    counting = s.app.background(
        "count rows of " + table.name(),
        () -> Lane.await(asked),
        n -> {
          counting = null;
          rows = n;
          counted = Counted.EXACT;
          pages.keySet().removeIf(p -> p * PAGE >= n);
          want(s, first, last);
        },
        error -> {
          counting = null;
          failed(s, error, false);
        });
  }

  /**
   * A read failed. A lane closed under it while connected, as by a refresh in the tree, is asked
   * again; a lost connection marks the connection lost in the tree; a table not found by a page
   * may have other columns now, so it is opened anew, which says whether it is gone.
   */
  private void failed(State s, Exception error, boolean page) {
    cancel();
    if (error instanceof InterruptedException) {
      if (s.catalogue.connected(id)) {
        if (columns == null || fields == null) {
          open(s, false);
        } else {
          want(s, first, last);
        }
      }
      return;
    }
    var problem = switch (error) {
      case QueryException e ->
        switch (e.failure()) {
          case NOT_FOUND -> Problem.GONE;
          case ACCESS_DENIED -> Problem.DENIED;
          case CONNECTION_LOST -> Problem.LOST;
          case ABORTED -> Problem.DISCARDED;
          case CANCELLED -> Problem.CANCELLED;
          case OTHER -> Problem.OTHER;
        };
      case CatalogException e ->
        switch (e.failure()) {
          case NOT_FOUND -> Problem.GONE;
          case ACCESS_DENIED -> Problem.DENIED;
          case CONNECTION_LOST -> Problem.LOST;
          case ABORTED -> Problem.DISCARDED;
          case CANCELLED -> Problem.CANCELLED;
          case WRONG_DATABASE, OTHER -> Problem.OTHER;
        };
      case ConnectionException e -> Problem.UNOPENED;
      default -> Problem.OTHER;
    };
    if (problem == Problem.GONE && page) {
      open(s, true);
      return;
    }
    var detail = s.catalogue.words(id, detail(error));
    if (error instanceof ConnectionException e) {
      var profile = s.profile(id);
      var note = profile == null
          ? null
          : Words.note(
              e.failure(), profile.withDatabase(table.container().database().name()), true);
      detail = note == null ? detail : detail.isEmpty() ? note : note + "\n" + detail;
    }
    failure = new Failure(problem, detail);
    if (problem == Problem.LOST || problem == Problem.DISCARDED) {
      s.catalogue.lost(s, id, detail);
    }
  }

  private static String detail(Exception error) {
    return switch (error) {
      case QueryException e -> e.detail();
      case CatalogException e -> e.detail();
      case ConnectionException e -> e.detail();
      default -> State.message(error);
    };
  }

  /** Gives up what is under way; nothing of it arrives. */
  private void cancel() {
    if (opening != null) {
      opening.cancel();
      opening = null;
    }
    if (counting != null) {
      counting.cancel();
      counting = null;
    }
    reading.values().forEach(Work::cancel);
    reading.clear();
  }

  @Override
  public void close() {
    cancel();
    pages.clear();
  }

  /** Whether anything of the tab is read now. */
  boolean busy() {
    return opening != null || !reading.isEmpty();
  }

  // The view

  /** Where the table lives, from its database or schema on: shop.public.orders. */
  String path() {
    return switch (table.container()) {
      case Schema x -> x.database().name() + "." + x.name() + "." + table.name();
      case dev.hoori.db.core.catalog.Database d -> d.name() + "." + table.name();
    };
  }

  /** The tab's content: its toolbar, why it cannot read, the grid, and its status line. */
  El view(Theme t, State s, App<State> app) {
    var connected = s.catalogue.connected(id);
    var profile = s.profile(id);
    String where = profile == null ? null : State.name(profile);
    El top = Card.toolbar(
        t,
        path(),
        where,
        button(
                t,
                SECONDARY,
                Symbol.CALCULATOR,
                counting == null ? "Count rows" : "Stop counting",
                connected && columns != null ? () -> count(s) : null)
            .key("count"),
        button(
                t,
                SECONDARY,
                Symbol.ARROW_CLOCKWISE,
                "Refresh",
                opening != null,
                connected ? () -> open(s, true) : null)
            .key("refresh"));
    El content = col().grow(1).minSize(0, 0).child(top).child(Divider.divider(t));
    El note = note(t, s, connected);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note));
    }
    // Loading cells only while something is read, or was: not for a table that failed at once.
    if (columns != null && (fields != null || failure == null)) {
      content.child(grid(t, s, app).grow(1));
    } else if (opening != null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading " + table.name())
          .child(Progress.spinner(t))
          .child(Label.label(t, Label.Variant.MUTED, "Reading " + path() + "…")));
    } else {
      content.child(col().grow(1));
    }
    return content.child(Card.footer(
        t,
        Label.label(t, Label.Variant.MUTED, rowsText()).key("rows-status").ellipsis(),
        busy()
            ? row()
                .gap(t.space().s())
                .align(Style.Align.CENTER)
                .child(Progress.spinner(t).label("Reading"))
                .child(Label.label(t, Label.Variant.MUTED, "Reading rows…"))
                .key("read-status")
            : Label.label(t, Label.Variant.MUTED, readText()).key("read-status")));
  }

  /** How many rows, and what the number rests on; empty before the first read. */
  String rowsText() {
    if (fields == null) {
      return "";
    }
    var n = String.format(Locale.ROOT, "%,d", rows) + (rows == 1 ? " row" : " rows");
    var text = switch (counted) {
      case ESTIMATED -> "About " + n + ", as the server estimates";
      case AT_LEAST -> "At least " + n;
      case EXACT -> n;
    };
    return counting == null ? text : text + " · counting…";
  }

  /** What the last read took. */
  String readText() {
    if (took < 0) {
      return "";
    }
    long ms = Math.round(took / 1e6);
    return "Read " + String.format(Locale.ROOT, "%,d", got) + (got == 1 ? " row" : " rows") + " in "
        + ms + " ms";
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(Theme t, State s, boolean connected) {
    var name = table.name();
    if (!connected) {
      boolean connecting = s.catalogue.active(id);
      String text = failure != null && failure.problem() == Problem.DISCARDED
          ? "The connection was closed, as the server did not stop a cancelled read."
          : failure != null && failure.problem() == Problem.LOST
              ? "The connection was lost: the server closed it, or the network to it failed."
              : "Its connection is closed.";
      text += " What shows was read before, and may have changed since.";
      return Callout.callout(
          t,
          Callout.Kind.WARNING,
          connecting ? "Connecting…" : "Disconnected",
          text,
          connecting
              ? new El[0]
              : new El[] {button(t, SECONDARY, "Connect", () -> s.catalogue.connect(s, id))});
    }
    if (failure == null) {
      return null;
    }
    var words = switch (failure.problem()) {
      case GONE ->
        new String[] {"Not found", name + " is no longer there, or this user may not see it."};
      case DENIED ->
        new String[] {"Access denied", "The server does not let this user read " + name + "."};
      case LOST ->
        new String[] {
          "Disconnected",
          "The connection was lost: the server closed it, or the network to it" + " failed."
        };
      case DISCARDED ->
        new String[] {
          "Disconnected", "The connection was closed, as the server did not stop a cancelled read."
        };
      case UNOPENED -> new String[] {"The database could not be opened", null};
      case CANCELLED -> new String[] {"Cancelled", "The server cancelled the read."};
      case OTHER -> new String[] {"Could not be read", "The server could not read " + name + "."};
    };
    var text = words[1] == null
        ? failure.detail()
        : failure.detail().isEmpty() ? words[1] : words[1] + "\n" + failure.detail();
    return Callout.callout(
        t,
        Callout.Kind.DANGER,
        words[0],
        text,
        button(t, SECONDARY, "Try again", () -> open(s, true)));
  }

  /** The rows in the grid, late: a row not read yet shows the grid's loading cells. */
  private El grid(Theme t, State s, App<State> app) {
    var shown = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < columns.size(); c++) {
      var column = columns.get(c);
      var kind = fields == null ? null : fields.get(c).kind();
      var type = kind == null ? DataGrid.DataType.OTHER : Cells.type(kind);
      int at = c;
      // A width by the kind once a page told it; the user's from then on.
      if (widths[c] < 0 && kind != null) {
        widths[c] = Cells.width(kind) * t.size().control();
      }
      float width = widths[c] < 0 ? Cells.width(Kind.DECIMAL) * t.size().control() : widths[c];
      var grid = new DataGrid.Column(column.name(), width, type, row -> Cells.text(value(row, at)))
          .typeName(column.type())
          .sortable(false);
      if (column.keyPosition() > 0) {
        grid = grid.icon(Symbol.KEY, Icon.Variant.WARNING);
      }
      if (kind == Kind.TEXT || kind == Kind.OTHER) {
        grid = grid.cellIcon(row -> Cells.icon(value(row, at)));
      }
      shown.add(grid);
    }
    var options = DataGrid.options()
        .select(selection)
        .onResize((c, w) -> widths[c] = w)
        .rowNumbers()
        .copy(app.clipboard())
        .copyLimit(
            100_000, copy -> s.tell("Only rows that were read can be copied; select fewer rows."))
        .contextMenu(
            menu, List.of(Menu.item("Refresh", () -> open(s, true)).icon(Symbol.ARROW_CLOCKWISE)))
        .loading(this::loaded, (first, last) -> want(s, first, last));
    return DataGrid.dataGrid(t, shown, (int) Math.min(rows, Integer.MAX_VALUE), options)
        .key("grid")
        .label("Rows of " + table.name());
  }

  private boolean loaded(int row) {
    var page = pages.get((long) row / PAGE);
    return page != null && row % PAGE < page.size();
  }

  private Object value(int row, int column) {
    return pages.get((long) row / PAGE).get(row % PAGE).get(column);
  }
}

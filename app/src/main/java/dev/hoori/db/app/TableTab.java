package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * A table's rows in a tab. They are read in pages, on the lane of the table's connection, as the
 * grid builds the rows in view: the pages in view first, one more on each side behind them, and
 * no more than {@link #KEPT} are held. A read the grid has scrolled away from is cancelled. Each
 * page is read on its own, so rows may repeat or go missing while others change the table;
 * Refresh reads it anew. Only the app's thread uses it.
 *
 * <p>A collection of documents shows as a grid of their fields, as a table does, or a page of
 * them at a time, whole: as Extended JSON, or as a tree of their fields.
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
    CHANGED,
    LOST,
    DISCARDED,
    UNOPENED,
    CANCELLED,
    OTHER
  }

  /** @param detail the server's or the driver's words, withheld where they hold the password */
  record Failure(Problem problem, String detail) {}

  /** How a collection's documents show. */
  enum Shown {
    /** A row each, a column a field, read as the grid scrolls. */
    GRID("Grid"),
    /** A page of them as an array of Relaxed Extended JSON. */
    JSON("JSON"),
    /** A page of them, each a tree of its fields. */
    TREE("Tree");

    final String title;

    Shown(String title) {
      this.title = title;
    }
  }

  /** The most rows a grid shows: it counts them in an int. */
  static final int GRID_ROWS = Integer.MAX_VALUE;

  final UUID id;
  Table table; // its kind may change, as a table replaced by a view of its name
  List<Column> columns; // as the catalog read them; null until then
  float[] widths; // of the columns, which the user may change
  private List<Field> fields; // as the last read gave them, which each page has to match
  long rows; // known to be there, or as estimated
  private boolean more; // a row more in the grid, whose read says whether there are more
  Counted counted = Counted.ESTIMATED;
  final Map<Long, List<List<Object>>> pages = new HashMap<>(); // the rows read, by page
  // A collection's documents of the pages read, whole; none for a table.
  final Map<Long, List<Document>> documents = new HashMap<>();
  Shown shown = Shown.GRID;
  long page; // the page of documents that JSON and Tree show
  final DocumentTree documentView = new DocumentTree();
  final Map<Long, Lane.Asked> reading = new HashMap<>(); // the pages asked for
  Lane.Asked opening; // the columns, the estimate and the first page, or null
  Lane.Asked counting; // the exact count, or null
  // The count asked for while the table opened came first: it is newer than the opening's estimate.
  private boolean countedFirst;
  // An empty page past what is known: the count says where the end is, and no page is read until
  // then, so that finding it takes one read and not one per page.
  private boolean ending;
  private boolean closed;
  private int first; // the rows the grid built last
  private int last = -1;
  Failure failure; // why the last read failed, or null
  long took = -1; // nanoseconds the last read with rows took on the server, -1 before the first
  int got; // rows of that read
  String said; // what the tab says for a while in its status line, in its own window; or null
  private int says;
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final Menu.State menu = new Menu.State();
  final ValuePanel value = new ValuePanel();
  private Whole whole; // the current cell's whole value, for the value panel; or null

  /**
   * The whole value of a cell that a page holds in part, asked for by its row as read: the text,
   * null for NULL, or why it could not be read, once it came.
   */
  private record Whole(
      List<Object> row, int column, Lane.Asked asked, String text, String failure) {}

  TableTab(UUID id, Table table) {
    this.id = id;
    this.table = table;
  }

  /**
   * Reads the table: its columns from the catalog, anew unless the tree has them and this is
   * not a refresh, the server's estimate of its rows, and the page at the first row in view, all
   * in one call. The rows read before go; the place in view stays.
   */
  void open(State s, boolean anew) {
    var lane = closed ? null : s.catalog.lane(s, id, table);
    if (lane == null) {
      return; // what was read stays, and the view says that it is not connected
    }
    stop();
    failure = null;
    ending = false;
    countedFirst = false;
    pages.clear();
    documents.clear();
    if (columns == null) {
      columns = s.catalog.columns(id, table);
      if (columns != null) {
        shape(columns);
        rows = PAGE; // loading rows until the estimate comes
      }
    }
    var live = s.catalog.live(id);
    var known = anew ? null : columns;
    boolean recount = anew && counted == Counted.EXACT; // a refresh counts again what was counted
    var of = table;
    long from = (long) first / PAGE * PAGE;
    record Opened(List<Column> columns, OptionalLong estimate, Page page, long took) {}
    opening = lane.ask(
        s.app,
        "open " + table.name(),
        Lane.Priority.VIEW,
        link -> {
          var read = known != null ? known : link.columns(of);
          var estimate = link.estimate(of);
          long start = System.nanoTime();
          var page = link.read(of, read, from, from + PAGE);
          return new Opened(read, estimate, page, System.nanoTime() - start);
        },
        opened -> {
          opening = null;
          if (!opened.columns().equals(columns)) {
            shape(opened.columns());
            s.catalog.columnsRead(id, table, opened.columns());
          }
          columns = opened.columns();
          fields = opened.page().fields();
          var estimate = opened.estimate();
          if (!countedFirst) {
            // Without an estimate the place in view stands until its page says more.
            rows = estimate.orElse(from);
            more = false;
            counted = estimate.isPresent() ? Counted.ESTIMATED : Counted.AT_LEAST;
          }
          arrived(s, from / PAGE, opened.page(), opened.took());
          want(s, first, last);
          if (counted != Counted.EXACT
              && (recount || estimate.isPresent() && estimate.getAsLong() < COUNT_BELOW)) {
            counted(s);
          }
        },
        error -> {
          opening = null;
          // Columns the tree read may be old; those read just now are not worth a second try.
          failed(s, error, !anew, live);
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

  /** The rows the grid shows: those known, and the one more that asks whether there are more. */
  long shown() {
    return rows + (more ? 1 : 0);
  }

  /**
   * The grid built these rows: the pages they are on are read first, then one on each side, and
   * what is read for rows far from them is given up, but for the page of the value panel's cell.
   */
  void want(State s, int first, int last) {
    this.first = first;
    this.last = last;
    if (closed || columns == null || opening != null || failure != null || ending || last < first) {
      return;
    }
    long top = first / PAGE;
    long bottom = last / PAGE;
    long shown = panelPage();
    giveUp(page -> (page < top - AROUND || page > bottom + AROUND) && page != shown);
    for (long page = top; page <= bottom; page++) {
      var asked = reading.get(page);
      if (asked != null && asked.priority() != Lane.Priority.VIEW) {
        // Asked for ahead, and in view now: it goes before what is asked for ahead still.
        asked.promote(Lane.Priority.VIEW);
      }
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
    if (page < 0 || page * PAGE >= shown() || reading.containsKey(page)) {
      return;
    }
    // A page read when the table seemed to end in it is read again for the rows now in range.
    var have = pages.get(page);
    if (have != null && have.size() >= Math.min(PAGE, rows - page * PAGE)) {
      return;
    }
    var lane = s.catalog.lane(s, id, table);
    if (lane == null) {
      return;
    }
    var live = s.catalog.live(id);
    var of = table;
    var with = columns;
    long from = page * PAGE;
    record Read(Page page, long took) {}
    var asked = lane.ask(
        s.app,
        "read rows of " + table.name(),
        priority,
        link -> {
          long start = System.nanoTime();
          var read = link.read(of, with, from, from + PAGE);
          return new Read(read, System.nanoTime() - start);
        },
        read -> {
          reading.remove(page);
          arrived(s, page, read.page(), read.took());
        },
        error -> {
          reading.remove(page);
          failed(s, error, true, live);
        });
    reading.put(page, asked);
  }

  /** A page came: it corrects the number of rows where it ends early or reaches beyond it. */
  private void arrived(State s, long page, Page read, long nanos) {
    // On PostgreSQL the kinds are those of the read: one changed since the columns were read.
    if (!read.fields().equals(fields)) {
      open(s, true);
      return;
    }
    int n = read.rows().size();
    if (n > 0 || took < 0) {
      took = nanos; // not the empty read that tells where the end is
      got = n;
    }
    long from = page * PAGE;
    if (n > 0) {
      pages.put(page, read.rows());
      documents.put(page, read.documents());
    } else {
      pages.remove(page);
      documents.remove(page);
    }
    if (n < PAGE) {
      var before = pages.get(page - 1);
      if (n > 0 || page == 0 || before != null && before.size() == PAGE) {
        rows = from + n;
        counted = Counted.EXACT;
        more = false;
      } else {
        // Empty, and what is before it not read: the end is somewhere before.
        rows = Math.min(rows, from);
        more = false;
        ending = true;
        counted(s);
      }
      pages.keySet().removeIf(p -> p * PAGE >= rows);
      documents.keySet().retainAll(pages.keySet());
      giveUp(p -> ending || p * PAGE >= rows);
    } else if (from + PAGE > rows || from + PAGE == rows && counted != Counted.EXACT) {
      rows = from + PAGE;
      counted = Counted.AT_LEAST;
      more = true;
    }
    keep();
  }

  /**
   * Drops the pages farthest from those in view, beyond {@link #KEPT}; not that of the cell the
   * value panel shows.
   */
  private void keep() {
    if (pages.size() <= KEPT) {
      return;
    }
    long middle = ((long) first + Math.max(first, last)) / 2 / PAGE;
    var far = new ArrayList<>(pages.keySet());
    far.remove(Long.valueOf(panelPage()));
    far.sort(Comparator.comparingLong(p -> -Math.abs(p - middle)));
    for (int i = 0; pages.size() > KEPT; i++) {
      // Not the page of documents shown whole.
      if (shown == Shown.GRID || far.get(i) != page) {
        pages.remove(far.get(i));
        documents.remove(far.get(i));
      }
    }
  }

  /** The page of the cell the open value panel shows, which stays; -1 for none. */
  private long panelPage() {
    return value.open && selection.row() >= 0 ? selection.row() / PAGE : -1;
  }

  /**
   * Count rows: the exact number, on the lane behind the pages; again, it stops counting. Not
   * while the count finds where the table ends.
   */
  void count(State s) {
    if (ending) {
      return;
    }
    if (counting != null) {
      counting.cancel();
      counting = null;
      return;
    }
    counted(s);
  }

  /** Makes sure the rows are counted: a count under way, whoever asked for it, goes on. */
  private void counted(State s) {
    if (closed || counting != null) {
      return;
    }
    var lane = s.catalog.lane(s, id, table);
    if (lane == null) {
      return;
    }
    var live = s.catalog.live(id);
    var of = table;
    counting = lane.ask(
        s.app,
        "count rows of " + table.name(),
        Lane.Priority.COUNT,
        link -> link.count(of),
        n -> {
          counting = null;
          countedFirst = opening != null;
          ending = false;
          rows = n;
          counted = Counted.EXACT;
          more = false;
          pages.keySet().removeIf(p -> p * PAGE >= n);
          documents.keySet().retainAll(pages.keySet());
          want(s, first, last);
          if (shown != Shown.GRID) {
            turn(s, Math.min(page, Math.max(0, (n - 1) / PAGE)));
          }
        },
        error -> {
          counting = null;
          failed(s, error, false, live);
        });
  }

  /**
   * A read failed. A lane closed under it while connected, as by a refresh in the tree, is asked
   * again; a lost connection marks the connection lost in the tree; a table not found, or whose
   * columns changed, is opened anew where that may help, which says whether it is gone. The
   * failure's words are checked against the password of the connection the read was asked of.
   *
   * @param again whether opening anew may help: not after the columns were just read
   */
  private void failed(State s, Exception error, boolean again, CatalogTree.Live live) {
    stop();
    if (closed) {
      return;
    }
    if (error instanceof InterruptedException) {
      if (s.catalog.connected(id)) {
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
          case COLUMNS_CHANGED -> Problem.CHANGED;
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
    if (again && (problem == Problem.GONE || problem == Problem.CHANGED)) {
      open(s, true);
      return;
    }
    var detail = CatalogTree.words(live, detail(error));
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
      s.catalog.lost(s, live, detail, problem == Problem.DISCARDED);
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

  /**
   * Gives up what is under way; nothing of it arrives. What was read stays: also when the
   * connection it was read on ends, whose work ends with it.
   */
  void stop() {
    var gone = asked(page -> true);
    if (whole != null && whole.asked() != null) {
      gone.add(whole.asked());
    }
    whole = null; // read anew with the pages
    if (opening != null) {
      gone.add(opening);
      opening = null;
    }
    if (counting != null) {
      gone.add(counting);
      counting = null;
    }
    Lane.cancel(gone);
  }

  /** Gives up the pages asked for that the test picks. */
  private void giveUp(LongPredicate which) {
    Lane.cancel(asked(which));
  }

  /** Takes the pages asked for that the test picks out of those asked for. */
  private List<Lane.Asked> asked(LongPredicate which) {
    var gone = new ArrayList<Lane.Asked>();
    reading.entrySet().removeIf(e -> which.test(e.getKey()) && gone.add(e.getValue()));
    return gone;
  }

  @Override
  public void close() {
    closed = true;
    stop();
    pages.clear();
    documents.clear();
  }

  /** Shows the documents as asked: whole, the page the grid was at. */
  void show(State s, Shown next) {
    if (next == shown) {
      return;
    }
    if (shown == Shown.GRID) {
      page = (long) first / PAGE;
    }
    shown = next;
    if (next == Shown.GRID) {
      want(s, (int) Math.min(page * PAGE, GRID_ROWS), (int)
          Math.min(page * PAGE + PAGE - 1, GRID_ROWS));
    } else {
      turn(s, page);
    }
  }

  /** Shows the page of documents whole, read where it is not. */
  void turn(State s, long to) {
    page = Math.max(0, to);
    if (closed || columns == null || opening != null || failure != null) {
      return;
    }
    var asked = reading.get(page);
    if (asked != null) {
      asked.promote(Lane.Priority.VIEW);
    }
    read(s, page, Lane.Priority.VIEW);
  }

  /** Whether anything of the tab is read now. */
  boolean busy() {
    return opening != null || !reading.isEmpty();
  }

  /** Says something in the tab's status line, in whichever window it is, for a while. */
  void say(State s, String text) {
    said = text;
    int told = ++says;
    s.app.after(State.MESSAGE_SECONDS, () -> {
      if (says == told) {
        said = null;
      }
    });
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
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var connected = s.catalog.connected(id);
    var profile = s.profile(id);
    String where = profile == null ? null : State.name(profile);
    boolean ofDocuments = profile != null && profile.provider().keepsDocuments();
    var actions = new ArrayList<El>();
    if (ofDocuments) {
      actions.add(Segmented.segmented(List.of(Shown.values()), shown, v -> v.title, v -> show(s, v))
          .key("shown")
          .label("Show documents as"));
    }
    actions.add(button(
            SECONDARY,
            Symbol.CALCULATOR,
            counting == null ? ofDocuments ? "Count documents" : "Count rows" : "Stop counting",
            connected && columns != null && !ending ? () -> count(s) : null)
        .key("count"));
    actions.add(button(
            SECONDARY,
            "Refresh",
            connected ? () -> open(s, true) : null,
            Button.options()
                .icon(Symbol.ARROW_CLOCKWISE)
                .state(opening != null && connected ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    El top = Card.toolbar(path(), where, actions.toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content =
        col().grow(1).minSize(0, 0).child(top).child(line == null ? Divider.divider() : line);
    El note = note(s, connected);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note));
    }
    // Loading cells only while something is read, or was: not for a table that failed at once.
    boolean readable = columns != null && (fields != null || failure == null);
    boolean grid = readable && !(ofDocuments && shown != Shown.GRID);
    if (readable) {
      content.child(grid ? withValue(s, app, grid(s, app)) : documents(s, app).grow(1));
    } else if (opening != null) {
      content.child(col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading " + table.name())
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading " + path() + "…")));
    } else {
      content.child(col().grow(1));
    }
    El read = busy()
        ? row()
            .gap(t.space().s())
            .align(Style.Align.CENTER)
            .child(Progress.spinner().label("Reading"))
            .child(Label.label(Label.Variant.MUTED, "Reading rows…"))
            .key("read-status")
        : Label.label(Label.Variant.MUTED, readText(ofDocuments)).key("read-status");
    El rowsStatus = Label.label(Label.Variant.MUTED, rowsText(ofDocuments))
        .key("rows-status")
        .ellipsis();
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    end.add(read);
    if (grid) {
      // After the grid: the rows in view are those its build in this view scrolled to.
      end.add(Label.label(Label.Variant.MUTED, inViewText(ofDocuments)).key("rows-in-view"));
      end.add(paging());
      end.add(value.button());
    }
    return content.child(Card.footer(rowsStatus, end.toArray(El[]::new)));
  }

  /**
   * The grid with the value panel, which shows the current cell's value: one that a page holds in
   * part is read whole by its row's key as the panel shows it, and given up once it shows another
   * cell or closes.
   */
  private El withValue(State s, App<State> app, El grid) {
    int row = selection.row();
    int column = selection.column();
    ValuePanel.Cell cell = null;
    String note = null;
    boolean waiting = false;
    boolean wanted = false; // the whole value, by the row's key
    if (value.open
        && fields != null
        && row >= 0
        && row < gridRows()
        && column >= 0
        && column < columns.size()) {
      var c = columns.get(column);
      Object v = null;
      if (loaded(row)) {
        var values = pages.get((long) row / PAGE).get(row % PAGE);
        v = values.get(column);
        if (v instanceof Cut && !Changes.names(columns, values)) {
          note = Cells.icon(v).tooltip() + "; without a primary key the rest cannot be read";
        } else if (v instanceof Cut) {
          wanted = true;
          // The row as read: a page read again may hold another value of the same start.
          if (whole == null || whole.row() != values || whole.column() != column) {
            ask(s, values, column);
          }
          if (whole == null) {
            note = Cells.icon(v).tooltip();
          } else if (whole.asked() != null) {
            waiting = true;
          } else if (whole.failure() != null) {
            note = whole.failure();
          } else {
            v = whole.text();
          }
        }
      } else {
        // Out of the pages held, as after a refresh far from it: read as a page in view.
        long page = row / PAGE;
        if (!closed && failure == null && opening == null && !ending) {
          read(s, page, Lane.Priority.VIEW);
        }
        waiting = opening != null || reading.containsKey(page);
      }
      cell = loaded(row) || waiting
          ? new ValuePanel.Cell(c.name(), c.type(), fields.get(column).kind(), v)
          : null;
    }
    // What was asked for a cell the panel no longer shows is given up.
    if (!wanted && whole != null) {
      if (whole.asked() != null) {
        whole.asked().cancel();
      }
      whole = null;
    }
    return value.around(app, s.zone, grid, cell, note, waiting);
  }

  /**
   * Asks for the whole value of the row's column, in place of what was asked before; not while
   * the tab cannot read, which leaves nothing asked.
   */
  private void ask(State s, List<Object> row, int column) {
    if (whole != null && whole.asked() != null) {
      whole.asked().cancel();
    }
    whole = null;
    var lane = closed || failure != null || opening != null ? null : s.catalog.lane(s, id, table);
    if (lane == null) {
      return;
    }
    var live = s.catalog.live(id);
    var of = table;
    var with = columns;
    var asked = lane.ask(
        s.app,
        "read a value of " + table.name(),
        Lane.Priority.VIEW,
        link -> Optional.ofNullable(link.whole(of, with, row, column)),
        text -> whole = new Whole(row, column, null, text.orElse(null), null),
        error -> {
          whole = null;
          if (error instanceof InterruptedException) {
            return; // the lane closed under it: asked again as the panel shows it
          }
          if (error instanceof ConnectionException
              || error instanceof QueryException e
                  && (e.failure() == QueryException.Failure.CONNECTION_LOST
                      || e.failure() == QueryException.Failure.ABORTED)) {
            failed(s, error, false, live);
            return;
          }
          var detail = CatalogTree.words(live, detail(error));
          whole = new Whole(
              row,
              column,
              null,
              null,
              "Could not be read whole" + (detail.isEmpty() ? "" : ": " + detail));
        });
    whole = new Whole(row, column, asked, null, null);
  }

  /** The rows wholly in view, as "Rows 201–223"; empty before the grid was laid out. */
  private String inViewText(boolean documents) {
    int top = selection.firstRowInView();
    if (top < 0 || top >= rows) {
      return "";
    }
    // Not the row that asks whether there are more, which is not known to be there.
    long bottom = Math.min(selection.lastRowInView() + 1L, rows);
    return String.format(
        Locale.ROOT, documents ? "Documents %,d–%,d" : "Rows %,d–%,d", top + 1, bottom);
  }

  /**
   * Buttons that scroll the grid a page, the rows in view, back or on, or to its start or end; the
   * selection stays. Each is off where the grid shows its first or last row already.
   */
  private El paging() {
    Theme t = Theme.CURRENT.get();
    int top = selection.firstRowInView();
    int bottom = selection.lastRowInView();
    boolean back = top > 0;
    boolean on = bottom >= 0 && bottom < gridRows() - 1;
    return row()
        .gap(t.space().xs())
        .align(Style.Align.CENTER)
        .child(page(Symbol.ARROW_UP, "First page", back, () -> selection.scrollToRow(0)))
        .child(page(Symbol.CHEVRON_UP, "Previous page", back, () -> {
          int first = selection.firstRowInView();
          int last = selection.lastRowInView();
          selection.scrollToRow(Math.max(0, first - (last - first + 1)));
        }))
        .child(page(
            Symbol.CHEVRON_DOWN,
            "Next page",
            on,
            () -> selection.scrollToRow(selection.lastRowInView() + 1)))
        // As Control+End: an estimate too high shows the end once the reads there correct it.
        .child(page(Symbol.ARROW_DOWN, "Last page", on, () -> selection.scrollToRow(gridRows())))
        .key("paging");
  }

  private static El page(Symbol icon, String label, boolean enabled, Runnable onPress) {
    return Button.icon(
        Button.Variant.GHOST,
        icon,
        label,
        enabled ? onPress : null,
        Button.options().size(Button.Size.SMALL));
  }

  /**
   * A page of documents whole, with what turns the pages: as Extended JSON, in an editor
   * that only shows them, or as a tree.
   */
  private El documents(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var read = documents.get(page);
    long from = page * PAGE;
    long to = read == null ? Math.min(from + PAGE, shown()) : from + read.size();
    var range = to <= from
        ? "No documents"
        : "Documents " + String.format(Locale.ROOT, "%,d", from + 1) + "–"
            + String.format(Locale.ROOT, "%,d", to);
    boolean last = (page + 1) * PAGE >= shown();
    El pager = row()
        .gap(t.space().s())
        .padding(t.space().s())
        .align(Style.Align.CENTER)
        .shrink(0)
        .child(Button.icon(
                Button.Variant.GHOST,
                Symbol.CHEVRON_LEFT,
                "Previous documents",
                page > 0 ? () -> turn(s, page - 1) : null)
            .key("previous"))
        .child(Label.label(Label.Variant.MUTED, range).key("range"))
        .child(Button.icon(
                Button.Variant.GHOST,
                Symbol.CHEVRON_RIGHT,
                "Next documents",
                !last ? () -> turn(s, page + 1) : null)
            .key("next"));
    El shownNow;
    if (read == null) {
      shownNow = col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading documents")
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading documents…"));
    } else {
      documentView.show("p" + page, read);
      shownNow = switch (shown) {
        case JSON ->
          CodeEditor.embedded(CodeEditor.codeEditor(
                      app, documentView.text(), documentView.editor, Json.of(), null)
                  .key("json")
                  .label("Documents of " + table.name()))
              .grow(1);
        case TREE, GRID ->
          documentView
              .tree(s.zone, node -> {})
              .key("documents")
              .label("Documents of " + table.name())
              .grow(1);
      };
    }
    return col().minSize(0, 0).child(pager).child(Divider.divider()).child(shownNow);
  }

  /** How many rows, and what the number rests on; empty before the first read. */
  String rowsText() {
    return rowsText(false);
  }

  /** How many rows or documents, and what the number rests on; empty before the first read. */
  String rowsText(boolean documents) {
    if (fields == null) {
      return "";
    }
    var n = String.format(Locale.ROOT, "%,d", rows) + noun(rows, documents);
    var text = switch (counted) {
      case ESTIMATED -> "About " + n + ", as the server estimates";
      case AT_LEAST -> "At least " + n;
      case EXACT -> n;
    };
    if (rows > GRID_ROWS) {
      text += " · the first " + String.format(Locale.ROOT, "%,d", GRID_ROWS) + " are shown";
    }
    return counting == null ? text : text + " · counting…";
  }

  /** What the last read took. */
  String readText(boolean documents) {
    if (took < 0) {
      return "";
    }
    long ms = Math.round(took / 1e6);
    return "Read " + String.format(Locale.ROOT, "%,d", got) + noun(got, documents) + " in " + ms
        + " ms";
  }

  private static String noun(long n, boolean documents) {
    return documents ? n == 1 ? " document" : " documents" : n == 1 ? " row" : " rows";
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(State s, boolean connected) {
    var name = table.name();
    if (!connected) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      String text = lost != null ? CatalogTree.why(lost) : "Its connection is closed.";
      text += " What shows was read before, and may have changed since.";
      return Callout.callout(
          Callout.Kind.WARNING,
          connecting ? "Connecting…" : "Disconnected",
          text,
          connecting
              ? new El[0]
              : new El[] {button(SECONDARY, "Connect", () -> s.catalog.connect(s, id))});
    }
    if (failure == null) {
      return null;
    }
    var words = switch (failure.problem()) {
      case GONE ->
        new String[] {"Not found", name + " is no longer there, or this user may not see it."};
      case DENIED ->
        new String[] {"Access denied", "The server does not let this user read " + name + "."};
      case CHANGED ->
        new String[] {"Columns changed", "The columns of " + name + " changed while it was read."};
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
        Callout.Kind.DANGER, words[0], text, button(SECONDARY, "Try again", () -> open(s, true)));
  }

  /** The rows in the grid, late: a row not read yet shows the grid's loading cells. */
  private El grid(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var zone = s.zone;
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
      var grid = new DataGrid.Column(
              column.name(), width, type, row -> Cells.text(value(row, at), zone))
          .typeName(column.type())
          .sortable(false);
      if (column.keyPosition() > 0) {
        grid = grid.icon(Symbol.KEY, Icon.Variant.WARNING);
      }
      if (kind == Kind.TEXT || kind == Kind.JSON || kind == Kind.OTHER) {
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
            100_000, copy -> say(s, "Only rows that were read can be copied; select fewer rows."))
        .contextMenu(
            menu, List.of(Menu.item("Refresh", () -> open(s, true)).icon(Symbol.ARROW_CLOCKWISE)))
        .loading(this::loaded, (first, last) -> want(s, first, last))
        // Nothing to do: the build it causes brings the footer's rows in view along.
        .onRowsInView((first, last) -> {});
    return DataGrid.dataGrid(shown, gridRows(), options)
        .key("grid")
        .label("Rows of " + table.name())
        .defaultFocus();
  }

  /** The rows in the grid, which counts them in an int. */
  private int gridRows() {
    return (int) Math.min(shown(), GRID_ROWS);
  }

  private boolean loaded(int row) {
    var page = pages.get((long) row / PAGE);
    return page != null && row % PAGE < page.size();
  }

  private Object value(int row, int column) {
    return pages.get((long) row / PAGE).get(row % PAGE).get(column);
  }
}

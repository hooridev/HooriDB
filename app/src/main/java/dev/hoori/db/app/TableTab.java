package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Completion;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Schema;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.ColumnFilter;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Editable;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentFilters;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.document.Values;
import dev.hoori.db.core.query.QueryException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

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
final class TableTab implements Tab, Guard.Guarded, ResultEdits.Host, ResultEdits.Target {

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

  private static final String DISCONNECTED = "Disconnected";
  private static final String REFRESH = "refresh";

  final UUID id;
  Table table; // its kind may change, as a table replaced by a view of its name
  List<Column> columns; // as the catalog read them; null until then
  float[] widths; // of the columns, which the user may change
  // Fits the columns once they show rows, and again when their names change.
  final DataGrid.Fit fit = new DataGrid.Fit();
  private List<Field> fields; // as the last read gave them, which each page has to match
  // The kinds of the columns by name as a read last gave them, kept while a new filter reads.
  private Map<String, Kind> kinds = Map.of();
  long rows; // known to be there, or as estimated
  private boolean more; // a row more in the grid, whose read says whether there are more
  Counted counted = Counted.ESTIMATED;
  final Map<Long, List<List<Object>>> pages = new HashMap<>(); // the rows read, by page
  // A collection's documents of the pages read, whole; none for a table.
  final Map<Long, List<Document>> documents = new HashMap<>();
  final DocumentPresentation presentation;
  final Map<Long, Lane.Asked> reading = new HashMap<>(); // the pages asked for
  Lane.Asked opening; // the columns, the estimate and the first page, or null
  Lane.Asked counting; // the exact count, or null
  private Lane.Asked readingKeys; // the table's foreign keys, read on demand for the context menu
  private boolean keysFailed; // a failed read of the keys is tried again at the menu's next opening
  // The count asked for while the table opened came first: it is newer than the opening's estimate.
  private boolean countedFirst;
  // An empty page past what is known: the count says where the end is, and no page is read until
  // then, so that finding it takes one read and not one per page.
  private boolean ending;
  private boolean toLast; // Last page asked while only a lower bound was known: turns at the count
  private boolean closed;
  private int first; // the rows the grid built last
  private int last = -1;
  Failure failure; // why the last read failed, or null
  private String countFailure; // why the last count failed, or null: the pages go on
  long took = -1; // nanoseconds the last read with rows took on the server, -1 before the first
  int got; // rows of that read
  String said; // what the tab says for a while in its status line, in its own window; or null
  private int says;
  final DataGrid.Selection selection = new DataGrid.Selection(DataGrid.Mode.CELLS);
  final Menu.State menu = new Menu.State();
  final ValuePanel value = new ValuePanel();
  Rows.Filter filter = Rows.Filter.NONE; // the clauses applied to reads and counts
  private int query; // counts the applied queries, so that JSON and Tree start anew with each
  private List<Sort> sorted = List.of(); // the header sort that made the applied order
  private List<HeaderFilter> headerFilters = List.of(); // header filters in the applied WHERE
  private String manualWhere = ""; // hand-written WHERE/Filter text joined with headerFilters
  private String refused; // why the clauses last submitted were refused, or null
  private String unapplied; // why a WHERE from foreign key navigation was not applied, or null
  private final Clause where;
  private final Clause order;
  private Whole whole; // the current cell's whole value, for the value panel; or null
  private boolean reread; // a commit changed a key, inserted, or deleted: read the rows anew
  final ResultEdits edits = new ResultEdits();
  ResultEdits.Preview preview; // the DML Preview dialog before committing edits, or null
  // Original rows (as read) of edited positions, kept also where a shrunk end trims their page.
  private final TreeMap<Integer, List<Object>> originals = new TreeMap<>();
  // Original rows moved out of originals on undo or Revert while GridChanges can still undo/redo
  // them.
  private final Map<Integer, List<Object>> undone = new HashMap<>();

  /** One clause field (WHERE/ORDER BY or Filter/Sort) with its caret and completions. */
  private static final class Clause {
    final String key;
    final String sqlKeyword;
    final String documentLabel;
    String text = "";
    int caret;
    final Completion.State completion = new Completion.State().openAfter(".");
    final Suggestions suggestions;

    Clause(TableTab tab, String key, String sqlKeyword, String documentLabel) {
      this.key = key;
      this.sqlKeyword = sqlKeyword;
      this.documentLabel = documentLabel;
      this.suggestions = new Suggestions(tab);
    }

    String label(boolean ofDocuments) {
      return ofDocuments ? documentLabel : sqlKeyword;
    }

    void close() {
      completion.close();
      suggestions.close();
    }
  }

  /** One column of a header sort, by name, so that its arrow follows the applied order. */
  private record Sort(String column, boolean descending) {}

  /** One column of a header filter, by name, so its funnel and chip follow the applied filter. */
  private record HeaderFilter(String column, DataGrid.Filter.Spec spec) {}

  /**
   * The whole value of a cell that a page holds in part, asked for by its row as read: the text,
   * null for NULL, or why it could not be read, once it came.
   */
  private record Whole(
      List<Object> row, int column, Lane.Asked asked, String text, String failure) {}

  TableTab(UUID id, Table table, DocumentPresentation.Mode initial) {
    this.id = id;
    this.table = table;
    this.presentation = new DocumentPresentation(initial);
    this.where = new Clause(this, "where", "WHERE", "Filter");
    this.order = new Clause(this, "order", "ORDER BY", "Sort");
  }

  /** A tab for the table or collection, starting in the configured view for documents. */
  static TableTab of(State s, UUID id, Table table) {
    var profile = s.profile(id);
    var initial = profile != null && Language.of(profile.provider()).documents()
        ? DocumentPresentation.of(s.settings.documentView())
        : DocumentPresentation.Mode.GRID;
    return new TableTab(id, table, initial);
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
    clearEdits();
    failure = null;
    countFailure = null;
    ending = false;
    countedFirst = false;
    pages.clear();
    documents.clear();
    if (columns == null) {
      treeColumns(s);
    }
    var live = s.catalog.live(id);
    var known = anew ? null : columns;
    boolean recount = anew && counted == Counted.EXACT; // a refresh counts again what was counted
    var of = table;
    var applied = filter;
    // The page in view: of the grid, or of the documents JSON and Tree show.
    long from = presentation.isGrid() ? (long) first / PAGE * PAGE : presentation.page() * PAGE;
    opening = lane.ask(
        s.app,
        "open " + table.name(),
        Lane.Priority.VIEW,
        link -> {
          var read = known != null ? known : link.columns(of);
          var estimate = link.estimate(of, applied);
          long start = System.nanoTime();
          var page = link.read(of, read, from, from + PAGE, applied);
          return new Opened(read, estimate, page, System.nanoTime() - start);
        },
        opened -> opened(s, from, recount, live, opened),
        error -> {
          opening = null;
          // Columns the tree read may be old; those read just now are not worth a second try.
          failed(s, error, !anew, live);
        });
  }

  /** The columns as the tree read them, if it has: they show while the rows are on their way. */
  private void treeColumns(State s) {
    columns = s.catalog.columns(id, table);
    if (columns != null) {
      shape(columns);
      rows = PAGE; // loading rows until the estimate comes
    }
  }

  /** What opening the table read in one call: its columns, the estimate, the page in view. */
  private record Opened(List<Column> columns, OptionalLong estimate, Page page, long took) {}

  /**
   * The table opened at the page from the row: its columns, the estimate unless a count came first,
   * and that page; then the pages in view, and a count where it is cheap or a refresh asks for one.
   */
  private void opened(State s, long from, boolean recount, Live live, Opened opened) {
    opening = null;
    if (!opened.columns().equals(columns)) {
      shape(opened.columns());
      s.catalog.columnsRead(id, table, opened.columns());
    }
    columns = opened.columns();
    fields = opened.page().fields();
    kinds = new HashMap<>();
    for (int c = 0; c < fields.size(); c++) {
      kinds.put(columns.get(c).name(), fields.get(c).kind());
    }
    configureEdits(s, live);
    var estimate = opened.estimate();
    if (!countedFirst) {
      // Without an estimate the place in view stands until its page says more.
      rows = estimate.orElse(from);
      more = false;
      counted = estimate.isPresent() ? Counted.ESTIMATED : Counted.AT_LEAST;
    }
    arrived(s, from / PAGE, opened.page(), opened.took());
    want(s, first, last);
    if (!presentation.isGrid()) {
      turn(s, presentation.page()); // as it was turned to while it opened
    }
    // A WHERE's estimate says little of what its count scans: its rows are counted by the pages.
    if (counted != Counted.EXACT
        && (recount
            || !filter.hasWhere() && estimate.isPresent() && estimate.getAsLong() < COUNT_BELOW)) {
      counted(s, recount);
    }
  }

  /** The widths of new columns: those of the same name keep theirs, until the fit is redone. */
  private void shape(List<Column> next) {
    if (columns != null && !names(columns).equals(names(next))) {
      fit.reset();
    }
    var old = new HashMap<String, Float>();
    for (int i = 0; columns != null && widths != null && i < columns.size(); i++) {
      old.put(columns.get(i).name(), widths[i]);
    }
    widths = new float[next.size()];
    for (int i = 0; i < widths.length; i++) {
      widths[i] = old.getOrDefault(next.get(i).name(), -1f);
    }
  }

  private static List<String> names(List<Column> columns) {
    return columns.stream().map(Column::name).toList();
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
    var applied = filter;
    long from = page * PAGE;
    record Read(Page page, long took) {}
    var asked = lane.ask(
        s.app,
        "read rows of " + table.name(),
        priority,
        link -> {
          long start = System.nanoTime();
          var read = link.read(of, with, from, from + PAGE, applied);
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
      if (atStake()) {
        stop();
        failure = new Failure(Problem.CHANGED, "");
      } else {
        open(s, true);
      }
      return;
    }
    int n = read.rows().size();
    if (n > 0 || took < 0) {
      took = nanos; // not the empty read that tells where the end is
      got = n;
    }
    long from = page * PAGE;
    if (n > 0) {
      if (!undone.isEmpty() && hasUndone(page)) {
        forgetHistory(); // rows come back where undo history was, which may be others now
      }
      int editing = selection.isEditing() ? positionOf(selection.editingRow()) : -1;
      // A draft is of the row as shown when it opened: of its original where one is held, else
      // of a row that must come back unchanged, as its values are what the write compares.
      if (editing >= 0 && editing / PAGE == page && !originals.containsKey(editing)) {
        int at = editing % PAGE;
        var was = pageRow(editing);
        if (was == null || at >= n || !was.equals(read.rows().get(at))) {
          selection.cancelEdit(); // its draft is of a row no longer there
          say(s, "The row being edited changed on the server; the edit was cancelled.");
        }
      }
      pages.put(page, read.rows());
      documents.put(page, read.documents());
    } else {
      pages.remove(page);
      documents.remove(page);
    }
    if (n < PAGE) {
      endsIn(s, page, n);
    } else if (from + PAGE > rows || from + PAGE == rows && counted != Counted.EXACT) {
      rows = from + PAGE;
      counted = Counted.AT_LEAST;
      more = true;
    }
    keep();
  }

  /**
   * A page of n rows ended before it was full: the table ends in it, or, where it is empty and the
   * page before it is not read, somewhere before, which the count finds.
   */
  private void endsIn(State s, long page, int n) {
    long from = page * PAGE;
    var before = pages.get(page - 1);
    if (n > 0 || page == 0 || before != null && before.size() == PAGE) {
      rows = from + n;
      counted = Counted.EXACT;
      more = false;
      ending = false; // found here: a count still asked for it no longer holds the pages
      countFailure = null;
    } else {
      // Empty, and what is before it not read: the end is somewhere before.
      rows = Math.min(rows, from);
      more = false;
      ending = true;
      counted(s, true);
    }
    pages.keySet().removeIf(p -> p * PAGE >= rows);
    documents.keySet().retainAll(pages.keySet());
    giveUp(p -> ending || p * PAGE >= rows);
    if (!presentation.isGrid()
        && !ending
        && presentation.page() > 0
        && presentation.page() * PAGE >= rows) {
      turn(s, Math.max(0, (rows - 1) / PAGE));
    }
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
    if (selection.isEditing()) {
      int editingPos = positionOf(selection.editingRow());
      if (editingPos >= 0) {
        far.remove(Long.valueOf(editingPos / PAGE)); // its draft's row stays
      }
    }
    // Pages with edits, or edits undo may bring back, stay, beside the pages kept: a page read
    // again may hold other rows at those positions, which the undo history, kept by position,
    // would then change.
    var pinned = pinned();
    pinned.retainAll(pages.keySet());
    far.removeAll(pinned);
    if (!presentation.isGrid()) {
      far.remove(Long.valueOf(presentation.page()));
    }
    far.sort(Comparator.comparingLong(p -> -Math.abs(p - middle)));
    for (int i = 0; i < far.size() && pages.size() > KEPT + pinned.size(); i++) {
      pages.remove(far.get(i));
      documents.remove(far.get(i));
    }
  }

  private boolean hasUndone(long page) {
    return undone.keySet().stream().anyMatch(row -> row / PAGE == page);
  }

  /**
   * Drops the undo history and keeps the staged changes: the kit keeps its history by position, so
   * a step must not replay on another row that came to its position.
   */
  private void forgetHistory() {
    undone.clear();
    edits.forgetHistory();
  }

  /** Whether the new values of these columns change the row's primary key. */
  private boolean changesKey(List<Object> row, Map<Integer, Object> values) {
    for (var value : values.entrySet()) {
      int c = value.getKey();
      if (columns.get(c).keyPosition() > 0 && !Objects.equals(row.get(c), value.getValue())) {
        return true;
      }
    }
    return false;
  }

  /** Whether two rows of the table have the same primary key. */
  private boolean sameKey(List<Object> a, List<Object> b) {
    for (int c = 0; c < columns.size(); c++) {
      if (columns.get(c).keyPosition() > 0 && !Objects.equals(a.get(c), b.get(c))) {
        return false;
      }
    }
    return true;
  }

  /** The pages of positions with edits or undo history; none while nothing is edited. */
  private Set<Long> pinned() {
    var pinned = new HashSet<Long>();
    originals.keySet().forEach(row -> pinned.add((long) row / PAGE));
    undone.keySet().forEach(row -> pinned.add((long) row / PAGE));
    return pinned;
  }

  /** The page of the cell the open value panel shows, which stays; -1 for none. */
  private long panelPage() {
    if (!value.open) {
      return -1;
    }
    int pos = positionOf(selection.row());
    return pos >= 0 ? pos / PAGE : -1;
  }

  /**
   * Makes sure the rows are counted: a count under way, whoever asked for it, goes on.
   *
   * @param separate whether to run on the database's count connection, for a count that may take
   *     long; false stays on the tree's lane for the cheap count of a small table at opening
   */
  private void counted(State s, boolean separate) {
    counted(s, separate, separate);
  }

  // retry: whether a count connection found gone, as after the server closed it while idle, is
  // replaced by a new one and the count asked again, once.
  private void counted(State s, boolean separate, boolean retry) {
    if (closed || counting != null) {
      return;
    }
    var lane = separate
        ? s.catalog.counter(s, id, table.container().database().name())
        : s.catalog.lane(s, id, table);
    if (lane == null) {
      return;
    }
    countFailure = null;
    var live = s.catalog.live(id);
    var of = table;
    var applied = filter;
    counting = lane.ask(
        s.app,
        "count rows of " + table.name(),
        Lane.Priority.COUNT,
        link -> link.count(of, applied),
        n -> {
          counting = null;
          countFailure = null;
          countedFirst = opening != null;
          ending = false;
          rows = n;
          counted = Counted.EXACT;
          more = false;
          pages.keySet().removeIf(p -> p * PAGE >= n);
          documents.keySet().retainAll(pages.keySet());
          want(s, first, last);
          if (toLast) {
            toEnd(s);
          } else if (!presentation.isGrid()) {
            turn(s, Math.min(presentation.page(), Math.max(0, (n - 1) / PAGE)));
          }
        },
        error -> countFailed(s, error, separate, retry, lane, live));
  }

  private void countFailed(
      State s, Exception error, boolean separate, boolean retry, Lane lane, Live live) {
    counting = null;
    if (closed) {
      return;
    }
    if (retry && lane.broken()) {
      counted(s, true, false);
    } else if (ending
        || error instanceof InterruptedException
        || Set.of(Problem.LOST, Problem.DISCARDED, Problem.UNOPENED).contains(problem(error))) {
      failed(s, error, false, live, !separate);
    } else {
      // The count itself failed, not the connection: keep the pages and let paging go on.
      toLast = false;
      // A page that found the end meanwhile left nothing to count.
      countFailure = counted == Counted.EXACT
          ? null
          : Objects.requireNonNullElse(CatalogTree.words(live, detail(error)), "");
    }
  }

  /**
   * A read failed. A lane closed under it while connected, as by a refresh in the tree, is asked
   * again; a lost connection marks the connection lost in the tree; a table not found, or whose
   * columns changed, is opened anew where that may help, which says whether it is gone. The
   * failure's words are checked against the password of the connection the read was asked of.
   *
   * @param again whether opening anew may help: not after the columns were just read
   */
  private void failed(State s, Exception error, boolean again, Live live) {
    failed(s, error, again, live, true);
  }

  private void failed(State s, Exception error, boolean again, Live live, boolean onTree) {
    stop();
    if (closed) {
      return;
    }
    if (error instanceof InterruptedException) {
      askAgain(s);
      return;
    }
    var problem = problem(error);
    if (problem == Problem.GONE || problem == Problem.CHANGED) {
      if (again && !atStake()) {
        open(s, true);
        return;
      }
      if (!filter.empty() && error instanceof QueryException && !again) {
        problem = Problem.OTHER; // the columns were just read: the clauses name what is not there
      }
    }
    var detail = CatalogTree.words(live, detail(error));
    if (error instanceof ConnectionException e) {
      detail = CatalogTree.join(unopened(s, e), detail);
    }
    failure = new Failure(problem, detail);
    if (onTree && (problem == Problem.LOST || problem == Problem.DISCARDED)) {
      s.catalog.lost(s, live, detail, problem == Problem.DISCARDED);
    }
  }

  /**
   * The connection connected anew under edits, which stay: what failed with the old one is read
   * again on the new one.
   */
  void reconnected(State s) {
    edits.live = s.catalog.live(id); // the same profile, as changing it asks first
    failure = null;
    countFailure = null;
    askAgain(s);
  }

  /** A lane closed under a read while connected, as by a refresh in the tree: it is asked again. */
  private void askAgain(State s) {
    if (!s.catalog.connected(id)) {
      return;
    }
    if (columns == null || fields == null) {
      open(s, false);
    } else if (ending) {
      counted(s, true); // the end was being found: no page past it is read until the count says
    } else if (presentation.isGrid()) {
      want(s, first, last);
    } else {
      turn(s, presentation.page());
    }
  }

  /** What the user can do where the table's database could not be opened, or null. */
  private String unopened(State s, ConnectionException e) {
    var profile = s.profile(id);
    return profile == null
        ? null
        : Words.note(
            e.failure(),
            profile.withDatabase(table.container().database().name()),
            true,
            s.settings.connectTimeout());
  }

  private static Problem problem(Exception error) {
    return switch (error) {
      case QueryException e ->
        switch (e.failure()) {
          case NOT_FOUND -> Problem.GONE;
          case ACCESS_DENIED -> Problem.DENIED;
          case COLUMNS_CHANGED -> Problem.CHANGED;
          case CONNECTION_LOST -> Problem.LOST;
          case ABORTED -> Problem.DISCARDED;
          case CANCELLED -> Problem.CANCELLED;
          case CLAUSE_REFUSED, OTHER -> Problem.OTHER;
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
      case ConnectionException _ -> Problem.UNOPENED;
      default -> Problem.OTHER;
    };
  }

  private static String detail(Exception error) {
    return switch (error) {
      case QueryException e -> e.detail();
      case CatalogException e -> e.detail();
      case ConnectionException e -> e.detail();
      default -> State.messageOf(error);
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
      toLast = false; // its jump to the end goes with the count
    }
    if (readingKeys != null) {
      gone.add(readingKeys);
      readingKeys = null;
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
    clearEdits();
    where.close();
    order.close();
    pages.clear();
    documents.clear();
  }

  /** Refreshes the table, asking first while changes are pending or committing. */
  void refresh(State s) {
    if (closed || !s.catalog.connected(id) || preview != null || s.guard.locked(this) != null) {
      return;
    }
    if (s.guard.asks(
        s,
        new Guard.Act(REFRESH, this),
        table.name() + " is refreshed",
        List.of(this),
        () -> refresh(s),
        Guard.Scope.CHANGES)) {
      return;
    }
    s.catalog.forgetForeignKeys(id, table);
    keysFailed = false;
    open(s, true); // which gives up a read of the keys under way
  }

  /** Replaces the table metadata (e.g. table became a view) and re-opens, asking first if edited. */
  void replace(State s, Table next) {
    if (closed || preview != null || s.guard.locked(this) != null) {
      return;
    }
    if (s.guard.asks(
        s,
        new Guard.Act(REFRESH, this),
        table.name() + " is refreshed",
        List.of(this),
        () -> replace(s, next),
        Guard.Scope.CHANGES)) {
      return;
    }
    table = next;
    open(s, true);
  }

  private boolean canApply(State s) {
    return !closed && s.catalog.connected(id) && preview == null && s.guard.locked(this) == null;
  }

  /**
   * Applies the clauses in the WHERE/ORDER BY or Filter/Sort fields: asks first while changes are
   * pending, then cancels reads in flight, drops old pages and count, resets to the first row or
   * document, clears the selection, and reads the table again with its known columns.
   */
  void apply(State s) {
    var profile = s.profile(id);
    if (!canApply(s) || profile == null) {
      return;
    }
    var next = new Rows.Filter(where.text.strip(), order.text.strip());
    var keptSort = !sorted.isEmpty() && next.order().equals(orderText(profile.provider(), sorted))
        ? sorted
        : List.<Sort>of();
    boolean keepFilters = !headerFilters.isEmpty() && next.where().equals(filter.where());
    var keptFilters = keepFilters ? headerFilters : List.<HeaderFilter>of();
    var keptManual = keepFilters ? manualWhere : "";
    apply(s, next, keptSort, keptFilters, keptManual, false, false);
  }

  /** Applies a WHERE clause from foreign key navigation, keeping the applied ORDER BY. */
  void applyWhere(State s, String clause) {
    var trimmed = clause.strip();
    if (!closed && s.catalog.connected(id) && s.profile(id) != null) {
      var locked = s.guard.locked(this);
      if (preview != null || locked != null) {
        where.text = trimmed;
        where.caret = where.text.length();
        where.completion.close();
        refused = null;
        unapplied = preview != null
            ? "The filter was not applied while DML Preview is open. Press Enter in WHERE once it"
                + " closes to apply it."
            : locked
                + ": the filter was not applied. Press Enter in WHERE once that finishes to apply"
                + " it.";
        return;
      }
    }
    apply(s, new Rows.Filter(trimmed, filter.order()), sorted, List.of(), "", true, false);
  }

  /** Drops the note of an unapplied WHERE once the WHERE field holds the applied one. */
  private void settleUnapplied() {
    if (where.text.strip().equals(filter.where())) {
      unapplied = null;
    }
  }

  /** Reads this table's foreign keys on its lane in the background when the context menu opens. */
  void readForeignKeys(State s) {
    if (closed || keysFailed || readingKeys != null || table.kind() != Table.Kind.TABLE) {
      return;
    }
    var lane = s.catalog.lane(s, id, table);
    if (lane == null) {
      return;
    }
    var live = s.catalog.live(id);
    var asked = live.forgetting;
    var of = table;
    readingKeys = lane.ask(
        s.app,
        "read foreign keys of " + table.name(),
        Lane.Priority.VIEW,
        link -> link.foreignKeys(of),
        keys -> {
          readingKeys = null;
          if (!closed && s.catalog.live(id) == live) {
            s.catalog.foreignKeysRead(id, of, keys, asked);
          }
        },
        error -> {
          readingKeys = null;
          if (closed || error instanceof InterruptedException) {
            return;
          }
          var problem = problem(error);
          if (problem == Problem.LOST || problem == Problem.DISCARDED) {
            s.catalog.lost(
                s, live, CatalogTree.words(live, detail(error)), problem == Problem.DISCARDED);
          } else {
            keysFailed = true;
          }
        });
  }

  /** Applies a header sort by writing it into ORDER BY or Sort while keeping the applied filter. */
  private void sort(State s, List<DataGrid.SortKey> keys) {
    var profile = s.profile(id);
    if (!canApply(s) || profile == null || columns == null) {
      return;
    }
    var nextSorted = new ArrayList<Sort>(keys.size());
    for (var key : keys) {
      if (key.column() < 0 || key.column() >= columns.size()) {
        return;
      }
      nextSorted.add(new Sort(columns.get(key.column()).name(), key.descending()));
    }
    var next = new Rows.Filter(filter.where(), orderText(profile.provider(), nextSorted));
    apply(s, next, List.copyOf(nextSorted), headerFilters, manualWhere, false, true);
  }

  /**
   * Applies header filters by writing them into WHERE or Filter, joined with AND to any
   * hand-written text, while keeping the applied order.
   */
  private void filter(State s, List<DataGrid.Filter> nextList) {
    var profile = s.profile(id);
    if (!canApply(s) || profile == null || columns == null) {
      return;
    }
    var incoming = new ArrayList<HeaderFilter>(nextList.size());
    for (var f : nextList) {
      if (f.column() < 0 || f.column() >= columns.size()) {
        return;
      }
      incoming.add(new HeaderFilter(columns.get(f.column()).name(), f.spec()));
    }
    // The marks show only while the field holds the applied text (activeFilters), so a field
    // edited by hand is new hand-written text, to which every filter the grid gives is joined.
    var marked = !headerFilters.isEmpty() && where.text.strip().equals(filter.where());
    String base = marked ? manualWhere : where.text.strip();
    List<HeaderFilter> nextFilters = List.copyOf(incoming);
    String clause;
    try {
      clause = whereText(profile.provider(), base, nextFilters, s.zone);
    } catch (QueryException e) {
      refused = e.detail();
      unapplied = null;
      return;
    }
    var next = new Rows.Filter(clause, filter.order());
    apply(s, next, sorted, nextFilters, nextFilters.isEmpty() ? "" : base, true, false);
  }

  private void apply(
      State s,
      Rows.Filter next,
      List<Sort> nextSorted,
      List<HeaderFilter> nextFilters,
      String nextManual,
      boolean syncWhere,
      boolean syncOrder) {
    var profile = s.profile(id);
    if (!canApply(s) || profile == null) {
      return;
    }
    Rows.Filter checked;
    try {
      checked = check(profile.provider(), next); // refused before anything is sent
      refused = null;
    } catch (QueryException e) {
      refused = e.detail(); // the applied filter and its reads go on
      unapplied = null;
      return;
    }
    if (checked.equals(filter) && failure == null) {
      if (syncWhere) {
        where.text = checked.where();
        where.caret = where.text.length();
        where.completion.close();
        headerFilters = nextFilters;
        manualWhere = nextManual;
      }
      if (syncOrder) {
        order.text = checked.order();
        order.caret = order.text.length();
        order.completion.close();
        sorted = nextSorted;
      }
      settleUnapplied();
      return;
    }
    if (s.guard.asks(
        s,
        new Guard.Act("filter", this),
        "the filter is applied",
        List.of(this),
        () -> apply(s, checked, nextSorted, nextFilters, nextManual, syncWhere, syncOrder),
        Guard.Scope.CHANGES)) {
      return;
    }
    if (syncWhere) {
      where.text = checked.where();
      where.caret = where.text.length();
      where.completion.close();
    }
    if (syncOrder) {
      order.text = checked.order();
      order.caret = order.text.length();
      order.completion.close();
    }
    sorted = nextSorted;
    headerFilters = nextFilters;
    manualWhere = nextManual;
    filter = checked;
    settleUnapplied();
    query++;
    toLast = false;
    fields = null; // read anew, also where the opening is asked again
    took = -1;
    got = 0;
    rows = 0;
    more = false;
    counted = Counted.ESTIMATED;
    countFailure = null;
    first = 0;
    last = -1;
    presentation.page(0);
    selection.clear();
    selection.scrollToRow(0);
    open(s, false);
  }

  private String whereText(Provider provider, String base, List<HeaderFilter> filters, ZoneId zone)
      throws QueryException {
    var core = new ArrayList<ColumnFilter>(filters.size());
    for (var f : filters) {
      core.add(new ColumnFilter(fieldOf(f.column(), provider), specOf(f.spec())));
    }
    return Language.of(provider).documents()
        ? DocumentFilters.filterText(base, core, zone)
        : Rows.where(provider, base, core, zone);
  }

  private Field fieldOf(String name, Provider provider) {
    int at = columnIndex(name);
    if (fields != null && at >= 0 && at < fields.size()) {
      return fields.get(at);
    }
    var col = columns.get(at);
    var kind = Language.of(provider).documents() ? Values.kind(col.type()) : kinds.get(name);
    return new Field(col.name(), col.type(), kind);
  }

  private static ColumnFilter.Spec specOf(DataGrid.Filter.Spec spec) {
    return switch (spec) {
      case DataGrid.Filter.TextMatch(var kind, var value) ->
        new ColumnFilter.Text(
            switch (kind) {
              case CONTAINS -> ColumnFilter.Match.CONTAINS;
              case STARTS_WITH -> ColumnFilter.Match.STARTS_WITH;
              case EXACTLY -> ColumnFilter.Match.EXACTLY;
              default -> ColumnFilter.Match.EQUALS;
            },
            value);
      case DataGrid.Filter.RegexMatch regex -> new ColumnFilter.Regex(regex.expression());
      case DataGrid.Filter.Between(var from, var to) -> new ColumnFilter.Between(from, to);
      case DataGrid.Filter.OneOf(var values) -> new ColumnFilter.OneOf(values);
      case DataGrid.Filter.NullCheck(var isNull) -> new ColumnFilter.Null(isNull);
    };
  }

  private static Rows.Filter check(Provider provider, Rows.Filter filter) throws QueryException {
    if (Language.of(provider).documents()) {
      var parsed = Servers.Documents.parse(filter);
      return new Rows.Filter(
          parsed.filter().isEmpty() ? "" : filter.where(),
          parsed.sort().isEmpty() ? "" : filter.order());
    }
    // The tree's and counts' SQL lanes are always read-only at the server.
    Rows.check(provider, filter, true);
    return filter;
  }

  private static String orderText(Provider provider, List<Sort> keys) {
    if (keys.isEmpty()) {
      return "";
    }
    return Language.of(provider).documents() ? orderDocument(keys) : orderSql(provider, keys);
  }

  private static String orderSql(Provider provider, List<Sort> keys) {
    var text = new StringBuilder();
    for (var key : keys) {
      if (!text.isEmpty()) {
        text.append(", ");
      }
      text.append(provider.quote(key.column())).append(key.descending() ? " DESC" : " ASC");
    }
    return text.toString();
  }

  private static String orderDocument(List<Sort> keys) {
    var text = new StringBuilder("{ ");
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        text.append(", ");
      }
      var key = keys.get(i);
      text.append(Shell.name(key.column())).append(key.descending() ? ": -1" : ": 1");
    }
    return text.append(" }").toString();
  }

  /** Same orderability rule as {@link Rows} uses for its tie-breaker columns. */
  private static boolean sortable(Kind kind) {
    return kind != null && kind != Kind.JSON && kind != Kind.OTHER;
  }

  /** Whether a column header can sort: SQL by kind, documents for top-level names without . or $. */
  private boolean sortable(String name, boolean ofDocuments) {
    return ofDocuments ? name.indexOf('.') < 0 && !name.startsWith("$") : sortable(kinds.get(name));
  }

  /** Whether a header can filter: as it can sort, but not binary data, which shows as hex. */
  private boolean filterable(String name, boolean ofDocuments) {
    return sortable(name, ofDocuments) && (ofDocuments || kinds.get(name) != Kind.BINARY);
  }

  private List<DataGrid.SortKey> sortKeys(Provider provider) {
    boolean ofDocuments = Language.of(provider).documents();
    if (sorted.isEmpty() || !filter.order().equals(orderText(provider, sorted))) {
      return List.of();
    }
    var keys = new ArrayList<DataGrid.SortKey>(sorted.size());
    for (var key : sorted) {
      int at = columnIndex(key.column());
      if (at < 0 || !sortable(key.column(), ofDocuments)) {
        return List.of();
      }
      keys.add(new DataGrid.SortKey(at, key.descending()));
    }
    return keys;
  }

  private List<DataGrid.Filter> activeFilters(boolean ofDocuments) {
    if (headerFilters.isEmpty() || !where.text.strip().equals(filter.where())) {
      return List.of();
    }
    var active = new ArrayList<DataGrid.Filter>(headerFilters.size());
    for (var f : headerFilters) {
      int at = columnIndex(f.column());
      if (at < 0 || !filterable(f.column(), ofDocuments)) {
        return List.of();
      }
      active.add(new DataGrid.Filter(at, f.spec()));
    }
    return active;
  }

  int columnIndex(String name) {
    if (columns == null) {
      return -1;
    }
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /** Shows the documents as asked: whole, the page the grid was at. */
  void show(State s, DocumentPresentation.Mode next) {
    if (next == presentation.mode()) {
      return;
    }
    if (presentation.isGrid()) {
      presentation.page((long) first / PAGE);
    }
    presentation.mode(next);
    if (next == DocumentPresentation.Mode.GRID) {
      // The grid at the page shown last, which it scrolls to as it is built.
      int top = (int) Math.min(presentation.page() * PAGE, DocumentPresentation.GRID_ROWS);
      selection.scrollToRow(top);
      want(s, top, (int)
          Math.min(presentation.page() * PAGE + PAGE - 1, DocumentPresentation.GRID_ROWS));
    } else {
      turn(s, presentation.page());
    }
  }

  /** The last page or rows, counted first where only a lower bound of them is known. */
  private void lastPage(State s) {
    if (counted == Counted.AT_LEAST) {
      toLast = true;
      counted(s, true);
    } else {
      toEnd(s);
    }
  }

  private void toEnd(State s) {
    toLast = false;
    if (presentation.isGrid()) {
      // As Control+End: an estimate too high shows the end once the reads there correct it.
      selection.scrollToRow(gridRowCount());
    } else {
      turn(s, (shown() - 1) / PAGE);
    }
  }

  /** A page the user chose: no jump to the end once counted. */
  private void chosen(State s, long page) {
    toLast = false;
    turn(s, page);
  }

  /** Shows the page of documents whole, read where it is not. */
  void turn(State s, long to) {
    presentation.page(to);
    long page = presentation.page();
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
  @Override
  public void say(State s, String text) {
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

  /** The tab's content: its toolbar, why it cannot read, the grid or documents, and its status line. */
  El view(State s, App<State> app) {
    Theme t = Theme.CURRENT.get();
    var connected = s.catalog.connected(id);
    var profile = s.profile(id);
    String connection = profile == null ? null : State.name(profile);
    boolean ofDocuments = profile != null && Language.of(profile.provider()).documents();
    El top =
        Card.toolbar(path(), connection, actions(s, connected, ofDocuments).toArray(El[]::new));
    El line = ConnectionColors.line(s.color(id));
    El content = col().grow(1).minSize(0, 0).child(top);
    content.child(Card.filters(filters(s, app, ofDocuments)).shrink(0));
    El chips = filterBar(s, ofDocuments);
    if (chips != null) {
      content.child(Card.filters(chips).shrink(0));
    }
    content.child(line == null ? Divider.divider() : line);
    El note = note(s, connected, ofDocuments);
    if (note != null) {
      content.child(col().padding(t.space().m()).shrink(0).child(note));
    }
    El writeProblem = ResultEdits.writeProblemCallout(edits);
    if (writeProblem != null) {
      content.child(writeProblem);
    }
    // Loading cells only while something is read, or was: not for a table that failed at once.
    boolean readable = columns != null && (fields != null || failure == null);
    DocumentPresentation.View presented = readable
        ? presentation.view(
            app, s.zone, ofDocuments, () -> gridView(s, app, ofDocuments), () -> documentPage(s))
        : null;
    content.child(presented != null ? presented.body() : openingBody());
    El rowsStatus = Label.label(Label.Variant.MUTED, rowsText(ofDocuments))
        .key("rows-status")
        .ellipsis();
    if (counting == null && countFailure != null) {
      rowsStatus =
          Tooltip.tooltip(rowsStatus, CatalogTree.join(rowsText(ofDocuments), countFailure));
    }
    // What the rows rest on keeps half the footer, so the texts at its end give way first; a
    // longer status, such as an estimate, is still cut and leaves the paging inside the card.
    rowsStatus.style().minWidth = Len.percent(0.5f);
    // The edit actions on a line of their own while there is something to commit: the status line
    // has no room for them beside the paging.
    boolean bar = edits.changes != null && (edits.changes.count() > 0 || edits.writing != null);
    var edited = bar ? new ArrayList<El>() : null;
    var footer = footer(s, ofDocuments, presented, edited);
    if (bar) {
      content
          .child(Divider.divider())
          .child(row()
              .key("edit-bar")
              .padding(t.space().s(), t.space().xl())
              .gap(t.space().s())
              .align(Style.Align.CENTER)
              .shrink(0)
              .children(edited.toArray(El[]::new)));
    }
    return content.child(Card.footer(rowsStatus, footer.toArray(El[]::new)));
  }

  /** The header filter chips above the view; null while no header filters are active. */
  private El filterBar(State s, boolean ofDocuments) {
    if (columns == null) {
      return null;
    }
    var active = activeFilters(ofDocuments);
    if (active.isEmpty()) {
      return null;
    }
    var cols = columns.stream()
        .map(c -> new DataGrid.Column(c.name(), 0, DataGrid.DataType.TEXT, row -> ""))
        .toList();
    return DataGrid.filterBar(cols, active, next -> filter(s, next));
  }

  /** The WHERE/ORDER BY or Filter/Sort fields above the view. */
  private El filters(State s, App<State> app, boolean ofDocuments) {
    Theme t = Theme.CURRENT.get();
    return row()
        .key("filter-bar")
        .grow(1)
        .minSize(0, 0)
        .gap(t.space().m())
        .align(Style.Align.CENTER)
        .child(clauseGroup(s, app, where, 3, ofDocuments))
        .child(clauseGroup(s, app, order, 2, ofDocuments));
  }

  private El clauseGroup(State s, App<State> app, Clause clause, float grow, boolean ofDocuments) {
    Theme t = Theme.CURRENT.get();
    El heading = Label.label(Label.Variant.MUTED, clause.label(ofDocuments))
        .font(t.type().mono().font().get())
        .fontSize(t.type().mono().size())
        .shrink(0);
    return row()
        .grow(grow)
        .basis(0)
        .minSize(0, 0)
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .child(heading)
        .child(clauseField(s, app, clause, ofDocuments));
  }

  private El clauseField(State s, App<State> app, Clause clause, boolean ofDocuments) {
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(id);
    boolean connected = profile != null && s.catalog.connected(id);
    if (!connected && clause.completion.isOpen()) {
      clause.completion.close();
    }
    El field = TextField.textField(clause.text, null, connected ? v -> clause.text = v : null)
        .key(clause.key)
        .label(clause.label(ofDocuments))
        .font(t.type().mono().font().get())
        .fontSize(t.type().mono().size())
        .codeWords()
        .onSelect((caret, anchor) -> clause.caret = caret)
        .grow(1)
        .basis(0)
        .minSize(0, 0);
    field.style().width = Len.AUTO;
    if (profile != null) {
      var syntax = Syntax.of(profile.provider());
      field.highlight(syntax, syntax);
    }
    if (!clause.completion.isOpen()) {
      clause.suggestions.forget();
    }
    return connected ? completing(s, app, profile.provider(), clause, field) : field;
  }

  /**
   * The field applies the clauses with Enter, and completes them as the console does a statement
   * of the table's SELECT or collection's find/sort, whose list takes Enter first while it is open.
   */
  private El completing(State s, App<State> app, Provider provider, Clause clause, El field) {
    field.onKey(k -> {
      if (k.modifiers() == 0 && (k.name().equals("Enter") || k.name().equals("NumpadEnter"))) {
        apply(s);
        return true;
      }
      return false;
    });
    clause.completion.openAfter(Language.of(provider).opensCompletion());
    var prefix = completionPrefix(provider, clause);
    var database = table.container().database().name();
    El completing = Completion.complete(
        app,
        field,
        clause.caret,
        clause.completion,
        word -> clause.suggestions.at(
            s,
            provider,
            database,
            prefix + clause.text,
            prefix.length() + Math.min(clause.caret, clause.text.length()),
            word));
    var keys = completing.onKey();
    return completing.onKey(k -> {
      if (k.name().equals("Space") && k.modifiers() == Key.CONTROL) {
        clause.suggestions.forget();
      }
      return keys != null && keys.test(k);
    });
  }

  private String completionPrefix(Provider provider, Clause clause) {
    if (Language.of(provider).documents()) {
      var target = "db.getCollection(" + Shell.quote(table.name()) + ")";
      return clause == where ? target + ".find(" : target + ".find({}).sort(";
    }
    return "SELECT * FROM " + Rows.qualified(provider, table) + " AS " + Rows.ALIAS + " "
        + clause.sqlKeyword + " ";
  }

  /** The toolbar's actions: how documents show, Open in console for SQL, and Refresh. */
  private List<El> actions(State s, boolean connected, boolean ofDocuments) {
    var actions = new ArrayList<El>();
    if (ofDocuments) {
      actions.add(presentation.switcher("shown", v -> show(s, v)));
    } else {
      actions.add(Button.icon(GHOST, Symbol.CODE, "Open in console", () -> openInConsole(s))
          .key("open-in-console"));
    }
    actions.add(Button.icon(
            GHOST,
            Symbol.ARROW_CLOCKWISE,
            "Refresh",
            connected ? () -> refresh(s) : null,
            Button.options()
                .state(opening != null && connected ? Button.State.BUSY : Button.State.READY))
        .key("refresh"));
    return actions;
  }

  /**
   * Opens a console on this table's connection and database with its SELECT and caret at end, or
   * appends it to an open console on that database when the setting reuses one.
   */
  private void openInConsole(State s) {
    var profile = s.profile(id);
    var group = s.tabs.groupOf(this);
    if (profile == null || group == null) {
      return;
    }
    // By the alias the grid's reads give the table, each clause on lines of its own, as they read.
    var sql = new StringBuilder("SELECT * FROM ").append(Rows.qualified(profile.provider(), table));
    if (!filter.empty()) {
      sql.append(" AS ").append(Rows.ALIAS);
    }
    if (filter.hasWhere()) {
      sql.append("\nWHERE ").append(filter.where());
    }
    if (filter.hasOrder()) {
      sql.append("\nORDER BY ").append(filter.order());
    }
    s.openInConsole(id, group, table.container().database().name(), sql.toString());
  }

  /** Placeholder body before columns are known: spinner while opening, else empty. */
  private El openingBody() {
    Theme t = Theme.CURRENT.get();
    if (opening != null) {
      return col()
          .grow(1)
          .center()
          .gap(t.space().m())
          .role("status")
          .label("Reading " + table.name())
          .child(Progress.spinner())
          .child(Label.label(Label.Variant.MUTED, "Reading " + path() + "…"));
    }
    return col().grow(1);
  }

  /** The status line after the number of rows: what it says, the last read, edits, and paging. */
  private List<El> footer(
      State s, boolean ofDocuments, DocumentPresentation.View presented, List<El> edited) {
    var end = new ArrayList<El>();
    if (said != null) {
      end.add(Label.label(Label.Variant.MUTED, said).key("said"));
    }
    // The spinner apart from its text, so that the footer can shorten the text in a narrow tab.
    if (busy()) {
      end.add(Progress.spinner().label("Reading"));
    }
    String read = busy() ? "Reading rows…" : readText(ofDocuments);
    end.add(Label.label(Label.Variant.MUTED, read).key("read-status"));
    if (!ofDocuments && fields != null) {
      ResultEdits.editControls(s, this, this, end, edited);
    }
    if (presented != null) {
      end.addAll(presented.footer());
    }
    return end;
  }

  /**
   * The grid and value panel input: a cell that a page holds in part is read whole by its row's
   * key as the panel shows it, and given up once it shows another cell or closes.
   */
  private DocumentPresentation.GridView gridView(State s, App<State> app, boolean ofDocuments) {
    var shows = inPanel(s);
    // What was asked for a cell the panel no longer shows is given up.
    if (!shows.wanted() && whole != null) {
      if (whole.asked() != null) {
        whole.asked().cancel();
      }
      whole = null;
    }
    return new DocumentPresentation.GridView(
        () -> grid(s, app, ofDocuments),
        value,
        shows.cell(),
        shows.note(),
        shows.waiting(),
        () -> inViewText(ofDocuments),
        () -> gridPaging(s));
  }

  /**
   * What the value panel shows of the current cell: the cell or none, a note, whether it waits for
   * a read, and whether it wants the cell's whole value, by the row's key.
   */
  private record InPanel(ValuePanel.Cell cell, String note, boolean waiting, boolean wanted) {
    static final InPanel NONE = new InPanel(null, null, false, false);
  }

  // Whether the cell holds a staged value of its own, which the panel shows in place of the row's.
  private boolean staged(int row, int column) {
    var changes = edits.changes;
    if (changes == null || changes.isEmpty()) {
      return false;
    }
    var status = changes.cellStatus(row, column);
    return status == DataGrid.Status.CHANGED || status == DataGrid.Status.ERROR;
  }

  /** Whether the cell (or its row) has an uncommitted staged change or modified open editor. */
  boolean isCellEdited(int row, int column) {
    var changes = edits.changes;
    if (changes == null) {
      return false;
    }
    if (!changes.isEmpty() && changes.cellStatus(row, column) != DataGrid.Status.UNCHANGED) {
      return true;
    }
    return selection.isEditing()
        && selection.editingRow() == row
        && selection.editingColumn() == column
        && !Objects.equals(selection.draft(), changes.cell(row, column));
  }

  /** What the open value panel shows of the current cell, read where its page is not held. */
  private InPanel inPanel(State s) {
    int row = selection.row();
    int column = selection.column();
    if (!value.open
        || fields == null
        || row < 0
        || row >= gridRowCount()
        || column < 0
        || column >= columns.size()) {
      return InPanel.NONE;
    }
    var changes = edits.changes;
    if (changes != null && !changes.isEmpty() && changes.isNew(row)) {
      boolean set = ResultEdits.insertedColumns(changes).get(changes.key(row)).contains(column);
      var v = set ? changes.cell(row, column) : ValuePanel.DEFAULT;
      return new InPanel(cell(column, v), null, false, false);
    }
    int pos = positionOf(row);
    if (!loaded(pos)) {
      // Out of the pages held, as after a refresh far from it: read as a page in view.
      long page = pos / PAGE;
      if (!closed && failure == null && opening == null && !ending) {
        read(s, page, Lane.Priority.VIEW);
      }
      boolean waiting = opening != null || reading.containsKey(page);
      return new InPanel(waiting ? cell(column, null) : null, null, waiting, false);
    }
    if (staged(row, column)) {
      return new InPanel(cell(column, changes.cell(row, column)), null, false, false);
    }
    var values = rowValues(pos);
    var v = values.get(column);
    if (!(v instanceof Cut)) {
      return new InPanel(cell(column, v), null, false, false);
    }
    if (!Changes.names(columns, values)) {
      return new InPanel(
          cell(column, v),
          Cells.icon(v).tooltip() + "; without a primary key the rest cannot be read",
          false,
          false);
    }
    return partial(s, values, column, v);
  }

  /** A value the row as read holds in part: its whole value, asked for by the row's key. */
  private InPanel partial(State s, List<Object> values, int column, Object v) {
    // The row as read: a page read again may hold another value of the same start.
    if (whole == null || whole.row() != values || whole.column() != column) {
      ask(s, values, column);
    }
    if (whole == null) {
      return new InPanel(cell(column, v), Cells.icon(v).tooltip(), false, true);
    }
    if (whole.asked() != null) {
      return new InPanel(cell(column, v), null, true, true);
    }
    if (whole.failure() != null) {
      return new InPanel(cell(column, v), whole.failure(), false, true);
    }
    return new InPanel(cell(column, whole.text()), null, false, true);
  }

  /** The value panel's cell of the column, with the value. */
  private ValuePanel.Cell cell(int column, Object v) {
    var c = columns.get(column);
    return new ValuePanel.Cell(c.name(), c.type(), fields.get(column).kind(), v);
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
    int bottom = selection.lastRowInView();
    if (top < 0 || bottom < top) {
      return "";
    }
    var changes = edits.changes;
    if (changes != null && !changes.isEmpty()) {
      var at = ResultEdits.positions(changes, top, bottom);
      if (at == null) {
        return "";
      }
      top = at[0];
      bottom = at[1];
    }
    if (top >= rows) {
      return "";
    }
    // Not the row that asks whether there are more, which is not known to be there.
    return DocumentPresentation.range(top + 1L, Math.min(bottom + 1L, rows), documents);
  }

  /**
   * Paging that scrolls the grid a page, the rows in view, back or on, or to its start or end; the
   * selection stays. Each is off where the grid shows its first or last row already.
   */
  private DocumentPresentation.Paging gridPaging(State s) {
    int total = gridRowCount();
    int top = selection.firstRowInView();
    int bottom = selection.lastRowInView();
    return new DocumentPresentation.Paging(
        top > 0,
        bottom >= 0 && bottom < total - 1,
        () -> {
          toLast = false;
          selection.scrollToRow(0);
        },
        () -> {
          toLast = false;
          int from = selection.firstRowInView();
          int to = selection.lastRowInView();
          selection.scrollToRow(Math.max(0, from - (to - from + 1)));
        },
        () -> {
          toLast = false;
          selection.scrollToRow(selection.lastRowInView() + 1);
        },
        () -> lastPage(s));
  }

  /** The page of documents for the JSON and Tree strategies. */
  private DocumentPresentation.DocumentPage documentPage(State s) {
    long page = presentation.page();
    var read = documents.get(page);
    long from = page * PAGE;
    long to = read == null ? Math.min(from + PAGE, shown()) : from + read.size();
    boolean loading = read == null && (reading.containsKey(page) || opening != null);
    var paging = new DocumentPresentation.Paging(
        page > 0,
        (page + 1) * PAGE < shown(),
        () -> chosen(s, 0),
        () -> chosen(s, page - 1),
        () -> chosen(s, page + 1),
        () -> lastPage(s));
    return new DocumentPresentation.DocumentPage(
        "q" + query + "-p" + page,
        read,
        0,
        read == null ? 0 : read.size(),
        from,
        to,
        loading,
        "Documents of " + table.name(),
        paging);
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
    var n = ConsoleTab.rows(rows, documents);
    var text = switch (counted) {
      case ESTIMATED -> "About " + n + ", as the server estimates";
      case AT_LEAST -> "At least " + n;
      case EXACT -> n;
    };
    if (rows > DocumentPresentation.GRID_ROWS) {
      text += " · the first "
          + String.format(Locale.ROOT, "%,d", DocumentPresentation.GRID_ROWS)
          + " are shown";
    }
    if (counting != null) {
      // First, as a failure below, so that a narrow line cuts the number short and not this.
      return "Counting… · " + Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
    if (countFailure != null) {
      // First, so that a narrow line cuts the number short and not this; why is in its tooltip.
      return "Count failed · " + Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
    return text;
  }

  /** What the last read took. */
  private String readText(boolean documents) {
    if (took < 0) {
      return "";
    }
    long ms = Math.round(took / 1e6);
    return "Read " + ConsoleTab.rows(got, documents) + " in " + ms + " ms";
  }

  /** Why the tab cannot read, with what the user can do; null while it can. */
  private El note(State s, boolean connected, boolean ofDocuments) {
    var name = table.name();
    if (!connected) {
      boolean connecting = s.catalog.active(id);
      var lost = s.catalog.lost(id);
      String text = lost != null ? CatalogTree.why(lost) : "Its connection is closed.";
      text += " What shows was read before, and may have changed since.";
      return Callout.callout(
          Callout.Kind.WARNING,
          connecting ? "Connecting…" : DISCONNECTED,
          text,
          connecting
              ? new El[0]
              : new El[] {button(SECONDARY, "Connect", () -> s.catalog.connect(s, id))});
    }
    // The refused or unapplied clauses beside, not in place of, why the applied ones could not be
    // read.
    El clauseNote = null;
    if (refused != null) {
      clauseNote = Callout.callout(
          Callout.Kind.DANGER, ofDocuments ? "Filter refused" : "Clause refused", refused);
    } else if (unapplied != null) {
      clauseNote = Callout.callout(Callout.Kind.WARNING, "Filter not applied", unapplied);
    }
    if (failure == null) {
      return clauseNote;
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
          DISCONNECTED,
          "The connection was lost: the server closed it, or the network to it failed."
        };
      case DISCARDED ->
        new String[] {
          DISCONNECTED, "The connection was closed, as the server did not stop a cancelled read."
        };
      case UNOPENED -> new String[] {"The database could not be opened", null};
      case CANCELLED -> new String[] {"Cancelled", "The server cancelled the read."};
      case OTHER -> new String[] {"Could not be read", "The server could not read " + name + "."};
    };
    var text = CatalogTree.join(words[1], failure.detail());
    El failed = Callout.callout(
        Callout.Kind.DANGER, words[0], text, button(SECONDARY, "Try again", () -> refresh(s)));
    return clauseNote == null
        ? failed
        : col().gap(Theme.CURRENT.get().space().s()).child(clauseNote).child(failed);
  }

  /** The rows in the grid, late: a row not read yet shows the grid's loading cells. */
  private El grid(State s, App<State> app, boolean ofDocuments) {
    var zone = s.zone;
    var editable = edits.editable;
    var changes = edits.changes;
    BooleanSupplier free = () ->
        edits.writing == null && s.guard.locked(this) == null && ResultEdits.current(s, this, this);
    boolean edited = editable != null && changes != null;
    int count = edited ? changes.rowCount() : gridRows();
    var inserted = edited ? ResultEdits.insertedColumns(changes) : Map.<Long, Set<Integer>>of();
    var shown = new ArrayList<DataGrid.Column>();
    for (int c = 0; c < columns.size(); c++) {
      var col = column(c, ofDocuments, zone);
      shown.add(edited ? ResultEdits.editedColumn(this, col, c, free, false, inserted) : col);
    }
    keysFailed &= menu.isOpen();
    var options = DataGrid.options()
        .select(selection)
        .onResize((c, w) -> widths[c] = w)
        .fitColumns(fit)
        .rowNumbers()
        .copy(app.clipboard())
        .nullText(Cells.nullText(s.settings.copyNull()))
        .copyLimit(
            100_000, copy -> say(s, "Only rows that were read can be copied; select fewer rows."))
        .contextMenu(menu, (row, column) -> ForeignKeys.gridMenu(s, this, ofDocuments, row, column))
        // The user's newer selection, even when nothing scrolls: no jump to the end at the count.
        .onSelect(() -> toLast = false)
        .loading(this::gridRowLoaded, (from, to) -> wantGrid(s, from, to))
        // Nothing to do: the build it causes brings the footer's rows in view along.
        .onRowsInView((from, to) -> {});
    sorting(s, options);
    filtering(s, options, ofDocuments);
    if (edited) {
      ResultEdits.configureGrid(s, this, options, free, true);
    }
    El grid = DataGrid.dataGrid(shown, count, options)
        .key("grid")
        .label("Rows of " + table.name())
        .defaultFocus()
        // Any user scroll of the grid's rows or columns cancels a pending jump to the end.
        .onUserScroll(() -> toLast = false);
    if (edited) {
      Predicate<Key> guard = k -> ResultEdits.rejectUnloadedKey(app, this, k);
      grid.onKey(grid.onKey() == null ? guard : guard.or(grid.onKey()));
    }
    return grid;
  }

  /** The grid's column c, sortable from its header where the language and column allow it. */
  private DataGrid.Column column(int c, boolean ofDocuments, ZoneId zone) {
    var kind = fields == null ? null : fields.get(c).kind();
    var col = Cells.column(columns.get(c), kind, widths, c, row -> value(row, c), zone);
    return sortable(columns.get(c).name(), ofDocuments) ? col.sortable(true) : col;
  }

  /** The header sort of the grid: its arrows, and presses while the tab can apply. */
  private void sorting(State s, DataGrid.Options options) {
    var profile = s.profile(id);
    var keys = profile == null ? List.<DataGrid.SortKey>of() : sortKeys(profile.provider());
    options.sort(keys, canApply(s) ? next -> sort(s, next) : null);
  }

  /** The header filters of the grid: funnels on filterable columns, and changes while applicable. */
  private void filtering(State s, DataGrid.Options options, boolean ofDocuments) {
    options
        .filterable(
            c -> c >= 0 && c < columns.size() && filterable(columns.get(c).name(), ofDocuments))
        .filters(activeFilters(ofDocuments), canApply(s) ? next -> filter(s, next) : null);
  }

  /** Base table rows in the grid (excluding staged new rows), which counts them in an int. */
  private int gridRows() {
    long n = shown();
    if (!originals.isEmpty()) {
      n = Math.max(n, originals.lastKey() + 1L); // an edited row past a shrunk end
    }
    return (int) Math.min(n, DocumentPresentation.GRID_ROWS);
  }

  /** Total rows shown in the grid, including staged new rows. */
  int gridRowCount() {
    var changes = edits.changes;
    return changes == null || changes.isEmpty() ? gridRows() : changes.rowCount();
  }

  /** Table position for a grid row, or -1 if it is a staged new row or out of range. */
  int positionOf(int gridRow) {
    return ResultEdits.position(edits.changes, gridRow);
  }

  private boolean gridRowLoaded(int gridRow) {
    var changes = edits.changes;
    if (changes == null || changes.isEmpty()) {
      return loaded(gridRow);
    }
    long key = changes.key(gridRow);
    return key < 0 || loaded((int) key);
  }

  private void wantGrid(State s, int gridFrom, int gridTo) {
    var changes = edits.changes;
    if (changes == null || changes.isEmpty()) {
      want(s, gridFrom, gridTo);
      return;
    }
    var at = ResultEdits.positions(changes, gridFrom, gridTo);
    if (at == null) {
      want(s, 0, -1);
    } else {
      want(s, at[0], at[1]);
    }
  }

  boolean loaded(int pos) {
    if (!originals.isEmpty() && originals.containsKey(pos)) {
      return true;
    }
    var page = pages.get((long) pos / PAGE);
    return page != null && pos % PAGE < page.size();
  }

  private Object value(int gridRow, int column) {
    int pos = positionOf(gridRow);
    if (pos < 0) {
      return null;
    }
    if (!originals.isEmpty()) {
      var rowValues = originals.get(pos);
      if (rowValues != null) {
        return rowValues.get(column);
      }
    }
    return pages.get((long) pos / PAGE).get(pos % PAGE).get(column);
  }

  List<Object> rowValues(int row) {
    if (!originals.isEmpty()) {
      var rowValues = originals.get(row);
      if (rowValues != null) {
        return rowValues;
      }
    }
    return pageRow(row);
  }

  private List<Object> pageRow(int row) {
    if (row < 0) {
      return null;
    }
    var page = pages.get((long) row / PAGE);
    int offset = row % PAGE;
    return page != null && offset < page.size() ? page.get(offset) : null;
  }

  // Editing and Guard participation

  /** Sets up editing directly from the table, catalog columns, and page fields without a query. */
  private void configureEdits(State s, Live live) {
    clearEdits();
    var profile = s.profile(id);
    if (profile == null || !(Language.of(profile.provider()) instanceof Language.Sql)) {
      return; // MongoDB and deleted connections stay as they were
    }
    if (profile.readOnly()) {
      edits.readOnlyReason = ResultEdits.READ_ONLY_CONNECTION;
      return;
    }
    if (table.kind() == Table.Kind.VIEW) {
      edits.readOnlyReason = ResultEdits.VIEW_READ_ONLY;
      return;
    }
    if (table.kind() != Table.Kind.TABLE) {
      edits.readOnlyReason = ResultEdits.FOREIGN_TABLE_READ_ONLY;
      return;
    }
    if (Changes.of(table, columns).isEmpty()) {
      edits.readOnlyReason = ResultEdits.NO_PRIMARY_KEY;
      return;
    }
    edits.live = live;
    edits.database = table.container().database().name();
    edits.editable = new Editable(table, columns, columns);
    edits.changes = ResultEdits.gridChanges(s, this);
  }

  private void clearEdits() {
    edits.stop();
    edits.discard(this);
    edits.editable = null;
    edits.readOnlyReason = null;
    edits.changes = null;
    edits.live = null;
    edits.database = null;
    preview = null;
  }

  boolean hasPendingChanges() {
    return edits.hasPending(this);
  }

  int pendingChanges() {
    return edits.pendingCount(this);
  }

  boolean writingChanges() {
    return edits.writing != null;
  }

  boolean atStake() {
    return hasPendingChanges() || writingChanges();
  }

  @Override
  public UUID connectionId() {
    return id;
  }

  @Override
  public boolean atStake(Guard.Scope scope) {
    return switch (scope) {
      case BOTH, CHANGES -> atStake();
      case TRANSACTION -> false;
    };
  }

  Guard.Pending.Question asked(State s) {
    return s.guard.pending instanceof Guard.Pending.Question question && question.tab() == this
        ? question
        : null;
  }

  @Override
  public El question(State s) {
    var asked = asked(s);
    if (asked == null) {
      return null;
    }
    return ResultEdits.discardDialog(
        s.title(this),
        asked.what(),
        writingChanges(),
        pendingChanges(),
        edits.unknown,
        () -> answer(s, null),
        () -> answer(s, ConsoleTab.STOP_WRITES),
        () -> answer(s, ConsoleTab.DISCARD));
  }

  void answer(State s, String choice) {
    var asked = asked(s);
    if (asked == null) {
      return;
    }
    if (choice == null) {
      s.guard.pending = null;
      return;
    }
    switch (choice) {
      case ConsoleTab.DISCARD -> {
        edits.discard(this);
        preview = null;
      }
      case ConsoleTab.STOP_WRITES -> ResultEdits.cancelWrite(s, this, this);
      default -> throw new IllegalArgumentException(choice);
    }
    settle(s);
  }

  private void settle(State s) {
    var asked = asked(s);
    if (asked != null && !atStake(asked.scope())) {
      s.guard.pending = null;
      asked.then().run();
    }
  }

  /** The DML Preview dialog before committing table edits, while it is open; or null. */
  El previewDialog(State s, App<State> app) {
    return ResultEdits.previewDialog(s, this, app);
  }

  @Override
  public Guard.Guarded guarded() {
    return this;
  }

  @Override
  public boolean owns(ResultEdits.Target target) {
    return !closed && target == this;
  }

  @Override
  public ResultEdits.Preview preview() {
    return preview;
  }

  @Override
  public void preview(ResultEdits.Preview next) {
    preview = next;
  }

  @Override
  public String committedHint() {
    return ResultEdits.TABLE_COMMITTED_HINT;
  }

  @Override
  public String connectionChangedReason(State s) {
    return s.catalog.connected(id)
        ? ResultEdits.TABLE_CONNECTION_CHANGED
        : ResultEdits.NOT_CONNECTED;
  }

  @Override
  public void settledFromEdit(State s) {
    settle(s);
    if (reread) {
      reread = false;
      open(s, true); // rows may have moved, been removed, or gained generated keys
    }
  }

  @Override
  public ResultEdits edits() {
    return edits;
  }

  @Override
  public DataGrid.Selection selection() {
    return selection;
  }

  @Override
  public List<Field> fields() {
    return fields;
  }

  @Override
  public int rowCount() {
    return gridRows();
  }

  @Override
  public boolean rowLoaded(int row) {
    return row >= 0 && row < gridRows() && loaded(row);
  }

  @Override
  public List<Object> rowAt(int row) {
    return rowValues(row);
  }

  @Override
  public List<Object> tableRow(int rowIndex) {
    return rowValues(rowIndex);
  }

  @Override
  public boolean hasUnloaded() {
    // Up to the last row the grid shows, which may be an edited one past a shrunk end.
    return counted != Counted.EXACT || more || rangeUnloaded(0, gridRows() - 1);
  }

  @Override
  public boolean rangeUnloaded(int top, int bottom) {
    int total = gridRows();
    if (top < 0 || bottom >= total) {
      return true;
    }
    long firstPage = (long) top / PAGE;
    long lastPage = (long) bottom / PAGE;
    for (long p = firstPage; p <= lastPage; p++) {
      var page = pages.get(p);
      int from = (int) Math.max(top, p * PAGE);
      int to = (int) Math.min(bottom, p * PAGE + PAGE - 1);
      int needed = to - (int) (p * PAGE) + 1;
      if (page != null && page.size() >= needed) {
        continue;
      }
      int have = page == null ? 0 : page.size();
      for (int r = Math.max(from, (int) (p * PAGE) + have); r <= to; r++) {
        if (!originals.containsKey(r)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public void beforeStage(State s, int row) {
    if (!originals.containsKey(row)) {
      var shown = pageRow(row);
      var before = undone.remove(row);
      if (before != null && !before.equals(shown)) {
        forgetHistory(); // its steps here are of another row, which must not replay on this one
      }
      if (shown != null) {
        originals.put(row, shown);
      }
    }
  }

  @Override
  public void afterChange() {
    var changes = edits.changes;
    if (changes == null) {
      originals.clear();
      undone.clear();
      return;
    }
    for (var gc : changes.changes()) {
      if (gc.key() >= 0) {
        keepRow((int) gc.key());
      }
    }
    boolean keepHistory = edits.hasHistory();
    var it = originals.entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      int gridRow = changes.rowOf(entry.getKey());
      if (gridRow < 0 || changes.rowStatus(gridRow) == DataGrid.Status.UNCHANGED) {
        if (keepHistory) {
          undone.put(entry.getKey(), entry.getValue());
        }
        it.remove();
      }
    }
    if (!keepHistory) {
      undone.clear();
    }
  }

  @Override
  public void onCleared() {
    originals.clear();
    undone.clear();
  }

  @Override
  public void applyCommitted(Map<Integer, Map<Integer, Object>> applied, boolean reload) {
    whole = null;
    reread = reload
        || applied.entrySet().stream().anyMatch(e -> {
          var original = originals.get(e.getKey());
          return original != null && changesKey(original, e.getValue());
        });
    if (reread) {
      return; // read anew once settled, as the server holds them
    }
    var updatedPages = new HashMap<Long, List<List<Object>>>();
    for (var entry : applied.entrySet()) {
      int rowIndex = entry.getKey();
      long pageKey = (long) rowIndex / PAGE;
      var page = updatedPages.get(pageKey);
      if (page == null) {
        var existing = pages.get(pageKey);
        if (existing == null) {
          continue;
        }
        page = new ArrayList<>(existing);
        updatedPages.put(pageKey, page);
      }
      int offset = rowIndex % PAGE;
      var original = originals.get(rowIndex);
      // Not where the page, read again since, holds another row at the edited row's position.
      if (offset < page.size() && (original == null || sameKey(original, page.get(offset)))) {
        var updated = new ArrayList<>(page.get(offset));
        entry.getValue().forEach(updated::set);
        page.set(offset, Collections.unmodifiableList(updated));
      }
    }
    updatedPages.forEach((p, list) -> pages.put(p, List.copyOf(list)));
  }

  private void keepRow(int row) {
    if (!originals.containsKey(row)) {
      // Undo and redo replay an edit on the row it was made on, also where another shows now.
      var original = undone.get(row);
      if (original == null) {
        original = pageRow(row);
      }
      if (original != null) {
        originals.put(row, original);
      }
    }
  }
}

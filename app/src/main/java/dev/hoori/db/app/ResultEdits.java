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
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Editable;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import dev.hoori.db.core.sql.EditableQuery;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Editing state of one grid ({@link ConsoleTab.Output} or {@link TableTab}): whether it is
 * editable, the staged {@link GridChanges}, the DML Preview dialog before committing, and writing
 * changes on the connection's dedicated write lane. Only the app's thread uses it.
 */
final class ResultEdits {

  static final String READ_ONLY_CONNECTION = "Read-only connection";
  static final String NOT_SINGLE_TABLE = "Read-only: not a single-table SELECT";
  static final String TEMP_TABLE_SESSION =
      "Read-only: session created a temporary table; reconnect to edit";
  static final String NOT_EDITABLE_RESULT =
      "Read-only: result needs one table with its primary key";
  static final String VIEW_READ_ONLY = "Read-only: views cannot be edited";
  static final String FOREIGN_TABLE_READ_ONLY = "Read-only: foreign tables cannot be edited";
  static final String NO_PRIMARY_KEY = "Read-only: table has no primary key";
  static final String NOT_CONNECTED = "Read-only: not connected";
  static final String CHECK_FAILED = "Read-only: could not check table";
  static final String COMMITTED_HINT =
      "Committed — run the statement again for the server's values";
  static final String TABLE_COMMITTED_HINT = "Committed — refresh for the server's values";
  static final String UNLOADED_PASTE = "Scroll to load all target rows before pasting or clearing";
  private static final String COMMIT_CHANGES = "Commit changes";
  static final String CONNECTION_CHANGED =
      "Read-only: the connection changed; run the statement again to edit";
  static final String TABLE_CONNECTION_CHANGED =
      "Read-only: the connection changed; refresh to edit";
  static final String REPEATED_ROW =
      "The same row shows more than once in the result; edit only one of its copies.";

  /** One editable grid's rows, fields, selection, and hooks for keeping edited rows. */
  interface Target {
    ResultEdits edits();

    DataGrid.Selection selection();

    List<Field> fields();

    /** Total rows shown in the grid (including any loading row at the end). */
    int rowCount();

    /** Whether the row at {@code row} is loaded (or kept) and available for reading or editing. */
    boolean rowLoaded(int row);

    /** The original row as read (or kept), in the grid's field order; null if not loaded. */
    List<Object> rowAt(int row);

    /** Whether any rows in the grid are not loaded (so paste and clear must check their range). */
    boolean hasUnloaded();

    /** Whether any row in {@code [top, bottom]} (inclusive) is not loaded. */
    boolean rangeUnloaded(int top, int bottom);

    /**
     * The row in table-column order, padded with {@link Missing#FIELD} for table columns absent
     * from the grid, as core's {@link Changes} expects.
     */
    default List<Object> tableRow(int rowIndex) {
      var editable = edits().editable;
      var resultRow = rowAt(rowIndex);
      var padded = new Object[editable.columns().size()];
      Arrays.fill(padded, Missing.FIELD);
      var fields = fields();
      for (int f = 0; f < fields.size(); f++) {
        var col = editable.edited().get(f);
        if (col != null) {
          padded[editable.columns().indexOf(col)] = resultRow.get(f);
        }
      }
      return Collections.unmodifiableList(Arrays.asList(padded));
    }

    /** Called before staging a cell edit on {@code row}, so a paged grid can keep its row. */
    default void beforeStage(State s, int row) {}

    /** Called after staging, reverting, or undo/redo changed staged state. */
    default void afterChange() {}

    /** Called when all pending edits are discarded or cleared. */
    default void onCleared() {}

    /** Applies committed new values into the rows currently held for display. */
    void applyCommitted(Map<Integer, Map<Integer, Object>> applied);
  }

  /** The tab hosting an editable grid: owns the preview dialog, write outcomes, and guard state. */
  interface Host {
    UUID connectionId();

    Guard.Guarded guarded();

    boolean owns(Target target);

    Preview preview();

    void preview(Preview preview);

    String committedHint();

    String connectionChangedReason(State s);

    /** Optional pre-commit check (such as an open console transaction); true if waiting. */
    default boolean askBeforeCommit(State s, Target target, Runnable then) {
      return false;
    }

    /** Records a commit outcome where the host keeps a log (such as a console's Messages). */
    default void onLogged(ConsoleTab.Level level, String statement, String text) {}

    void say(State s, String text);

    void settledFromEdit(State s);
  }

  Editable editable; // null while checking or when not editable
  String readOnlyReason; // why the whole grid is read-only; null for MongoDB or editable grids
  Lane.Asked checking; // the in-flight Editable.of call on the write lane, or null
  GridChanges changes; // non-null once editable != null
  Lane.Asked writing; // the in-flight Changes.write call on the write lane, or null
  ConsoleTab.Problem writeProblem; // why the last commit did not succeed, or null
  boolean unknown; // whether a commit's outcome is not known, until one commits or all is dropped
  String committedHint; // shown after Committed until the next edit or refresh/run
  String pasteRefused; // why a paste/clear was refused, cleared on next edit
  Live live; // the tree's connection the rows were read on, which writes them
  String database; // the database then, whose write lane writes them

  /**
   * The DML Preview dialog shown before committing changes: the statements formatted in the
   * dialect's colors, the core {@link Changes} to write, and the mapping back to the grid's rows.
   */
  static final class Preview {
    final Target target;
    final Changes changes;
    final List<Changes.Statement> statements;
    final String sql;
    final CodeEditor.State editor = new CodeEditor.State().readOnly(true);
    final Map<List<Object>, List<Integer>> rowsByKey;
    final Map<Integer, Map<Integer, Object>> applied;

    Preview(
        Target target,
        Changes changes,
        List<Changes.Statement> statements,
        Map<List<Object>, List<Integer>> rowsByKey,
        Map<Integer, Map<Integer, Object>> applied) {
      this.target = target;
      this.changes = changes;
      this.statements = statements;
      this.sql =
          statements.stream().map(st -> st.preview() + ";").collect(Collectors.joining("\n"));
      this.rowsByKey = rowsByKey;
      this.applied = applied;
    }
  }

  /** What a discard or cancel-commit question says and the action buttons it adds to Cancel. */
  record DiscardWording(String title, String text, List<El> buttons) {}

  /** Stops any in-flight editability check or write on the write lane. */
  void stop() {
    if (checking != null) {
      checking.cancel();
      checking = null;
    }
    if (writing != null) {
      writing.cancel();
      writing = null;
    }
  }

  /** Whether this grid has staged changes or a modified open cell editor. */
  boolean hasPending(Target t) {
    return pendingCount(t) > 0;
  }

  /** Number of pending row changes (counting a modified open cell editor as at least 1). */
  int pendingCount(Target t) {
    if (changes == null) {
      return 0;
    }
    int n = changes.count();
    return n > 0 ? n : (hasOpenEdit(t) ? 1 : 0);
  }

  boolean hasOpenEdit(Target t) {
    var selection = t.selection();
    if (changes == null || !selection.isEditing()) {
      return false;
    }
    int r = selection.editingRow();
    int c = selection.editingColumn();
    var fields = t.fields();
    if (fields == null || !t.rowLoaded(r) || c < 0 || c >= fields.size()) {
      return false;
    }
    return !Objects.equals(selection.draft(), changes.cell(r, c));
  }

  /** Discards every pending change, cancels any open editor, and clears write notes. */
  void discard(Target t) {
    t.selection().cancelEdit();
    if (changes != null) {
      changes.clear();
    }
    t.onCleared();
    writeProblem = null;
    unknown = false;
    committedHint = null;
    pasteRefused = null;
  }

  /**
   * Decides whether the SQL result {@code o} can be edited, checking cheap guards first and then
   * asking core's {@link Editable#of} on the connection's write lane.
   */
  static void check(State s, ConsoleTab tab, ConsoleTab.Output o) {
    var profile = s.profile(tab.id);
    if (profile == null || !(Language.of(profile.provider()) instanceof Language.Sql)) {
      return; // MongoDB and deleted connections stay as they were
    }
    if (profile.readOnly()) {
      o.edits.readOnlyReason = READ_ONLY_CONNECTION;
      return;
    }
    var provider = profile.provider();
    if (EditableQuery.singleTable(o.statement, provider).isEmpty()) {
      o.edits.readOnlyReason = NOT_SINGLE_TABLE;
      return;
    }
    boolean temp = tab.createdTemporaryTable;
    if (temp && Editable.temporaryTablesShadow(provider)) {
      o.edits.readOnlyReason = TEMP_TABLE_SESSION;
      return;
    }
    if (o.fields.stream().noneMatch(f -> f.origin() != null)) {
      o.edits.readOnlyReason = NOT_EDITABLE_RESULT;
      return;
    }
    // The result is of the session's connection and database, also when it is checked again.
    o.edits.live = tab.sessionLive;
    o.edits.database = tab.database;
    askEditable(s, tab, o, temp, true);
  }

  private static void askEditable(
      State s, ConsoleTab tab, ConsoleTab.Output o, boolean temp, boolean retry) {
    // A session that outlived the tree's connection may be on another server than its writer.
    if (!current(s, tab, o)) {
      o.edits.readOnlyReason = tab.connectionChangedReason(s);
      return;
    }
    var lane = s.catalog.writer(s, tab.id, o.edits.database);
    if (lane == null) {
      o.edits.readOnlyReason = NOT_CONNECTED;
      return;
    }
    var sql = o.statement;
    var fields = o.fields;
    o.edits.checking = lane.ask(
        s.app,
        "check whether result is editable",
        Lane.Priority.VIEW,
        link -> link.editable(sql, fields, temp),
        found -> {
          o.edits.checking = null;
          if (!tab.outputs.contains(o)) {
            return;
          }
          if (found.isEmpty()) {
            o.edits.readOnlyReason = NOT_EDITABLE_RESULT;
          } else {
            o.edits.editable = found.get();
            o.edits.readOnlyReason = null;
            o.edits.changes = gridChanges(s, o);
          }
        },
        error -> {
          o.edits.checking = null;
          if (!tab.outputs.contains(o) || error instanceof InterruptedException) {
            return;
          }
          if (retry && lane.broken()) {
            askEditable(s, tab, o, temp, false);
          } else {
            o.edits.readOnlyReason = CHECK_FAILED;
          }
        });
  }

  /**
   * Whether the grid's connection is still the one it was opened on: one opened again, perhaps to
   * another server, must not take its changes.
   */
  static boolean current(State s, Host host, Target t) {
    // A lost connection keeps its Live until it connects anew, but has no writer meanwhile.
    return t.edits().live != null
        && s.catalog.live(host.connectionId()) == t.edits().live
        && s.catalog.connected(host.connectionId());
  }

  /** GridChanges keyed by row position in the grid, never by primary key. */
  static GridChanges gridChanges(State s, Target t) {
    return new GridChanges(new GridChanges.Rows() {
      @Override
      public int count() {
        return t.rowCount();
      }

      @Override
      public long key(int row) {
        return row;
      }

      @Override
      public int place(long key) {
        int total = count();
        return key >= 0 && key < total ? (int) key : -1;
      }

      @Override
      public String cell(int row, int column) {
        var values = t.rowAt(row);
        return values != null ? Cells.cell(values.get(column), s.zone) : null;
      }
    });
  }

  /** Whether cell ({@code row}, {@code col}) in an editable grid can be edited. */
  static boolean cellEditable(Target t, int row, int col) {
    var edits = t.edits();
    var fields = t.fields();
    if (edits.editable == null
        || edits.writing != null
        || fields == null
        || !t.rowLoaded(row)
        || col < 0
        || col >= fields.size()) {
      return false;
    }
    var column = edits.editable.edited().get(col);
    if (column == null || fields.get(col).kind() == Kind.BINARY) {
      return false;
    }
    var values = t.rowAt(row);
    if (values == null || !wholeForEdit(values.get(col))) {
      return false;
    }
    return Changes.names(edits.editable.columns(), t.tableRow(row));
  }

  /** Whether the original value is whole and not shortened by display preview. */
  private static boolean wholeForEdit(Object value) {
    return Cells.whole(value) && !(value instanceof String str && Cells.shortened(str));
  }

  /**
   * Why the selected cell in an editable grid cannot be edited, or null if it can (or if no loaded
   * cell is selected).
   */
  static String cellReadOnlyReason(Target t) {
    var edits = t.edits();
    if (edits.editable == null) {
      return null;
    }
    var selection = t.selection();
    int row = selection.row();
    int col = selection.column();
    var fields = t.fields();
    if (fields == null || !t.rowLoaded(row) || col < 0 || col >= fields.size()) {
      return null;
    }
    var field = fields.get(col);
    var column = edits.editable.edited().get(col);
    if (column == null) {
      var origin = field.origin();
      if (origin == null) {
        return "Cell read-only: expression or computed column";
      }
      long occurrences =
          fields.stream().map(Field::origin).filter(origin::equals).count();
      if (occurrences > 1) {
        return "Cell read-only: repeated column";
      }
      if (field.type().toLowerCase(Locale.ROOT).startsWith("timestamp")) {
        return "Cell read-only: timestamp column";
      }
      return "Cell read-only: column cannot be edited here";
    }
    if (field.kind() == Kind.BINARY) {
      return "Cell read-only: binary values cannot be edited here";
    }
    var values = t.rowAt(row);
    if (values == null) {
      return null;
    }
    var value = values.get(col);
    if (value instanceof Cut || value instanceof Binary b && b.cut()) {
      return "Cell read-only: value cut short";
    }
    if (value instanceof String str && Cells.shortened(str)) {
      return "Cell read-only: long value shown in part";
    }
    if (!Changes.names(edits.editable.columns(), t.tableRow(row))) {
      return "Row read-only: primary key value is cut short or NULL";
    }
    return null;
  }

  /** Stages a single edited cell after validating against its column type and nullability. */
  static String commitCell(State s, Target t, int row, int col, String value) {
    if (!cellEditable(t, row, col)) {
      return "This cell is read-only";
    }
    var edits = t.edits();
    var field = t.fields().get(col);
    var column = edits.editable.edited().get(col);
    var original = t.rowAt(row).get(col);
    var parsed = Cells.parse(field, column, value, original, s.zone);
    if (!parsed.valid()) {
      return parsed.error();
    }
    // Keep GridChanges and core Changes in agreement when a different spelling parses to the
    // original value (e.g. "+5" vs "5").
    String stored = staged(field, parsed.value(), original, value, s.zone);
    edits.committedHint = null;
    edits.pasteRefused = null;
    edits.writeProblem = null;
    t.beforeStage(s, row);
    edits.changes.set(row, col, stored);
    t.afterChange();
    return null;
  }

  /**
   * The text a valid edit is staged as: the original's where it parses to the original value, so
   * that the grid and core agree that nothing changed; a moment with its offset, so that a later
   * change of the zone does not move it; else as typed.
   */
  static String staged(Field field, Object value, Object original, String text, ZoneId zone) {
    if (Objects.equals(value, original)) {
      return Cells.cell(value, zone);
    }
    return field.kind() == Kind.DATE_TIME_ZONED && value instanceof OffsetDateTime moment
        ? Cells.moment(moment, zone)
        : text;
  }

  /**
   * Intercepts Delete, Ctrl+0, Ctrl+V and Shift+Insert before the kit's handler when rows are not
   * all loaded (workaround for rethinkj/ui#495) so unloaded target rows are never skipped silently.
   */
  static boolean rejectUnloadedKey(App<State> app, Target t, Key k) {
    var edits = t.edits();
    if (!t.hasUnloaded()
        || edits.editable == null
        || edits.writing != null
        || t.selection().isEditing()) {
      return false;
    }
    int mods = k.modifiers();
    String name = k.name();
    boolean clear = mods == 0 && name.equals("Delete")
        || mods == Key.CONTROL && (name.equals("0") || name.equals("Numpad0"));
    boolean paste =
        mods == Key.CONTROL && name.equals("V") || mods == Key.SHIFT && name.equals("Insert");
    if (!clear && !paste) {
      return false;
    }
    boolean reachesUnloaded = selectionReachesUnloaded(t);
    if (!reachesUnloaded && paste) {
      var parsed = DataGrid.parseText(app.clipboard().read());
      if (parsed.size() > 1 || parsed.size() == 1 && parsed.getFirst().size() > 1) {
        int top = t.selection().row();
        if (top >= 0 && t.rangeUnloaded(top, top + parsed.size() - 1)) {
          reachesUnloaded = true;
        }
      }
    }
    if (reachesUnloaded) {
      edits.pasteRefused = UNLOADED_PASTE;
      return true;
    }
    return false;
  }

  /** Stages pasted or cleared cells, refusing if the selection reaches unloaded rows. */
  static void pasteCells(State s, Target t, List<DataGrid.CellValue> cells) {
    var edits = t.edits();
    if (edits.editable == null || edits.writing != null) {
      return;
    }
    if (t.hasUnloaded() && selectionReachesUnloaded(t)) {
      edits.pasteRefused = UNLOADED_PASTE;
      return;
    }
    var fields = t.fields();
    var valid = new ArrayList<DataGrid.CellValue>(cells.size());
    String leftOut = null;
    for (var cell : cells) {
      if (!cellEditable(t, cell.row(), cell.column())) {
        continue;
      }
      var field = fields.get(cell.column());
      var column = edits.editable.edited().get(cell.column());
      var original = t.rowAt(cell.row()).get(cell.column());
      var parsed = Cells.parse(field, column, cell.value(), original, s.zone);
      if (parsed.valid()) {
        String stored = staged(field, parsed.value(), original, cell.value(), s.zone);
        valid.add(new DataGrid.CellValue(cell.row(), cell.column(), stored));
      } else if (leftOut == null) {
        leftOut = "A pasted value was left out: " + parsed.error();
      }
    }
    edits.pasteRefused = leftOut;
    if (!valid.isEmpty()) {
      edits.committedHint = null;
      edits.writeProblem = null;
      for (var cell : valid) {
        t.beforeStage(s, cell.row());
      }
      edits.changes.set(valid);
      t.afterChange();
    }
  }

  private static boolean selectionReachesUnloaded(Target t) {
    var selection = t.selection();
    int row = selection.row();
    if (row >= 0 && !t.rowLoaded(row)) {
      return true;
    }
    for (var range : selection.ranges()) {
      if (t.rangeUnloaded(range.top(), range.bottom())) {
        return true;
      }
    }
    return false;
  }

  /** Whether any cell in the current selection has a pending change. */
  static boolean hasSelectedChanges(Target t) {
    if (t.edits().changes == null) {
      return false;
    }
    // Asked at each build, where there are changes: a small selection is looked up cell by cell, a
    // large one by the changes.
    var selection = t.selection();
    var ranges = selection.ranges();
    if (ranges.isEmpty()) {
      return changed(t, selection.row(), selection.column());
    }
    // A whole row or column reaches to the end, which the grid's bounds cut.
    int lastRow = t.rowCount() - 1;
    int lastColumn = t.fields().size() - 1;
    long area = 0;
    for (var range : ranges) {
      long rows = Math.max(0, Math.min(range.bottom(), lastRow) - range.top() + 1);
      long columns = Math.max(0, Math.min(range.right(), lastColumn) - range.left() + 1);
      area += rows * columns;
    }
    // shortcut: a large selection walks every staged change at each build; ask the kit once it
    // can say whether a selection holds one (rethinkj/ui#514).
    if (area > SCAN) {
      return !selectedChanges(t).isEmpty();
    }
    for (var range : ranges) {
      for (int r = range.top(); r <= Math.min(range.bottom(), lastRow); r++) {
        for (int c = range.left(); c <= Math.min(range.right(), lastColumn); c++) {
          if (selection.contains(r, c) && changed(t, r, c)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static final long SCAN = 10_000;

  private static boolean changed(Target t, int row, int column) {
    return row >= 0
        && row < t.rowCount()
        && column >= 0
        && column < t.fields().size()
        && t.edits().changes.cellStatus(row, column) != DataGrid.Status.UNCHANGED;
  }

  // The changed cells in the selection, as row and column; from the changes, which are few beside
  // a large selection.
  private static List<int[]> selectedChanges(Target t) {
    var cells = new ArrayList<int[]>();
    var changes = t.edits().changes;
    if (changes == null) {
      return cells;
    }
    var selection = t.selection();
    var ranges = selection.ranges();
    for (var change : changes.changes()) {
      int r = (int) change.key();
      for (int c : change.values().keySet()) {
        boolean selected = ranges.isEmpty()
            ? r == selection.row() && c == selection.column()
            : selection.contains(r, c);
        if (selected && t.rowLoaded(r)) {
          cells.add(new int[] {r, c});
        }
      }
    }
    return cells;
  }

  /** Reverts only the changed cells inside the current selection. */
  static void revertSelection(State s, Target t) {
    var edits = t.edits();
    if (edits.changes == null || edits.writing != null) {
      return;
    }
    t.selection().cancelEdit();
    var reverts = new ArrayList<DataGrid.CellValue>();
    for (var at : selectedChanges(t)) {
      reverts.add(
          new DataGrid.CellValue(at[0], at[1], Cells.cell(t.rowAt(at[0]).get(at[1]), s.zone)));
    }
    if (!reverts.isEmpty()) {
      edits.changes.set(reverts);
      t.afterChange();
      if (edits.changes.isEmpty()) {
        edits.writeProblem = null;
      }
    }
  }

  /** Reverts all pending changes in this grid. */
  static void revertAll(Target t) {
    var edits = t.edits();
    if (edits.writing != null) {
      return;
    }
    edits.discard(t);
  }

  /**
   * Commit…: commits any open cell editor, runs any host pre-commit check (such as an open console
   * transaction), and then opens the DML Preview dialog.
   */
  static void askCommit(State s, Host host, Target t) {
    var edits = t.edits();
    if (edits.editable == null
        || edits.writing != null
        || s.guard.locked(host.guarded()) != null
        || !current(s, host, t)) {
      return;
    }
    if (!t.selection().commitEdit()) {
      return; // open cell editor rejected its value
    }
    if (edits.changes.isEmpty()) {
      return;
    }
    if (host.askBeforeCommit(s, t, () -> askCommit(s, host, t))) {
      return;
    }
    var profile = s.profile(host.connectionId());
    if (profile == null) {
      return;
    }
    var preview = buildPreview(s, t, profile.provider());
    if (preview == null) {
      return;
    }
    host.preview(preview);
  }

  /** Builds core's {@link Changes} and its {@link Changes.Statement} previews from staged edits. */
  private static Preview buildPreview(State s, Target t, Provider provider) {
    var edits = t.edits();
    var editable = edits.editable;
    var fields = t.fields();
    var updates = new ArrayList<Change>();
    var rowsByKey = new HashMap<List<Object>, List<Integer>>();
    var applied = new LinkedHashMap<Integer, Map<Integer, Object>>();
    var keyCols = keyIndexes(editable.columns());
    for (var gc : edits.changes.changes()) {
      if (gc.kind() != GridChanges.Kind.UPDATE) {
        continue;
      }
      int rowIndex = (int) gc.key();
      var padded = t.tableRow(rowIndex);
      var key = keyCols.stream().map(padded::get).toList();
      var copies = rowsByKey.computeIfAbsent(key, k -> new ArrayList<>());
      if (!copies.isEmpty()) {
        edits.writeProblem = new ConsoleTab.Problem("Row shown twice", REPEATED_ROW);
        return null;
      }
      copies.add(rowIndex);
      var rowNew = new LinkedHashMap<Integer, Object>();
      var values = new TreeMap<Integer, Object>();
      var origRow = t.rowAt(rowIndex);
      for (var entry : gc.values().entrySet()) {
        int resultCol = entry.getKey();
        var col = editable.edited().get(resultCol);
        int tableCol = editable.columns().indexOf(col);
        var original = origRow.get(resultCol);
        var parsed = Cells.parse(fields.get(resultCol), col, entry.getValue(), original, s.zone);
        if (!parsed.valid()) {
          edits.changes.error(rowIndex, resultCol, parsed.error());
          edits.writeProblem =
              new ConsoleTab.Problem("Invalid value", col.name() + ": " + parsed.error());
          return null;
        }
        if (!Objects.equals(parsed.value(), original)) {
          values.put(tableCol, parsed.value());
        }
        rowNew.put(resultCol, parsed.value());
      }
      applied.put(rowIndex, rowNew);
      if (!values.isEmpty()) {
        updates.add(new Change.Update(padded, values));
      }
    }
    // One Changes for all, checked once, as a paste may stage thousands of rows.
    var core = new Changes(editable.table(), editable.columns(), updates);
    var statements = core.statements(provider);
    if (statements.isEmpty()) {
      edits.changes.clear();
      t.onCleared();
      return null;
    }
    return new Preview(t, core, statements, rowsByKey, applied);
  }

  private static List<Integer> keyIndexes(List<Column> columns) {
    return IntStream.range(0, columns.size())
        .filter(i -> columns.get(i).keyPosition() > 0)
        .boxed()
        .sorted(Comparator.comparingInt(i -> columns.get(i).keyPosition()))
        .toList();
  }

  /** The DML Preview dialog while open in the host's window, or null. */
  static El previewDialog(State s, Host host, App<State> app) {
    var p = host.preview();
    if (p == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(host.connectionId());
    var provider = profile == null ? null : profile.provider();
    int n = p.statements.size();
    var table =
        provider == null ? p.changes.table().name() : Rows.qualified(provider, p.changes.table());
    String summary = (n == 1 ? "1 statement" : n + " statements")
        + " will run in one transaction on " + table + ".";
    El code = CodeEditor.embedded(CodeEditor.codeEditor(
            app, p.sql, p.editor, provider == null ? null : Syntax.of(provider), null)
        .key("preview-sql")
        .label("SQL preview"));
    El content = col()
        // Statements are long and the editor does not wrap them.
        .w(2 * ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(summary).wrapText())
        .child(code.h(Math.min(10, Math.max(3, n + 1)) * t.size().control()).wFull());
    El commit = button(PRIMARY, "Commit", () -> commitPreview(s, host)).key("preview-commit");
    El cancel =
        button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> host.preview(null)).key("preview-cancel");
    return Dialog.dialog(
            "DML Preview",
            content,
            List.of(cancel, commit),
            () -> host.preview(null),
            Dialog.options().defaultButton(commit))
        .key("dml-preview");
  }

  /** Runs {@link Changes#write} on the write lane once the user confirms Commit in the preview. */
  static void commitPreview(State s, Host host) {
    var p = host.preview();
    if (p == null) {
      return;
    }
    host.preview(null);
    var t = p.target;
    var edits = t.edits();
    if (!host.owns(t) || edits.writing != null || !current(s, host, t)) {
      return;
    }
    var lane = s.catalog.writer(s, host.connectionId(), edits.database);
    if (lane == null) {
      edits.writeProblem = new ConsoleTab.Problem(
          "Not connected",
          "Reconnect " + connectionName(s, host) + " in Workspace before committing changes.");
      return;
    }
    edits.writeProblem = null;
    edits.committedHint = null;
    edits.pasteRefused = null;
    var changes = p.changes;
    var live = edits.live;
    edits.writing = lane.ask(
        s.app,
        "commit changes to " + changes.table().name(),
        Lane.Priority.VIEW,
        link -> link.write(changes),
        outcome -> {
          edits.writing = null;
          if (!host.owns(t)) {
            return;
          }
          handleOutcome(s, host, p, outcome);
          host.settledFromEdit(s);
        },
        error -> {
          edits.writing = null;
          if (!host.owns(t)) {
            return;
          }
          if (error instanceof InterruptedException) {
            // Only a lane that closed gives one up here; a cancel by the user delivers nothing.
            edits.unknown = true;
            edits.writeProblem = new ConsoleTab.Problem(
                ConsoleTab.UNSURE,
                "The commit was given up as its connection closed: whether the changes took"
                    + " effect is not known. Check the data; your changes are kept.");
          } else {
            handleFailure(s, host, t, live, error);
          }
          host.settledFromEdit(s);
        });
  }

  private static void handleOutcome(State s, Host host, Preview p, Changes.Outcome outcome) {
    var t = p.target;
    var edits = t.edits();
    switch (outcome) {
      case Changes.Outcome.Committed committed -> {
        t.applyCommitted(p.applied);
        edits.changes.clear();
        t.onCleared();
        edits.writeProblem = null;
        edits.unknown = false;
        edits.committedHint = host.committedHint();
        int n = p.statements.size();
        host.onLogged(
            ConsoleTab.Level.RAN,
            "Commit " + ConsoleTab.rows(n) + " to " + p.changes.table().name(),
            ConsoleTab.rows(n) + ConsoleTab.CHANGED);
        host.say(s, "Committed");
      }
      case Changes.Outcome.Missed(var change, var rows) -> {
        String why = rows == 0
            ? "Row changed or was deleted on the server since it was read"
            : "Primary key matched " + rows + " rows on the server";
        markMissed(t, p, change, why);
        edits.writeProblem = new ConsoleTab.Problem(
            "Changes not committed", why + ". Nothing was written; your changes are kept.");
        host.onLogged(
            ConsoleTab.Level.ERROR,
            "Commit to " + p.changes.table().name(),
            "Not committed: " + why);
      }
      case Changes.Outcome.Refused refused -> {
        String why = "The changes could not be written safely in one transaction (for example, a"
            + " non-transactional table engine, inherited tables, or a timestamp in a repeated"
            + " hour). Nothing was written; your changes are kept.";
        edits.writeProblem = new ConsoleTab.Problem("Changes refused", why);
        host.onLogged(ConsoleTab.Level.ERROR, "Commit to " + p.changes.table().name(), "Refused");
      }
    }
  }

  private static void markMissed(Target t, Preview p, Change change, String message) {
    if (!(change instanceof Change.Update(var row, var values))) {
      return;
    }
    var edits = t.edits();
    var key = keyIndexes(p.changes.columns()).stream().map(row::get).toList();
    var rows = p.rowsByKey.getOrDefault(key, List.of());
    int first = -1;
    for (int rowIndex : rows) {
      if (first < 0) {
        first = rowIndex;
      }
      var editedCols = p.applied.getOrDefault(rowIndex, Map.of());
      for (int col : editedCols.keySet()) {
        edits.changes.error(rowIndex, col, message);
      }
    }
    if (first >= 0) {
      t.selection().scrollToRow(first);
    }
  }

  private static void handleFailure(State s, Host host, Target t, Live live, Exception error) {
    var edits = t.edits();
    if (error instanceof QueryException qe
        && (qe.failure() == QueryException.Failure.CONNECTION_LOST
            || qe.failure() == QueryException.Failure.ABORTED)) {
      String detail = scrubbed(live, qe.detail());
      String body = "The connection was lost while committing changes: whether the commit took"
          + " effect is not known. Check the data before committing again; your changes are kept.";
      if (!detail.isEmpty()) {
        body += "\n" + detail;
      }
      edits.unknown = true;
      edits.writeProblem = new ConsoleTab.Problem(ConsoleTab.UNSURE, body);
      host.onLogged(ConsoleTab.Level.ERROR, COMMIT_CHANGES, "Outcome not known: connection lost");
      return;
    }
    String title;
    String detail;
    if (error instanceof QueryException qe) {
      title = switch (qe.failure()) {
        case ACCESS_DENIED -> "Access denied";
        case NOT_FOUND -> "Table not found";
        case CANCELLED -> ConsoleTab.CANCELLED_TEXT;
        default -> "Commit failed";
      };
      detail = scrubbed(live, qe.detail());
    } else if (error instanceof ConnectionException ce) {
      var profile = s.profile(host.connectionId());
      title = Words.title(ce.failure());
      detail = profile == null
          ? scrubbed(live, ce.detail())
          : Words.note(ce.failure(), profile, true, s.settings.connectTimeout());
    } else {
      title = "Commit failed";
      detail = scrubbed(live, State.messageOf(error));
    }
    String text = detail.isEmpty()
        ? "Nothing was written; your changes are kept."
        : detail + "\nNothing was written; your changes are kept.";
    edits.writeProblem = new ConsoleTab.Problem(title, text);
    host.onLogged(ConsoleTab.Level.ERROR, COMMIT_CHANGES, title);
  }

  /** Cancels an in-flight write on the write lane; changes stay and outcome is marked unknown. */
  static void cancelWrite(State s, Host host, Target t) {
    var edits = t.edits();
    if (edits.writing == null) {
      return;
    }
    edits.writing.cancel();
    edits.writing = null;
    edits.unknown = true;
    edits.writeProblem = new ConsoleTab.Problem(
        ConsoleTab.UNSURE,
        "The commit was cancelled: if the server had already begun committing, whether the changes"
            + " took effect is not known. Check the data; your changes are kept.");
    host.onLogged(ConsoleTab.Level.WARNING, COMMIT_CHANGES, ConsoleTab.CANCELLED_TEXT);
    host.settledFromEdit(s);
  }

  // Shared UI helpers for ResultsArea, TableTab, and ConsoleView

  /** Callout showing why the last commit did not succeed, with Dismiss; null when none. */
  static El writeProblemCallout(ResultEdits edits) {
    if (edits.writeProblem == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    return col()
        .padding(t.space().s())
        .shrink(0)
        .child(Callout.callout(
                Callout.Kind.DANGER,
                edits.writeProblem.title(),
                edits.writeProblem.text(),
                button(SECONDARY, "Dismiss", () -> edits.writeProblem = null)
                    .key("dismiss-write-problem"))
            .key("write-problem"));
  }

  /**
   * Edit status, pending count badge, Revert and Commit… controls for an editable SQL grid: the
   * status, as why a cell is read-only, goes to {@code status}; the pending
   * count and the buttons go to {@code actions}, which may be the same list, or null for none.
   */
  static void editControls(State s, Host host, Target target, List<El> status, List<El> actions) {
    Theme t = Theme.CURRENT.get();
    var edits = target.edits();
    if (edits.writing != null) {
      status.add(row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .key("edit-status")
          .child(Progress.spinner().label("Committing"))
          .child(Label.label(Label.Variant.MUTED, "Committing…")));
      if (actions != null) {
        actions.add(button(
                SECONDARY,
                ConsoleTab.CANCEL_BUTTON,
                () -> cancelWrite(s, host, target),
                Button.options().tone(Button.Tone.DANGER))
            .key("cancel-write"));
      }
      return;
    }
    boolean current = current(s, host, target);
    String why = edits.editable != null && !current
        ? host.connectionChangedReason(s)
        : editStatus(s, host, target);
    if (why != null) {
      // The line is short; the whole reason, as how to add an offset, shows on hover.
      status.add(Tooltip.tooltip(
          Label.label(Label.Variant.MUTED, why).key("edit-status").ellipsis(), why));
    }
    if (edits.editable == null || actions == null) {
      return;
    }
    int count = edits.changes.count();
    if (count > 0) {
      actions.add(Badge.badge(
              count == 1 ? "1 change" : count + " changes",
              Badge.options().toolbar().kind(Badge.Kind.WARNING))
          .key("pending-changes"));
    }
    boolean locked = s.guard.locked(host.guarded()) != null;
    boolean selectedChanges = count > 0 && hasSelectedChanges(target);
    actions.add(Button.icon(
            SECONDARY,
            Symbol.ARROW_UNDO,
            "Revert selected cells",
            selectedChanges && !locked ? () -> revertSelection(s, target) : null)
        .key("revert-selection"));
    actions.add(Tooltip.tooltip(
        button(
                SECONDARY,
                "Revert all",
                (count > 0 || edits.hasOpenEdit(target)) && !locked
                    ? () -> revertAll(target)
                    : null)
            .key("revert-all"),
        "Revert all pending changes"));
    boolean canCommit = (count > 0 || target.selection().isEditing()) && !locked && current;
    actions.add(button(PRIMARY, "Commit…", canCommit ? () -> askCommit(s, host, target) : null)
        .key("commit-changes"));
  }

  private static String editStatus(State s, Host host, Target target) {
    var edits = target.edits();
    if (edits.pasteRefused != null) {
      return edits.pasteRefused;
    }
    if (edits.editable != null) {
      String cellReason = cellReadOnlyReason(target);
      if (cellReason != null) {
        return cellReason;
      }
      return edits.committedHint;
    }
    var reason = edits.readOnlyReason;
    // Whether it is connected may have changed since the result was checked.
    return NOT_CONNECTED.equals(reason) || CONNECTION_CHANGED.equals(reason)
        ? host.connectionChangedReason(s)
        : reason;
  }

  /** Wires a grid column to staged values, editability, nullability, and warning icons. */
  static DataGrid.Column editedColumn(
      Target target, DataGrid.Column col, int at, BooleanSupplier free, boolean markKey) {
    var field = target.fields().get(at);
    var edits = target.edits();
    var changes = edits.changes;
    var column = edits.editable.edited().get(at);
    col = col.cell(changes.cell(at));
    if (markKey && column != null && column.keyPosition() > 0) {
      col = col.icon(Symbol.KEY, Icon.Variant.WARNING);
    }
    if (column != null && field.kind() != Kind.BINARY) {
      col = col.editable(row -> free.getAsBoolean() && cellEditable(target, row, at));
      if (column.nullable()) {
        col = col.nullable();
      }
    }
    if (field.kind() == Kind.TEXT || field.kind() == Kind.JSON || field.kind() == Kind.OTHER) {
      col = col.cellIcon(row -> {
        if (!target.rowLoaded(row)
            || !changes.isEmpty() && changes.cellStatus(row, at) != DataGrid.Status.UNCHANGED) {
          return null;
        }
        var values = target.rowAt(row);
        return values != null ? Cells.icon(values.get(at)) : null;
      });
    }
    return col;
  }

  /** Wires a {@link DataGrid.Options} to staged changes, undo/redo, commit, and paste handlers. */
  static void configureGrid(
      State s, Target target, DataGrid.Options options, BooleanSupplier free) {
    var changes = target.edits().changes;
    options
        .changes(changes)
        .history(
            () -> {
              if (free.getAsBoolean()) {
                changes.undo();
                target.afterChange();
              }
            },
            () -> {
              if (free.getAsBoolean()) {
                changes.redo();
                target.afterChange();
              }
            })
        .onCommit((row, col, value) -> free.getAsBoolean()
            ? commitCell(s, target, row, col, value)
            : "Not now: wait until what runs is done")
        .onPaste(cells -> {
          if (free.getAsBoolean()) {
            pasteCells(s, target, cells);
          }
        })
        .onInsertRow(null)
        .onDeleteRows(null);
  }

  /** Wording and action buttons for the Discard / Cancel commit question dialog. */
  static DiscardWording discardWording(
      String name,
      String before,
      boolean writing,
      int count,
      boolean unsure,
      Runnable onCancelCommit,
      Runnable onDiscard) {
    if (writing) {
      return new DiscardWording(
          "Waiting for changes to commit",
          "Changes are being committed on " + name + ". Wait until the commit finishes, or"
              + " cancel it: whether it took effect is then not known, and the changes stay.",
          List.of(button(
                  SECONDARY,
                  "Cancel commit",
                  onCancelCommit,
                  Button.options().tone(Button.Tone.DANGER))
              .key("answer-cancel-commit")));
    }
    String changes = count == 1 ? "1 change" : count + " changes";
    return new DiscardWording(
        "Discard " + changes + "?",
        name + " has " + (count == 1 ? "1 uncommitted change" : count + " uncommitted changes")
            + ". Discard " + (count == 1 ? "it" : "them") + " before " + before + "?"
            + (unsure
                ? " Whether the last commit took effect is not known: discarding drops the changes"
                    + " here and undoes nothing on the server."
                : ""),
        List.of(button(PRIMARY, "Discard", onDiscard, Button.options().tone(Button.Tone.DANGER))
            .key("answer-discard")));
  }

  /** Complete Discard / Cancel commit question dialog for a tab that has no console transaction. */
  static El discardDialog(
      String name,
      String before,
      boolean writing,
      int count,
      boolean unsure,
      Runnable onCancel,
      Runnable onCancelCommit,
      Runnable onDiscard) {
    Theme t = Theme.CURRENT.get();
    var wording = discardWording(name, before, writing, count, unsure, onCancelCommit, onDiscard);
    var buttons = new ArrayList<El>();
    buttons.add(button(SECONDARY, ConsoleTab.CANCEL_BUTTON, onCancel).key("answer-cancel"));
    buttons.addAll(wording.buttons());
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(wording.text()).wrapText())
        .child(
            writing
                ? row()
                    .gap(t.space().s())
                    .align(Style.Align.CENTER)
                    .child(Progress.spinner().label("Waiting"))
                    .child(Label.label(Label.Variant.MUTED, "Waiting for the server…"))
                : null);
    return Dialog.dialog(wording.title(), content, buttons, onCancel).key("transaction-question");
  }

  // The write lane signs in with the tree's password, which its messages are checked against.
  private static String scrubbed(Live live, String text) {
    return text == null || text.isEmpty() ? "" : CatalogTree.words(live, text);
  }

  private static String connectionName(State s, Host host) {
    ConnectionProfile profile = s.profile(host.connectionId());
    return profile == null ? "the connection" : State.name(profile);
  }
}

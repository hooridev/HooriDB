package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.GridChanges;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Theme;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Editing state of one console result ({@link ConsoleTab.Output}): whether core found it editable,
 * the staged {@link GridChanges}, the DML Preview dialog before committing, and writing changes on
 * the connection's dedicated write lane. Only the app's thread uses it.
 */
final class ResultEdits {

  static final String READ_ONLY_CONNECTION = "Read-only connection";
  static final String NOT_SINGLE_TABLE = "Read-only: not a single-table SELECT";
  static final String TEMP_TABLE_SESSION =
      "Read-only: session created a temporary table; reconnect to edit";
  static final String NOT_EDITABLE_RESULT =
      "Read-only: result needs one table with its primary key";
  static final String CHECK_FAILED = "Read-only: could not check table";
  static final String COMMITTED_HINT =
      "Committed — run the statement again for the server's values";
  static final String UNLOADED_PASTE = "Scroll to load all target rows before pasting or clearing";
  private static final String COMMIT_CHANGES = "Commit changes";
  static final String CONNECTION_CHANGED =
      "Read-only: the connection changed; run the statement again to edit";
  static final String REPEATED_ROW =
      "The same row shows more than once in the result; edit only one of its copies.";

  Editable editable; // null while checking or when not editable
  String readOnlyReason; // why the whole result is read-only; null for MongoDB or editable results
  Lane.Asked checking; // the in-flight Editable.of call on the write lane, or null
  GridChanges changes; // non-null once editable != null
  Lane.Asked writing; // the in-flight Changes.write call on the write lane, or null
  ConsoleTab.Problem writeProblem; // why the last commit did not succeed, or null
  boolean unknown; // whether a commit's outcome is not known, until one commits or all is dropped
  String committedHint; // shown after Committed until the next edit or run
  String pasteRefused; // why a paste/clear was refused, cleared on next edit
  Live live; // the tree's connection the result was found editable on, which writes it
  String database; // the console's database then, whose write lane writes it

  /**
   * The DML Preview dialog shown before committing changes: the statements formatted in the
   * dialect's colors, the core {@link Changes} to write, and the mapping back to the grid's rows.
   */
  static final class Preview {
    final ConsoleTab.Output output;
    final Changes changes;
    final List<Changes.Statement> statements;
    final String sql;
    final CodeEditor.State editor = new CodeEditor.State().readOnly(true);
    final Map<List<Object>, List<Integer>> rowsByKey;
    final Map<Integer, Map<Integer, Object>> applied;

    Preview(
        ConsoleTab.Output output,
        Changes changes,
        List<Changes.Statement> statements,
        Map<List<Object>, List<Integer>> rowsByKey,
        Map<Integer, Map<Integer, Object>> applied) {
      this.output = output;
      this.changes = changes;
      this.statements = statements;
      this.sql =
          statements.stream().map(st -> st.preview() + ";").collect(Collectors.joining("\n"));
      this.rowsByKey = rowsByKey;
      this.applied = applied;
    }
  }

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

  /** Whether this result has staged changes or a modified open cell editor. */
  boolean hasPending(ConsoleTab.Output o) {
    return pendingCount(o) > 0;
  }

  /** Number of pending row changes (counting a modified open cell editor as at least 1). */
  int pendingCount(ConsoleTab.Output o) {
    if (changes == null) {
      return 0;
    }
    int n = changes.count();
    return n > 0 ? n : (hasOpenEdit(o) ? 1 : 0);
  }

  boolean hasOpenEdit(ConsoleTab.Output o) {
    if (changes == null || !o.selection.isEditing()) {
      return false;
    }
    int r = o.selection.editingRow();
    int c = o.selection.editingColumn();
    if (r < 0 || r >= o.rows.size() || c < 0 || c >= o.fields.size()) {
      return false;
    }
    return !Objects.equals(o.selection.draft(), changes.cell(r, c));
  }

  /** Discards every pending change, cancels any open editor, and clears write notes. */
  void discard(ConsoleTab.Output o) {
    o.selection.cancelEdit();
    if (changes != null) {
      changes.clear();
    }
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
      o.edits.readOnlyReason = CONNECTION_CHANGED;
      return;
    }
    var lane = s.catalog.writer(s, tab.id, o.edits.database);
    if (lane == null) {
      o.edits.readOnlyReason = "Read-only: not connected";
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
   * Whether the result's connection is still the one it was found editable on: one opened again,
   * perhaps to another server, must not take its changes.
   */
  static boolean current(State s, ConsoleTab tab, ConsoleTab.Output o) {
    return o.edits.live != null && s.catalog.live(tab.id) == o.edits.live;
  }

  /** GridChanges keyed by row position in the result, never by primary key. */
  private static GridChanges gridChanges(State s, ConsoleTab.Output o) {
    return new GridChanges(new GridChanges.Rows() {
      @Override
      public int count() {
        return (int) Math.min(o.rows.size() + (o.more ? 1L : 0L), DocumentPresentation.GRID_ROWS);
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
        return row < o.rows.size() ? Cells.cell(o.rows.get(row).get(column), s.zone) : null;
      }
    });
  }

  /** Whether cell ({@code row}, {@code col}) in an editable result can be edited. */
  static boolean cellEditable(ConsoleTab.Output o, int row, int col) {
    if (o.edits.editable == null
        || o.edits.writing != null
        || row < 0
        || row >= o.rows.size()
        || col < 0
        || col >= o.fields.size()) {
      return false;
    }
    var column = o.edits.editable.edited().get(col);
    if (column == null || o.fields.get(col).kind() == Kind.BINARY) {
      return false;
    }
    var value = o.rows.get(row).get(col);
    if (!wholeForEdit(value)) {
      return false;
    }
    return Changes.names(o.edits.editable.columns(), tableRow(o, row));
  }

  /** Whether the original value is whole and not shortened by display preview. */
  private static boolean wholeForEdit(Object value) {
    return Cells.whole(value) && !(value instanceof String str && Cells.shortened(str));
  }

  /**
   * Why the selected cell in an editable result cannot be edited, or null if it can (or if no
   * loaded cell is selected).
   */
  static String cellReadOnlyReason(ConsoleTab.Output o) {
    if (o.edits.editable == null) {
      return null;
    }
    int row = o.selection.row();
    int col = o.selection.column();
    if (row < 0 || row >= o.rows.size() || col < 0 || col >= o.fields.size()) {
      return null;
    }
    var field = o.fields.get(col);
    var column = o.edits.editable.edited().get(col);
    if (column == null) {
      var origin = field.origin();
      if (origin == null) {
        return "Cell read-only: expression or computed column";
      }
      long occurrences =
          o.fields.stream().map(Field::origin).filter(origin::equals).count();
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
    var value = o.rows.get(row).get(col);
    if (value instanceof Cut || value instanceof Binary b && b.cut()) {
      return "Cell read-only: value cut short";
    }
    if (value instanceof String str && Cells.shortened(str)) {
      return "Cell read-only: long value shown in part";
    }
    if (!Changes.names(o.edits.editable.columns(), tableRow(o, row))) {
      return "Row read-only: primary key value is cut short or NULL";
    }
    return null;
  }

  /**
   * Builds the table row padded with {@link Missing#FIELD} for table columns absent from the
   * result, as core's {@link Changes#edit} expects.
   */
  static List<Object> tableRow(ConsoleTab.Output o, int rowIndex) {
    var editable = o.edits.editable;
    var resultRow = o.rows.get(rowIndex);
    var padded = new Object[editable.columns().size()];
    Arrays.fill(padded, Missing.FIELD);
    for (int f = 0; f < o.fields.size(); f++) {
      var col = editable.edited().get(f);
      if (col != null) {
        padded[editable.columns().indexOf(col)] = resultRow.get(f);
      }
    }
    return Collections.unmodifiableList(Arrays.asList(padded));
  }

  /** Stages a single edited cell after validating against its column type and nullability. */
  static String commitCell(State s, ConsoleTab.Output o, int row, int col, String value) {
    if (!cellEditable(o, row, col)) {
      return "This cell is read-only";
    }
    var field = o.fields.get(col);
    var column = o.edits.editable.edited().get(col);
    var original = o.rows.get(row).get(col);
    var parsed = Cells.parse(field, column, value, original, s.zone);
    if (!parsed.valid()) {
      return parsed.error();
    }
    // Keep GridChanges and core Changes in agreement when a different spelling parses to the
    // original value (e.g. "+5" vs "5").
    String stored = staged(field, parsed.value(), original, value, s.zone);
    o.edits.committedHint = null;
    o.edits.pasteRefused = null;
    o.edits.writeProblem = null;
    o.edits.changes.set(row, col, stored);
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
  static boolean rejectUnloadedKey(App<State> app, ConsoleTab.Output o, Key k) {
    if (!o.more || o.edits.editable == null || o.edits.writing != null || o.selection.isEditing()) {
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
    int loaded = o.rows.size();
    boolean reachesUnloaded = selectionReachesUnloaded(o, loaded);
    if (!reachesUnloaded && paste) {
      var parsed = DataGrid.parseText(app.clipboard().read());
      if (parsed.size() > 1 || parsed.size() == 1 && parsed.getFirst().size() > 1) {
        int top = o.selection.row();
        if (top >= 0 && top + parsed.size() > loaded) {
          reachesUnloaded = true;
        }
      }
    }
    if (reachesUnloaded) {
      o.edits.pasteRefused = UNLOADED_PASTE;
      return true;
    }
    return false;
  }

  /** Stages pasted or cleared cells, refusing if the selection reaches unloaded rows. */
  static void pasteCells(State s, ConsoleTab.Output o, List<DataGrid.CellValue> cells) {
    if (o.edits.editable == null || o.edits.writing != null) {
      return;
    }
    if (o.more && selectionReachesUnloaded(o, o.rows.size())) {
      o.edits.pasteRefused = UNLOADED_PASTE;
      return;
    }
    var valid = new ArrayList<DataGrid.CellValue>(cells.size());
    String leftOut = null;
    for (var cell : cells) {
      if (!cellEditable(o, cell.row(), cell.column())) {
        continue;
      }
      var field = o.fields.get(cell.column());
      var column = o.edits.editable.edited().get(cell.column());
      var original = o.rows.get(cell.row()).get(cell.column());
      var parsed = Cells.parse(field, column, cell.value(), original, s.zone);
      if (parsed.valid()) {
        String stored = staged(field, parsed.value(), original, cell.value(), s.zone);
        valid.add(new DataGrid.CellValue(cell.row(), cell.column(), stored));
      } else if (leftOut == null) {
        leftOut = "A pasted value was left out: " + parsed.error();
      }
    }
    o.edits.pasteRefused = leftOut;
    if (!valid.isEmpty()) {
      o.edits.committedHint = null;
      o.edits.writeProblem = null;
      o.edits.changes.set(valid);
    }
  }

  private static boolean selectionReachesUnloaded(ConsoleTab.Output o, int loaded) {
    if (o.selection.row() >= loaded) {
      return true;
    }
    for (var range : o.selection.ranges()) {
      if (range.bottom() >= loaded) {
        return true;
      }
    }
    return false;
  }

  /** Whether any cell in the current selection has a pending change. */
  static boolean hasSelectedChanges(ConsoleTab.Output o) {
    var changes = o.edits.changes;
    if (changes == null || changes.isEmpty()) {
      return false;
    }
    // Asked at each build: a small selection is looked up cell by cell, a large one by the changes.
    var ranges = o.selection.ranges();
    if (ranges.isEmpty()) {
      return changed(o, o.selection.row(), o.selection.column());
    }
    long area = 0;
    for (var range : ranges) {
      area += (long) (range.bottom() - range.top() + 1) * (range.right() - range.left() + 1);
    }
    if (area > SCAN) {
      return !selectedChanges(o).isEmpty();
    }
    for (var range : ranges) {
      for (int r = range.top(); r <= range.bottom(); r++) {
        for (int c = range.left(); c <= range.right(); c++) {
          if (o.selection.contains(r, c) && changed(o, r, c)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static final long SCAN = 10_000;

  private static boolean changed(ConsoleTab.Output o, int row, int column) {
    return row >= 0
        && row < o.rows.size()
        && column >= 0
        && column < o.fields.size()
        && o.edits.changes.cellStatus(row, column) != DataGrid.Status.UNCHANGED;
  }

  // The changed cells in the selection, as row and column; from the changes, which are few beside
  // a large selection.
  private static List<int[]> selectedChanges(ConsoleTab.Output o) {
    var cells = new ArrayList<int[]>();
    if (o.edits.changes == null) {
      return cells;
    }
    var ranges = o.selection.ranges();
    for (var change : o.edits.changes.changes()) {
      int r = (int) change.key();
      for (int c : change.values().keySet()) {
        boolean selected = ranges.isEmpty()
            ? r == o.selection.row() && c == o.selection.column()
            : o.selection.contains(r, c);
        if (selected && r < o.rows.size()) {
          cells.add(new int[] {r, c});
        }
      }
    }
    return cells;
  }

  /** Reverts only the changed cells inside the current selection. */
  static void revertSelection(State s, ConsoleTab.Output o) {
    if (o.edits.changes == null || o.edits.writing != null) {
      return;
    }
    o.selection.cancelEdit();
    var reverts = new ArrayList<DataGrid.CellValue>();
    for (var at : selectedChanges(o)) {
      reverts.add(
          new DataGrid.CellValue(at[0], at[1], Cells.cell(o.rows.get(at[0]).get(at[1]), s.zone)));
    }
    if (!reverts.isEmpty()) {
      o.edits.changes.set(reverts);
      if (o.edits.changes.isEmpty()) {
        o.edits.writeProblem = null;
      }
    }
  }

  /** Reverts all pending changes in this result. */
  static void revertAll(ConsoleTab.Output o) {
    if (o.edits.writing != null) {
      return;
    }
    o.edits.discard(o);
  }

  /**
   * Commit…: commits any open cell editor, asks to end an open console transaction first (so the
   * write lane does not block on the console's own lock), and then opens the DML Preview dialog.
   */
  static void askCommit(State s, ConsoleTab tab, ConsoleTab.Output o) {
    if (o.edits.editable == null
        || o.edits.writing != null
        || s.guard.locked(tab) != null
        || !current(s, tab, o)) {
      return;
    }
    if (!o.selection.commitEdit()) {
      return; // open cell editor rejected its value
    }
    if (o.edits.changes.isEmpty()) {
      return;
    }
    if (s.guard.asks(
        s,
        new Guard.Act("commit-changes", o),
        "the changes are committed",
        List.of(tab),
        () -> askCommit(s, tab, o),
        Guard.Scope.TRANSACTION)) {
      return;
    }
    var profile = s.profile(tab.id);
    if (profile == null) {
      return;
    }
    var preview = buildPreview(s, o, profile.provider());
    if (preview == null) {
      return;
    }
    tab.preview = preview;
  }

  /** Builds core's {@link Changes} and its {@link Changes.Statement} previews from staged edits. */
  private static Preview buildPreview(State s, ConsoleTab.Output o, Provider provider) {
    var editable = o.edits.editable;
    var updates = new ArrayList<Change>();
    var rowsByKey = new HashMap<List<Object>, List<Integer>>();
    var applied = new LinkedHashMap<Integer, Map<Integer, Object>>();
    var keyCols = keyIndexes(editable.columns());
    for (var gc : o.edits.changes.changes()) {
      if (gc.kind() != GridChanges.Kind.UPDATE) {
        continue;
      }
      int rowIndex = (int) gc.key();
      var padded = tableRow(o, rowIndex);
      var key = keyCols.stream().map(padded::get).toList();
      var copies = rowsByKey.computeIfAbsent(key, k -> new ArrayList<>());
      if (!copies.isEmpty()) {
        o.edits.writeProblem = new ConsoleTab.Problem("Row shown twice", REPEATED_ROW);
        return null;
      }
      copies.add(rowIndex);
      var rowNew = new LinkedHashMap<Integer, Object>();
      var values = new TreeMap<Integer, Object>();
      for (var entry : gc.values().entrySet()) {
        int resultCol = entry.getKey();
        var col = editable.edited().get(resultCol);
        int tableCol = editable.columns().indexOf(col);
        var original = o.rows.get(rowIndex).get(resultCol);
        var parsed = Cells.parse(o.fields.get(resultCol), col, entry.getValue(), original, s.zone);
        if (!parsed.valid()) {
          o.edits.changes.error(rowIndex, resultCol, parsed.error());
          o.edits.writeProblem =
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
      o.edits.changes.clear();
      return null;
    }
    return new Preview(o, core, statements, rowsByKey, applied);
  }

  private static List<Integer> keyIndexes(List<Column> columns) {
    return IntStream.range(0, columns.size())
        .filter(i -> columns.get(i).keyPosition() > 0)
        .boxed()
        .sorted(Comparator.comparingInt(i -> columns.get(i).keyPosition()))
        .toList();
  }

  /** The DML Preview dialog while open in the console's window, or null. */
  static El previewDialog(State s, ConsoleTab tab, App<State> app) {
    var p = tab.preview;
    if (p == null) {
      return null;
    }
    Theme t = Theme.CURRENT.get();
    var profile = s.profile(tab.id);
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
    El commit = button(PRIMARY, "Commit", () -> commitPreview(s, tab)).key("preview-commit");
    El cancel =
        button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> tab.preview = null).key("preview-cancel");
    return Dialog.dialog(
            "DML Preview",
            content,
            List.of(cancel, commit),
            () -> tab.preview = null,
            Dialog.options().defaultButton(commit))
        .key("dml-preview");
  }

  /** Runs {@link Changes#write} on the write lane once the user confirms Commit in the preview. */
  static void commitPreview(State s, ConsoleTab tab) {
    var p = tab.preview;
    if (p == null) {
      return;
    }
    tab.preview = null;
    var o = p.output;
    if (!tab.outputs.contains(o) || o.edits.writing != null || !current(s, tab, o)) {
      return;
    }
    var lane = s.catalog.writer(s, tab.id, o.edits.database);
    if (lane == null) {
      o.edits.writeProblem = new ConsoleTab.Problem(
          "Not connected",
          "Reconnect " + connectionName(s, tab) + " in Workspace before committing changes.");
      return;
    }
    o.edits.writeProblem = null;
    o.edits.committedHint = null;
    o.edits.pasteRefused = null;
    var changes = p.changes;
    var live = o.edits.live;
    o.edits.writing = lane.ask(
        s.app,
        "commit changes to " + changes.table().name(),
        Lane.Priority.VIEW,
        link -> link.write(changes),
        outcome -> {
          o.edits.writing = null;
          if (!tab.outputs.contains(o)) {
            return;
          }
          handleOutcome(s, tab, p, outcome);
          tab.settledFromEdit(s);
        },
        error -> {
          o.edits.writing = null;
          if (!tab.outputs.contains(o)) {
            return;
          }
          if (error instanceof InterruptedException) {
            // Only a lane that closed gives one up here; a cancel by the user delivers nothing.
            o.edits.unknown = true;
            o.edits.writeProblem = new ConsoleTab.Problem(
                ConsoleTab.UNSURE,
                "The commit was given up as its connection closed: whether the changes took"
                    + " effect is not known. Check the data; your changes are kept.");
          } else {
            handleFailure(s, tab, o, live, error);
          }
          tab.settledFromEdit(s);
        });
  }

  private static void handleOutcome(State s, ConsoleTab tab, Preview p, Changes.Outcome outcome) {
    var o = p.output;
    switch (outcome) {
      case Changes.Outcome.Committed committed -> {
        for (var entry : p.applied.entrySet()) {
          int rowIndex = entry.getKey();
          if (rowIndex >= 0 && rowIndex < o.rows.size()) {
            var updated = new ArrayList<>(o.rows.get(rowIndex));
            entry.getValue().forEach(updated::set);
            o.rows.set(rowIndex, Collections.unmodifiableList(updated));
          }
        }
        o.edits.changes.clear();
        o.edits.writeProblem = null;
        o.edits.unknown = false;
        o.edits.committedHint = COMMITTED_HINT;
        int n = p.statements.size();
        tab.log.add(new ConsoleTab.Logged(
            ConsoleTab.Level.RAN,
            "Commit " + ConsoleTab.rows(n) + " to " + p.changes.table().name(),
            null,
            ConsoleTab.rows(n) + ConsoleTab.CHANGED));
        tab.say(s, "Committed");
      }
      case Changes.Outcome.Missed(var change, var rows) -> {
        String why = rows == 0
            ? "Row changed or was deleted on the server since it was read"
            : "Primary key matched " + rows + " rows on the server";
        markMissed(o, p, change, why);
        o.edits.writeProblem = new ConsoleTab.Problem(
            "Changes not committed", why + ". Nothing was written; your changes are kept.");
        tab.log.add(new ConsoleTab.Logged(
            ConsoleTab.Level.ERROR,
            "Commit to " + p.changes.table().name(),
            null,
            "Not committed: " + why));
      }
      case Changes.Outcome.Refused refused -> {
        String why = "The changes could not be written safely in one transaction (for example, a"
            + " non-transactional table engine, inherited tables, or a timestamp in a repeated"
            + " hour). Nothing was written; your changes are kept.";
        o.edits.writeProblem = new ConsoleTab.Problem("Changes refused", why);
        tab.log.add(new ConsoleTab.Logged(
            ConsoleTab.Level.ERROR, "Commit to " + p.changes.table().name(), null, "Refused"));
      }
    }
  }

  private static void markMissed(ConsoleTab.Output o, Preview p, Change change, String message) {
    if (!(change instanceof Change.Update(var row, var values))) {
      return;
    }
    var key = keyIndexes(p.changes.columns()).stream().map(row::get).toList();
    var rows = p.rowsByKey.getOrDefault(key, List.of());
    int first = -1;
    for (int rowIndex : rows) {
      if (first < 0) {
        first = rowIndex;
      }
      var editedCols = p.applied.getOrDefault(rowIndex, Map.of());
      for (int col : editedCols.keySet()) {
        o.edits.changes.error(rowIndex, col, message);
      }
    }
    if (first >= 0) {
      o.selection.scrollToRow(first);
    }
  }

  private static void handleFailure(
      State s, ConsoleTab tab, ConsoleTab.Output o, Live live, Exception error) {
    if (error instanceof QueryException qe
        && (qe.failure() == QueryException.Failure.CONNECTION_LOST
            || qe.failure() == QueryException.Failure.ABORTED)) {
      String detail = scrubbed(live, qe.detail());
      String body = "The connection was lost while committing changes: whether the commit took"
          + " effect is not known. Check the data before committing again; your changes are kept.";
      if (!detail.isEmpty()) {
        body += "\n" + detail;
      }
      o.edits.unknown = true;
      o.edits.writeProblem = new ConsoleTab.Problem(ConsoleTab.UNSURE, body);
      tab.log.add(new ConsoleTab.Logged(
          ConsoleTab.Level.ERROR, COMMIT_CHANGES, null, "Outcome not known: connection lost"));
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
      var profile = s.profile(tab.id);
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
    o.edits.writeProblem = new ConsoleTab.Problem(title, text);
    tab.log.add(new ConsoleTab.Logged(ConsoleTab.Level.ERROR, COMMIT_CHANGES, null, title));
  }

  /** Cancels an in-flight write on the write lane; changes stay and outcome is marked unknown. */
  static void cancelWrite(State s, ConsoleTab tab, ConsoleTab.Output o) {
    if (o.edits.writing == null) {
      return;
    }
    o.edits.writing.cancel();
    o.edits.writing = null;
    o.edits.unknown = true;
    o.edits.writeProblem = new ConsoleTab.Problem(
        ConsoleTab.UNSURE,
        "The commit was cancelled: if the server had already begun committing, whether the changes"
            + " took effect is not known. Check the data; your changes are kept.");
    tab.log.add(new ConsoleTab.Logged(
        ConsoleTab.Level.WARNING, COMMIT_CHANGES, null, ConsoleTab.CANCELLED_TEXT));
    tab.settledFromEdit(s);
  }

  // The write lane signs in with the tree's password, which its messages are checked against.
  private static String scrubbed(Live live, String text) {
    return text == null || text.isEmpty() ? "" : CatalogTree.words(live, text);
  }

  private static String connectionName(State s, ConsoleTab tab) {
    ConnectionProfile profile = s.profile(tab.id);
    return profile == null ? "the connection" : State.name(profile);
  }
}

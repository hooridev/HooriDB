package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.editor.CodeEditor;
import com.rethinkj.ui.json.Json;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * The value of a grid's current cell in a panel beside the grid, which follows the cell as the
 * selection moves; Shift+Enter in the grid or the panel opens and closes it. JSON shows formatted
 * and colored, and JSON that is not valid as it came with its first problem marked; any other value
 * as its cell writes it, but whole. It only reads. One per tab; only the app's thread uses it.
 */
final class ValuePanel {

  /** The panel's first width, in the theme's control heights. */
  static final float WIDTH = 16;

  /** What the panel shows: the current cell's column, and its value as read, or whole. */
  record Cell(String column, String type, Kind kind, Object value) {}

  private static final Object NOTHING = new Object();
  /** The value of a new row's cell left without one, which takes the column's default. */
  static final Object DEFAULT = new Object();

  private static final String TITLE = "Value"; // of the panel, and of its button

  boolean open;
  private Split.Position split; // as the user moved it; null for the first width
  private CodeEditor.State editor = new CodeEditor.State().readOnly(true).inlineDiagnostics(true);
  // The value the text was made of, compared by identity: a value of a row, or a whole text.
  private Object made = NOTHING;
  private Kind madeKind;
  private ZoneId madeZone;
  private String text = "";

  /** The button in a status line that opens and closes it. */
  El button() {
    return Tooltip.tooltip(
        Button.toggleIcon(Symbol.PANEL_RIGHT, TITLE, open, on -> open = on).key("value-toggle"),
        "Value of the cell (Shift+Enter)");
  }

  /**
   * The grid, with the panel beside it while it is open.
   *
   * @param cell the current cell, or null for none
   * @param note what the panel says below the value, such as that it is cut short; or null
   * @param waiting whether the value is read still, which then shows in place of the cell's
   */
  El around(App<State> app, ZoneId zone, El grid, Cell cell, String note, boolean waiting) {
    Theme t = Theme.CURRENT.get();
    float unit = t.size().control();
    El shown = grid;
    if (!open && made != NOTHING) { // what it showed, which may be long, goes with it
      made = NOTHING;
      madeZone = null;
      text = "";
      editor = new CodeEditor.State().readOnly(true).inlineDiagnostics(true);
    }
    if (open) {
      var first = Split.Position.second(WIDTH * unit);
      shown = Split.split(
          Split.Direction.ROW,
          split == null ? first : split,
          first,
          p -> split = p,
          grid,
          8 * unit,
          panel(app, zone, cell, note, waiting),
          6 * unit);
    }
    return col().grow(1).minSize(0, 0).onKey(this::key).child(shown.grow(1));
  }

  private boolean key(Key key) {
    if (key.modifiers() != Key.SHIFT || !key.name().equals("Enter")) {
      return false;
    }
    open = !open;
    return true;
  }

  private El panel(App<State> app, ZoneId zone, Cell cell, String note, boolean waiting) {
    Theme t = Theme.CURRENT.get();
    El close = Button.icon(Button.Variant.GHOST, Symbol.DISMISS, "Close", () -> open = false)
        .key("value-close");
    El top = Card.toolbar(
        cell == null ? TITLE : cell.column(), cell == null ? null : cell.type(), close);
    El body;
    if (cell == null) {
      body = EmptyState.emptyState(
          Symbol.PANEL_RIGHT, "No cell", "Select a cell to see its value.", null);
    } else if (waiting) {
      body = col()
          .grow(1)
          .center()
          .role("status")
          .label("Reading the value")
          .child(Progress.spinner());
    } else if (cell.value() == null) {
      body = col().grow(1).center().child(Label.label(Label.Variant.MUTED, "NULL"));
    } else if (cell.value() == DEFAULT) {
      body = col().grow(1).center().child(Label.label(Label.Variant.MUTED, "DEFAULT"));
    } else if (cell.value() == Missing.FIELD) {
      body = col().grow(1).center().child(Label.label(Label.Variant.MUTED, "No such field"));
    } else {
      make(cell.value(), cell.kind(), zone);
      body = CodeEditor.embedded(CodeEditor.codeEditor(
                  app, text, editor, cell.kind() == Kind.JSON ? Json.of() : null, null)
              .key("value")
              .label("Value of " + cell.column())
              // Before the editor's own keys: a field takes Enter also where it only reads.
              .onKeyFirst(this::key))
          .grow(1);
    }
    El said = note == null
        ? null
        : row()
            .padding(t.space().s(), t.space().xl())
            .align(Style.Align.CENTER)
            .child(Label.label(Label.Variant.MUTED, note).key("value-note").ellipsis());
    return col()
        .grow(1)
        .minSize(0, 0)
        .label(TITLE)
        .key("value-panel")
        .child(top)
        .child(body.grow(1).minSize(0, 0))
        .child(said);
  }

  /** The text of the value, made anew only where it changed: JSON formatted where it is valid. */
  private void make(Object value, Kind kind, ZoneId zone) {
    if (value == made && kind == madeKind && Objects.equals(zone, madeZone)) {
      return;
    }
    made = value;
    madeKind = kind;
    madeZone = zone;
    editor.noDiagnostics();
    editor.select(0, 0);
    if (kind == Kind.JSON && value instanceof String json) {
      var formatted = Json.format(json);
      if (formatted.valid()) {
        text = formatted.text();
        return;
      }
      var problem = formatted.problem();
      text = json;
      editor.diagnostics(
          json,
          List.of(new CodeEditor.Diagnostic(
              problem.from(),
              problem.to(),
              CodeEditor.Severity.ERROR,
              Texts.CURRENT.get().formatProblem(problem.kind()))));
      return;
    }
    // A text cut short is not formatted: it would only end too early.
    text = Cells.text(value, zone);
  }
}

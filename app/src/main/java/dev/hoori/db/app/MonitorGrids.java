package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.monitor.Block;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.ServerVariable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * The grids of a server monitor's views: its sessions, variables, status counters and locks. Their
 * columns carry no type, as what a server tells of itself is text; their widths are the tab's, fitted
 * once to the first rows, and the user may change them.
 */
final class MonitorGrids {

  private MonitorGrids() {}

  /** The widths of a grid's columns, -1 where not set yet, and whether they are still to be fitted. */
  static final class Widths {
    float[] of;
    final DataGrid.Fit fit = new DataGrid.Fit();

    private Widths size(int columns) {
      if (of == null || of.length != columns) {
        of = new float[columns];
        Arrays.fill(of, -1);
      }
      return this;
    }
  }

  /** The sessions, a row each; the monitor's own marked. */
  static El sessions(
      List<ServerSession> shown,
      Widths widths,
      DataGrid.Selection selection,
      Runnable onSelect,
      Menu.State menu,
      List<Menu.Item> items,
      App<State> app) {
    var w = widths.size(8);
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column(w, 0, "ID", 4, RIGHT, shown, ServerSession::id)
        .cellIcon(row -> shown.get(row).own()
            ? DataGrid.CellIcon.before(Symbol.PERSON, Icon.Variant.ACCENT)
                .tooltip("The monitor's own session")
            : null));
    columns.add(column(w, 1, "User", 5, LEFT, shown, ServerSession::user));
    columns.add(column(w, 2, "State", 6, LEFT, shown, ServerSession::state));
    columns.add(column(w, 3, "Time", 3, RIGHT, shown, x -> elapsed(x.elapsed())));
    columns.add(column(w, 4, "Statement", 16, LEFT, shown, ServerSession::statement));
    columns.add(column(w, 5, "Waiting for", 6, LEFT, shown, ServerSession::waiting));
    columns.add(column(w, 6, "Database", 5, LEFT, shown, ServerSession::database));
    columns.add(column(w, 7, "Client", 8, LEFT, shown, ServerSession::client));
    var options = options(w, selection, app).onSelect(onSelect).contextMenu(menu, items);
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("sessions")
        .label("Sessions")
        .grow(1)
        .defaultFocus();
  }

  /** The variables, a row each, with where each comes from where the server tells. */
  static El variables(
      List<ServerVariable> shown,
      Widths widths,
      DataGrid.Selection selection,
      Runnable onSelect,
      App<State> app) {
    var w = widths.size(3);
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column(w, 0, "Name", 12, LEFT, shown, ServerVariable::name));
    columns.add(column(w, 1, "Value", 14, LEFT, shown, ServerVariable::value));
    columns.add(column(w, 2, "Source", 8, LEFT, shown, ServerVariable::source));
    return DataGrid.dataGrid(columns, shown.size(), options(w, selection, app).onSelect(onSelect))
        .key("variables")
        .label("Variables")
        .grow(1)
        .defaultFocus();
  }

  /** The status counters, a row each, a whole number grouped by thousands. */
  static El status(
      List<Counter> shown,
      Widths widths,
      DataGrid.Selection selection,
      Runnable onSelect,
      App<State> app) {
    var w = widths.size(2);
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column(w, 0, "Name", 12, LEFT, shown, Counter::name));
    columns.add(column(w, 1, "Value", 8, RIGHT, shown, c -> grouped(c.value())));
    return DataGrid.dataGrid(columns, shown.size(), options(w, selection, app).onSelect(onSelect))
        .key("status")
        .label("Status")
        .grow(1)
        .defaultFocus();
  }

  /**
   * The sessions that wait for a lock, beside the one that blocks each; activating a row goes to
   * the session that blocks.
   */
  static El locks(
      List<Block> shown,
      Widths widths,
      DataGrid.Selection selection,
      Runnable onSelect,
      IntConsumer onActivate,
      Menu.State menu,
      List<Menu.Item> items,
      App<State> app) {
    var w = widths.size(4);
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column(w, 0, "Waiting", 5, RIGHT, shown, Block::waiting));
    columns.add(column(w, 1, "Blocked by", 5, RIGHT, shown, Block::blocking));
    columns.add(column(w, 2, "On", 12, LEFT, shown, Block::object));
    columns.add(column(w, 3, "Mode", 8, LEFT, shown, Block::mode));
    var options = options(w, selection, app)
        .onSelect(onSelect)
        .onActivate(onActivate)
        .contextMenu(menu, items);
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("locks")
        .label("Locks")
        .grow(1)
        .defaultFocus();
  }

  private static final DataGrid.Align LEFT = DataGrid.Align.LEFT;
  private static final DataGrid.Align RIGHT = DataGrid.Align.RIGHT;

  private static DataGrid.Options options(Widths w, DataGrid.Selection selection, App<State> app) {
    return DataGrid.options()
        .select(selection)
        .copy(app.clipboard())
        .onResize((c, width) -> w.of[c] = width)
        .fitColumns(w.fit);
  }

  // A column of text without a type, so its header shows no type's icon; it is not sorted.
  private static <T> DataGrid.Column column(
      Widths w,
      int index,
      String title,
      float width,
      DataGrid.Align align,
      List<T> shown,
      Function<T, String> text) {
    Theme t = Theme.CURRENT.get();
    float at = w.of[index] < 0 ? width * t.size().control() : w.of[index];
    return new DataGrid.Column(title, at, align, false, false, row -> text.apply(shown.get(row)));
  }

  /** The items whose text holds the search, without regard to case; all for an empty one. */
  static <T> List<T> found(List<T> items, String search, Function<T, String> text) {
    if (search.isBlank()) {
      return items;
    }
    var wanted = search.strip().toLowerCase(Locale.ROOT);
    return items.stream()
        .filter(item -> text.apply(item).toLowerCase(Locale.ROOT).contains(wanted))
        .toList();
  }

  /** A whole number grouped by thousands, as "1,234,567"; any other value as it is. */
  static String grouped(String value) {
    var number = Format.BIG_INTEGER.parse(value).value();
    return number == null ? value : String.format(Locale.ROOT, "%,d", number);
  }

  /** How long, as "850 ms", "12 s", "4:03", "1:07:12" or "2 d 5 h"; empty where not known. */
  static String elapsed(Duration elapsed) {
    if (elapsed == null) {
      return "";
    }
    long ms = elapsed.toMillis();
    if (ms < 1000) {
      return ms + " ms";
    }
    long seconds = elapsed.toSeconds();
    if (seconds < 60) {
      return seconds + " s";
    }
    if (seconds < 3600) {
      return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    long hours = seconds / 3600;
    return hours < 24
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds % 3600 / 60, seconds % 60)
        : hours / 24 + " d " + hours % 24 + " h";
  }
}

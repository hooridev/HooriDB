package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** The grids of a server monitor's views: its sessions, variables, status counters and locks. */
final class MonitorGrids {

  private MonitorGrids() {}

  /** The sessions, a row each; the monitor's own marked. */
  static El sessions(
      List<ServerSession> shown,
      DataGrid.Selection selection,
      Runnable onSelect,
      Menu.State menu,
      List<Menu.Item> items,
      App<State> app) {
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column("ID", 4, DataGrid.DataType.INTEGER, shown, ServerSession::id)
        .cellIcon(row -> shown.get(row).own()
            ? DataGrid.CellIcon.before(Symbol.PERSON, Icon.Variant.ACCENT)
                .tooltip("The monitor's own session")
            : null));
    columns.add(column("User", 5, DataGrid.DataType.TEXT, shown, ServerSession::user));
    columns.add(column("Client", 8, DataGrid.DataType.TEXT, shown, ServerSession::client));
    columns.add(column("Database", 5, DataGrid.DataType.TEXT, shown, ServerSession::database));
    columns.add(column("State", 6, DataGrid.DataType.TEXT, shown, ServerSession::state));
    columns.add(column("Time", 4, DataGrid.DataType.INTERVAL, shown, x -> elapsed(x.elapsed())));
    columns.add(column("Waiting for", 6, DataGrid.DataType.TEXT, shown, ServerSession::waiting));
    columns.add(column("Statement", 20, DataGrid.DataType.TEXT, shown, ServerSession::statement));
    var options = DataGrid.options()
        .select(selection)
        .onSelect(onSelect)
        .copy(app.clipboard())
        .contextMenu(menu, items);
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("sessions")
        .label("Sessions")
        .grow(1)
        .defaultFocus();
  }

  /** The variables, a row each, with where each comes from where the server tells. */
  static El variables(List<ServerVariable> shown, DataGrid.Selection selection, App<State> app) {
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column("Name", 12, DataGrid.DataType.TEXT, shown, ServerVariable::name));
    columns.add(column("Value", 14, DataGrid.DataType.TEXT, shown, ServerVariable::value));
    columns.add(column("Source", 8, DataGrid.DataType.TEXT, shown, ServerVariable::source));
    var options = DataGrid.options().select(selection).copy(app.clipboard());
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("variables")
        .label("Variables")
        .grow(1)
        .defaultFocus();
  }

  /** The status counters, a row each. */
  static El status(List<Counter> shown, DataGrid.Selection selection, App<State> app) {
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column("Name", 12, DataGrid.DataType.TEXT, shown, Counter::name));
    columns.add(column("Value", 10, DataGrid.DataType.DECIMAL, shown, Counter::value));
    var options = DataGrid.options().select(selection).copy(app.clipboard());
    return DataGrid.dataGrid(columns, shown.size(), options)
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
      List<Block> shown, DataGrid.Selection selection, IntConsumer onActivate, App<State> app) {
    var columns = new ArrayList<DataGrid.Column>();
    columns.add(column("Waiting", 5, DataGrid.DataType.INTEGER, shown, Block::waiting));
    columns.add(column("Blocked by", 5, DataGrid.DataType.INTEGER, shown, Block::blocking));
    columns.add(column("On", 12, DataGrid.DataType.TEXT, shown, Block::object));
    columns.add(column("Mode", 8, DataGrid.DataType.TEXT, shown, Block::mode));
    var options = DataGrid.options().select(selection).onActivate(onActivate).copy(app.clipboard());
    return DataGrid.dataGrid(columns, shown.size(), options)
        .key("locks")
        .label("Locks")
        .grow(1)
        .defaultFocus();
  }

  private static <T> DataGrid.Column column(
      String title, float width, DataGrid.DataType type, List<T> shown, Function<T, String> text) {
    Theme t = Theme.CURRENT.get();
    return new DataGrid.Column(
            title, width * t.size().control(), type, row -> text.apply(shown.get(row)))
        .sortable(false);
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

  /** How long, as "850 ms", "12 s", "4 min 3 s" or "2 h 5 min"; empty where not known. */
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
      return seconds / 60 + " min " + seconds % 60 + " s";
    }
    long hours = seconds / 3600;
    return hours < 24
        ? hours + " h " + seconds % 3600 / 60 + " min"
        : hours / 24 + " d " + hours % 24 + " h";
  }
}

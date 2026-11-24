package dev.hoori.db.app;

import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Symbol;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Rows;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared formatting, filtering and grid navigation for foreign keys in the catalog tree, quick
 * documentation, console editor and table tabs.
 */
final class ForeignKeys {

  /** One outgoing reference from a column: the qualified target column and its non-default rules. */
  record ColumnTarget(String target, String rules) {}

  private ForeignKeys() {}

  /** Keys whose referencing table is {@code table} (self-references included). */
  static List<ForeignKey> outgoing(Table table, List<ForeignKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    return keys.stream().filter(k -> k.table().equals(table)).toList();
  }

  /** Outgoing foreign keys of {@code table} whose referencing columns include {@code column}. */
  static List<ForeignKey> outgoing(Table table, String column, List<ForeignKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    var result = new ArrayList<ForeignKey>();
    for (var key : keys) {
      if (key.table().equals(table) && key.columns().contains(column)) {
        result.add(key);
      }
    }
    return result;
  }

  /** Keys whose referenced table is {@code table} (self-references included). */
  static List<ForeignKey> incoming(Table table, List<ForeignKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    return keys.stream().filter(k -> k.referenced().equals(table)).toList();
  }

  /** Whether {@code column} participates in any outgoing foreign key of its table. */
  static boolean isOutgoingColumn(Column column, List<ForeignKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return false;
    }
    var table = column.table();
    for (var key : keys) {
      if (key.table().equals(table) && key.columns().contains(column.name())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The tree row detail of a column: its type, followed by the referenced column(s) where it is
   * part of one or more outgoing foreign keys.
   */
  static String columnDetail(Column column, List<ForeignKey> keys) {
    var targets =
        columnTargets(column, keys, null).stream().map(ColumnTarget::target).toList();
    return targets.isEmpty() ? column.type() : column.type() + " → " + String.join(", ", targets);
  }

  /** Each outgoing foreign key target of {@code column}, with its non-default rules. */
  static List<ColumnTarget> columnTargets(Column column, List<ForeignKey> keys, Provider provider) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    var table = column.table();
    var result = new ArrayList<ColumnTarget>();
    for (var key : keys) {
      if (!key.table().equals(table)) {
        continue;
      }
      int count = Math.min(key.columns().size(), key.referencedColumns().size());
      for (int i = 0; i < count; i++) {
        if (key.columns().get(i).equals(column.name())) {
          var target = otherTable(table, key.referenced()) + "."
              + key.referencedColumns().get(i);
          result.add(new ColumnTarget(target, rules(key, provider)));
        }
      }
    }
    return result;
  }

  /** Incoming foreign keys whose referenced columns include {@code column}. */
  static List<ForeignKey> referencingColumn(Column column, List<ForeignKey> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    var table = column.table();
    var result = new ArrayList<ForeignKey>();
    for (var key : keys) {
      if (key.referenced().equals(table) && key.referencedColumns().contains(column.name())) {
        result.add(key);
      }
    }
    return result;
  }

  /** The mapping and non-default rules of a foreign key, as seen from {@code table}. */
  static String detail(Table table, ForeignKey key, boolean incoming, Provider provider) {
    return mapping(table, key, incoming) + rules(key, provider);
  }

  /** The column mapping of a foreign key, qualifying the other table when its container differs. */
  static String mapping(Table table, ForeignKey key, boolean incoming) {
    var cols = "(" + String.join(", ", key.columns()) + ")";
    var refCols = "(" + String.join(", ", key.referencedColumns()) + ")";
    if (incoming) {
      return otherTable(table, key.table()) + cols + " → " + refCols;
    }
    return cols + " → " + otherTable(table, key.referenced()) + refCols;
  }

  /** Non-default referential rules formatted as {@code " · ON DELETE CASCADE"}, or empty. */
  static String rules(ForeignKey key, Provider provider) {
    var sb = new StringBuilder();
    appendRule(sb, "ON DELETE", key.onDelete(), provider);
    appendRule(sb, "ON UPDATE", key.onUpdate(), provider);
    return sb.toString();
  }

  /**
   * Context menu items for a table tab's grid: outgoing and incoming foreign key navigation for
   * the current cell and row, followed by Refresh. Reads missing keys on the lane only while the
   * menu is open.
   */
  static List<Menu.Item> gridMenu(State s, TableTab tab, boolean ofDocuments) {
    var refresh = Menu.item("Refresh", () -> tab.refresh(s)).icon(Symbol.ARROW_CLOCKWISE);
    var profile = s.profile(tab.id);
    if (!tab.menu.isOpen()
        || ofDocuments
        || profile == null
        || tab.table.kind() != Table.Kind.TABLE) {
      return List.of(refresh);
    }
    var keys = s.catalog.foreignKeys(tab.id, tab.table);
    if (keys == null) {
      if (s.catalog.connected(tab.id)) {
        tab.readForeignKeys(s);
      }
      return List.of(refresh);
    }
    int gridRow = tab.selection.row();
    int col = tab.selection.column();
    if (!onlyCell(tab, gridRow, col)) {
      return List.of(refresh);
    }
    var outgoing = outgoing(tab.table, tab.columns.get(col).name(), keys);
    var incoming = incoming(tab.table, keys);
    if (outgoing.isEmpty() && incoming.isEmpty()) {
      return List.of(refresh);
    }
    var items = new ArrayList<Menu.Item>();
    if (outgoing.size() == 1) {
      var fk = outgoing.getFirst();
      items.add(Menu.item(
              "Go to " + otherTable(tab.table, fk.referenced()), action(s, tab, gridRow, fk, true))
          .icon(Symbol.ARROW_RIGHT));
    } else if (outgoing.size() > 1) {
      var children = outgoing.stream()
          .map(fk -> Menu.item(
              label(otherTable(tab.table, fk.referenced()), fk), action(s, tab, gridRow, fk, true)))
          .toList();
      items.add(Menu.submenu("Go to", children).icon(Symbol.ARROW_RIGHT));
    }
    if (!incoming.isEmpty()) {
      var children = incoming.stream()
          .map(fk -> Menu.item(
              label(otherTable(tab.table, fk.table()), fk), action(s, tab, gridRow, fk, false)))
          .toList();
      items.add(Menu.submenu("Referenced by", children).icon(Symbol.ARROW_LEFT));
    }
    items.add(Menu.separator());
    items.add(refresh);
    return items;
  }

  /**
   * Whether the cell is in the grid and selected alone. Which cell a press inside a selection of
   * several was on, the grid does not say (rethinkj/ui#577): only the active cell selected alone
   * is surely the pressed one.
   */
  private static boolean onlyCell(TableTab tab, int gridRow, int col) {
    return tab.columns != null
        && gridRow >= 0
        && gridRow < tab.gridRowCount()
        && col >= 0
        && col < tab.columns.size()
        && tab.selection.ranges().equals(List.of(new DataGrid.Range(gridRow, col, gridRow, col)));
  }

  /** A submenu item's label, by both column lists, which tell apart keys between two tables. */
  private static String label(String table, ForeignKey fk) {
    return table + " (" + String.join(", ", fk.columns()) + " → "
        + String.join(", ", fk.referencedColumns()) + ")";
  }

  /** What following the key from the row does, out to the referenced table or in from the other. */
  private static Runnable action(State s, TableTab tab, int gridRow, ForeignKey fk, boolean out) {
    var from = out ? fk.columns() : fk.referencedColumns();
    var to = out ? fk.referencedColumns() : fk.columns();
    var profile = s.profile(tab.id);
    if (profile == null
        || !s.catalog.connected(tab.id)
        || from.isEmpty()
        || from.size() != to.size()) {
      return null;
    }
    var values = valuesFor(tab, gridRow, from);
    if (values.isEmpty()) {
      return null;
    }
    try {
      var where = Rows.where(profile.provider(), to, values);
      return () -> follow(s, tab, out ? fk.referenced() : fk.table(), where);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /** The row's values of the columns, or none where one is not there whole and committed. */
  private static List<Object> valuesFor(TableTab tab, int gridRow, List<String> sourceColumns) {
    int pos = tab.positionOf(gridRow);
    var row = pos < 0 || !tab.loaded(pos) ? null : tab.rowValues(pos);
    if (row == null) {
      return List.of();
    }
    var values = new ArrayList<Object>(sourceColumns.size());
    for (var name : sourceColumns) {
      int index = tab.columnIndex(name);
      if (index < 0 || index >= row.size() || tab.isCellEdited(gridRow, index)) {
        return List.of();
      }
      var value = row.get(index);
      if (value == null || value == Missing.FIELD || !Cells.whole(value)) {
        return List.of();
      }
      values.add(value);
    }
    return values;
  }

  /**
   * Opens {@code target} (reusing its open tab if any, in whichever window) and applies {@code
   * where} through the tab's normal filter path, asking about pending edits first and keeping its
   * {@code ORDER BY}.
   */
  private static void follow(State s, TableTab source, Table target, String where) {
    var open = s.tables(source.id).stream()
        .filter(x ->
            x.table.container().equals(target.container()) && x.table.name().equals(target.name()))
        .findFirst();
    var group = s.tabs.groupOf(source);
    if (group == null) {
      group = s.tabs.main().active();
    }
    TableTab tab;
    if (open.isEmpty()) { // opened here filtered, never read without the filter first
      tab = TableTab.of(s, source.id, target);
      s.tabs.add(group, group.tabs().size(), tab);
    } else {
      tab = open.get();
      group = s.openTable(source.id, target, group); // raises its window
    }
    Frame.focusContentSoon(s, group);
    tab.applyWhere(s, where);
  }

  static String otherTable(Table self, Table other) {
    return other.container().equals(self.container())
        ? other.name()
        : other.container().name() + "." + other.name();
  }

  private static void appendRule(
      StringBuilder sb, String clause, ForeignKey.Rule rule, Provider provider) {
    if (isDefault(rule, provider)) {
      return;
    }
    sb.append(" · ").append(clause).append(' ').append(ruleName(rule));
  }

  private static boolean isDefault(ForeignKey.Rule rule, Provider provider) {
    if (rule == null || rule == ForeignKey.Rule.NO_ACTION) {
      return true;
    }
    return rule == ForeignKey.Rule.RESTRICT
        && provider != null
        && !provider.keepsDocuments()
        && provider.dialect().catalog().restrictIsNoAction();
  }

  private static String ruleName(ForeignKey.Rule rule) {
    return switch (rule) {
      case NO_ACTION -> "NO ACTION";
      case RESTRICT -> "RESTRICT";
      case CASCADE -> "CASCADE";
      case SET_NULL -> "SET NULL";
      case SET_DEFAULT -> "SET DEFAULT";
    };
  }
}

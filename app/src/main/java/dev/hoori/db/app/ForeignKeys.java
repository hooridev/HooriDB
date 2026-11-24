package dev.hoori.db.app;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.connection.Provider;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared formatting and filtering for foreign keys in the catalog tree and quick documentation.
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

  private static String otherTable(Table self, Table other) {
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

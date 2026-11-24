package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.ConditionBuilder;
import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.data.ColumnFilter;
import dev.hoori.db.core.data.Condition;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.document.DocumentFilters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Dialog hosting the kit's {@link ConditionBuilder} for a {@link TableTab}, translating between
 * core's {@link Condition} / {@link ColumnFilter.Operator}, the kit's condition tree, and the
 * grid's header filters where both can express the same flat AND filter.
 */
final class ConditionsDialog {

  static final ConditionBuilder.Clause EMPTY =
      new ConditionBuilder.Clause(ConditionBuilder.Join.AND, List.of(), true);

  boolean open;
  ConditionBuilder.Clause applied = EMPTY;
  ConditionBuilder.Clause draft = EMPTY;
  private final ConditionBuilder.State ui = new ConditionBuilder.State();

  /** Opens the dialog, starting from the applied clause when it is still reflected in the field. */
  void open(boolean hasApplied) {
    open = true;
    draft = hasApplied ? applied : EMPTY;
  }

  void close() {
    open = false;
  }

  /** Builds the modal dialog with the condition builder, Cancel, and Apply. */
  El view(Provider provider, List<Field> availableFields, boolean loadingPaths, Runnable onApply) {
    Theme t = Theme.CURRENT.get();
    var kitFields = kitFields(provider, availableFields);
    El builder = ConditionBuilder.conditionBuilder(kitFields, draft, ui, next -> draft = next);
    El content = col().w(3.2f * t.size().field()).child(builder);
    Runnable close = this::close;
    El cancel = button(SECONDARY, "Cancel", close).key("conditions-cancel");
    El apply = button(PRIMARY, "Apply", onApply).key("conditions-apply");
    Dialog.Options options = Dialog.options().defaultButton(apply);
    if (loadingPaths) {
      options.start(row()
          .align(Style.Align.CENTER)
          .gap(t.space().s())
          .child(Progress.spinner().label("Reading nested fields"))
          .child(Label.label(Label.Variant.MUTED, "Reading nested fields…")));
    }
    return Dialog.dialog("Conditions", content, List.of(cancel, apply), close, options)
        .key("conditions-dialog");
  }

  /** Builds the kit's field definitions from core's fields and the provider's dialect operators. */
  static List<ConditionBuilder.Field> kitFields(Provider provider, List<Field> fields) {
    boolean ofDocuments = Language.of(provider).documents();
    var list = new ArrayList<ConditionBuilder.Field>(fields.size());
    for (var field : fields) {
      var ops = ofDocuments ? DocumentFilters.operators(field) : Rows.operators(provider, field);
      if (ops.isEmpty()) {
        continue;
      }
      var type = field.kind() == null ? DataGrid.DataType.OTHER : Cells.type(field.kind());
      var kitOps = ops.stream().map(ConditionsDialog::operator).toList();
      list.add(new ConditionBuilder.Field(field.name(), field.name(), type, List.of(), kitOps));
    }
    return list;
  }

  private static ConditionBuilder.Operator operator(ColumnFilter.Operator op) {
    return new ConditionBuilder.Operator(op.name(), label(op), arity(op.arity()));
  }

  private static String label(ColumnFilter.Operator op) {
    return switch (op) {
      case EQUALS -> "Equals";
      case NOT_EQUAL -> "Not equal";
      case CONTAINS -> "Contains";
      case STARTS_WITH -> "Starts with";
      case AT_LEAST -> "At least";
      case AT_MOST -> "At most";
      case BETWEEN -> "Between";
      case IS_NULL -> "Is NULL";
      case IS_NOT_NULL -> "Is not NULL";
      case EXISTS -> "Exists";
      case DOES_NOT_EXIST -> "Does not exist";
    };
  }

  private static ConditionBuilder.Arity arity(int arity) {
    return switch (arity) {
      case 0 -> ConditionBuilder.Arity.NONE;
      case 1 -> ConditionBuilder.Arity.ONE;
      case 2 -> ConditionBuilder.Arity.TWO;
      default -> throw new IllegalArgumentException("Unexpected arity: " + arity);
    };
  }

  /** Converts an applicable kit clause into core's {@link Condition} tree. */
  static Condition toCore(ConditionBuilder.Clause clause, Map<String, Field> byName) {
    var children = new ArrayList<Condition>(clause.conditions().size());
    for (var item : clause.conditions()) {
      switch (item) {
        case ConditionBuilder.Comparison c -> {
          var field = byName.get(c.field());
          if (field != null) {
            var op = ColumnFilter.Operator.valueOf(c.operator());
            children.add(new ColumnFilter(field, op.spec(c.values())));
          }
        }
        case ConditionBuilder.Clause nested -> {
          var sub = toCore(nested, byName);
          if (!(sub instanceof Condition.Group g) || !g.conditions().isEmpty()) {
            children.add(sub);
          }
        }
      }
    }
    return new Condition.Group(clause.join() == ConditionBuilder.Join.OR, children);
  }

  /**
   * Translates active header filters into a flat AND builder clause when every header filter can
   * be expressed by the builder's operators; otherwise {@link #EMPTY}.
   */
  static ConditionBuilder.Clause fromHeaderFilters(
      List<TableTab.HeaderFilter> filters, Provider provider, Function<String, Field> fieldByName) {
    if (filters.isEmpty()) {
      return EMPTY;
    }
    boolean ofDocuments = Language.of(provider).documents();
    var list = new ArrayList<ConditionBuilder.Condition>(filters.size());
    for (var f : filters) {
      var field = fieldByName.apply(f.column());
      if (field == null) {
        return EMPTY;
      }
      var comp = comparisonOf(f.column(), f.spec());
      if (comp == null) {
        return EMPTY;
      }
      var ops = ofDocuments ? DocumentFilters.operators(field) : Rows.operators(provider, field);
      if (!ops.contains(ColumnFilter.Operator.valueOf(comp.operator()))) {
        return EMPTY;
      }
      list.add(comp);
    }
    return new ConditionBuilder.Clause(ConditionBuilder.Join.AND, list, true);
  }

  private static ConditionBuilder.Comparison comparisonOf(
      String column, DataGrid.Filter.Spec spec) {
    return switch (spec) {
      case DataGrid.Filter.TextMatch(var kind, var value) ->
        switch (kind) {
          case CONTAINS ->
            new ConditionBuilder.Comparison(
                column, ColumnFilter.Operator.CONTAINS.name(), List.of(value), true);
          case STARTS_WITH ->
            new ConditionBuilder.Comparison(
                column, ColumnFilter.Operator.STARTS_WITH.name(), List.of(value), true);
          case EQUALS ->
            new ConditionBuilder.Comparison(
                column, ColumnFilter.Operator.EQUALS.name(), List.of(value), true);
          default -> null;
        };
      case DataGrid.Filter.Between(var from, var to) -> {
        var lo = from.strip();
        var hi = to.strip();
        if (!lo.isEmpty() && !hi.isEmpty()) {
          yield new ConditionBuilder.Comparison(
              column, ColumnFilter.Operator.BETWEEN.name(), List.of(lo, hi), true);
        }
        if (!lo.isEmpty()) {
          yield new ConditionBuilder.Comparison(
              column, ColumnFilter.Operator.AT_LEAST.name(), List.of(lo), true);
        }
        if (!hi.isEmpty()) {
          yield new ConditionBuilder.Comparison(
              column, ColumnFilter.Operator.AT_MOST.name(), List.of(hi), true);
        }
        yield null;
      }
      case DataGrid.Filter.OneOf(var values) ->
        values.size() == 1
            ? new ConditionBuilder.Comparison(
                column, ColumnFilter.Operator.EQUALS.name(), List.of(values.getFirst()), true)
            : null;
      case DataGrid.Filter.NullCheck(boolean isNull) ->
        new ConditionBuilder.Comparison(
            column,
            (isNull ? ColumnFilter.Operator.IS_NULL : ColumnFilter.Operator.IS_NOT_NULL).name(),
            List.of(),
            true);
      case DataGrid.Filter.RegexMatch _ -> null;
    };
  }

  /**
   * Translates an applicable builder clause into header filters when it is a flat AND (or single
   * comparison) of at most one condition per filterable top-level column whose operators map to
   * header filter specs; otherwise empty.
   */
  static List<TableTab.HeaderFilter> toHeaderFilters(
      ConditionBuilder.Clause applicable,
      Predicate<String> filterableColumn,
      Function<String, Field> fieldByName) {
    if (applicable.conditions().isEmpty()) {
      return List.of();
    }
    if (applicable.join() == ConditionBuilder.Join.OR && applicable.conditions().size() > 1) {
      return List.of();
    }
    var seen = new HashSet<String>();
    var result = new ArrayList<TableTab.HeaderFilter>(applicable.conditions().size());
    for (var item : applicable.conditions()) {
      if (!(item instanceof ConditionBuilder.Comparison c)) {
        return List.of();
      }
      if (!filterableColumn.test(c.field()) || !seen.add(c.field())) {
        return List.of();
      }
      var field = fieldByName.apply(c.field());
      if (field == null) {
        return List.of();
      }
      var spec = headerSpecOf(field, ColumnFilter.Operator.valueOf(c.operator()), c.values());
      if (spec == null) {
        return List.of();
      }
      result.add(new TableTab.HeaderFilter(c.field(), spec));
    }
    return List.copyOf(result);
  }

  private static DataGrid.Filter.Spec headerSpecOf(
      Field field, ColumnFilter.Operator op, List<String> values) {
    var type = field.kind() == null ? DataGrid.DataType.OTHER : Cells.type(field.kind());
    boolean ordered = type == DataGrid.DataType.INTEGER
        || type == DataGrid.DataType.DECIMAL
        || type == DataGrid.DataType.DATE
        || type == DataGrid.DataType.TIME
        || type == DataGrid.DataType.DATE_TIME;
    return switch (op) {
      case EQUALS ->
        type == DataGrid.DataType.BOOLEAN
            ? new DataGrid.Filter.OneOf(List.of(values.getFirst()))
            : new DataGrid.Filter.TextMatch(DataGrid.Filter.Kind.EQUALS, values.getFirst());
      case CONTAINS ->
        new DataGrid.Filter.TextMatch(DataGrid.Filter.Kind.CONTAINS, values.getFirst());
      case STARTS_WITH ->
        new DataGrid.Filter.TextMatch(DataGrid.Filter.Kind.STARTS_WITH, values.getFirst());
      case AT_LEAST -> ordered ? new DataGrid.Filter.Between(values.getFirst(), "") : null;
      case AT_MOST -> ordered ? new DataGrid.Filter.Between("", values.getFirst()) : null;
      case BETWEEN -> ordered ? new DataGrid.Filter.Between(values.get(0), values.get(1)) : null;
      case IS_NULL -> new DataGrid.Filter.NullCheck(true);
      case IS_NOT_NULL -> new DataGrid.Filter.NullCheck(false);
      case NOT_EQUAL, EXISTS, DOES_NOT_EXIST -> null;
    };
  }
}

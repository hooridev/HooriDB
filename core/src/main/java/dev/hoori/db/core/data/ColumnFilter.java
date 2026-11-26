package dev.hoori.db.core.data;

import java.util.List;
import java.util.Objects;

/**
 * A filter on one column or field, translated from a grid's header filter or condition builder so
 * core can build a SQL {@code WHERE} condition or a MongoDB filter document without depending on
 * the UI kit.
 */
public record ColumnFilter(Field field, Spec spec) implements Condition {

  public ColumnFilter {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(spec, "spec");
  }

  /** What a column filter keeps. */
  public sealed interface Spec permits Text, Regex, Between, OneOf, Null, Not, Exists {}

  /** Operators offered in the condition builder, each knowing its arity and {@link Spec}. */
  public enum Operator {
    EQUALS(1),
    NOT_EQUAL(1),
    CONTAINS(1),
    STARTS_WITH(1),
    AT_LEAST(1),
    AT_MOST(1),
    BETWEEN(2),
    IS_NULL(0),
    IS_NOT_NULL(0),
    EXISTS(0),
    DOES_NOT_EXIST(0);

    private final int arity;

    Operator(int arity) {
      this.arity = arity;
    }

    /** How many values this operator takes: 0, 1, or 2. */
    public int arity() {
      return arity;
    }

    /** Builds the {@link Spec} for this operator and its values. */
    public Spec spec(List<String> values) {
      Objects.requireNonNull(values, "values");
      if (values.size() != arity) {
        throw new IllegalArgumentException(
            name() + " expects " + arity + " value(s), got " + values.size());
      }
      return switch (this) {
        case EQUALS -> new Text(Match.EQUALS, values.getFirst());
        case NOT_EQUAL -> new Not(new Text(Match.EQUALS, values.getFirst()));
        case CONTAINS -> new Text(Match.CONTAINS, values.getFirst());
        case STARTS_WITH -> new Text(Match.STARTS_WITH, values.getFirst());
        case AT_LEAST -> new Between(values.getFirst(), "");
        case AT_MOST -> new Between("", values.getFirst());
        case BETWEEN -> new Between(values.get(0), values.get(1));
        case IS_NULL -> new Null(true);
        case IS_NOT_NULL -> new Null(false);
        case EXISTS -> new Exists(true);
        case DOES_NOT_EXIST -> new Exists(false);
      };
    }
  }

  /** How a {@link Text} filter compares. */
  public enum Match {
    CONTAINS,
    STARTS_WITH,
    /** The value, whatever the case. */
    EQUALS,
    /** The value, case and all. */
    EXACTLY
  }

  /** Text matching {@code value} by containment, prefix, or equality. */
  public record Text(Match match, String value) implements Spec {
    public Text {
      Objects.requireNonNull(match, "match");
      value = value == null ? "" : value;
    }
  }

  /** Regular expression matching {@code expression}, case-insensitively. */
  public record Regex(String expression) implements Spec {
    public Regex {
      Objects.requireNonNull(expression, "expression");
    }
  }

  /** Inclusive range from {@code from} to {@code to}; either end may be blank for an open bound. */
  public record Between(String from, String to) implements Spec {
    public Between {
      from = from == null ? "" : from;
      to = to == null ? "" : to;
    }
  }

  /** Membership in {@code values}. */
  public record OneOf(List<String> values) implements Spec {
    public OneOf {
      values = List.copyOf(values);
    }
  }

  /** {@code IS NULL} when {@code isNull} is true, else {@code IS NOT NULL}. */
  public record Null(boolean isNull) implements Spec {}

  /** Negation of {@code spec}: SQL {@code NOT (…)}, MongoDB {@code $ne} or {@code $not}. */
  public record Not(Spec spec) implements Spec {
    public Not {
      Objects.requireNonNull(spec, "spec");
    }
  }

  /** Whether the field exists in a document ({@code $exists}); refused by SQL servers. */
  public record Exists(boolean exists) implements Spec {}
}

package dev.hoori.db.core.data;

import java.util.List;
import java.util.Objects;

/**
 * A filter on one column or field, translated from a grid's header filter so core can build a SQL
 * {@code WHERE} condition or a MongoDB filter document without depending on the UI kit.
 */
public record ColumnFilter(Field field, Spec spec) {

  public ColumnFilter {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(spec, "spec");
  }

  /** What a column filter keeps. */
  public sealed interface Spec permits Text, Regex, Between, OneOf, Null {}

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
}

package dev.hoori.db.core.find;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What is searched for: a text, how it matches, and whether case counts. In names and definitions
 * HooriDB matches it itself, with a pattern as Java's regular expressions read it; in data the
 * server does, with a pattern as its own read it, and where the dialect says that the case
 * follows each column's collation, {@code caseSensitive} says nothing there.
 */
public record Search(String text, Match match, boolean caseSensitive) {

  /** How the text matches. */
  public enum Match {
    /** The text is in the value, anywhere. */
    CONTAINS,
    /** The value is the text, whole. */
    EQUALS,
    /** The text is a regular expression, found anywhere in the value. */
    PATTERN
  }

  /** At most this many rows of a table count as hits, before a search there stops. */
  public static final int LIMIT = 1_000;

  /**
   * @throws IllegalArgumentException where {@link #problem} says why it cannot be searched for
   */
  public Search {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(match, "match");
    var problem = problem(text, match);
    if (problem != null) {
      throw new IllegalArgumentException(problem);
    }
  }

  /** Why the text cannot be searched for so, as a sentence; null where it can. */
  public static String problem(String text, Match match) {
    if (text.isEmpty()) {
      return "Enter what to find.";
    }
    if (match == Match.PATTERN) {
      try {
        Pattern.compile(text);
      } catch (PatternSyntaxException e) {
        return "The pattern is not valid: " + e.getDescription() + ".";
      }
    }
    return null;
  }

  /** The text as a pattern of Java's, which finds where it matches as {@link #match} says. */
  public Pattern pattern() {
    var regex = switch (match) {
      case CONTAINS -> Pattern.quote(text);
      case EQUALS -> "^" + Pattern.quote(text) + "$";
      case PATTERN -> text;
    };
    return Pattern.compile(
        regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  /** Where it is found first in the text, or empty where it is not. */
  public Optional<Place> in(String value) {
    if (value == null) {
      return Optional.empty();
    }
    Matcher m = pattern().matcher(value);
    return m.find() ? Optional.of(new Place(m.start(), m.end())) : Optional.empty();
  }

  /** A part of a text, from its start up to its end. */
  public record Place(int start, int end) {}

  /**
   * The text as a number, where it is one and the match is not a pattern: the number columns hold
   * then, which they are searched for as equal to.
   */
  public Optional<BigDecimal> number() {
    if (match == Match.PATTERN) {
      return Optional.empty();
    }
    var trimmed = text.strip();
    if (trimmed.isEmpty() || !trimmed.matches("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?")) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BigDecimal(trimmed));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}

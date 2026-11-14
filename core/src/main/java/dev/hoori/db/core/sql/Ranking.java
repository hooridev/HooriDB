package dev.hoori.db.core.sql;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/** The order of completions: what fits what was typed best comes first. */
public final class Ranking {

  private Ranking() {}

  /**
   * The candidates whose names hold what was typed, whatever the case, all of them for nothing
   * typed: first those that start with it, then those of the statement, such as the columns of
   * its tables, then the rest; among equals by name, whatever the case, then as written.
   */
  public static <T> List<T> rank(
      Collection<T> candidates, Function<T, String> name, Predicate<T> ofStatement, String typed) {
    var lower = typed.toLowerCase(Locale.ROOT);
    Predicate<T> starts = c -> name.apply(c).regionMatches(true, 0, typed, 0, typed.length());
    return candidates.stream()
        .filter(c -> name.apply(c).toLowerCase(Locale.ROOT).contains(lower))
        .sorted(Comparator.comparing((T c) -> !starts.test(c))
            .thenComparing(c -> !ofStatement.test(c))
            .thenComparing(name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(name))
        .toList();
  }
}

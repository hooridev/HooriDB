package dev.hoori.db.core.sql;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** The order of completions: what fits what was typed best comes first. */
public final class Ranking {

  /** The priority of what ranks before every other match, such as a join from a foreign key. */
  public static final int FIRST = 2;

  private Ranking() {}

  /**
   * The candidates whose names hold what was typed, whatever the case, all of them for nothing
   * typed: first those of priority {@link #FIRST}, then those that start with it, then by
   * priority, highest first, such as the columns of the statement's tables before the rest; among
   * equals by name, whatever the case, then as written.
   */
  public static <T> List<T> rank(
      Collection<T> candidates, Function<T, String> name, ToIntFunction<T> priority, String typed) {
    var lower = typed.toLowerCase(Locale.ROOT);
    Predicate<T> starts = c -> name.apply(c).regionMatches(true, 0, typed, 0, typed.length());
    return candidates.stream()
        .filter(c -> name.apply(c).toLowerCase(Locale.ROOT).contains(lower))
        .sorted(Comparator.comparing((T c) -> priority.applyAsInt(c) < FIRST)
            .thenComparing(c -> !starts.test(c))
            .thenComparing(Comparator.comparingInt(priority).reversed())
            .thenComparing(name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(name))
        .toList();
  }
}

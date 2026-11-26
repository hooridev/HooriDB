package dev.hoori.db.core.sync;

import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Index;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Change;
import dev.hoori.db.core.data.Changes;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.KeyedRead;
import dev.hoori.db.core.data.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * How the rows of two tables are compared: their columns matched by name, and the key, of columns
 * both have, whose values pair a row of the source with the row of the target. The key is the
 * source's primary key, or else one of its unique keys, whose columns are all {@code NOT NULL},
 * that the target has too, as its primary key or a unique key of the same columns.
 *
 * <p>Once their reads say what kinds the columns' values are, {@link #read} leaves out the matched
 * columns whose kinds the two read differently, as a number and a text, which are then neither
 * compared nor written. Numbers of any kind compare by their value, and texts of {@link Kind#TEXT},
 * {@link Kind#JSON} and {@link Kind#OTHER} by their text.
 *
 * @param sourceColumns all the source's columns, in their order
 * @param targetColumns all the target's columns, in their order
 * @param columns the columns compared: of the same name in both, the key's among them, in the
 *     source's order
 * @param key the key's columns, in its order
 * @param keyName what the key is, as the user is told: {@code primary key}, or a unique key's name
 * @param unlike the names of the columns left out of {@code columns} as their kinds differ
 */
public record Pairing(
    Table source,
    List<Column> sourceColumns,
    Table target,
    List<Column> targetColumns,
    List<Pair> columns,
    List<Pair> key,
    String keyName,
    List<String> unlike) {

  /** What a primary key is called, as the user is told which key pairs the rows. */
  public static final String PRIMARY_KEY = "primary key";

  /** A column of the source and the column of the target it is compared with, by index. */
  public record Pair(int source, int target) {}

  public Pairing {
    sourceColumns = List.copyOf(sourceColumns);
    targetColumns = List.copyOf(targetColumns);
    columns = List.copyOf(columns);
    key = List.copyOf(key);
    unlike = List.copyOf(unlike);
  }

  /** What pairing two tables found: how to compare them, or why they cannot be. */
  public sealed interface Found {}

  /** They can be compared so. */
  public record Paired(Pairing pairing) implements Found {}

  /** They cannot be compared, for the reason, in words for the user. */
  public record Unpaired(String why) implements Found {}

  /**
   * How the rows of the tables are compared, or why they cannot be: they are the same table, or
   * share no key whose values name each row once.
   *
   * @param sourceIndexes the source's indexes, as the catalog reads them
   * @param targetIndexes the target's indexes
   */
  public static Found of(
      Table source,
      List<Column> sourceColumns,
      List<Index> sourceIndexes,
      Table target,
      List<Column> targetColumns,
      List<Index> targetIndexes) {
    if (source.equals(target)) {
      return new Unpaired("The source and the target are the same table.");
    }
    var targets = new LinkedHashMap<String, Integer>();
    for (int i = 0; i < targetColumns.size(); i++) {
      targets.put(targetColumns.get(i).name(), i);
    }
    var columns = new ArrayList<Pair>();
    for (int i = 0; i < sourceColumns.size(); i++) {
      var at = targets.get(sourceColumns.get(i).name());
      if (at != null) {
        columns.add(new Pair(i, at));
      }
    }
    // A key of the target of the same columns, in any order, names each row as the source's does.
    var theirs = keys(targetColumns, targetIndexes);
    for (var mine : keys(sourceColumns, sourceIndexes).entrySet()) {
      var names = mine.getValue();
      var shared = theirs.values().stream()
          .anyMatch(other -> other.size() == names.size() && other.containsAll(names));
      if (shared) {
        var key = names.stream()
            .map(name -> new Pair(indexOf(sourceColumns, name), targets.get(name)))
            .toList();
        return new Paired(new Pairing(
            source, sourceColumns, target, targetColumns, columns, key, mine.getKey(), List.of()));
      }
    }
    return new Unpaired(source.name() + " and " + target.name() + " share no primary key or"
        + " unique key of columns that are not null, by the columns' names, so their rows cannot"
        + " be paired.");
  }

  // The keys whose values name each row once: the primary key, then each unique key of columns
  // that are not null, by name; each as the names of its columns in its order.
  private static Map<String, List<String>> keys(List<Column> columns, List<Index> indexes) {
    var keys = new LinkedHashMap<String, List<String>>();
    var primary = columns.stream()
        .filter(column -> column.keyPosition() > 0)
        .sorted(Comparator.comparingInt(Column::keyPosition))
        .map(Column::name)
        .toList();
    if (!primary.isEmpty()) {
      keys.put(PRIMARY_KEY, primary);
    }
    var sorted = new TreeMap<String, List<String>>();
    for (var index : indexes) {
      boolean whole = !index.columns().isEmpty()
          && index.columns().stream()
              .allMatch(name -> columns.stream()
                  .anyMatch(column -> column.name().equals(name) && !column.nullable()));
      if (index.unique() && !index.primary() && whole) {
        sorted.put(index.name(), index.columns());
      }
    }
    keys.putAll(sorted);
    return keys;
  }

  private static int indexOf(List<Column> columns, String name) {
    return IntStream.range(0, columns.size())
        .filter(i -> columns.get(i).name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** The indexes of the key's columns among the source's, in the key's order. */
  public List<Integer> sourceKey() {
    return key.stream().map(Pair::source).toList();
  }

  /** The indexes of the key's columns among the target's, in the key's order. */
  public List<Integer> targetKey() {
    return key.stream().map(Pair::target).toList();
  }

  /** The names of the source's columns that the target does not have, which are not compared. */
  public List<String> onlyInSource() {
    var matched = columns.stream().map(Pair::source).toList();
    return IntStream.range(0, sourceColumns.size())
        .filter(
            i -> !matched.contains(i) && !unlike.contains(sourceColumns.get(i).name()))
        .mapToObj(i -> sourceColumns.get(i).name())
        .toList();
  }

  /**
   * The names of the target's columns that the source does not have: an update leaves them as they
   * are, and a new row takes their defaults.
   */
  public List<String> onlyInTarget() {
    var matched = columns.stream().map(Pair::target).toList();
    return IntStream.range(0, targetColumns.size())
        .filter(
            i -> !matched.contains(i) && !unlike.contains(targetColumns.get(i).name()))
        .mapToObj(i -> targetColumns.get(i).name())
        .toList();
  }

  /**
   * As the reads of both tables say their columns' kinds, in the columns' order: the columns of
   * kinds that compare differently are left out.
   *
   * @throws CompareException if a column of the key is of such kinds, which order apart, or the
   *     reads have other columns than the tables had
   */
  public Pairing read(List<Field> source, List<Field> target) throws CompareException {
    if (source.size() != sourceColumns.size() || target.size() != targetColumns.size()) {
      throw new CompareException(
          "The columns of the tables changed since they were read: compare them again.");
    }
    var compared = new ArrayList<Pair>();
    var left = new ArrayList<>(unlike);
    for (var pair : columns) {
      var mine = source.get(pair.source()).kind();
      var theirs = target.get(pair.target()).kind();
      if (alike(mine, theirs)) {
        compared.add(pair);
      } else if (key.contains(pair)) {
        throw new CompareException("Column " + sourceColumns.get(pair.source()).name()
            + " of the key holds " + what(mine) + " in the source and " + what(theirs)
            + " in the target, which sort apart, so the rows cannot be paired.");
      } else {
        left.add(sourceColumns.get(pair.source()).name());
      }
    }
    return new Pairing(
        this.source, sourceColumns, this.target, targetColumns, compared, key, keyName, left);
  }

  // Values of these kinds compare and sort alike.
  private static boolean alike(Kind a, Kind b) {
    return a == b || numeric(a) && numeric(b) || KeyedRead.textual(a) && KeyedRead.textual(b);
  }

  private static boolean numeric(Kind kind) {
    return kind == Kind.INTEGER || kind == Kind.DECIMAL;
  }

  private static String what(Kind kind) {
    return switch (kind) {
      case TEXT, JSON, OTHER -> "text";
      case INTEGER, DECIMAL -> "numbers";
      case BOOLEAN -> "booleans";
      case DATE -> "dates";
      case TIME -> "times";
      case DATE_TIME -> "dates and times";
      case DATE_TIME_ZONED -> "moments";
      case BINARY -> "binary data";
    };
  }

  /**
   * The target's columns with the key's as its primary key, as {@link Changes} names a row by it:
   * a unique key that pairs the rows names the target's row as well as its primary key does.
   */
  public List<Column> keyedTarget() {
    var keyed = new ArrayList<Column>(targetColumns.size());
    for (int i = 0; i < targetColumns.size(); i++) {
      var column = targetColumns.get(i);
      int position = targetKey().indexOf(i) + 1;
      keyed.add(
          new Column(column.table(), column.name(), column.type(), column.nullable(), position));
    }
    return keyed;
  }

  /**
   * The change of the target that the choice takes for the difference, which makes the row equal
   * to the source's in the compared columns: a new row with the source's values, an update of the
   * cells that differ, or the row's deletion; empty where the choice does not take it.
   */
  public Optional<Change> change(Difference difference, Choice choice) {
    if (!choice.takes(difference)) {
      return Optional.empty();
    }
    return Optional.of(
        switch (difference) {
          case Difference.OnlyInSource(var row) -> {
            var values = new TreeMap<Integer, Object>();
            columns.forEach(pair -> values.put(pair.target(), row.get(pair.source())));
            yield new Change.Insert(values);
          }
          case Difference.OnlyInTarget(var row) -> new Change.Delete(row);
          case Difference.Different(var mine, var theirs, var cells) -> {
            var values = new TreeMap<Integer, Object>();
            cells.forEach(pair -> values.put(pair.target(), mine.get(pair.source())));
            yield new Change.Update(theirs, values);
          }
        });
  }

  /** The changes of the target, in their order, as {@link Changes} writes them. */
  public Changes changes(List<Change> changes) {
    return new Changes(target, keyedTarget(), changes);
  }
}

package dev.hoori.db.core.transfer;

import dev.hoori.db.core.catalog.ForeignKey;
import dev.hoori.db.core.catalog.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The order in which tables are written, so that a row's referenced rows are there before it. */
public final class Order {

  private Order() {}

  /**
   * The tables, each after the tables it refers to by the foreign keys, and otherwise in the order
   * given; a key to a table that is not among them, or to its own table, does not count. Tables
   * that refer to each other in a circle, which no order serves, keep their order given, after the
   * others they refer to.
   */
  public static List<Table> of(List<Table> tables, Collection<ForeignKey> keys) {
    var among = new LinkedHashSet<>(tables);
    var refers = new HashMap<Table, Set<Table>>();
    for (var key : keys) {
      if (among.contains(key.table())
          && among.contains(key.referenced())
          && !key.table().equals(key.referenced())) {
        refers.computeIfAbsent(key.table(), t -> new HashSet<>()).add(key.referenced());
      }
    }
    var ordered = new ArrayList<Table>();
    var placed = new HashSet<Table>();
    while (ordered.size() < among.size()) {
      var next = among.stream()
          .filter(table -> !placed.contains(table))
          .filter(table -> placed.containsAll(refers.getOrDefault(table, Set.of())))
          .findFirst()
          // A circle: the first left goes, and what it refers to in the circle follows.
          .orElseGet(
              () -> among.stream().filter(t -> !placed.contains(t)).findFirst().get());
      ordered.add(next);
      placed.add(next);
    }
    return List.copyOf(ordered);
  }
}

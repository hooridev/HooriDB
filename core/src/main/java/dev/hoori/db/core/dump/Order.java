package dev.hoori.db.core.dump;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The order in which an export creates objects: each after what it needs and after the tables its
 * foreign keys refer to, so that rows go in where the keys are checked; otherwise sequences, then
 * routines, tables, views and triggers, each in the order given. Where objects need each other in a
 * circle, as two tables whose keys refer to each other, the circle is broken at the object that
 * comes first otherwise; a foreign key is added after all tables, so that tables never wait for
 * one.
 */
final class Order {

  private Order() {}

  /** Where an object comes where nothing it needs says otherwise. */
  static int rank(Item.Kind kind) {
    return switch (kind) {
      case SEQUENCE -> 0;
      case FUNCTION, PROCEDURE -> 1;
      case TABLE -> 2;
      case VIEW -> 3;
      case TRIGGER -> 4;
    };
  }

  /** The definitions in the order that creates them; what they need but are not given is there. */
  static List<Definition> sorted(List<Definition> definitions) {
    var index = new HashMap<Item, Integer>();
    for (var i = 0; i < definitions.size(); i++) {
      index.putIfAbsent(definitions.get(i).item(), i);
    }
    Comparator<Integer> first = Comparator.<Integer>comparingInt(
            i -> rank(definitions.get(i).item().kind()))
        .thenComparingInt(i -> i);
    // For each object what it waits for, and the objects given that wait for it.
    var before = new ArrayList<TreeSet<Integer>>();
    var waiting = new HashMap<Integer, List<Integer>>();
    for (var i = 0; i < definitions.size(); i++) {
      var definition = definitions.get(i);
      var waits = new TreeSet<>(first);
      for (var item : both(definition.needs(), definition.refers())) {
        var at = index.get(item);
        if (at != null && at != i) {
          waits.add(at);
        }
      }
      before.add(waits);
      for (var at : waits) {
        waiting.computeIfAbsent(at, k -> new ArrayList<>()).add(i);
      }
    }
    var ready = new TreeSet<>(first);
    var left = new TreeSet<>(first);
    for (var i = 0; i < definitions.size(); i++) {
      (before.get(i).isEmpty() ? ready : left).add(i);
    }
    var sorted = new ArrayList<Definition>(definitions.size());
    while (!ready.isEmpty() || !left.isEmpty()) {
      int next = ready.isEmpty() ? broken(left.first(), before, first) : ready.first();
      ready.remove(next);
      left.remove(next);
      sorted.add(definitions.get(next));
      for (var after : waiting.getOrDefault(next, List.of())) {
        var waits = before.get(after);
        if (waits.remove(next) && waits.isEmpty() && left.remove(after)) {
          ready.add(after);
        }
      }
    }
    return List.copyOf(sorted);
  }

  // Where a circle breaks: following what the object waits for, first by first, until it comes
  // round, at the first of the circle.
  private static int broken(int start, List<TreeSet<Integer>> before, Comparator<Integer> first) {
    var path = new ArrayList<Integer>();
    var at = start;
    while (!path.contains(at)) {
      path.add(at);
      at = before.get(at).first();
    }
    return path.subList(path.indexOf(at), path.size()).stream().min(first).orElseThrow();
  }

  private static Set<Item> both(Set<Item> needs, Set<Item> refers) {
    if (refers.isEmpty()) {
      return needs;
    }
    var all = new HashSet<>(needs);
    all.addAll(refers);
    return all;
  }
}

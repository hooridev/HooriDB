package dev.hoori.db.core.dump;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The order in which an export creates objects: each after what it needs and after the tables its
 * foreign keys refer to, so that rows go in where the keys are checked; otherwise sequences, then
 * routines, tables, views and triggers, each in the order given. Where objects wait for each other
 * in a circle, as two tables whose keys refer to each other, the circle is broken at the object
 * that comes first otherwise of those that wait in it only for what their keys refer to, as what
 * an object needs must be there before it; a foreign key is added after all tables, so that tables
 * never wait for one.
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
    return new Sorting(definitions).sorted();
  }

  /**
   * The tables among the definitions whose foreign keys refer back to themselves, straight or
   * through those of others among them: no order of their rows suits keys that are checked.
   */
  static Set<Item> circular(List<Definition> definitions) {
    var refers = new HashMap<Item, Set<Item>>();
    for (var definition : definitions) {
      refers.put(definition.item(), definition.refers());
    }
    var circular = new HashSet<Item>();
    for (var item : refers.keySet()) {
      if (reaches(item, refers)) {
        circular.add(item);
      }
    }
    return circular;
  }

  private static boolean reaches(Item item, Map<Item, Set<Item>> refers) {
    var seen = new HashSet<Item>();
    var next = new ArrayDeque<>(refers.get(item));
    while (!next.isEmpty()) {
      var at = next.poll();
      if (at.equals(item)) {
        return true;
      }
      if (seen.add(at)) {
        next.addAll(refers.getOrDefault(at, Set.of()));
      }
    }
    return false;
  }

  // The definitions by their place in the list given, and for each what it still waits for.
  private static final class Sorting {

    private final List<Definition> definitions;
    private final Comparator<Integer> first;
    private final List<TreeSet<Integer>> needs = new ArrayList<>();
    private final List<TreeSet<Integer>> refers = new ArrayList<>();
    private final Map<Integer, List<Integer>> waiting = new HashMap<>();

    Sorting(List<Definition> definitions) {
      this.definitions = definitions;
      this.first = Comparator.<Integer>comparingInt(
              i -> rank(definitions.get(i).item().kind()))
          .thenComparingInt(i -> i);
      var index = new HashMap<Item, Integer>();
      for (var i = 0; i < definitions.size(); i++) {
        index.putIfAbsent(definitions.get(i).item(), i);
      }
      for (var i = 0; i < definitions.size(); i++) {
        var definition = definitions.get(i);
        needs.add(given(i, definition.needs(), index, Set.of()));
        refers.add(given(i, definition.refers(), index, needs.get(i)));
        for (var at : needs.get(i)) {
          waiting.computeIfAbsent(at, k -> new ArrayList<>()).add(i);
        }
        for (var at : refers.get(i)) {
          waiting.computeIfAbsent(at, k -> new ArrayList<>()).add(i);
        }
      }
    }

    // Of the items, the places of those given but the one at i and those already there.
    private TreeSet<Integer> given(
        int i, Set<Item> items, Map<Item, Integer> index, Set<Integer> already) {
      var at = new TreeSet<>(first);
      for (var item : items) {
        var place = index.get(item);
        if (place != null && place != i && !already.contains(place)) {
          at.add(place);
        }
      }
      return at;
    }

    private boolean waits(int i) {
      return !needs.get(i).isEmpty() || !refers.get(i).isEmpty();
    }

    List<Definition> sorted() {
      var ready = new TreeSet<>(first);
      var left = new TreeSet<>(first);
      for (var i = 0; i < definitions.size(); i++) {
        (waits(i) ? left : ready).add(i);
      }
      var sorted = new ArrayList<Definition>(definitions.size());
      while (!ready.isEmpty() || !left.isEmpty()) {
        int next = ready.isEmpty() ? broken(left.first()) : ready.first();
        ready.remove(next);
        left.remove(next);
        sorted.add(definitions.get(next));
        for (var after : waiting.getOrDefault(next, List.of())) {
          needs.get(after).remove(next);
          refers.get(after).remove(next);
          if (!waits(after) && left.remove(after)) {
            ready.add(after);
          }
        }
      }
      return List.copyOf(sorted);
    }

    // Where a circle breaks: following what each object waits for, what it needs before what its
    // keys refer to, first by first, until it comes round; there at the first that waits only for
    // what its keys refer to, or at the first of all where each needs another.
    private int broken(int start) {
      var path = new ArrayList<Integer>();
      var seen = new HashMap<Integer, Integer>();
      var at = start;
      while (!seen.containsKey(at)) {
        seen.put(at, path.size());
        path.add(at);
        at = needs.get(at).isEmpty() ? refers.get(at).first() : needs.get(at).first();
      }
      var circle = path.subList(seen.get(at), path.size());
      return circle.stream()
          .filter(i -> needs.get(i).isEmpty())
          .min(first)
          .orElseGet(() -> circle.stream().min(first).orElseThrow());
    }
  }
}

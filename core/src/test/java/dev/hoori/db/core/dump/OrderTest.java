package dev.hoori.db.core.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.hoori.db.core.catalog.Database;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final Database DATABASE = new Database("shop", false);

  @Test
  void eachComesAfterWhatItNeedsAndTheTablesItsKeysReferTo() {
    var top = definition(Item.Kind.VIEW, "a_top", Set.of(item(Item.Kind.VIEW, "z_base")), Set.of());
    var base = definition(Item.Kind.VIEW, "z_base", Set.of(item(Item.Kind.TABLE, "t")), Set.of());
    var child = definition(Item.Kind.TABLE, "child", Set.of(), Set.of(item(Item.Kind.TABLE, "t")));
    var table = definition(Item.Kind.TABLE, "t", Set.of(), Set.of());
    var function = definition(Item.Kind.FUNCTION, "f", Set.of(), Set.of());
    var trigger =
        definition(Item.Kind.TRIGGER, "on_t", Set.of(item(Item.Kind.TABLE, "t")), Set.of());
    assertEquals(
        List.of("f", "t", "child", "z_base", "a_top", "on_t"),
        names(Order.sorted(List.of(top, base, trigger, child, table, function))));
  }

  @Test
  void aCircleBreaksAtWhatComesFirstOtherwiseAndTheRestFollows() {
    var a = definition(Item.Kind.TABLE, "a", Set.of(), Set.of(item(Item.Kind.TABLE, "b")));
    var b = definition(Item.Kind.TABLE, "b", Set.of(), Set.of(item(Item.Kind.TABLE, "a")));
    var c = definition(Item.Kind.TABLE, "c", Set.of(), Set.of(item(Item.Kind.TABLE, "b")));
    var view = definition(
        Item.Kind.VIEW,
        "v",
        Set.of(item(Item.Kind.TABLE, "a"), item(Item.Kind.TABLE, "c")),
        Set.of());
    assertEquals(List.of("a", "b", "c", "v"), names(Order.sorted(List.of(view, c, a, b))));
  }

  @Test
  void aCircleBreaksAtAKeyNeverBeforeWhatAnObjectNeeds() {
    // The child inherits the parent, whose key refers to the child.
    var child = definition(
        Item.Kind.TABLE,
        "a_child",
        Set.of(item(Item.Kind.TABLE, "z_parent")),
        Set.of(item(Item.Kind.TABLE, "z_parent")));
    var parent =
        definition(Item.Kind.TABLE, "z_parent", Set.of(), Set.of(item(Item.Kind.TABLE, "a_child")));
    assertEquals(List.of("z_parent", "a_child"), names(Order.sorted(List.of(child, parent))));
  }

  @Test
  void tablesWhoseKeysReferBackToThemselvesAreCircular() {
    var a = definition(Item.Kind.TABLE, "a", Set.of(), Set.of(item(Item.Kind.TABLE, "b")));
    var b = definition(Item.Kind.TABLE, "b", Set.of(), Set.of(item(Item.Kind.TABLE, "a")));
    var c = definition(Item.Kind.TABLE, "c", Set.of(), Set.of(item(Item.Kind.TABLE, "b")));
    var tree = definition(Item.Kind.TABLE, "tree", Set.of(), Set.of(item(Item.Kind.TABLE, "tree")));
    assertEquals(
        Set.of(
            item(Item.Kind.TABLE, "a"), item(Item.Kind.TABLE, "b"), item(Item.Kind.TABLE, "tree")),
        Order.circular(List.of(a, b, c, tree)));
  }

  @Test
  void whatIsNotGivenIsTakenForThere() {
    var view = definition(Item.Kind.VIEW, "v", Set.of(item(Item.Kind.TABLE, "gone")), Set.of());
    var sequence = definition(Item.Kind.SEQUENCE, "s", Set.of(), Set.of());
    assertEquals(List.of("s", "v"), names(Order.sorted(List.of(view, sequence))));
  }

  private static Item item(Item.Kind kind, String name) {
    return Item.of(DATABASE, kind, name);
  }

  private static Definition definition(
      Item.Kind kind, String name, Set<Item> needs, Set<Item> refers) {
    return new Definition(
        item(kind, name),
        List.of("CREATE " + name),
        false,
        List.of(),
        List.of(),
        List.of(),
        "",
        List.of(),
        List.of(),
        needs,
        refers);
  }

  private static List<String> names(List<Definition> definitions) {
    return definitions.stream().map(d -> d.item().name()).toList();
  }
}

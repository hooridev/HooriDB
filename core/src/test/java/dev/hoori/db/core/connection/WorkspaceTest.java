package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Workspace.Place;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

  private static final ConnectionProfile A = profile("a");
  private static final ConnectionProfile B = profile("b");
  private static final ConnectionProfile C = profile("c");

  private static ConnectionProfile profile(String name) {
    return ConnectionProfile.of(Provider.POSTGRESQL).withName(name);
  }

  private static Workspace.Folder folder(String name, Workspace.Entry... entries) {
    return new Workspace.Folder(UUID.randomUUID(), name, false, List.of(entries));
  }

  private static Workspace.Folder with(Workspace.Folder folder, Workspace.Entry... entries) {
    return new Workspace.Folder(folder.id(), folder.name(), folder.open(), List.of(entries));
  }

  private static final Workspace.Folder INNER = folder("inner", B);
  private static final Workspace.Folder OUTER = folder("outer", INNER, C);
  private static final Workspace.Folder EMPTY = folder("empty");
  private static final Workspace TREE = new Workspace(List.of(OUTER, A, EMPTY));

  @Test
  void theConnectionsGoFromTheTopDown() {
    assertEquals(List.of(B, C, A), TREE.connections());
    assertEquals(List.of(B, C), OUTER.connections(), "a folder's, its folders' too");
    assertEquals(List.of(), EMPTY.connections());
  }

  @Test
  void anIdIsUniqueInTheWholeTree() {
    assertThrows(IllegalArgumentException.class, () -> new Workspace(List.of(A, A)));
    assertThrows(IllegalArgumentException.class, () -> new Workspace(List.of(A, folder("f", A))));
    var shared = new Workspace.Folder(A.id(), "f", true, List.of());
    assertThrows(IllegalArgumentException.class, () -> new Workspace(List.of(A, shared)));
    var self = new Workspace.Folder(UUID.randomUUID(), "f", true, List.of());
    assertThrows(IllegalArgumentException.class, () -> new Workspace(List.of(with(self, self))));
    assertThrows(IllegalArgumentException.class, () -> TREE.after(B.id(), C), "C is there");
  }

  @Test
  void putReplacesInPlaceAtAnyDepthAndAddsAtTheEndOfTheTop() {
    var renamed = B.withName("b2");
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER, renamed), C), A, EMPTY)), TREE.put(renamed));
    var d = profile("d");
    assertEquals(new Workspace(List.of(OUTER, A, EMPTY, d)), TREE.put(d));
    assertEquals(TREE, TREE.put(A), "the same again");
  }

  @Test
  void afterPutsAnEntryBesideAnotherInItsFolder() {
    var d = profile("d");
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER, B, d), C), A, EMPTY)), TREE.after(B.id(), d));
    assertEquals(new Workspace(List.of(OUTER, A, d, EMPTY)), TREE.after(A.id(), d));
    assertThrows(IllegalArgumentException.class, () -> TREE.after(UUID.randomUUID(), d));
  }

  @Test
  void removeTakesAConnectionOutAndLeavesItsFolder() {
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER), C), A, EMPTY)), TREE.remove(B.id()));
    assertEquals(new Workspace(List.of(A, EMPTY)), TREE.remove(OUTER.id()), "with what it held");
    assertEquals(TREE, TREE.remove(UUID.randomUUID()));
  }

  @Test
  void aPathGoesFromTheTopDown() {
    assertEquals(List.of(OUTER, INNER), TREE.path(B.id()));
    assertEquals(List.of(OUTER), TREE.path(INNER.id()));
    assertEquals(List.of(), TREE.path(A.id()));
    assertNull(TREE.path(UUID.randomUUID()));
    assertEquals(INNER, TREE.folder(INNER.id()));
    assertNull(TREE.folder(A.id()), "a connection");
  }

  @Test
  void intoPutsAnEntryLastInAFolderOrAtTheTop() {
    var d = profile("d");
    assertEquals(
        new Workspace(List.of(with(OUTER, INNER, C, d), A, EMPTY)), TREE.into(OUTER.id(), d));
    assertEquals(new Workspace(List.of(OUTER, A, with(EMPTY, d))), TREE.into(EMPTY.id(), d));
    assertEquals(new Workspace(List.of(OUTER, A, EMPTY, d)), TREE.into(null, d));
    assertThrows(IllegalArgumentException.class, () -> TREE.into(A.id(), d), "not a folder");
    assertThrows(IllegalArgumentException.class, () -> TREE.into(UUID.randomUUID(), d));
  }

  @Test
  void moveTakesAnEntryWithWhatItHolds() {
    assertEquals(
        new Workspace(List.of(with(OUTER, C), A, with(EMPTY, INNER))),
        TREE.move(INNER.id(), EMPTY.id(), Place.INTO));
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER, B, A), C), EMPTY)),
        TREE.move(A.id(), INNER.id(), Place.INTO));
    assertEquals(
        new Workspace(List.of(A, OUTER, EMPTY)), TREE.move(A.id(), OUTER.id(), Place.BEFORE));
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER, B, A), C), EMPTY)),
        TREE.move(A.id(), B.id(), Place.AFTER));
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER), B, C), A, EMPTY)),
        TREE.move(B.id(), C.id(), Place.BEFORE),
        "out of its folder");
    assertEquals(
        new Workspace(List.of(with(OUTER, with(INNER), C), A, EMPTY, B)),
        TREE.move(B.id(), null, Place.INTO),
        "last at the top");
    assertEquals(
        new Workspace(List.of(with(OUTER, C, INNER), A, EMPTY)),
        TREE.move(INNER.id(), C.id(), Place.AFTER),
        "within its folder");
  }

  @Test
  void nothingMovesIntoItselfOrNowhere() {
    assertFalse(TREE.canMove(OUTER.id(), OUTER.id(), Place.INTO));
    assertFalse(TREE.canMove(OUTER.id(), INNER.id(), Place.INTO), "into its own folder");
    assertFalse(TREE.canMove(OUTER.id(), B.id(), Place.AFTER), "beside what it holds");
    assertFalse(TREE.canMove(A.id(), A.id(), Place.BEFORE));
    assertFalse(TREE.canMove(A.id(), C.id(), Place.INTO), "into a connection");
    assertFalse(TREE.canMove(A.id(), null, Place.AFTER));
    assertFalse(TREE.canMove(A.id(), UUID.randomUUID(), Place.INTO));
    assertFalse(TREE.canMove(UUID.randomUUID(), OUTER.id(), Place.INTO));
    assertTrue(TREE.canMove(INNER.id(), OUTER.id(), Place.BEFORE));
    assertThrows(
        IllegalArgumentException.class, () -> TREE.move(OUTER.id(), INNER.id(), Place.INTO));
  }

  /** A change never nests folders deeper than a save takes, and one at the limit fits. */
  @Test
  void foldersNestNoDeeperThanTheLimit() {
    var deepest = folder("deepest", A);
    Workspace.Entry chain = deepest;
    for (int i = 1; i < Workspace.DEPTH; i++) {
      chain = folder("f" + i, chain);
    }
    var other = folder("other");
    var deep = new Workspace(List.of(chain, other));
    assertEquals(Workspace.DEPTH, deep.path(A.id()).size());
    var two = folder("two", folder("one"));
    assertFalse(deep.fits(deepest.id(), folder("new")));
    assertTrue(deep.fits(deepest.id(), B));
    assertTrue(deep.canMove(A.id(), other.id(), Place.INTO));
    var below = deep.path(A.id()).get(Workspace.DEPTH - 2).id();
    var shallow = new Workspace(List.of(chain, two));
    assertTrue(shallow.canMove(two.id(), below, Place.BEFORE), "as deep as its neighbor");
    assertFalse(shallow.canMove(two.id(), below, Place.INTO));
    assertFalse(shallow.canMove(two.id(), A.id(), Place.AFTER));
    assertThrows(IllegalArgumentException.class, () -> deep.into(deepest.id(), folder("new")));
  }

  @Test
  void aFolderOpensIsRenamedAndDissolvesInPlace() {
    var opened = TREE.open(INNER.id(), true);
    assertTrue(opened.folder(INNER.id()).open());
    assertEquals(List.of(B), opened.folder(INNER.id()).entries());
    assertEquals("deep", TREE.rename(INNER.id(), "deep").folder(INNER.id()).name());
    assertEquals(new Workspace(List.of(with(OUTER, B, C), A, EMPTY)), TREE.dissolve(INNER.id()));
    assertEquals(new Workspace(List.of(INNER, C, A, EMPTY)), TREE.dissolve(OUTER.id()));
    assertEquals(new Workspace(List.of(OUTER, A)), TREE.dissolve(EMPTY.id()));
    assertThrows(IllegalArgumentException.class, () -> TREE.open(A.id(), true), "a connection");
    assertThrows(IllegalArgumentException.class, () -> TREE.dissolve(UUID.randomUUID()));
  }
}

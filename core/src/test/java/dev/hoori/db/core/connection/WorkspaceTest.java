package dev.hoori.db.core.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}

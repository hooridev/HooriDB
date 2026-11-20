package dev.hoori.db.core.connection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * What the user keeps in HooriDB: a tree of entries, connections and folders of entries, each level
 * in its order. An id is unique in the whole tree, among folders and connections alike. A change
 * makes a new workspace, which rebuilds the folders on the way to what changed.
 */
public record Workspace(List<Workspace.Entry> entries) {

  public static final Workspace EMPTY = new Workspace(List.of());

  /** A part of the workspace: a folder or a connection. */
  public sealed interface Entry permits Folder, ConnectionProfile {
    UUID id();
  }

  /** A folder with a name, open or closed in the tree, of entries, folders among them. */
  public record Folder(UUID id, String name, boolean open, List<Entry> entries) implements Entry {
    public Folder {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      entries = List.copyOf(entries);
    }
  }

  /**
   * @throws IllegalArgumentException if two entries have the same id
   */
  public Workspace {
    entries = List.copyOf(entries);
    var ids = all(entries).map(Entry::id).toList();
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException("Two entries have the same id");
    }
  }

  /** The connections from the top of the tree down: a folder's after the entries before it. */
  public List<ConnectionProfile> connections() {
    return all(entries)
        .filter(ConnectionProfile.class::isInstance)
        .map(ConnectionProfile.class::cast)
        .toList();
  }

  /** The workspace with the profile in place of the entry of its id, or else last at the top. */
  public Workspace put(ConnectionProfile profile) {
    if (!contains(profile.id())) {
      var list = new ArrayList<>(entries);
      list.add(profile);
      return new Workspace(list);
    }
    return replace(profile.id(), old -> List.of(profile));
  }

  /**
   * The workspace with the entry right after the one of the id, in its folder.
   *
   * @throws IllegalArgumentException if no entry has the id
   */
  public Workspace after(UUID id, Entry entry) {
    if (!contains(id)) {
      throw new IllegalArgumentException("No entry has the id " + id);
    }
    return replace(id, old -> List.of(old, entry));
  }

  /** The workspace without the entry of the id, and without what it holds where it is a folder. */
  public Workspace remove(UUID id) {
    return replace(id, old -> List.of());
  }

  private boolean contains(UUID id) {
    return all(entries).anyMatch(e -> e.id().equals(id));
  }

  // Each entry, a folder before what it holds.
  private static Stream<Entry> all(List<Entry> entries) {
    return entries.stream()
        .flatMap(e -> e instanceof Folder folder
            ? Stream.concat(Stream.of(folder), all(folder.entries()))
            : Stream.of(e));
  }

  // The entry of the id in its place, at any depth, replaced by what the function makes of it.
  private Workspace replace(UUID id, Function<Entry, List<Entry>> with) {
    return new Workspace(replace(entries, id, with));
  }

  private static List<Entry> replace(
      List<Entry> entries, UUID id, Function<Entry, List<Entry>> with) {
    var list = new ArrayList<Entry>();
    for (var entry : entries) {
      if (entry.id().equals(id)) {
        list.addAll(with.apply(entry));
      } else if (entry instanceof Folder f) {
        list.add(new Folder(f.id(), f.name(), f.open(), replace(f.entries(), id, with)));
      } else {
        list.add(entry);
      }
    }
    return list;
  }
}

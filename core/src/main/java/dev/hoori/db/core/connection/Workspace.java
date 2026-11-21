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

  /**
   * How deep folders nest at most, counting a folder at the top as one: a save takes this many
   * and a few more ({@link WorkspaceStore}), so that a change within it never fails to be saved.
   */
  public static final int DEPTH = 240;

  /** Where an entry goes from the one it is moved to. */
  public enum Place {
    /** Before it, in its folder. */
    BEFORE,
    /** After it, in its folder. */
    AFTER,
    /** Last in it, a folder. */
    INTO
  }

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

    /** The connections it holds, its folders' too, from the top down. */
    public List<ConnectionProfile> connections() {
      return Workspace.connections(entries);
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
    return connections(entries);
  }

  private static List<ConnectionProfile> connections(List<Entry> entries) {
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

  /** The folder of the id, or null where no folder has it. */
  public Folder folder(UUID id) {
    return entry(id) instanceof Folder folder ? folder : null;
  }

  /**
   * The folders the entry of the id is in, from the top down: none for an entry at the top, null
   * where no entry has the id.
   */
  public List<Folder> path(UUID id) {
    return path(entries, id, new ArrayList<>());
  }

  /**
   * Whether the entry may go into the folder of the id, or at the top where the id is null,
   * without folders nesting deeper than {@link #DEPTH}; never where no folder has the id.
   */
  public boolean fits(UUID folder, Entry entry) {
    if (folder == null) {
      return height(entry) <= DEPTH;
    }
    var path = path(folder);
    return path != null && folder(folder) != null && path.size() + 1 + height(entry) <= DEPTH;
  }

  /**
   * The workspace with the entry last in the folder of the id, or last at the top where it is
   * null.
   *
   * @throws IllegalArgumentException if no folder has the id, or folders would nest deeper than
   *     {@link #DEPTH}
   */
  public Workspace into(UUID folder, Entry entry) {
    if (!fits(folder, entry)) {
      throw new IllegalArgumentException("The entry does not fit into " + folder);
    }
    if (folder == null) {
      var list = new ArrayList<>(entries);
      list.add(entry);
      return new Workspace(list);
    }
    return replace(folder, old -> {
      var f = (Folder) old;
      var list = new ArrayList<>(f.entries());
      list.add(entry);
      return List.of(new Folder(f.id(), f.name(), f.open(), list));
    });
  }

  /**
   * Whether the entry of the id may go to the place at the target, which is null for the top with
   * {@link Place#INTO} alone: not to itself or into a folder of its own, into a folder only, and
   * without folders nesting deeper than {@link #DEPTH}.
   */
  public boolean canMove(UUID id, UUID target, Place place) {
    var entry = entry(id);
    if (entry == null || id.equals(target) || target == null && place != Place.INTO) {
      return false;
    }
    UUID into = target;
    if (place != Place.INTO) {
      var path = path(target);
      if (path == null) {
        return false;
      }
      into = path.isEmpty() ? null : path.getLast().id();
    }
    if (into != null) {
      var above = path(into);
      if (above == null
          || into.equals(id)
          || above.stream().anyMatch(f -> f.id().equals(id))) {
        return false;
      }
    }
    return fits(into, entry);
  }

  /**
   * The workspace with the entry of the id moved to the place at the target, as {@link #canMove}
   * allows; with what it holds, where it is a folder.
   *
   * @throws IllegalArgumentException if it cannot move there
   */
  public Workspace move(UUID id, UUID target, Place place) {
    if (!canMove(id, target, place)) {
      throw new IllegalArgumentException("The entry " + id + " cannot move there");
    }
    var entry = entry(id);
    var without = remove(id);
    return switch (place) {
      case BEFORE -> without.replace(target, old -> List.of(entry, old));
      case AFTER -> without.after(target, entry);
      case INTO -> without.into(target, entry);
    };
  }

  /**
   * The workspace with the folder of the id open or closed in the tree.
   *
   * @throws IllegalArgumentException if no folder has the id
   */
  public Workspace open(UUID id, boolean open) {
    var f = existing(id);
    return replace(id, old -> List.of(new Folder(id, f.name(), open, f.entries())));
  }

  /**
   * The workspace with the folder of the id named anew.
   *
   * @throws IllegalArgumentException if no folder has the id
   */
  public Workspace rename(UUID id, String name) {
    var f = existing(id);
    return replace(id, old -> List.of(new Folder(id, name, f.open(), f.entries())));
  }

  /**
   * The workspace without the folder of the id, and what it held in its place, one level up.
   *
   * @throws IllegalArgumentException if no folder has the id
   */
  public Workspace dissolve(UUID id) {
    var f = existing(id);
    return replace(id, old -> f.entries());
  }

  private Folder existing(UUID id) {
    var folder = folder(id);
    if (folder == null) {
      throw new IllegalArgumentException("No folder has the id " + id);
    }
    return folder;
  }

  private boolean contains(UUID id) {
    return entry(id) != null;
  }

  private Entry entry(UUID id) {
    return all(entries).filter(e -> e.id().equals(id)).findFirst().orElse(null);
  }

  // The folders above the entry of the id, after those above the entries; null where none has it,
  // as an empty list is the path of an entry at the top.
  private static List<Folder> path(List<Entry> entries, UUID id, List<Folder> above) {
    for (var entry : entries) {
      if (entry.id().equals(id)) {
        return List.copyOf(above);
      }
      if (entry instanceof Folder f) {
        above.add(f);
        var found = path(f.entries(), id, above);
        if (found != null) {
          return found;
        }
        above.removeLast();
      }
    }
    return null;
  }

  // How many folders deep the entry reaches: none for a connection, one for an empty folder.
  private static int height(Entry entry) {
    return entry instanceof Folder f
        ? 1 + f.entries().stream().mapToInt(Workspace::height).max().orElse(0)
        : 0;
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
      } else if (entry instanceof Folder(var folder, var name, var open, var inner)) {
        list.add(new Folder(folder, name, open, replace(inner, id, with)));
      } else {
        list.add(entry);
      }
    }
    return list;
  }
}

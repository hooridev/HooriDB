package dev.hoori.db.core.connection;

import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The statements run from consoles, of every connection, kept in {@value #FILE} in a folder beside
 * the profiles: at most {@value #LIMIT} of each connection, the oldest going first. Every call that
 * reads or writes blocks on the disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and the {@code entries} as an array,
 * the oldest first. It is readable and writable by its owner alone where the file system has POSIX
 * permissions, as a statement may hold a password.
 */
public final class HistoryStore {

  public static final String FILE = "history.json";

  /** Where {@link #save} keeps a copy of a file that {@link #load} could not understand. */
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  /** The most entries of a connection that are kept. */
  public static final int LIMIT = 1000;

  // shortcut: a longer statement, such as a pasted dump, is not kept at all; keep its start once
  // users miss such statements in the history.
  /** The longest statement kept, in bytes of UTF-8. */
  public static final int LONGEST = 64 * 1024;

  private static final int VERSION = 1;

  /** A larger file is refused unread; a save leaves out the oldest entries to stay below it. */
  static final long MAX_SIZE = 64L << 20;

  private static final String OUTCOME = "outcome";

  private final JsonFile json;

  public HistoryStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "history", MAX_SIZE);
  }

  /**
   * The history with the entry at its end, and without the oldest entries of its connection beyond
   * {@link #LIMIT}; as it was where the statement is longer than {@link #LONGEST}.
   */
  public static List<HistoryEntry> add(List<HistoryEntry> history, HistoryEntry entry) {
    if (entry.statement().getBytes(StandardCharsets.UTF_8).length > LONGEST) {
      return history;
    }
    var all = new ArrayList<>(history);
    all.add(entry);
    long over =
        all.stream().filter(e -> e.connection().equals(entry.connection())).count() - LIMIT;
    for (var i = all.iterator(); over > 0 && i.hasNext(); ) {
      if (i.next().connection().equals(entry.connection())) {
        i.remove();
        over--;
      }
    }
    return List.copyOf(all);
  }

  /**
   * The entries, the oldest first, or none when there is no file. A file that cannot be understood
   * is left as it is.
   *
   * @throws WorkspaceStore.UnreadableException if the file was read but not understood
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load} gives
   */
  public List<HistoryEntry> load() throws IOException {
    var bytes = json.load();
    return bytes == null ? List.of() : parse(bytes);
  }

  private List<HistoryEntry> parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var list = json.objects(json.root(bytes, VERSION), "entries", "entry");
    var entries = new ArrayList<HistoryEntry>();
    for (int i = 0; i < list.size(); i++) {
      var where = "entry " + (i + 1);
      var node = list.get(i);
      entries.add(new HistoryEntry(
          json.uuid(node, where, "connection"),
          json.text(node, where, "statement"),
          json.time(node, where, "started"),
          Duration.ofMillis(json.count(node, where, "took")),
          outcome(node, where)));
    }
    return entries;
  }

  // The names in the file, spelled out so that renaming a record cannot change it.
  private Outcome outcome(JsonNode node, String where) throws WorkspaceStore.UnreadableException {
    return switch (json.text(node, where, OUTCOME)) {
      case "rows" -> new Outcome.Rows();
      case "changed" -> new Outcome.Changed(json.count(node, where, "count"));
      case "failed" -> new Outcome.Failed(json.text(node, where, "message"));
      case "cancelled" -> new Outcome.Cancelled();
      default -> throw json.unreadable("in " + where + ", \"outcome\" is not one HooriDB knows");
    };
  }

  /**
   * Replaces the file with the entries, the oldest first, as {@link WorkspaceStore#save} replaces its
   * own. Where they would be larger than {@link #load} reads, the oldest are left out.
   *
   * @throws IOException if it could not be written, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public void save(List<HistoryEntry> entries) throws IOException {
    var nodes = new ArrayList<ObjectNode>();
    long size = 0;
    for (var entry : entries) {
      var node = node(entry);
      nodes.add(node);
      size += size(node);
    }
    // Rare: only a thousand long statements, of several connections, come near it.
    int first = 0;
    for (; size > MAX_SIZE - (1 << 10); first++) {
      size -= size(nodes.get(first));
    }
    var root = JsonFile.newRoot(VERSION);
    root.putArray("entries").addAll(nodes.subList(first, nodes.size()));
    json.save(JsonFile.bytes(root), this::parse);
  }

  // What the entry takes in the file, with room for its indentation in the array.
  private static long size(ObjectNode node) {
    return JsonFile.Json.MAPPER.writeValueAsBytes(node).length + 64L;
  }

  private static ObjectNode node(HistoryEntry entry) {
    var node = JsonFile.Json.MAPPER
        .createObjectNode()
        .put("connection", entry.connection().toString())
        .put("statement", entry.statement())
        .put("started", entry.started().toString())
        .put("took", Math.max(0, entry.took().toMillis()));
    switch (entry.outcome()) {
      case Outcome.Rows _ -> node.put(OUTCOME, "rows");
      case Outcome.Changed(var count) ->
        node.put(OUTCOME, "changed").put("count", Math.max(0, count));
      case Outcome.Failed(var message) -> node.put(OUTCOME, "failed").put("message", message);
      case Outcome.Cancelled _ -> node.put(OUTCOME, "cancelled");
    }
    return node;
  }
}

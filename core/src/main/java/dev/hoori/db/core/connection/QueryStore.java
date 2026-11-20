package dev.hoori.db.core.connection;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * The queries the user saved, of every connection, kept in {@value #FILE} in a folder beside the
 * workspace, in the order they were first saved. Every call that reads or writes blocks on the disk,
 * so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and the {@code queries} as an array.
 * It is readable and writable by its owner alone where the file system has POSIX permissions, as a
 * query may hold a password.
 */
public final class QueryStore {

  public static final String FILE = "queries.json";

  /** Where {@link #save} keeps a copy of a file that {@link #load} could not understand. */
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  private static final int VERSION = 1;

  /** A larger file is refused unread. */
  static final long MAX_SIZE = 64L << 20;

  private final JsonFile json;

  public QueryStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "saved queries", MAX_SIZE);
  }

  /**
   * The queries with this one in them: in place of one of its connection and name, in any case,
   * else after the others.
   */
  public static List<SavedQuery> put(List<SavedQuery> queries, SavedQuery query) {
    var all = new ArrayList<SavedQuery>(queries.size() + 1);
    boolean replaced = false;
    for (var q : queries) {
      if (!replaced && same(q, query)) {
        all.add(new SavedQuery(q.id(), q.connection(), query.name(), query.text(), query.saved()));
        replaced = true;
      } else {
        all.add(q);
      }
    }
    if (!replaced) {
      all.add(query);
    }
    return List.copyOf(all);
  }

  /** Whether a query of the connection is saved under the name already, in any case. */
  public static boolean named(List<SavedQuery> queries, java.util.UUID connection, String name) {
    var key = name.strip().toLowerCase(Locale.ROOT);
    return queries.stream()
        .anyMatch(q -> q.connection().equals(connection)
            && q.name().strip().toLowerCase(Locale.ROOT).equals(key));
  }

  private static boolean same(SavedQuery a, SavedQuery b) {
    return a.connection().equals(b.connection())
        && a.name()
            .strip()
            .toLowerCase(Locale.ROOT)
            .equals(b.name().strip().toLowerCase(Locale.ROOT));
  }

  /**
   * The queries, in their order, or none when there is no file. A file that cannot be understood
   * is left as it is.
   *
   * @throws WorkspaceStore.UnreadableException if the file was read but not understood
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load} gives
   */
  public List<SavedQuery> load() throws IOException {
    var bytes = json.load();
    return bytes == null ? List.of() : parse(bytes);
  }

  private List<SavedQuery> parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var list = json.objects(json.root(bytes, VERSION), "queries", "query");
    var queries = new ArrayList<SavedQuery>();
    for (int i = 0; i < list.size(); i++) {
      var where = "query " + (i + 1);
      var node = list.get(i);
      queries.add(new SavedQuery(
          json.uuid(node, where, "id"),
          json.uuid(node, where, "connection"),
          json.text(node, where, "name"),
          json.text(node, where, "text"),
          saved(node, where)));
    }
    return queries;
  }

  private Instant saved(JsonNode node, String where) throws WorkspaceStore.UnreadableException {
    try {
      return Instant.parse(json.text(node, where, "saved"));
    } catch (DateTimeParseException notATime) {
      throw json.unreadable("in " + where + ", \"saved\" is not a time");
    }
  }

  /**
   * Replaces the file with the queries, as {@link WorkspaceStore#save} replaces its own.
   *
   * @throws IOException if it could not be written, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public void save(List<SavedQuery> queries) throws IOException {
    var root = JsonFile.Json.MAPPER.createObjectNode();
    root.put("version", VERSION);
    var array = root.putArray("queries");
    for (var query : queries) {
      array
          .addObject()
          .put("id", query.id().toString())
          .put("connection", query.connection().toString())
          .put("name", query.name())
          .put("text", query.text())
          .put("saved", query.saved().toString());
    }
    json.save(JsonFile.bytes(root), this::parse);
  }
}

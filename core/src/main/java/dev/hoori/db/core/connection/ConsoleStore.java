package dev.hoori.db.core.connection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The consoles open when they were last saved, kept in {@value #FILE} in a folder, beside the
 * profiles. Every call blocks on the disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and the {@code consoles} as an array
 * in their order. It is readable and writable by its owner alone where the file system has POSIX
 * permissions, as a console's text may hold a password.
 */
public final class ConsoleStore {

  public static final String FILE = "consoles.json";

  /** Where {@link #save} keeps a copy of a file that {@link #load} could not understand. */
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  private static final int VERSION = 1;

  /** A larger file is refused unread, and a save of more refused. */
  static final long MAX_SIZE = 64L << 20;

  private static final String CONSOLES = "consoles";

  private final JsonFile json;

  public ConsoleStore(Path folder) {
    this.json = new JsonFile(folder, FILE, CONSOLES, MAX_SIZE);
  }

  /**
   * The consoles in the order they were saved, or none when there is no file. A file that cannot
   * be understood is left as it is.
   *
   * @throws WorkspaceStore.UnreadableException if the file was read but not understood
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load} gives
   */
  public List<StoredConsole> load() throws IOException {
    var bytes = json.load();
    return bytes == null ? List.of() : parse(bytes);
  }

  private List<StoredConsole> parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var list = json.objects(json.root(bytes, VERSION), CONSOLES, "console");
    var consoles = new ArrayList<StoredConsole>();
    for (int i = 0; i < list.size(); i++) {
      var where = "console " + (i + 1);
      var node = list.get(i);
      if (node.get("query") != null) {
        var query = json.uuid(node, where, "query");
        var connection =
            node.get("connection") != null ? json.uuid(node, where, "connection") : null;
        consoles.add(StoredConsole.ofQuery(query, connection));
      } else {
        consoles.add(new StoredConsole(
            json.uuid(node, where, "connection"),
            json.text(node, where, "database"),
            json.text(node, where, "text")));
      }
    }
    return consoles;
  }

  /**
   * Replaces the file with the consoles, as {@link WorkspaceStore#save} replaces its own.
   *
   * @throws IOException if it could not be written, or would be larger than {@link #load} reads
   */
  public void save(List<StoredConsole> consoles) throws IOException {
    var root = JsonFile.newRoot(VERSION);
    var list = root.putArray(CONSOLES);
    for (var console : consoles) {
      var object = list.addObject();
      if (console.query() != null) {
        object.put("query", console.query().toString());
        if (console.connection() != null) {
          object.put("connection", console.connection().toString());
        }
      } else {
        object
            .put("connection", console.connection().toString())
            .put("database", console.database())
            .put("text", console.text());
      }
    }
    var bytes = JsonFile.bytes(root);
    if (bytes.capacity() > MAX_SIZE) {
      throw new IOException("Cannot save the consoles in " + json.file + ": their texts are"
          + " larger than " + (MAX_SIZE >> 20) + " MB together.");
    }
    json.save(bytes, this::parse);
  }
}

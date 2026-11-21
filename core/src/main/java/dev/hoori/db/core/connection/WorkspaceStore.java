package dev.hoori.db.core.connection;

import dev.hoori.db.core.driver.Maven;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The workspace, kept in {@value #FILE} in a folder. Every call blocks on the disk, so none of them
 * belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and the {@code entries} of the top
 * level as an array in their order. Each entry is an object whose {@code kind} says what it is: a
 * {@code "folder"} with its {@code id}, {@code name}, whether it is {@code open} and its own {@code
 * entries}, or a {@code "connection"} with the fields of its profile. It holds no passwords: those
 * are in the system keyring under the profile's id. Another kind of entry raises the version.
 *
 * <p>Where there is no such file, {@link #load} takes the connections of {@value #CONNECTIONS},
 * where HooriDB kept them before it had a workspace. No save writes that file.
 */
public final class WorkspaceStore {

  public static final String FILE = "workspace.json";

  /**
   * Where {@link #save} keeps a copy of a file that {@link #load} could not understand, before it
   * replaces it, so that what was in it is not lost.
   */
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  /** The list of connections of a HooriDB before the workspace, which it replaces. */
  public static final String CONNECTIONS = "connections.json";

  private static final int VERSION = 1;

  /** A larger file is refused unread; thousands of connections are far below it. */
  static final long MAX_SIZE = 16L << 20;

  // The names of the file's fields that more than one place reads or writes.
  private static final String ENTRIES = "entries";
  private static final String FOLDER = "folder";
  private static final String CONNECTION = "connection";
  private static final String COLOR = "color";
  private static final String DRIVER_VERSION = "driverVersion";

  private final JsonFile json;
  private final JsonFile connectionsJson;

  public WorkspaceStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "connections", MAX_SIZE);
    this.connectionsJson = new JsonFile(folder, CONNECTIONS, "connections", MAX_SIZE);
  }

  /**
   * A file of HooriDB's that cannot be understood, of the workspace, consoles or history; its
   * message says why and names the file.
   */
  public static final class UnreadableException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Path file; // a Path is not serializable

    UnreadableException(String what, Path file, String why) {
      super("Cannot read the " + what + " in " + file + ": " + why + ".");
      this.file = file;
    }

    /** The file that could not be understood. */
    public Path file() {
      return file;
    }
  }

  /**
   * Where what a file that {@link #load} could not understand held is once a save has replaced the
   * workspace: in a copy of {@value #FILE}, or still in {@value #CONNECTIONS}, which no save
   * writes.
   */
  public static String kept(UnreadableException unreadable) {
    return unreadable.file().getFileName().toString().equals(CONNECTIONS)
        ? CONNECTIONS
        : UNREADABLE;
  }

  /**
   * The workspace as it was saved; where there is no file, the connections of {@value
   * #CONNECTIONS} at its top in their order, or none where that file is missing too. A file that
   * cannot be understood is left as it is.
   *
   * @throws UnreadableException if a file was read but not understood
   * @throws IOException if one could not be read; its message names the file and says why in
   *     HooriDB's words, and its cause is what the file system threw
   */
  public Workspace load() throws IOException {
    var bytes = json.load();
    if (bytes != null) {
      return parse(bytes);
    }
    // Only where the workspace is missing: in place of one that is there but could not be read,
    // the older list would bring back connections deleted since, without their passwords, and lose
    // those added since.
    var older = connectionsJson.load();
    return older == null ? Workspace.EMPTY : older(older);
  }

  // shortcut: folders nest some 248 deep, within the JSON parser's limit of 500, and a save of a
  // deeper workspace fails and leaves the file; parse and write without recursion to go deeper.
  private Workspace parse(byte[] bytes) throws UnreadableException {
    var root = json.root(bytes, VERSION);
    // Fields this version does not know are ignored: a later HooriDB may add one without raising
    // the version, as long as an older one can do without it. The older one drops it on a save.
    return new Workspace(entries(json.objects(root, ENTRIES, "entry"), "entry ", new HashSet<>()));
  }

  // The entries of a level, numbered in messages after the prefix, as "entry 2.1" for the first in
  // the second; a value of the file goes into a message only where it was understood.
  private List<Workspace.Entry> entries(JsonNode list, String prefix, Set<UUID> ids)
      throws UnreadableException {
    var entries = new ArrayList<Workspace.Entry>();
    for (int i = 0; i < list.size(); i++) {
      var where = prefix + (i + 1);
      var node = list.get(i);
      if (!node.isObject()) {
        throw json.unreadable(where + " is not a JSON object");
      }
      entries.add(
          switch (json.text(node, where, "kind")) {
            case FOLDER ->
              new Workspace.Folder(
                  id(json, node, where, ids),
                  json.text(node, where, "name"),
                  json.bool(node, where, "open"),
                  entries(json.array(node, where, ENTRIES), where + ".", ids));
            case CONNECTION -> profile(json, node, where, id(json, node, where, ids));
            default ->
              throw json.unreadable(
                  "in " + where + ", \"kind\" is not \"folder\" or \"connection\"");
          });
    }
    return entries;
  }

  // The list of an older HooriDB: its version 1, with the profiles in their order.
  private Workspace older(byte[] bytes) throws UnreadableException {
    var root = connectionsJson.root(bytes, 1);
    var list = connectionsJson.objects(root, "profiles", "profile");
    var ids = new HashSet<UUID>();
    var profiles = new ArrayList<Workspace.Entry>();
    for (int i = 0; i < list.size(); i++) {
      var where = "profile " + (i + 1);
      var node = list.get(i);
      profiles.add(profile(connectionsJson, node, where, id(connectionsJson, node, where, ids)));
    }
    return new Workspace(profiles);
  }

  private static UUID id(JsonFile in, JsonNode node, String where, Set<UUID> ids)
      throws UnreadableException {
    var id = in.uuid(node, where, "id");
    if (!ids.add(id)) {
      throw in.unreadable(where + " has the id " + id + " of an earlier one");
    }
    return id;
  }

  private static ConnectionProfile profile(JsonFile in, JsonNode node, String where, UUID id)
      throws UnreadableException {
    return new ConnectionProfile(
        id,
        in.text(node, where, "name"),
        provider(in, node, where),
        in.text(node, where, "host"),
        in.integer(node, where, "port"),
        in.text(node, where, "database"),
        in.text(node, where, "user"),
        tls(in, node, where),
        color(in, node, where),
        driverVersion(in, node, where),
        optionalBool(in, node, where, "readOnly"),
        optionalBool(in, node, where, "srv"),
        optionalText(in, node, where, "authDatabase"));
  }

  // False where it is missing, as in a file from before there was the field.
  private static boolean optionalBool(JsonFile in, JsonNode node, String where, String field)
      throws UnreadableException {
    return node.get(field) != null && in.bool(node, where, field);
  }

  // Empty where it is missing, as in a file from before there was the field.
  private static String optionalText(JsonFile in, JsonNode node, String where, String field)
      throws UnreadableException {
    return node.get(field) != null ? in.text(node, where, field) : "";
  }

  /**
   * Replaces the file with the workspace, creating the folder if it is missing. A crash leaves the
   * old file or the new one, never a part of it. A file that {@link #load} cannot understand is
   * kept as {@link #UNREADABLE} first, in place of an older copy. One save runs at a time; which of
   * two saves lands last is for the caller to order.
   *
   * @throws IOException if it could not be written, with a message as {@link #load} gives
   */
  public void save(Workspace workspace) throws IOException {
    var root = JsonFile.newRoot(VERSION);
    write(root.putArray(ENTRIES), workspace.entries());
    json.save(JsonFile.bytes(root), this::parse);
  }

  private static void write(ArrayNode list, List<Workspace.Entry> entries) {
    for (var entry : entries) {
      var object = list.addObject();
      switch (entry) {
        case Workspace.Folder(var id, var name, var open, var inner) -> {
          object.put("kind", FOLDER).put("id", id.toString()).put("name", name).put("open", open);
          write(object.putArray(ENTRIES), inner);
        }
        case ConnectionProfile profile -> write(object, profile);
      }
    }
  }

  private static void write(ObjectNode object, ConnectionProfile profile) {
    object
        .put("kind", CONNECTION)
        .put("id", profile.id().toString())
        .put("name", profile.name())
        .put("provider", stored(profile.provider()))
        .put("host", profile.host())
        .put("port", profile.port())
        .put("database", profile.database())
        .put("user", profile.user())
        .put("tls", stored(profile.tls()));
    if (profile.color() != null) {
      object.put(COLOR, stored(profile.color()));
    }
    if (profile.driverVersion() != null) {
      object.put(DRIVER_VERSION, profile.driverVersion());
    }
    // Left out where it may write, as in a file from before there was the choice.
    if (profile.readOnly()) {
      object.put("readOnly", true);
    }
    if (profile.srv()) {
      object.put("srv", true);
    }
    if (!profile.authDatabase().isEmpty()) {
      object.put("authDatabase", profile.authDatabase());
    }
  }

  private static Provider provider(JsonFile in, JsonNode profile, String where)
      throws UnreadableException {
    return choice(
        in,
        profile,
        where,
        "provider",
        Provider.values(),
        WorkspaceStore::stored,
        "a database HooriDB knows");
  }

  private static Tls tls(JsonFile in, JsonNode profile, String where) throws UnreadableException {
    return choice(
        in,
        profile,
        where,
        "tls",
        Tls.values(),
        WorkspaceStore::stored,
        "\"off\", \"encrypted\" or \"verified\"");
  }

  // Left out for none, as in a file written before there were colors.
  private static Color color(JsonFile in, JsonNode profile, String where)
      throws UnreadableException {
    if (profile.get(COLOR) == null) {
      return null;
    }
    return choice(
        in, profile, where, COLOR, Color.values(), WorkspaceStore::stored, "a color HooriDB knows");
  }

  // The constant whose stored name the field holds; the message says what else it could be.
  private static <E extends Enum<E>> E choice(
      JsonFile in,
      JsonNode profile,
      String where,
      String field,
      E[] values,
      Function<E, String> stored,
      String expected)
      throws UnreadableException {
    var value = in.text(profile, where, field);
    for (var constant : values) {
      if (stored.apply(constant).equals(value)) {
        return constant;
      }
    }
    throw in.unreadable("in " + where + ", \"" + field + "\" is not " + expected);
  }

  // Left out for the recommended one, as in a file written before a version could be chosen.
  private static String driverVersion(JsonFile in, JsonNode profile, String where)
      throws UnreadableException {
    if (profile.get(DRIVER_VERSION) == null) {
      return null;
    }
    var value = in.text(profile, where, DRIVER_VERSION);
    if (!Maven.isVersion(value)) {
      throw in.unreadable("in " + where + ", \"driverVersion\" is not a version HooriDB accepts");
    }
    return value;
  }

  // The names in the file, spelled out so that renaming a constant cannot change it.
  private static String stored(Provider provider) {
    return provider.traits().storedName();
  }

  private static String stored(Tls tls) {
    return switch (tls) {
      case OFF -> "off";
      case ENCRYPTED -> "encrypted";
      case VERIFIED -> "verified";
    };
  }

  private static String stored(Color color) {
    return switch (color) {
      case BLUE -> "blue";
      case VIOLET -> "violet";
      case PURPLE -> "purple";
      case PINK -> "pink";
      case RED -> "red";
      case ORANGE -> "orange";
      case AMBER -> "amber";
      case GREEN -> "green";
      case TEAL -> "teal";
      case GRAY -> "gray";
    };
  }
}

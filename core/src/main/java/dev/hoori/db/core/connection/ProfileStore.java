package dev.hoori.db.core.connection;

import dev.hoori.db.core.driver.Maven;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The list of connection profiles, kept in {@value #FILE} in a folder. Every call blocks on the
 * disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and the {@code profiles} as an array
 * in their order. It holds no passwords: those are in the system keyring under the profile's id.
 */
public final class ProfileStore {

  public static final String FILE = "connections.json";

  /**
   * Where {@link #save} keeps a copy of a file that {@link #load} could not understand, before it
   * replaces it, so that what was in it is not lost.
   */
  public static final String UNREADABLE = FILE + ".unreadable";

  private static final int VERSION = 1;

  /** A larger file is refused unread; thousands of profiles are far below it. */
  static final long MAX_SIZE = 16L << 20;

  private static final Object SAVING = new Object();

  // The tree model with the field names spelled out here, so that renaming a component of the
  // record
  // cannot change the file. Each field's node type is checked, so nothing is coerced.
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build();

  private final Path folder;
  private final Path file;

  public ProfileStore(Path folder) {
    this.folder = folder;
    this.file = folder.resolve(FILE);
  }

  /** A file of profiles that cannot be understood; its message says why and names the file. */
  public static final class UnreadableException extends IOException {
    UnreadableException(Path file, String why) {
      super("Cannot read the connections in " + file + ": " + why + ".");
    }
  }

  /**
   * The profiles in the order they were saved, or none when there is no file. A file that cannot be
   * understood is left as it is.
   *
   * @throws UnreadableException if the file was read but not understood
   * @throws IOException if it could not be read; its message names the file and says why in
   *     HooriDB's words, and its cause is what the file system threw
   */
  public List<ConnectionProfile> load() throws IOException {
    byte[] bytes;
    try {
      bytes = read();
    } catch (UnreadableException unreadable) {
      throw unreadable;
    } catch (IOException e) {
      throw failed("read", e, 0);
    }
    return bytes == null ? List.of() : parse(bytes);
  }

  // The file's bytes, or null when there is no file.
  private byte[] read() throws IOException {
    try {
      // Checked first, as a file of any size would be read into memory whole.
      if (Files.size(file) > MAX_SIZE) {
        throw unreadable("it is larger than " + (MAX_SIZE >> 20) + " MB");
      }
      return Files.readAllBytes(file);
    } catch (NoSuchFileException missing) {
      return null;
    }
  }

  private List<ConnectionProfile> parse(byte[] bytes) throws UnreadableException {
    JsonNode root;
    try {
      root = JSON.readTree(bytes);
    } catch (JacksonException malformed) {
      // Only the place: Jackson's own message quotes the text, and with it what a user typed.
      var at = malformed.getLocation();
      throw unreadable(
          at == null
              ? "it is not valid JSON"
              : "it is not valid JSON at line " + at.getLineNr() + ", column " + at.getColumnNr());
    }
    if (root == null || !root.isObject()) {
      throw unreadable("it is not a JSON object");
    }

    var version = root.get("version");
    if (version == null) {
      throw unreadable("it has no version");
    }
    if (version.isInt() && version.intValue() > VERSION) {
      throw unreadable("it was written by a newer HooriDB (version "
          + version.intValue()
          + "); update HooriDB to read it");
    }
    // A value of the file goes into a message only where it is a number or was understood: a
    // hand-edited file may hold anything, a password too.
    if (!version.isInt()) {
      throw unreadable("its version is not a whole number");
    }
    if (version.intValue() != VERSION) {
      throw unreadable("its version " + version.intValue() + " is unknown");
    }

    // Fields this version does not know are ignored: a later HooriDB may add one without raising
    // the version, as long as an older one can do without it. The older one drops it on a save.
    var list = root.get("profiles");
    if (list == null || !list.isArray()) {
      throw unreadable("\"profiles\" is missing or not an array");
    }
    var profiles = new ArrayList<ConnectionProfile>();
    var ids = new HashSet<UUID>();
    for (int i = 0; i < list.size(); i++) {
      var where = "profile " + (i + 1);
      var node = list.get(i);
      if (!node.isObject()) {
        throw unreadable(where + " is not a JSON object");
      }
      var profile = new ConnectionProfile(
          id(node, where),
          text(node, where, "name"),
          provider(node, where),
          text(node, where, "host"),
          integer(node, where, "port"),
          text(node, where, "database"),
          text(node, where, "user"),
          tls(node, where),
          color(node, where),
          driverVersion(node, where));
      if (!ids.add(profile.id())) {
        throw unreadable(where + " has the id " + profile.id() + " of an earlier profile");
      }
      profiles.add(profile);
    }
    return profiles;
  }

  /**
   * Replaces the file with the profiles, creating the folder if it is missing. A crash leaves the
   * old file or the new one, never a part of it. A file that {@link #load} cannot understand is
   * kept as {@value #UNREADABLE} first, in place of an older copy. One save runs at a time; which of
   * two saves lands last is for the caller to order.
   *
   * @throws IllegalArgumentException if two of the profiles have the same id, which {@link #load}
   *     would refuse
   * @throws IOException if it could not be written, with a message as {@link #load} gives
   */
  public void save(List<ConnectionProfile> profiles) throws IOException {
    if (profiles.stream().map(ConnectionProfile::id).distinct().count() != profiles.size()) {
      throw new IllegalArgumentException("Two profiles have the same id");
    }
    var root = JSON.createObjectNode();
    root.put("version", VERSION);
    var list = root.putArray("profiles");
    for (var profile : profiles) {
      var object = list.addObject()
          .put("id", profile.id().toString())
          .put("name", profile.name())
          .put("provider", stored(profile.provider()))
          .put("host", profile.host())
          .put("port", profile.port())
          .put("database", profile.database())
          .put("user", profile.user())
          .put("tls", stored(profile.tls()));
      if (profile.color() != null) {
        object.put("color", stored(profile.color()));
      }
      if (profile.driverVersion() != null) {
        object.put("driverVersion", profile.driverVersion());
      }
    }
    var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(root));

    // Two saves at once would each find the unreadable file, and the second would copy the first
    // one's new file over the copy of it.
    synchronized (SAVING) {
      try {
        write(bytes);
      } catch (IOException e) {
        throw failed("save", e, bytes.capacity());
      }
    }
  }

  private void write(ByteBuffer bytes) throws IOException {
    Files.createDirectories(folder);
    byte[] old = null;
    try {
      old = read();
      if (old != null) {
        parse(old);
      }
    } catch (UnreadableException unreadable) {
      var copy = folder.resolve(UNREADABLE);
      if (old == null) { // too large to have been read
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
      } else {
        // The bytes that were refused, and not the file again, which may have changed since.
        Files.write(copy, old);
      }
    }
    // In the same folder, so that the move is a rename on the same file system.
    var temporary = Files.createTempFile(folder, FILE + ".", ".tmp");
    try {
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        while (bytes.hasRemaining()) {
          channel.write(bytes);
        }
        // On disk before the rename, or a crash could leave the new name on an empty file.
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private JsonNode field(
      JsonNode profile, String where, String name, Predicate<JsonNode> ok, String expected)
      throws UnreadableException {
    var value = profile.get(name);
    if (value == null) {
      throw unreadable(where + " has no \"" + name + "\"");
    }
    if (!ok.test(value)) {
      throw unreadable("in " + where + ", \"" + name + "\" is not " + expected);
    }
    return value;
  }

  private String text(JsonNode profile, String where, String name) throws UnreadableException {
    return field(profile, where, name, JsonNode::isString, "text").stringValue();
  }

  private int integer(JsonNode profile, String where, String name) throws UnreadableException {
    return field(profile, where, name, JsonNode::isInt, "a whole number").intValue();
  }

  private UUID id(JsonNode profile, String where) throws UnreadableException {
    var value = text(profile, where, "id");
    try {
      var id = UUID.fromString(value);
      // fromString also takes shortened forms such as "1-2-3-4-5".
      if (id.toString().equalsIgnoreCase(value)) {
        return id;
      }
    } catch (IllegalArgumentException notAUuid) {
      // Reported below.
    }
    throw unreadable("in " + where + ", \"id\" is not a UUID");
  }

  private Provider provider(JsonNode profile, String where) throws UnreadableException {
    var value = text(profile, where, "provider");
    for (var provider : Provider.values()) {
      if (stored(provider).equals(value)) {
        return provider;
      }
    }
    throw unreadable("in " + where + ", \"provider\" is not a database HooriDB knows");
  }

  private Tls tls(JsonNode profile, String where) throws UnreadableException {
    var value = text(profile, where, "tls");
    for (var tls : Tls.values()) {
      if (stored(tls).equals(value)) {
        return tls;
      }
    }
    throw unreadable("in " + where + ", \"tls\" is not \"off\", \"encrypted\" or \"verified\"");
  }

  // Left out for none, as in a file written before there were colors.
  private Color color(JsonNode profile, String where) throws UnreadableException {
    if (profile.get("color") == null) {
      return null;
    }
    var value = text(profile, where, "color");
    for (var color : Color.values()) {
      if (stored(color).equals(value)) {
        return color;
      }
    }
    throw unreadable("in " + where + ", \"color\" is not a color HooriDB knows");
  }

  // Left out for the recommended one, as in a file written before a version could be chosen.
  private String driverVersion(JsonNode profile, String where) throws UnreadableException {
    if (profile.get("driverVersion") == null) {
      return null;
    }
    var value = text(profile, where, "driverVersion");
    if (!Maven.isVersion(value)) {
      throw unreadable("in " + where + ", \"driverVersion\" is not a version HooriDB accepts");
    }
    return value;
  }

  // The names in the file, spelled out so that renaming a constant cannot change it.
  private static String stored(Provider provider) {
    return switch (provider) {
      case POSTGRESQL -> "postgresql";
      case MARIADB -> "mariadb";
      case MYSQL -> "mysql";
    };
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

  /**
   * A failure of the file system in HooriDB's words, from what Java can tell: the operating
   * system's own text is in the user's language, and often names no file.
   *
   * @param needed the bytes a save writes, to tell a full disk; 0 for a read
   */
  private IOException failed(String doing, IOException e, long needed) {
    String why = null;
    if (Files.isDirectory(file)) {
      why = "it is a folder, not a file";
    }
    for (var p = folder; why == null && p != null; p = p.getParent()) {
      if (Files.exists(p) && !Files.isDirectory(p)) {
        why = p + " is a file, not a folder";
      }
    }
    if (why == null) {
      why = switch (e) {
        case AccessDeniedException denied -> "access was denied";
        case NoSuchFileException missing -> "its folder does not exist";
        default ->
          full(needed)
              ? "the disk is full"
              : "the system reported " + e.getClass().getSimpleName();
      };
    }
    return new IOException("Cannot " + doing + " the connections in " + file + ": " + why + ".", e);
  }

  private boolean full(long needed) {
    try {
      return needed > 0 && Files.getFileStore(folder).getUsableSpace() < needed;
    } catch (IOException unknown) {
      return false;
    }
  }

  private UnreadableException unreadable(String why) {
    return new UnreadableException(file, why);
  }
}

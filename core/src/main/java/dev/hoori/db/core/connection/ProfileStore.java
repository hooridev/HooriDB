package dev.hoori.db.core.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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

  // The tree model with the field names spelt out here, so that renaming a component of the record
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
   */
  public List<ConnectionProfile> load() throws IOException {
    JsonNode root;
    try {
      root = JSON.readTree(Files.readAllBytes(file));
    } catch (NoSuchFileException missing) {
      return List.of();
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
    if (!version.isInt() || version.intValue() != VERSION) {
      throw unreadable("its version " + version + " is unknown");
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
          tls(node, where));
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
   * kept as {@value #UNREADABLE} first, in place of an older copy.
   */
  public void save(List<ConnectionProfile> profiles) throws IOException {
    var root = JSON.createObjectNode();
    root.put("version", VERSION);
    var list = root.putArray("profiles");
    for (var profile : profiles) {
      list.addObject()
          .put("id", profile.id().toString())
          .put("name", profile.name())
          .put("provider", stored(profile.provider()))
          .put("host", profile.host())
          .put("port", profile.port())
          .put("database", profile.database())
          .put("user", profile.user())
          .put("tls", stored(profile.tls()));
    }
    var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(root));

    Files.createDirectories(folder);
    try {
      load();
    } catch (UnreadableException unreadable) {
      Files.copy(file, folder.resolve(UNREADABLE), StandardCopyOption.REPLACE_EXISTING);
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
    throw unreadable("in " + where + ", \"id\" is not a UUID: \"" + value + "\"");
  }

  private Provider provider(JsonNode profile, String where) throws UnreadableException {
    var value = text(profile, where, "provider");
    for (var provider : Provider.values()) {
      if (stored(provider).equals(value)) {
        return provider;
      }
    }
    throw unreadable(where + " is for \"" + value + "\", a database HooriDB does not know");
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

  // The names in the file, spelt out so that renaming a constant cannot change it.
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

  private UnreadableException unreadable(String why) {
    return new UnreadableException(file, why);
  }
}

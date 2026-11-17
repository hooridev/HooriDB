package dev.hoori.db.core.connection;

import dev.hoori.db.core.driver.Maven;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

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
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  private static final int VERSION = 1;

  /** A larger file is refused unread; thousands of profiles are far below it. */
  static final long MAX_SIZE = 16L << 20;

  private final JsonFile file;

  public ProfileStore(Path folder) {
    this.file = new JsonFile(folder, FILE, "connections", MAX_SIZE);
  }

  /**
   * A file of HooriDB's that cannot be understood, of profiles, consoles or history; its message
   * says why and names the file.
   */
  public static final class UnreadableException extends IOException {
    UnreadableException(String what, Path file, String why) {
      super("Cannot read the " + what + " in " + file + ": " + why + ".");
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
    var bytes = file.load();
    return bytes == null ? List.of() : parse(bytes);
  }

  private List<ConnectionProfile> parse(byte[] bytes) throws UnreadableException {
    var root = file.root(bytes, VERSION);
    // Fields this version does not know are ignored: a later HooriDB may add one without raising
    // the version, as long as an older one can do without it. The older one drops it on a save.
    var list = file.objects(root, "profiles", "profile");
    var profiles = new ArrayList<ConnectionProfile>();
    var ids = new HashSet<UUID>();
    for (int i = 0; i < list.size(); i++) {
      var where = "profile " + (i + 1);
      var node = list.get(i);
      var profile = new ConnectionProfile(
          file.uuid(node, where, "id"),
          file.text(node, where, "name"),
          provider(node, where),
          file.text(node, where, "host"),
          file.integer(node, where, "port"),
          file.text(node, where, "database"),
          file.text(node, where, "user"),
          tls(node, where),
          color(node, where),
          driverVersion(node, where));
      if (!ids.add(profile.id())) {
        throw file.unreadable(where + " has the id " + profile.id() + " of an earlier profile");
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
    var root = JsonFile.Json.MAPPER.createObjectNode();
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
    file.save(JsonFile.bytes(root), this::parse);
  }

  private Provider provider(JsonNode profile, String where) throws UnreadableException {
    var value = file.text(profile, where, "provider");
    for (var provider : Provider.values()) {
      if (stored(provider).equals(value)) {
        return provider;
      }
    }
    throw file.unreadable("in " + where + ", \"provider\" is not a database HooriDB knows");
  }

  private Tls tls(JsonNode profile, String where) throws UnreadableException {
    var value = file.text(profile, where, "tls");
    for (var tls : Tls.values()) {
      if (stored(tls).equals(value)) {
        return tls;
      }
    }
    throw file.unreadable(
        "in " + where + ", \"tls\" is not \"off\", \"encrypted\" or \"verified\"");
  }

  // Left out for none, as in a file written before there were colors.
  private Color color(JsonNode profile, String where) throws UnreadableException {
    if (profile.get("color") == null) {
      return null;
    }
    var value = file.text(profile, where, "color");
    for (var color : Color.values()) {
      if (stored(color).equals(value)) {
        return color;
      }
    }
    throw file.unreadable("in " + where + ", \"color\" is not a color HooriDB knows");
  }

  // Left out for the recommended one, as in a file written before a version could be chosen.
  private String driverVersion(JsonNode profile, String where) throws UnreadableException {
    if (profile.get("driverVersion") == null) {
      return null;
    }
    var value = file.text(profile, where, "driverVersion");
    if (!Maven.isVersion(value)) {
      throw file.unreadable("in " + where + ", \"driverVersion\" is not a version HooriDB accepts");
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
}

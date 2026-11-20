package dev.hoori.db.core.connection;

import dev.hoori.db.core.connection.WorkspaceStore.UnreadableException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A JSON file of HooriDB's in a folder, read whole and replaced whole: what the stores of the
 * workspace, the consoles and the history share. A JSON object in UTF-8 with a {@code version}.
 * Every call blocks on the disk.
 */
final class JsonFile {

  private static final Object SAVING = new Object();

  // The tree model with the field names spelled out by each store, so that renaming a component of
  // a record cannot change a file. Each field's node type is checked, so nothing is coerced.
  // Made at the first load or save, which run off the app's thread: making it takes a few hundred
  // milliseconds, which the app's start, where the stores are made, would wait for.
  static final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
  }

  /** What a save checks an old file with before it replaces it: it throws where it cannot read it. */
  interface Check {
    void parse(byte[] bytes) throws UnreadableException;
  }

  private interface Content {
    void write(FileChannel channel) throws IOException;
  }

  private final Path folder;
  final Path file;
  private final String name;
  private final String what; // "connections", in messages
  private final long maxSize;

  JsonFile(Path folder, String name, String what, long maxSize) {
    this.folder = folder;
    this.file = folder.resolve(name);
    this.name = name;
    this.what = what;
    this.maxSize = maxSize;
  }

  /** Where {@link #save} keeps a copy of a file that could not be understood, before it replaces it. */
  static String setAside(String name) {
    return name + ".unreadable";
  }

  /** The file's bytes, or null when there is none; a failure says so in HooriDB's words. */
  byte[] load() throws IOException {
    try {
      return read();
    } catch (UnreadableException unreadable) {
      throw unreadable;
    } catch (IOException e) {
      throw failed("read", e, 0);
    }
  }

  // The file's bytes, or null when there is no file.
  private byte[] read() throws IOException {
    try {
      // Checked first, as a file of any size would be read into memory whole.
      if (Files.size(file) > maxSize) {
        throw unreadable("it is larger than " + (maxSize >> 20) + " MB");
      }
      return Files.readAllBytes(file);
    } catch (NoSuchFileException missing) {
      return null;
    }
  }

  /** The file's JSON object, of the version given, which alone this HooriDB reads. */
  JsonNode root(byte[] bytes, int known) throws UnreadableException {
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(bytes);
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
    if (version.isInt() && version.intValue() > known) {
      throw unreadable("it was written by a newer HooriDB (version "
          + version.intValue()
          + "); update HooriDB to read it");
    }
    // A value of the file goes into a message only where it is a number or was understood: a
    // hand-edited file may hold anything, a password too.
    if (!version.isInt()) {
      throw unreadable("its version is not a whole number");
    }
    if (version.intValue() != known) {
      throw unreadable("its version " + version.intValue() + " is unknown");
    }
    return root;
  }

  /** The array of the root's field, each of its items an object. */
  JsonNode objects(JsonNode root, String field, String item) throws UnreadableException {
    var list = root.get(field);
    if (list == null || !list.isArray()) {
      throw unreadable("\"" + field + "\" is missing or not an array");
    }
    for (int i = 0; i < list.size(); i++) {
      if (!list.get(i).isObject()) {
        throw unreadable(item + " " + (i + 1) + " is not a JSON object");
      }
    }
    return list;
  }

  /** The root as bytes to save. */
  static ByteBuffer bytes(JsonNode root) {
    return ByteBuffer.wrap(Json.MAPPER.writeValueAsBytes(root));
  }

  /**
   * Replaces the file with the bytes, creating the folder if it is missing. A crash leaves the old
   * file or the new one, never a part of it. A file that {@code check} cannot understand is kept as
   * {@link #setAside} first, in place of an older copy. Both are readable and writable by their
   * owner alone where the file system has POSIX permissions. One save runs at a time; which of two
   * saves lands last is for the caller to order.
   */
  void save(ByteBuffer bytes, Check check) throws IOException {
    // Two saves at once would each find the unreadable file, and the second would copy the first
    // one's new file over the copy of it.
    synchronized (SAVING) {
      try {
        write(bytes, check);
      } catch (IOException e) {
        throw failed("save", e, bytes.capacity());
      }
    }
  }

  private void write(ByteBuffer bytes, Check check) throws IOException {
    Files.createDirectories(folder);
    byte[] old = null;
    try {
      old = read();
      if (old != null) {
        check.parse(old);
      }
    } catch (UnreadableException unreadable) {
      var copy = folder.resolve(setAside(name));
      if (old == null) { // too large to have been read
        replace(copy, channel -> {
          try (var in = Files.newInputStream(file)) {
            in.transferTo(Channels.newOutputStream(channel));
          }
        });
      } else {
        // The bytes that were refused, and not the file again, which may have changed since.
        var refused = ByteBuffer.wrap(old);
        replace(copy, channel -> all(channel, refused));
      }
    }
    replace(file, channel -> all(channel, bytes));
  }

  private static void all(FileChannel channel, ByteBuffer bytes) throws IOException {
    while (bytes.hasRemaining()) {
      channel.write(bytes);
    }
  }

  private void replace(Path target, Content content) throws IOException {
    // In the same folder, so that the move is a rename on the same file system; the target takes
    // the permissions of the temporary file, which a history holding secrets must keep to its
    // owner.
    FileAttribute<?>[] ownerOnly =
        folder.getFileSystem().supportedFileAttributeViews().contains("posix")
            ? new FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            }
            : new FileAttribute<?>[0];
    var temporary = Files.createTempFile(folder, name + ".", ".tmp", ownerOnly);
    try {
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        content.write(channel);
        // On disk before the rename, or a crash could leave the new name on an empty file.
        channel.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private JsonNode field(
      JsonNode object, String where, String field, Predicate<JsonNode> ok, String expected)
      throws UnreadableException {
    var value = object.get(field);
    if (value == null) {
      throw unreadable(where + " has no \"" + field + "\"");
    }
    if (!ok.test(value)) {
      throw unreadable("in " + where + ", \"" + field + "\" is not " + expected);
    }
    return value;
  }

  String text(JsonNode object, String where, String field) throws UnreadableException {
    return field(object, where, field, JsonNode::isString, "text").stringValue();
  }

  boolean bool(JsonNode object, String where, String field) throws UnreadableException {
    return field(object, where, field, JsonNode::isBoolean, "true or false").booleanValue();
  }

  /** An array, whose items are for the caller to check. */
  JsonNode array(JsonNode object, String where, String field) throws UnreadableException {
    return field(object, where, field, JsonNode::isArray, "an array");
  }

  int integer(JsonNode object, String where, String field) throws UnreadableException {
    return field(object, where, field, JsonNode::isInt, "a whole number").intValue();
  }

  /** A whole number of 0 or more that fits a long. */
  long count(JsonNode object, String where, String field) throws UnreadableException {
    return field(
            object,
            where,
            field,
            n -> n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0,
            "a whole number of 0 or more")
        .longValue();
  }

  UUID uuid(JsonNode object, String where, String field) throws UnreadableException {
    var value = text(object, where, field);
    try {
      var id = UUID.fromString(value);
      // fromString also takes shortened forms such as "1-2-3-4-5".
      if (id.toString().equalsIgnoreCase(value)) {
        return id;
      }
    } catch (IllegalArgumentException notAUuid) {
      // Reported below.
    }
    throw unreadable("in " + where + ", \"" + field + "\" is not a UUID");
  }

  /**
   * A failure of the file system in HooriDB's words, from what Java can tell: the operating
   * system's own text is in the user's language, and often names no file.
   *
   * @param needed the bytes a save writes, to tell a full disk; 0 for a read
   */
  IOException failed(String doing, IOException e, long needed) {
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
    return new IOException(
        "Cannot " + doing + " the " + what + " in " + file + ": " + why + ".", e);
  }

  private boolean full(long needed) {
    try {
      return needed > 0 && Files.getFileStore(folder).getUsableSpace() < needed;
    } catch (IOException unknown) {
      return false;
    }
  }

  UnreadableException unreadable(String why) {
    return new UnreadableException(what, file, why);
  }
}

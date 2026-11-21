package dev.hoori.db.core.connection;

import com.rethinkj.updater.Version;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * What updates keep, in {@value #FILE} in a folder, beside the profiles. Every call blocks on the
 * disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version}, and where there is one each: the
 * {@code channel} ({@code "stable"} or {@code "nightly"}), the version {@code skipped}, and the
 * {@code attempt} as an object of {@code version}, {@code file} and {@code sha256}.
 */
public final class UpdateStore {

  public static final String FILE = "updates.json";

  private static final int VERSION = 1;
  private static final long MAX_SIZE = 1L << 20;

  // The names of the file's fields, and of where a value is, in messages.
  private static final String CHANNEL = "channel";
  private static final String SKIPPED = "skipped";
  private static final String ATTEMPT = "attempt";
  private static final String THE_FILE = "the file";
  private static final String THE_ATTEMPT = "the attempt";

  private final JsonFile json;

  public UpdateStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "update settings", MAX_SIZE);
  }

  /**
   * What was saved, or {@link StoredUpdates#NONE} when there is no file or it cannot be
   * understood; the next save replaces such a file, and keeps it as {@link JsonFile#setAside}.
   *
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load} gives
   */
  public StoredUpdates load() throws IOException {
    try {
      var bytes = json.load();
      return bytes == null ? StoredUpdates.NONE : parse(bytes);
    } catch (WorkspaceStore.UnreadableException _) {
      return StoredUpdates.NONE;
    }
  }

  private StoredUpdates parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var root = json.root(bytes, VERSION);
    var channel = root.has(CHANNEL) ? channel(root) : null;
    var skipped = root.has(SKIPPED) ? version(root, THE_FILE, SKIPPED) : null;
    var attempt = root.has(ATTEMPT) ? attempt(root.get(ATTEMPT)) : null;
    return new StoredUpdates(channel, skipped, attempt);
  }

  private Channel channel(JsonNode root) throws WorkspaceStore.UnreadableException {
    return switch (json.text(root, THE_FILE, CHANNEL)) {
      case "stable" -> Channel.STABLE;
      case "nightly" -> Channel.NIGHTLY;
      default -> throw json.unreadable("its \"channel\" is neither stable nor nightly");
    };
  }

  private StoredUpdates.Attempt attempt(JsonNode node) throws WorkspaceStore.UnreadableException {
    if (!node.isObject()) {
      throw json.unreadable("its \"attempt\" is not a JSON object");
    }
    Path path;
    try {
      path = Path.of(json.text(node, THE_ATTEMPT, "file"));
    } catch (InvalidPathException _) {
      throw json.unreadable("in the attempt, \"file\" is not a path");
    }
    return new StoredUpdates.Attempt(
        version(node, THE_ATTEMPT, "version"), path, json.text(node, THE_ATTEMPT, "sha256"));
  }

  private Version version(JsonNode node, String where, String field)
      throws WorkspaceStore.UnreadableException {
    try {
      return Version.parse(json.text(node, where, field));
    } catch (IllegalArgumentException _) {
      throw json.unreadable("in " + where + ", \"" + field + "\" is not a version");
    }
  }

  /**
   * Replaces the file, as {@link WorkspaceStore#save} replaces its own.
   *
   * @throws IOException if it could not be written
   */
  public void save(StoredUpdates updates) throws IOException {
    var root = JsonFile.newRoot(VERSION);
    if (updates.channel() != null) {
      root.put(CHANNEL, updates.channel().name().toLowerCase(Locale.ROOT));
    }
    if (updates.skipped() != null) {
      root.put(SKIPPED, updates.skipped().toString());
    }
    if (updates.attempt() != null) {
      var attempt = updates.attempt();
      root.putObject(ATTEMPT)
          .put("version", attempt.version().toString())
          .put("file", attempt.file().toString())
          .put("sha256", attempt.sha256());
    }
    json.save(JsonFile.bytes(root), this::parse);
  }
}

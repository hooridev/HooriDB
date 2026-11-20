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

  private final JsonFile file;

  public UpdateStore(Path folder) {
    this.file = new JsonFile(folder, FILE, "update settings", MAX_SIZE);
  }

  /**
   * What was saved, or {@link StoredUpdates#NONE} when there is no file or it cannot be
   * understood; the next save replaces such a file, and keeps it as {@link JsonFile#setAside}.
   *
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load} gives
   */
  public StoredUpdates load() throws IOException {
    try {
      var bytes = file.load();
      return bytes == null ? StoredUpdates.NONE : parse(bytes);
    } catch (WorkspaceStore.UnreadableException unreadable) {
      return StoredUpdates.NONE;
    }
  }

  private StoredUpdates parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var root = file.root(bytes, VERSION);
    Channel channel = null;
    if (root.has("channel")) {
      var name = file.text(root, "the file", "channel");
      channel = switch (name) {
        case "stable" -> Channel.STABLE;
        case "nightly" -> Channel.NIGHTLY;
        default -> throw file.unreadable("its \"channel\" is neither stable nor nightly");
      };
    }
    var skipped = root.has("skipped") ? version(root, "the file", "skipped") : null;
    StoredUpdates.Attempt attempt = null;
    if (root.has("attempt")) {
      var node = root.get("attempt");
      if (!node.isObject()) {
        throw file.unreadable("its \"attempt\" is not a JSON object");
      }
      Path path;
      try {
        path = Path.of(file.text(node, "the attempt", "file"));
      } catch (InvalidPathException notAPath) {
        throw file.unreadable("in the attempt, \"file\" is not a path");
      }
      attempt = new StoredUpdates.Attempt(
          version(node, "the attempt", "version"), path, file.text(node, "the attempt", "sha256"));
    }
    return new StoredUpdates(channel, skipped, attempt);
  }

  private Version version(JsonNode node, String where, String field)
      throws WorkspaceStore.UnreadableException {
    try {
      return Version.parse(file.text(node, where, field));
    } catch (IllegalArgumentException notAVersion) {
      throw file.unreadable("in " + where + ", \"" + field + "\" is not a version");
    }
  }

  /**
   * Replaces the file, as {@link WorkspaceStore#save} replaces its own.
   *
   * @throws IOException if it could not be written
   */
  public void save(StoredUpdates updates) throws IOException {
    var root = JsonFile.Json.MAPPER.createObjectNode();
    root.put("version", VERSION);
    if (updates.channel() != null) {
      root.put("channel", updates.channel().name().toLowerCase(Locale.ROOT));
    }
    if (updates.skipped() != null) {
      root.put("skipped", updates.skipped().toString());
    }
    if (updates.attempt() != null) {
      var attempt = updates.attempt();
      root.putObject("attempt")
          .put("version", attempt.version().toString())
          .put("file", attempt.file().toString())
          .put("sha256", attempt.sha256());
    }
    file.save(JsonFile.bytes(root), this::parse);
  }
}

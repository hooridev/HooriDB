package dev.hoori.db.core.connection;

import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/**
 * The statement log, of every connection, in {@value #FILE} in a folder beside the profiles: what
 * HooriDB sent, one entry a line, the oldest first, appended in batches. Past {@link #LIMIT} the
 * file becomes {@value #OLDER}, in place of the one before, and a new one begins; so at most twice
 * the limit is kept. Whether logging is on is kept in {@value #SETTINGS}. Every call blocks on the
 * disk, so none of them belongs on the app's thread; one runs at a time.
 *
 * <p>Each line is a JSON object in UTF-8 (JSON Lines). The files are readable and writable by their
 * owner alone where the file system has POSIX permissions, as a statement may hold what is not
 * the reader's business; a password in one is masked before it gets here. A line that cannot be
 * understood, as one cut short by a crash, is skipped.
 */
public final class StatementLogStore {

  public static final String FILE = "statement-log.jsonl";
  public static final String OLDER = "statement-log.1.jsonl";
  public static final String SETTINGS = "statement-log.json";

  /** The size a file grows to before it becomes the older one. */
  public static final long LIMIT = 8L << 20;

  private static final int VERSION = 1;

  private final Path folder;
  private final JsonFile file; // for its readers and its messages
  private final JsonFile settings;

  public StatementLogStore(Path folder) {
    this.folder = folder;
    this.file = new JsonFile(folder, FILE, "statement log", LIMIT);
    this.settings = new JsonFile(folder, SETTINGS, "statement log's settings", 1L << 20);
  }

  /**
   * Adds the entries at the end, the oldest first, beginning a new file first where they would take
   * the file past {@link #LIMIT}.
   *
   * @throws IOException if they could not be written, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public synchronized void append(List<Logged> entries) throws IOException {
    if (entries.isEmpty()) {
      return;
    }
    var lines = new ByteArrayOutputStream();
    for (var entry : entries) {
      lines.writeBytes(LINE.writeValueAsBytes(node(entry)));
      lines.write('\n');
    }
    var bytes = ByteBuffer.wrap(lines.toByteArray());
    try {
      Files.createDirectories(folder);
      var path = file.file;
      if (Files.exists(path) && Files.size(path) + bytes.remaining() > LIMIT) {
        Files.move(path, folder.resolve(OLDER), StandardCopyOption.REPLACE_EXISTING);
      }
      // A line cut short, as by a crash, ends before the new ones, which it would spoil.
      boolean cut = cutShort(path);
      try (var channel = FileChannel.open(
          path,
          Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
          JsonFile.ownerOnly(folder))) {
        if (cut) {
          channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
        }
        while (bytes.hasRemaining()) {
          channel.write(bytes);
        }
      }
    } catch (IOException e) {
      throw file.failed("save", e, bytes.capacity());
    }
  }

  // Whether the file's last line has no line break; not where there is no file or it is empty.
  private static boolean cutShort(Path path) throws IOException {
    try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size == 0) {
        return false;
      }
      var last = ByteBuffer.allocate(1);
      channel.read(last, size - 1);
      return last.get(0) != '\n';
    } catch (NoSuchFileException none) {
      return false;
    }
  }

  /**
   * The last entries, at most {@code most}, the oldest first: of the file, and of the older one
   * where the file has fewer.
   *
   * @throws IOException if they could not be read, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public synchronized List<Logged> recent(int most) throws IOException {
    var last = new ArrayDeque<Logged>();
    for (var name : List.of(FILE, OLDER)) {
      if (last.size() >= most) {
        break;
      }
      List<String> lines;
      try {
        lines = Files.readAllLines(folder.resolve(name), StandardCharsets.UTF_8);
      } catch (NoSuchFileException none) {
        continue;
      } catch (IOException e) {
        throw file.failed("read", e, 0);
      }
      for (int i = lines.size() - 1; i >= 0 && last.size() < most; i--) {
        var entry = parse(lines.get(i));
        if (entry != null) {
          last.addFirst(entry);
        }
      }
    }
    return List.copyOf(last);
  }

  /** Deletes the log, both files. */
  public synchronized void clear() throws IOException {
    try {
      Files.deleteIfExists(folder.resolve(OLDER));
      Files.deleteIfExists(file.file);
    } catch (IOException e) {
      throw file.failed("delete", e, 0);
    }
  }

  /**
   * Whether logging is on: as last saved, and on where nothing was, or where the settings cannot be
   * understood.
   */
  public synchronized boolean enabled() throws IOException {
    try {
      var bytes = settings.load();
      return bytes == null || settings.bool(settings.root(bytes, VERSION), "the file", "enabled");
    } catch (WorkspaceStore.UnreadableException unreadable) {
      return true;
    }
  }

  /** Keeps whether logging is on. */
  public synchronized void enable(boolean on) throws IOException {
    var root = JsonFile.Json.MAPPER.createObjectNode().put("version", VERSION).put("enabled", on);
    settings.save(JsonFile.bytes(root), bytes -> settings.root(bytes, VERSION));
  }

  // One line each, without the indentation of the other files.
  private static final ObjectWriter LINE =
      JsonFile.Json.MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);

  // The names in the file, spelled out so that renaming a record cannot change it.
  private static ObjectNode node(Logged entry) {
    var sent = entry.sent();
    var node = JsonFile.Json.MAPPER
        .createObjectNode()
        .put("connection", entry.connection().toString())
        .put("at", sent.at().toString())
        .put("purpose", sent.purpose().name().toLowerCase(Locale.ROOT))
        .put("text", sent.text())
        .put("took", sent.took().toString());
    if (sent.database() != null) {
      node.put("database", sent.database());
    }
    switch (sent.outcome()) {
      case Outcome.Rows r ->
        node.put("outcome", "rows").put("count", Math.max(0, r.count())).put("more", r.more());
      case Outcome.Changed c -> node.put("outcome", "changed").put("count", Math.max(0, c.count()));
      case Outcome.Done d -> node.put("outcome", "done");
      case Outcome.Failed f -> node.put("outcome", "failed").put("message", f.message());
      case Outcome.Cancelled c -> node.put("outcome", "cancelled");
    }
    return node;
  }

  // The entry of a line, or null where it cannot be understood.
  private Logged parse(String line) {
    if (line.isBlank()) {
      return null;
    }
    try {
      var node = JsonFile.Json.MAPPER.readTree(line);
      if (!node.isObject()) {
        return null;
      }
      var where = "an entry";
      var database = node.has("database") ? file.text(node, where, "database") : null;
      var sent = new Sent(
          Instant.parse(file.text(node, where, "at")),
          purpose(file.text(node, where, "purpose")),
          database,
          file.text(node, where, "text"),
          Duration.parse(file.text(node, where, "took")),
          outcome(node, where));
      return new Logged(file.uuid(node, where, "connection"), sent);
    } catch (JacksonException
        | WorkspaceStore.UnreadableException
        | DateTimeParseException
        | IllegalArgumentException e) {
      return null;
    }
  }

  private static Purpose purpose(String name) {
    return Purpose.valueOf(name.toUpperCase(Locale.ROOT));
  }

  private Outcome outcome(JsonNode node, String where) throws WorkspaceStore.UnreadableException {
    return switch (file.text(node, where, "outcome")) {
      case "rows" ->
        new Outcome.Rows(file.count(node, where, "count"), file.bool(node, where, "more"));
      case "changed" -> new Outcome.Changed(file.count(node, where, "count"));
      case "done" -> Outcome.DONE;
      case "failed" -> new Outcome.Failed(file.text(node, where, "message"));
      case "cancelled" -> new Outcome.Cancelled();
      default -> throw file.unreadable("in " + where + ", \"outcome\" is not one HooriDB knows");
    };
  }
}

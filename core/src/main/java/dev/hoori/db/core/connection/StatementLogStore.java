package dev.hoori.db.core.connection;

import dev.hoori.db.core.log.Logged;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.log.Sent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
   * Adds the entries at the end, the oldest first, beginning a new file first where the next of
   * them would take the file past {@link #LIMIT}. An entry that alone would is kept without its
   * text, as {@link #TOO_LONG}. A file that is there already is made its owner's alone first. Each
   * entry is encoded as it is written, so that at most a file's worth waits in memory.
   *
   * @throws Unwritten if not all of them could be written, with how many were, and a message as
   *     {@link WorkspaceStore#load} gives
   */
  public synchronized void append(List<Logged> entries) throws Unwritten {
    int written = 0;
    long total = 0;
    var path = file.file;
    byte[] line = entries.isEmpty() ? null : line(entries.getFirst());
    try {
      Files.createDirectories(folder);
      int next = 0;
      while (line != null) {
        ownerOnly(path);
        long size = Files.exists(path) ? Files.size(path) : 0;
        // A line cut short, as by a crash, ends before the new ones, which it would spoil.
        boolean cut = cutShort(path);
        if (size > 0 && size + (cut ? 1 : 0) + line.length > LIMIT) {
          Files.move(path, folder.resolve(OLDER), StandardCopyOption.REPLACE_EXISTING);
          size = 0;
          cut = false;
        }
        long room = LIMIT - size - (cut ? 1 : 0);
        var chunk = new ByteArrayOutputStream();
        int count = 0;
        do {
          chunk.writeBytes(line);
          total += line.length;
          count++;
          next++;
          line = next < entries.size() ? line(entries.get(next)) : null;
        } while (line != null && chunk.size() + line.length <= room);
        write(path, chunk.toByteArray(), cut);
        written += count;
      }
    } catch (IOException e) {
      throw new Unwritten(
          file.failed("save", e, total + (line == null ? 0 : line.length)), written);
    }
  }

  /** The entries an {@link #append} wrote before it failed, the first ones of those it was given. */
  public static final class Unwritten extends IOException {

    private final int written;

    Unwritten(IOException failed, int written) {
      super(failed.getMessage(), failed.getCause());
      this.written = written;
    }

    /** How many of the entries were written, all of them whole. */
    public int written() {
      return written;
    }
  }

  /** What an entry's text becomes where it alone would take a file past the limit. */
  public static final String TOO_LONG = "/* Not kept: longer than the statement log keeps. */";

  // The entry's line, with its line break; without its text, or its message, where it is too long.
  private static byte[] line(Logged entry) {
    var line = encoded(entry);
    if (line.length <= LIMIT) {
      return line;
    }
    var sent = entry.sent();
    var outcome =
        sent.outcome() instanceof Outcome.Failed ? new Outcome.Failed(TOO_LONG) : sent.outcome();
    return encoded(new Logged(
        entry.connection(),
        new Sent(sent.at(), sent.purpose(), sent.database(), TOO_LONG, sent.took(), outcome)));
  }

  private static byte[] encoded(Logged entry) {
    var json = Lines.LINE.writeValueAsBytes(node(entry));
    var line = Arrays.copyOf(json, json.length + 1);
    line[json.length] = '\n';
    return line;
  }

  // Makes the file, where it is there, its owner's alone: the permissions of a new one are given as
  // it is made, and one restored from elsewhere may be wider.
  private void ownerOnly(Path path) throws IOException {
    if (Files.exists(path)
        && folder.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
  }

  // Appends the bytes to the file, owner-only, after a line break where a line was cut short.
  private void write(Path path, byte[] bytes, boolean cut) throws IOException {
    try (var channel = FileChannel.open(
        path,
        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND),
        JsonFile.ownerOnly(folder))) {
      if (cut) {
        channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
      }
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
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
   * The last entries, at most {@code most}, the oldest first by when they were sent: of the file,
   * and of the older one where the file has fewer.
   *
   * @throws IOException if they could not be read, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public synchronized List<Logged> recent(int most) throws IOException {
    var last = new ArrayList<Logged>();
    for (var name : List.of(FILE, OLDER)) {
      if (last.size() >= most) {
        break;
      }
      byte[] bytes;
      try {
        bytes = tail(folder.resolve(name));
      } catch (NoSuchFileException none) {
        continue;
      } catch (IOException e) {
        throw file.failed("read", e, 0);
      }
      // Line by line from the end, each decoded alone, so that one cut short in a character
      // spoils itself only.
      int end = bytes.length;
      while (end > 0 && last.size() < most) {
        int start = end;
        while (start > 0 && bytes[start - 1] != '\n') {
          start--;
        }
        var entry = parse(decoded(bytes, start, end));
        if (entry != null) {
          last.add(entry);
        }
        end = start - 1;
      }
    }
    // Written in batches as they ended, which may not be the order they were sent in.
    last.sort(Comparator.comparing(e -> e.sent().at()));
    return List.copyOf(last);
  }

  // The file's last LIMIT bytes, from a line's start: all of a file HooriDB wrote, and the end of
  // one that grew past it elsewhere.
  private static byte[] tail(Path path) throws IOException {
    try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
      long size = channel.size();
      long from = Math.max(0, size - LIMIT);
      var buffer = ByteBuffer.allocate((int) (size - from));
      while (buffer.hasRemaining() && channel.read(buffer, from + buffer.position()) >= 0) {
        // read on
      }
      var bytes = Arrays.copyOf(buffer.array(), buffer.position());
      if (from == 0) {
        return bytes;
      }
      int start = 0;
      while (start < bytes.length && bytes[start] != '\n') {
        start++;
      }
      return Arrays.copyOfRange(bytes, Math.min(bytes.length, start + 1), bytes.length);
    }
  }

  // The line's text, or null where it is not UTF-8.
  private static String decoded(byte[] bytes, int from, int to) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, from, to - from))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
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

  // One line each, without the indentation of the other files; made with the first line, off the
  // thread that makes the store, as the mapper takes a while.
  private static final class Lines {
    static final ObjectWriter LINE =
        JsonFile.Json.MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT);
  }

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
    if (line == null || line.isBlank()) {
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

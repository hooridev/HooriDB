package dev.hoori.db.core.connection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The layout the app remembers on this machine, kept in {@value #FILE} in a folder, beside the
 * workspace. Every call blocks on the disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version}, and where there is one each: {@code
 * window} as opaque text, {@code sidebar} as a number, and {@code statementLog} as an object of
 * {@code open}, {@code height} and {@code detail}. A field whose value is of the wrong type or out
 * of range is dropped to null, and any failure to read the file yields {@link UiState#EMPTY}.
 */
public final class UiStateStore {

  public static final String FILE = "ui-state.json";

  private static final int VERSION = 1;

  /** A larger file is refused unread, and a save of more refused. */
  static final long MAX_SIZE = 1L << 20;

  private static final String WINDOW = "window";
  private static final String SIDEBAR = "sidebar";
  private static final String STATEMENT_LOG = "statementLog";
  private static final String OPEN = "open";
  private static final String HEIGHT = "height";
  private static final String DETAIL = "detail";

  private final JsonFile json;

  public UiStateStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "UI state", MAX_SIZE);
  }

  /**
   * What was saved, or {@link UiState#EMPTY} when there is no file or it cannot be read or
   * understood.
   */
  public UiState load() {
    try {
      var bytes = json.load();
      return bytes == null ? UiState.EMPTY : parse(bytes);
    } catch (IOException _) {
      return UiState.EMPTY;
    }
  }

  private UiState parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var root = json.root(bytes, VERSION);
    var window = text(root, WINDOW);
    var sidebar = size(root, SIDEBAR);
    Boolean logOpen = null;
    Float logHeight = null;
    Float logDetail = null;
    var log = root.get(STATEMENT_LOG);
    if (log != null && log.isObject()) {
      logOpen = bool(log, OPEN);
      logHeight = size(log, HEIGHT);
      logDetail = detail(log, DETAIL);
    }
    return new UiState(window, sidebar, logOpen, logHeight, logDetail);
  }

  private static String text(JsonNode object, String field) {
    var node = object.get(field);
    return node != null && node.isString() ? node.stringValue() : null;
  }

  private static Boolean bool(JsonNode object, String field) {
    var node = object.get(field);
    return node != null && node.isBoolean() ? node.booleanValue() : null;
  }

  private static Float size(JsonNode object, String field) {
    var node = object.get(field);
    if (node == null || !node.isNumber()) {
      return null;
    }
    var value = node.numberValue().floatValue();
    return UiState.validSize(value) ? value : null;
  }

  private static Float detail(JsonNode object, String field) {
    var node = object.get(field);
    if (node == null || !node.isNumber()) {
      return null;
    }
    var value = node.numberValue().floatValue();
    return UiState.validDetail(value) ? value : null;
  }

  /**
   * Replaces the file with the non-null fields of {@code state}, as {@link WorkspaceStore#save}
   * replaces its own.
   *
   * @throws IOException if it could not be written
   */
  public void save(UiState state) throws IOException {
    Objects.requireNonNull(state, "state");
    var root = JsonFile.newRoot(VERSION);
    if (state.window() != null) {
      root.put(WINDOW, state.window());
    }
    if (state.sidebar() != null) {
      root.put(SIDEBAR, state.sidebar());
    }
    if (state.logOpen() != null || state.logHeight() != null || state.logDetail() != null) {
      var log = root.putObject(STATEMENT_LOG);
      if (state.logOpen() != null) {
        log.put(OPEN, state.logOpen());
      }
      if (state.logHeight() != null) {
        log.put(HEIGHT, state.logHeight());
      }
      if (state.logDetail() != null) {
        log.put(DETAIL, state.logDetail());
      }
    }
    json.save(JsonFile.bytes(root), this::parse);
  }
}

package dev.hoori.db.core.connection;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * The user's settings, kept in {@value #FILE} in a folder, beside the workspace. Every call blocks
 * on the disk, so none of them belongs on the app's thread.
 *
 * <p>The file is a JSON object in UTF-8 with a {@code version} and only the keys whose values
 * differ from {@link Settings#DEFAULT}: {@code theme}, {@code accent}, {@code density}, {@code
 * highlightOccurrences}, {@code keywordCase}, {@code checkForUpdates}, {@code
 * connectTimeoutSeconds}, {@code newConnectionsReadOnly}, {@code commitMode}, {@code timeZone},
 * {@code copyNull}, {@code documentView}, {@code keepHistory} and {@code monitorIntervalSeconds}.
 * Unknown keys are ignored; a key whose value is of the wrong type, unknown or out of range falls
 * back to its default and is reported by its name alone.
 */
public final class SettingsStore {

  public static final String FILE = "settings.json";

  /** Where {@link #save} keeps a copy of a file that {@link #load} could not understand. */
  public static final String UNREADABLE = JsonFile.setAside(FILE);

  private static final int VERSION = 1;

  /** A larger file is refused unread, and a save of more refused. */
  static final long MAX_SIZE = 1L << 20;

  // The names of the file's fields, spelled out so that renaming a component cannot change it.
  private static final String THEME = "theme";
  private static final String ACCENT = "accent";
  private static final String DENSITY = "density";
  private static final String HIGHLIGHT_OCCURRENCES = "highlightOccurrences";
  private static final String KEYWORD_CASE = "keywordCase";
  private static final String CHECK_FOR_UPDATES = "checkForUpdates";
  private static final String CONNECT_TIMEOUT_SECONDS = "connectTimeoutSeconds";
  private static final String NEW_CONNECTIONS_READ_ONLY = "newConnectionsReadOnly";
  private static final String COMMIT_MODE = "commitMode";
  private static final String TIME_ZONE = "timeZone";
  private static final String COPY_NULL = "copyNull";
  private static final String DOCUMENT_VIEW = "documentView";
  private static final String KEEP_HISTORY = "keepHistory";
  private static final String MONITOR_INTERVAL_SECONDS = "monitorIntervalSeconds";

  private final JsonFile json;

  public SettingsStore(Path folder) {
    this.json = new JsonFile(folder, FILE, "settings", MAX_SIZE);
  }

  /**
   * What {@link #load} read: the settings, and the JSON names of any keys whose values could not be
   * understood and fell back to their defaults.
   */
  public record Loaded(Settings settings, List<String> fellBack) {
    public Loaded {
      Objects.requireNonNull(settings, "settings");
      fellBack = List.copyOf(fellBack);
    }
  }

  /**
   * The settings as they were saved, or {@link Settings#DEFAULT} when there is no file. A file that
   * cannot be understood as a whole is left as it is.
   *
   * @throws WorkspaceStore.UnreadableException if the file was read but not understood
   * @throws IOException if it could not be read, with a message as {@link WorkspaceStore#load}
   *     gives
   */
  public Loaded load() throws IOException {
    var bytes = json.load();
    return bytes == null ? new Loaded(Settings.DEFAULT, List.of()) : parse(bytes);
  }

  private Loaded parse(byte[] bytes) throws WorkspaceStore.UnreadableException {
    var root = json.root(bytes, VERSION);
    var fellBack = new ArrayList<String>();
    var settings = new Settings(
        choice(
            root,
            THEME,
            Settings.Look.values(),
            SettingsStore::stored,
            Settings.DEFAULT.look(),
            fellBack),
        choice(
            root,
            ACCENT,
            Color.values(),
            WorkspaceStore::stored,
            Settings.DEFAULT.accent(),
            fellBack),
        choice(
            root,
            DENSITY,
            Settings.Density.values(),
            SettingsStore::stored,
            Settings.DEFAULT.density(),
            fellBack),
        bool(root, HIGHLIGHT_OCCURRENCES, Settings.DEFAULT.highlightOccurrences(), fellBack),
        choice(
            root,
            KEYWORD_CASE,
            Settings.KeywordCase.values(),
            SettingsStore::stored,
            Settings.DEFAULT.keywordCase(),
            fellBack),
        bool(root, CHECK_FOR_UPDATES, Settings.DEFAULT.checkForUpdates(), fellBack),
        connectTimeout(root, fellBack),
        bool(root, NEW_CONNECTIONS_READ_ONLY, Settings.DEFAULT.newConnectionsReadOnly(), fellBack),
        choice(
            root,
            COMMIT_MODE,
            Settings.CommitMode.values(),
            SettingsStore::stored,
            Settings.DEFAULT.commitMode(),
            fellBack),
        timeZone(root, fellBack),
        choice(
            root,
            COPY_NULL,
            Settings.CopyNull.values(),
            SettingsStore::stored,
            Settings.DEFAULT.copyNull(),
            fellBack),
        choice(
            root,
            DOCUMENT_VIEW,
            Settings.DocumentView.values(),
            SettingsStore::stored,
            Settings.DEFAULT.documentView(),
            fellBack),
        bool(root, KEEP_HISTORY, Settings.DEFAULT.keepHistory(), fellBack),
        monitorInterval(root, fellBack));
    return new Loaded(settings, fellBack);
  }

  private static <E extends Enum<E>> E choice(
      JsonNode root,
      String field,
      E[] values,
      Function<E, String> stored,
      E fallback,
      List<String> fellBack) {
    var node = root.get(field);
    if (node == null) {
      return fallback;
    }
    if (node.isString()) {
      var text = node.stringValue();
      for (var constant : values) {
        if (stored.apply(constant).equals(text)) {
          return constant;
        }
      }
    }
    fellBack.add(field);
    return fallback;
  }

  private static boolean bool(
      JsonNode root, String field, boolean fallback, List<String> fellBack) {
    var node = root.get(field);
    if (node == null) {
      return fallback;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    fellBack.add(field);
    return fallback;
  }

  private static Duration connectTimeout(JsonNode root, List<String> fellBack) {
    var node = root.get(CONNECT_TIMEOUT_SECONDS);
    if (node == null) {
      return Settings.DEFAULT.connectTimeout();
    }
    if (node.isInt()) {
      var timeout = Duration.ofSeconds(node.intValue());
      if (Settings.validConnectTimeout(timeout)) {
        return timeout;
      }
    }
    fellBack.add(CONNECT_TIMEOUT_SECONDS);
    return Settings.DEFAULT.connectTimeout();
  }

  private static ZoneId timeZone(JsonNode root, List<String> fellBack) {
    var node = root.get(TIME_ZONE);
    if (node == null) {
      return Settings.DEFAULT.timeZone();
    }
    if (node.isString()) {
      try {
        return ZoneId.of(node.stringValue());
      } catch (DateTimeException _) {
        // Reported below by key name alone.
      }
    }
    fellBack.add(TIME_ZONE);
    return Settings.DEFAULT.timeZone();
  }

  private static int monitorInterval(JsonNode root, List<String> fellBack) {
    var node = root.get(MONITOR_INTERVAL_SECONDS);
    if (node == null) {
      return Settings.DEFAULT.monitorInterval();
    }
    if (node.isInt() && Settings.validMonitorInterval(node.intValue())) {
      return node.intValue();
    }
    fellBack.add(MONITOR_INTERVAL_SECONDS);
    return Settings.DEFAULT.monitorInterval();
  }

  /**
   * Replaces the file with the settings that differ from {@link Settings#DEFAULT}, as {@link
   * WorkspaceStore#save} replaces its own.
   *
   * @throws IOException if it could not be written
   */
  public void save(Settings settings) throws IOException {
    Objects.requireNonNull(settings, "settings");
    var root = JsonFile.newRoot(VERSION);
    if (settings.look() != Settings.DEFAULT.look()) {
      root.put(THEME, stored(settings.look()));
    }
    if (settings.accent() != Settings.DEFAULT.accent()) {
      root.put(ACCENT, WorkspaceStore.stored(settings.accent()));
    }
    if (settings.density() != Settings.DEFAULT.density()) {
      root.put(DENSITY, stored(settings.density()));
    }
    if (settings.highlightOccurrences() != Settings.DEFAULT.highlightOccurrences()) {
      root.put(HIGHLIGHT_OCCURRENCES, settings.highlightOccurrences());
    }
    if (settings.keywordCase() != Settings.DEFAULT.keywordCase()) {
      root.put(KEYWORD_CASE, stored(settings.keywordCase()));
    }
    if (settings.checkForUpdates() != Settings.DEFAULT.checkForUpdates()) {
      root.put(CHECK_FOR_UPDATES, settings.checkForUpdates());
    }
    if (!settings.connectTimeout().equals(Settings.DEFAULT.connectTimeout())) {
      root.put(CONNECT_TIMEOUT_SECONDS, (int) settings.connectTimeout().getSeconds());
    }
    if (settings.newConnectionsReadOnly() != Settings.DEFAULT.newConnectionsReadOnly()) {
      root.put(NEW_CONNECTIONS_READ_ONLY, settings.newConnectionsReadOnly());
    }
    if (settings.commitMode() != Settings.DEFAULT.commitMode()) {
      root.put(COMMIT_MODE, stored(settings.commitMode()));
    }
    if (settings.timeZone() != null) {
      root.put(TIME_ZONE, settings.timeZone().getId());
    }
    if (settings.copyNull() != Settings.DEFAULT.copyNull()) {
      root.put(COPY_NULL, stored(settings.copyNull()));
    }
    if (settings.documentView() != Settings.DEFAULT.documentView()) {
      root.put(DOCUMENT_VIEW, stored(settings.documentView()));
    }
    if (settings.keepHistory() != Settings.DEFAULT.keepHistory()) {
      root.put(KEEP_HISTORY, settings.keepHistory());
    }
    if (settings.monitorInterval() != Settings.DEFAULT.monitorInterval()) {
      root.put(MONITOR_INTERVAL_SECONDS, settings.monitorInterval());
    }
    json.save(JsonFile.bytes(root), this::parse);
  }

  // The names in the file, spelled out so that renaming a constant cannot change it.
  private static String stored(Settings.Look look) {
    return switch (look) {
      case SYSTEM -> "system";
      case LIGHT -> "light";
      case DARK -> "dark";
    };
  }

  private static String stored(Settings.Density density) {
    return switch (density) {
      case COMPACT -> "compact";
      case DEFAULT -> "default";
      case COMFORTABLE -> "comfortable";
    };
  }

  private static String stored(Settings.KeywordCase keywordCase) {
    return switch (keywordCase) {
      case UPPER -> "upper";
      case LOWER -> "lower";
    };
  }

  private static String stored(Settings.CommitMode commitMode) {
    return switch (commitMode) {
      case AUTO -> "auto";
      case MANUAL -> "manual";
    };
  }

  private static String stored(Settings.CopyNull copyNull) {
    return switch (copyNull) {
      case EMPTY -> "empty";
      case NULL -> "null";
      case BACKSLASH_N -> "backslashN";
    };
  }

  private static String stored(Settings.DocumentView documentView) {
    return switch (documentView) {
      case GRID -> "grid";
      case JSON -> "json";
      case TREE -> "tree";
    };
  }
}

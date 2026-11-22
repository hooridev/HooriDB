package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.ComboBox.comboBox;
import static com.rethinkj.ui.kit.Group.group;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.NavList.navList;
import static com.rethinkj.ui.kit.NumberField.numberField;
import static com.rethinkj.ui.kit.Section.page;
import static com.rethinkj.ui.kit.Section.section;
import static com.rethinkj.ui.kit.Segmented.Size.LARGE;
import static com.rethinkj.ui.kit.Segmented.segmented;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Group;
import com.rethinkj.ui.kit.HuePicker;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.ValueField;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.update.Channel;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The modeless settings window over the shared {@link State}: one at a time, opened or raised by
 * File → Settings…, Control+, or the command palette. Every setting row is declared as data ({@link
 * Row}) so that search can be added later without changing the pages, and every change applies at
 * once.
 */
final class SettingsWindow {

  static final String TITLE = "Settings";

  // Sample moment for the time zone preview, formatted as the grid formats moments.
  private static final OffsetDateTime SAMPLE_MOMENT =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final Format<OffsetDateTime> MOMENT_FORMAT =
      Format.offsetDateTime(0, Format.Offset.SHORT);

  // All available zone choices sorted, after System and UTC.
  private static final List<ZoneChoice> AVAILABLE_ZONES = buildAvailableZones();

  private static List<ZoneChoice> buildAvailableZones() {
    var ids = ZoneId.getAvailableZoneIds().stream()
        .filter(id -> !id.equals("UTC"))
        .sorted()
        .toList();
    var choices = new ArrayList<ZoneChoice>(ids.size() + 1);
    choices.add(new ZoneChoice(ZoneId.of("UTC"), "UTC"));
    for (String id : ids) {
      choices.add(new ZoneChoice(ZoneId.of(id), id));
    }
    return List.copyOf(choices);
  }

  /** The pages in the order of the navigation list. */
  enum Page {
    GENERAL(Symbol.SETTINGS, "General"),
    APPEARANCE(Symbol.WINDOW, "Appearance"),
    EDITOR(Symbol.CODE, "Editor"),
    DATABASES(Symbol.DATABASE, "Databases"),
    HISTORY_AND_LOG(Symbol.HISTORY, "History & log");

    final Symbol icon;
    final String title;

    Page(Symbol icon, String title) {
      this.icon = icon;
      this.title = title;
    }

    NavList.Item item() {
      return NavList.item(icon, title);
    }
  }

  /** A choice in the time zone combo box: null for the computer's own zone. */
  private record ZoneChoice(ZoneId zone, String label) {}

  /** How a row's control is built from its current value and change handler. */
  @FunctionalInterface
  private interface Control<T> {
    El build(SettingsWindow window, State s, Row<T> row, T value, Consumer<T> write, El reset);
  }

  /**
   * One setting row as data: where it lives, its title and description, words to find it by, how to
   * read, write and reset it, and whether it is at its default.
   */
  record Row<T>(
      Page page,
      String section,
      String title,
      String description,
      List<String> keywords,
      Function<State, T> read,
      BiConsumer<State, T> write,
      Predicate<State> isDefault,
      Consumer<State> reset,
      Control<T> control) {

    El view(SettingsWindow window, State s, boolean locked) {
      T value = read.apply(s);
      boolean atDefault = isDefault.test(s);
      El resetButton = atDefault
          ? null
          : Button.icon(
                  GHOST,
                  Symbol.ARROW_UNDO,
                  "Reset " + title,
                  locked ? null : () -> window.resetRow(s, this))
              .key("reset");
      Consumer<T> writer = locked ? null : next -> write.accept(s, next);
      return control.build(window, s, this, value, writer, resetButton);
    }
  }

  static final List<Row<?>> ROWS = List.of(
      // General
      setting(
          Page.GENERAL,
          "Updates",
          "Check for updates at start",
          "Look for a newer release each time HooriDB starts",
          List.of("updates", "startup", "release", "check", "github"),
          Settings::checkForUpdates,
          Settings::withCheckForUpdates,
          SettingsWindow::toggleRow),
      new Row<>(
          Page.GENERAL,
          "Updates",
          "Update channel",
          "Stable releases, or nightly builds from main",
          List.of("updates", "channel", "stable", "nightly", "release"),
          s -> s.updates.channel(),
          (s, v) -> s.updates.channel(s, v),
          s -> s.updates.channel() == s.updates.defaultChannel(),
          s -> s.updates.channel(s, s.updates.defaultChannel()),
          segmentedRow(
              List.of(Channel.STABLE, Channel.NIGHTLY),
              c -> c == Channel.NIGHTLY ? "Nightly" : "Stable")),
      // Appearance
      setting(
          Page.APPEARANCE,
          "Colors",
          "Theme",
          "System follows the operating system's light or dark setting",
          List.of("theme", "look", "light", "dark", "system", "scheme", "appearance"),
          Settings::look,
          Settings::withLook,
          segmentedRow(
              List.of(Settings.Look.SYSTEM, Settings.Look.LIGHT, Settings.Look.DARK),
              SettingsWindow::lookLabel)),
      setting(
          Page.APPEARANCE,
          "Colors",
          "Accent",
          "Buttons, switches and selections",
          List.of("accent", "color", "hue", "theme"),
          Settings::accent,
          Settings::withAccent,
          SettingsWindow::accentRow),
      setting(
          Page.APPEARANCE,
          "Layout",
          "Row density",
          "Space between the rows of trees and grids",
          List.of("density", "spacing", "compact", "comfortable", "rows", "height"),
          Settings::density,
          Settings::withDensity,
          segmentedRow(
              List.of(
                  Settings.Density.COMPACT, Settings.Density.DEFAULT, Settings.Density.COMFORTABLE),
              SettingsWindow::densityLabel)),
      // Editor
      setting(
          Page.EDITOR,
          "Editing",
          "Highlight matching words",
          "Mark other occurrences of the word at the caret",
          List.of("highlight", "matching", "words", "occurrences", "editor", "caret"),
          Settings::highlightOccurrences,
          Settings::withHighlightOccurrences,
          SettingsWindow::toggleRow),
      setting(
          Page.EDITOR,
          "Editing",
          "SQL keywords in completions",
          "Letter case used when inserting a completed SQL keyword",
          List.of("sql", "keywords", "completions", "case", "upper", "lower", "autocomplete"),
          Settings::keywordCase,
          Settings::withKeywordCase,
          segmentedRow(
              List.of(Settings.KeywordCase.UPPER, Settings.KeywordCase.LOWER),
              k -> k == Settings.KeywordCase.UPPER ? "UPPER" : "lower")),
      // Databases
      setting(
          Page.DATABASES,
          "Connections",
          "Connect timeout",
          "Seconds to wait when opening a connection ("
              + Settings.MIN_CONNECT_TIMEOUT_SECONDS
              + "–"
              + Settings.MAX_CONNECT_TIMEOUT_SECONDS
              + ")",
          List.of("connect", "timeout", "seconds", "network", "connection"),
          settings -> (int) settings.connectTimeout().getSeconds(),
          (settings, seconds) -> settings.withConnectTimeout(Duration.ofSeconds(seconds)),
          SettingsWindow::timeoutRow),
      setting(
          Page.DATABASES,
          "Connections",
          "New connections start read-only",
          "Pre-select read-only when creating a new connection",
          List.of("read-only", "readonly", "new", "connection", "safe"),
          Settings::newConnectionsReadOnly,
          Settings::withNewConnectionsReadOnly,
          SettingsWindow::toggleRow),
      setting(
          Page.DATABASES,
          "Connections",
          "Commit mode of new consoles",
          "Auto commits each statement by itself; Manual waits for Commit or Rollback",
          List.of("commit", "autocommit", "manual", "transaction", "console"),
          Settings::commitMode,
          Settings::withCommitMode,
          segmentedRow(
              List.of(Settings.CommitMode.AUTO, Settings.CommitMode.MANUAL),
              m -> m == Settings.CommitMode.AUTO ? "Auto" : "Manual")),
      setting(
          Page.DATABASES,
          "Data",
          "Time zone of moments",
          "How timestamps with a time zone show",
          List.of("time", "zone", "timezone", "moments", "timestamp", "utc", "date"),
          Settings::timeZone,
          Settings::withTimeZone,
          SettingsWindow::timeZoneRow),
      setting(
          Page.DATABASES,
          "Data",
          "Copy NULL as",
          "Text placed on the clipboard when copying a NULL cell",
          List.of("copy", "null", "clipboard", "empty", "backslash", "grid"),
          Settings::copyNull,
          Settings::withCopyNull,
          segmentedRow(
              List.of(
                  Settings.CopyNull.EMPTY, Settings.CopyNull.NULL, Settings.CopyNull.BACKSLASH_N),
              SettingsWindow::copyNullLabel)),
      setting(
          Page.DATABASES,
          "Data",
          "First view of a MongoDB collection",
          "How a MongoDB collection or console result opens first",
          List.of("mongodb", "collection", "document", "view", "grid", "json", "tree"),
          Settings::documentView,
          Settings::withDocumentView,
          segmentedRow(
              List.of(
                  Settings.DocumentView.GRID,
                  Settings.DocumentView.JSON,
                  Settings.DocumentView.TREE),
              SettingsWindow::documentViewLabel)),
      // History & log
      setting(
          Page.HISTORY_AND_LOG,
          "Statements",
          "Keep statement history",
          "Remember statements run from consoles, up to 1,000 per connection",
          List.of("history", "statements", "console", "remember", "keep"),
          Settings::keepHistory,
          Settings::withKeepHistory,
          SettingsWindow::toggleRow),
      new Row<>(
          Page.HISTORY_AND_LOG,
          "Statements",
          "Statement log",
          "Record statements sent to servers in the statement log",
          List.of("statement", "log", "logging", "record", "sql"),
          s -> s.statements.on(),
          (s, on) -> s.statements.turn(on),
          s -> s.statements.on(),
          s -> s.statements.turn(true),
          SettingsWindow::toggleRow),
      setting(
          Page.HISTORY_AND_LOG,
          "Server monitor",
          "Interval of a new server monitor",
          "How often a newly opened server monitor refreshes",
          List.of("monitor", "server", "interval", "refresh", "seconds"),
          Settings::monitorInterval,
          Settings::withMonitorInterval,
          segmentedRow(Settings.MONITOR_INTERVALS, seconds -> seconds + " s")));

  private Windows.Handle handle;
  private Page page = Page.GENERAL;
  private final ComboBox.State zoneBox = new ComboBox.State();
  private final ValueField.Draft timeoutDraft = new ValueField.Draft();
  // An out-of-range timeout typed on the way to a valid one; null while the setting's value shows.
  private Integer timeoutTyped;

  private SettingsWindow() {}

  /** Opens the settings window, or raises the open one. */
  static void open(State s) {
    var open = s.dialogs.settings;
    if (open != null && open.handle != null && open.handle.isOpen()) {
      open.handle.raise();
      return;
    }
    var window = new SettingsWindow();
    s.dialogs.settings = window;
    window.launch(s);
  }

  private void launch(State s) {
    App<State> window = new App<>(s, this::view).provide(Theme.CURRENT, st -> st.theme);
    window.textRendering(s.app.textRendering());
    handle = s.app
        .windows()
        .open(
            TITLE,
            880,
            600,
            Window.options().frameless().minimumSize(560, 400).icon(HooriDb.ICON),
            window);
    handle.onClosed(() -> {
      if (s.dialogs.settings == this) {
        s.dialogs.settings = null;
      }
    });
  }

  private void select(Page next) {
    if (next == page) {
      return;
    }
    zoneBox.close();
    clearTimeoutInput();
    page = next;
  }

  private void clearTimeoutInput() {
    timeoutTyped = null;
    timeoutDraft.clear();
  }

  private void resetRow(State s, Row<?> row) {
    clearTimeoutInput();
    row.reset().accept(s);
  }

  private void restorePage(State s) {
    clearTimeoutInput();
    for (var row : rowsOf(page)) {
      if (!row.isDefault().test(s)) {
        row.reset().accept(s);
      }
    }
  }

  private El view(State s) {
    Theme t = Theme.CURRENT.get();
    El pages = navList(List.of(Page.values()), page, Page::item, this::select)
        .key("pages")
        .label(TITLE)
        .grow(1);
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(k -> {
          if (k.modifiers() == Key.CONTROL && k.name().equals(",")) {
            if (handle != null && handle.isOpen()) {
              handle.raise();
            }
            return true;
          }
          return false;
        })
        .child(titleBar(AppIcon.appIcon(16, HooriDb.COLOR, Symbol.SETTINGS), TITLE))
        .child(row()
            .grow(1)
            .minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .gap(t.space().m())
            .children(
                Card.flush(Card.inset(pages)).w(t.size().field()).shrink(0),
                pageView(s).key("page-" + page.name()).grow(1)));
  }

  private El pageView(State s) {
    boolean locked = s.keeping.settingsLoading || s.guard.exiting();
    var pageRows = rowsOf(page);
    var bySection = new LinkedHashMap<String, List<Row<?>>>();
    boolean changed = false;
    for (var row : pageRows) {
      bySection.computeIfAbsent(row.section(), k -> new ArrayList<>()).add(row);
      changed |= !row.isDefault().test(s);
    }
    var parts = new ArrayList<El>(bySection.size() + 1);
    for (var entry : bySection.entrySet()) {
      El[] built = entry.getValue().stream().map(r -> r.view(this, s, locked)).toArray(El[]::new);
      parts.add(section(entry.getKey(), null, group(built)));
    }
    parts.add(Form.buttons(button(
            SECONDARY,
            Symbol.ARROW_UNDO,
            "Restore defaults",
            !locked && changed ? () -> restorePage(s) : null)
        .key("restore-defaults")));
    return page(page.title, parts.toArray(El[]::new));
  }

  private static List<Row<?>> rowsOf(Page page) {
    return ROWS.stream().filter(r -> r.page() == page).toList();
  }

  private static <T> Row<T> setting(
      Page page,
      String section,
      String title,
      String description,
      List<String> keywords,
      Function<Settings, T> getter,
      BiFunction<Settings, T, Settings> wither,
      Control<T> control) {
    T fallback = getter.apply(Settings.DEFAULT);
    return new Row<>(
        page,
        section,
        title,
        description,
        keywords,
        s -> getter.apply(s.settings),
        (s, v) -> s.choose(wither.apply(s.settings, v)),
        s -> Objects.equals(getter.apply(s.settings), fallback),
        s -> s.choose(wither.apply(s.settings, fallback)),
        control);
  }

  private static El withReset(El reset, El control) {
    Theme t = Theme.CURRENT.get();
    return row()
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .children(reset, control.key("control"));
  }

  private static El toggleRow(
      SettingsWindow window,
      State s,
      Row<Boolean> row,
      Boolean value,
      Consumer<Boolean> write,
      El reset) {
    boolean on = Boolean.TRUE.equals(value);
    El control = toggle(on, write == null ? null : () -> write.accept(!on)).label(row.title());
    return Group.row(row.title(), row.description(), withReset(reset, control));
  }

  private static <T> Control<T> segmentedRow(List<T> values, Function<T, String> text) {
    return (window, s, row, value, write, reset) -> {
      El control = segmented(values, value, text, write, LARGE).label(row.title());
      return Group.row(row.title(), row.description(), withReset(reset, control));
    };
  }

  private static El accentRow(
      SettingsWindow window,
      State s,
      Row<Color> row,
      Color value,
      Consumer<Color> write,
      El reset) {
    El control = HuePicker.huePicker(
            ConnectionColors.hue(value),
            write == null ? null : hue -> write.accept(ConnectionColors.color(hue)))
        .label(row.title());
    return Group.row(row.title(), row.description(), withReset(reset, control));
  }

  private static El timeoutRow(
      SettingsWindow window,
      State s,
      Row<Integer> row,
      Integer value,
      Consumer<Integer> write,
      El reset) {
    int shown = window.timeoutTyped != null ? window.timeoutTyped : value;
    El control = numberField(
        row.title(),
        shown,
        Settings.MIN_CONNECT_TIMEOUT_SECONDS,
        Settings.MAX_CONNECT_TIMEOUT_SECONDS,
        1,
        window.timeoutDraft,
        write == null
            ? null
            : next -> {
              if (next >= Settings.MIN_CONNECT_TIMEOUT_SECONDS
                  && next <= Settings.MAX_CONNECT_TIMEOUT_SECONDS) {
                window.timeoutTyped = null;
                write.accept(next);
              } else {
                window.timeoutTyped = next;
              }
            });
    return Group.row(row.title(), row.description(), withReset(reset, control));
  }

  private static El timeZoneRow(
      SettingsWindow window,
      State s,
      Row<ZoneId> row,
      ZoneId value,
      Consumer<ZoneId> write,
      El reset) {
    Theme t = Theme.CURRENT.get();
    var choices = zoneChoices(value);
    ZoneChoice selected = choices.stream()
        .filter(c -> Objects.equals(c.zone(), value))
        .findFirst()
        .orElse(choices.getFirst());
    El box = comboBox(
            choices,
            selected,
            ZoneChoice::label,
            window.zoneBox,
            write == null ? null : choice -> write.accept(choice.zone()))
        .label(row.title())
        .w(8 * t.size().control());
    String preview = MOMENT_FORMAT.text(SAMPLE_MOMENT.atZoneSameInstant(s.zone).toOffsetDateTime());
    El right = col()
        .gap(t.space().xs())
        .align(Style.Align.END)
        .child(withReset(reset, box))
        .child(label(Label.Variant.MUTED, preview).key("timezone-preview"));
    return Group.row(row.title(), row.description(), right);
  }

  private static List<ZoneChoice> zoneChoices(ZoneId current) {
    var list = new ArrayList<ZoneChoice>(AVAILABLE_ZONES.size() + 2);
    list.add(new ZoneChoice(null, "System (" + ZoneId.systemDefault().getId() + ")"));
    list.addAll(AVAILABLE_ZONES);
    if (current != null && AVAILABLE_ZONES.stream().noneMatch(c -> current.equals(c.zone()))) {
      list.add(2, new ZoneChoice(current, current.getId()));
    }
    return list;
  }

  private static String lookLabel(Settings.Look look) {
    return switch (look) {
      case SYSTEM -> "System";
      case LIGHT -> "Light";
      case DARK -> "Dark";
    };
  }

  private static String densityLabel(Settings.Density density) {
    return switch (density) {
      case COMPACT -> "Compact";
      case DEFAULT -> "Default";
      case COMFORTABLE -> "Comfortable";
    };
  }

  private static String copyNullLabel(Settings.CopyNull copyNull) {
    return switch (copyNull) {
      case EMPTY -> "Empty";
      case NULL -> "NULL";
      case BACKSLASH_N -> "\\N";
    };
  }

  private static String documentViewLabel(Settings.DocumentView view) {
    return switch (view) {
      case GRID -> "Grid";
      case JSON -> "JSON";
      case TREE -> "Tree";
    };
  }
}

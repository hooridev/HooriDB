package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.SystemAppearance;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading, applying and saving user settings, including the theme and system appearance. */
class SettingsTest {

  private static final String OPENED = "frame\nadvance 1\nframe\n";

  private static App<State> app(Path config) {
    return HooriDb.app(
        config, () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"));
  }

  /**
   * Settings in the config folder apply at start; choosing Dark in View writes settings.json, and a
   * new app on the same folder starts dark.
   */
  @Test
  void lightSettingsLoadAndSwitchingToDarkSavesForTheNextStart(@TempDir Path dir)
      throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"theme":"light"}
        """);

    var first = app(config);
    // Before the file is read, a choice would replace its other settings: none can be made.
    Script.run("""
        size 1200 760
        frame
        press role=menuitem label="View"
        """ + OPENED + """
        snap loading
        key Escape
        """ + OPENED, first, out);
    var loading = Files.readString(out.resolve("loading.tree.txt"));
    assertTrue(line(loading, "label=\"Dark theme\"").contains("disabled"), loading);
    Script.run("""
        await
        frame
        snap first
        """, first, out);
    assertSame(Theme.LIGHT, first.state().theme);
    assertEquals(Settings.Look.LIGHT, first.state().settings.look());

    Script.run("""
        press role=menuitem label="View"
        """ + OPENED + """
        snap view
        press role=menuitem label="Dark theme"
        frame
        advance 2
        frame
        await
        frame
        """, first, out);
    var view = Files.readString(out.resolve("view.tree.txt"));
    assertTrue(line(view, "label=\"Light theme\"").contains("checked"), view);
    assertFalse(line(view, "label=\"System theme\"").contains("checked"), view);
    assertFalse(line(view, "label=\"Dark theme\"").contains("checked"), view);

    assertSame(Theme.DARK, first.state().theme);
    assertEquals(Settings.Look.DARK, first.state().settings.look());
    var saved = Files.readString(config.resolve(SettingsStore.FILE));
    assertTrue(saved.contains("\"theme\"") && saved.contains("\"dark\""), saved);

    var second = app(config);
    Script.run("""
        size 1200 760
        frame
        await
        frame
        press role=menuitem label="View"
        """ + OPENED + """
        snap second-view
        """, second, out);
    assertSame(Theme.DARK, second.state().theme);
    assertEquals(Settings.Look.DARK, second.state().settings.look());
    var secondView = Files.readString(out.resolve("second-view.tree.txt"));
    assertTrue(line(secondView, "label=\"Dark theme\"").contains("checked"), secondView);
  }

  /** A broken settings.json gives one notice and dark defaults, and is set aside on next save. */
  @Test
  void aBrokenSettingsFileGivesANoticeAndDarkDefaults(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    Files.writeString(config.resolve(SettingsStore.FILE), "{");

    var app = app(config);
    Script.run("""
        size 1200 760
        frame
        await
        frame
        snap broken
        """, app, out);
    assertSame(Theme.DARK, app.state().theme);
    assertEquals(Settings.DEFAULT, app.state().settings);
    assertEquals(1, app.state().notices.size());
    var broken = Files.readString(out.resolve("broken.tree.txt"));
    assertTrue(broken.contains("Settings · not read"), broken);

    Script.run("""
        press role=menuitem label="View"
        """ + OPENED + """
        press role=menuitem label="Light theme"
        frame
        advance 2
        frame
        await
        frame
        """, app, out);
    assertEquals("{", Files.readString(config.resolve(SettingsStore.UNREADABLE)));
    assertEquals(
        Settings.Look.LIGHT, new SettingsStore(config).load().settings().look());
  }

  /** Bad values of individual keys fall back to defaults with one notice naming only the keys. */
  @Test
  void keysThatFellBackGiveOneNoticeNamingThem(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"theme":"sepia","accent":42,"density":"compact"}
        """);

    var app = app(config);
    Script.run("""
        size 1200 760
        frame
        await
        frame
        snap fellback
        """, app, out);
    assertEquals(1, app.state().notices.size());
    var notice = app.state().notices.getFirst();
    assertFalse(notice.failure());
    assertTrue(notice.what().contains("theme") && notice.what().contains("accent"), notice.what());
    assertFalse(notice.what().contains("sepia") || notice.what().contains("42"), notice.what());
    assertEquals(Settings.Density.COMPACT, app.state().settings.density());
    assertTrue(app.state().theme.grid().rowHeight() < Theme.DARK.grid().rowHeight());
  }

  /**
   * System appearance switches System look between Light and Dark, accent and density adjust the
   * resolved theme, and timeZone updates the resolved zone without clobbering test overrides on
   * default load.
   */
  @Test
  void resolutionOfAppearanceAccentDensityAndZone(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var customZone = ZoneId.of("Pacific/Auckland");

    var app = app(config);
    app.state().theme = Theme.LIGHT;
    app.state().zone = customZone;
    Script.run("""
        size 1200 760
        frame
        await
        frame
        """, app, out);
    // Default settings equal the initial state, so test overrides survive the load.
    assertSame(Theme.LIGHT, app.state().theme);
    assertEquals(customZone, app.state().zone);

    var s = app.state();
    // System appearance takes effect when look is SYSTEM.
    s.systemChanged(new SystemAppearance(SystemAppearance.Scheme.LIGHT, 0, false));
    assertSame(Theme.LIGHT, s.theme);
    s.systemChanged(new SystemAppearance(SystemAppearance.Scheme.DARK, 0, false));
    assertSame(Theme.DARK, s.theme);

    // Explicit DARK with default accent and density resolves to Theme.DARK; ignores system Light.
    s.choose(Settings.DEFAULT.withLook(Settings.Look.DARK));
    assertEquals(Theme.DARK, s.theme);
    assertSame(Theme.DARK, s.theme);
    s.systemChanged(new SystemAppearance(SystemAppearance.Scheme.LIGHT, 0, false));
    assertSame(Theme.DARK, s.theme);

    // Accent and density apply on top of the look.
    s.choose(s.settings.withAccent(Color.TEAL).withDensity(Settings.Density.COMFORTABLE));
    assertEquals(Theme.Hue.TEAL.dark(), s.theme.accent());
    assertTrue(s.theme.grid().rowHeight() > Theme.DARK.grid().rowHeight());

    // Time zone resolves to the setting or back to the computer's zone.
    s.choose(s.settings.withTimeZone(ZoneId.of("UTC")));
    assertEquals(ZoneId.of("UTC"), s.zone);
    s.choose(s.settings.withTimeZone(null));
    assertEquals(ZoneId.systemDefault(), s.zone);
  }

  /** Exit waits for a pending settings save instead of losing it. */
  @Test
  void exitSavesPendingSettingsChange(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var app = app(config);
    Script.run("""
        size 1200 760
        frame
        await
        frame
        press role=menuitem label="View"
        """ + OPENED + """
        press role=menuitem label="Light theme"
        frame
        press role=menuitem label="File"
        """ + OPENED + """
        press role=menuitem label="Exit"
        frame
        await
        frame
        """, app, out);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(
        Settings.Look.LIGHT, new SettingsStore(config).load().settings().look());
  }

  /** Preloading with startReading/takeStart applies the settings before the first frame. */
  @Test
  void preloadingAppliesSettingsBeforeStart(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var savedSettings = Settings.DEFAULT.withLook(Settings.Look.LIGHT).withAccent(Color.GREEN);
    new SettingsStore(config).save(savedSettings);

    var initial = Keeping.startReading(config);
    var state = new State(
        config,
        new Services(
            () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"),
            null,
            Tester.SERVER,
            Servers.SERVER,
            null),
        HooriDb.version());
    var app = new App<>(state, Frame::view).provide(Theme.CURRENT, s -> s.theme);
    state.app = app;
    state.keeping.takeStart(initial);

    assertEquals(savedSettings, state.settings);
    assertEquals(Theme.LIGHT.withAccent(Theme.Hue.GREEN), state.theme);
    assertTrue(state.keeping.settingsKept.canSave);
  }
}

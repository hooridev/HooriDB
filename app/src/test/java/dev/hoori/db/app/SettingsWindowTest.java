package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.connection.StatementLogStore;
import dev.hoori.db.core.connection.UpdateStore;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The modeless settings window: opening, raising, every page's rows, reset, Restore defaults. */
class SettingsWindowTest {

  private static final String OPENED = "frame\nadvance 1\nframe\n";
  private static final String SAVE = "frame\nadvance 2\nframe\nawait\nframe\n";

  private static App<State> app(Path config) {
    return HooriDb.app(
        config, () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"));
  }

  /**
   * File → Settings…, Control+, and the palette open or raise the one settings window, from the
   * main window and from a torn-off window; Exit closes it.
   */
  @Test
  void opensOnceRaisesOnSecondShortcutWorksFromTornOffAndClosesOnExit(@TempDir Path dir)
      throws Exception {
    var c = new CatalogTreeTest();
    c.dir = dir;
    c.setUp();
    try {
      var app = c.app(CatalogTreeTest.SHOP);
      c.ready(app, CatalogTreeTest.SHOP);
      // Open a console and tear it off into its own window.
      c.run(
          app,
          CatalogTreeTest.START
              + CatalogTreeTest.menu("shop-prod", "New console")
              + "frame\n"
              + "press role=tab label=\"shop-prod: shop\" right\n"
              + OPENED
              + "press role=menuitem label=\"Move to new window\"\n"
              + OPENED);
      assertEquals(2, app.windows().all().size());

      // First Control+, from main opens Settings.
      c.run(app, "window main\nkey , control\nframe\n");
      assertEquals(3, app.windows().all().size());
      var settingsHandle = app.windows().all().stream()
          .filter(h -> SettingsWindow.TITLE.equals(h.title()))
          .findFirst()
          .orElseThrow();

      // Raising main, then pressing Control+, again raises the same Settings window (no second).
      c.run(app, "window main\nframe\nkey , control\nframe\n");
      assertEquals(3, app.windows().all().size());
      assertSame(
          settingsHandle,
          app.windows().place(settingsHandle.app(), 100, 100).window(),
          "second Control+, raises the existing Settings window");

      // Closing Settings and reopening via File → Settings… works.
      c.run(app, "window \"Settings\"\nclose\nframe\n");
      assertEquals(2, app.windows().all().size());
      c.run(
          app,
          "window main\npress role=menuitem label=\"File\"\n"
              + OPENED
              + "press role=menuitem label=\"Settings…\"\nframe\n");
      assertEquals(3, app.windows().all().size());

      // From the torn-off window, the palette opens/raises the same Settings window.
      c.run(
          app,
          "window \"shop-prod: shop\"\nkey P control shift\n"
              + OPENED
              + "type \"Settings\"\nframe\nkey Enter\n"
              + OPENED);
      assertEquals(3, app.windows().all().size());

      // From the torn-off window, Control+, raises the same Settings window without opening
      // another.
      c.run(app, "window \"shop-prod: shop\"\nkey , control\nframe\n");
      assertEquals(3, app.windows().all().size());

      // Exit closes all windows including Settings.
      c.run(
          app,
          "window main\npress role=menuitem label=\"File\"\n"
              + OPENED
              + "press role=menuitem label=\"Exit\"\nframe\nawait\nframe\n");
      assertTrue(app.windows().all().isEmpty());
    } finally {
      c.tearDown();
    }
  }

  /** Controls are disabled while settings.json is still loading. */
  @Test
  void controlsAreDisabledWhileSettingsLoad(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var app = app(config);

    Script.run("""
        size 1200 760
        frame
        key , control
        frame
        window "Settings"
        frame
        snap loading
        """, app, out);
    var loading = Files.readString(out.resolve("settings-loading.tree.txt"));
    assertTrue(
        line(loading, "role=switch label=\"Check for updates at start\"").contains("disabled"),
        loading);
    assertTrue(line(loading, "label=\"Restore defaults\"").contains("disabled"), loading);

    Script.run("""
        await
        frame
        window "Settings"
        frame
        snap loaded
        """, app, out);
    var loaded = Files.readString(out.resolve("settings-loaded.tree.txt"));
    assertFalse(
        line(loaded, "role=switch label=\"Check for updates at start\"").contains("disabled"),
        loaded);
  }

  /**
   * Changing every setting writes settings.json (or updates.json / statement-log.json for their
   * owners), shows a labeled reset button that resets the row and disappears, and Restore defaults
   * resets one page at a time.
   */
  @Test
  void everySettingWritesResetsAndRestoresPerPage(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var app = app(config);
    app.state().zone = ZoneId.of("Europe/Berlin");

    // Start, await load, open Settings.
    Script.run("""
        size 1200 760
        frame
        await
        frame
        key , control
        frame
        window "Settings"
        frame
        snap general-default
        """, app, out);
    var generalDefault = Files.readString(out.resolve("settings-general-default.tree.txt"));
    assertTrue(line(generalDefault, "label=\"Restore defaults\"").contains("disabled"));
    assertFalse(generalDefault.contains("label=\"Reset "));

    // General: toggle Check for updates at start, switch Update channel to Nightly.
    Script.run("""
        window "Settings"
        press role=switch label="Check for updates at start"
        frame
        press role=radio label="Nightly"
        """ + SAVE + """
        snap general-changed
        """, app, out);
    var generalChanged = Files.readString(out.resolve("settings-general-changed.tree.txt"));
    assertTrue(
        generalChanged.contains("label=\"Reset Check for updates at start\""), generalChanged);
    assertTrue(generalChanged.contains("label=\"Reset Update channel\""), generalChanged);
    assertFalse(line(generalChanged, "label=\"Restore defaults\"").contains("disabled"));
    assertFalse(app.state().settings.checkForUpdates());
    assertEquals(Channel.NIGHTLY, app.state().updates.channel());
    assertFalse(new SettingsStore(config).load().settings().checkForUpdates());
    assertEquals(Channel.NIGHTLY, new UpdateStore(config).load().channel());

    // Reset one row on General: its reset button disappears, the other remains.
    Script.run("""
        window "Settings"
        press role=button label="Reset Check for updates at start"
        """ + SAVE + """
        snap general-one-reset
        """, app, out);
    var generalOneReset = Files.readString(out.resolve("settings-general-one-reset.tree.txt"));
    assertFalse(
        generalOneReset.contains("label=\"Reset Check for updates at start\""), generalOneReset);
    assertTrue(generalOneReset.contains("label=\"Reset Update channel\""), generalOneReset);
    assertTrue(app.state().settings.checkForUpdates());
    assertTrue(new SettingsStore(config).load().settings().checkForUpdates());

    // Appearance: change Accent and Row density, keep changed to verify Restore defaults is
    // per-page.
    Script.run("""
        window "Settings"
        press role=option label="Appearance"
        frame
        press role=radio label="Teal"
        frame
        press role=radio label="Compact"
        """ + SAVE + """
        snap appearance-changed
        """, app, out);
    var appearanceChanged = Files.readString(out.resolve("settings-appearance-changed.tree.txt"));
    assertTrue(appearanceChanged.contains("label=\"Reset Accent\""), appearanceChanged);
    assertTrue(appearanceChanged.contains("label=\"Reset Row density\""), appearanceChanged);
    assertEquals(Color.TEAL, app.state().settings.accent());
    assertEquals(Settings.Density.COMPACT, app.state().settings.density());

    // Editor: change both rows.
    Script.run("""
        window "Settings"
        press role=option label="Editor"
        frame
        press role=switch label="Highlight matching words"
        frame
        press role=radio label="lower"
        """ + SAVE + """
        snap editor-changed
        """, app, out);
    assertFalse(app.state().settings.highlightOccurrences());
    assertEquals(Settings.KeywordCase.LOWER, app.state().settings.keywordCase());
    var savedEditor = new SettingsStore(config).load().settings();
    assertFalse(savedEditor.highlightOccurrences());
    assertEquals(Settings.KeywordCase.LOWER, savedEditor.keywordCase());

    // Databases: change all 7 rows (including typing in Time zone ComboBox and checking preview).
    Script.run("""
        window "Settings"
        press role=option label="Databases"
        frame
        press role=spinbutton label="Connect timeout"
        key A control
        type "25"
        frame
        press role=switch label="New connections start read-only"
        frame
        press role=radio label="Manual"
        frame
        press role=radio label="Always a new console"
        frame
        press role=combobox label="Time zone of moments"
        """ + OPENED + """
        type "UTC"
        frame
        press role=option label="UTC"
        """ + OPENED + """
        press role=radio label="NULL"
        frame
        press role=radio label="JSON"
        """ + SAVE + """
        snap databases-changed
        """, app, out);
    var databasesChanged = Files.readString(out.resolve("settings-databases-changed.tree.txt"));
    assertTrue(databasesChanged.contains("2026-06-15 12:00:00+00"), databasesChanged);
    assertTrue(databasesChanged.contains("label=\"Reset Connect timeout\""), databasesChanged);
    assertTrue(databasesChanged.contains("label=\"Reset Open in console\""), databasesChanged);
    assertTrue(databasesChanged.contains("label=\"Reset Time zone of moments\""), databasesChanged);
    var savedDb = new SettingsStore(config).load().settings();
    assertEquals(Duration.ofSeconds(25), savedDb.connectTimeout());
    assertTrue(savedDb.newConnectionsReadOnly());
    assertEquals(Settings.CommitMode.MANUAL, savedDb.commitMode());
    assertEquals(Settings.OpenInConsole.NEW, savedDb.openInConsole());
    assertEquals(ZoneId.of("UTC"), savedDb.timeZone());
    assertEquals(Settings.CopyNull.NULL, savedDb.copyNull());
    assertEquals(Settings.DocumentView.JSON, savedDb.documentView());

    // History & log: change all 3 rows.
    Script.run("""
        window "Settings"
        press role=option label="History & log"
        frame
        press role=switch label="Keep statement history"
        frame
        press role=switch label="Statement log"
        frame
        press role=radio label="10 s"
        """ + SAVE + """
        snap history-changed
        """, app, out);
    assertFalse(app.state().settings.keepHistory());
    assertFalse(app.state().statements.on());
    assertEquals(10, app.state().settings.monitorInterval());
    var savedHist = new SettingsStore(config).load().settings();
    assertFalse(savedHist.keepHistory());
    assertEquals(10, savedHist.monitorInterval());
    assertFalse(new StatementLogStore(config).enabled());

    // Restore defaults on History & log: resets only History & log, leaves Databases & Appearance!
    Script.run("""
        window "Settings"
        press role=button label="Restore defaults"
        """ + SAVE + """
        snap history-restored
        """, app, out);
    var historyRestored = Files.readString(out.resolve("settings-history-restored.tree.txt"));
    assertTrue(line(historyRestored, "label=\"Restore defaults\"").contains("disabled"));
    assertFalse(historyRestored.contains("label=\"Reset "));
    assertTrue(app.state().settings.keepHistory());
    assertTrue(app.state().statements.on());
    assertEquals(5, app.state().settings.monitorInterval());
    assertTrue(new StatementLogStore(config).enabled());
    // Other pages untouched.
    assertEquals(Color.TEAL, app.state().settings.accent());
    assertEquals(Duration.ofSeconds(25), app.state().settings.connectTimeout());
    assertEquals(Channel.NIGHTLY, app.state().updates.channel());
  }

  /** Changing the theme in the Settings window updates the main window in the same frame. */
  @Test
  void changingThemeUpdatesMainWindowInSameFrame(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var app = app(config);

    Script.run("""
        size 1200 760
        frame
        await
        frame
        key , control
        frame
        window "Settings"
        press role=option label="Appearance"
        frame
        snap dark-before
        """, app, out);
    assertSame(Theme.DARK, app.state().theme);
    int darkFill = app.tree().fill();

    // Press Light in the settings window, then ONE frame: main window must already have
    // Theme.LIGHT.
    Script.run("""
        window "Settings"
        press role=radio label="Light"
        frame
        """, app, out);
    assertSame(Theme.LIGHT, app.state().theme);
    assertEquals(
        Theme.LIGHT.surface().window(),
        app.tree().fill(),
        "main window switched to light in the same frame");
    assertNotEquals(darkFill, app.tree().fill());
  }

  /** Out-of-range connect timeout is shown as invalid and does not crash or save invalid values. */
  @Test
  void outOfRangeConnectTimeoutShowsInvalidWithoutSaving(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var out = dir.resolve("out");
    var app = app(config);

    Script.run("""
        size 1200 760
        frame
        await
        frame
        key , control
        frame
        window "Settings"
        press role=option label="Databases"
        frame
        press role=spinbutton label="Connect timeout"
        key A control
        clipboard "999"
        key V control
        frame
        snap invalid-timeout
        """, app, out);
    var tree = Files.readString(out.resolve("settings-invalid-timeout.tree.txt"));
    assertTrue(line(tree, "role=spinbutton label=\"Connect timeout\"").contains("invalid"), tree);
    assertEquals(Settings.DEFAULT.connectTimeout(), app.state().settings.connectTimeout());
    assertNull(app.state().settings.timeZone());
  }

  /**
   * Produces dark and light snaps of Appearance (with changed rows showing reset buttons) and
   * Databases for visual review.
   */
  @Test
  void darkAndLightReviewSnaps(@TempDir Path dir) throws IOException {
    var config = Files.createDirectories(dir.resolve("config"));
    var review = System.getProperty("hooridb.settings.review");
    var out = review != null ? Files.createDirectories(Path.of(review)) : dir.resolve("out");
    var app = app(config);
    app.state().zone = ZoneId.of("Europe/Berlin");

    // Dark theme: Appearance with Accent & Row density changed (reset buttons visible), and
    // Databases.
    Script.run(
        """
        size 1200 760
        frame
        await
        frame
        key , control
        frame
        window "Settings"
        press role=option label="Appearance"
        """ + OPENED + """
        press role=radio label="Teal"
        frame
        press role=radio label="Compact"
        """ + OPENED + """
        snap appearance-dark
        press role=option label="Databases"
        """ + OPENED + """
        snap databases-dark
        press role=option label="Appearance"
        """ + OPENED + """
        press role=radio label="Light"
        """ + OPENED + """
        snap appearance-light
        press role=option label="Databases"
        """ + OPENED
            + """
        snap databases-light
        """,
        app,
        out);

    assertTrue(Files.size(out.resolve("settings-appearance-dark.png")) > 0);
    assertTrue(Files.size(out.resolve("settings-appearance-light.png")) > 0);
    assertTrue(Files.size(out.resolve("settings-databases-dark.png")) > 0);
    assertTrue(Files.size(out.resolve("settings-databases-light.png")) > 0);
  }
}

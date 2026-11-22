package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Version;
import dev.hoori.db.core.connection.SettingsStore;
import dev.hoori.db.core.connection.StoredUpdates;
import dev.hoori.db.core.connection.UpdateStore;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HooriDB updates itself from its releases: what it offers, downloads and restarts with. */
class UpdatesTest {

  static final String NIGHTLY = "0.2.0-nightly.202611190300";
  // The start: the settings and the helper's result, the check, the download, and its notice.
  static final String START =
      "size 1200 760\nframe\n" + "await\nframe\n".repeat(4) + "advance 1\nframe\n";
  static final String OPENED = "advance 1\nframe\n";
  static final String RESTART = "press #update-act\nframe\n" + "await\nframe\n".repeat(3);
  static final String EXIT = "press role=menuitem label=\"File\"\nframe\n" + OPENED
      + "press role=menuitem label=\"Exit\"\nframe\n" + "await\nframe\n".repeat(3);
  static final String HELP = "press role=menuitem label=\"Help\"\nframe\n" + OPENED;

  @TempDir
  Path dir;

  FakeUpdater updater() {
    return new FakeUpdater(dir.resolve("updates").resolve("downloads"));
  }

  App<State> app(FakeUpdater updater, String version) {
    return HooriDb.app(
        dir.resolve("config"),
        () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"),
        updater,
        version);
  }

  void run(App<State> app, String script) throws IOException {
    Script.run(script, app, dir.resolve("out"));
  }

  String tree(String snap) throws IOException {
    return Files.readString(dir.resolve("out").resolve(snap + ".tree.txt"));
  }

  StoredUpdates stored() throws IOException {
    return new UpdateStore(dir.resolve("config")).load();
  }

  /**
   * A check offers the newest release of the channel, not a nightly on Stable, and once it is
   * downloaded, which the status line shows, a notice says that it is ready; in either theme.
   */
  @Test
  void aCheckOffersTheNewestReleaseAndIsReadyAfterItsDownload() throws Exception {
    var updater = updater().publish("0.2.1", "0.3.0", "0.4.0-nightly.202611190300", "0.1.0");
    updater.downloading = new CountDownLatch(1);
    var app = app(updater, "0.2.0");
    run(app, "size 1200 760\nframe\n" + "await\nframe\n".repeat(2) + "snap downloading\n");
    assertTrue(
        line(tree("downloading"), "#downloading").contains("Downloading HooriDB 0.3.0…"),
        tree("downloading"));
    updater.downloading.countDown();
    Script.run(
        "await\nframe\n".repeat(2) + OPENED + "snap ready-dark\n" + HELP + "snap help-dark\n"
            + "key Escape\nframe\n" + OPENED
            + "press role=menuitem label=\"View\"\nframe\n" + OPENED
            + "press role=menuitem label=\"Light theme\"\nframe\n" + OPENED + "advance 5\nframe\n"
            + "snap ready-light\n" + HELP + "snap help-light\n",
        app,
        dir.resolve("out"));
    assertEquals(List.of("0.3.0"), updater.downloaded);
    var ready = tree("ready-dark");
    assertTrue(ready.contains("HooriDB 0.3.0 is ready"), ready);
    assertTrue(ready.contains("Restart HooriDB to update from 0.2.0."), ready);
    assertTrue(line(ready, "#update-act").contains("label=\"Restart now\""), ready);
    assertTrue(line(ready, "#update-dismiss").contains("label=\"Later\""), ready);
    assertFalse(ready.contains("#downloading"), ready);
    var help = tree("help-light");
    assertTrue(line(help, "label=\"Stable updates\"").contains("checked"), help);
    assertFalse(line(help, "label=\"Nightly updates\"").contains("checked"), help);
    assertFalse(line(help, "label=\"Restart to update\"").contains("disabled"), help);
    assertEquals(Channel.STABLE, stored().channel(), "derived from the version, and saved");
  }

  /** Where HooriDB cannot replace itself, the notice opens the release's page; nothing downloads. */
  @Test
  void withoutRightsToItsFolderItShowsTheReleasePageAndDownloadsNothing() throws Exception {
    var updater = updater().publish("0.3.0");
    updater.writable = false;
    var app = app(updater, "0.2.0");
    run(app, START + "snap available\n" + "press #update-act\nframe\nsnap opened\n");
    var available = tree("available");
    assertTrue(available.contains("HooriDB 0.3.0 is available"), available);
    // The folder as the platform writes it, whose backslashes the tree escapes on Windows.
    var folder = Path.of("/opt/HooriDB").toString().replace("\\", "\\\\");
    assertTrue(
        available.contains(
            "HooriDB cannot replace itself in " + folder + ". Download it from its release page."),
        available);
    assertTrue(line(available, "#update-act").contains("label=\"Open release page\""), available);
    assertEquals(List.of(), updater.downloaded);
    var opened = Files.readString(dir.resolve("out").resolve("opened.opened.txt"));
    assertTrue(opened.contains("https://github.com/hooridev/HooriDB/releases/tag/v0.3.0"), opened);
  }

  /** A check that fails, as without a network, shows nothing. */
  @Test
  void aFailedCheckShowsNothing() throws Exception {
    var updater = updater().publish("0.3.0");
    updater.offline = true;
    var app = app(updater, "0.2.0");
    run(app, START + "snap start\n");
    assertFalse(tree("start").contains("#update"), tree("start"));
    assertEquals(null, app.state().updates.notice);
  }

  /** A download that fails says so. */
  @Test
  void aFailedDownloadShowsANotice() throws Exception {
    var updater = updater().publish("0.3.0");
    updater.failDownload = new IOException("HooriDB-0.3.0-linux-x64.tar.gz does not match");
    var app = app(updater, "0.2.0");
    run(app, START + "snap failed\n");
    var failed = tree("failed");
    assertTrue(failed.contains("HooriDB 0.3.0 could not be downloaded"), failed);
    assertTrue(failed.contains("does not match"), failed);
    assertTrue(line(failed, "#update-dismiss").contains("label=\"Dismiss\""), failed);
  }

  /**
   * Restart now goes through HooriDB's own Exit: a console with something at stake asks first, and
   * Cancel leaves HooriDB open. A plain Exit after it exits, without restarting.
   */
  @Test
  void restartNowAsksAConsoleAtStakeAndAnExitAfterItsCancelJustExits() throws Exception {
    var k = new ConsoleTest();
    k.dir = dir;
    k.setUp();
    try {
      var updater = updater().publish("0.3.0");
      k.c.updater = updater;
      k.c.version = "0.2.0";
      var app = k.console(SHOP);
      k.c.run(
          app, ConsoleTransactions.MANUAL + ConsoleTest.type(ConsoleTest.PAY) + ConsoleTest.RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction);
      assertTrue(app.state().updates.ready != null, "the update was downloaded meanwhile");
      k.c.run(app, "press #update-act\nframe\n" + OPENED + "snap asked\n");
      assertTrue(k.c.tree("asked").contains("role=dialog"), k.c.tree("asked"));
      k.c.run(app, "press #answer-cancel\nframe\n" + OPENED + CatalogTreeTest.SETTLE);
      assertFalse(app.windows().all().isEmpty());
      assertEquals(List.of(), updater.restarts);
      // Exit asks again, as the console still holds the change; Rollback lets it go.
      k.c.run(
          app,
          ConsoleTransactions.EXIT + "press #answer-rollback\nframe\n" + OPENED
              + CatalogTreeTest.SETTLE);
      assertTrue(app.windows().all().isEmpty());
      assertEquals(List.of(), updater.restarts, "a plain Exit does not restart");
      assertEquals(null, stored().attempt());
    } finally {
      k.tearDown();
    }
  }

  /** Without a console at stake, Restart now saves the attempt, starts the helper once and closes. */
  @Test
  void restartNowRestartsOnceAndCloses() throws Exception {
    var updater = updater().publish("0.3.0");
    var app = app(updater, "0.2.0");
    run(app, START + RESTART);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(1, updater.restarts.size(), updater.restarts.toString());
    var download = updater.restarts.getFirst();
    assertEquals(
        new StoredUpdates.Attempt(Version.parse("0.3.0"), download.file(), download.sha256()),
        stored().attempt());
  }

  /**
   * A helper that cannot be started keeps HooriDB open and says why; the attempt is the one before,
   * as of a helper still at work on the version that runs.
   */
  @Test
  void aRestartThatFailsStaysOpenWithANotice() throws Exception {
    var before = new StoredUpdates.Attempt(
        Version.parse("0.2.0"), dir.resolve("download-0.2.0"), "2".repeat(64));
    new UpdateStore(dir.resolve("config")).save(new StoredUpdates(Channel.STABLE, null, before));
    var updater = updater().publish("0.3.0");
    updater.failRestart = new IOException("The helper JAR is neither on the class path");
    var app = app(updater, "0.2.0");
    run(app, START + RESTART + OPENED + "snap failed\n");
    assertFalse(app.windows().all().isEmpty());
    var failed = tree("failed");
    assertTrue(failed.contains("HooriDB could not restart"), failed);
    assertTrue(failed.contains("The helper JAR is neither on the class path"), failed);
    assertTrue(failed.contains("HooriDB stayed open, as it could not restart to update."), failed);
    assertEquals(before, stored().attempt());
    assertEquals(null, app.state().guard.pending, "the Exit is over");
  }

  /**
   * An update that was rolled back is skipped: the notice says so, and the next start does not
   * offer it again, but a higher one.
   */
  @Test
  void aRolledBackUpdateIsSkippedUntilAHigherOneComesOut() throws Exception {
    var downloads = dir.resolve("updates").resolve("downloads");
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.STABLE,
            null,
            new StoredUpdates.Attempt(
                Version.parse("0.3.0"), downloads.resolve("download-x"), "0".repeat(64))));
    var updater = updater().publish("0.3.0");
    updater.result = new Installation.Result(
        Installation.Result.Status.ROLLED_BACK, "not-confirmed", "Not confirmed in time");
    var app = app(updater, "0.2.0");
    run(app, "size 1200 760\nframe\nawait\n");
    assertEquals(Version.parse("0.3.0"), stored().skipped(), "saved before the start takes it");
    run(app, START + "snap undone\n");
    var undone = tree("undone");
    assertTrue(undone.contains("The update to HooriDB 0.3.0 was undone"), undone);
    assertTrue(undone.contains("It did not start in time, so HooriDB 0.2.0 runs again."), undone);
    assertEquals(List.of(downloads), updater.cleared, "the downloads are done with");
    assertEquals(new StoredUpdates(Channel.STABLE, Version.parse("0.3.0"), null), stored());
    assertEquals(List.of(), updater.downloaded, "not checked again at once");

    var again = updater().publish("0.3.0");
    run(app(again, "0.2.0"), START + "snap again\n");
    assertFalse(tree("again").contains("#update"), tree("again"));
    assertEquals(List.of(), again.downloaded);

    var higher = updater().publish("0.3.0", "0.3.1");
    run(app(higher, "0.2.0"), START);
    assertEquals(List.of("0.3.1"), higher.downloaded);
  }

  /** An update whose files were in use is tried again with its download, which was kept. */
  @Test
  void aLockedUpdateTriesAgainWithItsDownload() throws Exception {
    var file = Files.createDirectories(dir.resolve("updates").resolve("downloads"))
        .resolve("download-HooriDB-0.3.0-linux-x64.tar.gz");
    Files.writeString(file, "0.3.0");
    var sha = "1".repeat(64);
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.STABLE, null, new StoredUpdates.Attempt(Version.parse("0.3.0"), file, sha)));
    var updater = updater().publish("0.3.0");
    updater.result = new Installation.Result(
        Installation.Result.Status.REJECTED, "locked", "A file stayed locked");
    var app = app(updater, "0.2.0");
    run(app, START + "snap locked\n");
    var locked = tree("locked");
    assertTrue(locked.contains("The update was not applied"), locked);
    assertTrue(locked.contains("Files of HooriDB were in use, as by a virus scanner."), locked);
    assertTrue(line(locked, "#update-act").contains("label=\"Try again\""), locked);
    assertEquals(List.of(file), updater.cleared, "all but the attempt's file go");
    run(app, RESTART);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(List.of(file), updater.restarts.stream().map(d -> d.file()).toList());
    assertEquals(sha, updater.restarts.getFirst().sha256());
    assertEquals(List.of(), updater.downloaded);
  }

  /** Another rejection says what the helper said, and keeps no attempt. */
  @Test
  void anotherRejectionSaysWhatTheHelperSaid() throws Exception {
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.STABLE,
            null,
            new StoredUpdates.Attempt(
                Version.parse("0.3.0"), dir.resolve("download-x"), "0".repeat(64))));
    var updater = updater().publish("0.3.0");
    updater.result = new Installation.Result(
        Installation.Result.Status.REJECTED,
        "checksum",
        "The download does not match its checksum");
    var app = app(updater, "0.2.0");
    run(app, START + "snap rejected\n");
    var rejected = tree("rejected");
    assertTrue(rejected.contains("The update was not applied"), rejected);
    assertTrue(rejected.contains("The download does not match its checksum"), rejected);
    assertEquals(null, stored().attempt());
  }

  /** A build of its own has no SemVer version: it checks for nothing and writes no settings. */
  @Test
  void aDevelopmentBuildWritesNoUpdateSettings() throws Exception {
    var updater = updater().publish("0.3.0");
    var app = app(updater, HooriDb.version());
    run(app, START + "snap start\n");
    assertFalse(Files.exists(dir.resolve("config").resolve(UpdateStore.FILE)));
    assertEquals(List.of(), updater.downloaded);
    assertEquals(Channel.STABLE, app.state().updates.channel());
  }

  /** A nightly without a stored channel takes Nightly, saves it, and the Help menu checks it. */
  @Test
  void aNightlyWithoutAStoredChannelStoresNightly() throws Exception {
    var app = app(updater(), NIGHTLY);
    run(app, START + HELP + "snap help\n");
    assertEquals(Channel.NIGHTLY, stored().channel());
    var help = tree("help");
    assertTrue(line(help, "label=\"Nightly updates\"").contains("checked"), help);
    assertFalse(line(help, "label=\"Stable updates\"").contains("checked"), help);
    assertTrue(line(help, "label=\"Restart to update\"").contains("disabled"), help);
  }

  /**
   * From Nightly to Stable while a nightly runs, the status line says that HooriDB stays on it;
   * the offer of a nightly goes, and About shows the channel.
   */
  @Test
  void switchingFromNightlyToStableSaysThatHooriDbStaysOnItsVersion() throws Exception {
    var updater = updater().publish("0.2.0-nightly.202611200300");
    var app = app(updater, NIGHTLY);
    run(app, START + "snap offered\n");
    assertTrue(tree("offered").contains("HooriDB 0.2.0-nightly.202611200300 is ready"));
    run(
        app,
        HELP + "press role=menuitem label=\"Stable updates\"\nframe\n" + OPENED
            + "await\nframe\n".repeat(2) + "snap stable\n"
            + "press role=button label=\"" + NIGHTLY + "\"\nframe\n" + OPENED + "snap about\n");
    var stable = tree("stable");
    assertTrue(
        stable.contains("HooriDB stays on " + NIGHTLY + " until a higher release comes out."),
        stable);
    assertFalse(stable.contains("#update"), "the nightly is no longer offered: " + stable);
    assertEquals(Channel.STABLE, stored().channel());
    var about = tree("about");
    assertTrue(about.contains("label=\"Updates\" ") && about.contains("value=\"Stable\""), about);
  }

  /** Try again offers the update of the old channel, so a switch of the channel drops it. */
  @Test
  void switchingTheChannelDropsTryAgain() throws Exception {
    var file = Files.createDirectories(dir.resolve("updates").resolve("downloads"))
        .resolve("download-HooriDB-" + NIGHTLY + "-linux-x64.tar.gz");
    Files.writeString(file, NIGHTLY);
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.NIGHTLY,
            null,
            new StoredUpdates.Attempt(Version.parse(NIGHTLY), file, "1".repeat(64))));
    var updater = updater();
    updater.result = new Installation.Result(
        Installation.Result.Status.REJECTED, "locked", "A file stayed locked");
    var app = app(updater, "0.1.0");
    run(
        app,
        START + HELP + "press role=menuitem label=\"Stable updates\"\nframe\n" + OPENED
            + "await\nframe\n".repeat(2) + "snap stable\n");
    assertFalse(tree("stable").contains("#update"), tree("stable"));
    assertEquals(List.of(), updater.restarts);
  }

  /** A switch of the channel while the start reads the result offers no Try again of the old one. */
  @Test
  void aSwitchDuringTheStartOffersNoTryAgainOfTheOldChannel() throws Exception {
    var file = Files.createDirectories(dir.resolve("updates").resolve("downloads"))
        .resolve("download-HooriDB-" + NIGHTLY + "-linux-x64.tar.gz");
    Files.writeString(file, NIGHTLY);
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.NIGHTLY,
            null,
            new StoredUpdates.Attempt(Version.parse(NIGHTLY), file, "1".repeat(64))));
    var updater = updater();
    updater.result = new Installation.Result(
        Installation.Result.Status.REJECTED, "locked", "A file stayed locked");
    updater.reading = new CountDownLatch(1);
    var app = app(updater, "0.1.0");
    run(
        app,
        "size 1200 760\nframe\n" + HELP + "press role=menuitem label=\"Stable updates\"\nframe\n");
    updater.reading.countDown();
    run(app, "await\nframe\n".repeat(3) + OPENED + "snap start\n");
    var start = tree("start");
    assertTrue(start.contains("A file stayed locked"), start);
    assertFalse(start.contains("Try again"), start);
  }

  /** An Exit while the start reads the result waits for it, so a rollback's skip is kept. */
  @Test
  void anExitDuringTheStartWaitsForTheResult() throws Exception {
    new UpdateStore(dir.resolve("config"))
        .save(new StoredUpdates(
            Channel.STABLE,
            null,
            new StoredUpdates.Attempt(
                Version.parse("0.3.0"), dir.resolve("download-x"), "0".repeat(64))));
    var updater = updater();
    updater.result = new Installation.Result(
        Installation.Result.Status.ROLLED_BACK, "not-confirmed", "Not confirmed in time");
    updater.reading = new CountDownLatch(1);
    var app = app(updater, "0.2.0");
    run(
        app,
        "size 1200 760\nframe\npress role=menuitem label=\"File\"\nframe\n" + OPENED
            + "press role=menuitem label=\"Exit\"\nframe\nframe\n");
    assertFalse(app.windows().all().isEmpty(), "waits for the start");
    updater.reading.countDown();
    run(app, "await\nframe\n".repeat(3));
    assertTrue(app.windows().all().isEmpty());
    assertEquals(Version.parse("0.3.0"), stored().skipped());
  }

  /** A version outside the app image, without an updater, chooses no channel by itself. */
  @Test
  void withoutAnUpdaterNoChannelIsStored() throws Exception {
    run(app(null, NIGHTLY), START);
    assertFalse(Files.exists(dir.resolve("config").resolve(UpdateStore.FILE)));
  }

  /** Once the first frame has shown, HooriDB confirms that it runs, once. */
  @Test
  void shownConfirmsOnce() throws Exception {
    var updater = updater();
    var app = app(updater, "0.2.0");
    run(app, START);
    app.state().updates.shown(app.state());
    app.state().updates.shown(app.state());
    run(app, "await\nframe\n");
    assertEquals(1, updater.confirms);
  }

  /**
   * With checkForUpdates off in settings.json, the start does not look for releases; turning it on
   * looks once, and toggling while work runs does not double-look.
   */
  @Test
  void checkForUpdatesOffDoesNotLookAtStartAndTurningItOnLooksOnce() throws Exception {
    var config = Files.createDirectories(dir.resolve("config"));
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"checkForUpdates":false}
        """);
    var updater = updater().publish("0.3.0");
    var app = app(updater, "0.2.0");
    run(app, START);
    assertEquals(0, updater.looks.get());
    assertEquals(List.of(), updater.downloaded);
    assertNull(app.state().updates.notice);

    updater.downloading = new CountDownLatch(1);
    app.state().choose(app.state().settings.withCheckForUpdates(true));
    run(app, "await\nframe\n");
    assertEquals(1, updater.looks.get());
    // Turning off and back on while the download is in flight does not look a second time.
    app.state().choose(app.state().settings.withCheckForUpdates(false));
    app.state().choose(app.state().settings.withCheckForUpdates(true));
    updater.downloading.countDown();
    run(app, "await\nframe\n".repeat(2) + OPENED);
    assertEquals(1, updater.looks.get());
    assertEquals(List.of("0.3.0"), updater.downloaded);
    // Nor once the update is ready: Restart now keeps the download it offers.
    app.state().choose(app.state().settings.withCheckForUpdates(false));
    app.state().choose(app.state().settings.withCheckForUpdates(true));
    run(app, "await\nframe\n");
    assertEquals(1, updater.looks.get());
    assertEquals(List.of("0.3.0"), updater.downloaded);
  }

  /** Turning checking on before updates have started looks once they have. */
  @Test
  void checkForUpdatesTurnedOnDuringTheStartLooksOnce() throws Exception {
    var config = Files.createDirectories(dir.resolve("config"));
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"checkForUpdates":false}
        """);
    var updater = updater().publish("0.3.0");
    updater.reading = new CountDownLatch(1);
    var app = app(updater, "0.2.0");
    run(app, "size 1200 760\nframe\n");
    app.await("load settings", Duration.ofSeconds(5));
    run(app, "frame\n");
    app.state().choose(app.state().settings.withCheckForUpdates(true));
    assertEquals(0, updater.looks.get());
    updater.reading.countDown();
    run(app, START);
    assertEquals(1, updater.looks.get());
  }

  /** Turning checking on and off again before updates have started looks for nothing. */
  @Test
  void checkForUpdatesTurnedOnAndOffDuringTheStartDoesNotLook() throws Exception {
    var config = Files.createDirectories(dir.resolve("config"));
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"checkForUpdates":false}
        """);
    var updater = updater().publish("0.3.0");
    updater.reading = new CountDownLatch(1);
    var app = app(updater, "0.2.0");
    run(app, "size 1200 760\nframe\n");
    app.await("load settings", Duration.ofSeconds(5));
    run(app, "frame\n");
    app.state().choose(app.state().settings.withCheckForUpdates(true));
    app.state().choose(app.state().settings.withCheckForUpdates(false));
    updater.reading.countDown();
    run(app, START);
    assertEquals(0, updater.looks.get());
  }

  /**
   * With checkForUpdates off, the start skips looking even when settings finish loading before
   * updates' own start.
   */
  @Test
  void checkForUpdatesOffSkipsLookWhenSettingsFinishBeforeUpdates() throws Exception {
    var config = Files.createDirectories(dir.resolve("config"));
    Files.writeString(config.resolve(SettingsStore.FILE), """
        {"version":1,"checkForUpdates":false}
        """);
    var updater = updater().publish("0.3.0");
    updater.reading = new CountDownLatch(1);
    var app = app(updater, "0.2.0");
    run(app, "size 1200 760\nframe\n");
    app.await("load settings", Duration.ofSeconds(5));
    run(app, "frame\n");
    assertFalse(app.state().keeping.settingsLoading);
    assertFalse(app.state().settings.checkForUpdates());
    updater.reading.countDown();
    run(app, START);
    assertEquals(0, updater.looks.get());
    assertEquals(List.of(), updater.downloaded);
  }

  /** Default settings (and an unreadable settings.json falling back to defaults) look once. */
  @Test
  void defaultAndUnreadableSettingsLookOnceAtStart() throws Exception {
    var defaultUpdater = updater().publish("0.3.0");
    run(app(defaultUpdater, "0.2.0"), START);
    assertEquals(1, defaultUpdater.looks.get());

    var config = Files.createDirectories(dir.resolve("config"));
    Files.writeString(config.resolve(SettingsStore.FILE), "{");
    var brokenUpdater = updater().publish("0.3.0");
    run(app(brokenUpdater, "0.2.0"), START);
    assertEquals(1, brokenUpdater.looks.get());
  }
}

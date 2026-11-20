package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Version;
import dev.hoori.db.core.connection.StoredUpdates;
import dev.hoori.db.core.connection.UpdateStore;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.secret.Keyring;
import dev.hoori.db.core.update.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  /** The images to look at, kept after the test in the module's build folder. */
  static final Path IMAGES = Path.of("target", "update-images").toAbsolutePath();

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
    Files.createDirectories(IMAGES);
    Script.run(
        "await\nframe\n".repeat(2) + OPENED + "snap ready-dark\n" + HELP + "snap help-dark\n"
            + "key Escape\nframe\n" + OPENED
            + "press role=menuitem label=\"View\"\nframe\n" + OPENED
            + "press role=menuitem label=\"Light theme\"\nframe\n" + OPENED + "advance 5\nframe\n"
            + "snap ready-light\n" + HELP + "snap help-light\n",
        app,
        IMAGES);
    assertEquals(List.of("0.3.0"), updater.downloaded);
    var ready = Files.readString(IMAGES.resolve("ready-dark.tree.txt"));
    assertTrue(ready.contains("HooriDB 0.3.0 is ready"), ready);
    assertTrue(ready.contains("Restart HooriDB to update from 0.2.0."), ready);
    assertTrue(line(ready, "#update-act").contains("label=\"Restart now\""), ready);
    assertTrue(line(ready, "#update-dismiss").contains("label=\"Later\""), ready);
    assertFalse(ready.contains("#downloading"), ready);
    var help = Files.readString(IMAGES.resolve("help-light.tree.txt"));
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
    assertTrue(
        available.contains("HooriDB cannot replace itself in " + Path.of("/opt/HooriDB")
            + ". Download it from its release page."),
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
    assertEquals(null, app.state().update);
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
          app, ConsoleTransactionTest.MANUAL + ConsoleTest.type(ConsoleTest.PAY) + ConsoleTest.RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction);
      assertTrue(app.state().ready != null, "the update was downloaded meanwhile");
      k.c.run(app, "press #update-act\nframe\n" + OPENED + "snap asked\n");
      assertTrue(k.c.tree("asked").contains("role=dialog"), k.c.tree("asked"));
      k.c.run(app, "press #answer-cancel\nframe\n" + OPENED + CatalogTreeTest.SETTLE);
      assertFalse(app.windows().all().isEmpty());
      assertEquals(List.of(), updater.restarts);
      // Exit asks again, as the console still holds the change; Rollback lets it go.
      k.c.run(
          app,
          ConsoleTransactionTest.EXIT + "press #answer-rollback\nframe\n" + OPENED
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

  /** A helper that cannot be started keeps HooriDB open, says why, and keeps no attempt. */
  @Test
  void aRestartThatFailsStaysOpenWithANotice() throws Exception {
    var updater = updater().publish("0.3.0");
    updater.failRestart = new IOException("The helper JAR is neither on the class path");
    var app = app(updater, "0.2.0");
    run(app, START + RESTART + OPENED + "snap failed\n");
    assertFalse(app.windows().all().isEmpty());
    var failed = tree("failed");
    assertTrue(failed.contains("HooriDB could not restart"), failed);
    assertTrue(failed.contains("The helper JAR is neither on the class path"), failed);
    assertTrue(failed.contains("HooriDB stayed open, as it could not restart to update."), failed);
    assertEquals(null, stored().attempt());
    assertEquals(null, app.state().pending, "the Exit is over");
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
    assertEquals(Channel.STABLE, app.state().channel());
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

  /** Once the first frame has shown, HooriDB confirms that it runs, once. */
  @Test
  void shownConfirmsOnce() throws Exception {
    var updater = updater();
    var app = app(updater, "0.2.0");
    run(app, START);
    app.state().shown();
    app.state().shown();
    run(app, "await\nframe\n");
    assertEquals(1, updater.confirms);
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TestConnectionTest.NEW_SHOP;
import static dev.hoori.db.app.TestConnectionTest.SETTLE;
import static dev.hoori.db.app.TestConnectionTest.START;
import static dev.hoori.db.app.TestConnectionTest.TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The version of a connection's driver, and the Drivers dialog. */
class DriversTest {

  static final String OLD = "42.7.10";
  static final String YOUNG = "42.7.15";
  static final String LOWER = "42.7.13"; // a maintenance release below the recommended one

  @TempDir
  Path dir;

  final ConnectionsTest.Secrets secrets = new ConnectionsTest.Secrets();
  final FakeDrivers.FakeServer server = new FakeDrivers.FakeServer();
  final FakeServers servers =
      new FakeServers().serve(CatalogueTest.PG, Provider.POSTGRESQL, "16.4", "shop\n  public\n");
  FakeDrivers repository;
  final List<App<State>> apps = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    repository = new FakeDrivers(Files.createDirectories(dir.resolve("repository")))
        .publishAll()
        .publish(OLD, FakeDrivers.PUBLISHED.minus(Duration.ofDays(60)))
        .publish(YOUNG, FakeDrivers.NOW.minus(Duration.ofHours(25)))
        .publish(LOWER, FakeDrivers.NOW.minus(Duration.ofHours(30)));
  }

  @AfterEach
  void tearDown() throws Exception {
    FakeDrivers.unlock(drivers(), FakeDrivers.VERSION);
    unlockArtifact();
    Files.deleteIfExists(jarFolder(OLD).resolve(FakeDrivers.HOLD));
    for (var gate :
        new CountDownLatch[] {server.gate, repository.reading, repository.opening, repository.dating
        }) {
      if (gate != null) {
        gate.countDown();
      }
    }
    servers.openAll();
    for (var app : apps) {
      app.await(Duration.ofSeconds(10));
      app.state().catalogue.closeAll();
      app.state().drivers.close();
    }
  }

  Path drivers() {
    return dir.resolve("drivers");
  }

  DriverStore store() {
    return new DriverStore(drivers(), repository);
  }

  App<State> app(ConnectionProfile... profiles) throws IOException {
    new ProfileStore(dir.resolve("config")).save(List.of(profiles));
    var app = HooriDb.app(
        dir.resolve("config"),
        () -> new Keyring.Available(secrets),
        repository.drivers(drivers()),
        server,
        servers);
    apps.add(app);
    return app;
  }

  Path jarFolder(String version) {
    return drivers()
        .resolve(FakeDrivers.DRIVER.group())
        .resolve(FakeDrivers.DRIVER.artifact())
        .resolve(version);
  }

  /** Makes the folder of all versions read-only, false where that cannot be done. */
  boolean lockArtifact() throws IOException {
    var artifact = jarFolder(OLD).getParent();
    if (!artifact.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return false;
    }
    Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("r-xr-xr-x"));
    return !Files.isWritable(artifact);
  }

  void unlockArtifact() throws IOException {
    var artifact = jarFolder(OLD).getParent();
    if (Files.exists(artifact)
        && artifact.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  void downloaded(String... versions) throws Exception {
    for (var version : versions) {
      store().download(FakeDrivers.DRIVER, version, DriverStore.Progress.NONE);
    }
  }

  void run(App<State> app, String script) throws IOException {
    Script.run(script, app, dir.resolve("out"));
  }

  String tree(String snap) throws IOException {
    return Files.readString(dir.resolve("out").resolve(snap + ".tree.txt"));
  }

  static final String VERSIONS = """
      press role=combobox label="Driver version"
      frame
      advance 1
      frame
      """;

  static String choose(String version) {
    return VERSIONS + "press role=option label=\"" + version + "\"\nframe\nadvance 1\nframe\n";
  }

  static final String OPEN_DRIVERS = """
      press role=menuitem label="File"
      frame
      advance 1
      frame
      press role=menuitem label="Drivers…"
      frame
      advance 1
      frame
      await
      frame
      advance 1
      frame
      """;

  @Test
  void theListOfVersionsWaitsLoadsAndFails() throws Exception {
    repository.opening = new CountDownLatch(1);
    var app = app();
    run(app, START + """
        press role=button label="New connection"
        frame
        advance 1
        frame
        snap waiting
        """);
    String waiting = tree("waiting");
    assertTrue(waiting.contains("role=status label=\"Reading the versions\""), waiting);
    assertTrue(waiting.contains("text \"Recommended\""), waiting);
    repository.opening.countDown();
    run(app, SETTLE + VERSIONS + "snap listed\nkey Escape\nframe\nadvance 1\nframe\n");
    String listed = tree("listed");
    assertFalse(listed.contains("Reading the versions"), listed);
    assertTrue(block(listed, "role=option label=\"Recommended\"").contains("\"42.7.14\""), listed);
    assertTrue(block(listed, "role=option label=\"" + YOUNG + "\"").contains("\"1 day old\""));
    assertTrue(block(listed, "role=option label=\"42.7.14\"").contains("\"recommended\""));
    assertTrue(listed.contains("role=option label=\"" + OLD + "\""), listed);
    repository.offline = true;
    run(app, """
        press role=radio label="MySQL"
        frame
        """ + SETTLE + "snap failed\n");
    String failed = tree("failed");
    assertTrue(
        failed.contains(
            "Its versions could not be read. Maven Central cannot be reached. Check the network"
                + " connection."),
        failed);
    assertTrue(failed.contains("role=button label=\"Read the versions again\""), failed);
    repository.offline = false;
    run(app, """
        press role=button label="Read the versions again"
        frame
        """ + SETTLE + "snap again\n");
    assertFalse(tree("again").contains("Its versions could not be read"), tree("again"));
  }

  /** The lines of the tree from the first that contains the text to the next of its kind. */
  static String block(String tree, String text) {
    var first = line(tree, text);
    var rest = tree.substring(tree.indexOf(first) + first.length());
    var kind = text.substring(0, text.indexOf(' ') < 0 ? text.length() : text.indexOf(' '));
    int next = rest.indexOf(kind);
    return first + (next < 0 ? rest : rest.substring(0, next));
  }

  @Test
  void aChosenVersionIsTestedAndSaved() throws Exception {
    var app = app();
    run(
        app,
        START + NEW_SHOP + choose(OLD) + "await\nframe\n" + TEST + "snap asked\n" + """
        press role=button label="Download"
        frame
        """ + SETTLE
            + SETTLE + SETTLE
            + "snap tested\n");
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("\"Download PostgreSQL JDBC " + OLD + "?\""), asked);
    String tested = tree("tested");
    assertTrue(tested.contains("role=status label=\"Connected to PostgreSQL 16.4\""), tested);
    assertTrue(tested.contains("\"PostgreSQL JDBC " + OLD + ", downloaded\""), tested);
    assertEquals(List.of(OLD), store().downloaded(FakeDrivers.DRIVER));
    assertSame(app.state().drivers.loaded(FakeDrivers.DRIVER, OLD), server.drivers.getFirst());
    assertFalse(tree("tested").contains("is 1 day old"), "an old version is not warned of");
    run(app, """
        press role=button label="Save"
        frame
        await
        frame
        await
        frame
        """);
    var saved = new ProfileStore(dir.resolve("config")).load();
    assertEquals(OLD, saved.getFirst().driverVersion());
  }

  @Test
  void aYoungVersionIsWarnedOf() throws Exception {
    var app = app();
    run(
        app,
        START + NEW_SHOP + choose(YOUNG) + "snap young\n" + choose("Recommended")
            + "snap recommended\n");
    String young = tree("young");
    assertTrue(young.contains("role=note label=\"Version " + YOUNG + " is 1 day old\""), young);
    assertTrue(young.contains("HooriDB recommends a release only once it has been out for 3 days"));
    // Right under the field it belongs to, above the next one.
    float note = TestConnectionTest.top(young, "role=note label=\"Version");
    assertTrue(TestConnectionTest.top(young, "label=\"Driver version\"") < note, young);
    assertTrue(note < TestConnectionTest.top(young, "#host"), young);
    assertFalse(tree("recommended").contains("role=note"), tree("recommended"));
  }

  /** A release below the recommended one can be the newest: its own date tells its age. */
  @Test
  void aLowerVersionPublishedYesterdayIsWarnedOf() throws Exception {
    var app = app();
    run(app, START + NEW_SHOP);
    repository.dating = new CountDownLatch(1);
    run(app, choose(LOWER) + "snap dating\n");
    String dating = tree("dating");
    assertTrue(
        dating.contains("role=status label=\"Looking up when " + LOWER + " was published\""),
        dating);
    assertFalse(dating.contains("role=note"), dating);
    repository.dating.countDown();
    run(app, SETTLE + "snap lower\n");
    String lower = tree("lower");
    assertTrue(lower.contains("role=note label=\"Version " + LOWER + " is 1 day old\""), lower);
    assertFalse(lower.contains("Looking up when"), lower);
  }

  @Test
  void theDriversDialogListsWhatIsDownloaded() throws Exception {
    var app = app();
    run(app, START + OPEN_DRIVERS + "snap empty\nkey Escape\nframe\nadvance 1\nframe\n");
    String empty = tree("empty");
    assertTrue(line(empty, "role=dialog").contains("label=\"Drivers\""), empty);
    assertEquals(3, empty.split("\"None downloaded\"").length - 1, empty);
    downloaded(FakeDrivers.VERSION, OLD);
    run(app, OPEN_DRIVERS + "snap listed\n");
    String listed = tree("listed");
    assertTrue(
        block(listed, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + ", recommended\""), listed);
    assertTrue(block(listed, "\"" + OLD + "\"").contains("\"" + FakeDrivers.SIZE + "\""), listed);
    assertEquals(2, listed.split("\"None downloaded\"").length - 1, listed);
    assertTrue(line(listed, "label=\"Download PostgreSQL JDBC\"").endsWith("disabled"), listed);
    assertTrue(listed.contains("\"42.7.14 is downloaded.\""), listed);
  }

  @Test
  void aDownloadShowsItsProgressAndCanBeCancelled() throws Exception {
    repository.reading = new CountDownLatch(1);
    var app = app();
    run(app, START + OPEN_DRIVERS + """
        press role=button label="Download PostgreSQL JDBC"
        frame
        """);
    assertTrue(repository.halfway.await(10, TimeUnit.SECONDS), "the transfer began");
    run(app, "frame\nadvance 1\nframe\nsnap downloading\n");
    String downloading = tree("downloading");
    assertTrue(
        downloading.contains("role=progressbar label=\"Downloading PostgreSQL JDBC 42.7.14\""),
        downloading);
    run(app, """
        press role=button label="Cancel download"
        frame
        """ + SETTLE + "snap cancelled\n");
    assertFalse(tree("cancelled").contains("role=progressbar label=\"Downloading"));
    TestConnectionTest.assertNothingIn(drivers());
    repository.reading = null;
    run(app, """
        press role=button label="Download PostgreSQL JDBC"
        frame
        """ + SETTLE + SETTLE + "snap done\n");
    String done = tree("done");
    assertTrue(
        block(done, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + ", recommended\""), done);
    assertEquals(List.of(FakeDrivers.VERSION), store().downloaded(FakeDrivers.DRIVER));
  }

  @Test
  void aFailedDownloadSaysWhatToDo() throws Exception {
    repository.publish(false);
    var app = app();
    run(app, START + OPEN_DRIVERS + """
        press role=button label="Download PostgreSQL JDBC"
        frame
        """ + SETTLE + "snap mismatch\n");
    String mismatch = tree("mismatch");
    assertTrue(
        mismatch.contains("role=note label=\"PostgreSQL JDBC 42.7.14 could not be downloaded\""),
        mismatch);
    assertTrue(mismatch.contains("\"The file does not match its checksum"), mismatch);
    repository.offline = true;
    run(app, """
        press role=button label="Try again"
        frame
        """ + SETTLE + "snap offline\n");
    assertTrue(
        tree("offline").contains("Maven Central cannot be reached. Check the network connection."),
        tree("offline"));
    var drivers = app.state().drivers;
    assertTrue(Fetch.text(failure(DriverException.Failure.NO_SPACE), drivers)
        .contains("Free some space and try again."));
    assertTrue(Fetch.text(failure(DriverException.Failure.UNAVAILABLE), drivers)
        .startsWith("Maven Central answered with an error or stopped answering. Try again later"));
  }

  private static DriverException failure(DriverException.Failure failure) {
    return new DriverException(failure, "a path");
  }

  static final String DELETE_OLD = """
      press role=button label="Delete PostgreSQL JDBC 42.7.10"
      frame
      advance 1
      frame
      """;

  @Test
  void aDeleteAsksFirst() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var app = app();
    run(
        app,
        START + OPEN_DRIVERS + DELETE_OLD + "snap asked\n" + """
        press role=button label="Cancel"
        frame
        advance 1
        frame
        """ + DELETE_OLD + """
        press role=button label="Delete"
        frame
        """ + SETTLE
            + SETTLE + "snap deleted\n");
    String asked = tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Delete PostgreSQL JDBC 42.7.10?\""), asked);
    String deleted = tree("deleted");
    assertFalse(deleted.contains("\"" + OLD + "\""), deleted);
    assertTrue(deleted.contains("\"PostgreSQL JDBC 42.7.10 deleted\""), deleted);
    assertEquals(List.of(FakeDrivers.VERSION), store().downloaded(FakeDrivers.DRIVER));
  }

  @Test
  void aVersionAConnectionUsesCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogueTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    app.state().rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    run(app, CatalogueTest.open("shop-prod") + SETTLE + SETTLE + OPEN_DRIVERS + "snap used\n");
    String used = tree("used");
    assertTrue(
        block(used, "\"" + OLD + "\"")
            .contains(
                "\"" + FakeDrivers.SIZE + ", in use by shop-prod. Disconnect to delete it.\""),
        used);
    assertTrue(line(used, "label=\"Delete PostgreSQL JDBC 42.7.10\"").endsWith("disabled"), used);
    assertFalse(line(used, "label=\"Delete PostgreSQL JDBC 42.7.14\"").endsWith("disabled"));
    // The connection connected with the version it chose.
    var live = app.state().catalogue.lives.get(shop.id());
    assertSame(app.state().drivers.loaded(FakeDrivers.DRIVER, OLD), live.driver);
  }

  @Test
  void aVersionOnlyAConsoleUsesCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogueTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    run(
        app,
        CatalogueTest.menu("shop-prod", "New console") + SETTLE
            + "press #sql\ntype \"SELECT 1\"\nkey Enter control\n" + CatalogueTest.SETTLE);
    var console = s.consoles(null).getFirst();
    // The console's own connection has the version the connection chose, as the tree's has.
    assertSame(s.drivers.loaded(FakeDrivers.DRIVER, OLD), console.driver);
    // The tree's connection is lost; the console's goes on, and holds the version alone.
    s.catalogue.lost(s, s.catalogue.live(shop.id()), "gone", false);
    assertTrue(s.catalogue.users(s, FakeDrivers.DRIVER, OLD).isEmpty());
    run(app, OPEN_DRIVERS + "snap used\n");
    String used = tree("used");
    assertTrue(
        block(used, "\"" + OLD + "\"")
            .contains(
                "\"" + FakeDrivers.SIZE + ", in use by shop-prod. Disconnect to delete it.\""),
        used);
    assertTrue(line(used, "label=\"Delete PostgreSQL JDBC 42.7.10\"").endsWith("disabled"), used);
    assertFalse(s.claimForDelete(FakeDrivers.DRIVER, OLD), "the delete is refused");
    // Another version for the connection ends the console's connection, as another host does.
    s.put(shop.withDriverVersion(FakeDrivers.VERSION));
    assertFalse(console.connected());
    assertTrue(s.users(FakeDrivers.DRIVER, OLD).isEmpty());
  }

  /** As on Windows, where a driver used since HooriDB started keeps its file open. */
  @Test
  void aDeleteThatFailsKeepsTheVersion() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var app = app();
    run(app, START);
    var s = app.state();
    s.drivers.load(app, FakeDrivers.DRIVER, FakeDrivers.VERSION, d -> {}, e -> {});
    assumeTrue(FakeDrivers.lock(drivers(), FakeDrivers.VERSION), "a folder can be read-only");
    FakeDrivers.lock(drivers(), OLD);
    try {
      run(
          app,
          SETTLE + OPEN_DRIVERS + """
          press role=button label="Delete PostgreSQL JDBC 42.7.14"
          frame
          advance 1
          frame
          press role=button label="Delete"
          frame
          """ + SETTLE + "snap loaded\n" + DELETE_OLD + """
          press role=button label="Delete"
          frame
          """ + SETTLE
              + "snap failed\n");
    } finally {
      FakeDrivers.unlock(drivers(), OLD);
    }
    String loaded = tree("loaded");
    assertTrue(
        block(loaded, "role=note label=\"PostgreSQL JDBC 42.7.14 could not be deleted\"")
            .contains("delete it after HooriDB was restarted."),
        loaded);
    String failed = tree("failed");
    assertTrue(
        block(failed, "role=note label=\"PostgreSQL JDBC 42.7.10 could not be deleted\"")
            .contains("Check that HooriDB may write to it, then try again."),
        failed);
    assertFalse(failed.contains(dir.toString()), "no absolute path in\n" + failed);
    assertEquals(List.of(FakeDrivers.VERSION, OLD), store().downloaded(FakeDrivers.DRIVER));
    assertTrue(
        block(failed, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + ", recommended\""), failed);
    store().load(FakeDrivers.DRIVER, FakeDrivers.VERSION).close(); // still whole
  }

  /** A connection that decided on a version holds it while its driver still loads. */
  @Test
  void aVersionBeingLoadedCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogueTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    app.state().rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    Files.writeString(jarFolder(OLD).resolve(FakeDrivers.HOLD), "");
    // The lookup decides on the version; its load then waits at the hold.
    run(app, CatalogueTest.open("shop-prod") + "await\nframe\n");
    for (int i = 0; !app.state().drivers.loading(FakeDrivers.DRIVER, OLD); i++) {
      assertTrue(i < 500, "the load began");
      Thread.sleep(10);
    }
    // An await would wait for the load, so the Drivers dialog cannot show its rows meanwhile;
    // it asks the same question.
    assertEquals(List.of("shop-prod"), app.state().users(FakeDrivers.DRIVER, OLD));
    assertFalse(app.state().claimForDelete(FakeDrivers.DRIVER, OLD), "the delete is refused");
    assertEquals(List.of(), app.state().users(FakeDrivers.DRIVER, FakeDrivers.VERSION));
    Files.delete(jarFolder(OLD).resolve(FakeDrivers.HOLD));
    run(app, SETTLE + SETTLE);
    var live = app.state().catalogue.lives.get(shop.id());
    assertTrue(live.phase instanceof Catalogue.Connected, String.valueOf(live.phase));
    assertEquals(List.of(FakeDrivers.VERSION, OLD), store().downloaded(FakeDrivers.DRIVER));
  }

  /** A connection that begins while a delete of its version runs asks to download it again. */
  @Test
  void aConnectionDuringADeleteAsksForTheDriver() throws Exception {
    downloaded(OLD);
    var shop = CatalogueTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    assertTrue(s.claimForDelete(FakeDrivers.DRIVER, OLD));
    run(app, CatalogueTest.open("shop-prod") + SETTLE + SETTLE + "snap asked\n");
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("\"Download PostgreSQL JDBC " + OLD + "?\""), asked);
    s.drivers.deleting(FakeDrivers.DRIVER, OLD, false); // the delete failed: the file is there
    run(app, "press role=button label=\"Download\"\nframe\n" + SETTLE + SETTLE + SETTLE);
    assertTrue(s.catalogue.lives.get(shop.id()).phase instanceof Catalogue.Connected);
  }

  /** Its file went, then its folder could not be renamed away: the version is gone, as it says. */
  @Test
  void aDeleteThatFailsAfterTheFileWentSaysItIsGone() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var app = app();
    run(app, START);
    var s = app.state();
    s.drivers.load(app, FakeDrivers.DRIVER, FakeDrivers.VERSION, d -> {}, e -> {});
    run(app, SETTLE);
    assumeTrue(lockArtifact(), "a folder can be read-only");
    run(app, OPEN_DRIVERS + """
        press role=button label="Delete PostgreSQL JDBC 42.7.14"
        frame
        advance 1
        frame
        press role=button label="Delete"
        frame
        """ + SETTLE + SETTLE + "snap gone\n");
    String gone = tree("gone");
    assertFalse(gone.contains("could not be deleted"), gone);
    assertFalse(gone.contains("\"42.7.14\"\n"), gone);
    assertTrue(gone.contains("\"PostgreSQL JDBC 42.7.14 deleted\""), gone);
    assertEquals(List.of(OLD), store().downloaded(FakeDrivers.DRIVER));
    assertEquals(null, s.drivers.loaded(FakeDrivers.DRIVER, FakeDrivers.VERSION));
  }

  /** The system's words name the absolute path; the dialog says what it is in HooriDB's. */
  @Test
  void anUnreadableFolderIsNamedWithoutItsPath() throws Exception {
    downloaded(OLD);
    var app = app();
    run(app, START);
    var artifact = jarFolder(OLD).getParent();
    assumeTrue(artifact.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("---------"));
    assumeTrue(!Files.isReadable(artifact), "not as root");
    run(app, OPEN_DRIVERS + "snap unreadable\n");
    String unreadable = tree("unreadable");
    assertTrue(
        block(unreadable, "role=note label=\"PostgreSQL JDBC could not be listed\"")
            .contains(
                "The drivers folder could not be read: access was denied. Check that HooriDB may"
                    + " read it, then try again."),
        unreadable);
    assertFalse(unreadable.contains(dir.toString()), "no absolute path in\n" + unreadable);
  }

  /**
   * The pictures for a review, light and dark, into the folder that {@code -Dreview=<folder>}
   * names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void pictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var app = app();
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    String close = "key Escape\nframe\nadvance 1\nframe\n";
    Script.run(
        START + NEW_SHOP + VERSIONS + "frame\nframe\nsnap connection-versions-" + look + "\n"
            + close
            + choose(YOUNG) + "await\nframe\nsnap connection-young-" + look + "\n" + close
            + "press role=button label=\"Discard\"\nframe\nadvance 1\nframe\n" + OPEN_DRIVERS
            + "snap drivers-empty-" + look + "\n" + close,
        app,
        out);
    downloaded(FakeDrivers.VERSION, OLD);
    repository.reading = new CountDownLatch(1);
    Script.run(
        OPEN_DRIVERS + "press role=button label=\"Download MariaDB Connector/J\"\nframe\n",
        app,
        out);
    assertTrue(repository.halfway.await(10, TimeUnit.SECONDS), "the transfer began");
    Script.run(
        "frame\nadvance 1\nframe\nsnap drivers-downloading-" + look + "\n"
            + "press role=button label=\"Cancel download\"\nframe\n" + SETTLE,
        app,
        out);
    repository.reading = null;
    repository.offline = true;
    Script.run(
        "press role=button label=\"Download MariaDB Connector/J\"\nframe\n" + SETTLE
            + "snap drivers-error-" + look + "\n" + DELETE_OLD + "snap drivers-question-"
            + look + "\n" + close + close,
        app,
        out);
    repository.offline = false;
    var s = app.state();
    s.drivers.load(app, FakeDrivers.DRIVER, FakeDrivers.VERSION, d -> {}, e -> {});
    if (FakeDrivers.lock(drivers(), FakeDrivers.VERSION)) {
      Script.run(
          SETTLE + OPEN_DRIVERS + "press role=button label=\"Delete PostgreSQL JDBC 42.7.14\"\n"
              + "frame\nadvance 1\nframe\npress role=button label=\"Delete\"\nframe\n" + SETTLE
              + "snap drivers-locked-" + look + "\n",
          app,
          out);
    }
    for (var name : List.of(
        "connection-versions",
        "connection-young",
        "drivers-empty",
        "drivers-downloading",
        "drivers-error",
        "drivers-question")) {
      assertTrue(Files.exists(out.resolve(name + "-" + look + ".png")), name);
    }
  }
}

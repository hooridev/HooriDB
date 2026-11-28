package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TestConnectionTest.NEW_SHOP;
import static dev.hoori.db.app.TestConnectionTest.SETTLE;
import static dev.hoori.db.app.TestConnectionTest.START;
import static dev.hoori.db.app.TestConnectionTest.TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.DriverException;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.driver.DriverStoreTest;
import dev.hoori.db.core.driver.LoadedDriver;
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
import java.util.concurrent.atomic.AtomicReference;
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
      new FakeServers().serve(CatalogTreeTest.PG, Provider.POSTGRESQL, "16.4", "shop\n  public\n");
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
      app.state().catalog.closeAll();
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
    new WorkspaceStore(dir.resolve("config")).save(new Workspace(List.of(profiles)));
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

  /** Driver…: the driver's dialog over the connection's, its versions read. */
  static final String DRIVER = """
      press role=button label="Driver"
      frame
      advance 1
      frame
      await
      frame
      advance 1
      frame
      """;

  static final String CLOSE_DRIVER = """
      press role=button label="Close"
      frame
      advance 1
      frame
      """;

  /** The list of versions, in the driver's dialog. */
  static final String LIST = """
      press role=combobox label="Driver version"
      frame
      advance 1
      frame
      """;

  static final String VERSIONS = DRIVER + LIST;

  /** Picks the version in the driver's dialog, which stays open. */
  static String pick(String version) {
    return LIST + "press role=option label=\"" + version + "\"\nframe\nadvance 1\nframe\n";
  }

  /** Opens the driver's dialog and picks the version; the dialog stays open. */
  static String choose(String version) {
    return DRIVER + pick(version);
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
  void theAppHasNoDriverOfMongoDbOnItsClassPath() {
    // It is downloaded as the JDBC drivers are, and core's bridge is loaded beside it.
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName(
            "com.mongodb.client.MongoClients", false, DriversTest.class.getClassLoader()));
  }

  @Test
  void theListOfVersionsWaitsLoadsAndFails() throws Exception {
    var app = app();
    run(app, START);
    repository.opening = new CountDownLatch(1);
    run(app, """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=button label="Driver"
        frame
        advance 1
        frame
        snap waiting
        """);
    String waiting = tree("waiting");
    assertTrue(waiting.contains("role=status label=\"Reading the versions\""), waiting);
    assertTrue(waiting.contains("text \"Recommended\""), waiting);
    repository.opening.countDown();
    run(app, SETTLE + LIST + "snap listed\nkey Escape\nframe\nadvance 1\nframe\n" + CLOSE_DRIVER);
    String listed = tree("listed");
    assertFalse(listed.contains("Reading the versions"), listed);
    assertTrue(block(listed, "role=option label=\"Recommended\"").contains("\"42.7.14\""), listed);
    assertTrue(block(listed, "role=option label=\"" + YOUNG + "\"").contains("\"1 day old\""));
    assertTrue(block(listed, "role=option label=\"42.7.14\"").contains("\"recommended\""));
    assertTrue(listed.contains("role=option label=\"" + OLD + "\""), listed);
    repository.offline = true;
    run(app, ConnectionsTest.provider("MySQL") + SETTLE + DRIVER + "snap failed\n");
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
        START + NEW_SHOP + choose(OLD) + CLOSE_DRIVER + "await\nframe\n" + TEST + "snap asked\n"
            + """
        press role=button label="Download"
        frame
        """ + SETTLE
            + SETTLE + SETTLE
            + "snap tested\n" + DRIVER + "snap driver\n" + CLOSE_DRIVER);
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("\"Download PostgreSQL JDBC " + OLD + "?\""), asked);
    String tested = tree("tested");
    assertTrue(tested.contains("role=note label=\"Connected to PostgreSQL 16.4\""), tested);
    assertTrue(tree("driver").contains("\"PostgreSQL JDBC " + OLD + ", downloaded\""), tested);
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
    var saved = new WorkspaceStore(dir.resolve("config")).load().connections();
    assertEquals(OLD, saved.getFirst().driverVersion());
  }

  @Test
  void aYoungVersionIsWarnedOfWithWhatItsDriverLineSays() throws Exception {
    var app = app();
    run(
        app,
        START + NEW_SHOP + choose(YOUNG) + "await\nframe\nsnap young\n" + pick("Recommended")
            + "await\nframe\nsnap recommended\n");
    String young = tree("young");
    // The field's warning, which stays valid: the driver's line and the age, under the field.
    var said = "PostgreSQL JDBC " + YOUNG + ", not downloaded yet (" + FakeDrivers.SIZE
        + ", from Maven Central). Only 1 day old: recommended after 3 days, once a bad release"
        + " would be withdrawn.";
    var box = line(young, "#driver-version");
    assertTrue(box.contains("warning=\"" + said), box);
    assertFalse(box.contains("invalid"), box);
    // Under the box in the driver's dialog, and under the type in the form beneath it too.
    var dialog = young.substring(young.indexOf("role=dialog label=\"PostgreSQL driver\""));
    float text = TestConnectionTest.top(dialog, "text \"" + said);
    assertTrue(TestConnectionTest.top(dialog, "#driver-version") < text, young);
    assertTrue(
        line(young, "#type").contains("warning=\"Only 1 day old: recommended after 3 days.\""),
        young);
    assertFalse(young.contains("role=note"), young);
    var recommended = tree("recommended");
    assertFalse(line(recommended, "#driver-version").contains("warning="), recommended);
    assertTrue(
        recommended.contains("\"PostgreSQL JDBC 42.7.14, not downloaded yet ("), recommended);
  }

  /**
   * What failed has to be said: the help says it, and no warning hides it. No step leads there yet,
   * as a failed list leaves no version too new, so the test sets the failure.
   */
  @Test
  void aFailureToSayWinsOverTheAgeOfAYoungVersion() throws Exception {
    var app = app();
    run(app, START + NEW_SHOP + choose(YOUNG) + "await\nframe\n");
    app.state().dialogs.dialog.versions.failed = new IOException("denied");
    app.invalidate();
    run(app, "frame\nsnap failed\n");
    String failed = tree("failed");
    assertFalse(line(failed, "#driver-version").contains("warning="), failed);
    assertTrue(
        failed.contains("\"PostgreSQL JDBC " + YOUNG + ", not downloaded yet (" + FakeDrivers.SIZE
            + ", from Maven Central)\\nThe drivers folder could not be read"),
        failed);
  }

  /** A release below the recommended one can be the newest: its own date tells its age. */
  @Test
  void aLowerVersionPublishedYesterdayIsWarnedOf() throws Exception {
    var app = app();
    run(app, START + NEW_SHOP + DRIVER);
    repository.dating = new CountDownLatch(1);
    run(app, pick(LOWER) + "snap dating\n");
    String dating = tree("dating");
    assertTrue(
        dating.contains("role=status label=\"Looking up when " + LOWER + " was published\""),
        dating);
    assertFalse(dating.contains("role=note"), dating);
    repository.dating.countDown();
    run(app, SETTLE + "snap lower\n");
    String lower = tree("lower");
    assertTrue(line(lower, "#driver-version").contains(". Only 1 day old: "), lower);
    assertFalse(lower.contains("Looking up when"), lower);
  }

  @Test
  void theDriversDialogListsWhatIsDownloaded() throws Exception {
    var app = app();
    run(app, START + OPEN_DRIVERS + "snap empty\nkey Escape\nframe\nadvance 1\nframe\n");
    String empty = tree("empty");
    assertTrue(line(empty, "role=dialog").contains("label=\"Drivers\""), empty);
    int kinds = Provider.values().length;
    assertEquals(kinds, empty.split("\"None downloaded\"").length - 1, empty);
    downloaded(FakeDrivers.VERSION, OLD);
    run(app, OPEN_DRIVERS + "snap listed\n");
    String listed = tree("listed");
    assertTrue(
        block(listed, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + ", recommended\""), listed);
    assertTrue(block(listed, "\"" + OLD + "\"").contains("\"" + FakeDrivers.SIZE + "\""), listed);
    assertEquals(kinds - 1, listed.split("\"None downloaded\"").length - 1, listed);
    assertTrue(line(listed, "label=\"Download PostgreSQL JDBC\"").endsWith("disabled"), listed);
    assertTrue(listed.contains("\"42.7.14 is downloaded.\""), listed);
  }

  @Test
  void theDriversDialogShowsDownloadedVersionsWhileTheRepositoryIsHeld() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var app = app();
    run(app, START);
    repository.opening = new CountDownLatch(1);
    run(app, """
        press role=menuitem label="File"
        frame
        advance 1
        frame
        press role=menuitem label="Drivers…"
        frame
        advance 1
        frame
        """);
    app.await("read the downloaded versions of a driver", Duration.ofSeconds(10));
    CatalogTreeTest.eventually(() -> repository.listings(Provider.POSTGRESQL) == 1);
    run(app, "frame\nadvance 1\nframe\nsnap held\n");
    assertEquals(1, repository.opening.getCount(), "the repository read is still held");
    String held = tree("held");
    assertTrue(block(held, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + "\""), held);
    assertTrue(block(held, "\"" + OLD + "\"").contains("\"" + FakeDrivers.SIZE + "\""), held);
    assertTrue(
        held.contains("role=status label=\"Reading the versions of PostgreSQL JDBC\""), held);
    assertFalse(held.contains("Reading the downloaded versions of"), held);
    repository.opening.countDown();
    run(app, SETTLE + "snap loaded\n");
    String loaded = tree("loaded");
    assertTrue(
        block(loaded, "\"42.7.14\"").contains("\"" + FakeDrivers.SIZE + ", recommended\""), loaded);
    assertFalse(loaded.contains("Reading the versions of"), loaded);
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

  /** A version used since HooriDB started is closed as it is deleted, as Windows needs it. */
  @Test
  void aVersionUsedBeforeIsDeletedWithNothingOpen() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    run(app, CatalogTreeTest.open("shop-prod") + SETTLE + SETTLE);
    var used = s.drivers.loaded(FakeDrivers.DRIVER, OLD);
    assertSame(used, s.catalog.lives.get(shop.id()).driver);
    run(app, CatalogTreeTest.menu("shop-prod", "Disconnect") + SETTLE);
    assertEquals(List.of(), s.users(FakeDrivers.DRIVER, OLD));
    run(app, OPEN_DRIVERS + DELETE_OLD + """
        press role=button label="Delete"
        frame
        """ + SETTLE + SETTLE + "snap deleted\n");
    assertTrue(tree("deleted").contains("\"PostgreSQL JDBC 42.7.10 deleted\""), tree("deleted"));
    assertEquals(List.of(FakeDrivers.VERSION), store().downloaded(FakeDrivers.DRIVER));
    assertEquals(List.of(), DriverStoreTest.openBelow(dir));
    assertNull(s.drivers.loaded(FakeDrivers.DRIVER, OLD));
  }

  /** A connection that closes may still run its driver's code: its version's delete waits. */
  @Test
  void aDeleteWaitsForAConnectionThatCloses() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    s.drivers.load(app, FakeDrivers.DRIVER, OLD, d -> {}, e -> {});
    run(app, SETTLE);
    servers.dead = true; // the read ignores the interrupt, so the closing waits for it
    var gate = servers.hold("databases " + CatalogTreeTest.PG);
    run(app, CatalogTreeTest.open("shop-prod"));
    CatalogTreeTest.eventually(() -> servers.calls.contains("databases " + CatalogTreeTest.PG));
    run(app, CatalogTreeTest.menu("shop-prod", "Disconnect"));
    assertEquals(List.of(), s.users(FakeDrivers.DRIVER, OLD));
    run(app, OPEN_DRIVERS + DELETE_OLD + """
        press role=button label="Delete"
        frame
        """);
    Thread.sleep(500);
    assertEquals(List.of(FakeDrivers.VERSION, OLD), store().downloaded(FakeDrivers.DRIVER));
    gate.countDown();
    run(app, SETTLE + SETTLE + "snap deleted\n");
    assertTrue(servers.closed.contains(CatalogTreeTest.PG + "/shop"), servers.closed.toString());
    assertTrue(tree("deleted").contains("\"PostgreSQL JDBC 42.7.10 deleted\""), tree("deleted"));
    assertEquals(List.of(FakeDrivers.VERSION), store().downloaded(FakeDrivers.DRIVER));
    assertEquals(List.of(), DriverStoreTest.openBelow(dir));
  }

  /**
   * A refused login holds no version; asked for the password again, it finds the driver anew and
   * then asks for the password.
   */
  @Test
  void thePasswordAskedAfterADeleteFindsTheDriverAnew() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    secrets.stored.put(shop.id(), ConnectionsTest.PASSWORD);
    servers.fail(
        "open " + CatalogTreeTest.PG + "/shop",
        FakeServers.notConnected(ConnectionException.Failure.LOGIN_REFUSED));
    run(app, CatalogTreeTest.open("shop-prod") + SETTLE + SETTLE + SETTLE);
    var refused = s.catalog.lives.get(shop.id()).driver;
    assertSame(s.drivers.loaded(FakeDrivers.DRIVER, OLD), refused);
    run(app, OPEN_DRIVERS + DELETE_OLD + """
        press role=button label="Delete"
        frame
        """ + SETTLE + SETTLE + """
        press role=button label="Close"
        frame
        advance 1
        frame
        press role=button label="Enter password…"
        frame
        advance 1
        frame
        """ + SETTLE + "snap asked\n");
    assertEquals(List.of(FakeDrivers.VERSION), store().downloaded(FakeDrivers.DRIVER));
    assertNotSame(refused, s.catalog.lives.get(shop.id()).driver);
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("\"Download PostgreSQL JDBC " + OLD + "?\""), asked);
    run(
        app,
        "press role=button label=\"Download\"\nframe\n" + SETTLE + SETTLE + SETTLE
            + "snap typing\n");
    // The password the server refused is not tried again: the user types one.
    assertEquals(List.of(ConnectionsTest.PASSWORD), servers.passwords);
    String typing = tree("typing");
    assertTrue(typing.contains("role=textbox label=\"Password\""), typing);
  }

  @Test
  void aVersionAConnectionUsesCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    app.state().rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    run(app, CatalogTreeTest.open("shop-prod") + SETTLE + SETTLE + OPEN_DRIVERS + "snap used\n");
    String used = tree("used");
    assertTrue(
        block(used, "\"" + OLD + "\"")
            .contains(
                "\"" + FakeDrivers.SIZE + ", in use by shop-prod. Disconnect to delete it.\""),
        used);
    assertTrue(line(used, "label=\"Delete PostgreSQL JDBC 42.7.10\"").endsWith("disabled"), used);
    assertFalse(line(used, "label=\"Delete PostgreSQL JDBC 42.7.14\"").endsWith("disabled"));
    // The connection connected with the version it chose.
    var live = app.state().catalog.lives.get(shop.id());
    assertSame(app.state().drivers.loaded(FakeDrivers.DRIVER, OLD), live.driver);
  }

  @Test
  void aVersionOnlyAConsoleUsesCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    run(
        app,
        CatalogTreeTest.menu("shop-prod", "New console") + SETTLE
            + "press #sql\ntype \"SELECT 1\"\nkey Enter control\n" + CatalogTreeTest.SETTLE);
    var console = s.consoles(null).getFirst();
    // The console's own connection has the version the connection chose, as the tree's has.
    assertSame(s.drivers.loaded(FakeDrivers.DRIVER, OLD), console.driver);
    // The tree's connection is lost; the console's goes on, and holds the version alone.
    s.catalog.lost(s, s.catalog.live(shop.id()), "gone", false);
    assertTrue(s.catalog.users(s, FakeDrivers.DRIVER, OLD).isEmpty());
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

  /** A version used since HooriDB started stays too, closed, and is loaded anew when needed. */
  @Test
  void aDeleteThatFailsKeepsTheVersion() throws Exception {
    // Before the load: on Windows, a driver loaded by a test that stops later keeps its file open,
    // and the test's folder cannot be deleted.
    assumeTrue(
        dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "a folder can be read-only");
    downloaded(FakeDrivers.VERSION, OLD);
    var app = app();
    run(app, START);
    var s = app.state();
    s.drivers.load(app, FakeDrivers.DRIVER, FakeDrivers.VERSION, d -> {}, e -> {});
    run(app, SETTLE);
    var used = s.drivers.loaded(FakeDrivers.DRIVER, FakeDrivers.VERSION);
    assertNotNull(used);
    assumeTrue(FakeDrivers.lock(drivers(), FakeDrivers.VERSION), "a folder can be read-only");
    FakeDrivers.lock(drivers(), OLD);
    try {
      run(
          app,
          OPEN_DRIVERS + """
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
            .contains("Check that HooriDB may write to it, then try again."),
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
    assertNull(s.drivers.loaded(FakeDrivers.DRIVER, FakeDrivers.VERSION));
    var again = new AtomicReference<LoadedDriver>();
    s.drivers.load(app, FakeDrivers.DRIVER, FakeDrivers.VERSION, again::set, e -> {});
    run(app, SETTLE);
    assertNotNull(again.get());
    assertNotSame(used, again.get());
  }

  /** A connection that decided on a version holds it while its driver still loads. */
  @Test
  void aVersionBeingLoadedCannotBeDeleted() throws Exception {
    downloaded(FakeDrivers.VERSION, OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    app.state().rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    Files.writeString(jarFolder(OLD).resolve(FakeDrivers.HOLD), "");
    // The lookup decides on the version; its load then waits at the hold.
    run(app, CatalogTreeTest.open("shop-prod") + "await\nframe\n");
    CatalogTreeTest.eventually(() -> app.state().drivers.loading(FakeDrivers.DRIVER, OLD));
    // An await would wait for the load, so the Drivers dialog cannot show its rows meanwhile;
    // it asks the same question.
    assertEquals(List.of("shop-prod"), app.state().users(FakeDrivers.DRIVER, OLD));
    assertFalse(app.state().claimForDelete(FakeDrivers.DRIVER, OLD), "the delete is refused");
    assertEquals(List.of(), app.state().users(FakeDrivers.DRIVER, FakeDrivers.VERSION));
    Files.delete(jarFolder(OLD).resolve(FakeDrivers.HOLD));
    run(app, SETTLE + SETTLE);
    var live = app.state().catalog.lives.get(shop.id());
    assertTrue(live.phase instanceof Live.Connected, String.valueOf(live.phase));
    assertEquals(List.of(FakeDrivers.VERSION, OLD), store().downloaded(FakeDrivers.DRIVER));
  }

  /** A connection that begins while a delete of its version runs asks to download it again. */
  @Test
  void aConnectionDuringADeleteAsksForTheDriver() throws Exception {
    downloaded(OLD);
    var shop = CatalogTreeTest.SHOP.withDriverVersion(OLD);
    var app = app(shop);
    run(app, START);
    var s = app.state();
    s.rememberPassword(shop.id(), ConnectionsTest.PASSWORD.toCharArray());
    assertTrue(s.claimForDelete(FakeDrivers.DRIVER, OLD));
    run(app, CatalogTreeTest.open("shop-prod") + SETTLE + SETTLE + "snap asked\n");
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("\"Download PostgreSQL JDBC " + OLD + "?\""), asked);
    s.drivers.deleting(FakeDrivers.DRIVER, OLD, false); // the delete failed: the file is there
    run(app, "press role=button label=\"Download\"\nframe\n" + SETTLE + SETTLE + SETTLE);
    assertTrue(s.catalog.lives.get(shop.id()).phase instanceof Live.Connected);
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

  /** A saved MySQL connection on this machine, its password in the keyring, its driver there. */
  static final String EDIT_LOCAL = """
      press role=treeitem label="localhost" right
      frame
      advance 1
      frame
      press role=menuitem label="Edit…"
      frame
      advance 1
      frame
      await
      frame
      advance 1
      frame
      """;

  App<State> local(String version) throws Exception {
    var profile = ConnectionProfile.of(Provider.MYSQL)
        .withHost("localhost")
        .withUser("test")
        .withTls(Tls.OFF)
        .withColor(Color.RED)
        .withDriverVersion(version);
    secrets.stored.put(profile.id(), "secret");
    store().download(Driver.of(Provider.MYSQL), FakeDrivers.VERSION, DriverStore.Progress.NONE);
    return app(profile);
  }

  /**
   * The connection dialog's pictures, light and dark, into the folder that {@code
   * -Dreview=<folder>} names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void dialogPictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    var app = local(null);
    app.state().theme = theme;
    Script.run(
        START + EDIT_LOCAL + "snap a-edit-" + look + "\n" + ConnectionColorsTest.COLOR
            + "snap g-color-" + look
            + "\nkey Escape\nframe\nadvance 1\nframe\n" + DRIVER + "snap b-driver-" + look + "\n"
            + CLOSE_DRIVER + "key Escape\nframe\nadvance 1\nframe\n"
            + ConnectionsTest.ADD_CONNECTION
            + """
            frame
            advance 1
            frame
            await
            frame
            """
            + ConnectionsTest.provider("MySQL") + """
            press role=textbox label="Host"
            type "db.example.com"
            press role=textbox label="User"
            type "app"
            press role=combobox label="Encrypted, any certificate"
            frame
            advance 1
            frame
            press role=menuitem label="Off"
            frame
            advance 1
            frame
            """ + "snap c-remote-off-" + look + "\n",
        app,
        out);
    var young = app();
    young.state().theme = theme;
    Script.run(
        START + NEW_SHOP + choose(YOUNG) + CLOSE_DRIVER + "await\nframe\nsnap e-young-" + look
            + "\n",
        young,
        out);
    var pinned = local(FakeDrivers.VERSION);
    pinned.state().theme = theme;
    Script.run(START + EDIT_LOCAL + "snap d-pinned-" + look + "\n", pinned, out);
    var longVersion = local("1.0.0-" + "a".repeat(58));
    longVersion.state().theme = theme;
    Script.run(
        START + EDIT_LOCAL + "snap f-long-version-" + look + "\n"
            + "move role=button label=\"Driver\"\nframe\nadvance 1\nframe\nadvance 1\nframe\n"
            + "snap f-long-version-tip-" + look + "\n",
        longVersion,
        out);
  }

  /**
   * The versions are read once, with the connection's dialog, and name the recommended version for
   * the list and a test alike, also where a newer one comes out meanwhile. Close and Escape close
   * the driver's dialog alone, after the list in it.
   */
  @Test
  void theListAndATestAgreeOnTheRecommendedVersion() throws Exception {
    var app = app();
    run(app, START + NEW_SHOP + "snap form\n");
    String form = tree("form");
    assertEquals(1, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    assertFalse(form.contains("#driver-version"), form);
    assertFalse(form.contains("PostgreSQL JDBC"), "nothing said of a driver that is well");
    assertTrue(block(form, "role=button label=\"Driver\"").contains("\"Driver…\""), form);
    // Out since the dialog opened, and old enough to be recommended: neither takes it now.
    repository.publish("42.7.16", FakeDrivers.NOW.minus(Duration.ofDays(10)));
    run(
        app,
        VERSIONS + "snap listed\nkey Escape\nframe\nadvance 1\nframe\nsnap kept\n"
            + "key Escape\nframe\nadvance 1\nframe\nsnap closed\n" + DRIVER + CLOSE_DRIVER
            + "snap end\n" + TEST + "snap asked\n");
    assertEquals(1, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    String listed = tree("listed");
    assertTrue(line(listed, "role=dialog label=\"PostgreSQL driver\"").contains("#driver"), listed);
    assertTrue(block(listed, "role=option label=\"Recommended\"").contains("\"42.7.14\""));
    assertFalse(listed.contains("role=option label=\"42.7.16\""), listed);
    String kept = tree("kept");
    assertFalse(kept.contains("role=option"), kept);
    assertTrue(kept.contains("role=dialog label=\"PostgreSQL driver\""), "Escape closed the list");
    for (var closed : List.of(tree("closed"), tree("end"))) {
      assertFalse(closed.contains("PostgreSQL driver"), closed);
      assertFalse(closed.contains("Discard changes?"), closed);
      assertTrue(closed.contains("role=dialog label=\"New connection\""), closed);
    }
    assertNotEquals(ConnectionDialog.Over.CHOOSING_DRIVER, app.state().dialogs.dialog.over);
    String asked = tree("asked");
    assertTrue(line(asked, "role=note").contains("\"Download PostgreSQL JDBC 42.7.14?\""), asked);
  }

  /**
   * A version chosen by hand needs no list until the driver's dialog opens, which reads it once;
   * Recommended is then looked up as at the start, by the lookup alone, which reads it again.
   */
  @Test
  void recommendedAfterAChosenVersionIsLookedUpAfresh() throws Exception {
    var app = app(ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("db.example.com")
        .withDriverVersion(OLD));
    run(app, START + edit("db.example.com") + SETTLE);
    assertEquals(0, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    run(app, choose("Recommended") + "await\nframe\nsnap recommended\n");
    assertEquals(2, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    assertTrue(
        tree("recommended").contains("\"PostgreSQL JDBC 42.7.14, not downloaded yet ("),
        tree("recommended"));
  }

  /**
   * Recommended chosen in the driver's dialog without a network is the lookup's, offline too: the
   * driver downloaded, not the list the dialog read while it could.
   */
  @Test
  void recommendedChosenOfflineUsesTheDownloadedDriver() throws Exception {
    downloaded(OLD);
    var profile = ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("db.example.com")
        .withDatabase("shop")
        .withUser("shop_app")
        .withDriverVersion(OLD);
    secrets.stored.put(profile.id(), "hunter2");
    var app = app(profile);
    run(app, START + edit("db.example.com") + SETTLE + DRIVER);
    repository.offline = true;
    run(
        app,
        pick("Recommended") + "await\nframe\n" + CLOSE_DRIVER + TEST + SETTLE + SETTLE + SETTLE
            + "snap tested\n");
    String tested = tree("tested");
    assertTrue(tested.contains("role=note label=\"Connected to PostgreSQL 16.4\""), tested);
    assertFalse(tested.contains("Download PostgreSQL JDBC"), tested);
    assertSame(app.state().drivers.loaded(FakeDrivers.DRIVER, OLD), server.drivers.getFirst());
  }

  /**
   * While the lookup has not found the version, Recommended in the driver's dialog names none, even
   * where the dialog's own list would: only the lookup recommends.
   */
  @Test
  void recommendedNamesNoVersionBeforeTheLookupHasOne() throws Exception {
    repository.offline = true; // the lookup finds nothing: no list, and nothing downloaded
    var app = app();
    run(app, START + NEW_SHOP);
    repository.offline = false;
    repository.publish("42.7.16", FakeDrivers.NOW.minus(Duration.ofDays(10)));
    run(app, VERSIONS + "snap missing\n");
    String missing = tree("missing");
    assertTrue(missing.contains("role=option label=\"42.7.16\""), "the dialog's list: " + missing);
    var recommended = block(missing, "role=option label=\"Recommended\"");
    assertFalse(recommended.contains("42.7.16"), recommended);
    assertFalse(block(missing, "role=option label=\"42.7.16\"").contains("recommended"), missing);
    // As while the lookup runs, which a script cannot hold open while the dialog's read comes in.
    var fetch = app.state().dialogs.dialog.probe.fetch;
    var found = fetch.known;
    fetch.known = new Fetch.Looking(Provider.POSTGRESQL);
    app.invalidate();
    run(app, "frame\nsnap pending\n");
    fetch.known = found;
    String pending = tree("pending");
    assertTrue(pending.contains("role=status label=\"Looking up the version to use\""), pending);
    assertFalse(block(pending, "role=option label=\"Recommended\"").contains("42.7."), pending);
    // Test connection looks it up again; then Recommended names what the lookup found.
    run(
        app,
        "key Escape\nframe\nadvance 1\nframe\n" + CLOSE_DRIVER + TEST + SETTLE + "snap asked\n"
            + VERSIONS + "snap found\n");
    assertTrue(
        line(tree("asked"), "role=note").contains("\"Download PostgreSQL JDBC 42.7.16?\""),
        tree("asked"));
    assertTrue(
        block(tree("found"), "role=option label=\"Recommended\"").contains("\"42.7.16\""),
        tree("found"));
  }

  static String edit(String connection) {
    return """
        press role=treeitem label="%s" right
        frame
        advance 1
        frame
        press role=menuitem label="Edit…"
        frame
        advance 1
        frame
        """.formatted(connection);
  }

  /**
   * Closing the window closes the connection's dialog, and cancels what it reads: here the lookup
   * and the driver's dialog's versions, both held by the repository.
   */
  @Test
  void closingTheWindowCancelsWhatTheDialogReads() throws Exception {
    var app = app();
    run(app, START);
    repository.opening = new CountDownLatch(1);
    run(app, """
        press role=button label="New connection"
        frame
        advance 1
        frame
        press role=button label="Driver"
        frame
        advance 1
        frame
        """);
    CatalogTreeTest.eventually(() -> repository.listings(Provider.POSTGRESQL) == 2);
    assertTrue(app.state().dialogs.dialog.versions.reading(), "held by the repository");
    app.state().requestClose();
    run(app, "frame\n");
    assertNull(app.state().dialogs.dialog);
    // Cancelled work counts as ended at once: its thread sees the interrupt a moment later.
    CatalogTreeTest.eventually(() -> repository.interrupted.get() == 2);
    assertEquals(1, repository.opening.getCount(), "the gate stayed shut");
  }

  /** Enter in the driver's dialog goes to neither Save nor the form under it. */
  @Test
  void enterInTheDriversDialogSavesNothing() throws Exception {
    var app = app();
    run(app, START + NEW_SHOP + DRIVER + "key Enter\nframe\nawait\nframe\nsnap entered\n");
    assertNotNull(app.state().dialogs.dialog, tree("entered"));
    assertTrue(new WorkspaceStore(dir.resolve("config")).load().connections().isEmpty());
    var empty = app();
    run(empty, START + """
        press role=button label="New connection"
        frame
        advance 1
        frame
        await
        frame
        """ + DRIVER + "key Enter\nframe\n");
    assertFalse(empty.state().dialogs.dialog.triedToSave, "the empty host is not shown as missing");
  }

  /** A saved young version is warned of under Type, without reading the list of versions. */
  @Test
  void aSavedYoungVersionIsWarnedOfWithoutTheList() throws Exception {
    var app = app(ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("db.example.com")
        .withDriverVersion(YOUNG));
    run(app, START + edit("db.example.com") + SETTLE + "snap edit\n");
    String edit = tree("edit");
    assertTrue(block(edit, "role=button label=\"Driver\"").contains("\"Driver " + YOUNG + "…\""));
    var warning = "warning=\"Only 1 day old: recommended after 3 days.\"";
    assertTrue(line(edit, "#type").contains(warning), edit);
    assertEquals(0, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    // The list, read as the driver's dialog opens, fails without a network: the age is known from
    // the date all the same.
    repository.offline = true;
    run(app, DRIVER + "snap failed\n" + CLOSE_DRIVER + "snap closed\n");
    assertEquals(1, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    assertTrue(tree("failed").contains("Its versions could not be read."), tree("failed"));
    assertTrue(line(tree("closed"), "#type").contains(warning), tree("closed"));
  }

  /**
   * Where the lookup has no list, as it ran without a network, the driver's dialog reads one itself.
   * Closed while that read is held, it cancels it; and the read changes neither the recommendation
   * nor a test's outcome.
   */
  @Test
  void theDriversDialogsOwnReadGoesWithItAndKeepsATest() throws Exception {
    downloaded(OLD);
    repository.offline = true;
    var app = app();
    var connected = "role=note label=\"Connected to PostgreSQL 16.4\"";
    run(app, START + NEW_SHOP + TEST + SETTLE + SETTLE + "snap tested\n");
    assertTrue(tree("tested").contains(connected), tree("tested"));
    assertSame(app.state().drivers.loaded(FakeDrivers.DRIVER, OLD), server.drivers.getFirst());
    repository.offline = false;
    repository.opening = new CountDownLatch(1);
    long before = repository.listings(Provider.POSTGRESQL);
    run(app, "press role=button label=\"Driver\"\nframe\nadvance 1\nframe\n");
    CatalogTreeTest.eventually(() -> repository.listings(Provider.POSTGRESQL) == before + 1);
    run(app, CLOSE_DRIVER);
    // Cancelled work counts as ended at once: its thread sees the interrupt a moment later.
    CatalogTreeTest.eventually(() -> repository.interrupted.get() == 1);
    repository.opening.countDown();
    run(app, SETTLE + "snap after\n");
    assertTrue(tree("after").contains(connected), "the outcome stays: " + tree("after"));
    assertEquals(before + 1, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    assertTrue(app.state().dialogs.dialog.probe.fetch.known instanceof Fetch.Found f
        && f.version().equals(OLD));
  }

  /**
   * Where no release has waited long enough, the lookup keeps the list it read, which the driver's
   * dialog shows without asking again; Test connection's retry reads a newer list, and the dialog
   * shows that one.
   */
  @Test
  void aRetriedLookupBringsItsNewerListToTheDriversDialog() throws Exception {
    repository = new FakeDrivers(Files.createDirectories(dir.resolve("young")))
        .publish("42.8.0", FakeDrivers.NOW.minus(Duration.ofDays(1)));
    var app = app();
    run(
        app,
        START + NEW_SHOP + VERSIONS + "snap none\nkey Escape\nframe\nadvance 1\nframe\n"
            + CLOSE_DRIVER);
    // One request: the lookup's, whose list the driver's dialog shows.
    assertEquals(1, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
    String none = tree("none");
    assertTrue(none.contains("role=option label=\"42.8.0\""), none);
    assertFalse(block(none, "role=option label=\"Recommended\"").contains("42.8.0"), none);
    repository.publish("42.7.20", FakeDrivers.NOW.minus(Duration.ofDays(10)));
    run(app, TEST + SETTLE + "snap asked\n" + VERSIONS + "snap newer\n");
    assertTrue(
        line(tree("asked"), "role=note").contains("\"Download PostgreSQL JDBC 42.7.20?\""),
        tree("asked"));
    String newer = tree("newer");
    assertTrue(block(newer, "role=option label=\"Recommended\"").contains("\"42.7.20\""), newer);
    assertTrue(newer.contains("role=option label=\"42.7.20\""), newer);
    // The retry's request, and none of the driver's dialog.
    assertEquals(2, repository.listings(Provider.POSTGRESQL), repository.calls.toString());
  }

  /** A version whose date is not known is not said to be too new, whatever it sorts above. */
  @Test
  void aVersionWithoutADateIsNotWarnedOf() throws Exception {
    var app = app(ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("db.example.com")
        .withDriverVersion("42.9.0"));
    run(
        app,
        START + edit("db.example.com") + SETTLE + "snap edit\n" + DRIVER + "snap driver\n"
            + CLOSE_DRIVER + "snap closed\n");
    for (var snap : List.of("edit", "driver", "closed")) {
      assertFalse(line(tree(snap), "#type").contains("warning="), tree(snap));
    }
    assertFalse(line(tree("driver"), "#driver-version").contains("warning="), tree("driver"));
    assertFalse(tree("driver").contains("Very new"), tree("driver"));
  }

  /** A version too long for the button is cut short there, so that the Type row fits. */
  @Test
  void aLongVersionFitsBesideType() throws Exception {
    var version = "1.0.0-" + "a".repeat(58);
    var app = app(ConnectionProfile.of(Provider.POSTGRESQL)
        .withHost("db.example.com")
        .withDriverVersion(version));
    run(
        app,
        START + edit("db.example.com") + SETTLE + "snap long\nmove role=button label=\"Driver\"\n"
            + "frame\nadvance 1\nframe\nframe\nsnap tip\n");
    String tree = tree("long");
    var button = line(tree, "role=button label=\"Driver\"");
    assertTrue(right(button) <= right(line(tree, "#tls")), "the Type row fits: " + tree);
    // The kit cuts the text short and shows it whole in a tooltip.
    assertTrue(
        line(tree("tip"), "role=tooltip").contains("label=\"Driver " + version + "…\""),
        tree("tip"));
  }

  /** Where an element of the tree ends on the right. */
  static float right(String line) {
    var m = java.util.regex.Pattern.compile(" (-?[\\d.]+),-?[\\d.]+ ([\\d.]+)x").matcher(line);
    assertTrue(m.find(), line);
    return Float.parseFloat(m.group(1)) + Float.parseFloat(m.group(2));
  }

  /** A version chosen by hand is named on Driver…, and a young one is warned of under Type. */
  @Test
  void aChosenVersionIsNamedOnTheButton() throws Exception {
    var app = app();
    run(
        app,
        START + NEW_SHOP + choose(OLD) + CLOSE_DRIVER + "snap old\n" + choose(YOUNG) + CLOSE_DRIVER
            + "await\nframe\nsnap young\n");
    String old = tree("old");
    assertTrue(block(old, "role=button label=\"Driver\"").contains("\"Driver " + OLD + "…\""));
    assertFalse(line(old, "#type").contains("warning="), old);
    String young = tree("young");
    assertTrue(
        line(young, "#type").contains("warning=\"Only 1 day old: recommended after 3 days.\""),
        young);
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
            + pick(YOUNG) + "await\nframe\nsnap connection-young-" + look + "\n" + close + close
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

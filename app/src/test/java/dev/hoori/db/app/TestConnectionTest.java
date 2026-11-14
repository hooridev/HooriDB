package dev.hoori.db.app;

import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.Tls;
import dev.hoori.db.core.driver.DriverStore;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test connection in the connection dialog, with the driver it needs. */
class TestConnectionTest {

  @TempDir
  Path dir;

  final ConnectionsTest.Secrets secrets = new ConnectionsTest.Secrets();
  final FakeDrivers.FakeServer server = new FakeDrivers.FakeServer();
  FakeDrivers repository;

  @BeforeEach
  void setUp() throws Exception {
    repository = new FakeDrivers(Files.createDirectories(dir.resolve("repository"))).publish(true);
  }

  Path drivers() {
    return dir.resolve("drivers");
  }

  App<State> app() {
    return HooriDb.app(
        dir.resolve("config"),
        () -> new Keyring.Available(secrets),
        repository.drivers(drivers()),
        server);
  }

  /** Puts the driver into the folder, as an earlier session would have. */
  void downloaded() throws Exception {
    new DriverStore(drivers(), repository)
        .download(FakeDrivers.DRIVER, FakeDrivers.VERSION, DriverStore.Progress.NONE);
  }

  static final String START = """
      size 1200 760
      frame
      await
      frame
      await
      frame
      advance 1
      frame
      """;

  // What fades out after a step hides what came in its place from a selector for a moment.
  static final String SETTLE = """
      await
      frame
      advance 1
      frame
      """;

  /** The dialog of a new connection to a server on the network, its driver looked up. */
  static final String NEW_SHOP = """
      press role=button label="New connection"
      frame
      advance 1
      frame
      await
      frame
      press role=textbox label="Host"
      type "db.example.com"
      press role=textbox label="Database"
      type "shop"
      press role=textbox label="User"
      type "shop_app"
      press role=textbox label="Password"
      type "hunter2"
      frame
      """;

  static final String TEST = """
      press role=button label="Test connection"
      frame
      """;

  void run(App<State> app, String script) throws IOException {
    Script.run(script, app, out());
  }

  Path out() {
    return dir.resolve("out");
  }

  String tree(String snap) throws IOException {
    return Files.readString(out().resolve(snap + ".tree.txt"));
  }

  /** The top of the first line that contains the text. */
  static float top(String tree, String text) {
    var parts = line(tree, text).split(" ");
    for (int i = 0; i + 1 < parts.length; i++) {
      if (parts[i].matches("-?[0-9.]+,-?[0-9.]+") && parts[i + 1].matches("[0-9.]+x[0-9.]+")) {
        return Float.parseFloat(parts[i].split(",")[1]);
      }
    }
    throw new AssertionError("No rectangle in " + line(tree, text));
  }

  /** That the text shows below the field and above the next one, and the field is invalid. */
  static void at(String tree, String field, String next, String text) {
    assertTrue(line(tree, field).contains("invalid"), field + " is invalid in\n" + tree);
    float y = top(tree, "\"" + text + "\"");
    assertTrue(top(tree, field) < y, text + " below " + field);
    if (next != null) {
      assertTrue(y < top(tree, next), text + " above " + next);
    }
  }

  void noPasswordIn(String... snaps) throws IOException {
    for (var snap : snaps) {
      assertFalse(tree(snap).contains(PASSWORD), snap);
    }
  }

  @Test
  void theDriverLineSaysWhatIsThere() throws Exception {
    repository.opening = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + """
        press role=button label="New connection"
        frame
        advance 1
        frame
        snap looking
        """);
    assertTrue(tree("looking").contains("\"PostgreSQL JDBC, looking up the version to use…\""));
    repository.opening.countDown();
    run(app, SETTLE + "snap missing\n");
    assertTrue(
        tree("missing")
            .contains("\"PostgreSQL JDBC 42.7.14, not downloaded yet (1\u00a0kB, from Maven"
                + " Central)\""),
        tree("missing"));
    repository.offline = true;
    run(app, """
        press role=radio label="MySQL"
        frame
        await
        frame
        snap offline
        """);
    assertTrue(tree("offline")
        .contains("\"MySQL Connector/J, not downloaded yet (Maven Central cannot be reached)\""));
    repository.offline = false;
    downloaded();
    repository.offline = true;
    run(app, """
        press role=radio label="PostgreSQL"
        frame
        await
        frame
        snap downloaded-offline
        """);
    assertTrue(tree("downloaded-offline")
        .contains("\"PostgreSQL JDBC 42.7.14, downloaded (Maven Central cannot be reached to look"
            + " for a newer one)\""));
    repository.offline = false;
    run(app, """
        press role=radio label="MySQL"
        frame
        press role=radio label="PostgreSQL"
        frame
        await
        frame
        snap downloaded
        """);
    assertTrue(tree("downloaded").contains("\"PostgreSQL JDBC 42.7.14, downloaded\""));
  }

  /** A script run has no repository: it says so, and nothing can be tested. */
  @Test
  void withoutDriversNothingIsTested() throws IOException {
    App<State> app = HooriDbTest.app(dir);
    run(app, START + NEW_SHOP + "snap end\n");
    String end = tree("end");
    assertTrue(
        end.contains("\"A script run downloads no drivers, so connections cannot be tested.\""));
    assertTrue(line(end, "label=\"Test connection\"").endsWith("disabled"), end);
  }

  @Test
  void aTestWithTheDriverThereShowsTheServer() throws Exception {
    downloaded();
    server.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + SETTLE + "snap busy\n");
    String busy = tree("busy");
    assertTrue(line(busy, "label=\"Test connection\"").contains("busy"), busy);
    assertTrue(busy.contains("role=status label=\"Connecting to db.example.com…\""), busy);
    server.gate.countDown();
    run(app, SETTLE + "snap end\n");
    String end = tree("end");
    assertTrue(end.contains("role=status label=\"Connected to PostgreSQL 16.4\""), end);
    assertFalse(line(end, "label=\"Test connection\"").contains("busy"), end);
    assertEquals(List.of(PASSWORD), server.passwords);
    noPasswordIn("busy", "end");
    // A second test reuses the driver loaded for the first.
    run(app, TEST + SETTLE + "snap again\n");
    assertEquals(2, server.drivers.size());
    assertSame(server.drivers.get(0), server.drivers.get(1));
  }

  /** A failure, the field its note is under, the next field, and the other field it marks. */
  record Case(
      Failure failure, String field, String next, String note, String title, String marked) {}

  @Test
  void eachFailureShowsAtItsField() throws Exception {
    downloaded();
    App<State> app = app();
    run(app, START + NEW_SHOP);
    var cases = List.of(
        new Case(
            Failure.LOGIN_REFUSED,
            "#password",
            "role=combobox",
            "The server refused this user name and password.",
            "Login refused",
            "#user"),
        new Case(
            Failure.NO_SUCH_DATABASE,
            "#database",
            "#user",
            "The server has no database named shop, or this user may not open it.",
            "No such database",
            null),
        new Case(
            Failure.UNREACHABLE,
            "#port",
            "#database",
            "Nothing answered at db.example.com, port 5432. Check the host and the port, and that"
                + " the server is running.",
            "The server cannot be reached",
            "#host"),
        new Case(
            Failure.TIMED_OUT,
            "#port",
            "#database",
            "db.example.com did not answer on port 5432 within 10 seconds. Check the host and the"
                + " port; a firewall may be in the way.",
            "No answer in time",
            "#host"),
        new Case(
            Failure.TLS_UNAVAILABLE,
            "role=combobox",
            null,
            "The server offers no TLS. Off connects without it, and password and data go over the"
                + " network as they are.",
            "The server offers no TLS",
            null),
        new Case(
            Failure.CERTIFICATE_REJECTED,
            "role=combobox",
            null,
            "The server's certificate is not trusted, or not for db.example.com. Encrypted accepts"
                + " it, without verifying the server.",
            "The certificate was not accepted",
            null),
        new Case(Failure.OTHER, null, null, null, "The connection failed", null));
    for (var c : cases) {
      String detail = "FATAL: the driver's words for " + c.failure();
      server.refuse(c.failure(), detail);
      String name = c.failure().name().toLowerCase();
      run(app, TEST + SETTLE + SETTLE + "snap " + name + "\n");
      String tree = tree(name);
      assertTrue(line(tree, "role=note").contains("label=\"" + c.title() + "\""), tree);
      assertTrue(tree.contains("\"" + detail + "\""), tree);
      if (c.marked() != null) {
        assertTrue(line(tree, c.marked()).contains("invalid"), c.marked() + " in\n" + tree);
      }
      if (c.field() != null) {
        at(tree, c.field(), c.next(), c.note());
        assertEquals(c.marked() == null ? 1 : 2, tree.split(" invalid").length - 1, tree);
      } else {
        assertFalse(tree.contains("invalid"), tree);
      }
      noPasswordIn(name);
    }
  }

  /** The driver is downloaded only after asking, and the test goes on by itself. */
  @Test
  void theDriverIsDownloadedAfterAsking() throws Exception {
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + """
        snap asked
        press role=button label="Not now"
        frame
        snap cancelled
        """ + TEST + """
        press role=button label="Download"
        frame
        snap downloading
        """);
    String asked = tree("asked");
    assertTrue(
        line(asked, "role=note").contains("label=\"Download PostgreSQL JDBC 42.7.14?\""), asked);
    // The tree shows the start and the end of a long text.
    assertTrue(
        asked.contains("To connect, HooriDB needs this driver (1\u00a0kB), which it downloads"
            + " from Maven Central:\\nhttps://repo.ma"),
        asked);
    assertEquals(
        "https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.14/"
            + "postgresql-42.7.14.jar",
        app.state().drivers.address(FakeDrivers.DRIVER, FakeDrivers.VERSION).toString());
    assertTrue(asked.contains("against its checksum and kept for later.\" length"), asked);
    assertFalse(tree("cancelled").contains("role=note"), tree("cancelled"));
    assertTrue(server.passwords.isEmpty(), "nothing was tested without the driver");
    String downloading = tree("downloading");
    assertTrue(
        line(downloading, "label=\"Downloading PostgreSQL JDBC\"").contains("0%"), downloading);
    assertTrue(downloading.contains("\"0 of 1\u00a0kB\""), downloading);
    run(app, SETTLE + SETTLE + SETTLE + "snap end\n");
    String end = tree("end");
    assertTrue(end.contains("role=status label=\"Connected to PostgreSQL 16.4\""), end);
    assertTrue(end.contains("\"PostgreSQL JDBC 42.7.14, downloaded\""), end);
    assertEquals(
        List.of(FakeDrivers.VERSION),
        new DriverStore(drivers(), repository).downloaded(FakeDrivers.DRIVER));
    noPasswordIn("asked", "downloading", "end");
  }

  @Test
  void aFailedDownloadIsTriedAgain() throws Exception {
    repository.publish(false);
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + """
        press role=button label="Download"
        frame
        """ + SETTLE + "snap mismatch\n");
    String mismatch = tree("mismatch");
    assertTrue(
        line(mismatch, "role=note").contains("\"The driver could not be downloaded\""), mismatch);
    assertTrue(
        mismatch.contains("\"The file does not match its checksum, so it was not used. It may have"
            + " been damaged on the way.\""),
        mismatch);
    repository.offline = true;
    run(app, """
        press role=button label="Try again"
        frame
        """ + SETTLE + "snap offline\n");
    assertTrue(tree("offline")
        .contains("\"Maven Central cannot be reached. Check the network connection.\""));
    repository.offline = false;
    repository.publish(true);
    run(app, """
        press role=button label="Try again"
        frame
        """ + SETTLE + SETTLE + SETTLE + "snap end\n");
    assertTrue(tree("end").contains("role=status label=\"Connected to PostgreSQL 16.4\""));
  }

  @Test
  void aCancelledDownloadLeavesNothingBehind() throws Exception {
    repository.reading = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + """
        press role=button label="Download"
        frame
        press role=button label="Cancel download"
        frame
        """ + SETTLE + "snap end\n");
    assertFalse(tree("end").contains("Downloading"), tree("end"));
    assertFalse(line(tree("end"), "label=\"Test connection\"").contains("busy"));
    assertNothingIn(drivers());
    assertTrue(server.passwords.isEmpty());
  }

  /** The download's thread cleans up after it was cancelled, which takes a moment. */
  static void assertNothingIn(Path folder) throws Exception {
    for (int i = 0; ; i++) {
      List<Path> files;
      try (Stream<Path> paths = Files.walk(folder)) {
        files = paths.filter(Files::isRegularFile).toList();
      }
      if (files.isEmpty()) {
        return;
      }
      if (i == 100) {
        throw new AssertionError("Left behind: " + files);
      }
      Thread.sleep(20);
    }
  }

  @Test
  void aRunningTestCanBeCancelled() throws Exception {
    downloaded();
    server.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + SETTLE + """
        press role=button label="Cancel test"
        frame
        """ + SETTLE + "snap end\n");
    String end = tree("end");
    assertFalse(end.contains("role=status label=\"Connecting"), end);
    assertFalse(line(end, "label=\"Test connection\"").contains("busy"), end);
    assertTrue(server.interrupted, "the test's thread was interrupted");
    assertTrue(line(end, "role=dialog").contains("\"New connection\""), "the dialog stays");
  }

  @Test
  void closingTheDialogCancelsATest() throws Exception {
    downloaded();
    var shop = ConnectionsTest.shop();
    new ProfileStore(dir.resolve("config")).save(List.of(shop));
    server.gate = new CountDownLatch(1);
    App<State> app = app();
    run(
        app,
        START + ConnectionsTest.EDIT_SHOP + SETTLE + TEST + SETTLE + """
        key Escape
        frame
        advance 1
        frame
        """ + SETTLE + "snap end\n");
    assertFalse(tree("end").contains("role=dialog"), tree("end"));
    assertTrue(server.interrupted);
  }

  @Test
  void closingTheDialogCancelsADownload() throws Exception {
    var shop = ConnectionsTest.shop();
    new ProfileStore(dir.resolve("config")).save(List.of(shop));
    repository.reading = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + ConnectionsTest.EDIT_SHOP + SETTLE + TEST + """
        press role=button label="Download"
        frame
        key Escape
        frame
        advance 1
        frame
        """ + SETTLE + "snap end\n");
    assertFalse(tree("end").contains("role=dialog"), tree("end"));
    assertNothingIn(drivers());
  }

  /** Closing the window does not wait for a test or a download: it cancels them. */
  @Test
  void closingTheWindowCancelsATest() throws Exception {
    downloaded();
    server.gate = new CountDownLatch(1);
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + SETTLE + "close\nframe\n" + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertTrue(server.interrupted);
  }

  @Test
  void anOutcomeGoesWhenTheFormChanges() throws Exception {
    downloaded();
    server.refuse(Failure.LOGIN_REFUSED, "FATAL: password authentication failed");
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + SETTLE + SETTLE + """
        snap refused
        press role=textbox label="Name"
        type "shop-prod"
        frame
        snap named
        press role=textbox label="Password"
        key Backspace
        frame
        snap typed
        """);
    assertTrue(tree("refused").contains("Login refused"));
    assertTrue(tree("named").contains("Login refused"), "the name does not change the test");
    String typed = tree("typed");
    assertFalse(typed.contains("Login refused"), typed);
    assertFalse(typed.contains("The server refused"), typed);
    assertFalse(line(typed, "#password").contains("invalid"), typed);
    server.answer = new Tester.Reached(new dev.hoori.db.core.connect.Server("PostgreSQL", "16.4"));
    run(app, TEST + SETTLE + SETTLE + """
        snap connected
        press role=textbox label="Host"
        type "x"
        frame
        snap end
        """);
    assertTrue(tree("connected").contains("Connected to PostgreSQL 16.4"));
    assertFalse(tree("end").contains("Connected to"), tree("end"));
  }

  /** The field left empty, the test uses the password saved for the connection. */
  @Test
  void anEmptyFieldTestsWithTheSavedPassword() throws Exception {
    downloaded();
    var shop = ConnectionsTest.shop();
    new ProfileStore(dir.resolve("config")).save(List.of(shop));
    secrets.stored.put(shop.id(), PASSWORD);
    App<State> app = app();
    run(app, START + ConnectionsTest.EDIT_SHOP + SETTLE + TEST + SETTLE + SETTLE + "snap end\n");
    assertEquals(List.of(PASSWORD), server.passwords);
    assertTrue(tree("end").contains("Connected to PostgreSQL 16.4"), tree("end"));
    noPasswordIn("end");
  }

  /** A driver's message that would show the password is not shown. */
  @Test
  void aMessageWithThePasswordIsNotShown() throws Exception {
    downloaded();
    server.refuse(Failure.OTHER, "The server said: hunter2 is wrong");
    App<State> app = app();
    run(app, START + NEW_SHOP + TEST + SETTLE + SETTLE + "snap end\n");
    String end = tree("end");
    assertTrue(end.contains("\"" + Probe.HIDDEN + "\""), end);
    noPasswordIn("end");
  }

  /** Testing saves nothing: not the list, not its file, not the keyring. */
  @Test
  void testingChangesNothing() throws Exception {
    downloaded();
    var shop = ConnectionsTest.shop().withTls(Tls.OFF);
    var store = new ProfileStore(dir.resolve("config"));
    store.save(List.of(shop));
    var file = dir.resolve("config").resolve(ProfileStore.FILE);
    String before = Files.readString(file);
    App<State> app = app();
    run(
        app,
        START + ConnectionsTest.EDIT_SHOP + SETTLE + """
        press role=textbox label="Password"
        type "hunter2"
        press role=radio label="MariaDB"
        frame
        press role=radio label="PostgreSQL"
        frame
        """ + SETTLE + TEST + SETTLE + SETTLE
            + "snap end\n");
    assertTrue(tree("end").contains("Connected to PostgreSQL 16.4"), tree("end"));
    assertEquals(List.of(PASSWORD), server.passwords);
    assertEquals(List.of(shop), app.state().profiles);
    assertEquals(before, Files.readString(file));
    assertTrue(secrets.stored.isEmpty());
    assertFalse(app.state().remembers(shop.id()));
    assertEquals(List.of(shop), store.load());
  }
}

package dev.hoori.db.core.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.driver.DriverException.Failure;
import dev.hoori.db.core.driver.DriverStore.Recommended;
import dev.hoori.db.core.driver.FolderRepository.Sum;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverStoreTest {

  private static final Driver DRIVER = FakeDriver.DRIVER;
  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  private static final byte[] BYTES = "not really a jar".getBytes(StandardCharsets.UTF_8);

  @TempDir
  Path temp;

  private FolderRepository repository;
  private Path storage;
  private DriverStore store;

  @BeforeEach
  void setUp() throws Exception {
    repository = new FolderRepository(Files.createDirectories(temp.resolve("repository")));
    storage = temp.resolve("drivers");
    store = new DriverStore(storage, repository);
  }

  @Test
  void versionsAreReadNewestFirst() throws Exception {
    for (var version : List.of("42.7.9", "42.7.10", "3.0.0-alpha", "3.0.0", "42.2.0.jre7")) {
      repository.publish(DRIVER, version, BYTES, Sum.SHA256, NOW);
    }
    var metadata = repository.root.resolve("dev/hoori/fake/fake-jdbc/maven-metadata.xml");
    Files.writeString(
        metadata,
        Files.readString(metadata).replace("<versions>", "<versions><version>../evil</version>"));
    assertEquals(
        List.of("42.7.10", "42.7.9", "42.2.0.jre7", "3.0.0", "3.0.0-alpha"),
        store.versions(DRIVER));
  }

  @Test
  void metadataWithADoctypeIsRefused() throws Exception {
    var metadata = repository.root.resolve("dev/hoori/fake/fake-jdbc/maven-metadata.xml");
    Files.createDirectories(metadata.getParent());
    Files.writeString(metadata, """
        <?xml version="1.0"?>
        <!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        <metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>
        """);
    assertFailure(Failure.INVALID_RESPONSE, () -> store.versions(DRIVER));
  }

  @Test
  void theRecommendedVersionHasWaitedAndIsStable() throws Exception {
    repository.publish(DRIVER, "42.7.9", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(30)));
    repository.publish(DRIVER, "42.7.10", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(4)));
    repository.publish(DRIVER, "42.7.11", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(2)));
    repository.publish(DRIVER, "43.0.0-rc1", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(9)));
    repository.publish(DRIVER, "42.7.11.jre7", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(9)));

    assertEquals(new Recommended("42.7.10", false), store.recommended(DRIVER, NOW));
    // Newest first, and no further than the first that qualifies.
    assertEquals(
        List.of(
            "dev/hoori/fake/fake-jdbc/42.7.11/fake-jdbc-42.7.11.jar",
            "dev/hoori/fake/fake-jdbc/42.7.10/fake-jdbc-42.7.10.jar"),
        repository.asked);

    assertEquals(
        new Recommended("42.7.11", false), store.recommended(DRIVER, NOW, Duration.ofDays(1)));
    assertFailure(Failure.NOT_FOUND, () -> store.recommended(DRIVER, NOW, Duration.ofDays(60)));
  }

  @Test
  void aVersionSaysWhenItWasPublished() throws Exception {
    var published = NOW.minus(Duration.ofHours(5));
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, published);
    assertEquals(Optional.of(published), store.published(DRIVER, "1.0.0"));
    assertFailure(Failure.NOT_FOUND, () -> store.published(DRIVER, "2.0.0"));
  }

  @Test
  void withoutANetworkTheNewestDownloadedVersionIsRecommended() throws Exception {
    repository.offline = true;
    assertFailure(Failure.NO_NETWORK, () -> store.recommended(DRIVER, NOW));

    repository.offline = false;
    repository.publish(DRIVER, "1.9.0", BYTES, Sum.SHA256, NOW);
    repository.publish(DRIVER, "1.10.0", BYTES, Sum.SHA256, NOW);
    store.download(DRIVER, "1.9.0", DriverStore.Progress.NONE);
    store.download(DRIVER, "1.10.0", DriverStore.Progress.NONE);
    repository.offline = true;
    assertEquals(new Recommended("1.10.0", true), store.recommended(DRIVER, NOW));
  }

  @Test
  void aDownloadLandsInStorageOnceAndWorksOffline() throws Exception {
    var jar = FakeDriver.jar("1", temp);
    repository.publish(DRIVER, "1.0.0", jar, Sum.SHA256, NOW);
    var progress = new ArrayList<long[]>();
    store.download(DRIVER, "1.0.0", (done, total) -> progress.add(new long[] {done, total}));

    var stored = storage.resolve("dev.hoori.fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar");
    assertTrue(java.util.Arrays.equals(jar, Files.readAllBytes(stored)));
    assertEquals(
        FolderRepository.hex("SHA-256", jar),
        Files.readString(stored.resolveSibling("fake-jdbc-1.0.0.jar.sha256")));
    assertEquals(List.of("fake-jdbc-1.0.0.jar", "fake-jdbc-1.0.0.jar.sha256"), files());
    assertEquals(jar.length, progress.getLast()[0]);
    assertEquals(jar.length, progress.getLast()[1]);

    var fetched = List.copyOf(repository.opened);
    repository.offline = true;
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    assertEquals(fetched, repository.opened);
    assertEquals(List.of("1.0.0"), store.downloaded(DRIVER));
    try (var loaded = store.load(DRIVER, "1.0.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void aSha1BesideTheFileIsUsedWhenThereIsNoSha256() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA1, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    assertEquals(List.of("1.0.0"), store.downloaded(DRIVER));
  }

  @Test
  void aFileWithoutAChecksumIsNotFound() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.NONE, NOW);
    assertFailure(
        Failure.NOT_FOUND, () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());
  }

  @Test
  void aWrongChecksumLeavesNothingBehind() throws Exception {
    var file = repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    Files.write(file, "damaged".getBytes(StandardCharsets.UTF_8));
    assertFailure(
        Failure.CHECKSUM_MISMATCH,
        () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());
    assertEquals(List.of(), store.downloaded(DRIVER));
  }

  @Test
  void aCancelInTheMiddleLeavesNothingBehind() throws Exception {
    repository.publish(DRIVER, "1.0.0", new byte[1 << 20], Sum.SHA256, NOW);
    try {
      assertFailure(
          Failure.CANCELLED,
          () -> store.download(
              DRIVER, "1.0.0", (done, total) -> Thread.currentThread().interrupt()));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of(), files());
  }

  @Test
  void aDownloadIsBounded() throws Exception {
    repository.publish(DRIVER, "1.0.0", new byte[2048], Sum.SHA256, NOW);
    var small = new DriverStore(storage, repository, 1024);
    assertFailure(
        Failure.TOO_LARGE, () -> small.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());
  }

  @Test
  void aMissingVersionIsNotFound() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    assertFailure(
        Failure.NOT_FOUND, () -> store.download(DRIVER, "9.9.9", DriverStore.Progress.NONE));
    assertFailure(Failure.NOT_DOWNLOADED, () -> store.load(DRIVER, "1.0.0"));
  }

  @Test
  void aFileChangedAfterItsDownloadIsRefused() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    var stored = storage.resolve("dev.hoori.fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar");
    Files.write(stored, FakeDriver.jar("2", temp));
    assertFailure(Failure.CHECKSUM_MISMATCH, () -> store.load(DRIVER, "1.0.0"));

    Files.delete(stored.resolveSibling("fake-jdbc-1.0.0.jar.sha256"));
    assertFailure(Failure.CHECKSUM_MISMATCH, () -> store.load(DRIVER, "1.0.0"));

    // A new download replaces the changed file.
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    try (var loaded = store.load(DRIVER, "1.0.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void twoVersionsLoadSideBySideAndUnseenByTheApp() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    repository.publish(DRIVER, "2.0.0", FakeDriver.jar("2", temp), Sum.SHA1, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    store.download(DRIVER, "2.0.0", DriverStore.Progress.NONE);
    try (var one = store.load(DRIVER, "1.0.0");
        var two = store.load(DRIVER, "2.0.0")) {
      assertEquals("1", one.connect("jdbc:fake:a", new Properties()).getCatalog());
      assertEquals("2", two.connect("jdbc:fake:b", new Properties()).getCatalog());
      assertNotSame(one.driver().getClass(), two.driver().getClass());
      assertThrows(
          java.sql.SQLException.class, () -> one.connect("jdbc:other:a", new Properties()));

      var loader = one.driver().getClass().getClassLoader();
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName("fake.jdbc.FakeDriver", false, getClass().getClassLoader()));
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(DriverStore.class.getName(), false, loader));
      assertEquals(java.sql.Driver.class, Class.forName("java.sql.Driver", false, loader));
    }
  }

  @Test
  void aClassThatIsNoDriverIsRefused() throws Exception {
    var wrong = new Driver(DRIVER.group(), DRIVER.artifact(), "fake.jdbc.Missing");
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    assertFailure(Failure.NOT_A_DRIVER, () -> store.load(wrong, "1.0.0"));
  }

  @Test
  void aVersionIsDeleted() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    repository.publish(DRIVER, "1.1.0", BYTES, Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    store.download(DRIVER, "1.1.0", DriverStore.Progress.NONE);
    assertEquals(List.of("1.1.0", "1.0.0"), store.downloaded(DRIVER));
    store.delete(DRIVER, "1.1.0");
    store.delete(DRIVER, "7.0.0");
    assertEquals(List.of("1.0.0"), store.downloaded(DRIVER));
    assertFalse(Files.exists(storage.resolve("dev.hoori.fake/fake-jdbc/1.1.0")));
  }

  @Test
  void versionsThatCouldEscapeAreRefusedBeforeAnyUse() {
    for (var version : List.of("..", "../1.0", "1.0/..", "1\\0", "1%2e", "a\nb", "", " 1", "nul")) {
      assertThrows(IllegalArgumentException.class, () -> store.download(DRIVER, version, null));
      assertThrows(IllegalArgumentException.class, () -> store.load(DRIVER, version));
      assertThrows(IllegalArgumentException.class, () -> store.delete(DRIVER, version));
      assertThrows(IllegalArgumentException.class, () -> store.published(DRIVER, version));
    }
    assertEquals(List.of(), repository.opened);
    assertEquals(List.of(), repository.asked);
  }

  private List<String> files() throws Exception {
    if (!Files.exists(storage)) {
      return List.of();
    }
    try (var paths = Files.walk(storage)) {
      return paths
          .filter(Files::isRegularFile)
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    }
  }

  private interface Call {
    void run() throws Exception;
  }

  private static void assertFailure(Failure failure, Call call) {
    var e = assertThrows(DriverException.class, call::run);
    assertEquals(failure, e.failure(), e.getMessage());
  }
}

package dev.hoori.db.core.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.DriverException.Failure;
import dev.hoori.db.core.driver.DriverStore.Recommended;
import dev.hoori.db.core.driver.FolderRepository.Sum;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DriverStoreTest {

  private static final Driver DRIVER = FakeDriver.DRIVER;
  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  private static final byte[] BYTES = FakeDriver.plain(null);

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

    var recommended = store.recommended(DRIVER, NOW);
    assertEquals("42.7.10", recommended.version());
    assertFalse(recommended.offline());
    assertEquals("42.7.10", recommended.choices().recommended(), "what named it");
    // Newest first, and no further than the first that qualifies.
    assertEquals(
        List.of(
            "dev/hoori/fake/fake-jdbc/42.7.11/fake-jdbc-42.7.11.jar",
            "dev/hoori/fake/fake-jdbc/42.7.10/fake-jdbc-42.7.10.jar"),
        repository.asked);

    assertEquals("42.7.11", store.recommended(DRIVER, NOW, Duration.ofDays(1)).version());
    // None qualifies: no version, and the choices read, which say so.
    var none = store.recommended(DRIVER, NOW, Duration.ofDays(60));
    assertNull(none.version());
    assertFalse(none.offline());
    assertTrue(none.choices().versions().containsAll(List.of("42.7.11", "42.7.10", "42.7.9")));
    assertNull(none.choices().recommended());
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
    repository.publish(DRIVER, "1.11.0", BYTES, Sum.SHA256, NOW);
    repository.publish(DRIVER, "2.0.0-rc1", BYTES, Sum.SHA256, NOW);
    for (var version : List.of("1.9.0", "1.10.0", "1.11.0", "2.0.0-rc1")) {
      store.download(DRIVER, version, DriverStore.Progress.NONE);
    }
    Files.write(
        storage.resolve("dev.hoori.fake/fake-jdbc/1.11.0/fake-jdbc-1.11.0.jar"),
        FakeDriver.plain("changed"));
    repository.offline = true;
    assertEquals(new Recommended("1.10.0", true, null), store.recommended(DRIVER, NOW));
  }

  @Test
  void theNumberOfCandidatesIsBounded() throws Exception {
    for (int i = 1; i <= 8; i++) {
      repository.publish(DRIVER, "1." + i + ".0", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(1)));
    }
    assertNull(store.recommended(DRIVER, NOW).version());
    assertEquals(DriverStore.MAX_CANDIDATES, repository.asked.size());
  }

  @Test
  void aMalformedResponseIsInvalid() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    repository.malformed.add("dev/hoori/fake/fake-jdbc/maven-metadata.xml");
    assertFailure(Failure.INVALID_RESPONSE, () -> store.versions(DRIVER));
    Files.writeString(
        repository.root.resolve("dev/hoori/fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar.sha256"),
        "not a checksum");
    assertFailure(
        Failure.INVALID_RESPONSE, () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
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
  void aFileWithoutAChecksumCannotBeVerified() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.NONE, NOW);
    assertFailure(
        Failure.NO_CHECKSUM, () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
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
  void anInterruptedThreadIsCancelledWithoutANetworkToo() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    repository.offline = true;
    Thread.currentThread().interrupt();
    try {
      assertFailure(
          Failure.CANCELLED, () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
      assertFailure(Failure.CANCELLED, () -> store.load(DRIVER, "1.0.0"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aJarWhoseManifestNamesAClassPathIsRefused() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.plain("../../../other.jar"), Sum.SHA256, NOW);
    assertFailure(
        Failure.CLASS_PATH_IN_MANIFEST,
        () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());

    repository.publish(DRIVER, "2.0.0", FakeDriver.jar("2", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "2.0.0", DriverStore.Progress.NONE);
    var stored = storage.resolve("dev.hoori.fake/fake-jdbc/2.0.0/fake-jdbc-2.0.0.jar");
    var changed = FakeDriver.plain("other.jar");
    Files.write(stored, changed);
    Files.writeString(
        stored.resolveSibling("fake-jdbc-2.0.0.jar.sha256"),
        FolderRepository.hex("SHA-256", changed));
    assertFailure(Failure.CLASS_PATH_IN_MANIFEST, () -> store.load(DRIVER, "2.0.0"));

    repository.publish(
        DRIVER, "3.0.0", "not a jar".getBytes(StandardCharsets.UTF_8), Sum.SHA256, NOW);
    assertFailure(
        Failure.NOT_A_DRIVER, () -> store.download(DRIVER, "3.0.0", DriverStore.Progress.NONE));
  }

  @Test
  void twoDownloadsOfOneVersionAtOnceBothSucceedWithOneResult() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    repository.served = path -> {
      if (path.endsWith(".jar") && calls.getAndIncrement() == 0) {
        started.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          throw new IllegalStateException(e);
        }
      }
    };
    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(() -> {
        store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
        return null;
      });
      assertTrue(started.await(10, TimeUnit.SECONDS));
      // The first download holds its bytes; the repository changes the file, and a second
      // download finishes before the first does.
      repository.publish(DRIVER, "1.0.0", FakeDriver.jar("2", temp), Sum.SHA256, NOW);
      store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    }
    assertEquals(List.of("fake-jdbc-1.0.0.jar", "fake-jdbc-1.0.0.jar.sha256"), files());
    assertEquals(List.of("1.0.0"), entries());
    try (var loaded = store.load(DRIVER, "1.0.0")) {
      assertEquals("2", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void aDeleteDuringADownloadDisturbsNeither() throws Exception {
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    var stored = storage.resolve("dev.hoori.fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar");
    Files.write(stored, FakeDriver.plain("changed"));
    store.download(DRIVER, "1.0.0", (done, total) -> {
      try {
        store.delete(DRIVER, "1.0.0");
      } catch (DriverException e) {
        throw new IllegalStateException(e);
      }
    });
    assertEquals(List.of("fake-jdbc-1.0.0.jar", "fake-jdbc-1.0.0.jar.sha256"), files());
    assertEquals(List.of("1.0.0"), entries());
    try (var loaded = store.load(DRIVER, "1.0.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
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

  /** Its file goes, then its folder cannot be renamed away: the version is gone, not broken. */
  @Test
  void aVersionWhoseFolderStaysAfterItsFileWentIsGone() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    var artifact = storage.resolve("dev.hoori.fake/fake-jdbc");
    var permissions = Files.getPosixFilePermissions(artifact);
    Files.setPosixFilePermissions(artifact, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isWritable(artifact), "not as root");
      store.delete(DRIVER, "1.0.0");
      assertEquals(List.of(), store.downloaded(DRIVER));
      assertFailure(Failure.NOT_DOWNLOADED, () -> store.load(DRIVER, "1.0.0"));
    } finally {
      Files.setPosixFilePermissions(artifact, permissions);
    }
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    store.load(DRIVER, "1.0.0").close();
  }

  @Test
  void aResponseThatBreaksOffIsTheRepositorysFailure() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    repository.breakingOff.add("dev/hoori/fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar");
    assertFailure(
        Failure.UNAVAILABLE, () -> store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE));
    repository.breakingOff.add("dev/hoori/fake/fake-jdbc/maven-metadata.xml");
    assertFailure(Failure.UNAVAILABLE, () -> store.versions(DRIVER));
  }

  /** A write that fails is put down to a full disk when the disk has less room than the file. */
  @Test
  void aFailedWriteIsNoSpaceWhenTheDiskHasLessRoomThanTheFile() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    var path = "dev/hoori/fake/fake-jdbc/1.0.0/fake-jdbc-1.0.0.jar";
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW);
    // Once the file is served, its temporary folder takes no file: the write fails.
    repository.served = served -> {
      if (served.equals(path)) {
        try (var entries = Files.list(storage.resolve("dev.hoori.fake/fake-jdbc"))) {
          for (var entry : entries.toList()) {
            Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("r-xr-xr-x"));
          }
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }
    };
    long size = BYTES.length;
    var roomy = new DriverStore(storage, repository, 1 << 20, folder -> size);
    assertFailure(Failure.STORAGE, () -> roomy.download(DRIVER, "1.0.0", null));
    var full = new DriverStore(storage, repository, 1 << 20, folder -> size - 1);
    assertFailure(Failure.NO_SPACE, () -> full.download(DRIVER, "1.0.0", null));
  }

  /** As a loaded JAR on Windows, which stays open until the app ends. */
  @Test
  void aVersionWhoseFileCannotBeDeletedStaysWholeAndUsable() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    repository.publish(DRIVER, "1.0.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    var dir = storage.resolve("dev.hoori.fake/fake-jdbc/1.0.0");
    var permissions = Files.getPosixFilePermissions(dir);
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeFalse(Files.isWritable(dir), "not as root");
      assertFailure(Failure.STORAGE, () -> store.delete(DRIVER, "1.0.0"));
      assertEquals(List.of("1.0.0"), store.downloaded(DRIVER));
      repository.offline = true;
      store.load(DRIVER, "1.0.0").close();
    } finally {
      Files.setPosixFilePermissions(dir, permissions);
    }
    store.delete(DRIVER, "1.0.0");
    assertEquals(List.of(), store.downloaded(DRIVER));
  }

  @Test
  void theChoicesSayWhichAreTooYoungToBeRecommended() throws Exception {
    var young = NOW.minus(Duration.ofDays(1));
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(30)));
    repository.publish(DRIVER, "1.1.0", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(5)));
    repository.publish(DRIVER, "1.2.0-rc1", BYTES, Sum.SHA256, young);
    repository.publish(DRIVER, "1.2.0", BYTES, Sum.SHA256, young);
    var choices = store.choices(DRIVER, NOW);
    assertEquals(List.of("1.2.0", "1.1.0", "1.0.0"), choices.versions());
    assertEquals("1.1.0", choices.recommended());
    assertEquals(java.util.Map.of("1.2.0", young), choices.published());
    store.download(DRIVER, "1.2.0", DriverStore.Progress.NONE);
    assertEquals(BYTES.length, store.sizeOnDisk(DRIVER, "1.2.0"));
    assertFailure(Failure.NOT_DOWNLOADED, () -> store.sizeOnDisk(DRIVER, "1.1.0"));
  }

  @Test
  void aRepositoryThatAnswersWithAnErrorIsUnavailable() throws Exception {
    repository.publish(DRIVER, "1.0.0", BYTES, Sum.SHA256, NOW.minus(Duration.ofDays(10)));
    repository.refusing = true;
    assertFailure(Failure.UNAVAILABLE, () -> store.versions(DRIVER));
    assertFailure(Failure.UNAVAILABLE, () -> store.recommended(DRIVER, NOW));
    repository.refusing = false;
    store.download(DRIVER, "1.0.0", DriverStore.Progress.NONE);
    repository.refusing = true;
    assertEquals(new Recommended("1.0.0", true, null), store.recommended(DRIVER, NOW));
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

  private List<String> entries() throws Exception {
    try (var paths = Files.list(storage.resolve("dev.hoori.fake/fake-jdbc"))) {
      return paths.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private interface Call {
    void run() throws Exception;
  }

  @Test
  void theSizeIsAskedWithoutDownloading() throws Exception {
    repository.publish(DRIVER, "1.0", BYTES, Sum.SHA256, NOW);
    assertEquals(BYTES.length, store.size(DRIVER, "1.0"));
    assertEquals(List.of(), store.downloaded(DRIVER));
    assertFailure(Failure.NOT_FOUND, () -> store.size(DRIVER, "2.0"));
    assertFailure(
        Failure.TOO_LARGE, () -> new DriverStore(storage, repository, 10).size(DRIVER, "1.0"));
    repository.offline = true;
    assertFailure(Failure.NO_NETWORK, () -> store.size(DRIVER, "1.0"));
  }

  /**
   * MySQL Connector/J reads its messages through a {@code jar:} URL as an attempt fails, which
   * Java's cache of such files keeps open past the class loader's close; Windows then deletes no
   * such file.
   */
  @Test
  void aClosedDriverHoldsItsFileOpenNowhere() throws Exception {
    var driver = Driver.of(Provider.MYSQL);
    var version = System.getProperty("hooridb.test.mysql.version");
    assertNotNull(version, "No hooridb.test.mysql.version; run the tests through Maven");
    var copied = Path.of(System.getProperty("hooridb.test.drivers", "target/jdbc-drivers"));
    var jar = Files.readAllBytes(copied.resolve(driver.artifact() + "-" + version + ".jar"));
    repository.publish(driver, version, jar, Sum.SHA256, NOW);
    store.download(driver, version, DriverStore.Progress.NONE);
    int port;
    try (var free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = free.getLocalPort();
    }
    try (var loaded = store.load(driver, version)) {
      var url = "jdbc:mysql://127.0.0.1:" + port + "/x";
      assertThrows(SQLException.class, () -> loaded.connect(url, new Properties()));
    }
    assertEquals(List.of(), openBelow(temp));
    store.delete(driver, version);
    assertEquals(List.of(), store.downloaded(driver));
  }

  /** The files below the folder that this process holds open, as Linux tells; none elsewhere. */
  public static List<String> openBelow(Path folder) throws IOException {
    var fds = Path.of("/proc/self/fd");
    if (!Files.isDirectory(fds)) {
      return List.of();
    }
    var below = folder.toRealPath().toString();
    var open = new ArrayList<String>();
    try (var list = Files.list(fds)) {
      for (var fd : (Iterable<Path>) list::iterator) {
        try {
          // A deleted file's link ends in " (deleted)".
          var target = Files.readSymbolicLink(fd).toString();
          if (target.startsWith(below)) {
            open.add(target);
          }
        } catch (IOException closedMeanwhile) {
          // Not open any longer.
        }
      }
    }
    return open;
  }

  private static final Driver PARTED = new Driver(
      DRIVER.group(),
      DRIVER.artifact(),
      DRIVER.driverClass(),
      List.of(new Driver.Part("fake-core", false), new Driver.Part("fake-extra", true)));

  private static Driver part(String artifact) {
    return new Driver(DRIVER.group(), artifact, DRIVER.driverClass());
  }

  @Test
  void aDriverOfSeveralPartsIsDownloadedAndLoadedWhole() throws Exception {
    // The driver's class is in a part, so that it loads only with the parts beside it.
    var core = FakeDriver.jar("1", temp);
    repository.publish(PARTED, "1.0", BYTES, Sum.SHA256, NOW);
    repository.publish(part("fake-core"), "1.0", core, Sum.SHA1, NOW);
    repository.publish(part("fake-extra"), "1.0", BYTES, Sum.SHA256, NOW);
    long all = BYTES.length + core.length + BYTES.length;
    assertEquals(all, store.size(PARTED, "1.0"));

    var progress = new ArrayList<long[]>();
    store.download(PARTED, "1.0", (done, total) -> progress.add(new long[] {done, total}));
    assertEquals(all, progress.getLast()[0]);
    assertEquals(all, progress.getLast()[1]);
    assertEquals(
        List.of(
            "fake-core-1.0.jar",
            "fake-core-1.0.jar.sha256",
            "fake-extra-1.0.jar",
            "fake-extra-1.0.jar.sha256",
            "fake-jdbc-1.0.jar",
            "fake-jdbc-1.0.jar.sha256"),
        files());
    assertEquals(List.of("1.0"), store.downloaded(PARTED));
    assertEquals(all, store.sizeOnDisk(PARTED, "1.0"));
    try (var loaded = store.load(PARTED, "1.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }

    var fetched = List.copyOf(repository.opened);
    repository.offline = true;
    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    assertEquals(fetched, repository.opened);
  }

  @Test
  void anOptionalPartThatTheRepositoryLacksIsLeftOut() throws Exception {
    var core = FakeDriver.jar("1", temp);
    repository.publish(PARTED, "1.0", BYTES, Sum.SHA256, NOW);
    repository.publish(part("fake-core"), "1.0", core, Sum.SHA256, NOW);
    assertEquals(BYTES.length + core.length, store.size(PARTED, "1.0"));
    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    assertEquals(List.of("1.0"), store.downloaded(PARTED));
    try (var loaded = store.load(PARTED, "1.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void anOptionalPartLostAfterItsDownloadIsDownloadedAgain() throws Exception {
    repository.publish(PARTED, "1.0", BYTES, Sum.SHA256, NOW);
    repository.publish(part("fake-core"), "1.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    repository.publish(part("fake-extra"), "1.0", BYTES, Sum.SHA256, NOW);
    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    // Its record stays: it was downloaded, and is missing now.
    var extra = storage.resolve("dev.hoori.fake/fake-jdbc/1.0/fake-extra-1.0.jar");
    Files.delete(extra);
    assertEquals(List.of(), store.downloaded(PARTED));
    assertFailure(Failure.NOT_DOWNLOADED, () -> store.load(PARTED, "1.0"));
    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    assertTrue(Files.isRegularFile(extra));
    try (var loaded = store.load(PARTED, "1.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void aMissingPartLeavesNothingBehind() throws Exception {
    repository.publish(PARTED, "1.0", BYTES, Sum.SHA256, NOW);
    assertFailure(Failure.NOT_FOUND, () -> store.size(PARTED, "1.0"));
    assertFailure(
        Failure.NOT_FOUND, () -> store.download(PARTED, "1.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());

    var file = repository.publish(part("fake-core"), "1.0", BYTES, Sum.SHA256, NOW);
    Files.write(file, "damaged".getBytes(StandardCharsets.UTF_8));
    assertFailure(
        Failure.CHECKSUM_MISMATCH, () -> store.download(PARTED, "1.0", DriverStore.Progress.NONE));
    assertEquals(List.of(), files());
    assertEquals(List.of(), store.downloaded(PARTED));
  }

  @Test
  void aPartChangedAfterItsDownloadIsRefusedAndDownloadedAgain() throws Exception {
    repository.publish(PARTED, "1.0", BYTES, Sum.SHA256, NOW);
    repository.publish(part("fake-core"), "1.0", FakeDriver.jar("1", temp), Sum.SHA256, NOW);
    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    var stored = storage.resolve("dev.hoori.fake/fake-jdbc/1.0/fake-core-1.0.jar");
    Files.write(stored, FakeDriver.jar("2", temp));
    assertFailure(Failure.CHECKSUM_MISMATCH, () -> store.load(PARTED, "1.0"));
    Files.delete(stored);
    assertEquals(List.of(), store.downloaded(PARTED));
    assertFailure(Failure.NOT_DOWNLOADED, () -> store.load(PARTED, "1.0"));

    store.download(PARTED, "1.0", DriverStore.Progress.NONE);
    try (var loaded = store.load(PARTED, "1.0")) {
      assertEquals("1", loaded.connect("jdbc:fake:x", new Properties()).getCatalog());
    }
  }

  @Test
  void aPartIsNamedOnce() {
    var twice = List.of(new Driver.Part("b", false), new Driver.Part("b", true));
    assertThrows(IllegalArgumentException.class, () -> new Driver("org.x", "a", "a.Driver", twice));
    var itself = List.of(new Driver.Part("a", false));
    assertThrows(
        IllegalArgumentException.class, () -> new Driver("org.x", "a", "a.Driver", itself));
    // One file where the file system ignores case.
    var inCase = List.of(new Driver.Part("A", false));
    assertThrows(
        IllegalArgumentException.class, () -> new Driver("org.x", "a", "a.Driver", inCase));
    assertThrows(IllegalArgumentException.class, () -> new Driver.Part("../b", false));
  }

  @Test
  void theAddressIsTheFilesPathUnderTheRepository() {
    assertEquals("dev/hoori/fake/fake-jdbc/1.0/fake-jdbc-1.0.jar", store.address(DRIVER, "1.0"));
    assertThrows(IllegalArgumentException.class, () -> store.address(DRIVER, "../1.0"));
  }

  private static void assertFailure(Failure failure, Call call) {
    var e = assertThrows(DriverException.class, call::run);
    assertEquals(failure, e.failure(), e.getMessage());
  }
}

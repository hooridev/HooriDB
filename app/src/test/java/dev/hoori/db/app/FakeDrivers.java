package dev.hoori.db.app;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connect.Server;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.driver.LoadedDriver;
import dev.hoori.db.core.driver.MavenRepository;
import dev.hoori.db.core.driver.Repository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/**
 * A repository in a folder that serves a made-up PostgreSQL driver as if it were Maven Central, and
 * a made-up server that answers a test as it is told.
 */
final class FakeDrivers implements Repository {

  static final String VERSION = "42.7.14";
  static final Driver DRIVER = Driver.of(Provider.POSTGRESQL);
  static final Instant PUBLISHED = Instant.parse("2026-10-01T00:00:00Z");
  /** What the drivers' clock says in every test, which ages count from. */
  static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  /** A file beside a driver's JAR in the drivers folder: its load waits while the file is there. */
  static final String HOLD = "hold";

  private static int jarOrFail() {
    try {
      return jar(DRIVER).length;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final Map<Driver, byte[]> JARS = new HashMap<>(); // compiled once for all tests

  /** The size of each made-up driver, as the app writes it. */
  static final String SIZE = Fetch.size(jarOrFail());

  final Path root;
  volatile boolean offline;
  // Holds every file opened before it is served, until it opens.
  volatile CountDownLatch opening;
  // Holds each question when a file was published, until it opens.
  volatile CountDownLatch dating;
  // Holds a driver's file after its first half, until it opens.
  volatile CountDownLatch reading;
  /** Every call, as "open <path>" or "published <path>", in order. */
  final List<String> calls = new CopyOnWriteArrayList<>();
  // How many calls held at a gate were interrupted: what asked for them was cancelled.
  final AtomicInteger interrupted = new AtomicInteger();
  // Opens once a driver's file has been read halfway: the transfer is under way.
  final CountDownLatch halfway = new CountDownLatch(1);

  FakeDrivers(Path root) {
    this.root = root;
  }

  /** The drivers of an app, kept in the folder and downloaded from this repository. */
  Drivers drivers(Path folder) {
    return new Drivers(
        folder, this, "Maven Central", MavenRepository.CENTRAL, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Override
  public Response open(String path) throws IOException, InterruptedException {
    calls.add("open " + path);
    if (offline) {
      throw new ConnectException("offline");
    }
    var gate = opening;
    if (gate != null) {
      held(gate);
    }
    var bytes = Files.readAllBytes(root.resolve(path));
    InputStream body = new ByteArrayInputStream(bytes);
    if (path.endsWith(".jar")) {
      body = new Halfway(body, bytes.length / 2);
    }
    return new Response(body, bytes.length);
  }

  @Override
  public Optional<Instant> published(String path) throws IOException, InterruptedException {
    calls.add("published " + path);
    if (offline) {
      throw new ConnectException("offline");
    }
    var gate = dating;
    if (gate != null) {
      held(gate);
    }
    return Optional.of(Files.getLastModifiedTime(root.resolve(path)).toInstant());
  }

  private void held(CountDownLatch gate) throws InterruptedException {
    try {
      gate.await();
    } catch (InterruptedException e) {
      interrupted.incrementAndGet();
      throw e;
    }
  }

  /** How many times the list of the provider's versions was read. */
  long listings(Provider provider) {
    var driver = Driver.of(provider);
    var path = "open " + driver.group().replace('.', '/') + "/" + driver.artifact()
        + "/maven-metadata.xml";
    return calls.stream().filter(path::equals).count();
  }

  /** Waits at the half of the file while {@link #reading} is shut. */
  private final class Halfway extends FilterInputStream {
    private long left;

    Halfway(InputStream in, long half) {
      super(in);
      left = half;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left == 0) {
        left = -1;
        halfway.countDown();
        var gate = reading;
        if (gate != null) {
          try {
            gate.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
          }
        }
      }
      int n = super.read(b, off, left > 0 ? (int) Math.min(len, left) : len);
      if (left > 0 && n > 0) {
        left -= n;
      }
      return n;
    }
  }

  /** Puts the made-up driver into the repository, with its checksum or a wrong one. */
  FakeDrivers publish(boolean rightChecksum) throws Exception {
    return publish(DRIVER, rightChecksum);
  }

  /** Puts a made-up driver of each kind into the repository. */
  FakeDrivers publishAll() throws Exception {
    for (var provider : Provider.values()) {
      publish(Driver.of(provider), true);
    }
    return this;
  }

  private FakeDrivers publish(Driver driver, boolean rightChecksum) throws Exception {
    return publish(driver, VERSION, PUBLISHED, rightChecksum);
  }

  /** Puts a version of the made-up PostgreSQL driver into the repository, published then. */
  FakeDrivers publish(String version, Instant published) throws Exception {
    return publish(DRIVER, version, published, true);
  }

  private FakeDrivers publish(
      Driver driver, String version, Instant published, boolean rightChecksum) throws Exception {
    var dir = root.resolve(driver.group().replace('.', '/'))
        .resolve(driver.artifact())
        .resolve(version);
    Files.createDirectories(dir);
    var bytes = jar(driver);
    var file = dir.resolve(driver.artifact() + "-" + version + ".jar");
    Files.write(file, bytes);
    Files.setLastModifiedTime(file, FileTime.from(published));
    var sum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Files.writeString(
        dir.resolve(file.getFileName() + ".sha256"), rightChecksum ? sum : "0".repeat(64));
    // Its parts, each the same made-up JAR: core's bridge, of a driver of documents, comes from
    // core, and none of them is read.
    for (var part : driver.parts()) {
      var at = root.resolve(driver.group().replace('.', '/'))
          .resolve(part.artifact())
          .resolve(version);
      Files.createDirectories(at);
      var partFile = at.resolve(part.artifact() + "-" + version + ".jar");
      Files.write(partFile, bytes);
      Files.setLastModifiedTime(partFile, FileTime.from(published));
      Files.writeString(at.resolve(partFile.getFileName() + ".sha256"), sum);
    }
    var versions = new StringBuilder();
    try (var entries = Files.list(dir.getParent())) {
      entries
          .filter(Files::isDirectory)
          .forEach(v -> versions.append("<version>").append(v.getFileName()).append("</version>"));
    }
    Files.writeString(
        dir.getParent().resolve("maven-metadata.xml"),
        "<?xml version=\"1.0\"?><metadata><versioning><versions>" + versions
            + "</versions></versioning></metadata>",
        StandardCharsets.UTF_8);
    return this;
  }

  /**
   * Makes the version's folder read-only, so that its file cannot be deleted, as Windows keeps a
   * loaded driver's file; false where the file system cannot, or the test runs as root.
   */
  static boolean lock(Path drivers, String version) throws IOException {
    var dir = drivers.resolve(DRIVER.group()).resolve(DRIVER.artifact()).resolve(version);
    if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return false;
    }
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
    return !Files.isWritable(dir);
  }

  static void unlock(Path drivers, String version) throws IOException {
    var dir = drivers.resolve(DRIVER.group()).resolve(DRIVER.artifact()).resolve(version);
    if (Files.exists(dir) && dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  /** The JAR of a class of the driver's name that is a driver and connects nowhere. */
  static synchronized byte[] jar(Driver driver) throws Exception {
    var jar = JARS.get(driver);
    if (jar != null) {
      return jar;
    }
    var name = driver.driverClass();
    var pkg = name.substring(0, name.lastIndexOf('.'));
    var simple = name.substring(pkg.length() + 1);
    var entry = name.replace('.', '/');
    var work = Files.createTempDirectory("hooridb-fake-driver-");
    try {
      var src = Files.createDirectories(work.resolve("src").resolve(pkg.replace('.', '/')));
      var classes = Files.createDirectories(work.resolve("classes"));
      var file =
          Files.writeString(src.resolve(simple + ".java"), """
          package %s;

          import java.sql.*;
          import java.util.Properties;
          import java.util.logging.Logger;

          public class %s implements java.sql.Driver {
            // Its load waits while a file named hold lies beside its JAR, so that a test can
            // hold it open.
            static {
              try {
                var jar = java.nio.file.Path.of(
                    %s.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                while (java.nio.file.Files.exists(jar.resolveSibling("hold"))) {
                  Thread.sleep(10);
                }
              } catch (Exception e) {
                // Loaded from elsewhere, or interrupted: it goes on.
              }
            }

            public Connection connect(String url, Properties info) {
              return null;
            }

            public boolean acceptsURL(String url) {
              return false;
            }

            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
              return new DriverPropertyInfo[0];
            }

            public int getMajorVersion() {
              return 42;
            }

            public int getMinorVersion() {
              return 7;
            }

            public boolean jdbcCompliant() {
              return false;
            }

            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
              throw new SQLFeatureNotSupportedException();
            }
          }
          """.formatted(pkg, simple, simple));
      int status = ToolProvider.getSystemJavaCompiler()
          .run(null, null, null, "-d", classes.toString(), file.toString());
      if (status != 0) {
        throw new IllegalStateException("The made-up driver does not compile");
      }
      var bytes = new ByteArrayOutputStream();
      try (var out = new JarOutputStream(bytes)) {
        out.putNextEntry(new JarEntry(entry + ".class"));
        out.write(Files.readAllBytes(classes.resolve(entry + ".class")));
        out.closeEntry();
      }
      jar = bytes.toByteArray();
      JARS.put(driver, jar);
      return jar;
    } finally {
      try (var paths = Files.walk(work)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
  }

  /** A server that answers as it is told, and notes what each test sent it. */
  static final class FakeServer implements Tester {
    volatile Tester.Result answer = new Tester.Reached(new Server("PostgreSQL", "16.4"));
    volatile CountDownLatch gate; // holds each test until it opens
    final List<String> passwords = new CopyOnWriteArrayList<>();
    final List<LoadedDriver> drivers = new CopyOnWriteArrayList<>();
    volatile boolean interrupted;

    void refuse(ConnectionException.Failure failure, String detail) {
      answer = new Tester.Refused(failure, detail);
    }

    @Override
    public Tester.Result test(ConnectionProfile profile, char[] password, LoadedDriver driver) {
      passwords.add(new String(password));
      drivers.add(driver);
      var g = gate;
      if (g != null) {
        try {
          g.await();
        } catch (InterruptedException _) {
          interrupted = true;
          Thread.currentThread().interrupt();
          return new Tester.Refused(ConnectionException.Failure.CANCELLED, "");
        }
      }
      return answer;
    }
  }
}

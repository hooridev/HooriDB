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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

  private static byte[] jar; // compiled once for all tests

  final Path root;
  volatile boolean offline;
  // Holds every file opened before it is served, until it opens.
  volatile CountDownLatch opening;
  // Holds a driver's file after its first half, until it opens.
  volatile CountDownLatch reading;
  // Opens once a driver's file has been read halfway: the transfer is under way.
  final CountDownLatch halfway = new CountDownLatch(1);

  FakeDrivers(Path root) {
    this.root = root;
  }

  /** The drivers of an app, kept in the folder and downloaded from this repository. */
  Drivers drivers(Path folder) {
    return new Drivers(folder, this, "Maven Central", MavenRepository.CENTRAL);
  }

  @Override
  public Response open(String path) throws IOException, InterruptedException {
    if (offline) {
      throw new ConnectException("offline");
    }
    var gate = opening;
    if (gate != null) {
      gate.await();
    }
    var bytes = Files.readAllBytes(root.resolve(path));
    InputStream body = new ByteArrayInputStream(bytes);
    if (path.endsWith(".jar")) {
      body = new Halfway(body, bytes.length / 2);
    }
    return new Response(body, bytes.length);
  }

  @Override
  public Optional<Instant> published(String path) throws IOException {
    if (offline) {
      throw new ConnectException("offline");
    }
    return Optional.of(Files.getLastModifiedTime(root.resolve(path)).toInstant());
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
          } catch (InterruptedException e) {
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
    var dir = root.resolve(DRIVER.group().replace('.', '/'))
        .resolve(DRIVER.artifact())
        .resolve(VERSION);
    Files.createDirectories(dir);
    var bytes = jar();
    var file = dir.resolve(DRIVER.artifact() + "-" + VERSION + ".jar");
    Files.write(file, bytes);
    Files.setLastModifiedTime(file, FileTime.from(PUBLISHED));
    var sum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Files.writeString(
        dir.resolve(file.getFileName() + ".sha256"), rightChecksum ? sum : "0".repeat(64));
    Files.writeString(
        dir.getParent().resolve("maven-metadata.xml"),
        "<?xml version=\"1.0\"?><metadata><versioning><versions><version>" + VERSION
            + "</version></versions></versioning></metadata>",
        StandardCharsets.UTF_8);
    return this;
  }

  /** The JAR of a class of the PostgreSQL driver's name that is a driver and connects nowhere. */
  static synchronized byte[] jar() throws Exception {
    if (jar != null) {
      return jar;
    }
    var work = Files.createTempDirectory("hooridb-fake-driver-");
    try {
      var src = Files.createDirectories(work.resolve("src/org/postgresql"));
      var classes = Files.createDirectories(work.resolve("classes"));
      var file = Files.writeString(src.resolve("Driver.java"), """
          package org.postgresql;

          import java.sql.*;
          import java.util.Properties;
          import java.util.logging.Logger;

          public class Driver implements java.sql.Driver {
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
          """);
      int status = ToolProvider.getSystemJavaCompiler()
          .run(null, null, null, "-d", classes.toString(), file.toString());
      if (status != 0) {
        throw new IllegalStateException("The made-up driver does not compile");
      }
      var bytes = new ByteArrayOutputStream();
      try (var out = new JarOutputStream(bytes)) {
        out.putNextEntry(new JarEntry("org/postgresql/Driver.class"));
        out.write(Files.readAllBytes(classes.resolve("org/postgresql/Driver.class")));
        out.closeEntry();
      }
      jar = bytes.toByteArray();
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
        } catch (InterruptedException e) {
          interrupted = true;
          Thread.currentThread().interrupt();
          return new Tester.Refused(ConnectionException.Failure.CANCELLED, "");
        }
      }
      return answer;
    }
  }
}

package dev.hoori.db.core.driver;

import static dev.hoori.db.core.driver.DriverException.Failure.CANCELLED;
import static dev.hoori.db.core.driver.DriverException.Failure.CHECKSUM_MISMATCH;
import static dev.hoori.db.core.driver.DriverException.Failure.INVALID_RESPONSE;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_A_DRIVER;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_DOWNLOADED;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_FOUND;
import static dev.hoori.db.core.driver.DriverException.Failure.NO_NETWORK;
import static dev.hoori.db.core.driver.DriverException.Failure.STORAGE;
import static dev.hoori.db.core.driver.DriverException.Failure.TOO_LARGE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Drivers downloaded from a repository into a folder, with a sub-folder per artifact and version
 * such as {@code org.postgresql/postgresql/42.7.14/}. A file there under its real name has matched
 * its checksum, which is recorded beside it and checked again before the file is loaded. What is
 * downloaded works without a network. Every method blocks; interrupting the thread cancels it with
 * {@link DriverException.Failure#CANCELLED} and leaves the thread interrupted.
 */
public final class DriverStore {

  /** How long a release must have been published before it is recommended. */
  public static final Duration WAITING_PERIOD = Duration.ofDays(3);

  static final long MAX_DRIVER_SIZE = 64L << 20;
  private static final int MAX_METADATA_SIZE = 4 << 20;
  private static final int MAX_CHECKSUM_SIZE = 1024;

  /** The version to use, and whether it was chosen from what is downloaded, without a network. */
  public record Recommended(String version, boolean offline) {}

  /** How far a download is: bytes so far and in all, or -1 when that is not known. */
  @FunctionalInterface
  public interface Progress {
    Progress NONE = (done, total) -> {};

    void update(long done, long total);
  }

  private final Path folder;
  private final Repository repository;
  private final long maxSize;

  public DriverStore(Path folder, Repository repository) {
    this(folder, repository, MAX_DRIVER_SIZE);
  }

  DriverStore(Path folder, Repository repository, long maxSize) {
    this.folder = folder;
    this.repository = repository;
    this.maxSize = maxSize;
  }

  /** The versions the repository has of the driver, newest first. */
  public List<String> versions(Driver driver) throws DriverException {
    var path = base(driver) + "maven-metadata.xml";
    return parse(path, fetch(path, MAX_METADATA_SIZE)).stream()
        .filter(Maven::isVersion)
        .distinct()
        .sorted(Maven.NEWEST_FIRST)
        .toList();
  }

  /** When the repository got the version, or empty when it does not say. */
  public Optional<Instant> published(Driver driver, String version) throws DriverException {
    var path = file(driver, version);
    return remote(path, () -> repository.published(path));
  }

  /** {@link #recommended(Driver, Instant, Duration)} with the default waiting period. */
  public Recommended recommended(Driver driver, Instant now) throws DriverException {
    return recommended(driver, now, WAITING_PERIOD);
  }

  /**
   * The newest stable release that was published at least the waiting period before now. A bad
   * release is usually noticed and withdrawn within days, so a younger one is never recommended,
   * though it can still be downloaded by name. When the repository cannot be reached, this is the
   * newest version that is downloaded, and {@link DriverException.Failure#NO_NETWORK} only when
   * there is none.
   */
  public Recommended recommended(Driver driver, Instant now, Duration waitingPeriod)
      throws DriverException {
    try {
      var latest = now.minus(waitingPeriod);
      for (var version : versions(driver)) {
        if (!Maven.isStable(version)) {
          continue;
        }
        Optional<Instant> published;
        try {
          published = published(driver, version);
        } catch (DriverException e) {
          if (e.failure() == NOT_FOUND) {
            continue;
          }
          throw e;
        }
        // Without a date, a version cannot be shown to have waited.
        if (published.isPresent() && !published.get().isAfter(latest)) {
          return new Recommended(version, false);
        }
      }
      throw new DriverException(NOT_FOUND, "No release is old enough: " + base(driver));
    } catch (DriverException e) {
      if (e.failure() != NO_NETWORK) {
        throw e;
      }
      var downloaded = downloaded(driver);
      if (downloaded.isEmpty()) {
        throw e;
      }
      return new Recommended(downloaded.getFirst(), true);
    }
  }

  /**
   * Downloads the version into the folder, unless it is there already and matches its record.
   * The bytes go to a temporary file, which gets its real name only once its checksum matched.
   */
  public void download(Driver driver, String version, Progress progress) throws DriverException {
    var jar = jar(driver, version);
    if (matchesRecord(jar)) {
      return;
    }
    var path = file(driver, version);
    // The checksum the repository publishes beside the file, fetched over HTTPS as well. It shows
    // that a download is not damaged or cut short, but not that the repository serves a good
    // file: one that serves a bad file can serve a checksum to match. The waiting period before
    // a release is recommended is the answer to a bad release, not this.
    var bySha256 = true;
    String expected;
    try {
      expected = checksum(path + ".sha256", 64);
    } catch (DriverException e) {
      if (e.failure() != NOT_FOUND) {
        throw e;
      }
      bySha256 = false;
      expected = checksum(path + ".sha1", 40);
    }
    var dir = jar.getParent();
    Path part = null;
    Path recordPart = null;
    try {
      Files.createDirectories(dir);
      part = Files.createTempFile(dir, jar.getFileName() + ".", ".part");
      var sha256 = digest("SHA-256");
      var sha1 = digest("SHA-1");
      try (var response = remote(path, () -> repository.open(path));
          var out = Files.newOutputStream(part)) {
        copy(path, response, out, maxSize, progress, sha256, sha1);
      }
      var recorded = HexFormat.of().formatHex(sha256.digest());
      var actual = bySha256 ? recorded : HexFormat.of().formatHex(sha1.digest());
      if (!actual.equals(expected)) {
        throw new DriverException(CHECKSUM_MISMATCH, path);
      }
      recordPart = Files.createTempFile(dir, jar.getFileName() + ".sha256.", ".part");
      Files.writeString(recordPart, recorded, StandardCharsets.US_ASCII);
      Files.move(recordPart, record(jar), StandardCopyOption.ATOMIC_MOVE);
      Files.move(part, jar, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw storage(e);
    } finally {
      deleteQuietly(part);
      deleteQuietly(recordPart);
      try {
        Files.deleteIfExists(dir);
      } catch (DirectoryNotEmptyException e) {
        // It holds this download or an earlier one.
      } catch (IOException e) {
        // Only an empty folder is left behind.
      }
    }
  }

  /** The versions of the driver in the folder, newest first. */
  public List<String> downloaded(Driver driver) throws DriverException {
    var dir = folder.resolve(driver.group()).resolve(driver.artifact());
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var entries = Files.list(dir)) {
      return entries
          .map(entry -> entry.getFileName().toString())
          .filter(Maven::isVersion)
          .filter(version -> Files.isRegularFile(jar(driver, version)))
          .sorted(Maven.NEWEST_FIRST)
          .toList();
    } catch (IOException e) {
      throw storage(e);
    } catch (UncheckedIOException e) {
      throw storage(e.getCause());
    }
  }

  /** Deletes the version from the folder; on Windows, it must not be loaded. */
  public void delete(Driver driver, String version) throws DriverException {
    var jar = jar(driver, version);
    var dir = jar.getParent();
    if (!Files.isDirectory(dir)) {
      return;
    }
    try {
      Files.deleteIfExists(jar);
      try (var entries = Files.list(dir)) {
        for (var entry : entries.toList()) {
          Files.delete(entry);
        }
      }
      Files.delete(dir);
    } catch (IOException e) {
      throw storage(e);
    }
  }

  /** Loads the downloaded version in a class loader of its own. */
  public LoadedDriver load(Driver driver, String version) throws DriverException {
    var jar = jar(driver, version);
    if (!Files.isRegularFile(jar)) {
      throw new DriverException(NOT_DOWNLOADED, file(driver, version));
    }
    // The file sat on disk since it was checked, and whatever changed it must not run.
    if (!matchesRecord(jar)) {
      throw new DriverException(CHECKSUM_MISMATCH, file(driver, version));
    }
    URL url;
    try {
      url = jar.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
    var loader = new URLClassLoader(
        driver.artifact() + "-" + version, new URL[] {url}, ClassLoader.getPlatformClassLoader());
    try {
      var type =
          Class.forName(driver.driverClass(), true, loader).asSubclass(java.sql.Driver.class);
      return new LoadedDriver(type.getDeclaredConstructor().newInstance(), loader);
    } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
      try {
        loader.close();
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw new DriverException(NOT_A_DRIVER, driver.driverClass(), e);
    }
  }

  private Path jar(Driver driver, String version) {
    if (!Maven.isVersion(version)) {
      throw new IllegalArgumentException("Not a version: " + version);
    }
    return folder
        .resolve(driver.group())
        .resolve(driver.artifact())
        .resolve(version)
        .resolve(driver.artifact() + "-" + version + ".jar");
  }

  private static Path record(Path jar) {
    return jar.resolveSibling(jar.getFileName() + ".sha256");
  }

  private static String base(Driver driver) {
    return driver.group().replace('.', '/') + "/" + driver.artifact() + "/";
  }

  private static String file(Driver driver, String version) {
    if (!Maven.isVersion(version)) {
      throw new IllegalArgumentException("Not a version: " + version);
    }
    return base(driver) + version + "/" + driver.artifact() + "-" + version + ".jar";
  }

  private boolean matchesRecord(Path jar) throws DriverException {
    if (!Files.isRegularFile(jar)) {
      return false;
    }
    try {
      var recorded = Files.readString(record(jar), StandardCharsets.US_ASCII).strip();
      var sha256 = digest("SHA-256");
      try (var in = new DigestInputStream(Files.newInputStream(jar), sha256)) {
        in.transferTo(OutputStream.nullOutputStream());
      }
      return recorded.equals(HexFormat.of().formatHex(sha256.digest()));
    } catch (NoSuchFileException e) {
      return false;
    } catch (IOException e) {
      throw storage(e);
    }
  }

  private String checksum(String path, int length) throws DriverException {
    var text = new String(fetch(path, MAX_CHECKSUM_SIZE), StandardCharsets.US_ASCII).strip();
    // The file holds the hexadecimal digest, sometimes followed by the file's name.
    var digest = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    if (digest.length() != length || !digest.chars().allMatch(HexFormat::isHexDigit)) {
      throw new DriverException(INVALID_RESPONSE, path);
    }
    return digest;
  }

  private byte[] fetch(String path, int limit) throws DriverException {
    var out = new ByteArrayOutputStream();
    try (var response = remote(path, () -> repository.open(path))) {
      copy(path, response, out, limit, Progress.NONE);
    } catch (IOException e) {
      throw network(path, e);
    }
    return out.toByteArray();
  }

  private void copy(
      String path,
      Repository.Response response,
      OutputStream out,
      long limit,
      Progress progress,
      MessageDigest... digests)
      throws DriverException {
    if (response.length() > limit) {
      throw new DriverException(TOO_LARGE, path);
    }
    var buffer = new byte[64 * 1024];
    long done = 0;
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        throw new DriverException(CANCELLED, path);
      }
      int n;
      try {
        n = response.body().read(buffer);
      } catch (IOException e) {
        throw network(path, e);
      }
      if (n < 0) {
        return;
      }
      done += n;
      if (done > limit) {
        throw new DriverException(TOO_LARGE, path);
      }
      for (var digest : digests) {
        digest.update(buffer, 0, n);
      }
      try {
        out.write(buffer, 0, n);
      } catch (IOException e) {
        throw storage(e);
      }
      progress.update(done, response.length());
    }
  }

  private interface Call<T> {
    T run() throws IOException, InterruptedException;
  }

  private static <T> T remote(String path, Call<T> call) throws DriverException {
    try {
      return call.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DriverException(CANCELLED, path, e);
    } catch (NoSuchFileException e) {
      throw new DriverException(NOT_FOUND, path, e);
    } catch (IOException e) {
      throw network(path, e);
    }
  }

  private static DriverException network(String path, IOException e) {
    return Thread.currentThread().isInterrupted()
        ? new DriverException(CANCELLED, path, e)
        : new DriverException(NO_NETWORK, path, e);
  }

  private static DriverException storage(IOException e) {
    return Thread.currentThread().isInterrupted()
        ? new DriverException(CANCELLED, "storage", e)
        : new DriverException(STORAGE, String.valueOf(e.getMessage()), e);
  }

  private static List<String> parse(String path, byte[] xml) throws DriverException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      // Metadata has no DTD; one that brings any, or entities from elsewhere, is refused.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      // Its default handler prints errors to the console as well as throwing them.
      builder.setErrorHandler(new DefaultHandler());
      var nodes = builder.parse(new ByteArrayInputStream(xml)).getElementsByTagName("version");
      var versions = new ArrayList<String>();
      for (int i = 0; i < nodes.getLength(); i++) {
        var node = nodes.item(i);
        if (node.getParentNode().getNodeName().equals("versions")) {
          versions.add(node.getTextContent().strip());
        }
      }
      return versions;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    } catch (SAXException | IOException e) {
      throw new DriverException(INVALID_RESPONSE, path, e);
    }
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      // What cannot be deleted keeps its temporary name and is never loaded.
    }
  }
}

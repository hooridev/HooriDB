package dev.hoori.db.core.driver;

import static dev.hoori.db.core.driver.DriverException.Failure.CANCELLED;
import static dev.hoori.db.core.driver.DriverException.Failure.CHECKSUM_MISMATCH;
import static dev.hoori.db.core.driver.DriverException.Failure.CLASS_PATH_IN_MANIFEST;
import static dev.hoori.db.core.driver.DriverException.Failure.INVALID_RESPONSE;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_A_DRIVER;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_DOWNLOADED;
import static dev.hoori.db.core.driver.DriverException.Failure.NOT_FOUND;
import static dev.hoori.db.core.driver.DriverException.Failure.NO_CHECKSUM;
import static dev.hoori.db.core.driver.DriverException.Failure.NO_NETWORK;
import static dev.hoori.db.core.driver.DriverException.Failure.NO_SPACE;
import static dev.hoori.db.core.driver.DriverException.Failure.STORAGE;
import static dev.hoori.db.core.driver.DriverException.Failure.TOO_LARGE;
import static dev.hoori.db.core.driver.DriverException.Failure.UNAVAILABLE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Drivers downloaded from a repository into a folder, with a sub-folder per artifact and version
 * such as {@code org.postgresql/postgresql/42.7.14/}, which holds the driver's file and those of its
 * {@link Driver.Part parts}. A version's folder is complete or absent: a download fills a temporary
 * folder and renames it into place, and a delete removes the driver's file first, so that a file
 * that cannot be deleted, as one that is open on Windows, leaves it whole. Each file has matched
 * the repository's checksum, which is recorded beside it and checked again before the file is
 * loaded. What is downloaded works without a network. Every method blocks; interrupting the thread
 * cancels it with {@link DriverException.Failure#CANCELLED} and leaves the thread interrupted,
 * except while a driver's own class initialization runs during {@link #load}, which cannot be
 * interrupted.
 */
public final class DriverStore {

  /** How long a release must have been published before it is recommended. */
  public static final Duration WAITING_PERIOD = Duration.ofDays(3);

  static final long MAX_DRIVER_SIZE = 64L << 20;
  // How many stable releases, newest first, are asked for their date before giving up.
  static final int MAX_CANDIDATES = 5;
  private static final int MAX_METADATA_SIZE = 4 << 20;
  private static final int MAX_CHECKSUM_SIZE = 1024;

  // What a version's folder is published under, by its path.
  private static final ConcurrentHashMap<Path, Object> PUBLISHING = new ConcurrentHashMap<>();

  /**
   * The version to use, and whether it was chosen from what is downloaded, without a network.
   *
   * @param version the version, or null where none of the newest few releases qualifies
   * @param choices what the repository offered, which named it or none; null when it was chosen
   *     offline
   */
  public record Recommended(String version, boolean offline, Choices choices) {}

  /**
   * The stable versions that can be chosen, newest first; the recommended one among them, or null
   * when none of the newest few qualifies; and when the versions newer than it were published, for
   * those the repository gives a date.
   */
  public record Choices(
      List<String> versions, String recommended, Map<String, Instant> published) {}

  /** How far a download is: bytes so far and in all, or -1 when that is not known. */
  @FunctionalInterface
  public interface Progress {
    Progress NONE = (done, total) -> {};

    void update(long done, long total);
  }

  /** The room left on the disk of a folder, in bytes; tests hand a store one of their own. */
  @FunctionalInterface
  interface Space {
    Space DISK = folder -> Files.getFileStore(folder).getUsableSpace();

    long usable(Path folder) throws IOException;
  }

  private final Path folder;
  private final Repository repository;
  private final long maxSize;
  private final Space space;

  public DriverStore(Path folder, Repository repository) {
    this(folder, repository, MAX_DRIVER_SIZE, Space.DISK);
  }

  DriverStore(Path folder, Repository repository, long maxSize) {
    this(folder, repository, maxSize, Space.DISK);
  }

  DriverStore(Path folder, Repository repository, long maxSize, Space space) {
    this.folder = folder;
    this.repository = repository;
    this.maxSize = maxSize;
    this.space = space;
  }

  /** The versions the repository has of the driver, newest first. */
  public List<String> versions(Driver driver) throws DriverException {
    var path = base(driver) + "maven-metadata.xml";
    checkCancelled(path);
    return parse(path, fetch(path, MAX_METADATA_SIZE)).stream()
        .filter(Maven::isVersion)
        .distinct()
        .sorted(Maven.NEWEST_FIRST)
        .toList();
  }

  /** When the repository got the version, or empty when it does not say. */
  public Optional<Instant> published(Driver driver, String version) throws DriverException {
    var path = file(driver, version);
    checkCancelled(path);
    return remote(path, () -> repository.published(path));
  }

  /**
   * The size of the version's files in bytes, or -1 when the repository does not say; nothing is
   * downloaded. Each file is opened and closed again before its content is read, which over HTTPS
   * costs one request whose body is cut off after the headers.
   */
  public long size(Driver driver, String version) throws DriverException {
    long total = 0;
    for (var artifact : artifacts(driver)) {
      var path = file(driver, artifact.artifact(), version);
      checkCancelled(path);
      try (var response = remote(path, () -> repository.open(path))) {
        if (response.length() > maxSize) {
          throw new DriverException(TOO_LARGE, path);
        }
        total = total < 0 || response.length() < 0 ? -1 : total + response.length();
      } catch (DriverException e) {
        if (e.failure() != NOT_FOUND || !artifact.optional()) {
          throw e;
        }
      } catch (IOException e) {
        throw network(path, e);
      }
    }
    return total;
  }

  /**
   * Where the version's file is under the repository, such as {@code
   * org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar}, for showing to the user.
   */
  public String address(Driver driver, String version) {
    return file(driver, version);
  }

  /** {@link #recommended(Driver, Instant, Duration)} with the default waiting period. */
  public Recommended recommended(Driver driver, Instant now) throws DriverException {
    return recommended(driver, now, WAITING_PERIOD);
  }

  /**
   * The newest stable release that was published at least the waiting period before now. A bad
   * release is usually noticed and withdrawn within days, so a younger one is never recommended,
   * though it can still be downloaded by name. Only the newest few releases are asked for their
   * date; when none of them qualifies, the version is null, with the choices read. When the
   * repository cannot be reached or fails,
   * this is the newest stable version that is downloaded and matches its record, and {@link
   * DriverException.Failure#NO_NETWORK} or {@link DriverException.Failure#UNAVAILABLE} only when
   * there is none.
   */
  public Recommended recommended(Driver driver, Instant now, Duration waitingPeriod)
      throws DriverException {
    try {
      var choices = choices(driver, now, waitingPeriod);
      return new Recommended(choices.recommended(), false, choices);
    } catch (DriverException e) {
      if (e.failure() != NO_NETWORK && e.failure() != UNAVAILABLE) {
        throw e;
      }
      // A version on disk was either recommended when it was downloaded or chosen on purpose,
      // which is good enough offline, so its age is not checked again and no dates are kept.
      for (var version : downloaded(driver)) {
        if (Maven.isStable(version) && complete(driver, version)) {
          return new Recommended(version, true, null);
        }
      }
      throw e;
    }
  }

  /** The versions to choose from, with the default waiting period; it needs the repository. */
  public Choices choices(Driver driver, Instant now) throws DriverException {
    return choices(driver, now, WAITING_PERIOD);
  }

  private Choices choices(Driver driver, Instant now, Duration waitingPeriod)
      throws DriverException {
    var latest = now.minus(waitingPeriod);
    // shortcut: pre-releases cannot be chosen; offer them when a user needs one.
    var versions = versions(driver).stream().filter(Maven::isStable).toList();
    var dates = new HashMap<String, Instant>();
    for (var version : versions.subList(0, Math.min(MAX_CANDIDATES, versions.size()))) {
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
        return new Choices(versions, version, Map.copyOf(dates));
      }
      published.ifPresent(date -> dates.put(version, date));
    }
    return new Choices(versions, null, Map.copyOf(dates));
  }

  /**
   * Downloads the version into the folder, unless it is there already and matches its record. Its
   * files go into a temporary folder, which takes the version's name only once each file matched
   * its checksum. Two downloads of one version at once both succeed with one of their results.
   */
  public void download(Driver driver, String version, Progress progress) throws DriverException {
    var jar = jar(driver, version);
    var path = file(driver, version);
    checkCancelled(path);
    if (complete(driver, version)) {
      return;
    }
    var wanted = new ArrayList<Wanted>();
    for (var artifact : artifacts(driver)) {
      var expected = expected(driver, artifact, version);
      if (expected != null) {
        wanted.add(expected);
      }
    }
    var dir = jar.getParent();
    Path temporary = null;
    long needed = 1 << 20; // a megabyte, and the files' length once it is known
    try {
      Files.createDirectories(dir.getParent());
      // Its name starts with a dot, which no version does, so nothing lists or loads it.
      temporary = Files.createTempDirectory(dir.getParent(), "." + version + ".");
      // One file is read at a time, to its end, so that no response waits unread behind another.
      // Of several, each is opened once before to learn their length.
      long total = wanted.size() == 1 ? 0 : lengths(wanted);
      if (total > 0) {
        needed = total;
      }
      long before = 0;
      for (var file : wanted) {
        var response = remote(file.path(), () -> repository.open(file.path()));
        try {
          long all = wanted.size() == 1 ? response.length() : total;
          if (all > 0) {
            needed = all;
          }
          before += transfer(file, response, temporary, progress, before, all);
        } finally {
          closeQuietly(response);
        }
      }
      checkCancelled(path);
      publish(temporary, dir, driver, version);
    } catch (IOException e) {
      throw full(dir, needed) ? new DriverException(NO_SPACE, path, e) : storage(e);
    } catch (DriverException e) {
      throw e.failure() == STORAGE && full(dir, needed)
          ? new DriverException(NO_SPACE, path, e)
          : e;
    } finally {
      deleteTree(temporary);
    }
  }

  // The files' length in all as the repository gives it, or -1 where it does not say one's.
  private long lengths(List<Wanted> wanted) throws DriverException {
    long total = 0;
    for (var file : wanted) {
      checkCancelled(file.path());
      try (var response = remote(file.path(), () -> repository.open(file.path()))) {
        total = total < 0 || response.length() < 0 ? -1 : total + response.length();
      } catch (IOException e) {
        throw network(file.path(), e);
      }
    }
    return total;
  }

  // Copies the file into the folder, checks it against its checksum and manifest and writes its
  // record beside it; its size in bytes.
  private long transfer(
      Wanted file,
      Repository.Response response,
      Path folder,
      Progress progress,
      long start,
      long all)
      throws IOException, DriverException {
    var part = folder.resolve(file.name());
    var sha256 = digest("SHA-256");
    var sha1 = digest("SHA-1");
    Progress each = (done, length) -> progress.update(start + done, all);
    try (var out = Files.newOutputStream(part)) {
      copy(file.path(), response, out, maxSize, each, sha256, sha1);
    }
    var recorded = HexFormat.of().formatHex(sha256.digest());
    var actual = file.bySha256() ? recorded : HexFormat.of().formatHex(sha1.digest());
    if (!actual.equals(file.expected())) {
      throw new DriverException(CHECKSUM_MISMATCH, file.path());
    }
    checkManifest(part, file.path());
    Files.writeString(record(part), recorded, StandardCharsets.US_ASCII);
    return Files.size(part);
  }

  private static void closeQuietly(Repository.Response response) {
    try {
      response.close();
    } catch (IOException ignored) {
      // Read to its end or given up; nothing more comes of it.
    }
  }

  // A file to download: where it is, its name in the version's folder, and its checksum.
  private record Wanted(String path, String name, String expected, boolean bySha256) {}

  // The checksum the repository publishes beside the file, fetched over HTTPS as well. It shows
  // that a download is not damaged or cut short, but not that the repository serves a good file:
  // one that serves a bad file can serve a checksum to match. The waiting period before a release
  // is recommended is the answer to a bad release, not this. Null for an optional part that the
  // repository does not have.
  private Wanted expected(Driver driver, Driver.Part artifact, String version)
      throws DriverException {
    var path = file(driver, artifact.artifact(), version);
    var name = artifact.artifact() + "-" + version + ".jar";
    try {
      return new Wanted(path, name, checksum(path + ".sha256", 64), true);
    } catch (DriverException e) {
      if (e.failure() != NOT_FOUND) {
        throw e;
      }
    }
    try {
      return new Wanted(path, name, checksum(path + ".sha1", 40), false);
    } catch (DriverException e) {
      if (e.failure() != NOT_FOUND) {
        throw e;
      }
    }
    // Without either checksum the file itself may be missing, which is NOT_FOUND.
    try {
      remote(path, () -> repository.published(path));
    } catch (DriverException e) {
      if (e.failure() == NOT_FOUND && artifact.optional()) {
        return null;
      }
      throw e;
    }
    throw new DriverException(NO_CHECKSUM, path);
  }

  // Renaming a folder is atomic on Linux and Windows. Linux replaces an empty target and refuses
  // one with files; Windows refuses any target that exists. Either way a version that is there
  // and matches its record has been published by a concurrent download, and that one stands.
  private void publish(Path temporary, Path dir, Driver driver, String version)
      throws IOException, DriverException {
    // Within HooriDB one download at a time checks, discards and replaces a version, so that none
    // discards what another has just put there.
    synchronized (PUBLISHING.computeIfAbsent(dir.toAbsolutePath().normalize(), d -> new Object())) {
      for (int attempt = 0; ; attempt++) {
        try {
          Files.move(temporary, dir, StandardCopyOption.ATOMIC_MOVE);
          return;
        } catch (IOException e) {
          if (complete(driver, version)) {
            return;
          }
          if (attempt > 0 || !Files.exists(dir)) {
            throw e;
          }
          // An earlier download that no longer matches its record, which this one replaces.
          discard(dir);
        }
      }
    }
  }

  /** The versions of the driver in the folder, newest first. */
  public List<String> downloaded(Driver driver) throws DriverException {
    var dir = folder.resolve(driver.group()).resolve(driver.artifact());
    checkCancelled(base(driver));
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

  /** The size in bytes of the version's downloaded files. */
  public long sizeOnDisk(Driver driver, String version) throws DriverException {
    var jar = jar(driver, version);
    checkCancelled(file(driver, version));
    try {
      long size = Files.size(jar);
      for (var part : driver.parts()) {
        var file = jar(driver, part.artifact(), version);
        if (Files.isRegularFile(file)) {
          size += Files.size(file);
        }
      }
      return size;
    } catch (NoSuchFileException e) {
      throw new DriverException(NOT_DOWNLOADED, file(driver, version), e);
    } catch (IOException e) {
      throw storage(e);
    }
  }

  /**
   * Deletes the version from the folder; a download in progress is not disturbed. It fails only
   * while nothing has changed, with {@link DriverException.Failure#STORAGE}: the version stays as
   * it was, downloaded and usable, as a loaded one does on Windows, whose file is open. Once its
   * file is gone the version is gone, and what is left of its folder is removed as far as it can
   * be.
   */
  public void delete(Driver driver, String version) throws DriverException {
    var jar = jar(driver, version);
    checkCancelled(file(driver, version));
    try {
      Files.deleteIfExists(jar);
    } catch (IOException e) {
      throw storage(e);
    }
    // Without its file a folder is not a version, which no one lists or loads, and a download of
    // the version replaces it.
    try {
      discard(jar.getParent());
    } catch (IOException leftOver) {
      // The record beside the file stays, in a folder that holds no version.
    }
  }

  /** Loads the downloaded version in a class loader of its own. */
  public LoadedDriver load(Driver driver, String version) throws DriverException {
    var jar = jar(driver, version);
    var path = file(driver, version);
    checkCancelled(path);
    // shortcut: a folder that cannot be read is reported as not downloaded, as if it were absent;
    // tell the two apart if users meet unreadable data folders.
    if (!Files.isRegularFile(jar)) {
      throw new DriverException(NOT_DOWNLOADED, path);
    }
    // A check against damage and accidental change since the download: whoever can write the
    // files can write their records as well.
    var urls = new ArrayList<URL>();
    for (var artifact : artifacts(driver)) {
      var file = jar(driver, artifact.artifact(), version);
      var at = file(driver, artifact.artifact(), version);
      if (absent(artifact, file)) {
        continue;
      }
      if (!matchesRecord(file)) {
        throw new DriverException(
            Files.isRegularFile(file) ? CHECKSUM_MISMATCH : NOT_DOWNLOADED, at);
      }
      checkManifest(file, at);
      try {
        urls.add(file.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalStateException(e);
      }
    }
    checkCancelled(path);
    // shortcut: the files are hashed and then opened again by the class loader, so a file replaced
    // in between loads unchecked; load from a snapshot in memory if that ever matters, though it
    // does not against whoever can write the data folder, who can replace the record or the app.
    // Its resources are read past Java's cache of jar: files, which keeps a file open beyond the
    // loader's close, as long as the process runs; Windows deletes no file that is open.
    var loader =
        new URLClassLoader(
            driver.artifact() + "-" + version,
            urls.toArray(URL[]::new),
            ClassLoader.getPlatformClassLoader()) {
          @Override
          public URL findResource(String name) {
            return uncached(super.findResource(name));
          }

          @Override
          public Enumeration<URL> findResources(String name) throws IOException {
            return Collections.enumeration(Collections.list(super.findResources(name)).stream()
                .map(DriverStore::uncached)
                .toList());
          }
        };
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

  // Opens a URL as Java does, but without its cache of jar: files.
  private static final URLStreamHandler UNCACHED = new URLStreamHandler() {
    @Override
    protected URLConnection openConnection(URL url) throws IOException {
      URLConnection connection;
      try {
        connection = url.toURI().toURL().openConnection();
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
      connection.setUseCaches(false);
      return connection;
    }
  };

  private static URL uncached(URL url) {
    if (url == null) {
      return null;
    }
    try {
      return URL.of(url.toURI(), UNCACHED);
    } catch (URISyntaxException | MalformedURLException e) {
      return url; // read through the cache, as Java reads it
    }
  }

  // The driver's own artifact first, then its parts.
  private static List<Driver.Part> artifacts(Driver driver) {
    var all = new ArrayList<Driver.Part>();
    all.add(new Driver.Part(driver.artifact(), false));
    all.addAll(driver.parts());
    return all;
  }

  private Path jar(Driver driver, String version) {
    return jar(driver, driver.artifact(), version);
  }

  // shortcut: two versions that differ only in letter case share a folder on a case-insensitive
  // file system; no driver publishes such versions, so they are not told apart.
  private Path jar(Driver driver, String artifact, String version) {
    if (!Maven.isVersion(version)) {
      throw new IllegalArgumentException("Not a version: " + version);
    }
    return folder
        .resolve(driver.group())
        .resolve(driver.artifact())
        .resolve(version)
        .resolve(artifact + "-" + version + ".jar");
  }

  // Whether the version's files are there and match their records, an optional part where it is.
  private boolean complete(Driver driver, String version) throws DriverException {
    for (var artifact : artifacts(driver)) {
      var file = jar(driver, artifact.artifact(), version);
      if (absent(artifact, file)) {
        continue;
      }
      if (!matchesRecord(file)) {
        return false;
      }
    }
    return true;
  }

  // An optional part that the version never had: neither its file nor its record is there. One of
  // them alone is a download damaged since.
  private static boolean absent(Driver.Part artifact, Path file) {
    return artifact.optional() && !Files.exists(file) && !Files.exists(record(file));
  }

  private static Path record(Path jar) {
    return jar.resolveSibling(jar.getFileName() + ".sha256");
  }

  private static String base(Driver driver) {
    return driver.group().replace('.', '/') + "/" + driver.artifact() + "/";
  }

  private static String file(Driver driver, String version) {
    return file(driver, driver.artifact(), version);
  }

  private static String file(Driver driver, String artifact, String version) {
    if (!Maven.isVersion(version)) {
      throw new IllegalArgumentException("Not a version: " + version);
    }
    return driver.group().replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
        + version + ".jar";
  }

  private static void checkCancelled(String path) throws DriverException {
    if (Thread.currentThread().isInterrupted()) {
      throw new DriverException(CANCELLED, path);
    }
  }

  private static boolean matchesRecord(Path jar) throws DriverException {
    if (!Files.isRegularFile(jar)) {
      return false;
    }
    try {
      var recorded = Files.readString(record(jar), StandardCharsets.US_ASCII).strip();
      var sha256 = digest("SHA-256");
      try (var in = Files.newInputStream(jar)) {
        var buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) >= 0; ) {
          checkCancelled(jar.getFileName().toString());
          sha256.update(buffer, 0, n);
        }
      }
      return recorded.equals(HexFormat.of().formatHex(sha256.digest()));
    } catch (NoSuchFileException e) {
      return false;
    } catch (IOException e) {
      throw storage(e);
    }
  }

  // The class loader would also load whatever the manifest's Class-Path names, none of it checked.
  private static void checkManifest(Path jar, String path) throws DriverException {
    try (var file = new JarFile(jar.toFile(), false)) {
      var manifest = file.getManifest();
      var classPath = manifest == null
          ? null
          : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      if (classPath != null && !classPath.isBlank()) {
        throw new DriverException(CLASS_PATH_IN_MANIFEST, path);
      }
    } catch (IOException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw new DriverException(CANCELLED, path, e);
      }
      throw new DriverException(NOT_A_DRIVER, "Not a JAR: " + path, e);
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
      checkCancelled(path);
      int n;
      try {
        n = response.body().read(buffer);
      } catch (IOException e) {
        throw broken(path, e);
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
    if (Thread.currentThread().isInterrupted()) {
      return new DriverException(CANCELLED, path, e);
    }
    if (e instanceof ProtocolException) {
      return new DriverException(INVALID_RESPONSE, path, e);
    }
    boolean answeredBadly = e instanceof Repository.Refused
        || e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException);
    return new DriverException(answeredBadly ? UNAVAILABLE : NO_NETWORK, path, e);
  }

  // Once the repository has answered, a response that breaks off is its failure, not the network's
  // absence.
  private static DriverException broken(String path, IOException e) {
    if (Thread.currentThread().isInterrupted()) {
      return new DriverException(CANCELLED, path, e);
    }
    return e instanceof ProtocolException
        ? new DriverException(INVALID_RESPONSE, path, e)
        : new DriverException(UNAVAILABLE, path, e);
  }

  // Java does not say why a write failed, so a disk with less room than the file is taken as why.
  private boolean full(Path dir, long needed) {
    var existing = dir.toAbsolutePath();
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    try {
      return existing != null && space.usable(existing) < needed;
    } catch (IOException unknown) {
      return false;
    }
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

  // Renames the folder away first, so that no one finds it half deleted.
  private static void discard(Path dir) throws IOException {
    var trash = dir.resolveSibling("." + dir.getFileName() + "." + UUID.randomUUID() + ".deleted");
    try {
      Files.move(dir, trash, StandardCopyOption.ATOMIC_MOVE);
    } catch (NoSuchFileException e) {
      return;
    }
    deleteTree(trash);
  }

  private static void deleteTree(Path dir) {
    if (dir == null) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | UncheckedIOException e) {
      // What is left keeps a name that starts with a dot and is never listed or loaded.
    }
  }
}

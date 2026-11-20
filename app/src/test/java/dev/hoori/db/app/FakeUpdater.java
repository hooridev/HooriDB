package dev.hoori.db.app;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import com.rethinkj.updater.Version;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Made-up releases of HooriDB and a helper that does what it is told: its result is given, and
 * what it downloads and restarts with is recorded. Its downloads go into a folder, and can fail or
 * be held at a gate.
 */
final class FakeUpdater implements Updater {

  final Path downloads;
  final List<Release> releases = new CopyOnWriteArrayList<>();
  volatile Installation.Result result; // what the next start reads, once
  volatile boolean writable = true;
  volatile boolean offline;
  volatile IOException failDownload;
  volatile IOException failRestart;
  // Holds each download before it writes its file, until it opens.
  volatile CountDownLatch downloading;
  final List<String> downloaded = new CopyOnWriteArrayList<>(); // the versions, in order
  final List<Download> restarts = new CopyOnWriteArrayList<>();
  final List<Path> cleared = new CopyOnWriteArrayList<>(); // what each clear kept, or the folder
  volatile int confirms;

  FakeUpdater(Path downloads) {
    this.downloads = downloads;
  }

  /** A release with an archive for each platform. */
  static Release release(String version) {
    var page = URI.create("https://github.com/hooridev/HooriDB/releases/tag/v" + version);
    var base =
        "https://github.com/hooridev/HooriDB/releases/download/v" + version + "/HooriDB-" + version;
    return new Release(
        Version.parse(version),
        "v" + version,
        "HooriDB " + version,
        Instant.parse("2026-11-01T00:00:00Z"),
        "",
        false,
        page,
        List.of(
            new Asset(
                "HooriDB-" + version + "-linux-x64.tar.gz",
                URI.create(base + "-linux-x64.tar.gz"),
                1,
                ""),
            new Asset(
                "HooriDB-" + version + "-windows-x64.zip",
                URI.create(base + "-windows-x64.zip"),
                1,
                "")));
  }

  FakeUpdater publish(String... versions) {
    for (var version : versions) {
      releases.add(release(version));
    }
    return this;
  }

  @Override
  public Optional<Installation.Result> result() {
    var last = result;
    result = null;
    return Optional.ofNullable(last);
  }

  @Override
  public void confirm() {
    confirms++;
  }

  @Override
  public boolean writable() {
    return writable;
  }

  @Override
  public List<Release> releases() throws IOException {
    if (offline) {
      throw new ConnectException("offline");
    }
    return List.copyOf(releases);
  }

  @Override
  public Download download(Release release, Asset asset) throws IOException {
    var gate = downloading;
    if (gate != null) {
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }
    if (failDownload != null) {
      throw failDownload;
    }
    downloaded.add(release.version().toString());
    var file = Files.createDirectories(downloads).resolve("download-" + asset.name());
    Files.writeString(file, release.version().toString());
    return new Download(file, "0".repeat(64));
  }

  @Override
  public void restart(Download download) throws IOException {
    if (failRestart != null) {
      throw failRestart;
    }
    restarts.add(download);
  }

  @Override
  public void clear(Path keep) {
    cleared.add(keep == null ? downloads : keep);
  }

  @Override
  public Path app() {
    return Path.of("/opt/HooriDB");
  }
}

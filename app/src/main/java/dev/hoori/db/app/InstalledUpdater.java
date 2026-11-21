package dev.hoori.db.app;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Downloader;
import com.rethinkj.updater.GitHubSource;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@link Updater} of an app image, with {@link Installation}, {@link GitHubSource} and {@link
 * Downloader}; the last two are made at their first use, off the app's thread, as each makes an
 * HTTP client.
 */
final class InstalledUpdater implements Updater {
  private final Installation installation;
  private final String api;
  private final Path downloads;
  private GitHubSource source; // guarded by this
  private Downloader downloader; // guarded by this

  InstalledUpdater(Installation installation, String api) {
    this.installation = installation;
    this.api = api;
    this.downloads = installation.updates().resolve("downloads");
  }

  @Override
  public Optional<Installation.Result> result() throws IOException {
    return installation.result();
  }

  @Override
  public void confirm() throws IOException {
    installation.confirm();
  }

  @Override
  public boolean writable() {
    return installation.writable();
  }

  @Override
  public List<Release> releases() throws IOException {
    return source().releases();
  }

  private synchronized GitHubSource source() {
    if (source == null) {
      // A bad address fails the check, which shows nothing, as a source that is not there.
      source = api == null
          ? new GitHubSource(REPOSITORY)
          : new GitHubSource(URI.create(api), REPOSITORY, null);
    }
    return source;
  }

  @Override
  public Download download(Release release, Asset asset) throws IOException {
    var from = downloader();
    clear(null);
    return from.download(release, asset, Files.createDirectories(downloads), bytes -> {});
  }

  private synchronized Downloader downloader() {
    if (downloader == null) {
      downloader = new Downloader();
    }
    return downloader;
  }

  @Override
  public void restart(Download download) throws IOException {
    installation.restart(download);
  }

  @Override
  public void clear(Path keep) {
    try (var files = Files.newDirectoryStream(downloads)) {
      for (var file : files) {
        if (!file.equals(keep)) {
          delete(file);
        }
      }
    } catch (IOException | DirectoryIteratorException _) {
      // What is left goes at the next try; also when there is no such folder yet.
    }
  }

  /** Deletes the file where it can: one in use goes at the next try. */
  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException _) {
      // In use: it goes at the next try.
    }
  }

  @Override
  public Path app() {
    return installation.app();
  }
}

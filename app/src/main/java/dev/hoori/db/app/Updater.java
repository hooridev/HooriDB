package dev.hoori.db.app;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Downloader;
import com.rethinkj.updater.GitHubSource;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Updates HooriDB from its GitHub releases, with rethinkj Updater. Every call but {@link #app()}
 * blocks, off the app's thread. The app gets it from outside, so that a test can put made-up
 * releases in its place; it has none where it does not run from its app image.
 */
interface Updater {

  String REPOSITORY = "hooridev/HooriDB";

  /** The archive of a release for the platform this runs on. */
  Pattern ASSET = Pattern.compile(
      File.separatorChar == '\\'
          ? "HooriDB-.*-windows-x64\\.zip"
          : "HooriDB-.*-linux-x64\\.tar\\.gz");

  /** What the helper reported about the last update, once; empty if nothing. */
  Optional<Installation.Result> result() throws IOException;

  /** Tells a helper waiting for it that this version runs. */
  void confirm() throws IOException;

  /** Whether this user may replace the app's folder. */
  boolean writable();

  /** The published releases. */
  List<Release> releases() throws IOException;

  /** Downloads the asset into the downloads folder, deleting what is there first, as it can. */
  Download download(Release release, Asset asset) throws IOException;

  /** Starts the helper, which replaces HooriDB once it has exited and starts it again. */
  void restart(Download download) throws IOException;

  /** Deletes the files in the downloads folder but {@code keep}, as far as it can. */
  void clear(Path keep);

  /** The app's folder, which the helper replaces. */
  Path app();

  /**
   * The updater of the installation, whose releases come from GitHub, or from the GitHub-compatible
   * REST API at {@code api} where it is not null.
   */
  static Updater of(Installation installation, String api) {
    return new Installed(installation, api);
  }

  /**
   * With {@link Installation}, {@link GitHubSource} and {@link Downloader}; the last two are made
   * at their first use, off the app's thread, as each makes an HTTP client.
   */
  final class Installed implements Updater {
    private final Installation installation;
    private final String api;
    private final Path downloads;
    private GitHubSource source; // guarded by this
    private Downloader downloader; // guarded by this

    Installed(Installation installation, String api) {
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
      GitHubSource source;
      synchronized (this) {
        if (this.source == null) {
          // A bad address fails the check, which shows nothing, as a source that is not there.
          this.source = api == null
              ? new GitHubSource(REPOSITORY)
              : new GitHubSource(URI.create(api), REPOSITORY, null);
        }
        source = this.source;
      }
      return source.releases();
    }

    @Override
    public Download download(Release release, Asset asset) throws IOException {
      Downloader downloader;
      synchronized (this) {
        if (this.downloader == null) {
          this.downloader = new Downloader();
        }
        downloader = this.downloader;
      }
      clear(null);
      return downloader.download(release, asset, Files.createDirectories(downloads), bytes -> {});
    }

    @Override
    public void restart(Download download) throws IOException {
      installation.restart(download);
    }

    @Override
    public void clear(Path keep) {
      try (var files = Files.newDirectoryStream(downloads)) {
        for (var file : files) {
          try {
            if (!file.equals(keep)) {
              Files.deleteIfExists(file);
            }
          } catch (IOException inUse) {
            // Goes at the next try.
          }
        }
      } catch (IOException | DirectoryIteratorException e) {
        // What is left goes at the next try; also when there is no such folder yet.
      }
    }

    @Override
    public Path app() {
      return installation.app();
    }
  }
}

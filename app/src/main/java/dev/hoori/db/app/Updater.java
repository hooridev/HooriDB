package dev.hoori.db.app;

import com.rethinkj.updater.Asset;
import com.rethinkj.updater.Download;
import com.rethinkj.updater.Installation;
import com.rethinkj.updater.Release;
import java.io.File;
import java.io.IOException;
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
    return new InstalledUpdater(installation, api);
  }
}

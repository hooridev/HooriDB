package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.SystemDialogs;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowIcon;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.updater.Installation;
import dev.hoori.db.core.AppFolders;
import dev.hoori.db.core.DesktopEntry;
import dev.hoori.db.core.driver.MavenRepository;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** The app's name and color, and its window. */
public final class HooriDb {

  static final String NAME = "HooriDB";

  /** The app's own color, for its icon on the window and in the title bar. */
  static final int COLOR = Colors.rgb(0x0F766E);

  /** The icon of the app's windows. */
  static final WindowIcon ICON = WindowIcon.of(
      size -> Theme.CURRENT.with(Theme.DARK, () -> AppIcon.appIcon(size, COLOR, Symbol.DATABASE)));

  private HooriDb() {}

  /**
   * Who may use a script's temporary folder, in the shared temporary folder: its owner alone, where
   * the file system has POSIX permissions; on Windows the user's own temporary folder is private.
   */
  private static FileAttribute<?>[] ownerOnly() {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[0];
    }
    return new FileAttribute<?>[] {
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    };
  }

  /** The version that runs, as its JAR's manifest names it; a build of its own has none. */
  static String version() {
    String version = HooriDb.class.getPackage().getImplementationVersion();
    return version == null ? "Development build" : version;
  }

  /** The app without drivers, as in a script run: no connection can be tested. */
  static App<State> app(Path folder, Callable<Keyring> keyring) {
    return app(folder, keyring, null, Tester.SERVER);
  }

  /**
   * The app over a new state, with the text rendering that a script's images are made with. It
   * starts loading the connections and looking up the keyring.
   *
   * @param folder where the connections are kept
   * @param keyring finds the system keyring, off the app's thread
   * @param drivers where drivers are kept and downloaded from, or null for none
   * @param tester tests a connection, off the app's thread
   */
  static App<State> app(Path folder, Callable<Keyring> keyring, Drivers drivers, Tester tester) {
    return app(folder, keyring, drivers, tester, Servers.SERVER);
  }

  /**
   * The app as {@link #app(Path, Callable, Drivers, Tester)}, whose connections go to these
   * servers.
   */
  static App<State> app(
      Path folder, Callable<Keyring> keyring, Drivers drivers, Tester tester, Servers servers) {
    return app(folder, keyring, drivers, tester, servers, null, version());
  }

  /** The app without drivers, which updates itself with this updater as this version. */
  static App<State> app(Path folder, Callable<Keyring> keyring, Updater updater, String version) {
    return app(folder, keyring, null, Tester.SERVER, Servers.SERVER, updater, version);
  }

  /**
   * The app as {@link #app(Path, Callable, Drivers, Tester, Servers)}, which updates itself.
   *
   * @param updater updates HooriDB, or null for no updates
   * @param version the version that runs, as {@link #version()} names it
   */
  static App<State> app(
      Path folder,
      Callable<Keyring> keyring,
      Drivers drivers,
      Tester tester,
      Servers servers,
      Updater updater,
      String version) {
    var state = new State(folder, keyring, drivers, tester, servers, updater, version);
    App<State> app = new App<>(state, Frame::view).provide(Theme.CURRENT, s -> s.theme);
    app.state().app = app;
    // A script's images must not depend on the platform they were made on.
    app.textRendering(TextRendering.LINUX);
    app.state().start();
    return app;
  }

  /**
   * Opens the window, or with {@code --script <file> --out <folder>} runs the script without one
   * and writes its images and element trees. A script never reaches the user's connections or
   * keyring: it has a new temporary folder, or the one {@code --config <folder>} names, no
   * keyring, no drivers, so that it never downloads one, and no updates, so that it never asks
   * the network for releases.
   */
  public static void main(String[] args) throws IOException {
    var rest = new ArrayList<>(List.of(args));
    if (rest.contains("--script")) {
      int at = rest.indexOf("--config");
      Path folder;
      if (at >= 0 && at + 1 < rest.size()) {
        folder = Path.of(rest.remove(at + 1));
        rest.remove(at);
      } else {
        folder = Files.createTempDirectory("hooridb-script-", ownerOnly());
      }
      var none = new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a script run");
      Script.handle(rest.toArray(String[]::new), app(folder, () -> none));
      return;
    }
    // The GPU device on a thread of its own from here, not only from Window.open, so that it
    // overlaps with the rest of the start; a script, which draws without a window, needs none.
    GpuPresenter.prepare();
    var folders = AppFolders.find();
    var central = MavenRepository.mavenCentral();
    var drivers = new Drivers(
        folders.data().resolve("drivers"), central, "Maven Central", MavenRepository.CENTRAL);
    // Only from the app image, whose launcher the helper starts again: never from Maven or a
    // script.
    var updater = Installation.launcher(folders.data().resolve("updates"))
        .map(installation -> Updater.of(installation, System.getProperty("hooridb.releases")))
        .orElse(null);
    App<State> app = app(
        folders.config(),
        Keyring::find,
        drivers,
        Tester.SERVER,
        Servers.SERVER,
        updater,
        version());
    app.textRendering(TextRendering.platform());
    // The menu's entry and the dock's icon of an unpacked app image; without them it still runs.
    var os = System.getProperty("os.name");
    var launcher = System.getProperty("jpackage.app-path");
    app.background(
        "write the desktop entry",
        () -> DesktopEntry.write(os, launcher, folders),
        wrote -> {},
        failed -> {});
    // Shows what goes wrong before there is a window to show it in, such as no usable GPU.
    SystemDialogs.reportFailure(NAME, () -> {
      Window.identity(DesktopEntry.ID, NAME);
      Window.Options options = Window.options().icon(ICON).frameless().minimumSize(640, 400);
      try (Window window = Window.open(NAME, 1200, 760, options);
          GpuPresenter presenter = new GpuPresenter(window)) {
        var loop = new WindowLoop(window, app, presenter);
        // Not at the start: a version whose window cannot show is put back by the helper.
        loop.windows().of(app).onShown(app.state()::shown);
        loop.run();
      }
    });
  }
}

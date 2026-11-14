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
import dev.hoori.db.core.AppFolders;
import dev.hoori.db.core.driver.MavenRepository;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** The app's name and colour, and its window. */
public final class HooriDb {

  static final String NAME = "HooriDB";

  /** The app's own colour, for its icon and its mark in the title bar. */
  static final int COLOUR = Colors.rgb(0x0F766E);

  private HooriDb() {}

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
    App<State> app = new App<>(new State(folder, keyring, drivers, tester, servers), Frame::view);
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
   * keyring, and no drivers, so that it never downloads one.
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
        folder = Files.createTempDirectory("hooridb-script-");
      }
      var none = new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a script run");
      Script.handle(rest.toArray(String[]::new), app(folder, () -> none));
      return;
    }
    var folders = AppFolders.find();
    var central = MavenRepository.central();
    var drivers = new Drivers(
        folders.data().resolve("drivers"), central, "Maven Central", MavenRepository.CENTRAL);
    App<State> app = app(folders.config(), Keyring::find, drivers, Tester.SERVER);
    app.textRendering(TextRendering.platform());
    // Shows what goes wrong before there is a window to show it in, such as no usable GPU.
    SystemDialogs.reportFailure(NAME, () -> {
      Window.identity("dev.hoori.db", NAME);
      Window.Options options = Window.options()
          .icon(WindowIcon.of(size -> AppIcon.appIcon(Theme.DARK, size, COLOUR, Symbol.DATABASE)))
          .frameless()
          .minimumSize(640, 400);
      try (Window window = Window.open(NAME, 1200, 760, options);
          GpuPresenter presenter = new GpuPresenter(window)) {
        WindowLoop.run(window, app, presenter);
      }
    });
  }
}

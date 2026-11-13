package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
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
import java.io.IOException;

/** The app: its state, its view and its window. */
public final class HooriDb {

  static final String NAME = "HooriDB";

  /** The app's own colour, for its icon and its mark in the title bar. */
  static final int COLOUR = Colors.rgb(0x0F766E);

  App<HooriDb> app; // whose window says whether it is maximized

  /**
   * Opens the window, or with {@code --script <file> --out <folder>} runs the script without one
   * and writes its images and element trees.
   */
  public static void main(String[] args) throws IOException {
    App<HooriDb> app = new App<>(new HooriDb(), HooriDb::view);
    app.state().app = app;
    // A script's images must not depend on the platform they were made on.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
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

  static El view(HooriDb s) {
    Theme t = Theme.DARK;
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .child(titleBar(t, NAME, COLOUR, s.app != null && s.app.windowMaximized()))
        .child(col()
            .grow(1)
            .center()
            .child(emptyState(
                t,
                Symbol.DATABASE,
                "No connections yet",
                "Connections to your databases will be listed here.",
                null)));
  }
}

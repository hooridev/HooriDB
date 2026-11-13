package dev.hoori.db.app;

import com.rethinkj.ui.App;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Windows;
import java.util.List;

/** The app's state, which only the handlers change, on the app's thread. */
final class State {

  static final Split.Position SIDEBAR = Split.Position.first(260);
  static final double MESSAGE_SECONDS = 5; // how long a message stays in the status line

  App<State> app; // runs the clock and the windows, and says whether the window is maximized
  Theme theme = Theme.DARK;
  Split.Position sidebar = SIDEBAR;
  final MenuBar.State bar = new MenuBar.State();
  // shortcut: nothing opens a tab yet, so none can be torn off; give the opener a window of its
  // own when the first kind of tab comes.
  final TabGroups<Object> tabs = new TabGroups<>(List.of(), (group, options) -> {
    throw new UnsupportedOperationException("No tab can be torn off yet");
  });
  String message; // in the status line for a while, or null
  private int messages; // told so far

  /** Switches to a theme and says so in the status line. */
  void theme(Theme theme, String name) {
    this.theme = theme;
    tell(name + " on");
  }

  /** Shows a message in the status line for {@link #MESSAGE_SECONDS}, on the app's clock. */
  void tell(String text) {
    message = text;
    int told = ++messages;
    app.after(MESSAGE_SECONDS, () -> {
      if (messages == told) { // not replaced meanwhile
        message = null;
      }
    });
  }

  /** Closes every window, which ends the app. */
  void quit() {
    for (Windows.Handle window : app.windows().all()) {
      window.close();
    }
  }
}

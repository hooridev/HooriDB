package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.TitleBar.withMenus;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import java.util.List;
import java.util.function.Predicate;

/** The main window: menus in the title bar, the catalogue beside the tabs, the status line. */
final class Frame {

  private Frame() {}

  static El view(State s) {
    Theme t = s.theme;
    List<Menu.Item> menus = menus(s);
    El catalogue = Card.flush(
            t,
            Card.toolbar(t, "Connections", null),
            emptyState(
                t,
                Symbol.DATABASE,
                "No connections yet",
                "Connections to your databases will be listed here.",
                null))
        .key("catalogue")
        .label("Catalogue");
    El tabs = s.tabs
        .tabs(
            t,
            s.tabs.main(),
            tab -> new Tabs.Tab(tab.toString()),
            tab -> tab == null
                ? emptyState(
                    t, Symbol.TABLE, "No tabs open", "Tables and queries you open show here.", null)
                : null)
        .key("tabs");
    // The menus' keys, and the tabs' keys also while the focus is outside them; none while empty.
    Predicate<Key> keys = MenuBar.keys(s.bar, menus);
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(tabs.onKey() == null ? keys : keys.or(tabs.onKey()))
        .onKeyUp(MenuBar.keysUp(s.bar, menus))
        .child(withMenus(
            t,
            HooriDb.NAME,
            HooriDb.COLOUR,
            MenuBar.menuBar(t, s.bar, menus),
            s.app != null && s.app.windowMaximized()))
        .child(Split.cards(
                t,
                Split.Direction.ROW,
                s.sidebar,
                State.SIDEBAR,
                p -> s.sidebar = p,
                catalogue,
                160,
                tabs,
                320)
            .grow(1)
            .padding(0, t.space().m(), 0, t.space().m()))
        .child(StatusBar.statusBar(
            t,
            s.message == null ? List.of() : List.of(StatusBar.text(t, s.message).key("message")),
            List.of()));
  }

  /** Only what does something today: quitting, and the themes. */
  static List<Menu.Item> menus(State s) {
    return List.of(
        Menu.submenu("&File", List.of(Menu.item("Exit", s::quit))),
        Menu.submenu(
            "&View",
            List.of(
                themeItem(s, Theme.DARK, "Dark theme"), themeItem(s, Theme.LIGHT, "Light theme"))));
  }

  private static Menu.Item themeItem(State s, Theme theme, String name) {
    return Menu.item(name, () -> s.theme(theme, name)).checked(s.theme == theme);
  }
}

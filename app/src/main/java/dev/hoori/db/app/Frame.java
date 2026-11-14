package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TitleBar.withMenus;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.TitleBar;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.catalog.Table;
import java.util.List;
import java.util.function.Predicate;

/** The main window: menus in the title bar, the catalogue beside the tabs, the status line. */
final class Frame {

  private Frame() {}

  static El view(State s) {
    Theme t = s.theme;
    var main = s.tabs.main();
    // One dialog at a time: what the catalogue asks waits for the others to close.
    El asked = s.dialog == null && s.deleting == null ? s.catalogue.dialog(t, s, main) : null;
    boolean modal = s.dialog != null || s.deleting != null || asked != null || s.driverList != null;
    List<Menu.Item> menus = menus(s, main, modal);
    El catalogue = catalogue(t, s).key("catalogue").label("Catalogue");
    El tabs = tabs(s, main, s.app).key("tabs");
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(keys(MenuBar.keys(s.bar, menus), s.palette(main), modal, tabs))
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
    // The window closes once what was begun has ended and the connections are written.
    Windows.Handle window = s.app == null ? null : s.app.windows().of(s.app);
    if (window != null) {
      window.onCloseRequest(s::requestClose);
    }
    if (s.dialog != null) {
      root.overlay(s.dialog.view(t, s)).overlay(s.dialog.question(t, s));
    }
    root.overlay(s.deleting == null ? null : deleteDialog(t, s));
    root.overlay(asked);
    if (s.driverList != null && s.dialog == null && s.deleting == null && asked == null) {
      root.overlay(s.driverList.view(t, s)).overlay(s.driverList.question(t, s));
    }
    return root.overlay(modal ? null : palette(s, main, menus));
  }

  /**
   * A window's keys: its menus' combinations, the palette's own while no dialog is open, and the
   * tabs' also while the focus is outside them, as in the tree; none of the tabs' while empty.
   */
  private static Predicate<Key> keys(
      Predicate<Key> menus, CommandPalette.State palette, boolean modal, El tabs) {
    Predicate<Key> keys = modal ? menus : menus.or(CommandPalette.keys(palette));
    return tabs.onKey() == null ? keys : keys.or(tabs.onKey());
  }

  /** The window's command palette, over the commands of its menus and its tables after @. */
  private static El palette(State s, TabGroups.Group<Tab> group, List<Menu.Item> menus) {
    return CommandPalette.palette(
        s.theme, s.palette(group), CommandPalette.commands(menus), List.of(GoTo.source(s, group)));
  }

  /** The tabs of a window, each with its content; the main window's empty one says what goes in. */
  private static El tabs(State s, TabGroups.Group<Tab> group, App<State> app) {
    Theme t = s.theme;
    return s.tabs.tabs(
        t,
        group,
        tab -> switch (tab) {
          case TableTab x ->
            new Tabs.Tab(s.title(x))
                .icon(x.table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE)
                .hue(Colours.hue(s.colour(x.id)));
          case ConsoleTab x ->
            new Tabs.Tab(s.title(x)).icon(Symbol.CODE).hue(Colours.hue(s.colour(x.id)));
        },
        tab -> switch (tab) {
          case null ->
            group == s.tabs.main()
                ? emptyState(
                    t,
                    Symbol.TABLE,
                    "No tabs open",
                    "Open a table in the tree, with a double press or Enter, to see its rows, or"
                        + " a console from a connection's menu to run statements.",
                    null)
                : null;
          case TableTab x -> x.view(t, s, app);
          case ConsoleTab x -> x.view(t, s, app);
        });
  }

  /** What a window of tabs is called: its active tab's title. */
  static String title(State s, Tab tab) {
    return switch (tab) {
      case null -> HooriDb.NAME;
      case TableTab x -> s.title(x);
      case ConsoleTab x -> s.title(x);
    };
  }

  /** The window of tabs torn off: its title bar over its tabs, whose status lines they show. */
  static El window(State s, TabGroups.Group<Tab> group) {
    Theme t = s.theme;
    App<State> app = app(s, group);
    El tabs = tabs(s, group, app).key("tabs");
    // What a console of this window asked for, such as a password.
    El asked = s.catalogue.dialog(t, s, group);
    // The menus' commands without the bar, acting on this window's tabs.
    List<Menu.Item> menus = menus(s, group, asked != null);
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(keys(Menu.shortcuts(menus), s.palette(group), asked != null, tabs))
        .child(TitleBar.titleBar(
            t, title(s, group.activeTab()), HooriDb.COLOUR, app != null && app.windowMaximized()))
        .child(col()
            .grow(1)
            .minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(Card.flush(t, tabs.grow(1)).grow(1)))
        .overlay(asked)
        .overlay(asked != null ? null : palette(s, group, menus));
  }

  /** The app of the window of these tabs. */
  @SuppressWarnings("unchecked") // every window runs an app of the state
  private static App<State> app(State s, TabGroups.Group<Tab> group) {
    return group.window() == null ? s.app : (App<State>) group.window().app();
  }

  /** The connections: waiting while they load, then a tree of them or the empty state. */
  private static El catalogue(Theme t, State s) {
    Runnable create = s.canChange() ? s::newConnection : null;
    El problem = null;
    if (s.loadError != null) {
      // A file that could not be read for another reason is still there: nothing may replace it.
      problem = Callout.callout(
          t,
          Callout.Kind.DANGER,
          "Connections could not be read",
          s.loadError,
          s.canChange() ? new El[0] : new El[] {tryAgain(t, s::reload)});
    } else if (s.saveError != null) {
      problem = Callout.callout(
          t,
          Callout.Kind.DANGER,
          "Connections could not be saved",
          s.saveError,
          tryAgain(t, s::persist));
    }
    El body;
    if (!s.loaded) {
      body = col()
          .role("status")
          .label("Loading connections")
          .grow(1)
          .center()
          .gap(t.space().m())
          .child(Progress.spinner(t))
          .child(label(t, Label.Variant.MUTED, "Loading connections…"));
    } else if (!s.profiles.isEmpty()) {
      var c = s.catalogue;
      El tree = Tree.tree(
              t,
              new Catalogue.Model(t, s),
              c.open::contains,
              s.selected,
              node -> s.selected = node,
              (node, open) -> c.toggle(s, node, open),
              node -> c.activate(s, node))
          .key("connections")
          .label("Connections");
      body = Card.inset(t, Menu.contextMenu(t, tree, c.menu, c.items(s, s.selected)));
      El callout = c.callout(t, s);
      if (callout != null) {
        body = col()
            .grow(1)
            .child(body)
            .child(col().padding(0, t.space().s(), t.space().s(), t.space().s()).child(callout));
      }
    } else if (s.loadError == null) {
      body = emptyState(
          t,
          Symbol.DATABASE,
          "No connections yet",
          "Connections to your databases will be listed here.",
          button(t, Button.Variant.PRIMARY, Symbol.ADD, "New connection", create));
    } else {
      body = null;
    }
    return Card.flush(
        t,
        Card.toolbar(
            t,
            "Connections",
            null,
            Button.icon(t, Button.Variant.GHOST, Symbol.ADD, "Add connection", create)),
        problem == null ? null : col().padding(t.space().s()).child(problem),
        body);
  }

  private static El tryAgain(Theme t, Runnable action) {
    return button(t, Button.Variant.SECONDARY, "Try again", action);
  }

  private static El deleteDialog(Theme t, State s) {
    El delete = button(
        t,
        Button.Variant.PRIMARY,
        "Delete",
        s::delete,
        Button.options().tone(Button.Tone.DANGER).busy(s.removing));
    El content = col()
        .gap(t.space().m())
        .child(label(t, "Delete the connection " + State.name(s.deleting) + "?"))
        .child(
            s.tables(s.deleting.id()).isEmpty() && s.consoles(s.deleting.id()).isEmpty()
                ? null
                : label(t, Label.Variant.MUTED, "Its open tabs close."))
        .child(label(
            t,
            Label.Variant.MUTED,
            "If its password is saved in the system keyring, it is deleted there too."));
    if (s.removeError != null) {
      content.child(Callout.callout(
          t, Callout.Kind.DANGER, "The password could not be deleted", s.removeError));
    }
    return Dialog.dialog(
        t,
        "Delete connection",
        content,
        List.of(
            button(t, Button.Variant.SECONDARY, "Cancel", s.removing ? null : s::cancelDelete),
            delete),
        s::cancelDelete);
  }

  /**
   * Only what does something today: a new connection, a console, the drivers, the tabs of the
   * window, quitting, the palette, the themes, and where the focus goes. They are the commands of
   * the palette too, and their keys act in the window of these tabs, with or without its bar.
   *
   * @param modal whether a dialog is open, which the palette waits for
   */
  static List<Menu.Item> menus(State s, TabGroups.Group<Tab> group, boolean modal) {
    var palette = s.palette(group);
    var console = s.consoleFor(group);
    Runnable close = group.activeTab() == null ? null : () -> s.closeTab(group);
    boolean several = group.tabs().size() > 1;
    boolean tree = group == s.tabs.main() && s.loaded && !s.profiles.isEmpty();
    return List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New connection…", s.canChange() ? s::newConnection : null)
                    .icon(Symbol.ADD),
                Menu.item(
                        "New console", console == null ? null : () -> s.openConsole(console, group))
                    .icon(Symbol.CODE)
                    .shortcut(Key.CONTROL, "N"),
                Menu.item(
                    "Drivers…",
                    s.drivers == null
                            || s.dialog != null
                            || s.deleting != null
                            || s.driverList != null
                        ? null
                        : () -> s.driverList = DriversDialog.open(s)),
                Menu.separator(),
                Menu.item("Close tab", close).icon(Symbol.DISMISS).shortcut(Key.CONTROL, "W"),
                Menu.separator(),
                Menu.item("Exit", s::quit))),
        Menu.submenu(
            "&View",
            List.of(
                Menu.item("Command palette…", modal ? null : palette::open)
                    .icon(Symbol.SEARCH)
                    .shortcut(Key.CONTROL | Key.SHIFT, "P"),
                Menu.separator(),
                themeItem(s, Theme.DARK, "Dark theme"),
                themeItem(s, Theme.LIGHT, "Light theme"))),
        Menu.submenu(
            "&Go",
            List.of(
                Menu.item("Go to table…", modal ? null : () -> palette.open(GoTo.PREFIX))
                    .icon(Symbol.TABLE)
                    .shortcut(Key.CONTROL, "P"),
                Menu.separator(),
                // Control+Tab and Control+Shift+Tab do the same, through the kit's tabs.
                Menu.item("Next tab", several ? () -> s.nextTab(group, 1) : null)
                    .shortcut(Key.CONTROL, "PageDown"),
                Menu.item("Previous tab", several ? () -> s.nextTab(group, -1) : null)
                    .shortcut(Key.CONTROL, "PageUp"),
                Menu.separator(),
                Menu.item("Connections", tree ? () -> focusTree(s) : null)
                    .shortcut(Key.CONTROL, "0"),
                Menu.item("Tab content", close == null ? null : () -> focusContent(s, group))
                    .shortcut(Key.CONTROL, "1"))));
  }

  /** Gives the focus to the tree of connections. */
  private static void focusTree(State s) {
    El tree = find(s.app.tree(), e -> "connections".equals(e.key()) && e.isFocusable());
    if (tree != null) {
      s.app.focus(tree);
    }
  }

  /**
   * Gives the focus to the content of the window's active tab: its editor or grid, else the first
   * element there that takes it.
   */
  // shortcut: not back to where the focus was in the content, as the kit cannot focus a region
  // that way; upgrade when it can.
  private static void focusContent(State s, TabGroups.Group<Tab> group) {
    var app = app(s, group);
    El panel = find(app.tree(), e -> "tabpanel".equals(e.role()));
    if (panel == null) {
      return;
    }
    El main = find(panel, e -> "sql".equals(e.key()) || "grid".equals(e.key()));
    El target = main == null ? null : find(main, El::isFocusable);
    if (target == null) {
      target = find(panel, El::isFocusable);
    }
    if (target != null) {
      app.focus(target);
    }
  }

  /** The first element at or below this one, in tree order, that passes the test; or null. */
  private static El find(El el, Predicate<El> test) {
    if (el == null || test.test(el)) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, test);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static Menu.Item themeItem(State s, Theme theme, String name) {
    return Menu.item(name, () -> s.theme(theme, name)).checked(s.theme == theme);
  }
}

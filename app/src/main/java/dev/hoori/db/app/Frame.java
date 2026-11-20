package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Properties;
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
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** The main window: menus in the title bar, the catalog beside the tabs, the status line. */
final class Frame {

  private Frame() {}

  static El view(State s) {
    Theme t = Theme.CURRENT.get();
    var main = s.tabs.main();
    // One dialog at a time: what the catalog asks waits for the others to close.
    El asked =
        s.dialog == null && s.deleting == null && !s.about ? s.catalog.dialog(s, main) : null;
    El question = question(s, main);
    El confirmation = s.dialog == null
            && s.deleting == null
            && !s.about
            && asked == null
            && s.driverList == null
            && question == null
        ? confirmation(s, main)
        : null;
    boolean modal = s.dialog != null
        || s.deleting != null
        || s.about
        || asked != null
        || s.driverList != null
        || question != null
        || confirmation != null;
    closePalette(s, main, modal);
    List<Menu.Item> menus = menus(s, main, modal);
    El catalog = catalog(s).key("catalog").label("Catalog");
    El tabs = tabs(s, main, s.app).key("tabs");
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(keys(MenuBar.keys(s.bar, menus), s.bar, s.palette(main), modal, tabs))
        .onKeyUp(MenuBar.keysUp(s.bar, menus))
        .child(TitleBar.titleBar(
            icon(), HooriDb.NAME, List.of(MenuBar.menuBar(s.bar, menus)), null, List.of()))
        .child(Split.cards(
                Split.Direction.ROW,
                s.sidebar,
                State.SIDEBAR,
                p -> s.sidebar = p,
                catalog,
                160,
                tabs,
                320)
            .grow(1)
            .padding(0, t.space().m(), t.space().m(), t.space().m()))
        .child(StatusBar.statusBar(
            Arrays.asList(connections(s), uncommitted(s, modal)),
            s.message == null ? null : StatusBar.text(s.message).key("message"),
            List.of(StatusBar.item(null, HooriDb.version(), modal ? null : () -> s.about = true)
                .key("version"))));
    // The window closes once what was begun has ended and the connections are written.
    Windows.Handle window = s.app == null ? null : s.app.windows().of(s.app);
    if (window != null) {
      window.onCloseRequest(s::requestClose);
    }
    if (s.dialog != null) {
      root.overlay(s.dialog.view(s)).overlay(s.dialog.question(s));
    }
    root.overlay(s.deleting == null ? null : deleteDialog(s));
    root.overlay(s.about ? aboutDialog(s) : null);
    root.overlay(asked);
    root.overlay(question);
    root.overlay(confirmation);
    root.overlay(confirmation == null ? null : historyQuestion(s, main));
    if (s.driverList != null
        && s.dialog == null
        && s.deleting == null
        && !s.about
        && asked == null) {
      root.overlay(s.driverList.view(s)).overlay(s.driverList.question(s));
    }
    return root.overlay(modal ? null : palette(s, main, menus));
  }

  /**
   * A window's keys: the tabs' first, also while the focus is outside them, as in the tree, so that
   * they go through the tabs as inside them, closing the menu open in the bar; then its menus'
   * combinations, and the palette's own while no dialog is open. None of the tabs' while empty.
   *
   * @param bar the window's menu bar, null for a window without one
   */
  private static Predicate<Key> keys(
      Predicate<Key> menus,
      MenuBar.State bar,
      CommandPalette.State palette,
      boolean modal,
      El tabs) {
    Predicate<Key> keys = modal ? menus : menus.or(CommandPalette.keys(palette));
    Predicate<Key> ofTabs = tabs.onKey();
    if (ofTabs == null) {
      return keys;
    }
    return ((Predicate<Key>) k -> {
          if (!ofTabs.test(k)) {
            return false;
          }
          if (bar != null) {
            bar.close();
          }
          return true;
        })
        .or(keys);
  }

  /** Closes the window's palette, and so stops what it reads, once a dialog hides it. */
  private static void closePalette(State s, TabGroups.Group<Tab> group, boolean modal) {
    var palette = s.palette(group);
    if (modal && palette.isOpen()) {
      palette.close();
    }
  }

  /** The window's command palette, over the commands of its menus and its tables after @. */
  private static El palette(State s, TabGroups.Group<Tab> group, List<Menu.Item> menus) {
    return CommandPalette.palette(
        s.palette(group), CommandPalette.commands(menus), List.of(GoTo.source(s, group)));
  }

  /** The tabs of a window, each with its content; the main window's empty one says what goes in. */
  private static El tabs(State s, TabGroups.Group<Tab> group, App<State> app) {
    return s.tabs.tabs(
        group,
        tab -> switch (tab) {
          case TableTab x ->
            new Tabs.Tab(s.title(x))
                .icon(x.table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE)
                .hue(ConnectionColors.hue(s.color(x.id)))
                .busy(x.opening != null || x.counting != null);
          // What may be open is changes not yet committed: the tab's dot.
          case ConsoleTab x ->
            new Tabs.Tab(
                s.title(x),
                true,
                x.atStake(),
                Symbol.CODE,
                ConnectionColors.hue(s.color(x.id)),
                x.busy());
        },
        tab -> switch (tab) {
          case null ->
            group == s.tabs.main()
                ? emptyState(
                    Symbol.TABLE,
                    "No tabs open",
                    "Open a table in the tree, with a double press or Enter, to see its rows, or"
                        + " a console from a connection's menu to run statements.",
                    null)
                : null;
          case TableTab x -> x.view(s, app);
          case ConsoleTab x -> x.view(s, app);
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
    Theme t = Theme.CURRENT.get();
    App<State> app = app(s, group);
    El tabs = tabs(s, group, app).key("tabs");
    // What a console of this window asked for, such as a password.
    El asked = s.catalog.dialog(s, group);
    // What its console asks about what may be open in it.
    El question = question(s, group);
    El confirmation = asked == null && question == null ? confirmation(s, group) : null;
    // The menus' commands without the bar, acting on this window's tabs.
    boolean modal = asks(s, group);
    List<Menu.Item> menus = menus(s, group, modal);
    closePalette(s, group, modal);
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(keys(Menu.shortcuts(menus), null, s.palette(group), modal, tabs))
        .child(TitleBar.titleBar(icon(), title(s, group.activeTab())))
        .child(col()
            .grow(1)
            .minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(Card.flush(tabs.grow(1)).grow(1)))
        .overlay(asked)
        .overlay(question)
        .overlay(confirmation)
        .overlay(confirmation == null ? null : historyQuestion(s, group))
        .overlay(modal ? null : palette(s, group, menus));
  }

  /** The app's icon before a title bar's title, as the window's. */
  private static El icon() {
    return AppIcon.appIcon(16, HooriDb.COLOR, Symbol.DATABASE);
  }

  /** What a console of the window asks about what may be open in it, or null. */
  private static El question(State s, TabGroups.Group<Tab> group) {
    return questioned(s, group) ? s.pending.console().question(s) : null;
  }

  /** Whether a console of the window asks about what may be open in it. */
  private static boolean questioned(State s, TabGroups.Group<Tab> group) {
    var pending = s.pending;
    return pending != null && !pending.armed() && s.tabs.groupOf(pending.console()) == group;
  }

  /**
   * Whether the window of these tabs shows a dialog of its own, without making it: a console's
   * question, what its console in front asks before a statement or its History, or what the
   * catalog asks there.
   */
  private static boolean asks(State s, TabGroups.Group<Tab> group) {
    return questioned(s, group)
        || group.activeTab() instanceof ConsoleTab console
            && (console.confirming != null || console.history != null)
        || s.catalog.asks(s, group);
  }

  /**
   * What the window's console in front asks before it sends a statement, or null; that of a
   * console behind another tab waits until its tab is shown.
   */
  private static El confirmation(State s, TabGroups.Group<Tab> group) {
    if (!(group.activeTab() instanceof ConsoleTab console)) {
      return null;
    }
    El asked = console.confirmation(s);
    return asked != null || console.history == null
        ? asked
        : console.history.view(s, console, app(s, group));
  }

  /** The question of the History dialog of the window's console in front, or null. */
  private static El historyQuestion(State s, TabGroups.Group<Tab> group) {
    return group.activeTab() instanceof ConsoleTab console && console.history != null
        ? console.history.question(s, console)
        : null;
  }

  /** The app of the window of these tabs. */
  @SuppressWarnings("unchecked") // every window runs an app of the state
  private static App<State> app(State s, TabGroups.Group<Tab> group) {
    return group.window() == null ? s.app : (App<State>) group.window().app();
  }

  /** The connections: waiting while they load, then a tree of them or the empty state. */
  private static El catalog(State s) {
    Theme t = Theme.CURRENT.get();
    Runnable create = s.canChange() ? s::newConnection : null;
    El problem = null;
    if (s.loadError != null) {
      // A file that could not be read for another reason is still there: nothing may replace it.
      problem = Callout.callout(
          Callout.Kind.DANGER,
          "Connections could not be read",
          s.loadError,
          s.canChange() ? new El[0] : new El[] {tryAgain(s::reload)});
    } else if (s.saveError != null) {
      problem = Callout.callout(
          Callout.Kind.DANGER, "Connections could not be saved", s.saveError, tryAgain(s::persist));
    }
    El body;
    if (!s.loaded) {
      body = col()
          .role("status")
          .label("Loading connections")
          .grow(1)
          .center()
          .gap(t.space().m())
          .child(Progress.spinner())
          .child(label(Label.Variant.MUTED, "Loading connections…"));
    } else if (!s.profiles.isEmpty()) {
      var c = s.catalog;
      El tree = Tree.tree(
              new CatalogTree.Model(t, s),
              new Tree.Options<Node>()
                  .open(c.open::contains, (node, open) -> c.toggle(s, node, open))
                  .select(s.selected, node -> s.selected = node)
                  .onActivate(node -> c.activate(s, node)))
          .key("connections")
          .label("Connections");
      body = Card.inset(Menu.contextMenu(tree, c.menu, c.items(s, s.selected)));
      El callout = c.callout(s);
      if (callout != null) {
        body = col()
            .grow(1)
            .child(body)
            .child(col().padding(0, t.space().s(), t.space().s(), t.space().s()).child(callout));
      }
    } else if (s.loadError == null) {
      body = emptyState(
          Symbol.DATABASE,
          "No connections yet",
          "Connections to your databases will be listed here.",
          button(Button.Variant.PRIMARY, Symbol.ADD, "New connection", create));
    } else {
      body = null;
    }
    return Card.flush(
        Card.toolbar(
            "Connections",
            null,
            Button.icon(Button.Variant.GHOST, Symbol.ADD, "Add connection", create)),
        problem == null ? null : col().padding(t.space().s()).child(problem),
        s.notices.isEmpty() ? null : notices(s),
        body);
  }

  /**
   * The notices of what finishing something on a console's session did, the newest first, each
   * until dismissed: a failure as a danger, a warning as such. They scroll in a region of bounded
   * height above the tree, with Dismiss all where there are several.
   */
  private static El notices(State s) {
    Theme t = Theme.CURRENT.get();
    int shown = s.notices.getLast().number(); // the newest this view shows
    var list = col().gap(t.space().s()).key("notices").label("Notices");
    for (var notice : s.notices.reversed()) {
      list.child(Callout.callout(
              notice.failure() ? Callout.Kind.DANGER : Callout.Kind.WARNING,
              notice.connection() + " · " + notice.statement(),
              notice.what(),
              button(Button.Variant.SECONDARY, "Dismiss", () -> s.dismiss(notice))
                  .key("dismiss-" + notice.number()))
          .key("notice-" + notice.number()));
    }
    return col()
        .gap(t.space().s())
        .padding(t.space().s())
        .child(
            s.notices.size() > 3
                ? row()
                    .child(button(
                            Button.Variant.SECONDARY,
                            "Dismiss all",
                            // Those shown: a notice that comes before the press stays.
                            () -> s.dismissUpTo(shown))
                        .key("dismiss-all"))
                : null)
        // shortcut: bounded by the field size, the theme having no size for a panel's part.
        .child(list.scroll().maxSize(Float.MAX_VALUE, t.size().field()));
  }

  private static El tryAgain(Runnable action) {
    return button(Button.Variant.SECONDARY, "Try again", action);
  }

  private static El deleteDialog(State s) {
    Theme t = Theme.CURRENT.get();
    El delete = button(
        Button.Variant.PRIMARY,
        "Delete",
        s::delete,
        Button.options()
            .tone(Button.Tone.DANGER)
            .state(s.removing ? Button.State.BUSY : Button.State.READY));
    El content = col()
        .gap(t.space().m())
        .child(label("Delete the connection " + State.name(s.deleting) + "?"))
        .child(
            s.tables(s.deleting.id()).isEmpty() && s.consoles(s.deleting.id()).isEmpty()
                ? null
                : label(Label.Variant.MUTED, "Its open tabs close."))
        .child(label(
            Label.Variant.MUTED,
            "If its password is saved in the system keyring, it is deleted there too."));
    if (s.removingSlow) {
      content.child(Callout.callout(
          Callout.Kind.WARNING,
          "The system keyring does not answer",
          "Cancel gives up waiting: the password may then still be in the keyring or not, and the"
              + " connection is not deleted."
              + (s.keyringWorker.behind() ? ConnectionDialog.BEHIND : "")));
    }
    if (s.removeError != null) {
      content.child(
          Callout.callout(Callout.Kind.DANGER, "The password could not be deleted", s.removeError));
    }
    return Dialog.dialog(
        "Delete connection",
        content,
        List.of(
            button(
                Button.Variant.SECONDARY,
                "Cancel",
                s.removing && !s.removingSlow ? null : s::cancelDelete),
            delete),
        s::cancelDelete);
  }

  /** The connections the tree opened, each once; busy while one is on its way. */
  private static El connections(State s) {
    int connected = 0;
    boolean connecting = false;
    for (var id : s.catalog.lives.keySet()) {
      if (s.catalog.connected(id)) {
        connected++;
      } else {
        connecting |= s.catalog.active(id);
      }
    }
    String text = connected > 0 ? connected + " connected" : "No connections";
    if (connecting) {
      return StatusBar.status(StatusBar.State.BUSY, connected > 0 ? text : "Connecting…", null)
          .key("connected");
    }
    return StatusBar.status(connected > 0 ? StatusBar.State.OK : StatusBar.State.IDLE, text, null)
        .key("connected");
  }

  /**
   * The consoles where something may be at stake, as Exit asks about; pressing it shows the first,
   * but not while a dialog is open in the main window or its own, whose tab it would switch. Null
   * while there are none.
   *
   * @param modal whether a dialog is open in the main window
   */
  private static El uncommitted(State s, boolean modal) {
    var consoles = s.consoles(null).stream().filter(ConsoleTab::atStake).toList();
    if (consoles.isEmpty()) {
      return null;
    }
    int n = consoles.size();
    var first = consoles.getFirst();
    boolean asking = modal || asks(s, s.tabs.groupOf(first));
    return StatusBar.item(
            Badge.Kind.WARNING,
            Symbol.LOCK_OPEN,
            n + (n == 1 ? " console may" : " consoles may") + " hold uncommitted work",
            asking ? null : () -> first.show(s))
        .key("uncommitted");
  }

  /** The version that runs, its author, license and website. */
  private static El aboutDialog(State s) {
    float side = Theme.CURRENT.get().size().icon();
    El mark = AppIcon.appIcon(Math.round(side), HooriDb.COLOR, Symbol.DATABASE).size(side, side);
    Runnable close = () -> s.about = false;
    El ok = button(Button.Variant.PRIMARY, "Close", close);
    El content = Properties.properties(
        List.of(
            Properties.property("Version", HooriDb.version()),
            Properties.property("Author", "Patrick Trautmann"),
            Properties.property("License", "MIT"),
            Properties.property("Website", "https://db.hoori.dev")),
        null);
    return Dialog.dialog(
        "About " + HooriDb.NAME,
        content,
        List.of(ok),
        close,
        Dialog.options().leading(mark).defaultButton(ok));
  }

  /**
   * Only what does something today: a new connection, a console, the drivers, the tabs of the
   * window, quitting, the palette, the themes, where the focus goes, and About. They are the
   * commands of the palette too, and their keys act in the window of these tabs, with or without
   * its bar.
   *
   * @param modal whether a dialog is open, which the palette waits for
   */
  static List<Menu.Item> menus(State s, TabGroups.Group<Tab> group, boolean modal) {
    var palette = s.palette(group);
    var console = s.consoleFor(group);
    // What opens a dialog of the main window waits for the one open there, from every window.
    boolean free = !mainDialog(s);
    Runnable close = group.activeTab() == null ? null : () -> s.closeTab(group);
    boolean several = group.tabs().size() > 1;
    boolean tree = group == s.tabs.main() && s.loaded && !s.profiles.isEmpty();
    return List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New connection…", free && s.canChange() ? s::newConnection : null)
                    .icon(Symbol.ADD),
                Menu.item(
                        "New console", console == null ? null : () -> s.openConsole(console, group))
                    .icon(Symbol.CODE)
                    .shortcut(Key.CONTROL, "N"),
                Menu.item(
                        "History…",
                        !modal && group.activeTab() instanceof ConsoleTab c ? c::openHistory : null)
                    .icon(Symbol.HISTORY),
                Menu.item(
                    "Drivers…",
                    s.drivers == null || !free ? null : () -> s.driverList = DriversDialog.open(s)),
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
                // The kit's tabs take these keys first; Page Down shows, as it says the direction.
                Menu.item("Next tab", several ? () -> s.nextTab(group, 1) : null)
                    .shortcut(Key.CONTROL, "PageDown")
                    .alsoShortcut(Key.CONTROL, "Tab"),
                Menu.item("Previous tab", several ? () -> s.nextTab(group, -1) : null)
                    .shortcut(Key.CONTROL, "PageUp")
                    .alsoShortcut(Key.CONTROL | Key.SHIFT, "Tab"),
                Menu.separator(),
                Menu.item("Connections", tree ? () -> focusTree(s) : null)
                    .shortcut(Key.CONTROL, "0"),
                Menu.item("Tab content", close == null ? null : () -> focusContent(s, group))
                    .shortcut(Key.CONTROL, "1"))),
        Menu.submenu(
            "&Help", List.of(Menu.item("About HooriDB", free ? () -> s.about = true : null))));
  }

  /** Whether a dialog of the main window is open: its own, or one the catalog asks there. */
  private static boolean mainDialog(State s) {
    return s.dialog != null
        || s.deleting != null
        || s.about
        || s.driverList != null
        || s.catalog.asks(s, s.tabs.main());
  }

  /** Gives the focus to the tree of connections. */
  private static void focusTree(State s) {
    focus(s.app, find(s.app.tree(), "connections"));
  }

  /**
   * Gives the focus to the content of the window's active tab: where it was there last, else its
   * default, a table's grid as soon as it is built or the console's editor.
   */
  private static void focusContent(State s, TabGroups.Group<Tab> group) {
    var app = app(s, group);
    focus(app, find(find(app.tree(), "tabs"), "panel"));
  }

  /**
   * Gives the focus to the content of the window's active tab once the window has built it anew: a
   * handler, in that window or another, has just made the tab active.
   */
  static void focusContentSoon(State s, TabGroups.Group<Tab> group) {
    var app = app(s, group);
    app.invalidate();
    app.after(0, () -> focusContent(s, group));
  }

  private static void focus(App<State> app, El el) {
    if (el != null) {
      app.focus(el);
    }
  }

  /** The element at or below this one with the key, in tree order; or null. */
  static El find(El el, String key) {
    if (el == null || key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
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

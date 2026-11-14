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
    List<Menu.Item> menus = menus(s);
    El catalogue = catalogue(t, s).key("catalogue").label("Catalogue");
    El tabs = tabs(s, s.tabs.main(), s.app).key("tabs");
    // The menus' keys, and the tabs' keys also while the focus is outside them; none while empty.
    Predicate<Key> keys = MenuBar.keys(s.bar, menus);
    El root = col()
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
    // The window closes once what was begun has ended and the connections are written.
    Windows.Handle window = s.app == null ? null : s.app.windows().of(s.app);
    if (window != null) {
      window.onCloseRequest(s::requestClose);
    }
    if (s.dialog != null) {
      root.overlay(s.dialog.view(t, s)).overlay(s.dialog.question(t, s));
    }
    root.overlay(s.deleting == null ? null : deleteDialog(t, s));
    // One dialog at a time: what the catalogue asks waits for the others to close.
    return root.overlay(s.dialog == null && s.deleting == null ? s.catalogue.dialog(t, s) : null);
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
                .icon(x.table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE);
        },
        tab -> switch (tab) {
          case null ->
            group == s.tabs.main()
                ? emptyState(
                    t,
                    Symbol.TABLE,
                    "No tabs open",
                    "Open a table in the tree, with a double press or Enter, to see its rows.",
                    null)
                : null;
          case TableTab x -> x.view(t, s, app);
        });
  }

  /** What a window of tabs is called: its active tab's title. */
  static String title(State s, Tab tab) {
    return switch (tab) {
      case null -> HooriDb.NAME;
      case TableTab x -> s.title(x);
    };
  }

  /** The window of tabs torn off: its title bar over its tabs, whose status lines they show. */
  static El window(State s, TabGroups.Group<Tab> group) {
    Theme t = s.theme;
    @SuppressWarnings("unchecked") // every window runs an app of the state
    App<State> app =
        group.window() == null ? s.app : (App<State>) group.window().app();
    El tabs = tabs(s, group, app).key("tabs");
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(tabs.onKey())
        .child(TitleBar.titleBar(
            t, title(s, group.activeTab()), HooriDb.COLOUR, app != null && app.windowMaximized()))
        .child(col()
            .grow(1)
            .minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(Card.flush(t, tabs.grow(1)).grow(1)));
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
            s.tables(s.deleting.id()).isEmpty()
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

  /** Only what does something today: a new connection, quitting, and the themes. */
  static List<Menu.Item> menus(State s) {
    return List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item("New connection…", s.canChange() ? s::newConnection : null)
                    .icon(Symbol.ADD),
                Menu.separator(),
                Menu.item("Exit", s::quit))),
        Menu.submenu(
            "&View",
            List.of(
                themeItem(s, Theme.DARK, "Dark theme"), themeItem(s, Theme.LIGHT, "Light theme"))));
  }

  private static Menu.Item themeItem(State s, Theme theme, String name) {
    return Menu.item(name, () -> s.theme(theme, name)).checked(s.theme == theme);
  }
}

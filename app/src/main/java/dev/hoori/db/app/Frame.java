package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.EmptyState.emptyState;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TitleBar.withMenus;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Split;
import com.rethinkj.ui.kit.StatusBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.connection.ConnectionProfile;
import java.util.List;
import java.util.function.Predicate;

/** The main window: menus in the title bar, the catalogue beside the tabs, the status line. */
final class Frame {

  private Frame() {}

  static El view(State s) {
    Theme t = s.theme;
    List<Menu.Item> menus = menus(s);
    El catalogue = catalogue(t, s).key("catalogue").label("Catalogue");
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
    return root.overlay(s.deleting == null ? null : deleteDialog(t, s));
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
      var selected = s.profile(s.selected);
      El tree = Tree.tree(
              t,
              new Connections(t, s.profiles),
              p -> false,
              selected,
              p -> s.selected = p.id(),
              null,
              null)
          .key("connections")
          .label("Connections");
      body = Card.inset(t, Menu.contextMenu(t, tree, s.connectionMenu, items(s, selected)));
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

  /** The context menu of a connection. */
  private static List<Menu.Item> items(State s, ConnectionProfile p) {
    if (p == null) {
      return List.of();
    }
    return List.of(
        Menu.item("Edit…", () -> s.edit(p)).icon(Symbol.EDIT),
        Menu.item("Duplicate", () -> s.duplicate(p)).icon(Symbol.COPY),
        Menu.separator(),
        Menu.item("Delete…", () -> s.askDelete(p)).icon(Symbol.DELETE));
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

  /** The saved profiles as the tree's nodes, without children yet. */
  private record Connections(Theme t, List<ConnectionProfile> roots)
      implements Tree.Model<ConnectionProfile> {

    @Override
    public List<ConnectionProfile> children(ConnectionProfile node) {
      return List.of();
    }

    @Override
    public boolean isLeaf(ConnectionProfile node) {
      return true;
    }

    @Override
    public String label(ConnectionProfile node) {
      return State.name(node);
    }

    @Override
    public El icon(ConnectionProfile node) {
      return Icon.icon(t, Symbol.DATABASE, Icon.Variant.MUTED);
    }
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

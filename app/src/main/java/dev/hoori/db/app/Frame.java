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
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
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
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.kit.Tree;
import com.rethinkj.ui.window.Windows;
import dev.hoori.db.core.catalog.Routine;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.update.Channel;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** The main window: menus in the title bar, the catalog beside the tabs, the status line. */
final class Frame {

  /** The sidebar's title, the tree's label, and the item that focuses it. */
  private static final String WORKSPACE = "Workspace";

  private Frame() {}

  static El view(State s) {
    Theme t = Theme.CURRENT.get();
    var main = s.tabs.main().active();
    // One dialog at a time: what the catalog asks waits for the others to close.
    El asked = ownDialog(s) ? null : s.catalog.dialog(s, main);
    El question = question(s, main);
    boolean before =
        ownDialog(s) || asked != null || s.dialogs.driverList != null || question != null;
    El confirmation = before ? null : confirmation(s, main);
    boolean modal = before || confirmation != null;
    closePalette(s, main, modal);
    List<Menu.Item> menus = menus(s, main, modal);
    El catalog = catalog(s, modal).key("catalog").label("Catalog");
    El tabs = tabs(s, main, s.app).key("tabs");
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .onKey(keys(MenuBar.keys(s.bar, menus), s.bar, s.palette(main), modal, tabs))
        .onKeyUp(MenuBar.keysUp(s.bar, menus))
        .child(TitleBar.titleBar(
            icon(), HooriDb.NAME, List.of(MenuBar.menuBar(s.bar, menus)), null, List.of()))
        .child(s.logPanel
            .around(
                s,
                Split.cards(
                    Split.Direction.ROW,
                    s.sidebar,
                    State.DEFAULT_SIDEBAR,
                    s::moveSidebar,
                    catalog,
                    160,
                    tabs,
                    320))
            .grow(1)
            .padding(0, t.space().m(), t.space().m(), t.space().m()))
        .child(StatusBar.statusBar(
            Arrays.asList(
                connections(s),
                uncommitted(s, modal),
                downloading(s),
                FileRunDialog.statusItem(s, modal)),
            s.message == null ? null : StatusBar.text(s.message).key("message"),
            List.of(
                s.logPanel.toggle(s, modal),
                StatusBar.item(null, s.version, modal ? null : () -> s.dialogs.about = true)
                    .key("version"))));
    closeOnRequest(s);
    if (s.dialogs.dialog != null) {
      root.overlay(s.dialogs.dialog.view(s))
          .overlay(s.dialogs.dialog.driverDialog(s))
          .overlay(s.dialogs.dialog.question(s));
    }
    root.overlay(s.dialogs.deleting == null ? null : deleteDialog(s));
    root.overlay(s.dialogs.folderDialog == null ? null : s.dialogs.folderDialog.view(s));
    root.overlay(s.dialogs.queryDialog == null ? null : s.dialogs.queryDialog.view(s));
    root.overlay(s.dialogs.about ? aboutDialog(s) : null);
    root.overlay(FileRunDialog.overlay(s));
    root.overlay(asked);
    root.overlay(question);
    root.overlay(confirmation);
    root.overlay(confirmation == null ? null : historyQuestion(s, main));
    if (s.dialogs.driverList != null && !ownDialog(s) && asked == null) {
      root.overlay(s.dialogs.driverList.view(s)).overlay(s.dialogs.driverList.question(s));
    }
    return root.overlay(modal ? null : palette(s, main, menus));
  }

  /** The window closes once what was begun has ended and the connections are written. */
  private static void closeOnRequest(State s) {
    Windows.Handle window = s.app == null ? null : s.app.windows().of(s.app);
    if (window != null) {
      window.onCloseRequest(s::requestClose);
    }
  }

  /**
   * Whether a dialog of the window's own is open: a connection's, a delete's, a folder's or About,
   * which what the catalog asks, the drivers and the confirmations wait for.
   */
  private static boolean ownDialog(State s) {
    return s.dialogs.dialog != null
        || s.dialogs.deleting != null
        || s.dialogs.folderDialog != null
        || s.dialogs.queryDialog != null
        || s.dialogs.about
        || s.dialogs.fileRun != null && s.dialogs.fileRun.shown();
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
        group.frame(),
        tab -> switch (tab) {
          case TableTab x ->
            new Tabs.Tab(
                s.title(x),
                true,
                x.atStake(),
                x.table.kind() == Table.Kind.VIEW ? Symbol.EYE : Symbol.TABLE,
                ConnectionColors.hue(s.color(x.id)),
                x.opening != null || x.counting != null || x.writingChanges());
          // What may be open is changes not yet committed: the tab's dot.
          case ConsoleTab x ->
            new Tabs.Tab(
                s.title(x),
                true,
                x.atStake(),
                x.isQuery() ? Glyphs.query(x.queryLanguage) : Symbol.CODE,
                ConnectionColors.hue(s.color(x.id)),
                x.busy());
          case MonitorTab x ->
            new Tabs.Tab(s.title(x))
                .icon(Symbol.SERVER)
                .hue(ConnectionColors.hue(s.color(x.id)))
                .busy(x.busy());
          // What is edited and not saved: the tab's dot.
          case RoutineTab x ->
            new Tabs.Tab(
                s.title(x),
                true,
                x.atStake(Guard.Scope.BOTH),
                x.routine != null && x.routine.kind() == Routine.Kind.PROCEDURE
                    ? Symbol.CODE
                    : Symbol.MATH_FORMULA,
                ConnectionColors.hue(s.color(x.id)),
                x.busy());
        },
        tab -> switch (tab) {
          case null ->
            group == s.tabs.main().active()
                ? emptyState(
                    Symbol.TABLE,
                    "No tabs open",
                    "Open a table in the tree, with a double press or Enter, to see its rows, or"
                        + " a console from a connection's menu to run statements.",
                    null)
                : null;
          case TableTab x -> x.view(s, app);
          case ConsoleTab x -> x.view(s, app);
          case MonitorTab x -> x.view(s, app);
          case RoutineTab x -> x.view(s, app);
        });
  }

  /** What a window of tabs is called: its active tab's title. */
  static String title(State s, Tab tab) {
    return switch (tab) {
      case null -> HooriDb.NAME;
      case TableTab x -> s.title(x);
      case ConsoleTab x -> s.title(x);
      case MonitorTab x -> s.title(x);
      case RoutineTab x -> s.title(x);
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

  /** What a tab of the window asks about what may be open in it, or null. */
  private static El question(State s, TabGroups.Group<Tab> group) {
    var questioned = questioned(s, group);
    return questioned != null ? questioned.tab().question(s) : null;
  }

  /**
   * The question a tab of the window asks about what may be open in it, or null; a file's run
   * asks in the main window.
   */
  private static Guard.Pending.Question questioned(State s, TabGroups.Group<Tab> group) {
    return s.guard.pending instanceof Guard.Pending.Question question
            && group(s, question.tab()) == group
        ? question
        : null;
  }

  // The tabs of the window where what is guarded asks.
  private static TabGroups.Group<Tab> group(State s, Guard.Guarded guarded) {
    return guarded instanceof Tab tab ? s.tabs.groupOf(tab) : s.tabs.main().active();
  }

  /**
   * Whether the window of these tabs shows a dialog of its own, without making it: a tab's
   * question, a DML preview, what its console in front asks before a statement or its History, or
   * what the catalog asks there.
   */
  private static boolean asks(State s, TabGroups.Group<Tab> group) {
    return questioned(s, group) != null
        || group.activeTab() instanceof TableTab table
            && (table.preview != null || table.documentDialog != null || table.conditions.open)
        || group.activeTab() instanceof ConsoleTab console
            && (console.confirming != null || console.preview != null || console.history != null)
        || group.activeTab() instanceof MonitorTab monitor && monitor.asked != null
        || group.activeTab() instanceof RoutineTab routine && routine.asked != null
        || s.catalog.asks(s, group);
  }

  /**
   * What the window's tab in front asks, or null; that of a tab behind another waits until its tab
   * is shown.
   */
  private static El confirmation(State s, TabGroups.Group<Tab> group) {
    if (group.activeTab() instanceof MonitorTab monitor) {
      return monitor.question(s);
    }
    if (group.activeTab() instanceof RoutineTab routine) {
      return routine.dialog(s, app(s, group));
    }
    if (group.activeTab() instanceof TableTab table) {
      El document = table.documentView(s, app(s, group));
      if (document != null) {
        return document;
      }
      El preview = table.previewDialog(s, app(s, group));
      return preview != null ? preview : table.conditionsDialog(s);
    }
    if (!(group.activeTab() instanceof ConsoleTab console)) {
      return null;
    }
    El asked = console.confirmation(s);
    if (asked != null) {
      return asked;
    }
    El preview = console.preview(s, app(s, group));
    if (preview != null) {
      return preview;
    }
    return console.history == null ? null : console.history.view(s, console, app(s, group));
  }

  /** The question of the History dialog of the window's console in front, or null. */
  private static El historyQuestion(State s, TabGroups.Group<Tab> group) {
    if (group.activeTab() instanceof ConsoleTab console && console.history != null) {
      return console.history.question(s, console);
    }
    return null;
  }

  /** The app of the window of these tabs. */
  @SuppressWarnings("unchecked") // every window runs an app of the state
  private static App<State> app(State s, TabGroups.Group<Tab> group) {
    return group.window() == null ? s.app : (App<State>) group.window().app();
  }

  /**
   * The workspace: waiting while it loads, then a tree of its folders and connections, or the
   * empty state while it holds none.
   *
   * @param modal whether a dialog is open in the main window, which the notices' buttons wait for
   */
  private static El catalog(State s, boolean modal) {
    Theme t = Theme.CURRENT.get();
    Runnable create = s.canChange() ? s::newConnection : null;
    var c = s.catalog;
    El problem = problem(s);
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
    } else if (!s.workspace().entries().isEmpty()) {
      body = workspace(s);
    } else if (s.loadError == null) {
      body = emptyState(
          Symbol.DATABASE,
          "No connections yet",
          "Connections to your databases will be listed here.",
          button(Button.Variant.PRIMARY, Symbol.ADD, "New connection", create));
    } else {
      body = null;
    }
    El add = Menu.dropdown(
        Button.icon(
            Button.Variant.GHOST, Symbol.ADD, "Add", s.canChange() ? c.adding::toggle : null),
        c.adding,
        List.of(
            Menu.item("New connection…", create).icon(Symbol.ADD),
            Menu.item("New query…", s.canChange() ? s::newQuery : null).icon(Symbol.DOCUMENT),
            Menu.item("New folder…", s.canAddFolder() ? s::newFolder : null).icon(Symbol.FOLDER)));
    return Card.flush(
        Card.toolbar(WORKSPACE, null, add),
        problem == null ? null : col().padding(t.space().s()).child(problem),
        s.updates.notice == null ? null : update(s, modal),
        s.notices.isEmpty() ? null : notices(s),
        body);
  }

  /** Why the workspace could not be read or saved, or null. */
  private static El problem(State s) {
    if (s.loadError != null) {
      // A file that could not be read for another reason is still there: nothing may replace it.
      return Callout.callout(
          Callout.Kind.DANGER,
          "Connections could not be read",
          s.loadError,
          s.canChange() ? new El[0] : new El[] {tryAgain(s::reload)});
    }
    if (s.saveError != null) {
      return Callout.callout(
          Callout.Kind.DANGER, "Connections could not be saved", s.saveError, tryAgain(s::persist));
    }
    return null;
  }

  /** The tree of folders and connections, with its menu, and what the catalog says below it. */
  private static El workspace(State s) {
    Theme t = Theme.CURRENT.get();
    var c = s.catalog;
    El tree = Tree.tree(
            new CatalogModel(t, s),
            new Tree.Options<Node>()
                .open(
                    node -> node instanceof Node.Folder f
                        ? s.folderOpen(f.id())
                        : c.open.contains(node),
                    (node, open) -> c.toggle(s, node, open))
                .select(s.selected, node -> s.selected = node)
                .onActivate(node -> c.activate(s, node))
                // A table opens its tab: reading its columns and keys would hold its first rows.
                .toggleOnDoublePress(node -> !(node instanceof Node.OfTable))
                .drag(
                    c.dragging,
                    CatalogTree::canDrag,
                    move -> CatalogTree.canDrop(s, move),
                    move -> CatalogTree.dropped(s, move)))
        .key("connections")
        .label(WORKSPACE);
    // Its items only while it is open: Move to walks the workspace for each folder.
    var items = c.menu.isOpen() ? c.items(s, s.selected) : List.<Menu.Item>of();
    El body = Card.inset(Menu.contextMenu(tree, c.menu, items));
    El callout = c.callout(s);
    if (callout == null) {
      return body;
    }
    return col()
        .grow(1)
        .child(body)
        .child(col().padding(0, t.space().s(), t.space().s(), t.space().s()).child(callout));
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

  /**
   * The notice of updates, apart from those of the consoles' sessions: what is ready or available,
   * or what became of the last update. Its buttons wait while a dialog is open.
   */
  private static El update(State s, boolean modal) {
    Theme t = Theme.CURRENT.get();
    var notice = s.updates.notice;
    El act = null;
    if (notice.act() != null) {
      var variant = notice.primary() ? Button.Variant.PRIMARY : Button.Variant.SECONDARY;
      act = button(variant, notice.act(), modal ? null : notice.action()).key("update-act");
    }
    El dismiss = button(
            Button.Variant.SECONDARY, notice.dismiss(), modal ? null : s.updates::dismiss)
        .key("update-dismiss");
    El callout = Callout.callout(
            notice.kind(),
            notice.title(),
            notice.text(),
            act == null ? new El[] {dismiss} : new El[] {act, dismiss})
        .key("update");
    var details = notice.details();
    if (details != null) {
      El changes = updateChanges(notice.title(), details, s.zone);
      callout = Tooltip.tooltip(callout, () -> changes);
    }
    return col().padding(t.space().s()).child(callout);
  }

  /** What an offered update brings, for its notice's tooltip. */
  private static El updateChanges(String title, Updates.Details details, ZoneId zone) {
    Theme t = Theme.CURRENT.get();
    var changes = details.changes();
    var date = Format.DATE.text(LocalDate.ofInstant(details.published(), zone));
    var header = col()
        .gap(t.space().xs())
        .child(label(Label.Variant.HEADING, title).wrapText())
        .child(label(Label.Variant.SMALL, details.version() + " · " + date).wrapText());
    El body = col().w(t.size().tooltip()).gap(t.space().s()).child(header);
    if (details.highlights() != null) {
      body.child(Markdown.fragment(details.highlights(), Markdown.options()));
      int more = changes.count() - changes.highlights().size();
      if (more > 0) {
        body.child(label(
                Label.Variant.NORMAL,
                "Plus " + more + (more == 1 ? " more change." : " more changes."))
            .wrapText());
      }
    }
    String counts = changes.count()
        + (changes.count() == 1 ? " change" : " changes")
        + " from "
        + changes.contributors()
        + (changes.contributors() == 1 ? " contributor" : " contributors");
    return body.child(label(Label.Variant.MUTED, counts).wrapText());
  }

  /** The download of an update, busy while it runs; null while none does. */
  private static El downloading(State s) {
    return s.updates.downloading == null
        ? null
        : StatusBar.status(
                StatusBar.State.BUSY,
                "Downloading HooriDB " + s.updates.downloading.version() + "…",
                null)
            .key("downloading");
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
            .state(s.removal.running ? Button.State.BUSY : Button.State.READY));
    El content = col()
        .gap(t.space().m())
        .child(label("Delete the connection " + State.name(s.dialogs.deleting) + "?"))
        .child(
            s.tables(s.dialogs.deleting.id()).isEmpty()
                    && s.consoles(s.dialogs.deleting.id()).isEmpty()
                ? null
                : label(Label.Variant.MUTED, "Its open tabs close."))
        .child(label(
            Label.Variant.MUTED,
            "If its password is saved in the system keyring, it is deleted there too."));
    if (s.removal.slow) {
      content.child(Callout.callout(
          Callout.Kind.WARNING,
          "The system keyring does not answer",
          "Cancel gives up waiting: the password may then still be in the keyring or not, and the"
              + " connection is not deleted."
              + (s.keyringWorker.behind() ? ConnectionDialog.BEHIND : "")));
    }
    if (s.removal.error != null) {
      content.child(Callout.callout(
          Callout.Kind.DANGER, "The password could not be deleted", s.removal.error));
    }
    return Dialog.dialog(
        "Delete connection",
        content,
        List.of(
            button(
                Button.Variant.SECONDARY,
                "Cancel",
                s.removal.running && !s.removal.slow ? null : s::cancelDelete),
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
   * The tabs where something may be at stake, as Exit asks about; pressing it shows the first,
   * but not while a dialog is open in the main window or its own, whose tab it would switch. Null
   * while there are none.
   *
   * @param modal whether a dialog is open in the main window
   */
  private static El uncommitted(State s, boolean modal) {
    var tabs = s.guarded(null).stream().filter(t -> t.atStake(Guard.Scope.BOTH)).toList();
    if (tabs.isEmpty()) {
      return null;
    }
    int consoles = 0;
    int tables = 0;
    int routines = 0;
    boolean run = false;
    for (var tab : tabs) {
      switch (tab) {
        case ConsoleTab _ -> consoles++;
        case TableTab _ -> tables++;
        case RoutineTab _ -> routines++;
        case FileRunDialog _ -> run = true;
      }
    }
    var first = tabs.getFirst();
    boolean asking = modal || asks(s, group(s, first));
    return StatusBar.item(
            Badge.Kind.WARNING,
            Symbol.LOCK_OPEN,
            uncommitted(consoles, tables, routines, run),
            asking ? null : () -> first.show(s))
        .key("uncommitted");
  }

  /** What the consoles, tables, routines and a file's run that may hold uncommitted work say. */
  private static String uncommitted(int consoles, int tables, int routines, boolean run) {
    if (consoles + tables + routines == 0) {
      return "A file that runs in one transaction holds uncommitted work";
    }
    if (tables + routines == 0 && !run) {
      return consoles + (consoles == 1 ? " console may" : " consoles may")
          + " hold uncommitted work";
    }
    if (consoles + routines == 0 && !run) {
      return tables + (tables == 1 ? " table has" : " tables have") + " uncommitted changes";
    }
    if (consoles + tables == 0 && !run) {
      return routines + (routines == 1 ? " routine has" : " routines have") + " unsaved edits";
    }
    int n = consoles + tables + routines;
    return n + (n == 1 ? " tab" : " tabs") + (run ? " and a file's run" : "")
        + " may hold uncommitted work";
  }

  /** The version that runs, its authors, license and website. */
  private static El aboutDialog(State s) {
    Theme t = Theme.CURRENT.get();
    float side = t.size().icon();
    El mark = AppIcon.appIcon(Math.round(side), HooriDb.COLOR, Symbol.DATABASE).size(side, side);
    Runnable close = () -> s.dialogs.about = false;
    El ok = button(Button.Variant.PRIMARY, "Close", close);
    El content = Properties.properties(
            List.of(
                Properties.property("Version", s.version),
                Properties.property(
                    "Updates", s.updates.channel() == Channel.NIGHTLY ? "Nightly" : "Stable"),
                Properties.property("Authors", "HooriDB contributors"),
                Properties.property("License", "MIT"),
                Properties.property("Website", "https://db.hoori.dev")),
            null)
        // Room beside the longest line, which would otherwise touch the dialog's edge.
        .minSize(10 * t.size().control(), 0);
    return Dialog.dialog(
        "About " + HooriDb.NAME,
        content,
        List.of(ok),
        close,
        Dialog.options().leading(mark).defaultButton(ok));
  }

  /**
   * Only what does something today: a new connection, a console, the drivers, the settings, the
   * tabs of the window, quitting, the palette, where the focus goes, and About. They are the
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
    boolean tree =
        group == s.tabs.main().active() && s.loaded && !s.workspace().entries().isEmpty();
    return List.of(
        Menu.submenu(
            "&File",
            List.of(
                Menu.item(
                        "New connection…",
                        free && s.canChange() ? inMain(s, s::newConnection) : null)
                    .icon(Symbol.ADD),
                Menu.item("New query…", free && s.canChange() ? inMain(s, s::newQuery) : null)
                    .icon(Symbol.DOCUMENT),
                Menu.item("New folder…", free && s.canAddFolder() ? inMain(s, s::newFolder) : null)
                    .icon(Symbol.FOLDER),
                Menu.item(
                        "New console", console == null ? null : () -> s.openConsole(console, group))
                    .icon(Symbol.CODE)
                    .shortcut(Key.CONTROL, "N"),
                Menu.item(
                        "Server monitor",
                        console == null ? null : () -> s.openMonitor(console, group))
                    .icon(Symbol.SERVER),
                Menu.item(
                        "Run SQL file…",
                        free && console != null && s.canRunFile(console)
                            ? inMain(s, () -> s.openFileRun(console, ""))
                            : null)
                    .icon(Symbol.DOCUMENT),
                Menu.item(
                        "History…",
                        !modal && group.activeTab() instanceof ConsoleTab c && c.id != null
                            ? c::openHistory
                            : null)
                    .icon(Symbol.HISTORY),
                Menu.item(
                    "Drivers…",
                    s.drivers == null || !free
                        ? null
                        : inMain(s, () -> s.dialogs.driverList = DriversDialog.open(s))),
                Menu.item("Settings…", s.guard.exiting() ? null : () -> SettingsWindow.open(s))
                    .icon(Symbol.SETTINGS)
                    .shortcut(Key.CONTROL, ","),
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
                Menu.item("Statement log", inMain(s, () -> s.logPanel.toggle(s)))
                    .icon(Symbol.TEXT_BULLET_LIST_LTR)
                    .checked(s.logPanel.open))),
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
                Menu.item(WORKSPACE, tree ? () -> focusTree(s) : null).shortcut(Key.CONTROL, "0"),
                Menu.item("Tab content", close == null ? null : () -> focusContent(s, group))
                    .shortcut(Key.CONTROL, "1"))),
        Menu.submenu(
            "&Help",
            List.of(
                Menu.item(
                    "Restart to update",
                    free && s.updates.ready != null && !s.guard.exiting()
                        ? s::restartToUpdate
                        : null),
                Menu.separator(),
                Menu.item(
                    "About HooriDB", free ? inMain(s, () -> s.dialogs.about = true) : null))));
  }

  /** Opens a dialog of the main window and brings that window forward, as it may lie behind. */
  private static Runnable inMain(State s, Runnable open) {
    return () -> {
      open.run();
      var window = s.app.windows().of(s.app);
      if (window != null) {
        window.raise();
      }
    };
  }

  /**
   * Whether a dialog of the main window is open: its own, or one that its console or the catalog
   * asks there.
   */
  private static boolean mainDialog(State s) {
    return s.dialogs.dialog != null
        || s.dialogs.deleting != null
        || s.dialogs.folderDialog != null
        || s.dialogs.queryDialog != null
        || s.dialogs.about
        || s.dialogs.driverList != null
        || s.dialogs.fileRun != null && s.dialogs.fileRun.shown()
        || asks(s, s.tabs.main().active());
  }

  /** Gives the focus to the tree of the workspace. */
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
  private static El find(El el, String key) {
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
}

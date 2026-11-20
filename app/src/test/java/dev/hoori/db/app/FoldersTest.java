package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The workspace's folders in the tree: made, renamed, moved, opened and deleted. */
class FoldersTest {

  @TempDir
  Path dir;

  final CatalogTreeTest c = new CatalogTreeTest();

  @BeforeEach
  void setUp() throws Exception {
    c.dir = dir;
    c.setUp();
  }

  @AfterEach
  void tearDown() throws Exception {
    c.tearDown();
  }

  static Workspace.Folder folder(String name, boolean open, Workspace.Entry... entries) {
    return new Workspace.Folder(UUID.randomUUID(), name, open, List.of(entries));
  }

  Workspace saved() throws IOException {
    return new WorkspaceStore(dir.resolve("config")).load();
  }

  /** After a dialog or a menu opens or closes. */
  static final String SHOWN = "frame\nadvance 1\nframe\n";

  static final String FILE = "press role=menuitem label=\"File\"\n" + SHOWN;

  /** The sidebar's "+" and the item of its menu. */
  static String add(String item) {
    return "press role=button label=\"Add\"\n" + SHOWN + "press role=menuitem label=\"" + item
        + "\"\n" + SHOWN;
  }

  /** Move to and the place in its submenu, from the row's context menu. */
  static String moveTo(String row, String place) {
    return "press role=treeitem label=\"" + row + "\" right\n" + SHOWN
        + "press role=menuitem label=\"Move to\"\n" + SHOWN + "press role=menuitem label=\""
        + place + "\"\n" + SHOWN;
  }

  /** The name typed into the folder's dialog in place of what it held, and Enter. */
  static String name(String name) {
    return "press role=textbox label=\"Name\"\nkey A control\ntype \"" + name + "\"\nframe\n"
        + "key Enter\n" + SHOWN;
  }

  private static final Pattern RECT =
      Pattern.compile("(-?[\\d.]+),(-?[\\d.]+) ([\\d.]+)x([\\d.]+)");

  /** A point in the row of the label, at the fraction of its height from its top. */
  static String at(String tree, String label, double down) {
    var row = line(tree, "role=treeitem label=\"" + label + "\"");
    var m = RECT.matcher(row.substring(row.indexOf("label=\"" + label + "\"")));
    if (!m.find()) {
      throw new AssertionError("No place in " + row);
    }
    double x = Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(3)) / 2;
    double y = Double.parseDouble(m.group(2)) + Double.parseDouble(m.group(4)) * down;
    return Math.round(x) + "," + Math.round(y);
  }

  /** Drags the row onto the point, a step at a time, with the image of it held there. */
  static String drag(String row, String to, String snap) {
    return "down role=treeitem label=\"" + row + "\"\nframe\nmove " + to + "\nframe\nmove " + to
        + "\nframe\nsnap " + snap + "\nup " + to + "\nframe\nawait\nframe\n";
  }

  @Test
  void aFolderIsMadeRenamedAndDeletedWithWhatItHeldMovedUp() throws Exception {
    var app = c.app(new Workspace(List.of(SHOP, INVENTORY)));
    c.run(
        app,
        START + add("New folder…") + "snap asked\n" + name("Prod") + "snap made\n"
            + moveTo("shop-prod", "Prod") + "snap moved\n"
            + menu("Prod", "Rename…") + "snap renaming\n" + name("Production")
            + menu("Production", "New folder…") + name("EU") + "snap nested\n"
            + menu("Production", "Delete…") + "snap deleting\n"
            + "press role=button label=\"Move contents up\"\n" + SHOWN + SETTLE + "snap end\n");
    var asked = c.tree("asked");
    assertTrue(line(asked, "role=dialog").contains("label=\"New folder\""), asked);
    assertTrue(line(asked, "label=\"Create\"").endsWith("disabled"), "a name first");
    assertEquals(List.of("shop-prod", "inventory", "Prod"), rows(c.tree("made")));
    assertTrue(line(c.tree("made"), "label=\"Prod\"").contains("selected"));
    assertEquals(List.of("inventory", "Prod", "  shop-prod"), rows(c.tree("moved")));
    assertTrue(line(c.tree("moved"), "label=\"shop-prod\"").contains("selected"));
    var renaming = c.tree("renaming");
    assertTrue(line(renaming, "role=dialog").contains("label=\"Rename folder\""), renaming);
    assertTrue(line(renaming, "role=textbox").contains("\"Prod\""), renaming);
    assertEquals(List.of("inventory", "Production", "  shop-prod", "  EU"), rows(c.tree("nested")));
    var deleting = c.tree("deleting");
    assertTrue(line(deleting, "role=dialog").contains("label=\"Delete folder\""), deleting);
    assertTrue(deleting.contains("Delete the folder Production?"), deleting);
    assertEquals(List.of("inventory", "shop-prod", "EU"), rows(c.tree("end")));
    var saved = saved();
    assertEquals(List.of(INVENTORY, SHOP), saved.connections());
    var eu = (Workspace.Folder) saved.entries().get(2);
    assertEquals("EU", eu.name());
    assertEquals(List.of(), eu.entries());
  }

  /** An empty folder goes without a question, and a workspace of one shows the tree. */
  @Test
  void anEmptyFolderIsDeletedAtOnce() throws Exception {
    var empty = folder("empty", false);
    var app = c.app(new Workspace(List.of(empty)));
    c.run(app, START + "snap start\n" + menu("empty", "Delete…") + SETTLE + "snap end\n");
    var start = c.tree("start");
    assertFalse(start.contains("No connections yet"), start);
    assertEquals(List.of("empty"), rows(start));
    var end = c.tree("end");
    assertFalse(end.contains("role=dialog"), end);
    assertTrue(end.contains("No connections yet"), end);
    assertEquals(Workspace.EMPTY, saved());
  }

  /** New things go into the folder selected, or that of the connection selected. */
  @Test
  void aNewConnectionGoesIntoTheFolderOfTheSelection() throws Exception {
    var team = folder("team", false, INVENTORY);
    var app = c.app(new Workspace(List.of(SHOP, team)));
    c.run(
        app,
        START + "press role=treeitem label=\"team\"\nframe\n" + add("New connection…")
            + """
            press role=textbox label="Name"
            type "crm"
            press role=textbox label="Host"
            type "crm.example.com"
            frame
            key Enter
            """
            + SETTLE + "snap end\n");
    assertEquals(List.of("shop-prod", "team", "  inventory", "  crm"), rows(c.tree("end")));
    var saved = saved();
    var folder = (Workspace.Folder) saved.entries().get(1);
    assertEquals(
        "crm",
        ((dev.hoori.db.core.connection.ConnectionProfile) folder.entries().get(1)).name());
    assertTrue(folder.open(), "opened to show it");
  }

  /** Whether a folder is open is saved, and the next start opens it so again. */
  @Test
  void whatIsOpenOutlastsARestart() throws Exception {
    var team = folder("team", false, INVENTORY);
    var app = c.app(new Workspace(List.of(SHOP, team)));
    c.run(app, START + open("team") + SETTLE + "snap opened\n");
    assertEquals(List.of("shop-prod", "team", "  inventory"), rows(c.tree("opened")));
    var again = c.restarted();
    c.run(
        again,
        START + "snap again\n" + "press role=treeitem label=\"team\"\nkey ArrowLeft\n" + "frame\n"
            + SETTLE);
    assertEquals(List.of("shop-prod", "team", "  inventory"), rows(c.tree("again")));
    c.run(c.restarted(), START + "snap closed\n");
    assertEquals(List.of("shop-prod", "team"), rows(c.tree("closed")));
  }

  /**
   * A row dragged into a folder, before and after another, a folder with what it holds; a
   * database below a connection stays where it is.
   */
  @Test
  void rowsAreDraggedIntoAndBesideFolders() throws Exception {
    var team = folder("team", true, INVENTORY);
    var empty = folder("empty", true);
    var app = c.app(new Workspace(List.of(SHOP, team, empty)));
    c.run(app, START + "snap start\n");
    var start = c.tree("start");
    c.run(app, drag("shop-prod", at(start, "empty", 0.5), "into") + "snap intoEnd\n");
    var into = c.tree("into");
    assertTrue(line(into, "label=\"shop-prod\"").contains("grabbed"), into);
    assertTrue(into.contains("#marker"), into);
    assertEquals(List.of("team", "  inventory", "empty", "  shop-prod"), rows(c.tree("intoEnd")));
    var placed = c.tree("intoEnd");
    c.run(app, drag("empty", at(placed, "team", 0.1), "before") + "snap beforeEnd\n");
    assertEquals(List.of("empty", "  shop-prod", "team", "  inventory"), rows(c.tree("beforeEnd")));
    var before = c.tree("beforeEnd");
    c.run(app, drag("inventory", at(before, "shop-prod", 0.9), "after") + "snap afterEnd\n");
    assertEquals(List.of("empty", "  shop-prod", "  inventory", "team"), rows(c.tree("afterEnd")));
    assertTrue(line(c.tree("afterEnd"), "label=\"inventory\"").contains("selected"));
    assertEquals(
        new Workspace(List.of(
            new Workspace.Folder(empty.id(), "empty", true, List.of(SHOP, INVENTORY)),
            new Workspace.Folder(team.id(), "team", true, List.of()))),
        saved());
  }

  /**
   * A connected connection moved by its menu and by a drag stays connected, with what is open and
   * read below it, its table's tab and its console; what is below it cannot be dragged.
   */
  @Test
  void aMoveKeepsAConnectionConnectedWithItsTabs() throws Exception {
    var team = folder("team", true);
    var app = c.app(new Workspace(List.of(SHOP, team)));
    c.ready(app, SHOP);
    c.run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + "press role=treeitem label=\"customers\"\nkey Enter\nframe\n" + SETTLE
            + menu("shop-prod", "New console") + SETTLE + "snap connected\n");
    var connected = c.tree("connected");
    var before = rows(connected);
    c.run(app, moveTo("shop-prod", "team") + SETTLE + "snap moved\n");
    var moved = c.tree("moved");
    var expected = new java.util.ArrayList<String>(List.of("team"));
    before.stream().filter(r -> !r.equals("team")).map(r -> "  " + r).forEach(expected::add);
    assertEquals(expected, rows(moved));
    assertTrue(line(moved, "#connected").contains("1 connected"), moved);
    assertTrue(moved.contains("role=tab label=\"customers"), moved);
    assertEquals(2, app.state().tabs.main().tabs().size(), moved);
    c.run(
        app,
        drag("shop", at(moved, "team", 0.1), "database") + "snap stays\n"
            + drag("shop-prod", at(moved, "team", 0.1), "dragged") + SETTLE + "snap back\n");
    assertFalse(line(c.tree("database"), "label=\"shop\"").contains("grabbed"));
    assertEquals(rows(moved), rows(c.tree("stays")));
    var back = c.tree("back");
    assertEquals(before, rows(back), "before the folder again");
    assertTrue(line(back, "#connected").contains("1 connected"), back);
    assertEquals(2, app.state().tabs.main().tabs().size(), back);
  }

  /** New folder… waits where a folder would nest deeper than a save takes. */
  @Test
  void noFolderNestsDeeperThanTheLimit() throws Exception {
    Workspace.Entry chain = folder("deepest", false);
    var deepest = chain.id();
    for (int i = 1; i < Workspace.DEPTH; i++) {
      chain = folder("f" + i, false, chain);
    }
    var app = c.app(new Workspace(List.of(chain, SHOP)));
    c.run(app, START);
    app.state().selected = new Node.Folder(deepest);
    c.run(app, "frame\n" + FILE + "snap deep\n");
    assertTrue(line(c.tree("deep"), "label=\"New folder…\"").endsWith("disabled"));
    app.state().selected = new Node.Connection(SHOP.id());
    c.run(app, "key Escape\n" + SHOWN + FILE + "snap top\n");
    assertFalse(line(c.tree("top"), "label=\"New folder…\"").endsWith("disabled"));
  }

  /**
   * The pictures for a review, light and dark, into the folder that {@code -Dreview=<folder>}
   * names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void pictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var shop = SHOP.withColor(Color.TEAL);
    var eu = folder("EU", false, INVENTORY);
    var prod = folder("Production", true, shop, eu);
    var app =
        c.app(new Workspace(List.of(prod, folder("Staging", false), folder("Archive", true))));
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    c.ready(app, shop);
    var close = "key Escape\n" + SHOWN;
    Script.run(
        START + open("shop-prod") + SETTLE + "press role=button label=\"Add\"\n" + SHOWN
            + "snap folders-add-" + look + "\n" + close + add("New folder…")
            + "type \"Reports\"\nframe\nadvance 1\nframe\nsnap folders-new-" + look + "\n" + close
            + "press role=treeitem label=\"shop-prod\" right\n" + SHOWN
            + "press role=menuitem label=\"Move to\"\n" + SHOWN + "snap folders-move-" + look
            + "\n" + close + close,
        app,
        out);
    var tree = Files.readString(out.resolve("folders-move-" + look + ".tree.txt"));
    Script.run(
        "down role=treeitem label=\"Staging\"\nframe\nmove " + at(tree, "Archive", 0.5)
            + "\nframe\nmove " + at(tree, "Archive", 0.5) + "\nframe\nsnap folders-drag-into-"
            + look + "\nmove " + at(tree, "EU", 0.1) + "\nframe\nmove " + at(tree, "EU", 0.1)
            + "\nframe\nsnap folders-drag-before-" + look + "\nkey Escape\nup "
            + at(tree, "EU", 0.1) + "\nframe\n" + menu("Production", "Delete…")
            + "snap folders-delete-" + look + "\n",
        app,
        out);
  }
}

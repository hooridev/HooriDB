package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.CatalogTreeTest.row;
import static dev.hoori.db.app.CatalogTreeTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.table;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Workspace;
import dev.hoori.db.core.connection.WorkspaceStore;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A connection's color: chosen in its dialog, shown at its row and at its tabs. */
class ConnectionColorsTest {

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

  /** The app over the connections the folder holds now, as after a restart. */
  App<State> restart() {
    var app = HooriDb.app(
        dir.resolve("config"),
        () -> new Keyring.Available(c.secrets),
        c.repository.drivers(dir.resolve("drivers")),
        Tester.SERVER,
        c.servers);
    c.apps.add(app);
    return app;
  }

  /** How many marks of the color the text has: a tree row's, a tab header's. */
  static long marks(String text, String color) {
    return text.lines()
        .filter(l -> l.contains("#color ") && l.contains("value=\"" + color + "\""))
        .count();
  }

  /** Color in the dialog: the button that opens the swatches in a popover. */
  static final String COLOR = """
      press role=button label="Color"
      frame
      advance 1
      frame
      """;

  /** Chooses a swatch in the open popover, which closes. */
  static String swatch(String name) {
    return "press role=radio label=\"" + name + "\"\nframe\nadvance 1\nframe\n";
  }

  @Test
  void aColorChosenInTheDialogShowsAtTheRowAndSurvivesARestart() throws Exception {
    var app = c.app(SHOP);
    c.run(
        app,
        START + "snap none\n" + menu("shop-prod", "Edit…") + SETTLE + "snap closed\n" + COLOR
            + "snap open\n" + swatch("Red") + "snap red\n" + COLOR + """
        key ArrowRight
        frame
        snap orange
        key ArrowLeft
        frame
        key Escape
        frame
        advance 1
        frame
        snap escaped
        press role=button label="Save"
        frame
        advance 1
        frame
        """ + SETTLE
            + "snap saved\n");
    assertFalse(row(c.tree("none"), "shop-prod").contains("#color "), "a file without colors");
    String closed = c.tree("closed");
    assertTrue(line(closed, "role=button label=\"Color\"").contains("expanded=false"), closed);
    assertTrue(line(closed, "role=button label=\"Color\"").contains("value=\"None\""), closed);
    assertFalse(closed.contains("role=radio "), "no swatches in the form: " + closed);
    String open = c.tree("open");
    assertTrue(line(open, "#popover role=dialog").contains("label=\"Color\""), open);
    assertTrue(line(open, "role=radiogroup").endsWith(" focused"), "arrows work at once: " + open);
    assertTrue(line(open, "role=button label=\"Color\"").contains(" expanded"), open);
    assertFalse(line(open, "role=button label=\"Color\"").contains("expanded=false"), open);
    assertTrue(line(open, "role=radio label=\"None\"").endsWith(" checked"), open);
    String red = c.tree("red");
    assertFalse(red.contains("role=radiogroup"), "a swatch chosen closes it: " + red);
    var button = line(red, "role=button label=\"Color\"");
    assertTrue(button.contains("value=\"Red\"") && button.contains("expanded=false"), red);
    assertTrue(line(c.tree("orange"), "role=radio label=\"Orange\"").endsWith(" checked"), "keys");
    // Escape closes the popover alone: the dialog stays, and asks nothing.
    String escaped = c.tree("escaped");
    assertFalse(escaped.contains("role=radiogroup"), escaped);
    assertTrue(escaped.contains("role=dialog label=\"Edit connection\""), escaped);
    assertFalse(escaped.contains("Discard changes?"), escaped);
    assertTrue(line(escaped, "role=button label=\"Color\"").contains("value=\"Red\""), escaped);
    // The mark beside the dialog's title follows the choice.
    assertEquals(1, marks(red, "red"), red);
    assertEquals(1, marks(c.tree("orange"), "orange"), c.tree("orange"));
    String saved = c.tree("saved");
    assertFalse(saved.contains("role=dialog"), saved);
    assertEquals(1, marks(row(saved, "shop-prod"), "red"), saved);
    assertEquals(
        Color.RED,
        new WorkspaceStore(dir.resolve("config"))
            .load()
            .connections()
            .getFirst()
            .color());

    c.run(restart(), START + "snap restarted\n");
    assertEquals(1, marks(row(c.tree("restarted"), "shop-prod"), "red"));
  }

  /** A color alone is a change: Cancel asks first; None takes the color away. */
  @Test
  void onlyAColorChangedAsksBeforeItIsDiscardedAndNoneTakesItAway() throws Exception {
    var app = c.app(SHOP.withColor(Color.RED));
    c.run(
        app,
        START + menu("shop-prod", "Edit…") + SETTLE + COLOR + swatch("Blue") + """
        key Escape
        frame
        advance 1
        frame
        snap asked
        press role=button label="Discard"
        frame
        advance 1
        frame
        """ + SETTLE
            + "snap discarded\n" + menu("shop-prod", "Edit…") + SETTLE + COLOR
            + swatch("None") + "snap none\n" + """
        press role=button label="Save"
        frame
        advance 1
        frame
        """ + SETTLE
            + "snap saved\n");
    assertTrue(c.tree("asked").contains("Discard changes?"), c.tree("asked"));
    assertEquals(1, marks(row(c.tree("discarded"), "shop-prod"), "red"), c.tree("discarded"));
    String none = c.tree("none");
    assertTrue(line(none, "role=button label=\"Color\"").contains("value=\"None\""), none);
    var dialog = none.substring(none.indexOf("role=dialog label=\"Edit connection\""));
    assertEquals(0, marks(dialog, "red"), "no mark beside the title: " + none);
    assertFalse(row(c.tree("saved"), "shop-prod").contains("#color "), c.tree("saved"));
    assertEquals(
        null,
        new WorkspaceStore(dir.resolve("config"))
            .load()
            .connections()
            .getFirst()
            .color());
  }

  @Test
  void aTabTakesItsConnectionsColorAlsoWhenItChanges() throws Exception {
    var shop = SHOP.withColor(Color.RED);
    var app = c.app(shop);
    c.ready(app, shop);
    c.run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + table("orders") + SETTLE + "snap red\n");
    String red = c.tree("red");
    assertTrue(line(red, "role=tab label=\"orders\"").contains("value=\"red\""), red);
    assertEquals(1, marks(row(red, "shop-prod"), "red"), red);
    // The row's mark and the line under the tab's header.
    assertEquals(2, marks(red, "red"), red);

    c.run(
        app,
        menu("shop-prod", "Color")
            + "press role=menuitem label=\"Green\"\nframe\nadvance 1\nframe\n" + SETTLE
            + "snap green\n");
    String green = c.tree("green");
    assertEquals("orders", tabs(green), "the tab stays");
    assertTrue(line(green, "role=tab label=\"orders\"").contains("value=\"green\""), green);
    assertEquals(2, marks(green, "green"), green);
    assertTrue(rows(green).contains("  shop"), "still connected: " + green);
    assertEquals(
        1, c.servers.calls.stream().filter(("open " + PG + "/shop")::equals).count());
    assertEquals(
        Color.GREEN,
        new WorkspaceStore(dir.resolve("config"))
            .load()
            .connections()
            .getFirst()
            .color());
  }

  @Test
  void anUnknownColorIsRefusedAsABrokenFile() throws Exception {
    new WorkspaceStore(dir.resolve("config")).save(new Workspace(java.util.List.of(SHOP)));
    var file = dir.resolve("config").resolve(WorkspaceStore.FILE);
    Files.writeString(
        file, Files.readString(file).replace("\"tls\"", "\"color\": \"crimson\", \"tls\""));
    c.run(restart(), START + "snap end\n");
    String end = c.tree("end");
    assertTrue(end.contains("Connections could not be read"), end);
    assertTrue(end.contains("\\\"color\\\" is not a color HooriDB knows"), end);
    assertFalse(end.contains("crimson"), end);
  }
}

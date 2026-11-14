package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.CatalogueTest.START;
import static dev.hoori.db.app.CatalogueTest.menu;
import static dev.hoori.db.app.CatalogueTest.open;
import static dev.hoori.db.app.CatalogueTest.row;
import static dev.hoori.db.app.CatalogueTest.rows;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.TableTabTest.table;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A connection's colour: chosen in its dialog, shown at its row and at its tabs. */
class ColoursTest {

  @TempDir
  Path dir;

  final CatalogueTest c = new CatalogueTest();

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

  /** How many marks of the colour the text has: a tree row's, a tab header's. */
  static long marks(String text, String colour) {
    return text.lines()
        .filter(l -> l.contains("#colour ") && l.contains("value=\"" + colour + "\""))
        .count();
  }

  @Test
  void aColourChosenInTheDialogShowsAtTheRowAndSurvivesARestart() throws Exception {
    var app = c.app(SHOP);
    c.run(
        app,
        START + "snap none\n" + menu("shop-prod", "Edit…") + SETTLE + """
        press role=radio label="Red"
        frame
        snap red
        key ArrowRight
        frame
        snap orange
        key ArrowLeft
        frame
        press role=button label="Save"
        frame
        advance 1
        frame
        """ + SETTLE
            + "snap saved\n");
    assertFalse(row(c.tree("none"), "shop-prod").contains("#colour "), "a file without colours");
    String red = c.tree("red");
    assertTrue(line(red, "role=radiogroup").contains("label=\"Colour\""), red);
    assertTrue(line(red, "role=radio label=\"None\"").contains("checked=false"), red);
    assertTrue(line(red, "role=radio label=\"Red\"").contains("checked"), red);
    assertFalse(line(red, "role=radio label=\"Red\"").contains("checked=false"), red);
    assertTrue(line(c.tree("orange"), "role=radio label=\"Orange\"").endsWith(" checked"), "keys");
    String saved = c.tree("saved");
    assertFalse(saved.contains("role=dialog"), saved);
    assertTrue(marks(row(saved, "shop-prod"), "red") == 1, saved);
    assertEquals(
        Colour.RED, new ProfileStore(dir.resolve("config")).load().getFirst().colour());

    c.run(restart(), START + "snap restarted\n");
    assertTrue(marks(row(c.tree("restarted"), "shop-prod"), "red") == 1);
  }

  @Test
  void aTabTakesItsConnectionsColourAlsoWhenItChanges() throws Exception {
    var shop = SHOP.withColour(Colour.RED);
    var app = c.app(shop);
    c.ready(app, shop);
    c.run(
        app,
        START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE
            + table("orders") + SETTLE + "snap red\n");
    String red = c.tree("red");
    assertTrue(line(red, "role=tab label=\"orders\"").contains("value=\"red\""), red);
    assertTrue(marks(row(red, "shop-prod"), "red") == 1, red);
    // The row's mark and the line under the tab's header.
    assertEquals(2, marks(red, "red"), red);

    c.run(
        app,
        menu("shop-prod", "Colour")
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
        Colour.GREEN, new ProfileStore(dir.resolve("config")).load().getFirst().colour());
  }

  @Test
  void anUnknownColourIsRefusedAsABrokenFile() throws Exception {
    new ProfileStore(dir.resolve("config")).save(java.util.List.of(SHOP));
    var file = dir.resolve("config").resolve(ProfileStore.FILE);
    Files.writeString(
        file, Files.readString(file).replace("\"tls\"", "\"colour\": \"crimson\", \"tls\""));
    c.run(restart(), START + "snap end\n");
    String end = c.tree("end");
    assertTrue(end.contains("Connections could not be read"), end);
    assertTrue(end.contains("\\\"colour\\\" is not a colour HooriDB knows"), end);
    assertFalse(end.contains("crimson"), end);
  }
}

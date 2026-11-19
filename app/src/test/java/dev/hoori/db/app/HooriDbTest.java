package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.secret.Keyring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooriDbTest {

  /** The app with its connections in the folder, and without a keyring. */
  static App<State> app(Path folder) {
    return HooriDb.app(
        folder.resolve("config"),
        () -> new Keyring.Unavailable(Keyring.Reason.UNSUPPORTED_PLATFORM, "a test"));
  }

  /** The app runs under a script, without a window: this is how its views are tested. */
  @Test
  void anEmptyAppSaysThatItHasNoConnections(@TempDir Path out) throws IOException {
    Script.run("""
        size 1200 760
        frame
        await
        frame
        snap start
        """, app(out), out);
    String tree = Files.readString(out.resolve("start.tree.txt"));
    assertTrue(tree.contains("role=status label=\"No connections yet\""), tree);
    assertTrue(tree.contains("role=menubar"), tree);
    assertTrue(tree.contains("role=menuitem label=\"File\""), tree);
    assertTrue(tree.contains("role=menuitem label=\"View\""), tree);
    assertTrue(tree.contains("#catalog"), tree);
    assertTrue(tree.contains("#tabs"), tree);
    assertTrue(tree.contains("role=status label=\"No tabs open\""), tree);
    assertTrue(tree.contains("role=status label=\"Status bar\""), tree);
    assertTrue(Files.size(out.resolve("start.png")) > 0);
  }

  /** The status bar says that nothing is connected, and its version opens About. */
  @Test
  void theVersionInTheStatusBarOpensAbout(@TempDir Path out) throws IOException {
    Script.run("""
        size 1200 760
        frame
        await
        frame
        snap start
        press role=button label="Development build"
        frame
        advance 1
        frame
        snap about
        """, app(out), out);
    String start = Files.readString(out.resolve("start.tree.txt"));
    String none = ConnectionsTest.line(start, "#connected");
    assertTrue(none.contains("label=\"No connections\"") && none.contains("value=\"idle\""), start);
    assertTrue(ConnectionsTest.line(start, "#version").contains("role=button"), start);
    String about = Files.readString(out.resolve("about.tree.txt"));
    assertTrue(about.contains("role=dialog label=\"About HooriDB\""), about);
  }

  /** The View menu switches the theme, and the status line says so for a while. */
  @Test
  void theViewMenuSwitchesTheTheme(@TempDir Path out) throws IOException {
    App<State> app = app(out);
    Script.run("""
        size 1200 760
        frame
        press role=menuitem label="View"
        frame
        advance 0.3
        frame
        press role=menuitem label="Light theme"
        frame
        advance 0.3
        frame
        snap light
        advance 5
        frame
        snap later
        """, app, out);
    assertSame(Theme.LIGHT, app.state().theme);
    String light = Files.readString(out.resolve("light.tree.txt"));
    assertTrue(light.contains("#message") && light.contains("\"Light theme on\""), light);
    String later = Files.readString(out.resolve("later.tree.txt"));
    assertFalse(later.contains("#message"), "the message went after its time: " + later);
  }

  /**
   * Help, About HooriDB shows the version, here a development build, its author, license and
   * website, in either theme; Close and Enter close it. It waits while another dialog is open.
   */
  @Test
  void aboutShowsTheVersionAuthorLicenseAndWebsite(@TempDir Path out) throws IOException {
    String open = """
        press role=menuitem label="Help"
        frame
        advance 1
        frame
        press role=menuitem label="About HooriDB"
        frame
        advance 1
        frame
        """;
    String close = """
        press role=button label="Close"
        frame
        advance 1
        frame
        """;
    String light = """
        press role=menuitem label="View"
        frame
        advance 1
        frame
        press role=menuitem label="Light theme"
        frame
        advance 1
        frame
        """;
    String newConnection = """
        press role=menuitem label="File"
        frame
        advance 1
        frame
        press role=menuitem label="New connection…"
        frame
        advance 1
        frame
        snap busy
        """;
    App<State> app = app(out);
    Script.run(
        "size 1200 760\nframe\nawait\nframe\n" + open + "snap dark\n" + close + "snap closed\n"
            + light + open + "snap light\nkey Enter\nframe\nadvance 1\nframe\nsnap entered\n"
            + newConnection,
        app,
        out);
    for (String name : new String[] {"dark", "light"}) {
      String tree = Files.readString(out.resolve(name + ".tree.txt"));
      assertTrue(tree.contains("role=dialog label=\"About HooriDB\""), tree);
      assertTrue(
          tree.contains("label=\"Version\" ") && tree.contains("value=\"Development build\""),
          tree);
      assertTrue(
          tree.contains("label=\"Author\" ") && tree.contains("value=\"Patrick Trautmann\""), tree);
      assertTrue(tree.contains("label=\"License\" ") && tree.contains("value=\"MIT\""), tree);
      assertTrue(
          tree.contains("label=\"Website\" ") && tree.contains("value=\"https://db.hoori.dev\""),
          tree);
    }
    for (String name : new String[] {"closed", "entered"}) {
      String tree = Files.readString(out.resolve(name + ".tree.txt"));
      assertFalse(tree.contains("role=dialog"), tree);
    }
    // The menus behind a dialog take no presses, so the item is looked at where they are made.
    String busy = Files.readString(out.resolve("busy.tree.txt"));
    assertTrue(busy.contains("role=dialog label=\"New connection\""), busy);
    var help = Frame.menus(app.state(), app.state().tabs.main(), true).getLast();
    assertEquals("&Help", help.label());
    assertFalse(help.submenu().getFirst().isEnabled());
  }

  @Test
  void exitClosesTheWindow(@TempDir Path out) throws IOException {
    App<State> app = app(out);
    Script.run("""
        size 1200 760
        frame
        press role=menuitem label="File"
        frame
        advance 0.3
        frame
        press role=menuitem label="Exit"
        frame
        """, app, out);
    assertTrue(app.windows().all().isEmpty());
  }
}

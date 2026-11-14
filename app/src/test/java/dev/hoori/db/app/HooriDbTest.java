package dev.hoori.db.app;

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
    assertTrue(tree.contains("#catalogue"), tree);
    assertTrue(tree.contains("#tabs"), tree);
    assertTrue(tree.contains("role=status label=\"No tabs open\""), tree);
    assertTrue(tree.contains("role=status label=\"Status bar\""), tree);
    assertTrue(Files.size(out.resolve("start.png")) > 0);
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

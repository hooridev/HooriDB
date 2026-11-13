package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HooriDbTest {

  /** The app runs under a script, without a window: this is how its views are tested. */
  @Test
  void anEmptyAppSaysThatItHasNoConnections(@TempDir Path out) throws IOException {
    App<HooriDb> app = new App<>(new HooriDb(), HooriDb::view);
    app.textRendering(TextRendering.LINUX);
    Script.run("""
        size 1200 760
        frame
        snap start
        """, app, out);
    String tree = Files.readString(out.resolve("start.tree.txt"));
    assertTrue(tree.contains("role=status label=\"No connections yet\""), tree);
    assertTrue(Files.size(out.resolve("start.png")) > 0);
  }
}

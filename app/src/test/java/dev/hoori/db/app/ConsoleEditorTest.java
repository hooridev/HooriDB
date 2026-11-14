package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogueTest.INVENTORY;
import static dev.hoori.db.app.CatalogueTest.PG;
import static dev.hoori.db.app.CatalogueTest.SETTLE;
import static dev.hoori.db.app.CatalogueTest.SHOP;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.Colour;
import dev.hoori.db.core.execution.Failure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A console's editor: where an error is, and what it suggests at the caret. */
class ConsoleEditorTest {

  static final String WRONG = "SELECT id\nFROM orders\nWHERE WHERE id = 1";
  static final String ITEMS = "SELECT count(*) FROM items";
  static final String MARIA_WRONG = "SELECT sku,\n  name\nFROM items\nWHERE sku IN ('a',, 'b')";

  @TempDir
  Path dir;

  final ConsoleTest console = new ConsoleTest();
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    console.dir = dir;
    console.setUp();
    servers = console
        .servers
        .statement(ITEMS, List.of(FakeServers.made(1, "count: bigint")))
        .statement(
            WRONG,
            List.of(new Failure(
                Failure.Kind.INVALID,
                "syntax error at or near \"WHERE\"",
                "42601",
                0,
                WRONG.indexOf("WHERE id") + 1,
                "",
                0,
                0,
                "",
                "",
                "",
                "")))
        .statement(
            MARIA_WRONG,
            List.of(new Failure(
                Failure.Kind.INVALID,
                "You have an error in your SQL syntax; check the manual that corresponds to your"
                    + " MariaDB server version for the right syntax to use near ' 'b')' at line 4",
                "42000",
                1064,
                0,
                "",
                0,
                4,
                " 'b')",
                "",
                "",
                "")));
  }

  @AfterEach
  void tearDown() throws Exception {
    console.tearDown();
  }

  ConsoleEditor help(App<State> app) {
    return console.tab(app).help;
  }

  static String caret(String tree) {
    return line(tree, "#sql").replaceAll(".* caret (\\d+).*", "$1");
  }

  @Test
  void anErrorInTheSecondStatementIsUnderlinedWhereTheServerSaysAndTheCaretGoesThere()
      throws Exception {
    var app = console.console(SHOP);
    var text = COUNT + ";\n" + WRONG + ";";
    int at = text.indexOf(WRONG) + WRONG.indexOf("WHERE id");
    console.c.run(app, type(text) + RUN_ALL + "snap failed\n");
    String failed = console.c.tree("failed");
    assertEquals(new Failure.Place(at, at + 5), help(app).underlined());
    assertEquals(String.valueOf(at), caret(failed), line(failed, "#sql"));
    assertTrue(failed.contains("\"syntax error at or near \\\"WHERE\\\"\""), failed);
    // Typing drops it, and the message stays.
    console.c.run(app, "type \" \"\nframe\nsnap typed\n");
    assertNull(help(app).underlined());
    assertTrue(
        console.c.tree("typed").contains("syntax error at or near"), console.c.tree("typed"));
    // Run again, it comes back; while the next run runs, it is gone.
    console.c.run(app, "key Backspace\n" + RUN_ALL);
    assertEquals(new Failure.Place(at, at + 5), help(app).underlined());
    var held = servers.hold("run " + PG + " " + COUNT);
    console.c.run(app, "key Home control\nkey Enter control\nframe\n");
    assertNull(help(app).underlined());
    held.countDown();
    console.c.run(app, SETTLE);
    assertNull(help(app).underlined());
  }

  @Test
  void mariadbsLineAndNearTextFindThePlaceInASelectedStatement() throws Exception {
    var app = console.console(INVENTORY);
    var text = ITEMS + ";\n" + MARIA_WRONG + ";";
    // The second comma of the last line: MariaDB's text begins after it.
    int at = text.indexOf(",, 'b'") + 1;
    // Selected from the start of the second line to the end.
    console.c.run(
        app,
        type(text) + "key Home control\nkey ArrowDown\nkey End control shift\nframe\n" + RUN
            + "snap failed\n");
    String failed = console.c.tree("failed");
    assertEquals(new Failure.Place(at, at + 1), help(app).underlined());
    assertEquals(String.valueOf(at), caret(failed), line(failed, "#sql"));
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
    var theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    var app = console.console(SHOP.withColour(Colour.TEAL));
    app.state().theme = theme;
    Script.run(
        type(COUNT + ";\n" + WRONG + ";") + RUN_ALL + "snap editor-error-" + look + "\n", app, out);
    var maria = console.console(INVENTORY);
    maria.state().theme = theme;
    Script.run(
        type(ITEMS + ";\n" + MARIA_WRONG + ";") + RUN_ALL + "snap editor-error-mariadb-" + look
            + "\n",
        maria,
        out);
  }
}

package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.PASSWORD;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.MISSING;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.TableTabTest.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import dev.hoori.db.core.connection.StatementLogStore;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.sql.Secrets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The statement log: what the tree, the grid and the consoles sent, in a panel below them. */
class StatementLogTest {

  static final String TOGGLE =
      "press role=button label=\"Statement log\"\nframe\nadvance 1\nframe\n";

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;
  FakeServers servers;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
    servers = k.servers;
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  /** The app with shop's public schema open in the tree. */
  App<State> shop() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, START + open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    return app;
  }

  @Test
  void thePanelIsCollapsedUntilTheStatusLineOpensIt() throws Exception {
    var app = shop();
    c.run(app, "snap collapsed\n");
    var collapsed = c.tree("collapsed");
    assertFalse(collapsed.contains("#statement-log "), collapsed);
    assertTrue(line(collapsed, "#statement-log-toggle").contains("expanded=false"), collapsed);
    // What the tree read is in the log already.
    assertTrue(
        app.state().statements.entries().stream()
            .anyMatch(e -> e.sent().purpose() == Purpose.CATALOG),
        app.state().statements.entries().toString());

    c.run(app, TOGGLE + "snap open\n");
    var open = c.tree("open");
    assertTrue(line(open, "#statement-log-toggle").contains("expanded"), open);
    assertTrue(line(open, "#statement-log ").contains("label=\"Statement log\""), open);
    assertTrue(open.contains("\"Tree\""), open);
    assertTrue(open.contains("\"tables " + CatalogTreeTest.PG + "/shop.public\""), open);
    assertTrue(open.contains("\"shop-prod\""), open);

    c.run(app, "press #statement-log-close\nframe\nadvance 1\nframe\nsnap closed\n");
    assertFalse(c.tree("closed").contains("#statement-log "), c.tree("closed"));
  }

  @Test
  void theViewMenuAndThePaletteOpenItToo() throws Exception {
    var app = shop();
    c.run(
        app,
        "press role=menuitem label=\"View\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Statement log\"\nframe\nadvance 1\nframe\nsnap menu\n");
    assertTrue(c.tree("menu").contains("#statement-log "), c.tree("menu"));
    c.run(
        app,
        "key P control shift\nframe\nadvance 1\nframe\ntype \"Statement log\"\nframe\n"
            + "key Enter\nframe\nadvance 1\nframe\nsnap palette\n");
    assertFalse(c.tree("palette").contains("#statement-log "), c.tree("palette"));
  }

  @Test
  void theGridsAndTheConsolesStatementsShowWithTheirPartAndOutcome() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT) + RUN + "press #sql\nkey A control\nkey Delete\n" + type(MISSING) + RUN);
    c.run(app, open("shop-prod") + SETTLE + open("shop") + SETTLE + open("public") + SETTLE);
    c.run(app, table("orders") + SETTLE + TOGGLE + "snap log\n");
    var log = c.tree("log");
    assertTrue(log.contains("\"" + COUNT + "\""), log);
    assertTrue(log.contains("\"1 row\""), log);
    assertTrue(log.contains("\"Console\""), log);
    assertTrue(log.contains("relation \\\"missing\\\" does not exist"), log);
    assertTrue(log.contains("\"Grid\""), log);
    assertTrue(log.contains("\"read " + CatalogTreeTest.PG + "/shop.public.orders 0-200\""), log);
    // The newest is chosen, its statement whole beside the rows.
    var last = app.state().statements.entries().getLast();
    assertTrue(line(log, "#statement-log-statement").contains(last.sent().text()), log);
    assertEquals("shop", last.sent().database());
  }

  @Test
  void aPasswordNeverShowsInThePanelNorInTheFile() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type("ALTER ROLE app PASSWORD 'role-s3cret'") + RUN);
    c.run(app, "press #sql\nkey A control\nkey Delete\n" + type("SELECT '" + PASSWORD + "'") + RUN);
    c.run(app, TOGGLE + "snap log\n" + SETTLE);
    var log = panel(c.tree("log"));
    assertFalse(log.contains("role-s3cret"), log);
    assertTrue(log.contains("ALTER ROLE app PASSWORD " + Secrets.MASK), log);
    assertFalse(log.contains(PASSWORD), log);
    assertTrue(log.contains(Recorded.WITHHELD), log);
    var file = dir.resolve("config").resolve(StatementLogStore.FILE);
    var kept = Files.readString(file);
    assertTrue(kept.contains("ALTER ROLE app PASSWORD"), kept);
    assertFalse(kept.contains("role-s3cret"), kept);
    assertFalse(kept.contains(PASSWORD), kept);
  }

  /** The panel's part of the element tree, apart from the console's text above it. */
  static String panel(String tree) {
    var lines = tree.lines().toList();
    int from = -1;
    int to = lines.size();
    for (int i = 0; i < lines.size(); i++) {
      if (from < 0 && lines.get(i).contains("#statement-log ")) {
        from = i;
      } else if (from >= 0 && lines.get(i).contains("#statement-log-toggle")) {
        to = i;
        break;
      }
    }
    if (from < 0) {
      throw new AssertionError("no statement log in\n" + tree);
    }
    return String.join("\n", lines.subList(from, to));
  }
}

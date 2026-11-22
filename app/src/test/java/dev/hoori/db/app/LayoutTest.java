package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.open;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleHistoryTest.EXIT;
import static dev.hoori.db.app.ConsoleHistoryTest.SAVED;
import static dev.hoori.db.app.StatementLogTest.TOGGLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.window.Window;
import dev.hoori.db.core.connection.UiState;
import dev.hoori.db.core.connection.UiStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The layout remembered in {@code ui-state.json}: sidebar width, statement log and window. */
class LayoutTest {

  private static final Pattern BOX = Pattern.compile(
      " -?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)? " + "(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)");
  private static final Pattern VALUE = Pattern.compile("value=\"([^\"]+)\"");

  @TempDir
  Path dir;

  final ConsoleTest k = new ConsoleTest();
  CatalogTreeTest c;

  @BeforeEach
  void setUp() throws Exception {
    k.dir = dir;
    k.setUp();
    c = k.c;
  }

  @AfterEach
  void tearDown() throws Exception {
    k.tearDown();
  }

  private Path config() {
    return dir.resolve("config");
  }

  private UiState stored() {
    return new UiStateStore(config()).load();
  }

  /** Moves the focused divider {@code steps} times with {@code key}. */
  private static String resize(String label, String key, int steps) {
    var script =
        new StringBuilder("focus role=separator label=\"").append(label).append("\"\n");
    for (int i = 0; i < steps; i++) {
      script.append("key ").append(key).append("\nframe\n");
    }
    return script.toString();
  }

  private static float widthOf(String tree, String match) {
    var line = line(tree, match);
    var matcher = BOX.matcher(line);
    assertTrue(matcher.find(), "no box in: " + line);
    return Float.parseFloat(matcher.group(1));
  }

  private static String valueOf(String tree, String match) {
    var line = line(tree, match);
    var matcher = VALUE.matcher(line);
    assertTrue(matcher.find(), "no value in: " + line);
    return matcher.group(1);
  }

  @Test
  void sidebarAndStatementLogOutlastARestart() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        START
            + open("shop-prod")
            + SETTLE
            + resize("Resize Catalog", "ArrowRight", 5)
            + TOGGLE
            + resize("Resize", "ArrowUp", 3)
            + resize("Resize Statements sent", "ArrowLeft", 3)
            + SAVED
            + "snap before\n");

    var before = c.tree("before");
    var sidebarValue = valueOf(before, "label=\"Resize Catalog\"");
    var logHeightValue = valueOf(before, "label=\"Resize\"");
    var logDetailValue = valueOf(before, "label=\"Resize Statements sent\"");
    float catalogWidth = widthOf(before, "#catalog ");

    assertTrue(Files.exists(config().resolve(UiStateStore.FILE)));
    var saved = stored();
    assertEquals(app.state().sidebar.value(), saved.sidebar());
    assertNotEquals(State.DEFAULT_SIDEBAR.value(), saved.sidebar());
    assertEquals(Boolean.TRUE, saved.logOpen());
    assertEquals(app.state().logPanel.split.value(), saved.logHeight());
    assertNotEquals(LogPanel.SPLIT.value(), saved.logHeight());
    assertEquals(app.state().logPanel.detail.value(), saved.logDetail());
    assertNotEquals(LogPanel.DETAIL.value(), saved.logDetail());

    // A new app on the same folder opens with the same sidebar width and statement log layout.
    var again = c.restarted();
    c.run(again, START + "snap again\n");
    var restored = c.tree("again");
    assertTrue(line(restored, "#statement-log-toggle").contains("expanded"), restored);
    assertTrue(restored.contains("#statement-log "), restored);
    assertEquals(sidebarValue, valueOf(restored, "label=\"Resize Catalog\""));
    assertEquals(logHeightValue, valueOf(restored, "label=\"Resize\""));
    assertEquals(logDetailValue, valueOf(restored, "label=\"Resize Statements sent\""));
    assertEquals(catalogWidth, widthOf(restored, "#catalog "));

    // Closing the statement log is remembered too, without losing the sidebar width.
    c.run(again, "press #statement-log-close\nframe\nadvance 1\nframe\n" + SAVED);
    assertEquals(Boolean.FALSE, stored().logOpen());
    assertEquals(saved.sidebar(), stored().sidebar());
    var closed = c.restarted();
    c.run(closed, START + "snap closed\n");
    var closedTree = c.tree("closed");
    assertFalse(closedTree.contains("#statement-log "), closedTree);
    assertEquals(sidebarValue, valueOf(closedTree, "label=\"Resize Catalog\""));
  }

  @Test
  void shrinkingAndGrowingTheWindowKeepsTheSidebarsWidth() throws Exception {
    var app = c.app(SHOP);
    c.run(app, START + resize("Resize Catalog", "ArrowRight", 6) + SAVED + "snap wide\n");
    var wide = c.tree("wide");
    var sidebarValue = valueOf(wide, "label=\"Resize Catalog\"");
    float wideCatalog = widthOf(wide, "#catalog ");
    var savedWidth = stored().sidebar();
    assertTrue(wideCatalog > 380, "moved wider than fits in a 640px window: " + wideCatalog);

    c.run(app, "size 640 400\n" + SAVED + "snap small\n");
    var small = c.tree("small");
    float smallCatalog = widthOf(small, "#catalog ");
    assertTrue(smallCatalog < wideCatalog, "shrunk to keep tabs' minimum: " + smallCatalog);
    assertEquals(sidebarValue, valueOf(small, "label=\"Resize Catalog\""));
    assertEquals(savedWidth, stored().sidebar());

    c.run(app, "size 1200 760\nframe\nadvance 1\nframe\nsnap back\n");
    var back = c.tree("back");
    assertEquals(wideCatalog, widthOf(back, "#catalog "));
    assertEquals(sidebarValue, valueOf(back, "label=\"Resize Catalog\""));
    assertEquals(savedWidth, stored().sidebar());
  }

  @Test
  void exitSavesPendingLayoutAndAFailedSaveNeverStopsExit() throws Exception {
    // A layout change right before Exit is flushed without waiting for the debounce pause.
    var app = c.app(SHOP);
    c.run(app, START + resize("Resize Catalog", "ArrowRight", 4) + EXIT + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "Exit closed the window");
    assertEquals(app.state().sidebar.value(), stored().sidebar());
    assertNotEquals(State.DEFAULT_SIDEBAR.value(), stored().sidebar());

    // Where ui-state.json cannot be read or written, defaults apply and Exit never stays open.
    var file = config().resolve(UiStateStore.FILE);
    Files.deleteIfExists(file);
    Files.createDirectories(file);
    var broken = c.restarted();
    assertEquals(State.DEFAULT_SIDEBAR, broken.state().sidebar);
    assertFalse(broken.state().logPanel.open);

    c.run(broken, START + resize("Resize Catalog", "ArrowRight", 3) + TOGGLE + SAVED);
    assertTrue(broken.state().notices.isEmpty(), broken.state().notices.toString());
    assertNull(broken.state().message);

    c.run(broken, resize("Resize Catalog", "ArrowRight", 2) + EXIT + SETTLE);
    assertTrue(broken.windows().all().isEmpty(), "a failed UI state save never stops Exit");
    assertTrue(broken.state().notices.isEmpty(), broken.state().notices.toString());
    assertNull(broken.state().message);
  }

  @Test
  void aLayoutThatFailedToSaveIsSavedAtExit() throws Exception {
    var file = config().resolve(UiStateStore.FILE);
    Files.createDirectories(file); // a folder in its place: the save fails
    var app = c.app(SHOP);
    c.run(app, START + TOGGLE + SAVED);
    Files.delete(file);
    c.run(app, EXIT + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "Exit closed the window");
    assertEquals(Boolean.TRUE, stored().logOpen());
  }

  @Test
  void windowPlacementParsesValidLinesFallsBackOnBadOnesAndSurvivesLayoutSaves() throws Exception {
    var placement = new Window.Placement(120, 80, 1280, 800, true, "DP-1");
    new UiStateStore(config()).save(UiState.EMPTY.withWindow(placement.toString()));

    var app = c.app(SHOP);
    assertEquals(placement, app.state().keeping.windowPlacement());
    // Saving another layout change in a script run keeps the stored window placement.
    c.run(app, START + resize("Resize Catalog", "ArrowRight", 2) + SAVED);
    var after = stored();
    assertEquals(placement.toString(), after.window());
    assertEquals(app.state().sidebar.value(), after.sidebar());

    // An unparseable window placement falls back silently to null (open as the first time).
    new UiStateStore(config()).save(UiState.EMPTY.withWindow("not a placement"));
    var bad = c.restarted();
    assertNull(bad.state().keeping.windowPlacement());
  }
}

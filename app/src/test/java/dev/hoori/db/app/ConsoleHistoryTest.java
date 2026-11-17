package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.START;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.MISSING;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.TableTabTest.cell;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.ConsoleStore;
import dev.hoori.db.core.connection.HistoryEntry.Outcome;
import dev.hoori.db.core.connection.HistoryStore;
import dev.hoori.db.core.connection.ProfileStore;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.connection.StoredConsole;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Consoles kept from one start to the next, and the history of what they ran. */
class ConsoleHistoryTest {

  static final String SAVED = "frame\nadvance 1.5\nframe\n" + SETTLE; // past the pause after typing
  static final String HISTORY = "press role=button label=\"History\"\nframe\nadvance 1\nframe\n";
  static final String EXIT = "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
      + "press role=menuitem label=\"Exit\"\nframe\n";

  static final String CLEAR = "press #sql\nkey A control\nkey Delete\n";

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

  Path config() {
    return dir.resolve("config");
  }

  List<StoredConsole> consoles() throws Exception {
    return new ConsoleStore(config()).load();
  }

  @Test
  void aConsoleComesBackWithItsTextAndConnectionNotConnected() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type("SELECT 'kept';") + SAVED);
    assertEquals(List.of(new StoredConsole(SHOP.id(), "shop", "SELECT 'kept';")), consoles());
    // One that the tree has not connected: what it has is the text and where it runs.
    var opened = servers.calls.size();
    var again = c.again(SHOP);
    c.run(again, START + "snap again\n");
    String tree = c.tree("again");
    assertTrue(tabs(tree).startsWith("shop-prod: shop"), tabs(tree));
    assertTrue(line(tree, "#sql").contains("\"SELECT 'kept';\""), tree);
    assertEquals(opened, servers.calls.size(), "nothing connects: " + servers.calls);
    assertFalse(again.state().catalog.connected(SHOP.id()));
  }

  @Test
  void consolesOfAConnectionThatIsGoneAreDroppedAndTheOthersKeepTheirOrder() throws Exception {
    var gone = java.util.UUID.randomUUID();
    new ConsoleStore(config())
        .save(List.of(
            new StoredConsole(gone, "", "-- gone"),
            new StoredConsole(SHOP.id(), "inventory", "SELECT 2;"),
            new StoredConsole(SHOP.id(), "shop", "SELECT 3;")));
    var app = c.again(SHOP);
    c.run(app, START + "snap restored\n");
    var restored = app.state().consoles(null).stream().map(ConsoleTab::stored).toList();
    assertEquals(
        List.of(
            new StoredConsole(SHOP.id(), "inventory", "SELECT 2;"),
            new StoredConsole(SHOP.id(), "shop", "SELECT 3;")),
        restored);
    // Nothing was saved yet: the file keeps the one that is gone until the next change.
    assertEquals(3, consoles().size());
    c.run(app, "key W control\nframe\n" + SETTLE);
    assertEquals(List.of(restored.getFirst()), consoles(), "a console closed is no longer kept");
  }

  @Test
  void aDeletedConnectionTakesItsConsolesAndItsHistoryAlong() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT) + RUN + SAVED);
    assertEquals(1, consoles().size());
    assertEquals(1, new HistoryStore(config()).load().size());
    c.run(
        app,
        menu(SHOP.name(), "Delete…") + "press role=button label=\"Disconnect\"\nframe\n" + SETTLE
            + "press role=button label=\"Delete\"\nframe\n"
            + SETTLE + "snap deleted\n");
    assertEquals(List.of(), consoles());
    assertEquals(List.of(), new HistoryStore(config()).load());
    assertEquals(List.of(), app.state().history);
  }

  /** The History dialog's part of the tree, whose cells are not the console's. */
  static String dialog(String tree) {
    return tree.substring(tree.indexOf("#history-dialog"));
  }

  /** The console with four statements run, each ending another way. */
  App<State> ran() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT) + RUN + CLEAR + type(PAY) + RUN);
    c.run(app, CLEAR + type(MISSING) + RUN);
    var held = servers.hold("run " + PG + " " + ORDERS);
    c.run(app, CLEAR + type(ORDERS) + "key Enter control\nframe\n");
    c.run(app, "press role=button label=\"Cancel\"\nframe\nframe\n");
    held.countDown();
    c.run(app, SETTLE + CLEAR + SAVED);
    return app;
  }

  @Test
  void eachStatementRunGoesIntoTheHistoryWithItsOutcome() throws Exception {
    var app = ran();
    var entries = new HistoryStore(config()).load();
    assertEquals(
        List.of(COUNT, PAY, MISSING, ORDERS),
        entries.stream().map(e -> e.statement()).toList());
    assertEquals(
        List.of(
            new Outcome.Rows(),
            new Outcome.Changed(3),
            new Outcome.Failed("relation \"missing\" does not exist"),
            new Outcome.Cancelled()),
        entries.stream().map(e -> e.outcome()).toList());
    assertTrue(entries.stream().allMatch(e -> e.connection().equals(SHOP.id())));
    assertEquals(4, entries.getFirst().took().toMillis(), "the server's time");
    assertEquals(entries, app.state().history);
  }

  @Test
  void historyListsTheNewestFirstFindsAndInsertsAtTheCaret() throws Exception {
    var app = ran();
    c.run(app, type("-- before\n\n-- after") + "key ArrowUp\n" + HISTORY + "snap history\n");
    String history = dialog(c.tree("history"));
    assertTrue(line(history, "role=dialog").contains("label=\"History of shop-prod\""), history);
    assertTrue(line(history, "#history-search").contains("focused"), history);
    assertTrue(cell(history, 0, 0).contains("\"" + ORDERS + "\""), history);
    assertTrue(cell(history, 0, 2).contains("ms\""), history);
    assertTrue(cell(history, 0, 3).contains("\"Cancelled\""), history);
    assertTrue(cell(history, 1, 3).contains("relation \\\"missing\\\" does not exist"), history);
    assertTrue(cell(history, 2, 3).contains("\"3 rows changed\""), history);
    assertTrue(cell(history, 3, 3).contains("\"Rows\""), history);
    assertTrue(line(history, "#row-0").contains("selected"), "the newest is chosen: " + history);
    // Any case, any part.
    c.run(app, "type \"FROM o\"\nframe\nframe\nsnap found\n");
    String found = dialog(c.tree("found"));
    assertTrue(cell(found, 0, 0).contains("\"" + ORDERS + "\""), found);
    assertTrue(cell(found, 1, 0).contains("\"" + COUNT + "\""), found);
    assertFalse(found.contains("#cell-2-0"), found);
    // The second, by the keyboard: Enter puts it in at the caret, on the empty line.
    c.run(app, "press #cell-1-0\nframe\nkey Enter\nframe\nadvance 1\nframe\nsnap inserted\n");
    String inserted = c.tree("inserted");
    assertFalse(inserted.contains("role=dialog"), inserted);
    assertEquals("-- before\n" + COUNT + "\n-- after", k.tab(app).text);
    assertTrue(line(inserted, "#sql").contains("focused"), inserted);
    // As one step, which Undo takes back.
    c.run(app, "key Z control\nframe\n");
    assertEquals("-- before\n\n-- after", k.tab(app).text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"key Enter", "press #cell-0-0 clicks 2", "press #history-insert"})
  void enterFromTheSearchADoublePressAndInsertEachInsertTheChosenOne(String how) throws Exception {
    var app = ran();
    c.run(
        app,
        HISTORY + "type \"count\"\nframe\nframe\n" + how + "\nframe\nadvance 1\nframe\n"
            + "snap inserted\n");
    assertFalse(c.tree("inserted").contains("role=dialog"), c.tree("inserted"));
    assertEquals(COUNT, k.tab(app).text);
  }

  @Test
  void aSearchThatFindsNothingSaysSoAndInsertWaits() throws Exception {
    var app = ran();
    c.run(app, HISTORY + "type \"nothing like it\"\nframe\nframe\nsnap none\n");
    String none = c.tree("none");
    assertTrue(none.contains("No statement holds"), none);
    assertTrue(line(none, "label=\"Insert\"").contains("disabled"), none);
  }

  @Test
  void clearHistoryAsksAndThenDeletesTheConnectionsStatements() throws Exception {
    var app = ran();
    c.run(
        app,
        HISTORY + "press role=button label=\"Clear history\"\nframe\nadvance 1\nframe\nsnap ask\n"
            + "press role=button label=\"Clear\"\nframe\nadvance 1\nframe\n" + SETTLE
            + "snap cleared\n");
    String ask = c.tree("ask");
    assertTrue(ask.contains("label=\"Clear the history of shop-prod?\""), ask);
    assertTrue(ask.contains("\"Its 4 statements are deleted from this computer.\""), ask);
    String cleared = c.tree("cleared");
    assertTrue(cleared.contains("\"No statements yet\""), cleared);
    assertTrue(line(cleared, "label=\"Clear history\"").contains("disabled"), cleared);
    assertEquals(List.of(), new HistoryStore(config()).load());
  }

  @Test
  void exitSavesTheTextTypedJustBefore() throws Exception {
    var app = k.console(SHOP);
    // No time passes for the pause after typing: Exit itself saves.
    c.run(app, type("SELECT 'last words';") + EXIT + "await\nframe\n".repeat(4));
    assertEquals("SELECT 'last words';", consoles().getFirst().text());
    assertTrue(app.windows().all().isEmpty(), "and then closes");
  }

  @Test
  void exitStaysOpenOnceWhereTheConsolesWereNotReadAndCannotBeSaved() throws Exception {
    Files.createDirectories(config().resolve(ConsoleStore.FILE)); // fails to read, not unreadable
    var app = c.again(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        START + menu(SHOP.name(), "New console") + SETTLE + type("SELECT 'unkept';") + EXIT
            + "await\nframe\n".repeat(4) + "snap stayed\n");
    assertFalse(app.windows().all().isEmpty(), "it stays open");
    assertTrue(
        c.tree("stayed").contains("the consoles were not read, so they cannot be saved"),
        c.tree("stayed"));
    c.run(app, EXIT + "await\nframe\n".repeat(4));
    assertTrue(app.windows().all().isEmpty(), "the second Exit quits without them");
  }

  @Test
  void consolesOfConnectionsSetAsideStayInTheirFile() throws Exception {
    Files.createDirectories(config());
    Files.writeString(config().resolve(ProfileStore.FILE), "{"); // read, and not understood
    var kept = List.of(new StoredConsole(SHOP.id(), "shop", "SELECT 'set aside';"));
    new ConsoleStore(config()).save(kept);
    var app = HooriDb.app(
        config(),
        () -> new Keyring.Available(c.secrets),
        c.repository.drivers(dir.resolve("drivers")),
        Tester.SERVER,
        servers);
    c.apps.add(app);
    var s = app.state();
    c.run(app, START + SETTLE);
    var made = ConnectionProfile.of(Provider.POSTGRESQL).withName("new").withHost(PG);
    app.post(() -> s.put(made));
    c.run(app, "await\nframe\n" + SETTLE);
    assertEquals(null, s.loadError, "the connections are saved anew");
    app.post(() -> s.openConsole(made.id())); // a change of the consoles, which is not saved
    c.run(app, "await\nframe\n" + SETTLE);
    assertEquals(1, s.consoles(null).size());
    assertEquals(kept, consoles());
  }

  @Test
  void aConsoleStaysWithItsTextWhereItsConnectionMovesToAnotherDatabase() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type("SELECT 'kept';") + SAVED);
    var s = app.state();
    app.post(() -> s.put(s.profile(SHOP.id()).withDatabase("analytics")));
    c.run(app, "await\nframe\n" + SETTLE);
    assertEquals(
        List.of(new StoredConsole(SHOP.id(), "shop", "SELECT 'kept';")),
        s.consoles(null).stream().map(ConsoleTab::stored).toList(),
        "it stays open, on its database");
  }

  @Test
  void aCancelAfterTheStatementEndedKeepsHowItEnded() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    var tab = k.tab(app);
    c.run(app, type("\n" + PAY) + "key Enter control\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(app, "press role=button label=\"Cancel\"\nframe\n" + SETTLE);
    assertEquals(new Outcome.Changed(3), app.state().history.getLast().outcome());
  }

  @Test
  void aReadThatFailsInItsLaterRowsIsKeptAsFailed() throws Exception {
    var sql = "SELECT * FROM late";
    servers.statement(
        sql,
        List.of(
            FakeServers.made(300, "id: bigint"),
            FakeServers.serverError(Failure.Kind.OTHER, "division by zero")));
    var app = k.console(SHOP);
    c.run(app, type(sql) + RUN);
    assertEquals(new Outcome.Rows(), app.state().history.getLast().outcome());
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE);
    assertEquals(
        new Outcome.Failed("division by zero"), app.state().history.getLast().outcome());
    assertEquals(1, app.state().history.size());
  }

  @Test
  void aStatementLongerThanTheHistoryKeepsIsNotKept() throws Exception {
    var app = k.console(SHOP);
    var tab = k.tab(app);
    tab.text = "SELECT '" + "x".repeat(HistoryStore.LONGEST) + "'";
    tab.editor.select(0, 0); // the editor has the focus, the caret before it
    c.run(app, "frame\n" + RUN + "snap long\n");
    // Sent, and the made-up server does not know it: short, it would be kept as failed.
    assertTrue(line(c.tree("long"), "#run-status").contains("Failed after"), c.tree("long"));
    assertEquals(List.of(), app.state().history);
  }

  /**
   * The pictures for a review: the History dialog with an entry of each outcome, with a search,
   * and the console's toolbar with its History button, written where {@code -Dreview=<folder>}
   * names; skipped without it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"dark", "light"})
  void pictures(String look) throws Exception {
    var review = System.getProperty("review");
    assumeTrue(review != null, "-Dreview=<folder> asks for the pictures");
    var out = Files.createDirectories(Path.of(review));
    var app = ran();
    app.state().theme = look.equals("dark") ? Theme.DARK : Theme.LIGHT;
    app.invalidate(); // built again in it, without an input
    Script.run(
        "frame\nadvance 1\nframe\nsnap console-toolbar-" + look + "\n" + HISTORY + "snap history-"
            + look + "\n" + "type \"orders\"\nframe\nframe\nsnap history-search-" + look + "\n",
        app,
        out);
  }
}

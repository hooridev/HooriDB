package dev.hoori.db.app;

import static dev.hoori.db.app.CatalogTreeTest.INVENTORY;
import static dev.hoori.db.app.CatalogTreeTest.MARIA;
import static dev.hoori.db.app.CatalogTreeTest.PG;
import static dev.hoori.db.app.CatalogTreeTest.SETTLE;
import static dev.hoori.db.app.CatalogTreeTest.SHOP;
import static dev.hoori.db.app.CatalogTreeTest.eventually;
import static dev.hoori.db.app.CatalogTreeTest.menu;
import static dev.hoori.db.app.ConnectionsTest.line;
import static dev.hoori.db.app.ConsoleTest.COUNT;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.type;
import static dev.hoori.db.app.TableTabTest.tabs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** What asks before a console's transaction ends, and what waits for its answer or a save. */
class ConsoleTransactionQuestionTest extends ConsoleTransactions {

  @Test
  void theStatusBarBringsAConsoleThatMayHoldUncommittedWorkForward() throws Exception {
    var app = open(SHOP);
    c.run(app, menu(SHOP.name(), "New console") + MANUAL + type(PAY) + RUN + "snap other\n");
    String other = c.tree("other");
    String first = "role=tab label=\"shop-prod: shop (1)\"";
    assertFalse(line(other, first).contains("selected"), other);
    String two = "2 consoles may hold uncommitted work";
    assertTrue(line(other, "#uncommitted").contains("role=button label=\"" + two + "\""), other);
    c.run(app, "press role=button label=\"" + two + "\"\nframe\nadvance 1\nframe\nsnap shown\n");
    assertTrue(line(c.tree("shown"), first).contains("selected"), c.tree("shown"));
  }

  /**
   * Nor while a dialog is open: About in the main window, or a password asked in the window of the
   * console it would show, whose tab it would switch behind the dialog.
   */
  @Test
  void theStatusBarLeavesAWindowAskingForAPasswordAsItIs() throws Exception {
    servers.serve(MARIA, Provider.MARIADB, "11.4.2-MariaDB", CatalogTreeTest.MARIA_OUTLINE, "x");
    var app = c.app(SHOP, INVENTORY);
    c.ready(app, SHOP, INVENTORY);
    String item = "1 console may hold uncommitted work";
    c.run(
        app,
        CatalogTreeTest.START + menu(SHOP.name(), "New console") + SETTLE + MANUAL + type(PAY)
            + RUN + "press role=menuitem label=\"Help\"\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"About HooriDB\"\nframe\nadvance 1\nframe\n"
            + "snap about\npress role=button label=\"Close\"\nframe\nadvance 1\nframe\n"
            + menu(INVENTORY.name(), "New console") + SETTLE
            + "press role=tab label=\"inventory\" right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n");
    String about = c.tree("about");
    assertFalse(line(about, "#version").contains("role=button"), about);
    assertFalse(line(about, "#uncommitted").contains("role=button"), about);
    // The console that may hold work joins the window of the other connection's.
    var s = app.state();
    var shop = s.consoles(SHOP.id()).getFirst();
    var inventory = s.consoles(INVENTORY.id()).getFirst();
    var window = s.tabs.groupOf(inventory);
    var main = s.tabs.main();
    // In a frame, as a drag's release, so that the windows it changes are built again.
    app.post(
        () -> s.tabs.dock(main, main.tabs().indexOf(shop), window, window.tabs().size()));
    String title = "window \"" + s.title(inventory) + "\"\n";
    c.run(
        app,
        "await\nframe\n" + title + "frame\nadvance 1\nframe\n"
            + "press role=tab label=\"inventory\"\nframe\nadvance 1\nframe\n" + type(COUNT) + RUN
            + "press role=button label=\"Enter password…\"\nframe\nadvance 1\nframe\n"
            + "window main\nframe\nsnap main\npress #uncommitted\nframe\n" + title
            + "frame\nsnap asked\n");
    String bar = c.tree("main");
    assertTrue(line(bar, "#uncommitted").contains("label=\"" + item + "\""), bar);
    assertFalse(line(bar, "#uncommitted").contains("role=button"), "no press: " + bar);
    assertEquals(inventory, window.activeTab());
    String asked = c.tree("inventory-asked");
    assertTrue(asked.contains("role=dialog label=\"Password for inventory\""), asked);
  }

  @Test
  void closingTheTabAsksAndCancelStays() throws Exception {
    var app = open(SHOP);
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
    assertTrue(
        asked.contains(
            "after 1 statement. Commit it or roll it back before the" + " console closes."),
        asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE + "snap stayed\n");
    String stayed = c.tree("stayed");
    assertFalse(stayed.contains("role=dialog"), stayed);
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
  }

  @Test
  void controlWAndTheMenusCloseTabAskToo() throws Exception {
    var app = open(SHOP);
    c.run(app, "key W control\nframe\n" + OPENED + "snap key\n");
    assertTrue(c.tree("key").contains("role=dialog label=\"Commit the transaction?\""));
    c.run(
        app,
        "press #answer-cancel\nframe\n" + OPENED
            + "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Close tab\"\nframe\n" + OPENED + "snap menu\n");
    assertTrue(c.tree("menu").contains("role=dialog label=\"Commit the transaction?\""));
    assertEquals(1, app.state().consoles(null).size());
  }

  /** In a window of its own, Control+W and the palette's Close tab ask as the main window's do. */
  @Test
  void aTornOffWindowsKeysAndPaletteAskToo() throws Exception {
    var app = open(SHOP);
    var window = "shop-prod: shop";
    c.run(app, tearOff(window) + in(window) + "key W control\nframe\n" + OPENED + "snap key\n");
    String key = c.tree("shop-prod-shop-key");
    assertTrue(key.contains("role=dialog label=\"Commit the transaction?\""), key);
    c.run(
        app,
        in(window) + "press #answer-cancel\nframe\n" + OPENED + PaletteTest.PALETTE
            + "type \"close tab\"\nframe\nkey Enter\n" + PaletteTest.SHOWN + OPENED
            + "snap palette\n");
    String palette = c.tree("shop-prod-shop-palette");
    assertTrue(palette.contains("role=dialog label=\"Commit the transaction?\""), palette);
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  @Test
  void closingTheTabCommitsOrRollsBackFirst() throws Exception {
    for (var answer : List.of("COMMIT", "ROLLBACK")) {
      var app = open(SHOP);
      c.run(
          app,
          "press #close-0\nframe\n" + OPENED + "press #answer-" + answer.toLowerCase() + "\nframe\n"
              + OPENED + SETTLE);
      assertTrue(servers.calls.contains("run " + PG + " " + answer), servers.calls.toString());
      assertTrue(app.state().consoles(null).isEmpty(), answer);
      servers.calls.clear();
    }
  }

  @Test
  void switchingToAutoAsks() throws Exception {
    var app = open(SHOP);
    c.run(app, "press role=radio label=\"Auto\"\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to Auto."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(ConsoleTab.Mode.MANUAL, k.tab(app).mode);
    c.run(
        app,
        "press role=radio label=\"Auto\"\nframe\n" + OPENED + "press #answer-commit\nframe\n"
            + OPENED + SETTLE + "snap auto\n");
    String auto = c.tree("auto");
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(line(auto, "role=radio label=\"Auto\"").contains("checked"), auto);
    assertFalse(auto.contains("#transaction"), auto);
    // The next statement runs with auto-commit on.
    c.run(app, "press #sql\n" + RUN);
    assertTrue(servers.calls.contains("autocommit " + PG + " on"), servers.calls.toString());
    assertEquals(Transaction.NONE, k.tab(app).transaction);
  }

  @Test
  void switchingTheDatabaseAsks() throws Exception {
    var app = open(SHOP);
    var choose = "press #database\nframe\n" + OPENED
        + "press role=menuitem label=\"analytics\"\nframe\n" + OPENED;
    c.run(app, choose + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to analytics."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    assertEquals("shop", k.tab(app).database);
    c.run(app, choose + "press #answer-rollback\nframe\n" + OPENED + SETTLE + "snap switched\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(tabs(c.tree("switched")).startsWith("shop-prod: analytics,"), c.tree("switched"));
  }

  @Test
  void closingHooriDbAsks() throws Exception {
    var app = open(SHOP);
    var exit = "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
        + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED;
    c.run(app, exit + "snap asked\n");
    assertTrue(c.tree("asked").contains("before HooriDB closes."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    c.run(app, exit + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void closingHooriDbCommitsFirst() throws Exception {
    var app = open(SHOP);
    c.run(
        app,
        "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"Exit\"\nframe\n" + OPENED
            + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertTrue(servers.calls.contains("run " + PG + " COMMIT"), servers.calls.toString());
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void disconnectingAsksAndCancelLeavesItConnected() throws Exception {
    var app = open(SHOP);
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
    assertTrue(asked.contains("before shop-prod disconnects."), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().catalog.connected(SHOP.id()));
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    assertTrue(servers.closed.isEmpty(), servers.closed.toString());
    c.run(
        app,
        menu("shop-prod", "Disconnect") + OPENED + "press #answer-rollback\nframe\n" + OPENED
            + SETTLE + "snap ended\n");
    assertTrue(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertFalse(app.state().catalog.connected(SHOP.id()));
    assertFalse(k.tab(app).connected());
    assertFalse(c.tree("ended").contains("role=dialog"), c.tree("ended"));
    eventually(() -> servers.closed.contains(PG + "/shop"));
  }

  /** 3: closing while Commit runs waits for its answer, and queues no rollback behind it. */
  @Test
  void closingWhileCommitRunsWaitsForItsOutcome() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, "press role=button label=\"Commit\"\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(
        app,
        "press role=button label=\"Commit\"\nframe\npress #close-0\nframe\n" + OPENED
            + "snap waiting\n");
    String waiting = c.tree("waiting");
    assertTrue(waiting.contains("role=dialog label=\"Waiting for the commit\""), waiting);
    assertFalse(waiting.contains("#answer-rollback"), waiting);
    assertTrue(line(waiting, "#cancel").contains("disabled"), "a commit runs to its end");
    gate.countDown();
    c.run(app, SETTLE);
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /**
   * 4: a first Manual statement that still runs may have changed something: the console waits for
   * it, or goes on with the plain word that what the server did with it will not be known; it
   * offers no Rollback that would promise anything.
   */
  @Test
  void closingWhileTheFirstManualStatementRunsWaitsOrGoesOnPlainly() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN + MANUAL);
    var key = "run " + PG + " " + PAY;
    var gate = servers.hold(key);
    c.run(app, type("\n" + PAY) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("role=dialog label=\"Waiting for " + PAY + "\""), asked);
    // The dump cuts the long text short: its end, of what will not be known, is in the picture.
    assertTrue(asked.contains(PAY + " still runs on shop-prod: shop. Wait"), asked);
    assertTrue(line(asked, "#answer-go-on").contains("label=\"Go on now\""), asked);
    assertFalse(asked.contains("#answer-commit"), asked);
    assertFalse(asked.contains("#answer-rollback"), asked);
    c.run(app, "press #answer-cancel\nframe\n" + OPENED);
    gate.countDown();
    c.run(app, SETTLE + "snap stayed\n");
    assertEquals(1, app.state().consoles(null).size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    // Go on now: the connection ends at once, and the tab closes without a claim.
    var held = "run " + PG + " " + BROKEN;
    servers.hold(held);
    c.run(app, type(";\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(held));
    c.run(
        app, "press #close-0\nframe\n" + OPENED + "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
  }

  /** 7: the tree's connection is not the console's: a color while it is lost ends nothing. */
  @Test
  void aColorWhileTheTreeIsLostLeavesTheConsoleAlone() throws Exception {
    var app = open(SHOP);
    servers.fail(
        "tables " + PG + "/shop.public",
        FakeServers.notRead(CatalogException.Failure.CONNECTION_LOST));
    c.run(app, CatalogTreeTest.open("shop") + SETTLE + CatalogTreeTest.open("public") + SETTLE);
    assertFalse(app.state().catalog.active(SHOP.id()), "the tree's connection is lost");
    app.state().put(SHOP.withColor(Color.RED));
    // And as the tree's connection is given up, as when its password is not given.
    app.state().catalog.drop(app.state(), SHOP.id());
    c.run(app, "frame\n" + SETTLE + "snap colored\n");
    String colored = c.tree("colored");
    assertFalse(colored.contains("role=dialog"), colored);
    assertTrue(k.tab(app).connected());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /**
   * Third review, 7: an Exit that waits for a save is the one pending act, armed: it locks every
   * console until it is done, so that nothing can come between and nothing older can resume later.
   */
  @Test
  void anExitThatWaitsForASaveLocksEveryConsole() throws Exception {
    var app = k.console(SHOP);
    var window = "window \"shop-prod: shop\"\n";
    c.run(
        app,
        type(COUNT + ";") + RUN + MANUAL
            + "press role=tab label=\"shop-prod: shop\" right\nframe\nadvance 1\nframe\n"
            + "press role=menuitem label=\"Move to new window\"\nframe\nadvance 1\nframe\n");
    // A new connection, whose password the keyring holds: HooriDB closes once it is saved.
    c.secrets.gate = new CountDownLatch(1);
    c.run(
        app,
        "window main\n" + ConnectionsTest.viaAdd(ConnectionsTest.NEW_SHOP)
            + "key Enter\nframe\nclose\nframe\n");
    assertFalse(app.windows().all().isEmpty());
    // The console, in its own window, starts nothing meanwhile, and says why.
    c.run(app, window + type("\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    String locked = c.tree("shop-prod-shop-locked");
    assertTrue(line(locked, "#locked").contains("HooriDB is closing"), locked);
    assertTrue(line(locked, "#run ").contains("disabled"), locked);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
  }

  /** Third review, 7: an Exit whose question was cancelled is over; a later save resumes nothing. */
  @Test
  void anExitCancelledAtItsQuestionIsOverForGood() throws Exception {
    var app = open(SHOP);
    c.run(app, EXIT + "press #answer-cancel\nframe\n" + OPENED);
    assertEquals(null, app.state().pending);
    app.state().put(SHOP.withColor(Color.GREEN)); // a save of the connections, which settles
    c.run(app, "frame\n" + SETTLE + "press #sql\n" + RUN + COMMIT_BUTTON + SETTLE);
    assertFalse(app.windows().all().isEmpty());
  }

  /** 9: the switch the question asked about goes on to its own database once nothing is open. */
  @Test
  void aSwitchAskedWhileTheServerWasAskedGoesToItsDatabase() throws Exception {
    ConsoleTest.twoDatabases(servers);
    var app = open(INVENTORY);
    var probe = servers.hold("transaction " + MARIA, 1); // past the statement's own
    long asked = probes(MARIA);
    c.run(app, type(";\n" + DEADLOCKED) + "key Enter control\nframe\nawait\nframe\n");
    eventually(() -> probes(MARIA) > asked); // held: what the statement left is asked
    c.run(
        app,
        "frame\npress #database\nframe\n" + OPENED
            + "press role=menuitem label=\"inventory\"\nframe\n" + OPENED + "snap asked\n");
    assertTrue(c.tree("asked").contains("before it switches to inventory."), c.tree("asked"));
    probe.countDown(); // the deadlock rolled it back: nothing is open
    c.run(app, SETTLE + OPENED + "snap switched\n");
    assertTrue(servers.calls.contains("use " + MARIA + " inventory"), servers.calls.toString());
    assertTrue(tabs(c.tree("switched")).startsWith("inventory: inventory,"), c.tree("switched"));
    assertFalse(c.tree("switched").contains("role=dialog"), c.tree("switched"));
  }

  /** 10: with chained completion, the user's Commit or Rollback still lets the way out go on. */
  @Test
  void aTransactionTheServerChainsAfterTheAnswerDoesNotHoldTheTabOpen() throws Exception {
    for (var answer : List.of("commit", "rollback")) {
      var app = k.console(INVENTORY);
      c.run(app, type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction);
      c.run(
          app,
          "press #close-0\nframe\n" + OPENED + "press #answer-" + answer + "\nframe\n" + OPENED
              + SETTLE);
      assertTrue(app.state().consoles(null).isEmpty(), answer);
    }
    // And Exit, and the switch to Auto, whose auto-commit then ends the empty one.
    var app = k.console(INVENTORY);
    c.run(app, type(CHAIN) + RUN + MANUAL + type(";\n" + PAY) + RUN);
    c.run(
        app,
        "press role=radio label=\"Auto\"\nframe\n" + OPENED + "press #answer-commit\nframe\n"
            + OPENED + SETTLE + "snap auto\n");
    assertTrue(
        line(c.tree("auto"), "role=radio label=\"Auto\"").contains("checked"), c.tree("auto"));
    assertTrue(servers.calls.contains("autocommit " + MARIA + " on"), servers.calls.toString());
    // Exit waits for the session it ends to say how it closed.
    c.run(app, type(";\n" + PAY) + RUN + EXIT + SETTLE);
    assertTrue(app.windows().all().isEmpty());
  }

  @Test
  void exitAsksEachConsoleAndCancelAtAnyKeepsItAllOpen() throws Exception {
    var app = open(SHOP);
    c.run(app, menu("shop-prod", "New console") + MANUAL + type(PAY) + RUN);
    var consoles = app.state().consoles(null);
    assertEquals(2, consoles.size());
    assertEquals(Transaction.OPEN, consoles.get(1).transaction);
    // The first answers Commit; the second Cancel.
    c.run(app, EXIT + "snap first\n");
    assertTrue(c.tree("first").contains("shop-prod: shop (1) has a transaction"), c.tree("first"));
    c.run(app, "press #answer-commit\nframe\n" + OPENED + SETTLE + OPENED + "snap second\n");
    assertTrue(
        c.tree("second").contains("shop-prod: shop (2) has a transaction"), c.tree("second"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(Transaction.NONE, consoles.get(0).transaction);
    assertEquals(Transaction.OPEN, consoles.get(1).transaction);
    c.run(app, EXIT + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.windows().all().isEmpty());
    assertEquals(1, servers.calls.stream().filter(COMMITTING::equals).count());
  }

  @Test
  void editingTheConnectionAsksItsConsolesFirst() throws Exception {
    var app = open(SHOP);
    var edit =
        menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED;
    c.run(app, edit + "snap asked\n");
    assertTrue(c.tree("asked").contains("before shop-prod disconnects."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE + "snap stayed\n");
    assertTrue(app.state().catalog.connected(SHOP.id()));
    assertEquals(null, app.state().dialog, "no edit before the console's answer");
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
    c.run(app, edit + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().dialog != null, "the edit goes on");
    assertFalse(k.tab(app).connected());
    // A color leaves the console's session as it is.
    var colored = open(SHOP);
    colored.state().put(SHOP.withColor(Color.BLUE));
    c.run(colored, "frame\n" + SETTLE);
    assertTrue(k.tab(colored).connected());
    assertEquals(Transaction.OPEN, k.tab(colored).transaction);
  }

  @Test
  void deletingTheConnectionAsksItsConsolesFirst() throws Exception {
    var app = open(SHOP);
    c.run(
        app,
        menu("shop-prod", "Delete…") + "press role=button label=\"Disconnect\"\nframe\n" + OPENED
            + "snap asked\n");
    assertTrue(c.tree("asked").contains("before shop-prod disconnects."), c.tree("asked"));
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    assertEquals(null, app.state().deleting);
    assertEquals(1, app.state().profiles.size());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /**
   * Third review, 6: once the questions are answered, Save locks the connection's consoles until it
   * is done, so that nothing comes between the answers and the act, and no keyring step is left half
   * applied by a question at the act.
   */
  @Test
  void aSaveLocksItsConsolesUntilItIsDone() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Edit…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    // The edit is open; the console has a Manual session again, with nothing at stake.
    c.run(app, in(window) + MANUAL + "press #sql\n" + RUN + COMMIT_BUTTON);
    assertFalse(k.tab(app).atStake());
    c.secrets.gate = new CountDownLatch(1);
    c.run(
        app,
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"hunter2\"\n"
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\n");
    assertTrue(app.state().dialog.changing, "the keyring works");
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    String locked = c.tree("shop-prod-shop-locked");
    assertTrue(line(locked, "#locked").contains("shop-prod is being saved"), locked);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE + in(window) + OPENED + "snap saved\n");
    assertFalse(c.tree("shop-prod-shop-saved").contains("role=dialog"));
    assertEquals(SHOP.user() + "2", app.state().profile(SHOP.id()).user());
    assertEquals(null, app.state().dialog);
    assertEquals(null, app.state().pending);
    assertFalse(k.tab(app).connected(), "its session, opened with what changed, ended");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
  }

  /** Third review, 6: so with Delete, whose password then goes for good only with the profile. */
  @Test
  void aDeleteLocksItsConsolesUntilItIsDone() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + "press #sql\n" + RUN + COMMIT_BUTTON);
    assertFalse(k.tab(app).atStake());
    c.secrets.gate = new CountDownLatch(1);
    c.run(app, in("main") + "press role=button label=\"Delete\"\nframe\n");
    assertTrue(app.state().removal.running, "the keyring works");
    c.run(app, in(window) + type(";\n" + PAY) + "key Enter control\nframe\nframe\nsnap locked\n");
    assertTrue(
        line(c.tree("shop-prod-shop-locked"), "#locked").contains("shop-prod is being deleted"));
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
    assertTrue(app.state().profiles.isEmpty());
    assertEquals(null, app.state().pending);
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
  }

  /** New 3: a typed COMMIT is waited for, never cancelled into a rollback. */
  @Test
  void closingWhileATypedCommitRunsWaitsForIt() throws Exception {
    var app = open(SHOP);
    var gate = servers.hold(COMMITTING);
    c.run(app, type(";\nCOMMIT") + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap waiting\n");
    String waiting = c.tree("waiting");
    assertTrue(waiting.contains("role=dialog label=\"Waiting for COMMIT\""), waiting);
    assertFalse(waiting.contains("#answer-rollback"), waiting);
    gate.countDown();
    c.run(app, SETTLE);
    assertFalse(servers.calls.contains("run " + PG + " ROLLBACK"), servers.calls.toString());
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /** New 4: a Commit the answer chose that fails keeps the console, and says why. */
  @Test
  void aFailedCommitGivesUpThePendingCloseAndExit() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(FakeServers.serverError(
            Failure.Kind.CONSTRAINT, "insert or update violates foreign key constraint")));
    var app = open(SHOP);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\n" + OPENED + SETTLE
            + "snap failed\n");
    String failed = c.tree("failed");
    assertEquals(1, app.state().consoles(null).size(), "the close was given up");
    assertEquals(null, app.state().pending);
    assertTrue(line(failed, "#problem").contains("A constraint was violated"), failed);
    assertFalse(failed.contains("role=dialog"), failed);
    // And Exit.
    c.run(app, "press #sql\n" + RUN + EXIT + "press #answer-commit\nframe\n" + OPENED + SETTLE);
    assertFalse(app.windows().all().isEmpty());
    assertEquals(null, app.state().pending);
  }

  /** New 4: so where the COMMIT is not sent, as the server ended the transaction by itself. */
  @Test
  void aCommitNotSentGivesUpThePendingClose() throws Exception {
    var app = open(INVENTORY);
    servers.rollBackBehind(MARIA);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\n" + OPENED + SETTLE
            + "snap not\n");
    assertEquals(1, app.state().consoles(null).size());
    assertTrue(line(c.tree("not"), "#problem").contains("Not committed"), c.tree("not"));
    assertFalse(servers.calls.contains("run " + MARIA + " COMMIT"), servers.calls.toString());
  }

  /** New 5: closing the dialog whose Save asked gives its way out up. */
  @Test
  void discardingTheEditGivesUpTheSaveItsQuestionWaitsFor() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Edit…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + type("\n" + PAY) + RUN);
    c.run(
        app,
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=button label=\"Save\"\nframe\n" + OPENED);
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "key Escape\nframe\n" + OPENED + "press role=button label=\"Discard\"\nframe\n"
            + OPENED + SETTLE + in(window) + OPENED + "snap after\n");
    assertEquals(null, app.state().pending);
    assertFalse(c.tree("shop-prod-shop-after").contains("role=dialog"));
    assertEquals(SHOP.user(), app.state().profile(SHOP.id()).user());
    assertEquals(Transaction.OPEN, k.tab(app).transaction);
  }

  /** A Save that waits for a console's answer saves the form as it was: nothing changes it. */
  @Test
  void theFormWaitsWithItsSaveForTheConsolesAnswer() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Edit…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + type("\n" + PAY) + RUN);
    c.run(
        app,
        in("main") + "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=button label=\"Save\"\nframe\n" + OPENED + "snap waiting\n"
            + "press role=button label=\"Driver\"\nframe\n" + OPENED);
    assertTrue(app.state().pending != null, "the console asks");
    String waiting = c.tree("waiting");
    assertTrue(line(waiting, "role=button label=\"Driver\"").endsWith("disabled"), waiting);
    assertTrue(line(waiting, "role=textbox label=\"User\"").contains("disabled"), waiting);
    assertFalse(line(waiting, "role=button label=\"Cancel\"").contains("disabled"), waiting);
    assertFalse(app.state().dialog.choosingDriver, "Driver… opens nothing");
    // Cancel gives the Save up, and the form is open to changes again.
    c.run(app, in(window) + "press #answer-cancel\nframe\n" + OPENED + SETTLE);
    c.run(app, in("main") + "frame\nsnap open\n");
    assertEquals(null, app.state().pending);
    String open = c.tree("open");
    assertFalse(line(open, "role=button label=\"Driver\"").contains("disabled"), open);
    assertFalse(line(open, "role=textbox label=\"User\"").contains("disabled"), open);
    c.run(
        app,
        in("main") + "press role=button label=\"Save\"\nframe\n" + OPENED + in(window)
            + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertEquals(null, app.state().dialog);
    assertEquals(SHOP.user() + "2", app.state().profile(SHOP.id()).user());
    assertEquals(null, app.state().profile(SHOP.id()).driverVersion());
  }

  /** New 5: so with the delete dialog's Cancel. */
  @Test
  void cancellingTheDeleteGivesUpTheDeleteItsQuestionWaitsFor() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(
        app,
        type(COUNT + ";") + RUN + tearOff(window) + in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED + SETTLE);
    c.run(app, in(window) + MANUAL + type("\n" + PAY) + RUN);
    c.run(app, in("main") + "press role=button label=\"Delete\"\nframe\n" + OPENED);
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "press role=button label=\"Cancel\"\nframe\n" + OPENED + SETTLE + in(window)
            + OPENED + "snap after\n");
    assertEquals(null, app.state().pending);
    assertFalse(c.tree("shop-prod-shop-after").contains("role=dialog"));
    assertEquals(1, app.state().profiles.size());
  }

  /**
   * Third review, 8, and the second review's 6: one act waits at most. An Exit asked again while
   * the first waits on another console is the same act, now asking that console; its Cancel ends
   * the Exit for good, and the first console's Commit, when it ends, resumes nothing.
   */
  @Test
  void anExitCancelledAtAnyConsoleIsOverForGood() throws Exception {
    var app = k.console(SHOP);
    var b = "shop-prod: shop (2)";
    c.run(
        app,
        type(COUNT)
            + RUN
            + menu("shop-prod", "New console")
            + MANUAL
            + type(PAY)
            + RUN
            + tearOff(b));
    var commit = servers.hold(COMMITTING);
    // Exit asks B, whose Commit is held.
    c.run(app, in("main") + EXIT + in(b) + "press #answer-commit\nframe\nframe\n");
    eventually(() -> servers.calls.contains(COMMITTING));
    // A begins a change meanwhile; a second Exit asks A, and is cancelled.
    var change = "run " + PG + " " + BROKEN;
    var held = servers.hold(change);
    c.run(app, in("main") + type(";\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(change));
    c.run(app, in("main") + EXIT + "snap second\n");
    assertTrue(c.tree("second").contains("role=dialog label=\"Waiting for " + BROKEN + "\""));
    c.run(app, in("main") + "press #answer-cancel\nframe\n" + OPENED);
    held.countDown();
    commit.countDown();
    c.run(app, SETTLE + SETTLE);
    assertFalse(app.windows().all().isEmpty(), "the Cancel gave the Exit up");
    assertEquals(null, app.state().pending);
  }

  /** Third review, 8: another act that would ask is refused, visibly, while one waits. */
  @Test
  void anotherActIsRefusedWhileOneWaits() throws Exception {
    var app = open(SHOP);
    var b = "shop-prod: shop (2)";
    c.run(app, menu("shop-prod", "New console") + MANUAL + type(PAY) + RUN + tearOff(b));
    // Closing B's tab asks in B's window; an Exit from the main window meanwhile is refused.
    c.run(app, in(b) + "press #close-0\nframe\n" + OPENED);
    var pending = app.state().pending;
    assertTrue(pending != null && pending.what().equals("the console closes"));
    c.run(app, in("main") + EXIT + "snap refused\n");
    assertTrue(app.state().pending == pending, "not replaced");
    assertTrue(line(c.tree("refused"), "#message").contains("waits for its answer"));
  }

  /**
   * An open transaction is at stake whatever ran in it: after only a read in Manual, closing asks,
   * without claiming that anything changed.
   */
  @Test
  void aTransactionOpenAfterOnlyAReadInManualAsks() throws Exception {
    for (var profile : List.of(SHOP, INVENTORY)) {
      var app = k.console(profile);
      // A session the server said had nothing open, then a read in Manual.
      c.run(app, type(COUNT + ";") + RUN + MANUAL + type("\n" + COUNT) + RUN);
      assertEquals(Transaction.OPEN, k.tab(app).transaction, profile.name());
      c.run(app, "press #close-0\nframe\n" + OPENED + "snap asked\n");
      String asked = c.tree("asked");
      assertTrue(asked.contains("role=dialog label=\"Commit the transaction?\""), asked);
      assertTrue(asked.contains("has a transaction open, after 1 statement. Commit it or"), asked);
      assertFalse(asked.contains("changes"), asked);
      c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE);
      assertTrue(app.state().consoles(null).isEmpty(), profile.name());
    }
    // In Auto, with none open, a read with rows left still asks nothing.
    var auto = k.console(SHOP);
    c.run(auto, type(ORDERS) + RUN + "press #close-0\nframe\n" + OPENED);
    assertTrue(auto.state().consoles(null).isEmpty());
  }

  /** F3: a statement that waited for its connection is refused while a lock holds it. */
  @Test
  void aStatementThatWaitedForItsConnectionIsRefusedUnderALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + SETTLE + MANUAL);
    var opening = servers.hold("open " + PG + "/shop");
    c.run(app, type(PAY) + "key Enter control\nframe\nframe\n");
    assertTrue(k.tab(app).activity instanceof ConsoleTab.Waiting);
    // As a Save of the connection does, once nothing of its consoles was at stake.
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", Set.of(SHOP.id())));
    opening.countDown();
    c.run(app, SETTLE + "snap refused\n");
    String refused = c.tree("refused");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    assertTrue(line(refused, "#problem").contains("Not run"), refused);
    assertTrue(refused.contains("shop-prod is being saved"), refused);
    app.state().drop(save);
  }

  /** F3: Run all goes on to its next statement only through the same gate. */
  @Test
  void runAllStopsAtALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    var key = "run " + PG + " " + ORDERS;
    var held = servers.hold(key);
    // The first, COUNT again, runs to its end; its result starts the held one.
    c.run(
        app,
        type("\n" + ORDERS + ";\n" + PAY + ";")
            + "key Enter control shift\nframe\nawait\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", Set.of(SHOP.id())));
    held.countDown();
    c.run(app, SETTLE + "snap stopped\n");
    assertFalse(servers.calls.contains("run " + PG + " " + PAY), servers.calls.toString());
    assertTrue(line(c.tree("stopped"), "#problem").contains("Not run"), c.tree("stopped"));
    app.state().drop(save);
  }

  /** F3: and the grid's further rows are not read under a lock. */
  @Test
  void noFurtherRowsAreReadUnderALock() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    var save = new Object();
    assertTrue(app.state().lock(save, "shop-prod is being saved", Set.of(SHOP.id())));
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE);
    assertFalse(
        servers.calls.contains("next " + PG + " " + ORDERS + " 200"), servers.calls.toString());
    app.state().drop(save);
  }

  /** F8: an Edit that waited on a question does not replace a dialog opened meanwhile. */
  @Test
  void anEditThatWaitedReplacesNoNewerDialog() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(app, MANUAL + type(PAY) + RUN + tearOff(window));
    c.run(
        app,
        in("main") + menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n"
            + OPENED);
    assertTrue(app.state().pending != null, "the console asks");
    c.run(
        app,
        in("main") + "press role=menuitem label=\"File\"\nframe\nadvance 0.3\nframe\n"
            + "press role=menuitem label=\"New connection…\"\nframe\n" + OPENED
            + "press role=textbox label=\"Name\"\ntype \"kept\"\nframe\n");
    var newer = app.state().dialog;
    assertTrue(newer != null && newer.isNew, "the new connection's dialog");
    c.run(app, in(window) + "press #answer-rollback\nframe\n" + SETTLE + OPENED);
    assertEquals(newer, app.state().dialog);
    assertTrue(app.state().message.contains("was not opened for editing"), app.state().message);
  }

  /** F9: a Delete asked while an Edit of the same connection waits is refused, not put in place. */
  @Test
  void aDeleteDoesNotReplaceAWaitingEdit() throws Exception {
    var app = k.console(SHOP);
    var window = "shop-prod: shop";
    c.run(app, MANUAL + type(PAY) + RUN + tearOff(window));
    c.run(
        app,
        in("main") + menu("shop-prod", "Edit…") + "press role=button label=\"Disconnect\"\nframe\n"
            + OPENED);
    var edit = app.state().pending;
    c.run(
        app,
        in("main") + menu("shop-prod", "Delete…")
            + "press role=button label=\"Disconnect\"\nframe\n" + OPENED);
    assertEquals(edit, app.state().pending, "the edit still waits");
    c.run(app, in(window) + "press #answer-rollback\nframe\n" + SETTLE + OPENED);
    assertTrue(app.state().dialog != null, "the edit goes on");
    assertEquals(null, app.state().deleting);
  }

  /**
   * 3: a Rollback that the close question ran, and that rolled back only in part, stops the close:
   * the console stays and says so, and the app keeps it.
   */
  @Test
  void aRollbackInPartStopsTheCloseItAnswered() throws Exception {
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var app = open(INVENTORY);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-rollback\nframe\n" + OPENED + SETTLE
            + "snap partly\n");
    String partly = c.tree("partly");
    assertEquals(1, app.state().consoles(null).size(), "the close stopped");
    assertEquals(null, app.state().pending);
    assertTrue(line(partly, "#problem").contains(ConsoleTab.PARTLY), partly);
    assertTrue(noticed(app).contains(ConsoleTab.PARTLY), noticed(app));
  }
}

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
import static dev.hoori.db.app.ConsoleTest.MISSING;
import static dev.hoori.db.app.ConsoleTest.ORDERS;
import static dev.hoori.db.app.ConsoleTest.PAY;
import static dev.hoori.db.app.ConsoleTest.RUN;
import static dev.hoori.db.app.ConsoleTest.RUN_ALL;
import static dev.hoori.db.app.ConsoleTest.near;
import static dev.hoori.db.app.ConsoleTest.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Color;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.ShortEnding;
import dev.hoori.db.core.execution.Transaction;
import dev.hoori.db.core.secret.Keyring;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** What outlives the console that asked: notices, give-ups, sessions that end within their bounds, and a keyring that does not answer. */
class ConsoleNoticeTest extends ConsoleTransactions {

  /** New 7: an outcome in doubt guards every way out until the user has read it. */
  @Test
  void anUnknownOutcomeStaysUntilAcknowledged() throws Exception {
    servers.statement(
        "COMMIT",
        List.of(
            FakeServers.serverError(Failure.Kind.CONNECTION_LOST, "server closed the connection")));
    var app = open(SHOP);
    c.run(app, "press role=button label=\"Commit\"\nframe\n" + SETTLE);
    assertFalse(k.tab(app).connected());
    var close = "press #close-0\nframe\n" + OPENED;
    c.run(
        app, close + "snap first\npress #answer-cancel\nframe\n" + OPENED + close + "snap again\n");
    assertTrue(c.tree("again").contains("role=dialog label=\"The outcome is not known\""));
    // Another statement does not clear it.
    c.run(app, "press #answer-cancel\nframe\n" + OPENED + "press #sql\n" + RUN + "snap ran\n");
    assertTrue(c.tree("ran").contains("#doubt"), c.tree("ran"));
    c.run(app, "press role=button label=\"Acknowledge\"\nframe\n" + OPENED + "snap read\n");
    assertFalse(c.tree("read").contains("#doubt"), c.tree("read"));
    c.run(app, "press role=tab label=\"Messages\"\nframe\nsnap messages\n");
    assertTrue(c.tree("messages").contains("Outcome not known"), c.tree("messages"));
    // What asks now is the transaction the statement began, not the outcome that was read.
    c.run(app, close + "snap open\n");
    assertTrue(c.tree("open").contains("role=dialog label=\"Commit the transaction?\""));
    c.run(app, "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
  }

  /** What giving up the rest of a statement's rows failed with, the console says. */
  @Test
  void aFailureAtGivingUpIsShown() throws Exception {
    servers.failsAtGiveUp(
        ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, "the commit at give-up failed"));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS + ";\n" + COUNT) + RUN_ALL + "snap given\n");
    assertTrue(servers.calls.contains("close " + PG + " " + ORDERS), servers.calls.toString());
    // The fifth review's first finding: what came after it is not sent, and the run stops.
    assertFalse(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
    assertTrue(line(c.tree("given"), "#problem").contains("Not run"), c.tree("given"));
    c.run(app, "press role=tab label=\"Messages\"\nframe\n" + OPENED + "snap messages\n");
    assertTrue(
        c.tree("messages").contains(Session.ENDED + "the commit at give-up failed"),
        c.tree("messages"));
    assertTrue(noticed(app).contains(Session.ENDED + "the commit at give-up failed"));
  }

  /**
   * The commit of what a read changed, as its session ends with its tab, fails: the main window
   * says so until dismissed, with the connection and the statement.
   */
  @Test
  void aCommitThatFailsAsTheSessionEndsReachesTheMainWindow() throws Exception {
    servers.failsAtGiveUp(
        ORDERS, FakeServers.serverError(Failure.Kind.OTHER, "could not serialize access"));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    c.run(app, "press #close-0\nframe\n" + OPENED);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing asked: a read in Auto");
    eventually(() -> servers.calls.contains("close " + PG + " " + ORDERS));
    // Posted by the thread that closed the session, once it has.
    eventually(() -> {
      try {
        c.run(app, "await\nframe\n");
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return !app.state().notices.isEmpty();
    });
    c.run(app, "frame\nsnap told\n");
    String told = c.tree("told");
    assertTrue(near(told, "#notice-1").contains("shop-prod · " + ORDERS), told);
    assertTrue(noticed(app).contains("could not serialize access"), noticed(app));
    c.run(app, "press #dismiss-1\nframe\nadvance 1\nframe\nsnap dismissed\n");
    assertFalse(c.tree("dismissed").contains("#notice-1"), c.tree("dismissed"));
    assertTrue(app.state().notices.isEmpty());
  }

  /** A keyring that does not answer can be given up, and says what that leaves. */
  @Test
  void aKeyringThatDoesNotAnswerCanBeGivenUp() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    c.secrets.gate = new CountDownLatch(1);
    c.run(app, """
            press role=textbox label="User"
            type "2"
            press role=textbox label="Password"
            type "hunter2"
            press role=checkbox label="Save in system keyring"
            frame
            press role=button label="Save"
            frame
            snap waits
            """);
    assertTrue(line(c.tree("waits"), "role=button label=\"Cancel\"").contains("disabled"));
    assertTrue(app.state().guard.pending != null, "the save holds its lock");
    c.run(app, "advance " + State.KEYRING_SECONDS + "\nframe\nsnap slow\n");
    assertTrue(c.tree("slow").contains("The system keyring does not answer"), c.tree("slow"));
    c.run(app, "press role=button label=\"Cancel\"\nframe\nsnap given\n");
    String given = c.tree("given");
    assertTrue(given.contains("the save was given up"), given);
    assertEquals(null, app.state().guard.pending, "the lock ended");
    assertFalse(app.state().dialogs.dialog.changing());
    c.secrets.gate.countDown();
    c.run(app, SETTLE);
  }

  /**
   * 2: the failure of giving up a read is the app's at once, and neither a Cancel of what came
   * after nor a close of the tab can lose it.
   */
  @Test
  void aGiveUpsFailureOutlivesWhatCameAfter() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    for (var after : List.of(
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n",
        "press #close-0\nframe\n")) {
      var app = k.console(SHOP);
      c.run(app, type(ORDERS) + RUN);
      c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\nframe\n");
      eventually(() -> servers.calls.contains("close " + PG + " " + ORDERS));
      c.run(app, after + OPENED + SETTLE + "snap after\n");
      assertTrue(noticed(app).contains(Session.ENDED + DUPLICATE), after + noticed(app));
      assertTrue(near(c.tree("after"), "#notice-1").contains(DUPLICATE), c.tree("after"));
      servers.calls.clear();
    }
  }

  /**
   * A give-up's failure names the read it gave up, not a statement asked for after it and
   * cancelled before its call began.
   */
  @Test
  void aGiveUpsFailureNamesTheReadItGaveUp() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    var tab = k.tab(app);
    // The session's lane, as the probe after a cancel names it.
    c.run(app, type(COUNT + ";") + RUN);
    var counting = "run " + PG + " " + COUNT;
    var counted = servers.hold(counting);
    c.run(app, type("\n" + COUNT) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(counting));
    var probing = servers.hold("transaction " + PG);
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
    var lane = tab.probing.lane();
    counted.countDown();
    probing.countDown();
    c.run(app, SETTLE + "press #sql\n" + type(";\n" + ORDERS) + RUN);
    // Something before it on the lane: the statement asked for next waits, and is cancelled.
    var busy = new CountDownLatch(1);
    lane.submit(link -> {
      busy.await();
      return null;
    });
    c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\n");
    c.run(app, "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n");
    busy.countDown();
    c.run(app, SETTLE);
    assertTrue(servers.calls.contains("close " + PG + " " + ORDERS), servers.calls.toString());
    assertTrue(
        noticed(app).contains(" · " + ORDERS + " · " + Session.ENDED + DUPLICATE), noticed(app));
  }

  /**
   * 4: a keyring change given up still runs, and what is asked of the keyring after it runs after
   * it: the password a save gave up on is not left behind by the delete that came later.
   */
  @Test
  void aKeyringChangeGivenUpRunsBeforeTheNextOne() throws Exception {
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=textbox label=\"User\"\ntype \"2\"\n"
            + "press role=textbox label=\"Password\"\ntype \"first\"\n"
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nadvance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED
            + "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED
            + "press role=button label=\"Discard\"\nframe\n" + OPENED
            + menu("shop-prod", "Delete…") + OPENED
            + "press role=button label=\"Delete\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nsnap waiting\n");
    assertTrue(c.tree("waiting").contains("this one waits behind it"), c.tree("waiting"));
    late.countDown(); // the save given up writes now, as a native call can
    c.run(app, SETTLE + "snap deleted\n");
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "the delete came last");
    assertTrue(app.state().profiles.isEmpty());
  }

  /** 5: while the keyring is still being found, a Delete can be given up, and so can Exit. */
  @Test
  void aKeyringNeverFoundStrandsNeitherDeleteNorExit() throws Exception {
    var finding = new CountDownLatch(1);
    try {
      var app = c.app(
          servers,
          () -> {
            finding.await();
            return new Keyring.Available(c.secrets);
          },
          SHOP);
      c.ready(app, SHOP);
      // The load comes in; the keyring is looked for from then on, and not found.
      c.run(app, "size 1200 760\nframe\nawait\nframe\nframe\nadvance 1\nframe\n");
      c.run(
          app,
          menu("shop-prod", "Delete…") + OPENED + "press role=button label=\"Delete\"\nframe\n"
              + "advance " + State.KEYRING_SECONDS + "\nframe\nadvance 1\nframe\nsnap slow\n");
      String slow = c.tree("slow");
      assertTrue(slow.contains("The system keyring does not answer"), slow);
      c.run(
          app,
          "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
              + OPENED + "snap given\n");
      assertTrue(c.tree("given").contains("the deletion was given up"), c.tree("given"));
      assertEquals(null, app.state().guard.pending);
      // Delete again, and Exit: the first gives the delete up once it is slow, the next goes.
      c.run(
          app,
          "press role=button label=\"Delete\"\nframe\nadvance " + State.KEYRING_SECONDS
              + "\nframe\nadvance 1\nframe\n");
      app.state().requestClose(); // the window's close button, under the dialog
      c.run(app, "frame\n");
      assertFalse(app.windows().all().isEmpty(), "the first close gave up the delete");
      assertEquals(1, app.state().profiles.size());
      assertEquals(null, app.state().guard.pending);
      app.state().requestClose();
      c.run(app, "frame\n");
      assertTrue(app.windows().all().isEmpty());
    } finally {
      finding.countDown(); // the keyring is found at last, also where an assertion failed
    }
  }

  /** 6: two sessions whose closing commits fail leave two notices, each readable. */
  @Test
  void twoClosingFailuresAreBothKept() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + menu("shop-prod", "New console") + SETTLE + type(ORDERS) + RUN);
    assertEquals(2, app.state().consoles(null).size());
    c.run(app, menu("shop-prod", "Disconnect") + OPENED + SETTLE + "snap both\n");
    assertEquals(2, app.state().notices.size(), noticed(app));
    String both = c.tree("both");
    assertTrue(near(both, "#notice-1").contains(DUPLICATE), both);
    assertTrue(near(both, "#notice-2").contains(DUPLICATE), both);
  }

  /** 7: a switch to Manual gives up a read left with rows, which then says so and reads no more. */
  @Test
  void aSwitchThatGivesUpAReadLeavesItsGridCut() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + MANUAL);
    c.run(app, "scroll #grid row 180\nframe\nframe\n" + SETTLE + "snap scrolled\n");
    String scrolled = c.tree("scrolled");
    assertFalse(
        servers.calls.contains("next " + PG + " " + ORDERS + " 200"), servers.calls.toString());
    assertTrue(near(scrolled, "#result-status").contains("the rest was not read"), scrolled);
    assertEquals(Transaction.NONE, k.tab(app).transaction);
    assertEquals(null, k.tab(app).problem);
  }

  /** Exit waits for the sessions it ends; one that reports a failure keeps HooriDB open. */
  @Test
  void exitStaysOpenOnASessionsFailure() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN + EXIT + SETTLE + "snap stayed\n");
    assertFalse(app.windows().all().isEmpty(), "stayed open");
    assertTrue(near(c.tree("stayed"), "#notice-1").contains(DUPLICATE), c.tree("stayed"));
    assertEquals(null, app.state().guard.pending);
    c.run(app, EXIT + SETTLE);
    assertTrue(app.windows().all().isEmpty());
  }

  /** Exit waits for a session that does not say it closed only so long, then once more. */
  @Test
  void exitWaitsForASilentSessionOnlySoLong() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    servers.dead = true; // the held call ends only once its gate opens, interrupted or not
    var gate = servers.hold("run " + PG + " " + COUNT);
    c.run(app, type(";\n" + COUNT) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains("run " + PG + " " + COUNT));
    c.run(app, EXIT + "advance " + State.EXIT_SECONDS + "\nframe\nsnap silent\n");
    assertFalse(app.windows().all().isEmpty());
    assertTrue(c.tree("silent").contains("did not say in time"), c.tree("silent"));
    // The saves end, which an await would not hear, as it waits for the silent session too; the
    // consoles' change since the save on its way is saved after it.
    c.run(app, """
            await "save history"
            await "save consoles"
            frame
            await "save consoles"
            frame
            """);
    c.run(app, EXIT);
    assertTrue(app.windows().all().isEmpty(), "the second Exit goes");
    gate.countDown();
  }

  /** Delivered, then the tab closed: the console showed it, and no notice repeats it. */
  @Test
  void anOutcomeTakenIsNoNotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(MISSING) + RUN + "press #close-0\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(app.state().notices.isEmpty(), noticed(app));
  }

  /** The lane had the outcome, the console had not taken it, and Cancel came: the app posts it. */
  @Test
  void anOutcomeThereAndNotTakenBecomesANotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN); // the session is open
    var tab = k.tab(app);
    c.run(app, type("\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + SETTLE);
    assertTrue(noticed(app).contains("relation \"missing\" does not exist"), noticed(app));
    assertTrue(app.state().notices.getFirst().failure());
  }

  /** Cancel came first, the outcome after it: the lane posts it, as the console gave it up. */
  @Test
  void anOutcomeAfterItWasGivenUpBecomesANotice() throws Exception {
    var app = k.console(SHOP);
    c.run(app, type(COUNT + ";") + RUN);
    servers.dead = true; // the held call answers once its gate opens, cancelled or not
    var key = "run " + PG + " " + BROKEN;
    var gate = servers.hold(key);
    c.run(app, type("\n" + BROKEN) + "key Enter control\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\nframe\n");
    gate.countDown();
    c.run(app, SETTLE);
    assertTrue(noticed(app).contains("relation \"missing\" does not exist"), noticed(app));
  }

  /** A typed ROLLBACK that rolled back only in part, cancelled before the console took it. */
  @Test
  void aTypedRollbackInPartCancelledIsANotice() throws Exception {
    servers.statement(ConsoleTab.ROLLBACK, List.of(new Result.Count(0)), NOT_ROLLED_BACK);
    var app = open(INVENTORY);
    var tab = k.tab(app);
    c.run(app, type(";\n" + ConsoleTab.ROLLBACK) + "key Enter control\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(
        app,
        "advance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + SETTLE);
    assertTrue(noticed(app).contains(ConsoleTab.PARTLY), noticed(app));
  }

  /**
   * The last portion of a read, whose own transaction's commit failed, there and not taken as the
   * tab closes: nothing asks at that close, and the app has it.
   */
  @Test
  void aReadsLastPortionFailingAtTheCloseIsANotice() throws Exception {
    servers.statement(
        LAST,
        List.of(
            FakeServers.made(250, "id: bigint"),
            FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE)));
    var app = k.console(SHOP);
    var tab = k.tab(app);
    c.run(app, type(LAST) + RUN + "scroll #grid row 180\nframe\nframe\n");
    eventually(tab::outcomeWaiting);
    c.run(app, "press #close-0\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty(), "nothing asked");
    assertTrue(noticed(app).contains(DUPLICATE), noticed(app));
  }

  /**
   * 3: Exit after the connections file failed to save goes without the file, as the user was
   * told, but not without the sessions' reports: one that fails keeps HooriDB open.
   */
  @Test
  void quittingWithoutTheFileStillWaitsForTheSessions() throws Exception {
    servers.failsAtGiveUp(ORDERS, FakeServers.serverError(Failure.Kind.CONSTRAINT, DUPLICATE));
    var app = k.console(SHOP);
    c.run(app, type(ORDERS) + RUN);
    var file = dir.resolve("config").resolve(dev.hoori.db.core.connection.WorkspaceStore.FILE);
    Files.delete(file);
    Files.createDirectories(file); // the store cannot write here
    app.state().put(SHOP.withColor(Color.GREEN)); // a change to save
    c.run(app, SETTLE + "close\nframe\n" + SETTLE + "snap unsaved\n");
    assertTrue(c.tree("unsaved").contains("Close it again to quit without them."));
    assertFalse(app.windows().all().isEmpty());
    c.run(app, "close\nframe\n" + SETTLE + "snap reported\n");
    assertFalse(app.windows().all().isEmpty(), "the session's report keeps it open");
    assertTrue(noticed(app).contains(DUPLICATE), noticed(app));
    c.run(app, "close\nframe\n" + SETTLE);
    assertTrue(app.windows().all().isEmpty(), "and then it goes, without the file");
  }

  /**
   * 4: a Rollback that keeps failing, the connection still there, does not keep the console: the
   * next question offers to end the connection, and the app keeps that it was.
   */
  @Test
  void aRollbackThatKeepsFailingCanBeLeft() throws Exception {
    servers.statement(
        ConsoleTab.ROLLBACK,
        List.of(FakeServers.serverError(Failure.Kind.OTHER, "Error writing file")));
    var app = open(INVENTORY);
    c.run(
        app,
        "press #close-0\nframe\n" + OPENED + "press #answer-rollback\nframe\n" + OPENED + SETTLE);
    assertEquals(1, app.state().consoles(null).size(), "the failed Rollback stopped the close");
    c.run(app, "press #close-0\nframe\n" + OPENED + "snap again\n");
    assertTrue(c.tree("again").contains("End the connection"), c.tree("again"));
    c.run(app, "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(noticed(app).contains(ConsoleTab.WENT_ON), noticed(app));
  }

  /** 4: a Commit the server does not answer can be left once it has kept the question waiting. */
  @Test
  void aCommitThatDoesNotAnswerCanBeLeft() throws Exception {
    var app = open(INVENTORY);
    var key = "run " + MARIA + " " + ConsoleTab.COMMIT;
    servers.hold(key);
    c.run(app, "press #close-0\nframe\n" + OPENED + "press #answer-commit\nframe\nframe\n");
    eventually(() -> servers.calls.contains(key));
    c.run(app, "frame\nsnap waiting\n");
    assertFalse(c.tree("waiting").contains("End the connection"), "not at once");
    c.run(app, "advance " + ConsoleTab.FIRM_SECONDS + "\nframe\n" + OPENED + "snap overdue\n");
    assertTrue(c.tree("overdue").contains("End the connection"), c.tree("overdue"));
    c.run(app, "press #answer-go-on\nframe\n" + OPENED + SETTLE);
    assertTrue(app.state().consoles(null).isEmpty());
    assertTrue(noticed(app).contains(ConsoleTab.WENT_ON), noticed(app));
  }

  /**
   * 2: after a give-up whose delete and refresh both failed, the dialog does not know what the
   * keyring holds; choosing not to keep the password there then asks it to delete, not nothing.
   */
  @Test
  void anUnknownKeyringIsNotTakenForAnEmptyOne() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE + "snap kept\n");
    assertTrue(line(c.tree("kept"), "role=checkbox").contains("checked"), "it says it holds one");
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=textbox label=\"Password\"\ntype \"fresh\"\n"
            + "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nadvance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED);
    c.secrets.fails = true; // the delete given up fails, and so does the refresh behind it
    late.countDown();
    c.run(app, SETTLE + "snap unknown\n");
    assertTrue(c.tree("unknown").contains("is not known"), c.tree("unknown"));
    assertTrue(c.secrets.stored.containsKey(SHOP.id()), "still there");
    c.secrets.fails = false;
    c.run(app, "press role=button label=\"Save\"\nframe\n" + SETTLE);
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "asked to delete, as chosen");
    assertEquals(null, app.state().dialogs.dialog);
  }

  /**
   * 5: a keyring read that never returns keeps no connection from its password: after the
   * keyring's patience, the connection asks for it, and says why.
   */
  @Test
  void aKeyringReadThatNeverReturnsGoesToThePasswordQuestion() throws Exception {
    c.secrets.stored.put(SHOP.id(), ConnectionsTest.PASSWORD);
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    app.state().forgetPassword(SHOP.id()); // from the keyring, not this session
    var never = new CountDownLatch(1);
    c.secrets.late = never;
    c.run(
        app,
        CatalogTreeTest.START + CatalogTreeTest.open("shop-prod") + "frame\nadvance "
            + State.KEYRING_SECONDS + "\nframe\n" + OPENED + "snap asked\n");
    String asked = c.tree("asked");
    assertTrue(asked.contains("The system keyring did not answer"), asked);
    c.run(
        app,
        "press role=textbox label=\"Password\"\ntype \"" + ConnectionsTest.PASSWORD + "\"\n"
            + "press role=button label=\"Connect\"\nframe\n" + SETTLE);
    assertTrue(app.state().catalog.connected(SHOP.id()));
    never.countDown();
  }

  /** 5: the edit dialog's look into a keyring that does not answer does not hold Save back. */
  @Test
  void aLookupThatNeverReturnsDoesNotHoldSaveBack() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var never = new CountDownLatch(1);
    c.secrets.late = never;
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(
        app,
        CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED
            + "press role=textbox label=\"User\"\ntype \"2\"\nframe\nadvance "
            + State.KEYRING_SECONDS + "\nframe\nadvance 1\nframe\nsnap ready\n");
    String ready = c.tree("ready");
    assertFalse(line(ready, "role=button label=\"Save\"").contains("disabled"), ready);
    assertTrue(ready.contains("is not known"), ready);
    never.countDown();
  }

  /**
   * 5: a session whose read is left open closes, and the driver's call as its statement ends never
   * returns: the closing thread ends within its bound all the same, and says what it could not.
   */
  @Test
  void aClosingWhoseDriverCallNeverReturnsEnds() throws Exception {
    var stuck = new dev.hoori.db.core.execution.Stuck();
    try (var _ = new ShortEnding()) {
      var connection = stuck.connection();
      var lane = new Lane(e -> {});
      lane.open(
          (profile, password, driver) -> Servers.Real.of(
              connection,
              Provider.POSTGRESQL,
              dev.hoori.db.core.connection.ServerVersion.UNKNOWN,
              "shop",
              false),
          SHOP,
          ConnectionsTest.PASSWORD.toCharArray(),
          null);
      var first = lane.submit(link -> link.run("SELECT a FROM t").next(10))
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(first.more(), "left open");
      stuck.arm();
      var ended =
          new java.util.concurrent.CompletableFuture<dev.hoori.db.core.execution.Abandoned>();
      var closing = lane.close(ended::complete);
      var bound = ShortEnding.CLOSES;
      assertTrue(closing.join(bound), "the closing thread ended within " + bound);
      assertTrue(stuck.stuck() != null, "the give-up reached the hung call");
      var failure = ended.getNow(null).failure().orElseThrow();
      assertEquals(Failure.Kind.CONNECTION_LOST, failure.kind());
      assertTrue(failure.message().contains(ShortEnding.CUT_OFF), failure.message());
    } finally {
      stuck.release();
    }
  }

  /**
   * The last re-check: a give-up that its deadline cut off ends the session at once and for good.
   * The statement that asked for it never reaches the old link, the console says the connection
   * ended, the next run opens a new session, and the old worker that wakes up changes nothing.
   */
  @Test
  void aGiveUpCutOffByItsDeadlineEndsTheSession() throws Exception {
    var stuck = new dev.hoori.db.core.execution.Stuck();
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    // The tree's connection and the later ones made up; the console's first one stuck.
    Servers mixed = (profile, password, driver) -> opens.getAndIncrement() == 1
        ? Servers.Real.of(
            stuck.connection(),
            Provider.POSTGRESQL,
            dev.hoori.db.core.connection.ServerVersion.UNKNOWN,
            "shop",
            false)
        : servers.open(profile, password, driver);
    try (var _ = new ShortEnding()) {
      var app = c.app(mixed, () -> new Keyring.Available(c.secrets), SHOP);
      c.ready(app, SHOP);
      c.run(app, CatalogTreeTest.START + menu("shop-prod", "New console") + SETTLE);
      var tab = k.tab(app);
      c.run(app, type("SELECT a FROM t") + RUN);
      assertTrue(tab.outputs.getFirst().more, "a read left open on the stuck connection");
      stuck.arm();
      // Its give-up runs to its short deadline, which the shipped one would overrun.
      c.run(
          app,
          type(";\n" + COUNT) + "key Enter control\nframe\nawait "
              + ShortEnding.CLOSES.toSeconds() + "\nframe\n" + SETTLE
              + "snap ended\n");
      String ended = c.tree("ended");
      assertFalse(tab.connected(), "the session is over");
      assertTrue(line(ended, "#problem").contains("Disconnected"), ended);
      assertEquals(
          1,
          stuck.calls.stream().filter("createStatement"::equals).count(),
          "nothing more reached the old link: " + stuck.calls);
      // The old worker wakes up, and changes nothing on the connection given up.
      int before = stuck.calls.size();
      var worker = stuck.stuck();
      stuck.release();
      assertTrue(worker.join(java.time.Duration.ofSeconds(10)));
      var after = stuck.calls.subList(before, stuck.calls.size());
      assertTrue(
          after.stream()
              .noneMatch(
                  x -> x.equals("commit") || x.equals("rollback") || x.startsWith("setAutoCommit")),
          after.toString());
      // The next run opens a new session.
      c.run(app, "press #sql\n" + RUN);
      assertTrue(servers.calls.contains("run " + PG + " " + COUNT), servers.calls.toString());
      assertTrue(tab.connected());
      assertEquals(
          1,
          stuck.calls.stream().filter("createStatement"::equals).count(),
          "still nothing on the old link: " + stuck.calls);
    } finally {
      stuck.release();
    }
  }

  /**
   * 2: the lane could not open the console's connection, so it never made the call: the console
   * does not stay busy, and says why.
   */
  @Test
  void aConnectionThatCannotOpenEndsTheCallItWasFor() throws Exception {
    var app = k.console(SHOP);
    // A session, lost: the next statement opens another, behind the tree's connection.
    c.run(app, type(COUNT + ";") + RUN + type("\n" + KILL) + RUN);
    assertFalse(k.tab(app).connected());
    servers.fail(
        "open " + PG + "/shop",
        FakeServers.notConnected(
            dev.hoori.db.core.connect.ConnectionException.Failure.UNREACHABLE));
    c.run(app, type(";\n" + COUNT) + RUN + "snap failed\n");
    assertFalse(k.tab(app).busy(), "not busy");
    assertTrue(line(c.tree("failed"), "#problem").contains("role=note"), c.tree("failed"));
  }

  /** 3: a Teller wiped with its session posts no word of the server's that arrives after. */
  @Test
  void aTellerWipedPostsNoUncheckedWords() throws Exception {
    var app = k.console(SHOP);
    var teller =
        new Teller(app, app.state(), "shop-prod", ConnectionsTest.PASSWORD.toCharArray(), null);
    teller.wipe();
    teller.tell(PAY, "password authentication failed for " + ConnectionsTest.PASSWORD, true);
    c.run(app, SETTLE);
    assertFalse(noticed(app).contains(ConnectionsTest.PASSWORD), noticed(app));
    assertTrue(noticed(app).contains(Teller.TOO_LATE), noticed(app));
  }

  /**
   * 4: Password empty, saving unchecked, the delete given up while the read before it was held,
   * and the refresh behind it failed: what the keyring holds is not known, so the choice still
   * counts, and Save asks again to delete.
   */
  @Test
  void anUnknownKeyringWithNothingElseChangedCanStillBeAskedToDelete() throws Exception {
    c.secrets.stored.put(SHOP.id(), "old");
    var app = c.app(SHOP);
    c.ready(app, SHOP);
    c.run(app, CatalogTreeTest.START + menu("shop-prod", "Edit…") + OPENED + SETTLE);
    var late = new CountDownLatch(1);
    c.secrets.late = late;
    c.run(
        app,
        "press role=checkbox label=\"Save in system keyring\"\nframe\n"
            + "press role=button label=\"Save\"\nframe\nadvance " + State.KEYRING_SECONDS
            + "\nframe\nadvance 1\nframe\nadvance 0.5\nframe\nadvance 1\nframe\npress role=button label=\"Cancel\"\nframe\n"
            + OPENED);
    eventually(() -> c.secrets.late == null); // the delete's read took it
    int before = c.secrets.calls.size() - 1;
    // The read answers; the refresh behind it is held, and fails.
    var refreshing = new CountDownLatch(1);
    c.secrets.late = refreshing;
    late.countDown();
    eventually(() -> c.secrets.late == null);
    c.secrets.fails = true;
    refreshing.countDown();
    c.run(app, SETTLE);
    assertEquals(
        List.of("read", "read"),
        c.secrets.calls.subList(before, c.secrets.calls.size()),
        "the read given up answered, its delete was not asked, and the refresh failed");
    assertTrue(c.secrets.stored.containsKey(SHOP.id()), "still there");
    c.secrets.fails = false;
    before = c.secrets.calls.size();
    // Save's handler, as its button or Enter calls it, whether or not it would go.
    app.state().dialogs.dialog.save(app.state());
    c.run(app, "frame\n" + SETTLE);
    assertEquals(
        List.of("read", "delete"), c.secrets.calls.subList(before, c.secrets.calls.size()));
    assertFalse(c.secrets.stored.containsKey(SHOP.id()), "asked to delete, as chosen");
  }

  /** 6: Dismiss all dismisses the notices shown when it was pressed, not one that came since. */
  @Test
  void dismissAllLeavesANoticeThatCameDuringThePress() throws Exception {
    var app = k.console(SHOP);
    var s = app.state();
    for (int i = 1; i <= 4; i++) {
      int n = i;
      app.post(() -> s.notice("shop-prod", "SELECT " + n, "shown " + n, true, null));
    }
    c.run(app, "await\nframe\n" + OPENED + "down #dismiss-all\nframe\n");
    // Between the press and the release, and not shown: no frame was built since.
    s.notice("shop-prod", "SELECT 5", "came during the press", true, null);
    c.run(app, "up #dismiss-all\nframe\n");
    assertEquals(1, s.notices.size(), noticed(app));
    assertTrue(noticed(app).contains("came during the press"), noticed(app));
  }
}

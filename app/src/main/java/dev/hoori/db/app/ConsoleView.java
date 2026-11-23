package dev.hoori.db.app;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Callout;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Segmented;
import com.rethinkj.ui.kit.Select;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Tooltip;
import com.rethinkj.ui.text.Font;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.execution.Transaction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a console shows around its editor and its results area: the toolbar with the database, the
 * transaction's mode and ends and the runs; the status line with the transaction's badge; and the
 * questions it asks in its window. It shows the console's state and calls its handlers.
 */
final class ConsoleView {

  private final ConsoleTab tab;
  private final Menu.State databases = new Menu.State(); // the database switch's menu

  ConsoleView(ConsoleTab tab) {
    this.tab = tab;
  }

  /** Keys go up from the focused element: these work from the editor and from the grids. */
  Predicate<Key> keys(State s) {
    return key -> {
      if (key.modifiers() != (Key.CONTROL | Key.SHIFT)) {
        return false;
      }
      switch (key.name()) {
        case "C" -> tab.commitTransaction(s);
        case "R" -> tab.rollBackTransaction(s);
        default -> {
          return false;
        }
      }
      return true;
    };
  }

  /**
   * The toolbar's actions: the database at the start, the runs, the transaction's, and the
   * statements kept at the end. All stay in place whatever the mode or the transaction, so nothing
   * moves when they change; what cannot be used is off.
   */
  List<El> actions(State s, Provider provider) {
    boolean busy = tab.busy();
    var locked = s.guard.locked(tab);
    var actions = new ArrayList<El>();
    actions.add(databases(s));
    actions.add(runs(s, locked));
    if (Language.of(provider).transactions()) {
      // Off where Manual cannot be made sure of, which its tooltip and a note say.
      boolean switchable = !busy && locked == null && tab.unavailable == null;
      actions.add(modes(s, switchable));
      actions.add(commitButton(s));
      actions.add(rollbackButton(s));
    }
    actions.add(Card.spacer());
    actions.addAll(kept(s));
    return actions;
  }

  /** Auto or Manual, with what each does, or why Manual is unavailable. */
  private El modes(State s, boolean switchable) {
    return Tooltip.tooltip(
        Segmented.segmented(
                List.of(ConsoleTab.Mode.values()),
                tab.mode,
                m -> m.title,
                switchable ? m -> tab.switchMode(s, m) : null)
            .key("mode")
            .label("Transactions"),
        tab.unavailable == null
            ? "Auto commits each statement by itself; in Manual, Commit or Rollback ends the"
                + " transaction"
            : "Manual is unavailable: " + tab.unavailable);
  }

  private El commitButton(State s) {
    return Button.icon(
            Button.Variant.GHOST,
            Symbol.CHECKMARK,
            "Commit",
            tab.canCommit() ? () -> tab.commitTransaction(s) : null,
            Button.options()
                .state(busyWith(ConsoleTab.COMMIT))
                .tone(Button.Tone.SUCCESS)
                .shortcut(Key.CONTROL | Key.SHIFT, "C"))
        .key("commit")
        .keepFocus();
  }

  private El rollbackButton(State s) {
    // In Auto with nothing open, off: no Rollback could say what it undid.
    boolean offered = tab.mode == ConsoleTab.Mode.MANUAL || tab.transaction != Transaction.NONE;
    return Button.icon(
            Button.Variant.GHOST,
            Symbol.ARROW_UNDO,
            ConsoleTab.ROLLBACK_BUTTON,
            offered && tab.canRollback() ? () -> tab.rollBackTransaction(s) : null,
            Button.options()
                .state(busyWith(ConsoleTab.ROLLBACK))
                .tone(Button.Tone.ACCENT)
                .shortcut(Key.CONTROL | Key.SHIFT, "R"))
        .key("rollback")
        .keepFocus();
  }

  /** Busy while Commit's or Rollback's own statement runs. */
  private Button.State busyWith(String sql) {
    return tab.firm() && sql.equals(tab.running) ? Button.State.BUSY : Button.State.READY;
  }

  /**
   * Run, or Cancel in its place while work has run for a while; Run all is Control+Shift+Enter. In
   * a place of their own, which the focus stays in when one takes the other's place.
   */
  private El runs(State s, String locked) {
    El button;
    if (tab.stoppable()) {
      button = Button.icon(
              Button.Variant.GHOST,
              Filled.STOP,
              ConsoleTab.CANCEL_BUTTON,
              () -> tab.cancel(s),
              Button.options().tone(Button.Tone.DANGER))
          // Its own key: a press begun on Cancel does not end on Run, which takes its place.
          .key("cancel");
    } else {
      button = Button.icon(
              PRIMARY,
              Filled.PLAY,
              "Run",
              tab.firm() || locked != null ? null : () -> tab.run(s),
              Button.options()
                  // Off, not busy, while a change of its connection is saved or deleted.
                  .state(
                      tab.working() && !tab.firm() && locked == null
                          ? Button.State.BUSY
                          : Button.State.READY)
                  .shortcut(Key.CONTROL, "Enter"))
          .key("run");
    }
    return row().key("runs").child(button.keepFocus());
  }

  /** Fluent's filled Play and Stop (ic_fluent_*_16_filled) alone; the kit's symbols are outlines. */
  private static final class Filled {
    static final Font FONT = Font.resource(ConsoleView.class, "filled-icons.ttf");
    static final Icon PLAY = Icon.glyph(FONT, 0xE9A2, "Run");
    static final Icon STOP = Icon.glyph(FONT, 0xF741, "Stop");
  }

  /** Save query, Saved queries and History. */
  private List<El> kept(State s) {
    return List.of(
        Tooltip.tooltip(
            Button.icon(
                    Button.Variant.GHOST,
                    Symbol.SAVE,
                    "Save query",
                    s.keeping.queriesUsable() ? tab::saveQuery : null)
                .key("query-save-open")
                .keepFocus(),
            "Save query: the selection, or else the whole text, under a name"),
        Tooltip.tooltip(
            Button.icon(
                    Button.Variant.GHOST,
                    Symbol.STAR,
                    "Saved queries",
                    s.keeping.queriesUsable() ? tab::openQueries : null)
                .key("queries-open")
                .keepFocus(),
            "Saved queries"),
        Tooltip.tooltip(
            Button.icon(Button.Variant.GHOST, Symbol.HISTORY, "History", tab::openHistory)
                .key("history-open")
                .keepFocus(),
            "History"));
  }

  /** The databases to switch to: those the tree read, and the one it is on. */
  private El databases(State s) {
    Theme t = Theme.CURRENT.get();
    var names = new LinkedHashSet<String>();
    names.add(tab.database);
    names.addAll(s.catalog.databases(tab.id));
    return Select.select(
            List.copyOf(names),
            tab.database,
            n -> n.isEmpty() ? "No database" : n,
            databases,
            n -> tab.use(s, n))
        .key("database")
        .label("Database")
        .w(6 * t.size().control());
  }

  /** The last run's time and rows, or that it runs. */
  El footer(State s) {
    El status = runStatus(s).key("run-status");
    El badge = badge();
    var end = new ArrayList<El>();
    if (tab.said != null) {
      end.add(Label.label(Label.Variant.MUTED, tab.said).key("said"));
    }
    if (badge != null) {
      end.add(badge);
    }
    return Card.footer(status, end.toArray(El[]::new));
  }

  /** That the console waits for the user's answer, what runs, or how the last run went. */
  private El runStatus(State s) {
    Theme t = Theme.CURRENT.get();
    if (tab.confirming != null) {
      return Label.label(Label.Variant.MUTED, "Waiting for your answer");
    }
    if (tab.busy()) {
      return row()
          .gap(t.space().s())
          .align(Style.Align.CENTER)
          .child(Progress.spinner().label("Running"))
          .child(Label.label(Label.Variant.MUTED, underWay()));
    }
    return Label.label(Label.Variant.MUTED, tab.status(s)).ellipsis();
  }

  /** What runs or waits, as the status line says it. */
  private String underWay() {
    if (tab.activity instanceof ConsoleTab.Waiting) {
      return "Connecting…";
    }
    if (tab.switching != null) {
      return "Switching to " + (tab.switching.isEmpty() ? "no database" : tab.switching) + "…";
    }
    if (!tab.firm()) {
      return tab.writes ? "Running; rows show once all are read…" : "Running…";
    }
    if (ConsoleTab.COMMIT.equals(tab.running)) {
      return "Committing…";
    }
    return ConsoleTab.ROLLBACK.equals(tab.running) ? "Rolling back…" : "Switching auto-commit…";
  }

  /** The transaction, as the server last said it; in Auto none while none is open. */
  private El badge() {
    var o = Badge.options().toolbar();
    if (tab.doubt != null) {
      o.kind(Badge.Kind.DANGER).strong().icon(Symbol.ERROR_CIRCLE);
      return Badge.badge("Outcome not known", o).key("transaction");
    }
    String label = switch (tab.transaction) {
      case NONE -> {
        o.kind(Badge.Kind.NEUTRAL);
        yield tab.mode == ConsoleTab.Mode.MANUAL ? "No transaction" : null;
      }
      case OPEN -> {
        if (tab.empty) {
          o.kind(Badge.Kind.NEUTRAL);
          yield "Transaction open · empty";
        }
        o.kind(Badge.Kind.WARNING).strong().icon(Symbol.LOCK_OPEN);
        yield "Transaction open"
            + (tab.inTransaction > 0 ? " · " + ConsoleTab.counted(tab.inTransaction) : "");
      }
      case FAILED -> {
        o.kind(Badge.Kind.DANGER).strong().icon(Symbol.ERROR_CIRCLE);
        yield "Transaction failed";
      }
      case UNKNOWN -> {
        o.kind(Badge.Kind.WARNING).icon(Symbol.LOCK_OPEN);
        yield "Transaction may be open";
      }
    };
    return label == null ? null : Badge.badge(label, o).key("transaction");
  }

  /** What the question about what may be open says, and the buttons it adds to Cancel. */
  private record Wording(String title, String text, List<El> buttons) {}

  /**
   * The question about what may be open, while it is asked; in the console's window. What it
   * offers follows what is known: Commit only where the server said a transaction is open,
   * Rollback where anything may be, and while Commit or Rollback runs nothing but waiting.
   */
  El question(State s) {
    Theme t = Theme.CURRENT.get();
    var asked = tab.asked(s);
    if (asked == null) {
      return null;
    }
    var buttons = new ArrayList<El>();
    buttons.add(button(SECONDARY, ConsoleTab.CANCEL_BUTTON, () -> tab.answer(s, null))
        .key("answer-cancel"));
    boolean unpinnedOnly = asked.scope() == Guard.Scope.CHANGES;
    boolean discarding = asked.scope() != Guard.Scope.TRANSACTION
        && (tab.writingChanges(unpinnedOnly) || tab.pendingChanges(unpinnedOnly) > 0);
    boolean waits = !discarding && tab.waits() || discarding && tab.writingChanges(unpinnedOnly);
    var wording = wording(s, s.title(tab), asked, waits, discarding);
    buttons.addAll(wording.buttons());
    var body = wording.text();
    if (!discarding && tab.endFailed && !tab.firm() && tab.work() == null && tab.doubt == null) {
      body += " Or end the connection: it is closed, and the server rolls back what was not"
          + " committed.";
      buttons.add(1, endIt(s));
    }
    El content = col()
        .w(ConnectionDialog.width())
        .gap(t.space().m())
        .child(Label.label(body).wrapText())
        .child(
            waits && (discarding || tab.doubt == null)
                ? row()
                    .gap(t.space().s())
                    .align(Style.Align.CENTER)
                    .child(Progress.spinner().label("Waiting"))
                    .child(Label.label(Label.Variant.MUTED, "Waiting for the server…"))
                : null)
        // Why the last Commit or Rollback did not end it, where it did not.
        .child(
            discarding || tab.problem == null || waits || tab.doubt != null
                ? null
                : Callout.callout(Callout.Kind.DANGER, tab.problem.title(), tab.problem.text())
                    .key("question-problem"));
    return Dialog.dialog(wording.title(), content, buttons, () -> tab.answer(s, null))
        .key("transaction-question");
  }

  /** What the question says before pending changes of a result are lost, or while one commits. */
  private Wording discarding(State s, String name, Guard.Pending.Question asked) {
    String before = asked.what();
    boolean unpinnedOnly = asked.scope() == Guard.Scope.CHANGES;
    if (tab.writingChanges(unpinnedOnly)) {
      return new Wording(
          "Waiting for changes to commit",
          "Changes are being committed on " + name + ". Wait until the commit finishes, or"
              + " cancel it: whether it took effect is then not known, and the changes stay.",
          List.of(button(
                  SECONDARY,
                  "Cancel commit",
                  () -> tab.answer(s, ConsoleTab.STOP_WRITES),
                  Button.options().tone(Button.Tone.DANGER))
              .key("answer-cancel-commit")));
    }
    int n = tab.pendingChanges(unpinnedOnly);
    String changes = n == 1 ? "1 change" : n + " changes";
    boolean unsure =
        tab.outputs.stream().anyMatch(o -> (!unpinnedOnly || !o.pinned) && o.edits.unknown);
    return new Wording(
        "Discard " + changes + "?",
        name + " has " + (n == 1 ? "1 uncommitted change" : n + " uncommitted changes")
            + ". Discard " + (n == 1 ? "it" : "them") + " before " + before + "?"
            + (unsure
                ? " Whether the last commit took effect is not known: discarding drops the changes"
                    + " here and undoes nothing on the server."
                : ""),
        List.of(button(
                PRIMARY,
                "Discard",
                () -> tab.answer(s, ConsoleTab.DISCARD),
                Button.options().tone(Button.Tone.DANGER))
            .key("answer-discard")));
  }

  /** What the question says on the console {@code name} before the act {@code asked}. */
  private Wording wording(
      State s, String name, Guard.Pending.Question asked, boolean waits, boolean discarding) {
    String before = asked.what();
    if (discarding) {
      return discarding(s, name, asked);
    }
    if (tab.doubt != null) {
      return new Wording(
          ConsoleTab.UNSURE,
          "On " + name + ", " + tab.doubt + " Go on, and " + before + "?",
          List.of(button(PRIMARY, "Continue", () -> tab.answer(s, ConsoleTab.CONTINUE))
              .key("answer-continue")));
    }
    if (tab.firm()) {
      return waitingForEnd(s, name, before, tab.firm.overdue());
    }
    if (waits) {
      // What it promises nothing of: no Rollback, which could not say what it undid.
      return new Wording(
          "Waiting for " + ConsoleTab.brief(tab.running),
          ConsoleTab.brief(tab.running) + " still runs on " + name
              + ". Wait: once it has ended with nothing"
              + " left open, " + before + ". Or go on now: the connection ends, the server rolls"
              + " back what was not committed, and what it did with this statement will not be"
              + " known.",
          List.of(button(
                  SECONDARY,
                  "Go on now",
                  () -> tab.answer(s, ConsoleTab.GO_ON),
                  Button.options().tone(Button.Tone.DANGER))
              .key("answer-go-on")));
    }
    if (tab.work() != null) {
      return rollBackFirst(
          s,
          "A statement still runs on " + name + ", and a transaction may be open. Roll back,"
              + " which cancels the statement, or wait: once it has ended with nothing open, "
              + before + ".");
    }
    if (tab.transaction == Transaction.FAILED) {
      return rollBackFirst(
          s,
          "The transaction of " + name + " failed: the server takes nothing more in it but a"
              + " rollback. Roll it back before " + before + ".");
    }
    if (!tab.canCommit()) {
      return rollBackFirst(
          s,
          "A transaction may be open on " + name + ": the server has not said. Roll it back"
              + " before " + before + ".");
    }
    // A transaction is open, which is not to say that anything in it changed.
    return new Wording(
        "Commit the transaction?",
        name + " has a transaction open"
            + (tab.inTransaction > 0 ? ", after " + ConsoleTab.counted(tab.inTransaction) : "")
            + ". Commit it or roll it back before " + before + ".",
        List.of(
            button(SECONDARY, ConsoleTab.ROLLBACK_BUTTON, () -> tab.answer(s, ConsoleTab.ROLLBACK))
                .key("answer-rollback"),
            button(PRIMARY, "Commit", () -> tab.answer(s, ConsoleTab.COMMIT))
                .key("answer-commit")));
  }

  /** Commit, Rollback or a switch of auto-commit runs: the question waits for it. */
  private Wording waitingForEnd(State s, String name, String before, boolean overdue) {
    String title;
    if (ConsoleTab.COMMIT.equals(tab.running)) {
      title = "Waiting for the commit";
    } else if (ConsoleTab.ROLLBACK.equals(tab.running)) {
      title = "Waiting for the rollback";
    } else {
      title = "Waiting for the server";
    }
    var body = name + " waits for the server's answer. Once nothing is left open, " + before + ".";
    if (!overdue) {
      return new Wording(title, body, List.of());
    }
    return new Wording(
        title,
        body + " It has not answered for a while. Or end the connection now: it is closed, the"
            + " server rolls back what was not committed, and what this did is not known.",
        List.of(endIt(s)));
  }

  /** The question that offers Rollback alone, with Cancel. */
  private Wording rollBackFirst(State s, String text) {
    return new Wording(
        ConsoleTab.ROLL_BACK_FIRST,
        text,
        List.of(
            button(PRIMARY, ConsoleTab.ROLLBACK_BUTTON, () -> tab.answer(s, ConsoleTab.ROLLBACK))
                .key("answer-rollback")));
  }

  /** What is asked before a statement is sent, while it is; in the console's window. */
  El confirmation(State s) {
    Theme t = Theme.CURRENT.get();
    var asked = tab.confirming;
    if (asked == null) {
      return null;
    }
    var options = asked.danger() ? Button.options().tone(Button.Tone.DANGER) : Button.options();
    return Dialog.dialog(
            asked.title(),
            col()
                .w(ConnectionDialog.width())
                .gap(t.space().m())
                .child(Label.label(asked.text()).wrapText()),
            List.of(
                button(SECONDARY, ConsoleTab.CANCEL_BUTTON, tab::decline).key("confirm-cancel"),
                button(PRIMARY, "Run anyway", () -> tab.proceed(s), options).key("confirm-run")),
            tab::decline)
        .key("run-question");
  }

  /** The way out where the server does not answer, or its answer keeps failing. */
  private El endIt(State s) {
    return button(
            SECONDARY,
            "End the connection",
            () -> tab.answer(s, ConsoleTab.GO_ON),
            Button.options().tone(Button.Tone.DANGER))
        .key("answer-go-on");
  }
}

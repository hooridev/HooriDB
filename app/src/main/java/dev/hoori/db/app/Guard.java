package dev.hoori.db.app;

import com.rethinkj.ui.El;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one act in the app that may wait, a part of the state: while a tab asks what becomes of
 * what is at stake in it, or a file that runs in one transaction asks before it is cancelled, or
 * once the act is armed and locks tabs of its connections. Only the app's thread uses it.
 */
final class Guard {

  Pending pending; // the one act that waits, or null

  /**
   * A tab that may hold uncommitted work and asks before an act discards or ends it, or the run of
   * a file in one transaction, which asks in the main window.
   */
  sealed interface Guarded permits ConsoleTab, TableTab, FileRunDialog {

    /** The connection this tab belongs to, for {@link Pending.Locked}. */
    UUID connectionId();

    /** Whether something within {@code scope} is at stake on this tab. */
    boolean atStake(Scope scope);

    /** Shows the tab in its window, raised, so the user sees its question. */
    default void show(State s) {
      var tab = (Tab) this;
      var group = s.tabs.groupOf(tab);
      if (group == null) {
        return;
      }
      s.tabs.select(group, group.tabs().indexOf(tab));
      // The main window's tabs have no window of their own: the app's is theirs.
      var window = group.window();
      if (window == null && s.app != null) {
        window = s.app.windows().of(s.app);
      }
      if (window != null) {
        window.raise();
      }
    }

    /** The question dialog while {@link Pending.Question} names this tab, or null. */
    El question(State s);
  }

  /** What a question protects: both pending grid changes and the transaction, or one of them. */
  enum Scope {
    /** Pending edits first, then a console's transaction if still at stake. */
    BOTH,
    /** The console's transaction alone, keeping pending result edits (for Commit…). */
    TRANSACTION,
    /** Pending edits alone, leaving any transaction open (for Run, Refresh, or filter changes). */
    CHANGES
  }

  /**
   * The one act in the app that waits, with what owns it and what it is: a {@link Question} while a
   * tab asks, or {@link Locked} once it is armed.
   */
  sealed interface Pending {
    Object owner();

    String what();

    /** The act waits on the question of a tab, and goes on by {@code then} once it is settled. */
    record Question(Object owner, String what, Guarded tab, Runnable then, Scope scope)
        implements Pending {}

    /**
     * The act is armed and locks the tabs of a scope until it is done: those of these connections,
     * or all of them for null.
     */
    record Locked(Object owner, String what, Set<UUID> scope) implements Pending {}
  }

  /**
   * An act that may wait, as the owner of {@link Pending}: what it does, and to what. Two are the
   * same act only where both are the same; another act, on the same connection or tab, is refused
   * while one waits.
   */
  record Act(String kind, Object target) {}

  // The owner of the act of Exit, and of Restart now.
  static final Object EXIT = new Object();

  /**
   * The one guard of every way out that would end consoles' sessions or discard uncommitted
   * changes: the first of the tabs where something is at stake ({@link Guarded#atStake}) asks in
   * its window what becomes of it, and {@code then} runs again once that is settled. True while one
   * asks, and the caller goes no further. While another act waits, one that would ask is refused,
   * visibly, instead of taking its place.
   */
  boolean asks(State s, Object owner, String what, List<? extends Guarded> tabs, Runnable then) {
    return asks(s, owner, what, tabs, then, Scope.BOTH);
  }

  /**
   * As {@link #asks(State, Object, String, List, Runnable)}, asking only for what {@code scope}
   * covers.
   */
  boolean asks(
      State s,
      Object owner,
      String what,
      List<? extends Guarded> tabs,
      Runnable then,
      Scope scope) {
    var asking = tabs.stream().filter(t -> t.atStake(scope)).findFirst().orElse(null);
    boolean mine = pending != null && Objects.equals(pending.owner(), owner);
    if (asking == null) {
      if (mine && pending instanceof Pending.Question) {
        pending = null;
      }
      return false;
    }
    if (pending != null && !mine) {
      refuse(s);
      return true;
    }
    pending = new Pending.Question(owner, what, asking, then, scope);
    asking.show(s);
    return true;
  }

  /**
   * Arms the act once nothing of its tabs is at stake: from now until it is done or given up
   * ({@link #drop}), those tabs start nothing, so that nothing comes between the answers and the
   * act. False, refused visibly, while another act waits.
   *
   * @param scope the connections whose tabs it locks, or null for all
   */
  boolean lock(State s, Object owner, String what, Set<UUID> scope) {
    if (pending != null && !Objects.equals(pending.owner(), owner)) {
      refuse(s);
      return false;
    }
    pending = new Pending.Locked(owner, what, scope);
    return true;
  }

  private void refuse(State s) {
    switch (pending) {
      case Pending.Locked locked -> s.tell(locked.what() + ": that comes first.");
      case Pending.Question question -> {
        s.tell(
            switch (question.tab()) {
              case ConsoleTab _ -> "A question about a console's transaction waits for its answer.";
              case TableTab _ -> "A question about uncommitted changes waits for its answer.";
              case FileRunDialog _ -> "A question about a file's run waits for its answer.";
            });
        question.tab().show(s);
      }
    }
  }

  /** The act is done, or given up: with what owns it, or with the tab it waited on. */
  void drop(Object owner) {
    if (pending != null
        && (Objects.equals(pending.owner(), owner)
            || pending instanceof Pending.Question question && question.tab() == owner)) {
      pending = null;
    }
  }

  /** Whether the act waits: for a tab's answer, or until it is done. */
  boolean pends(Object owner) {
    return pending != null && Objects.equals(pending.owner(), owner);
  }

  /** Why the tab may start nothing now, or null. */
  String locked(Guarded tab) {
    return pending instanceof Pending.Locked locked
            && (locked.scope() == null
                || tab.connectionId() != null && locked.scope().contains(tab.connectionId())
                || tab instanceof ConsoleTab c
                    && c.queryId != null
                    && locked.scope().contains(c.queryId))
        ? locked.what()
        : null;
  }

  boolean quitting() {
    return pending instanceof Pending.Locked locked && locked.owner() == EXIT;
  }

  /** Whether Exit, or Restart now, asks or is under way: nothing comes between. */
  boolean exiting() {
    return pending != null && pending.owner() == EXIT;
  }
}

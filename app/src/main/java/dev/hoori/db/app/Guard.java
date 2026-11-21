package dev.hoori.db.app;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one act in the app that may wait, a part of the state: while a console asks what becomes of
 * what is at stake in it, or once the act is armed and locks consoles. Only the app's thread uses
 * it.
 */
final class Guard {

  Pending pending; // the one act that waits, or null

  /**
   * The one act in the app that waits, with what owns it and what it is: a {@link Question} while a
   * console asks, or {@link Locked} once it is armed.
   */
  sealed interface Pending {
    Object owner();

    String what();

    /** The act waits on the question of a console, and goes on by {@code then} once it is settled. */
    record Question(Object owner, String what, ConsoleTab console, Runnable then)
        implements Pending {}

    /**
     * The act is armed and locks the consoles of a scope until it is done: those of these
     * connections, or all of them for null.
     */
    record Locked(Object owner, String what, Set<UUID> scope) implements Pending {}
  }

  /**
   * An act that may wait, as the owner of {@link Pending}: what it does, and to what. Two are the
   * same act only where both are the same; another act, on the same connection or console, is
   * refused while one waits.
   */
  record Act(String kind, Object target) {}

  // The owner of the act of Exit, and of Restart now.
  static final Object EXIT = new Object();

  /**
   * The one guard of every way out that would end consoles' sessions or their transactions: the
   * first of the consoles where something is at stake ({@link ConsoleTab#atStake}) asks in its
   * window what becomes of it, and {@code then} runs again once that is settled. True while one
   * asks, and the caller goes no further. While another act waits, one that would ask is refused,
   * visibly, instead of taking its place.
   */
  boolean asks(State s, Object owner, String what, List<ConsoleTab> consoles, Runnable then) {
    var asking = consoles.stream().filter(ConsoleTab::atStake).findFirst().orElse(null);
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
    pending = new Pending.Question(owner, what, asking, then);
    asking.show(s);
    return true;
  }

  /**
   * Arms the act once nothing of its consoles is at stake: from now until it is done or given up
   * ({@link #drop}), those consoles start nothing, so that nothing comes between the answers and the
   * act. False, refused visibly, while another act waits.
   *
   * @param scope the connections whose consoles it locks, or null for all
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
        s.tell("A question about a console's transaction waits for its answer.");
        question.console().show(s);
      }
    }
  }

  /** The act is done, or given up: with what owns it, or with the console it waited on. */
  void drop(Object owner) {
    if (pending != null
        && (Objects.equals(pending.owner(), owner)
            || pending instanceof Pending.Question question && question.console() == owner)) {
      pending = null;
    }
  }

  /** Whether the act waits: for a console's answer, or until it is done. */
  boolean pends(Object owner) {
    return pending != null && Objects.equals(pending.owner(), owner);
  }

  /** Why the console may start nothing now, or null. */
  String locked(ConsoleTab console) {
    return pending instanceof Pending.Locked locked
            && (locked.scope() == null || locked.scope().contains(console.id))
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

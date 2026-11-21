package dev.hoori.db.app;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One call's outcome between the lane and the console: pending, done (the lane has it),
 * delivered (the console took it), or given up (cancelled, or its session ended). The two
 * threads meet here atomically, and whichever comes second of the outcome and the giving up
 * posts what matters of it as a notice ({@link #matters}): the lane where it was given up
 * before, the app's thread where the outcome was there and not taken.
 */
final class Delivery {
  static final int PENDING = 0;
  private static final int DONE = 1;
  private static final int DELIVERED = 2;
  private static final int GIVEN_UP = 3;

  private final Cell state;
  private final Tell tell;
  private final String sql; // what a notice names; null for a call whose outcome tells nothing
  private final boolean plain;
  private volatile Object outcome; // the value, or the exception

  Delivery(Tell tell, String sql, boolean plain) {
    this(tell, sql, plain, Cell.of(PENDING));
  }

  Delivery(Tell tell, String sql, boolean plain, Cell state) {
    this.tell = tell;
    this.sql = sql;
    this.plain = plain;
    this.state = state;
  }

  /** The one atomic variable of its state; a test steps the two threads through it. */
  interface Cell {
    int get();

    boolean compareAndSet(int expected, int next);

    static Cell of(int initial) {
      var value = new AtomicInteger(initial);
      return new Cell() {
        @Override
        public int get() {
          return value.get();
        }

        @Override
        public boolean compareAndSet(int expected, int next) {
          return value.compareAndSet(expected, next);
        }
      };
    }
  }

  /** On the lane: the call has its outcome. */
  void done(Object outcome) {
    this.outcome = outcome;
    while (true) {
      int now = state.get();
      if (now == PENDING && state.compareAndSet(PENDING, DONE)) {
        return; // the console takes it, or gives it up and posts it
      }
      if (now == GIVEN_UP) {
        post(); // the console gave it up before
        return;
      }
      if (now == DELIVERED) {
        return; // the lane failed before the call, which the console took as its outcome
      }
    }
  }

  /**
   * On the app's thread, with the call's result or failure: whether it goes to the console, which
   * then has it. Also where the lane ended the call without making it, as when it could not open
   * its connection or was closed: what came instead is the outcome.
   */
  boolean deliver() {
    while (true) {
      int now = state.get();
      if (now == GIVEN_UP || now == DELIVERED) {
        return false;
      }
      if (state.compareAndSet(now, DELIVERED)) {
        return true;
      }
    }
  }

  /** On the app's thread: the console no longer waits for it. */
  void giveUp() {
    while (true) {
      int now = state.get();
      if (now == PENDING && state.compareAndSet(PENDING, GIVEN_UP)) {
        return; // the lane posts it, once it has it
      }
      if (now == DONE && state.compareAndSet(DONE, GIVEN_UP)) {
        post(); // there, and not taken
        return;
      }
      if (now == GIVEN_UP || now == DELIVERED) {
        return;
      }
    }
  }

  /** Whether the lane has the outcome and the console has not taken it yet. */
  boolean waiting() {
    return state.get() == DONE;
  }

  /** The outcome the lane has and the console has not taken yet, or null. */
  Object untaken() {
    return waiting() ? outcome : null;
  }

  private void post() {
    if (sql != null && tell != null) {
      matters(outcome, plain).forEach(m -> tell.tell(sql, m.what(), m.failure()));
    }
  }

  /** What a notice says of an outcome: its words, and whether it is a failure. */
  record Matter(String what, boolean failure) {}

  /**
   * What matters of an outcome the console did not take: any failure, and any warning the server
   * sent with a statement that is not a plain read. Not what the giving up itself made, a cancel,
   * nor what is said already: a statement not sent, a give-up's failure, Commit's and Rollback's
   * own outcome ({@link Session#finish} tells it).
   */
  static List<Matter> matters(Object outcome, boolean plain) {
    return switch (outcome) {
      case Session.Ran r -> matters(r.portion(), plain);
      case Portion p -> matters(p, plain);
      case InterruptedException e -> List.of();
      case Session.NotRun e -> List.of();
      case Session.TooMany e -> List.of();
      case Session.GaveUp e -> List.of();
      case QueryException e when e.failure() == QueryException.Failure.CANCELLED -> List.of();
      case ConnectionException e
      when e.failure() == ConnectionException.Failure.CANCELLED -> List.of();
      case Exception e -> List.of(new Matter("It failed: " + State.message(e), true));
      case null, default -> List.of();
    };
  }

  private static List<Matter> matters(Portion portion, boolean plain) {
    var all = new ArrayList<Matter>();
    var failure = Session.failure(portion);
    if (failure != null && failure.kind() != Failure.Kind.CANCELLED) {
      all.add(new Matter("It failed: " + failure.message(), true));
    }
    if (!plain) {
      for (var m : portion.messages()) {
        all.add(matter(m));
      }
    }
    return all;
  }

  /** What a notice says of a message of the server's: a rollback in part, or a warning. */
  static Matter matter(Message m) {
    return m.code() == ConsoleTab.NOT_ROLLED_BACK
        ? new Matter(ConsoleTab.PARTLY + ": " + m.text() + ". Those changes stand.", true)
        : new Matter(warned(m), false);
  }

  /** A warning of the server's, with its code. */
  static String warned(Message m) {
    return "The server warned: " + m.text() + coded(m);
  }

  /** A message's code in parentheses after a space, its SQLSTATE before the vendor's; or none. */
  static String coded(Message m) {
    String code;
    if (m.state().isEmpty()) {
      code = m.code() == 0 ? "" : String.valueOf(m.code());
    } else {
      code = m.state();
    }
    return code.isEmpty() ? "" : " (" + code + ")";
  }

  /**
   * Says what finishing something on a session did, from whichever thread sees it, at once: to the
   * app's notices ({@link State#notice}), which nothing that can be cancelled, closed or replaced
   * stands between.
   */
  interface Tell {
    void tell(String sql, String what, boolean failure);
  }
}

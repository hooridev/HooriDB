package dev.hoori.db.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A call's outcome between the lane and the console, through every interleaving of the two threads'
 * steps on its state: it is delivered, or posted by the lane, or posted by the app, exactly once.
 */
class DeliveryTest {

  // An outcome that matters: a failure.
  private static final Object FAILED = FakeServers.queryFailure(QueryException.Failure.OTHER);

  /**
   * The state, whose every read and compare-and-set waits for its turn: the driver lets one side
   * step at a time, by a schedule.
   */
  private static final class Turns implements Delivery.Cell {
    private final AtomicInteger value = new AtomicInteger(Delivery.PENDING);
    private final ThreadLocal<Integer> side = new ThreadLocal<>();
    private final Semaphore[] go = {new Semaphore(0), new Semaphore(0)};
    private final Semaphore arrived = new Semaphore(0);
    private final boolean[] waiting = new boolean[2];
    private final boolean[] over = new boolean[2];

    private void turn() {
      int me = side.get();
      synchronized (this) {
        waiting[me] = true;
      }
      arrived.release();
      go[me].acquireUninterruptibly();
    }

    @Override
    public int get() {
      turn();
      return value.get();
    }

    @Override
    public boolean compareAndSet(int expected, int next) {
      turn();
      return value.compareAndSet(expected, next);
    }

    Thread start(int me, Runnable steps) {
      return Thread.ofPlatform().start(() -> {
        side.set(me);
        try {
          steps.run();
        } finally {
          synchronized (this) {
            over[me] = true;
          }
          arrived.release();
        }
      });
    }

    /**
     * Runs both sides by the schedule, then the first side wherever both may step; how many times
     * both could step.
     */
    int run(List<Integer> schedule) throws InterruptedException {
      int decisions = 0;
      while (true) {
        // Each side is waiting at a step or is over.
        while (true) {
          synchronized (this) {
            if ((waiting[0] || over[0]) && (waiting[1] || over[1])) {
              break;
            }
          }
          arrived.acquire();
        }
        int pick;
        synchronized (this) {
          if (over[0] && over[1]) {
            return decisions;
          }
          if (waiting[0] && waiting[1]) {
            pick = decisions < schedule.size() ? schedule.get(decisions) : 0;
            decisions++;
          } else {
            pick = waiting[0] ? 0 : 1;
          }
          waiting[pick] = false;
        }
        go[pick].release();
      }
    }
  }

  /** The lane's done and the app's give-up, by a schedule: how many times it ended. */
  private static int ended(List<Integer> schedule, int[] decisions) throws Exception {
    var posted = new AtomicInteger();
    var turns = new Turns();
    var delivery =
        new Delivery((sql, what, failure) -> posted.incrementAndGet(), "UPDATE t", false, turns);
    var lane = turns.start(0, () -> delivery.done(FAILED));
    var app = turns.start(1, delivery::giveUp);
    decisions[0] = turns.run(schedule);
    lane.join();
    app.join();
    return posted.get();
  }

  @Test
  void theOutcomeOfACallGivenUpIsPostedExactlyOnceInEveryInterleaving() throws Exception {
    var explored = new ArrayList<List<Integer>>();
    var todo = new ArrayList<List<Integer>>();
    todo.add(List.of());
    while (!todo.isEmpty()) {
      var prefix = todo.removeLast();
      var decisions = new int[1];
      int posted = ended(prefix, decisions);
      explored.add(prefix);
      assertEquals(1, posted, "posted once by the schedule " + prefix);
      // Each choice taken by default after the prefix branches the other way too.
      for (int i = prefix.size(); i < decisions[0]; i++) {
        var other = new ArrayList<>(prefix);
        while (other.size() < i) {
          other.add(0);
        }
        other.add(1);
        todo.add(other);
      }
    }
    assertTrue(explored.size() > 2, "interleavings explored: " + explored.size());
  }

  @Test
  void anOutcomeTakenIsNotPostedAndOneTheLaneNeverMadeIsTaken() {
    var posted = new AtomicInteger();
    var taken = new Delivery((sql, what, failure) -> posted.incrementAndGet(), "x", false);
    taken.done(FAILED);
    assertTrue(taken.deliver());
    taken.giveUp();
    assertEquals(0, posted.get());
    // The lane failed before it made the call: what came instead is the outcome, taken once.
    var unmade = new Delivery((sql, what, failure) -> posted.incrementAndGet(), "x", false);
    assertTrue(unmade.deliver());
    assertEquals(false, unmade.deliver());
    unmade.giveUp();
    assertEquals(0, posted.get());
  }
}

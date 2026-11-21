package dev.hoori.db.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Giving up a statement ends within its bound, also where a driver call never returns. */
class AbandonBoundTest {

  /** The bound the app ships, which every other test of a give-up shortens. */
  static final Duration SHIPPED = Duration.ofSeconds(10);

  @Test
  void aGiveUpWhoseDriverCallNeverReturnsEndsWithinItsBound() throws Exception {
    var stuck = new Stuck();
    try (var _ = new ShortEnding()) {
      givenUpStuck(stuck, ShortEnding.BOUND, Query.GRACE.multipliedBy(3));
    } finally {
      stuck.release();
    }
  }

  /** As the app ships it: the give-up waits out the ten seconds, and not much more. */
  @Test
  void aGiveUpWhoseDriverCallNeverReturnsEndsAtTheShippedBound() throws Exception {
    assertEquals(Execution.ENDING, Execution.giveUpBound, "no shorter bound left behind");
    var stuck = new Stuck();
    try {
      givenUpStuck(stuck, SHIPPED, Query.GRACE.multipliedBy(2));
    } finally {
      stuck.release();
    }
  }

  // Gives up a read whose driver call hangs: it ends as the bound passes, within the slack after.
  private static void givenUpStuck(Stuck stuck, Duration bound, Duration slack) throws Exception {
    var read = new Execution(stuck.connection(), Provider.POSTGRESQL, "SELECT a FROM t");
    assertTrue(read.next(10).more());
    stuck.arm();
    long started = System.nanoTime();
    var ended = CompletableFuture.supplyAsync(read::abandon)
        .get(bound.plus(slack).toMillis(), TimeUnit.MILLISECONDS);
    var took = Duration.ofNanos(System.nanoTime() - started);
    var failure = ended.failure().orElseThrow();
    assertEquals(Failure.Kind.CONNECTION_LOST, failure.kind());
    assertTrue(took.compareTo(bound) >= 0, "not before its bound: " + took);
    assertTrue(
        failure.message().contains("did not answer within " + bound.toSeconds() + " seconds"),
        failure.message());
    assertTrue(stuck.stuck() != null && stuck.stuck().isAlive(), "still in the hung call");
  }

  /**
   * The worker of a give-up that the deadline cut off wakes up after all: it does nothing more on
   * the connection given up, neither commits the statement's own transaction, nor rolls it back,
   * nor turns auto-commit on again.
   */
  @Test
  void aGiveUpsWorkerThatWakesUpAfterItsDeadlineChangesNothing() throws Exception {
    var stuck = new Stuck();
    try (var _ = new ShortEnding()) {
      var read = new Execution(stuck.connection(), Provider.POSTGRESQL, "SELECT a FROM t");
      assertTrue(read.next(10).more());
      assertTrue(stuck.calls.contains("setAutoCommit(false)"), "its own transaction");
      stuck.arm();
      var ended = read.abandon();
      assertEquals(Failure.Kind.CONNECTION_LOST, ended.failure().orElseThrow().kind());
      int before = stuck.calls.size();
      var worker = stuck.stuck();
      stuck.release();
      assertTrue(worker.join(Duration.ofSeconds(10)), "the worker ended");
      var after = stuck.calls.subList(before, stuck.calls.size());
      for (var call : after) {
        assertTrue(
            !call.equals("commit") && !call.equals("rollback") && !call.startsWith("setAutoCommit"),
            "after its deadline: " + after);
      }
    } finally {
      stuck.release();
    }
  }
}

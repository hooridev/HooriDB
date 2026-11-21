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

  @Test
  void aGiveUpWhoseDriverCallNeverReturnsEndsWithinItsBound() throws Exception {
    var stuck = new Stuck();
    try (var _ = new ShortEnding()) {
      var read = new Execution(stuck.connection(), Provider.POSTGRESQL, "SELECT a FROM t");
      assertTrue(read.next(10).more());
      stuck.arm();
      var bound = ShortEnding.BOUND.plus(Query.GRACE.multipliedBy(3));
      var ended =
          CompletableFuture.supplyAsync(read::abandon).get(bound.toMillis(), TimeUnit.MILLISECONDS);
      assertEquals(Failure.Kind.CONNECTION_LOST, ended.failure().orElseThrow().kind());
    } finally {
      stuck.release();
    }
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

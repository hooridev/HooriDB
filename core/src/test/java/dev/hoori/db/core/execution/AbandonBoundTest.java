package dev.hoori.db.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.query.Query;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Giving up a statement ends within its bound, also where a driver call never returns. */
class AbandonBoundTest {

  @Test
  void aGiveUpWhoseDriverCallNeverReturnsEndsWithinItsBound() throws Exception {
    var release = new CountDownLatch(1);
    var stuck = new AtomicBoolean();
    try {
      var read =
          new Execution(Stuck.connection(stuck, release), Provider.POSTGRESQL, "SELECT a FROM t");
      assertTrue(read.next(10).more());
      stuck.set(true);
      var bound = Execution.ENDING.plus(Query.GRACE.multipliedBy(3));
      var ended =
          CompletableFuture.supplyAsync(read::abandon).get(bound.toMillis(), TimeUnit.MILLISECONDS);
      assertEquals(Failure.Kind.CONNECTION_LOST, ended.failure().orElseThrow().kind());
    } finally {
      release.countDown();
    }
  }
}

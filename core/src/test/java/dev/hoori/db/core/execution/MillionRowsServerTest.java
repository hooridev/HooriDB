package dev.hoori.db.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.connection.Provider;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reads all of a million rows made by the server, a thousand at a time, and the heap stays as it
 * was. It takes seconds per server, so it runs only when asked: {@code mvn -pl core test
 * -Dtest=MillionRowsServerTest -Dhooridb.test.deep=true}. It prints the times and the heap.
 */
@EnabledIfSystemProperty(named = "hooridb.test.deep", matches = "true")
class MillionRowsServerTest {

  @ParameterizedTest
  @EnumSource(Provider.class)
  void aMillionRowsKeepMemoryFlat(Provider provider) throws Exception {
    try (var connection = ExecutionServerTest.open(provider);
        var execution =
            new Execution(connection, provider, ExecutionServerTest.million(provider))) {
      var started = System.nanoTime();
      var before = heap();
      long most = 0;
      long read = 0;
      Portion portion;
      do {
        portion = execution.next(1000);
        read += ((Result.Rows) portion.results().getFirst()).rows().size();
        if (read % 100_000 == 0) {
          most = Math.max(most, heap() - before);
        }
      } while (portion.more());
      assertEquals(1_000_000, read);
      System.out.printf(
          "%s: a million rows in %.1f s, the heap at most %d MB above the start%n",
          provider, (System.nanoTime() - started) / 1e9, most >> 20);
      assertTrue(most < 16 << 20, provider + " grew by " + most + " bytes");
    }
  }

  private static long heap() {
    var runtime = Runtime.getRuntime();
    System.gc();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}

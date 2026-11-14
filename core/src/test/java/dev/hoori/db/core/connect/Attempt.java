package dev.hoori.db.core.connect;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.hoori.db.core.TestServer;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.Duration;

/** An open on a thread of its own, as the app calls it, which a test may give up on. */
final class Attempt {

  private final Thread caller;
  private volatile ConnectionException failure;
  private volatile boolean interrupted;

  private Attempt(ConnectionProfile profile, char[] password, Duration timeout) {
    var driver = TestServer.driver(profile.provider());
    caller = Thread.ofPlatform().start(() -> {
      try {
        Connector.close(Connector.open(profile, password, driver, timeout));
      } catch (ConnectionException e) {
        failure = e;
      }
      interrupted = Thread.currentThread().isInterrupted();
    });
  }

  static Attempt start(ConnectionProfile profile, Duration timeout) {
    return new Attempt(profile, TestServer.password(), timeout);
  }

  static Attempt start(ConnectionProfile profile, char[] password, Duration timeout) {
    return new Attempt(profile, password, timeout);
  }

  /** Interrupts the caller, and gives the time until it was released. */
  Duration cancel() throws InterruptedException {
    var start = System.nanoTime();
    caller.interrupt();
    return end(start);
  }

  /** Waits for the caller, and gives the time until it was released. */
  Duration end() throws InterruptedException {
    return end(System.nanoTime());
  }

  private Duration end(long start) throws InterruptedException {
    caller.join(Duration.ofSeconds(30));
    assertFalse(caller.isAlive());
    return Duration.ofNanos(System.nanoTime() - start);
  }

  ConnectionException failure() {
    return failure;
  }

  /** Whether the caller was still interrupted when it returned. */
  boolean interrupted() {
    return interrupted;
  }

  /**
   * Waits until no thread of {@link Connector} works for the provider any longer, and gives the
   * time it took, or null if one still does after the time.
   */
  static Duration workersEnd(Provider provider, Duration timeout) throws Exception {
    var name = "\"connect " + provider.displayName() + "\"";
    var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    var dir = Files.createTempDirectory("threads");
    var start = System.nanoTime();
    try {
      for (int i = 0; ; i++) {
        // The only dump that lists virtual threads.
        var file = dir.resolve(i + ".txt");
        bean.dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
        var alive = Files.readString(file).contains(name);
        Files.delete(file);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (!alive) {
          return elapsed;
        }
        if (elapsed.compareTo(timeout) > 0) {
          return null;
        }
        Thread.sleep(20);
      }
    } finally {
      Files.delete(dir);
    }
  }
}

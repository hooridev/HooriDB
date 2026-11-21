package dev.hoori.db.core;

import dev.hoori.db.core.connection.Provider;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Starts the servers of SQL of the JVM as its tests begin, beside the classes that come before the
 * first that asks for them, where {@code HOORIDB_TEST_EARLY_SERVERS} is {@code true}, as in CI's
 * shards that run classes using them. They are the JVM's own, as {@link TestServer#of} gives
 * them; a class that asks meanwhile waits for them, and one whose start failed here starts them
 * again and fails as it would have.
 */
public final class EarlyServers implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    if ("true".equals(System.getenv("HOORIDB_TEST_EARLY_SERVERS"))) {
      Thread.ofPlatform().daemon().name("early-servers").start(EarlyServers::start);
    }
  }

  private static void start() {
    try {
      TestServer.of(Provider.POSTGRESQL);
    } catch (RuntimeException e) {
      // The class that asks next starts them again and reports what fails.
    }
  }
}

package dev.hoori.db.core.driver;

import java.io.IOException;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A driver loaded in a class loader of its own, which sees the JDK's platform modules, among them
 * {@code java.sql}, but none of HooriDB's classes. Two versions of one driver load side by side.
 * Connections go to the driver directly and never through {@link java.sql.DriverManager}.
 */
public final class LoadedDriver implements AutoCloseable {

  private final java.sql.Driver driver;
  private final URLClassLoader loader;
  private int holds; // under this object's lock

  LoadedDriver(java.sql.Driver driver, URLClassLoader loader) {
    this.driver = driver;
    this.loader = loader;
  }

  public java.sql.Driver driver() {
    return driver;
  }

  /** Opens a connection; the properties hold the user and the password, if any. */
  public Connection connect(String url, Properties properties) throws SQLException {
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    // Some drivers look up their own parts through the thread's class loader.
    thread.setContextClassLoader(loader);
    try {
      var connection = driver.connect(url, properties);
      if (connection == null) {
        throw new SQLException("The driver does not accept this URL");
      }
      return connection;
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * Holds the driver for a connection that may still run its code, such as one that closes: {@link
   * #released} waits for the hold's end, which the call returned makes, once however often it is
   * called, on any thread.
   */
  public Runnable hold() {
    synchronized (this) {
      holds++;
    }
    var ended = new AtomicBoolean();
    return () -> {
      if (ended.compareAndSet(false, true)) {
        synchronized (this) {
          holds--;
          notifyAll();
        }
      }
    };
  }

  /** Waits until no hold is left, at most for the limit; whether none is. */
  public synchronized boolean released(Duration limit) throws InterruptedException {
    var deadline = System.nanoTime() + limit.toNanos();
    while (holds > 0) {
      var left = deadline - System.nanoTime();
      if (left <= 0) {
        return false;
      }
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    return true;
  }

  /**
   * Closes the class loader at once, held or not; connections already open should be closed
   * first.
   */
  @Override
  public void close() throws IOException {
    loader.close();
  }
}

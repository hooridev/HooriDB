package dev.hoori.db.core.driver;

import dev.hoori.db.core.document.DocumentClient;
import dev.hoori.db.core.document.DocumentDriver;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.Target;
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
 * Connections go to the driver directly and never through {@link java.sql.DriverManager}. A driver
 * of documents is reached through a class of core's that is defined in the driver's class loader,
 * and that alone sees core: the {@link DocumentDriver}.
 */
public final class LoadedDriver implements AutoCloseable {

  private final java.sql.Driver driver; // null for a driver of documents
  private final DocumentDriver documents; // null for a JDBC driver
  private final URLClassLoader loader;
  private int holds; // under this object's lock

  LoadedDriver(java.sql.Driver driver, URLClassLoader loader) {
    this.driver = driver;
    this.documents = null;
    this.loader = loader;
  }

  LoadedDriver(DocumentDriver documents, URLClassLoader loader) {
    this.driver = null;
    this.documents = documents;
    this.loader = loader;
  }

  /**
   * The JDBC driver.
   *
   * @throws IllegalStateException for a driver of documents
   */
  public java.sql.Driver driver() {
    if (driver == null) {
      throw new IllegalStateException("Not a JDBC driver");
    }
    return driver;
  }

  /** Whether it is a driver of documents, which {@link #open} connects with. */
  public boolean documents() {
    return documents != null;
  }

  /**
   * A client of a server of documents, as {@link DocumentDriver#connect}.
   *
   * @throws IllegalStateException for a JDBC driver
   */
  public DocumentClient open(Target target, char[] password) throws DocumentException {
    if (documents == null) {
      throw new IllegalStateException("Not a driver of documents");
    }
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      return documents.connect(target, password);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** Opens a connection; the properties hold the user and the password, if any. */
  public Connection connect(String url, Properties properties) throws SQLException {
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    // Some drivers look up their own parts through the thread's class loader.
    thread.setContextClassLoader(loader);
    try {
      var connection = driver().connect(url, properties);
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

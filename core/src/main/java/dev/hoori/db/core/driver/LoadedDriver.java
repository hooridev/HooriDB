package dev.hoori.db.core.driver;

import java.io.IOException;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A driver loaded in a class loader of its own, which sees the JDK's platform modules, among them
 * {@code java.sql}, but none of HooriDB's classes. Two versions of one driver load side by side.
 * Connections go to the driver directly and never through {@link java.sql.DriverManager}.
 */
public final class LoadedDriver implements AutoCloseable {

  private final java.sql.Driver driver;
  private final URLClassLoader loader;

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

  /** Closes the class loader; connections already open should be closed first. */
  @Override
  public void close() throws IOException {
    loader.close();
  }
}

package dev.hoori.db.core.connect;

import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.connection.Provider;
import dev.hoori.db.core.driver.LoadedDriver;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Opens, tests and closes connections to the server of a profile. Each call blocks. The driver
 * works on a thread of its own, so that the caller returns as soon as the time limit passes or its
 * thread is interrupted, with {@link Failure#CANCELLED} and the thread still interrupted, even
 * where a driver would go on waiting; a connection that arrives after that is closed.
 */
public final class Connector {

  // Beyond the time limit, the drivers' own timeouts have this long to report first, with their
  // own words, before the wait here gives up.
  private static final Duration GRACE = Duration.ofSeconds(1);

  private Connector() {}

  private interface Step<T> {
    T apply(Connection connection) throws SQLException;
  }

  /**
   * Opens a connection to the profile's server as its user, with the password. The time limit
   * applies to reaching the server and logging in; the connection has none afterwards.
   */
  public static Connection open(
      ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout)
      throws ConnectionException {
    return connect(profile, password, driver, timeout, connection -> {
      connection.setNetworkTimeout(Runnable::run, 0);
      return connection;
    });
  }

  /** Connects to the profile's server, reads what it is, and disconnects. */
  public static Server test(
      ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout)
      throws ConnectionException {
    return connect(profile, password, driver, timeout, connection -> {
      try (connection) {
        var metadata = connection.getMetaData();
        return new Server(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
      }
    });
  }

  /**
   * Closes the connection. A failure to close is not reported: the connection is gone either way.
   * An interrupt of the calling thread waits until it is closed, so that the driver can still say
   * goodbye to the server instead of dropping the socket.
   */
  public static void close(Connection connection) {
    var interrupted = Thread.interrupted();
    try {
      connection.close();
    } catch (SQLException | RuntimeException e) {
      // Nothing is left to do with a connection that cannot even be closed.
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static <T> T connect(
      ConnectionProfile profile,
      char[] password,
      LoadedDriver driver,
      Duration timeout,
      Step<T> step)
      throws ConnectionException {
    var provider = profile.provider();
    String url;
    var properties = new Properties();
    try {
      var parameters = provider.parameters(profile);
      url = parameters.url();
      properties.putAll(parameters.properties());
    } catch (ConnectionProfile.UnfitException e) {
      throw new ConnectionException(e.problems());
    }
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    properties.putAll(timeouts(provider, timeout));
    properties.setProperty("user", profile.user());
    // JDBC takes the password only as a String, which cannot be wiped; it is dropped from the
    // properties once the driver is done with them, and the driver may keep a copy of its own.
    var secret = password.length > 0 ? new String(password) : null;
    if (secret != null) {
      properties.setProperty("password", secret);
    }
    var result = new CompletableFuture<T>();
    var worker = Thread.ofVirtual().name("connect " + provider.displayName()).start(() -> {
      Connection connection = null;
      try {
        connection = driver.connect(url, properties);
        if (result.complete(step.apply(connection))) {
          connection = null;
        }
      } catch (Throwable e) {
        result.completeExceptionally(e);
      } finally {
        properties.remove("password");
        // The caller has given up, or the step failed.
        if (connection != null) {
          close(connection);
        }
      }
    });
    return await(
        result, worker, timeout.plus(GRACE), thrown -> Failures.of(profile, secret, thrown));
  }

  /**
   * The worker's result, or the failure it ended with. An interrupted caller gets {@link
   * Failure#CANCELLED} even where the result is there already, and a connection in it is closed.
   */
  static <T> T await(
      CompletableFuture<T> result,
      Thread worker,
      Duration limit,
      Function<Throwable, ConnectionException> failure)
      throws ConnectionException {
    T value;
    try {
      // get() does not look at the interrupt when the result is there, hence the checks after it.
      value = result.get(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      abandon(result, worker);
      Thread.currentThread().interrupt();
      throw cancelled();
    } catch (TimeoutException e) {
      abandon(result, worker);
      throw new ConnectionException(Failure.TIMED_OUT, "", null);
    } catch (ExecutionException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw cancelled();
      }
      throw failure.apply(e.getCause());
    }
    if (Thread.currentThread().isInterrupted()) {
      if (value instanceof Connection connection) {
        close(connection);
      }
      throw cancelled();
    }
    return value;
  }

  private static ConnectionException cancelled() {
    return new ConnectionException(Failure.CANCELLED, "", null);
  }

  // The worker closes what arrives from now on, and what arrived just before is closed here. The
  // interrupt ends the worker in the driver's socket calls, as it is a virtual thread: the
  // drivers' timeouts bound each read, but not a server that answers slowly without end.
  private static void abandon(CompletableFuture<?> result, Thread worker) {
    if (!result.cancel(false)
        && result.state() == Future.State.SUCCESS
        && result.resultNow() instanceof Connection connection) {
      close(connection);
    }
    worker.interrupt();
  }

  // Each driver's own time limits for reaching the server and for logging in. PostgreSQL's
  // loginTimeout is not one of them: it leaves its own thread behind, waiting on a server that
  // never answers. The socket timeouts would apply for good, so open lifts them once logged in.
  private static Map<String, String> timeouts(Provider provider, Duration timeout) {
    var millis = String.valueOf(Math.max(1, timeout.toMillis()));
    return switch (provider) {
      case POSTGRESQL -> {
        // In whole seconds, and 0 would mean none.
        var seconds = String.valueOf(Math.max(1, (timeout.toMillis() + 999) / 1000));
        yield Map.of("connectTimeout", seconds, "socketTimeout", seconds);
      }
      // Also the time limit for each read while logging in.
      case MARIADB -> Map.of("connectTimeout", millis);
      case MYSQL -> Map.of("connectTimeout", millis, "socketTimeout", millis);
    };
  }
}

package dev.hoori.db.app;

import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.driver.LoadedDriver;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One connection to a server, and the one thread that uses it: the calls asked for run there one
 * after another, in the order asked, so that the connection serves one call at a time and no other
 * connection waits for it. Its first call opens it. Asked for on the app's thread; only the lane's
 * thread touches the connection.
 */
final class Lane {

  /** A call on the open connection. */
  interface Call<T> {
    T call(Servers.Link link) throws Exception;
  }

  private final ExecutorService thread =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("connection").factory());
  private volatile Servers.Link link; // written on the lane's thread only
  private Servers.NotConnected failed; // why it could not be opened; on the lane's thread only

  /**
   * Opens the connection as the lane's first call, with a copy of the password, which it wipes.
   */
  Future<Servers.Link> open(
      Servers servers, ConnectionProfile profile, char[] password, LoadedDriver driver) {
    char[] copy = password.clone();
    return thread.submit(() -> {
      try {
        link = servers.open(profile, copy, driver);
        return link;
      } catch (Servers.NotConnected e) {
        failed = e;
        throw e;
      } finally {
        Arrays.fill(copy, '\0');
      }
    });
  }

  /** Runs the call once the calls asked for before it have ended. */
  <T> Future<T> submit(Call<T> call) {
    return thread.submit(() -> {
      if (link == null) {
        throw failed != null ? failed : new IllegalStateException("Not open");
      }
      return call.call(link);
    });
  }

  /**
   * Waits for the call, off the app's thread. An interrupt, as when the work that waits is
   * cancelled, interrupts the lane's thread in the call, which core turns into a cancel on the
   * server.
   */
  static <T> T await(Future<T> call) throws Exception {
    try {
      return call.get();
    } catch (InterruptedException e) {
      call.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception cause ? cause : e;
    } catch (CancellationException e) {
      throw new InterruptedException("The lane was closed");
    }
  }

  /**
   * Closes the connection without waiting for it: the call that runs is interrupted, which core
   * turns into a cancel on the server, those asked for after it are dropped, and the connection is
   * closed on a thread of its own once the call has ended, which takes at most {@link
   * dev.hoori.db.core.catalog.Catalog#GRACE} after a server that no longer answers.
   */
  void close() {
    for (var dropped : thread.shutdownNow()) {
      ((Future<?>) dropped).cancel(false);
    }
    Thread.ofVirtual().name("close a connection").start(() -> {
      try {
        // As long as it takes: the thread keeps nothing alive, as a virtual thread.
        thread.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
      } catch (InterruptedException e) {
        return;
      }
      if (link != null) {
        link.close();
      }
    });
  }
}

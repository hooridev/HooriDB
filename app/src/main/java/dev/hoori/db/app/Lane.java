package dev.hoori.db.app;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.connect.ConnectionException;
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
 * thread touches the connection. A connection that could not be opened, or that a call reports as
 * gone, is not used again: every call after that fails as it did.
 */
final class Lane {

  /** A call on the open connection. */
  interface Call<T> {
    T call(Servers.Link link) throws Exception;
  }

  private final ExecutorService thread =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("connection").factory());
  private volatile Servers.Link link; // written on the lane's thread only
  private Exception failed; // why it cannot be used; on the lane's thread only
  private volatile boolean broken; // whether it cannot be used, for the app's thread
  private Future<?> opening; // the first call, and the copy of the password it is given
  private char[] copy;

  /**
   * Opens the connection as the lane's first call, with a copy of the password, which is wiped
   * once the call ends, or by {@link #close} if it never starts.
   */
  Future<Servers.Link> open(
      Servers servers, ConnectionProfile profile, char[] password, LoadedDriver driver) {
    char[] given = password.clone();
    copy = given;
    Future<Servers.Link> call = thread.submit(() -> {
      try {
        link = servers.open(profile, given, driver);
        return link;
      } catch (ConnectionException e) {
        fail(e);
        throw e;
      } finally {
        Arrays.fill(given, '\0');
      }
    });
    opening = call;
    return call;
  }

  /** Runs the call once the calls asked for before it have ended. */
  <T> Future<T> submit(Call<T> call) {
    return thread.submit(() -> {
      if (failed != null) {
        throw failed;
      }
      if (link == null) {
        throw new IllegalStateException("Not open");
      }
      try {
        return call.call(link);
      } catch (CatalogException e) {
        if (e.failure() == CatalogException.Failure.CONNECTION_LOST
            || e.failure() == CatalogException.Failure.ABORTED) {
          fail(e);
        }
        throw e;
      }
    });
  }

  private void fail(Exception e) {
    failed = e;
    broken = true;
  }

  /** Whether the connection could not be opened or is gone, so that a new lane takes its place. */
  boolean broken() {
    return broken;
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
      if (dropped == opening) {
        Arrays.fill(copy, '\0'); // the opening never started
      }
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

package dev.hoori.db.app;

import dev.hoori.db.core.secret.SecretStore;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The system keyring's one worker: finding it, and every read and change of it, run one after
 * another on one thread, in the order they were asked for, each until the native call returns. A
 * caller that gives up stops waiting, by its interrupt; the call it made still runs, and what is
 * asked after it runs after it, so that the last change asked for is the last made.
 */
final class KeyringWorker {

  // shortcut: a daemon thread, so that a native call that never returns does not keep HooriDB
  // from closing; a change under way as it closes may be cut short, which the dialog that gave
  // it up has said. Make it wait at exit if a keyring is found that ends its calls in time.
  private final ExecutorService thread = Executors.newSingleThreadExecutor(
      Thread.ofPlatform().daemon().name("system keyring").factory());
  // Calls whose callers gave up and that have not returned yet.
  private final AtomicInteger abandoned = new AtomicInteger();

  /** Runs the call in its turn and waits for it; an interrupt stops the waiting, not the call. */
  <T> T call(Callable<T> call) throws Exception {
    return await(CompletableFuture.supplyAsync(
        () -> {
          try {
            return call.call();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        thread));
  }

  private <T> T await(CompletableFuture<T> done) throws Exception {
    try {
      return done.get();
    } catch (InterruptedException e) {
      abandoned.incrementAndGet();
      done.whenComplete((value, error) -> {
        if (value instanceof Optional<?> o && o.orElse(null) instanceof char[] secret) {
          Arrays.fill(secret, '\0'); // read for nobody
        }
        abandoned.decrementAndGet();
      });
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("gave up waiting for the system keyring");
    } catch (ExecutionException e) {
      throw e.getCause() instanceof CompletionException c && c.getCause() instanceof Exception x
          ? x
          : e.getCause() instanceof Exception x ? x : e;
    }
  }

  /** Whether a call that its caller gave up still runs, which what is asked now waits behind. */
  boolean behind() {
    return abandoned.get() > 0;
  }

  /** The store, with each of its calls made in its turn on this worker. */
  SecretStore serial(SecretStore store) {
    return new SecretStore() {
      @Override
      public void store(UUID key, char[] secret) throws IOException {
        // The call's own copy: the caller wipes its array as it stops waiting.
        var copy = secret.clone();
        run(() -> {
          try {
            store.store(key, copy);
          } finally {
            Arrays.fill(copy, '\0');
          }
          return null;
        });
      }

      @Override
      public Optional<char[]> read(UUID key) throws IOException {
        return run(() -> store.read(key));
      }

      @Override
      public void delete(UUID key) throws IOException {
        run(() -> {
          store.delete(key);
          return null;
        });
      }
    };
  }

  private <T> T run(Callable<T> call) throws IOException {
    try {
      return call(call);
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}

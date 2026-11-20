package dev.hoori.db.core.connect;

import dev.hoori.db.core.connect.ConnectionException.Failure;
import dev.hoori.db.core.connection.ConnectionProfile;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentClient;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.Target;
import dev.hoori.db.core.driver.LoadedDriver;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Connects to a server of documents through its driver, as {@link Connector} does to a server of
 * SQL: within a time limit, cancelled by an interrupt, and with failures that never hold the
 * password.
 */
public final class DocumentConnector {

  // Beyond the time limit, the driver's own timeouts have this long to report first.
  private static final Duration GRACE = Duration.ofSeconds(1);

  /** The server's code of a login that failed. */
  static final int AUTHENTICATION_FAILED = 18;

  private DocumentConnector() {}

  /**
   * An open client, with what was read of it while it opened.
   *
   * @param database the database the profile names, or null where it names none
   */
  public record Opened(DocumentClient client, Server server, String database) {}

  /**
   * Opens a client of the profile's server as its user, with the password, reaches the server and
   * logs in, and reads which version it is, all within the time limit.
   */
  public static Opened open(
      ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout)
      throws ConnectionException {
    return connect(profile, password, driver, timeout, false);
  }

  /** Connects to the profile's server, reads what it is, and disconnects. */
  public static Server test(
      ConnectionProfile profile, char[] password, LoadedDriver driver, Duration timeout)
      throws ConnectionException {
    return connect(profile, password, driver, timeout, true).server();
  }

  /** The target of the profile, which must have no problems. */
  public static Target target(ConnectionProfile profile, Duration timeout) {
    var host = profile.host();
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    return new Target(
        host,
        profile.port(),
        profile.user(),
        profile.provider().documents().authDatabase(),
        profile.tls(),
        timeout);
  }

  private static Opened connect(
      ConnectionProfile profile,
      char[] password,
      LoadedDriver driver,
      Duration timeout,
      boolean close)
      throws ConnectionException {
    var problems = profile.problems();
    if (!problems.isEmpty()) {
      throw new ConnectionException(problems);
    }
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
    var target = target(profile, timeout);
    var secret = password.clone();
    var database = profile.database().isEmpty() ? null : profile.database();
    var result = new CompletableFuture<Opened>();
    var worker = Thread.ofVirtual()
        .name("connect " + profile.provider().displayName())
        .start(() -> {
          DocumentClient client = null;
          try {
            client = driver.open(target, secret);
            client.command("admin", Document.of("ping", 1));
            var build = client.command("admin", Document.of("buildInfo", 1));
            var version = build.get("version") instanceof String v ? v : "";
            var opened = new Opened(client, new Server("MongoDB", version), database);
            if (close) {
              client.close();
              client = null;
              result.complete(opened);
            } else if (result.complete(opened)) {
              client = null;
            }
          } catch (Throwable e) {
            result.completeExceptionally(e);
          } finally {
            java.util.Arrays.fill(secret, '\0');
            if (client != null) {
              client.close();
            }
          }
        });
    Opened opened;
    try {
      opened = result.get(timeout.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
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
      throw failure(e.getCause(), password);
    }
    if (Thread.currentThread().isInterrupted()) {
      if (!close) {
        opened.client().close();
      }
      throw cancelled();
    }
    return opened;
  }

  // What arrives from now on is closed by the worker, and what arrived just before here.
  private static void abandon(CompletableFuture<Opened> result, Thread worker) {
    if (!result.cancel(false) && result.state() == Future.State.SUCCESS) {
      result.resultNow().client().close();
    }
    worker.interrupt();
  }

  private static ConnectionException cancelled() {
    return new ConnectionException(Failure.CANCELLED, "", null);
  }

  // Without making a String of the password, which could not be wiped.
  private static boolean contains(String text, char[] password) {
    if (password.length == 0) {
      return false;
    }
    for (int i = 0; i + password.length <= text.length(); i++) {
      int j = 0;
      while (j < password.length && text.charAt(i + j) == password[j]) {
        j++;
      }
      if (j == password.length) {
        return true;
      }
    }
    return false;
  }

  /** Why the driver could not connect; its words are dropped where they hold the password. */
  static ConnectionException failure(Throwable thrown, char[] password) {
    if (!(thrown instanceof DocumentException e)) {
      return new ConnectionException(Failure.OTHER, String.valueOf(thrown.getMessage()), thrown);
    }
    var failure = switch (e.kind()) {
      case UNREACHABLE -> Failure.UNREACHABLE;
      case TIMED_OUT -> Failure.TIMED_OUT;
      case LOGIN_REFUSED -> Failure.LOGIN_REFUSED;
      case TLS_UNAVAILABLE -> Failure.TLS_UNAVAILABLE;
      case CERTIFICATE_REJECTED -> Failure.CERTIFICATE_REJECTED;
      case CANCELLED -> Failure.CANCELLED;
      case SERVER -> e.code() == AUTHENTICATION_FAILED ? Failure.LOGIN_REFUSED : Failure.OTHER;
      case CONNECTION_LOST, READ_ONLY, OTHER -> Failure.OTHER;
    };
    var detail = e.detail();
    if (contains(detail, password)) {
      return new ConnectionException(failure, ConnectionException.WITHHELD, null);
    }
    return new ConnectionException(failure, detail, null);
  }
}

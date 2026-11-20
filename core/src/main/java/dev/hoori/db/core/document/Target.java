package dev.hoori.db.core.document;

import dev.hoori.db.core.connection.Tls;
import java.time.Duration;
import java.util.Objects;

/**
 * Where a client of a server of documents connects, and as whom, without the password.
 *
 * @param host one host name or IP address
 * @param user the user, or empty to connect without logging in
 * @param authDatabase the database the user is defined in
 * @param timeout how long reaching the server and logging in may take
 */
public record Target(
    String host, int port, String user, String authDatabase, Tls tls, Duration timeout) {

  public Target {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(authDatabase, "authDatabase");
    Objects.requireNonNull(tls, "tls");
    Objects.requireNonNull(timeout, "timeout");
  }
}

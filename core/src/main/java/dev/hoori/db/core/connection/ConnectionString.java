package dev.hoori.db.core.connection;

import java.util.List;
import java.util.Objects;

/**
 * The parts of a connection string, such as MongoDB's {@code mongodb://} or {@code
 * mongodb+srv://}, ready to fill a connection profile and its password field.
 *
 * @param host the first host name or IP address, or with {@code srv} the name of the DNS SRV record
 * @param port the server's port, or the provider's default where none was given or with {@code srv}
 * @param srv whether the host names a DNS SRV record
 * @param user the decoded user name, or empty where none was given
 * @param password the decoded password, or empty where none was given
 * @param database the decoded database name, or empty where none was given
 * @param authDatabase the database the user is defined in, or empty for the provider's default
 * @param tls the TLS mode the string asks for
 * @param unused names (never values) of options the dialog has no field for
 * @param otherHosts hosts after the first, as written, which HooriDB does not use
 */
public record ConnectionString(
    String host,
    int port,
    boolean srv,
    String user,
    String password,
    String database,
    String authDatabase,
    Tls tls,
    List<String> unused,
    List<String> otherHosts) {

  public ConnectionString {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(authDatabase, "authDatabase");
    Objects.requireNonNull(tls, "tls");
    unused = List.copyOf(unused);
    otherHosts = List.copyOf(otherHosts);
  }

  /** Never shows the user or password. */
  @Override
  public String toString() {
    return "ConnectionString[host="
        + host
        + ", port="
        + port
        + ", srv="
        + srv
        + ", user="
        + (user.isEmpty() ? "" : "***")
        + ", password="
        + (password.isEmpty() ? "" : "***")
        + ", database="
        + database
        + ", authDatabase="
        + authDatabase
        + ", tls="
        + tls
        + ", unused="
        + unused
        + ", otherHosts="
        + otherHosts
        + "]";
  }
}

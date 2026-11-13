package dev.hoori.db.core.connection;

import java.util.Objects;
import java.util.UUID;

/**
 * How to reach one database. The strings may be empty, as while a form edits a new profile. The
 * password is not part of it: it is kept in the system keyring under the id.
 */
public record ConnectionProfile(
    UUID id,
    String name,
    Provider provider,
    String host,
    int port,
    String database,
    String user,
    boolean tls) {

  public ConnectionProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(user, "user");
  }

  /** A new, empty profile of the provider, on its default port and with TLS on. */
  public static ConnectionProfile of(Provider provider) {
    return new ConnectionProfile(
        UUID.randomUUID(), "", provider, "", provider.defaultPort(), "", "", true);
  }

  public ConnectionProfile withName(String name) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withProvider(Provider provider) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withHost(String host) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withPort(int port) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withDatabase(String database) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withUser(String user) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }

  public ConnectionProfile withTls(boolean tls) {
    return new ConnectionProfile(id, name, provider, host, port, database, user, tls);
  }
}

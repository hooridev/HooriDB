package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connect.ConnectionException;
import dev.hoori.db.core.connection.Tls;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** How a provider's driver is told where and how to connect, and why it could not. */
public interface ConnectDialect {

  /** The scheme of the driver's URL, after {@code jdbc:}. */
  String scheme();

  /** An IPv6 address, without its brackets, as the driver reads it between them. */
  String ipv6(String address);

  /** The driver's properties for the level of TLS. */
  Map<String, String> tls(Tls tls);

  /** The properties the driver always gets. */
  Map<String, String> properties();

  /** The property that names the database; empty where the name goes in the URL's path. */
  Optional<String> databaseProperty();

  /** The driver's own time limits for reaching the server and for logging in. */
  Map<String, String> timeouts(Duration timeout);

  /**
   * Why the server refused, from what the driver threw that is none of Java's network or TLS
   * failures nor a refused login; {@link ConnectionException.Failure#OTHER} where it does not say.
   *
   * @param tls whether the profile asks for TLS
   */
  ConnectionException.Failure refusal(SQLException e, String state, String message, boolean tls);

  /** Whether the server opens the database named after the user where none is given. */
  boolean defaultDatabaseIsUser();

  /**
   * The statement that makes every later transaction of the session read-only at the server, run
   * as a read-only connection opens.
   */
  String readOnly();

  /**
   * What turns a read-only session back to read-write, in lower case with one space between words:
   * the names of its settings, and the words of a statement that does so, such as {@code read
   * write}. A read-only connection does not send a statement that holds one, as {@link
   * dev.hoori.db.core.sql.ReadWrite} reads it.
   */
  Set<String> readWrite();
}

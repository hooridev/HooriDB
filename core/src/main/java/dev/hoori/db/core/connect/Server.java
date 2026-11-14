package dev.hoori.db.core.connect;

import dev.hoori.db.core.connection.ServerVersion;

/** The server a connection reached: its product and version, as its driver reports them. */
public record Server(String product, String version) {

  /** The version as numbers, or {@link ServerVersion#UNKNOWN} if it cannot be read. */
  public ServerVersion parsed() {
    return ServerVersion.parse(version);
  }
}

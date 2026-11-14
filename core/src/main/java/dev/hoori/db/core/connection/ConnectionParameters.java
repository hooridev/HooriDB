package dev.hoori.db.core.connection;

import java.util.Map;

/** What a JDBC driver gets to connect: the URL and the properties, without user and password. */
public record ConnectionParameters(String url, Map<String, String> properties) {

  public ConnectionParameters {
    properties = Map.copyOf(properties);
  }
}

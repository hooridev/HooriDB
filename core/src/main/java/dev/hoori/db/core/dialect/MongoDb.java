package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.driver.Driver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MongoDB, with its Java driver, through core's bridge to it. */
public final class MongoDb implements DocumentDialect {

  public static final MongoDb INSTANCE = new MongoDb();

  // The synchronous driver needs its core and BSON beside it, and the record codec, which the core
  // looks for at run time, from version 4.6 on. The bridge is built and tested against 5.15.0,
  // whose API older versions lack in part, as SocketSettings' connectTimeout of a long.
  private final Driver driver = new Driver(
      "org.mongodb",
      "mongodb-driver-sync",
      "dev.hoori.db.core.document.mongo.MongoBridge",
      List.of(
          new Driver.Part("mongodb-driver-core", false),
          new Driver.Part("bson", false),
          new Driver.Part("bson-record-codec", true)),
      "5.15.0");

  private MongoDb() {}

  @Override
  public String displayName() {
    return "MongoDB";
  }

  @Override
  public String storedName() {
    return "mongodb";
  }

  @Override
  public int defaultPort() {
    return 27017;
  }

  /** No schemas, and one connection reads every database. */
  @Override
  public Map<Capability, Since> capabilities() {
    return Map.of();
  }

  @Override
  public Driver driver() {
    return driver;
  }

  @Override
  public String driverName() {
    return "MongoDB Java Driver";
  }

  @Override
  public Set<String> systemDatabases() {
    return Set.of("admin", "config", "local");
  }

  // Where the server's own setup, Atlas and most deployments define users.
  @Override
  public String authDatabase() {
    return "admin";
  }

  @Override
  public String defaultDatabase() {
    return "test";
  }

  // Fewer than 64 bytes, and none of the characters that a server refuses on every system; what
  // only a server on Windows refuses it says itself, at the first command on the database.
  @Override
  public boolean isDatabaseName(String name) {
    return name.isEmpty()
        || name.getBytes(StandardCharsets.UTF_8).length < 64
            && name.chars().noneMatch(c -> "/\\. \"$\0".indexOf(c) >= 0);
  }
}

package dev.hoori.db.core.dialect;

import dev.hoori.db.core.connection.Capability;
import dev.hoori.db.core.connection.ConnectionString;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.ExtendedJson;
import dev.hoori.db.core.driver.Driver;
import dev.hoori.db.core.monitor.Counter;
import dev.hoori.db.core.monitor.ServerSession;
import dev.hoori.db.core.monitor.ServerVariable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MongoDB, with its Java driver, through core's bridge to it. */
public final class MongoDb implements DocumentDialect {

  public static final MongoDb INSTANCE = new MongoDb();

  /** What a secret becomes where a command is shown. */
  public static final String MASK = "***";

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

  private final DocumentMonitorDialect monitor = new Monitor();

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
  public Optional<ConnectionString> connectionString(String text) {
    var parsed = MongoConnectionString.parse(text, defaultPort());
    parsed.ifPresent(p -> {
      if (!isDatabaseName(p.database()) || !isDatabaseName(p.authDatabase())) {
        throw new IllegalArgumentException("Not a name of a database that MongoDB takes.");
      }
    });
    return parsed;
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

  /** The password of {@code createUser} and {@code updateUser}. */
  @Override
  public Set<String> secretFields() {
    return Set.of("pwd");
  }

  /** As {@code db.runCommand({…})}, which the console runs as it is. */
  @Override
  public String shown(Document command) {
    return "db.runCommand(" + command.masked(secretFields(), MASK) + ")";
  }

  @Override
  public DocumentMonitorDialect monitor() {
    return monitor;
  }

  /**
   * {@code $currentOp}, of the connections of clients: each with what it runs, or idle. The server
   * has no command that ends another client's connection; {@code killOp} stops what it runs.
   */
  static final class Monitor implements DocumentMonitorDialect {

    @Override
    public String database() {
      return "admin";
    }

    @Override
    public Document sessions(boolean allUsers, String mark) {
      return Document.of(
          "aggregate",
          1,
          "pipeline",
          Document.array(Document.of(
              "$currentOp",
              // Through mongos, the router's own connections, which have their clients.
              Document.of(
                  "allUsers",
                  allUsers,
                  "idleConnections",
                  true,
                  "idleSessions",
                  false,
                  "localOps",
                  true))),
          "cursor",
          Document.EMPTY,
          "comment",
          mark);
    }

    // The server's own threads have no client, and are left out.
    @Override
    public List<ServerSession> sessions(List<Document> entries, String mark) {
      Object own = null;
      for (var entry : entries) {
        if (entry.get("command") instanceof Document c && mark.equals(c.get("comment"))) {
          own = entry.get("connectionId");
        }
      }
      var sessions = new ArrayList<ServerSession>();
      for (var entry : entries) {
        var connection = entry.get("connectionId");
        if (connection instanceof Number && entry.get("client") instanceof String) {
          sessions.add(session(entry, connection.equals(own)));
        }
      }
      sessions.sort(Comparator.comparingLong(s -> Long.parseLong(s.id())));
      return sessions;
    }

    // An operation's id is a number, but through mongos, which names its shard before it.
    @Override
    public Document cancel(String operation) {
      Object op = operation;
      try {
        op = Integer.parseInt(operation);
      } catch (NumberFormatException e) {
        // A shard's name and the operation's number on it.
      }
      return Document.of("killOp", 1, "op", op);
    }

    @Override
    public Document variables() {
      return Document.of("getParameter", "*");
    }

    // Every field of the reply but those of the reply itself.
    @Override
    public List<ServerVariable> variables(Document reply) {
      var variables = new ArrayList<ServerVariable>();
      for (var entry : reply.entries()) {
        var name = entry.name();
        if (!name.equals("ok") && !name.equals("operationTime") && !name.startsWith("$")) {
          variables.add(new ServerVariable(name, value(entry.value()), ""));
        }
      }
      variables.sort(Comparator.comparing(ServerVariable::name));
      return variables;
    }

    @Override
    public Document status() {
      return Document.of("serverStatus", 1);
    }

    // The sections that say how busy the server is, by dotted names.
    private static final List<String> SECTIONS =
        List.of("uptime", "connections", "opcounters", "network", "mem");

    @Override
    public List<Counter> status(Document reply) {
      var counters = new ArrayList<Counter>();
      for (var section : SECTIONS) {
        flatten(section, reply.get(section), counters);
      }
      return counters;
    }

    private static void flatten(String name, Object value, List<Counter> counters) {
      if (value instanceof Document d) {
        for (var entry : d.entries()) {
          flatten(name + "." + entry.name(), entry.value(), counters);
        }
      } else if (value != null) {
        counters.add(new Counter(name, value(value)));
      }
    }

    // A text as it is, any other value as Relaxed Extended JSON.
    private static String value(Object value) {
      return value instanceof String s ? s : ExtendedJson.of(value);
    }

    private static ServerSession session(Document entry, boolean own) {
      boolean active = Boolean.TRUE.equals(entry.get("active"));
      var opid = active && entry.get("opid") != null ? String.valueOf(entry.get("opid")) : "";
      var command = entry.get("command") instanceof Document c ? c : null;
      return new ServerSession(
          String.valueOf(((Number) entry.get("connectionId")).longValue()),
          opid,
          "",
          own,
          false,
          user(entry),
          ServerSession.client(text(entry.get("appName")), text(entry.get("client"))),
          database(entry, command),
          active ? "active" : "idle",
          command == null ? "" : ExtendedJson.of(shown(command)),
          active && entry.get("microsecs_running") instanceof Number n
              ? Duration.ofNanos(n.longValue() * 1000)
              : null,
          waiting(entry));
    }

    private static String user(Document entry) {
      if (entry.get("effectiveUsers") instanceof List<?> users
          && !users.isEmpty()
          && users.getFirst() instanceof Document user) {
        return text(user.get("user"));
      }
      return "";
    }

    // The database of its namespace, else the one its command names.
    private static String database(Document entry, Document command) {
      var ns = text(entry.get("ns"));
      int dot = ns.indexOf('.');
      if (!ns.isEmpty()) {
        return dot < 0 ? ns : ns.substring(0, dot);
      }
      return command == null ? "" : text(command.get("$db"));
    }

    // The command as the user sent it: without the session and the fields the driver adds.
    private static Document shown(Document command) {
      var entries = new ArrayList<Document.Entry>();
      for (var entry : command.entries()) {
        if (!entry.name().startsWith("$") && !entry.name().equals("lsid")) {
          entries.add(entry);
        }
      }
      return new Document(entries);
    }

    private static String waiting(Document entry) {
      if (Boolean.TRUE.equals(entry.get("waitingForLock"))) {
        return "lock";
      }
      if (Boolean.TRUE.equals(entry.get("waitingForFlowControl"))) {
        return "flow control";
      }
      return "";
    }

    private static String text(Object value) {
      return value instanceof String s ? s : "";
    }
  }
}

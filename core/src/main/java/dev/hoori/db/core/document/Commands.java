package dev.hoori.db.core.document;

import java.util.List;
import java.util.Set;

/**
 * What the server's commands do, as far as a connection that is read-only must know: which of them
 * only read. It goes by the command's name, the first field of its document, as the server does,
 * and lets through only those known to change nothing, so that a command it does not know is
 * refused, not sent.
 */
public final class Commands {

  /** The commands that read and change nothing, whatever else they are given. */
  static final Set<String> READING = Set.of(
      "find",
      "count",
      "distinct",
      "getMore",
      "killCursors",
      "listCollections",
      "listDatabases",
      "listIndexes",
      "listSearchIndexes",
      "dbStats",
      "collStats",
      "dataSize",
      "buildInfo",
      "buildinfo",
      "hello",
      "isMaster",
      "ismaster",
      "ping",
      "serverStatus",
      "connectionStatus",
      "hostInfo",
      "getParameter",
      "getCmdLineOpts",
      "listCommands",
      "whatsmyuri",
      "usersInfo",
      "rolesInfo");

  /** The stages of a pipeline that write what it gives into a collection. */
  static final Set<String> WRITING_STAGES = Set.of("$out", "$merge");

  private Commands() {}

  /**
   * Whether the command only reads: one of {@link #READING}, an aggregate whose pipeline writes
   * nowhere, or the explain of a command that reads.
   */
  public static boolean reads(Document command) {
    var name = command.first();
    if (READING.contains(name)) {
      return true;
    }
    return switch (name) {
      case "aggregate" -> command.get("pipeline") instanceof List<?> stages && reads(stages);
      case "explain" -> command.get("explain") instanceof Document explained && reads(explained);
      default -> false;
    };
  }

  // A stage that writes may stand only at the end of the outer pipeline, but none is let through
  // anywhere, also inside $facet or $lookup, where the server would refuse it anyway.
  private static boolean reads(List<?> stages) {
    for (var stage : stages) {
      if (!(stage instanceof Document)) {
        return false; // the server refuses it; it is not sent
      }
    }
    return writesNowhere(stages);
  }

  private static boolean writesNowhere(Object value) {
    return switch (value) {
      case Document d ->
        d.entries().stream()
            .allMatch(e -> !WRITING_STAGES.contains(e.name()) && writesNowhere(e.value()));
      case List<?> list -> list.stream().allMatch(item -> item == null || writesNowhere(item));
      case null, default -> true;
    };
  }
}

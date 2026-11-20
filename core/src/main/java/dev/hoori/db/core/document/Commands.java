package dev.hoori.db.core.document;

import dev.hoori.db.core.sql.EveryRow;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
   * The change of every document that the command asks for: an update of many, or a delete of
   * all, with an empty filter, as of its collection; empty for any other.
   */
  public static Optional<EveryRow> every(Document command) {
    var collection = command.get(command.first()) instanceof String c ? c : "";
    return switch (command.first()) {
      case "update" -> {
        for (var item : list(command.get("updates"))) {
          if (item instanceof Document u
              && Boolean.TRUE.equals(u.get("multi"))
              && empty(u.get("q"))) {
            yield Optional.of(new EveryRow("UPDATE", collection, true));
          }
        }
        yield Optional.empty();
      }
      case "delete" -> {
        for (var item : list(command.get("deletes"))) {
          if (item instanceof Document d
              && d.get("limit") instanceof Number n
              && n.intValue() == 0
              && empty(d.get("q"))) {
            yield Optional.of(new EveryRow("DELETE", collection, true));
          }
        }
        yield Optional.empty();
      }
      default -> Optional.empty();
    };
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static boolean empty(Object filter) {
    return filter == null || filter instanceof Document d && d.isEmpty();
  }

  /**
   * Whether the command only reads: one of {@link #READING}, an aggregate whose pipeline writes
   * nowhere, or the explain of a command that reads. A command with a name twice in a document,
   * at any depth, is not one: what is checked here must be what the server takes.
   */
  public static boolean reads(Document command) {
    if (!once(command)) {
      return false;
    }
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

  // Whether each document in the value names each of its fields once.
  private static boolean once(Object value) {
    return switch (value) {
      case Document d -> {
        var names = new HashSet<String>();
        for (var entry : d.entries()) {
          if (!names.add(entry.name()) || !once(entry.value())) {
            yield false;
          }
        }
        yield true;
      }
      case List<?> list -> list.stream().allMatch(Commands::once);
      case null, default -> true;
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

  // What $literal holds is a value, never a stage, whatever its names.
  private static boolean writesNowhere(Object value) {
    return switch (value) {
      case Document d ->
        d.entries().stream()
            .allMatch(e -> e.name().equals("$literal")
                || !WRITING_STAGES.contains(e.name()) && writesNowhere(e.value()));
      case List<?> list -> list.stream().allMatch(item -> item == null || writesNowhere(item));
      case null, default -> true;
    };
  }
}

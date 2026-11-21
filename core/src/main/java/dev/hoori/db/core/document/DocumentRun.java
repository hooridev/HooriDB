package dev.hoori.db.core.document;

import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.execution.Abandoned;
import dev.hoori.db.core.execution.Failure;
import dev.hoori.db.core.execution.Message;
import dev.hoori.db.core.execution.Portion;
import dev.hoori.db.core.execution.Result;
import dev.hoori.db.core.execution.Transaction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A statement of the MongoDB shell run on a session, as {@link
 * dev.hoori.db.core.execution.Execution} runs one of SQL: nothing is sent before the first {@link
 * #next}, which sends its command and gives the first documents of its reply; each call after
 * that reads more of its cursor, until it is over. A statement the shell's syntax does not allow
 * fails, as does one that writes on a read-only session, without anything sent. Every command
 * commits by itself: there is never a transaction.
 */
public final class DocumentRun {

  /** The server's codes of a command that is wrong as it is written. */
  static final Set<Integer> INVALID = Set.of(2, 9, 14, 26, 59, 72, 15974, 16410, 17287, 40324);

  /** The server's codes of a document that a unique index or a validator refused. */
  static final Set<Integer> CONSTRAINT = Set.of(11000, 11001, 121);

  /** The fields of a reply that tell about the session, not about what was asked. */
  static final Set<String> SESSION_FIELDS = Set.of("ok", "$clusterTime", "operationTime");

  private final DocumentSession session;
  private final String database;
  private final Script.Parsed parsed;
  private final String statement;
  private final Consumer<String> use;
  private DocumentSession.Cursor cursor;
  private List<Field> fields;
  private long read; // documents given so far of the cursor
  private boolean started;
  private boolean over;
  private boolean one; // findOne: a document at most

  /**
   * @param database the console's database, which a statement runs on unless it names another
   * @param parsed the statement, as it was read before it runs
   * @param use told the database that {@code use} switches to
   */
  public DocumentRun(
      DocumentSession session, String database, Script.Parsed parsed, Consumer<String> use) {
    this.session = session;
    this.database = database;
    this.parsed = parsed;
    this.statement = parsed.statement();
    this.use = use;
  }

  /** Its results up to the given number of documents, then as many more. */
  public Portion next(int count) {
    long start = System.nanoTime();
    var results = new ArrayList<Result>();
    var messages = new ArrayList<Message>();
    if (!over) {
      try {
        if (!started) {
          started = true;
          first(count, results, messages);
        } else if (cursor != null) {
          more(count, results);
        }
      } catch (SyntaxException e) {
        over = true;
        results.add(failure(
            Failure.Kind.INVALID,
            e.getMessage(),
            0,
            statement.codePointCount(0, Math.min(e.offset(), statement.length())) + 1));
      } catch (DocumentException e) {
        // A cancel ends the call, not the server's cursor, which is freed here within a bound.
        over = true;
        results.add(failure(e));
        var left = close();
        left.failure().ifPresent(results::add);
      }
    }
    return new Portion(
        results,
        messages,
        !over,
        Duration.ofNanos(System.nanoTime() - start),
        Transaction.NONE,
        true);
  }

  private void first(int count, List<Result> results, List<Message> messages)
      throws SyntaxException, DocumentException {
    if (parsed.error() != null) {
      throw parsed.error();
    }
    var request = parsed.request();
    switch (request.shape()) {
      case USE -> {
        over = true;
        use.accept(request.use());
        messages.add(new Message("Switched to the database " + request.use(), "", 0));
      }
      case NAME -> {
        over = true;
        results.add(rows(List.of(Document.of("name", on(request))), 0));
      }
      default -> send(request, count, results, messages);
    }
  }

  // The database it runs on: the one it names, or the console's.
  private String on(Request request) {
    return request.database() != null ? request.database() : database;
  }

  private void send(Request request, int count, List<Result> results, List<Message> messages)
      throws DocumentException {
    if (session.readOnly() && !request.reads()) {
      throw DocumentException.of(
          DocumentException.Kind.READ_ONLY,
          "The connection is read-only: " + Script.method(statement) + " may change data and was"
              + " not sent.");
    }
    var on = on(request);
    var reply = session.command(on, request.command());
    switch (request.shape()) {
      case CURSOR, ONE -> {
        one = request.shape() == Request.Shape.ONE;
        cursor = session.cursor(on, reply);
        more(count, results);
      }
      case COUNT -> {
        over = true;
        results.add(single("count", counted(reply)));
      }
      case ESTIMATE -> {
        over = true;
        results.add(single("count", number(reply.get("n"))));
      }
      case VALUES -> {
        over = true;
        results.add(rows(values(reply), 0));
      }
      case DATABASES -> {
        over = true;
        results.add(rows(documents(reply.get("databases")), 0));
      }
      case WRITE -> {
        over = true;
        written(reply, results, messages);
      }
      case REPLY -> {
        over = true;
        replied(reply, results);
      }
      case USE, NAME -> throw new IllegalStateException("It sends nothing");
    }
  }

  // The n of the first document of the cursor's first batch, as the stage $group gives it.
  private static long counted(Document reply) {
    var documents = documents(((Document) or(reply.get("cursor"))).get("firstBatch"));
    return documents.isEmpty() ? 0 : number(documents.getFirst().get("n"));
  }

  // The values of a distinct, each as a document of its own.
  private static List<Document> values(Document reply) {
    var documents = new ArrayList<Document>();
    if (reply.get("values") instanceof List<?> values) {
      for (var value : values) {
        documents.add(Document.of("value", value));
      }
    }
    return documents;
  }

  // The reply of a command, without the fields about the session.
  private void replied(Document reply, List<Result> results) {
    var entries =
        reply.entries().stream().filter(e -> !SESSION_FIELDS.contains(e.name())).toList();
    results.add(rows(List.of(new Document(entries)), 0));
    // A write sent as a command, which may have written some of its documents.
    failures(reply, "The write failed", results);
  }

  private void more(int count, List<Result> results) throws DocumentException {
    var documents = cursor.next(one ? 1 : count);
    if (one) {
      cursor.close();
    }
    if (!cursor.more()) {
      over = true;
      cursor = null;
    }
    results.add(rows(documents, read));
    read += documents.size();
  }

  // What a write changed, or why it failed for some of its documents.
  private void written(Document reply, List<Result> results, List<Message> messages) {
    long n = number(reply.get("n"));
    if (reply.has("nModified")) {
      long modified = number(reply.get("nModified"));
      long upserted = documents(reply.get("upserted")).size();
      messages.add(new Message(
          "Matched " + (n - upserted) + ", modified " + modified
              + (upserted > 0 ? ", inserted " + upserted : ""),
          "",
          0));
      n = modified + upserted;
    }
    results.add(new Result.Count(n));
    failures(reply, Script.method(statement) + " failed", results);
  }

  // The first of a write's errors, with how many more it had, and the failure of its write
  // concern.
  private static void failures(Document reply, String failed, List<Result> results) {
    var errors = documents(reply.get("writeErrors"));
    if (!errors.isEmpty()) {
      var more = errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : "";
      results.add(failure(server(errors.getFirst(), failed, more)));
    }
    if (reply.get("writeConcernError") instanceof Document concern) {
      results.add(failure(server(concern, "The write concern failed", "")));
    }
  }

  // The server's error, as a reply's document of it gives it.
  private static DocumentException server(Document error, String failed, String more) {
    return DocumentException.server(
        (int) number(error.get("code")),
        error.get("codeName") instanceof String s ? s : "",
        (error.get("errmsg") instanceof String s ? s : failed) + more);
  }

  /** Gives up the documents not read: the server's cursor is freed. */
  public Abandoned close() {
    over = true;
    var open = cursor;
    cursor = null;
    if (open == null) {
      return Abandoned.NOTHING;
    }
    try {
      open.close();
      return Abandoned.NOTHING;
    } catch (DocumentException e) {
      return new Abandoned(Optional.of(failure(e)), List.of());
    }
  }

  // The fields are those of the first documents, which those after them keep.
  private Result.Rows rows(List<Document> documents, long from) {
    if (fields == null) {
      fields = Values.found(documents);
    }
    var rows = new ArrayList<List<Object>>(documents.size());
    for (var document : documents) {
      rows.add(Values.row(fields, document));
    }
    return new Result.Rows(fields, from, Collections.unmodifiableList(rows), documents);
  }

  private static Result.Rows single(String name, long n) {
    return new Result.Rows(
        List.of(new Field(name, "long", Kind.INTEGER)),
        0,
        List.of(List.of(n)),
        List.of(Document.of(name, n)));
  }

  private static List<Document> documents(Object value) {
    var documents = new ArrayList<Document>();
    if (value instanceof List<?> list) {
      for (var item : list) {
        if (item instanceof Document d) {
          documents.add(d);
        }
      }
    }
    return documents;
  }

  private static Object or(Object document) {
    return document instanceof Document ? document : Document.EMPTY;
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }

  /** The failure as a console shows a statement's. */
  public static Failure failure(DocumentException e) {
    var kind = switch (e.kind()) {
      case READ_ONLY -> Failure.Kind.ACCESS_DENIED;
      case CANCELLED -> Failure.Kind.CANCELLED;
      case CONNECTION_LOST, UNREACHABLE, TIMED_OUT -> Failure.Kind.CONNECTION_LOST;
      case SERVER -> {
        if (e.code() == DocumentException.UNAUTHORIZED) {
          yield Failure.Kind.ACCESS_DENIED;
        }
        if (e.code() == DocumentException.MAX_TIME_EXPIRED) {
          yield Failure.Kind.TIMEOUT;
        }
        if (e.code() == DocumentException.INTERRUPTED) {
          yield Failure.Kind.CANCELLED;
        }
        if (CONSTRAINT.contains(e.code())) {
          yield Failure.Kind.CONSTRAINT;
        }
        yield INVALID.contains(e.code()) ? Failure.Kind.INVALID : Failure.Kind.OTHER;
      }
      default -> Failure.Kind.OTHER;
    };
    var message = e.detail().isEmpty() ? e.kind().toString() : e.detail();
    if (!e.codeName().isEmpty()) {
      message = e.codeName() + ": " + message;
    }
    return failure(kind, message, e.code(), 0);
  }

  private static Failure failure(Failure.Kind kind, String message, int code, int position) {
    return new Failure(kind, message, "", code, position, "", 0, 0, "", "", "", "");
  }
}

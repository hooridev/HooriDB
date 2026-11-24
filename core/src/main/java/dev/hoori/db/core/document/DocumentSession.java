package dev.hoori.db.core.document;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.dialect.DocumentDialect;
import dev.hoori.db.core.log.Journal;
import dev.hoori.db.core.log.Outcome;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A client of a server of documents, as the app uses it: its catalog of databases, collections and
 * the fields their documents have, and their documents read in pages. Every command goes through
 * {@link #command}, which a read-only session lets through only where it reads. One call at a time;
 * an interrupt cancels it.
 */
public final class DocumentSession implements AutoCloseable {

  /** Documents a collection's fields are read from, at random where the server can. */
  public static final int SAMPLE = 100;

  private static final String CURSOR = "cursor";
  private static final String BATCH_SIZE = "batchSize";

  private final DocumentClient client;
  private final DocumentDialect dialect;
  private final boolean readOnly;

  public DocumentSession(DocumentClient client, DocumentDialect dialect, boolean readOnly) {
    this.client = client;
    this.dialect = dialect;
    this.readOnly = readOnly;
  }

  /** Whether it sends only commands that read. */
  public boolean readOnly() {
    return readOnly;
  }

  /** What its server's provider does its own way. */
  public DocumentDialect dialect() {
    return dialect;
  }

  /**
   * Runs the command on the database, as a statement the user ran, unless the session is read-only
   * and the command would change something: then nothing is sent, and it fails with {@link
   * DocumentException.Kind#READ_ONLY}.
   */
  public Document command(String database, Document command) throws DocumentException {
    return command(Purpose.STATEMENT, database, command);
  }

  /**
   * As {@link #command(String, Document)}, for the purpose given, which {@link Journal} records
   * with it.
   */
  public Document command(Purpose purpose, String database, Document command)
      throws DocumentException {
    if (retired) {
      throw DocumentException.of(
          DocumentException.Kind.CONNECTION_LOST,
          "The connection was given up: the server did not answer in time");
    }
    if (readOnly && !Commands.reads(command)) {
      throw DocumentException.of(
          DocumentException.Kind.READ_ONLY,
          "The connection is read-only: " + command.first() + " was not sent, as it may change"
              + " data");
    }
    return Journal.command(
        dialect,
        purpose,
        database,
        command,
        reply -> outcome(command, reply),
        () -> answer(client.command(database, command)));
  }

  // The reply, unless it says the command failed.
  private static Document answer(Document reply) throws DocumentException {
    var ok = reply.get("ok");
    if (ok instanceof Number n && n.doubleValue() == 0) {
      var code = reply.get("code") instanceof Number c ? c.intValue() : 0;
      var name = reply.get("codeName") instanceof String s ? s : "";
      var message = reply.get("errmsg") instanceof String s ? s : "";
      throw DocumentException.server(code, name, message);
    }
    return reply;
  }

  private static final Set<String> WRITES = Set.of("insert", "update", "delete");

  // What a reply says the command came to: the documents of its cursor's batch, those a write
  // changed, or nothing more.
  private static Outcome outcome(Document command, Document reply) {
    if (reply.get("cursor") instanceof Document cursor) {
      var batch = cursor.get("firstBatch") instanceof List<?> first
          ? first
          : cursor.get("nextBatch") instanceof List<?> next ? next : List.of();
      boolean more = cursor.get("id") instanceof Number id && id.longValue() != 0;
      return new Outcome.Rows(batch.size(), more);
    }
    if (WRITES.contains(command.first())) {
      // A write the server took may still have failed, in part or whole: ok, with its errors.
      var failed = reply.get("writeErrors") instanceof List<?> errors && !errors.isEmpty()
          ? errors.getFirst()
          : reply.get("writeConcernError");
      if (failed instanceof Document error) {
        var message = error.get("errmsg") instanceof String s && !s.isBlank() ? s : "Write error";
        return new Outcome.Failed(message);
      }
      var changed = reply.get("nModified") instanceof Number m ? m : reply.get("n");
      return changed instanceof Number n ? new Outcome.Changed(n.longValue()) : Outcome.DONE;
    }
    return Outcome.DONE;
  }

  // The catalog

  /** The databases the user may see, those of the server itself among them. */
  public List<Database> databases() throws CatalogException {
    try {
      var reply = command(
          Purpose.CATALOG,
          "admin",
          Document.of("listDatabases", 1, "nameOnly", true, "authorizedDatabases", true));
      var databases = new ArrayList<Database>();
      for (var item : list(reply.get("databases"))) {
        if (item instanceof Document d && d.get("name") instanceof String name) {
          databases.add(new Database(name, dialect.systemDatabases().contains(name)));
        }
      }
      databases.sort(Comparator.comparing(Database::system).thenComparing(Database::name));
      return databases;
    } catch (DocumentException e) {
      throw catalog(e);
    }
  }

  /** The collections and views of the database, by name; the server's own are left out. */
  public List<Table> collections(Database database) throws CatalogException {
    try {
      var documents = all(
          Purpose.CATALOG,
          database.name(),
          Document.of(
              "listCollections",
              1,
              // Names and types, all that is read here; with authorizedCollections, a user who
              // may read some collections of a database but not list it sees those.
              "nameOnly",
              true,
              "authorizedCollections",
              true,
              CURSOR,
              Document.EMPTY));
      var tables = new ArrayList<Table>();
      for (var d : documents) {
        if (!(d.get("name") instanceof String name) || name.startsWith("system.")) {
          continue;
        }
        var kind = "view".equals(d.get("type")) ? Table.Kind.VIEW : Table.Kind.TABLE;
        tables.add(new Table(database, name, kind));
      }
      tables.sort(Comparator.comparing(Table::name));
      return tables;
    } catch (DocumentException e) {
      throw catalog(e);
    }
  }

  /**
   * The fields of a collection's documents, as {@link #SAMPLE} of them have them: {@code _id}
   * first, then in the order they first came, each with the types it held, such as {@code string}
   * or {@code int | null}. A field that some of them lack, or that is null in some, is nullable.
   * {@code _id} is the key.
   */
  public List<Column> fields(Table collection) throws CatalogException {
    try {
      return fields(collection, sample(collection));
    } catch (DocumentException e) {
      throw catalog(e);
    }
  }

  private List<Document> sample(Table collection) throws DocumentException {
    // In the order of their ids, so that the fields come in the same order each time.
    var pipeline = Document.array(
        Document.of("$sample", Document.of("size", SAMPLE)),
        Document.of("$sort", Document.of("_id", 1)));
    try {
      return first(collection, pipeline);
    } catch (DocumentException e) {
      // A server or view that cannot sample: the first documents then.
      if (e.kind() != DocumentException.Kind.SERVER) {
        throw e;
      }
      return first(collection, Document.array(Document.of("$limit", SAMPLE)));
    }
  }

  private List<Document> first(Table collection, List<Object> pipeline) throws DocumentException {
    return all(
        Purpose.CATALOG,
        database(collection),
        Document.of(
            "aggregate",
            collection.name(),
            "pipeline",
            pipeline,
            CURSOR,
            Document.of(BATCH_SIZE, SAMPLE)));
  }

  /** The fields that the documents have, as {@link #fields(Table)} says. */
  public static List<Column> fields(Table collection, List<Document> documents) {
    Map<String, Set<String>> types = new LinkedHashMap<>();
    types.put("_id", new LinkedHashSet<>());
    Map<String, Integer> present = new LinkedHashMap<>();
    for (var document : documents) {
      for (var entry : document.entries()) {
        types
            .computeIfAbsent(entry.name(), x -> new LinkedHashSet<>())
            .add(Bson.type(entry.value()));
        present.merge(entry.name(), 1, Integer::sum);
      }
    }
    var columns = new ArrayList<Column>();
    for (var field : types.entrySet()) {
      var name = field.getKey();
      if (name.equals("_id") && field.getValue().isEmpty() && !documents.isEmpty()) {
        continue; // a view whose documents have none
      }
      var held = new ArrayList<>(field.getValue());
      boolean nullable = held.remove("null") || present.getOrDefault(name, 0) < documents.size();
      columns.add(new Column(
          collection, name, type(name, held, nullable), nullable, name.equals("_id") ? 1 : 0));
    }
    return columns;
  }

  // The types a field held, with null where it is nullable; null for none, objectId for the key.
  private static String type(String name, List<String> held, boolean nullable) {
    if (held.isEmpty()) {
      return name.equals("_id") ? "objectId" : "null";
    }
    var type = String.join(" | ", held);
    return nullable ? type + " | null" : type;
  }

  // Reading documents

  /**
   * The server's estimate of a collection's documents, from its metadata, which costs nothing; none
   * for a view, which the server would have to run to count.
   */
  public OptionalLong estimate(Table collection) throws QueryException {
    return estimate(collection, Document.EMPTY);
  }

  /**
   * As {@link #estimate(Table)}, narrowed by the filter: empty where a filter is set, as the
   * collection's metadata count ignores it.
   */
  public OptionalLong estimate(Table collection, Document filter) throws QueryException {
    if (collection.kind() == Table.Kind.VIEW || filter != null && !filter.isEmpty()) {
      return OptionalLong.empty();
    }
    try {
      var reply =
          command(Purpose.ROWS, database(collection), Document.of("count", collection.name()));
      return reply.get("n") instanceof Number n
          ? OptionalLong.of(n.longValue())
          : OptionalLong.empty();
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  /** The exact number of a collection's documents. */
  public long count(Table collection) throws QueryException {
    return count(collection, Document.EMPTY);
  }

  /** The exact number of a collection's documents matching the filter. */
  public long count(Table collection, Document filter) throws QueryException {
    var pipeline = filter != null && !filter.isEmpty()
        ? Document.array(Document.of("$match", filter), Document.of("$count", "n"))
        : Document.array(Document.of("$count", "n"));
    try {
      var documents = all(
          Purpose.ROWS,
          database(collection),
          Document.of(
              "aggregate", collection.name(), "pipeline", pipeline, CURSOR, Document.EMPTY));
      return documents.isEmpty() || !(documents.getFirst().get("n") instanceof Number n)
          ? 0
          : n.longValue();
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  /**
   * The documents from {@code from} up to {@code to}, fewer at the end, in the order of {@code _id},
   * with the values of the fields as a grid shows them, as {@link Values#row} gives them.
   */
  public Page read(Table collection, List<Column> fields, long from, long to)
      throws QueryException {
    return read(collection, fields, from, to, Document.EMPTY, Document.EMPTY);
  }

  /**
   * As {@link #read(Table, List, long, long)}, narrowed by {@code filter} and ordered by {@code
   * sort} followed by {@code _id: 1} unless {@code sort} names {@code _id}, so paging stays
   * deterministic.
   */
  public Page read(
      Table collection, List<Column> fields, long from, long to, Document filter, Document sort)
      throws QueryException {
    if (to <= from) {
      return Values.page(fields, List.of()); // a limit of 0 would be no limit
    }
    var command = Document.of("find", collection.name());
    if (filter != null && !filter.isEmpty()) {
      command = command.with("filter", filter);
    }
    command = command.with("sort", effectiveSort(sort));
    if (from > 0) {
      command = command.with("skip", from);
    }
    command = command.with("limit", to - from).with(BATCH_SIZE, (int)
        Math.min(to - from, Integer.MAX_VALUE));
    try {
      var documents = all(Purpose.ROWS, database(collection), command);
      return Values.page(fields, documents);
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  private static Document effectiveSort(Document sort) {
    if (sort == null || sort.isEmpty()) {
      return Document.of("_id", 1);
    }
    // The server takes a $natural order alone, and it is a stable order of its own.
    return sort.has("_id") || sort.has("$natural") ? sort : sort.with("_id", 1);
  }

  /**
   * The whole value of a field of the document whose {@code _id} the row of a page holds, as the
   * grid shows a value not cut short: a text itself, a document or an array as its Relaxed Extended
   * JSON, another value as the shell writes it; null for a field that is null or missing.
   *
   * @param fields the collection's fields, as {@link #read} takes them
   * @param row a row of a page of the collection, with those fields
   * @param column the field's index among them
   * @throws QueryException as {@link #read} reports it; {@link QueryException.Failure#NOT_FOUND}
   *     also where no document has the {@code _id} now
   * @throws IllegalArgumentException if the fields have no {@code _id}, or the row's cannot name
   *     its document, as one read in part
   */
  public String whole(Table collection, List<Column> fields, List<Object> row, int column)
      throws QueryException {
    int key = -1;
    for (int i = 0; i < fields.size(); i++) {
      if (fields.get(i).name().equals("_id")) {
        key = i;
      }
    }
    if (key < 0 || row.size() != fields.size()) {
      throw new IllegalArgumentException("The row has no _id to read its document by");
    }
    var name = fields.get(column).name();
    var command = Document.of(
        "find", collection.name(), "filter", Document.of("_id", id(row.get(key), fields.get(key))));
    // A projection takes a dot for a field of a field, a $ for an operator: those read it all.
    if (name.indexOf('.') < 0 && !name.startsWith("$")) {
      command = command.with("projection", Document.of(name, 1));
    }
    command = command.with("limit", 2).with("singleBatch", true);
    try {
      var documents = all(Purpose.ROWS, database(collection), command);
      if (documents.size() != 1) {
        throw QueryException.of(
            QueryException.Failure.NOT_FOUND,
            (documents.isEmpty() ? "No document" : "More than one document") + " of "
                + collection.name() + " has the _id");
      }
      return switch (documents.getFirst().get(name)) {
        case null -> null;
        case String s -> s;
        case Document d -> ExtendedJson.of(d);
        case List<?> list -> ExtendedJson.of(list);
        case Object value -> Shell.compact(value);
      };
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  // The _id as the server has it, from its cell: as Values made it of the value.
  private static Object id(Object cell, Column field) {
    var kind = Values.kind(field.type());
    try {
      return switch (cell) {
        case null -> throw new IllegalArgumentException("The row's _id is null");
        case Missing.FIELD -> throw new IllegalArgumentException("The row has no _id");
        case Cut _ -> throw new IllegalArgumentException("The row's _id was read in part");
        case String s when kind == Kind.TEXT -> s;
        case String s -> Shell.parse(s); // the shell's text of it, or its Extended JSON
        case OffsetDateTime t -> t.toInstant();
        case BigDecimal d -> new Bson.Decimal128(d.toString());
        case Binary b when b.length() == b.bytes().length -> new Bson.Binary(0, b.bytes());
        case Binary _ -> throw new IllegalArgumentException("The row's _id was read in part");
        default -> cell;
      };
    } catch (SyntaxException e) {
      throw new IllegalArgumentException("The row's _id cannot name its document", e);
    }
  }

  // Cursors

  /**
   * A cursor's documents: the first batch and those the server reads on as they are asked for,
   * with {@code getMore}. Close it to free the server's cursor where it is not read to its end.
   */
  public final class Cursor {

    private final Purpose purpose;
    private final String database;
    private final String collection;
    private long id;
    private List<Document> batch;

    Cursor(Purpose purpose, String database, Document reply) throws DocumentException {
      this.purpose = purpose;
      this.database = database;
      var cursor = reply.get(CURSOR) instanceof Document c ? c : Document.EMPTY;
      this.id = cursor.get("id") instanceof Number n ? n.longValue() : 0;
      var ns = cursor.get("ns") instanceof String s ? s : "";
      int dot = ns.indexOf('.');
      this.collection = dot < 0 ? ns : ns.substring(dot + 1);
      this.batch = documents(cursor.get("firstBatch"));
    }

    /** Whether the server has more of it, beyond what {@link #next} gave. */
    public boolean more() {
      return id != 0 || !batch.isEmpty();
    }

    /** Up to the number of documents, fewer at its end. */
    public List<Document> next(int count) throws DocumentException {
      var got = new ArrayList<Document>(Math.min(count, 1024));
      while (got.size() < count) {
        if (batch.isEmpty()) {
          if (id == 0) {
            break;
          }
          var reply = command(
              purpose,
              database,
              Document.of("getMore", id, "collection", collection, BATCH_SIZE, count - got.size()));
          var cursor = reply.get(CURSOR) instanceof Document c ? c : Document.EMPTY;
          id = cursor.get("id") instanceof Number n ? n.longValue() : 0;
          batch = documents(cursor.get("nextBatch"));
          if (batch.isEmpty()) {
            // At its end, or at the end for now of a cursor that waits for more, as a tailable
            // one: what it gave so far, and more() says whether to ask again.
            break;
          }
          continue;
        }
        int take = Math.min(count - got.size(), batch.size());
        got.addAll(batch.subList(0, take));
        batch = batch.subList(take, batch.size());
      }
      return got;
    }

    /**
     * Frees the server's cursor, where it has one left, within {@link #GRACE}: also after the
     * thread was interrupted, as by a cancel, which leaves the server's cursor open.
     */
    public void close() throws DocumentException {
      batch = List.of();
      if (id != 0) {
        var open = id;
        id = 0;
        bounded(
            "end a cursor",
            () -> command(
                purpose,
                database,
                Document.of("killCursors", collection, "cursors", Document.array(open))));
      }
    }
  }

  /**
   * A cursor of the reply of a command that opens one, such as find or aggregate, which a statement
   * the user ran sent.
   */
  public Cursor cursor(String database, Document reply) throws DocumentException {
    return new Cursor(Purpose.STATEMENT, database, reply);
  }

  // All the documents of a command that opens a cursor, which is freed where reading it fails.
  private List<Document> all(Purpose purpose, String database, Document command)
      throws DocumentException {
    var cursor = new Cursor(purpose, database, command(purpose, database, command));
    var documents = new ArrayList<Document>();
    try {
      while (cursor.more()) {
        var next = cursor.next(Integer.MAX_VALUE);
        if (next.isEmpty()) {
          break;
        }
        documents.addAll(next);
      }
    } catch (DocumentException e) {
      try {
        cursor.close();
      } catch (DocumentException left) {
        e.addSuppressed(left);
      }
      throw e;
    }
    return documents;
  }

  /** How long freeing a cursor or closing the connection may take before it is given up. */
  public static final Duration GRACE = Duration.ofSeconds(2);

  private interface Call {
    void run() throws DocumentException;
  }

  // Set once a call given up may still use the client's session, which no other call may use with
  // it: the session takes no command from then on.
  private volatile boolean retired;

  // Runs the call on a thread of its own, also when this one was interrupted, and waits for it
  // within GRACE. Past that it interrupts it, which ends the driver's I/O on a virtual thread, and
  // fails as timed out; where it has not ended even so, the session is retired.
  private void bounded(String what, Call call) throws DocumentException {
    var failure = new AtomicReference<Throwable>();
    var worker = Thread.ofVirtual().name(what).start(Journal.carried(() -> {
      try {
        call.run();
      } catch (DocumentException | RuntimeException e) {
        failure.set(e);
      }
    }));
    boolean interrupted = Thread.interrupted();
    try {
      if (!worker.join(GRACE)) {
        worker.interrupt();
        if (!worker.join(Duration.ofMillis(100))) {
          retired = true;
        }
        throw DocumentException.of(
            DocumentException.Kind.TIMED_OUT,
            "The server did not answer within " + GRACE.toSeconds() + " seconds");
      }
    } catch (InterruptedException _) {
      worker.interrupt();
      interrupted = true;
      try {
        if (!worker.join(Duration.ofMillis(100))) {
          retired = true;
        }
      } catch (InterruptedException _) {
        retired = true;
      }
      throw DocumentException.of(DocumentException.Kind.CANCELLED, "");
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    switch (failure.get()) {
      case null -> {
        // The call ended as it should.
      }
      case DocumentException e -> throw e;
      case RuntimeException e -> throw e;
      default -> throw new IllegalStateException(failure.get());
    }
  }

  private static List<Document> documents(Object value) {
    var documents = new ArrayList<Document>();
    for (var item : list(value)) {
      if (item instanceof Document d) {
        documents.add(d);
      }
    }
    return documents;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  private static String database(Table collection) {
    return collection.container().database().name();
  }

  /** Ends the client and its connections, from any thread. */
  @Override
  public void close() {
    try {
      bounded("close a connection", client::close);
    } catch (DocumentException | RuntimeException _) {
      // Given up: the driver's threads end the connection as they can.
    }
  }

  // Failures as the catalog and queries report them.

  /** The failure as the catalog reports it. */
  public static CatalogException catalog(DocumentException e) {
    var failure = switch (e.kind()) {
      case CANCELLED -> CatalogException.Failure.CANCELLED;
      case CONNECTION_LOST, UNREACHABLE, TIMED_OUT -> CatalogException.Failure.CONNECTION_LOST;
      case SERVER ->
        switch (e.code()) {
          case DocumentException.UNAUTHORIZED -> CatalogException.Failure.ACCESS_DENIED;
          case DocumentException.NAMESPACE_NOT_FOUND -> CatalogException.Failure.NOT_FOUND;
          case DocumentException.INTERRUPTED -> CatalogException.Failure.CANCELLED;
          default -> CatalogException.Failure.OTHER;
        };
      default -> CatalogException.Failure.OTHER;
    };
    return CatalogException.of(failure, e.detail());
  }

  /** The failure as a query reports it. */
  public static QueryException query(DocumentException e) {
    var failure = switch (e.kind()) {
      case CANCELLED -> QueryException.Failure.CANCELLED;
      case CONNECTION_LOST, UNREACHABLE, TIMED_OUT -> QueryException.Failure.CONNECTION_LOST;
      case SERVER ->
        switch (e.code()) {
          case DocumentException.UNAUTHORIZED -> QueryException.Failure.ACCESS_DENIED;
          case DocumentException.NAMESPACE_NOT_FOUND -> QueryException.Failure.NOT_FOUND;
          case DocumentException.INTERRUPTED -> QueryException.Failure.CANCELLED;
          default -> QueryException.Failure.OTHER;
        };
      default -> QueryException.Failure.OTHER;
    };
    return QueryException.of(failure, e.detail());
  }
}

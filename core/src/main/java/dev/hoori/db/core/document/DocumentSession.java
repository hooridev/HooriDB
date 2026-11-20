package dev.hoori.db.core.document;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.data.Page;
import dev.hoori.db.core.dialect.DocumentDialect;
import dev.hoori.db.core.query.QueryException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * A client of a server of documents, as the app uses it: its catalog of databases, collections and
 * the fields their documents have, and their documents read in pages. Every command goes through
 * {@link #command}, which a read-only session lets through only where it reads. One call at a time;
 * an interrupt cancels it.
 */
public final class DocumentSession implements AutoCloseable {

  /** Documents a collection's fields are read from, at random where the server can. */
  public static final int SAMPLE = 100;

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

  /**
   * Runs the command on the database, unless the session is read-only and the command would change
   * something: then nothing is sent, and it fails with {@link DocumentException.Kind#READ_ONLY}.
   */
  public Document command(String database, Document command) throws DocumentException {
    if (readOnly && !Commands.reads(command)) {
      throw DocumentException.of(
          DocumentException.Kind.READ_ONLY,
          "The connection is read-only: " + command.first() + " was not sent, as it may change"
              + " data");
    }
    var reply = client.command(database, command);
    var ok = reply.get("ok");
    if (ok instanceof Number n && n.doubleValue() == 0) {
      var code = reply.get("code") instanceof Number c ? c.intValue() : 0;
      var name = reply.get("codeName") instanceof String s ? s : "";
      var message = reply.get("errmsg") instanceof String s ? s : "";
      throw DocumentException.server(code, name, message);
    }
    return reply;
  }

  // The catalog

  /** The databases the user may see, those of the server itself among them. */
  public List<Database> databases() throws CatalogException {
    try {
      var reply = command(
          "admin", Document.of("listDatabases", 1, "nameOnly", true, "authorizedDatabases", true));
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
          database.name(),
          Document.of(
              "listCollections",
              1,
              "nameOnly",
              false,
              "authorizedCollections",
              true,
              "cursor",
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
        database(collection),
        Document.of(
            "aggregate",
            collection.name(),
            "pipeline",
            pipeline,
            "cursor",
            Document.of("batchSize", SAMPLE)));
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
      var type =
          held.isEmpty() ? name.equals("_id") ? "objectId" : "null" : String.join(" | ", held);
      if (nullable && !held.isEmpty()) {
        type += " | null";
      }
      columns.add(new Column(collection, name, type, nullable, name.equals("_id") ? 1 : 0));
    }
    return columns;
  }

  // Reading documents

  /**
   * The server's estimate of a collection's documents, from its metadata, which costs nothing; none
   * for a view, which the server would have to run to count.
   */
  public OptionalLong estimate(Table collection) throws QueryException {
    if (collection.kind() == Table.Kind.VIEW) {
      return OptionalLong.empty();
    }
    try {
      var reply = command(database(collection), Document.of("count", collection.name()));
      return reply.get("n") instanceof Number n
          ? OptionalLong.of(n.longValue())
          : OptionalLong.empty();
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  /** The exact number of a collection's documents. */
  public long count(Table collection) throws QueryException {
    try {
      var documents = all(
          database(collection),
          Document.of(
              "aggregate",
              collection.name(),
              "pipeline",
              Document.array(Document.of("$count", "n")),
              "cursor",
              Document.EMPTY));
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
    var command = Document.of("find", collection.name(), "sort", Document.of("_id", 1));
    if (from > 0) {
      command = command.with("skip", from);
    }
    command = command.with("limit", to - from).with("batchSize", (int)
        Math.min(to - from, Integer.MAX_VALUE));
    try {
      var documents = all(database(collection), command);
      return Values.page(fields, documents);
    } catch (DocumentException e) {
      throw query(e);
    }
  }

  // Cursors

  /**
   * A cursor's documents: the first batch and those the server reads on as they are asked for,
   * with {@code getMore}. Close it to free the server's cursor where it is not read to its end.
   */
  public final class Cursor {

    private final String database;
    private final String collection;
    private long id;
    private List<Document> batch;

    Cursor(String database, Document reply) throws DocumentException {
      this.database = database;
      var cursor = reply.get("cursor") instanceof Document c ? c : Document.EMPTY;
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
              database,
              Document.of(
                  "getMore", id, "collection", collection, "batchSize", count - got.size()));
          var cursor = reply.get("cursor") instanceof Document c ? c : Document.EMPTY;
          id = cursor.get("id") instanceof Number n ? n.longValue() : 0;
          batch = documents(cursor.get("nextBatch"));
          if (batch.isEmpty() && id == 0) {
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

    /** Frees the server's cursor, where it has one left. */
    public void close() throws DocumentException {
      batch = List.of();
      if (id != 0) {
        var open = id;
        id = 0;
        command(database, Document.of("killCursors", collection, "cursors", Document.array(open)));
      }
    }
  }

  /** A cursor of the reply of a command that opens one, such as find or aggregate. */
  public Cursor cursor(String database, Document reply) throws DocumentException {
    return new Cursor(database, reply);
  }

  // All the documents of a command that opens a cursor.
  private List<Document> all(String database, Document command) throws DocumentException {
    var cursor = new Cursor(database, command(database, command));
    var documents = new ArrayList<Document>();
    while (cursor.more()) {
      var next = cursor.next(Integer.MAX_VALUE);
      if (next.isEmpty()) {
        break;
      }
      documents.addAll(next);
    }
    return documents;
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
    client.close();
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

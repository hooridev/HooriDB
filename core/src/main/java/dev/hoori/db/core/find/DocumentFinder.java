package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.DocumentSession;
import dev.hoori.db.core.document.Shell;
import dev.hoori.db.core.log.Purpose;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Searches a server of documents, through {@link DocumentSession#command}, so that a read-only
 * session sends only what reads: the names of the collections of a database and of the fields that
 * a sample of their documents has, and the values of those fields, a collection at a time, with
 * {@code $regex} for a text and equality for a number. The fields are those of {@link
 * DocumentSession#SAMPLE} documents, nested ones and those in arrays included, by their paths.
 */
public final class DocumentFinder {

  private DocumentFinder() {}

  /**
   * The collections and views of the database whose names match, and the fields of their sampled
   * documents whose paths do, as columns of the collection.
   */
  public static List<Found> objects(DocumentSession session, Database database, Search search)
      throws QueryException {
    var found = new ArrayList<Found>();
    var collections = collections(session, database);
    for (var collection : collections) {
      search
          .in(collection.name())
          .ifPresent(at -> found.add(new Found(
              collection.kind() == Table.Kind.VIEW ? Found.Kind.VIEW : Found.Kind.TABLE,
              database,
              collection,
              collection.name(),
              false,
              null,
              at.start(),
              at.end())));
    }
    for (var collection : collections) {
      for (var path : fields(sample(session, collection)).keySet()) {
        search
            .in(path)
            .ifPresent(at -> found.add(new Found(
                Found.Kind.COLUMN, database, collection, path, false, null, at.start(), at.end())));
      }
    }
    return List.copyOf(found);
  }

  /** The collections and views of the database, with the server's estimate of their documents. */
  public static List<Candidate> candidates(DocumentSession session, Database database)
      throws QueryException {
    var candidates = new ArrayList<Candidate>();
    for (var collection : collections(session, database)) {
      candidates.add(
          new Candidate(collection, session.estimate(collection), OptionalLong.empty(), List.of()));
    }
    return List.copyOf(candidates);
  }

  private static List<Table> collections(DocumentSession session, Database database)
      throws QueryException {
    try {
      return session.collections(database);
    } catch (CatalogException e) {
      throw QueryException.of(query(e.failure()), e.detail());
    }
  }

  private static QueryException.Failure query(CatalogException.Failure failure) {
    return switch (failure) {
      case CONNECTION_LOST -> QueryException.Failure.CONNECTION_LOST;
      case NOT_FOUND -> QueryException.Failure.NOT_FOUND;
      case ACCESS_DENIED -> QueryException.Failure.ACCESS_DENIED;
      case CANCELLED -> QueryException.Failure.CANCELLED;
      case ABORTED -> QueryException.Failure.ABORTED;
      case WRONG_DATABASE, OTHER -> QueryException.Failure.OTHER;
    };
  }

  /** What the values at a path held in the sample. */
  private enum Held {
    TEXT,
    NUMBER
  }

  /**
   * Searches the collection: a sample of its documents names the fields, by their paths, that hold
   * text, or numbers where the text is one; then one find of the documents where any of them
   * matches, at most one more than {@link Search#LIMIT}, says how many did, and in which fields,
   * by the values it gave. {@code others} says nothing here: a field is searched by what its
   * values are.
   */
  public static Hits search(
      DocumentSession session, Candidate candidate, Search search, boolean others)
      throws QueryException {
    var collection = candidate.table();
    var fields = fields(sample(session, collection));
    var number = search.number();
    var searched = new ArrayList<String>();
    var any = new ArrayList<Object>();
    for (var field : fields.entrySet()) {
      var condition = condition(field.getKey(), field.getValue(), search, number);
      if (condition != null) {
        searched.add(field.getKey());
        any.add(condition);
      }
    }
    if (searched.isEmpty()) {
      return Hits.none(collection);
    }
    // Only the first part of each path, as two paths of one field would collide.
    var projection = new LinkedHashMap<String, Object>();
    for (var path : searched) {
      projection.put(path.split("\\.", 2)[0], 1);
    }
    var shown = new ArrayList<Object>();
    projection.forEach((name, one) -> {
      shown.add(name);
      shown.add(one);
    });
    int limit = Search.LIMIT + 1;
    var command = Document.of(
        "find",
        collection.name(),
        "filter",
        Document.of("$or", Document.array(any)),
        "projection",
        Document.of(shown.toArray()),
        "limit",
        limit,
        "batchSize",
        limit);
    var documents = all(session, collection, command);
    var pattern = search.pattern();
    var counts = new int[searched.size()];
    int matched = 0;
    for (var document : documents) {
      if (++matched > Search.LIMIT) {
        break;
      }
      for (int i = 0; i < counts.length; i++) {
        var path = searched.get(i);
        for (var value : values(document, path)) {
          boolean hit = value instanceof String text
              ? fields.get(path).contains(Held.TEXT) && pattern.matcher(text).find()
              : number.isPresent()
                  && number(value).map(n -> n.compareTo(number.get()) == 0).orElse(false);
          if (hit) {
            counts[i]++;
            break;
          }
        }
      }
    }
    var hits = new ArrayList<Hits.Hit>();
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] > 0) {
        hits.add(new Hits.Hit(searched.get(i), counts[i]));
      }
    }
    return new Hits(
        collection, searched, hits, Math.min(matched, Search.LIMIT), matched > Search.LIMIT);
  }

  // The condition on the field: $regex for text, equality for a number; null where it holds
  // neither that can match.
  private static Document condition(
      String path, Set<Held> held, Search search, Optional<BigDecimal> number) {
    var conditions = new ArrayList<Object>();
    if (held.contains(Held.TEXT)) {
      var regex = Document.of("$regex", regex(search));
      conditions.add(
          Document.of(path, search.caseSensitive() ? regex : regex.with("$options", "i")));
    }
    if (held.contains(Held.NUMBER) && number.isPresent()) {
      conditions.add(Document.of(path, value(number.get())));
    }
    return switch (conditions.size()) {
      case 0 -> null;
      case 1 -> (Document) conditions.getFirst();
      default -> Document.of("$or", Document.array(conditions));
    };
  }

  /**
   * The statement of the shell that finds the collection's documents whose value in the field
   * matches, for a console; empty where the search would not read the field, as its value could
   * not match.
   */
  public static Optional<String> rows(Table collection, String path, Search search) {
    var conditions = new ArrayList<Object>();
    var regex = Document.of("$regex", regex(search));
    conditions.add(Document.of(path, search.caseSensitive() ? regex : regex.with("$options", "i")));
    search.number().ifPresent(n -> conditions.add(Document.of(path, value(n))));
    var filter = conditions.size() == 1
        ? (Document) conditions.getFirst()
        : Document.of("$or", Document.array(conditions));
    return Optional.of("db.getCollection(" + Shell.quote(collection.name()) + ").find("
        + Shell.compact(filter) + ")");
  }

  // The search as a pattern of the server's: a text as itself, each sign that means something
  // there escaped.
  private static String regex(Search search) {
    return switch (search.match()) {
      case CONTAINS -> literal(search.text());
      case EQUALS -> "^" + literal(search.text()) + "$";
      case PATTERN -> search.text();
    };
  }

  private static String literal(String text) {
    var out = new StringBuilder(text.length() * 2);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if ("\\^$.|?*+()[]{}/-".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  // A number as the server compares it: a whole one as a long, else as a double.
  private static Object value(BigDecimal number) {
    try {
      return number.longValueExact();
    } catch (ArithmeticException e) {
      return number.doubleValue();
    }
  }

  private static Optional<BigDecimal> number(Object value) {
    return switch (value) {
      case Integer i -> Optional.of(BigDecimal.valueOf(i));
      case Long l -> Optional.of(BigDecimal.valueOf(l));
      case Double d when Double.isFinite(d) -> Optional.of(BigDecimal.valueOf(d));
      case Bson.Decimal128 d -> Optional.ofNullable(d.value());
      default -> Optional.empty();
    };
  }

  // The documents of a sample of the collection, in the order of their ids.
  private static List<Document> sample(DocumentSession session, Table collection)
      throws QueryException {
    var pipeline = Document.array(
        Document.of("$sample", Document.of("size", DocumentSession.SAMPLE)),
        Document.of("$sort", Document.of("_id", 1)));
    var command = Document.of(
        "aggregate",
        collection.name(),
        "pipeline",
        pipeline,
        "cursor",
        Document.of("batchSize", DocumentSession.SAMPLE));
    try {
      return all(session, collection, command);
    } catch (QueryException e) {
      // A view that cannot sample: its first documents then.
      if (e.failure() != QueryException.Failure.OTHER) {
        throw e;
      }
      return all(
          session,
          collection,
          command.with("pipeline", Document.array(Document.of("$limit", DocumentSession.SAMPLE))));
    }
  }

  // The paths of the documents' fields that hold text or numbers, in the order they first came.
  private static Map<String, Set<Held>> fields(List<Document> documents) {
    var fields = new LinkedHashMap<String, Set<Held>>();
    for (var document : documents) {
      fields(document, "", fields);
    }
    return fields;
  }

  private static void fields(Document document, String above, Map<String, Set<Held>> fields) {
    for (var entry : document.entries()) {
      held(above + entry.name(), entry.value(), fields);
    }
  }

  private static void held(String path, Object value, Map<String, Set<Held>> fields) {
    switch (value) {
      case String _ -> fields.computeIfAbsent(path, p -> new LinkedHashSet<>()).add(Held.TEXT);
      case Integer _, Long _, Double _, Bson.Decimal128 _ ->
        fields.computeIfAbsent(path, p -> new LinkedHashSet<>()).add(Held.NUMBER);
      case Document d -> fields(d, path + ".", fields);
      case List<?> items -> {
        for (var item : items) {
          held(path, item, fields);
        }
      }
      case null, default -> {
        // Nothing a text or a number is found in.
      }
    }
  }

  // The values at the path, through arrays, as the server reads a path.
  private static List<Object> values(Object value, String path) {
    var values = new ArrayList<Object>();
    values(value, path.split("\\."), 0, values);
    return values;
  }

  private static void values(Object value, String[] parts, int at, List<Object> values) {
    if (value instanceof List<?> items) {
      for (var item : items) {
        values(item, parts, at, values);
      }
      return;
    }
    if (at == parts.length) {
      values.add(value);
      return;
    }
    if (value instanceof Document d && d.has(parts[at])) {
      values(d.get(parts[at]), parts, at + 1, values);
    }
  }

  // Every document of the command's cursor, which is freed where reading fails.
  private static List<Document> all(DocumentSession session, Table collection, Document command)
      throws QueryException {
    var database = collection.container().database().name();
    try {
      var cursor = session.cursor(database, session.command(Purpose.SEARCH, database, command));
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
    } catch (DocumentException e) {
      throw DocumentSession.query(e);
    }
  }
}

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
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Searches a server of documents, through {@link DocumentSession#command}, so that a read-only
 * session sends only what reads: the names of the collections of a database and of the fields that
 * a sample of their documents has, and the values of those fields, a collection at a time, with
 * {@code $regex} for a text and equality for a number. The fields are those of {@link
 * DocumentSession#SAMPLE} documents, nested ones and those in arrays included, by their paths.
 */
public final class DocumentFinder {

  // The least exponent of a Decimal128's coefficient, and the greatest of its leading digit.
  private static final int DECIMAL128_MIN_EXPONENT = -6176;
  private static final int DECIMAL128_MAX_ADJUSTED = 6144;

  private DocumentFinder() {}

  /**
   * The collections and views of the database whose names match, and the fields of their sampled
   * documents whose paths do, as columns of the collection.
   */
  public static List<Found> objects(DocumentSession session, Database database, Search search)
      throws QueryException {
    var matching = search.compiled();
    var found = new ArrayList<Found>();
    var collections = collections(session, database);
    for (var collection : collections) {
      matching
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
        matching
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

  /** What the values at a path held in the sample that a text or a number is found in. */
  private enum Held {
    TEXT,
    NUMBER
  }

  /**
   * Searches the collection: a sample of its documents names the fields, by their paths, that hold
   * text, or numbers where the text is one; then one find of the documents where any of them
   * matches, at most one more than {@link Search#LIMIT}, says how many did, and in which fields,
   * by the values it gave. A field is searched by what its values are, never as text.
   */
  public static Hits search(DocumentSession session, Candidate candidate, Search search)
      throws QueryException {
    var collection = candidate.table();
    var fields = fields(sample(session, collection));
    var number = search.number();
    var searched = new ArrayList<String>();
    var any = new ArrayList<Object>();
    fields.forEach((path, held) -> condition(path, held, search, number).ifPresent(condition -> {
      searched.add(path);
      any.add(condition);
    }));
    if (searched.isEmpty()) {
      return Hits.none(collection);
    }
    int limit = Search.LIMIT + 1;
    var command = Document.of(
        "find",
        collection.name(),
        "filter",
        Document.of("$or", Document.array(any)),
        "projection",
        projection(searched),
        "limit",
        limit,
        "batchSize",
        limit);
    var documents = all(session, collection, command);
    var pattern = search.pattern();
    var counts = new int[searched.size()];
    for (var document : documents.subList(0, Math.min(documents.size(), Search.LIMIT))) {
      for (int i = 0; i < counts.length; i++) {
        var path = searched.get(i);
        var held = fields.get(path);
        if (values(document, path).stream().anyMatch(v -> hit(v, held, pattern, number))) {
          counts[i]++;
        }
      }
    }
    return Hits.of(collection, searched, counts, documents.size());
  }

  // Only the first part of each path, as two paths of one field would collide.
  private static Document projection(List<String> paths) {
    var projection = new LinkedHashMap<String, Object>();
    for (var path : paths) {
      projection.put(path.split("\\.", 2)[0], 1);
    }
    var shown = new ArrayList<Object>();
    projection.forEach((name, one) -> {
      shown.add(name);
      shown.add(one);
    });
    return Document.of(shown.toArray());
  }

  // Whether the value matches as the condition on its field asks.
  private static boolean hit(
      Object value, Set<Held> held, Pattern pattern, Optional<BigDecimal> number) {
    if (value instanceof String text) {
      return held.contains(Held.TEXT) && pattern.matcher(text).find();
    }
    return held.contains(Held.NUMBER)
        && number.isPresent()
        && number(value).map(n -> n.compareTo(number.get()) == 0).orElse(false);
  }

  // The condition on the field: $regex for text, equality for a number; empty where it holds
  // neither that can match.
  private static Optional<Document> condition(
      String path, Set<Held> held, Search search, Optional<BigDecimal> number) {
    var conditions = new ArrayList<Object>();
    if (held.contains(Held.TEXT)) {
      conditions.add(text(path, search));
    }
    if (held.contains(Held.NUMBER)) {
      number.flatMap(DocumentFinder::value).ifPresent(v -> conditions.add(Document.of(path, v)));
    }
    return switch (conditions.size()) {
      case 0 -> Optional.empty();
      case 1 -> Optional.of((Document) conditions.getFirst());
      default -> Optional.of(Document.of("$or", Document.array(conditions)));
    };
  }

  private static Document text(String path, Search search) {
    var regex = Document.of("$regex", regex(search));
    return Document.of(path, search.caseSensitive() ? regex : regex.with("$options", "i"));
  }

  /**
   * The statement of the shell that finds the collection's documents whose value in the field
   * matches, for a console: as text, and as a number where the text is one that a number of the
   * server's can be.
   */
  public static String rows(Table collection, String path, Search search) {
    var conditions = new ArrayList<Object>();
    conditions.add(text(path, search));
    search
        .number()
        .flatMap(DocumentFinder::value)
        .ifPresent(v -> conditions.add(Document.of(path, v)));
    var filter = conditions.size() == 1
        ? (Document) conditions.getFirst()
        : Document.of("$or", Document.array(conditions));
    return "db.getCollection(" + Shell.quote(collection.name()) + ").find(" + Shell.compact(filter)
        + ")";
  }

  // The search as a pattern of the server's: a text as itself, each sign that means something
  // there escaped.
  private static String regex(Search search) {
    return switch (search.match()) {
      case CONTAINS -> literal(search.text());
      case EQUALS -> "\\A" + literal(search.text()) + "\\z";
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

  // A number as the server compares it: a whole one as a long, which every type of number is
  // equal to; else as a double, for the doubles, or as a decimal of the text, for a Decimal128,
  // which is equal to no double that is not exactly it, as 0.1 is not. Each only where it holds
  // the number: a double whose shortest decimal is the number, so that 1E-6177 does not become
  // 0.0, and a Decimal128 where it is exact, which the driver refuses otherwise; empty for a
  // number neither holds, which no field equals.
  private static Optional<Object> value(BigDecimal number) {
    try {
      return Optional.of(number.longValueExact());
    } catch (ArithmeticException _) {
      var operands = new ArrayList<Object>();
      if (Search.isDouble(number)) {
        operands.add(number.doubleValue());
      }
      if (decimal128(number)) {
        operands.add(new Bson.Decimal128(number.toString()));
      }
      return operands.isEmpty()
          ? Optional.empty()
          : Optional.of(Document.of("$in", Document.array(operands)));
    }
  }

  // Whether a Decimal128 holds the number exactly: at most 34 digits, at an exponent in its range.
  static boolean decimal128(BigDecimal number) {
    var stripped = number.stripTrailingZeros();
    int exponent = -stripped.scale();
    return stripped.precision() <= MathContext.DECIMAL128.getPrecision()
        && exponent >= DECIMAL128_MIN_EXPONENT
        && exponent + stripped.precision() - 1 <= DECIMAL128_MAX_ADJUSTED;
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

  // The paths of the documents' fields, in the order they first came, with what of their values
  // a text or a number is found in.
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
    var held = fields.computeIfAbsent(path, p -> new LinkedHashSet<>());
    switch (value) {
      case String _ -> held.add(Held.TEXT);
      case Integer _, Long _, Double _, Bson.Decimal128 _ -> held.add(Held.NUMBER);
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

  // Every document of the command's cursor.
  private static List<Document> all(DocumentSession session, Table collection, Document command)
      throws QueryException {
    try {
      return session.all(Purpose.SEARCH, collection.container().database().name(), command);
    } catch (DocumentException e) {
      throw DocumentSession.query(e);
    }
  }
}

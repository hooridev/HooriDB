package dev.hoori.db.core.find;

import dev.hoori.db.core.catalog.CatalogException;
import dev.hoori.db.core.catalog.Database;
import dev.hoori.db.core.catalog.Table;
import dev.hoori.db.core.document.Bson;
import dev.hoori.db.core.document.Document;
import dev.hoori.db.core.document.DocumentException;
import dev.hoori.db.core.document.DocumentFilters;
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

/**
 * Searches a server of documents, through {@link DocumentSession#command}, so that a read-only
 * session sends only what reads: the names of the collections of a database and of the fields that
 * a sample of their documents has, and the values of those fields, a collection at a time, with
 * {@code $regex} for a text and equality for a number, which the server matches and counts itself.
 * The fields are those of {@link DocumentSession#SAMPLE} documents, nested ones and those in arrays
 * included, by their paths; a name that a path cannot name, one with a dot or starting with
 * {@code $}, is left out with what it holds. Every command goes through {@link
 * dev.hoori.db.core.log.Journal} as a {@link Purpose#SEARCH}.
 */
public final class DocumentFinder {

  // The least exponent of a Decimal128's coefficient, and the greatest of its leading digit.
  private static final int DECIMAL128_MIN_EXPONENT = -6176;
  private static final int DECIMAL128_MAX_ADJUSTED = 6144;

  // The facet that counts the documents matched, beside those of the fields, f0, f1 and on.
  private static final String MATCHED = "matched";

  private DocumentFinder() {}

  /**
   * The collections and views of the database whose names match, and the fields of their sampled
   * documents whose paths do, as columns of the collection; a collection the user may not read,
   * or that is gone meanwhile, is found by its name alone.
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
      var documents = readable(() -> sample(session, collection));
      for (var path : fields(documents.orElse(List.of())).keySet()) {
        matching
            .in(path)
            .ifPresent(at -> found.add(new Found(
                Found.Kind.COLUMN, database, collection, path, false, null, at.start(), at.end())));
      }
    }
    return List.copyOf(found);
  }

  /**
   * The collections and views of the database, with the server's estimate of their documents;
   * none for one the user may not count, or that is gone meanwhile, whose search then says why.
   */
  public static List<Candidate> candidates(DocumentSession session, Database database)
      throws QueryException {
    var candidates = new ArrayList<Candidate>();
    for (var collection : collections(session, database)) {
      var rows = readable(() -> session.estimate(Purpose.SEARCH, collection));
      candidates.add(new Candidate(
          collection, rows.orElse(OptionalLong.empty()), OptionalLong.empty(), List.of()));
    }
    return List.copyOf(candidates);
  }

  private interface Read<T> {
    T read() throws QueryException;
  }

  // What the read gives, or empty where the user may not read the collection or it is gone.
  private static <T> Optional<T> readable(Read<T> read) throws QueryException {
    try {
      return Optional.of(read.read());
    } catch (QueryException e) {
      if (e.failure() == QueryException.Failure.ACCESS_DENIED
          || e.failure() == QueryException.Failure.NOT_FOUND) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private static List<Table> collections(DocumentSession session, Database database)
      throws QueryException {
    try {
      return session.collections(Purpose.SEARCH, database);
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
   * text, or numbers where the text is one; then one aggregation, of the documents where any of
   * them matches, at most one more than {@link Search#LIMIT}, counts on the server how many did,
   * and in each field, by its own patterns, so that only the counts come back. A field is searched
   * by what its values are, never as text.
   */
  public static Hits search(DocumentSession session, Candidate candidate, Search search)
      throws QueryException {
    var collection = candidate.table();
    var fields = fields(sample(session, collection));
    var number = search.number();
    var searched = new ArrayList<String>();
    var conditions = new ArrayList<Document>();
    fields.forEach((path, held) -> condition(path, held, search, number).ifPresent(condition -> {
      searched.add(path);
      conditions.add(condition);
    }));
    if (searched.isEmpty()) {
      return Hits.none(collection);
    }
    var facets = new ArrayList<Object>(List.of(MATCHED, Document.array(count())));
    for (int i = 0; i < conditions.size(); i++) {
      facets.add("f" + i);
      facets.add(Document.array(
          Document.of("$limit", Search.LIMIT), Document.of("$match", conditions.get(i)), count()));
    }
    var pipeline = Document.array(
        Document.of("$match", Document.of("$or", Document.array(conditions))),
        Document.of("$limit", Search.LIMIT + 1),
        Document.of("$facet", Document.of(facets.toArray())));
    var command =
        Document.of("aggregate", collection.name(), "pipeline", pipeline, "cursor", Document.EMPTY);
    var counted = all(session, collection, command);
    var counts = new int[searched.size()];
    int matched = 0;
    if (!counted.isEmpty()) {
      var reply = counted.getFirst();
      matched = count(reply.get(MATCHED));
      for (int i = 0; i < counts.length; i++) {
        counts[i] = count(reply.get("f" + i));
      }
    }
    return Hits.of(collection, searched, counts, matched);
  }

  private static Document count() {
    return Document.of("$count", "n");
  }

  // What a facet of $count gave: one document with the number, or none for no document.
  private static int count(Object facet) {
    return facet instanceof List<?> list
            && !list.isEmpty()
            && list.getFirst() instanceof Document d
            && d.get("n") instanceof Number n
        ? n.intValue()
        : 0;
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
   * The Filter of the collection's tab that finds its documents whose value in the field matches,
   * as a document of the shell: as text, and as a number where the text is one that a number of
   * the server's can be.
   */
  public static String filter(String path, Search search) {
    var conditions = new ArrayList<Object>();
    conditions.add(text(path, search));
    search
        .number()
        .flatMap(DocumentFinder::value)
        .ifPresent(v -> conditions.add(Document.of(path, v)));
    var filter = conditions.size() == 1
        ? (Document) conditions.getFirst()
        : Document.of("$or", Document.array(conditions));
    return Shell.compact(filter);
  }

  // The search as a pattern of the server's: a text as itself.
  private static String regex(Search search) {
    return switch (search.match()) {
      case CONTAINS -> DocumentFilters.escapeRegex(search.text());
      case EQUALS -> "\\A" + DocumentFilters.escapeRegex(search.text()) + "\\z";
      case PATTERN -> search.text();
    };
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

  private static List<Document> sample(DocumentSession session, Table collection)
      throws QueryException {
    try {
      return session.sampleDocuments(Purpose.SEARCH, collection);
    } catch (DocumentException e) {
      throw DocumentSession.query(e);
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

  // A name that a path cannot name is none of a field's: a dot parts a path, and a $ starts an
  // operator in a filter.
  private static void fields(Document document, String above, Map<String, Set<Held>> fields) {
    for (var entry : document.entries()) {
      var name = entry.name();
      if (!name.isEmpty() && name.indexOf('.') < 0 && !name.startsWith("$")) {
        held(above + name, entry.value(), fields);
      }
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

package dev.hoori.db.core.document;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * The values of documents as Relaxed Extended JSON writes them, in one line: JSON where it has the
 * type, as numbers, strings and dates of ordinary years, and an object of a {@code $} name for the
 * types JSON lacks, such as {@code {"$oid": "…"}} and {@code {"$numberDecimal": "…"}}.
 *
 * @see <a href="https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/">MongoDB
 *     Extended JSON (v2)</a>
 */
public final class ExtendedJson {

  // The dates that Relaxed Extended JSON writes as text: those of the years 1970 to 9999.
  private static final long FIRST = Instant.parse("1970-01-01T00:00:00Z").toEpochMilli();
  private static final long LAST = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

  private ExtendedJson() {}

  /** The value as Relaxed Extended JSON. */
  public static String of(Object value) {
    var out = new Output();
    write(out, value, 0);
    return out.text();
  }

  /** The documents as a JSON array of them. */
  public static String array(List<Document> documents) {
    var out = new Output();
    write(out, documents, 0);
    return out.text();
  }

  /**
   * The documents as a JSON array where its compact text and nesting fit the given bounds, or
   * empty where either bound would be exceeded. Traversal and escaped temporary text are bounded
   * by those limits too.
   */
  public static Optional<String> limitedArray(
      List<Document> documents, int maxCharacters, int maxNesting) {
    if (maxCharacters < 0 || maxNesting < 0) {
      throw new IllegalArgumentException("Negative Extended JSON bound");
    }
    try {
      var out = new Output(maxCharacters, maxNesting);
      write(out, documents, 0);
      return Optional.of(out.text());
    } catch (TooLarge _) {
      return Optional.empty();
    }
  }

  private static void write(Output out, Object value, int nesting) {
    switch (value) {
      case null -> out.append("null");
      case Boolean b -> out.append(b);
      case Integer n -> out.append(n);
      case Long n -> out.append(n);
      case Double n when n.isNaN() || n.isInfinite() -> wrapped(out, "$numberDouble", nonFinite(n));
      case Double n -> out.append(n);
      case Bson.Decimal128(var text) -> wrapped(out, "$numberDecimal", text);
      case String s -> string(out, s);
      case Instant i -> date(out, i);
      case Bson.ObjectId(var hex) -> wrapped(out, "$oid", hex);
      case Bson.Binary(var subtype, var bytes) -> {
        long encoded = 4L * ((bytes.length + 2L) / 3L);
        out.require(encoded + 52L); // the wrapper, subtype and quotes as well as the base64
        out.append("{\"$binary\": {\"base64\": ");
        string(out, Base64.getEncoder().encodeToString(bytes));
        out.append(", \"subType\": ");
        string(out, String.format("%02x", subtype));
        out.append("}}");
      }
      case Bson.Timestamp(var seconds, var increment) ->
        out.append("{\"$timestamp\": {\"t\": ")
            .append(seconds)
            .append(", \"i\": ")
            .append(increment)
            .append("}}");
      case Bson.Regex(var pattern, var options) -> regex(out, pattern, options);
      case Bson.Code(var code, var scope) -> code(out, code, scope, nesting);
      case Bson.Symbol(var symbol) -> wrapped(out, "$symbol", symbol);
      case Bson.Special s ->
        out.append(
            switch (s) {
              case MIN_KEY -> "{\"$minKey\": 1}";
              case MAX_KEY -> "{\"$maxKey\": 1}";
              case UNDEFINED -> "{\"$undefined\": true}";
            });
      case Document d -> document(out, d, nesting);
      case List<?> list -> array(out, list, nesting);
      default ->
        throw new IllegalArgumentException(
            "Not a value of a document: " + value.getClass().getName());
    }
  }

  private static String nonFinite(double n) {
    if (Double.isNaN(n)) {
      return "NaN";
    }
    return n > 0 ? "Infinity" : "-Infinity";
  }

  private static void date(Output out, Instant instant) {
    long millis = instant.toEpochMilli();
    out.append("{\"$date\": ");
    if (millis >= FIRST && millis <= LAST) {
      string(out, Shell.date(instant));
    } else {
      wrapped(out, "$numberLong", Long.toString(millis));
    }
    out.append('}');
  }

  private static void regex(Output out, String pattern, String options) {
    out.append("{\"$regularExpression\": {\"pattern\": ");
    string(out, pattern);
    out.append(", \"options\": ");
    // In alphabetical order, as Extended JSON has them.
    var sorted = options.toCharArray();
    java.util.Arrays.sort(sorted);
    string(out, new String(sorted));
    out.append("}}");
  }

  private static void code(Output out, String code, Document scope, int nesting) {
    out.append("{\"$code\": ");
    string(out, code);
    if (scope != null) {
      out.append(", \"$scope\": ");
      write(out, scope, nesting + 1);
    }
    out.append('}');
  }

  private static void document(Output out, Document document, int nesting) {
    out.nested(nesting);
    out.append('{');
    boolean first = true;
    for (var entry : document.entries()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      string(out, entry.name());
      out.append(": ");
      write(out, entry.value(), nesting + 1);
    }
    out.append('}');
  }

  private static void array(Output out, List<?> list, int nesting) {
    out.nested(nesting);
    out.append('[');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        out.append(", ");
      }
      write(out, list.get(i), nesting + 1);
    }
    out.append(']');
  }

  private static void wrapped(Output out, String name, String text) {
    out.append("{\"").append(name).append("\": ");
    string(out, text);
    out.append('}');
  }

  private static void string(Output out, String text) {
    // Quoted JSON is never shorter. Reject before asking Jackson to allocate the quoted copy.
    out.require(text.length() + 2L);
    var quoted = new StringBuilder();
    JsonStringEncoder.getInstance().quoteAsString(text, quoted);
    // Valid JSON as they are, but an editor breaks the line at them.
    out.append('"')
        .append(quoted.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))
        .append('"');
  }

  /** A bounded builder for untrusted document values. */
  private static final class Output {
    private final StringBuilder text = new StringBuilder();
    private final int maxCharacters;
    private final int maxNesting;

    Output() {
      this(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    Output(int maxCharacters, int maxNesting) {
      this.maxCharacters = maxCharacters;
      this.maxNesting = maxNesting;
    }

    void nested(int nesting) {
      if (nesting > maxNesting) {
        throw TooLarge.INSTANCE;
      }
    }

    void require(long characters) {
      if (characters > maxCharacters - (long) text.length()) {
        throw TooLarge.INSTANCE;
      }
    }

    Output append(char value) {
      require(1);
      text.append(value);
      return this;
    }

    Output append(String value) {
      require(value.length());
      text.append(value);
      return this;
    }

    Output append(Object value) {
      return append(String.valueOf(value));
    }

    String text() {
      return text.toString();
    }
  }

  /** A limit signal without the allocation and traversal of an exception stack. */
  private static final class TooLarge extends RuntimeException {
    private static final TooLarge INSTANCE = new TooLarge();

    private TooLarge() {
      super(null, null, false, false);
    }
  }
}

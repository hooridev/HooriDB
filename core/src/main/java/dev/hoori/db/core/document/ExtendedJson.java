package dev.hoori.db.core.document;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

/**
 * The values of documents as Relaxed Extended JSON writes them, in one line: JSON where it has the
 * type, as numbers, strings and dates of ordinary years, and an object of a {@code $} name for the
 * types JSON lacks, such as {@code {"$oid": "…"}} and {@code {"$numberDecimal": "…"}}.
 *
 * @see <a href="https://www.mongodb.com/docs/manual/reference/mongodb-extended-json/">MongoDB
 *     Extended JSON (v2)</a>
 */
public final class ExtendedJson {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  // The dates that Relaxed Extended JSON writes as text: those of the years 1970 to 9999.
  private static final long FIRST = Instant.parse("1970-01-01T00:00:00Z").toEpochMilli();
  private static final long LAST = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();

  private ExtendedJson() {}

  /** The value as Relaxed Extended JSON. */
  public static String of(Object value) {
    var out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  /** The documents as a JSON array of them. */
  public static String array(List<Document> documents) {
    var out = new StringBuilder();
    write(out, documents);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("null");
      case Boolean b -> out.append(b);
      case Integer n -> out.append(n);
      case Long n -> out.append(n);
      case Double n -> {
        if (n.isNaN() || n.isInfinite()) {
          wrapped(out, "$numberDouble", n.isNaN() ? "NaN" : n > 0 ? "Infinity" : "-Infinity");
        } else {
          out.append(n);
        }
      }
      case Bson.Decimal128 n -> wrapped(out, "$numberDecimal", n.text());
      case String s -> string(out, s);
      case Instant i -> {
        long millis = i.toEpochMilli();
        out.append("{\"$date\": ");
        if (millis >= FIRST && millis <= LAST) {
          string(out, DATE.format(i));
        } else {
          wrapped(out, "$numberLong", Long.toString(millis));
        }
        out.append('}');
      }
      case Bson.ObjectId id -> wrapped(out, "$oid", id.hex());
      case Bson.Binary b -> {
        out.append("{\"$binary\": {\"base64\": ");
        string(out, Base64.getEncoder().encodeToString(b.bytes()));
        out.append(", \"subType\": ");
        string(out, String.format("%02x", b.subtype()));
        out.append("}}");
      }
      case Bson.Timestamp t ->
        out.append("{\"$timestamp\": {\"t\": ")
            .append(t.seconds())
            .append(", \"i\": ")
            .append(t.increment())
            .append("}}");
      case Bson.Regex r -> {
        out.append("{\"$regularExpression\": {\"pattern\": ");
        string(out, r.pattern());
        out.append(", \"options\": ");
        // In alphabetical order, as Extended JSON has them.
        var options = r.options().toCharArray();
        java.util.Arrays.sort(options);
        string(out, new String(options));
        out.append("}}");
      }
      case Bson.Code c -> {
        out.append("{\"$code\": ");
        string(out, c.code());
        if (c.scope() != null) {
          out.append(", \"$scope\": ");
          write(out, c.scope());
        }
        out.append('}');
      }
      case Bson.Symbol s -> wrapped(out, "$symbol", s.symbol());
      case Bson.Special s ->
        out.append(
            switch (s) {
              case MIN_KEY -> "{\"$minKey\": 1}";
              case MAX_KEY -> "{\"$maxKey\": 1}";
              case UNDEFINED -> "{\"$undefined\": true}";
            });
      case Document d -> {
        out.append('{');
        boolean first = true;
        for (var entry : d.entries()) {
          if (!first) {
            out.append(", ");
          }
          first = false;
          string(out, entry.name());
          out.append(": ");
          write(out, entry.value());
        }
        out.append('}');
      }
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(", ");
          }
          write(out, list.get(i));
        }
        out.append(']');
      }
      default ->
        throw new IllegalArgumentException(
            "Not a value of a document: " + value.getClass().getName());
    }
  }

  private static void wrapped(StringBuilder out, String name, String text) {
    out.append("{\"").append(name).append("\": ");
    string(out, text);
    out.append('}');
  }

  private static void string(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c == '\u2028' || c == '\u2029') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}

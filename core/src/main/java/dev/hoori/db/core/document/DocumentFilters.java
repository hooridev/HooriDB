package dev.hoori.db.core.document;

import dev.hoori.db.core.data.ColumnFilter;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds MongoDB filter documents and shell filter text from {@link ColumnFilter}s, typing values
 * according to each field's sampled type.
 */
public final class DocumentFilters {

  private static final String REGEX_META = "\\.^$*+?()[]{}|";
  private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
  private static final Pattern DECIMAL =
      Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
  private static final BigInteger MIN_INT = BigInteger.valueOf(Integer.MIN_VALUE);
  private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
  private static final Set<String> NUMERIC_TYPES =
      Set.of("int", "int32", "long", "int64", "double", "decimal", "decimal128");

  private enum ValueType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    DECIMAL,
    BOOL,
    DATE,
    OBJECT_ID,
    UNSUPPORTED
  }

  private DocumentFilters() {}

  /**
   * A filter document for the given column filters, with one entry per filter.
   *
   * @throws QueryException {@link QueryException.Failure#CLAUSE_REFUSED} when a field cannot be
   *     filtered or a value is invalid for its sampled type
   */
  public static Document filter(List<ColumnFilter> filters, ZoneId zone) throws QueryException {
    Objects.requireNonNull(filters, "filters");
    Objects.requireNonNull(zone, "zone");
    if (filters.isEmpty()) {
      return Document.EMPTY;
    }
    var entries = new ArrayList<Document.Entry>(filters.size());
    for (var f : filters) {
      entries.add(entry(f, zone));
    }
    return new Document(entries);
  }

  /**
   * Shell filter text combining optional hand-written {@code base} text with {@code filters} using
   * {@code $and}.
   *
   * @throws QueryException {@link QueryException.Failure#CLAUSE_REFUSED} when {@code base} is not a
   *     valid shell document or any column filter is invalid
   */
  public static String filterText(String base, List<ColumnFilter> filters, ZoneId zone)
      throws QueryException {
    Objects.requireNonNull(filters, "filters");
    Objects.requireNonNull(zone, "zone");
    var trimmed = base == null ? "" : base.strip();
    if (filters.isEmpty()) {
      return trimmed;
    }
    var added = filter(filters, zone);
    if (trimmed.isEmpty()) {
      return Shell.compact(added);
    }
    try {
      if (Shell.document(trimmed).isEmpty()) {
        return Shell.compact(added);
      }
    } catch (SyntaxException e) {
      throw refused("Filter: " + e.getMessage());
    }
    // The hand-written text goes in as written, so that its form is kept.
    return "{ $and: [ " + trimmed + ", " + Shell.compact(added) + " ] }";
  }

  private static Document.Entry entry(ColumnFilter filter, ZoneId zone) throws QueryException {
    var field = filter.field();
    var name = field.name();
    if (name == null || name.isEmpty() || name.indexOf('.') >= 0 || name.startsWith("$")) {
      throw refused("Field " + name + " cannot be filtered");
    }
    if (filter.spec() instanceof ColumnFilter.Null(boolean isNull)) {
      // A field a document lacks shows as an empty cell, not as NULL.
      var isNullType = Document.of("$type", "null");
      return new Document.Entry(name, isNull ? isNullType : Document.of("$not", isNullType));
    }
    var type = valueType(field.type());
    return switch (filter.spec()) {
      case ColumnFilter.Null _ -> throw new AssertionError();
      case ColumnFilter.Text(var match, var value) -> textEntry(field, type, match, value, zone);
      case ColumnFilter.Regex(var expression) -> {
        if (type != ValueType.STRING) {
          throw refused("Field " + name + " does not support regular expression matching");
        }
        if (expression.isEmpty()) {
          throw refused("Regular expression for " + name + " cannot be empty");
        }
        yield new Document.Entry(name, Document.of("$regex", expression, "$options", "i"));
      }
      case ColumnFilter.Between(var from, var to) -> betweenEntry(field, type, from, to, zone);
      case ColumnFilter.OneOf(var values) -> oneOfEntry(field, type, values, zone);
    };
  }

  private static Document.Entry textEntry(
      Field field, ValueType type, ColumnFilter.Match match, String value, ZoneId zone)
      throws QueryException {
    var name = field.name();
    if (match == ColumnFilter.Match.EQUALS || match == ColumnFilter.Match.EXACTLY) {
      if (type == ValueType.STRING && match == ColumnFilter.Match.EQUALS) {
        return new Document.Entry(
            name, Document.of("$regex", "^" + escapeRegex(value) + "\\z", "$options", "i"));
      }
      return new Document.Entry(name, parseValue(field, type, value, zone));
    }
    if (type != ValueType.STRING) {
      throw refused("Field " + name + " of type " + field.type()
          + " does not support text matching; use Equals");
    }
    var pattern =
        match == ColumnFilter.Match.STARTS_WITH ? "^" + escapeRegex(value) : escapeRegex(value);
    return new Document.Entry(name, Document.of("$regex", pattern, "$options", "i"));
  }

  private static Document.Entry betweenEntry(
      Field field, ValueType type, String from, String to, ZoneId zone) throws QueryException {
    var name = field.name();
    if (type == ValueType.UNSUPPORTED || type == ValueType.BOOL) {
      throw refused("Field " + name + " does not support range filters");
    }
    var lo = from.strip();
    var hi = to.strip();
    if (lo.isEmpty() && hi.isEmpty()) {
      throw refused("Range filter on " + name + " needs at least one bound");
    }
    var range = Document.EMPTY;
    if (!lo.isEmpty()) {
      range = range.with("$gte", parseValue(field, type, lo, zone));
    }
    if (!hi.isEmpty()) {
      range = range.with("$lte", parseValue(field, type, hi, zone));
    }
    return new Document.Entry(name, range);
  }

  private static Document.Entry oneOfEntry(
      Field field, ValueType type, List<String> values, ZoneId zone) throws QueryException {
    if (type == ValueType.UNSUPPORTED) {
      throw refused("Field " + field.name() + " does not support value list filters");
    }
    var parsed = new ArrayList<Object>(values.size());
    for (var v : values) {
      parsed.add(parseValue(field, type, v, zone));
    }
    return new Document.Entry(field.name(), Document.of("$in", Document.array(parsed)));
  }

  private static ValueType valueType(String sampled) {
    if (sampled == null || sampled.isBlank()) {
      return ValueType.STRING;
    }
    var types = new LinkedHashSet<String>();
    for (var part : sampled.split("\\|")) {
      var trimmed = part.strip().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty() && !trimmed.equals("null")) {
        types.add(trimmed);
      }
    }
    if (types.isEmpty()) {
      return ValueType.STRING;
    }
    if (types.equals(Set.of("objectid"))) {
      return ValueType.OBJECT_ID;
    }
    if (types.equals(Set.of("bool")) || types.equals(Set.of("boolean"))) {
      return ValueType.BOOL;
    }
    if (types.equals(Set.of("date"))) {
      return ValueType.DATE;
    }
    if (NUMERIC_TYPES.containsAll(types)) {
      if (types.contains("double")) {
        return ValueType.DOUBLE;
      }
      if (types.contains("decimal") || types.contains("decimal128")) {
        return ValueType.DECIMAL;
      }
      if (types.contains("long") || types.contains("int64")) {
        return ValueType.LONG;
      }
      return ValueType.INT;
    }
    if (types.contains("string")) {
      return ValueType.STRING;
    }
    return ValueType.UNSUPPORTED;
  }

  private static Object parseValue(Field field, ValueType type, String raw, ZoneId zone)
      throws QueryException {
    var name = field.name();
    return switch (type) {
      case STRING -> raw;
      case INT -> parseInt(name, raw);
      case LONG -> parseLong(name, raw);
      case DOUBLE -> parseDouble(name, raw);
      case DECIMAL -> parseDecimal(name, raw);
      case BOOL -> Rows.parseBoolean(name, raw);
      case DATE -> parseDate(name, raw, zone);
      case OBJECT_ID -> parseObjectId(name, raw);
      case UNSUPPORTED ->
        throw refused("Field " + name + " of type " + field.type()
            + " only supports Is NULL and Is not NULL");
    };
  }

  private static Object parseInt(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!INTEGER.matcher(s).matches()) {
      throw refused("Field " + name + " expects a whole number: " + raw);
    }
    var n = new BigInteger(s);
    if (n.compareTo(MIN_INT) >= 0 && n.compareTo(MAX_INT) <= 0) {
      return n.intValueExact();
    }
    if (n.compareTo(MIN_LONG) >= 0 && n.compareTo(MAX_LONG) <= 0) {
      return n.longValueExact();
    }
    throw refused("Field " + name + " number is out of 64-bit range: " + raw);
  }

  private static Long parseLong(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!INTEGER.matcher(s).matches()) {
      throw refused("Field " + name + " expects a whole number: " + raw);
    }
    var n = new BigInteger(s);
    if (n.compareTo(MIN_LONG) < 0 || n.compareTo(MAX_LONG) > 0) {
      throw refused("Field " + name + " number is out of 64-bit range: " + raw);
    }
    return n.longValueExact();
  }

  private static Double parseDouble(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!DECIMAL.matcher(s).matches()) {
      throw refused("Field " + name + " expects a number: " + raw);
    }
    double d = Double.parseDouble(s);
    if (!Double.isFinite(d)) {
      throw refused("Field " + name + " expects a finite number: " + raw);
    }
    return d;
  }

  private static Bson.Decimal128 parseDecimal(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!DECIMAL.matcher(s).matches()) {
      throw refused("Field " + name + " expects a decimal number: " + raw);
    }
    try {
      new BigDecimal(s);
      return new Bson.Decimal128(s);
    } catch (NumberFormatException _) {
      throw refused("Field " + name + " expects a decimal number: " + raw);
    }
  }

  private static Instant parseDate(String name, String raw, ZoneId zone) throws QueryException {
    return Rows.parseMoment(name, raw, zone).toInstant();
  }

  private static Bson.ObjectId parseObjectId(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (s.length() == 24) {
      try {
        return Bson.ObjectId.of(s);
      } catch (IllegalArgumentException _) {
        // Fall through to refusal.
      }
    } else if (s.startsWith("ObjectId(")) {
      try {
        if (Shell.parse(s) instanceof Bson.ObjectId oid) {
          return oid;
        }
      } catch (SyntaxException _) {
        // Fall through to refusal.
      }
    }
    throw refused("Field " + name + " expects an ObjectId (24 hexadecimal digits): " + raw);
  }

  private static String escapeRegex(String value) {
    var out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (REGEX_META.indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  private static QueryException refused(String detail) {
    return QueryException.of(QueryException.Failure.CLAUSE_REFUSED, detail);
  }
}

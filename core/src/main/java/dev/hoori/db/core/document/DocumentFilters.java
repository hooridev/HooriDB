package dev.hoori.db.core.document;

import dev.hoori.db.core.data.ColumnFilter;
import dev.hoori.db.core.data.Condition;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Rows;
import dev.hoori.db.core.query.QueryException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
  private static final Set<String> NUMERIC_TYPES = Set.of("int", "long", "double", "decimal");

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
   * The condition builder operators offered for {@code field}'s sampled type, in display order,
   * including {@code Exists} and {@code Does not exist}. Empty for paths that cannot be filtered.
   */
  public static List<ColumnFilter.Operator> operators(Field field) {
    Objects.requireNonNull(field, "field");
    if (!validPath(field.name())) {
      return List.of();
    }
    return switch (valueType(field.type())) {
      case STRING ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.CONTAINS,
            ColumnFilter.Operator.STARTS_WITH,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST);
      case INT, LONG, DOUBLE, DECIMAL, DATE, OBJECT_ID ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.AT_LEAST,
            ColumnFilter.Operator.AT_MOST,
            ColumnFilter.Operator.BETWEEN,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST);
      case BOOL ->
        List.of(
            ColumnFilter.Operator.EQUALS,
            ColumnFilter.Operator.NOT_EQUAL,
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST);
      case UNSUPPORTED ->
        List.of(
            ColumnFilter.Operator.IS_NULL,
            ColumnFilter.Operator.IS_NOT_NULL,
            ColumnFilter.Operator.EXISTS,
            ColumnFilter.Operator.DOES_NOT_EXIST);
    };
  }

  /**
   * A filter document for the given column filters, with one entry per filter (or {@code $and}
   * where two filters name the same field).
   *
   * @throws QueryException {@link QueryException.Failure#CLAUSE_REFUSED} when a field cannot be
   *     filtered or a value is invalid for its sampled type
   */
  public static Document filter(List<ColumnFilter> filters, ZoneId zone) throws QueryException {
    Objects.requireNonNull(filters, "filters");
    return filter(Condition.Group.and(filters), zone);
  }

  /**
   * A filter document for {@code condition}, using flat fields for an {@code AND} group of
   * distinct fields and {@code $and}/{@code $or} for disjunctions, nested groups, or repeated
   * fields.
   *
   * @throws QueryException {@link QueryException.Failure#CLAUSE_REFUSED} when a field cannot be
   *     filtered or a value is invalid for its sampled type
   */
  public static Document filter(Condition condition, ZoneId zone) throws QueryException {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(zone, "zone");
    return switch (condition) {
      case ColumnFilter f -> new Document(List.of(entry(f, zone)));
      case Condition.Group(boolean any, var children) -> group(any, children, zone);
    };
  }

  private static Document group(boolean any, List<Condition> children, ZoneId zone)
      throws QueryException {
    if (children.isEmpty()) {
      return Document.EMPTY;
    }
    if (!any && flatDistinct(children)) {
      var entries = new ArrayList<Document.Entry>(children.size());
      for (var child : children) {
        entries.add(entry((ColumnFilter) child, zone));
      }
      return new Document(entries);
    }
    var parts = new ArrayList<Document>(children.size());
    for (var child : children) {
      var doc = filter(child, zone);
      if (!doc.isEmpty()) {
        parts.add(doc);
      }
    }
    if (parts.isEmpty()) {
      return Document.EMPTY;
    }
    if (parts.size() == 1) {
      return parts.getFirst();
    }
    return Document.of(any ? "$or" : "$and", Document.array(parts));
  }

  private static boolean flatDistinct(List<Condition> children) {
    var seen = new HashSet<String>(children.size());
    for (var child : children) {
      if (!(child instanceof ColumnFilter f) || !seen.add(f.field().name())) {
        return false;
      }
    }
    return true;
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
    return filterText(base, Condition.Group.and(filters), zone);
  }

  /**
   * Shell filter text combining optional hand-written {@code base} text with {@code condition}
   * using {@code $and}.
   *
   * @throws QueryException {@link QueryException.Failure#CLAUSE_REFUSED} when {@code base} is not a
   *     valid shell document or any filter is invalid
   */
  public static String filterText(String base, Condition condition, ZoneId zone)
      throws QueryException {
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(zone, "zone");
    var trimmed = base == null ? "" : base.strip();
    var added = filter(condition, zone);
    if (added.isEmpty()) {
      return trimmed;
    }
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
    var joined = "{ $and: [ " + trimmed + ", " + Shell.compact(added) + " ] }";
    try {
      Shell.document(joined);
      return joined;
    } catch (SyntaxException _) {
      // A line comment at the end took in what follows; a line break ends it.
      return "{ $and: [ " + trimmed + "\n, " + Shell.compact(added) + " ] }";
    }
  }

  /** Whether {@code name} is a valid MongoDB field path (dot-separated segments, none empty or $). */
  private static boolean validPath(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    for (var segment : name.split("\\.", -1)) {
      if (segment.isEmpty() || segment.startsWith("$")) {
        return false;
      }
    }
    return true;
  }

  private static Document.Entry entry(ColumnFilter filter, ZoneId zone) throws QueryException {
    var field = filter.field();
    var name = field.name();
    if (!validPath(name)) {
      throw refused(name, "cannot be filtered");
    }
    if (filter.spec() instanceof ColumnFilter.Null(boolean isNull)) {
      // A field a document lacks shows as an empty cell, not as NULL.
      var isNullType = Document.of("$type", "null");
      return new Document.Entry(name, isNull ? isNullType : Document.of("$not", isNullType));
    }
    if (filter.spec() instanceof ColumnFilter.Exists(boolean exists)) {
      return new Document.Entry(name, Document.of("$exists", exists));
    }
    if (filter.spec() instanceof ColumnFilter.Not(var inner)) {
      return negate(field, inner, zone);
    }
    var type = valueType(field.type());
    return switch (filter.spec()) {
      case ColumnFilter.Null _, ColumnFilter.Exists _, ColumnFilter.Not _ ->
        throw new AssertionError();
      case ColumnFilter.Text(var match, var value) -> textEntry(field, type, match, value, zone);
      case ColumnFilter.Regex(var expression) -> {
        if (type != ValueType.STRING) {
          throw refused(name, "does not support regular expression matching");
        }
        if (expression.isEmpty()) {
          throw refused("Regular expression for " + name + " cannot be empty");
        }
        yield new Document.Entry(name, regex(expression));
      }
      case ColumnFilter.Between(var from, var to) -> betweenEntry(field, type, from, to, zone);
      case ColumnFilter.OneOf(var values) -> oneOfEntry(field, type, values, zone);
    };
  }

  private static Document.Entry negate(Field field, ColumnFilter.Spec inner, ZoneId zone)
      throws QueryException {
    if (inner instanceof ColumnFilter.Null(boolean isNull)) {
      return entry(new ColumnFilter(field, new ColumnFilter.Null(!isNull)), zone);
    }
    if (inner instanceof ColumnFilter.Exists(boolean exists)) {
      return entry(new ColumnFilter(field, new ColumnFilter.Exists(!exists)), zone);
    }
    if (inner instanceof ColumnFilter.Not(var nested)) {
      return entry(new ColumnFilter(field, nested), zone);
    }
    var positive = entry(new ColumnFilter(field, inner), zone).value();
    if (positive instanceof Document doc && doc.has("$regex")) {
      var pattern = (String) doc.get("$regex");
      var options = doc.get("$options") instanceof String o ? o : "";
      // MongoDB requires a BSON RegExp inside $not, not { $regex, $options }.
      return new Document.Entry(
          field.name(), Document.of("$not", new Bson.Regex(pattern, options)));
    }
    if (positive instanceof Document doc && !doc.isEmpty() && doc.first().startsWith("$")) {
      return new Document.Entry(field.name(), Document.of("$not", doc));
    }
    return new Document.Entry(field.name(), Document.of("$ne", positive));
  }

  private static Document.Entry textEntry(
      Field field, ValueType type, ColumnFilter.Match match, String value, ZoneId zone)
      throws QueryException {
    var name = field.name();
    if (match == ColumnFilter.Match.EQUALS || match == ColumnFilter.Match.EXACTLY) {
      if (type == ValueType.STRING && match == ColumnFilter.Match.EQUALS) {
        return new Document.Entry(name, regex("^" + escapeRegex(value) + "\\z"));
      }
      return new Document.Entry(name, parseValue(field, type, value, zone));
    }
    if (type != ValueType.STRING) {
      throw refused(
          name, "of type " + field.type() + " does not support text matching; use Equals");
    }
    var pattern =
        match == ColumnFilter.Match.STARTS_WITH ? "^" + escapeRegex(value) : escapeRegex(value);
    return new Document.Entry(name, regex(pattern));
  }

  private static Document.Entry betweenEntry(
      Field field, ValueType type, String from, String to, ZoneId zone) throws QueryException {
    var name = field.name();
    if (type == ValueType.UNSUPPORTED || type == ValueType.BOOL) {
      throw refused(name, "does not support range filters");
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
      throw refused(field.name(), "does not support value list filters");
    }
    var parsed = new ArrayList<Object>(values.size());
    for (var v : values) {
      parsed.add(parseValue(field, type, v, zone));
    }
    return new Document.Entry(field.name(), Document.of("$in", Document.array(parsed)));
  }

  // The sampled types as Values names them, such as "int | null": one type, numbers of any
  // width, or text among others.
  private static ValueType valueType(String sampled) {
    var types = new HashSet<String>();
    for (var part : (sampled == null ? "" : sampled).split("\\|")) {
      var type = part.strip();
      if (!type.isEmpty() && !type.equals("null")) {
        types.add(type);
      }
    }
    if (types.isEmpty() || types.contains("string")) {
      return ValueType.STRING;
    }
    if (NUMERIC_TYPES.containsAll(types)) {
      return widest(types);
    }
    return types.size() > 1
        ? ValueType.UNSUPPORTED
        : switch (types.iterator().next()) {
          case "objectId" -> ValueType.OBJECT_ID;
          case "bool" -> ValueType.BOOL;
          case "date" -> ValueType.DATE;
          default -> ValueType.UNSUPPORTED;
        };
  }

  // The widest of number types, so that every sampled value fits.
  private static ValueType widest(Set<String> numbers) {
    if (numbers.contains("double")) {
      return ValueType.DOUBLE;
    }
    if (numbers.contains("decimal")) {
      return ValueType.DECIMAL;
    }
    return numbers.contains("long") ? ValueType.LONG : ValueType.INT;
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
        throw refused(name, "of type " + field.type() + " only supports Is NULL and Is not NULL");
    };
  }

  private static Object parseInt(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!INTEGER.matcher(s).matches()) {
      throw refused(name, "expects a whole number: " + raw);
    }
    var n = new BigInteger(s);
    if (n.compareTo(MIN_INT) >= 0 && n.compareTo(MAX_INT) <= 0) {
      return n.intValueExact();
    }
    if (n.compareTo(MIN_LONG) >= 0 && n.compareTo(MAX_LONG) <= 0) {
      return n.longValueExact();
    }
    throw refused(name, "number is out of 64-bit range: " + raw);
  }

  private static Long parseLong(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!INTEGER.matcher(s).matches()) {
      throw refused(name, "expects a whole number: " + raw);
    }
    var n = new BigInteger(s);
    if (n.compareTo(MIN_LONG) < 0 || n.compareTo(MAX_LONG) > 0) {
      throw refused(name, "number is out of 64-bit range: " + raw);
    }
    return n.longValueExact();
  }

  private static Double parseDouble(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!DECIMAL.matcher(s).matches()) {
      throw refused(name, "expects a number: " + raw);
    }
    double d = Double.parseDouble(s);
    if (!Double.isFinite(d)) {
      throw refused(name, "expects a finite number: " + raw);
    }
    return d;
  }

  private static Bson.Decimal128 parseDecimal(String name, String raw) throws QueryException {
    var s = raw.strip();
    if (!DECIMAL.matcher(s).matches()) {
      throw refused(name, "expects a decimal number: " + raw);
    }
    BigDecimal number;
    try {
      number = new BigDecimal(s);
    } catch (NumberFormatException _) {
      throw refused(name, "expects a decimal number: " + raw);
    }
    // A Decimal128 keeps 34 digits, and BSON refuses to round.
    try {
      if (number.signum() != 0 && number.stripTrailingZeros().precision() > 34) {
        throw refused(name, "takes at most 34 significant digits: " + raw);
      }
    } catch (ArithmeticException _) {
      throw refused(name, "expects a decimal number: " + raw);
    }
    return new Bson.Decimal128(s);
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
    throw refused(name, "expects an ObjectId (24 hexadecimal digits): " + raw);
  }

  /** The text as a pattern of the server's that matches it as it is, each sign of patterns escaped. */
  public static String escapeRegex(String value) {
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

  private static QueryException refused(String field, String detail) {
    return refused("Field " + field + " " + detail);
  }

  private static Document regex(String pattern) {
    return Document.of("$regex", pattern, "$options", "i");
  }
}

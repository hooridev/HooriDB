package dev.hoori.db.core.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The values of documents as the MongoDB shell writes them: {@code { _id: ObjectId('…'), name:
 * 'Ada', born: ISODate('1815-12-10T00:00:00.000Z') }}. What it writes it reads back as the same
 * value, and it reads what people write in the shell besides: names and strings in either quotes,
 * {@code NumberLong(5)}, {@code new Date(…)}, regular expressions as {@code /a.c/i}, comments, a
 * comma after the last field. A whole number that fits 32 bits is an {@code Integer}, a larger one
 * a {@code Long}, and one with a point or an exponent a {@code Double}.
 */
public final class Shell {

  /**
   * The names of the constructors of values that the shell takes, such as {@code ObjectId} and
   * {@code Binary.createFromBase64}.
   */
  public static final Set<String> CONSTRUCTORS = Set.copyOf(Reader.MAKERS.keySet());

  /** Those of them that completions offer: the common ones, without their aliases. */
  public static final List<String> OFFERED = Reader.MAKERS.entrySet().stream()
      .filter(e -> e.getValue().offered())
      .map(Map.Entry::getKey)
      .toList();

  /** Where {@link #pretty} writes a document or array on one line. */
  static final int LINE = 72;

  private static final String INFINITY = "Infinity";

  private static final Pattern NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Shell() {}

  /** The value on one line. */
  public static String compact(Object value) {
    var out = new StringBuilder();
    write(out, value, -1, 0);
    return out.toString();
  }

  /**
   * The value over lines, indented by two spaces a level; a document or array that fits {@link
   * #LINE} columns stays on one line.
   */
  public static String pretty(Object value) {
    var out = new StringBuilder();
    write(out, value, 0, 0);
    return out.toString();
  }

  /** A field's name as the shell writes it: bare where it can be, else quoted. */
  public static String name(String name) {
    return NAME.matcher(name).matches() ? name : quote(name);
  }

  /** The text in single quotes, with what needs it escaped. */
  // A literal where it reads back as the same pattern; else the constructor, as for an escaped
  // slash, which reads back as a slash, a line break, an empty pattern, which would be a comment,
  // or a backslash at the end.
  private static String regex(Bson.Regex r) {
    var p = r.pattern();
    int trailing = 0;
    while (trailing < p.length() && p.charAt(p.length() - 1 - trailing) == '\\') {
      trailing++;
    }
    if (p.isEmpty()
        || p.contains("\\/")
        || p.indexOf('\n') >= 0
        || p.indexOf('\r') >= 0
        || trailing % 2 == 1) {
      return "BSONRegExp(" + quote(p) + ", " + quote(r.options()) + ")";
    }
    return "/" + p.replace("/", "\\/") + "/" + r.options();
  }

  public static String quote(String text) {
    var out = new StringBuilder(text.length() + 2).append('\'');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\'' -> out.append("\\'");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c == 0x7f || c == '\u2028' || c == '\u2029') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('\'').toString();
  }

  // indent < 0: on one line.
  private static void write(StringBuilder out, Object value, int indent, int column) {
    switch (value) {
      case Document d -> {
        var items = d.entries().stream()
            .map(entry -> new Item(name(entry.name()) + ": ", entry.value()))
            .toList();
        nested(out, d, "{}", items, indent, column);
      }
      case List<?> list -> {
        var items = list.stream().map(item -> new Item("", item)).toList();
        nested(out, list, "[]", items, indent, column);
      }
      case null, default -> out.append(scalar(value));
    }
  }

  // A field of a document, as its name and a colon before its value, or a value of an array.
  private record Item(String head, Object value) {}

  // A document or an array, in its brackets: on one line where it fits, or else each item on a
  // line of its own.
  private static void nested(
      StringBuilder out, Object value, String brackets, List<Item> items, int indent, int column) {
    if (items.isEmpty()) {
      out.append(brackets);
      return;
    }
    if (indent < 0) {
      out.append(brackets.charAt(0)).append(' ');
      for (int i = 0; i < items.size(); i++) {
        out.append(items.get(i).head());
        write(out, items.get(i).value(), -1, 0);
        out.append(i < items.size() - 1 ? ", " : " ");
      }
      out.append(brackets.charAt(1));
      return;
    }
    var line = compact(value);
    if (column + line.length() <= LINE) {
      out.append(line);
      return;
    }
    out.append(brackets.charAt(0)).append('\n');
    for (int i = 0; i < items.size(); i++) {
      var head = "  ".repeat(indent + 1) + items.get(i).head();
      out.append(head);
      write(out, items.get(i).value(), indent + 1, head.length());
      out.append(i < items.size() - 1 ? ",\n" : "\n");
    }
    out.append("  ".repeat(indent)).append(brackets.charAt(1));
  }

  /** A value that is not a document or an array, as the shell writes it. */
  public static String scalar(Object value) {
    return switch (value) {
      case null -> "null";
      case Boolean b -> b.toString();
      case Integer n -> n.toString();
      case Long n -> "Long('" + n + "')";
      case Double n -> floating(n);
      case Bson.Decimal128 n -> "Decimal128('" + n.text() + "')";
      case String s -> quote(s);
      case Instant i -> "ISODate('" + date(i) + "')";
      case Bson.ObjectId id -> "ObjectId('" + id.hex() + "')";
      case Bson.Binary b ->
        b.uuid() != null
            ? "UUID('" + b.uuid() + "')"
            : "Binary.createFromBase64('" + Base64.getEncoder().encodeToString(b.bytes()) + "', "
                + b.subtype() + ")";
      case Bson.Timestamp(var seconds, var increment) ->
        "Timestamp({ t: " + seconds + ", i: " + increment + " })";
      case Bson.Regex r -> regex(r);
      case Bson.Code(var code, var scope) ->
        scope == null
            ? "Code(" + quote(code) + ")"
            : "Code(" + quote(code) + ", " + compact(scope) + ")";
      case Bson.Symbol(var symbol) -> "Symbol(" + quote(symbol) + ")";
      case Bson.Special s ->
        switch (s) {
          case MIN_KEY -> "MinKey()";
          case MAX_KEY -> "MaxKey()";
          case UNDEFINED -> "undefined";
        };
      default -> compact(value);
    };
  }

  /** A date as ISO 8601 in UTC with milliseconds, as the server keeps it. */
  static String date(Instant instant) {
    var truncated = instant.truncatedTo(ChronoUnit.MILLIS);
    int year = truncated.atOffset(ZoneOffset.UTC).getYear();
    if (year < 0 || year > 9999) {
      return truncated.toString();
    }
    return MILLIS.format(truncated);
  }

  // With a point, so that it reads back as a Double and not as an Integer.
  private static String floating(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? INFINITY : "-" + INFINITY;
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return (value == 0 && 1 / value < 0 ? "-" : "") + (long) value + ".0";
    }
    return Double.toString(value).replace("E", "e");
  }

  /** Reads one value, and nothing but space and comments around it. */
  public static Object parse(String text) throws SyntaxException {
    var reader = new Reader(text, 0);
    var value = reader.value();
    reader.space();
    if (!reader.end()) {
      throw reader.error("Nothing more was expected here");
    }
    return value;
  }

  /** Reads a document, and nothing but space and comments around it. */
  public static Document document(String text) throws SyntaxException {
    var reader = new Reader(text, 0);
    reader.space();
    int at = reader.at;
    var value = parse(text);
    if (!(value instanceof Document d)) {
      throw new SyntaxException("A document in braces is needed: { … }", at);
    }
    return d;
  }

  /** Reads the values of a text, one after another. Used by {@link Script}. */
  static final class Reader {

    private static final int MAX_NESTING = 200;

    final String text;
    int at;
    private int nesting;

    Reader(String text, int at) {
      this.text = text;
      this.at = at;
    }

    boolean end() {
      return at >= text.length();
    }

    char peek() {
      return at < text.length() ? text.charAt(at) : '\0';
    }

    SyntaxException error(String message) {
      return new SyntaxException(message, Math.min(at, text.length()));
    }

    /** Skips spaces, line breaks and comments. */
    void space() throws SyntaxException {
      while (at < text.length()) {
        if (Lexer.whitespace(text.charAt(at))) {
          at++;
          continue;
        }
        if (!text.startsWith("//", at) && !text.startsWith("/*", at)) {
          return;
        }
        var token = Lexer.token(text, at, false);
        if (token.open() && text.startsWith("/*", at)) {
          throw error("The comment is not closed: */ is missing");
        }
        at = token.end();
      }
    }

    /** Whether the next character, after space, is c; takes it if so. */
    boolean take(char c) throws SyntaxException {
      space();
      if (peek() == c) {
        at++;
        return true;
      }
      return false;
    }

    void expect(char c, String what) throws SyntaxException {
      if (!take(c)) {
        throw error(what);
      }
    }

    /** A name as JavaScript writes one, or null where none starts here. */
    String identifier() throws SyntaxException {
      space();
      int start = at;
      if (at < text.length() && Character.isJavaIdentifierStart(text.charAt(at))) {
        at++;
        while (at < text.length() && Character.isJavaIdentifierPart(text.charAt(at))) {
          at++;
        }
        return text.substring(start, at);
      }
      return null;
    }

    @FunctionalInterface
    interface Step<T> {
      T run() throws SyntaxException;
    }

    <T> T nested(String what, int offset, Step<T> step) throws SyntaxException {
      if (nesting >= MAX_NESTING) {
        throw new SyntaxException(
            what + " cannot be nested more than " + MAX_NESTING + " levels deep",
            Math.min(offset, text.length()),
            true);
      }
      nesting++;
      try {
        return step.run();
      } finally {
        nesting--;
      }
    }

    Object value() throws SyntaxException {
      space();
      if (end()) {
        throw error("A value is missing");
      }
      char c = peek();
      int start = at;
      return switch (c) {
        case '{' -> nested("A value", start, this::object);
        case '[' -> nested("A value", start, this::array);
        case '\'', '"' -> string();
        case '/' -> regex();
        default -> {
          if (c == '-' || c == '+' || c == '.' || c >= '0' && c <= '9') {
            yield number();
          }
          var name = identifier();
          if (name == null) {
            throw error("A value is needed here: a document, an array, a string or a number");
          }
          yield named(name, start);
        }
      };
    }

    Document object() throws SyntaxException {
      expect('{', "{ is missing");
      var entries = new ArrayList<Document.Entry>();
      var positions = new HashMap<String, Integer>();
      while (true) {
        space();
        if (take('}')) {
          return new Document(entries);
        }
        var name = fieldName();
        expect(':', ": is missing after the name " + name);
        var entry = new Document.Entry(name, value());
        // As in JavaScript, a name given twice keeps its first place and its last value.
        var previous = positions.putIfAbsent(name, entries.size());
        if (previous == null) {
          entries.add(entry);
        } else {
          entries.set(previous, entry);
        }
        if (!take(',')) {
          space();
          if (!take('}')) {
            throw error("A comma or } is missing");
          }
          return new Document(entries);
        }
      }
    }

    // A name in quotes, of digits alone, or as JavaScript writes one.
    private String fieldName() throws SyntaxException {
      char c = peek();
      if (c == '\'' || c == '"') {
        return string();
      }
      if (c >= '0' && c <= '9') {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at))) {
          at++;
        }
        return text.substring(start, at);
      }
      var name = identifier();
      if (name == null) {
        throw error("A field's name is needed here");
      }
      return name;
    }

    List<Object> array() throws SyntaxException {
      expect('[', "[ is missing");
      var values = new ArrayList<Object>();
      while (true) {
        if (take(']')) {
          return Document.array(values);
        }
        values.add(value());
        if (!take(',')) {
          if (!take(']')) {
            throw error("A comma or ] is missing");
          }
          return Document.array(values);
        }
      }
    }

    String string() throws SyntaxException {
      space();
      char quote = peek();
      if (quote != '\'' && quote != '"') {
        throw error("A string in quotes is needed here");
      }
      var token = Lexer.token(text, at, false);
      if (token.open()) {
        if (token.end() == text.length()) {
          throw error("The string is not closed: " + quote + " is missing");
        }
        at = token.end();
        throw error("The string is not closed on its line: " + quote + " is missing");
      }
      at++;
      var out = new StringBuilder();
      while (true) {
        char c = text.charAt(at++);
        if (c == quote) {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        escape(out);
      }
    }

    // The character after a backslash, and what it stands for.
    private void escape(StringBuilder out) throws SyntaxException {
      char e = text.charAt(at++);
      switch (e) {
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'v' -> out.append('\u000b');
        case '0' -> out.append('\0');
        case '\n' -> {
          // A line continued: the break is not in the string.
        }
        case 'u' -> out.append(escaped(4, "\\u needs four hexadecimal digits"));
        case 'x' -> out.append(escaped(2, "\\x needs two hexadecimal digits"));
        default -> out.append(e);
      }
    }

    // The character of the hexadecimal digits that follow.
    private char escaped(int digits, String missing) throws SyntaxException {
      if (at + digits > text.length()) {
        throw error(missing);
      }
      try {
        char c = (char) Integer.parseInt(text.substring(at, at + digits), 16);
        at += digits;
        return c;
      } catch (NumberFormatException _) {
        throw error(missing);
      }
    }

    Bson.Regex regex() throws SyntaxException {
      var token = Lexer.token(text, at, false);
      if (token.open()) {
        throw error("The regular expression is not closed: / is missing");
      }
      int options = token.end();
      while (Character.isLetter(text.charAt(options - 1))) {
        options--;
      }
      var pattern = new StringBuilder();
      int i = at + 1;
      while (i < options - 1) {
        char c = text.charAt(i++);
        if (c == '\\') {
          char next = text.charAt(i++); // \/ is the slash itself; any other escape stays as written
          pattern.append(next == '/' ? "/" : "\\" + next);
        } else {
          pattern.append(c);
        }
      }
      at = token.end();
      return new Bson.Regex(pattern.toString(), sorted(text.substring(options, at)));
    }

    Object number() throws SyntaxException {
      int start = at;
      boolean negative = false;
      if (peek() == '-' || peek() == '+') {
        negative = peek() == '-';
        at++;
        space();
      }
      if (Character.isLetter(peek())) {
        return namedNumber(start, negative);
      }
      if (text.startsWith("0x", at) || text.startsWith("0X", at)) {
        return hexadecimal(negative);
      }
      return decimal(start, negative);
    }

    // Infinity or NaN, after a sign.
    private Object namedNumber(int start, boolean negative) throws SyntaxException {
      var name = identifier();
      if (name.equals(INFINITY)) {
        return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
      }
      if (name.equals("NaN")) {
        return Double.NaN;
      }
      at = start;
      throw error("A number is needed here");
    }

    // 0x and up to 64 bits of hexadecimal digits.
    private Object hexadecimal(boolean negative) throws SyntaxException {
      int digits = at;
      at += 2;
      int hex = at;
      while (at < text.length() && HexFormat.isHexDigit(text.charAt(at))) {
        at++;
      }
      if (at == hex) {
        throw error("Hexadecimal digits are missing after 0x");
      }
      try {
        long value = Long.parseUnsignedLong(text.substring(hex, at), 16);
        return whole(negative ? -value : value);
      } catch (NumberFormatException _) {
        at = digits;
        throw error("The number is too large for 64 bits");
      }
    }

    // Digits, with a point or an exponent a double, without them a whole number.
    private Object decimal(int start, boolean negative) throws SyntaxException {
      int digits = at;
      boolean fraction = digits(digits);
      var written = text.substring(digits, at).replace("_", "");
      if (written.isEmpty() || written.equals(".")) {
        at = start;
        throw error("A number is needed here");
      }
      if (!end() && Character.isJavaIdentifierStart(peek())) {
        throw error("A number cannot run into a name");
      }
      try {
        if (fraction) {
          double value = Double.parseDouble(written);
          return negative ? -value : value;
        }
        return whole(written, negative);
      } catch (NumberFormatException _) {
        at = start;
        throw error("Not a number");
      }
    }

    // Goes over the digits, a point and an exponent; whether it met either.
    private boolean digits(int digits) {
      boolean fraction = false;
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c >= '0' && c <= '9' || c == '_') {
          at++;
        } else if (c == '.' && !fraction) {
          fraction = true;
          at++;
        } else if ((c == 'e' || c == 'E') && at > digits) {
          fraction = true;
          at++;
          if (peek() == '-' || peek() == '+') {
            at++;
          }
        } else {
          return fraction;
        }
      }
      return fraction;
    }

    // A whole number as an int or a long where it fits, or else as a double.
    private static Object whole(String written, boolean negative) {
      var whole = new BigDecimal(written);
      if (negative) {
        whole = whole.negate();
      }
      try {
        return whole(whole.longValueExact());
      } catch (ArithmeticException _) {
        return whole.doubleValue();
      }
    }

    private static Object whole(long value) {
      return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
          ? (Object) (int) value
          : value;
    }

    /** What a name begins: a constant, or a constructor such as ObjectId('…'). */
    Object named(String name, int start) throws SyntaxException {
      return switch (name) {
        case "true" -> true;
        case "false" -> false;
        case "null" -> null;
        case "undefined" -> Bson.Special.UNDEFINED;
        case "NaN" -> Double.NaN;
        case INFINITY -> Double.POSITIVE_INFINITY;
        case "new" -> {
          int typeAt = at;
          var type = identifier();
          if (type == null) {
            throw error("A type is needed after new, such as Date");
          }
          yield constructed(type, typeAt);
        }
        default -> constructed(name, start);
      };
    }

    private Object constructed(String name, int start) throws SyntaxException {
      var type = qualified(name);
      space();
      if (!take('(')) {
        return constant(type, start);
      }
      var starts = new ArrayList<Integer>();
      var args = nested("A value", start, () -> arguments(starts));
      var constructor = MAKERS.get(type);
      if (constructor == null) {
        throw new SyntaxException("Unknown name " + type + ": a value is needed here", start);
      }
      try {
        return constructor.maker().make(args);
      } catch (IllegalArgumentException | DateTimeParseException e) {
        var message = e.getMessage() == null ? "Not a valid " + type : e.getMessage();
        throw new SyntaxException(message, starts.isEmpty() ? start : starts.getFirst());
      }
    }

    // Binary and BSON with the method after them, as Binary.createFromBase64.
    private String qualified(String type) throws SyntaxException {
      if (type.equals("Binary") || type.equals("BSON")) {
        space();
        if (take('.')) {
          return type + "." + identifier();
        }
      }
      return type;
    }

    // A type that is a value without parentheses.
    private Object constant(String type, int start) throws SyntaxException {
      return switch (type) {
        case "MinKey" -> Bson.Special.MIN_KEY;
        case "MaxKey" -> Bson.Special.MAX_KEY;
        default -> {
          at = start;
          throw error("Unknown name " + type + ": a value is needed here");
        }
      };
    }

    // The arguments up to the closing parenthesis, and where each starts.
    private List<Object> arguments(List<Integer> starts) throws SyntaxException {
      var args = new ArrayList<Object>();
      while (!take(')')) {
        space();
        starts.add(at);
        args.add(value());
        if (!take(',')) {
          if (!take(')')) {
            throw error("A comma or ) is missing");
          }
          break;
        }
      }
      return args;
    }

    /** Makes a value of what a constructor was given. */
    private interface Maker {
      Object make(List<Object> args);
    }

    /**
     * A constructor: how it makes its value, and whether completions offer it, as they do the
     * common ones and not their aliases, such as Long for NumberLong.
     */
    record Constructor(Maker maker, boolean offered) {}

    /** The constructors of values, by their names. */
    static final Map<String, Constructor> MAKERS = makers();

    private static void put(
        Map<String, Constructor> makers, String name, boolean offered, Maker maker) {
      makers.put(name, new Constructor(maker, offered));
    }

    private static Map<String, Constructor> makers() {
      var makers = new TreeMap<String, Constructor>();
      Maker date = args -> args.isEmpty() ? Instant.now() : instant(args.getFirst());
      Maker whole = args -> Long.parseLong(digits(args));
      Maker integer = args -> Integer.parseInt(digits(args));
      Maker decimal = args -> new Bson.Decimal128(digits(args));
      Maker regex =
          args -> new Bson.Regex(text(args, 0), args.size() > 1 ? sorted(text(args, 1)) : "");
      Maker symbol = args -> new Bson.Symbol(text(args, 0));
      put(
          makers,
          "ObjectId",
          true,
          args -> args.isEmpty() ? Bson.ObjectId.next() : Bson.ObjectId.of(text(args, 0)));
      put(makers, "ISODate", true, date);
      put(makers, "Date", false, date);
      put(makers, "NumberLong", true, whole);
      put(makers, "Long", false, whole);
      put(makers, "NumberInt", true, integer);
      put(makers, "Int32", false, integer);
      put(makers, "Double", false, args -> ((Number) number(args)).doubleValue());
      put(makers, "NumberDecimal", true, decimal);
      put(makers, "Decimal128", false, decimal);
      put(
          makers,
          "UUID",
          true,
          args -> args.isEmpty() ? Bson.Binary.of(UUID.randomUUID()) : uuid(text(args, 0)));
      put(makers, "BinData", false, Reader::binData);
      put(
          makers,
          "Binary.createFromBase64",
          false,
          args -> new Bson.Binary(subtypeAt(args, 1), base64(text(args, 0))));
      put(
          makers,
          "Binary.createFromHexString",
          false,
          args -> new Bson.Binary(subtypeAt(args, 1), HexFormat.of().parseHex(text(args, 0))));
      put(makers, "Timestamp", true, Reader::timestamp);
      put(makers, "MinKey", false, args -> Bson.Special.MIN_KEY);
      put(makers, "MaxKey", false, args -> Bson.Special.MAX_KEY);
      put(makers, "RegExp", false, regex);
      put(makers, "BSONRegExp", false, regex);
      put(makers, "Code", false, Reader::code);
      put(makers, "Symbol", false, symbol);
      put(makers, "BSONSymbol", false, symbol);
      return Collections.unmodifiableMap(makers);
    }

    private static Bson.Binary binData(List<Object> args) {
      if (args.size() != 2) {
        throw new IllegalArgumentException("BinData needs a subtype and a base64 string");
      }
      return new Bson.Binary(subtype(args.getFirst()), base64(text(args, 1)));
    }

    // The subtype at the index, or 0, the generic one, where none is given.
    private static int subtypeAt(List<Object> args, int index) {
      return args.size() > index ? subtype(args.get(index)) : 0;
    }

    private static Bson.Code code(List<Object> args) {
      var scope = args.size() > 1 ? args.get(1) : null;
      if (scope != null && !(scope instanceof Document)) {
        throw new IllegalArgumentException("The scope of Code is a document");
      }
      return new Bson.Code(text(args, 0), (Document) scope);
    }

    private static String text(List<Object> args, int index) {
      if (args.size() <= index || !(args.get(index) instanceof String s)) {
        throw new IllegalArgumentException("A string in quotes is needed");
      }
      return s;
    }

    private static Object number(List<Object> args) {
      if (args.isEmpty()) {
        throw new IllegalArgumentException("A number is needed");
      }
      var value = args.getFirst();
      if (value instanceof String s) {
        return Double.parseDouble(s.strip());
      }
      if (!(value instanceof Number)) {
        throw new IllegalArgumentException("A number is needed");
      }
      return value;
    }

    // A number written as itself or in quotes, as NumberLong('5') and NumberLong(5).
    private static String digits(List<Object> args) {
      if (args.isEmpty()) {
        return "0";
      }
      return switch (args.getFirst()) {
        case String s -> s.strip();
        case Integer n -> n.toString();
        case Long n -> n.toString();
        case Double n -> {
          if (n != Math.rint(n) || Double.isInfinite(n)) {
            yield BigDecimal.valueOf(n).toPlainString();
          }
          yield BigDecimal.valueOf(n).toBigInteger().toString();
        }
        case null, default -> throw new IllegalArgumentException("A number is needed");
      };
    }

    private static int subtype(Object value) {
      if (!(value instanceof Integer n)) {
        throw new IllegalArgumentException("A subtype is a number from 0 to 255");
      }
      return n;
    }

    private static byte[] base64(String text) {
      return Base64.getDecoder().decode(text.strip());
    }

    private static Bson.Binary uuid(String text) {
      var hex = text.replace("-", "");
      if (hex.length() != 32) {
        throw new IllegalArgumentException("A UUID has 32 hexadecimal digits");
      }
      return new Bson.Binary(Bson.Binary.SUBTYPE_UUID, HexFormat.of().parseHex(hex));
    }

    private static Bson.Timestamp timestamp(List<Object> args) {
      if (args.size() == 1 && args.getFirst() instanceof Document d) {
        return new Bson.Timestamp(unsigned(d.get("t")), unsigned(d.get("i")));
      }
      if (args.size() == 2) {
        return new Bson.Timestamp(unsigned(args.get(0)), unsigned(args.get(1)));
      }
      throw new IllegalArgumentException("Timestamp needs { t: seconds, i: increment }");
    }

    private static long unsigned(Object value) {
      return switch (value) {
        case Integer n -> n;
        case Long n -> n;
        case null, default -> throw new IllegalArgumentException("A whole number is needed");
      };
    }

    private static Instant instant(Object value) {
      return switch (value) {
        case Integer n -> Instant.ofEpochMilli(n);
        case Long n -> Instant.ofEpochMilli(n);
        case Double n -> Instant.ofEpochMilli(n.longValue());
        case String s -> instant(s.strip());
        case null, default -> throw new IllegalArgumentException("A date in quotes is needed");
      };
    }

    // ISO 8601 as the shell takes it: a date alone, or with a time, and an offset or UTC.
    static Instant instant(String text) {
      if (text.length() == 10) {
        return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
      }
      var iso = text.replace(' ', 'T');
      try {
        return OffsetDateTime.parse(iso).toInstant();
      } catch (DateTimeParseException _) { // without an offset: UTC
        return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
      }
    }

    // The options of a regular expression in order, as the server keeps them.
    private static String sorted(String options) {
      var chars = options.toCharArray();
      java.util.Arrays.sort(chars);
      return new String(chars);
    }
  }
}

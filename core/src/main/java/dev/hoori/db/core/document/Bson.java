package dev.hoori.db.core.document;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** The values of a {@link Document} that Java has no type of its own for. */
public final class Bson {

  private Bson() {}

  /** The 12 bytes that identify a document, written as 24 hexadecimal digits in lower case. */
  public record ObjectId(String hex) implements Comparable<ObjectId> {

    private static final Pattern DIGITS = Pattern.compile("[0-9a-f]{24}");
    private static final byte[] PROCESS = new byte[5];
    private static final AtomicInteger COUNTER;

    static {
      var random = new SecureRandom();
      random.nextBytes(PROCESS);
      COUNTER = new AtomicInteger(random.nextInt());
    }

    public ObjectId {
      if (!DIGITS.matcher(hex).matches()) {
        throw new IllegalArgumentException("Not an ObjectId: 24 hexadecimal digits are needed");
      }
    }

    /** One of the text, whose letters may be in either case. */
    public static ObjectId of(String text) {
      return new ObjectId(text.toLowerCase(java.util.Locale.ROOT));
    }

    /** One of the 12 bytes. */
    public static ObjectId of(byte[] bytes) {
      if (bytes.length != 12) {
        throw new IllegalArgumentException("An ObjectId has 12 bytes");
      }
      return new ObjectId(HexFormat.of().formatHex(bytes));
    }

    /** A new one, as a driver makes it: the time in seconds, this process, and a counter. */
    public static ObjectId next() {
      var buffer = ByteBuffer.allocate(12);
      buffer.putInt((int) Instant.now().getEpochSecond());
      buffer.put(PROCESS);
      int count = COUNTER.getAndIncrement();
      buffer.put((byte) (count >> 16)).put((byte) (count >> 8)).put((byte) count);
      return of(buffer.array());
    }

    public byte[] bytes() {
      return HexFormat.of().parseHex(hex);
    }

    /** When it was made, to the second. */
    public Instant time() {
      return Instant.ofEpochSecond(
          Integer.toUnsignedLong(ByteBuffer.wrap(bytes()).getInt()));
    }

    @Override
    public int compareTo(ObjectId other) {
      return hex.compareTo(other.hex);
    }
  }

  /** Binary data of a subtype, which says what it holds, such as 4 for a UUID. */
  public record Binary(int subtype, byte[] bytes) {

    public static final int GENERIC = 0;
    public static final int SUBTYPE_UUID = 4;

    public Binary {
      if (subtype < 0 || subtype > 255) {
        throw new IllegalArgumentException("A subtype is a byte");
      }
      bytes = bytes.clone();
    }

    /** A UUID, as subtype 4 holds it: its 16 bytes, most significant first. */
    public static Binary of(UUID uuid) {
      var buffer = ByteBuffer.allocate(16);
      buffer.putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
      return new Binary(SUBTYPE_UUID, buffer.array());
    }

    /** The UUID it holds, or null where it is not of subtype 4 with 16 bytes. */
    public UUID uuid() {
      if (subtype != SUBTYPE_UUID || bytes.length != 16) {
        return null;
      }
      var buffer = ByteBuffer.wrap(bytes);
      return new UUID(buffer.getLong(), buffer.getLong());
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    public int length() {
      return bytes.length;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Binary b && b.subtype == subtype && Arrays.equals(b.bytes, bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(bytes) + subtype;
    }

    @Override
    public String toString() {
      return "Binary[" + subtype + ", " + HexFormat.of().formatHex(bytes) + "]";
    }
  }

  /** A 128-bit decimal as the server writes it, such as {@code 1.50}, {@code NaN} or {@code -Infinity}. */
  public record Decimal128(String text) {

    private static final Pattern SPECIAL = Pattern.compile("-?Infinity|NaN");

    public Decimal128 {
      Objects.requireNonNull(text, "text");
      if (!SPECIAL.matcher(text).matches()) {
        new BigDecimal(text); // throws a NumberFormatException for anything else
      }
    }

    /** Its value, or null for NaN and the infinities. */
    public BigDecimal value() {
      return SPECIAL.matcher(text).matches() ? null : new BigDecimal(text);
    }
  }

  /** The server's own time of an operation: seconds since the epoch, and an ordinal within them. */
  public record Timestamp(long seconds, long increment) {
    public Timestamp {
      if (seconds < 0 || seconds > 0xffff_ffffL || increment < 0 || increment > 0xffff_ffffL) {
        throw new IllegalArgumentException("A timestamp's parts are unsigned 32-bit numbers");
      }
    }
  }

  /** A regular expression with its options, such as {@code i} for any case. */
  public record Regex(String pattern, String options) {
    public Regex {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(options, "options");
    }
  }

  /** JavaScript code, with the variables it is given, or null for none. */
  public record Code(String code, Document scope) {
    public Code {
      Objects.requireNonNull(code, "code");
    }
  }

  /** A symbol, a kind of string from older servers. */
  public record Symbol(String symbol) {
    public Symbol {
      Objects.requireNonNull(symbol, "symbol");
    }
  }

  /**
   * A pointer to a document of older servers: its collection's namespace and its {@code _id}. The
   * shell has no constructor of it, so its text does not read back.
   */
  public record DbPointer(String namespace, ObjectId id) {
    public DbPointer {
      Objects.requireNonNull(namespace, "namespace");
      Objects.requireNonNull(id, "id");
    }
  }

  /** The values that are a type of their own and nothing more. */
  public enum Special {
    /** Below every other value. */
    MIN_KEY,
    /** Above every other value. */
    MAX_KEY,
    /** The undefined value of older servers. */
    UNDEFINED
  }

  /**
   * The value as a document holds it, with an array copied, and the arrays in it, into lists that
   * cannot be changed; throws for anything that is not a value of a document, also in an array.
   */
  static Object value(Object value) {
    return switch (value) {
      case null -> null;
      case Boolean _,
          Integer _,
          Long _,
          Double _,
          Decimal128 _,
          String _,
          Instant _,
          ObjectId _,
          Binary _,
          Timestamp _,
          Regex _,
          Code _,
          Symbol _,
          DbPointer _,
          Special _,
          Document _,
          Array _ -> value; // an Array made by this method, of values it took
      case List<?> list -> Array.of(list);
      default ->
        throw new IllegalArgumentException(
            "Not a value of a document: " + value.getClass().getName());
    };
  }

  // An array as a document holds it: its values, checked, which nothing changes; null among them.
  static final class Array extends AbstractList<Object> implements RandomAccess {
    private final Object[] values;

    private Array(Object[] values) {
      this.values = values;
    }

    static Array of(List<?> list) {
      var values = list.toArray();
      for (int i = 0; i < values.length; i++) {
        values[i] = value(values[i]);
      }
      return new Array(values);
    }

    @Override
    public Object get(int index) {
      return values[index];
    }

    @Override
    public int size() {
      return values.length;
    }
  }

  /** The name of the value's type, as the server's {@code $type} gives it. */
  public static String type(Object value) {
    return switch (value) {
      case null -> "null";
      case Boolean _ -> "bool";
      case Integer _ -> "int";
      case Long _ -> "long";
      case Double _ -> "double";
      case Decimal128 _ -> "decimal";
      case String _ -> "string";
      case Instant _ -> "date";
      case ObjectId _ -> "objectId";
      case Binary _ -> "binData";
      case Timestamp _ -> "timestamp";
      case Regex _ -> "regex";
      case Code c -> c.scope() == null ? "javascript" : "javascriptWithScope";
      case Symbol _ -> "symbol";
      case DbPointer _ -> "dbPointer";
      case Special s ->
        switch (s) {
          case MIN_KEY -> "minKey";
          case MAX_KEY -> "maxKey";
          case UNDEFINED -> "undefined";
        };
      case Document _ -> "object";
      case List<?> _ -> "array";
      default -> throw new IllegalArgumentException("Not a value of a document");
    };
  }
}

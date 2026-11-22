package dev.hoori.db.app;

import com.rethinkj.ui.kit.DataGrid;
import com.rethinkj.ui.kit.DataGrid.CellIcon;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import dev.hoori.db.core.catalog.Column;
import dev.hoori.db.core.connection.Settings;
import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import dev.hoori.db.core.data.Field;
import dev.hoori.db.core.data.Kind;
import dev.hoori.db.core.data.Missing;
import dev.hoori.db.core.data.Rows;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.function.IntFunction;

/**
 * How the values of a table show in its grid: as a database writes them, not as a locale would.
 * Numbers without grouping and with all their digits, dates and times in ISO 8601's form with their
 * seconds, a moment in the computer's time zone with the offset it has there at that moment, NULL
 * as the grid's muted NULL, and a field a document does not have as an empty cell.
 */
final class Cells {

  /** How many bytes of binary data a cell shows, in hex. */
  static final int PREVIEW = 8;

  // The kit's Format writes a time without its seconds where they are 0, which no database does;
  // the fraction as far as it has digits, as PostgreSQL writes it.
  private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
      .appendPattern("HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
      .toFormatter();
  private static final DateTimeFormatter OFFSET = new DateTimeFormatterBuilder()
      .append(TIME)
      .appendOffset("+HH:mm:ss", "+00")
      .toFormatter();
  private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .appendLiteral(' ')
      .append(TIME)
      .toFormatter();
  private static final DateTimeFormatter DATE_TIME_OFFSET = new DateTimeFormatterBuilder()
      .append(DATE_TIME)
      .appendOffset("+HH:mm:ss", "+00")
      .toFormatter();

  private Cells() {}

  /** What copying a NULL cell puts on the clipboard. */
  static String nullText(Settings.CopyNull copyNull) {
    return switch (copyNull) {
      case EMPTY -> "";
      case NULL -> "NULL";
      case BACKSLASH_N -> "\\N";
    };
  }

  /** The grid's kind of column for core's kind of values. */
  static DataType type(Kind kind) {
    return switch (kind) {
      case TEXT -> DataType.TEXT;
      case INTEGER -> DataType.INTEGER;
      case DECIMAL -> DataType.DECIMAL;
      case BOOLEAN -> DataType.BOOLEAN;
      case DATE -> DataType.DATE;
      case TIME -> DataType.TIME;
      case DATE_TIME, DATE_TIME_ZONED -> DataType.DATE_TIME;
      case BINARY -> DataType.BINARY;
      case JSON -> DataType.JSON;
      case OTHER -> DataType.OTHER;
    };
  }

  /** A column's first width, in rows' heights, by what its values usually take. */
  static float width(Kind kind) {
    return switch (kind) {
      case BOOLEAN -> 4;
      case INTEGER, DATE, TIME -> 5;
      case DECIMAL -> 6;
      case DATE_TIME, DATE_TIME_ZONED, BINARY -> 9;
      case TEXT, JSON, OTHER -> 10;
    };
  }

  private record Header(String name, String type, Kind kind, boolean key) {}

  /**
   * A grid column for a table column, initializing its width from the kind when not yet set by the
   * user.
   */
  static DataGrid.Column column(
      Column column,
      Kind kind,
      float[] widths,
      int index,
      IntFunction<Object> valueAt,
      ZoneId zone) {
    return column(
        new Header(column.name(), column.type(), kind, column.keyPosition() > 0),
        widths,
        index,
        valueAt,
        zone);
  }

  /**
   * A grid column for a query field, initializing its width from the kind when not yet set by the
   * user.
   */
  static DataGrid.Column column(
      Field field, float[] widths, int index, IntFunction<Object> valueAt, ZoneId zone) {
    return column(
        new Header(field.name(), field.type(), field.kind(), false), widths, index, valueAt, zone);
  }

  private static DataGrid.Column column(
      Header header, float[] widths, int index, IntFunction<Object> valueAt, ZoneId zone) {
    Theme t = Theme.CURRENT.get();
    var kind = header.kind();
    var type = kind == null ? DataType.OTHER : type(kind);
    if (widths[index] < 0 && kind != null) {
      widths[index] = width(kind) * t.size().control();
    }
    float w = widths[index] < 0 ? width(Kind.DECIMAL) * t.size().control() : widths[index];
    var column = new DataGrid.Column(header.name(), w, type, row -> cell(valueAt.apply(row), zone))
        .typeName(header.type())
        .sortable(false);
    if (header.key()) {
      column = column.icon(Symbol.KEY, Icon.Variant.WARNING);
    }
    if (kind == Kind.TEXT || kind == Kind.JSON || kind == Kind.OTHER) {
      column = column.cellIcon(row -> icon(valueAt.apply(row)));
    }
    return column;
  }

  /**
   * The text a grid cell shows: at most {@link Rows#LIMIT} code points of a string, so that
   * drawing and fitting columns stay fast while {@link #text} keeps the whole value.
   */
  static String cell(Object value, ZoneId zone) {
    return value instanceof String s ? preview(s) : text(value, zone);
  }

  // shortcut: the grid shapes a cell's whole text (rethinkj/ui#414); drop this once it bounds it.
  private static String preview(String text) {
    if (text.length() <= Rows.LIMIT
        || text.length() <= 2 * Rows.LIMIT && text.codePointCount(0, text.length()) <= Rows.LIMIT) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, Rows.LIMIT));
  }

  /**
   * The text of a value as core read it, or null for NULL and empty for a field a document does not
   * have; a moment in the zone. A value that its
   * kind's type could not hold came as the server's own text, which shows as it is.
   */
  static String text(Object value, ZoneId zone) {
    return switch (value) {
      case null -> null;
      case Missing.FIELD -> "";
      case String s -> s;
      case Cut c -> c.start();
      case Long n -> n.toString();
      case BigInteger n -> n.toString();
      case BigDecimal n -> Format.DECIMAL.text(n);
      case Double n -> floating(n, Double.toString(n));
      case Float n -> floating(n, Float.toString(n));
      case Boolean b -> Format.BOOLEAN.text(b);
      case LocalDate d -> Format.DATE.text(d);
      case LocalTime t -> TIME.format(t);
      case OffsetTime t -> OFFSET.format(t);
      case LocalDateTime d -> DATE_TIME.format(d);
      case OffsetDateTime d -> DATE_TIME_OFFSET.format(d.atZoneSameInstant(zone));
      case Binary b -> binary(b);
      default -> value.toString();
    };
  }

  /** The icon of a value that is cut short, which says how long it is; null for any other. */
  static CellIcon icon(Object value) {
    if (!(value instanceof Cut c)) {
      return null;
    }
    var of = c.length().isPresent() ? " of " + Format.BYTES.text(c.length().getAsLong()) : "";
    return CellIcon.before(Symbol.MORE_HORIZONTAL, Icon.Variant.WARNING)
        .tooltip("Cut short: the first " + Rows.LIMIT + " characters" + of);
  }

  /** The start in hex, and the size. */
  private static String binary(Binary b) {
    var bytes = b.bytes();
    int shown = Math.min(bytes.length, PREVIEW);
    return "0x" + HexFormat.of().formatHex(bytes, 0, shown) + (b.length() > shown ? "…" : "") + " ("
        + Format.BYTES.text(b.length()) + ")";
  }

  /**
   * A floating point number with the digits that tell it apart, as {@code toString} finds them,
   * written as PostgreSQL does: plain from 0.0001 to below 10^15, else with an exponent, {@code
   * 1e+20}.
   */
  private static String floating(double value, String shortest) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? "Infinity" : "-Infinity";
    }
    var decimal = new BigDecimal(shortest).stripTrailingZeros();
    int exponent = decimal.precision() - decimal.scale() - 1;
    if (decimal.signum() == 0) {
      return Double.compare(value, 0.0) < 0 ? "-0" : "0";
    }
    if (exponent >= -4 && exponent < 15) {
      return decimal.toPlainString();
    }
    var digits = decimal.unscaledValue().abs().toString();
    return (decimal.signum() < 0 ? "-" : "")
        + digits.charAt(0)
        + (digits.length() > 1 ? "." + digits.substring(1) : "")
        + (exponent < 0 ? "e-" : "e+")
        + (Math.abs(exponent) < 10 ? "0" : "")
        + Math.abs(exponent);
  }
}

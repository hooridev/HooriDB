package dev.hoori.db.core.file;

import dev.hoori.db.core.data.Binary;
import dev.hoori.db.core.data.Cut;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The values of a table's rows as a file writes them: as the server gives them, a number in full
 * without an exponent but a floating point one's, a truth as {@code true} or {@code false}, a date
 * and a time in ISO 8601, a moment in ISO 8601 in UTC with its offset, {@code +00:00}, and binary
 * data in hex. A text that the server gave in place of a value of the kind, as a zero date, stays
 * as it is.
 */
final class Texts {

  private static final DateTimeFormatter MOMENT = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      .appendOffset("+HH:MM", "+00:00")
      .toFormatter(Locale.ROOT);

  private Texts() {}

  /**
   * The value's text; null for NULL.
   *
   * @throws IllegalArgumentException if it was read only in part, or is of no kind's type
   */
  static String of(Object value) {
    return switch (value) {
      case null -> null;
      case String text -> text;
      case Long number -> number.toString();
      case BigInteger number -> number.toString();
      case BigDecimal number -> number.toPlainString();
      case Float number -> number.toString();
      case Double number -> number.toString();
      case Boolean truth -> truth.toString();
      case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
      case LocalTime time -> DateTimeFormatter.ISO_LOCAL_TIME.format(time);
      case OffsetTime time -> DateTimeFormatter.ISO_OFFSET_TIME.format(time);
      case LocalDateTime moment -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(moment);
      case OffsetDateTime moment -> MOMENT.format(moment);
      case Binary binary when !binary.cut() -> HexFormat.of().formatHex(binary.bytes());
      case Binary _, Cut _ -> throw new IllegalArgumentException("A value read only in part");
      default ->
        throw new IllegalArgumentException(
            "A value of " + value.getClass().getName() + " cannot be written");
    };
  }
}

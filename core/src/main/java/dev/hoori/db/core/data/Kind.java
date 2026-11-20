package dev.hoori.db.core.data;

/**
 * What a column's values are, for how they are aligned and edited, and the Java type each kind's
 * values have. SQL NULL is {@code null}, apart from an empty text. Of {@link #DATE}, {@link #TIME},
 * {@link #DATE_TIME} and {@link #DATE_TIME_ZONED}, a value that the Java type cannot hold is the
 * server's own text, as a {@code String}: MariaDB's and MySQL's zero dates, such as {@code
 * 0000-00-00} or {@code 2024-00-15}, and their times beyond a day, such as {@code -01:00:00} or
 * {@code 838:59:59}, and PostgreSQL's {@code infinity}, {@code -infinity} and {@code 24:00:00}.
 */
public enum Kind {
  /** Text, as {@code String}, or {@link Cut} beyond {@link Rows#LIMIT} characters. */
  TEXT,
  /**
   * Whole numbers, as {@code Long}; MariaDB's and MySQL's {@code bigint unsigned} as {@code
   * BigInteger}. Their {@code tinyint(1)}, also written {@code boolean}, holds -128 to 127 and is one
   * of them, and so is {@code year}.
   */
  INTEGER,
  /**
   * Numbers with a fraction: {@code decimal} and {@code numeric} as {@code BigDecimal}, with
   * PostgreSQL's {@code NaN} and infinities as {@code Double}, and floating point as {@code Float}
   * or {@code Double}, as wide as the column's own.
   */
  DECIMAL,
  /** As {@code Boolean}: PostgreSQL's {@code boolean}, and MariaDB's and MySQL's {@code bit(1)}. */
  BOOLEAN,
  /** As {@code LocalDate}, with years before the common era as 0 and below. */
  DATE,
  /** A time of day, as {@code LocalTime}; PostgreSQL's {@code time with time zone} as {@code OffsetTime}. */
  TIME,
  /** A date and time without a zone, as {@code LocalDateTime}; no zone is applied to it. */
  DATE_TIME,
  /**
   * A moment, as {@code OffsetDateTime} in UTC: PostgreSQL's {@code timestamp with time zone}, and
   * MariaDB's and MySQL's {@code timestamp}.
   */
  DATE_TIME_ZONED,
  /** Binary data, as {@link Binary}, of at most {@link Rows#LIMIT} bytes. */
  BINARY,
  /**
   * JSON as the server writes it, which need not be valid: a {@code String}, or {@link Cut} beyond
   * {@link Rows#LIMIT} characters. PostgreSQL's {@code json} and {@code jsonb}, MySQL's {@code
   * json}, and MariaDB's {@code json}, a {@code longtext} that {@code json_valid()} checks, also
   * where the check is written by hand, where its driver says so with the rows.
   */
  JSON,
  /**
   * Anything else, as the server writes it: a {@code String}, or {@link Cut} beyond {@link
   * Rows#LIMIT} characters. Among them UUIDs, arrays, intervals and PostgreSQL's bit strings.
   */
  OTHER
}

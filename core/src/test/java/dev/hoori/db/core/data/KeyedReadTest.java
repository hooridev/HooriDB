package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The order in which a keyed read gives keys, which a merge pairs them by. */
class KeyedReadTest {

  // As UTF-8's bytes go, where String.compareTo puts a character beyond U+FFFF before U+E000.
  @Test
  void textsGoByTheirCodePoints() {
    var texts = new ArrayList<>(List.of("😀", "", "é", "a", "B", "", "ab", "_"));
    texts.sort(KeyedRead::compare);
    assertEquals(List.of("", "B", "_", "a", "ab", "é", "", "😀"), texts);
  }

  @Test
  void numbersGoByTheirValueWhateverTheirType() {
    assertEquals(0, KeyedRead.compare(new BigDecimal("1.0"), new BigDecimal("1.00")));
    assertEquals(0, KeyedRead.compare(1L, new BigDecimal("1.000")));
    assertEquals(0, KeyedRead.compare(0.5f, new BigDecimal("0.5")));
    assertTrue(KeyedRead.compare(new BigInteger("18446744073709551615"), Long.MAX_VALUE) > 0);
    assertTrue(KeyedRead.compare(-1L, 0.5) < 0);
    // NaN above all, as PostgreSQL orders it, and infinities beyond every number.
    assertTrue(KeyedRead.compare(Double.NaN, Double.POSITIVE_INFINITY) > 0);
    assertTrue(KeyedRead.compare(Double.POSITIVE_INFINITY, new BigDecimal("1e300")) > 0);
    assertTrue(KeyedRead.compare(Double.NEGATIVE_INFINITY, Long.MIN_VALUE) < 0);
    assertEquals(0, KeyedRead.compare(Double.NaN, Double.NaN));
  }

  @Test
  void binaryDataGoesByItsBytesUnsigned() {
    var low = new Binary(new byte[] {0x01}, 1);
    var high = new Binary(new byte[] {(byte) 0xff}, 1);
    var longer = new Binary(new byte[] {0x01, 0x00}, 2);
    assertTrue(KeyedRead.compare(low, high) < 0);
    assertTrue(KeyedRead.compare(low, longer) < 0);
  }

  @Test
  void momentsGoByTheirInstant() {
    var at = OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
    assertEquals(0, KeyedRead.compare(at, at.withOffsetSameInstant(ZoneOffset.UTC)));
    assertTrue(KeyedRead.compare(at, at.plusNanos(1)) < 0);
    assertTrue(KeyedRead.compare(false, true) < 0);
  }

  // A value of another type than its kind's, such as a zero date as the server writes it, still
  // has a place, which need not be the server's: a merge finds that out.
  @Test
  void valuesOfOtherTypesHaveAPlaceOfTheirOwn() {
    var date = java.time.LocalDate.of(2024, 1, 1);
    int by = KeyedRead.compare("0000-00-00", date);
    assertEquals(-Integer.signum(by), Integer.signum(KeyedRead.compare(date, "0000-00-00")));
    assertTrue(by != 0);
  }
}

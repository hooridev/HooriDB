package dev.hoori.db.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InsertsTest {

  @Test
  void readsAFilesTextAsTheColumnsKindTakesIt() {
    assertEquals(
        OffsetDateTime.of(2024, 3, 31, 0, 30, 0, 0, ZoneOffset.UTC),
        Inserts.value(Kind.DATE_TIME_ZONED, "2024-03-31T02:30:00+02:00"));
    assertEquals(
        OffsetDateTime.of(2024, 3, 31, 2, 30, 0, 0, ZoneOffset.UTC),
        Inserts.value(Kind.DATE_TIME_ZONED, "2024-03-31 02:30:00Z"));
    assertEquals(
        LocalDateTime.of(2024, 3, 31, 2, 30, 1),
        Inserts.value(Kind.DATE_TIME, "2024-03-31T02:30:01"));
    assertEquals(true, Inserts.value(Kind.BOOLEAN, "TRUE"));
    assertEquals(false, Inserts.value(Kind.BOOLEAN, "0"));
    assertEquals(new Binary(new byte[] {0, -1}, 2), Inserts.value(Kind.BINARY, "\\x00ff"));
    assertEquals(new Binary(new byte[] {16}, 1), Inserts.value(Kind.BINARY, "10"));
    // What is no such value stays text, which the server reads or refuses.
    assertEquals("yesterday", Inserts.value(Kind.DATE_TIME_ZONED, "yesterday"));
    assertEquals("maybe", Inserts.value(Kind.BOOLEAN, "maybe"));
    assertEquals("xyz", Inserts.value(Kind.BINARY, "xyz"));
    assertEquals("42", Inserts.value(Kind.INTEGER, "42"));
    assertNull(Inserts.value(Kind.TEXT, null));
  }

  @Test
  void refusesABatchOfNoRows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Inserts.Options(false, 0, Inserts.Commit.ONCE, Inserts.OnError.STOP));
  }
}

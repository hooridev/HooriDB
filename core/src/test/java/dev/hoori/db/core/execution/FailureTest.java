package dev.hoori.db.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Where a failure is in the statement it came from, from what the server said. */
class FailureTest {

  static Failure at(int position) {
    return new Failure(Failure.Kind.INVALID, "", "", 0, position, "", 0, 0, "", "", "", "");
  }

  static Failure near(int line, String near) {
    return new Failure(Failure.Kind.INVALID, "", "", 1064, 0, "", 0, line, near, "", "", "");
  }

  static Optional<Failure.Place> place(int start, int end) {
    return Optional.of(new Failure.Place(start, end));
  }

  @Test
  void postgresqlsPositionCountsCodePointsFromOne() {
    var sql = "SELECT '😀', nothere FROM t";
    // 'n' is the 13th code point, and the 14th char.
    assertEquals(place(13, 20), at(13).place(sql));
    assertEquals(place(0, 6), at(1).place(sql));
    // A quoted name is taken whole, an operator alone.
    assertEquals(place(7, 11), at(8).place(sql));
    assertEquals(place(11, 12), at(11).place(sql));
    // Beyond the end: the last word.
    assertEquals(place(26, 27), at(99).place(sql));
    assertEquals(place(7, 8), at(11).place("SELECT ( \n"));
  }

  @Test
  void mariadbsLineAndNearTextAreFoundOnOrAfterThatLine() {
    var sql = "SELECT a,\n  b,\nFROM t WHERE b = 'b,'";
    // "FROM" is on line 3; the same text before that line does not count.
    assertEquals(place(15, 19), near(3, "FROM t WHERE b = 'b,'").place(sql));
    var twice = "SELECT x\nFROM x";
    assertEquals(place(14, 15), near(2, "x").place(twice));
    // MariaDB's text after the token it failed on: that token.
    assertEquals(place(15, 16), near(1, " 2)").place("SELECT 1 IN (1,, 2)"));
    assertEquals(place(7, 11), near(1, " 2").place("SELECT from 2"));
    // Not found: the line; at the end: the last word; a line it does not have: nowhere.
    assertEquals(place(9, 13), near(2, "gone").place("SELECT a\n  b,\nc"));
    assertEquals(place(9, 14), near(1, "").place("SELECT a WHERE"));
    assertEquals(Optional.empty(), near(4, "x").place(twice));
  }

  @Test
  void withoutAPlaceThereIsNone() {
    assertEquals(Optional.empty(), at(0).place("SELECT nothere"));
  }

  @Test
  void theNearTextIsItsLastOccurrenceOutsideStringsAndComments() {
    var sql = "SELECT 'WHERE id = 1', id FROM reading WHERE WHERE id = 1";
    int second = sql.lastIndexOf("WHERE");
    assertEquals(place(second, second + 5), near(1, "WHERE id = 1").place(sql));
    // Cut short, and once in a comment.
    var cut = "SELECT a -- WHERE b = 1 AND c\nFROM t WHERE WHERE b = 1 AND c = 2";
    int last = cut.lastIndexOf("WHERE");
    assertEquals(place(last, last + 5), near(1, "WHERE b = 1 AND").place(cut));
    // After the token: not the quote of a string that holds the same text.
    var after = "SELECT ' 2)' AS a FROM t WHERE id IN (1,, 2)";
    int comma = after.indexOf(",, 2") + 1;
    assertEquals(place(comma, comma + 1), near(1, " 2)").place(after));
  }

  @Test
  void aRangeTakesAQuotedNameWholeAndNeverHalfACharacter() {
    var escaped = "SELECT \"a\"\"b\" FROM t";
    assertEquals(place(7, 13), at(8).place(escaped));
    var backtick = "SELECT `a``b` FROM t";
    assertEquals(place(7, 13), near(1, "`a``b` FROM t").place(backtick));
    // 𝒜 is one character, and two chars.
    var astral = "SELECT 𝒜bc FROM t";
    assertEquals(place(7, 11), at(8).place(astral));
    assertEquals(place(12, 16), at(12).place(astral)); // FROM, the 12th character
    assertEquals(place(7, 9), at(8).place("SELECT 😀+ FROM t"));
    var end = "SELECT 1 + 𝒜";
    assertEquals(place(11, 13), at(99).place(end));
    assertEquals(place(11, 13), near(1, "").place(end));
  }
}

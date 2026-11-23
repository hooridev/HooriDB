package dev.hoori.db.app;

import static dev.hoori.db.app.ShellSyntaxTest.CODE;
import static dev.hoori.db.app.ShellSyntaxTest.colored;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The colors of a workspace query's SQL before it has a target, by the standard's rules alone. */
class StandardSyntaxTest {

  private static final StandardSyntax SYNTAX = new StandardSyntax(CODE);

  @Test
  void wordsStringsNumbersAndComments() {
    assertEquals(
        List.of(
            "keyword SELECT",
            "string 'it''s'",
            "operator ,",
            "number 1.5e3",
            "keyword FROM",
            "comment -- the best"),
        colored(SYNTAX, "SELECT 'it''s', 1.5e3 FROM \"order\" -- the best"));
  }

  @Test
  void theStandardsWordsAlsoWhereAServerTakesThemAsNames() {
    assertEquals(
        List.of(
            "keyword CASE",
            "keyword WHEN",
            "keyword THEN",
            "number 1",
            "keyword END",
            "keyword OVER",
            "operator (",
            "operator )"),
        colored(SYNTAX, "CASE WHEN x THEN 1 END OVER ()"));
  }

  @Test
  void dollarQuotesBackticksAndHashesAreNotTheStandards() {
    // As the neutral splitter reads it: two statements, and no string.
    assertEquals(
        List.of("keyword SELECT", "operator ;", "operator #"), colored(SYNTAX, "SELECT $$a;b$$ #"));
  }

  @Test
  void stringsAndBlockCommentsGoOnOverLines() {
    assertEquals(
        List.of("string 'a", "string b'", "comment /* c", "comment d */", "keyword SELECT"),
        colored(SYNTAX, "'a", "b' /* c", "d */ SELECT"));
  }
}

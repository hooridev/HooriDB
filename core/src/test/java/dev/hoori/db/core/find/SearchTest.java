package dev.hoori.db.core.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hoori.db.core.find.Search.Match;
import dev.hoori.db.core.find.Search.Place;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SearchTest {

  @Test
  void containsFindsTheTextAnywhereAndTakesItAsItIs() {
    var search = new Search("a.c", Match.CONTAINS, false);
    assertEquals(Optional.of(new Place(2, 5)), search.in("xxA.Cyy"));
    assertEquals(Optional.empty(), search.in("abc"));
    assertEquals(Optional.empty(), search.in(null));
  }

  @Test
  void caseCountsWhereAskedTo() {
    var search = new Search("Ä", Match.CONTAINS, true);
    assertEquals(Optional.empty(), search.in("ä"));
    assertEquals(Optional.of(new Place(0, 1)), new Search("Ä", Match.CONTAINS, false).in("ä"));
  }

  @Test
  void equalsMatchesTheWholeValue() {
    var search = new Search("customer", Match.EQUALS, false);
    assertEquals(Optional.of(new Place(0, 8)), search.in("Customer"));
    assertEquals(Optional.empty(), search.in("customers"));
  }

  @Test
  void aPatternIsARegularExpression() {
    var search = new Search("cust.m", Match.PATTERN, false);
    assertEquals(Optional.of(new Place(3, 9)), search.in("my_customer"));
    assertEquals(
        "The pattern is not valid: Unclosed character class.", Search.problem("[a", Match.PATTERN));
    assertThrows(IllegalArgumentException.class, () -> new Search("[a", Match.PATTERN, false));
  }

  @Test
  void nothingIsNoSearch() {
    assertEquals("Enter what to find.", Search.problem("", Match.CONTAINS));
    assertNull(Search.problem("x", Match.EQUALS));
  }

  @Test
  void aNumberIsFoundInNumbersUnlessItIsAPattern() {
    assertEquals(
        Optional.of(new BigDecimal("42")), new Search("42", Match.CONTAINS, false).number());
    assertEquals(
        Optional.of(new BigDecimal("-1.5")), new Search(" -1.5 ", Match.EQUALS, false).number());
    assertEquals(Optional.empty(), new Search("42", Match.PATTERN, false).number());
    assertEquals(Optional.empty(), new Search("4 2", Match.EQUALS, false).number());
    assertTrue(new Search("1e3", Match.EQUALS, false).number().isPresent());
  }
}

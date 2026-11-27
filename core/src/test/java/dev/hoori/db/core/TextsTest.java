package dev.hoori.db.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextsTest {

  @Test
  void aCutKeepsACharacterBeyondOneCharWholeOrLeavesItOut() {
    var face = Character.toString(0x1F600);
    assertEquals("abc", Texts.start("abc", 3));
    assertEquals("ab", Texts.start("abc", 2));
    assertEquals("a", Texts.start("a" + face, 2));
    assertEquals("a" + face, Texts.start("a" + face + "b", 3));
  }
}

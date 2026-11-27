package dev.hoori.db.core;

/** What is done alike to texts that are shown or kept cut short. */
public final class Texts {

  private Texts() {}

  /**
   * The text's first chars, at most as many as given, without the first of a surrogate pair alone
   * at the cut, as a character beyond the 16 bits of one char is either kept whole or left out.
   */
  public static String start(String text, int chars) {
    if (text.length() <= chars) {
      return text;
    }
    return text.substring(0, Character.isHighSurrogate(text.charAt(chars - 1)) ? chars - 1 : chars);
  }
}

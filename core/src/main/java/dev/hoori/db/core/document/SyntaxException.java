package dev.hoori.db.core.document;

/** Text that is not what the shell's syntax allows, with where in it the trouble is. */
public final class SyntaxException extends Exception {

  private final int offset;

  public SyntaxException(String message, int offset) {
    super(message);
    this.offset = offset;
  }

  /** The offset of the character, counted from 0, where the text stops making sense. */
  public int offset() {
    return offset;
  }
}

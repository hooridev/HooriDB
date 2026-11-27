package dev.hoori.db.core.sync;

/**
 * How many rows a merge read of each table and how many of each sort it found, so far or in all.
 *
 * @param sourceRows the rows of the source read
 * @param targetRows the rows of the target read
 */
public record Tally(
    long onlyInSource,
    long onlyInTarget,
    long different,
    long equal,
    long sourceRows,
    long targetRows) {

  public static final Tally NONE = new Tally(0, 0, 0, 0, 0, 0);

  /** The rows that differ in any way. */
  public long differences() {
    return onlyInSource + onlyInTarget + different;
  }

  /** How many of the differences the choice takes. */
  public long taken(Choice choice) {
    return (choice.insert() ? onlyInSource : 0)
        + (choice.update() ? different : 0)
        + (choice.delete() ? onlyInTarget : 0);
  }
}

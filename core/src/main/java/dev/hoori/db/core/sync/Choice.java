package dev.hoori.db.core.sync;

/**
 * What of the differences is made equal in the target.
 *
 * @param insert whether a row only in the source is inserted into the target
 * @param update whether a row of the target that differs takes the source's values
 * @param delete whether a row only in the target is deleted
 */
public record Choice(boolean insert, boolean update, boolean delete) {

  /** Every difference: the target's rows become the source's. */
  public static final Choice ALL = new Choice(true, true, true);

  /** Whether it takes any difference at all. */
  public boolean any() {
    return insert || update || delete;
  }

  /** Whether it takes the difference. */
  public boolean takes(Difference difference) {
    return switch (difference) {
      case Difference.OnlyInSource _ -> insert;
      case Difference.OnlyInTarget _ -> delete;
      case Difference.Different _ -> update;
    };
  }
}

package dev.hoori.db.core.execution;

import dev.hoori.db.core.query.Query;
import java.time.Duration;

/**
 * While open, a give-up ends within {@link #BOUND} in place of {@link Execution#ENDING}, for the
 * tests of a give-up that its server or driver never answers, which wait the bound out. Where it
 * lasts makes no difference to what they show: that the give-up ends at its bound all the same.
 */
public final class ShortEnding implements AutoCloseable {

  public static final Duration BOUND = Duration.ofSeconds(1);

  /**
   * How long closing a session whose give-up is cut off takes at most: the bound, the give-up of
   * the connection after it, and the close's own, each within {@link Query#GRACE}, and a second to
   * spare. Below {@link Execution#ENDING}, so that a give-up that waited out that one fails.
   */
  public static final Duration CLOSES =
      BOUND.plus(Query.GRACE.multipliedBy(3)).plus(Duration.ofSeconds(1));

  /** What the failure of a give-up says that the bound cut off, not anything else. */
  public static final String CUT_OFF = "did not answer within " + BOUND.toSeconds() + " seconds";

  private final Duration before = Execution.giveUpBound;

  public ShortEnding() {
    Execution.giveUpBound = BOUND;
  }

  @Override
  public void close() {
    Execution.giveUpBound = before;
  }
}

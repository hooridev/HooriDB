package dev.hoori.db.core.execution;

import java.time.Duration;

/**
 * While open, a give-up ends within {@link #BOUND} in place of {@link Execution#ENDING}, for the
 * tests of a give-up that its server or driver never answers, which wait the bound out. Where it
 * lasts makes no difference to what they show: that the give-up ends at its bound all the same.
 */
public final class ShortEnding implements AutoCloseable {

  public static final Duration BOUND = Duration.ofSeconds(1);

  private final Duration before = Execution.giveUpBound;

  public ShortEnding() {
    Execution.giveUpBound = BOUND;
  }

  @Override
  public void close() {
    Execution.giveUpBound = before;
  }
}

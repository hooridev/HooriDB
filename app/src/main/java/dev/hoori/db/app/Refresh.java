package dev.hoori.db.app;

import com.rethinkj.ui.App;
import java.util.List;

/**
 * When something is read again by itself: at an interval that can be changed, unless paused. Each
 * schedule replaces the one before, whose tick then does nothing, so that a change of the interval
 * or a pause takes effect at once. Only the app's thread uses it.
 */
final class Refresh {

  /** How often it reads again. */
  enum Interval {
    ONE(1),
    TWO(2),
    FIVE(5),
    TEN(10),
    THIRTY(30);

    final int seconds;

    Interval(int seconds) {
      this.seconds = seconds;
    }

    static Interval of(int seconds) {
      for (var each : values()) {
        if (each.seconds == seconds) {
          return each;
        }
      }
      return FIVE;
    }

    String title() {
      return seconds + " s";
    }
  }

  static final List<Interval> INTERVALS = List.of(Interval.values());

  Interval interval;
  private boolean paused;
  private int schedule; // the one in force; a tick of an earlier one does nothing

  Refresh(Interval interval) {
    this.interval = interval;
  }

  boolean paused() {
    return paused;
  }

  /** The tick runs once the interval has passed, unless another schedule or a pause came since. */
  void next(App<?> app, Runnable tick) {
    if (paused) {
      return;
    }
    int at = ++schedule;
    app.after(interval.seconds, () -> {
      if (at == schedule && !paused) {
        tick.run();
      }
    });
  }

  /** No tick comes, until the next schedule. */
  void stop() {
    schedule++;
  }

  void pause(boolean pause) {
    paused = pause;
    stop();
  }
}

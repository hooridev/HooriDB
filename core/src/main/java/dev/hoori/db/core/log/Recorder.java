package dev.hoori.db.core.log;

/**
 * Takes what was sent, on the thread that sent it, which waits for it: it hands the entry over, as
 * into a queue, and returns, never blocking on the disk or a lock held long. What it throws is
 * dropped.
 */
@FunctionalInterface
public interface Recorder {
  void sent(Sent sent);

  /**
   * Whether it takes what is sent now, asked as a call ends: where it does not, no entry is made,
   * and the text is not masked for one.
   */
  default boolean taking() {
    return true;
  }
}

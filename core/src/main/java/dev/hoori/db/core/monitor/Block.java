package dev.hoori.db.core.monitor;

import java.util.Objects;

/**
 * A session that waits for a lock that another holds.
 *
 * @param waiting the id of the session that waits, as {@link ServerSession#id}
 * @param blocking the id of the session that holds what it waits for
 * @param object what the lock is on, as a table, where the server tells
 * @param mode the mode of the lock it waits for, in the server's words, where it tells
 */
public record Block(String waiting, String blocking, String object, String mode) {

  public Block {
    Objects.requireNonNull(waiting, "waiting");
    Objects.requireNonNull(blocking, "blocking");
    object = object == null ? "" : object;
    mode = mode == null ? "" : mode;
  }
}

package dev.hoori.db.core.monitor;

import dev.hoori.db.core.query.QueryException;
import java.util.List;

/**
 * Reads what a server does now, on a connection of the monitor's own, which nothing else uses
 * meanwhile. Each call blocks, without a time limit; an interrupt of the calling thread cancels it,
 * as {@link dev.hoori.db.core.query.Query} says, and leaves the thread interrupted.
 */
public interface Monitor {

  /**
   * The sessions of the server's clients, the monitor's own among them, in the order of their ids,
   * as far as the user may see them.
   */
  List<ServerSession> sessions() throws QueryException;
}

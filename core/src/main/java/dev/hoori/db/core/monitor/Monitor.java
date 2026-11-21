package dev.hoori.db.core.monitor;

import dev.hoori.db.core.query.QueryException;
import java.util.List;
import java.util.Optional;

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

  /**
   * Cancels what the session runs, by its {@link ServerSession#operation}; the session stays.
   *
   * @return whether the server found the session, false where it had ended meanwhile; a server
   *     that does not tell says true
   * @throws IllegalArgumentException for a session that is not {@link ServerSession#cancellable}
   */
  boolean cancel(ServerSession session) throws QueryException;

  /**
   * Ends the session: the server closes its connection, and rolls back what it did not commit.
   *
   * @return whether the server found the session, false where it had ended meanwhile
   * @throws IllegalArgumentException for a session that does not {@link ServerSession#ends}
   */
  boolean end(ServerSession session) throws QueryException;

  /** The server's variables and settings, by name; the values of credentials masked. */
  List<ServerVariable> variables() throws QueryException;

  /** The server's status counters, in the order the server gives them. */
  List<Counter> status() throws QueryException;

  /**
   * The sessions that wait for a lock another holds, by the one that waits, as far as the user
   * may see them; empty where the server does not tell who holds a lock.
   */
  Optional<List<Block>> locks() throws QueryException;
}

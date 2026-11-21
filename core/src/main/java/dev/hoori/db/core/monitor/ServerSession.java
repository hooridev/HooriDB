package dev.hoori.db.core.monitor;

import java.time.Duration;
import java.util.Objects;

/**
 * A session of a server as its monitor shows it: a client's connection, what it runs and what it
 * waits for. What the server does not tell is empty, a duration it does not tell null; nothing is
 * made up in its place.
 *
 * @param id the server's own id of the session, as its users know it: PostgreSQL's process id,
 *     MariaDB's and MySQL's thread id, MongoDB's connection id
 * @param operation the id by which what it runs is cancelled; empty while it runs nothing that can
 *     be
 * @param own whether it is the monitor's own session, which reads the list
 * @param endable whether the server can end it from another session
 * @param user the user it is logged in as
 * @param client the program and the address it connects from, as far as the server knows them
 * @param database the database it is on
 * @param state what it does, in the server's words
 * @param statement what it runs, or ran last where the server keeps that, with the values of
 *     credentials masked
 * @param elapsed how long it has been in its state, as the server measured it as it was read; null
 *     where the server does not tell
 * @param waiting what it waits for, in the server's words; empty where it waits for nothing
 */
public record ServerSession(
    String id,
    String operation,
    boolean own,
    boolean endable,
    String user,
    String client,
    String database,
    String state,
    String statement,
    Duration elapsed,
    String waiting) {

  public ServerSession {
    Objects.requireNonNull(id, "id");
    operation = text(operation);
    user = text(user);
    client = text(client);
    database = text(database);
    state = text(state);
    statement = text(statement);
    waiting = text(waiting);
  }

  /** Whether what it runs can be cancelled from the monitor: not the monitor's own. */
  public boolean cancellable() {
    return !own && !operation.isEmpty();
  }

  /** Whether it can be ended from the monitor: not the monitor's own. */
  public boolean ends() {
    return !own && endable;
  }

  /** The same session with this statement. */
  public ServerSession withStatement(String statement) {
    return new ServerSession(
        id, operation, own, endable, user, client, database, state, statement, elapsed, waiting);
  }

  /**
   * A client as a session shows it: the program, and in parentheses the address it connects from;
   * either alone where the other is not known.
   */
  public static String client(String program, String address) {
    var p = text(program);
    var a = text(address);
    if (p.isEmpty() || a.isEmpty()) {
      return p + a;
    }
    return p + " (" + a + ")";
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}

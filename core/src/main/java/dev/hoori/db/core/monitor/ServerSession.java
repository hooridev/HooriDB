package dev.hoori.db.core.monitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A session of a server as its monitor shows it: a client's connection, what it runs and what it
 * waits for. What the server does not tell is empty, a duration it does not tell null; nothing is
 * made up in its place.
 *
 * @param id the server's own id of the session, as its users know it: PostgreSQL's process id,
 *     MariaDB's and MySQL's thread id, MongoDB's connection id
 * @param operation what names what it runs, by which it is cancelled: PostgreSQL's start of the
 *     statement, which names it together with the id and {@code started}, MariaDB's and MySQL's
 *     thread id, MongoDB's operation id; empty while it runs nothing that can be cancelled
 * @param started when the session started, in the server's words, by which an action checks that
 *     the id still names this session, as PostgreSQL reuses its process ids; empty where the
 *     server does not tell or never reuses an id
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
 * @param digest a digest of the statement as the server told it, before any masking, so that two
 *     statements that read alike once masked are still told apart; it keeps no credential
 */
public record ServerSession(
    String id,
    String operation,
    String started,
    boolean own,
    boolean endable,
    String user,
    String client,
    String database,
    String state,
    String statement,
    Duration elapsed,
    String waiting,
    String digest) {

  /** A session whose digest is that of its statement, as the server told it. */
  public ServerSession(
      String id,
      String operation,
      String started,
      boolean own,
      boolean endable,
      String user,
      String client,
      String database,
      String state,
      String statement,
      Duration elapsed,
      String waiting) {
    this(
        id,
        operation,
        started,
        own,
        endable,
        user,
        client,
        database,
        state,
        statement,
        elapsed,
        waiting,
        digest(statement));
  }

  public ServerSession {
    Objects.requireNonNull(id, "id");
    operation = text(operation);
    started = text(started);
    user = text(user);
    client = text(client);
    database = text(database);
    state = text(state);
    statement = text(statement);
    waiting = text(waiting);
    digest = text(digest);
  }

  /** Whether what it runs can be cancelled from the monitor: not the monitor's own. */
  public boolean cancellable() {
    return !own && !operation.isEmpty();
  }

  /** Whether it can be ended from the monitor: not the monitor's own. */
  public boolean ends() {
    return !own && endable;
  }

  /** The same session with this statement, as masked, and the digest of the one it had. */
  public ServerSession withStatement(String statement) {
    return new ServerSession(
        id, operation, started, own, endable, user, client, database, state, statement, elapsed,
        waiting, digest);
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

  private static String digest(String statement) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256")
              .digest(text(statement).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // every Java platform has SHA-256
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}

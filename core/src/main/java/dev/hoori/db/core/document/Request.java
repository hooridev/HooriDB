package dev.hoori.db.core.document;

import java.util.Objects;

/**
 * A statement of the shell as what is sent to the server: a command, run on a database, and how
 * its reply is shown. Every statement but {@code use} is one command, as the server's own commands
 * are what the shell's methods send: {@code db.orders.find({ total: { $gt: 5 } })} is {@code {
 * find: 'orders', filter: { total: { $gt: 5 } } }}.
 *
 * @param database the database it runs on, or null for the console's own
 * @param command the command, whose first field names it
 * @param shape how its reply is shown
 * @param use the database that {@code use} makes the console's own, or null for any other
 */
public record Request(String database, Document command, Shape shape, String use) {

  /** How a command's reply is shown. */
  public enum Shape {
    /** The documents of a cursor, read in portions as they are wanted: find and aggregate. */
    CURSOR,
    /** The first document of a cursor, or none: findOne. */
    ONE,
    /** The number in the field {@code n} of the cursor's only document, or 0 without one. */
    COUNT,
    /** The number in the reply's field {@code n}, as the count command gives it. */
    ESTIMATE,
    /** The values in the reply's field {@code values}, as distinct gives them. */
    VALUES,
    /** What a write changed: the reply's {@code n}, {@code nModified} and {@code upserted}. */
    WRITE,
    /** The databases of listDatabases, one a row. */
    DATABASES,
    /** The reply itself, as one document. */
    REPLY,
    /** Nothing is sent: {@code use} switches the database. */
    USE,
    /** Nothing is sent: the name of the database it runs on, as {@code db.getName()} gives it. */
    NAME
  }

  public Request {
    Objects.requireNonNull(shape, "shape");
    if (shape == Shape.USE ? use == null || command != null : use != null) {
      throw new IllegalArgumentException("A use names a database and sends nothing");
    }
    if ((shape == Shape.NAME) != (shape != Shape.USE && command == null)) {
      throw new IllegalArgumentException("Every request but a use and a name sends a command");
    }
  }

  /** The name of the console's database, sent nowhere. */
  public static Request name() {
    return new Request(null, null, Shape.NAME, null);
  }

  /** A command on the console's database, shown as its shape says. */
  public static Request of(Document command, Shape shape) {
    return new Request(null, command, shape, null);
  }

  /** A switch to the database. */
  public static Request use(String database) {
    return new Request(null, null, Shape.USE, database);
  }

  /** The same, run on the database. */
  public Request on(String database) {
    return new Request(database, command, shape, use);
  }

  /** Whether it only reads: what a connection that is read-only lets through. */
  public boolean reads() {
    return command == null || Commands.reads(command);
  }
}

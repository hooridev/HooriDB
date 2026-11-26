package dev.hoori.db.core.query;

import java.io.Serial;

/** Why a query failed. The message is the failure and the driver's or the server's own words. */
public final class QueryException extends Exception {
  @Serial
  private static final long serialVersionUID = 1L;

  public enum Failure {
    /** The connection is closed or broken; going on needs a new one. */
    CONNECTION_LOST,
    /** A table, view, column or schema that the query names does not exist, or no longer. */
    NOT_FOUND,
    /** The server refused the user what the query asks for. */
    ACCESS_DENIED,
    /**
     * The thread was interrupted, or the query was cancelled on the server; the connection is
     * usable.
     */
    CANCELLED,
    /**
     * The thread was interrupted and the query could not be stopped for certain: the connection is
     * closed, and going on needs a new one.
     */
    ABORTED,
    /**
     * The table's columns are no longer those the read was given: one was added, dropped, renamed,
     * moved or given another type. Read the columns again, then the rows.
     */
    COLUMNS_CHANGED,
    /**
     * A clause of a filter was refused before anything was sent: it held more than one statement,
     * left a string or comment open, had unbalanced parentheses, or may turn a read-only session
     * back to read-write.
     */
    CLAUSE_REFUSED,
    /** Anything else; the detail says what. */
    OTHER
  }

  private final Failure failure;
  private final String detail;

  QueryException(Failure failure, String detail, Throwable cause) {
    super(detail.isEmpty() ? failure.toString() : failure + ": " + detail, cause);
    this.failure = failure;
    this.detail = detail;
  }

  /**
   * A failure as a stand-in for a server reports it, such as a made-up server in a test. The
   * detail must not hold a password.
   */
  public static QueryException of(Failure failure, String detail) {
    return new QueryException(failure, detail, null);
  }

  public Failure failure() {
    return failure;
  }

  /** The driver's or the server's own message; empty when there is none. */
  public String detail() {
    return detail;
  }
}

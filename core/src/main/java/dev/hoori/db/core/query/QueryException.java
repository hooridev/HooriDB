package dev.hoori.db.core.query;

/** Why a query failed. The message is the failure and the driver's or the server's own words. */
public final class QueryException extends Exception {

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

  public Failure failure() {
    return failure;
  }

  /** The driver's or the server's own message; empty when there is none. */
  public String detail() {
    return detail;
  }
}

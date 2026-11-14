package dev.hoori.db.core.catalog;

/**
 * Why the catalogue could not be read. The message is the failure and the driver's or the server's
 * own words, or the name of what is missing.
 */
public final class CatalogException extends Exception {

  public enum Failure {
    /** The connection is closed or broken; reading on needs a new one. */
    CONNECTION_LOST,
    /** What was asked about does not exist, or no longer, or the user may not see it. */
    NOT_FOUND,
    /** The server refused the user to read its catalogue. */
    ACCESS_DENIED,
    /** On PostgreSQL, the connection is to another database than the one asked about. */
    WRONG_DATABASE,
    /** The thread was interrupted, or the query was cancelled on the server. */
    CANCELLED,
    /** Anything else; the detail says what. */
    OTHER
  }

  private final Failure failure;
  private final String detail;

  CatalogException(Failure failure, String detail, Throwable cause) {
    super(detail.isEmpty() ? failure.toString() : failure + ": " + detail, cause);
    this.failure = failure;
    this.detail = detail;
  }

  public Failure failure() {
    return failure;
  }

  /** The driver's or the server's own message, or what is missing; empty when there is none. */
  public String detail() {
    return detail;
  }
}

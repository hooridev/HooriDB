package dev.hoori.db.core.connect;

import dev.hoori.db.core.connection.ConnectionProfile.Problem;
import java.util.Set;

/**
 * Why a connection could not be opened. The message is the failure and the driver's or the
 * server's own words, which never hold the password: the URL and the properties that a driver
 * might quote hold none, and where a server quotes it, nothing of what the driver said is kept.
 */
public final class ConnectionException extends Exception {

  /**
   * The detail in place of the driver's message when any message of the driver or the server, down
   * to the causes, contains the password; the exception has no cause then. A short password can
   * withhold a harmless message that merely contains it.
   */
  public static final String WITHHELD = "The message is not shown, as it contains the password.";

  public enum Failure {
    /** The profile cannot connect as it is; {@link #problems} says why. */
    UNFIT,
    /** Nothing answered at the host and port: the host is unknown or unreachable, or refused. */
    UNREACHABLE,
    /** The time limit passed before the server let the login finish. */
    TIMED_OUT,
    /** The server refused the user or the password. */
    LOGIN_REFUSED,
    /** The server has no database of the profile's name, or none that the user may open. */
    NO_SUCH_DATABASE,
    /** The profile asks for TLS, and the server offers none. */
    TLS_UNAVAILABLE,
    /** The server's certificate is not trusted, or not for the host, under the verified level. */
    CERTIFICATE_REJECTED,
    /** The thread was interrupted. */
    CANCELLED,
    /** Anything else; the detail says what. */
    OTHER
  }

  private final Failure failure;
  private final String detail;
  private final transient Set<Problem> problems;

  ConnectionException(Failure failure, String detail, Throwable cause) {
    super(detail.isEmpty() ? failure.toString() : failure + ": " + detail, cause);
    this.failure = failure;
    this.detail = detail;
    this.problems = Set.of();
  }

  ConnectionException(Set<Problem> problems) {
    super(Failure.UNFIT + ": " + problems);
    this.failure = Failure.UNFIT;
    this.detail = "";
    this.problems = Set.copyOf(problems);
  }

  /**
   * A failure as a stand-in for a server reports it, such as a made-up server in a test. The
   * detail must not hold a password.
   */
  public static ConnectionException of(Failure failure, String detail) {
    return new ConnectionException(failure, detail, null);
  }

  /** {@link Failure#UNFIT}, for these problems. */
  public static ConnectionException of(Set<Problem> problems) {
    return new ConnectionException(problems);
  }

  public Failure failure() {
    return failure;
  }

  /**
   * The driver's or the server's own message, empty when there is none, or {@link #WITHHELD}.
   */
  public String detail() {
    return detail;
  }

  /** What keeps the profile from connecting, for {@link Failure#UNFIT}; empty otherwise. */
  public Set<Problem> problems() {
    return problems;
  }
}

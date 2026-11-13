package dev.hoori.db.core.driver;

/** Why a driver could not be listed, downloaded or loaded. */
public final class DriverException extends Exception {

  public enum Failure {
    /** The version is not in the storage folder. */
    NOT_DOWNLOADED,
    /**
     * The repository could not be reached, timed out or answered with an error. From {@link
     * DriverStore#recommended}, it also means that nothing is downloaded to fall back on.
     */
    NO_NETWORK,
    /** The repository has no such artifact or version, or no release that is old enough. */
    NOT_FOUND,
    /** A download, or a stored file, does not match its checksum. */
    CHECKSUM_MISMATCH,
    /** A download is larger than a driver may be. */
    TOO_LARGE,
    /** The repository's metadata or checksum file cannot be read. */
    INVALID_RESPONSE,
    /** The thread was interrupted. */
    CANCELLED,
    /** The storage folder could not be read or written. */
    STORAGE,
    /** The file holds no class of the driver's name that is a {@link java.sql.Driver}. */
    NOT_A_DRIVER
  }

  private final Failure failure;

  public DriverException(Failure failure, String message, Throwable cause) {
    super(failure + ": " + message, cause);
    this.failure = failure;
  }

  public DriverException(Failure failure, String message) {
    this(failure, message, null);
  }

  public Failure failure() {
    return failure;
  }
}

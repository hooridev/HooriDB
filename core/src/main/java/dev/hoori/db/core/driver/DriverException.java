package dev.hoori.db.core.driver;

import java.io.Serial;

/** Why a driver could not be listed, downloaded or loaded. */
public final class DriverException extends Exception {
  @Serial
  private static final long serialVersionUID = 1L;

  public enum Failure {
    /** The version is not in the storage folder. */
    NOT_DOWNLOADED,
    /**
     * The repository could not be reached: no network, no such host, or no connection. From {@link
     * DriverStore#recommended}, it also means that nothing is downloaded to fall back on.
     */
    NO_NETWORK,
    /**
     * The repository was reached but answered with an error or stopped answering. From {@link
     * DriverStore#recommended}, it also means that nothing is downloaded to fall back on.
     */
    UNAVAILABLE,
    /** The repository has no such artifact or version. */
    NOT_FOUND,
    /** None of the newest releases is known to have been published the waiting period ago. */
    NO_RECOMMENDATION,
    /** The repository publishes no checksum beside the file, so a download cannot be verified. */
    NO_CHECKSUM,
    /** A download, or a stored file, does not match its checksum. */
    CHECKSUM_MISMATCH,
    /** A download is larger than a driver may be. */
    TOO_LARGE,
    /** The JAR's manifest names further files to load, which nothing has checked. */
    CLASS_PATH_IN_MANIFEST,
    /** The repository's response, metadata or checksum file cannot be read. */
    INVALID_RESPONSE,
    /** The thread was interrupted. */
    CANCELLED,
    /** The storage folder could not be read or written. */
    STORAGE,
    /** The disk of the storage folder has no room for the download. */
    NO_SPACE,
    /** The file holds no class of the driver's name that is a {@link java.sql.Driver}. */
    NOT_A_DRIVER,
    /** The version is older than the oldest that HooriDB works with, {@link Driver#oldest}. */
    TOO_OLD
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

package dev.hoori.db.core.secret;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Secrets kept under keys in the operating system's keyring; {@link Keyring#find()} gives the one
 * of the platform. Every call may block, on a prompt to unlock the keyring too, so none of them
 * belongs on the app's thread. The caller owns the arrays it passes in and gets back, and should
 * fill them with zeros when it is done with them. When the keyring stays locked, a read or a
 * delete of a secret that is there throws an {@link IOException} that says so, and never answers as
 * if the secret were absent.
 */
public interface SecretStore {

  /** Stores the secret under the key, in place of one that was there. */
  void store(UUID key, char[] secret) throws IOException;

  /** The secret under the key, or empty when there is none. */
  Optional<char[]> read(UUID key) throws IOException;

  /** Deletes the secret under the key, if there is one. */
  void delete(UUID key) throws IOException;
}

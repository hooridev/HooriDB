package dev.hoori.db.core.driver;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Optional;

/**
 * A Maven repository, from which files are read by their path under it, such as {@code
 * org/postgresql/postgresql/maven-metadata.xml}. {@link MavenRepository} is the one over HTTPS;
 * tests read a folder instead. Both throw a {@link NoSuchFileException} for a file that is not
 * there, a {@link Refused} when the repository answers with another error, and any other {@link
 * IOException} when it cannot be reached.
 */
public interface Repository {

  /** A file being read, and its length in bytes, or -1 when that is not known. */
  record Response(InputStream body, long length) implements Closeable {
    @Override
    public void close() throws IOException {
      body.close();
    }
  }

  /** The repository was reached but answered with an error, such as a server error. */
  final class Refused extends IOException {
    public Refused(String message) {
      super(message);
    }
  }

  Response open(String path) throws IOException, InterruptedException;

  /** When the file was put into the repository, or empty when the repository does not say. */
  Optional<Instant> published(String path) throws IOException, InterruptedException;
}

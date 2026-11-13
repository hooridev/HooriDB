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
 * there, and any other {@link IOException} when the repository cannot be reached or fails.
 */
public interface Repository {

  /** A file being read, and its length in bytes, or -1 when that is not known. */
  record Response(InputStream body, long length) implements Closeable {
    @Override
    public void close() throws IOException {
      body.close();
    }
  }

  Response open(String path) throws IOException, InterruptedException;

  /** When the file was put into the repository, or empty when the repository does not say. */
  Optional<Instant> published(String path) throws IOException, InterruptedException;
}
